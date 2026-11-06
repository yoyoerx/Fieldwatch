package app.fieldwatch.domain

import kotlinx.serialization.Serializable

/**
 * Signs seen with fake base stations, scored on every poll and at any place.
 * One strong sign or a few weak ones open a SUSPECT episode. Any single sign
 * can have an ordinary cause. This names the signs, not a cause.
 */
enum class CellFlag(val weight: Int) {
    /** Test or private network code. Stock SDR base station builds default to these. */
    TEST_NETWORK(100),
    /** Reserved or out-of-range area code, cell id, or PCI. */
    BAD_IDENTITY(50),
    /** A cell id seen before now carries a different PCI. */
    PCI_CHANGED(45),
    /** A cell id seen before now sits on a different channel. */
    CHANNEL_CHANGED(30),
    /** A cell id seen before now carries a different area code. */
    AREA_CHANGED(25),
    /** Three or more area codes on one network in a few minutes while still. */
    AREA_CHURN(40),
    /** Served on 2G or 3G while a usable 4G or 5G cell is heard. */
    FORCED_DOWN(40),
    /** 2G does not authenticate the network to the phone. */
    PLAIN_2G(20),
    /** Network code not in the operator table. */
    UNLISTED_NETWORK(20),
    /** Channel number outside every known band. */
    ODD_CHANNEL(15),
    /** A cell never seen before, and much louder than usual. */
    LOUD_NEW(25),
    /** A cell never seen before, with a zero timing advance. */
    CLOSE_NEW(10),
    /** No neighbor cells on 4G or 5G, on a phone that usually reports them. */
    LONE(15),
    ;

    fun textKey(): String = "cell.flag." + name.lowercase().replace('_', '.')
}

@Serializable
data class CellNeighbor(
    val gen: CellGen,
    val pci: Int? = null,
    val channel: Int? = null,
    val signal: Int? = null,
)

/** One tower this phone has camped on, anywhere. Mismatches do not overwrite it; Learn does. */
@Serializable
data class CellSeen(
    val tower: String,
    val area: Int,
    val pci: Int? = null,
    val channel: Int? = null,
    val firstAt: Long = 0L,
    val lastAt: Long = 0L,
)

@Serializable
data class CellAreaHit(
    val plmn: String,
    val gen: CellGen,
    val area: Int,
    val at: Long,
)

data class CellVerdict(val flags: List<CellFlag> = emptyList(), val score: Int = 0) {
    val alarming: Boolean get() = score >= CellSuspect.ALERT
}

object CellSuspect {
    const val ALERT = 60
    const val SEEN_CAP = 1500
    const val CHURN_MS = 10 * 60_000L
    const val CHURN_AREAS = 3
    const val NEIGHBOR_FLOOR_DBM = -110
    private const val HIT_CAP = 64

    private val testMcc = setOf("000", "001", "999")
    private val testPlmn = setOf("208-92", "208-93", "208-95", "208-99")

    fun judge(book: CellBook, reading: CellReading, still: Boolean, now: Long): CellVerdict {
        val id = reading.identity ?: return CellVerdict()
        val flags = ArrayList<CellFlag>()
        val test = testNetwork(id.plmn)
        if (test) flags += CellFlag.TEST_NETWORK
        if (badIdentity(id, reading.pci)) flags += CellFlag.BAD_IDENTITY
        if (!test && CellPlmn.operator(id.plmn) == null) flags += CellFlag.UNLISTED_NETWORK
        val channel = reading.channel
        if (channel != null && CellBand.label(id.gen, channel) == null) flags += CellFlag.ODD_CHANNEL

        val seen = book.seen.find { it.tower == id.towerKey() }
        if (seen != null) {
            if (seen.pci != null && reading.pci != null && seen.pci != reading.pci) flags += CellFlag.PCI_CHANGED
            if (seen.channel != null && channel != null && seen.channel != channel) flags += CellFlag.CHANNEL_CHANGED
            if (seen.area != id.area) flags += CellFlag.AREA_CHANGED
        } else {
            if (loud(id.gen, reading.signal)) flags += CellFlag.LOUD_NEW
            if (reading.timingAdvance == 0 && (id.gen == CellGen.LTE || id.gen == CellGen.GSM)) {
                flags += CellFlag.CLOSE_NEW
            }
        }

        if (id.gen == CellGen.GSM) flags += CellFlag.PLAIN_2G
        if (id.gen.low() && reading.neighbors.any { it.gen.high() && (it.signal ?: Int.MIN_VALUE) >= NEIGHBOR_FLOOR_DBM }) {
            flags += CellFlag.FORCED_DOWN
        }
        if (id.gen.high() && reading.neighborCount == 0 && book.sawNeighbors) flags += CellFlag.LONE
        if (still && churning(book, id, now)) flags += CellFlag.AREA_CHURN

        return CellVerdict(flags, flags.sumOf { it.weight })
    }

    /** Add this reading to the ledger. A mismatch keeps the stored value. Area hits count only while still. */
    fun remember(book: CellBook, reading: CellReading, still: Boolean, now: Long): CellBook {
        val id = reading.identity ?: return book
        val tower = id.towerKey()
        val seen = book.seen.toMutableList()
        val at = seen.indexOfFirst { it.tower == tower }
        if (at >= 0) {
            val old = seen[at]
            seen[at] = old.copy(
                pci = old.pci ?: reading.pci,
                channel = old.channel ?: reading.channel,
                lastAt = now,
            )
        } else {
            if (seen.size >= SEEN_CAP) seen.remove(seen.minBy { it.lastAt })
            seen += CellSeen(tower, id.area, reading.pci, reading.channel, now, now)
        }
        val recent = if (still) book.areaHits.filter { now - it.at < CHURN_MS } else emptyList()
        val last = recent.lastOrNull { it.plmn == id.plmn && it.gen == id.gen }
        val hits = when {
            !still -> recent
            last?.area == id.area -> recent
            else -> (recent + CellAreaHit(id.plmn, id.gen, id.area, now)).takeLast(HIT_CAP)
        }
        return book.copy(
            seen = seen,
            areaHits = hits,
            sawNeighbors = book.sawNeighbors || reading.neighborCount > 0,
        )
    }

    /** Learn this cell: the ledger takes the values this episode saw. */
    fun accept(book: CellBook, episode: CellEpisode, now: Long): CellBook {
        val tower = episode.identity.towerKey()
        val seen = book.seen.filter { it.tower != tower } + CellSeen(
            tower = tower,
            area = episode.identity.area,
            pci = episode.pci,
            channel = episode.channel,
            firstAt = book.seen.find { it.tower == tower }?.firstAt ?: now,
            lastAt = now,
        )
        return book.copy(seen = seen, areaHits = emptyList())
    }

    fun testNetwork(plmn: String): Boolean {
        val mcc = plmn.substringBefore('-', "")
        return mcc in testMcc || plmn in testPlmn
    }

    fun badIdentity(id: CellIdentity, pci: Int?): Boolean = when (id.gen) {
        CellGen.GSM ->
            id.area == 0 || id.area == 0xFFFE || id.cell == 0L || id.cell == 0xFFFFL || (pci != null && pci !in 0..63)
        CellGen.WCDMA ->
            id.area == 0 || id.area == 0xFFFE || id.cell == 0L || id.cell > 0xFFFFFFFL || (pci != null && pci !in 0..511)
        CellGen.TDSCDMA ->
            id.area == 0 || id.area == 0xFFFE || id.cell == 0L || id.cell > 0xFFFFFFFL || (pci != null && pci !in 0..127)
        CellGen.LTE ->
            id.area == 0 || id.area == 0xFFFE || id.cell == 0L || id.cell > 0xFFFFFFFL || (pci != null && pci !in 0..503)
        CellGen.NR ->
            id.area == 0 || id.area == 0xFFFFFE || id.cell == 0L || id.cell > 0xFFFFFFFFFL || (pci != null && pci !in 0..1007)
    }

    private fun loud(gen: CellGen, signal: Int?): Boolean {
        val dbm = signal ?: return false
        // RSRP for 4G and 5G, RSSI for 2G and 3G.
        return if (gen.high()) dbm >= -65 else dbm >= -55
    }

    private fun churning(book: CellBook, id: CellIdentity, now: Long): Boolean {
        val areas = book.areaHits
            .filter { now - it.at < CHURN_MS && it.plmn == id.plmn && it.gen == id.gen }
            .map { it.area }
            .toMutableSet()
        areas += id.area
        return areas.size >= CHURN_AREAS
    }
}
