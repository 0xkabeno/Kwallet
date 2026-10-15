package app.hwallet

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import android.util.Log
import android.view.MotionEvent
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
import org.json.JSONArray
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
 * v1.7 (P1, #86): every tab uses the normal HTTP cache (LOAD_DEFAULT); only while the phone is offline a tab loads
 * cache-first, and an offline restored tab shows its MHTML snapshot. Closing a tab deletes its folder. At most [MAX_LIVE] WebViews stay
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
        var uaH: androidx.webkit.ScriptHandler? = null   // v1.8.7: navigator.* to match the chosen user agent
        var desk = HwUa.deskDef()   // v1.8.8 (R4b): desktop view for this tab; v1.8.9 (R18): new tabs start from the last choice
        var vpH: androidx.webkit.ScriptHandler? = null   // v1.8.9 (R18): the 1024 px desktop viewport (desktop view only)
        var cover: android.widget.ImageView? = null       // v1.8.9 (R14): the hibernated tab's last picture, until its page paints
        var reloadOnShow = false                          // v1.8.9 (R18): a hidden tab whose user agent changed reloads when shown
        var inc = false                                   // v1.8.13: incognito tab (nothing kept after it closes)
        val incOrigins = HashSet<String>()                // v1.8.13: origins it visited (cleanup when the WebView has no profiles)
    }

    private val ui = Handler(Looper.getMainLooper())
    private val tabs = HashMap<String, Tab>()
    private var baseUa: String? = null
    init { try { HwShield.init(act); HwUa.init(act) } catch (e: Throwable) { Log.w(TAG, "shield/ua init", e) } }

    // ---------------------------------------------------------------- v1.8.13: incognito tabs
    // Where the WebView supports profiles (androidx.webkit MULTI_PROFILE, WebView 123+), incognito tabs run in their own
    // profile "hw-incognito": separate cookies, web storage, IndexedDB, cache and service workers. The profile is deleted
    // (with everything in it) when the last incognito tab closes, and at every start (left over after a crash / kill).
    // Without profile support: no HTTP cache for the tab, no saved state / snapshot / thumbnail / icon, and on close the
    // cookies and web storage of every origin the tab visited are deleted. Incognito tabs never get the saved-inputs script
    // and their autofill messages are dropped here, so nothing they type is saved.
    private val incIds = java.util.Collections.synchronizedSet(HashSet<String>())
    private val incProfiles: Boolean by lazy { try { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) } catch (_: Throwable) { false } }
    @Volatile private var afScript: String? = null
    fun markInc(id: String) { if (okId(id)) incIds.add(id) }
    fun afInit(s: String) { if (s.length in 100..200_000 && afScript == null) afScript = s }
    @SuppressLint("RequiresFeature")
    private fun incWipe(tries: Int) {
        if (!incProfiles) return
        post {
            if (tabs.values.any { it.inc && it.web != null }) return@post
            try {
                val ps = androidx.webkit.ProfileStore.getInstance()
                if (ps.getAllProfileNames().contains(INC_PROFILE)) ps.deleteProfile(INC_PROFILE)
            } catch (e: IllegalStateException) { if (tries < 5) ui.postDelayed({ incWipe(tries + 1) }, 800L) }
            catch (e: Throwable) { Log.w(TAG, "incognito profile delete", e) }
        }
    }
    /** fallback (no profiles): the cookies and storage of what this incognito tab visited */
    private fun incForget(t: Tab) {
        val os = HashSet(t.incOrigins); if (os.isEmpty()) return
        try {
            val cm = CookieManager.getInstance()
            for (o in os) {
                val c = cm.getCookie(o) ?: continue
                c.split(';').map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }.forEach { n ->
                    val host = Uri.parse(o).host ?: return@forEach
                    cm.setCookie(o, "$n=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/")
                    cm.setCookie(o, "$n=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Domain=$host")
                    val parts = host.split('.'); if (parts.size > 2) cm.setCookie(o, "$n=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Domain=." + parts.takeLast(2).joinToString("."))
                }
            }
            cm.flush()
        } catch (_: Throwable) {}
        try { val ws = WebStorage.getInstance(); os.forEach { ws.deleteOrigin(it) } } catch (_: Throwable) {}
    }
    init { incWipe(0) }
    private val base = File(act.filesDir, "br")
    /** v1.8.9 (R16): one background thread for every browser file write / read / trim (never the UI thread, writes in order) */
    private val ioEx = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "hw-br-io").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }
    private fun fio(f: () -> Unit) { try { ioEx.execute { try { f() } catch (e: Throwable) { Log.w(TAG, "browser file op failed", e) } } } catch (_: Throwable) {} }
    @Volatile private var trimAt = 0L
    private fun trimSoon() { val n = System.currentTimeMillis(); if (n - trimAt > 60_000) { trimAt = n; trim() } }
    @Volatile private var script: String? = null
    private var visible: String? = null
    private var top = 0
    private var bottom = 0

    // ---------------------------------------------------------------- v1.7.4 (#108): native pull to refresh (https and http)
    private var pull: HwPull? = null
    private var pTab: Tab? = null
    private var py0 = 0f
    private var px0 = 0f
    private var pCand = false
    private var pOn = false
    private val pCap = Runnable { try { pull?.finish() } catch (_: Throwable) {} }

    fun ptrUi(v: String) { ptrUiPref = v.take(24); post { pull?.let { if (!it.busy) { it.reset(); applyPullStyle(it) } } } }
    private fun applyPullStyle(p: HwPull) {
        val v = ptrUiPref; val st = v.substringBefore(':')
        p.style = if (st == "m3" || st == "miuix") st else "og"; p.dark = v.endsWith(":dark")
    }
    private fun pv(w: WebView): HwPull {
        val p = pull ?: HwPull(act).also { pull = it }
        if (!p.busy) applyPullStyle(p)
        val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        lp.topMargin = top; lp.bottomMargin = bottom
        if (p.parent == null) root.addView(p, lp) else p.layoutParams = lp
        p.visibility = View.VISIBLE; p.bringToFront()
        p.onOffset = { o -> try { w.translationY = o } catch (_: Throwable) {} }
        return p
    }
    private fun pullOff() {
        if (pOn) tabs.values.forEach { t -> t.web?.let { pullRelease(it) } }
        pull?.let { it.reset(); it.visibility = View.GONE }
        ui.removeCallbacks(pCap); pTab = null; pOn = false; pCand = false
        tabs.values.forEach { t -> t.web?.translationY = 0f }
    }
    /* v1.7.5: page-at-top probe. canScrollVertically() only sees the page's root scroller; dApps that scroll an inner box
       (body fixed, #root overflow:auto) looked "at top" all the time, so a scroll-up inside them pulled the page. On finger-down
       the page is asked (evaluateJavascript, works on http and https, nothing persistent is injected) whether the element under
       the finger has a scrolled ancestor; the pull only engages once the answer is "at top". */
    private var pSeq = 0
    private var pT0 = 0L
    private var pTop: Boolean? = null
    private var pOver = View.OVER_SCROLL_IF_CONTENT_SCROLLS
    private fun probeTop(w: WebView, x: Float, y: Float) {
        val seq = ++pSeq; pTop = null
        try {
            @Suppress("DEPRECATION") val sc = (w.scale.takeIf { it > 0f } ?: act.resources.displayMetrics.density)
            val cx = (x / sc).toInt(); val cy = (y / sc).toInt()
            val js = "(function(x,y){try{var se=document.scrollingElement||document.documentElement;if((se&&se.scrollTop>0)||window.scrollY>0)return 0;" +
                "var e=document.elementFromPoint(x,y);while(e&&e.nodeType===1&&e!==document.body&&e!==document.documentElement){" +
                "if(e.scrollTop>0){var o=getComputedStyle(e).overflowY;if(o==='auto'||o==='scroll'||o==='overlay')return 0}e=e.parentElement}" +
                "return 1}catch(_){return 1}})($cx,$cy)"
            w.evaluateJavascript(js) { r -> if (seq == pSeq) { pTop = r?.trim() != "0"; if (pTop == false) { pCand = false } } }
        } catch (_: Throwable) { pTop = true }
    }
    private fun pullEngage(w: WebView, e: MotionEvent) {
        pOn = true; py0 = e.rawY
        // the page stops seeing this finger (no click, no drag, no overscroll stretch) while the pull runs
        try { val c = MotionEvent.obtain(e); c.action = MotionEvent.ACTION_CANCEL; w.onTouchEvent(c); c.recycle() } catch (_: Throwable) {}
        try { pOver = w.overScrollMode; w.overScrollMode = View.OVER_SCROLL_NEVER } catch (_: Throwable) {}
        pv(w)
    }
    private fun pullRelease(w: WebView) { try { w.overScrollMode = pOver } catch (_: Throwable) {} }
    /** true = the event belongs to the pull (the WebView does not get it) */
    private fun pullTouch(t: Tab, w: WebView, e: MotionEvent): Boolean {
        try {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pOn = false; py0 = e.rawY; px0 = e.rawX
                    pCand = visible == t.id && !w.canScrollVertically(-1) && pull?.busy != true && pTab == null
                    if (pCand) probeTop(w, e.x, e.y)
                }
                MotionEvent.ACTION_POINTER_DOWN -> { if (pOn) { pull?.cancel(); pullRelease(w); pOn = false; pCand = false; return true }; pCand = false }
                MotionEvent.ACTION_MOVE -> {
                    if (pOn) {
                        if (w.canScrollVertically(-1)) { pOn = false; pCand = false; pull?.cancel(); pullRelease(w); return true }
                        pull?.drag(Math.max(0f, e.rawY - py0)); return true
                    }
                    if (!pCand) return false
                    val d = act.resources.displayMetrics.density
                    val dy = e.rawY - py0; val dx = Math.abs(e.rawX - px0); val slop = 8f * d
                    if (dy < -slop || (dx > slop * 2f && dx > dy)) { pCand = false; return false }
                    val top = pTop ?: return false          // the page has not answered yet: wait (it answers within a frame or two)
                    if (!top) { pCand = false; return false }
                    if (dy > slop && dy > dx * 1.5f && !w.canScrollVertically(-1)) { pullEngage(w, e); pull?.drag(0f); return true }
                    return false
                }
                MotionEvent.ACTION_UP -> {
                    pCand = false
                    if (pOn) {
                        pOn = false; pullRelease(w)
                        val p = pull
                        if (p != null && p.release() && pTab == null) {
                            live(t, w)
                            pTab = t; pT0 = android.os.SystemClock.uptimeMillis(); ui.removeCallbacks(pCap); ui.postDelayed(pCap, 9000)
                            if (t.offline) { leaveSnapshot(t, w); if (isWeb(t.url)) w.loadUrl(t.url) } else w.reload()
                        }
                        return true
                    }
                }
                MotionEvent.ACTION_CANCEL -> { if (pOn) { pull?.cancel(); pullRelease(w); pOn = false; pCand = false; return true }; pCand = false }
            }
        } catch (x: Throwable) { Log.w(TAG, "pull to refresh", x) }
        return false
    }
    private fun pullDone(t: Tab) { if (pTab === t) { ui.removeCallbacks(pCap); pTab = null; try { pull?.finish() } catch (_: Throwable) {} } }

    private fun q(s: String) = JSONObject.quote(s)
    private fun okId(id: String) = id.length in 2..24 && id.matches(Regex("^t[0-9a-z]+$"))
    private fun dir(id: String) = File(base, id)
    /** v1.7 (P1): failures are logged (v1.6 swallowed them, which hid #86: an invalid origin rule threw in make()) */
    private fun post(f: () -> Unit) { ui.post { try { f() } catch (e: Throwable) { Log.w(TAG, "browser op failed", e) } } }

    // ---------------------------------------------------------------- page -> native (via HwNative)

    /* v1.8.15: the page sends the script again when Chains change (switched-off chains are not announced); new tabs use the latest */
    fun init(s: String) { if (s.length in 100..400_000) script = s }

    fun open(id: String, url: String, scroll: Int) {
        if (!okId(id)) return
        post {
            val t = tabs.getOrPut(id) { Tab(id) }
            if (t.web != null) return@post
            if (incIds.contains(id)) t.inc = true
            val w = make(t)
            t.web = w
            // attached at once (hidden) so it lays out and loads like a normal view; show() places it
            val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            lp.topMargin = top; lp.bottomMargin = bottom
            root.addView(w, lp)
            evict()
            // v1.8.9 (R14/R16): the saved state, desktop flag and last picture are read off the UI thread; the page starts
            // as soon as they are in (a tab with nothing saved starts at once)
            if (t.inc) { if (isWeb(url)) { t.url = url; w.loadUrl(url); startEv(t) }; return@post }
            fio {
                val d = dir(id)
                val bytes = try { File(d, "state.bin").takeIf { it.exists() && it.length() <= 4_000_000 }?.readBytes() } catch (_: Throwable) { null }
                val mht = try { File(d, "page.mht").exists() } catch (_: Throwable) { false }
                val meta = try { File(d, "meta.json").takeIf { it.exists() && it.length() < 20_000 }?.readText()?.let { JSONObject(it) } } catch (_: Throwable) { null }
                val pic = if (bytes != null || mht) try { File(d, "thumb.jpg").takeIf { it.exists() && it.length() < 400_000 }?.let { BitmapFactory.decodeFile(it.absolutePath) } } catch (_: Throwable) { null } else null
                ui.post {
                    try {
                        if (t.web !== w) { pic?.recycle(); return@post }
                        if (meta != null && meta.has("desk")) { val dk = meta.optBoolean("desk"); if (dk != t.desk) { t.desk = dk; applyUaTo(t, w) } }
                        if (bytes != null || mht) { if (pic != null) cover(t, w, pic); restore(t, w, url, scroll, bytes, mht) }
                        else if (isWeb(url)) { t.url = url; w.loadUrl(url); startEv(t) }
                    } catch (e: Throwable) { Log.w(TAG, "open", e) }
                }
            }
        }
    }

    /** v1.8.9 (R14): a hibernated tab shows its last picture at once, over the WebView, until the page paints */
    private fun cover(t: Tab, w: WebView, pic: Bitmap) {
        uncover(t)
        val iv = android.widget.ImageView(act)
        iv.scaleType = android.widget.ImageView.ScaleType.MATRIX
        iv.setBackgroundColor(android.graphics.Color.WHITE)
        iv.setImageBitmap(pic)
        iv.addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ -> try { val s = (r - l).toFloat() / pic.width; (v as android.widget.ImageView).imageMatrix = android.graphics.Matrix().apply { setScale(s, s) } } catch (_: Throwable) {} }
        val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        lp.topMargin = top; lp.bottomMargin = bottom
        iv.visibility = w.visibility
        root.addView(iv, lp)
        t.cover = iv
        ui.postDelayed({ if (t.cover === iv) uncover(t) }, 6000)   // never longer than a few seconds
    }
    private fun uncover(t: Tab) {
        val iv = t.cover ?: return; t.cover = null
        try { root.removeView(iv); iv.setImageDrawable(null) } catch (_: Throwable) {}
    }

    fun show(id: String, topCss: Int, botCss: Int) {
        post {
            val d = act.resources.displayMetrics.density
            top = (topCss * d).toInt(); bottom = (botCss * d).toInt()
            val t = tabs[id] ?: return@post
            val w = t.web ?: return@post
            if (visible != id) pullOff()
            tabs.values.forEach { o -> if (o !== t) o.web?.let { if (it.visibility != View.GONE) { save(o); it.visibility = View.GONE; it.onPause(); prio(it, false) } } }
            val lp = (w.layoutParams as? FrameLayout.LayoutParams) ?: FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            lp.topMargin = top; lp.bottomMargin = bottom
            w.layoutParams = lp
            if (w.parent == null) root.addView(w, lp)
            w.visibility = View.VISIBLE; w.onResume(); w.bringToFront()
            t.cover?.let { c -> c.layoutParams = FrameLayout.LayoutParams(lp.width, lp.height).apply { topMargin = top; bottomMargin = bottom }; c.visibility = View.VISIBLE; c.bringToFront() }
            tabs.values.forEach { o -> if (o !== t) o.cover?.visibility = View.GONE }
            prio(w, true)
            if (t.reloadOnShow) { t.reloadOnShow = false; reloadUa(t, w) }
            t.shown = System.currentTimeMillis(); visible = id
        }
    }

    fun hide() {
        post {
            // v1.7 (P1): out of the way first (a sheet in Hwallet is waiting to be seen), then save; the switcher's thumbnail
            // comes from snap(), so the slow software draw of a thumbnail no longer delays the hide
            pullOff()
            tabs.values.forEach { t -> t.cover?.visibility = View.GONE; t.web?.let { if (it.visibility == View.VISIBLE) { it.visibility = View.GONE; it.onPause(); prio(it, false); ui.post { save(t) } } } }
            visible = null
        }
    }

    fun load(id: String, url: String) {
        if (!isWeb(url)) return
        post { val t = tabs[id] ?: return@post; val w = t.web ?: return@post; leaveSnapshot(t, w); live(t, w); t.url = url; w.loadUrl(url); startEv(t) }
    }

    /** v1.7 (P1): Android Back while a page is on screen goes back in that page first (UI thread, from HwActivity) */
    fun back(): Boolean {
        val t = tabs[visible ?: return false] ?: return false
        val w = t.web ?: return false
        // v1.8.7: dApps navigate with history.pushState (no page load), so ask the WebView itself every time
        if (!w.canGoBack()) return false
        live(t, w); w.goBack(); ui.postDelayed({ try { navEv(t, w, w.progress < 100, w.progress) } catch (_: Throwable) {} }, 120); return true
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
            val gone = tabs.remove(id)
            gone?.let { t -> uncover(t); t.web?.let { w -> root.removeView(w); w.stopLoading(); try { if (t.inc && !incProfiles) w.clearCache(true) } catch (_: Throwable) {}; w.destroy() } }
            if (visible == id) visible = null
            incIds.remove(id)
            if (gone?.inc == true) { if (incProfiles) incWipe(0) else incForget(gone) }
            fio { dir(id).deleteRecursively() }
        }
    }

    /** Browser settings > Clear all: every tab, snapshot, the HTTP cache, cookies and the storage of every site.
     *  Hwallet's own storage (appassets.androidplatform.net) is never touched. */
    fun clearAll() {
        post {
            tabs.values.forEach { t -> uncover(t); t.web?.let { w -> root.removeView(w); w.stopLoading(); w.destroy() } }
            tabs.clear(); visible = null; incIds.clear(); incWipe(0)
            fio { base.deleteRecursively(); icoDir.deleteRecursively(); try { brHttp.cache?.evictAll() } catch (_: Throwable) {} }
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
        val http = File(act.cacheDir, "br_http").let { if (it.exists()) size(it) else 0L }
        val ico = if (icoDir.exists()) size(icoDir) else 0L
        return "{\"tabs\":$tabsB,\"cache\":${c + http},\"icons\":$ico,\"capTabs\":$CAP_TABS}"
    }

    /** v1.8.9 (R16): Browser settings > Storage > Clear cached pages: the HTTP caches only (tabs, cookies and logins stay) */
    fun clearCache() {
        post { try { host.clearCache(true) } catch (_: Throwable) {} }
        fio { try { brHttp.cache?.evictAll() } catch (_: Throwable) {}; icoDir.listFiles()?.forEach { it.delete() } }
    }

    /** v1.8.9 (R16): storage stays bounded however long the browser is used. Tab folders: saved pages (page.mht) of the
     *  least recently used tabs go first until all tabs fit in [CAP_TABS]; their history (state.bin) stays, so they still
     *  restore from the network. Site icons: at most [CAP_ICONS] files, oldest first. Runs on the io thread. */
    private fun trim() {
        try {
            val dirs = (base.listFiles() ?: emptyArray()).filter { it.isDirectory }
            var total = dirs.sumOf { size(it) }
            if (total > CAP_TABS) {
                for (d in dirs.sortedBy { File(it, "meta.json").lastModified() }) {
                    if (total <= CAP_TABS * 8 / 10) break
                    if (d.name == visible) continue
                    for (n in arrayOf("page.mht", "thumb.jpg")) { val f = File(d, n); if (f.exists()) { total -= f.length(); f.delete() } }
                }
            }
            val ics = icoDir.listFiles() ?: emptyArray()
            if (ics.size > CAP_ICONS) ics.sortedBy { it.lastModified() }.take(ics.size - CAP_ICONS).forEach { it.delete() }
        } catch (e: Throwable) { Log.w(TAG, "trim", e) }
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
    fun destroy() { try { ui.removeCallbacksAndMessages(null); tabs.values.forEach { t -> uncover(t); t.web?.let { save(t); root.removeView(it); it.destroy() } }; tabs.clear() } catch (_: Throwable) {} }

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

    /** v1.7 (P1, #86): the normal HTTP cache (LOAD_DEFAULT: fresh pages, cached sub-resources by their headers). Cache-first
     *  only while the phone has no validated network, so nothing ever waits on a stale copy when the network is there. */
    @Suppress("UNUSED_PARAMETER")
    private fun mode(t: Tab): Int = if (t.inc && !incProfiles) WebSettings.LOAD_NO_CACHE else if (!online()) WebSettings.LOAD_CACHE_ELSE_NETWORK else WebSettings.LOAD_DEFAULT
    /** the visible tab's renderer is important; hidden tabs may be killed first under memory pressure */
    private fun prio(w: WebView, on: Boolean) {
        try { if (Build.VERSION.SDK_INT >= 26) w.setRendererPriorityPolicy(if (on) WebView.RENDERER_PRIORITY_IMPORTANT else WebView.RENDERER_PRIORITY_BOUND, true) } catch (_: Throwable) {}
    }
    private fun startEv(t: Tab) { ev(JSONObject().put("t", "nav").put("id", t.id).put("url", t.url).put("title", "").put("back", false).put("fwd", false).put("loading", true).put("progress", 10).put("offline", false)) }
    private fun live(t: Tab, w: WebView) { t.touched = true; t.restoring = false; w.settings.cacheMode = mode(t) }

    private fun ev(o: JSONObject) { js("window.hwBrEv&&hwBrEv(${q(o.toString())})") }
    private fun navEv(t: Tab, w: WebView, loading: Boolean, progress: Int) {
        val u = if (t.offline) t.url else (w.url ?: t.url)
        if (isWeb(u)) t.url = u
        ev(JSONObject().put("t", "nav").put("id", t.id).put("url", t.url).put("title", w.title ?: "")
            .put("back", w.canGoBack()).put("fwd", w.canGoForward()).put("loading", loading).put("progress", progress).put("offline", t.offline)
            .put("desk", t.desk)
            .put("blk", try { HwShield.count(Uri.parse(t.url).host?.lowercase() ?: "") } catch (_: Throwable) { 0 }).put("shield", HwShield.on() && !(try { HwShield.siteOff(Uri.parse(t.url).host?.lowercase() ?: "") } catch (_: Throwable) { false })))
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility", "RequiresFeature")
    private fun make(t: Tab): WebView {
        val w = WebView(act)
        var incProf = false
        if (t.inc && incProfiles) try {
            androidx.webkit.ProfileStore.getInstance().getOrCreateProfile(INC_PROFILE)
            WebViewCompat.setProfile(w, INC_PROFILE); incProf = true
        } catch (e: Throwable) { Log.w(TAG, "incognito profile", e) }
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
            if (baseUa == null) baseUa = userAgentString
            userAgentString = HwUa.uaFor(t.desk, baseUa ?: userAgentString)   // Off: the WebView's own minus "; wv" (some dApps refuse a "WebView" user agent); v1.8.9 (R18): desktop view = the chosen desktop UA or Windows Chrome
            cacheMode = mode(t)
            if (t.inc) { saveFormData = false; if (!incProf) cacheMode = WebSettings.LOAD_NO_CACHE }
        }
        bfcache(w.settings)
        w.isVerticalScrollBarEnabled = false
        if (Build.VERSION.SDK_INT >= 26) w.defaultFocusHighlightEnabled = false
        prio(w, false)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, false)
        // wallet providers before any page script; the message channel carries the page's real origin.
        // v1.7 (P1, #86 root cause): v1.6 passed the origin rule "https://*", which Chromium's OriginMatcher rejects
        // (a host wildcard must be "*.domain"); addWebMessageListener threw IllegalArgumentException, make() never
        // returned and the tab never attached or loaded (stuck at 8% on every phone). The rule is now "*" (every origin)
        // and https-only is enforced here (origin check below) and in hw_provider.js (location.protocol). A failure to
        // inject is logged and never stops the page from loading.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) try {
            WebViewCompat.addWebMessageListener(w, "hwBridge", ORIGINS) { _, msg: WebMessageCompat, origin: Uri, isMain: Boolean, proxy: JavaScriptReplyProxy ->
                val data = msg.data ?: return@addWebMessageListener
                if (data.length > 1_000_000) return@addWebMessageListener
                val rid = try { JSONObject(data).optString("id") } catch (_: Throwable) { "" }
                if (rid.isEmpty() || rid.length > 64) return@addWebMessageListener
                val o = originOf(origin.toString())
                /* v1.8.13: nothing typed in an incognito tab is ever offered for saving */
                if (t.inc && (try { JSONObject(data).optString("ns") } catch (_: Throwable) { "" }) == "hwaf") return@addWebMessageListener
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
            if (s != null && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) WebViewCompat.addDocumentStartJavaScript(w, s, ORIGINS)
            /* v1.8.13: saved inputs (offer to save / fill), never in incognito tabs */
            val af = afScript
            if (af != null && !t.inc && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) WebViewCompat.addDocumentStartJavaScript(w, af, ORIGINS)
        } catch (e: Throwable) { Log.e(TAG, "wallet provider injection failed; the page loads without it", e) }
        uaScript(t, w)
        w.setOnTouchListener { _, e -> if (!t.touched) live(t, w); pullTouch(t, w, e) }
        w.setOnScrollChangeListener { _, _, y, _, _ -> t.scroll = y; scrollEv(t) }
        w.setDownloadListener { url, _, _, _, _ -> try { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)) } catch (_: Throwable) {} }

        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url; val s = u.scheme?.lowercase() ?: return true
                /* v1.8.16 (4.2 d): a WalletConnect deep link the page opens (wallet-list buttons of a WC modal: trust://wc?uri=,
                   metamask:wc?uri=, intent://wc?uri=…, https://<wallet>/wc?uri=wc:…) pairs with Hwallet's own WalletConnect wallet
                   instead of leaving the app. Only a real WC v2 pairing URI (wc:<topic>@2?…symKey=…) is taken. */
                wcIn(u.toString())?.let { onWc(it); return true }
                if (s == "https" || s == "http") {
                    if (t.offline && req.isForMainFrame) { leaveSnapshot(t, view); live(t, view); view.loadUrl(u.toString()); return true }
                    if (req.isForMainFrame && req.hasGesture() && !t.touched) live(t, view)
                    return false
                }
                if (s == "wc") { onWc(u.toString()); return true }
                outside(u.toString(), s)
                return true
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { if (t.inc) { val o = originOf(url); if (o.isNotEmpty()) t.incOrigins.add(o) }; try { Uri.parse(url ?: "").host?.let { HwShield.resetCount(it.lowercase()) } } catch (_: Throwable) {}; navEv(t, view, true, 10); lateInject(view) }
            override fun onPageCommitVisible(view: WebView, url: String?) { lateInject(view); hide(view, url); uncover(t) }
            // v1.8.7: single-page dApps (pushState / hash routes) update Back / Forward too
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) { navEv(t, view, view.progress < 100, view.progress) }
            override fun onPageFinished(view: WebView, url: String?) {
                pullDone(t)
                lateInject(view)
                hide(view, url)
                navEv(t, view, false, 100)
                if (t.pendingScroll > 0) { val y = t.pendingScroll; t.pendingScroll = 0; ui.postDelayed({ try { view.evaluateJavascript("window.scrollTo(0,$y)", null) } catch (_: Throwable) {} }, 350) }
                if (!t.offline && !t.inc && isWeb(view.url)) { save(t); snapshotSoon(t); siteIcon(t, view) }
            }
            override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
                if (req.isForMainFrame && !t.offline && File(dir(t.id), "page.mht").exists() && (t.restoring || !online())) loadSnapshot(t, view)
            }
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? = (try { HwShield.check(req, t.url) } catch (_: Throwable) { null }) ?: (if (t.inc) null else capped(req))
            @android.annotation.TargetApi(26)
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                if (t.web === view) { uncover(t); root.removeView(view); view.destroy(); t.web = null; if (visible == t.id) visible = null; ev(JSONObject().put("t", "gone").put("id", t.id)) }
                return true
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            private var lastP = 0
            override fun onProgressChanged(view: WebView, p: Int) { if (p == 100 && android.os.SystemClock.uptimeMillis() - pT0 > 400) pullDone(t); if (p - lastP >= 15 || p == 100 || p < lastP) { lastP = p; navEv(t, view, p < 100, p); lateInject(view) } }
            override fun onReceivedTitle(view: WebView, title: String?) { navEv(t, view, view.progress < 100, view.progress) }
            override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
                if (icon == null || t.offline || t.inc) return
                if (icoFile(Uri.parse(view.url ?: "").host).exists()) return   // the site's own large icon is already on disk
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
                        val wc = wcIn(u ?: "")
                        if (wc != null) onWc(wc)
                        else if (isWeb(u)) ev(JSONObject().put("t", "new").put("url", u))
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

    /** v1.8.16: the WalletConnect v2 pairing URI inside a link (itself, or its uri= parameter), else null */
    private fun wcIn(u: String): String? {
        try {
            val ok = { w: String -> Regex("^wc:[0-9a-fA-F]{64}@2\\?").containsMatchIn(w) && w.contains("symKey=") }
            if (u.startsWith("wc:", true)) return if (ok(u)) u else null
            val m = Regex("[?&#]uri=(wc(?:%3A|:)[^&#\\s]+)", RegexOption.IGNORE_CASE).find(u) ?: return null
            var w = m.groupValues[1]
            for (k in 0 until 2) { if (w.startsWith("wc%3A", true)) w = java.net.URLDecoder.decode(w, "UTF-8") }
            return if (ok(w)) w.take(2048) else null
        } catch (_: Throwable) { return null }
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
    /* v1.8.7: the blocker's element hiding (uBO cosmetic filters) for this page */
    private fun hide(w: WebView, url: String?) {
        if (!HwShield.on() || !isWeb(url)) return
        Thread {
            val css = try { HwShield.css(url) } catch (_: Throwable) { "" }
            if (css.isNotEmpty()) ui.post { try { w.evaluateJavascript("(function(c){try{var s=document.getElementById('hw-shield-css');if(!s){s=document.createElement('style');s.id='hw-shield-css';(document.head||document.documentElement).appendChild(s)}s.textContent=c}catch(e){}})(" + q(css) + ")", null) } catch (_: Throwable) {} }
        }.apply { isDaemon = true }.start()
    }
    @SuppressLint("RequiresFeature")
    private fun uaScript(t: Tab, w: WebView) {
        try { t.uaH?.remove() } catch (_: Throwable) {}; t.uaH = null
        try { t.vpH?.remove() } catch (_: Throwable) {}; t.vpH = null
        if (!dss) return
        val p = HwUa.presetFor(t.desk)
        if (p != null) try { t.uaH = WebViewCompat.addDocumentStartJavaScript(w, HwUa.scriptFor(p), setOf("*")) } catch (e: Throwable) { Log.w(TAG, "ua script", e) }
        if (t.desk) try { t.vpH = WebViewCompat.addDocumentStartJavaScript(w, HwUa.VIEWPORT_JS, setOf("*")) } catch (e: Throwable) { Log.w(TAG, "viewport script", e) }
    }
    /** v1.8.9 (R18): the tab's user agent (settings.userAgentString), navigator.* hints and viewport, from its desktop flag */
    private fun applyUaTo(t: Tab, w: WebView) {
        w.settings.userAgentString = HwUa.uaFor(t.desk, baseUa ?: w.settings.userAgentString)
        w.settings.useWideViewPort = true; w.settings.loadWithOverviewMode = true
        uaScript(t, w)
    }
    /** a page load with the new user agent: loadUrl of the current address (a plain reload() can keep the old request headers) */
    private fun reloadUa(t: Tab, w: WebView) {
        live(t, w)
        val u = w.url ?: t.url
        if (t.offline) { leaveSnapshot(t, w); if (isWeb(t.url)) w.loadUrl(t.url) }
        else if (isWeb(u)) w.loadUrl(u) else w.reload()
        navEv(t, w, true, 10)
    }
    /** v1.8.7: a new user agent (Settings or the browser menu) applies to every open tab; the page on screen reloads.
     *  v1.8.9 (R18): a desktop user agent puts every tab in desktop view, a phone one takes them out of it */
    fun applyUa() {
        post {
            val dk = HwUa.deskDef()
            tabs.values.forEach { t ->
                t.desk = dk
                t.web?.let { w -> applyUaTo(t, w); if (w.visibility == View.VISIBLE) reloadUa(t, w) else t.reloadOnShow = true }
                saveMeta(t)
            }
        }
    }
    /** v1.8.8 (R4b): desktop view on/off for one tab. v1.8.9 (R18): works with any chosen user agent (a desktop preset or
     *  Custom string is used as is, otherwise Windows Chrome), sets the 1024 px desktop viewport, is kept with the tab
     *  (meta.json, also through hibernation) and becomes the default for new tabs */
    fun desk(id: String, on: Boolean) {
        HwUa.setDeskDef(on)
        post {
            val t = tabs[id] ?: return@post; val w = t.web ?: return@post
            if (t.desk == on) { navEv(t, w, w.progress < 100, w.progress); return@post }
            t.desk = on
            applyUaTo(t, w); reloadUa(t, w); saveMeta(t)
        }
    }
    private fun saveMeta(t: Tab) {
        if (t.inc) return
        val d = dir(t.id); val meta = JSONObject().put("url", t.url).put("scroll", t.scroll).put("desk", t.desk).toString().toByteArray()
        fio { try { d.mkdirs(); atomic(File(d, "meta.json"), meta) } catch (_: Throwable) {} }
    }
    /** v1.8.9 (R14): back / forward from the WebView's back-forward cache (no reload) where this WebView supports it.
     *  Called by reflection so it compiles against any androidx.webkit; a WebView without the feature just skips it. */
    private fun bfcache(s: WebSettings) {
        try {
            if (!WebViewFeature.isFeatureSupported("BACK_FORWARD_CACHE")) return
            Class.forName("androidx.webkit.WebSettingsCompat").getMethod("setBackForwardCacheEnabled", WebSettings::class.java, java.lang.Boolean.TYPE).invoke(null, s, true)
        } catch (_: Throwable) {}
    }
    /** v1.8.8 (R4): one of Hwallet's own scripts in the tab's page; the result goes back to Hwallet's page */
    fun evalIn(id: String, seq: Int, code: String) {
        post {
            val t = tabs[id]; val w = t?.web
            if (w == null) { js("window.hwBrEvalDone&&hwBrEvalDone(${q(id)},$seq,null)"); return@post }
            w.evaluateJavascript(code) { r -> js("window.hwBrEvalDone&&hwBrEvalDone(${q(id)},$seq,${q(r ?: "null")})") }
        }
    }
    /** v1.8.7: blocker switched on/off, or this site's shield: reload the page on screen */
    fun reloadShown() { post { visible?.let { tabs[it]?.web?.reload() } } }

    /** v1.8.9 (R14b): the capped path's client keeps an HTTP disk cache (LRU, 64 MB): unchanged resources are revalidated
     *  with If-None-Match / If-Modified-Since and served from disk (a 304 costs a few hundred bytes of the data limit) */
    private val brHttp: okhttp3.OkHttpClient by lazy { HwNet.withTimeout(30000).newBuilder().cache(okhttp3.Cache(File(act.cacheDir, "br_http"), 64L * 1024 * 1024)).build() }
    private fun capped(req: WebResourceRequest): WebResourceResponse? {
        if (!HwNet.rate.on() || req.isForMainFrame || req.method != "GET" || req.url.scheme != "https") return null
        return try {
            val url = req.url.toString()
            val b = okhttp3.Request.Builder().url(url)
            req.requestHeaders?.forEach { (k, v) -> if (!k.equals("Accept-Encoding", true)) b.header(k, v) }
            CookieManager.getInstance().getCookie(url)?.let { b.header("Cookie", it) }
            val r = brHttp.newCall(b.build()).execute()
            val body = r.body ?: run { r.close(); return null }
            val mt = body.contentType()
            val hdr = HashMap<String, String>()
            r.headers.names().forEach { n -> if (!n.equals("Content-Encoding", true) && !n.equals("Content-Length", true) && !n.equals("Transfer-Encoding", true) && !n.equals("Set-Cookie", true)) hdr[n] = r.header(n) ?: "" }
            r.headers("Set-Cookie").forEach { CookieManager.getInstance().setCookie(url, it) }
            WebResourceResponse(if (mt != null) mt.type + "/" + mt.subtype else "application/octet-stream", mt?.charset()?.name(), r.code, r.message.ifEmpty { "OK" }, hdr, body.byteStream())
        } catch (_: Throwable) { null }
    }

    private fun restore(t: Tab, w: WebView, url: String, scroll: Int, bytes: ByteArray?, mht: Boolean) {
        t.restoring = true; t.touched = false; t.pendingScroll = scroll; t.url = url
        w.settings.cacheMode = mode(t)
        if (!online() && mht) { loadSnapshot(t, w); return }
        val b = bytes?.let { unState(it) }
        if (b != null && w.restoreState(b) != null) return
        uncover(t)
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
        if (t.offline || t.inc) return
        try {
            val b = Bundle(); w.saveState(b) ?: return
            val p = Parcel.obtain()
            val bytes = try { b.writeToParcel(p, 0); p.marshall() } finally { p.recycle() }
            if (bytes.size > 4_000_000) return
            val d = dir(t.id); d.mkdirs()
            val meta = JSONObject().put("url", t.url).put("scroll", t.scroll).put("desk", t.desk).toString().toByteArray()
            fio { try { atomic(File(d, "state.bin"), bytes); atomic(File(d, "meta.json"), meta) } catch (_: Throwable) {}; trimSoon() }
        } catch (_: Throwable) {}
    }

    private fun unState(a: ByteArray): Bundle? {
        val p = Parcel.obtain()
        return try { p.unmarshall(a, 0, a.size); p.setDataPosition(0); p.readBundle(HwBrowser::class.java.classLoader) } catch (_: Throwable) { null } finally { p.recycle() }
    }

    private val snapT = HashMap<String, Runnable>()
    private fun snapshotSoon(t: Tab) {
        snapT[t.id]?.let { ui.removeCallbacks(it) }
        val r = Runnable {
            val w = t.web ?: return@Runnable
            if (t.offline || t.inc || !isWeb(w.url)) return@Runnable
            val d = dir(t.id)
            fio { d.mkdirs(); ui.post { try { if (t.web === w && !t.offline) w.saveWebArchive(File(d, "page.mht").absolutePath, false, null) } catch (_: Throwable) {} } }
        }
        snapT[t.id] = r; ui.postDelayed(r, 2500)
    }

    private fun thumb(t: Tab) {
        val w = t.web ?: return
        if (t.inc) return
        if (w.visibility != View.VISIBLE || w.width <= 0 || w.height <= 0) return
        try {
            val s = 360f / w.width
            val bmp = Bitmap.createBitmap(360, (w.height * s).toInt().coerceAtLeast(1), Bitmap.Config.RGB_565)
            val c = Canvas(bmp); c.scale(s, s); c.translate(-w.scrollX.toFloat(), -w.scrollY.toFloat()); w.draw(c)
            val d = dir(t.id)
            fio { val o = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, 70, o); bmp.recycle(); d.mkdirs(); atomic(File(d, "thumb.jpg"), o.toByteArray()) }
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
        alive.filter { it.id != visible && !it.inc }.sortedBy { it.shown }.take(alive.size - MAX_LIVE).forEach { t ->
            save(t); thumb(t)
            t.web?.let { root.removeView(it); it.destroy() }
            t.web = null; t.pending.clear(); t.lastProxy = null
            ev(JSONObject().put("t", "gone").put("id", t.id))
        }
    }

    private fun atomic(f: File, b: ByteArray) { val tmp = File(f.parentFile, f.name + ".tmp"); tmp.writeBytes(b); if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) } }
    private fun size(f: File): Long = if (!f.exists()) 0L else if (f.isFile) f.length() else (f.listFiles() ?: emptyArray()).sumOf { size(it) }

    /** v1.7 (P1): WebViews without DOCUMENT_START_SCRIPT (older than ~M87, e.g. the API 30 emulator's 83) still get the
     *  providers: injected as early as the page lets us (start, every progress step, commit, finish). hw_provider.js runs once
     *  per document (__hwProv) and only on https top pages; EIP-6963 / Wallet Standard announce it to dApps that load first. */
    private val dss: Boolean by lazy { try { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) } catch (_: Throwable) { false } }
    private val wml: Boolean by lazy { try { WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) } catch (_: Throwable) { false } }
    private fun lateInject(view: WebView) {
        if (dss || !wml) return
        val s = script ?: return
        if (view.url?.startsWith("https://") != true) return
        try { view.evaluateJavascript(s, null) } catch (_: Throwable) {}
        val af = afScript; val inc = tabs.values.any { it.web === view && it.inc }
        if (af != null && !inc) try { view.evaluateJavascript(af, null) } catch (_: Throwable) {}
    }
    /** what this phone's WebView supports, for Settings > About and the device check */
    fun features(): String = JSONObject().put("documentStartScript", dss).put("webMessageListener", wml)
        .put("webview", try { WebViewCompat.getCurrentWebViewPackage(act)?.versionName ?: "" } catch (_: Throwable) { "" }).toString()

    // ---------------------------------------------------------------- v1.7 (P1, #99): site icons
    // Bundled dApp icons live in the page (assets/dapps). For every other site the best icon is fetched once, on the first
    // finished load: web app manifest icon > apple-touch-icon > <link rel=icon> > /favicon.ico, decoded (PNG/JPEG/WebP/ICO
    // frame; SVG is skipped), scaled to 96 px and kept in files/ico/<host>.png for 30 days. The page asks for it by host
    // (iconData) and gets a "site icon" event; a letter tile shows only until then or when a site has none.
    private val icoDir = File(act.filesDir, "ico")
    private val icoBusy = java.util.Collections.synchronizedSet(HashSet<String>())
    private fun icoKey(h: String?): String = (h ?: "").lowercase().removePrefix("www.").replace(Regex("[^a-z0-9.-]"), "_").take(120)
    private fun icoFile(h: String?): File = File(icoDir, icoKey(h) + ".png")
    /** network work (site icons) on its own thread, so it never holds up the file queue ([ioEx]) */
    private fun io(f: () -> Unit) { Thread { try { f() } catch (e: Throwable) { Log.w(TAG, "browser io failed", e) } }.apply { isDaemon = true }.start() }

    fun iconData(host: String): String {
        val f = icoFile(host)
        if (host.isEmpty() || !f.exists() || f.length() > 60_000) return ""
        return try { "data:image/png;base64," + Base64.encodeToString(f.readBytes(), Base64.NO_WRAP) } catch (_: Throwable) { "" }
    }

    private fun siteIcon(t: Tab, view: WebView) {
        val u = Uri.parse(view.url ?: return); val host = u.host ?: return
        if (u.scheme != "https") return
        val f = icoFile(host)
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < 30L * 86_400_000L) { icoEv(t, host); return }
        if (!icoBusy.add(icoKey(host))) return
        view.evaluateJavascript(ICON_JS) { r ->
            val links = try { JSONObject(JSONArray("[$r]").getString(0)) } catch (_: Throwable) { JSONObject() }
            io {
                try {
                    val got = bestIcon(u, links)
                    if (got != null) { icoDir.mkdirs(); atomic(f, got); ui.post { icoEv(t, host) } }
                } finally { icoBusy.remove(icoKey(host)) }
            }
        }
    }

    private fun icoEv(t: Tab, host: String) {
        val d = iconData(host); if (d.isEmpty()) return
        ev(JSONObject().put("t", "icon").put("id", t.id).put("host", icoKey(host)).put("icon", d).put("site", true))
    }

    /** candidates in order of preference; the first one that decodes to at least 32 px wins */
    private fun bestIcon(page: Uri, links: JSONObject): ByteArray? {
        val c = ArrayList<String>()
        fun sz(a: JSONArray, i: Int) = try { a.getJSONArray(i).optInt(1, 0) } catch (_: Throwable) { 0 }
        fun hrefs(k: String): List<String> {
            val a = links.optJSONArray(k) ?: return emptyList()
            return (0 until a.length()).sortedByDescending { i -> val s = sz(a, i); if (s in 96..512) 10_000 - kotlin.math.abs(s - 192) else s }
                .mapNotNull { i -> try { a.getJSONArray(i).getString(0) } catch (_: Throwable) { null } }
        }
        val man = links.optString("m")
        if (man.startsWith("https://")) try {
            val j = JSONObject(String(get(man, 200_000) ?: ByteArray(0)))
            val ics = j.optJSONArray("icons") ?: JSONArray()
            (0 until ics.length()).map { ics.getJSONObject(it) }
                .filter { !it.optString("purpose").contains("monochrome") && !it.optString("type").contains("svg") && !it.optString("src").endsWith(".svg") }
                .sortedByDescending { o -> val s = o.optString("sizes").split(" ").last().substringBefore("x").toIntOrNull() ?: 0; if (s in 96..512) 10_000 - kotlin.math.abs(s - 192) else s }
                .forEach { o -> try { c.add(java.net.URL(java.net.URL(man), o.getString("src")).toString()) } catch (_: Throwable) {} }
        } catch (_: Throwable) {}
        c.addAll(hrefs("a")); c.addAll(hrefs("i"))
        c.add(page.scheme + "://" + page.host + "/apple-touch-icon.png"); c.add(page.scheme + "://" + page.host + "/favicon.ico")
        for (href in c.distinct().take(8)) {
            if (!href.startsWith("https://") || href.endsWith(".svg")) continue
            val b = get(href, 1_000_000) ?: continue
            val bmp = try { BitmapFactory.decodeByteArray(b, 0, b.size) } catch (_: Throwable) { null } ?: continue
            if (bmp.width < 32 || bmp.height < 32) { bmp.recycle(); continue }
            val s = Bitmap.createScaledBitmap(bmp, 96, 96, true)
            val o = ByteArrayOutputStream(); s.compress(Bitmap.CompressFormat.PNG, 100, o)
            if (s !== bmp) s.recycle(); bmp.recycle()
            if (o.size() in 1..60_000) return o.toByteArray()
        }
        return null
    }

    private fun get(url: String, max: Int): ByteArray? = try {
        HwNet.withTimeout(10000).newCall(okhttp3.Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r ->
            val b = r.body
            if (!r.isSuccessful || b == null || (b.contentLength() > max)) null
            else b.byteStream().use { s -> val buf = ByteArrayOutputStream(); val tmp = ByteArray(16384); var n = 0
                while (true) { val k = s.read(tmp); if (k < 0) break; n += k; if (n > max) return@use null; buf.write(tmp, 0, k) }
                buf.toByteArray() }
        }
    } catch (_: Throwable) { null }

    companion object {
        /** v1.7.4 (#108): "og" | "m3" | "miuix", optionally ":dark"; set by the page (HwNative.brPtrUi) */
        @Volatile var ptrUiPref: String = "og"
        const val MAX_LIVE = 3   // v1.8.9 (R14): the last 3 tabs stay live; older ones hibernate (saved state + picture)
        const val CAP_TABS = 160L * 1024 * 1024
        const val CAP_ICONS = 400
        private const val TAG = "HwBrowser"
        private const val INC_PROFILE = "hw-incognito"
        // every origin: Chromium accepts only a bare star or scheme://host rules whose host wildcard is a "*." prefix (an https-star rule is invalid)
        private val ORIGINS = setOf("*")
        private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
        private const val ICON_JS = "(function(){var o={m:'',a:[],i:[]};try{document.querySelectorAll('link[rel][href]').forEach(function(l){" +
            "var r=(l.getAttribute('rel')||'').toLowerCase(),s=parseInt((l.getAttribute('sizes')||'').split('x')[0])||0;" +
            "if(r.indexOf('manifest')>=0)o.m=l.href;else if(r.indexOf('apple-touch-icon')>=0)o.a.push([l.href,s||180]);" +
            "else if(/(^|\\s)icon/.test(r))o.i.push([l.href,s]);})}catch(e){}return JSON.stringify(o)})()"
    }
}
