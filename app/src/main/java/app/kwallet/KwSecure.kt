package app.kwallet

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * v3.99.50: nothing in Kwallet can be captured, recorded or drawn over, in every state:
 *  - FLAG_SECURE on every window (activities, dialogs, popups): screenshots, recordings, casting and the recents
 *    thumbnail come out black;
 *  - recents thumbnail off (setRecentsScreenshotEnabled(false), API 33+);
 *  - other apps' overlays hidden over Kwallet (setHideOverlayWindows, API 31+) and touches refused when something draws over
 *    a sensitive view (filterTouchesWhenObscured, anti-tapjacking);
 *  - sensitive views readable only by real accessibility tools such as TalkBack (setAccessibilityDataSensitive, API 34+);
 *  - while a screen recording is visible (API 35) or right after a screenshot attempt (API 34) the window is covered black.
 * The CI emulator build (-PkwE2E=true) keeps capture on, because the device check takes screenshots.
 */
object KwSecure {
    private val E2E = BuildConfig.E2E

    fun window(w: Window?) {
        if (w == null) return
        if (!E2E) w.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 31) try { w.setHideOverlayWindows(true) } catch (_: Throwable) {}
    }

    fun dialog(d: Dialog) { window(d.window); d.setOnShowListener { window(d.window); d.window?.decorView?.let { view(it) } } }

    fun view(v: View) {
        v.filterTouchesWhenObscured = true
        if (Build.VERSION.SDK_INT >= 34) try { v.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES) } catch (_: Throwable) {}
    }

    fun activity(a: Activity) {
        window(a.window)
        if (!E2E && Build.VERSION.SDK_INT >= 33) try { a.setRecentsScreenshotEnabled(false) } catch (_: Throwable) {}
        a.window?.decorView?.let { view(it) }
    }

    /* ---- recording / screenshot callbacks: cover the window while capture is going on ---- */
    private val ui = Handler(Looper.getMainLooper())
    private fun cover(a: Activity, on: Boolean) {
        val root = a.window?.decorView as? ViewGroup ?: return
        val old = root.findViewWithTag<View>("kw-cover")
        if (on && old == null) root.addView(View(a).apply { tag = "kw-cover"; setBackgroundColor(0xFF000000.toInt()); isClickable = true; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        else if (!on && old != null) root.removeView(old)
    }
    private val recCb = HashMap<Activity, Any>()
    private val capCb = HashMap<Activity, Any>()

    /** onStart: watch for recordings (API 35, reflection: compileSdk 34) and screenshots (API 34) */
    fun start(a: Activity) {
        if (E2E) return
        if (Build.VERSION.SDK_INT >= 35) try {
            val wm = a.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val cb = java.util.function.Consumer<Int> { st -> ui.post { cover(a, st == 1) } }
            val m = WindowManager::class.java.getMethod("addScreenRecordingCallback", java.util.concurrent.Executor::class.java, java.util.function.Consumer::class.java)
            val st = m.invoke(wm, a.mainExecutor, cb) as? Int
            recCb[a] = cb; cover(a, st == 1)
        } catch (_: Throwable) {}
        if (Build.VERSION.SDK_INT >= 34) try {
            val cb = Activity.ScreenCaptureCallback { ui.post { cover(a, true); ui.postDelayed({ cover(a, false) }, 1500) } }
            a.registerScreenCaptureCallback(a.mainExecutor, cb); capCb[a] = cb
        } catch (_: Throwable) {}
    }
    fun stop(a: Activity) {
        recCb.remove(a)?.let { cb -> try {
            val wm = a.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            WindowManager::class.java.getMethod("removeScreenRecordingCallback", java.util.function.Consumer::class.java).invoke(wm, cb)
        } catch (_: Throwable) {} }
        if (Build.VERSION.SDK_INT >= 34) capCb.remove(a)?.let { cb -> try { a.unregisterScreenCaptureCallback(cb as Activity.ScreenCaptureCallback) } catch (_: Throwable) {} }
    }

    /* ---- clipboard: a copied secret is marked sensitive (no preview, API 33+) and cleared after ms, only if the clipboard
       still holds that same copy. Reading / pasting is never touched. ---- */
    @Volatile private var lastClip: ClipData? = null
    fun copySecret(c: Context, text: String, ms: Long): Boolean = try {
        val cm = c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Kwallet", text)
        clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)   /* ClipDescription.EXTRA_IS_SENSITIVE (API 33+); older phones ignore it */
        }
        cm.setPrimaryClip(clip); lastClip = clip
        val mine = clip
        ui.postDelayed({ clearIfMine(c, mine, text) }, ms.coerceIn(5_000L, 600_000L))
        true
    } catch (_: Throwable) { false }

    /** clears only when the clipboard still holds our copy (same text, our label); anything the user copied since stays */
    fun clearIfMine(c: Context, clip: ClipData, text: String) {
        try {
            val cm = c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val cur = cm.primaryClip
            if (KwClipRule.isMine(cur?.description?.label?.toString(), cur?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString(), "Kwallet", text)) {
                if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() else cm.setPrimaryClip(ClipData.newPlainText("", ""))
            }
            if (lastClip === clip) lastClip = null
        } catch (_: Throwable) {}
    }
}

/** pure rule (JVM test): our copy is still on the clipboard */
object KwClipRule {
    fun isMine(curLabel: String?, curText: String?, label: String, text: String): Boolean = curLabel == label && curText != null && curText == text
}
