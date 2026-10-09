package app.kwallet

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
 * Kwallet shell: one WebView that serves the offline app from the APK's assets.
 * Served from https://appassets.androidplatform.net so the page runs in a secure
 * context (WebCrypto, Clipboard API). Nothing is ever loaded from the network:
 * the app has no INTERNET permission and every non-asset request is refused.
 */
class MainActivity : FragmentActivity() {
    private val LIME = 0xFFDDF869.toInt()

    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    @Volatile var insetCss = "{\"t\":0,\"r\":0,\"b\":0,\"l\":0}"

    private fun pushInsets() {
        if (::web.isInitialized) web.evaluateJavascript("window.kwInsets&&kwInsets($insetCss)", null)
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
        web.setBackgroundColor(0xFFDDF869.toInt())
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
                assets.shouldInterceptRequest(request.url)
                    ?: if (request.url.scheme == "blob" || request.url.scheme == "data") null
                    else WebResourceResponse("text/plain", "utf-8", 403, "Offline", null, null)

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
            onAwake = { on -> runOnUiThread { web.keepScreenOn = on } }), "HarkNative")
        tuneWebView(this, web)

        root = FrameLayout(this)
        root.setBackgroundColor(0xFFDDF869.toInt())
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
        else web.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    private val BIO = BiometricManager.Authenticators.BIOMETRIC_WEAK

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
            .setSubtitle("Kwallet")
            .setNegativeButtonText("Use PIN")
            .setAllowedAuthenticators(BIO)
            .setConfirmationRequired(false)
            .build()
        try { prompt.authenticate(info) } catch (_: Exception) { bioResult("fail") }
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
    override fun onResume() { super.onResume(); web.onResume() }
    override fun onDestroy() { web.destroy(); super.onDestroy() }
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
            activity.window.attributes = lp
        }
    }
    if (Build.VERSION.SDK_INT >= 29) {
        @Suppress("DEPRECATION")
        webView.isForceDarkAllowed = false
    }
}
