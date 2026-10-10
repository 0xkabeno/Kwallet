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
    companion object { const val KWALLET = "app.kwallet" }
    private lateinit var root: FrameLayout
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    @Volatile var insetCss = "{\"t\":0,\"r\":0,\"b\":0,\"l\":0}"

    private fun pushInsets() {
        if (::web.isInitialized) web.evaluateJavascript("window.kwInsets&&kwInsets($insetCss)", null)
    }

    /** Kwallet's sign page: one request at a time, answered only to this app. */
    private val kwSign = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        signing = false
        val json = res.data?.getStringExtra("res") ?: "{\"status\":\"rejected\"}"
        web.evaluateJavascript("window.hwSignDone&&hwSignDone(${JSONObject.quote(json)})", null)
    }
    /* v1.3 (#46): enough threads for every chain's history and balances at once (was 6, which queued the Activity tab) */
    private val net = java.util.concurrent.Executors.newFixedThreadPool(24)
    /** v1.1: true while Kwallet's sign page is open over Hwallet; the page keeps running to stream live data to it. */
    @Volatile private var signing = false

    inner class HwBridge {
        /** Opens Kwallet's sign page with one request (sign or pair). The result comes back through window.hwSignDone. */
        @android.webkit.JavascriptInterface
        fun sign(json: String) {
            runOnUiThread {
                val i = Intent().setClassName(KWALLET, "$KWALLET.SignActivity").putExtra("req", json.take(65536))
                val ok = try { packageManager.getPackageInfo(KWALLET, 0); true } catch (_: Exception) { false }
                if (!ok) { web.evaluateJavascript("window.hwSignDone&&hwSignDone(${JSONObject.quote("{\"status\":\"missing\"}")})", null); return@runOnUiThread }
                try { signing = true; kwSign.launch(i) } catch (_: Exception) {
                    signing = false
                    web.evaluateJavascript("window.hwSignDone&&hwSignDone(${JSONObject.quote("{\"status\":\"missing\"}")})", null)
                }
            }
        }

        /** HTTPS JSON requests to public nodes (no CORS limits here). Result via window.hwHttpDone(id, status, body).
         *  v1.4 (#53): through Hwallet's one network stack (HwNet), so the data speed limit applies. */
        @android.webkit.JavascriptInterface
        fun http(id: String, method: String, url: String, body: String, timeoutMs: Int) { http2(id, method, url, body, timeoutMs, "application/json") }

        /** v1.1: like http() with a content type; "type;hex" sends the hex body as raw bytes (Cardano CBOR). */
        @android.webkit.JavascriptInterface
        fun http2(id: String, method: String, url: String, body: String, timeoutMs: Int, contentType: String) {
            net.execute {
                var st = 0; var out = ""
                try {
                    if (!url.startsWith("https://")) throw IllegalArgumentException("https only")
                    val rb = okhttp3.Request.Builder().url(url)
                        .header("Accept", "application/json, text/plain, */*")
                        .header("User-Agent", "Hwallet/" + BuildConfig.VERSION_NAME)
                    if (method == "POST") {
                        val hex = contentType.endsWith(";hex")
                        val ct = if (hex) contentType.removeSuffix(";hex") else contentType.ifEmpty { "application/json" }
                        val bytes = if (hex) ByteArray(body.length / 2) { k -> body.substring(k * 2, k * 2 + 2).toInt(16).toByte() } else body.toByteArray(Charsets.UTF_8)
                        rb.post(bytes.toRequestBody(ct.toMediaTypeOrNull()))
                    }
                    /* at a low speed cap a big page needs longer than the page's timeout: let it finish */
                    val t = if (HwNet.rate.on()) 30000 else timeoutMs.coerceIn(1000, 20000)
                    HwNet.withTimeout(t).newCall(rb.build()).execute().use { r ->
                        st = r.code
                        out = r.body?.string()?.take(4_000_000) ?: ""
                    }
                } catch (_: Exception) { st = 0; out = "" }
                val js = "window.hwHttpDone&&hwHttpDone(${JSONObject.quote(id)},$st,${JSONObject.quote(out)})"
                runOnUiThread { if (::web.isInitialized) web.evaluateJavascript(js, null) }
            }
        }

        /** v1.4 (#53): Settings > Data speed limit, KB/s (0 = unlimited). Saved and applied at once to all traffic. */
        @android.webkit.JavascriptInterface
        fun setSpeed(kb: Int) { HwNet.setKbps(this@HwActivity, kb) }

        @android.webkit.JavascriptInterface
        fun speed(): Int = HwNet.kbps(this@HwActivity)

        /** v1.5 (#67): true only while the cap is set AND the phone is on mobile data (Wi-Fi is never limited) */
        @android.webkit.JavascriptInterface
        fun capActive(): Boolean = HwNet.rate.on()

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
        /* the battery prompt once, a moment later and only if Hwallet is still in front */
        val p = getSharedPreferences(HwLiveService.PREF, MODE_PRIVATE)
        if (!batteryOk() && !p.getBoolean("askedBatt", false)) ui.postDelayed({
            try { if (resumed() && !batteryOk() && !p.getBoolean("askedBatt", false)) { p.edit().putBoolean("askedBatt", true).apply(); askBatteryNow() } } catch (_: Throwable) {}
        }, 4000)
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
        val uri = if (res.resultCode == Activity.RESULT_OK) res.data?.data else null
        fileCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
        fileCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
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
                if (request.url.host == "appassets.androidplatform.net") assets.shouldInterceptRequest(request.url) else limited(request)

            override fun onPageFinished(view: WebView, url: String) { pushInsets(); pageReady = true; root.invalidate() }
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
            override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = cb
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    val types = params.acceptTypes.flatMap { it.split(",") }.map { it.trim() }.filter { it.contains("/") }
                    if (types.isNotEmpty()) putExtra(Intent.EXTRA_MIME_TYPES, (types + "application/octet-stream").distinct().toTypedArray())
                }
                return try { pickFile.launch(i); true } catch (_: Exception) { fileCallback = null; false }
            }
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
                // let the page close an open sheet first; otherwise leave the app
                web.evaluateJavascript("(window.kwBack && window.kwBack()) ? 1 : 0") { r ->
                    if (r != "1") moveTaskToBack(true)
                }
            }
        })

        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else web.loadUrl(startUrl())
        HwActDb.listener = { wid, json -> actPatch(wid, json) }
        HwNet.onChange = { runOnUiThread { try { if (::web.isInitialized) web.evaluateJavascript("window.hwNetChanged&&hwNetChanged()", null) } catch (_: Throwable) {} } }
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
    private fun paintBars(top: Int, bottom: Int) {
        if (::root.isInitialized) root.setBackgroundColor(bottom)
        if (::web.isInitialized) web.setBackgroundColor(top)
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < 35) { window.statusBarColor = top; window.navigationBarColor = bottom }
        val c = WindowCompat.getInsetsController(window, window.decorView)
        val lt = isLight(top); val lb = isLight(bottom)
        if (c.isAppearanceLightStatusBars != lt) c.isAppearanceLightStatusBars = lt
        if (c.isAppearanceLightNavigationBars != lb) c.isAppearanceLightNavigationBars = lb
    }

    private fun isLight(c: Int): Boolean =
        (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0 >= 0.5

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onPause() { super.onPause(); if (!signing) web.onPause() }
    override fun onResume() {
        super.onResume(); web.onResume(); if (::web.isInitialized) tuneWebView(this, web)
        if (pendingLive) ui.postDelayed({ if (pendingLive) startLiveSafe() }, 300)
    }  // OEMs drop back to 60 Hz after resume
    override fun onDestroy() { try { HwActDb.listener = null; ui.removeCallbacksAndMessages(null); net.shutdownNow(); web.destroy() } catch (_: Throwable) {} ; super.onDestroy() }
}

/** Smooth rendering like Chrome: highest refresh rate, no overscroll glow.
 *  v1.3 (#42): every call guarded on its own; the v1.2 forced View hardware layer is gone (WebView is already GPU-composited,
 *  a View layer adds a full-screen texture copy per frame and is a known source of trouble on new Android versions). */
fun tuneWebView(activity: Activity, webView: WebView) {
    try { if (Build.VERSION.SDK_INT >= 26) webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false) } catch (_: Throwable) {}
    try { webView.settings.offscreenPreRaster = false } catch (_: Throwable) {}
    try { webView.isVerticalScrollBarEnabled = false; webView.overScrollMode = View.OVER_SCROLL_NEVER } catch (_: Throwable) {}
    try {
        if (Build.VERSION.SDK_INT >= 23) {
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
            if (pm.isSustainedPerformanceModeSupported) activity.window.setSustainedPerformanceMode(true)
        }
    } catch (_: Throwable) {}
}
