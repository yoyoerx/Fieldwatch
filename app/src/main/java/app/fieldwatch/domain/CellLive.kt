package app.fieldwatch.domain

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Text for the cell card at the bottom of Live: the cell this phone is connected to, and the
 * cells it detected nearby. Built from the cached reading; nothing here asks the modem for more.
 * Privacy mode masks the area code and the cell id, as the sit report does.
 */
object CellLive {
    /** Neighbors shown before "n more". */
    const val NEIGHBOR_CAP = 8

    data class Serving(
        val title: String,
        val lines: List<String>,
        /** Reasons when the score has any. */
        val reasons: List<String> = emptyList(),
        val alarming: Boolean = false,
    )

    data class Neighbor(val text: String)

    data class View(
        val serving: Serving?,
        val neighbors: List<Neighbor>,
        /** Neighbors left off after [NEIGHBOR_CAP]. */
        val more: Int,
        /** Shown when there is no serving cell. */
        val note: String?,
    )

    fun view(reading: CellReading, verdict: CellVerdict, watchOn: Boolean, demo: Boolean): View {
        val neighbors = neighborRows(reading.neighbors)
        val shown = neighbors.take(NEIGHBOR_CAP)
        val serving = serving(reading, verdict, watchOn, demo)
        val note = when {
            serving != null -> null
            !reading.registered -> LanguageText.text("live.cell.none")
            else -> null
        }
        return View(serving, shown, neighbors.size - shown.size, note)
    }

    private fun serving(reading: CellReading, verdict: CellVerdict, watchOn: Boolean, demo: Boolean): Serving? {
        if (!reading.registered) return null
        val gen = reading.identity?.gen ?: reading.generation
        val id = reading.identity
        val title = buildString {
            append(LanguageText.text("live.cell.connected"))
            if (gen != null) append(" · ").append(gen.band())
            val operator = id?.let { CellPlmn.operator(it.plmn) }
            if (!operator.isNullOrBlank()) append(" · ").append(operator)
        }
        val lines = ArrayList<String>(5)
        if (id != null) {
            val country = CellPlmn.country(id.plmn)
            lines += label("cell.network", if (country.isNullOrBlank()) id.plmn else "${id.plmn} · $country")
        }
        val band = if (gen != null && reading.channel != null) CellBand.label(gen, reading.channel) else null
        if (band != null) {
            lines += label("cell.band", band)
        } else if (reading.channel != null) {
            lines += label("cell.channel", reading.channel.toString())
        }
        if (id != null) {
            lines += listOfNotNull(
                label("cell.area", masked(id.area.toString(), demo)),
                label("cell.cell", masked(id.cell.toString(), demo)),
                reading.pci?.let { label("cell.pci", it.toString()) },
            ).joinToString(" · ")
        } else if (reading.emptyFields.isNotEmpty()) {
            val names = reading.emptyFields.joinToString(", ") { field ->
                LanguageText.text(
                    when (field) {
                        CellField.NETWORK -> "cell.network"
                        CellField.AREA -> "cell.area"
                        CellField.CELL -> "cell.cell"
                    },
                )
            }
            lines += LanguageText.text("live.cell.empty", mapOf("fields" to names))
        }
        val radio = listOfNotNull(
            reading.signal?.let { label("cell.signal", "$it dBm") },
            reading.timingAdvance?.let { ta ->
                val km = gen?.let { towerKm(it, ta) }
                val dist = km?.let { " (" + LanguageText.text("live.cell.km", mapOf("km" to formatKm(it))) + ")" }.orEmpty()
                label("cell.timing", "$ta$dist")
            },
        )
        if (radio.isNotEmpty()) lines += radio.joinToString(" · ")
        val reasons = if (watchOn) CellCopy.flagTexts(verdict.flags.map { it.name }) else emptyList()
        lines += if (watchOn) {
            label("cell.score", verdict.score.toString())
        } else {
            LanguageText.text("live.cell.watch.off")
        }
        return Serving(title, lines, reasons, watchOn && verdict.alarming)
    }

    /** Strongest first. Duplicate entries for one cell, which some modems report, are folded. */
    private fun neighborRows(neighbors: List<CellNeighbor>): List<Neighbor> =
        neighbors
            .distinctBy { Triple(it.gen, it.pci, it.channel) }
            .sortedWith(compareByDescending<CellNeighbor> { it.signal ?: Int.MIN_VALUE })
            .map { n ->
                val band = n.channel?.let { CellBand.label(n.gen, it) }
                val parts = listOfNotNull(
                    n.gen.band(),
                    n.pci?.let { label("cell.pci", it.toString()) },
                    band ?: n.channel?.let { label("cell.channel", it.toString()) },
                    n.signal?.let { "$it dBm" },
                )
                Neighbor(parts.joinToString(" · "))
            }

    /**
     * One-way distance a timing advance stands for. 2G steps are 553 m, 4G steps 78 m, and the
     * 5G value is already microseconds of round trip. 3G reports none.
     */
    fun towerKm(gen: CellGen, timingAdvance: Int): Double? {
        if (timingAdvance < 0) return null
        val meters = when (gen) {
            CellGen.GSM -> timingAdvance * 553.5
            CellGen.LTE -> timingAdvance * 78.12
            CellGen.NR -> timingAdvance * 149.9
            CellGen.WCDMA, CellGen.TDSCDMA -> return null
        }
        return meters / 1000.0
    }

    private fun formatKm(km: Double): String =
        if (km < 10) String.format(Locale.US, "%.1f", km) else km.roundToInt().toString()

    private fun label(key: String, value: String): String = "${LanguageText.text(key)} $value"

    private fun masked(value: String, demo: Boolean): String = if (demo) "masked" else value
}
