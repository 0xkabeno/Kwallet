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
import okhttp3.Dns
import okhttp3.dnsoverhttps.DnsOverHttps
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
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
        try { HwVpn.load(c) } catch (_: Throwable) {}
    }
    @Volatile private var lastNet: android.net.Network? = null
    @Volatile private var netLost = false

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
                    override fun onCapabilitiesChanged(n: android.net.Network, caps: android.net.NetworkCapabilities) {
                        upd(caps)
                        /* v1.9.19: a different default network (Wi-Fi <-> mobile, back from airplane mode): fresh VPN tunnel */
                        if (n != lastNet) { val had = lastNet != null || netLost; lastNet = n; netLost = false; if (had) try { HwVpn.networkChanged() } catch (_: Throwable) {} }
                    }
                    override fun onLost(n: android.net.Network) { upd(null); if (n == lastNet) { lastNet = null; netLost = true } }
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

    /* ---------- v1.9.19: VPN (HwVpn) and Secure DNS ----------
       One client, as before. Instead of rebuilding it, its proxy and DNS are live objects that read the VPN state on every
       new connection, so every client derived from it (withTimeout(...), the copies some classes keep) follows at once:
       VPN on  -> SOCKS5 127.0.0.1:<port> for every request and WebSocket (the exit side resolves names: no DNS leak),
       VPN off -> the old path (the system ProxySelector, usually direct), with DoH (Secure DNS) or the system DNS.
       Kill switch: the first interceptor refuses every request while the VPN is on and not connected. */
    private val loop: InetAddress = InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1))
    /** with the VPN on: always exactly the local proxy, never an empty list (OkHttp would go direct). Off: what OkHttp
     *  used before (the system's ProxySelector.getDefault(), i.e. a Wi-Fi proxy if the user set one) */
    private val socks = object : ProxySelector() {
        override fun select(uri: URI?): MutableList<Proxy> {
            if (HwVpn.on) return mutableListOf(Proxy(Proxy.Type.SOCKS, InetSocketAddress(loop, HwVpn.port.coerceAtLeast(1))))
            return try { ProxySelector.getDefault()?.select(uri)?.toMutableList() ?: mutableListOf(Proxy.NO_PROXY) } catch (_: Throwable) { mutableListOf(Proxy.NO_PROXY) }
        }
        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
            if (!HwVpn.on) try { ProxySelector.getDefault()?.connectFailed(uri, sa, ioe) } catch (_: Throwable) {}
        }
    }
    private val kill = Interceptor { chain ->
        if (HwVpn.blocked()) throw IOException("VPN reconnecting")
        chain.proceed(chain.request())
    }
    @Volatile private var doh: Dns? = null
    @Volatile private var dohFor = ""
    private val dnsLive = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val d = dohNow() ?: return Dns.SYSTEM.lookup(hostname)
            return try { d.lookup(hostname) } catch (e: java.net.UnknownHostException) {
                /* VPN off and the DoH server unreachable on this network: the system resolver (as before 1.9.19), so the
                   wallet keeps working where Quad9 / Mullvad is blocked. With the VPN on, DNS never reaches this point. */
                if (HwVpn.on) throw e
                Dns.SYSTEM.lookup(hostname)
            }
        }
    }
    private fun dohNow(): Dns? {
        val want = HwVpn.dns
        if (want == "off") return null
        val have = doh
        if (have != null && dohFor == want) return have
        synchronized(this) {
            if (doh != null && dohFor == want) return doh
            val d = try {
                /* the bootstrap client: its own small client with the same live proxy + kill switch (DoH goes through the tunnel when on) */
                val boot = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
                    .proxySelector(socks).addInterceptor(kill).build()
                if (want == "mullvad") DnsOverHttps.Builder().client(boot).url("https://dns.mullvad.net/dns-query".toHttpUrl())
                    .bootstrapDnsHosts(InetAddress.getByName("194.242.2.2"), InetAddress.getByName("2a07:e340::2")).build()
                else DnsOverHttps.Builder().client(boot).url("https://dns.quad9.net/dns-query".toHttpUrl())
                    .bootstrapDnsHosts(InetAddress.getByName("9.9.9.9"), InetAddress.getByName("149.112.112.112"), InetAddress.getByName("2620:fe::fe"), InetAddress.getByName("2620:fe::9")).build()
            } catch (_: Throwable) { null }
            doh = d; dohFor = want
            return d
        }
    }
    /** Secure DNS changed: new lookups use it at once; pooled connections (made with the old resolver) are dropped */
    fun dnsChanged() { doh = null; dohFor = ""; try { client.connectionPool.evictAll() } catch (_: Throwable) {} }
    /** told by HwVpn when the route changes (on/off, a new port, connected/reconnecting) */
    @Volatile private var wasOn = false
    fun vpnChanged() {
        try { client.connectionPool.evictAll() } catch (_: Throwable) {}
        val now = HwVpn.on
        if (now != wasOn) {
            wasOn = now
            /* on <-> off: nothing still running on the old path (direct while turning on) may finish there */
            try { client.dispatcher.cancelAll() } catch (_: Throwable) {}
            /* an HTTP/2 connection that carried a cancelled call goes back to the pool idle: drop those too (turning on, the
               kill switch holds every call until the tunnel is connected, which takes longer than this) */
            Thread({ for (d in longArrayOf(300, 1500)) { try { Thread.sleep(d); client.connectionPool.evictAll() } catch (_: Throwable) {} } }, "hwnet-evict").apply { isDaemon = true }.start()
            try { onChange?.invoke() } catch (_: Throwable) {}
        }
    }

    val client: OkHttpClient by lazy {
        wasOn = HwVpn.on
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS).retryOnConnectionFailure(true)
            .dispatcher(Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 12 })
            .proxySelector(socks).dns(dnsLive)
            .addInterceptor(kill)
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
