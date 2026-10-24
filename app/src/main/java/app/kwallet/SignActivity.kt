package app.kwallet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.content.ContextCompat
import android.content.MutableContextWrapper
import org.json.JSONObject

/**
 * Hwallet sign page (v3.90, v3.91). Not in the launcher, not in recents. Opens only for Hwallet
 * (package app.hwallet signed with the same release key as Kwallet), runs one request, and returns
 * signed / rejected / expired to Hwallet alone. Still no INTERNET permission.
 * v3.91: opens straight on the transaction over Hwallet (translucent window, no splash, no PIN screen)
 * and takes live fee / balance / price updates from Hwallet through a broadcast that only apps signed
 * with the Kwallet key can send (signature permission), matched to the one open request id.
 * v3.92: the window stays transparent until the page has drawn the transaction (no Kwallet UI flash),
 * and opens/closes without a window animation.
 * v3.96: dApp requests (personal_sign, typed data, contract calls, Solana and Tron) as a translucent bottom sheet.
 * v3.97 wave A: every request (send, stake, pairing, dApp) is that sheet; when Kwallet is warm the sign page is already loaded
 * (KwWarm) and this activity adopts it, so the sheet only has to draw.
 * v3.98.16: no timeouts. A live Binder link to Hwallet carries every state; either side notices the other's death.
 */
class SignActivity : KwActivity() {
    private var req = ""
    private var reqId = ""
    /** v3.98.8 (push 13, R9a / R11a): Hwallet's handoff files (content:// with a one-shot grant); null = an older Hwallet */
    private var resUri: android.net.Uri? = null
    private var rid = ""
    private var replied = false
    private var feedOn = false
    private var wv: WebView? = null
    @Volatile private var sheetMode = false
    /** P2a: when Hwallet asked (its uptime clock, same as ours), for the "sheet visible" timing in logcat */
    private var tAsk = 0L
    private var tCreate = 0L
    /* v3.98.14: the window stays transparent until the sheet draws. If it never draws, the invisible window used to swallow
       every touch over Hwallet ("frozen" until Back). Now it is revealed after 10 s (v3.98.16: the
       request itself keeps waiting; only the user's decision, a crash, or Hwallet's Cancel ends it). */
    @Volatile private var sheetShown = false
    private val dogH = android.os.Handler(android.os.Looper.getMainLooper())
    private val dogReveal = Runnable { if (!sheetShown && !replied && !isFinishing) { Log.w("KwSign", "sheet not ready after 10 s, revealing"); revealSign() } }
    /* v3.98.15 (Monero 4.1 A): the last stage the sign page reported (KwSign.stage).
       v3.98.16: no 30 s watchdog any more. Kwallet and Hwallet listen to each other (the live link below) and only the
       signing decision ends a request. A sign page that crashes before it draws answers at once, naming that stage. */
    @Volatile private var lastStage = ""
    /* ---- v3.98.16: live K <-> H link (no timeouts) ----
       Hwallet hands this activity a Binder in the launch Intent ("kwlink"). Kwallet pushes every state change to it
       (received, sheet, waiting, signing, signed / rejected / error + stage) as one-way transactions, and sends its own Binder
       with the first one so Hwallet can (a) cancel the open sheet and (b) notice if Kwallet's process dies (linkToDeath).
       Kwallet links to Hwallet's Binder the same way: if Hwallet dies, the sheet closes instead of waiting. Both sides check
       the caller's package (signature-matched app.hwallet / app.kwallet) on every incoming transaction. */
    private var hLink: android.os.IBinder? = null
    private val hDeath = android.os.IBinder.DeathRecipient {
        Log.w("KwSign", "Hwallet's process died; closing the sheet")
        hLink = null
        runOnUiThread { if (!replied && !isFinishing) { if (wv != null) wv?.evaluateJavascript("window.kwSignPeerGone?kwSignPeerGone():0", null) else reply("{\"status\":\"rejected\",\"error\":\"Hwallet closed\"}") } }
    }
    private val kLink = object : android.os.Binder() {
        override fun onTransact(code: Int, data: android.os.Parcel, rp: android.os.Parcel?, flags: Int): Boolean {
            if (code != LINK_CANCEL && code != LINK_PING) return super.onTransact(code, data, rp, flags)
            if (!uidIsHwallet(android.os.Binder.getCallingUid())) return false
            if (code == LINK_CANCEL) {
                val id = try { data.readString() ?: "" } catch (_: Exception) { "" }
                if (id.isEmpty() || id == reqId) runOnUiThread {
                    if (!replied && !isFinishing) {
                        Log.i("KwSign", "Hwallet cancelled the request")
                        if (wv != null && sheetShown) wv?.evaluateJavascript("window.kwSignCancel?kwSignCancel():0", null)
                        else this@SignActivity.reply("{\"status\":\"rejected\",\"error\":\"Cancelled in Hwallet\"}")
                    }
                }
            }
            return true
        }
    }
    private fun uidIsHwallet(uid: Int): Boolean = try {
        (packageManager.getPackagesForUid(uid) ?: emptyArray()).any { it == HWALLET } && packageManager.checkSignatures(packageName, HWALLET) == PackageManager.SIGNATURE_MATCH
    } catch (_: Exception) { false }
    private var linkSentSelf = false
    /** one state to Hwallet (one-way, never blocks). st: received | sheet | waiting | signing | signed | rejected | error | stage */
    internal fun linkSend(st: String, detail: String = "") {
        val b = hLink ?: return
        val d = android.os.Parcel.obtain()
        try {
            d.writeString(reqId); d.writeString(st.take(24)); d.writeString(detail.take(200))
            d.writeStrongBinder(if (!linkSentSelf) kLink else null)
            b.transact(LINK_STATUS, d, null, android.os.IBinder.FLAG_ONEWAY)
            linkSentSelf = true
        } catch (e: Exception) { Log.w("KwSign", "link send failed: $e") } finally { d.recycle() }
    }
    internal fun onStage(s: String) {
        lastStage = s.take(160)
        Log.i("KwSign", "stage +${SystemClock.uptimeMillis() - tCreate}ms $lastStage")
        runOnUiThread { linkSend("stage", lastStage) }
    }
    /** v3.98.16: the page says what it is doing now (waiting | signing), forwarded to Hwallet live */
    internal fun onState(st: String, detail: String) { runOnUiThread { linkSend(st, detail) } }
    /** v3.98.16: the page crashed before its sheet drew: answer now, with the stage (never a silent wait) */
    internal fun onEarlyCrash(why: String) {
        runOnUiThread {
            if (replied || sheetShown) return@runOnUiThread
            val at = lastStage.ifEmpty { "the sign page never started (request ${req.length / 1024} KB)" }
            reply(JSONObject().put("status", "error").put("error", "Kwallet could not open the signing screen: ${why.take(160)} (at: $at)").put("stage", at).toString())
        }
    }

    override val signPage: Boolean = true
    override fun bootColor(c: Int): Int {
        val dark = getSharedPreferences("kw", MODE_PRIVATE).getBoolean("dark", false)
        return if (dark) 0xFF0E1117.toInt() else 0xFFF8F9FA.toInt()
    }

    private fun callerOk(): Boolean {
        val caller = callingActivity?.packageName ?: return false
        /* v3.99.14: QR air-gap. Kwallet's own main screen hands over a request it scanned from Hwallet on another phone
           (startActivityForResult with air=true). callingActivity is set by the system, so no other app can pass as Kwallet. */
        if (caller == packageName && intent.getBooleanExtra("air", false)) return true
        if (caller != HWALLET) return false
        return try { packageManager.checkSignatures(packageName, caller) == PackageManager.SIGNATURE_MATCH } catch (_: Exception) { false }
    }

    override fun startUrl(): String = KwWarm.SIGN_URL
    /** wave A (P2a f): take the pre-loaded sign page if Kwallet was warmed (only after the caller check passed) */
    private var callerChecked = false
    override fun adoptWeb(): WebView? = if (callerChecked) KwWarm.take(this) else null
    internal fun reqJson(): String = req

    private val feedRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val id = i.getStringExtra("id") ?: return
            val f = i.getStringExtra("feed") ?: return
            onFeed(id, f)
        }
    }
    /** K-H link: one live feed for the open request, from the SIGN_FEED broadcast or from Hwallet's bound link
     *  (KwLinkService). Main thread. Both paths hand the page exactly the same data. */
    internal fun onFeed(id: String, f: String) {
        if (replied || id.isEmpty() || id != reqId || f.length > 262144) return
        wv?.evaluateJavascript("window.kwFeed&&kwFeed(${JSONObject.quote(f)})", null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        tCreate = SystemClock.uptimeMillis(); tAsk = intent.getLongExtra("t0", 0L)
        // nothing on this page may be captured, recorded or drawn over (the CI emulator build, -PkwE2E=true, must screenshot it)
        if (!BuildConfig.E2E) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 31) window.setHideOverlayWindows(true)
        var ok = callerOk(); callerChecked = ok
        req = if (ok) readReq() else ""   /* v3.98: a Monero request carries the unsigned set + exported outputs; v3.98.8: from a file when large */
        val rq = try { JSONObject(req) } catch (_: Exception) { null }
        reqId = rq?.optString("id", "") ?: ""
        /* v3.99.14: an air-gap seed signs only for a request Kwallet itself scanned (air=true from Kwallet), never for one
           Hwallet on this phone sends; and Kwallet's own hand-off always names its air-gap seed */
        val airIntent = callingActivity?.packageName == packageName
        if (ok && (if (rq == null) airIntent else rq.has("air") != airIntent)) { ok = false; callerChecked = false; req = "" }
        setResult(RESULT_CANCELED, Intent().putExtra("res", if (ok) "{\"status\":\"rejected\"}" else "{\"status\":\"refused\",\"error\":\"Only Hwallet signed with the Kwallet key can ask Kwallet to sign\"}"))
        super.onCreate(null)
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
        if (!ok) { replied = true; finish(); return }
        open = this   // K-H link: KwLinkService delivers Hwallet's live feed here
        dogH.postDelayed(dogReveal, 10000)
        try {
            hLink = intent.extras?.getBinder("kwlink")
            hLink?.linkToDeath(hDeath, 0)
        } catch (e: Exception) { Log.w("KwSign", "no live link: $e"); hLink = null }
        linkSend("received")
        try {
            ContextCompat.registerReceiver(this, feedRx, IntentFilter(FEED_ACTION), FEED_PERM, null, ContextCompat.RECEIVER_EXPORTED)
            feedOn = true
        } catch (_: Exception) {}
    }

    override fun onWebReady(w: WebView) {
        w.filterTouchesWhenObscured = true
        wv = w
        if (adopted) return  // the pre-loaded page already has KwSign (delegating to KwWarm.host = this) and KwE2E
        w.addJavascriptInterface(KwSignBridge { this }, "KwSign")
        /* P2a: the CI emulator build only. Lets the sign page show a dApp request for an address no seed is paired for
         *  ("E2E test wallet", display only, it cannot sign), so the device check can open and cancel the sheet. No key or
         *  mnemonic exists for it anywhere. Store builds (build.yml) never set -PkwE2E=true. */
        if (BuildConfig.E2E) w.addJavascriptInterface(KwE2EFlag(), "KwE2E")
    }
    /** the adopted page waited for its request (KwSign.pending()); start it now */
    override fun onAdopted() {
        Log.i("KwSign", "adopted pre-loaded sign page (loaded ${SystemClock.uptimeMillis() - KwWarm.loadedAt}ms ago)")
        wv?.evaluateJavascript("window.kwSignStart&&kwSignStart()", null)
    }
    /** v3.92: the transaction is on screen; reveal the window (it stays transparent until then). */
    internal fun onSheetReady() {
        sheetShown = true; dogH.removeCallbacks(dogReveal)
        revealSign()
        linkSend("sheet", lastStage)
        val now = SystemClock.uptimeMillis()
        Log.i("KwSign", "sheet ready create+${now - tCreate}ms" + (if (tAsk > 0) " ask+${now - tAsk}ms" else "") + (if (adopted) " warm" else " cold"))
    }
    /** v3.96 (#60): the page draws its own dim backdrop, so the window, root and WebView become fully transparent and
     *  Hwallet stays visible behind. v3.97: every request. */
    internal fun onSheetMode() {
        sheetMode = true
        try { wv?.setBackgroundColor(android.graphics.Color.TRANSPARENT); (wv?.parent as? android.view.View)?.setBackgroundColor(android.graphics.Color.TRANSPARENT) } catch (_: Throwable) {}
    }
    internal fun replyFromPage(json: String) = reply(json)

    /** v3.98.8: the request from Hwallet's file when it sent one (no size cap), else the Intent extra as before */
    private fun readReq(): String {
        rid = (intent.getStringExtra("rid") ?: "").take(64)
        val ru = intent.getStringExtra("resUri"); val qu = intent.getStringExtra("reqUri")
        resUri = try { if (ru != null && ru.startsWith("content://app.hwallet.kwx/res/")) android.net.Uri.parse(ru) else null } catch (_: Exception) { null }
        if (qu != null && qu.startsWith("content://app.hwallet.kwx/req/")) {
            try {
                contentResolver.openInputStream(android.net.Uri.parse(qu))?.use { st ->
                    val b = st.readBytes()
                    if (b.size in 2..(24 * 1024 * 1024)) return String(b, Charsets.UTF_8)
                }
            } catch (e: Exception) { Log.w("KwSign", "handoff read failed: $e") }
        }
        return (intent.getStringExtra("req") ?: "").take(400000)
    }

    private fun reply(json: String) {
        if (replied) return
        replied = true
        try {
            val o = JSONObject(json); val st = o.optString("status", "rejected")
            linkSend(if (st == "signed" || st == "paired") "signed" else if (st == "error") "error" else "rejected", o.optString("error", o.optString("stage", "")))
        } catch (_: Exception) { linkSend("error", "unreadable answer") }
        /* v3.98.8 (R11a): a large answer (signed Monero set) goes back through Hwallet's answer file; a small one in the extra */
        var out = Intent().putExtra("res", "{\"status\":\"error\",\"error\":\"Kwallet could not hand the answer back. Try again\"}")
        val ru = resUri
        if (json.length <= 120000) out = Intent().putExtra("res", json)
        else if (ru != null) {
            try {
                contentResolver.openOutputStream(ru, "wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                out = Intent().putExtra("resf", true).putExtra("rid", rid)
            } catch (e: Exception) { Log.w("KwSign", "handoff write failed: $e") }
        } else if (json.length <= 400000) out = Intent().putExtra("res", json)
        setResult(RESULT_OK, out)
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        dogH.removeCallbacksAndMessages(null)
        try { hLink?.unlinkToDeath(hDeath, 0) } catch (_: Exception) {}
        if (!replied) linkSend("rejected", "Kwallet closed the sheet")
        hLink = null
        if (open === this) open = null
        if (feedOn) { try { unregisterReceiver(feedRx) } catch (_: Exception) {}; feedOn = false }
        wv = null
        KwWarm.release(this)
        super.onDestroy()
        // keep Kwallet warm: load the next sign page in the background for the next request
        if (callerChecked) KwWarm.again(applicationContext)
    }

    companion object {
        const val HWALLET = "app.hwallet"
        const val FEED_ACTION = "app.kwallet.SIGN_FEED"
        const val FEED_PERM = "app.kwallet.permission.SIGN_FEED"
        /* v3.98.16 live link transaction codes (same numbers in Hwallet's HwActivity) */
        const val LINK_STATUS = android.os.IBinder.FIRST_CALL_TRANSACTION + 1
        const val LINK_CANCEL = android.os.IBinder.FIRST_CALL_TRANSACTION + 2
        const val LINK_PING = android.os.IBinder.FIRST_CALL_TRANSACTION + 3
        /** K-H link: the sign page open for Hwallet right now (main thread writes), for KwLinkService's feed */
        @Volatile var open: SignActivity? = null
    }
}

/** window.KwSign. Talks to whichever SignActivity hosts the page; a page pre-loaded while warm has none yet (pending). */
class KwSignBridge(private val host: () -> SignActivity?) {
    @JavascriptInterface fun request(): String = host()?.reqJson() ?: ""
    @JavascriptInterface fun pending(): Boolean = host() == null
    @JavascriptInterface fun done(json: String) { val a = host() ?: return; a.runOnUiThread { a.replyFromPage(json) } }
    @JavascriptInterface fun ready() { val a = host() ?: return; a.runOnUiThread { a.onSheetReady() } }
    @JavascriptInterface fun sheet() { val a = host() ?: return; a.runOnUiThread { a.onSheetMode() } }
    /** v3.98.15: the page reports the stage it reached (plain text, kept for the watchdog's error) */
    @JavascriptInterface fun stage(s: String) { val a = host() ?: return; a.onStage(s) }
    /** v3.98.16: live state for Hwallet (waiting | signing), and an immediate answer when the page crashed before drawing */
    @JavascriptInterface fun state(st: String, detail: String) { val a = host() ?: return; a.onState(st.take(24), detail.take(200)) }
    @JavascriptInterface fun crashed(why: String) { val a = host() ?: return; a.onEarlyCrash(why.take(200)) }
}
class KwE2EFlag { @JavascriptInterface fun on(): Boolean = true }
