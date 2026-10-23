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
 * Engines (prebuilt Android arm64 executables in jniLibs/arm64-v8a, run as child processes from nativeLibraryDir; CI
 * fetches them pinned and never commits them):
 *   libusque.so   = usque v4.2.1 (github.com/Diniboy1123/usque, MIT): Cloudflare WARP over MASQUE (HTTP/3 on UDP 443,
 *                   like the official 1.1.1.1 app). Gets through networks that drop WireGuard data after the handshake.
 *   libpsiphon.so = Psiphon tunnel core ConsoleClient (github.com/Psiphon-Labs/psiphon-tunnel-core, GPL-3.0), built by
 *                   CI from a pinned commit, unmodified. Used only for a country.
 * Auto      : usque socks on 127.0.0.1:<port>  (the one port OkHttp and both WebViews use)
 * A country : usque socks on 127.0.0.1:<uport> (internal) -> Psiphon with UpstreamProxyURL=socks5://127.0.0.1:<uport>,
 *             EgressRegion=XX, its SOCKS on 127.0.0.1:<port>. So Psiphon rides the working WARP tunnel.
 * First start: `usque register -a` writes filesDir/usque/config.json (a free WARP device; private storage, never backed up).
 * usque attempts rotate HTTP/3 :443 -> HTTP/2 over TLS :443 (--http2) -> HTTP/3 :500, starting from the variant that
 * last worked (prefs "uv"). usque listens at once and connects on first use, so a stage counts only once a request to
 * https://1.1.1.1/cdn-cgi/trace through it answers (warp=on for usque; any trace for Psiphon).
 * Names asked through the proxy resolve inside the tunnel with Cloudflare DNS (1.1.1.1 / 1.0.0.1): Quad9 answers
 * "no such host" for RPCs like polygon.drpc.org and polygon-bor-rpc.publicnode.com.
 * HwNet routes every OkHttp request through <port>, ProxyController routes both WebViews through it.
 * Kill switch: while the VPN is ON and the tunnel is not connected, HwNet refuses every request and the WebViews stay
 * pointed at the local port (loads fail), so nothing ever goes direct. Prefs "hw_vpn": on, region, dns, uv, dns2.
 */
object HwVpn {
    const val PREF = "hw_vpn"
    private const val BIN_U = "libusque.so"
    private const val BIN_P = "libpsiphon.so"

    /** Psiphon egress regions offered (the same list warp-plus offered); "" = Auto (best) */
    val REGIONS: List<Pair<String, String>> = listOf(
        "AT" to "Austria", "AU" to "Australia", "BE" to "Belgium", "BG" to "Bulgaria", "CA" to "Canada", "CH" to "Switzerland",
        "CZ" to "Czechia", "DE" to "Germany", "DK" to "Denmark", "EE" to "Estonia", "ES" to "Spain", "FI" to "Finland",
        "FR" to "France", "GB" to "United Kingdom", "HR" to "Croatia", "HU" to "Hungary", "IE" to "Ireland", "IN" to "India",
        "IT" to "Italy", "JP" to "Japan", "LV" to "Latvia", "NL" to "Netherlands", "NO" to "Norway", "PL" to "Poland",
        "PT" to "Portugal", "RO" to "Romania", "RS" to "Serbia", "SE" to "Sweden", "SG" to "Singapore", "SK" to "Slovakia",
        "US" to "United States"
    )
    /** Secure DNS (DoH) servers: key -> (DoH URL, bootstrap IPs). Keys: off | cloudflare (default) | quad9 | google | adguard */
    val DNS_KEYS = listOf("off", "cloudflare", "quad9", "google", "adguard")
    fun dohServer(k: String): Pair<String, List<String>> = when (k) {
        "quad9" -> "https://dns.quad9.net/dns-query" to listOf("9.9.9.9", "149.112.112.112", "2620:fe::fe", "2620:fe::9")
        "google" -> "https://dns.google/dns-query" to listOf("8.8.8.8", "8.8.4.4", "2001:4860:4860::8888", "2001:4860:4860::8844")
        "adguard" -> "https://dns.adguard-dns.com/dns-query" to listOf("94.140.14.14", "94.140.15.15", "2a10:50c0::ad1:ff", "2a10:50c0::ad2:ff")
        else -> "https://cloudflare-dns.com/dns-query" to listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001")
    }
    /** a saved key from an older build ("mullvad", removed in 1.9.19) or anything unknown becomes Cloudflare */
    private fun dnsKey(k: String?): String = if (k != null && k in DNS_KEYS) k else "cloudflare"

    fun regionName(code: String): String = if (code.isEmpty()) "Auto" else REGIONS.firstOrNull { it.first == code }?.second ?: code

    @Volatile var on = false; private set
    @Volatile var region = ""; private set
    @Volatile var dns = "cloudflare"; private set
    /** off | connecting | connected | reconnecting | error */
    @Volatile var status = "off"; private set
    @Volatile var since = 0L; private set
    @Volatile var error = ""; private set
    /** the local SOCKS port (kept across restarts so the WebView override never has to point anywhere else) */
    @Volatile var port = 0; private set
    /** a country: usque's internal port, Psiphon's upstream */
    private var uport = 0
    @Volatile private var loaded = false
    @Volatile private var app: Context? = null

    /** told on every state change (any thread) */
    val listeners = CopyOnWriteArrayList<() -> Unit>()

    private val ex = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "hwvpn").apply { isDaemon = true } }
    private val ui = Handler(Looper.getMainLooper())
    /** usque, and Psiphon on top of it for a country */
    private var proc: Process? = null
    private var procP: Process? = null
    private var gen = 0
    private var tries = 0
    /** attempts since the last connect / user change / network change: picks usque's variant (see usqueArgs) */
    private var attempt = 0
    private var retry: ScheduledFuture<*>? = null
    private var watchdog: ScheduledFuture<*>? = null
    private var health: ScheduledFuture<*>? = null

    /** true = the shared client must use the local proxy (VPN on, whatever the tunnel state) */
    fun routed(): Boolean = on && port > 0
    /** true = kill switch engaged: nothing may leave Hwallet */
    fun blocked(): Boolean = on && status != "connected"

    fun binU(c: Context): File = File(c.applicationInfo.nativeLibraryDir ?: "", BIN_U)
    fun binP(c: Context): File = File(c.applicationInfo.nativeLibraryDir ?: "", BIN_P)
    fun supported(c: Context): Boolean = try { binU(c).exists() } catch (_: Throwable) { false }
    private fun cfgU(c: Context): File = File(File(c.filesDir, "usque").apply { mkdirs() }, "config.json")
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
                /* fix 3: Cloudflare is the default now (Quad9 answers "no such host" for common RPCs); a Quad9 saved by an
                   older build (it was the default) moves to Cloudflare once ("dns2"); picking Quad9 again later sticks */
                var d0 = p.getString("dns", "cloudflare")
                if (!p.getBoolean("dns2", false)) { if (d0 == "quad9") d0 = "cloudflare"; p.edit().putBoolean("dns2", true).remove("ep").apply() }
                dns = dnsKey(d0)
                uVar = p.getInt("uv", 0).coerceIn(0, 2)
                if (region.isNotEmpty() && REGIONS.none { it.first == region }) region = ""
                if (dns != p.getString("dns", null)) p.edit().putString("dns", dns).apply()
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

    private fun kill(p: Process?) {
        if (p == null) return
        try { p.destroy() } catch (_: Throwable) {}
        /* minSdk 24: no waitFor(timeout); give it up to 1.5 s to leave (its port is then free again) */
        var n = 0
        while (n++ < 15) { try { p.exitValue(); break } catch (_: IllegalThreadStateException) { try { Thread.sleep(100) } catch (_: Throwable) { break } } catch (_: Throwable) { break } }
    }

    private fun stopProc() {
        gen++
        retry?.cancel(false); retry = null; watchdog?.cancel(false); watchdog = null; health?.cancel(false); health = null
        val pp = procP; procP = null; kill(pp)
        val p = proc; proc = null; kill(p)
    }

    private fun restartProc() { stopProc(); startProc() }

    /** a new run of attempts (VPN turned on, new location, new network, after a connect) */
    private fun fresh() { tries = 0; attempt = 0 }

    /* ---------------- usque (always; Auto serves on it, a country puts Psiphon on top) ---------------- */
    @Volatile private var tunFails = 0
    @Volatile private var uVar = 0
    private var curVar = 0
    private var regTried = false
    private var verifying = false
    /** this attempt has reached Psiphon (a country) */
    private var psiUp = false
    private val VARS = arrayOf("HTTP/3 :443", "HTTP/2 :443", "HTTP/3 :500")

    /** usque socks flags for this attempt: HTTP/3 :443 -> HTTP/2 :443 -> HTTP/3 :500, from the variant that last worked */
    private fun usqueArgs(): List<String> {
        val a = ArrayList<String>()
        val v = (attempt + uVar) % 3; curVar = v
        when (v) { 1 -> a.add("--http2"); 2 -> { a.add("-P"); a.add("500") } }
        attempt++
        /* Cloudflare DNS inside the tunnel, whatever Secure DNS says (Quad9 answers "no such host" for common RPCs) */
        a.add("-d"); a.add("1.1.1.1"); a.add("-d"); a.add("1.0.0.1")
        return a
    }

    private fun startProc() {
        val c = app ?: return
        if (!on) return
        if (proc != null) return
        val b = binU(c)
        if (!b.exists()) { setStatus("error", "Not supported on this phone"); return }
        val country = region.isNotEmpty()
        if (country && !binP(c).exists()) { setStatus("error", "Countries aren\u2019t available in this build"); return }
        val dir = File(c.filesDir, "usque").apply { mkdirs() }
        val cfg = cfgU(c)
        val reg = !(cfg.exists() && cfg.length() > 50)
        /* the public port stays the same when it is free again, so the WebView override never moves */
        val old = port
        if (!portFree(port)) port = freePort()
        if (country && (uport <= 0 || uport == port || !portFree(uport))) uport = freePort()
        if (port <= 0 || (country && uport <= 0)) { log("hwvpn: no free local port"); setStatus("reconnecting", "Couldn't start the VPN. Trying again\u2026"); schedule(); return }
        if (port != old) changed(true)
        val bind = if (country) uport else port
        val args: List<String> = if (reg) listOf("register", "-a", "-n", "Hwallet") else listOf("socks", "-b", "127.0.0.1", "-p", bind.toString()) + usqueArgs()
        val cmd = arrayListOf(b.absolutePath, "-c", cfg.absolutePath); cmd.addAll(args)
        val my = ++gen
        tunFails = 0; verifying = false; psiUp = false
        log("hwvpn: " + (if (reg) "registering a Cloudflare WARP device (one time)" else "attempt #$attempt " + regionName(region) + ": WARP (MASQUE " + VARS[curVar] + ")" + (if (country) " then Psiphon " + region else "")))
        try {
            val pb = ProcessBuilder(cmd).directory(dir).redirectErrorStream(true)
            pb.environment()["HOME"] = c.filesDir.absolutePath
            pb.environment()["TMPDIR"] = c.cacheDir.absolutePath
            val p = pb.start()
            proc = p
            Thread({ readUsque(p, my, reg) }, "hwvpn-log").apply { isDaemon = true }.start()
        } catch (e: Throwable) {
            proc = null
            log("hwvpn: could not start: " + (e.message ?: e.javaClass.simpleName).take(160))
            setStatus("reconnecting", "Couldn't start the VPN. Trying again\u2026")
            schedule(); return
        }
        progress(my, if (reg) "Setting up Cloudflare WARP\u2026" else "Connecting to Cloudflare\u2026")
        /* registration or a MASQUE connection that hasn't proved itself in 75 s: next variant (Psiphon gets its own time) */
        dog(my, 75, "no WARP connection after 75 s, trying another way")
    }

    private fun dog(my: Int, s: Long, why: String) {
        watchdog?.cancel(false)
        watchdog = ex.schedule(Runnable {
            if (my == gen && on && status != "connected") {
                log("hwvpn: $why")
                stopProc(); setStatus("reconnecting", "Taking too long. Trying another way\u2026"); schedule()
            }
        }, s, TimeUnit.SECONDS)
    }

    /** usque logs `2026/10/23 22:29:01 UTC SOCKS proxy listening on 127.0.0.1:PORT`, then on first use
     *  `Establishing MASQUE connection to IP:443` and `Connected to MASQUE server`, or `Failed to connect tunnel: ...`.
     *  Per-connection lines (`SOCKS TCP handle ... failed`) are kept to one in ten so the log stays readable. */
    private fun readUsque(p: Process, my: Int, reg: Boolean) {
        var last = ""
        var noisy = 0
        try {
            p.inputStream.bufferedReader().use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    if (line.isBlank()) continue
                    val l = line.lowercase()
                    if (l.contains("socks tcp handle") || l.contains("closed during negotiation") || l.contains("socks udp")) { if (noisy++ % 10 == 0) log(line); continue }
                    if (l.contains("receive buffer size") || l.contains("hint: l4-socks")) continue
                    log(line)
                    when {
                        l.contains("proxy listening on") -> ex.execute { if (my == gen && on && status != "connected") verify(my, 0, false) }
                        l.contains("connected to masque server") -> { tunFails = 0; if (!psiUp) progress(my, "Checking the connection\u2026") }
                        l.contains("failed to connect tunnel") || l.contains("failed to establish") -> {
                            last = line.substringAfter("UTC ").take(200)
                            val n = ++tunFails
                            ex.execute { if (my == gen && on) tunnelFailed(my, n, l) }
                        }
                        l.contains("error") || l.contains("fatal") || l.contains("failed") -> last = line.substringAfter("UTC ").take(200)
                    }
                }
            }
        } catch (_: Throwable) {}
        val code = try { p.waitFor() } catch (_: Throwable) { -1 }
        ex.execute {
            if (my != gen) return@execute
            log("hwvpn: usque exited (code $code)")
            proc = null
            val pp = procP; procP = null; kill(pp)
            watchdog?.cancel(false); health?.cancel(false)
            if (!on) return@execute
            if (reg) {
                val ok = code == 0 && app?.let { cfgU(it).length() > 50 } == true
                if (ok) { log("hwvpn: WARP device registered"); startProc(); return@execute }
                val ll = last.lowercase()
                setStatus("reconnecting", if (ll.contains("429") || ll.contains("rate")) "Cloudflare is busy. Trying again shortly\u2026" else "Couldn't sign up with Cloudflare WARP. Trying again\u2026")
                schedule(); return@execute
            }
            setStatus("reconnecting", friendly(last))
            schedule()
        }
    }

    /** a MASQUE connect failed: a revoked device (401/403) re-registers once; 3 failures before connecting, or 6 in a row
     *  after, end this attempt (the next one tries the next variant) */
    private fun tunnelFailed(my: Int, n: Int, l: String) {
        val c = app ?: return
        if ((l.contains("401") || l.contains("403") || l.contains("unauthorized") || l.contains("forbidden")) && !regTried) {
            regTried = true
            log("hwvpn: Cloudflare refused this device, registering a new one")
            stopProc(); try { cfgU(c).delete() } catch (_: Throwable) {}
            setStatus("reconnecting", "Setting up Cloudflare WARP again\u2026"); startProc(); return
        }
        val was = status == "connected"
        if (n < (if (was) 6 else 3)) return
        log("hwvpn: MASQUE connection failed $n times, trying another way")
        stopProc()
        if (was) fresh()
        setStatus("reconnecting", if (was) "Lost the connection. Reconnecting\u2026" else "Couldn't reach Cloudflare. Trying another way\u2026")
        schedule()
    }

    /** proves a stage: https://1.1.1.1/cdn-cgi/trace through a local SOCKS port (an IP, so no DNS is needed; this also
     *  wakes usque's lazy connect). usque must answer warp=on; Psiphon any trace (its exit isn't WARP). Every 4 s while
     *  the attempt lives. */
    private fun verify(my: Int, n: Int, psi: Boolean) {
        if (my != gen || !on || status == "connected" || verifying) return
        verifying = true
        val pt = if (psi || region.isEmpty()) port else uport
        Thread({
            var ok = false; var why = ""; var loc = ""
            try {
                val px = java.net.Proxy(java.net.Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", pt))
                val h = java.net.URL("https://1.1.1.1/cdn-cgi/trace").openConnection(px) as java.net.HttpURLConnection
                h.connectTimeout = 15000; h.readTimeout = 15000; h.useCaches = false
                h.setRequestProperty("User-Agent", "Hwallet")
                val body = try { h.inputStream.bufferedReader().use { it.readText().take(2000) } } finally { try { h.disconnect() } catch (_: Throwable) {} }
                loc = Regex("(?m)^loc=([A-Z]{2})").find(body)?.groupValues?.get(1) ?: ""
                ok = if (psi) Regex("(?m)^ip=").containsMatchIn(body) else Regex("(?m)^warp=(on|plus)").containsMatchIn(body)
                if (!ok) why = "trace says " + (Regex("(?m)^warp=\\w+").find(body)?.value ?: "nothing useful")
            } catch (e: Throwable) { why = (e.message ?: e.javaClass.simpleName).take(120) }
            ex.execute {
                verifying = false
                if (my != gen || !on || status == "connected") return@execute
                if (ok && !psi && region.isNotEmpty()) { log("hwvpn: WARP check passed (warp=on), starting Psiphon " + region); startPsi(my) }
                else if (ok) { log("hwvpn: tunnel check passed" + (if (psi) " (exit $loc)" else " (warp=on)")); connectedNow() }
                else {
                    if (n == 0 || n % 3 == 2) log("hwvpn: tunnel check failed: $why")
                    ex.schedule(Runnable { verify(my, n + 1, psi) }, 4, TimeUnit.SECONDS)
                }
            }
        }, "hwvpn-check").apply { isDaemon = true }.start()
    }

    /* ---------------- Psiphon (a country), riding usque ---------------- */

    /** Psiphon ConsoleClient config: the same public network values warp-plus v1.2.6 shipped for its cfon mode */
    private fun psiConfig(dir: File): String = JSONObject()
        .put("EgressRegion", region)
        .put("LocalSocksProxyPort", port)
        .put("DisableLocalHTTPProxy", true)
        .put("UpstreamProxyURL", "socks5://127.0.0.1:$uport")
        .put("PropagationChannelId", "FFFFFFFFFFFFFFFF")
        .put("SponsorId", "FFFFFFFFFFFFFFFF")
        .put("RemoteServerListDownloadFilename", "remote_server_list")
        .put("RemoteServerListSignaturePublicKey", PSI_KEY)
        .put("RemoteServerListUrl", "https://s3.amazonaws.com//psiphon/web/mjr4-p23r-puwl/server_list_compressed")
        .put("NetworkID", "hwallet")
        .put("ClientPlatform", "Android_4.0.4_com.example.exampleClientLibraryApp")
        .put("EstablishTunnelTimeoutSeconds", 90)
        .put("DataRootDirectory", dir.absolutePath)
        .toString()

    private fun startPsi(my: Int) {
        val c = app ?: return
        if (my != gen || !on || procP != null) return
        val b = binP(c)
        if (!b.exists()) { setStatus("error", "Countries aren\u2019t available in this build"); return }
        val dir = File(c.filesDir, "psiphon").apply { mkdirs() }
        val cf = File(dir, "config.json")
        try { cf.writeText(psiConfig(dir)) } catch (e: Throwable) { log("hwvpn: psiphon config: " + (e.message ?: "")); stopProc(); setStatus("reconnecting", "Couldn't start the VPN. Trying again\u2026"); schedule(); return }
        psiUp = true
        try {
            val pb = ProcessBuilder(arrayListOf(b.absolutePath, "-config", cf.absolutePath, "-dataRootDirectory", dir.absolutePath)).directory(dir).redirectErrorStream(true)
            pb.environment()["HOME"] = c.filesDir.absolutePath
            pb.environment()["TMPDIR"] = c.cacheDir.absolutePath
            val p = pb.start()
            procP = p
            Thread({ readPsi(p, my) }, "hwvpn-psi").apply { isDaemon = true }.start()
        } catch (e: Throwable) {
            procP = null
            log("hwvpn: could not start Psiphon: " + (e.message ?: e.javaClass.simpleName).take(160))
            stopProc(); setStatus("reconnecting", "Couldn't start the VPN. Trying again\u2026"); schedule(); return
        }
        progress(my, "Connecting to " + regionName(region) + "\u2026")
        /* the first run downloads Psiphon's server list through WARP; give it 2 minutes */
        dog(my, 120, "no " + regionName(region) + " connection after 2 min, trying again")
    }

    /** Psiphon notices are JSON lines on stderr: {"noticeType":"Tunnels","data":{"count":1}}, "ListeningSocksProxyPort",
     *  "EstablishTunnelTimeout", "Alert", "Error" ... Only the useful ones are logged. */
    private fun readPsi(p: Process, my: Int) {
        var last = ""
        try {
            p.inputStream.bufferedReader().use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    if (line.isBlank()) continue
                    val o = try { JSONObject(line) } catch (_: Throwable) { null }
                    val t = o?.optString("noticeType", "") ?: ""
                    val d = o?.optJSONObject("data")
                    when (t) {
                        "Tunnels" -> {
                            val n = d?.optInt("count", 0) ?: 0
                            log("psiphon: tunnels $n")
                            if (n > 0) ex.execute { if (my == gen && on && status != "connected") verify(my, 0, true) }
                            else ex.execute { if (my == gen && on && status == "connected") { setStatus("reconnecting", "Reconnecting to " + regionName(region) + "\u2026") } }
                        }
                        "ListeningSocksProxyPort" -> log("psiphon: listening on " + (d?.optInt("port") ?: 0))
                        "EstablishTunnelTimeout" -> { last = "psiphon timeout"; log("psiphon: no server answered in time") }
                        "CandidateServers" -> log("psiphon: " + (d?.optInt("count") ?: 0) + " candidate servers for " + region)
                        "RemoteServerListResourceDownloaded" -> log("psiphon: server list downloaded")
                        "AvailableEgressRegions" -> log("psiphon: regions " + (d?.optJSONArray("regions")?.toString()?.take(200) ?: ""))
                        "Alert", "Error", "Warning" -> { val m = d?.optString("message", "") ?: line; last = "psiphon " + m; log("psiphon: " + t.lowercase() + " " + m.take(240)) }
                        "" -> log("psiphon: " + line.take(240))
                    }
                }
            }
        } catch (_: Throwable) {}
        val code = try { p.waitFor() } catch (_: Throwable) { -1 }
        ex.execute {
            if (my != gen) return@execute
            log("hwvpn: psiphon exited (code $code)")
            procP = null
            if (!on) return@execute
            stopProc()
            setStatus("reconnecting", "Couldn't connect to " + regionName(region) + ". Trying again\u2026")
            schedule()
        }
    }

    private fun progress(my: Int, msg: String) {
        ex.execute { if (my == gen && on && status != "connected" && status != "off") setStatus(if (status == "error") "reconnecting" else status, msg) }
    }

    private fun connectedNow() {
        if (!probe(port)) { val my0 = gen; ex.schedule(Runnable { if (my0 == gen && on && status != "connected" && probe(port)) connectedNow() }, 1, TimeUnit.SECONDS); return }
        fresh()
        watchdog?.cancel(false); watchdog = null
        if (curVar != uVar) {
            uVar = curVar
            try { app?.getSharedPreferences(PREF, Context.MODE_PRIVATE)?.edit()?.putInt("uv", uVar)?.apply() } catch (_: Throwable) {}
        }
        log("hwvpn: connected (WARP MASQUE " + VARS[curVar] + (if (region.isNotEmpty()) ", Psiphon " + region else "") + ")")
        setStatus("connected", "")
        val my = gen
        health?.cancel(false)
        health = ex.scheduleWithFixedDelay(Runnable {
            if (my == gen && on && status == "connected" && (!probe(port) || (region.isNotEmpty() && !probe(uport)))) { log("hwvpn: local proxy stopped answering"); setStatus("reconnecting", "Lost the connection. Reconnecting\u2026"); restartProc() }
        }, 30, 30, TimeUnit.SECONDS)
    }

    /** backoff 2 s, 4 s, 8 s ... 30 s (each attempt already rotates the variant) */
    private fun schedule() {
        if (!on) return
        retry?.cancel(false)
        val d = minOf(30L, 2L shl minOf(tries, 4))
        tries++
        retry = ex.schedule(Runnable { if (on && proc == null) startProc() }, d, TimeUnit.SECONDS)
    }

    /** the raw engine error (kept in the log) as one plain-English line for the status row */
    private fun friendly(raw: String): String {
        val r = raw.lowercase()
        return when {
            r.contains("psiphon") -> "Couldn't connect to " + regionName(region) + ". Trying again\u2026"
            r.contains("x509") || r.contains("certificate") -> "Your network is interfering with the connection. Trying another way\u2026"
            r.contains("masque") || r.contains("quic") || r.contains("connect-ip") || r.contains("no recent network activity")
                || r.contains("deadline exceeded") || r.contains("timeout") || r.contains("i/o") || r.contains("unreachable")
                || r.contains("refused") || r.contains("no route") -> "Couldn't reach Cloudflare. Trying another way\u2026"
            r.contains("register") || r.contains("api.cloudflareclient") -> "Couldn't sign up with Cloudflare WARP. Trying again\u2026"
            else -> "Couldn't connect. Trying again\u2026"
        }
    }

    private const val PSI_KEY = "MIICIDANBgkqhkiG9w0BAQEFAAOCAg0AMIICCAKCAgEAt7Ls+/39r+T6zNW7GiVpJfzq/xvL9SBH5rIFnk0RXYEYavax3WS6HOD35eTAqn8AniOwiH+DOkvgSKF2caqk/y1dfq47Pdymtwzp9ikpB1C5OfAysXzBiwVJlCdajBKvBZDerV1cMvRzCKvKwRmvDmHgphQQ7WfXIGbRbmmk6opMBh3roE42KcotLFtqp0RRwLtcBRNtCdsrVsjiI1Lqz/lH+T61sGjSjQ3CHMuZYSQJZo/KrvzgQXpkaCTdbObxHqb6/+i1qaVOfEsvjoiyzTxJADvSytVtcTjijhPEV6XskJVHE1Zgl+7rATr/pDQkw6DPCNBS1+Y6fy7GstZALQXwEDN/qhQI9kWkHijT8ns+i1vGg00Mk/6J75arLhqcodWsdeG/M/moWgqQAnlZAGVtJI1OgeF5fsPpXu4kctOfuZlGjVZXQNW34aOzm8r8S0eVZitPlbhcPiR4gT/aSMz/wd8lZlzZYsje/Jr8u/YtlwjjreZrGRmG8KMOzukV3lLmMppXFMvl4bxv6YFEmIuTsOhbLTwFgh7KYNjodLj/LsqRVfwz31PgWQFTEPICV7GCvgVlPRxnofqKSjgTWI4mxDhBpVcATvaoBl1L/6WLbFvBsoAUBItWwctO2xalKxF5szhGm8lccoc5MZr8kfE0uxMgsxz4er68iCID+rsCAQM="

    /* ---------------- log: the last 200 VPN engine lines (keys redacted), for Settings > Network & VPN > View log ---------------- */
    private const val LOG_MAX = 200
    private val ring = ArrayDeque<String>(LOG_MAX)
    private val tf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
    private fun log(line: String) {
        val l = redact(line).take(600)
        synchronized(ring) {
            /* usque lines carry their own time; our own lines and Psiphon's get one */
            ring.addLast(if (l.startsWith("hwvpn:") || l.startsWith("psiphon:")) tf.format(java.util.Date()) + " " + l else l)
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
