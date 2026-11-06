package app.fieldwatch.domain

import java.time.ZoneId

/**
 * Radios already heard at two or more stops, today or in the last 7 days.
 * The list does not wait for a sit, and it does not wait for the radio to be in earshot.
 * Each row keeps one color. The map uses that same color.
 * A stop is where this phone was. Rows rank by [score]: more stops, more days, and a louder
 * average put a radio higher. A radio at most of this phone's stops at a strong signal is
 * flagged [Row.likelyMine], so the operator's own gear can be marked Mine in one tap.
 */
object PlaceReport {
    enum class Kind { TAG, HOTSPOT, BLE, DRONE }

    /** Share of this phone's stops, and the average signal, that flag a radio as likely the operator's. */
    const val LIKELY_SHARE = 0.8
    const val LIKELY_DBM = -65
    const val LIKELY_MIN_STOPS = 3
    /**
     * Saturated enough to read on a street map and on the list, in day and night.
     * The ninth radio repeats the first color.
     */
    val COLORS: LongArray = longArrayOf(
        0xFF2563EB,
        0xFFDC2626,
        0xFF059669,
        0xFFD97706,
        0xFF7C3AED,
        0xFFDB2777,
        0xFF0891B2,
        0xFF4F46E5,
    )

    data class Stop(
        val lat: Double,
        val lon: Double,
        val at: Long,
    )

    data class Pin(
        val lat: Double,
        val lon: Double,
        val color: Long,
    )

    /** One stop, in the order this phone heard the radio. */
    data class Visit(
        val number: Int,
        val time: String,
        val name: String,
        val street: String,
        val coord: String,
    )

    data class Row(
        val id: String,
        val deviceKey: String,
        val color: Long,
        val title: String,
        val address: String,
        val count: Int,
        val visits: List<Visit>,
        /** A return after a quiet stretch. Blank when there is nothing to add. */
        val note: String,
        val stops: List<Stop>,
        /** True when this radio is still on the live list. The row opens either way. */
        val known: Boolean,
        val kind: Kind = Kind.TAG,
        /** Distinct days heard inside the window. */
        val days: Int = 1,
        val avgRssi: Int? = null,
        val score: Int = 0,
        val likelyMine: Boolean = false,
        /** Marked Known, not mine. Listed only when the page asks for them. */
        val familiar: Boolean = false,
        /** Stops at places this phone went on only one day of the week. */
        val oneOff: Int = 0,
    )

    /** One summary for the page header. [routine] stops are ones this phone used on two or more days. */
    data class Summary(val radios: Int, val stops: Int, val likelyMine: Int, val routine: Int = 0, val familiar: Int = 0)

    fun summary(
        rows: List<Row>,
        records: List<PlaceMemory.Record>,
        watchlist: List<WatchTarget>,
        since: Long,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Summary {
        val week = Clusters.of(records, now - PlaceMemory.WINDOW_MS, zone)
        val shown = Clusters.of(records, since, zone)
        val familiar = RadioBookmarks.familiarKeys(watchlist)
        val hidden = records.count { record ->
            record.deviceKey in familiar && PlaceMemory.placesSince(record, since).size >= PlaceMemory.MIN_PLACES
        }
        return Summary(
            radios = rows.size,
            stops = shown.size,
            likelyMine = rows.count { it.likelyMine },
            routine = shown.centers.count { c -> week.routineAt(c.lat, c.lon) },
            familiar = hidden,
        )
    }

    /** A place this phone used on this many days of the week is routine: home, the office. */
    const val ROUTINE_DAYS = 2

    fun kindOf(record: PlaceMemory.Record): Kind = when (PlaceMemory.kindOf(record)) {
        PlaceMemory.KIND_WIFI -> Kind.HOTSPOT
        PlaceMemory.KIND_BLE -> Kind.BLE
        PlaceMemory.KIND_DRONE -> Kind.DRONE
        else -> Kind.TAG
    }

    /**
     * Fifteen a one-off stop, five a routine one, fifteen for each extra day. A radio at the places
     * only this phone went to says more than one at home and the office, where coworkers and
     * neighbors are. Loudness only breaks ties, so it stays under one routine stop.
     */
    fun score(stops: Int, days: Int, avgRssi: Int?, oneOff: Int = stops): Int {
        val loud = when {
            avgRssi == null -> 0
            avgRssi >= -60 -> 4
            avgRssi >= -75 -> 2
            else -> 0
        }
        val single = oneOff.coerceIn(0, stops)
        return single * 15 + (stops - single) * 5 + (days - 1).coerceAtLeast(0) * 15 + loud
    }

    /**
     * This phone's own stops since [since]: every confirmed place on any record, merged
     * when closer than [PlaceMemory.STOP_MOVE_M]. Mine radios still count, since they were with the phone.
     */
    fun phoneStops(records: List<PlaceMemory.Record>, mineKeys: Set<String>, since: Long): Int =
        Clusters.of(records, since, ZoneId.of("UTC")).size

    /** This phone's stops, each with the local days it was there. Looked up through a coarse grid. */
    internal class Clusters private constructor() {
        class Center(val lat: Double, val lon: Double) {
            val days = HashSet<Long>()
        }

        // Grid cells wider than one separation, so a neighbor is always in the 3 x 3 block around a stop.
        private val cells = HashMap<Long, MutableList<Center>>()
        val centers = ArrayList<Center>()
        val size: Int get() = centers.size

        fun find(lat: Double, lon: Double): Center? {
            val row = kotlin.math.floor(lat * GRID_PER_DEG).toLong()
            val col = kotlin.math.floor(lon * GRID_PER_DEG).toLong()
            for (dr in -1L..1L) {
                for (dc in -1L..1L) {
                    val hit = cells[cellKey(row + dr, col + dc)]?.firstOrNull {
                        Geo.meters(it.lat, it.lon, lat, lon) < PlaceMemory.STOP_MOVE_M
                    }
                    if (hit != null) return hit
                }
            }
            return null
        }

        fun routineAt(lat: Double, lon: Double): Boolean = (find(lat, lon)?.days?.size ?: 0) >= ROUTINE_DAYS

        private fun add(lat: Double, lon: Double): Center {
            find(lat, lon)?.let { return it }
            val center = Center(lat, lon)
            val row = kotlin.math.floor(lat * GRID_PER_DEG).toLong()
            val col = kotlin.math.floor(lon * GRID_PER_DEG).toLong()
            cells.getOrPut(cellKey(row, col)) { ArrayList(2) } += center
            centers += center
            return center
        }

        companion object {
            fun of(records: List<PlaceMemory.Record>, since: Long, zone: ZoneId): Clusters {
                val out = Clusters()
                for (record in records) {
                    for (place in record.places) {
                        if (place.count < PlaceMemory.CONFIRM_HITS || place.lastAt < since) continue
                        if (!PayloadLocation.validCoord(place.lat, place.lon)) continue
                        val center = out.add(place.lat, place.lon)
                        center.days += PlaceMemory.localDate(place.firstAt, zone).toEpochDay()
                        center.days += PlaceMemory.localDate(place.lastAt, zone).toEpochDay()
                    }
                }
                return out
            }
        }
    }

    /**
     * About 556 m per cell north to south and wider than [PlaceMemory.STOP_MOVE_M] east to west
     * up to about 60 degrees of latitude, so the 3 x 3 block always holds a stop's neighbors there.
     */
    private const val GRID_PER_DEG = 200.0

    private fun cellKey(row: Long, col: Long): Long = (row shl 32) xor (col and 0xFFFFFFFFL)

    /** Start of [now]'s local day. */
    fun todayStart(now: Long, zone: ZoneId): Long =
        PlaceMemory.localDate(now, zone).atStartOfDay(zone).toInstant().toEpochMilli()

    fun colorAt(index: Int): Long = COLORS[index % COLORS.size]

    fun rows(
        records: List<PlaceMemory.Record>,
        devices: List<Sighting>,
        fleets: List<Fleet>,
        watchlist: List<WatchTarget>,
        now: Long,
        zone: ZoneId,
        demo: Boolean,
        streets: Map<String, String>,
        /** Count stops on or after this time. Missing is the 7-day window. */
        since: Long = now - PlaceMemory.WINDOW_MS,
        /** List radios marked Known, not mine, too. */
        showFamiliar: Boolean = false,
    ): List<Row> {
        if (records.isEmpty()) return emptyList()
        val sinceDay = PlaceMemory.localDate(since.coerceAtLeast(0L), zone).toEpochDay()
        val mine = RadioBookmarks.mineKeys(watchlist)
        val familiar = RadioBookmarks.familiarKeys(watchlist)
        val custom = RadioBookmarks.labels(watchlist)
        val fleetNames = HashMap<String, String>(fleets.size)
        for (fleet in fleets) fleetNames[fleet.id] = fleet.name
        val live = HashMap<String, String>(devices.size)
        val known = HashSet<String>(devices.size)
        for (device in devices) {
            known += device.key
            val sig = device.fleetIds.mapNotNull { fleetNames[it] }
            val title = displayTitle(
                device.listLineText(ListLine.NAME_AND_TYPE, sig, custom[device.key]),
                device.mac,
            )
            if (title.isNotEmpty()) live[device.key] = title
        }
        val stopsOfPhone = Clusters.of(records, since, zone).size
        // Routine is judged on the whole week, so a Today view still knows home from an errand.
        val week = Clusters.of(records, now - PlaceMemory.WINDOW_MS, zone)
        data class Scored(
            val record: PlaceMemory.Record,
            val places: List<PlaceMemory.Place>,
            val days: Int,
            val avg: Int?,
            val oneOff: Int,
            val score: Int,
        )
        val ranked = records
            .asSequence()
            .filter { it.deviceKey !in mine && (showFamiliar || it.deviceKey !in familiar) }
            .mapNotNull { record ->
                val places = PlaceMemory.placesSince(record, since)
                if (places.size < PlaceMemory.MIN_PLACES) return@mapNotNull null
                val days = PlaceMemory.daysSince(record, sinceDay).coerceAtLeast(1)
                val avg = PlaceMemory.averageRssi(places)
                val oneOff = places.count { !week.routineAt(it.lat, it.lon) }
                Scored(record, places, days, avg, oneOff, score(places.size, days, avg, oneOff))
            }
            .sortedWith(
                compareByDescending<Scored> { it.score }
                    .thenByDescending { it.record.lastHeardAt }
                    .thenBy { it.record.id },
            )
            .toList()
        return ranked.mapIndexed { index, scored ->
            val record = scored.record
            val count = scored.places.size
            val address = addressOf(record.deviceKey, demo)
            val mac = RadioBookmarks.parseKey(record.deviceKey)?.second.orEmpty()
            val stored = displayTitle(record.label, mac)
            val named = displayTitle(custom[record.deviceKey].orEmpty(), mac)
            val title = live[record.deviceKey].orEmpty()
                .ifBlank { named }
                .ifBlank { stored }
                .ifBlank { address }
                .ifBlank { "—" }
            val places = scored.places.sortedBy { it.firstAt }
            val stops = places.mapNotNull { place ->
                if (!PayloadLocation.validCoord(place.lat, place.lon)) null
                else Stop(place.lat, place.lon, place.lastAt)
            }
            val visits = places.mapIndexed { placeIndex, place ->
                val parts = PlaceMemory.visitParts(place, now, zone, demo, streets)
                Visit(
                    number = placeIndex + 1,
                    time = parts.time,
                    name = parts.name,
                    street = parts.street,
                    coord = parts.coord,
                )
            }
            Row(
                id = record.id,
                deviceKey = record.deviceKey,
                color = colorAt(index),
                title = title,
                address = if (address.isNotEmpty() && !address.equals(title, ignoreCase = true)) address else "",
                count = count,
                visits = visits,
                note = PlaceMemory.returnLine(record, now),
                stops = stops,
                known = record.deviceKey in known,
                kind = kindOf(record),
                days = scored.days,
                avgRssi = scored.avg,
                score = scored.score,
                familiar = record.deviceKey in familiar,
                oneOff = scored.oneOff,
                // A tag at every stop is the operator's or planted on them. Never offer it in one tap.
                likelyMine = kindOf(record) != Kind.TAG &&
                    record.deviceKey !in familiar &&
                    count >= LIKELY_MIN_STOPS &&
                    stopsOfPhone > 0 &&
                    count >= stopsOfPhone * LIKELY_SHARE &&
                    (scored.avg ?: Int.MIN_VALUE) >= LIKELY_DBM,
            )
        }
    }

    fun pins(rows: List<Row>): List<Pin> = rows.flatMap { row ->
        row.stops.map { Pin(it.lat, it.lon, row.color) }
    }

    /** Latest stop on the list. The wide map opens here. */
    fun newest(rows: List<Row>): Stop? =
        rows.asSequence().flatMap { it.stops.asSequence() }.maxByOrNull { it.at }

    /**
     * A radio the live list has already dropped.
     * The name is the one saved with the places. The radio page still reads those places.
     */
    fun remembered(record: PlaceMemory.Record): Sighting? {
        val parsed = RadioBookmarks.parseKey(record.deviceKey) ?: return null
        val (kind, mac) = parsed
        val first = record.places.minOfOrNull { it.firstAt } ?: record.lastHeardAt
        val last = if (record.lastHeardAt > 0L) record.lastHeardAt else first
        return Sighting(
            key = record.deviceKey,
            kind = kind,
            mac = mac,
            name = displayTitle(record.label, mac),
            rssi = -100,
            rssiMin = -100,
            rssiMax = -100,
            channel = 0,
            frequencyMhz = 0,
            vendor = null,
            randomized = MacUtil.isRandomized(mac),
            hiddenSsid = false,
            serviceUuids = emptyList(),
            manufacturerId = null,
            manufacturerDataHex = "",
            rawHex = "",
            extras = "",
            firstSeen = first,
            lastSeen = last,
            hitCount = record.places.sumOf { it.count.coerceAtLeast(0) },
            fleetIds = emptySet(),
            rssiHistory = emptyList(),
            presence = listOf(PresenceSpan(first, last)),
            gone = true,
        )
    }

    /** Live Name + type, with a MAC or "unnamed LE" left blank so the row can fall through. */
    private fun displayTitle(raw: String, mac: String = ""): String {
        val title = raw.trim()
        if (title.isEmpty() || title.equals("unnamed LE", ignoreCase = true)) return ""
        if (mac.isNotEmpty() && title.equals(mac, ignoreCase = true)) return ""
        return title
    }

    private fun addressOf(deviceKey: String, demo: Boolean): String {
        val mac = RadioBookmarks.parseKey(deviceKey)?.second ?: return ""
        return MacUtil.screenMac(mac, demo)
    }
}
