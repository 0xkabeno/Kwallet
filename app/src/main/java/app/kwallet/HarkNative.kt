package app.kwallet

import android.content.ContentValues
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.Environment
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import org.json.JSONArray
import java.io.File

/** JavaScript bridge exposed to the page as window.HarkNative */
class HarkNative(
    private val ctx: Context,
    private val onBars: (Int, Int) -> Unit = { _, _ -> },
    private val insetsJson: () -> String = { "{}" },
    private val bio: () -> String = { "none" },
    private val bioPrompt: (String) -> Unit = { },
    private val onAwake: (Boolean) -> Unit = { }
) {

    /** Screen Wake Lock for the WebView (Chrome has navigator.wakeLock, WebView doesn't). */
    @JavascriptInterface
    fun keepAwake(on: Boolean) { onAwake(on) }

    /** "#rrggbb" under the status bar and under the navigation bar; icons flip for contrast. */
    @JavascriptInterface
    fun setBars(top: String, bottom: String) {
        try { onBars(android.graphics.Color.parseColor(top), android.graphics.Color.parseColor(bottom)) } catch (_: Exception) {}
    }

    /** "ok" | "unenrolled" | "none": can this phone unlock with fingerprint / face right now? */
    @JavascriptInterface
    fun bioStatus(): String = try { bio() } catch (_: Exception) { "none" }

    /** Shows the system biometric sheet; the result comes back through window.kwBioDone(r). */
    @JavascriptInterface
    fun bioAuth(title: String) { bioPrompt(title) }

    /** Material You: the phone's wallpaper tonal palettes (Android 12+), as JSON {"a1":{"0":"#..",..},"a2":..,"a3":..,"n1":..,"n2":..}; "{}" before Android 12. */
    @JavascriptInterface
    fun dynamicColors(): String {
        if (Build.VERSION.SDK_INT < 31) return "{}"
        return try {
            val out = org.json.JSONObject()
            val tones = intArrayOf(0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000)
            for ((key, name) in listOf("a1" to "accent1", "a2" to "accent2", "a3" to "accent3", "n1" to "neutral1", "n2" to "neutral2")) {
                val o = org.json.JSONObject()
                for (tn in tones) {
                    val id = ctx.resources.getIdentifier("system_${name}_$tn", "color", "android")
                    if (id != 0) o.put(tn.toString(), String.format("#%06X", 0xFFFFFF and ctx.getColor(id)))
                }
                out.put(key, o)
            }
            out.toString()
        } catch (_: Exception) { "{}" }
    }

    /** Material You on/off: remembered natively so the next launch's system splash and window use the wallpaper colours too. */
    @JavascriptInterface
    fun setMd3(on: Boolean) {
        try { ctx.getSharedPreferences("kw", Context.MODE_PRIVATE).edit().putBoolean("md3", on).apply() } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= 33 && ctx is android.app.Activity) {
            try { ctx.splashScreen.setSplashScreenTheme(splashTheme()) } catch (_: Exception) {}
        }
    }

    private fun splashTheme(): Int {
        val p = ctx.getSharedPreferences("kw", Context.MODE_PRIVATE)
        return if (!p.getBoolean("md3", false)) 0 else if (p.getBoolean("dark", false)) R.style.Theme_Kwallet_Md3Dark else R.style.Theme_Kwallet_Md3
    }

    /** App theme light/dark: lets the next launch open on the dark Material You splash. */
    @JavascriptInterface
    fun setDark(dark: Boolean) {
        val p = ctx.getSharedPreferences("kw", Context.MODE_PRIVATE)
        if (p.getBoolean("dark", false) == dark) return
        try { p.edit().putBoolean("dark", dark).apply() } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= 33 && ctx is android.app.Activity) {
            try { ctx.splashScreen.setSplashScreenTheme(splashTheme()) } catch (_: Exception) {}
        }
    }

    /** "dark" | "light": the phone's own dark-theme setting. */
    @JavascriptInterface
    fun systemDark(): Boolean = (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

    /** System bar and cutout insets in CSS px: {"t":..,"r":..,"b":..,"l":..} */
    @JavascriptInterface
    fun insets(): String = insetsJson()

    /** "normal" | "vibrate" | "silent" — the page plays sounds only on "normal". */
    @JavascriptInterface
    fun ringerMode(): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return when (am.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> "normal"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            else -> "silent"
        }
    }

    /** Pattern JSON like "[20,50,20]" (on, off, on ms). */
    @JavascriptInterface
    fun vibrate(patternJson: String) {
        try {
            val arr = JSONArray(patternJson)
            val timings = LongArray(arr.length() + 1)
            for (i in 0 until arr.length()) timings[i + 1] = arr.getLong(i)
            val vib: Vibrator = if (Build.VERSION.SDK_INT >= 31)
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            else @Suppress("DEPRECATION") (ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
            if (!vib.hasVibrator()) return
            val effect = VibrationEffect.createWaveform(timings, -1)
            if (Build.VERSION.SDK_INT >= 33) {
                vib.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            }
        } catch (_: Exception) {}
    }

    /** Saves an export/backup into Download/Kwallet. Returns the folder shown to the user, or "" on failure. */
    @JavascriptInterface
    fun saveFile(name: String, mime: String, base64: String): String {
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safe)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Kwallet")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val r = ctx.contentResolver
                val uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return ""
                r.openOutputStream(uri)?.use { it.write(bytes) } ?: return ""
                cv.clear(); cv.put(MediaStore.Downloads.IS_PENDING, 0)
                r.update(uri, cv, null, null)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Kwallet")
                dir.mkdirs()
                File(dir, safe).writeBytes(bytes)
            }
            "Download/Kwallet"
        } catch (_: Exception) { "" }
    }
}
