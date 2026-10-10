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
    /** v1.5 (#67): the user's cap (bytes/s, 0 = unlimited) and whether the default network is mobile data */
    @Volatile private var userBps = 0L
    @Volatile var mobile = false
        private set
    @Volatile private var watching = false
    /** v1.5 (#67): told when the limit turns on/off with the network (the page restarts its own WebSockets) */
    @Volatile var onChange: (() -> Unit)? = null

    /** the bucket runs at the user's cap only on mobile data; Wi-Fi and Ethernet are never limited.
     *  setRate wakes transfers already waiting, so a network change applies to them at once. */
    private fun apply() { rate.setRate(HwGate.effective(userBps, mobile)) }

    fun load(c: Context) {
        if (!loaded) { loaded = true; try { userBps = kbps(c).toLong() * 1024L } catch (_: Throwable) {} }
        watch(c)
        apply()
    }

    /** v1.5 (#67): follows the default network live (ConnectivityManager default-network callback) */
    private fun watch(c0: Context) {
        if (watching) return
        try {
            val c = c0.applicationContext ?: c0
            val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            fun upd(caps: android.net.NetworkCapabilities?) {
                val m = if (caps == null) false else HwGate.limited(
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR),
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI),
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET),
                    !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
                if (m != mobile) { mobile = m; apply(); try { onChange?.invoke() } catch (_: Throwable) {} }
            }
            if (android.os.Build.VERSION.SDK_INT >= 23) try { upd(cm.getNetworkCapabilities(cm.activeNetwork)) } catch (_: Throwable) {}
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(n: android.net.Network, caps: android.net.NetworkCapabilities) { upd(caps) }
                    override fun onLost(n: android.net.Network) { upd(null) }
                })
            }
            watching = true
        } catch (_: Throwable) {}
    }

    /** KB/s, 0 = unlimited */
    fun kbps(c: Context): Int = try { c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt("kbps", 0) } catch (_: Throwable) { 0 }

    fun setKbps(c: Context, kb: Int) {
        val v = if (kb <= 0) 0 else kb.coerceAtMost(1_000_000)
        try { c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt("kbps", v).apply() } catch (_: Throwable) {}
        userBps = v * 1024L
        loaded = true
        watch(c)
        apply()
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

/** v1.5 (#67): pure gating rule (unit tested): limit only mobile data, never Wi-Fi or Ethernet */
object HwGate {
    fun limited(cellular: Boolean, wifi: Boolean, ethernet: Boolean, metered: Boolean): Boolean =
        if (wifi || ethernet) false else cellular || metered
    fun effective(userBps: Long, mobile: Boolean): Long = if (mobile && userBps > 0) userBps else 0L
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
