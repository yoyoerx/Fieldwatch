package app.fieldwatch.data

import app.fieldwatch.domain.Fleet
import app.fieldwatch.domain.PlaceMemory
import app.fieldwatch.domain.RadioBookmarks
import app.fieldwatch.domain.Sighting
import app.fieldwatch.domain.Sit
import app.fieldwatch.domain.WatchTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.ZoneId

/**
 * Local place file. One lookup per eligible hear, flushed on the sit cadence.
 * Coordinates stay in this file. Settings export does not include it.
 */
class PlaceStore(private val file: File) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }
    private val lock = Any()
    private val records = HashMap<String, PlaceMemory.Record>()
    private var dirty = false
    /** Counters and signal moved, nothing a page would miss. Written at most every [SOFT_FLUSH_MS]. */
    private var soft = false
    private var lastFlushAt = 0L
    private var generation = 0
    private var cacheMinute = Long.MIN_VALUE
    private var cacheGen = -1
    private var cacheMine = 0
    private var cacheKeys: Set<String> = emptySet()

    fun load() {
        val text = runCatching { file.readText() }.getOrNull() ?: return
        val parsed = runCatching { json.decodeFromString<PlaceFile>(text) }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        synchronized(lock) {
            records.clear()
            for (record in parsed.records) {
                val kept = PlaceMemory.prune(record, now) ?: continue
                records[kept.id] = kept
            }
            trimLocked(now)
            generation++
        }
    }

    fun startFlusher(scope: CoroutineScope) {
        scope.launch {
            while (true) {
                delay(Sit.FLUSH_MS)
                runCatching { flush() }
            }
        }
    }

    /**
     * Note one batch. Returns records that just reached [alertStops] stops today
     * and have not warned today. Zero turns that off.
     */
    fun hear(
        devices: List<Sighting>,
        fleets: List<Fleet>,
        watchlist: List<WatchTarget>,
        sitId: String?,
        sitLabel: String?,
        wide: Boolean = false,
        alertStops: Int = 0,
    ): List<PlaceMemory.Record> {
        if (devices.isEmpty()) return emptyList()
        val names = HashMap<String, String>(fleets.size)
        for (fleet in fleets) names[fleet.id] = fleet.name
        val custom = RadioBookmarks.labels(watchlist)
        val mine = RadioBookmarks.mineKeys(watchlist)
        val familiar = RadioBookmarks.familiarKeys(watchlist)
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val today = PlaceMemory.localDate(now, zone)
        val todayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val todayDay = today.toEpochDay()
        val crossed = ArrayList<PlaceMemory.Record>()
        synchronized(lock) {
            var changed = false
            var structural = false
            for (device in devices) {
                val id = PlaceMemory.accept(device, names, mine, wide = wide) ?: continue
                val at = if (device.lastSeen > 0L) device.lastSeen else now
                val day = PlaceMemory.localDate(at, zone).toEpochDay()
                val sigNames = device.fleetIds.mapNotNull { names[it] }
                val prev = records[id]
                val next = PlaceMemory.prune(
                    PlaceMemory.note(
                        prev,
                        PlaceMemory.Hear(
                            id = id,
                            deviceKey = device.key,
                            at = at,
                            lat = device.latitude,
                            lon = device.longitude,
                            sitId = sitId,
                            sitLabel = sitLabel,
                            localDay = day,
                            label = PlaceMemory.heardLabel(device, sigNames, custom[device.key]),
                            rssi = device.rssi,
                            kind = PlaceMemory.kindOf(id, device, names),
                        ),
                    ),
                    now,
                )?.let { record ->
                    val due = alertStops > 0 && record.alertedDay != todayDay &&
                        device.key !in familiar &&
                        PlaceMemory.placesSince(record, todayStart).size >= alertStops
                    if (due) record.copy(alertedDay = todayDay).also { crossed += it } else record
                }
                if (next == null) {
                    if (prev != null) {
                        records.remove(id)
                        changed = true
                        structural = true
                    }
                    continue
                }
                if (next != prev) {
                    records[next.id] = next
                    changed = true
                    if (!structural && isStructural(prev, next)) structural = true
                }
            }
            if (changed) {
                trimLocked(now)
                if (structural) dirty = true else soft = true
                generation++
            }
        }
        return crossed
    }

    fun linesFor(
        device: Sighting,
        fleets: List<Fleet>,
        mineKeys: Set<String>,
        now: Long,
        demo: Boolean = false,
        streets: Map<String, String> = emptyMap(),
    ): List<String> {
        val record = recordFor(device, fleets, mineKeys) ?: return emptyList()
        return PlaceMemory.detailLines(record, now, ZoneId.systemDefault(), demo, streets)
    }

    /** Confirmed places in the last 7 days, when this radio has at least two. */
    fun placePoints(
        device: Sighting,
        fleets: List<Fleet>,
        mineKeys: Set<String>,
        now: Long,
    ): List<Pair<Double, Double>> {
        val record = recordFor(device, fleets, mineKeys) ?: return emptyList()
        val places = PlaceMemory.placesInWindow(record, now)
        if (places.size < PlaceMemory.MIN_PLACES) return emptyList()
        return places.map { it.lat to it.lon }
    }

    private fun recordFor(device: Sighting, fleets: List<Fleet>, mineKeys: Set<String>): PlaceMemory.Record? {
        if (device.key in mineKeys) return null
        val names = HashMap<String, String>(fleets.size)
        for (fleet in fleets) names[fleet.id] = fleet.name
        val id = PlaceMemory.accept(device, names, mineKeys)
        synchronized(lock) {
            if (id != null) records[id]?.let { return it }
            return records.values.firstOrNull { it.deviceKey == device.key }
        }
    }

    fun multiPlaceKeys(now: Long, mineKeys: Set<String>): Set<String> {
        val minute = now / 60_000L
        val mineStamp = mineKeys.hashCode()
        synchronized(lock) {
            if (cacheGen == generation && cacheMinute == minute && cacheMine == mineStamp) {
                return cacheKeys
            }
            val keys = HashSet<String>()
            for (record in records.values) {
                if (record.deviceKey in mineKeys) continue
                if (PlaceMemory.placeCount(record, now) >= PlaceMemory.MIN_PLACES) {
                    keys += record.deviceKey
                }
            }
            cacheGen = generation
            cacheMinute = minute
            cacheMine = mineStamp
            cacheKeys = keys
            return keys
        }
    }

    fun snapshot(): List<PlaceMemory.Record> = synchronized(lock) { records.values.toList() }

    fun flush() {
        val now = System.currentTimeMillis()
        val copy = synchronized(lock) {
            val due = dirty || (soft && now - lastFlushAt >= SOFT_FLUSH_MS)
            if (!due) return
            dirty = false
            soft = false
            lastFlushAt = now
            records.values.toList()
        }
        try {
            val text = json.encodeToString(PlaceFile(records = copy))
            val dir = file.parentFile
            if (dir != null && !dir.isDirectory) dir.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.writeText(text)
                tmp.delete()
            }
        } catch (err: Exception) {
            synchronized(lock) { dirty = true }
            throw err
        }
    }

    /** Over the cap, a radio at one stop goes before a radio at two or more. Oldest first within each. */
    private fun trimLocked(now: Long) {
        if (records.size <= PlaceMemory.MAX_RECORDS) return
        val drop = records.values
            .sortedWith(
                compareBy<PlaceMemory.Record> { PlaceMemory.placeCount(it, now) >= PlaceMemory.MIN_PLACES }
                    .thenBy { it.lastHeardAt },
            )
            .take(records.size - PlaceMemory.MAX_RECORDS)
        for (record in drop) {
            if (now - record.lastHeardAt >= 0L) records.remove(record.id)
        }
    }

    /** A new radio, a new or newly confirmed stop, a new day, or a warning. */
    private fun isStructural(prev: PlaceMemory.Record?, next: PlaceMemory.Record): Boolean {
        if (prev == null) return true
        if (prev.places.size != next.places.size) return true
        if (prev.days != next.days || prev.alertedDay != next.alertedDay) return true
        if (prev.returnAt != next.returnAt || prev.label != next.label) return true
        val confirmedBefore = prev.places.count { it.count >= PlaceMemory.CONFIRM_HITS }
        return confirmedBefore != next.places.count { it.count >= PlaceMemory.CONFIRM_HITS }
    }

    private companion object {
        /** Wide Places changes on nearly every scan. Counters wait up to five minutes. */
        const val SOFT_FLUSH_MS = 5 * 60_000L
    }

    @Serializable
    private data class PlaceFile(
        val format: String = PlaceMemory.FORMAT,
        val records: List<PlaceMemory.Record> = emptyList(),
    )
}
