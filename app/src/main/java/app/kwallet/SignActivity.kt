package app.kwallet

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView

/**
 * v3.90 Hwallet sign page. Not in the launcher, not in recents. Opens only for Hwallet
 * (package app.hwallet signed with the same release key as Kwallet), runs one request,
 * and returns signed / rejected / expired to Hwallet alone. Still no INTERNET permission.
 */
class SignActivity : KwActivity() {
    private var req = ""
    private var replied = false

    private fun callerOk(): Boolean {
        val caller = callingActivity?.packageName ?: return false
        if (caller != HWALLET) return false
        return try { packageManager.checkSignatures(packageName, caller) == PackageManager.SIGNATURE_MATCH } catch (_: Exception) { false }
    }

    override fun startUrl(): String = "https://appassets.androidplatform.net/assets/index.html#sign"

    override fun onCreate(savedInstanceState: Bundle?) {
        // nothing on this page may be captured, recorded or drawn over
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 31) window.setHideOverlayWindows(true)
        val ok = callerOk()
        req = if (ok) (intent.getStringExtra("req") ?: "").take(65536) else ""
        setResult(RESULT_CANCELED, Intent().putExtra("res", if (ok) "{\"status\":\"rejected\"}" else "{\"status\":\"refused\",\"error\":\"Only Hwallet signed with the Kwallet key can ask Kwallet to sign\"}"))
        super.onCreate(null)
        if (!ok) { replied = true; finish() }
    }

    override fun onWebReady(w: WebView) {
        w.filterTouchesWhenObscured = true
        w.addJavascriptInterface(Bridge(), "KwSign")
    }

    inner class Bridge {
        @JavascriptInterface fun request(): String = req
        @JavascriptInterface fun done(json: String) { runOnUiThread { reply(json) } }
    }

    private fun reply(json: String) {
        if (replied) return
        replied = true
        setResult(RESULT_OK, Intent().putExtra("res", json.take(262144)))
        finish()
    }

    companion object { const val HWALLET = "app.hwallet" }
}
