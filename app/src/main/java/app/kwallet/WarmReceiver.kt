package app.kwallet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader

/**
 * P2a (#97a): Hwallet sends app.kwallet.WARM (explicit component, signature permission) when a dApp connects, a request is
 * coming or the Send sheet opens. v3.97 wave A: Kwallet then pre-loads its sign page (index.html#sign, offline assets only,
 * no request yet) in a WebView that the next SignActivity adopts, so the sheet appears without loading or parsing the app.
 * Kwallet has no INTERNET permission; every non-asset request is refused exactly as in KwActivity.
 */
class WarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { KwWarm.warm(c.applicationContext, "broadcast") }
}

object KwWarm {
    const val SIGN_URL = "https://appassets.androidplatform.net/assets/index.html#sign"
    @Volatile private var pre: WebView? = null
    @Volatile var host: SignActivity? = null
    @Volatile var loadedAt = 0L
    private val ui = Handler(Looper.getMainLooper())

    /** main thread (receivers run there) */
    fun warm(app: Context, why: String) {
        Log.i("KwSign", "warm received ($why) pre=${pre != null}")
        if (pre != null || host != null) return  // one spare page; none while a sheet is open (the next one loads after it)
        try {
            val w = WebView(MutableContextWrapper(app))
            kwWebSettings(w)
            val assets = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(app)).build()
            w.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    assets.shouldInterceptRequest(request.url)?.also { if (request.url.path?.endsWith(".js") == true) it.mimeType = "text/javascript" }  /* v3.98: the Monero engine worker script */
                        ?: if (request.url.scheme == "blob" || request.url.scheme == "data") null
                        else WebResourceResponse("text/plain", "utf-8", 403, "Offline", null, null)
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = request.url.host != "appassets.androidplatform.net"
                override fun onPageFinished(view: WebView, url: String) { loadedAt = SystemClock.uptimeMillis(); Log.i("KwSign", "warm sign page loaded") }
            }
            w.addJavascriptInterface(kwHarkNative(w.context) { host }, "HarkNative")
            w.addJavascriptInterface(KwSignBridge { host }, "KwSign")
            if (BuildConfig.E2E) w.addJavascriptInterface(KwE2EFlag(), "KwE2E")
            if (BuildConfig.DEBUG || BuildConfig.E2E) WebView.setWebContentsDebuggingEnabled(true)
            w.loadUrl(SIGN_URL)
            pre = w
        } catch (t: Throwable) { Log.w("KwSign", "warm failed: $t") }
    }

    /** SignActivity.onCreate (after its caller check): the pre-loaded page, now hosted by that activity */
    fun take(a: SignActivity): WebView? {
        val w = pre ?: return null
        pre = null
        (w.context as? MutableContextWrapper)?.baseContext = a
        host = a
        return w
    }
    fun release(a: SignActivity) { if (host === a) host = null }
    /** after a request: have the next sign page ready (once the closing animation is done) */
    fun again(app: Context) { ui.postDelayed({ if (host == null) warm(app, "after sign") }, 1200) }
}
