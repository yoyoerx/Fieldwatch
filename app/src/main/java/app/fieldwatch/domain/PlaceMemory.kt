package app.fieldwatch.domain

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One stable radio heard at this phone's stops.
 *
 * A place is a cluster of this phone's GPS, about 100 m across. A second place
 * opens only after a quiet stretch, so a tag heard the whole drive stays one
 * visit. The update looks at that one record. It does not scan sits or the log.
 *
 * A fleet name is not an identity. Rotating BLE addresses are skipped unless
 * the radio already carries a stable Remote ID. Ordinary Wi-Fi and unmatched
 * Bluetooth stay out unless [accept] is asked to include them ([wide]).
 * A Wi-Fi access point counts only at stops [WIFI_SEPARATION_M] apart, so a
 * router heard from the next street is still one place.
 */
object PlaceMemory {
    const val SEPARATION_M = 100.0
    const val QUIET_MS = 15 * 60_000L
    const val RETURN_MS = 20 * 60_000L
    const val DAY_MS = 24 * 60 * 60_000L
    /** Stops listed on the radio page, in the sit report, and on the Live filter. */
    const val WINDOW_MS = 7 * DAY_MS
    /** How long “Heard again” stays on the page. The gap itself can be longer. */
    const val RETURN_SHOW_MS = DAY_MS
    const val RETAIN_MS = WINDOW_MS
    const val MIN_PLACES = 2
    const val CONFIRM_HITS = 2
    const val MAX_PLACES = 8
    const val MAX_RECORDS = 3000
    const val MAX_DAYS = 8
    const val WIFI_SEPARATION_M = 500.0
    /** A hear this close to a stop already on the record joins it. GPS indoors wanders past 100 m. */
    const val STOP_MOVE_M = 250.0
    /** Longer than a phone's 15-minute address rotation. An address this old is not rotating. */
    const val STABLE_MS = 20 * 60_000L
    const val FORMAT = "fieldwatch-places"

    const val KIND_TAG = "tag"
    const val KIND_DRONE = "drone"
    const val KIND_WIFI = "wifi"
    const val KIND_BLE = "ble"

    @Serializable
    data class Place(
        val lat: Double,
        val lon: Double,
        val firstAt: Long,
        val lastAt: Long,
        val count: Int,
        val name: String? = null,
        /** Sum and count of signal readings at this stop, for the average. */
        val rssiSum: Long = 0L,
        val rssiHits: Int = 0,
    )

    @Serializable
    data class Record(
        val id: String,
        val deviceKey: String,
        val places: List<Place> = emptyList(),
        val lastHeardAt: Long = 0L,
        val returnAt: Long = 0L,
        val returnGapMs: Long = 0L,
        val sitId: String? = null,
        val localDay: Long = Long.MIN_VALUE,
        /** Last name worth showing after this radio leaves the live list. */
        val label: String = "",
        /** Local epoch days this radio was heard, newest last, up to [MAX_DAYS]. */
        val days: List<Long> = emptyList(),
        /** One of the KIND_ values. Blank on files from before it was stored. */
        val kind: String = "",
        /** Local epoch day the stops warning last fired for this radio. */
        val alertedDay: Long = Long.MIN_VALUE,
    )

    data class Hear(
        val id: String,
        val deviceKey: String,
        val at: Long,
        val lat: Double?,
        val lon: Double?,
        val sitId: String?,
        val sitLabel: String?,
        val localDay: Long,
        val label: String = "",
        val rssi: Int? = null,
        val kind: String = "",
    )

    /**
     * Stable id, or null when this radio cannot be followed across time.
     * Mine radios are out. [generic] is for tests; the phone leaves it off.
     */
    fun accept(
        device: Sighting,
        names: Map<String, String>,
        mineKeys: Set<String>,
        generic: Boolean = false,
        wide: Boolean = false,
    ): String? {
        if (device.key in mineKeys) return null
        val uas = device.payloadUasId?.trim().orEmpty()
        if (uas.isNotEmpty()) return "rid:$uas"
        if (device.kind == RadioKind.WIFI) {
            // An access point keeps its BSSID, even a locally administered one.
            return if (generic || wide) "wifi:${device.mac}" else null
        }
        val finder = TrackerMatch.kind(device, names) == TrackerMatch.Kind.FINDER
        if (!finder && !generic && !wide) return null
        // A public address, or a random one that has already outlived a rotation. A tag away from
        // its owner holds its address for a day, so it is kept from its twentieth minute on.
        if (!stableBle(device)) return null
        return "ble:${device.mac}"
    }

    /** Public address, or one heard unchanged for longer than a rotation. Unknown type falls back to the old rule. */
    fun stableBle(device: Sighting): Boolean {
        val type = device.facts.addressType
        if (type.equals("Public", ignoreCase = true)) return true
        if (device.lastSeen - device.firstSeen >= STABLE_MS) return true
        val reported = type.equals("Random", ignoreCase = true) || type.equals("Anonymous", ignoreCase = true)
        return !reported && !MacUtil.isRandomized(device.mac)
    }

    /** What sort of radio an accepted id is. */
    fun kindOf(id: String, device: Sighting, names: Map<String, String>): String = when {
        id.startsWith("rid:") -> KIND_DRONE
        id.startsWith("wifi:") -> KIND_WIFI
        TrackerMatch.kind(device, names) == TrackerMatch.Kind.FINDER -> KIND_TAG
        else -> KIND_BLE
    }

    /** Stored kind, or a guess for files written before it was stored. Those only held tags and drones. */
    fun kindOf(record: Record): String = when {
        record.kind.isNotBlank() -> record.kind
        record.id.startsWith("rid:") -> KIND_DRONE
        record.id.startsWith("wifi:") -> KIND_WIFI
        else -> KIND_TAG
    }

    /**
     * Name to keep on the place record.
     * A custom name wins. Otherwise the live title, when it is a real name.
     * A blank result keeps whatever the record already stored.
     */
    fun heardLabel(device: Sighting, signatureNames: List<String>, custom: String?): String {
        val named = keptLabel("", custom.orEmpty())
        if (named.isNotEmpty() && !named.equals(device.mac, ignoreCase = true)) return named
        val title = device.listTitle(signatureNames).trim()
        return keptLabel("", title).let { if (it.equals(device.mac, ignoreCase = true)) "" else it }
    }

    fun note(prev: Record?, hear: Hear): Record {
        val at = hear.at
        val gap = if (prev == null) 0L else (at - prev.lastHeardAt).coerceAtLeast(0L)
        var returnAt = prev?.returnAt ?: 0L
        var returnGap = prev?.returnGapMs ?: 0L
        val sameDay = prev != null && prev.localDay == hear.localDay
        val sameSit = prev != null && hear.sitId != null && hear.sitId == prev.sitId
        if (prev != null && gap >= RETURN_MS && (sameDay || sameSit)) {
            returnAt = at
            returnGap = gap
        }
        val places = when {
            hear.lat == null || hear.lon == null -> prev?.places.orEmpty()
            prev == null || prev.places.isEmpty() -> listOf(openPlace(hear))
            else -> {
                val near = nearest(prev.places, hear.lat, hear.lon)
                    ?: nearest(prev.places, hear.lat, hear.lon, STOP_MOVE_M)
                when {
                    near != null -> cap(prev.places.map { if (it == near) touch(it, hear) else it })
                    gap >= QUIET_MS -> cap(prev.places + openPlace(hear))
                    else -> prev.places
                }
            }
        }
        return Record(
            id = hear.id,
            deviceKey = hear.deviceKey,
            places = places,
            lastHeardAt = at,
            returnAt = returnAt,
            returnGapMs = returnGap,
            sitId = hear.sitId,
            localDay = hear.localDay,
            label = keptLabel(prev?.label, hear.label),
            days = addDay(prev?.days.orEmpty(), hear.localDay),
            kind = hear.kind.ifBlank { prev?.kind.orEmpty() },
            alertedDay = prev?.alertedDay ?: Long.MIN_VALUE,
        )
    }

    private fun addDay(days: List<Long>, day: Long): List<Long> {
        if (day == Long.MIN_VALUE || day in days) return days
        return (days + day).sorted().takeLast(MAX_DAYS)
    }

    /** Stops since [since], with the Wi-Fi spread rule. */
    fun placesSince(record: Record, since: Long): List<Place> = counted(record, since)

    /** Distinct local days heard on or after [sinceDay]. */
    fun daysSince(record: Record, sinceDay: Long): Int {
        val days = record.days.count { it >= sinceDay }
        return if (days > 0) days else if (record.localDay >= sinceDay) 1 else 0
    }

    /** Average signal over [places], or null when none was stored. */
    fun averageRssi(places: List<Place>): Int? {
        val hits = places.sumOf { it.rssiHits }
        if (hits <= 0) return null
        return (places.sumOf { it.rssiSum } / hits).toInt()
    }

    fun prune(record: Record, now: Long): Record? {
        if (now - record.lastHeardAt > RETAIN_MS) return null
        val cut = now - RETAIN_MS
        val places = record.places.filter { it.lastAt >= cut }
        return if (places.size == record.places.size) record else record.copy(places = places)
    }

    fun placeCount(record: Record, now: Long): Int = counted(record, now - WINDOW_MS).size

    fun placesInWindow(record: Record, now: Long): List<Place> = counted(record, now - WINDOW_MS)

    fun detailLines(
        record: Record,
        now: Long,
        zone: ZoneId,
        demo: Boolean = false,
        streets: Map<String, String> = emptyMap(),
    ): List<String> {
        val counted = counted(record, now - WINDOW_MS).sortedBy { it.firstAt }
        val lines = ArrayList<String>(counted.size + 2)
        if (counted.size >= MIN_PLACES) {
            val today = counted.all { onSameDay(it.firstAt, now, zone) && onSameDay(it.lastAt, now, zone) }
            lines += if (today) {
                "Seen at ${counted.size} places today"
            } else {
                "Seen at ${counted.size} places"
            }
            val named = if (demo) emptyMap() else streets
            counted.forEach { lines += placeLine(it, zone, now, demo, named) }
        }
        val again = returnLine(record, now)
        if (again.isNotEmpty()) lines += again
        return lines
    }

    /** Time, sit name, street, and coordinate kept apart for the places report. */
    internal fun visitParts(
        place: Place,
        now: Long,
        zone: ZoneId,
        demo: Boolean,
        streets: Map<String, String>,
    ): VisitParts {
        val coord = if (demo) "masked" else Geo.screenCoord(place.lat, place.lon, false).orEmpty()
        val street = if (demo) "" else Geo.nameNear(place.lat, place.lon, streets, SEPARATION_M).orEmpty()
        return VisitParts(
            time = span(place, zone, now, spaced = true),
            name = blankToNull(place.name).orEmpty(),
            street = street,
            coord = coord,
        )
    }

    /** Quiet return, or blank. The places report shows this under the numbered stops. */
    internal fun returnLine(record: Record, now: Long): String {
        if (record.returnAt > 0L &&
            record.returnGapMs >= RETURN_MS &&
            now - record.returnAt in 0..RETURN_SHOW_MS
        ) {
            return "Heard again after ${gapPhrase(record.returnGapMs)}"
        }
        return ""
    }

    /**
     * Sit-report body for radios in [devices] that already have two places.
     * Null when there is nothing to add.
     */
    fun section(
        devices: List<Sighting>,
        records: List<Record>,
        names: Map<String, String>,
        customNames: Map<String, String>,
        mineKeys: Set<String>,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        demo: Boolean = false,
        streets: Map<String, String> = emptyMap(),
    ): String? {
        if (records.isEmpty() || devices.isEmpty()) return null
        val byId = HashMap<String, Record>(records.size)
        for (record in records) byId[record.id] = record
        val seen = HashSet<String>()
        val blocks = ArrayList<Pair<Int, String>>()
        for (device in devices) {
            val id = accept(device, names, mineKeys) ?: continue
            if (!seen.add(id)) continue
            val record = byId[id] ?: continue
            val lines = detailLines(record, now, zone, demo, streets)
            if (lines.none { it.startsWith("Seen at ") }) continue
            val count = placeCount(record, now)
            val text = buildString {
                append(device.reportName(customNames))
                append("  ")
                append(device.mac)
                lines.forEach {
                    append('\n')
                    append("  ")
                    append(it)
                }
            }
            blocks += count to text
        }
        if (blocks.isEmpty()) return null
        blocks.sortByDescending { it.first }
        return buildString {
            append("A stable address heard at two or more places in the last 7 days. ")
            append("The times are when this phone heard it. ")
            append("The coordinates are where this phone was.")
            blocks.forEach { (_, text) ->
                append('\n')
                append(text)
            }
        }
    }

    private fun counted(record: Record, cut: Long): List<Place> {
        val confirmed = record.places.filter { it.count >= CONFIRM_HITS && it.lastAt >= cut }
        if (!record.id.startsWith("wifi:") || confirmed.size < 2) return confirmed
        // Keep the busiest stops first. A Wi-Fi stop within the spread of one kept is the same router.
        val kept = ArrayList<Place>(confirmed.size)
        for (place in confirmed.sortedByDescending { it.count }) {
            if (kept.none { Geo.meters(it.lat, it.lon, place.lat, place.lon) < WIFI_SEPARATION_M }) kept += place
        }
        return confirmed.filter { it in kept }
    }

    private fun nearest(places: List<Place>, lat: Double, lon: Double, within: Double = SEPARATION_M): Place? {
        var best: Place? = null
        var bestM = within
        for (place in places) {
            val meters = Geo.meters(place.lat, place.lon, lat, lon)
            if (meters < bestM) {
                best = place
                bestM = meters
            }
        }
        return best
    }

    private fun openPlace(hear: Hear): Place = Place(
        lat = hear.lat ?: 0.0,
        lon = hear.lon ?: 0.0,
        firstAt = hear.at,
        lastAt = hear.at,
        count = 1,
        name = blankToNull(hear.sitLabel),
        rssiSum = usableRssi(hear.rssi)?.toLong() ?: 0L,
        rssiHits = if (usableRssi(hear.rssi) != null) 1 else 0,
    )

    private fun touch(place: Place, hear: Hear): Place {
        val rssi = usableRssi(hear.rssi)
        return place.copy(
            lastAt = hear.at,
            count = place.count + 1,
            name = place.name ?: blankToNull(hear.sitLabel),
            rssiSum = place.rssiSum + (rssi ?: 0),
            rssiHits = place.rssiHits + if (rssi != null) 1 else 0,
        )
    }

    /** 127 and 0 are "not available" on some stacks. */
    private fun usableRssi(rssi: Int?): Int? = rssi?.takeIf { it in -130..-1 }

    /**
     * Keep the latest [MAX_PLACES] confirmed stops. One unconfirmed stop may sit
     * past that until the second hear, so a single jump does not erase an older stop.
     * The next confirmed stop drops the one heard longest ago.
     */
    private fun cap(places: List<Place>): List<Place> {
        val out = places.toMutableList()
        while (out.size > MAX_PLACES) {
            val unconfirmed = out.filter { it.count < CONFIRM_HITS }
            val confirmed = out.filter { it.count >= CONFIRM_HITS }
            if (unconfirmed.size == 1 && confirmed.size <= MAX_PLACES) break
            val drop = when {
                unconfirmed.size > 1 -> unconfirmed.minBy { it.lastAt }
                confirmed.isNotEmpty() -> confirmed.minBy { it.lastAt }
                else -> unconfirmed.minBy { it.lastAt }
            }
            out.remove(drop)
        }
        return out
    }

    internal data class VisitParts(
        val time: String,
        val name: String,
        val street: String,
        val coord: String,
    )

    private fun placeLine(
        place: Place,
        zone: ZoneId,
        now: Long,
        demo: Boolean,
        streets: Map<String, String>,
    ): String {
        val parts = visitParts(place, now, zone, demo, streets)
        val time = span(place, zone, now, spaced = false)
        val head = if (parts.name.isEmpty()) time else "${parts.name} · $time"
        if (parts.coord.isEmpty()) return head
        return if (parts.street.isEmpty()) "$head · ${parts.coord}" else "$head · ${parts.street} · ${parts.coord}"
    }

    private fun span(place: Place, zone: ZoneId, now: Long, spaced: Boolean): String {
        val dash = if (spaced) " – " else "–"
        val dated = !onSameDay(place.firstAt, now, zone) || !onSameDay(place.lastAt, now, zone)
        val start = formatAt(place.firstAt, zone, dated)
        val endSame = formatAt(place.lastAt, zone, dated)
        val endClock = clock.format(Instant.ofEpochMilli(place.lastAt).atZone(zone))
        return when {
            start == endSame -> start
            dated && onSameDay(place.firstAt, place.lastAt, zone) -> start + dash + endClock
            dated -> start + dash + formatAt(place.lastAt, zone, true)
            else -> if (start == endClock) start else start + dash + endClock
        }
    }

    private fun onSameDay(at: Long, now: Long, zone: ZoneId): Boolean =
        localDate(at, zone) == localDate(now, zone)

    /** LocalDate.ofInstant is API 31. minSdk is 29. */
    internal fun localDate(atMs: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate()

    private fun formatAt(at: Long, zone: ZoneId, dated: Boolean): String {
        val zoned = Instant.ofEpochMilli(at).atZone(zone)
        return if (dated) dayClock.format(zoned) else clock.format(zoned)
    }

    private fun gapPhrase(ms: Long): String {
        val minutes = ((ms + 30_000L) / 60_000L).toInt().coerceAtLeast(1)
        if (minutes < 120) return if (minutes == 1) "1 minute" else "$minutes minutes"
        val hours = (minutes + 30) / 60
        if (hours < 48) return if (hours == 1) "1 hour" else "$hours hours"
        val days = (hours + 12) / 24
        return if (days == 1) "1 day" else "$days days"
    }

    private fun keptLabel(prev: String?, next: String): String {
        val fresh = next.trim()
        if (fresh.isNotEmpty() && fresh != "unnamed LE") return fresh
        val older = prev?.trim().orEmpty()
        return if (older == "unnamed LE") "" else older
    }

    private fun blankToNull(raw: String?): String? = raw?.trim()?.ifBlank { null }

    private val clock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    private val dayClock: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.US)
}
