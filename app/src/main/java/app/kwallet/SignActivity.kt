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
 */
class SignActivity : KwActivity() {
    private var req = ""
    private var reqId = ""
    private var replied = false
    private var feedOn = false
    private var wv: WebView? = null
    @Volatile private var sheetMode = false
    /** P2a: when Hwallet asked (its uptime clock, same as ours), for the "sheet visible" timing in logcat */
    private var tAsk = 0L
    private var tCreate = 0L

    override val signPage: Boolean = true
    override fun bootColor(c: Int): Int {
        val dark = getSharedPreferences("kw", MODE_PRIVATE).getBoolean("dark", false)
        return if (dark) 0xFF0E1117.toInt() else 0xFFF8F9FA.toInt()
    }

    private fun callerOk(): Boolean {
        val caller = callingActivity?.packageName ?: return false
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
            if (replied || id.isEmpty() || id != reqId || f.length > 262144) return
            wv?.evaluateJavascript("window.kwFeed&&kwFeed(${JSONObject.quote(f)})", null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        tCreate = SystemClock.uptimeMillis(); tAsk = intent.getLongExtra("t0", 0L)
        // nothing on this page may be captured, recorded or drawn over (the CI emulator build, -PkwE2E=true, must screenshot it)
        if (!BuildConfig.E2E) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 31) window.setHideOverlayWindows(true)
        val ok = callerOk(); callerChecked = ok
        req = if (ok) (intent.getStringExtra("req") ?: "").take(65536) else ""
        reqId = try { JSONObject(req).optString("id", "") } catch (_: Exception) { "" }
        setResult(RESULT_CANCELED, Intent().putExtra("res", if (ok) "{\"status\":\"rejected\"}" else "{\"status\":\"refused\",\"error\":\"Only Hwallet signed with the Kwallet key can ask Kwallet to sign\"}"))
        super.onCreate(null)
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
        if (!ok) { replied = true; finish(); return }
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
        revealSign()
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

    private fun reply(json: String) {
        if (replied) return
        replied = true
        setResult(RESULT_OK, Intent().putExtra("res", json.take(262144)))
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
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
    }
}

/** window.KwSign. Talks to whichever SignActivity hosts the page; a page pre-loaded while warm has none yet (pending). */
class KwSignBridge(private val host: () -> SignActivity?) {
    @JavascriptInterface fun request(): String = host()?.reqJson() ?: ""
    @JavascriptInterface fun pending(): Boolean = host() == null
    @JavascriptInterface fun done(json: String) { val a = host() ?: return; a.runOnUiThread { a.replyFromPage(json) } }
    @JavascriptInterface fun ready() { val a = host() ?: return; a.runOnUiThread { a.onSheetReady() } }
    @JavascriptInterface fun sheet() { val a = host() ?: return; a.runOnUiThread { a.onSheetMode() } }
}
class KwE2EFlag { @JavascriptInterface fun on(): Boolean = true }
