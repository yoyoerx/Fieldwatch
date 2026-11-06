package app.fieldwatch.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleAddressTypeTest {
    @Test
    fun theReportedAddressTypeBeatsTheWifiBitForBluetooth() {
        // E0 has the Wi-Fi local bit clear and FE has it set. Both are random Bluetooth addresses.
        assertFalse(MacUtil.isRandomized("E0:FB:1D:EA:7C:18"))
        assertTrue(MacUtil.isRandomFor(RadioKind.BLE, "E0:FB:1D:EA:7C:18", "Random"))
        assertFalse(MacUtil.isRandomFor(RadioKind.BLE, "FE:00:68:6D:61:5A", "Public"))
        assertTrue(MacUtil.isRandomFor(RadioKind.BLE, "FE:00:68:6D:61:5A", null))
        // Wi-Fi keeps its own rule.
        assertTrue(MacUtil.isRandomFor(RadioKind.WIFI, "FE:00:68:6D:61:5A", "Public"))
    }
}
