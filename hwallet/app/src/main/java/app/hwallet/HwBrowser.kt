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
import androidx.webkit.WebSettingsCompat
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
        var ctx: android.view.ContextThemeWrapper? = null // dark pages: the WebView's own theme (its isLightTheme follows the setting)
    }

    private val ui = Handler(Looper.getMainLooper())
    private val tabs = HashMap<String, Tab>()
    private var baseUa: String? = null
    init { try { HwShield.init(act); HwUa.init(act) } catch (e: Throwable) { Log.w(TAG, "shield/ua init", e) } }
    init { try { HwScam.init(act) } catch (e: Throwable) { Log.w(TAG, "scam init", e) } }   // v1.9.14: deceptive-site warning

    /** v1.9.14: a top-level navigation to a listed deceptive site: the page shows its full-page warning (nothing loads) */
    private fun scamHit(u: String?): String? = try { if (!isWeb(u)) null else HwScam.check(Uri.parse(u).host) } catch (_: Throwable) { null }
    private fun scamEv(t: Tab, u: String, why: String) {
        val h = try { Uri.parse(u).host?.lowercase() ?: "" } catch (_: Throwable) { "" }
        ev(JSONObject().put("t", "scam").put("id", t.id).put("host", h).put("url", u).put("why", why))
    }
    init { try { darkPref = darkNorm(act.getSharedPreferences(DARK_PREFS, android.content.Context.MODE_PRIVATE).getString("mode", darkPref) ?: darkPref) } catch (_: Throwable) {} }

    // ---------------------------------------------------------------- dark pages (Browser ⋮ > Dark pages: Auto / On / Off)
    // The page sends "on", "off", "auto:dark" or "auto:light" (Auto = Hwallet's theme) through HwNative.brDark; it is kept
    // here (prefs "hwbr_dark") so tabs opened before the page syncs start right. Hwallet targets SDK 34, so on WebView 105+
    // (ALGORITHMIC_DARKENING) prefers-color-scheme and darkening follow the WebView context's isLightTheme: every tab's
    // WebView gets its own ContextThemeWrapper whose theme is switched between Theme.DeviceDefault and .Light (isLightTheme),
    // and algorithmic darkening is allowed only while dark. Sites with their own dark theme use it; others are darkened.
    // Older WebViews (no ALGORITHMIC_DARKENING) use the legacy FORCE_DARK + PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING.
    // Android 7-9 (no isLightTheme attribute) on WebView 105+: pages stay light (WebView limitation).
    fun dark(v: String) {
        val n = darkNorm(v); if (n != v) return
        darkPref = n
        try { act.getSharedPreferences(DARK_PREFS, android.content.Context.MODE_PRIVATE).edit().putString("mode", n).apply() } catch (_: Throwable) {}
        post { tabs.values.forEach { t -> t.web?.let { w -> applyDark(t, w); darkCheck(t, w) } } }
    }
    private fun darkOn() = darkPref == "on" || darkPref == "auto:dark"
    /* isLightTheme is set only by the platform's base Theme (false) / Theme.Light (true) lineage, not by the ThemeOverlay.Material.* overlays */
    private fun darkTheme() = if (darkOn()) android.R.style.Theme_DeviceDefault else android.R.style.Theme_DeviceDefault_Light
    private fun darkAlgo() = try { WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING) } catch (_: Throwable) { false }
    @Suppress("DEPRECATION")
    @SuppressLint("RequiresFeature")
    private fun applyDark(t: Tab, w: WebView) {
        val on = darkOn()
        try { t.ctx?.theme?.applyStyle(darkTheme(), true) } catch (e: Throwable) { Log.w(TAG, "dark theme", e) }
        try {
            val s = w.settings
            if (darkAlgo()) WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, on)
            else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY))
                    WebSettingsCompat.setForceDarkStrategy(s, WebSettingsCompat.DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING)
                WebSettingsCompat.setForceDark(s, if (on) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF)
            }
        } catch (e: Throwable) { Log.w(TAG, "dark pages", e) }
    }
    /** an open page whose prefers-color-scheme did not follow the switch (a WebView that reads the theme only per load)
     *  reloads: at once on screen, when shown if hidden. Only where this WebView can darken at all. */
    private fun darkCheck(t: Tab, w: WebView) {
        val can = (darkAlgo() && Build.VERSION.SDK_INT >= 29) || (!darkAlgo() && (try { WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK) } catch (_: Throwable) { false }))
        if (!can || t.offline) return
        val want = darkOn()
        ui.postDelayed({
            try {
                if (t.web !== w || darkOn() != want || !isWeb(w.url)) return@postDelayed
                w.evaluateJavascript(DARK_JS) { r ->
                    try {
                        if ((r == "true" || r == "false") && (r == "true") != want && darkOn() == want && t.web === w) {
                            if (w.visibility == View.VISIBLE) reloadUa(t, w) else t.reloadOnShow = true
                        }
                    } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
        }, 400)
    }

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
            if (visible != id) { pullOff(); afDrop(null) }   // v1.8.35 (B13)
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
            pops.values.forEach { p -> if (p.shown && p.parent == id) popPlace(p) else p.box.visibility = View.GONE }   // v1.9.10
        }
    }

    /* v1.8.35 (B13): the autofill dropdown over the active tab (HwAfPop), driven by Hwallet's page (brAfPop/brAfMove/brAfHide) */
    private var afp: HwAfPop? = null
    private fun afPop(): HwAfPop = afp ?: HwAfPop(act) { tab, tok, idx -> js("window.hwAfPicked&&hwAfPicked(${q(tab)},$tok,$idx)") }.also { afp = it }
    fun afShow(id: String, json: String) { if (!okId(id) || json.length > 60_000) return; post { val t = tabs[id]; val w = t?.web; if (t == null || w == null || t.inc || visible != id || w.visibility != View.VISIBLE) { afp?.close(); return@post }; afPop().show(id, w, json) } }
    fun afMove(id: String, json: String) { if (json.length > 2000) return; post { afp?.move(id, json) } }
    fun afHide() { post { afp?.closeQuiet() } }
    private fun afDrop(id: String?) { val p = afp ?: return; if (id == null || p.tabId() == id) p.close() }

    fun hide() {
        post {
            afDrop(null)   // v1.8.35 (B13)
            // v1.7 (P1): out of the way first (a sheet in Hwallet is waiting to be seen), then save; the switcher's thumbnail
            // comes from snap(), so the slow software draw of a thumbnail no longer delays the hide
            pullOff()
            pops.values.forEach { it.box.visibility = View.GONE }   // v1.9.10
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
        if (afp?.isOpen == true) { afDrop(null); return true }   // v1.8.35 (B13): Back closes the autofill dropdown first
        pops.values.lastOrNull { it.shown && it.parent == visible && it.box.visibility == View.VISIBLE }?.let { p -> if (p.w.canGoBack()) p.w.goBack() else popClose(p); return true }   // v1.9.10
        val t = tabs[visible ?: return false] ?: return false
        val w = t.web ?: return false
        // v1.8.7: dApps navigate with history.pushState (no page load), so ask the WebView itself every time
        if (!w.canGoBack()) { if (isWeb(t.url) || isWeb(w.url)) { toStart(t); return true }; return false }
        /* v1.9.19: Back walks the tab's own pages only. The entry behind the first site is the tab's blank start
           (about:blank / data:): instead of going there, the tab returns to the Browser's start page (Hwallet's page
           closes it); Back never leaves the Browser for the wallet. */
        if (!realBack(w)) { toStart(t); return true }
        live(t, w); w.goBack(); ui.postDelayed({ try { navEv(t, w, w.progress < 100, w.progress) } catch (_: Throwable) {} }, 120); return true
    }

    private fun realBack(w: WebView): Boolean = try {
        val h = w.copyBackForwardList(); val i = h.currentIndex
        val prev = if (i > 0) h.getItemAtIndex(i - 1)?.url ?: "" else ""
        prev.startsWith("https://") || prev.startsWith("http://")
    } catch (_: Throwable) { w.canGoBack() }
    private fun toStart(t: Tab) { js("window.hwBrToStart&&hwBrToStart(${q(t.id)})") }

    /* v1.9.19: edge swipes on a page, like Chrome: from the left edge = back, from the right edge = forward. The edge
       band in the middle of the screen is excluded from Android's own back gesture so the swipe reaches the page; an
       arrow follows the finger and the step happens only past the threshold. */
    private var eSide = 0; private var eOn = false; private var ex0 = 0f; private var ey0 = 0f; private var eArrow: android.widget.TextView? = null
    private fun edgeTouch(t: Tab, w: WebView, e: MotionEvent): Boolean {
        try {
            val d = act.resources.displayMetrics.density; val band = 24f * d; val full = 90f * d
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    eOn = false; ex0 = e.rawX; ey0 = e.rawY
                    eSide = if (visible != t.id || pOn) 0 else if (e.x <= band) -1 else if (e.x >= w.width - band) 1 else 0
                }
                MotionEvent.ACTION_POINTER_DOWN -> { if (eOn) { arrowDone(); eOn = false; eSide = 0; return true }; eSide = 0 }
                MotionEvent.ACTION_MOVE -> {
                    if (eSide == 0) return false
                    val dx = (e.rawX - ex0) * (if (eSide < 0) 1f else -1f); val dy = Math.abs(e.rawY - ey0)
                    if (!eOn) {
                        if (dx < -8f * d || dy > 24f * d && dy > dx) { eSide = 0; return false }
                        if (dx > 12f * d && dx > dy * 1.5f) {
                            eOn = true; pCand = false
                            val c = MotionEvent.obtain(e); c.action = MotionEvent.ACTION_CANCEL; w.onTouchEvent(c); c.recycle()
                        } else return false
                    }
                    arrow(w, eSide, Math.min(1f, Math.max(0f, dx / full)), e.rawY); return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!eOn) { eSide = 0; return false }
                    val dx = (e.rawX - ex0) * (if (eSide < 0) 1f else -1f); val side = eSide
                    eOn = false; eSide = 0; arrowDone()
                    if (e.actionMasked == MotionEvent.ACTION_UP && dx >= full) {
                        try { w.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY) } catch (_: Throwable) {}
                        if (side < 0) { if (w.canGoBack() && realBack(w)) { live(t, w); w.goBack(); ui.postDelayed({ try { navEv(t, w, w.progress < 100, w.progress) } catch (_: Throwable) {} }, 120) } else toStart(t) }
                        else if (w.canGoForward()) { live(t, w); w.goForward(); ui.postDelayed({ try { navEv(t, w, w.progress < 100, w.progress) } catch (_: Throwable) {} }, 120) }
                    }
                    return true
                }
            }
        } catch (x: Throwable) { Log.w(TAG, "edge swipe", x) }
        return false
    }
    private fun arrow(w: WebView, side: Int, p: Float, y: Float) {
        val d = act.resources.displayMetrics.density; val sz = (40 * d).toInt()
        val a = eArrow ?: android.widget.TextView(act).also { v ->
            v.gravity = android.view.Gravity.CENTER; v.setTextColor(0xFFFFFFFF.toInt()); v.textSize = 22f; v.includeFontPadding = false
            v.background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0xCC202124.toInt()) }
            v.elevation = 6 * d; root.addView(v, FrameLayout.LayoutParams(sz, sz)); eArrow = v
        }
        a.text = if (side < 0) "\u2039" else "\u203A"
        a.bringToFront(); a.visibility = View.VISIBLE
        val loc = IntArray(2); root.getLocationOnScreen(loc)
        a.translationY = y - loc[1] - sz / 2f
        val travel = 56 * d * p
        a.translationX = if (side < 0) -sz + travel + 4 * d else root.width - travel - 4 * d
        a.alpha = 0.35f + 0.65f * p; a.scaleX = if (p >= 1f) 1.12f else 1f; a.scaleY = a.scaleX
    }
    private fun arrowDone() { eArrow?.let { it.animate().alpha(0f).setDuration(120).withEndAction { it.visibility = View.GONE }.start() } }
    private fun edgeExclude(w: WebView) {
        if (Build.VERSION.SDK_INT < 29) return
        w.addOnLayoutChangeListener { v, l, tp, r, b, _, _, _, _ ->
            try {
                val d = act.resources.displayMetrics.density; val bw = (24 * d).toInt(); val bh = (100 * d).toInt(); val mid = (b - tp) / 2
                v.systemGestureExclusionRects = listOf(android.graphics.Rect(0, mid - bh, bw, mid + bh), android.graphics.Rect(r - l - bw, mid - bh, r - l, mid + bh))
            } catch (_: Throwable) {}
        }
    }

    fun nav(id: String, a: String) {
        post {
            val t = tabs[id] ?: return@post; val w = t.web ?: return@post
            when (a) {
                "back" -> if (w.canGoBack() && realBack(w)) { live(t, w); w.goBack() } else toStart(t)
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
            afDrop(id)   // v1.8.35 (B13)
            val gone = tabs.remove(id)
            pops.values.filter { it.parent == id }.forEach { popClose(it) }   // v1.9.10
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
            val t = tabs[id] ?: pops[id]?.t ?: return@post
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
    fun destroy() { try { pops.values.toList().forEach { popClose(it) } } catch (_: Throwable) {}; try { afp?.closeQuiet(); afp = null } catch (_: Throwable) {}; try { ui.removeCallbacksAndMessages(null); tabs.values.forEach { t -> uncover(t); t.web?.let { save(t); root.removeView(it); it.destroy() } }; tabs.clear() } catch (_: Throwable) {} }

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

    /** the wallet bridge (hwBridge) and the provider scripts for one WebView: a tab, or a sign-in window (v1.9.10) */
    @SuppressLint("RequiresFeature")
    private fun bridge(t: Tab, w: WebView, withAf: Boolean) {
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
            if (af != null && withAf && !t.inc && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) WebViewCompat.addDocumentStartJavaScript(w, af, ORIGINS)
        } catch (e: Throwable) { Log.e(TAG, "wallet provider injection failed; the page loads without it", e) }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility", "RequiresFeature")
    private fun make(t: Tab): WebView {
        val ctx = android.view.ContextThemeWrapper(act, darkTheme()); t.ctx = ctx   // dark pages: this tab's own theme
        val w = WebView(ctx)
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
        applyDark(t, w)
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
        bridge(t, w, true)
        uaScript(t, w)
        w.setOnTouchListener { _, e -> if (!t.touched) live(t, w); edgeTouch(t, w, e) || pullTouch(t, w, e) }
        edgeExclude(w)   // v1.9.19
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
                    if (req.isForMainFrame) scamHit(u.toString())?.let { why -> scamEv(t, u.toString(), why); return true }   // v1.9.14
                    if (t.offline && req.isForMainFrame) { leaveSnapshot(t, view); live(t, view); view.loadUrl(u.toString()); return true }
                    if (req.isForMainFrame && req.hasGesture() && !t.touched) live(t, view)
                    return false
                }
                if (s == "wc") { onWc(u.toString()); return true }
                outside(u.toString(), s)
                return true
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { scamHit(url)?.let { why -> try { view.stopLoading() } catch (_: Throwable) {}; scamEv(t, url ?: "", why); return }   /* v1.9.14: loads that skip shouldOverrideUrlLoading (typed, restored, redirects) */
                afDrop(t.id); if (t.inc) { val o = originOf(url); if (o.isNotEmpty()) t.incOrigins.add(o) }; try { Uri.parse(url ?: "").host?.let { HwShield.resetCount(it.lowercase()) } } catch (_: Throwable) {}; navEv(t, view, true, 10); lateInject(view) }
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
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? = vpnBlock(req) ?: (try { HwShield.check(req, t.url) } catch (_: Throwable) { null }) ?: (if (t.inc) null else capped(req))
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
            /** v1.8.25: <input type=file> on a website opens the system file picker (uploads from storage) */
            override fun onShowFileChooser(view: WebView, cb: android.webkit.ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean =
                try { act.chooseFiles(cb, params) } catch (_: Throwable) { false }
            /** target=_blank / window.open after a tap: a new Hwallet tab (never a popup window) */
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                if (!isUserGesture) return false
                /* v1.9.10 (F6b): every window starts as a real one (window.opener works); its first address decides: Internet
                   Identity stays as a sign-in window over the tab, anything else becomes a new tab as before */
                try { makePop(t, t.id, false, resultMsg); return true } catch (e: Throwable) { Log.w(TAG, "sign-in window", e) }
                val tmp = WebView(act)
                tmp.webViewClient = object : WebViewClient() {
                    @android.annotation.TargetApi(26)
                    override fun onRenderProcessGone(v: WebView, d: android.webkit.RenderProcessGoneDetail): Boolean { try { v.destroy() } catch (_: Throwable) {}; return true }
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

    // ---------------------------------------------------------------- v1.9.10 (F6b): sign-in windows
    // window.open / target=_blank after a tap gets a real WebView window (the page keeps window.opener and can talk to it).
    // Its first address decides: Internet Identity stays open as a sign-in window over its tab (II's passkey prompts are
    // answered by Kwallet through the same wallet bridge); anything else becomes a new Hwallet tab and the window is dropped,
    // as before. A window that never loads anything is dropped after a minute. Windows a sign-in window opens itself (an
    // identity provider's login) stay as sign-in windows.
    private class Pop(val t: Tab, val parent: String, val box: FrameLayout, val w: WebView, val head: android.widget.TextView, val always: Boolean) { var shown = false; var decided = false }
    private val pops = LinkedHashMap<String, Pop>()
    private var popN = 0
    private val iiRe = Regex("^(identity\\.ic0\\.app|identity\\.internetcomputer\\.org|id\\.ai|www\\.id\\.ai|beta\\.id\\.ai)$")
    private fun iiHost(u: String?): Boolean = try { iiRe.matches(Uri.parse(u ?: "").host?.lowercase() ?: "") } catch (_: Throwable) { false }

    @SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
    private fun makePop(parent: Tab, parentId: String, always: Boolean, resultMsg: Message) {
        while (pops.size >= 4) popClose(pops.values.first())
        val pt = Tab("p" + (++popN).toString(36)).apply { inc = parent.inc; desk = parent.desk }
        val ctx = android.view.ContextThemeWrapper(act, darkTheme()); pt.ctx = ctx
        val w = WebView(ctx); pt.web = w   // reply() checks the window's own address before it answers
        if (pt.inc && incProfiles) try {
            androidx.webkit.ProfileStore.getInstance().getOrCreateProfile(INC_PROFILE)
            WebViewCompat.setProfile(w, INC_PROFILE)
        } catch (e: Throwable) { Log.w(TAG, "incognito profile (window)", e) }
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
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if (Build.VERSION.SDK_INT >= 26) safeBrowsingEnabled = true
            if (baseUa == null) baseUa = userAgentString
            userAgentString = HwUa.uaFor(pt.desk, baseUa ?: userAgentString)
            if (pt.inc) saveFormData = false
        }
        applyDark(pt, w)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, false)
        bridge(pt, w, false)
        uaScript(pt, w)
        val d = act.resources.displayMetrics.density
        val dark = darkOn()
        val box = FrameLayout(act).apply { visibility = View.GONE; setBackgroundColor(if (dark) 0xFF101418.toInt() else 0xFFFFFFFF.toInt()); isClickable = true }
        val head = android.widget.TextView(act).apply {
            text = "\u2715   Sign-in window"; textSize = 14f; gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((16 * d).toInt(), 0, (16 * d).toInt(), 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(if (dark) 0xFFE6E8EC.toInt() else 0xFF1B2230.toInt()); setBackgroundColor(if (dark) 0xFF1A1F27.toInt() else 0xFFF1F3F6.toInt())
        }
        val bar = (44 * d).toInt()
        box.addView(head, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, bar))
        box.addView(w, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply { topMargin = bar })
        val p = Pop(pt, parentId, box, w, head, always)
        head.setOnClickListener { popClose(p) }
        pops[pt.id] = p
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url; val sc = u.scheme?.lowercase() ?: return true
                if (sc != "https" && sc != "http") { if (sc == "wc") onWc(u.toString()) else outside(u.toString(), sc); return true }
                if (!p.decided && req.isForMainFrame) return !decide(p, u.toString())
                return false
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (!p.decided && isWeb(url)) { if (!decide(p, url!!)) { view.stopLoading(); return } }
                if (p.shown) popTitle(p, url)
            }
            override fun onPageFinished(view: WebView, url: String?) { if (p.shown) popTitle(p, url) }
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? = vpnBlock(req)
            @android.annotation.TargetApi(26)
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean { popClose(p); return true }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onCloseWindow(window: WebView) { popClose(p) }
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                if (!isUserGesture) return false
                return try { makePop(pt, parentId, true, resultMsg); true } catch (e: Throwable) { false }
            }
            override fun onShowFileChooser(view: WebView, cb: android.webkit.ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean =
                try { act.chooseFiles(cb, params) } catch (_: Throwable) { false }
        }
        (resultMsg.obj as WebView.WebViewTransport).webView = w
        resultMsg.sendToTarget()
        ui.postDelayed({ if (!p.decided && pops[pt.id] === p) popClose(p) }, 60_000)
    }
    /** the window's first address: true = it stays (Internet Identity, or opened by a sign-in window), false = a new tab */
    private fun decide(p: Pop, u: String): Boolean {
        p.decided = true
        if (p.always || iiHost(u)) { p.shown = true; popTitle(p, u); popPlace(p); return true }
        val wc = wcIn(u)
        if (wc != null) onWc(wc) else if (isWeb(u)) ev(JSONObject().put("t", "new").put("url", u))
        ui.post { popClose(p) }
        return false
    }
    private fun popTitle(p: Pop, u: String?) { try { val h = Uri.parse(u ?: "").host ?: ""; p.head.text = "\u2715   " + (if (iiHost(u)) "Internet Identity \u00b7 " else "Sign-in \u00b7 ") + h } catch (_: Throwable) {} }
    private fun popPlace(p: Pop) {
        if (!p.shown) return
        val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply { topMargin = top; bottomMargin = bottom }
        if (p.box.parent == null) root.addView(p.box, lp) else p.box.layoutParams = lp
        if (visible == p.parent) { p.box.visibility = View.VISIBLE; p.box.bringToFront(); try { p.w.onResume() } catch (_: Throwable) {} }
        else p.box.visibility = View.GONE
    }
    private fun popClose(p: Pop) {
        if (pops[p.t.id] !== p) return
        pops.remove(p.t.id)
        try { root.removeView(p.box) } catch (_: Throwable) {}
        try { p.box.removeAllViews(); p.w.stopLoading(); p.w.destroy() } catch (_: Throwable) {}
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
            /* v1.8.32: a wallet link the page opens to wake its wallet (bifrostwallet://, joey://, girinwallet://… with no
               pairing URI) resolves to Hwallet itself; starting it would mark Hwallet as woken by a dApp, and the next
               WalletConnect answer would send Hwallet to the background (xrp.cafe closed Hwallet on Connect). Hwallet is
               already the wallet on screen, so such a link is dropped. */
            val own = try { i.resolveActivity(act.packageManager)?.packageName == act.packageName } catch (_: Throwable) { false }
            if (own) return
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
    /** v1.9.19: VPN on while this WebView cannot use the local proxy (Android System WebView too old) or the tunnel has
     *  no port yet: the page gets a short notice instead of loading direct (kill switch). Proxied WebViews need nothing
     *  here: ProxyController keeps them on the local port, so loads fail while the tunnel is down. */
    private fun vpnBlock(req: WebResourceRequest): WebResourceResponse? {
        if (!HwVpn.webBlocked()) return null
        val s = req.url.scheme?.lowercase()
        if (s != "https" && s != "http") return null
        val msg = if (!HwVpn.webSupported()) "Your Android WebView is too old for the VPN, update Android System WebView" else "VPN reconnecting, traffic paused"
        val html = if (req.isForMainFrame) "<!doctype html><meta name=viewport content='width=device-width'><body style='font:16px system-ui;padding:32px 24px;color:#555'><b>Hwallet VPN</b><p>" + msg + "</p>" else ""
        return WebResourceResponse("text/html", "utf-8", 503, "VPN", mapOf("Cache-Control" to "no-store"), java.io.ByteArrayInputStream(html.toByteArray()))
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
        /** dark pages: "on" | "off" | "auto:dark" | "auto:light" (Auto follows Hwallet's theme); set by the page (HwNative.brDark) */
        @Volatile var darkPref: String = "auto:light"
        private const val DARK_PREFS = "hwbr_dark"
        private const val DARK_JS = "(function(){try{return matchMedia('(prefers-color-scheme: dark)').matches}catch(e){return null}})()"
        fun darkNorm(v: String): String = if (v == "on" || v == "off" || v == "auto:dark" || v == "auto:light") v else "auto:light"
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
