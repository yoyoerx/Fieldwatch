package app.fieldwatch.domain

import app.fieldwatch.data.PlaceStore
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneOffset

/** Coincidence tracking: wider Places, the Wi-Fi spread rule, days, score, likely Mine, and the stops warning. */
class CoincidenceTest {
    private val zone = ZoneOffset.UTC
    private val t0 = Instant.parse("2026-06-15T12:00:00Z").toEpochMilli()
    private val day0 = Instant.ofEpochMilli(t0).atZone(zone).toLocalDate().toEpochDay()
    private val names = mapOf("tile" to "Tile")

    @Test
    fun wideAcceptsFixedBluetoothAndWifiButNotRotatingOrMine() {
        val car = radio(RadioKind.BLE, "00:1A:7D:DA:71:13")
        val ap = radio(RadioKind.WIFI, "A4:91:B1:00:00:01")
        val rotating = radio(RadioKind.BLE, "D2:5C:3A:C0:9A:5B")
        assertNull(PlaceMemory.accept(car, names, emptySet()))
        assertNull(PlaceMemory.accept(ap, names, emptySet()))
        assertEquals("ble:${car.mac}", PlaceMemory.accept(car, names, emptySet(), wide = true))
        assertEquals("wifi:${ap.mac}", PlaceMemory.accept(ap, names, emptySet(), wide = true))
        assertNull(PlaceMemory.accept(rotating, names, emptySet(), wide = true))
        assertNull(PlaceMemory.accept(car, names, setOf(car.key), wide = true))
        assertEquals(PlaceMemory.KIND_BLE, PlaceMemory.kindOf("ble:${car.mac}", car, names))
        assertEquals(PlaceMemory.KIND_WIFI, PlaceMemory.kindOf("wifi:${ap.mac}", ap, names))
        val tile = radio(RadioKind.BLE, "00:11:22:33:44:55", setOf("tile"))
        assertEquals(PlaceMemory.KIND_TAG, PlaceMemory.kindOf("ble:${tile.mac}", tile, names))
    }

    @Test
    fun aWifiAccessPointNeedsStopsFiveHundredMetersApart() {
        val id = "wifi:A4:91:B1:00:00:01"
        val near = record(id, listOf(home, north(home, 300.0)))
        assertEquals(1, PlaceMemory.placeCount(near, t0 + 4 * HOUR))
        val far = record(id, listOf(home, north(home, 800.0)))
        assertEquals(2, PlaceMemory.placeCount(far, t0 + 4 * HOUR))
        // Bluetooth keeps the ordinary 100 m rule.
        val ble = record("ble:00:1A:7D:DA:71:13", listOf(home, north(home, 300.0)))
        assertEquals(2, PlaceMemory.placeCount(ble, t0 + 4 * HOUR))
    }

    @Test
    fun daysAndAverageSignalAreKept() {
        var rec: PlaceMemory.Record? = null
        for (d in 0..2) rec = visit(rec, "ble:00:1A:7D:DA:71:13", home, t0 + d * DAY, rssi = -50 - d * 10)
        val record = rec!!
        assertEquals(listOf(day0, day0 + 1, day0 + 2), record.days)
        assertEquals(3, PlaceMemory.daysSince(record, day0))
        assertEquals(1, PlaceMemory.daysSince(record, day0 + 2))
        assertEquals(-60, PlaceMemory.averageRssi(record.places))
    }

    @Test
    fun anOldRecordWithNoKindReadsAsATag() {
        val json = Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString(
            PlaceMemory.Record.serializer(),
            """{"id":"ble:00:11:22:33:44:55","deviceKey":"BLE:00:11:22:33:44:55"}""",
        )
        assertEquals(PlaceMemory.KIND_TAG, PlaceMemory.kindOf(old))
        assertEquals(PlaceReport.Kind.TAG, PlaceReport.kindOf(old))
        assertTrue(old.days.isEmpty())
    }

    @Test
    fun scoreWeighsDaysOverStopsAndLoudnessOnlyBreaksTies() {
        assertTrue(PlaceReport.score(3, 1, -90) > PlaceReport.score(2, 1, -40))
        assertTrue(PlaceReport.score(2, 3, -90) > PlaceReport.score(3, 1, -90))
        assertTrue(PlaceReport.score(2, 1, -55) > PlaceReport.score(2, 1, -80))
    }

    @Test
    fun todayLeavesOutYesterdaysStops() {
        val id = "ble:00:1A:7D:DA:71:13"
        var rec = visit(null, id, home, t0 - DAY)
        rec = visit(rec, id, store, t0 - DAY + HOUR)
        rec = visit(rec, id, park, t0 + HOUR)
        val now = t0 + 2 * HOUR
        val week = rows(listOf(rec), now, now - PlaceMemory.WINDOW_MS)
        assertEquals(3, week.single().count)
        assertEquals(2, week.single().days)
        val today = rows(listOf(rec), now, PlaceReport.todayStart(now, zone))
        assertTrue(today.isEmpty())
    }

    @Test
    fun aRadioAtEveryStopAtStrongSignalIsLikelyMine() {
        val stops = listOf(home, store, park)
        val mineLike = record("ble:00:1A:7D:DA:71:13", stops, rssi = -48)
        val passerby = record("ble:00:1A:7D:DA:71:14", stops, rssi = -85)
        val partial = record("ble:00:1A:7D:DA:71:15", listOf(home, store), rssi = -48)
        val rows = rows(listOf(mineLike, passerby, partial), t0 + 6 * HOUR, t0 - DAY)
        val byKey = rows.associateBy { it.deviceKey }
        assertTrue(byKey.getValue(mineLike.deviceKey).likelyMine)
        assertFalse(byKey.getValue(passerby.deviceKey).likelyMine)
        assertFalse(byKey.getValue(partial.deviceKey).likelyMine)
        val summary = PlaceReport.summary(rows, listOf(mineLike, passerby, partial), emptyList(), t0 - DAY)
        assertEquals(3, summary.stops)
        assertEquals(1, summary.likelyMine)
    }

    @Test
    fun aTagAtEveryStopIsNeverOfferedAsLikelyMine() {
        // Yours, or planted on you. Only a deliberate tap on its own page marks it.
        val tag = record("ble:00:11:22:33:44:55", listOf(home, store, park), rssi = -48)
            .copy(kind = PlaceMemory.KIND_TAG)
        val row = rows(listOf(tag), t0 + 6 * HOUR, t0 - DAY).single()
        assertEquals(PlaceReport.Kind.TAG, row.kind)
        assertFalse(row.likelyMine)
    }

    @Test
    fun mineRadiosLeaveTheListButStillCountAsStops() {
        val mine = record("ble:00:1A:7D:DA:71:13", listOf(home, store, park))
        val other = record("ble:00:1A:7D:DA:71:14", listOf(home, store))
        val watch = listOf(WatchTarget(id = "w", deviceKey = mine.deviceKey, label = "My watch", mine = true))
        val rows = PlaceReport.rows(
            records = listOf(mine, other),
            devices = emptyList(),
            fleets = emptyList(),
            watchlist = watch,
            now = t0 + 6 * HOUR,
            zone = zone,
            demo = false,
            streets = emptyMap(),
            since = t0 - DAY,
        )
        assertEquals(listOf(other.deviceKey), rows.map { it.deviceKey })
        assertEquals(3, PlaceReport.summary(rows, listOf(mine, other), watch, t0 - DAY).stops)
    }

    @Test
    fun phoneStopsMergeNearbyPlacesAcrossRadios() {
        val a = record("ble:00:1A:7D:DA:71:13", listOf(home, store))
        val b = record("ble:00:1A:7D:DA:71:14", listOf(north(home, 40.0), park))
        assertEquals(3, PlaceReport.phoneStops(listOf(a, b), emptySet(), 0L))
        // Either side of a grid line, and west of the prime meridian.
        val west = 51.4995 to -0.0001
        val east = 51.4995 to 0.0002
        val c = record("ble:00:1A:7D:DA:71:15", listOf(west))
        val d = record("ble:00:1A:7D:DA:71:16", listOf(east))
        assertEquals(1, PlaceReport.phoneStops(listOf(c, d), emptySet(), 0L))
    }

    @Test
    fun theStoreWarnsOnceADayWhenARadioReachesTheStops() {
        val file = File.createTempFile("places", ".json").apply { deleteOnExit() }
        val places = PlaceStore(file)
        val now = System.currentTimeMillis()
        // Keep every stop on the phone's today, even just after midnight.
        val base = maxOf(now - 70 * MINUTE, PlaceReport.todayStart(now, java.time.ZoneId.systemDefault()) + MINUTE)
        val car = radio(RadioKind.BLE, "00:1A:7D:DA:71:13")
        val stops = listOf(home, store, park)
        val all = ArrayList<PlaceMemory.Record>()
        stops.forEachIndexed { i, where ->
            // Two hears per stop confirm it. Stops are spaced past the quiet gap.
            val at = base + i * 20 * MINUTE
            for (hit in 0..1) {
                val heard = car.copy(lastSeen = at + hit * MINUTE, latitude = where.first, longitude = where.second)
                all += places.hear(listOf(heard), emptyList(), emptyList(), null, null, wide = true, alertStops = 3)
            }
        }
        assertEquals(1, all.size)
        assertEquals(car.key, all.single().deviceKey)
        val again = car.copy(lastSeen = base + 41 * MINUTE, latitude = park.first, longitude = park.second)
        assertTrue(places.hear(listOf(again), emptyList(), emptyList(), null, null, wide = true, alertStops = 3).isEmpty())
        // Not wide: an ordinary Bluetooth radio is not kept at all.
        val narrow = PlaceStore(File.createTempFile("places", ".json").apply { deleteOnExit() })
        assertTrue(narrow.hear(listOf(again), emptyList(), emptyList(), null, null, alertStops = 1).isEmpty())
        assertTrue(narrow.snapshot().isEmpty())
    }

    private fun rows(records: List<PlaceMemory.Record>, now: Long, since: Long) = PlaceReport.rows(
        records = records,
        devices = emptyList(),
        fleets = emptyList(),
        watchlist = emptyList(),
        now = now,
        zone = zone,
        demo = false,
        streets = emptyMap(),
        since = since,
    )

    /** One stop per entry, an hour apart, two hears each. */
    private fun record(id: String, stops: List<Pair<Double, Double>>, rssi: Int = -60): PlaceMemory.Record {
        var rec: PlaceMemory.Record? = null
        stops.forEachIndexed { i, where -> rec = visit(rec, id, where, t0 + i * HOUR, rssi) }
        return rec!!
    }

    private fun visit(
        prev: PlaceMemory.Record?,
        id: String,
        where: Pair<Double, Double>,
        at: Long,
        rssi: Int = -60,
    ): PlaceMemory.Record {
        val first = PlaceMemory.note(prev, hear(id, at, where, rssi))
        return PlaceMemory.note(first, hear(id, at + MINUTE, where, rssi))
    }

    private fun hear(id: String, at: Long, where: Pair<Double, Double>, rssi: Int) = PlaceMemory.Hear(
        id = id,
        deviceKey = "BLE:" + id.substringAfter(':'),
        at = at,
        lat = where.first,
        lon = where.second,
        sitId = null,
        sitLabel = null,
        localDay = Instant.ofEpochMilli(at).atZone(zone).toLocalDate().toEpochDay(),
        rssi = rssi,
        kind = if (id.startsWith("wifi:")) PlaceMemory.KIND_WIFI else PlaceMemory.KIND_BLE,
    )

    private fun north(from: Pair<Double, Double>, meters: Double) = (from.first + meters / 111_320.0) to from.second

    private fun radio(kind: RadioKind, mac: String, fleetIds: Set<String> = emptySet()) = Sighting(
        key = "${kind.name}:$mac",
        kind = kind,
        mac = mac,
        name = "",
        rssi = -60,
        rssiMin = -70,
        rssiMax = -50,
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
        firstSeen = t0,
        lastSeen = t0,
        hitCount = 1,
        fleetIds = fleetIds,
        rssiHistory = emptyList(),
        presence = emptyList(),
    )

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
        val home = 38.0 to -77.0
        val store = 38.01 to -77.0
        val park = 38.02 to -77.0
    }
}
