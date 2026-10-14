package app.hwallet

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.sun.jna.Native
import com.sun.jna.Pointer
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * v1.8.12 (push 17, R1): Hwallet's native Monero engine (replaces the monero-ts WebAssembly scan when the library loads).
 *
 * The approach is Cake Wallet's cw_monero: monero_c's libwallet2_api_c (wallet2 compiled for Android, arm64-v8a /
 * armeabi-v7a / x86_64), a view-only wallet file in the app's private storage, wallet2's own refresh thread
 * (startRefresh + refreshAsync, auto refresh every 10 s) and plain polling of the heights. Measured on the same wallet,
 * node and block range: ~260 blocks/s native vs ~34 blocks/s for the WASM engine, and the hash fast-forward to the restore
 * height in ~5 s instead of ~33 s.
 *
 * It keeps going until the chain tip: it lives in the app process, not in the page, so it does not care about screens,
 * the WebView being paused or the screen locking. HwXmrService (foreground service + partial wake lock + Wi-Fi lock) keeps
 * the process alive while it catches up, restarts it after the process is killed (START_STICKY, the config is persisted
 * and the wallet file keeps the height it reached) and after a reboot (BootReceiver). Network blips: wallet2 retries on its
 * own every 10 s; the local relay below switches node with backoff; a watchdog re-arms the refresh when the height has
 * not moved for 45 s. The only thing that stops it is the user's explicit Pause (persisted).
 *
 * Node traffic: wallet2 talks plain HTTP to a relay on 127.0.0.1 (random port, loopback only) and the relay forwards each
 * request with Hwallet's own OkHttp client (Android's TLS and CA store, the Settings data limit, HTTPS nodes only), trying
 * the next node in the pool when one fails. The view key never leaves the phone; nodes only serve blocks.
 */
object HwXmrN {
    private const val PREF = "hw_xmrn"
    private const val KS_ALIAS = "hw_xmrn_v1"
    private const val LIB = "monero_wallet2_api_c"

    @Volatile private var lib: HwXmrLib? = null
    @Volatile var loadErr: String = ""; private set
    @Volatile private var tried = false
    @Volatile private var appCtx: Context? = null

    /* one thread for every wallet call of ours (wallet2's own refresh thread runs beside it) */
    private val ex: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "hwxmr").apply { isDaemon = true } }
    private var dog: ScheduledExecutorService? = null

    private var wm: Pointer? = null
    @Volatile private var w: Pointer? = null
    @Volatile private var key = ""
    @Volatile private var dir: File? = null

    /* status (read by the page without touching the library) */
    @Volatile var h = 0L; private set
    @Volatile var dh = 0L; private set
    @Volatile var startH = 0L; private set
    @Volatile var synced = false; private set
    @Volatile var paused = false; private set
    @Volatile var err = ""; private set
    @Volatile private var bps = 0.0
    @Volatile private var hold = 0L
    @Volatile private var lastMove = 0L
    @Volatile private var lastStore = 0L
    @Volatile private var kicks = 0
    private val win = ArrayDeque<LongArray>()

    /** called by the service on every change (progress notification, stop when done) */
    @Volatile var onTick: (() -> Unit)? = null

    fun lib(c: Context): HwXmrLib? {
        if (lib != null) return lib
        synchronized(this) {
            if (lib != null || tried) return lib
            tried = true; appCtx = c.applicationContext
            try {
                try { System.loadLibrary(LIB) } catch (_: Throwable) {}
                val path = File(c.applicationInfo.nativeLibraryDir, "lib$LIB.so")
                val l = if (path.exists()) Native.load(path.absolutePath, HwXmrLib::class.java) else Native.load(LIB, HwXmrLib::class.java)
                l.MONERO_WalletManagerFactory_setLogLevel(-1)
                wm = l.MONERO_WalletManagerFactory_getWalletManager() ?: throw IllegalStateException("no wallet manager")
                lib = l
            } catch (e: Throwable) { loadErr = e.toString().take(300); HwApp.write(c, "note: native Monero engine not available, the WebAssembly engine is used", loadErr) }
        }
        return lib
    }
    fun available(c: Context): Boolean = lib(c) != null

    private fun b(v: Byte) = v.toInt() != 0
    private fun t(v: Boolean): Byte = if (v) 1 else 0

    /* ---------- the loopback relay to the node pool ---------- */
    private object Relay {
        @Volatile var nodes: List<String> = emptyList()
        @Volatile var fixed = ""
        @Volatile var cur = 0
        @Volatile var port = 0
        @Volatile var bytes = 0L
        @Volatile var lastOk = 0L
        @Volatile var lastErr = ""
        private var ss: ServerSocket? = null
        private val http by lazy { HwNet.withTimeout(30000) }

        fun node(): String { val f = fixed; if (f.isNotEmpty()) return f; val n = nodes; return if (n.isEmpty()) "" else n[Math.floorMod(cur, n.size)] }
        fun rotate() { if (fixed.isEmpty() && nodes.size > 1) cur = (cur + 1) % nodes.size }

        @Synchronized fun start(): Int {
            if (ss != null && !ss!!.isClosed) return port
            val s = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
            ss = s; port = s.localPort
            Thread({ while (!s.isClosed) { try { val c = s.accept(); Thread({ serve(c) }, "hwxmr-relay").apply { isDaemon = true }.start() } catch (_: Throwable) { if (s.isClosed) break } } }, "hwxmr-accept").apply { isDaemon = true }.start()
            return port
        }

        private fun line(i: InputStream): String? {
            val sb = StringBuilder(); var n = 0
            while (true) { val c = i.read(); if (c < 0) return if (sb.isEmpty()) null else sb.toString(); if (c == '\n'.code) break; if (c != '\r'.code) sb.append(c.toChar()); if (++n > 16384) return null }
            return sb.toString()
        }

        private fun serve(c: Socket) {
            try {
                c.soTimeout = 300000; c.tcpNoDelay = true
                val i = BufferedInputStream(c.getInputStream()); val o = c.getOutputStream()
                while (true) {
                    val rl = line(i) ?: break
                    val p = rl.split(' ')
                    if (p.size < 2) break
                    val method = p[0].uppercase(); val path = p[1]
                    var len = 0; var ct = "application/json"; var close = false
                    while (true) {
                        val hl = line(i) ?: return
                        if (hl.isEmpty()) break
                        val k = hl.substringBefore(':').trim().lowercase(); val v = hl.substringAfter(':', "").trim()
                        when (k) { "content-length" -> len = v.toIntOrNull() ?: 0; "content-type" -> ct = v; "connection" -> close = v.equals("close", true) }
                    }
                    if (len < 0 || len > 64 * 1024 * 1024) return
                    val body = ByteArray(len); var got = 0
                    while (got < len) { val r = i.read(body, got, len - got); if (r < 0) return; got += r }
                    val (st, out, oct) = forward(method, path, ct, body)
                    val head = "HTTP/1.1 $st " + (if (st == 200) "OK" else "Error") + "\r\nContent-Type: $oct\r\nContent-Length: ${out.size}\r\nConnection: " + (if (close) "close" else "keep-alive") + "\r\n\r\n"
                    o.write(head.toByteArray(Charsets.US_ASCII)); o.write(out); o.flush()
                    if (close) break
                }
            } catch (_: Throwable) {} finally { try { c.close() } catch (_: Throwable) {} }
        }

        /** one request to the node; on failure the next node of the pool, with backoff (0.5 s, 1 s, 2 s ... 8 s) */
        fun forward(method: String, path: String, ct: String, body: ByteArray): Triple<Int, ByteArray, String> {
            if (!path.startsWith("/") || path.contains("..") || path.length > 200) return Triple(400, ByteArray(0), "text/plain")
            val n = maxOf(1, nodes.size)
            var lastSt = 502
            for (a in 0 until n * 2) {
                val base = node()
                if (base.isEmpty() || !base.startsWith("https://")) break
                try {
                    val rb = if (method == "POST") body.toRequestBody(ct.toMediaTypeOrNull()) else null
                    val rq = Request.Builder().url(base + path).method(if (method == "POST") "POST" else "GET", rb).header("User-Agent", "Hwallet").build()
                    http.newCall(rq).execute().use { r ->
                        val b = r.body?.bytes() ?: ByteArray(0)
                        if (r.code in 200..499 && !(r.code == 429)) {
                            bytes += b.size; lastOk = System.currentTimeMillis(); lastErr = ""
                            return Triple(r.code, b, r.header("Content-Type") ?: (if (path.endsWith(".bin")) "application/octet-stream" else "application/json"))
                        }
                        lastSt = r.code; lastErr = "HTTP " + r.code
                    }
                } catch (e: Throwable) { lastErr = e.javaClass.simpleName }
                rotate()
                try { Thread.sleep(minOf(8000L, 500L shl minOf(a, 4))) } catch (_: InterruptedException) { break }
            }
            return Triple(lastSt, ByteArray(0), "text/plain")
        }
    }

    /* ---------- persisted config (the view key sealed with an Android Keystore key) ---------- */
    private fun ksKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KS_ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(KS_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return g.generateKey()
    }
    private fun seal(s: String): String = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, ksKey())
        "k1:" + Base64.encodeToString(c.iv + c.doFinal(s.toByteArray()), Base64.NO_WRAP)
    } catch (_: Throwable) { "p0:$s" }
    private fun unseal(s: String): String = try {
        if (s.startsWith("p0:")) s.substring(3) else {
            val b = Base64.decode(s.removePrefix("k1:"), Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, ksKey(), GCMParameterSpec(128, b, 0, 12))
            String(c.doFinal(b, 12, b.size - 12))
        }
    } catch (_: Throwable) { "" }

    private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    fun wanted(c: Context): Boolean = try { prefs(c).getBoolean("want", false) && !prefs(c).getBoolean("paused", false) && prefs(c).getString("cfg", null) != null } catch (_: Throwable) { false }

    private fun saveCfg(c: Context, cfg: JSONObject) {
        val o = JSONObject(cfg.toString()); o.put("view", seal(cfg.optString("view")))
        prefs(c).edit().putString("cfg", o.toString()).putBoolean("want", true).apply()
    }
    private fun loadCfg(c: Context): JSONObject? {
        val raw = try { prefs(c).getString("cfg", null) } catch (_: Throwable) { null } ?: return null
        return try { val o = JSONObject(raw); o.put("view", unseal(o.optString("view"))); if (o.optString("view").length == 64) o else null } catch (_: Throwable) { null }
    }

    private fun sha(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)

    /* ---------- page ops (JSON in, JSON out); every one runs on the hwxmr thread ---------- */
    fun call(c: Context, op: String, args: String, done: (Boolean, String) -> Unit) {
        ex.execute {
            try {
                val a = try { JSONObject(args) } catch (_: Exception) { JSONObject() }
                val r: Any? = when (op) {
                    "open" -> open(c, a)
                    "resume" -> { resume(c); true }
                    "state" -> state()
                    "pause" -> { setPaused(c, a.optBoolean("on")); true }
                    "stop" -> { setPaused(c, true); true }
                    "server", "nodes" -> { nodes(a); true }
                    "newSub" -> newSub(a.optString("label"))
                    "label" -> { lw().MONERO_Wallet_setSubaddressLabel(wp(), 0, a.optInt("i"), a.optString("label").take(40)); store(true); true }
                    "build" -> build(c, a)
                    "submit" -> submit(c, a)
                    "importKI" -> importKI(a)
                    "outputs" -> JSONObject().put("outputs", outputsHex(c))
                    "release" -> { hold = 0; arm(); true }
                    "save" -> { store(true); true }
                    "close" -> { close(true); true }
                    "forget" -> { forget(c); true }
                    else -> throw IllegalArgumentException("Unknown op $op")
                }
                done(true, if (r is JSONObject) r.toString() else if (r == null) "null" else r.toString())
            } catch (e: Throwable) {
                done(false, JSONObject().put("e", (e.message ?: e.toString()).take(400)).toString())
            }
        }
    }

    /** cheap status for the page's 1 s poll: cached values only, never a library call */
    fun status(): String {
        val o = JSONObject().put("open", w != null).put("eng", "native").put("h", h).put("dh", dh).put("start", startH).put("synced", synced).put("paused", paused)
        val left = if (dh > h) dh - h else 0L
        val span = maxOf(1L, dh - startH)
        o.put("left", left).put("pct", if (dh <= 0) 0.0 else if (synced) 1.0 else ((h - startH).toDouble() / span).coerceIn(0.0, 0.999))
        o.put("bps", Math.round(bps)).put("eta", if (bps > 0.5 && left > 0) Math.round(left / bps) else -1L)
        o.put("node", Relay.node().removePrefix("https://")).put("bytes", Relay.bytes).put("err", err).put("hold", hold > System.currentTimeMillis())
        return o.toString()
    }

    private fun lw(): HwXmrLib = lib ?: throw IllegalStateException("The native Monero engine is not available")
    private fun wp(): Pointer = w ?: throw IllegalStateException("Monero wallet is not open")
    private fun werr(): String = (try { lw().MONERO_Wallet_errorString(wp()) } catch (_: Throwable) { null } ?: "").ifEmpty { "Monero engine error" }

    private fun nodes(a: JSONObject) {
        val arr = a.optJSONArray("nodes"); val l = ArrayList<String>()
        if (arr != null) for (i in 0 until arr.length()) { val u = arr.optString(i).trimEnd('/'); if (Regex("^https://[A-Za-z0-9.-]+(:\\d{2,5})?$").matches(u)) l.add(u) }
        if (l.isNotEmpty()) Relay.nodes = l
        val f = a.optString("server", a.optString("uri", "")).trimEnd('/')
        Relay.fixed = if (a.optBoolean("custom") && f.startsWith("https://")) f else ""
        if (!a.optBoolean("custom") && f.isNotEmpty()) { val i = Relay.nodes.indexOf(f); if (i >= 0) Relay.cur = i }
    }

    private fun open(c: Context, a: JSONObject): JSONObject? {
        val L = lib(c) ?: throw IllegalStateException("The native Monero engine is not available: $loadErr")
        val addr = a.optString("address"); val view = a.optString("view").lowercase()
        if (!Regex("^4[1-9A-HJ-NP-Za-km-z]{94}$").matches(addr)) throw IllegalArgumentException("Need the primary Monero address (starts with 4)")
        if (!Regex("^[0-9a-f]{64}$").matches(view)) throw IllegalArgumentException("Need the private view key")
        val height = maxOf(0L, a.optLong("height", 0)); val acc = a.optInt("accLook", 1).coerceIn(1, 50); val sub = a.optInt("subLook", 3).coerceIn(3, 5000)
        val pw = a.optString("pw").ifEmpty { "hw" }
        nodes(a)
        val k = sha("$addr:$height:${acc}x$sub")
        if (w != null && key == k) { a.put("view", view); saveCfg(c, a); paused = prefs(c).getBoolean("paused", false); arm(); return state() }
        close(true)
        val d = File(c.filesDir, "xmrn/$k"); d.mkdirs()
        /* other wallets of this address (another start height / lookahead) only take space */
        try { File(c.filesDir, "xmrn").listFiles()?.forEach { f -> if (f.isDirectory && f.name != k && File(f, "addr").let { x -> x.exists() && x.readText() == addr }) f.deleteRecursively() } } catch (_: Throwable) {}
        val path = File(d, "w").absolutePath
        var p: Pointer? = null
        if (File(d, "w.keys").exists()) {
            p = L.MONERO_WalletManager_openWallet(wm!!, path, pw, 0)
            if (p == null || L.MONERO_Wallet_status(p) != 0) { try { if (p != null) L.MONERO_WalletManager_closeWallet(wm!!, p, 0) } catch (_: Throwable) {}; p = null; d.deleteRecursively(); d.mkdirs() }
        }
        if (p == null) {
            p = L.MONERO_WalletManager_createWalletFromKeys(wm!!, path, pw, "English", 0, height, addr, view, "", 1)
            if (p == null) throw IllegalStateException("The Monero wallet could not be created")
            if (L.MONERO_Wallet_status(p) != 0) { val e = L.MONERO_Wallet_errorString(p) ?: "error"; try { L.MONERO_WalletManager_closeWallet(wm!!, p, 0) } catch (_: Throwable) {}; d.deleteRecursively(); throw IllegalStateException("Monero wallet: $e") }
            L.MONERO_Wallet_setRefreshFromBlockHeight(p, height)
        }
        if (L.MONERO_Wallet_address(p, 0, 0) != addr) { L.MONERO_WalletManager_closeWallet(wm!!, p, 0); d.deleteRecursively(); throw IllegalStateException("The view key does not belong to this address") }
        File(d, "addr").writeText(addr)
        L.MONERO_Wallet_setSubaddressLookahead(p, acc, maxOf(sub, 50))
        var n = L.MONERO_Wallet_numSubaddresses(p, 0).toLong(); var guard = 0
        while (n < sub && guard++ < 5000) { L.MONERO_Wallet_addSubaddress(p, 0, ""); n = L.MONERO_Wallet_numSubaddresses(p, 0).toLong() }
        val port = Relay.start()
        L.MONERO_Wallet_init(p, "127.0.0.1:$port", 0, "", "", 0, 0, "")
        L.MONERO_Wallet_setTrustedDaemon(p, 0)
        w = p; key = k; dir = d; startH = L.MONERO_Wallet_getRefreshFromBlockHeight(p); h = L.MONERO_Wallet_blockChainHeight(p); dh = 0; synced = false; err = ""; bps = 0.0
        synchronized(win) { win.clear() }
        L.MONERO_Wallet_store(p, "")
        a.put("view", view); saveCfg(c, a)
        paused = prefs(c).getBoolean("paused", false)
        startDog(); arm()
        if (!paused) HwXmrService.kick(c)
        return state()
    }

    /** reopen the persisted wallet (service restart, boot, page reload) and go on from the height it reached */
    fun resume(c: Context) {
        if (w != null) { paused = prefs(c).getBoolean("paused", false); arm(); if (!synced && !paused) HwXmrService.kick(c); return }
        val cfg = loadCfg(c) ?: throw IllegalStateException("Monero wallet is not open")
        open(c, cfg)
    }
    fun resumeAsync(c: Context) { ex.execute { try { if (lib(c) != null && loadCfg(c) != null) resume(c) } catch (e: Throwable) { err = (e.message ?: "").take(200) } } }

    /** refresh on unless the user paused or a send holds the wallet */
    private fun arm() {
        val L = lib ?: return; val p = w ?: return
        if (paused || hold > System.currentTimeMillis()) { L.MONERO_Wallet_pauseRefresh(p); return }
        L.MONERO_Wallet_setAutoRefreshInterval(p, 10000)
        L.MONERO_Wallet_startRefresh(p); L.MONERO_Wallet_refreshAsync(p)
        lastMove = System.currentTimeMillis()
    }

    private fun setPaused(c: Context, on: Boolean) {
        paused = on; prefs(c).edit().putBoolean("paused", on).apply()
        val L = lib ?: return; val p = w ?: return
        if (on) { L.MONERO_Wallet_pauseRefresh(p); store(true) } else { err = ""; kicks = 0; arm(); HwXmrService.kick(c) }
        onTick?.invoke()
    }

    private fun store(force: Boolean) {
        val L = lib ?: return; val p = w ?: return
        if (!force && System.currentTimeMillis() - lastStore < 90000) return
        lastStore = System.currentTimeMillis()
        try { L.MONERO_Wallet_store(p, "") } catch (_: Throwable) {}
    }

    fun close(save: Boolean) {
        val L = lib ?: return; val p = w ?: return
        w = null; key = ""; synced = false
        try { L.MONERO_Wallet_pauseRefresh(p) } catch (_: Throwable) {}
        try { L.MONERO_WalletManager_closeWallet(wm!!, p, t(save)) } catch (_: Throwable) {}
    }

    private fun forget(c: Context) {
        close(false)
        try { File(c.filesDir, "xmrn").deleteRecursively() } catch (_: Throwable) {}
        prefs(c).edit().remove("cfg").putBoolean("want", false).apply()
        h = 0; dh = 0; startH = 0; err = ""
        onTick?.invoke()
    }

    /* ---------- the watchdog: heights, speed, stall re-arm, periodic save ---------- */
    private fun startDog() {
        if (dog != null) return
        dog = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "hwxmr-dog").apply { isDaemon = true } }
        dog!!.scheduleWithFixedDelay({ try { tick() } catch (_: Throwable) {} }, 1, 1, TimeUnit.SECONDS)
    }
    private var dhAt = 0L
    private fun tick() {
        val L = lib ?: return; val p = w ?: return
        val now = System.currentTimeMillis()
        val nh = L.MONERO_Wallet_blockChainHeight(p)
        if (now - dhAt > 4000 || dh == 0L) { dhAt = now; val d = L.MONERO_Wallet_daemonBlockChainHeight(p); if (d > 0) dh = maxOf(d, if (d + 10 < dh) d else dh) }
        if (nh != h) { lastMove = now; kicks = 0; if (err.isNotEmpty() && nh > h) err = "" }
        h = nh
        synchronized(win) { win.addLast(longArrayOf(now, nh)); while (win.size > 2 && now - win.first()[0] > 20000) win.removeFirst()
            val f = win.first(); bps = if (now - f[0] > 1500 && nh > f[1]) (nh - f[1]) * 1000.0 / (now - f[0]) else if (nh == f[1] && now - f[0] > 8000) 0.0 else bps }
        val wasSynced = synced
        synced = dh > 0 && h >= dh && b(L.MONERO_Wallet_synchronized(p))
        if (synced && !wasSynced) { store(true); err = "" }
        else if (!synced) store(false)
        if (!paused && hold <= now && !synced && now - lastMove > 45000) {
            kicks++; lastMove = now
            err = if (Relay.lastErr.isNotEmpty()) "Node not answering (" + Relay.node().removePrefix("https://") + "), retrying" else "Waiting for the node, retrying"
            if (kicks % 2 == 0) Relay.rotate()
            arm()
        }
        if (hold in 1..now) { hold = 0; arm() }
        onTick?.invoke()
    }

    /* ---------- state for the page (same shape as the WASM engine's hState) ---------- */
    private fun state(): JSONObject? {
        val L = lib ?: return null; val p = w ?: return null
        val coins = L.MONERO_Wallet_coins(p); val per = HashMap<String, Array<BigInteger>>(); val used = HashSet<String>(); var kiMissing = 0
        val accs = sortedSetOf(0)
        if (coins != null) {
            L.MONERO_Coins_refresh(coins)
            for (i in 0 until L.MONERO_Coins_count(coins)) {
                val ci = L.MONERO_Coins_coin(coins, i) ?: continue
                val ak = L.MONERO_CoinsInfo_subaddrAccount(ci); val ik = L.MONERO_CoinsInfo_subaddrIndex(ci); val k = "$ak/$ik"
                used.add(k); accs.add(ak)
                if (b(L.MONERO_CoinsInfo_spent(ci)) || b(L.MONERO_CoinsInfo_frozen(ci))) continue
                val amt = BigInteger(java.lang.Long.toUnsignedString(L.MONERO_CoinsInfo_amount(ci)))
                val e = per.getOrPut(k) { arrayOf(BigInteger.ZERO, BigInteger.ZERO) }
                e[0] = e[0].add(amt); if (b(L.MONERO_CoinsInfo_unlocked(ci))) e[1] = e[1].add(amt)
                if (!b(L.MONERO_CoinsInfo_keyImageKnown(ci))) kiMissing++
            }
        }
        fun u(v: Long) = java.lang.Long.toUnsignedString(v)
        var bal = BigInteger.ZERO; var unl = BigInteger.ZERO
        for (ai in accs) { bal = bal.add(BigInteger(u(L.MONERO_Wallet_balance(p, ai)))); unl = unl.add(BigInteger(u(L.MONERO_Wallet_unlockedBalance(p, ai)))) }
        val subs = JSONArray()
        val n0 = L.MONERO_Wallet_numSubaddresses(p, 0).toInt()
        for (i in 0 until minOf(n0, 400)) {
            val k = "0/$i"; val e = per[k]
            subs.put(JSONObject().put("acc", 0).put("i", i).put("a", L.MONERO_Wallet_address(p, 0, i.toLong()) ?: "").put("l", L.MONERO_Wallet_getSubaddressLabel(p, 0, i) ?: "")
                .put("b", (e?.get(0) ?: BigInteger.ZERO).toString()).put("u", (e?.get(1) ?: BigInteger.ZERO).toString()).put("used", used.contains(k)))
        }
        for (k in used) { val ai = k.substringBefore('/').toInt(); val ii = k.substringAfter('/').toInt(); if (ai == 0 && ii < n0) continue
            val e = per[k]; subs.put(JSONObject().put("acc", ai).put("i", ii).put("a", L.MONERO_Wallet_address(p, ai.toLong(), ii.toLong()) ?: "").put("l", "").put("b", (e?.get(0) ?: BigInteger.ZERO).toString()).put("u", (e?.get(1) ?: BigInteger.ZERO).toString()).put("used", true)) }
        val txs = ArrayList<JSONObject>()
        val hist = L.MONERO_Wallet_history(p)
        if (hist != null) {
            L.MONERO_TransactionHistory_refresh(hist)
            for (i in 0 until L.MONERO_TransactionHistory_count(hist)) {
                val ti = L.MONERO_TransactionHistory_transaction(hist, i) ?: continue
                val out = L.MONERO_TransactionInfo_direction(ti) == 1
                val amt = u(L.MONERO_TransactionInfo_amount(ti)); val fee = u(L.MONERO_TransactionInfo_fee(ti))
                val conf = L.MONERO_TransactionInfo_confirmations(ti); val pend = b(L.MONERO_TransactionInfo_isPending(ti))
                val to = JSONArray()
                if (out) for (j in 0 until L.MONERO_TransactionInfo_transfers_count(ti)) to.put(JSONObject().put("a", L.MONERO_TransactionInfo_transfers_address(ti, j) ?: "").put("v", u(L.MONERO_TransactionInfo_transfers_amount(ti, j))))
                val sa = L.MONERO_TransactionInfo_subaddrAccount(ti); val sub = JSONArray()
                (L.MONERO_TransactionInfo_subaddrIndex(ti, ",") ?: "").split(',').mapNotNull { it.trim().toIntOrNull() }.forEach { x -> if (sa == 0) sub.put(x) else sub.put("$sa/$x") }
                val ts = L.MONERO_TransactionInfo_timestamp(ti) * 1000L
                txs.add(JSONObject().put("hash", L.MONERO_TransactionInfo_hash(ti) ?: "").put("h", L.MONERO_TransactionInfo_blockHeight(ti)).put("ts", if (ts > 0) ts else if (pend) System.currentTimeMillis() else 0)
                    .put("conf", conf).put("pool", pend).put("failed", b(L.MONERO_TransactionInfo_isFailed(ti))).put("locked", !out && conf < 10)
                    .put("fee", fee).put("inA", if (out) "0" else amt).put("outA", if (out) BigInteger(amt).add(BigInteger(fee)).toString() else "0")
                    .put("dir", if (out) "out" else "in").put("to", to).put("sub", sub))
            }
        }
        txs.sortWith(compareByDescending<JSONObject> { if (it.optLong("ts") == 0L) 9e15.toLong() else it.optLong("ts") }.thenByDescending { it.optLong("h") })
        val ta = JSONArray(); txs.take(400).forEach { ta.put(it) }
        val st = JSONObject().put("height", h).put("daemon", maxOf(dh, h)).put("bal", bal.toString()).put("unl", unl.toString())
            .put("unl0", u(L.MONERO_Wallet_unlockedBalance(p, 0))).put("subs", subs).put("txs", ta).put("kiMissing", kiMissing).put("at", System.currentTimeMillis()).put("eng", "native")
        if (paused) st.put("paused", 1)
        return st
    }

    private fun newSub(label: String): JSONObject {
        val L = lw(); val p = wp()
        L.MONERO_Wallet_addSubaddress(p, 0, label.take(40))
        val i = L.MONERO_Wallet_numSubaddresses(p, 0).toInt() - 1
        store(true)
        return JSONObject().put("i", i).put("a", L.MONERO_Wallet_address(p, 0, i.toLong()) ?: "").put("l", L.MONERO_Wallet_getSubaddressLabel(p, 0, i) ?: "")
    }

    private fun hex(b: ByteArray): String { val sb = StringBuilder(b.size * 2); for (x in b) { val v = x.toInt() and 0xff; sb.append("0123456789abcdef"[v shr 4]).append("0123456789abcdef"[v and 15]) }; return sb.toString() }
    private fun unhex(s: String): ByteArray { val t = s.trim(); require(t.length % 2 == 0 && Regex("^[0-9a-fA-F]*$").matches(t)) { "bad hex" }; return ByteArray(t.length / 2) { ((Character.digit(t[it * 2], 16) shl 4) or Character.digit(t[it * 2 + 1], 16)).toByte() } }
    private fun tmp(c: Context, n: String) = File(c.cacheDir, "xmrn-$n-" + System.nanoTime()).also { it.parentFile?.mkdirs() }

    private fun outputsHex(c: Context): String {
        val L = lw(); val p = wp(); val f = tmp(c, "outs")
        try { if (!b(L.MONERO_Wallet_exportOutputs(p, f.absolutePath, 1)) || !f.exists()) throw IllegalStateException("Exporting outputs failed: " + werr()); return hex(f.readBytes()) } finally { f.delete() }
    }

    private val PRI = mapOf("slow" to 1, "medium" to 2, "fast" to 3, "fastest" to 4)
    private fun build(c: Context, a: JSONObject): JSONObject {
        val L = lw(); val p = wp()
        if (!synced) throw IllegalStateException("Monero is still syncing (" + maxOf(0L, dh - h) + " blocks left). Sending unlocks when it reaches the chain tip")
        hold = System.currentTimeMillis() + 240000; L.MONERO_Wallet_pauseRefresh(p)
        val to = a.optString("to"); val pri = PRI[a.optString("level")] ?: 2
        var acc = 0
        val pt: Pointer?
        if (a.optBoolean("max")) pt = L.MONERO_Wallet_createTransactionMultDest(p, to, ";", "", 1, "", ";", 0, pri, 0, "", ";")
        else {
            val amt = BigInteger(a.optString("amount", "0"))
            if (amt.signum() <= 0 || amt.bitLength() > 63) throw IllegalArgumentException("Bad amount")
            try { if (BigInteger(java.lang.Long.toUnsignedString(L.MONERO_Wallet_unlockedBalance(p, 0))) < amt) {
                for (ai in 1 until 50) { if (BigInteger(java.lang.Long.toUnsignedString(L.MONERO_Wallet_unlockedBalance(p, ai))) > amt) { acc = ai; break } } } } catch (_: Throwable) { acc = 0 }
            pt = L.MONERO_Wallet_createTransaction(p, to, "", amt.toLong(), 0, pri, acc, "", ";")
        }
        if (pt == null) throw IllegalStateException("The wallet did not make a transaction")
        if (L.MONERO_PendingTransaction_status(pt) != 0) throw IllegalStateException((L.MONERO_PendingTransaction_errorString(pt) ?: "Building failed").take(300))
        val f = tmp(c, "unsigned")
        try {
            if (!b(L.MONERO_PendingTransaction_commit(pt, f.absolutePath, 1)) || L.MONERO_PendingTransaction_status(pt) != 0 || !f.exists()) throw IllegalStateException("Saving the unsigned transaction failed: " + (L.MONERO_PendingTransaction_errorString(pt) ?: ""))
            val raw = f.readBytes()
            if (!String(raw, 0, minOf(22, raw.size), Charsets.ISO_8859_1).startsWith("Monero unsigned tx set")) throw IllegalStateException("The wallet did not make an unsigned transaction")
            val fee = java.lang.Long.toUnsignedString(L.MONERO_PendingTransaction_fee(pt)); val amt = java.lang.Long.toUnsignedString(L.MONERO_PendingTransaction_amount(pt))
            return JSONObject().put("unsigned", hex(raw)).put("outputs", outputsHex(c)).put("fee", fee).put("amount", amt).put("n", L.MONERO_PendingTransaction_txCount(pt)).put("weight", 0)
        } finally { f.delete() }
    }

    private fun submit(c: Context, a: JSONObject): JSONObject {
        val L = lw(); val p = wp(); val f = tmp(c, "signed")
        try {
            f.writeBytes(unhex(a.optString("signed")))
            /* wallet2 also imports every key image Kwallet put in the signed set, so spends are known right away */
            if (!b(L.MONERO_Wallet_submitTransaction(p, f.absolutePath))) throw IllegalStateException(werr())
        } finally { f.delete(); hold = 0 }
        store(true); arm()
        return JSONObject().put("hashes", JSONArray()).put("state", state())
    }

    /** "Sync spent status": Kwallet's signed key images (index = offset + i, wallet2's output order). The node says which are
     *  spent; spent outputs are frozen, so they leave the balance and are never picked for a send (wallet2 cannot import
     *  key images into a view-only wallet without the file format, which needs the spend side to encrypt). */
    private fun importKI(a: JSONObject): JSONObject {
        val L = lw(); val p = wp()
        val ki = a.optJSONArray("ki") ?: JSONArray(); val off = a.optInt("offset", 0)
        val coins = L.MONERO_Wallet_coins(p) ?: throw IllegalStateException("No outputs")
        L.MONERO_Coins_refresh(coins)
        val byIdx = HashMap<Long, Pointer>()
        for (i in 0 until L.MONERO_Coins_count(coins)) { val ci = L.MONERO_Coins_coin(coins, i) ?: continue; byIdx[L.MONERO_CoinsInfo_internalOutputIndex(ci).toLong()] = ci }
        val ask = ArrayList<Pair<String, Pointer>>()
        for (i in 0 until ki.length()) {
            val hx = ki.optJSONObject(i)?.optString("hex") ?: ki.optString(i)
            if (!Regex("^[0-9a-f]{64}$").matches(hx)) continue
            val ci = byIdx[(off + i).toLong()] ?: continue
            if (b(L.MONERO_CoinsInfo_spent(ci)) || b(L.MONERO_CoinsInfo_frozen(ci))) continue
            ask.add(hx to ci)
        }
        var spent = BigInteger.ZERO; var unspent = BigInteger.ZERO
        for (chunk in ask.chunked(100)) {
            val body = JSONObject().put("key_images", JSONArray(chunk.map { it.first })).toString().toByteArray()
            val (st, out, _) = Relay.forward("POST", "/is_key_image_spent", "application/json", body)
            if (st != 200) throw IllegalStateException("The Monero node did not answer (HTTP $st)")
            val arr = JSONObject(String(out)).optJSONArray("spent_status") ?: throw IllegalStateException("The Monero node gave no spent status")
            for (j in chunk.indices) {
                val ci = chunk[j].second; val amt = BigInteger(java.lang.Long.toUnsignedString(L.MONERO_CoinsInfo_amount(ci)))
                if (arr.optInt(j, 0) != 0) { L.MONERO_Coins_setFrozenByPublicKey(coins, L.MONERO_CoinsInfo_pubKey(ci) ?: continue); spent = spent.add(amt) } else unspent = unspent.add(amt)
            }
        }
        store(true)
        return JSONObject().put("spent", spent.toString()).put("unspent", unspent.toString()).put("state", state())
    }
}
