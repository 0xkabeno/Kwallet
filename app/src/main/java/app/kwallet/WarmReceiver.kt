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
                override fun onPageFinished(view: WebView, url: String) { loadedAt = SystemClock.uptimeMillis(); Log.i("KwSign", "warm sign page loaded"); if (view === pre) { preLoaded = true; quietFlush() } }
            }
            w.addJavascriptInterface(kwHarkNative(w.context) { host }, "HarkNative")
            w.addJavascriptInterface(KwSignBridge { host }, "KwSign")
            w.addJavascriptInterface(KwQuietBridge(), "KwQuietOut")   /* v3.99.23: headless identity reads (kwQuiet) */
            if (BuildConfig.E2E) w.addJavascriptInterface(KwE2EFlag(), "KwE2E")
            if (BuildConfig.DEBUG || BuildConfig.E2E) WebView.setWebContentsDebuggingEnabled(true)
            preLoaded = false
            w.loadUrl(SIGN_URL)
            pre = w
        } catch (t: Throwable) { Log.w("KwSign", "warm failed: $t") }
    }

    /** SignActivity.onCreate (after its caller check): the pre-loaded page, now hosted by that activity */
    fun take(a: SignActivity): WebView? {
        if (quietBusy()) return null   // v3.99.23: a headless read is still running on the spare page; this sheet loads its own
        val w = pre ?: return null
        pre = null; preLoaded = false
        (w.context as? MutableContextWrapper)?.baseContext = a
        host = a
        return w
    }
    /** K-H link: a sign page is pre-loaded or open (KwLinkService's ping answer; Hwallet warms again when it is not) */
    fun ready(): Boolean = pre != null || host != null
    fun release(a: SignActivity) { if (host === a) host = null }
    /** after a request: have the next sign page ready (once the closing animation is done) */
    fun again(app: Context) { ui.postDelayed({ if (host == null) warm(app, "after sign") }, 1200) }

    /* ---- v3.99.23: headless identity reads (Hwallet -> KwLinkService SVC_QUIET -> this spare page -> kwQuiet) ----
       No activity, no window, no sheet: the page signs only icp_iiNames (read-only identity_info) and only with the hardware-sealed
       signing copy; anything else, or a seed that needs the PIN, answers without signing. Main thread. */
    @Volatile private var preLoaded = false
    private val qWait = LinkedHashMap<String, Pair<String, (String) -> Unit>>()
    private val qRun = HashMap<String, (String) -> Unit>()
    private var qSeq = 0
    fun quietBusy(): Boolean = qRun.isNotEmpty() || qWait.isNotEmpty()
    fun quiet(app: Context, body: String, cb: (String) -> Unit) {
        if (host != null) { cb("{\"status\":\"busy\"}"); return }   // a sheet is open: Hwallet shows numbers, never a second window
        val id = "q" + (++qSeq)
        qWait[id] = body to cb
        if (pre == null) warm(app, "quiet") else quietFlush()
        ui.postDelayed({ quietEnd(id, "{\"status\":\"timeout\"}") }, 9000)
    }
    private fun quietFlush() {
        val w = pre ?: return
        if (!preLoaded) return
        val it = qWait.entries.iterator()
        while (it.hasNext()) {
            val (id, v) = it.next(); it.remove()
            qRun[id] = v.second
            w.evaluateJavascript("window.kwQuiet?kwQuiet(${org.json.JSONObject.quote(id)},${org.json.JSONObject.quote(v.first)}):KwQuietOut.done(${org.json.JSONObject.quote(id)},'{\"status\":\"error\",\"error\":\"old page\"}')", null)
        }
    }
    fun quietEnd(id: String, json: String) {
        val cb = qRun.remove(id) ?: qWait.remove(id)?.second ?: return
        try { cb(json) } catch (_: Throwable) {}
    }
}

/** v3.99.23: the spare sign page's answer to a headless read (any thread -> main) */
class KwQuietBridge {
    private val ui = Handler(Looper.getMainLooper())
    @android.webkit.JavascriptInterface fun done(id: String, json: String) { val j = json.take(262144); ui.post { KwWarm.quietEnd(id.take(16), j) } }
}
