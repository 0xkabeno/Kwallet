package app.hwallet

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Parcel
import android.util.Base64
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v1.6 (#59/#83): the Browser tab's pages. One native WebView per open tab, laid over the Hwallet page's #br-v box
 * (the address bar, tab switcher and sheets are drawn by the Hwallet page itself, segBrowser.js).
 *
 * Wallet providers (hw_provider.js, handed over once by brInit) are added at document start for https origins only,
 * and the page talks back over the origin-checked WebMessageListener "hwBridge": Hwallet always learns the real
 * origin from here, never from the page. Requests go to window.hwBrReq -> dappRoute (the WalletConnect path) ->
 * Kwallet; replies come back through [reply] to the exact frame that asked, and only while it still shows that origin.
 *
 * #83 persistence, per tab in files/br/<id>/: state.bin (WebView.saveState Bundle = back/forward history),
 * page.mht (MHTML snapshot, the offline fallback), thumb.jpg (tab switcher), meta.json (url, scroll).
 * A restored tab loads with LOAD_CACHE_ELSE_NETWORK (no network for anything cached) until the user touches it or
 * reloads; offline it shows the MHTML snapshot. Closing a tab deletes its folder. At most [MAX_LIVE] WebViews stay
 * alive; older tabs are saved and destroyed, and come back from disk when selected, so the tab count is unlimited.
 *
 * All public methods may be called from the JavaScript binder thread; WebView work is posted to the UI thread.
 */
class HwBrowser(
    private val act: HwActivity,
    private val root: FrameLayout,
    private val host: WebView,
    private val js: (String) -> Unit,
    private val onWc: (String) -> Unit
) {
    private class Tab(val id: String) {
        var web: WebView? = null
        var shown = 0L
        var url = ""
        var scroll = 0
        var pendingScroll = 0
        var touched = false
        var offline = false
        var restoring = false
        var lastProxy: JavaScriptReplyProxy? = null
        var lastOrigin = ""
        val pending = HashMap<String, Pair<JavaScriptReplyProxy, String>>()
    }

    private val ui = Handler(Looper.getMainLooper())
    private val tabs = HashMap<String, Tab>()
    private val base = File(act.filesDir, "br")
    @Volatile private var script: String? = null
    private var visible: String? = null
    private var top = 0
    private var bottom = 0

    private fun q(s: String) = JSONObject.quote(s)
    private fun okId(id: String) = id.length in 2..24 && id.matches(Regex("^t[0-9a-z]+$"))
    private fun dir(id: String) = File(base, id)
    private fun post(f: () -> Unit) { ui.post { try { f() } catch (_: Throwable) {} } }

    // ---------------------------------------------------------------- page -> native (via HwNative)

    fun init(s: String) { if (s.length in 100..400_000 && script == null) script = s }

    fun open(id: String, url: String, scroll: Int) {
        if (!okId(id)) return
        post {
            val t = tabs.getOrPut(id) { Tab(id) }
            if (t.web != null) return@post
            val w = make(t)
            t.web = w
            // attached at once (hidden) so it lays out and loads like a normal view; show() places it
            val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            lp.topMargin = top; lp.bottomMargin = bottom
            root.addView(w, lp)
            val st = File(dir(id), "state.bin")
            if (st.exists() || File(dir(id), "page.mht").exists()) restore(t, w, url, scroll)
            else if (isWeb(url)) { t.url = url; w.loadUrl(url) }
            evict()
        }
    }

    fun show(id: String, topCss: Int, botCss: Int) {
        post {
            val d = act.resources.displayMetrics.density
            top = (topCss * d).toInt(); bottom = (botCss * d).toInt()
            val t = tabs[id] ?: return@post
            val w = t.web ?: return@post
            tabs.values.forEach { o -> if (o !== t) o.web?.let { if (it.visibility != View.GONE) { save(o); it.visibility = View.GONE; it.onPause() } } }
            val lp = (w.layoutParams as? FrameLayout.LayoutParams) ?: FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            lp.topMargin = top; lp.bottomMargin = bottom
            w.layoutParams = lp
            if (w.parent == null) root.addView(w, lp)
            w.visibility = View.VISIBLE; w.onResume(); w.bringToFront()
            t.shown = System.currentTimeMillis(); visible = id
        }
    }

    fun hide() {
        post {
            tabs.values.forEach { t -> t.web?.let { if (it.visibility == View.VISIBLE) { thumb(t); save(t); it.visibility = View.GONE; it.onPause() } } }
            visible = null
        }
    }

    fun load(id: String, url: String) {
        if (!isWeb(url)) return
        post { val t = tabs[id] ?: return@post; val w = t.web ?: return@post; leaveSnapshot(t, w); live(t, w); t.url = url; w.loadUrl(url) }
    }

    fun nav(id: String, a: String) {
        post {
            val t = tabs[id] ?: return@post; val w = t.web ?: return@post
            when (a) {
                "back" -> if (w.canGoBack()) { live(t, w); w.goBack() }
                "fwd" -> if (w.canGoForward()) { live(t, w); w.goForward() }
                "reload" -> { live(t, w); if (t.offline) { leaveSnapshot(t, w); w.loadUrl(t.url) } else w.reload() }
                "stop" -> w.stopLoading()
            }
        }
    }

    /** before the tab switcher: a fresh thumbnail and a saved state (waits up to 400 ms so the switcher can show it) */
    fun snap(id: String) {
        val l = CountDownLatch(1)
        ui.post { try { tabs[id]?.let { thumb(it); save(it) } } catch (_: Throwable) {} ; l.countDown() }
        try { l.await(400, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
    }

    fun close(id: String) {
        if (!okId(id)) return
        post {
            tabs.remove(id)?.let { t -> t.web?.let { w -> root.removeView(w); w.stopLoading(); w.destroy() } }
            if (visible == id) visible = null
            Thread { dir(id).deleteRecursively() }.start()
        }
    }

    /** Browser settings > Clear all: every tab, snapshot, the HTTP cache, cookies and the storage of every site.
     *  Hwallet's own storage (appassets.androidplatform.net) is never touched. */
    fun clearAll() {
        post {
            tabs.values.forEach { t -> t.web?.let { w -> root.removeView(w); w.stopLoading(); w.destroy() } }
            tabs.clear(); visible = null
            Thread { base.deleteRecursively() }.start()
            try { host.clearCache(true) } catch (_: Throwable) {}
            try { CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush() } catch (_: Throwable) {}
            try {
                val ws = WebStorage.getInstance()
                ws.getOrigins { m ->
                    (m as? Map<*, *>)?.keys?.forEach { k ->
                        val o = k?.toString() ?: return@forEach
                        if (!o.contains("appassets.androidplatform.net")) ws.deleteOrigin(o)
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    fun usage(): String {
        val tabsB = size(base)
        val c = File(act.cacheDir, "WebView").let { if (it.exists()) size(it) else 0L } +
            File(act.cacheDir, "org.chromium.android_webview").let { if (it.exists()) size(it) else 0L } +
            File(act.applicationInfo.dataDir, "app_webview").let { if (it.exists()) size(it) else 0L }
        return "{\"tabs\":$tabsB,\"cache\":$c}"
    }

    fun thumbData(id: String): String {
        if (!okId(id)) return ""
        val f = File(dir(id), "thumb.jpg")
        if (!f.exists() || f.length() > 400_000) return ""
        return try { "data:image/jpeg;base64," + Base64.encodeToString(f.readBytes(), Base64.NO_WRAP) } catch (_: Throwable) { "" }
    }

    /** the answer to one request goes back only to the frame that asked, and only if it still shows the same origin */
    fun reply(id: String, json: String) {
        post {
            val t = tabs[id] ?: return@post
            val rid = try { JSONObject(json).optString("id") } catch (_: Throwable) { "" }
            val p = t.pending.remove(rid) ?: return@post
            val w = t.web ?: return@post
            if (originOf(w.url) != p.second) return@post
            try { p.first.postMessage(json) } catch (_: Throwable) {}
        }
    }

    /** a wallet event (accountsChanged, chainChanged, disconnect) for every tab that is showing [origin] */
    fun emit(origin: String, json: String) {
        post {
            tabs.values.forEach { t ->
                val w = t.web ?: return@forEach
                val px = t.lastProxy ?: return@forEach
                if (t.lastOrigin == origin && originOf(w.url) == origin) try { px.postMessage(json) } catch (_: Throwable) {}
            }
        }
    }

    // ---------------------------------------------------------------- activity lifecycle

    fun pause() { post { tabs.values.forEach { t -> t.web?.let { save(t); it.onPause() } } ; CookieManager.getInstance().flush() } }
    fun resume() { post { visible?.let { tabs[it]?.web?.onResume() } } }
    /** called on the UI thread from HwActivity.onDestroy */
    fun destroy() { try { ui.removeCallbacksAndMessages(null); tabs.values.forEach { t -> t.web?.let { save(t); root.removeView(it); it.destroy() } }; tabs.clear() } catch (_: Throwable) {} }

    // ---------------------------------------------------------------- internals

    private fun isWeb(u: String?) = u != null && (u.startsWith("https://") || u.startsWith("http://"))
    private fun originOf(u: String?): String = try {
        val x = Uri.parse(u ?: ""); if (x.scheme == null || x.host == null) "" else x.scheme + "://" + x.host + (if (x.port > 0) ":" + x.port else "")
    } catch (_: Throwable) { "" }

    private fun online(): Boolean = try {
        val cm = act.getSystemService(ConnectivityManager::class.java)
        val c = cm.getNetworkCapabilities(cm.activeNetwork)
        c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } catch (_: Throwable) { true }

    /** cache first while restored and untouched, or while the mobile-data limit is on */
    private fun mode(t: Tab): Int = if ((t.restoring && !t.touched) || HwNet.rate.on()) WebSettings.LOAD_CACHE_ELSE_NETWORK else WebSettings.LOAD_DEFAULT
    private fun live(t: Tab, w: WebView) { t.touched = true; t.restoring = false; w.settings.cacheMode = mode(t) }

    private fun ev(o: JSONObject) { js("window.hwBrEv&&hwBrEv(${q(o.toString())})") }
    private fun navEv(t: Tab, w: WebView, loading: Boolean, progress: Int) {
        val u = if (t.offline) t.url else (w.url ?: t.url)
        if (isWeb(u)) t.url = u
        ev(JSONObject().put("t", "nav").put("id", t.id).put("url", t.url).put("title", w.title ?: "")
            .put("back", w.canGoBack()).put("fwd", w.canGoForward()).put("loading", loading).put("progress", progress).put("offline", t.offline))
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility", "RequiresFeature")
    private fun make(t: Tab): WebView {
        val w = WebView(act)
        w.visibility = View.GONE
        with(w.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true); builtInZoomControls = true; displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if (Build.VERSION.SDK_INT >= 26) safeBrowsingEnabled = true
            userAgentString = userAgentString.replace("; wv)", ")")   // some dApps refuse a "WebView" user agent
            cacheMode = mode(t)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, false)
        // wallet providers: https only, before any page script; the message channel carries the page's real origin
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(w, "hwBridge", setOf("https://*")) { _, msg: WebMessageCompat, origin: Uri, isMain: Boolean, proxy: JavaScriptReplyProxy ->
                val data = msg.data ?: return@addWebMessageListener
                if (data.length > 1_000_000) return@addWebMessageListener
                val rid = try { JSONObject(data).optString("id") } catch (_: Throwable) { "" }
                if (rid.isEmpty() || rid.length > 64) return@addWebMessageListener
                val o = originOf(origin.toString())
                if (!isMain || !o.startsWith("https://")) {
                    try { proxy.postMessage(JSONObject().put("id", rid).put("error", JSONObject().put("code", 4100).put("message", "Only the page itself can use the wallet")).toString()) } catch (_: Throwable) {}
                    return@addWebMessageListener
                }
                t.lastProxy = proxy; t.lastOrigin = o
                if (t.pending.size > 200) t.pending.clear()
                t.pending[rid] = Pair(proxy, o)
                js("window.hwBrReq&&hwBrReq(${q(t.id)},${q(o)},${q(data)})")
            }
            val s = script
            if (s != null && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) WebViewCompat.addDocumentStartJavaScript(w, s, setOf("https://*"))
        }
        w.setOnTouchListener { _, _ -> if (!t.touched) live(t, w); false }
        w.setOnScrollChangeListener { _, _, y, _, _ -> t.scroll = y; scrollEv(t) }
        w.setDownloadListener { url, _, _, _, _ -> try { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)) } catch (_: Throwable) {} }

        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url; val s = u.scheme?.lowercase() ?: return true
                if (s == "https" || s == "http") {
                    if (t.offline && req.isForMainFrame) { leaveSnapshot(t, view); live(t, view); view.loadUrl(u.toString()); return true }
                    if (req.isForMainFrame && req.hasGesture() && !t.touched) live(t, view)
                    return false
                }
                if (s == "wc") { onWc(u.toString()); return true }
                outside(u.toString(), s)
                return true
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { navEv(t, view, true, 10) }
            override fun onPageFinished(view: WebView, url: String?) {
                navEv(t, view, false, 100)
                if (t.pendingScroll > 0) { val y = t.pendingScroll; t.pendingScroll = 0; ui.postDelayed({ try { view.evaluateJavascript("window.scrollTo(0,$y)", null) } catch (_: Throwable) {} }, 350) }
                if (!t.offline && isWeb(view.url)) { save(t); snapshotSoon(t) }
            }
            override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
                if (req.isForMainFrame && !t.offline && File(dir(t.id), "page.mht").exists() && (t.restoring || !online())) loadSnapshot(t, view)
            }
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? = capped(req)
            @android.annotation.TargetApi(26)
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                if (t.web === view) { root.removeView(view); view.destroy(); t.web = null; if (visible == t.id) visible = null; ev(JSONObject().put("t", "gone").put("id", t.id)) }
                return true
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            private var lastP = 0
            override fun onProgressChanged(view: WebView, p: Int) { if (p - lastP >= 15 || p == 100 || p < lastP) { lastP = p; navEv(t, view, p < 100, p) } }
            override fun onReceivedTitle(view: WebView, title: String?) { navEv(t, view, view.progress < 100, view.progress) }
            override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
                if (icon == null || t.offline) return
                try {
                    val b = Bitmap.createScaledBitmap(icon, 48, 48, true); val o = ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.PNG, 100, o)
                    if (o.size() < 11_000) ev(JSONObject().put("t", "icon").put("id", t.id).put("icon", "data:image/png;base64," + Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP)))
                } catch (_: Throwable) {}
            }
            /** target=_blank / window.open after a tap: a new Hwallet tab (never a popup window) */
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                if (!isUserGesture) return false
                val tmp = WebView(act)
                tmp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean { grab(r.url.toString()); return true }
                    override fun onPageStarted(v: WebView, url: String?, f: Bitmap?) { grab(url) }
                    private var done = false
                    private fun grab(u: String?) {
                        if (done) return; done = true
                        if (isWeb(u)) ev(JSONObject().put("t", "new").put("url", u))
                        else if (u != null && u.startsWith("wc:")) onWc(u)
                        ui.post { try { tmp.destroy() } catch (_: Throwable) {} }
                    }
                }
                (resultMsg.obj as WebView.WebViewTransport).webView = tmp
                resultMsg.sendToTarget()
                return true
            }
        }
        return w
    }

    /** intent:, market:, mailto:, tel: … open outside, never with an explicit component (no intent hijacking) */
    private fun outside(u: String, scheme: String) {
        try {
            val i = if (scheme == "intent") Intent.parseUri(u, Intent.URI_INTENT_SCHEME).apply { component = null; selector = null; addCategory(Intent.CATEGORY_BROWSABLE) }
                    else Intent(Intent.ACTION_VIEW, Uri.parse(u)).addCategory(Intent.CATEGORY_BROWSABLE)
            act.startActivity(i)
        } catch (_: ActivityNotFoundException) {
        } catch (_: Throwable) {}
    }

    /** v1.4 (#53): while the mobile-data limit is on, the page's sub-resources (images, scripts, styles) are fetched
     *  through HwNet and share the same limit; the page document itself loads cache-first (see [mode]). */
    private fun capped(req: WebResourceRequest): WebResourceResponse? {
        if (!HwNet.rate.on() || req.isForMainFrame || req.method != "GET" || req.url.scheme != "https") return null
        return try {
            val url = req.url.toString()
            val b = okhttp3.Request.Builder().url(url)
            req.requestHeaders?.forEach { (k, v) -> if (!k.equals("Accept-Encoding", true)) b.header(k, v) }
            CookieManager.getInstance().getCookie(url)?.let { b.header("Cookie", it) }
            val r = HwNet.withTimeout(30000).newCall(b.build()).execute()
            val body = r.body ?: run { r.close(); return null }
            val mt = body.contentType()
            val hdr = HashMap<String, String>()
            r.headers.names().forEach { n -> if (!n.equals("Content-Encoding", true) && !n.equals("Content-Length", true) && !n.equals("Transfer-Encoding", true) && !n.equals("Set-Cookie", true)) hdr[n] = r.header(n) ?: "" }
            r.headers("Set-Cookie").forEach { CookieManager.getInstance().setCookie(url, it) }
            WebResourceResponse(if (mt != null) mt.type + "/" + mt.subtype else "application/octet-stream", mt?.charset()?.name(), r.code, r.message.ifEmpty { "OK" }, hdr, body.byteStream())
        } catch (_: Throwable) { null }
    }

    private fun restore(t: Tab, w: WebView, url: String, scroll: Int) {
        t.restoring = true; t.touched = false; t.pendingScroll = scroll; t.url = url
        w.settings.cacheMode = mode(t)
        val mht = File(dir(t.id), "page.mht")
        if (!online() && mht.exists()) { loadSnapshot(t, w); return }
        val b = readState(File(dir(t.id), "state.bin"))
        if (b != null && w.restoreState(b) != null) return
        if (isWeb(url)) w.loadUrl(url)
    }

    private fun loadSnapshot(t: Tab, w: WebView) {
        val f = File(dir(t.id), "page.mht"); if (!f.exists()) return
        t.offline = true
        w.settings.allowFileAccess = true          // only while the snapshot shows; MHTML runs no scripts
        w.loadUrl(Uri.fromFile(f).toString())
    }

    private fun leaveSnapshot(t: Tab, w: WebView) { if (t.offline) { t.offline = false; w.settings.allowFileAccess = false } }

    private fun save(t: Tab) {
        val w = t.web ?: return
        if (t.offline) return
        try {
            val b = Bundle(); w.saveState(b) ?: return
            val p = Parcel.obtain()
            val bytes = try { b.writeToParcel(p, 0); p.marshall() } finally { p.recycle() }
            if (bytes.size > 4_000_000) return
            val d = dir(t.id); d.mkdirs()
            val meta = JSONObject().put("url", t.url).put("scroll", t.scroll).toString().toByteArray()
            Thread { try { atomic(File(d, "state.bin"), bytes); atomic(File(d, "meta.json"), meta) } catch (_: Throwable) {} }.start()
        } catch (_: Throwable) {}
    }

    private fun readState(f: File): Bundle? {
        if (!f.exists() || f.length() > 4_000_000) return null
        val p = Parcel.obtain()
        return try { val a = f.readBytes(); p.unmarshall(a, 0, a.size); p.setDataPosition(0); p.readBundle(HwBrowser::class.java.classLoader) } catch (_: Throwable) { null } finally { p.recycle() }
    }

    private val snapT = HashMap<String, Runnable>()
    private fun snapshotSoon(t: Tab) {
        snapT[t.id]?.let { ui.removeCallbacks(it) }
        val r = Runnable {
            val w = t.web ?: return@Runnable
            if (t.offline || !isWeb(w.url)) return@Runnable
            val d = dir(t.id); d.mkdirs()
            try { w.saveWebArchive(File(d, "page.mht").absolutePath, false, null) } catch (_: Throwable) {}
        }
        snapT[t.id] = r; ui.postDelayed(r, 2500)
    }

    private fun thumb(t: Tab) {
        val w = t.web ?: return
        if (w.visibility != View.VISIBLE || w.width <= 0 || w.height <= 0) return
        try {
            val s = 360f / w.width
            val bmp = Bitmap.createBitmap(360, (w.height * s).toInt().coerceAtLeast(1), Bitmap.Config.RGB_565)
            val c = Canvas(bmp); c.scale(s, s); c.translate(-w.scrollX.toFloat(), -w.scrollY.toFloat()); w.draw(c)
            val o = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, 70, o); bmp.recycle()
            val d = dir(t.id); d.mkdirs(); atomic(File(d, "thumb.jpg"), o.toByteArray())
        } catch (_: Throwable) {}
    }

    private val scrollT = HashMap<String, Runnable>()
    private fun scrollEv(t: Tab) {
        scrollT[t.id]?.let { ui.removeCallbacks(it) }
        val r = Runnable { ev(JSONObject().put("t", "scroll").put("id", t.id).put("y", (t.scroll / act.resources.displayMetrics.density).toInt())) }
        scrollT[t.id] = r; ui.postDelayed(r, 600)
    }

    /** keep at most MAX_LIVE WebViews: the least recently shown hidden tab is saved to disk and released */
    private fun evict() {
        val alive = tabs.values.filter { it.web != null }
        if (alive.size <= MAX_LIVE) return
        alive.filter { it.id != visible }.sortedBy { it.shown }.take(alive.size - MAX_LIVE).forEach { t ->
            save(t); thumb(t)
            t.web?.let { root.removeView(it); it.destroy() }
            t.web = null; t.pending.clear(); t.lastProxy = null
            ev(JSONObject().put("t", "gone").put("id", t.id))
        }
    }

    private fun atomic(f: File, b: ByteArray) { val tmp = File(f.parentFile, f.name + ".tmp"); tmp.writeBytes(b); if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) } }
    private fun size(f: File): Long = if (!f.exists()) 0L else if (f.isFile) f.length() else (f.listFiles() ?: emptyArray()).sumOf { size(it) }

    companion object { const val MAX_LIVE = 4 }
}
