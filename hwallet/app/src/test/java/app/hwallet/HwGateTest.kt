package app.hwallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.5 (#67): the data speed limit applies only on mobile data, never on Wi-Fi or Ethernet */
class HwGateTest {
    @Test fun cellularIsLimited() { assertTrue(HwGate.limited(cellular = true, wifi = false, ethernet = false, metered = true)) }
    @Test fun unmeteredCellularStillLimited() { assertTrue(HwGate.limited(true, false, false, false)) }
    @Test fun wifiNeverLimited() { assertFalse(HwGate.limited(false, true, false, false)) }
    @Test fun meteredWifiNeverLimited() { assertFalse(HwGate.limited(false, true, false, true)) }
    @Test fun ethernetNeverLimited() { assertFalse(HwGate.limited(false, false, true, true)) }
    @Test fun otherMeteredLimited() { assertTrue(HwGate.limited(false, false, false, true)) }
    @Test fun otherUnmeteredFree() { assertFalse(HwGate.limited(false, false, false, false)) }
    @Test fun effectiveRate() {
        assertEquals(10240L, HwGate.effective(10240L, true))
        assertEquals(0L, HwGate.effective(10240L, false))
        assertEquals(0L, HwGate.effective(0L, true))
    }
    /** a transfer waiting under the mobile cap is released when the phone moves to Wi-Fi */
    @Test fun switchToWifiReleasesWaiters() {
        var now = 0L; var sleeps = 0
        val r = HwRate(clock = { now }, sleeper = { ns -> now += ns; if (++sleeps == 2) { r0!!.setRate(HwGate.effective(1024L, false)) } })
        r0 = r
        r.setRate(HwGate.effective(1024L, true))
        r.take(1024L * 1024L)   // would take ~17 min at 1 KB/s
        assertTrue("released early, waited ${now / 1_000_000} ms", now < 1_000_000_000L)
        assertFalse(r.on())
    }
    companion object { var r0: HwRate? = null }
}
