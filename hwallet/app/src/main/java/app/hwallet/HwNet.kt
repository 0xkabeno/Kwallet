package app.hwallet

import android.content.Context
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.buffer
import java.util.concurrent.TimeUnit

/**
 * v1.4: one network stack for all of Hwallet. The page's requests (HwNative.http), the live service's requests and
 * WebSockets and, while a cap is set, the page's remote images all go through this client, so the Settings
 * "Data speed limit" (#53) is a single shared limit. The limit is saved and applied at once (no restart).
 */
object HwNet {
    const val PREF = "hw_net"
    val rate = HwRate()
    @Volatile private var loaded = false

    fun load(c: Context) {
        if (loaded) return
        loaded = true
        try { rate.setRate(kbps(c).toLong() * 1024L) } catch (_: Throwable) {}
    }

    /** KB/s, 0 = unlimited */
    fun kbps(c: Context): Int = try { c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt("kbps", 0) } catch (_: Throwable) { 0 }

    fun setKbps(c: Context, kb: Int) {
        val v = if (kb <= 0) 0 else kb.coerceAtMost(1_000_000)
        try { c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt("kbps", v).apply() } catch (_: Throwable) {}
        rate.setRate(v * 1024L)
        loaded = true
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS).retryOnConnectionFailure(true)
            .dispatcher(Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 12 })
            .addInterceptor(RateInterceptor(rate))
            .build()
    }

    /** the same client with the caller's timeouts (shares the connection pool and the limit) */
    fun withTimeout(ms: Int): OkHttpClient {
        val t = ms.coerceIn(1000, 30000).toLong()
        return client.newBuilder().connectTimeout(t, TimeUnit.MILLISECONDS).readTimeout(t, TimeUnit.MILLISECONDS).build()
    }

    /** WebSocket frames: charged on the reader / sender thread, which also slows the socket itself (TCP back-pressure) */
    fun wsIn(n: Int) { rate.take(n + 6) }
    fun wsOut(n: Int) { rate.take(n + 8) }
}

/** charges request headers + bodies and response bodies to the shared limit */
class RateInterceptor(private val rate: HwRate) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val ws = req.header("Upgrade") != null
        rate.take((req.url.toString().length + 160).toLong())
        val body = req.body
        val r2 = if (body != null && !ws) req.newBuilder().method(req.method, RateRequestBody(body, rate)).build() else req
        val res = chain.proceed(r2)
        if (ws || res.code == 101) return res
        val rb = res.body ?: return res
        rate.take(200)
        return res.newBuilder().body(RateResponseBody(rb, rate)).build()
    }
}

class RateResponseBody(private val b: ResponseBody, private val rate: HwRate) : ResponseBody() {
    private val src: BufferedSource by lazy { RateSource(b.source(), rate).buffer() }
    override fun contentType(): MediaType? = b.contentType()
    override fun contentLength(): Long = b.contentLength()
    override fun source(): BufferedSource = src
}

class RateRequestBody(private val b: RequestBody, private val rate: HwRate) : RequestBody() {
    override fun contentType(): MediaType? = b.contentType()
    override fun contentLength(): Long = b.contentLength()
    override fun writeTo(sink: BufferedSink) {
        val buf = Buffer()
        b.writeTo(buf)
        while (!buf.exhausted()) {
            val n = minOf(buf.size, rate.chunk().toLong())
            rate.take(n)
            sink.write(buf, n)
        }
    }
}
