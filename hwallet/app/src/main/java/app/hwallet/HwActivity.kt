package app.hwallet

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.graphics.Color
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import org.json.JSONObject
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.webkit.WebViewAssetLoader
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Hwallet shell: one WebView that serves the watch-only app from the APK's assets
 * (https://appassets.androidplatform.net, a secure context). Balances come from public
 * nodes through HwNative.http; signing is always done by Kwallet (offline) via SignActivity.
 */
class HwActivity : FragmentActivity() {
    private var LIME = 0xFFDDF869.toInt()

    private lateinit var web: WebView

    private fun startUrl(): String = "https://appassets.androidplatform.net/assets/index.html"

    /** v1.6 (#57): a WalletConnect pairing link that opened the app (wc:…, hwallet://wc?uri=…, https://hwallet.app/wc?uri=…).
     *  Kept until the page takes it (wcPending) or pushed to window.hwWcLink when the page is already up. */
    @Volatile private var pendingWc: String = ""
    private fun wcFrom(i: Intent?): String {
        val u = i?.data ?: return ""
        if (i?.action != Intent.ACTION_VIEW) return ""
        val s = when (u.scheme?.lowercase()) {
            "wc" -> u.toString()
            "hwallet", "https", "bifrostwallet", "girinwallet", "joey", "metamask", "trust", "okex", "bitkeep", "tpoutside", "rainbow", "safepalwallet", "zerion", "uniswap" ->
                try { u.getQueryParameter("uri") ?: "" } catch (_: Throwable) { "" }
            else -> ""
        }
        return if (s.startsWith("wc:") && s.length < 4096) s else ""
    }
    /** wave B (#97b): Hwallet was opened by a dApp's WalletConnect link (pairing or a request ping). After the answer the
     *  page calls wcReturn() so the user lands back in the dApp. */
    @Volatile private var wcWokeAt = 0L
    private fun wcWake(i: Intent?) {
        val u = i?.data ?: return
        if (i.action != Intent.ACTION_VIEW) return
        val sc = u.scheme?.lowercase() ?: return
        val isWc = sc == "wc" || (sc == "hwallet" && u.host == "wc") || sc in WC_ALIAS ||
            (sc == "https" && (u.host == "hwallet.app" || u.host == "app.bifrostwallet.com") && (u.path ?: "").startsWith("/wc"))
        if (!isWc) return
        wcWokeAt = System.currentTimeMillis()
        if (pageReady && ::web.isInitialized) web.evaluateJavascript("window.hwWcWoke&&hwWcWoke()", null)
    }
    private fun takeWc(i: Intent?) {
        wcWake(i)
        val s = wcFrom(i); if (s.isEmpty()) return
        pendingWc = s
        if (pageReady && ::web.isInitialized) web.evaluateJavascript("window.hwWcLink&&hwWcLink(${JSONObject.quote(s)})") { r -> if (r == "true") pendingWc = "" }
    }
    /** v1.6 (#59): a wc: link tapped inside the Browser tab pairs exactly like a scanned or opened one */
    private fun wcLink(s: String) {
        if (!s.startsWith("wc:") || s.length >= 4096) return
        pendingWc = s
        runOnUiThread { if (pageReady && ::web.isInitialized) web.evaluateJavascript("window.hwWcLink&&hwWcLink(${JSONObject.quote(s)})") { r -> if (r == "true") pendingWc = "" } }
    }

    /** v1.6 (#59/#83): the Browser tab's native pages, created on first use */
    private var br: HwBrowser? = null
    private fun brw(): HwBrowser = br ?: HwBrowser(this, root, web,
        js = { s -> runOnUiThread { try { if (::web.isInitialized) web.evaluateJavascript(s, null) } catch (_: Throwable) {} } },
        onWc = { s -> wcLink(s) }).also { br = it }

    companion object {
        const val KWALLET = "app.kwallet"; const val EXTRA_MAX = 120000
        /* v1.8.16 live link transaction codes (same numbers in Kwallet's SignActivity) */
        const val LINK_STATUS = android.os.IBinder.FIRST_CALL_TRANSACTION + 1
        const val LINK_CANCEL = android.os.IBinder.FIRST_CALL_TRANSACTION + 2
    }
    private lateinit var root: FrameLayout
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    @Volatile var insetCss = "{\"t\":0,\"r\":0,\"b\":0,\"l\":0}"

    private fun pushInsets() {
        if (::web.isInitialized) web.evaluateJavascript("window.kwInsets&&kwInsets($insetCss)", null)
    }

    /** Kwallet's sign page: one request at a time, answered only to this app. */
    private val kwSign = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        signing = false; kwSeq++; kwUnlink()
        val d = res.data
        val rid = kwRid
        /* v1.8.8 (push 13, R11a): a large answer (signed Monero set) is in the answer file; the page reads it from /kwres/<id> */
        val json = if (d?.getBooleanExtra("resf", false) == true && rid.isNotEmpty() && KwxProvider.file(this, "res", rid).length() > 0L)
            "{\"status\":\"file\",\"f\":${JSONObject.quote(rid)}}"
        else d?.getStringExtra("res") ?: (if (d == null) "{\"status\":\"error\",\"error\":\"Kwallet closed before answering. Nothing was signed\",\"gone\":true}" else "{\"status\":\"rejected\"}")
        if (rid.isNotEmpty()) { try { KwxProvider.file(this, "req", rid).delete() } catch (_: Throwable) {} }
        web.evaluateJavascript("window.hwSignDone&&hwSignDone(${JSONObject.quote(json)})", null)
        ui.postDelayed({ if (fgNow && !signing) warmKwallet() }, 1500L)   // K-H link: the next sheet is ready again
    }
    /* v1.8.8 (R9a / R11): the open request's id (handoff file names), a sequence for the open watchdog, and whether Hwallet
       was paused since the launch (it is, as soon as Kwallet's sheet comes up over it) */
    @Volatile private var kwRid = ""
    @Volatile private var kwSeq = 0
    @Volatile private var kwPaused = false
    /** R9c: the token the Monero worker uses for its direct node path (/xmrq/<token>); new for every app start */
    private val xmrToken: String = java.util.UUID.randomUUID().toString().replace("-", "") + java.util.UUID.randomUUID().toString().replace("-", "").take(8)
    /* ---- v1.8.16: live K <-> H link (no timeouts; see Kwallet's SignActivity) ----
       A fresh Binder rides in every launch Intent. Kwallet pushes each state to it (received, sheet, stage, signing, signed /
       rejected / error) and hands over its own Binder once: Hwallet uses that to cancel the open sheet and links to its death,
       so a Kwallet that dies is reported at once instead of waited on. Calls are accepted only from the Kwallet package. */
    @Volatile private var kwPeer: android.os.IBinder? = null
    @Volatile private var kwLinkSeq = 0
    private val kwDeath = android.os.IBinder.DeathRecipient {
        val seq = kwLinkSeq
        kwPeer = null
        runOnUiThread { if (signing && seq == kwSeq) { kwJs("{\"st\":\"gone\"}"); signFail("Kwallet stopped before answering. Nothing was signed") } }
    }
    private fun kwUidOk(uid: Int): Boolean = try {
        (packageManager.getPackagesForUid(uid) ?: emptyArray()).any { it == KWALLET } && packageManager.checkSignatures(packageName, KWALLET) == android.content.pm.PackageManager.SIGNATURE_MATCH
    } catch (_: Exception) { false }
    private fun kwJs(json: String) { try { if (::web.isInitialized) web.evaluateJavascript("window.hwKStatus&&hwKStatus(${JSONObject.quote(json)})", null) } catch (_: Throwable) {} }
    private inner class KwLink(val seq: Int) : android.os.Binder() {
        override fun onTransact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean {
            if (code != LINK_STATUS) return super.onTransact(code, data, reply, flags)
            if (!kwUidOk(android.os.Binder.getCallingUid())) return false
            val id = data.readString() ?: ""; val st = data.readString() ?: ""; val det = data.readString() ?: ""
            val peer = try { data.readStrongBinder() } catch (_: Exception) { null }
            runOnUiThread {
                if (seq != kwSeq) return@runOnUiThread
                if (peer != null && kwPeer == null) { try { peer.linkToDeath(kwDeath, 0); kwPeer = peer } catch (_: Exception) { kwDeath.binderDied() } }
                kwJs(JSONObject().put("id", id).put("st", st).put("d", det).toString())
            }
            return true
        }
    }
    private fun kwUnlink() { val p = kwPeer; kwPeer = null; try { p?.unlinkToDeath(kwDeath, 0) } catch (_: Exception) {} }
    /** Cancel from Hwallet: closes Kwallet's open sheet (Kwallet answers "rejected" through the normal result) */
    private fun kwCancel(id: String) {
        val p = kwPeer
        if (p == null) { if (signing) signFail("Cancelled. Nothing was signed"); return }
        val d = android.os.Parcel.obtain()
        try { d.writeString(id); p.transact(LINK_CANCEL, d, null, android.os.IBinder.FLAG_ONEWAY) }
        catch (_: Exception) { if (signing) signFail("Cancelled. Nothing was signed") } finally { d.recycle() }
    }
    private fun signFail(why: String) {
        signing = false; kwSeq++; kwUnlink()
        web.evaluateJavascript("window.hwSignDone&&hwSignDone(${JSONObject.quote(JSONObject().put("status", "error").put("error", why).toString())})", null)
    }
    /** v1.8.8 (R11f): Kwallet's version code (0 = not installed) */
    private fun kwCode(): Long = try { androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(packageManager.getPackageInfo(KWALLET, 0)) } catch (_: Exception) { 0L }
    private fun launchSign(json: String, rid: String, file: Boolean) {
        val code = kwCode()
        if (code <= 0L) { web.evaluateJavascript("window.hwSignDone&&hwSignDone(${JSONObject.quote("{\"status\":\"missing\"}")})", null); return }
        val big = json.length > EXTRA_MAX
        /* a Kwallet before 3.98.8 only reads the request from the Intent extra: a large one cannot reach it */
        if (big && code < 39808L) { signFail("This transaction is too large for Kwallet " + (try { packageManager.getPackageInfo(KWALLET, 0).versionName } catch (_: Exception) { "" }) + ". Update Kwallet to 3.98.8 or later, then try again"); return }
        if (big && !file) { signFail("Hwallet could not hand this request to Kwallet (storage full?). Free some space and try again"); return }
        val i = Intent().setClassName(KWALLET, "$KWALLET.SignActivity").putExtra("t0", android.os.SystemClock.uptimeMillis()).putExtra("rid", rid)
        if (!big) i.putExtra("req", json)
        if (file) {
            val rq = KwxProvider.uri("req", rid); val rs = KwxProvider.uri("res", rid)
            i.putExtra("reqUri", rq.toString()).putExtra("resUri", rs.toString())
            i.clipData = android.content.ClipData.newRawUri("kwx", rq).apply { addItem(android.content.ClipData.Item(rs)) }
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        kwRid = rid
        /* P2a (#97a): no window animation (Kwallet's page slides its own sheet up over this screen, which stays as it is) */
        try {
            kwUnlink()
            signing = true; kwPaused = false; val seq = ++kwSeq; kwLinkSeq = seq
            i.putExtras(Bundle().apply { putBinder("kwlink", KwLink(seq)) })
            kwSign.launch(i, androidx.core.app.ActivityOptionsCompat.makeCustomAnimation(this@HwActivity, 0, 0))
            /* v1.8.16: no limit. If Kwallet has not said "received" after 12 s, the waiting card says so (with Cancel); the
               request keeps waiting until Kwallet answers, dies, or the user cancels */
            ui.postDelayed({ if (signing && seq == kwSeq && kwPeer == null) kwJs("{\"st\":\"slow\"}") }, 12000L)
        } catch (e: Exception) {
            signing = false
            signFail("Kwallet could not be opened (" + (e.javaClass.simpleName) + "). Try again")
        }
    }
    /* v1.3 (#46): enough threads for every chain's history and balances at once (was 6, which queued the Activity tab).
       v1.9.20: 48 threads (24 were nearly all busy behind slow RPCs), idle ones end after 30 s, and the page's HTTP calls are
       capped per host (netHost), so one slow chain can never take every thread from the others. */
    private val net: java.util.concurrent.ThreadPoolExecutor = java.util.concurrent.ThreadPoolExecutor(48, 48, 30L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue<Runnable>(), object : java.util.concurrent.ThreadFactory {
            private val n = java.util.concurrent.atomic.AtomicInteger()
            override fun newThread(r: Runnable) = Thread(r, "hw-net-" + n.incrementAndGet())
        }).apply { allowCoreThreadTimeOut(true) }
    /** v1.9.20: at most HOST_MAX calls to one host in flight; the rest wait in that host's queue without holding a thread */
    private val HOST_MAX = 12   // v1.9.21: was 4, which starved one-host chains
    private val hostQ = HashMap<String, ArrayDeque<Runnable>>()
    private val hostRun = HashMap<String, Int>()
    private fun netHost(host: String, r: Runnable) {
        if (host.isEmpty() || hostFree(host)) { net.execute(r); return }
        synchronized(hostQ) {
            val n = hostRun[host] ?: 0
            if (n >= HOST_MAX) { hostQ.getOrPut(host) { ArrayDeque() }.addLast(r); return }
            hostRun[host] = n + 1
        }
        netHostRun(host, r)
    }
    /** v1.9.21: the Internet Computer is one host for every balance, II lookup and identity_info call, so it is never capped */
    private fun hostFree(h: String): Boolean = h == "icp-api.io" || h == "icp0.io" || h == "ic0.app" || h.endsWith(".icp0.io") || h.endsWith(".ic0.app") || h.endsWith(".icp-api.io")
    private fun netHostRun(host: String, r: Runnable) {
        try { net.execute { try { r.run() } finally { netHostDone(host) } } }
        catch (_: java.util.concurrent.RejectedExecutionException) { synchronized(hostQ) { hostQ.remove(host); hostRun.remove(host) } }
    }
    private fun netHostDone(host: String) {
        val next: Runnable? = synchronized(hostQ) {
            val q = hostQ[host]
            val nx = q?.removeFirstOrNull()
            if (q != null && q.isEmpty()) hostQ.remove(host)
            if (nx == null) { val n = (hostRun[host] ?: 1) - 1; if (n <= 0) hostRun.remove(host) else hostRun[host] = n }
            nx
        }
        if (next != null) netHostRun(host, next)
    }
    /** v1.9.19-dbg1: the Kwallet handoff has its own thread, so it can never queue behind network calls on `net` */
    private val kwEx = java.util.concurrent.ThreadPoolExecutor(1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS, java.util.concurrent.LinkedBlockingQueue<Runnable>(),
        java.util.concurrent.ThreadFactory { r -> Thread(r, "hw-kw").apply { isDaemon = true } })
    /** v1.1: true while Kwallet's sign page is open over Hwallet; the page keeps running to stream live data to it. */
    @Volatile private var signing = false
    private val socks = java.util.concurrent.ConcurrentHashMap<String, okhttp3.WebSocket>()
    /** v1.9.19: VPN state to the page; on <-> off also restarts the page's native WebSockets (WalletConnect relay) on the new path */
    @Volatile private var vpnWasOn = HwVpn.on
    private val vpnL: () -> Unit = {
        val now = HwVpn.on
        if (now != vpnWasOn) { vpnWasOn = now; try { socks.values.forEach { it.cancel() } } catch (_: Throwable) {} }
        val j = HwVpn.stateJson()
        runOnUiThread { try { if (pageReady && ::web.isInitialized) web.evaluateJavascript("window.hwVpnState&&hwVpnState(${JSONObject.quote(j)})", null) } catch (_: Throwable) {} }
    }
    private fun wsJs(id: String, t: String, d: String) { runOnUiThread { if (::web.isInitialized) web.evaluateJavascript("window.hwWs&&hwWs(${JSONObject.quote(id)},${JSONObject.quote(t)},${JSONObject.quote(d)})", null) } }

    inner class HwBridge {
        /** Opens Kwallet's sign page with one request (sign or pair). The result comes back through window.hwSignDone. */
        @android.webkit.JavascriptInterface
        fun sign(json: String) {
            /* v1.8.8 (push 13, R9a / R11a): every request is also written to a handoff file (off the UI thread) and passed as a
             * content:// URI with a one-shot grant, so its size no longer matters; small ones still ride in the extra too
             * (an older Kwallet reads only that). */
            kwEx.execute {
                var rid = ""
                try { rid = JSONObject(json).optString("id", "") } catch (_: Exception) {}
                rid = rid.replace(Regex("[^A-Za-z0-9_-]"), "").take(48)
                if (rid.length < 4) rid = java.util.UUID.randomUUID().toString().replace("-", "").take(24)
                var file = false
                try { KwxProvider.clean(this@HwActivity); KwxProvider.file(this@HwActivity, "res", rid).delete(); KwxProvider.file(this@HwActivity, "req", rid).writeText(json); file = true } catch (_: Throwable) {}
                val r = rid; val f = file
                runOnUiThread { launchSign(json, r, f) }
            }
        }

        /** v1.8.16: Cancel on Hwallet's "Waiting for Kwallet" card closes Kwallet's sheet (nothing is signed) */
        @android.webkit.JavascriptInterface
        fun kwCancel(id: String) { runOnUiThread { this@HwActivity.kwCancel(id.take(64)) } }

        /* v1.9.20: the 1.9.19-dbg freeze catcher is gone; these stay as no-ops so a page that still calls them never throws */
        @android.webkit.JavascriptInterface
        fun pong(seq: Int) {}
        @android.webkit.JavascriptInterface
        fun jsLog(s: String) {}
        @android.webkit.JavascriptInterface
        fun stallReport() {}
        @android.webkit.JavascriptInterface
        fun dbgMethods(): String = "[]"

        /** v1.8.12 (push 17, R1): the native Monero engine (monero_c wallet2). xmrNOk: the library loaded on this phone.
         *  xmrN: one engine op, answered later through window.hwXmrN(id, ok, json). xmrNStatus: cached heights, never blocks. */
        @android.webkit.JavascriptInterface
        fun xmrNOk(): Boolean = try { HwXmrN.available(this@HwActivity) } catch (_: Throwable) { false }

        @android.webkit.JavascriptInterface
        fun xmrNStatus(): String = HwXmrN.status()

        /** v1.8.13: Settings > Chains: Monero off closes the native wallet and keeps it (and its service) stopped */
        @android.webkit.JavascriptInterface
        fun xmrNOff(off: Boolean) { try { HwXmrN.setOff(this@HwActivity, off); if (off) stopService(android.content.Intent(this@HwActivity, HwXmrService::class.java)) } catch (_: Throwable) {} }

        @android.webkit.JavascriptInterface
        fun xmrN(op: String, args: String, cb: String) {
            val id = cb.replace(Regex("[^A-Za-z0-9_-]"), "").take(32)
            HwXmrN.call(this@HwActivity, op.take(16), args) { ok, json ->
                runOnUiThread { try { if (::web.isInitialized) web.evaluateJavascript("window.hwXmrN&&hwXmrN(" + JSONObject.quote(id) + "," + ok + "," + JSONObject.quote(json) + ")", null) } catch (_: Throwable) {} }
            }
        }

        /** v1.8.8 (R9c): the Monero worker's direct node path token (this app run only) */
        @android.webkit.JavascriptInterface
        fun xmrTok(): String = xmrToken

        /** wave B (#97b): after a WalletConnect answer: back to the dApp when Hwallet was opened by its link (within 3 min).
         *  redirect = the dApp's metadata.redirect.native (a deep link), else the previous app comes back (task to back). */
        @android.webkit.JavascriptInterface
        fun wcReturn(redirect: String): Boolean {
            if (wcWokeAt == 0L || System.currentTimeMillis() - wcWokeAt > 180000L) return false
            wcWokeAt = 0L
            runOnUiThread {
                var ok = false
                val r = redirect.trim()
                if (r.isNotEmpty() && r.length < 512 && !r.startsWith("javascript:", true) && !r.startsWith("intent:", true) && !r.startsWith("file:", true) && !r.startsWith("content:", true) && !r.startsWith("hwallet:", true)) {
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); ok = true } catch (_: Exception) {}
                }
                if (!ok) moveTaskToBack(true)
            }
            return true
        }
        @android.webkit.JavascriptInterface
        fun wcWoke(): Boolean = wcWokeAt > 0L && System.currentTimeMillis() - wcWokeAt < 180000L

        /** v1.7 (#92): WalletConnect while Hwallet is in the background. The relay socket is native (wsOpen) and the live
         *  foreground service keeps the process, so requests still arrive. Android does not let a background app open
         *  Kwallet, so the page asks for a heads-up notification; tapping it brings Hwallet forward and the page pops the
         *  Kwallet sheet at once (window.hwFront). */
        @android.webkit.JavascriptInterface
        fun isFg(): Boolean = fgNow
        @android.webkit.JavascriptInterface
        fun wcNotify(kind: String, title: String, body: String) { runOnUiThread { wcPost(kind.take(16), title.take(80), body.take(160)) } }
        @android.webkit.JavascriptInterface
        fun wcClear() { runOnUiThread { try { (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager).cancel(WC_NOTE) } catch (_: Throwable) {} } }
        /** v1.7.2 (S17): transaction status (pending / confirmed / failed + plain reason) as a quiet notification, only while
         *  Hwallet is not on screen (on screen the page shows its own toast). One notification per transaction id. */
        @android.webkit.JavascriptInterface
        fun txNotify(id: String, title: String, body: String, state: String) { if (!fgNow || state == "pending") runOnUiThread { txPost(id.take(80), title.take(80), body.take(200), state.take(12)) } }
        @android.webkit.JavascriptInterface
        fun wcBg(on: Boolean) { wcKeep = on; if (on) runOnUiThread { try { startLiveSafe() } catch (_: Throwable) {} } }

        /** P2a (#97a): wake Kwallet's process + WebView engine before a signature is likely (dApp connect / request). */
        @android.webkit.JavascriptInterface
        fun warmK() { warmKwallet() }

        /** v1.9.23: a headless Kwallet read (only icp_iiNames: read-only identity_info signatures) over the bound K-H link. No
         *  Kwallet window opens. Result via window.hwKQuiet(id, json): Kwallet's answer, or {"status":"nolink"} when the link is
         *  down or Kwallet is too old (the page then shows identity numbers; it never falls back to a sheet for this). */
        @android.webkit.JavascriptInterface
        fun kQuiet(id: String, json: String) { kwQuiet(id.take(64), json) }
        @android.webkit.JavascriptInterface
        fun kQuietOk(): Boolean = kwSvc != null && kwSvcProto >= 2

        /** HTTPS JSON requests to public nodes (no CORS limits here). Result via window.hwHttpDone(id, status, body).
         *  v1.4 (#53): through Hwallet's one network stack (HwNet), so the data speed limit applies. */
        @android.webkit.JavascriptInterface
        fun http(id: String, method: String, url: String, body: String, timeoutMs: Int) { http2(id, method, url, body, timeoutMs, "application/json") }

        /** v1.1: like http() with a content type; "type;hex" sends the hex body as raw bytes (Cardano CBOR). */
        @android.webkit.JavascriptInterface
        fun http2(id: String, method: String, url: String, body: String, timeoutMs: Int, contentType: String) { http3(id, method, url, body, timeoutMs, contentType, "") }

        /** v1.6: like http2() with extra headers (JSON object). URL, headers and body may carry {{K:name}} placeholders:
         *  the provider key is added here, natively, only for that provider's hosts (HwKeys). The page never sees it.
         *  A keyed request answered 401/403/429 rests that key and returns status -429 so the page goes keyless. */
        @android.webkit.JavascriptInterface
        fun http3(id: String, method: String, url0: String, body0: String, timeoutMs: Int, contentType: String, headers: String) {
            val hk = try { Uri.parse(url0).host?.lowercase() ?: "" } catch (_: Throwable) { "" }
            netHost(hk) {
                var st = 0; var out = ""
                val used = HashSet<String>()
                try {
                    if (!url0.startsWith("https://")) throw IllegalArgumentException("https only")
                    val host = android.net.Uri.parse(url0).host ?: ""
                    val url = HwKeys.fill(this@HwActivity, host, url0, used) ?: throw IllegalStateException("nokey")
                    val body = HwKeys.fill(this@HwActivity, host, body0, used) ?: throw IllegalStateException("nokey")
                    val rb = okhttp3.Request.Builder().url(url)
                        .header("Accept", "application/json, text/plain, */*")
                        .header("User-Agent", "Hwallet/" + BuildConfig.VERSION_NAME)
                    var b64 = false   /* v1.9.0: "X-Hw-B64: 1" = return the raw reply bytes as a data: URL (images, Internet Computer CBOR replies) */
                    if (headers.isNotEmpty()) {
                        val h = JSONObject(headers); val it = h.keys()
                        while (it.hasNext()) { val k = it.next(); if (k == "X-Hw-B64") { b64 = true; continue }; val v = HwKeys.fill(this@HwActivity, host, h.optString(k), used) ?: throw IllegalStateException("nokey"); if (k.length < 64) rb.header(k, v) }
                    }
                    if (method == "POST" || method == "PUT") {
                        val hex = contentType.endsWith(";hex")
                        val ct = if (hex) contentType.removeSuffix(";hex") else contentType.ifEmpty { "application/json" }
                        val bytes = if (hex) ByteArray(body.length / 2) { k -> body.substring(k * 2, k * 2 + 2).toInt(16).toByte() } else body.toByteArray(Charsets.UTF_8)
                        rb.method(method, bytes.toRequestBody(ct.toMediaTypeOrNull()))
                    }
                    /* at a low speed cap a big page needs longer than the page's timeout: let it finish */
                    val t = if (HwNet.rate.on()) 30000 else timeoutMs.coerceIn(1000, 30000)
                    HwNet.withTimeout(t).newCall(rb.build()).execute().use { r ->
                        st = r.code
                        out = if (b64) { val by = r.body?.bytes() ?: ByteArray(0); if (by.size <= 3_000_000) "data:" + (r.header("Content-Type") ?: "application/octet-stream").substringBefore(';').trim() + ";base64," + android.util.Base64.encodeToString(by, android.util.Base64.NO_WRAP) else "" }
                              else r.body?.string()?.take(4_000_000) ?: ""
                    }
                    if (used.isNotEmpty() && (st == 401 || st == 403 || st == 429)) { used.forEach { HwKeys.cool(it) }; st = -429; out = "" }
                } catch (e: IllegalStateException) { st = if (e.message == "nokey") -1 else 0; out = "" }
                catch (_: Exception) { st = 0; out = "" }
                val js = "window.hwHttpDone&&hwHttpDone(${JSONObject.quote(id)},$st,${JSONObject.quote(out)})"
                runOnUiThread { if (::web.isInitialized) web.evaluateJavascript(js, null) }
            }
        }

        /** v1.8 (Monero): the Monero engine (a worker running wallet2) talks to the node in .bin and JSON. The page registers
         *  each request here (method, https URL, base64 body), then fetches /xmrnet/<id> on the app's own origin; that fetch
         *  is answered by xmrServe() below, which streams the node's raw reply. No base64 on the way back, no CORS. */
        @android.webkit.JavascriptInterface
        fun xmrPrep(id: String, method: String, url: String, bodyB64: String, contentType: String): Boolean {
            if (!url.startsWith("https://") || id.length !in 4..48 || bodyB64.length > 4_000_000) return false
            if (xmrPend.size > 128) xmrPend.clear()
            xmrPend[id] = arrayOf(if (method == "GET") "GET" else "POST", url, bodyB64, contentType)
            return true
        }

        /** v1.8: "Received" notification for Monero, found on this phone by the view-key scan (nothing is uploaded). */
        @android.webkit.JavascriptInterface
        fun inNotify(id: String, title: String, body: String) { runOnUiThread { inPost(id.take(80), title.take(80), body.take(160)) } }

        /** v1.6: which providers have a key right now: {"alchemy":"app"|"user"|"",...}. Never the keys themselves. */
        @android.webkit.JavascriptInterface
        fun keyState(): String { val o = JSONObject(); HwKeys.NAMES.forEach { o.put(it, HwKeys.state(this@HwActivity, it)) }; return o.toString() }

        /** v1.6: Settings > API keys: the user's own key for one provider (empty clears it). */
        @android.webkit.JavascriptInterface
        fun setUserKey(name: String, v: String) { HwKeys.setUser(this@HwActivity, name, v) }

        /** v1.6 (#57): native WebSockets for the page (WalletConnect relay). The URL may carry {{K:reown}}; the page
         *  only ever sees the relay's messages. Events arrive through window.hwWs(id, type, data). */
        @android.webkit.JavascriptInterface
        fun wsOpen(id: String, url0: String) {
            net.execute {
                try {
                    val host = android.net.Uri.parse(url0).host ?: ""
                    val used = HashSet<String>()
                    val url = HwKeys.fill(this@HwActivity, host, url0, used)
                    if (url == null || !url.startsWith("wss://")) { wsJs(id, "close", "nokey"); return@execute }
                    val req = okhttp3.Request.Builder().url(url).header("User-Agent", "Hwallet/" + BuildConfig.VERSION_NAME).header("Origin", "https://hwallet.app").build()
                    val so = HwNet.withTimeout(20000).newBuilder().readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS).pingInterval(25, java.util.concurrent.TimeUnit.SECONDS).build().newWebSocket(req, object : okhttp3.WebSocketListener() {
                        override fun onOpen(w: okhttp3.WebSocket, r: okhttp3.Response) { wsJs(id, "open", "") }
                        override fun onMessage(w: okhttp3.WebSocket, t: String) { HwNet.wsIn(t.length); wsJs(id, "msg", t) }
                        override fun onClosed(w: okhttp3.WebSocket, c: Int, r: String) { socks.remove(id); wsJs(id, "close", c.toString()) }
                        override fun onFailure(w: okhttp3.WebSocket, t: Throwable, r: okhttp3.Response?) {
                            socks.remove(id)
                            val code = r?.code ?: 0
                            if (used.isNotEmpty() && (code == 401 || code == 403 || code == 429)) used.forEach { HwKeys.cool(it) }
                            wsJs(id, "close", "fail:$code")
                        }
                    })
                    socks[id] = so
                } catch (_: Throwable) { wsJs(id, "close", "error") }
            }
        }
        @android.webkit.JavascriptInterface
        fun wsSend(id: String, text: String): Boolean { val s = socks[id] ?: return false; HwNet.wsOut(text.length); return s.send(text) }
        @android.webkit.JavascriptInterface
        fun wsClose(id: String) { socks.remove(id)?.close(1000, null) }
        /** v1.6 (#57): the wc: link the app was opened with, once (empty when none). */
        @android.webkit.JavascriptInterface
        fun wcPending(): String { val s = pendingWc; pendingWc = ""; return s }

        /** v1.4 (#53): Settings > Data speed limit, KB/s (0 = unlimited). Saved and applied at once to all traffic. */
        @android.webkit.JavascriptInterface
        fun setSpeed(kb: Int) { HwNet.setKbps(this@HwActivity, kb) }

        @android.webkit.JavascriptInterface
        fun speed(): Int = HwNet.kbps(this@HwActivity)

        /** v1.5 (#67): true only while the cap is set AND the phone is on mobile data (Wi-Fi is never limited) */
        @android.webkit.JavascriptInterface
        fun capActive(): Boolean = HwNet.rate.on()

        /** v1.8.10 (push 15, R3): the default network is mobile data or metered (Wi-Fi / Ethernet never), for Adaptive mode */
        @android.webkit.JavascriptInterface
        fun netMobile(): Boolean = HwNet.mobile

        /** v1.9.19: VPN for Hwallet (HwVpn) + Secure DNS. JSON in / out; changes are pushed to window.hwVpnState(json). */
        @android.webkit.JavascriptInterface
        fun vpnState(): String = try { HwVpn.load(this@HwActivity); HwVpn.stateJson() } catch (_: Throwable) { "{}" }
        @android.webkit.JavascriptInterface
        fun vpnSet(json: String): String = try { if (json.length > 2000) "Bad request" else HwVpn.set(this@HwActivity, JSONObject(json)) } catch (e: Throwable) { "Could not change the VPN" }
        @android.webkit.JavascriptInterface
        fun vpnRegions(): String = try { HwVpn.regionsJson() } catch (_: Throwable) { "[]" }
        /** v1.9.19: the last ~200 VPN engine log lines (keys redacted) for Settings > Network & VPN > View log */
        @android.webkit.JavascriptInterface
        fun vpnLog(): String = try { HwVpn.logText() } catch (_: Throwable) { "" }

        /* v1.6 (#59/#83): Browser tab (HwBrowser.kt). Tab ids are t[0-9a-z]+; positions are CSS px of the page's #br-v box. */
        @android.webkit.JavascriptInterface
        fun brInit(script: String) { brw().init(script) }
        @android.webkit.JavascriptInterface
        fun brOpen(id: String, url: String, scroll: Int) { brw().open(id, url, scroll) }

        /** v1.8.13: the next brOpen of this tab is an incognito tab; brAfInit: the saved-inputs page script (not in incognito) */
        @android.webkit.JavascriptInterface
        fun brInc(id: String) { brw().markInc(id) }

        @android.webkit.JavascriptInterface
        fun brAfInit(script: String) { brw().afInit(script) }

        /** v1.8.13: Settings > Saved inputs: passwords are sealed with an Android Keystore AES-GCM key (never stored in clear) */
        @android.webkit.JavascriptInterface
        fun afSeal(plain: String): String = if (plain.length > 4096) "" else HwVault.seal(plain)

        /** v1.8.35 (B13): a saved password opens only within 60 s of a device unlock (afAuth: fingerprint / face / screen
         *  lock); it locks again when Hwallet goes to the background or the page calls afLock. "" while locked. */
        @android.webkit.JavascriptInterface
        fun afOpen(sealed: String): String = if (sealed.length > 20000 || !afUnlockedNow()) "" else HwVault.open(sealed)
        /** v1.8.35 (B13): "is this the saved password?" (Save vs Update prompt) without ever returning it */
        @android.webkit.JavascriptInterface
        fun afSame(sealed: String, plain: String): Boolean = sealed.length <= 20000 && plain.length <= 4096 && plain.isNotEmpty() && HwVault.open(sealed) == plain
        @android.webkit.JavascriptInterface
        fun afUnlocked(): Boolean = afUnlockedNow()
        /** asks for the phone's own unlock; the answer goes to window.hwAfAuthDone("ok" | "cancel" | "fail" | "nolock") */
        @android.webkit.JavascriptInterface
        fun afAuth(title: String) { val t = title.take(80); runOnUiThread { afDevAuth(t) } }
        @android.webkit.JavascriptInterface
        fun afLock() { afUntil = 0L }
        /** v1.8.35 (B13): the Browser's native autofill dropdown (rows are labels only; see HwAfPop) */
        @android.webkit.JavascriptInterface
        fun brAfPop(id: String, json: String) { br?.afShow(id, json) }
        @android.webkit.JavascriptInterface
        fun brAfMove(id: String, json: String) { br?.afMove(id, json) }
        @android.webkit.JavascriptInterface
        fun brAfHide() { br?.afHide() }
        @android.webkit.JavascriptInterface
        fun brShow(id: String, top: Int, bottom: Int) { brw().show(id, top, bottom) }
        /** v1.7.4 (#108): UI style for the Browser's native pull to refresh: og | m3 | miuix, optional ":dark" */
        @android.webkit.JavascriptInterface
        fun brPtrUi(v: String) { if (v.length in 2..24) { HwBrowser.ptrUiPref = v; br?.ptrUi(v) } }
        /** Browser ⋮ > Dark pages: "on" | "off" | "auto:dark" | "auto:light" (Auto = Hwallet's theme), saved natively, applied to every tab */
        @android.webkit.JavascriptInterface
        fun brDark(v: String) { if (v.length in 2..10) brw().dark(v) }
        @android.webkit.JavascriptInterface
        fun brHide() { br?.hide() }
        /** v1.9.38: Browser ⋮ drop-down over the live page (see HwBrowser.front) */
        @android.webkit.JavascriptInterface
        fun brFront(on: Boolean) { brw().front(on) }
        @android.webkit.JavascriptInterface
        fun brLoad(id: String, url: String) { brw().load(id, url) }
        @android.webkit.JavascriptInterface
        fun brNav(id: String, action: String) { br?.nav(id, action) }
        @android.webkit.JavascriptInterface
        fun brSnap(id: String) { br?.snap(id) }
        @android.webkit.JavascriptInterface
        fun brThumb(id: String): String = brw().thumbData(id)
        @android.webkit.JavascriptInterface
        fun brClose(id: String) { brw().close(id) }
        @android.webkit.JavascriptInterface
        fun brClearAll() { brw().clearAll() }
        /** v1.8.9 (R16): Browser settings > Clear cached pages (HTTP caches + site icons; tabs, cookies and history stay) */
        @android.webkit.JavascriptInterface
        fun brClearCache() { brw().clearCache() }
        @android.webkit.JavascriptInterface
        fun brUsage(): String = try { brw().usage() } catch (_: Throwable) { "{}" }
        /** v1.7 (P1): {documentStartScript, webMessageListener, webview version} of this phone's WebView */
        @android.webkit.JavascriptInterface
        fun brFeatures(): String = try { brw().features() } catch (_: Throwable) { "{}" }
        /** v1.7 (P1, #99): a site's cached icon (data: PNG, 96 px) by host, "" when none is on disk yet */
        @android.webkit.JavascriptInterface
        fun brIcon(host: String): String = if (host.length in 1..200) try { brw().iconData(host) } catch (_: Throwable) { "" } else ""
        @android.webkit.JavascriptInterface
        fun brShield(): String = try { HwShield.init(this@HwActivity); HwShield.state() } catch (_: Throwable) { "{}" }
        @android.webkit.JavascriptInterface
        fun brShieldSet(on: Boolean) { HwShield.init(this@HwActivity); HwShield.setOn(on); br?.reloadShown() }
        @android.webkit.JavascriptInterface
        fun brShieldSite(host: String, on: Boolean) { if (host.length in 1..200) { HwShield.init(this@HwActivity); HwShield.setSite(host.lowercase(), on); br?.reloadShown() } }
        @android.webkit.JavascriptInterface
        fun brShieldList(k: String, on: Boolean) { HwShield.init(this@HwActivity); if (HwShield.LISTS.containsKey(k)) HwShield.setList(k, on) }
        @android.webkit.JavascriptInterface
        fun brShieldUpdate() { HwShield.init(this@HwActivity); HwShield.update() }
        /* v1.9.14: deceptive-site warning (HwScam): state, on/off, check a host, continue to a host this session, update now */
        @android.webkit.JavascriptInterface
        fun brScam(): String = try { HwScam.init(this@HwActivity); HwScam.state() } catch (_: Throwable) { "{}" }
        @android.webkit.JavascriptInterface
        fun brScamSet(on: Boolean) { try { HwScam.init(this@HwActivity); HwScam.setOn(on) } catch (_: Throwable) {} }
        @android.webkit.JavascriptInterface
        fun brScamCheck(host: String): String = if (host.length in 1..253) try { HwScam.init(this@HwActivity); if (HwScam.isAllowed(host)) "" else HwScam.check(host) ?: "" } catch (_: Throwable) { "" } else ""
        @android.webkit.JavascriptInterface
        fun brScamAllow(host: String) { if (host.length in 1..253) try { HwScam.init(this@HwActivity); HwScam.allow(host) } catch (_: Throwable) {} }
        @android.webkit.JavascriptInterface
        fun brScamUpdate() { try { HwScam.init(this@HwActivity); HwScam.update() } catch (_: Throwable) {} }
        @android.webkit.JavascriptInterface
        fun brUa(): String = try { HwUa.init(this@HwActivity); HwUa.state() } catch (_: Throwable) { "{}" }
        @android.webkit.JavascriptInterface
        fun brUaSet(on: Boolean, id: String, custom: String) { HwUa.init(this@HwActivity); HwUa.set(on, id.take(24), custom); HwUa.setDeskDef(HwUa.chosenIsDesktop()); br?.applyUa() }   // v1.8.9 (R18): a desktop user agent = Desktop view on
        @android.webkit.JavascriptInterface
        fun brReply(id: String, json: String) { if (json.length < 2_000_000) br?.reply(id, json) }
        /** v1.8.8 (R4): run one of Hwallet's own scripts in a tab's page (translation: read text, write translations, restore,
         *  detect the language); the result comes back through window.hwBrEvalDone(id, seq, result) */
        @android.webkit.JavascriptInterface
        fun brEval(id: String, seq: Int, js: String) { if (js.length < 2_000_000) br?.evalIn(id, seq, js) }
        /** v1.8.8 (R4b): desktop view for one tab (the Windows Chrome preset, reload) */
        @android.webkit.JavascriptInterface
        fun brDesk(id: String, on: Boolean) { br?.desk(id, on) }
        /** v1.8.8 (R4a): bookmarks, kept natively (title + url list, newest first) */
        @android.webkit.JavascriptInterface
        fun brBm(): String = getSharedPreferences("hwbm", MODE_PRIVATE).getString("list", "[]") ?: "[]"
        @android.webkit.JavascriptInterface
        fun brBmSet(json: String) { if (json.length < 400_000) try { org.json.JSONArray(json); getSharedPreferences("hwbm", MODE_PRIVATE).edit().putString("list", json).apply() } catch (_: Exception) {} }

        @android.webkit.JavascriptInterface
        fun brEmit(origin: String, json: String) { if (json.length < 100_000) br?.emit(origin, json) }

        /** v1.4 (#55): the persistent activity cache. Rows for the Activity tab, newest first, read from disk (no network). */
        @android.webkit.JavascriptInterface
        fun actRows(wid: String, limit: Int): String = try { HwActDb.get(this@HwActivity).rows(wid, limit) } catch (_: Throwable) { "[]" }

        /** rows the page fetched itself (pull to refresh, older pages) or a send it just broadcast (pending) */
        @android.webkit.JavascriptInterface
        fun actPut(wid: String, json: String) {
            if (json.length > 3_000_000) return
            net.execute {
                try {
                    val a = org.json.JSONArray(json); val l = ArrayList<JSONObject>()
                    for (i in 0 until a.length()) a.optJSONObject(i)?.let { l.add(it) }
                    HwActDb.get(this@HwActivity).put(wid, l, false)
                    /* a pending send: the service checks that chain every few seconds until it confirms */
                } catch (_: Throwable) {}
            }
        }

        /** v1.9.19: Activity timeline events (dApp connects / sign-ins / signed messages / approvals), recorded by the page when
         *  it handles them. Labels only (HwActDb.evPut keeps a fixed set of short fields). */
        @android.webkit.JavascriptInterface
        fun evPut(wid: String, json: String) {
            if (json.length > 400_000) return
            net.execute {
                try {
                    val a = org.json.JSONArray(json); val l = ArrayList<JSONObject>()
                    for (i in 0 until minOf(a.length(), 400)) a.optJSONObject(i)?.let { l.add(it) }
                    HwActDb.get(this@HwActivity).evPut(wid.take(80), l, false)
                } catch (_: Throwable) {}
            }
        }
        /** newest first, older than before (ms, 0 = newest), limit per page; kinds = "dapp,approval,alert" (any subset) */
        @android.webkit.JavascriptInterface
        fun evRows(wid: String, kinds: String, before: String, limit: Int): String = try { HwActDb.get(this@HwActivity).evRows(wid.take(80), kinds.take(60), before.toLongOrNull() ?: 0L, limit) } catch (_: Throwable) { "[]" }

        /** v1.8.9 (R17): the page removed a local Pending entry (never broadcast or dropped): off disk and out of the shade */
        @android.webkit.JavascriptInterface
        fun actDrop(wid: String, ch: String, hash: String) { net.execute { try { HwActDb.get(this@HwActivity).dropPending(wid.take(80), ch.take(24), hash.take(160)) } catch (_: Throwable) {} } }
        @android.webkit.JavascriptInterface
        fun actPurge() { net.execute { try { HwActDb.get(this@HwActivity).purgeHashless() } catch (_: Throwable) {} } }
        @android.webkit.JavascriptInterface
        fun txCancel(id: String) { runOnUiThread { try { (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager).cancel(7800 + (id.take(80).hashCode() and 0x3ff)) } catch (_: Throwable) {} } }

        /** when the live service last read the chains for this wallet (ms since 1970), 0 = never / not running */
        @android.webkit.JavascriptInterface
        fun actFresh(wid: String): String = try { if (HwLiveService.enabled(this@HwActivity)) HwActDb.get(this@HwActivity).lastAt(wid).toString() else "0" } catch (_: Throwable) { "0" }

        /** v1.1: the clipboard text for Paste buttons (the WebView clipboard API is unreliable on phones). */
        @android.webkit.JavascriptInterface
        fun clip(): String = try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val c = cm.primaryClip
            if (c != null && c.itemCount > 0) (c.getItemAt(0).coerceToText(this@HwActivity)?.toString() ?: "").take(20000) else ""
        } catch (_: Exception) { "" }

        /** v1.5 (#66): the clipboard for Send's paste chip, read once when Send opens. Only plain text, never text the
         *  phone marks sensitive (Android 13+), and only when copied in the last 30 minutes; "" otherwise. */
        @android.webkit.JavascriptInterface
        fun clipPeek(): String = try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val d = cm.primaryClipDescription
            val sensitive = d?.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true
            val old = Build.VERSION.SDK_INT >= 26 && d != null && d.timestamp > 0 && System.currentTimeMillis() - d.timestamp > 30 * 60 * 1000L
            if (d == null || sensitive || old || !(d.hasMimeType("text/plain") || d.hasMimeType("text/*"))) ""
            else { val c = cm.primaryClip; if (c != null && c.itemCount > 0) (c.getItemAt(0).text?.toString() ?: "").trim().take(200) else "" }
        } catch (_: Exception) { "" }

        /** v1.1: live fee / balance / price data for Kwallet's open sign page. Only apps signed with the
         *  Kwallet key may receive it (signature permission on Kwallet's receiver). */
        @android.webkit.JavascriptInterface
        fun feed(id: String, json: String) {
            if (!signing || json.length > 262144) return
            /* v1.9.20: over the bound K-H link when it is up (same entry point in Kwallet, same data); else the broadcast */
            if (kwSvcFeed(id, json)) return
            try {
                sendBroadcast(Intent("app.kwallet.SIGN_FEED").setPackage(KWALLET).putExtra("id", id).putExtra("feed", json))
            } catch (_: Exception) {}
        }

        /** v1.2: settings for the always-on listener (background refresh, live notification, alerts, watched addresses).
         *  v1.3 (#42): called by the page after it has loaded. The service is never started in the same moment as a permission
         *  or battery dialog: notification permission first, the service once Hwallet is back in front, the battery prompt later. */
        @android.webkit.JavascriptInterface
        fun setLive(json: String) {
            if (json.length > 200000) return
            runOnUiThread {
                try {
                    val p = getSharedPreferences(HwLiveService.PREF, MODE_PRIVATE)
                    p.edit().putString("cfg", json).apply()
                    if (!HwLiveService.enabled(this@HwActivity)) { HwLiveService.stop(this@HwActivity); return@runOnUiThread }
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this@HwActivity, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED && !p.getBoolean("askedNotif", false)) {
                        p.edit().putBoolean("askedNotif", true).apply()
                        pendingLive = true
                        try { askNotif.launch(android.Manifest.permission.POST_NOTIFICATIONS) } catch (_: Throwable) { startLiveSafe() }
                    } else startLiveSafe()
                } catch (e: Throwable) { HwApp.write(this@HwActivity, "note: setLive failed", e.toString()) }
            }
        }

        /** v1.3 (#50): the lightest haptic the phone has (a clock tick), one pulse, for the bottom bar */
        @android.webkit.JavascriptInterface
        fun tick() { runOnUiThread { lightTick() } }

        /** v1.5 (#61): Hwallet's own scanner (CameraX + ZXing, no Play services). The page draws Gem's scanner UI over the
         *  camera preview; results via window.hwScanHit(text), camera state via window.hwScanState(s). */
        @android.webkit.JavascriptInterface
        fun scan() { scanStart() }

        @android.webkit.JavascriptInterface
        fun scanStart() { runOnUiThread { scanOpen() } }

        @android.webkit.JavascriptInterface
        fun scanStop(bg: String) { runOnUiThread { scanClose(bg) } }

        @android.webkit.JavascriptInterface
        fun scanResume() { runOnUiThread { scanner?.resume() } }

        /** #65: torch on/off; false when the camera has no flash */
        @android.webkit.JavascriptInterface
        fun scanTorch(on: Boolean): Boolean { var ok = false; val l = java.util.concurrent.CountDownLatch(1); runOnUiThread { ok = scanner?.torch(on) ?: false; l.countDown() }; try { l.await(800, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Throwable) {}; return ok }

        /** the Android photo picker; the chosen image is decoded on the phone */
        @android.webkit.JavascriptInterface
        fun scanPick() { runOnUiThread { try { pickPhoto.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } catch (_: Throwable) { scanJs("hwScanState", "pickfail") } } }

        /** v1.5 (#64): the crash recorded since the last prompt ("" when none), shown once on the next launch */
        @android.webkit.JavascriptInterface
        fun crashPending(): String = HwApp.pending(this@HwActivity)

        @android.webkit.JavascriptInterface
        fun crashAck() { HwApp.ack(this@HwActivity) }

        /** v1.3 (#42): the saved crash log for Settings > About > Crash log */
        @android.webkit.JavascriptInterface
        fun crashLog(): String = HwApp.read(this@HwActivity)

        @android.webkit.JavascriptInterface
        fun clearCrashLog() { HwApp.clear(this@HwActivity) }

        @android.webkit.JavascriptInterface
        fun batteryExempt(): String = if (batteryOk()) "1" else "0"

        @android.webkit.JavascriptInterface
        fun askBattery() { runOnUiThread { askBatteryNow() } }

        @android.webkit.JavascriptInterface
        fun maker(): String = Build.MANUFACTURER ?: ""

        @android.webkit.JavascriptInterface
        fun openAppSettings() {
            runOnUiThread { try { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (_: Exception) {} }
        }

        @android.webkit.JavascriptInterface
        fun openUrl(url: String) {
            runOnUiThread { try { if (url.startsWith("https://")) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {} }
        }

        /** v1.8.34 (Kwallet wipe link, see KwWipe.kt). kwPending: the purge Hwallet must apply before it draws any wallet
         *  ("" = none), read synchronously at page start. kwPendingDone: that purge is applied. */
        @android.webkit.JavascriptInterface
        fun kwPending(): String = KwWipe.pending(this@HwActivity)

        @android.webkit.JavascriptInterface
        fun kwPendingDone(at: String) { try { KwWipe.done(this@HwActivity, at.toLong()) } catch (_: Throwable) {} }

        /** v1.8.38 (B24): Kwallet's seed names {sid: name} as last pushed or answered ("" = none yet); Hwallet shows these */
        @android.webkit.JavascriptInterface
        fun kwNames(): String = KwWipe.names(this@HwActivity)

        /** the Kwallet-paired wallets (id, seed ID, install id, Monero addresses), so a purge can erase their native copies
         *  even while the page is not running */
        @android.webkit.JavascriptInterface
        fun kwMap(json: String) { KwWipe.setMap(this@HwActivity, json) }

        /** a live purge in the page: erase the native copies of these wallets now ({ids:[], xmr:[]}) */
        @android.webkit.JavascriptInterface
        fun kwErased(json: String) {
            if (json.length > 200_000) return
            net.execute {
                try {
                    val o = JSONObject(json); val ids = HashSet<String>(); val xmr = HashSet<String>()
                    o.optJSONArray("ids")?.let { a -> for (i in 0 until a.length()) ids.add(a.optString(i)) }
                    o.optJSONArray("xmr")?.let { a -> for (i in 0 until a.length()) xmr.add(a.optString(i)) }
                    KwWipe.eraseIds(this@HwActivity, ids, xmr, false)
                } catch (_: Throwable) {}
            }
        }

        /** ask Kwallet for {install id, seed IDs}; the answer comes back through window.hwKwState(json). Never blocks the page. */
        @android.webkit.JavascriptInterface
        fun kwCheck() {
            net.execute {
                val r = try { KwWipe.query(this@HwActivity) } catch (e: Throwable) { JSONObject().put("st", "unreachable").put("why", e.javaClass.simpleName) }
                runOnUiThread { try { if (::web.isInitialized) web.evaluateJavascript("window.hwKwState&&hwKwState(${JSONObject.quote(r.toString())})", null) } catch (_: Throwable) {} }
            }
        }

        /** v1.6 (#79): is Kwallet on this phone, and which version. Package visibility comes from <queries> in the manifest. */
        @android.webkit.JavascriptInterface
        fun kwInfo(): String = try {
            val pi = packageManager.getPackageInfo(KWALLET, 0)
            JSONObject().put("installed", true).put("version", pi.versionName ?: "").toString()
        } catch (_: Exception) { "{\"installed\":false}" }

        /** v1.6 (#79): Install Kwallet. "play" opens the Play Store listing (store app, else its web page); anything else the GitHub releases page. */
        @android.webkit.JavascriptInterface
        fun installKw(where: String) {
            runOnUiThread {
                val gh = "https://github.com/0xkabeno/Kwallet/releases/latest"
                try {
                    if (where == "play") {
                        val m = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$KWALLET")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        if (m.resolveActivity(packageManager) != null) startActivity(m)
                        else startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$KWALLET")))
                    } else startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(gh)))
                } catch (_: Exception) { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(gh))) } catch (_: Exception) {} }
            }
        }
    }

    /** v1.2: notification permission (Android 13+). v1.3: the service starts after it, once Hwallet is in front again */
    private val askNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        pendingLive = true
        startLiveSafe()
    }
    @Volatile private var pendingLive = false
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private fun resumed() = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
    /** starts the live service only while Hwallet is the resumed, visible app; otherwise waits for onResume */
    private fun startLiveSafe() {
        if (!resumed()) { pendingLive = true; return }
        pendingLive = false
        HwLiveService.start(this, true)
        /* v1.7 (P1): the system "Let app always run in background?" dialog is never shown unasked any more (it popped up over
           Home after setup). It is requested only from an explicit tap: Settings > Battery optimization (whose row explains
           why), and later from the WalletConnect background switch with an in-app explanation first (P3). */
    }

    private fun lightTick() {
        try {
            val vib: android.os.Vibrator = if (Build.VERSION.SDK_INT >= 31) (getSystemService(VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
                else @Suppress("DEPRECATION") (getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator)
            if (!vib.hasVibrator()) return
            val eff = if (Build.VERSION.SDK_INT >= 29) android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_TICK)
                else android.os.VibrationEffect.createOneShot(8, if (vib.hasAmplitudeControl()) 70 else android.os.VibrationEffect.DEFAULT_AMPLITUDE)
            if (Build.VERSION.SDK_INT >= 33) vib.vibrate(eff, android.os.VibrationAttributes.createForUsage(android.os.VibrationAttributes.USAGE_TOUCH))
            else vib.vibrate(eff)
        } catch (_: Throwable) { try { web.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) } catch (_: Throwable) {} }
    }

    /* ---------- v1.5 (#61/#65): own scanner ---------- */
    private var scanner: HwScan? = null
    private fun scanJs(f: String, v: String) { if (::web.isInitialized) web.evaluateJavascript("window.$f&&$f(${JSONObject.quote(v)})", null) }
    private val askCam = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) scanOpen()
        else scanJs("hwScanState", if (!shouldShowRequestPermissionRationale(android.Manifest.permission.CAMERA)) "denied:forever" else "denied")
    }
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) { scanJs("hwScanState", "pickcancel"); return@registerForActivityResult }
        val sc = scanner ?: HwScan(this, root) { t -> scanJs("hwScanHit", t) }.also { scanner = it }
        sc.decodeImage(uri) { t -> if (t.isEmpty()) scanJs("hwScanState", "noqr") else scanJs("hwScanHit", t) }
    }
    private fun scanOpen() {
        try {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                askCam.launch(android.Manifest.permission.CAMERA); return
            }
            val sc = scanner ?: HwScan(this, root) { t -> scanJs("hwScanHit", t) }.also { scanner = it }
            sc.state = { st -> scanJs("hwScanState", st) }
            web.setBackgroundColor(Color.TRANSPARENT)
            root.setBackgroundColor(Color.BLACK)
            sc.start()
        } catch (e: Throwable) { scanJs("hwScanState", "error:" + (e.message ?: "camera")); HwApp.write(this, "note: scanner failed", e.toString()) }
    }
    private fun scanClose(bg: String) {
        try { scanner?.stop() } catch (_: Throwable) {}
        val c = try { Color.parseColor(bg) } catch (_: Throwable) { LIME }
        try { web.setBackgroundColor(c); root.setBackgroundColor(c) } catch (_: Throwable) {}
    }

    private fun batteryOk(): Boolean = try {
        (getSystemService(POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(packageName)
    } catch (_: Exception) { false }

    @SuppressLint("BatteryLife")
    private fun askBatteryNow() {
        if (batteryOk()) return
        try { startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
        catch (_: Exception) { try { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {} }
    }

    private val pickFile = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        /* v1.8.25: one file or several (a page's <input type=file multiple>) */
        val out = ArrayList<Uri>()
        if (res.resultCode == Activity.RESULT_OK) {
            val cd = res.data?.clipData
            if (cd != null) for (k in 0 until cd.itemCount) cd.getItemAt(k)?.uri?.let { out.add(it) }
            else res.data?.data?.let { out.add(it) }
        }
        try { fileCallback?.onReceiveValue(if (out.isNotEmpty()) out.toTypedArray() else null) } catch (_: Throwable) {}
        fileCallback = null
    }
    /** v1.8.25: the system file picker for the app page and for every Browser tab (websites that upload a file) */
    fun chooseFiles(cb: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams?): Boolean {
        try { fileCallback?.onReceiveValue(null) } catch (_: Throwable) {}
        fileCallback = cb
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            val types = (params?.acceptTypes ?: emptyArray()).flatMap { it.split(",") }.map { it.trim().lowercase() }.filter { it.contains("/") }
            if (types.isNotEmpty()) putExtra(Intent.EXTRA_MIME_TYPES, (types + "application/octet-stream").distinct().toTypedArray())
            if (params?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        return try { pickFile.launch(i); true } catch (_: Exception) {
            try { pickFile.launch(Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")); true } catch (_: Exception) { fileCallback = null; false }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        E2eSkip.read(intent)
        // Edge-to-edge: the system draws its own bars (gesture pill, status icons) over the app's surface
        enableEdgeToEdge(
            // launch screen is lime: dark icons from the very first frame, never a flip on start
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        try { HwNet.load(this) } catch (_: Throwable) {}
        // Material You chosen in Settings: the window behind the page uses the wallpaper colour, never a lime flash
        val kwp = getSharedPreferences("kw", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 31 && kwp.getBoolean("md3", false)) {
            LIME = if (kwp.getBoolean("dark", false))
                androidx.core.graphics.ColorUtils.blendARGB(getColor(android.R.color.system_neutral1_900), getColor(android.R.color.system_accent1_800), .28f)
            else getColor(android.R.color.system_accent1_100)
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(LIME))
        }
        // Android 12+: the system splash fades into the app instead of cutting
        if (Build.VERSION.SDK_INT >= 31) {
            splashScreen.setOnExitAnimationListener { v ->
                v.animate().alpha(0f).setDuration(260).setInterpolator(PathInterpolator(.2f, 0f, 0f, 1f))
                    .withEndAction { v.remove() }.start()
            }
        }
        paintBars(LIME, LIME)
        // no grey/white scrim behind 3-button navigation: the page colour shows through
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }

        val assets = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this)
        web.setBackgroundColor(LIME)
        web.isHapticFeedbackEnabled = true
        // v1.7 (P1): no system focus highlight over the page after a tap (buttons draw their own pressed state)
        if (Build.VERSION.SDK_INT >= 26) web.defaultFocusHighlightEnabled = false
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            // Same layout rules as Chrome: honour the page's <meta viewport> and the
            // phone's system font size, so the app sizes exactly like the HTML in a browser.
            useWideViewPort = true
            loadWithOverviewMode = false
            textZoom = 100
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            if (Build.VERSION.SDK_INT >= 26) safeBrowsingEnabled = false
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                if (request.url.host == "appassets.androidplatform.net" && (request.url.path ?: "").startsWith("/xmrnet/")) xmrServe((request.url.path ?: "").removePrefix("/xmrnet/"))
                else if (request.url.host == "appassets.androidplatform.net" && (request.url.path ?: "").startsWith("/xmrq/")) xmrDirect(request.url)
                else if (request.url.host == "appassets.androidplatform.net" && (request.url.path ?: "").startsWith("/kwres/")) kwResServe((request.url.path ?: "").removePrefix("/kwres/"))
                else if (request.url.host == "appassets.androidplatform.net") assets.shouldInterceptRequest(request.url)?.also { if (request.url.path?.endsWith(".js") == true) it.mimeType = "text/javascript" }
                /* v1.9.19: VPN on but this WebView cannot be proxied (too old) or the tunnel has no port yet: nothing loads direct */
                else if (HwVpn.webBlocked()) WebResourceResponse("text/plain", "utf-8", 503, "VPN reconnecting", mapOf("Cache-Control" to "no-store"), java.io.ByteArrayInputStream(ByteArray(0)))
                else limited(request)

            override fun onPageFinished(view: WebView, url: String) { pushInsets(); pageReady = true; root.invalidate() }

            /* v1.8.25: every WebView in the app shares one renderer process; when a heavy dApp page makes it die (out of
               memory, a renderer crash), a WebView without this handler takes the whole app down with it (the force close
               seen on some XRP dApps). Hwallet now rebuilds its screen instead of closing. */
            @android.annotation.TargetApi(26)
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                try { (view.parent as? android.view.ViewGroup)?.removeView(view); view.destroy() } catch (_: Throwable) {}
                if (view === web) ui.post { try { recreate() } catch (_: Throwable) {} }
                return true
            }
            override fun onPageCommitVisible(view: WebView, url: String) { pageReady = true; root.invalidate() }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.host == "appassets.androidplatform.net") return false
                // explorer links etc. open outside the app, never inside it
                try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) {}
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean = chooseFiles(cb, params)
        }

        web.addJavascriptInterface(HarkNative(this,
            onBars = { top, bottom -> runOnUiThread { applyBars(top, bottom) } },
            insetsJson = { insetCss },
            bio = { bioStatus() },
            bioPrompt = { t -> runOnUiThread { bioAuth(t) } },
            onAwake = { on -> runOnUiThread { web.keepScreenOn = on } },
            bioCryptCb = { mode, data, title -> runOnUiThread { bioCrypt(mode, data, title) } },
            devAuthCb = { t -> runOnUiThread { devAuth(t) } }), "HarkNative")
        web.addJavascriptInterface(HwBridge(), "HwNative")
        tuneWebView(this, web)

        root = FrameLayout(this)
        root.setBackgroundColor(LIME)
        root.addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // True edge-to-edge: the page draws under the status and navigation bars, so the bars
        // show the page's own pixels (lime splash, white app, dark app). The page keeps its
        // content clear of them with the --kw-sa* CSS variables pushed from here.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val imeOn = insets.isVisible(WindowInsetsCompat.Type.ime())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            // keyboard open: shrink the WebView above it (same as adjustResize)
            v.setPadding(0, 0, 0, if (imeOn) ime.bottom else 0)
            val d = resources.displayMetrics.density
            insetCss = "{\"t\":${bars.top / d},\"r\":${bars.right / d},\"b\":${if (imeOn) 0f else bars.bottom / d},\"l\":${bars.left / d}}"
            pushInsets()
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)
        // hold the first frame until the page has painted (max 1.2 s): no blank flash on open
        val t0 = System.currentTimeMillis()
        root.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (pageReady || System.currentTimeMillis() - t0 > 1200) { root.viewTreeObserver.removeOnPreDrawListener(this); return true }
                return false
            }
        })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // v1.7 (P1): a Browser page on screen goes back in its own history first (no sheet can be open over it:
                // Hwallet hides the page while one is)
                if (br?.back() == true) return
                // let the page close an open sheet first; otherwise leave the app
                web.evaluateJavascript("(window.kwBack && window.kwBack()) ? 1 : 0") { r ->
                    if (r != "1") moveTaskToBack(true)
                }
            }
        })

        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else web.loadUrl(startUrl())
        takeWc(intent)
        dbgCleanup()
        HwActDb.listener = { wid, json -> actPatch(wid, json) }
        /* v1.8.34: Kwallet cleared / uninstalled / erased / a seed removed while this screen is up: the page applies it now */
        KwWipe.onPurge = { runOnUiThread { try { if (pageReady && ::web.isInitialized) web.evaluateJavascript("window.hwKwPurge&&hwKwPurge()", null) } catch (_: Throwable) {} } }
        /* v1.8.38 (B24): a seed renamed in Kwallet while this screen is up: the page shows the new name now */
        KwWipe.onNames = { runOnUiThread { try { if (pageReady && ::web.isInitialized) web.evaluateJavascript("window.hwKwNames&&hwKwNames()", null) } catch (_: Throwable) {} } }
        HwNet.onChange = { runOnUiThread { try { if (::web.isInitialized) web.evaluateJavascript("window.hwNetChanged&&hwNetChanged()", null) } catch (_: Throwable) {} } }
        HwVpn.listeners.add(vpnL)
        // v1.3 (#42): the always-on listener is NOT started here any more. The page starts it through setLive()
        // after it has loaded, when Hwallet is resumed (see startLiveSafe).
    }

    /** v1.4 (#53): while a data speed limit is set, the page's remote images (token logos) load through HwNet too,
     *  so they share the same limit. Unlimited: the WebView loads them itself as before. */
    private fun limited(req: WebResourceRequest): WebResourceResponse? {
        if (!HwNet.rate.on() || req.method != "GET" || req.url.scheme != "https") return null
        return try {
            val r = HwNet.withTimeout(30000).newCall(okhttp3.Request.Builder().url(req.url.toString()).header("User-Agent", "Hwallet/" + BuildConfig.VERSION_NAME).build()).execute()
            val b = r.body
            if (!r.isSuccessful || b == null) { r.close(); WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0))) }
            else {
                val mt = b.contentType(); val mime = if (mt != null) mt.type + "/" + mt.subtype else "application/octet-stream"
                WebResourceResponse(mime, mt?.charset()?.name(), r.code, r.message.ifEmpty { "OK" }, mapOf("Cache-Control" to "max-age=86400", "Access-Control-Allow-Origin" to "*"), b.byteStream())
            }
        } catch (_: Throwable) { WebResourceResponse("text/plain", "utf-8", 504, "Gateway Timeout", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0))) }
    }

    /** v1.4 (#55): rows the live service just wrote to the activity cache, patched into the open Activity list in place */
    private fun actPatch(wid: String, json: String) {
        runOnUiThread { try { if (::web.isInitialized) web.evaluateJavascript("window.hwActPatch&&hwActPatch(${JSONObject.quote(wid)},${JSONObject.quote(json)})", null) } catch (_: Throwable) {} }
    }

    private val BIO = BiometricManager.Authenticators.BIOMETRIC_STRONG

    private fun bioStatus(): String = when (BiometricManager.from(this).canAuthenticate(BIO)) {
        BiometricManager.BIOMETRIC_SUCCESS -> "ok"
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "unenrolled"
        else -> "none"   // no sensor, broken sensor, or unavailable: the app stays on PIN only
    }

    private fun bioResult(r: String) = web.evaluateJavascript("window.kwBioDone&&kwBioDone(${JSONObject.quote(r)})", null)

    private fun bioAuth(title: String) {
        if (bioStatus() != "ok") { bioResult("fail"); return }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { bioResult("ok") }
            override fun onAuthenticationError(code: Int, msg: CharSequence) {
                bioResult(when (code) {
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON -> "pin"
                    BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_CANCELED -> "cancel"
                    BiometricPrompt.ERROR_LOCKOUT, BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> "lockout"
                    else -> "fail:$msg"
                })
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle("Hwallet")
            .setNegativeButtonText("Use PIN")
            .setAllowedAuthenticators(BIO)
            .setConfirmationRequired(false)
            .build()
        try { prompt.authenticate(info) } catch (_: Exception) { bioResult("fail") }
    }

    /** v1.8.35 (B13): saved Browser passwords open (afOpen) only within 60 s of this unlock, and never in the background */
    @Volatile private var afUntil = 0L
    @Volatile private var afAuthing = false   // the unlock prompt itself may pause Hwallet (screen-lock PIN page)
    private fun afUnlockedNow(): Boolean = android.os.SystemClock.elapsedRealtime() < afUntil
    private fun afDone(r: String) { afAuthing = false; if (::web.isInitialized) web.evaluateJavascript("window.hwAfAuthDone&&hwAfAuthDone(${JSONObject.quote(r)})", null) }
    private fun afDevAuth(title: String) {
        val km = getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager
        if (!km.isDeviceSecure) { afDone("nolock"); return }
        afAuthing = true
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { afUntil = android.os.SystemClock.elapsedRealtime() + 60_000L; afDone("ok") }
            override fun onAuthenticationError(code: Int, msg: CharSequence) {
                afDone(if (code == BiometricPrompt.ERROR_USER_CANCELED || code == BiometricPrompt.ERROR_CANCELED || code == BiometricPrompt.ERROR_NEGATIVE_BUTTON) "cancel" else "fail")
            }
        })
        val b = BiometricPrompt.PromptInfo.Builder().setTitle(title.ifEmpty { "Use saved password" }).setSubtitle("Hwallet").setConfirmationRequired(false)
        if (Build.VERSION.SDK_INT >= 30) b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        else @Suppress("DEPRECATION") b.setDeviceCredentialAllowed(true)
        try { prompt.authenticate(b.build()) } catch (_: Exception) { afDone("fail") }
    }

    /** v3.90: the phone's own lock for the sign page when Kwallet has no PIN. "ok" | "cancel" | "fail" | "nolock" */
    private fun devResult(r: String) = web.evaluateJavascript("window.kwDevDone&&kwDevDone(${JSONObject.quote(r)})", null)
    private fun devAuth(title: String) {
        val km = getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager
        if (!km.isDeviceSecure) { devResult("nolock"); return }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { devResult("ok") }
            override fun onAuthenticationError(code: Int, msg: CharSequence) {
                devResult(if (code == BiometricPrompt.ERROR_USER_CANCELED || code == BiometricPrompt.ERROR_CANCELED || code == BiometricPrompt.ERROR_NEGATIVE_BUTTON) "cancel" else "fail")
            }
        })
        val b = BiometricPrompt.PromptInfo.Builder().setTitle(title).setSubtitle("Hwallet").setConfirmationRequired(false)
        if (Build.VERSION.SDK_INT >= 30) b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        else @Suppress("DEPRECATION") b.setDeviceCredentialAllowed(true)
        try { prompt.authenticate(b.build()) } catch (_: Exception) { devResult("fail") }
    }

    private fun b64(b: ByteArray) = android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)
    private fun ub64(s: String) = android.util.Base64.decode(s, android.util.Base64.NO_WRAP)

    /** v3.84: biometric sheet bound to a Keystore cipher. "enc": data = base64 data key -> "ok:<iv>:<ct>".
     *  "dec": data = "<iv>:<ct>" -> "ok:<base64 data key>". "invalid" when fingerprints changed (key revoked). */
    private fun bioCrypt(mode: String, data: String, title: String) {
        if (bioStatus() != "ok") { bioResult("fail"); return }
        val enc = mode == "enc"
        val parts = data.split(":")
        val cipher = try {
            if (enc) KwKeys.bioCipher(true, null) else KwKeys.bioCipher(false, ub64(parts[0]))
        } catch (_: android.security.keystore.KeyPermanentlyInvalidatedException) { KwKeys.deleteBio(); bioResult("invalid"); return
        } catch (_: Exception) { bioResult(if (enc) "fail" else "invalid"); return }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                try {
                    val c = result.cryptoObject?.cipher ?: throw IllegalStateException()
                    bioResult(if (enc) { val ct = c.doFinal(ub64(data)); "ok:" + b64(c.iv) + ":" + b64(ct) }
                              else "ok:" + b64(c.doFinal(ub64(parts[1]))))
                } catch (_: Exception) { bioResult("fail") }
            }
            override fun onAuthenticationError(code: Int, msg: CharSequence) {
                bioResult(when (code) {
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON -> "pin"
                    BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_CANCELED -> "cancel"
                    BiometricPrompt.ERROR_LOCKOUT, BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> "lockout"
                    else -> "fail:$msg"
                })
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle("Hwallet")
            .setNegativeButtonText("Use PIN")
            .setAllowedAuthenticators(BIO)
            .setConfirmationRequired(false)
            .build()
        try { prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher)) } catch (_: Exception) { bioResult("fail") }
    }

    /** Called by the page with the colours under the status bar and the navigation bar.
     *  v3.54: the bars glide to the new colour (same timing as the page's own fade) and the
     *  icons flip at the midpoint, so nothing sticks on lime or snaps on open. */
    @Volatile private var pageReady = false
    private var curTop = LIME; private var curBot = LIME
    private var barAnim: ValueAnimator? = null
    private fun applyBars(top: Int, bottom: Int) {
        if (top == curTop && bottom == curBot && barAnim?.isRunning != true) return
        barAnim?.cancel()
        val fromT = curTop; val fromB = curBot; val ev = ArgbEvaluator()
        barAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420
            interpolator = PathInterpolator(.22f, 1f, .36f, 1f)
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                paintBars(ev.evaluate(f, fromT, top) as Int, ev.evaluate(f, fromB, bottom) as Int)
            }
            start()
        }
        curTop = top; curBot = bottom
    }
    /** v1.9.38: the Browser ⋮ drop-down closed: Hwallet's WebView gets the bars' colour back */
    fun brFrontDone() { runOnUiThread { try { if (::web.isInitialized) web.setBackgroundColor(curTop) } catch (_: Throwable) {} } }
    private fun paintBars(top: Int, bottom: Int) {
        if (::root.isInitialized) root.setBackgroundColor(bottom)
        if (::web.isInitialized && br?.front != true) web.setBackgroundColor(top)   // v1.9.38: see-through while the Browser ⋮ drop-down is open
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < 35) { window.statusBarColor = top; window.navigationBarColor = bottom }
        val c = WindowCompat.getInsetsController(window, window.decorView)
        val lt = isLight(top); val lb = isLight(bottom)
        if (c.isAppearanceLightStatusBars != lt) c.isAppearanceLightStatusBars = lt
        if (c.isAppearanceLightNavigationBars != lb) c.isAppearanceLightNavigationBars = lb
    }

    private fun isLight(c: Int): Boolean =
        (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0 >= 0.5

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); if (intent.hasExtra("hw_wc_req")) wcWokeAt = System.currentTimeMillis(); takeWc(intent) }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    /* v1.7 (#92): WalletConnect in the background */
    @Volatile private var fgNow = false
    @Volatile private var wcKeep = false
    private val WC_NOTE = 7702
    private fun wcPost(kind: String, title: String, body: String) {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel("hw_wc") == null) {
                nm.createNotificationChannel(android.app.NotificationChannel("hw_wc", "WalletConnect requests", android.app.NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Connection and signature requests from apps connected with WalletConnect"; enableVibration(true); vibrationPattern = longArrayOf(0, 40, 60, 40)
                })
            }
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
            val i = Intent(this, HwActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("hw_wc_req", kind)
            val pi = android.app.PendingIntent.getActivity(this, 7702, i, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            val n = androidx.core.app.NotificationCompat.Builder(this, "hw_wc").setSmallIcon(R.drawable.hw_stat)
                .setContentTitle(title).setContentText(body).setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH).setCategory(androidx.core.app.NotificationCompat.CATEGORY_CALL)
                .setDefaults(androidx.core.app.NotificationCompat.DEFAULT_SOUND).setAutoCancel(true).setTimeoutAfter(300000L)
                .setContentIntent(pi).addAction(0, if (kind == "connect") "Review" else "Sign in Kwallet", pi).build()
            nm.notify(WC_NOTE, n)
        } catch (e: Throwable) { android.util.Log.w("HwWC", "notify failed: $e") }
    }
    private fun txPost(id: String, title: String, body: String, state: String) {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel("hw_tx") == null) {
                nm.createNotificationChannel(android.app.NotificationChannel("hw_tx", "Transaction status", android.app.NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Pending, confirmed and failed transactions sent from this phone"; setSound(null, null)
                })
            }
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
            val nid = 7800 + (id.hashCode() and 0x3ff)
            if (state == "pending" && fgNow) return
            val i = Intent(this, HwActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi = android.app.PendingIntent.getActivity(this, nid, i, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            val b = androidx.core.app.NotificationCompat.Builder(this, "hw_tx").setSmallIcon(R.drawable.hw_stat)
                .setContentTitle(title).setContentText(body).setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pi).setOnlyAlertOnce(true).setSilent(state == "pending")
                .setAutoCancel(state != "pending").setOngoing(false)
            if (state == "pending") b.setProgress(0, 0, true).setTimeoutAfter(900000L)
            nm.notify(nid, b.build())
        } catch (e: Throwable) { android.util.Log.w("HwTx", "notify failed: $e") }
    }
    private val xmrPend = java.util.concurrent.ConcurrentHashMap<String, Array<String>>()
    /** v1.8.8 (R9c): GET /xmrq/<token>?m=GET|POST&ct=b|j&u=<https url>&b=<base64url body>, fetched by the Monero worker itself.
     *  Every answer carries X-Hw: 1 so the worker can tell this path works in this WebView. */
    private fun xmrDirect(u: Uri): WebResourceResponse {
        val hdr = mapOf("Cache-Control" to "no-store", "X-Hw" to "1")
        val none = { code: Int, why: String -> WebResourceResponse("text/plain", "utf-8", code, why, hdr, java.io.ByteArrayInputStream(ByteArray(0))) }
        val tok = (u.path ?: "").removePrefix("/xmrq/")
        if (tok != xmrToken) return none(403, "Forbidden")
        val url = u.getQueryParameter("u") ?: ""
        if (!url.startsWith("https://")) return none(400, "Bad Request")
        val m = if (u.getQueryParameter("m") == "GET") "GET" else "POST"
        val ct = when (u.getQueryParameter("ct")) { "b" -> "application/octet-stream"; "f" -> "application/x-www-form-urlencoded"; else -> "application/json" }   // v1.9.34: f = form posts (Google Translate)
        return try {
            val b64 = u.getQueryParameter("b") ?: ""
            val bytes = if (b64.isEmpty()) ByteArray(0) else android.util.Base64.decode(b64, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
            val rb = okhttp3.Request.Builder().url(url).header("User-Agent", "Hwallet/" + BuildConfig.VERSION_NAME)
            if (m == "POST") rb.post(bytes.toRequestBody(ct.toMediaTypeOrNull()))
            val r = HwNet.withTimeout(60000).newCall(rb.build()).execute()
            val code = r.code
            if (code < 200 || code > 599 || code in 300..399) { r.close(); return none(502, "Bad Gateway") }
            val body = r.body ?: run { r.close(); return none(502, "Bad Gateway") }
            WebResourceResponse((r.header("Content-Type") ?: "application/octet-stream").substringBefore(';').trim().ifEmpty { "application/octet-stream" }, null, code,
                r.message.ifBlank { if (code == 200) "OK" else "Error" }, hdr, body.byteStream())
        } catch (_: Throwable) { none(502, "Bad Gateway") }
    }
    /** v1.8.8 (R11a): Kwallet's large answer, read once by the page and then deleted */
    private fun kwResServe(id: String): WebResourceResponse {
        val none = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", mapOf("Cache-Control" to "no-store"), java.io.ByteArrayInputStream(ByteArray(0)))
        if (!KwxProvider.ID.matches(id) || id != kwRid) return none
        val f = KwxProvider.file(this, "res", id)
        return try {
            val bytes = f.readBytes(); f.delete()
            WebResourceResponse("application/json", "utf-8", 200, "OK", mapOf("Cache-Control" to "no-store"), java.io.ByteArrayInputStream(bytes))
        } catch (_: Throwable) { none }
    }
    /** v1.8 (Monero): answers the page's GET /xmrnet/<id> with the node's reply to the request registered by xmrPrep (runs off the UI thread) */
    private fun xmrServe(id: String): WebResourceResponse {
        val none = { code: Int, why: String -> WebResourceResponse("text/plain", "utf-8", code, why, mapOf("Cache-Control" to "no-store"), java.io.ByteArrayInputStream(ByteArray(0))) }
        val p = xmrPend.remove(id) ?: return none(404, "Not Found")
        return try {
            val rb = okhttp3.Request.Builder().url(p[1]).header("User-Agent", "Hwallet/" + BuildConfig.VERSION_NAME)
            if (p[0] == "POST") {
                val bytes = if (p[2].isEmpty()) ByteArray(0) else android.util.Base64.decode(p[2], android.util.Base64.NO_WRAP)
                rb.post(bytes.toRequestBody((p[3].ifEmpty { "application/octet-stream" }).toMediaTypeOrNull()))
            }
            val r = HwNet.withTimeout(60000).newCall(rb.build()).execute()
            val code = r.code
            if (code < 200 || code > 599 || code in 300..399) { r.close(); return none(502, "Bad Gateway") }
            val body = r.body ?: run { r.close(); return none(502, "Bad Gateway") }
            WebResourceResponse((r.header("Content-Type") ?: "application/octet-stream").substringBefore(';').trim().ifEmpty { "application/octet-stream" }, null, code,
                r.message.ifBlank { if (code == 200) "OK" else "Error" }, mapOf("Cache-Control" to "no-store"), body.byteStream())
        } catch (_: Throwable) { none(502, "Bad Gateway") }
    }
    /** v1.8: an incoming-payment notification on the same channel the live service uses for other chains */
    private fun inPost(id: String, title: String, body: String) {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(HwLiveService.CH_IN) == null) {
                nm.createNotificationChannel(android.app.NotificationChannel(HwLiveService.CH_IN, "Incoming payments", android.app.NotificationManager.IMPORTANCE_HIGH))
            }
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
            val nid = 7300 + (id.hashCode() and 0x3ff)
            val i = Intent(this, HwActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi = android.app.PendingIntent.getActivity(this, nid, i, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            val n = androidx.core.app.NotificationCompat.Builder(this, HwLiveService.CH_IN).setSmallIcon(R.drawable.hw_stat).setColor(0xFFDDF869.toInt())
                .setContentTitle(title).setContentText(body).setAutoCancel(true).setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setCategory(androidx.core.app.NotificationCompat.CATEGORY_STATUS).setContentIntent(pi).build()
            nm.notify(nid, n)
        } catch (e: Throwable) { android.util.Log.w("HwIn", "notify failed: $e") }
    }
    /* P2a (#95): while Kwallet's sheet is over Hwallet the dApp tab stays live and visible behind it (no pause, no save) */
    override fun onPause() {
        super.onPause(); fgNow = false; kwPaused = true; if (!afAuthing) afUntil = 0L   // v1.8.35 (B13): saved passwords lock in the background
        if (!signing) { web.onPause(); br?.pause() }
        /* v1.8.13: Kwallet's sheet came up over Hwallet: the page's Monero no-answer limit (45 s) stops here */
        else if (pageReady && ::web.isInitialized) try { web.evaluateJavascript("window.hwKShown&&hwKShown()", null) } catch (_: Throwable) {}
    }
    @Volatile private var warmAt = 0L
    @Volatile private var warmLinkAt = 0L
    /** P2a / wave A: have Kwallet pre-load its sign page. v1.9.20: over the bound K-H link when it is up (cheap, Kwallet keeps
     *  one spare page and ignores repeats), else the WARM broadcast as before (at most every 30 s). */
    fun warmKwallet(force: Boolean = false) {
        val now = android.os.SystemClock.uptimeMillis()
        val b = kwSvc
        if (b != null) {
            if (!force && now - warmLinkAt < 1000L) return
            warmLinkAt = now
            if (kwSvcSend(b, KW_SVC_WARM, null, null)) return
        }
        if (now - warmAt < 30000) return; warmAt = now
        /* wave A (P2a f): an explicit component broadcast (package visibility via <queries>; the receiver needs the signature
         * permission app.kwallet.permission.SIGN_FEED that Hwallet holds). Kwallet then pre-loads its sign page. */
        try {
            sendBroadcast(Intent("app.kwallet.WARM").setComponent(android.content.ComponentName(KWALLET, "$KWALLET.WarmReceiver")))
            android.util.Log.i("HwWarm", "warm sent to Kwallet")
        } catch (e: Exception) { android.util.Log.w("HwWarm", "warm failed: $e") }
    }
    /* v1.8.28: WalletConnect links of other wallets Hwallet answers (pairing link or the request ping) */
    private val WC_ALIAS = setOf("bifrostwallet", "girinwallet", "joey", "metamask", "trust", "okex", "bitkeep", "tpoutside", "rainbow", "safepalwallet", "zerion", "uniswap")
    /** v1.8.28: a WalletConnect link copied from a dApp's QR ("Copy link") pairs as soon as Hwallet gets focus. Each link
     *  is used once; Android only lets the app read the clipboard while it has focus. */
    @Volatile private var lastClipWc = ""
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val d = cm.primaryClip ?: return
            if (d.itemCount < 1) return
            val t = (d.getItemAt(0)?.text ?: return).toString().trim()
            if (!t.startsWith("wc:") || !t.contains("symKey=") || t.length >= 4096 || t == lastClipWc) return
            if (getSharedPreferences("hw_wc_clip", MODE_PRIVATE).getString("last", "") == t) { lastClipWc = t; return }
            lastClipWc = t; getSharedPreferences("hw_wc_clip", MODE_PRIVATE).edit().putString("last", t).apply()
            wcLink(t)
        } catch (_: Throwable) {}
    }

    override fun onResume() {
        super.onResume(); fgNow = true; web.onResume(); br?.resume(); if (::web.isInitialized) tuneWebView(this, web)
        if (pageReady && ::web.isInitialized) web.evaluateJavascript("window.hwFront&&hwFront()", null)
        /* v1.8.34: back in front: apply a purge that came in meanwhile, then ask Kwallet whether its seeds are still there */
        if (pageReady && ::web.isInitialized) web.evaluateJavascript("window.hwKwFront&&hwKwFront()", null)
        if (pendingLive) ui.postDelayed({ if (pendingLive) startLiveSafe() }, 300)
        ui.postDelayed({ warmKwallet() }, 2500)  // P2a: keep Kwallet's sign sheet quick to open while Hwallet is in use
        kwSvcBind()   // v1.9.20: usually already bound since onStart; rebinds if the link dropped
    }  // OEMs drop back to 60 Hz after resume
    override fun onDestroy() { try { br?.destroy(); br = null } catch (_: Throwable) {}; try { socks.values.forEach { it.cancel() }; socks.clear(); HwActDb.listener = null; HwVpn.listeners.remove(vpnL); KwWipe.onPurge = null; KwWipe.onNames = null; ui.removeCallbacksAndMessages(null); kwSvcUnbind(); net.shutdownNow(); kwEx.shutdownNow(); kwLinkEx.shutdownNow(); web.destroy() } catch (_: Throwable) {} ; super.onDestroy() }

    /* ---- v1.9.20: the persistent K-H link ----
       While Hwallet is on screen (onStart .. onStop) it stays bound to Kwallet's KwLinkService (signature permission; Kwallet
       checks this app's uid and signature on every call). A ping every 5 s on its own thread (hw-link) proves the link; a dead
       Kwallet (linkToDeath), a dropped binding or a failed ping rebinds at once. Over it Hwallet warms the sign page (and warms
       again whenever Kwallet says no page is ready) and streams the live fee / balance / price feed to the open sheet (the
       SIGN_FEED broadcast stays the fallback). Nothing runs in the background: onStop unbinds and stops the ping.
       The per-request kwlink Binder of each sign launch is unchanged. */
    private val KW_SVC_PING = android.os.IBinder.FIRST_CALL_TRANSACTION + 11
    private val KW_SVC_WARM = android.os.IBinder.FIRST_CALL_TRANSACTION + 12
    private val KW_SVC_FEED = android.os.IBinder.FIRST_CALL_TRANSACTION + 13
    private val KW_SVC_QUIET = android.os.IBinder.FIRST_CALL_TRANSACTION + 14
    /** v1.9.24: hand Kwallet our event Binder; Kwallet calls it (one-way) the moment anything changes there */
    private val KW_SVC_WATCH = android.os.IBinder.FIRST_CALL_TRANSACTION + 15
    /** Kwallet's state stamp from the last ping (install id + pairing mirror time); a change = something happened in Kwallet */
    @Volatile private var kwStamp = ""
    /** main thread: tell the page what Kwallet just did (wipe | gone | names | pair | stamp | died | up) */
    private fun kwEvent(ev: String) {
        try { if (pageReady && ::web.isInitialized) web.evaluateJavascript("window.hwKwEvent&&hwKwEvent(${JSONObject.quote(ev.take(16))})", null) } catch (_: Throwable) {}
    }
    /** the Binder Kwallet calls back on; only Kwallet's uid (signature checked) is listened to */
    private val kwWatch = object : android.os.Binder() {
        override fun onTransact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean {
            if (code != android.os.IBinder.FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
            val uid = android.os.Binder.getCallingUid()
            val ok = try { (packageManager.getPackagesForUid(uid) ?: emptyArray()).any { it == KWALLET } && packageManager.checkSignatures(packageName, KWALLET) == android.content.pm.PackageManager.SIGNATURE_MATCH } catch (_: Exception) { false }
            if (!ok) return false
            val ev = try { data.readString() ?: "" } catch (_: Exception) { "" }
            val st = try { data.readString() ?: "" } catch (_: Exception) { "" }
            if (st.isNotEmpty()) kwStamp = st
            if (ev.isNotEmpty()) runOnUiThread { kwEvent(ev) }
            return true
        }
    }
    /** v1.9.23: Kwallet's link protocol from its last ping (2+ = headless identity reads) */
    @Volatile private var kwSvcProto = 0
    private val KW_SVC_BEAT = 5000L
    /** the link's Binder while connected (null = not connected) */
    @Volatile private var kwSvc: android.os.IBinder? = null
    /* main thread only */
    private var kwSvcWant = false
    private var kwSvcBound = false
    private var kwSvcBoundAt = 0L
    private var kwSvcRetryAt = 0L
    /** wait before the next bind after a failure: 0.5 s, doubling to 60 s; back to 0.5 s after a good ping */
    private var kwSvcBackoff = 500L
    @Volatile private var kwSvcPinging = false
    private val kwLinkEx = java.util.concurrent.ThreadPoolExecutor(1, 1, 15L, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.LinkedBlockingQueue<Runnable>(),
        java.util.concurrent.ThreadFactory { r -> Thread(r, "hw-link").apply { isDaemon = true } }).apply { allowCoreThreadTimeOut(true) }
    private val kwSvcDeath = android.os.IBinder.DeathRecipient { runOnUiThread { kwSvcLost("Kwallet died"); kwEvent("died") } }   // v1.9.24: Clear data / force stop is seen at once
    private val kwSvcConn = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
            val b = service ?: run { kwSvcLost("no binder"); return }
            kwSvcDrop()
            try { b.linkToDeath(kwSvcDeath, 0) } catch (_: Exception) { kwSvcLost("dead on connect"); return }
            kwSvc = b
            android.util.Log.i("HwLink", "K-H link up")
            if (kwSvcWant) warmKwallet(true)
            /* v1.9.24: register for Kwallet's instant events (a Kwallet before 3.99.24 ignores this code) */
            kwLinkEx.execute { val d = android.os.Parcel.obtain(); try { d.writeStrongBinder(kwWatch); b.transact(KW_SVC_WATCH, d, null, android.os.IBinder.FLAG_ONEWAY) } catch (_: Exception) {} finally { d.recycle() } }
            kwEvent("up")
        }
        /* Kwallet's process went away; the system reconnects by itself (BIND_AUTO_CREATE), and the beat rebinds if it does not */
        override fun onServiceDisconnected(name: android.content.ComponentName?) { kwSvcDrop() }
        override fun onBindingDied(name: android.content.ComponentName?) { kwSvcLost("binding died") }
        override fun onNullBinding(name: android.content.ComponentName?) { kwSvcLost("null binding") }
    }
    private val kwSvcBeat = object : Runnable {
        override fun run() {
            if (!kwSvcWant) return
            val b = kwSvc
            when {
                !kwSvcBound -> kwSvcBind()
                b != null -> if (!kwSvcPinging) { kwSvcPinging = true; kwLinkEx.execute { kwSvcPing(b) } }
                /* bound but never (re)connected within two beats: start over */
                android.os.SystemClock.uptimeMillis() - kwSvcBoundAt > 2 * KW_SVC_BEAT -> kwSvcLost("no connection")
            }
            ui.removeCallbacks(this)
            if (kwSvcWant) ui.postDelayed(this, KW_SVC_BEAT)
        }
    }
    /** main thread: bind (once) and start the beat */
    private fun kwSvcBind() {
        kwSvcWant = true
        if (!kwSvcBound) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now >= kwSvcRetryAt) {
                var ok = false
                if (kwCode() > 0L) {
                    try {
                        val i = Intent().setClassName(KWALLET, "$KWALLET.KwLinkService")
                        ok = bindService(i, kwSvcConn, android.content.Context.BIND_AUTO_CREATE or android.content.Context.BIND_IMPORTANT)
                    } catch (e: Exception) { android.util.Log.w("HwLink", "bind failed: $e") }
                }
                if (ok) { kwSvcBound = true; kwSvcBoundAt = now }
                else {
                    /* Kwallet missing, or a Kwallet without the link: try again later, less and less often (the broadcast paths still work) */
                    try { unbindService(kwSvcConn) } catch (_: Exception) {}
                    kwSvcRetryAt = now + maxOf(kwSvcBackoff, KW_SVC_BEAT); kwSvcBackoff = minOf(kwSvcBackoff * 2, 60000L)
                }
            }
        }
        ui.removeCallbacks(kwSvcBeat)
        ui.postDelayed(kwSvcBeat, KW_SVC_BEAT)
    }
    /** main thread: forget the current Binder (the binding itself stays) */
    private fun kwSvcDrop() {
        val b = kwSvc; kwSvc = null
        try { b?.unlinkToDeath(kwSvcDeath, 0) } catch (_: Exception) {}
    }
    /** main thread: the link broke; unbind and bind again right away while Hwallet is in front */
    private fun kwSvcLost(why: String) {
        android.util.Log.w("HwLink", "K-H link lost: $why")
        kwSvcDrop()
        if (kwSvcBound) { try { unbindService(kwSvcConn) } catch (_: Exception) {}; kwSvcBound = false }
        val wait = kwSvcBackoff
        kwSvcRetryAt = android.os.SystemClock.uptimeMillis() + wait; kwSvcBackoff = minOf(kwSvcBackoff * 2, 60000L)
        if (kwSvcWant) { ui.removeCallbacks(kwSvcBeat); ui.postDelayed(kwSvcBeat, wait) }
    }
    /** main thread: Hwallet left the screen (or is closing): unbind and stop the beat */
    private fun kwSvcUnbind() {
        kwSvcWant = false
        ui.removeCallbacks(kwSvcBeat)
        kwSvcDrop()
        if (kwSvcBound) { try { unbindService(kwSvcConn) } catch (_: Exception) {}; kwSvcBound = false }
        kwSvcRetryAt = 0L; kwSvcBackoff = 500L
    }
    /** hw-link thread: one heartbeat. Kwallet answers its protocol version and whether a sign page is ready. */
    private fun kwSvcPing(b: android.os.IBinder) {
        val d = android.os.Parcel.obtain(); val r = android.os.Parcel.obtain()
        var ok = false; var ready = true
        try {
            ok = b.transact(KW_SVC_PING, d, r, 0)
            if (ok) { kwSvcProto = r.readInt(); ready = r.readInt() == 1; if (kwSvcProto >= 3) { val st = try { r.readString() ?: "" } catch (_: Exception) { "" }; if (st.isNotEmpty() && st != kwStamp) { val was = kwStamp; kwStamp = st; if (was.isNotEmpty()) runOnUiThread { kwEvent("stamp") } } } }
        } catch (_: Exception) { ok = false } finally { d.recycle(); r.recycle(); kwSvcPinging = false }
        runOnUiThread {
            if (kwSvc !== b) return@runOnUiThread
            if (ok) kwSvcBackoff = 500L
            if (!ok) kwSvcLost("ping failed")
            else if (!ready && kwSvcWant && !signing) warmKwallet()
        }
    }
    /** one-way call over the link (never blocks); false = not sent (the caller falls back to the broadcast) */
    private fun kwSvcSend(b: android.os.IBinder, code: Int, a: String?, c: String?): Boolean {
        val d = android.os.Parcel.obtain()
        return try {
            if (a != null) d.writeString(a)
            if (c != null) d.writeString(c)
            b.transact(code, d, null, android.os.IBinder.FLAG_ONEWAY)
        } catch (_: Exception) {
            runOnUiThread { if (kwSvc === b) kwSvcLost("send failed") }
            false
        } finally { d.recycle() }
    }
    /** live feed for the open sheet over the link (Kwallet hands it to the same SignActivity entry as the broadcast).
     *  Large feeds keep using the broadcast so a one-way call never comes near the Binder buffer limit. */
    private fun kwSvcFeed(id: String, json: String): Boolean {
        val b = kwSvc ?: return false
        if (json.length > 65536 || id.length > 256) return false
        return kwSvcSend(b, KW_SVC_FEED, id, json)
    }
    /** v1.9.23: headless identity read over the link, on its own short-lived thread (a blocking call, at most ~10 s) */
    private fun kwQuiet(id: String, json: String) {
        val b = kwSvc
        val done = { out: String -> runOnUiThread { if (::web.isInitialized) web.evaluateJavascript("window.hwKQuiet&&hwKQuiet(${JSONObject.quote(id)},${JSONObject.quote(out)})", null) } }
        if (b == null || json.length > 65536) { done("{\"status\":\"nolink\"}"); return }
        Thread({
            val d = android.os.Parcel.obtain(); val r = android.os.Parcel.obtain()
            var out = "{\"status\":\"nolink\"}"
            try { d.writeString(json); if (b.transact(KW_SVC_QUIET, d, r, 0)) out = r.readString() ?: out } catch (_: Exception) {} finally { d.recycle(); r.recycle() }
            done(out)
        }, "hw-kquiet").apply { isDaemon = true }.start()
    }
    override fun onStart() { super.onStart(); kwSvcBind() }
    override fun onStop() { kwSvcUnbind(); super.onStop() }
    /** v1.9.20: what the 1.9.19-dbg test builds left behind (saved freeze dumps, the Freeze report notification and channel) */
    private fun dbgCleanup() {
        try { net.execute { try { java.io.File(filesDir, "hw_stall.txt").delete() } catch (_: Throwable) {} } } catch (_: Throwable) {}
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.cancel(7701)
            if (Build.VERSION.SDK_INT >= 26) nm.deleteNotificationChannel("hw_dbg")
        } catch (_: Throwable) {}
    }
}

/** Smooth rendering like Chrome: highest refresh rate, no overscroll glow.
 *  v1.3 (#42): every call guarded on its own; the v1.2 forced View hardware layer is gone (WebView is already GPU-composited,
 *  a View layer adds a full-screen texture copy per frame and is a known source of trouble on new Android versions). */
fun tuneWebView(activity: Activity, webView: WebView) {
    try { if (Build.VERSION.SDK_INT >= 26 && !E2eSkip.has("prio")) webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false) } catch (_: Throwable) {}
    try { webView.settings.offscreenPreRaster = false } catch (_: Throwable) {}
    try { webView.isVerticalScrollBarEnabled = false; webView.overScrollMode = View.OVER_SCROLL_NEVER } catch (_: Throwable) {}
    try {
        if (Build.VERSION.SDK_INT >= 23 && !E2eSkip.has("mode")) {
            @Suppress("DEPRECATION")
            val display = if (Build.VERSION.SDK_INT >= 30) activity.display else activity.windowManager.defaultDisplay
            val cur = display?.mode
            val best = if (cur == null) null else display.supportedModes?.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }?.maxByOrNull { it.refreshRate }
            if (best != null) {
                val lp = activity.window.attributes
                if (lp.preferredDisplayModeId != best.modeId || lp.preferredRefreshRate != best.refreshRate) {
                    lp.preferredDisplayModeId = best.modeId
                    lp.preferredRefreshRate = best.refreshRate   // some OEM skins (MIUI, One UI) read this one instead
                    activity.window.attributes = lp
                }
            }
        }
    } catch (_: Throwable) {}
    try { if (Build.VERSION.SDK_INT >= 29) { @Suppress("DEPRECATION") webView.isForceDarkAllowed = false } } catch (_: Throwable) {}
    try {
        if (Build.VERSION.SDK_INT >= 24) {
            val pm = activity.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            if (pm.isSustainedPerformanceModeSupported && !E2eSkip.has("perf")) activity.window.setSustainedPerformanceMode(true)
        }
    } catch (_: Throwable) {}
}

/** v3.97 P0b: CI emulator bisect switches. `am start -e kw_e2e_skip mode,perf,prio` skips those window/WebView tunings so
 *  the device check can find which one takes the API 34 emulator down. Read only in debug or -PkwE2E=true builds;
 *  store builds ignore the extra. */
object E2eSkip {
    private var skip: Set<String> = emptySet()
    fun read(i: android.content.Intent?) {
        if (!(BuildConfig.DEBUG || BuildConfig.E2E)) return
        skip = (i?.getStringExtra("kw_e2e_skip") ?: return).split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }
    fun has(k: String) = k in skip
}
