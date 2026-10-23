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
 * pointed at the local port (loads fail), so nothing ever goes direct. Prefs "hw_vpn": on, region, dns, ep (the last WARP
 * endpoint that served, tried first with -e on the next start).
 * Attempts (warp-plus v1.2.6 flags): Auto rotates [--scan -4] -> [--scan -4 --gool] -> repeat; a country is always
 * [--scan -4 --cfon --country XX]. With a remembered endpoint the first attempt is [-e EP] (+ --cfon --country XX), no scan.
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
    /** Secure DNS (DoH) servers: key -> (DoH URL, bootstrap IPs). Keys: off | quad9 (default) | cloudflare | google | adguard */
    val DNS_KEYS = listOf("off", "quad9", "cloudflare", "google", "adguard")
    fun dohServer(k: String): Pair<String, List<String>> = when (k) {
        "cloudflare" -> "https://cloudflare-dns.com/dns-query" to listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001")
        "google" -> "https://dns.google/dns-query" to listOf("8.8.8.8", "8.8.4.4", "2001:4860:4860::8888", "2001:4860:4860::8844")
        "adguard" -> "https://dns.adguard-dns.com/dns-query" to listOf("94.140.14.14", "94.140.15.15", "2a10:50c0::ad1:ff", "2a10:50c0::ad2:ff")
        else -> "https://dns.quad9.net/dns-query" to listOf("9.9.9.9", "149.112.112.112", "2620:fe::fe", "2620:fe::9")
    }
    /** a saved key from an older build ("mullvad", removed in 1.9.19) or anything unknown becomes Quad9 */
    private fun dnsKey(k: String?): String = if (k != null && k in DNS_KEYS) k else "quad9"

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
    /** attempts since the last connect / user change / network change: picks the flags (see attemptArgs) */
    private var attempt = 0
    /** the remembered endpoint (prefs "ep") and whether this run of attempts already tried it */
    @Volatile private var lastEp = ""
    private var epTried = false
    /** this attempt: the endpoint passed with -e ("" = scan) and the endpoint warp-plus said it uses */
    private var curEp = ""
    @Volatile private var seenEp = ""
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
                on = p.getBoolean("on", false); region = p.getString("region", "") ?: ""
                val d0 = p.getString("dns", "quad9"); dns = dnsKey(d0)
                lastEp = validEp(p.getString("ep", "") ?: "")
                if (region.isNotEmpty() && REGIONS.none { it.first == region }) region = ""
                if (d0 != dns) p.edit().putString("dns", dns).apply()
            } catch (_: Throwable) {}
        }
        if (on) {
            /* "on" restored onto a phone without the binary (backup from a 64-bit phone): it could never connect, so it is off */
            if (!supported(c)) { on = false; save(c); status = "off"; error = ""; since = System.currentTimeMillis() }
            else { status = "connecting"; since = System.currentTimeMillis(); ex.execute { fresh(); startProc() } }
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
        if (wantDns !in DNS_KEYS) return "Unknown DNS option"
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
            if (on) { status = if (toggled) "connecting" else "reconnecting"; error = ""; since = System.currentTimeMillis(); ex.execute { fresh(); restartProc() } }
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
            fresh()
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

    /** a new run of attempts (VPN turned on, new location, new network, after a connect): remembered endpoint first */
    private fun fresh() { tries = 0; attempt = 0; epTried = false }

    /** this attempt's warp-plus flags (see the class comment); also sets curEp */
    private fun attemptArgs(): List<String> {
        val a = ArrayList<String>()
        if (lastEp.isNotEmpty() && !epTried) {
            epTried = true; curEp = lastEp
            a.add("-e"); a.add(lastEp)
        } else {
            curEp = ""
            a.add("--scan"); a.add("-4")
            /* from the third scan on, accept slower endpoints (slow or lossy networks) */
            if (attempt >= 2) { a.add("--rtt"); a.add("2s") }
            if (region.isEmpty() && attempt % 2 == 1) a.add("--gool")
            attempt++
        }
        if (region.isNotEmpty()) { a.add("--cfon"); a.add("--country"); a.add(region) }
        return a
    }

    private fun startProc() {
        val c = app ?: return
        if (!on) return
        if (proc != null) return
        val b = bin(c)
        if (!b.exists()) { setStatus("error", "Not supported on this phone"); return }
        /* the same port as before when it is free again, so the WebView override never moves */
        val old = port
        if (!portFree(port)) port = freePort()
        if (port <= 0) { log("hwvpn: no free local port"); setStatus("reconnecting", "Couldn't start the VPN. Trying again\u2026"); schedule(); return }
        if (port != old) changed(true)
        val cache = File(c.filesDir, "warpplus").apply { mkdirs() }
        val args = attemptArgs()
        val cmd = arrayListOf(b.absolutePath, "--bind", "127.0.0.1:$port", "--cache-dir", cache.absolutePath)
        cmd.addAll(args)
        seenEp = ""
        val my = ++gen
        log("hwvpn: attempt " + (if (curEp.isNotEmpty()) "(last working endpoint)" else "#$attempt") + " " + regionName(region) + ": " + args.joinToString(" "))
        try {
            val pb = ProcessBuilder(cmd).directory(cache).redirectErrorStream(true)
            pb.environment()["HOME"] = c.filesDir.absolutePath
            pb.environment()["TMPDIR"] = c.cacheDir.absolutePath
            val p = pb.start()
            proc = p
            Thread({ readLog(p, my) }, "hwvpn-log").apply { isDaemon = true }.start()
        } catch (e: Throwable) {
            proc = null
            log("hwvpn: could not start: " + (e.message ?: e.javaClass.simpleName).take(160))
            setStatus("reconnecting", "Couldn't start the VPN. Trying again\u2026")
            schedule(); return
        }
        progress(my, if (curEp.isNotEmpty()) "Connecting to Cloudflare\u2026" else "Finding a working server\u2026")
        /* scan (up to 1 min) + WARP test + Psiphon can take a while; give up on this attempt after 3 minutes, start a fresh one */
        watchdog = ex.schedule(Runnable {
            if (my == gen && on && status != "connected") {
                log("hwvpn: attempt took longer than 3 min, starting another")
                stopProc(); attemptFailed(); setStatus("reconnecting", "Taking too long. Trying another server\u2026"); schedule()
            }
        }, 180, TimeUnit.SECONDS)
    }

    /** the attempt with the remembered endpoint failed: forget it (the next scan finds and saves a new one) */
    private fun attemptFailed() {
        if (curEp.isEmpty()) return
        log("hwvpn: last working endpoint failed, scanning instead")
        lastEp = ""; curEp = ""
        try { app?.getSharedPreferences(PREF, Context.MODE_PRIVATE)?.edit()?.remove("ep")?.apply() } catch (_: Throwable) {}
    }

    /** warp-plus logs to stdout (slog text), e.g. `level=INFO msg="using warp endpoints" endpoints="[A:P B:P]"`, then
     *  `msg="connection test successful"`, then `msg="serving proxy" address=127.0.0.1:PORT` once the SOCKS port serves.
     *  A fatal error is `level=ERROR msg="context deadline exceeded"` (the WARP test timed out) and exit 1. */
    private fun readLog(p: Process, my: Int) {
        var last = ""
        try {
            p.inputStream.bufferedReader().use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    if (line.isBlank()) continue
                    log(line)
                    if (line.contains("level=ERROR") || line.startsWith("error:")) { if (!line.contains("connection test failed")) last = plain(line) }
                    when {
                        line.contains("serving proxy") -> ex.execute { if (my == gen && on) connectedNow() }
                        line.contains("using warp endpoints") -> {
                            val m = Regex("endpoints=\"?\\[([^\\]]*)\\]").find(line)
                            val ep = m?.groupValues?.get(1)?.trim()?.split(Regex("\\s+"))?.firstOrNull() ?: ""
                            if (validEp(ep).isNotEmpty()) seenEp = ep
                            progress(my, "Connecting to Cloudflare\u2026")
                        }
                        line.contains("scanner mode enabled") -> progress(my, "Finding a working server\u2026")
                        line.contains("connection test successful") && region.isNotEmpty() -> progress(my, "Connecting to " + regionName(region) + "\u2026")
                    }
                }
            }
        } catch (_: Throwable) {}
        val code = try { p.waitFor() } catch (_: Throwable) { -1 }
        ex.execute {
            if (my != gen) return@execute
            log("hwvpn: warp-plus exited (code $code)")
            proc = null
            watchdog?.cancel(false); health?.cancel(false)
            if (!on) return@execute
            attemptFailed()
            setStatus("reconnecting", friendly(last))
            schedule()
        }
    }

    /** a progress line for the status row while this attempt is still connecting */
    private fun progress(my: Int, msg: String) {
        ex.execute { if (my == gen && on && status != "connected" && status != "off") setStatus(if (status == "error") "reconnecting" else status, msg) }
    }

    private fun connectedNow() {
        if (!probe(port)) { val my0 = gen; ex.schedule(Runnable { if (my0 == gen && on && status != "connected" && probe(port)) connectedNow() }, 1, TimeUnit.SECONDS); return }
        fresh()
        watchdog?.cancel(false); watchdog = null
        /* remember the endpoint that served: the next start tries it first with -e (no scan) */
        val ep = seenEp
        if (ep.isNotEmpty() && ep != lastEp) {
            lastEp = ep
            try { app?.getSharedPreferences(PREF, Context.MODE_PRIVATE)?.edit()?.putString("ep", ep)?.apply() } catch (_: Throwable) {}
        }
        log("hwvpn: connected" + (if (ep.isNotEmpty()) " via $ep" else ""))
        setStatus("connected", "")
        val my = gen
        health?.cancel(false)
        health = ex.scheduleWithFixedDelay(Runnable {
            if (my == gen && on && status == "connected" && !probe(port)) { log("hwvpn: local proxy stopped answering"); setStatus("reconnecting", "Lost the connection. Reconnecting\u2026"); restartProc() }
        }, 30, 30, TimeUnit.SECONDS)
    }

    /** backoff 2 s, 4 s, 8 s ... 30 s (each attempt already rotates the flags) */
    private fun schedule() {
        if (!on) return
        retry?.cancel(false)
        val d = minOf(30L, 2L shl minOf(tries, 4))
        tries++
        retry = ex.schedule(Runnable { if (on && proc == null) startProc() }, d, TimeUnit.SECONDS)
    }

    private fun plain(line: String): String {
        val m = Regex("msg=\"([^\"]{1,160})\"").find(line)?.groupValues?.get(1)
            ?: Regex("msg=(\\S{1,160})").find(line)?.groupValues?.get(1) ?: line.removePrefix("error:").trim()
        val e = Regex("error=\"([^\"]{1,160})\"").find(line)?.groupValues?.get(1)
        return (if (e != null) "$m: $e" else m).take(160)
    }

    /** the raw warp-plus error (kept in the log) as one plain-English line for the status row */
    private fun friendly(raw: String): String {
        val r = raw.lowercase()
        return when {
            r.contains("psiphon") -> "Couldn't connect to " + regionName(region) + ". Trying again\u2026"
            r.contains("user canceled") -> "Couldn't find a working server. Trying again\u2026"
            r.contains("deadline exceeded") || r.contains("connection test") || r.contains("handshake") || r.contains("timeout")
                || r.contains("i/o") || r.contains("unreachable") || r.contains("refused") || r.contains("no route") ->
                "Couldn't reach Cloudflare. Trying another server\u2026"
            r.contains("identity") || r.contains("api.cloudflareclient") || r.contains("register") -> "Couldn't sign up with Cloudflare WARP. Trying again\u2026"
            else -> "Couldn't connect. Trying again\u2026"
        }
    }

    private fun validEp(e: String): String =
        if (Regex("^\\d{1,3}(\\.\\d{1,3}){3}:\\d{1,5}$").matches(e) || Regex("^\\[[0-9A-Fa-f:]{2,45}]:\\d{1,5}$").matches(e)) e else ""

    /* ---------------- log: the last 200 warp-plus lines (keys redacted), for Settings > Network & VPN > View log ---------------- */
    private const val LOG_MAX = 200
    private val ring = ArrayDeque<String>(LOG_MAX)
    private val tf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
    private fun log(line: String) {
        val l = redact(line).take(600)
        synchronized(ring) {
            /* warp-plus lines carry their own time=...; our own lines get one */
            ring.addLast(if (l.startsWith("hwvpn:")) tf.format(java.util.Date()) + " " + l else l)
            while (ring.size > LOG_MAX) ring.removeFirst()
        }
    }
    fun logText(): String = synchronized(ring) { ring.joinToString("\n") }

    /** never let a WARP private key, license key or token reach the log */
    private fun redact(s: String): String {
        var t = s.replace(Regex("(?i)\\b((?:private_?|public_?)?key|license|token|auth[a-z_]*|secret)\\s*[=:]\\s*(\"[^\"]*\"|\\S+)"), "$1=[redacted]")
        t = t.replace(Regex("[A-Za-z0-9+/]{42,43}="), "[redacted]")
        t = t.replace(Regex("\\b[0-9a-fA-F]{64}\\b"), "[redacted]")
        t = t.replace(Regex("\\b[A-Za-z0-9]{8}-[A-Za-z0-9]{8}-[A-Za-z0-9]{8}\\b"), "[redacted]")
        return t
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
