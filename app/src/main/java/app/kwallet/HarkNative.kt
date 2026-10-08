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
