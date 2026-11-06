package app.fieldwatch.domain

/**
 * Experimental. Follows one Bluetooth radio across its address changes.
 *
 * A phone or a pair of earbuds swaps its random address about every 15 minutes. At the swap the
 * old address goes quiet and a new one starts within seconds, with the same kind of advertisement
 * and about the same loudness. When exactly one quiet address fits a new one, the two join one
 * chain. When two or more fit, or two new addresses could take the same old one, nothing joins:
 * in a crowd a chain ends early instead of jumping to a stranger. A chain is still a guess.
 *
 * Kept in memory only. A chain that stays with this phone across distance is the one to look at.
 */
class RotationLinker {
    data class Chain(
        val id: Long,
        val signature: String,
        val label: String,
        val firstAt: Long,
        val lastAt: Long,
        /** Address changes followed. Zero is one address that never swapped. */
        val links: Int,
        /** How far this phone moved while the chain was heard. */
        val withYouM: Double,
        val lastKey: String,
    )

    private class Track(
        val key: String,
        val chainId: Long,
        val signature: String,
        var lastAt: Long,
        var rssi: Int,
        /** A newer address already took this one's place. */
        var succeeded: Boolean = false,
    )

    private class Live(
        val id: Long,
        val signature: String,
        var label: String,
        val firstAt: Long,
        var lastAt: Long,
        var links: Int = 0,
        var withYouM: Double = 0.0,
        var lastLat: Double? = null,
        var lastLon: Double? = null,
        var lastKey: String,
    )

    private val lock = Any()
    private val tracks = HashMap<String, Track>()
    private val chains = HashMap<Long, Live>()
    private val hidden = HashSet<Long>()
    private var nextId = 1L

    /** One scan batch. [phoneLat] and [phoneLon] are where this phone was. */
    fun ingest(devices: List<Sighting>, now: Long, phoneLat: Double?, phoneLon: Double?) {
        synchronized(lock) {
            val fresh = ArrayList<Pair<Sighting, String>>()
            val heardChains = HashSet<Long>()
            for (device in devices) {
                if (device.kind != RadioKind.BLE || !device.randomized) continue
                val sig = signature(device) ?: continue
                val known = tracks[device.key]
                if (known != null) {
                    known.lastAt = maxOf(known.lastAt, device.lastSeen)
                    if (Rssi.measured(device.rssi)) known.rssi = device.rssi
                    chains[known.chainId]?.let { chain ->
                        chain.lastAt = maxOf(chain.lastAt, device.lastSeen)
                        chain.lastKey = device.key
                        heardChains += chain.id
                    }
                } else {
                    fresh += device to sig
                }
            }
            // How many new addresses share each signature in this batch. Two at once is a crowd.
            val freshBySig = fresh.groupingBy { it.second }.eachCount()
            for ((device, sig) in fresh) {
                val start = device.firstSeen
                val candidates = tracks.values.filter { t ->
                    !t.succeeded &&
                        t.signature == sig &&
                        t.lastAt <= start &&
                        start - t.lastAt <= LINK_GAP_MS &&
                        (!Rssi.measured(device.rssi) || kotlin.math.abs(t.rssi - device.rssi) <= RSSI_SLACK_DB)
                }
                val chain = if (candidates.size == 1 && freshBySig[sig] == 1) {
                    val prev = candidates.single()
                    prev.succeeded = true
                    chains[prev.chainId]?.also { it.links++ }
                } else {
                    null
                } ?: Live(
                    id = nextId++,
                    signature = sig,
                    label = "",
                    firstAt = start,
                    lastAt = device.lastSeen,
                    lastKey = device.key,
                ).also { chains[it.id] = it }
                chain.lastAt = maxOf(chain.lastAt, device.lastSeen)
                chain.lastKey = device.key
                chain.label = labelOf(device, sig)
                tracks[device.key] = Track(device.key, chain.id, sig, device.lastSeen, device.rssi)
                heardChains += chain.id
            }
            if (phoneLat != null && phoneLon != null && phoneLat.isFinite() && phoneLon.isFinite()) {
                for (id in heardChains) {
                    val chain = chains[id] ?: continue
                    val lat = chain.lastLat
                    val lon = chain.lastLon
                    if (lat == null || lon == null) {
                        chain.lastLat = phoneLat
                        chain.lastLon = phoneLon
                        continue
                    }
                    val moved = Geo.meters(lat, lon, phoneLat, phoneLon)
                    if (moved >= STEP_M) {
                        chain.withYouM += moved
                        chain.lastLat = phoneLat
                        chain.lastLon = phoneLon
                    }
                }
            }
            prune(now)
        }
    }

    /** Chains worth showing: some address changes, some time, and some distance with this phone. */
    fun chains(
        now: Long,
        minLinks: Int = MIN_LINKS,
        minSpanMs: Long = MIN_SPAN_MS,
        minWithYouM: Double = MIN_WITH_YOU_M,
    ): List<Chain> = synchronized(lock) {
        chains.values
            .filter { it.id !in hidden && it.links >= minLinks && it.lastAt - it.firstAt >= minSpanMs }
            .filter { it.withYouM >= minWithYouM && now - it.lastAt <= KEEP_MS }
            .sortedByDescending { it.withYouM }
            .map { Chain(it.id, it.signature, it.label, it.firstAt, it.lastAt, it.links, it.withYouM, it.lastKey) }
    }

    /** Hide a chain until the app restarts. A rotating radio cannot be marked Mine for good. */
    fun hide(id: Long) {
        synchronized(lock) { hidden += id }
    }

    private fun prune(now: Long) {
        tracks.values.removeAll { now - it.lastAt > TRACK_MS }
        chains.values.removeAll { now - it.lastAt > KEEP_MS }
        if (chains.size > MAX_CHAINS) {
            chains.values.sortedBy { it.lastAt }.take(chains.size - MAX_CHAINS).forEach { chains.remove(it.id) }
        }
        val alive = chains.keys
        tracks.values.removeAll { it.chainId !in alive }
        hidden.retainAll(alive)
    }

    companion object {
        const val LINK_GAP_MS = 30_000L
        const val RSSI_SLACK_DB = 10
        const val STEP_M = 50.0
        const val MIN_LINKS = 2
        const val MIN_SPAN_MS = 30 * 60_000L
        const val MIN_WITH_YOU_M = 2_000.0
        const val TRACK_MS = 10 * 60_000L
        const val KEEP_MS = 12 * 60 * 60_000L
        const val MAX_CHAINS = 400

        /**
         * What kind of advertisement this is, without the parts that change at a swap.
         * Company, the first payload byte (Apple's message type), payload length, and services.
         * Null when there is nothing to tell two radios apart by.
         */
        fun signature(device: Sighting): String? {
            val company = device.manufacturerId
            val hex = device.manufacturerDataHex.filter { it.isLetterOrDigit() }
            val uuids = device.serviceUuids.map { it.lowercase() }.sorted().joinToString(",")
            if (company == null && uuids.isEmpty()) return null
            val type = if (hex.length >= 2) hex.substring(0, 2).uppercase() else ""
            return "${company ?: "-"}|$type|${hex.length / 2}|$uuids"
        }

        private fun labelOf(device: Sighting, sig: String): String {
            val name = device.name.trim()
            if (name.isNotEmpty() && !name.equals(device.mac, ignoreCase = true) && name != "unnamed LE") return name
            return when (device.manufacturerId) {
                0x004C -> "Apple, type ${sig.split('|').getOrNull(1).orEmpty().ifBlank { "?" }}"
                0x00E0 -> "Google"
                0x0075 -> "Samsung"
                0x0006 -> "Microsoft"
                null -> "Bluetooth"
                else -> "Company 0x%04X".format(device.manufacturerId)
            }
        }
    }
}
