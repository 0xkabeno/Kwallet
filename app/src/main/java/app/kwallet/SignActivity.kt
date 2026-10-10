package app.kwallet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.content.ContextCompat
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
 */
class SignActivity : KwActivity() {
    private var req = ""
    private var reqId = ""
    private var replied = false
    private var feedOn = false
    private var wv: WebView? = null

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

    override fun startUrl(): String = "https://appassets.androidplatform.net/assets/index.html#sign"

    private val feedRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val id = i.getStringExtra("id") ?: return
            val f = i.getStringExtra("feed") ?: return
            if (replied || id.isEmpty() || id != reqId || f.length > 262144) return
            wv?.evaluateJavascript("window.kwFeed&&kwFeed(${JSONObject.quote(f)})", null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // nothing on this page may be captured, recorded or drawn over
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 31) window.setHideOverlayWindows(true)
        val ok = callerOk()
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
        w.addJavascriptInterface(Bridge(), "KwSign")
    }

    inner class Bridge {
        @JavascriptInterface fun request(): String = req
        @JavascriptInterface fun done(json: String) { runOnUiThread { reply(json) } }
        /** v3.92: the transaction is on screen; reveal the window (it stays transparent until then). */
        @JavascriptInterface fun ready() { runOnUiThread { revealSign() } }
    }

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
        super.onDestroy()
    }

    companion object {
        const val HWALLET = "app.hwallet"
        const val FEED_ACTION = "app.kwallet.SIGN_FEED"
        const val FEED_PERM = "app.kwallet.permission.SIGN_FEED"
    }
}
