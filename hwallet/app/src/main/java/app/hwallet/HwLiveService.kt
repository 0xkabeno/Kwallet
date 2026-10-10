package app.hwallet

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Hwallet always-on listener (v1.2). A foreground service that keeps prices, balances and incoming
 * payments live while the app is closed:
 *  - a sleek custom notification: BTC, ETH, SOL, XMR logos with live price and coloured 24h change;
 *  - WebSocket subscriptions (EVM new blocks, Solana account, mempool.space address, XRPL account) that
 *    trigger an immediate balance check, plus polling at the chosen background interval (down to 1 s);
 *  - an alert with sound when any watched balance goes up.
 * Holds a partial wake lock and a Wi-Fi lock while running, restarts itself after being swiped away
 * and after reboot (BootReceiver). Watch-only: it only ever reads public addresses.
 */
class HwLiveService : Service() {
    companion object {
        const val CH_LIVE = "hw_live"
        /* v1.3 (#47): same notification, no status-bar icon. Channel importance can't change after creation, so it is a second channel */
        const val CH_QUIET = "hw_live_quiet"
        const val CH_IN = "hw_in"
        const val NID = 7101
        const val PREF = "hw_live"

        fun enabled(c: Context): Boolean {
            val cfg = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("cfg", null) ?: return false
            return try { val o = JSONObject(cfg); o.optBoolean("on") && (o.optInt("bg", 5) > 0 || o.optBoolean("notif", true)) } catch (_: Exception) { false }
        }

        /** v1.3 (#42): from the app (visible activity) a plain startService: no "must call startForeground within N s" contract,
         *  so a refused promotion can never crash the app. From the background (boot, restart alarm) startForegroundService. */
        fun start(c: Context, fromApp: Boolean = false) {
            try {
                if (!enabled(c)) { stop(c); return }
                val i = Intent(c, HwLiveService::class.java)
                if (fromApp || Build.VERSION.SDK_INT < 26) c.startService(i) else ContextCompat.startForegroundService(c, i)
            } catch (e: Throwable) { HwApp.write(c, "note: live service not started (" + (if (fromApp) "app" else "background") + ")", e.toString()) }
        }
        fun sbar(c: Context): Boolean = try { JSONObject(c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("cfg", null) ?: "{}").optBoolean("sbar", true) } catch (_: Exception) { true }

        fun stop(c: Context) { try { c.stopService(Intent(c, HwLiveService::class.java)) } catch (_: Exception) {} }
    }

    /* built lazily: nothing heavy runs before the service is in the foreground */
    /* v1.4 (#53): Hwallet's one network stack, so the Settings data speed limit covers the service too */
    private val http by lazy { HwNet.withTimeout(10000) }
    /* v1.4 (#55): background activity: every watched chain's history goes into the persistent activity cache */
    private val actDb by lazy { HwActDb.get(this) }
    private val hist by lazy { HwHist(actDb) }
    private val histAt = ConcurrentHashMap<String, Long>()
    private val histBusy = ConcurrentHashMap<String, Boolean>()
    private val histBack = ConcurrentHashMap<String, Long>()
    @Volatile private var catchUp = true
    private val JSON_T = "application/json".toMediaType()
    private var exec: ScheduledExecutorService? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private val sockets = ArrayList<WebSocket>()
    @Volatile private var cfg = JSONObject()
    @Volatile private var running = false

    /* prices: 4 coins for the notification */
    private val coins = arrayOf("bitcoin", "ethereum", "solana", "monero")
    private val px = DoubleArray(4)
    private val pct = arrayOfNulls<Double>(4)
    private var cgAt = 0L
    private var lastNote = ""
    private var lastNoteAt = 0L

    /* balances: key -> base units; per-chain timing + backoff */
    private val snap = ConcurrentHashMap<String, BigInteger>()
    private val nextAt = ConcurrentHashMap<String, Long>()
    private val backoff = ConcurrentHashMap<String, Long>()
    private val busy = ConcurrentHashMap<String, Boolean>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        /* v1.3: foreground first, before anything else can fail or take time */
        try { channels() } catch (_: Throwable) {}
        goForeground()
        try { HwNet.load(this) } catch (_: Throwable) {}
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Hwallet:live").apply { setReferenceCounted(false); acquire() }
        } catch (_: Throwable) {}
        try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifi = wm.createWifiLock(if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_HIGH_PERF else WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Hwallet:live").apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) {}
        loadSnap()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        try { reload() } catch (e: Throwable) { HwApp.write(this, "note: live service reload failed", e.toString()) }
        return START_STICKY
    }

    /** swiped away from Recents: come straight back */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!enabled(this)) return
        try {
            if (!(getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)) return
            val it = Intent(this, HwLiveService::class.java)
            val fl = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val pi = if (Build.VERSION.SDK_INT >= 26) PendingIntent.getForegroundService(this, 1, it, fl) else PendingIntent.getService(this, 1, it, fl)
            val am = getSystemService(ALARM_SERVICE) as AlarmManager
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 1500, pi)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        running = false
        try { exec?.shutdownNow() } catch (_: Exception) {}
        closeSockets()
        try { if (wake?.isHeld == true) wake?.release() } catch (_: Exception) {}
        try { if (wifi?.isHeld == true) wifi?.release() } catch (_: Exception) {}
        saveSnap()
        super.onDestroy()
    }

    private fun channels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val live = NotificationChannel(CH_LIVE, "Live prices", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Always-on BTC, ETH, SOL and XMR prices"; setShowBadge(false); enableVibration(false); setSound(null, null)
        }
        val inc = NotificationChannel(CH_IN, "Incoming payments", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "When funds arrive in a watched wallet"; enableVibration(true); vibrationPattern = longArrayOf(0, 40, 60, 40, 60, 90)
        }
        val quiet = NotificationChannel(CH_QUIET, "Live prices (no status-bar icon)", NotificationManager.IMPORTANCE_MIN).apply {
            description = "The same live prices, without the H in the status bar"; setShowBadge(false); enableVibration(false); setSound(null, null)
        }
        nm.createNotificationChannel(live); nm.createNotificationChannel(quiet); nm.createNotificationChannel(inc)
    }

    private fun openApp(): PendingIntent {
        val i = packageManager.getLaunchIntentForPackage(packageName) ?: Intent(this, HwActivity::class.java)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun fmtPx(v: Double): String = when {
        v <= 0.0 -> "—"
        v >= 10000 -> String.format("$%,.0f", v)
        v >= 100 -> String.format("$%,.1f", v)
        v >= 1 -> String.format("$%,.2f", v)
        else -> String.format("$%.4f", v)
    }

    private fun buildNote(): Notification {
        val dark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val up = if (dark) 0xFF34C77B.toInt() else 0xFF188038.toInt()
        val dn = if (dark) 0xFFF2665E.toInt() else 0xFFD93025.toInt()
        val mute = if (dark) 0xFF9AA4B2.toInt() else 0xFF6B7079.toInt()
        val rv = RemoteViews(packageName, R.layout.hw_notif)
        val ids = arrayOf(intArrayOf(R.id.p0, R.id.c0), intArrayOf(R.id.p1, R.id.c1), intArrayOf(R.id.p2, R.id.c2), intArrayOf(R.id.p3, R.id.c3))
        for (k in 0 until 4) {
            rv.setTextViewText(ids[k][0], fmtPx(px[k]))
            val p = pct[k]
            rv.setTextViewText(ids[k][1], if (p == null) "" else String.format("%s%.2f%%", if (p >= 0) "+" else "", p))
            rv.setTextColor(ids[k][1], if (p == null || Math.abs(p) < 0.005) mute else if (p >= 0) up else dn)
        }
        val b = NotificationCompat.Builder(this, if (sbar(this)) CH_LIVE else CH_QUIET)
            .setSmallIcon(R.drawable.hw_stat)
            .setColor(0xFFDDF869.toInt())
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(rv)
            .setContentTitle("Hwallet")
            .setContentText(String.format("BTC %s · ETH %s · SOL %s · XMR %s", fmtPx(px[0]), fmtPx(px[1]), fmtPx(px[2]), fmtPx(px[3])))
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openApp())
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        return b.build()
    }

    private var fgOk = false
    private var fgChannel = ""
    private fun goForeground() {
        /* the v1.2 custom layout; if it can't be built on this phone, a plain text notification instead of a crash */
        val n = try { buildNote() } catch (e: Throwable) {
            HwApp.write(this, "note: custom notification failed, plain one used", e.toString())
            try { NotificationCompat.Builder(this, if (sbar(this)) CH_LIVE else CH_QUIET).setSmallIcon(R.drawable.hw_stat).setContentTitle("Hwallet").setContentText("Live prices").setOngoing(true).setSilent(true).build() } catch (_: Throwable) { null }
        } ?: return
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NID, n)
            fgOk = true; fgChannel = if (sbar(this)) CH_LIVE else CH_QUIET
        } catch (e: Throwable) { fgOk = false; HwApp.write(this, "note: live service could not enter the foreground", e.toString()) }
    }

    @SuppressLint("MissingPermission")
    private fun pushNote(force: Boolean = false) {
        val key = (0 until 4).joinToString("|") { fmtPx(px[it]) + (pct[it]?.let { p -> String.format("%.2f", p) } ?: "") }
        val now = System.currentTimeMillis()
        if (!force && (key == lastNote || now - lastNoteAt < 900)) return
        lastNote = key; lastNoteAt = now
        try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NID, buildNote()) } catch (_: Throwable) {}
    }

    /* ---------- config from the app (Settings > Live data) ---------- */
    private fun reload() {
        val s = getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("cfg", null)
        cfg = try { JSONObject(s ?: "{}") } catch (_: Exception) { JSONObject() }
        if (!enabled(this)) { stopSelf(); return }
        /* v1.3 (#47): status-bar switch changed: repost the foreground notification on the other channel */
        if (fgChannel.isNotEmpty() && fgChannel != (if (sbar(this)) CH_LIVE else CH_QUIET)) goForeground()
        try { exec?.shutdownNow() } catch (_: Exception) {}
        closeSockets()
        running = true
        histAt.clear(); catchUp = true
        val ex = Executors.newScheduledThreadPool(8)
        exec = ex
        ex.scheduleWithFixedDelay({ tick() }, 0, 1, TimeUnit.SECONDS)
        ex.execute { try { openSockets() } catch (_: Throwable) {} }
    }

    private fun bgSec(): Int = cfg.optInt("bg", 5)

    private fun tick() {
        if (!running) return
        try {
            if (cfg.optBoolean("notif", true)) prices()
            val bg = bgSec()
            if (bg <= 0) return
            val w = cfg.optJSONArray("watch") ?: return
            val now = System.currentTimeMillis()
            for (i in 0 until w.length()) {
                val c = w.optJSONObject(i) ?: continue
                val id = c.optString("id")
                val minGap = when (c.optString("k")) { "trx" -> 3000L; "ada" -> 5000L; "evm" -> if (id == "ethereum" || id == "base") 1000L else 2000L; else -> 1000L }
                val gap = maxOf(bg * 1000L, minGap) + (backoff[id] ?: 0L)
                if (now < (nextAt[id] ?: 0L) || busy[id] == true) continue
                nextAt[id] = now + gap
                try { exec?.execute { poll(c) } } catch (_: Throwable) {}
            }
            histTick(w, now)
        } catch (_: Exception) {}
    }

    /* ---------- v1.4 (#55): background activity ----------
       Every watched chain's newest history page is read into the activity cache: at start (catching up from each
       stream's last-seen cursor), whenever a WebSocket event or a balance change says something happened, every few
       seconds while a send from Hwallet is pending, and otherwise lightly (once a minute). */
    private fun wid(): String = cfg.optString("wid", "")
    private fun histKey(c: JSONObject): String = c.optString("id")
    fun histKick(id: String, delayMs: Long = 1200L) {
        val now = System.currentTimeMillis(); val at = histAt[id] ?: Long.MAX_VALUE
        if (at > now + delayMs) histAt[id] = now + delayMs
    }
    private fun histTick(w: JSONArray, now: Long) {
        val wid = wid(); if (wid.isEmpty()) return
        val pend = try { actDb.pendingChains(wid) } catch (_: Throwable) { emptySet<String>() }
        for (i in 0 until w.length()) {
            val c = w.optJSONObject(i) ?: continue
            val id = histKey(c)
            if (c.optString("k") == "evm" && c.optString("bs").isEmpty()) continue
            val pending = pend.contains(id)
            val light = if (pending) 4000L else 60000L
            val at = histAt[id]
            if (at == null) { histAt[id] = now + 400L * i; continue }
            if (now < at || histBusy[id] == true) continue
            histBusy[id] = true
            histAt[id] = now + light + (histBack[id] ?: 0L)
            try { exec?.execute {
                try { hist.sync(wid, c); histBack.remove(id) }
                catch (_: Throwable) { histBack[id] = minOf(120000L, maxOf(5000L, (histBack[id] ?: 2500L) * 2)) }
                finally { histBusy[id] = false }
            } } catch (_: Throwable) { histBusy[id] = false }
        }
    }

    /* ---------- prices ---------- */
    private fun get(url: String): String? = try {
        http.newCall(Request.Builder().url(url).header("User-Agent", "Hwallet/" + BuildConfig.VERSION_NAME).header("Accept", "application/json").build()).execute().use { r: Response ->
            if (r.isSuccessful) r.body?.string() else { if (r.code == 429) throw RateLimit(); null }
        }
    } catch (e: RateLimit) { throw e } catch (_: Exception) { null }

    private fun post(url: String, body: String): String? = try {
        http.newCall(Request.Builder().url(url).header("User-Agent", "Hwallet/" + BuildConfig.VERSION_NAME).post(body.toRequestBody(JSON_T)).build()).execute().use { r: Response ->
            if (r.isSuccessful) r.body?.string() else { if (r.code == 429) throw RateLimit(); null }
        }
    } catch (e: RateLimit) { throw e } catch (_: Exception) { null }

    private class RateLimit : Exception()

    private var pxBusy = false
    private var pxAt = 0L
    private fun prices() {
        val now = System.currentTimeMillis()
        val gap = maxOf(1000L, if (bgSec() > 0) bgSec() * 1000L else 5000L)
        if (pxBusy || now - pxAt < gap) return
        pxBusy = true; pxAt = now
        try { exec?.execute {
            try {
                if (now - cgAt > 60000) {
                    val j = get("https://api.coingecko.com/api/v3/simple/price?ids=bitcoin,ethereum,solana,monero&vs_currencies=usd&include_24hr_change=true")
                    if (j != null) {
                        val o = JSONObject(j)
                        for (k in 0 until 4) { val c = o.optJSONObject(coins[k]) ?: continue; val v = c.optDouble("usd", 0.0); if (v > 0) px[k] = v; if (c.has("usd_24h_change")) pct[k] = c.optDouble("usd_24h_change") }
                        cgAt = now
                    }
                }
                val k = get("https://api.kraken.com/0/public/Ticker?pair=XBTUSD,ETHUSD,SOLUSD,XMRUSD")
                if (k != null) {
                    val r = JSONObject(k).optJSONObject("result")
                    if (r != null) {
                        val map = mapOf("XXBTZUSD" to 0, "XETHZUSD" to 1, "SOLUSD" to 2, "XXMRZUSD" to 3)
                        for ((key, idx) in map) { val t = r.optJSONObject(key) ?: continue; val v = t.optJSONArray("c")?.optString(0)?.toDoubleOrNull() ?: continue; if (v > 0) px[idx] = v }
                    }
                }
                pushNote()
            } catch (_: Exception) {} finally { pxBusy = false }
        } } catch (_: Throwable) { pxBusy = false }
    }

    /* ---------- balances (watch only) ---------- */
    private fun hexBig(h: String?): BigInteger? = try { if (h == null || h == "0x") BigInteger.ZERO else BigInteger(h.removePrefix("0x").ifEmpty { "0" }, 16) } catch (_: Exception) { null }
    private fun pad32(a: String): String = a.removePrefix("0x").lowercase().padStart(64, '0')
    private fun arr(a: JSONArray?): List<String> { val o = ArrayList<String>(); if (a != null) for (i in 0 until a.length()) o.add(a.optString(i)); return o }

    private fun rpcPost(urls: List<String>, body: String): String? {
        for (u in urls) { val r = post(u, body); if (r != null) return r }
        return null
    }

    private fun poll(c: JSONObject) {
        val id = c.optString("id"); busy[id] = true
        try {
            val out = HashMap<String, BigInteger>()
            val addr = c.optString("addr"); val rpc = arr(c.optJSONArray("rpc")); val toks = c.optJSONArray("tokens") ?: JSONArray()
            when (c.optString("k")) {
                "evm" -> {
                    val calls = JSONArray()
                    calls.put(JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "eth_getBalance").put("params", JSONArray().put(addr).put("latest")))
                    for (t in 0 until toks.length()) {
                        val ta = toks.getJSONObject(t).optString("a")
                        calls.put(JSONObject().put("jsonrpc", "2.0").put("id", t + 2).put("method", "eth_call").put("params", JSONArray().put(JSONObject().put("to", ta).put("data", "0x70a08231" + pad32(addr))).put("latest")))
                    }
                    val r = rpcPost(rpc, calls.toString()) ?: throw IllegalStateException()
                    val a = JSONArray(r)
                    for (i in 0 until a.length()) {
                        val o = a.getJSONObject(i); val n = o.optInt("id"); if (!o.has("result")) continue
                        val v = hexBig(o.optString("result").take(66)) ?: continue
                        if (n == 1) out["$id:native"] = v else { val t = toks.optJSONObject(n - 2) ?: continue; out["$id:" + t.optString("a").lowercase()] = v }
                    }
                }
                "sol" -> {
                    val calls = JSONArray().put(JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "getBalance").put("params", JSONArray().put(addr).put(JSONObject().put("commitment", "confirmed"))))
                    for (t in 0 until toks.length()) calls.put(JSONObject().put("jsonrpc", "2.0").put("id", t + 2).put("method", "getTokenAccountsByOwner").put("params", JSONArray().put(addr).put(JSONObject().put("mint", toks.getJSONObject(t).optString("a"))).put(JSONObject().put("encoding", "jsonParsed").put("commitment", "confirmed"))))
                    val r = rpcPost(rpc, calls.toString()) ?: throw IllegalStateException()
                    val a = JSONArray(r)
                    for (i in 0 until a.length()) {
                        val o = a.getJSONObject(i); val n = o.optInt("id"); val res = o.optJSONObject("result") ?: continue
                        if (n == 1) out["sol:native"] = BigInteger.valueOf(res.optLong("value"))
                        else {
                            var sum = BigInteger.ZERO; val v = res.optJSONArray("value") ?: continue
                            for (k in 0 until v.length()) { try { sum = sum.add(BigInteger(v.getJSONObject(k).getJSONObject("account").getJSONObject("data").getJSONObject("parsed").getJSONObject("info").getJSONObject("tokenAmount").getString("amount"))) } catch (_: Exception) {} }
                            val t = toks.optJSONObject(n - 2) ?: continue; out["sol:" + t.optString("a")] = sum
                        }
                    }
                }
                "btc" -> {
                    var r: String? = null; for (u in rpc) { r = get("$u/address/$addr"); if (r != null) break }
                    val o = JSONObject(r ?: throw IllegalStateException()); val cs = o.optJSONObject("chain_stats") ?: JSONObject(); val ms = o.optJSONObject("mempool_stats") ?: JSONObject()
                    out["btc:native"] = BigInteger.valueOf(cs.optLong("funded_txo_sum") - cs.optLong("spent_txo_sum") + ms.optLong("funded_txo_sum") - ms.optLong("spent_txo_sum"))
                }
                "trx" -> {
                    val r = get(rpc.firstOrNull().orEmpty() + "/v1/accounts/" + addr) ?: throw IllegalStateException()
                    val d = JSONObject(r).optJSONArray("data")?.optJSONObject(0) ?: JSONObject()
                    out["trx:native"] = BigInteger.valueOf(d.optLong("balance"))
                    val t20 = d.optJSONArray("trc20")
                    if (t20 != null) for (i in 0 until t20.length()) { val m = t20.getJSONObject(i); val it = m.keys(); while (it.hasNext()) { val k = it.next(); try { out["trx:$k"] = BigInteger(m.optString(k)) } catch (_: Exception) {} } }
                }
                "sui" -> {
                    val r = rpcPost(rpc, JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "suix_getAllBalances").put("params", JSONArray().put(addr)).toString()) ?: throw IllegalStateException()
                    val a = JSONObject(r).optJSONArray("result") ?: JSONArray()
                    for (i in 0 until a.length()) { val b = a.getJSONObject(i); val ty = b.optString("coinType"); val k = if (Regex("^0x0*2::sui::SUI$").matches(ty)) "native" else ty.lowercase(); try { out["sui:$k"] = BigInteger(b.optString("totalBalance")) } catch (_: Exception) {} }
                }
                "xrp" -> {
                    val r = rpcPost(rpc, JSONObject().put("method", "account_info").put("params", JSONArray().put(JSONObject().put("account", addr).put("ledger_index", "validated"))).toString()) ?: throw IllegalStateException()
                    val ad = JSONObject(r).optJSONObject("result")?.optJSONObject("account_data")
                    out["xrp:native"] = if (ad != null) BigInteger(ad.optString("Balance", "0")) else BigInteger.ZERO
                }
                "ada" -> {
                    val r = post(rpc.firstOrNull().orEmpty() + "/address_info", JSONObject().put("_addresses", JSONArray().put(addr)).toString()) ?: throw IllegalStateException()
                    val d = JSONArray(r).optJSONObject(0)
                    out["ada:native"] = if (d != null) BigInteger(d.optString("balance", "0")) else BigInteger.ZERO
                }
            }
            backoff.remove(id)
            compare(c, out)
        } catch (_: RateLimit) {
            backoff[id] = minOf(60000L, maxOf(2000L, (backoff[id] ?: 1000L) * 2))
        } catch (_: Exception) {
            backoff[id] = minOf(30000L, (backoff[id] ?: 0L) + 2000L)
        } finally { busy[id] = false }
    }

    private fun compare(c: JSONObject, now: Map<String, BigInteger>) {
        var changed = false
        for ((k, v) in now) {
            val old = snap[k]
            if (old != null && v > old && cfg.optBoolean("alerts", true)) alert(c, k, v.subtract(old))
            if (old != v) { if (old != null) histKick(c.optString("id"), 300L); snap[k] = v; changed = true }
        }
        if (changed) saveSnap()
    }

    private fun alert(c: JSONObject, key: String, amt: BigInteger) {
        val tok = key.substringAfter(":")
        var sym = c.optString("sym"); var dec = c.optInt("dec", 18)
        if (tok != "native") {
            val toks = c.optJSONArray("tokens") ?: JSONArray()
            for (i in 0 until toks.length()) { val t = toks.getJSONObject(i); if (t.optString("a").equals(tok, ignoreCase = true)) { sym = t.optString("s"); dec = t.optInt("d") } }
            if (sym == c.optString("sym")) sym = "tokens"
        }
        val txt = try { BigDecimal(amt, dec).stripTrailingZeros().toPlainString() } catch (_: Exception) { amt.toString() }
        val title = "Received +$txt $sym"
        val body = "On " + c.optString("name") + (cfg.optString("wallet").let { if (it.isNotEmpty()) " · $it" else "" })
        try {
            val n = NotificationCompat.Builder(this, CH_IN).setSmallIcon(R.drawable.hw_stat).setColor(0xFFDDF869.toInt())
                .setContentTitle(title).setContentText(body).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STATUS).setDefaults(NotificationCompat.DEFAULT_SOUND)
                .setContentIntent(openApp()).build()
            @SuppressLint("MissingPermission")
            val ok = (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            ok.notify((key.hashCode() and 0x7ffffff) + 100, n)
        } catch (_: Exception) {}
    }

    private fun saveSnap() {
        try {
            val o = JSONObject(); for ((k, v) in snap) o.put(k, v.toString())
            getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("snap", o.toString()).apply()
        } catch (_: Exception) {}
    }

    private fun loadSnap() {
        try {
            val s = getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("snap", null) ?: return
            val o = JSONObject(s); val it = o.keys(); while (it.hasNext()) { val k = it.next(); try { snap[k] = BigInteger(o.getString(k)) } catch (_: Exception) {} }
        } catch (_: Exception) {}
    }

    /* ---------- WebSockets: any event = check that chain now ----------
       v1.4 (#55): subscriptions to the user's addresses: EVM new blocks (Ethereum, Base) plus ERC-20 Transfer logs to and
       from the address on every EVM network with a WebSocket, Solana account + logs mentioning the address, mempool.space
       address tracking, XRPL account stream. An event refreshes that chain's balance at once and reads its history into
       the activity cache. TRON, Sui and Cardano have no free push: light polling. Frames count against the speed limit. */
    private fun closeSockets() { synchronized(sockets) { for (s in sockets) try { s.cancel() } catch (_: Exception) {}; sockets.clear() } }

    private val TRANSFER = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"
    private fun openSockets() {
        if (bgSec() <= 0) return
        val w = cfg.optJSONArray("watch") ?: return
        for (i in 0 until w.length()) {
            val c = w.optJSONObject(i) ?: continue
            val url = c.optString("ws"); if (url.isEmpty()) continue
            val addr = c.optString("addr"); val id = c.optString("id")
            val subs = ArrayList<String>()
            when (c.optString("k")) {
                "evm" -> {
                    if (id == "ethereum" || id == "base") subs.add("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"eth_subscribe\",\"params\":[\"newHeads\"]}")
                    val t = "\"0x" + pad32(addr) + "\""
                    subs.add("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"eth_subscribe\",\"params\":[\"logs\",{\"topics\":[\"$TRANSFER\",null,$t]}]}")
                    subs.add("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"eth_subscribe\",\"params\":[\"logs\",{\"topics\":[\"$TRANSFER\",$t]}]}")
                }
                "sol" -> {
                    subs.add("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"accountSubscribe\",\"params\":[\"$addr\",{\"commitment\":\"confirmed\",\"encoding\":\"base64\"}]}")
                    subs.add("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"logsSubscribe\",\"params\":[{\"mentions\":[\"$addr\"]},{\"commitment\":\"confirmed\"}]}")
                }
                "btc" -> subs.add("{\"track-address\":\"$addr\"}")
                "xrp" -> subs.add("{\"id\":1,\"command\":\"subscribe\",\"accounts\":[\"$addr\"]}")
            }
            if (subs.isEmpty()) continue
            ws(url, subs, c, 0)
        }
    }

    private fun ws(url: String, subs: List<String>, c: JSONObject, tries: Int) {
        if (!running) return
        val req = try { Request.Builder().url(url).build() } catch (_: Exception) { return }
        val id = c.optString("id"); val k = c.optString("k")
        val s = try { http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { for (m in subs) { HwNet.wsOut(m.length); webSocket.send(m) } }
            override fun onMessage(webSocket: WebSocket, text: String) {
                HwNet.wsIn(text.length)
                if (!running) return
                /* subscription confirmations are not events */
                if (text.contains("\"result\"") && !text.contains("\"method\"") && k != "btc" && k != "xrp") return
                if (busy[id] != true) { nextAt[id] = 0L }
                /* a new block alone does not mean our address moved; a log, an account change, an address tx does */
                val newHead = text.contains("\"parentHash\"") && !text.contains("\"topics\"")
                if (!newHead) histKick(id, if (k == "evm") 2500L else 1200L)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!running) return
                val delay = minOf(15000L, 500L shl minOf(tries, 5))
                try { exec?.schedule({ ws(url, subs, c, tries + 1) }, delay, TimeUnit.MILLISECONDS) } catch (_: Throwable) {}
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!running) return
                try { exec?.schedule({ ws(url, subs, c, tries + 1) }, 1000, TimeUnit.MILLISECONDS) } catch (_: Throwable) {}
            }
        }) } catch (_: Throwable) { return }
        synchronized(sockets) { sockets.add(s) }
    }
}
