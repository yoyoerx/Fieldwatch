package app.fieldwatch.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RotationLinkerTest {
    private val t0 = Instant.parse("2026-06-15T12:00:00Z").toEpochMilli()

    @Test
    fun theLinkerFollowsOneRotationAndRefusesAnAmbiguousOne() {
        val linker = RotationLinker()
        var lat = 38.0
        var at = t0
        var addr = 0x40
        // One phone, rotating every 15 minutes, with this phone driving 1 km each step.
        repeat(4) {
            val mac = "%02X:11:22:33:44:55".format(addr)
            val born = at
            for (step in 0 until 15) {
                lat += 1_000.0 / 111_320.0 / 15
                linker.ingest(listOf(phone(mac, born, at)), at, lat, -77.0)
                at += MINUTE
            }
            addr++
            // The next address starts 5 s after this one's last packet.
            at = at - MINUTE + 5_000L
        }
        val chain = linker.chains(at).single()
        assertEquals(3, chain.links)
        assertTrue(chain.withYouM > 3_000.0)

        // Two old addresses went quiet together. A new one could be either, so it starts its own chain.
        val crowd = RotationLinker()
        val start = t0
        crowd.ingest(listOf(phone("40:00:00:00:00:01", start, start), phone("40:00:00:00:00:02", start, start)), start, 38.0, -77.0)
        crowd.ingest(listOf(phone("40:00:00:00:00:03", start + 10_000, start + 10_000)), start + 10_000, 38.0, -77.0)
        assertTrue(crowd.chains(start + 10_000, minLinks = 1, minSpanMs = 0, minWithYouM = 0.0).isEmpty())
    }

    @Test
    fun theSignatureIgnoresTheAddressAndKeepsTheMessageType() {
        val a = phone("40:11:22:33:44:55", t0, t0)
        val b = phone("7F:00:00:00:00:01", t0, t0)
        assertEquals(RotationLinker.signature(a), RotationLinker.signature(b))
        val other = a.copy(manufacturerDataHex = "0C0E00AABBCC")
        assertFalse(RotationLinker.signature(a) == RotationLinker.signature(other))
    }

    /** An Apple Nearby Info advertisement on a rotating address. */
    private fun phone(mac: String, firstSeen: Long, lastSeen: Long) = radio(mac, type = "Random", ageMs = 0).copy(
        manufacturerId = 0x004C,
        manufacturerDataHex = "1005031C0A1B2C",
        firstSeen = firstSeen,
        lastSeen = lastSeen,
        rssi = -62,
        randomized = true,
    )

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
    }
}
