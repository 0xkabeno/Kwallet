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
        val json = res.data?.getStringExtra("res") ?: "{\"status\":\"rejected\"}"
        web.evaluateJavascript("window.hwSignDone&&hwSignDone(${JSONObject.quote(json)})", null)
    }
    private val net = java.util.concurrent.Executors.newFixedThreadPool(6)

    inner class HwBridge {
        /** Opens Kwallet's sign page with one request (sign or pair). The result comes back through window.hwSignDone. */
        @android.webkit.JavascriptInterface
        fun sign(json: String) {
            runOnUiThread {
                val i = Intent().setClassName(KWALLET, "$KWALLET.SignActivity").putExtra("req", json.take(65536))
                val ok = try { packageManager.getPackageInfo(KWALLET, 0); true } catch (_: Exception) { false }
                if (!ok) { web.evaluateJavascript("window.hwSignDone&&hwSignDone(${JSONObject.quote("{\"status\":\"missing\"}")})", null); return@runOnUiThread }
                try { kwSign.launch(i) } catch (_: Exception) {
                    web.evaluateJavascript("window.hwSignDone&&hwSignDone(${JSONObject.quote("{\"status\":\"missing\"}")})", null)
                }
            }
        }

        /** HTTPS JSON requests to public nodes (no CORS limits here). Result via window.hwHttpDone(id, status, body). */
        @android.webkit.JavascriptInterface
        fun http(id: String, method: String, url: String, body: String, timeoutMs: Int) {
            net.execute {
                var st = 0; var out = ""
                try {
                    val u = java.net.URL(url)
                    if (u.protocol != "https") throw IllegalArgumentException("https only")
                    val c = u.openConnection() as java.net.HttpURLConnection
                    c.connectTimeout = timeoutMs.coerceIn(1000, 15000); c.readTimeout = timeoutMs.coerceIn(1000, 15000)
                    c.requestMethod = if (method == "POST") "POST" else "GET"
                    c.setRequestProperty("Accept", "application/json")
                    c.setRequestProperty("User-Agent", "Hwallet/" + BuildConfig.VERSION_NAME)
                    if (method == "POST") {
                        c.doOutput = true
                        c.setRequestProperty("Content-Type", "application/json")
                        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    }
                    st = c.responseCode
                    val stream = if (st in 200..299) c.inputStream else c.errorStream
                    out = stream?.use { s -> s.readBytes().toString(Charsets.UTF_8).take(4_000_000) } ?: ""
                    c.disconnect()
                } catch (_: Exception) { st = 0; out = "" }
                val js = "window.hwHttpDone&&hwHttpDone(${JSONObject.quote(id)},$st,${JSONObject.quote(out)})"
                runOnUiThread { if (::web.isInitialized) web.evaluateJavascript(js, null) }
            }
        }

        @android.webkit.JavascriptInterface
        fun openUrl(url: String) {
            runOnUiThread { try { if (url.startsWith("https://")) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {} }
        }
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
                if (request.url.host == "appassets.androidplatform.net") assets.shouldInterceptRequest(request.url) else null

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

    override fun onPause() { super.onPause(); web.onPause() }
    override fun onResume() { super.onResume(); web.onResume(); if (::web.isInitialized) tuneWebView(this, web) }  // OEMs drop back to 60 Hz after resume
    override fun onDestroy() { net.shutdownNow(); web.destroy(); super.onDestroy() }
}

/** Smooth rendering like Chrome: highest refresh rate, no overscroll glow. */
fun tuneWebView(activity: Activity, webView: WebView) {
    // WebView is already GPU-composited; forcing a View hardware layer adds an extra
    // full-screen texture copy every frame (the main cause of scroll jank) - so don't.
    if (Build.VERSION.SDK_INT >= 26) webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
    // No offscreen pre-raster: Chrome doesn't do it, and on long pages it rasterises
    // everything up front (memory spikes, slower first paint, dropped frames).
    webView.settings.offscreenPreRaster = false
    webView.isVerticalScrollBarEnabled = false
    webView.overScrollMode = View.OVER_SCROLL_NEVER
    if (Build.VERSION.SDK_INT >= 23) {
        @Suppress("DEPRECATION")
        val display = if (Build.VERSION.SDK_INT >= 30) activity.display else activity.windowManager.defaultDisplay
        val best = display?.supportedModes?.filter { it.physicalWidth == display.mode.physicalWidth }?.maxByOrNull { it.refreshRate }
        if (best != null) {
            val lp = activity.window.attributes
            lp.preferredDisplayModeId = best.modeId
            lp.preferredRefreshRate = best.refreshRate   // some OEM skins (MIUI, One UI) read this one instead
            activity.window.attributes = lp
        }
    }
    if (Build.VERSION.SDK_INT >= 29) {
        @Suppress("DEPRECATION")
        webView.isForceDarkAllowed = false
    }
}
