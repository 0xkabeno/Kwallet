package app.hwallet

import okio.Buffer
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** v1.4 (#53): the shared data speed limit */
class HwRateTest {
    /** a fake clock that moves only when the limiter sleeps: exact, instant tests */
    private class Fake {
        var now = 0L
        val rate = HwRate(clock = { now }, sleeper = { ns -> now += ns })
    }

    @Test fun unlimitedNeverWaits() {
        val f = Fake()
        repeat(1000) { assertEquals(0L, f.rate.take(65536)) }
        assertEquals(0L, f.now)
        assertTrue(!f.rate.on())
    }

    @Test fun oneKilobytePerSecond() {
        val f = Fake(); f.rate.setRate(1024)
        // 10 KB in 100-byte pieces at 1 KB/s: about 10 s of (simulated) time
        repeat(100) { f.rate.take(100) }
        val sec = f.now / 1e9
        assertTrue("took $sec s", sec in 9.0..10.2)
    }

    @Test fun anySpeedCanBeChosen() {
        for (kb in listOf(1L, 5L, 10L, 23L, 100L, 777L)) {
            val f = Fake(); f.rate.setRate(kb * 1024)
            val total = kb * 1024 * 8          // 8 seconds of traffic at that speed
            var moved = 0L
            while (moved < total) { val n = minOf(f.rate.chunk().toLong(), total - moved); f.rate.take(n); moved += n }
            val sec = f.now / 1e9
            assertTrue("$kb KB/s took $sec s", sec in 7.5..8.2)
        }
    }

    @Test fun chunksStaySmallAtLowSpeeds() {
        val f = Fake()
        f.rate.setRate(1024); assertEquals(256, f.rate.chunk())
        f.rate.setRate(100 * 1024); assertEquals(12800, f.rate.chunk())
        f.rate.setRate(0); assertEquals(65536, f.rate.chunk())
    }

    @Test fun sharedAcrossThreads() {
        // real clock: 4 threads x 10 000 bytes at 20 000 B/s share one budget -> about 2 s together, not 0.5 s each
        val rate = HwRate(); rate.setRate(20_000)
        val pool = Executors.newFixedThreadPool(4)
        val t0 = System.nanoTime()
        repeat(4) { pool.execute { var left = 10_000; while (left > 0) { val n = minOf(500, left); rate.take(n); left -= n } } }
        pool.shutdown(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        val sec = (System.nanoTime() - t0) / 1e9
        assertTrue("took $sec s", sec in 1.4..2.8)
    }

    @Test fun raisingTheLimitAppliesAtOnce() {
        // a transfer waiting on a 1 KB/s cap is released within moments when the user picks Unlimited
        val rate = HwRate(); rate.setRate(1024)
        val waited = AtomicLong(-1)
        val th = Thread { waited.set(rate.take(100_000)) }   // would wait about 97 s at 1 KB/s
        th.start(); Thread.sleep(150)
        rate.setRate(0)
        th.join(2000)
        assertTrue("still waiting", !th.isAlive)
        assertTrue("waited ${waited.get() / 1e6} ms", waited.get() in 0L..1_000_000_000L)
    }

    @Test fun responseBodiesAreLimited() {
        val f = Fake(); f.rate.setRate(2048)
        val src = Buffer().write(ByteArray(10 * 1024))
        val body = RateSource(src, f.rate).buffer()
        val bytes = body.readByteArray()
        assertEquals(10 * 1024, bytes.size)
        val sec = f.now / 1e9
        assertTrue("took $sec s", sec in 4.5..5.2)
    }
}
