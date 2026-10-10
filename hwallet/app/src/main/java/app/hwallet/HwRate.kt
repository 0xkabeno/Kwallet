package app.hwallet

import okio.Buffer
import okio.ForwardingSource
import okio.Source

/**
 * v1.4 (#53): Settings > Data speed limit. ONE token bucket shared by every byte Hwallet moves over the network
 * (HTTP requests and responses, WebSocket frames, remote images in the page). The rate is in bytes per second,
 * 0 = unlimited. Callers charge the bytes they move; when the shared budget is spent the calling thread waits,
 * so all traffic together never goes faster than the cap. Requests are never refused, only slowed down.
 *
 * Debt model: the bucket may go below zero; each caller waits until its own debt is repaid at the current rate,
 * so N threads moving B bytes each take N*B/rate seconds together. A rate change applies at once: sleepers wake
 * up within 50 ms and the old debt is forgiven.
 *
 * Pure Kotlin (no Android) so it is unit tested on the JVM (HwRateTest).
 */
class HwRate(
    private val clock: () -> Long = { System.nanoTime() },
    private val sleeper: (Long) -> Unit = { ns -> if (ns > 0) Thread.sleep(ns / 1_000_000L, (ns % 1_000_000L).toInt()) }
) {
    @Volatile var bytesPerSec: Long = 0L
        private set
    @Volatile private var gen = 0
    private var tokens = 0.0
    private var last = clock()
    private val lock = Any()

    fun on(): Boolean = bytesPerSec > 0

    /** bytes per second, 0 (or less) = unlimited. Applied immediately, also to transfers in progress. */
    fun setRate(bps: Long) {
        synchronized(lock) {
            bytesPerSec = if (bps <= 0) 0L else bps
            tokens = 0.0
            last = clock()
            gen++
        }
    }

    /** how many bytes may move before the bucket runs dry (a quarter second of traffic, at least 512 bytes) */
    fun burst(): Long = if (bytesPerSec <= 0) Long.MAX_VALUE else maxOf(512L, bytesPerSec / 4)

    /** the largest piece one read or write should move at once, so a big transfer is spread smoothly */
    fun chunk(): Int = if (bytesPerSec <= 0) 65536 else minOf(65536L, maxOf(256L, bytesPerSec / 8)).toInt()

    private fun refill(now: Long) {
        val r = bytesPerSec
        if (r > 0) tokens = minOf(burst().toDouble(), tokens + (now - last).coerceAtLeast(0L) * r / 1e9)
        last = now
    }

    /** charges n bytes against the shared budget; blocks the calling thread until the budget allows them.
     *  Returns the nanoseconds it waited (0 when unlimited or within the burst). */
    fun take(n: Long): Long {
        if (n <= 0 || bytesPerSec <= 0) return 0L
        val g: Int
        val wait: Long
        synchronized(lock) {
            val r = bytesPerSec
            if (r <= 0) return 0L
            refill(clock())
            tokens -= n.toDouble()
            wait = if (tokens >= 0) 0L else ((-tokens) * 1e9 / r).toLong()
            g = gen
        }
        if (wait <= 0) return 0L
        val t0 = clock()
        val end = t0 + wait
        while (true) {
            val left = end - clock()
            if (left <= 0 || gen != g) break
            sleeper(minOf(left, 50_000_000L))
        }
        return clock() - t0
    }

    fun take(n: Int): Long = take(n.toLong())
}

/** a byte source whose reads are charged to the shared limit (response bodies) */
class RateSource(delegate: Source, private val rate: HwRate) : ForwardingSource(delegate) {
    override fun read(sink: Buffer, byteCount: Long): Long {
        val n = super.read(sink, minOf(byteCount, rate.chunk().toLong()))
        if (n > 0) rate.take(n)
        return n
    }
}
