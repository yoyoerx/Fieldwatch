package app.fieldwatch.domain

import app.fieldwatch.data.PlaceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneOffset

/** Stability by behaviour, the 250 m stop rule, Known not mine, and one-off scoring. */
class CoincidenceExtrasTest {
    private val zone = ZoneOffset.UTC
    private val t0 = Instant.parse("2026-06-15T12:00:00Z").toEpochMilli()

    @Test
    fun aRandomAddressCountsOnlyAfterItOutlivesARotation() {
        val young = radio("E0:FB:1D:EA:7C:18", type = "Random", ageMs = 5 * MINUTE)
        val old = radio("E0:FB:1D:EA:7C:18", type = "Random", ageMs = 25 * MINUTE)
        val public = radio("FE:00:68:6D:61:5A", type = "Public", ageMs = 0)
        assertNull(PlaceMemory.accept(young, emptyMap(), emptySet(), wide = true))
        assertEquals("ble:${old.mac}", PlaceMemory.accept(old, emptyMap(), emptySet(), wide = true))
        assertEquals("ble:${public.mac}", PlaceMemory.accept(public, emptyMap(), emptySet(), wide = true))
    }

    @Test
    fun aTagIsKeptOnceItOutlivesARotationWhateverItsAddressBitsSay() {
        // Upstream dropped this one for good: the Wi-Fi local bit is set on FE.
        val names = mapOf("tile" to "Tile")
        val fresh = radio("FE:00:68:6D:61:5A", type = "Random", ageMs = 0, fleetIds = setOf("tile"))
        assertNull(PlaceMemory.accept(fresh, names, emptySet()))
        val held = radio("FE:00:68:6D:61:5A", type = "Random", ageMs = 25 * MINUTE, fleetIds = setOf("tile"))
        assertEquals("ble:${held.mac}", PlaceMemory.accept(held, names, emptySet()))
    }

    @Test
    fun gpsDriftInsideTwoHundredFiftyMetersIsTheSameStop() {
        val id = "ble:00:1A:7D:DA:71:13"
        var rec = visit(null, id, home, t0)
        // Back after lunch, with the indoor fix 180 m off.
        rec = visit(rec, id, north(home, 180.0), t0 + 2 * HOUR)
        assertEquals(1, PlaceMemory.placeCount(rec, t0 + 3 * HOUR))
        rec = visit(rec, id, north(home, 400.0), t0 + 4 * HOUR)
        assertEquals(2, PlaceMemory.placeCount(rec, t0 + 5 * HOUR))
    }

    @Test
    fun knownRadiosLeaveTheReportAndNeverWarn() {
        val coworker = record("ble:00:1A:7D:DA:71:13", listOf(home to t0, errandA to t0 + HOUR))
        val watch = listOf(WatchTarget(id = "w", deviceKey = coworker.deviceKey, label = "Sam", familiar = true))
        assertTrue(rows(listOf(coworker), watch).isEmpty())
        val shown = rows(listOf(coworker), watch, showFamiliar = true).single()
        assertTrue(shown.familiar)
        assertFalse(shown.likelyMine)
        val summary = PlaceReport.summary(emptyList(), listOf(coworker), watch, t0 - DAY, t0 + 2 * HOUR, zone)
        assertEquals(1, summary.familiar)

        val store = PlaceStore(File.createTempFile("places", ".json").apply { deleteOnExit() })
        val now = System.currentTimeMillis()
        val base = maxOf(now - 70 * MINUTE, PlaceReport.todayStart(now, java.time.ZoneId.systemDefault()) + MINUTE)
        val car = radio("00:1A:7D:DA:71:13", type = "Public", ageMs = 0)
        val crossed = ArrayList<PlaceMemory.Record>()
        listOf(home, errandA, errandB).forEachIndexed { i, where ->
            for (hit in 0..1) {
                val at = base + i * 20 * MINUTE + hit * MINUTE
                val heard = car.copy(lastSeen = at, latitude = where.first, longitude = where.second)
                crossed += store.hear(listOf(heard), emptyList(), watch, null, null, wide = true, alertStops = 2)
            }
        }
        assertTrue(crossed.isEmpty())
        assertEquals(3, PlaceMemory.placeCount(store.snapshot().single(), base + HOUR))
    }

    @Test
    fun theKnownMarkClearsMineAndTheTwoDoNotStack() {
        val start = listOf(WatchTarget(id = "w", deviceKey = "BLE:00:1A:7D:DA:71:13", label = "x", mine = true))
        val known = RadioBookmarks.setFamiliar(start, "BLE:00:1A:7D:DA:71:13", true, "x")
        assertTrue(known.single().familiar)
        assertFalse(known.single().mine)
        assertEquals(setOf("BLE:00:1A:7D:DA:71:13"), RadioBookmarks.familiarKeys(known))
        assertTrue(RadioBookmarks.familiarKeys(start).isEmpty())
    }

    @Test
    fun oneOffStopsOutrankHomeAndTheOffice() {
        // This phone is at home and the office on two days, and runs two errands once.
        val routineRadio = record(
            "ble:00:1A:7D:DA:71:13",
            listOf(home to t0, office to t0 + HOUR, home to t0 + DAY, office to t0 + DAY + HOUR),
        )
        val errandRadio = record("ble:00:1A:7D:DA:71:14", listOf(errandA to t0 + 3 * HOUR, errandB to t0 + 4 * HOUR))
        val out = rows(listOf(routineRadio, errandRadio), emptyList(), now = t0 + DAY + 2 * HOUR)
        val byKey = out.associateBy { it.deviceKey }
        assertEquals(0, byKey.getValue(routineRadio.deviceKey).oneOff)
        assertEquals(2, byKey.getValue(errandRadio.deviceKey).oneOff)
        assertEquals(errandRadio.deviceKey, out.first().deviceKey)
        val summary = PlaceReport.summary(out, listOf(routineRadio, errandRadio), emptyList(), t0 - DAY, t0 + DAY + 2 * HOUR, zone)
        assertEquals(4, summary.stops)
        assertEquals(2, summary.routine)
    }

    private fun rows(
        records: List<PlaceMemory.Record>,
        watch: List<WatchTarget>,
        showFamiliar: Boolean = false,
        now: Long = t0 + 6 * HOUR,
    ) = PlaceReport.rows(
        records = records,
        devices = emptyList(),
        fleets = emptyList(),
        watchlist = watch,
        now = now,
        zone = zone,
        demo = false,
        streets = emptyMap(),
        since = t0 - DAY,
        showFamiliar = showFamiliar,
    )

    private fun record(id: String, stops: List<Pair<Pair<Double, Double>, Long>>): PlaceMemory.Record {
        var rec: PlaceMemory.Record? = null
        for ((where, at) in stops) rec = visit(rec, id, where, at)
        return rec!!
    }

    private fun visit(prev: PlaceMemory.Record?, id: String, where: Pair<Double, Double>, at: Long): PlaceMemory.Record {
        val first = PlaceMemory.note(prev, hear(id, at, where))
        return PlaceMemory.note(first, hear(id, at + MINUTE, where))
    }

    private fun hear(id: String, at: Long, where: Pair<Double, Double>) = PlaceMemory.Hear(
        id = id,
        deviceKey = "BLE:" + id.substringAfter(':'),
        at = at,
        lat = where.first,
        lon = where.second,
        sitId = null,
        sitLabel = null,
        localDay = Instant.ofEpochMilli(at).atZone(zone).toLocalDate().toEpochDay(),
        rssi = -70,
        kind = PlaceMemory.KIND_BLE,
    )

    private fun north(from: Pair<Double, Double>, meters: Double) = (from.first + meters / 111_320.0) to from.second

    private fun radio(mac: String, type: String?, ageMs: Long, fleetIds: Set<String> = emptySet()) = Sighting(
        key = "BLE:$mac",
        kind = RadioKind.BLE,
        mac = mac,
        name = "",
        rssi = -60,
        rssiMin = -70,
        rssiMax = -50,
        channel = 0,
        frequencyMhz = 0,
        vendor = null,
        randomized = MacUtil.isRandomFor(RadioKind.BLE, mac, type),
        hiddenSsid = false,
        serviceUuids = emptyList(),
        manufacturerId = null,
        manufacturerDataHex = "",
        rawHex = "",
        extras = "",
        firstSeen = t0,
        lastSeen = t0 + ageMs,
        hitCount = 1,
        fleetIds = fleetIds,
        rssiHistory = emptyList(),
        presence = emptyList(),
        facts = RadioFacts(addressType = type),
    )

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR
        val home = 38.0 to -77.0
        val office = 38.05 to -77.0
        val errandA = 38.01 to -77.02
        val errandB = 38.02 to -77.04
    }
}
