package app.hwallet

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * v1.9.19: "VPN for Hwallet", app-only (no VpnService, no system prompt, other apps unaffected).
 * The tunnel is warp-plus (github.com/bepass-org/warp-plus, MIT), the prebuilt Android arm64 executable shipped as
 * jniLibs/arm64-v8a/libwarpplus.so (CI downloads it from the pinned release and checks its SHA-256). It runs as a child
 * process from nativeLibraryDir and exposes ONE local SOCKS5 proxy on 127.0.0.1:<port>:
 *   Auto = plain Cloudflare WARP; a country = WARP + Psiphon exit in that country (--cfon --country XX).
 * HwNet routes every OkHttp request through it, ProxyController routes both WebViews through it.
 * Kill switch: while the VPN is ON and the tunnel is not connected, HwNet refuses every request and the WebViews stay
 * pointed at the local port (loads fail), so nothing ever goes direct. Prefs "hw_vpn": on, region, dns.
 */
object HwVpn {
    const val PREF = "hw_vpn"
    private const val BIN = "libwarpplus.so"

    /** the countries warp-plus v1.2.6 accepts for --country (its README); "" = Auto (best) */
    val REGIONS: List<Pair<String, String>> = listOf(
        "AT" to "Austria", "AU" to "Australia", "BE" to "Belgium", "BG" to "Bulgaria", "CA" to "Canada", "CH" to "Switzerland",
        "CZ" to "Czechia", "DE" to "Germany", "DK" to "Denmark", "EE" to "Estonia", "ES" to "Spain", "FI" to "Finland",
        "FR" to "France", "GB" to "United Kingdom", "HR" to "Croatia", "HU" to "Hungary", "IE" to "Ireland", "IN" to "India",
        "IT" to "Italy", "JP" to "Japan", "LV" to "Latvia", "NL" to "Netherlands", "NO" to "Norway", "PL" to "Poland",
        "PT" to "Portugal", "RO" to "Romania", "RS" to "Serbia", "SE" to "Sweden", "SG" to "Singapore", "SK" to "Slovakia",
        "US" to "United States"
    )
    fun regionName(code: String): String = if (code.isEmpty()) "Auto" else REGIONS.firstOrNull { it.first == code }?.second ?: code

    @Volatile var on = false; private set
    @Volatile var region = ""; private set
    @Volatile var dns = "quad9"; private set
    /** off | connecting | connected | reconnecting | error */
    @Volatile var status = "off"; private set
    @Volatile var since = 0L; private set
    @Volatile var error = ""; private set
    /** the local SOCKS port (kept across restarts so the WebView override never has to point anywhere else) */
    @Volatile var port = 0; private set
    @Volatile private var loaded = false
    @Volatile private var app: Context? = null

    /** told on every state change (any thread) */
    val listeners = CopyOnWriteArrayList<() -> Unit>()

    private val ex = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "hwvpn").apply { isDaemon = true } }
    private val ui = Handler(Looper.getMainLooper())
    private var proc: Process? = null
    private var gen = 0
    private var tries = 0
    private var retry: ScheduledFuture<*>? = null
    private var watchdog: ScheduledFuture<*>? = null
    private var health: ScheduledFuture<*>? = null

    /** true = the shared client must use the local proxy (VPN on, whatever the tunnel state) */
    fun routed(): Boolean = on && port > 0
    /** true = kill switch engaged: nothing may leave Hwallet */
    fun blocked(): Boolean = on && status != "connected"

    fun bin(c: Context): File = File(c.applicationInfo.nativeLibraryDir ?: "", BIN)
    fun supported(c: Context): Boolean = try { bin(c).exists() } catch (_: Throwable) { false }
    fun webSupported(): Boolean = try { WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE) } catch (_: Throwable) { false }

    /** once per process (HwApp, HwActivity, HwLiveService): read prefs, apply DNS, bring the tunnel back if it was on */
    fun load(c0: Context) {
        val c = c0.applicationContext ?: c0
        synchronized(this) {
            if (loaded) return
            loaded = true; app = c
            try {
                val p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                on = p.getBoolean("on", false); region = p.getString("region", "") ?: ""; dns = p.getString("dns", "quad9") ?: "quad9"
                if (region.isNotEmpty() && REGIONS.none { it.first == region }) region = ""
                if (dns != "quad9" && dns != "mullvad" && dns != "off") dns = "quad9"
            } catch (_: Throwable) {}
        }
        if (on) {
            /* "on" restored onto a phone without the binary (backup from a 64-bit phone): it could never connect, so it is off */
            if (!supported(c)) { on = false; save(c); status = "off"; error = ""; since = System.currentTimeMillis() }
            else { status = "connecting"; since = System.currentTimeMillis(); ex.execute { startProc() } }
        }
        changed(true)
    }

    /** from the page: {on, region, dns}; returns "" or a plain error */
    fun set(c: Context, j: JSONObject): String {
        load(c)
        val wantOn = if (j.has("on")) j.optBoolean("on") else on
        val wantRegion = if (j.has("region")) j.optString("region", "").uppercase().take(2) else region
        val wantDns = if (j.has("dns")) j.optString("dns", dns) else dns
        if (wantRegion.isNotEmpty() && REGIONS.none { it.first == wantRegion }) return "Unknown location"
        if (wantDns != "quad9" && wantDns != "mullvad" && wantDns != "off") return "Unknown DNS option"
        if (wantOn && !supported(c)) {
            if (on) { on = false; save(c) }
            status = "error"; error = "Not supported on this phone"; since = System.currentTimeMillis(); changed(true)
            return "The VPN is not supported on this phone (it needs a 64-bit ARM phone)"
        }
        val toggled = wantOn != on
        val regionChanged = wantRegion != region
        val dnsChanged = wantDns != dns
        on = wantOn; region = wantRegion; dns = wantDns
        save(c)
        if (dnsChanged) HwNet.dnsChanged()
        if (toggled || (on && regionChanged)) {
            if (on) { status = if (toggled) "connecting" else "reconnecting"; error = ""; since = System.currentTimeMillis(); tries = 0; ex.execute { restartProc() } }
            else { status = "off"; error = ""; since = System.currentTimeMillis(); ex.execute { stopProc() } }
            changed(true)
        } else if (dnsChanged || regionChanged) changed(false)
        return ""
    }

    private fun save(c: Context) {
        try { c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean("on", on).putString("region", region).putString("dns", dns).apply() } catch (_: Throwable) {}
    }

    fun stateJson(): String = JSONObject().put("on", on).put("status", status).put("region", region).put("regionName", regionName(region))
        .put("since", since).put("error", error).put("dns", dns).put("web", webSupported()).put("port", if (on) port else 0)
        .put("supported", app?.let { supported(it) } ?: true).toString()

    fun regionsJson(): String { val a = JSONArray(); for ((k, n) in REGIONS) a.put(JSONObject().put("code", k).put("name", n)); return a.toString() }

    /** HwNet's default-network watcher: a new network, so a fresh tunnel (the old one's sockets are gone) */
    fun networkChanged() {
        if (!on || !loaded) return
        ex.execute {
            if (!on) return@execute
            tries = 0
            if (status == "connected") setStatus("reconnecting", "")
            restartProc()
        }
    }

    /* ---------------- process (all on the hwvpn thread) ---------------- */

    private fun freePort(): Int = try { ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort } } catch (_: Throwable) { 0 }
    private fun portFree(p: Int): Boolean = p > 0 && try { ServerSocket(p, 1, java.net.InetAddress.getByName("127.0.0.1")).use { true } } catch (_: Throwable) { false }
    private fun probe(p: Int): Boolean = p > 0 && try { Socket().use { it.connect(InetSocketAddress("127.0.0.1", p), 1500); true } } catch (_: Throwable) { false }

    private fun stopProc() {
        gen++
        retry?.cancel(false); retry = null; watchdog?.cancel(false); watchdog = null; health?.cancel(false); health = null
        val p = proc; proc = null
        if (p != null) {
            try { p.destroy() } catch (_: Throwable) {}
            /* minSdk 24: no waitFor(timeout); give it up to 1.5 s to leave (its port is then free again) */
            var n = 0
            while (n++ < 15) { try { p.exitValue(); break } catch (_: IllegalThreadStateException) { try { Thread.sleep(100) } catch (_: Throwable) { break } } catch (_: Throwable) { break } }
        }
    }

    private fun restartProc() { stopProc(); startProc() }

    private fun startProc() {
        val c = app ?: return
        if (!on) return
        if (proc != null) return
        val b = bin(c)
        if (!b.exists()) { setStatus("error", "Not supported on this phone"); return }
        /* the same port as before when it is free again, so the WebView override never moves */
        val old = port
        if (!portFree(port)) port = freePort()
        if (port <= 0) { setStatus("reconnecting", "No free local port"); schedule(); return }
        if (port != old) changed(true)
        val cache = File(c.filesDir, "warpplus").apply { mkdirs() }
        val cmd = arrayListOf(b.absolutePath, "--bind", "127.0.0.1:$port", "--cache-dir", cache.absolutePath)
        if (region.isNotEmpty()) { cmd.add("--cfon"); cmd.add("--country"); cmd.add(region) }
        /* the default endpoint did not work twice in a row: let warp-plus scan for one that answers on this network */
        if (tries >= 2) cmd.add("--scan")
        val my = ++gen
        try {
            val pb = ProcessBuilder(cmd).directory(cache).redirectErrorStream(true)
            pb.environment()["HOME"] = c.filesDir.absolutePath
            pb.environment()["TMPDIR"] = c.cacheDir.absolutePath
            val p = pb.start()
            proc = p
            Thread({ readLog(p, my) }, "hwvpn-log").apply { isDaemon = true }.start()
        } catch (e: Throwable) {
            proc = null
            setStatus("reconnecting", "Could not start the VPN: " + (e.message ?: e.javaClass.simpleName).take(120))
            schedule(); return
        }
        /* Psiphon (a country) can take a while; give up on this attempt after 2 minutes and start a fresh one */
        watchdog = ex.schedule(Runnable {
            if (my == gen && on && status != "connected") { stopProc(); setStatus("reconnecting", "Taking too long to connect"); schedule() }
        }, 120, TimeUnit.SECONDS)
    }

    /** warp-plus logs to stdout (slog text): `level=INFO msg="serving proxy" address=127.0.0.1:PORT` once the SOCKS port serves */
    private fun readLog(p: Process, my: Int) {
        var last = ""
        try {
            p.inputStream.bufferedReader().use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    if (line.isBlank()) continue
                    if (line.contains("level=ERROR") || line.startsWith("error:")) last = plain(line)
                    if (line.contains("serving proxy")) ex.execute { if (my == gen && on) connectedNow() }
                }
            }
        } catch (_: Throwable) {}
        val code = try { p.waitFor() } catch (_: Throwable) { -1 }
        ex.execute {
            if (my != gen) return@execute
            proc = null
            watchdog?.cancel(false); health?.cancel(false)
            if (!on) return@execute
            setStatus("reconnecting", if (last.isNotEmpty()) last else "The VPN stopped (code $code)")
            schedule()
        }
    }

    private fun connectedNow() {
        if (!probe(port)) { val my0 = gen; ex.schedule(Runnable { if (my0 == gen && on && status != "connected" && probe(port)) connectedNow() }, 1, TimeUnit.SECONDS); return }
        tries = 0
        watchdog?.cancel(false); watchdog = null
        setStatus("connected", "")
        val my = gen
        health?.cancel(false)
        health = ex.scheduleWithFixedDelay(Runnable {
            if (my == gen && on && status == "connected" && !probe(port)) { setStatus("reconnecting", "Lost the VPN connection"); restartProc() }
        }, 30, 30, TimeUnit.SECONDS)
    }

    /** backoff 2 s, 4 s, 8 s ... 60 s */
    private fun schedule() {
        if (!on) return
        retry?.cancel(false)
        val d = minOf(60L, 2L shl minOf(tries, 5))
        tries++
        retry = ex.schedule(Runnable { if (on && proc == null) startProc() }, d, TimeUnit.SECONDS)
    }

    private fun plain(line: String): String {
        val m = Regex("msg=\"([^\"]{1,160})\"").find(line)?.groupValues?.get(1) ?: line.removePrefix("error:").trim()
        val e = Regex("error=\"([^\"]{1,160})\"").find(line)?.groupValues?.get(1)
        return (if (e != null) "$m: $e" else m).take(160)
    }

    private fun setStatus(s: String, err: String) {
        val was = status
        if (was == s && error == err) return
        status = s; error = err
        if (was != s) since = System.currentTimeMillis()
        changed(was == "connected" || s == "connected")
    }

    /** routing = the proxy or the kill switch changed: drop pooled connections, re-point the WebViews */
    private fun changed(routing: Boolean) {
        if (routing) { try { HwNet.vpnChanged() } catch (_: Throwable) {}; applyWeb() }
        for (l in listeners) try { l() } catch (_: Throwable) {}
    }

    /* ---------------- WebViews (process-wide: the main page and every Browser tab) ---------------- */
    @Volatile private var webOn = false
    @Volatile private var webPort = 0
    fun applyWeb() {
        ui.post {
            try {
                if (!webSupported()) return@post
                val pc = ProxyController.getInstance()
                if (on && port > 0) {
                    if (webOn && webPort == port) return@post
                    /* kept on the local port while reconnecting too: loads fail instead of going direct (kill switch) */
                    val cfg = ProxyConfig.Builder().addProxyRule("socks5://127.0.0.1:$port").build()
                    pc.setProxyOverride(cfg, { r -> r.run() }, { })
                    webOn = true; webPort = port
                } else if (!on && webOn) {
                    pc.clearProxyOverride({ r -> r.run() }, { })
                    webOn = false; webPort = 0
                }
            } catch (_: Throwable) {}
        }
    }

    /** the WebView cannot be proxied (too old) or has no port yet: pages must not load while the VPN is on */
    fun webBlocked(): Boolean = on && (!webSupported() || port <= 0 || !webOn)
}
