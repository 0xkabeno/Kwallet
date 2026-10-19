package app.kwallet

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** v3.98.38: crash reports stay on the phone. An uncaught exception is written to filesDir/crash.txt (stack trace,
 *  app version, Android version, device model; never page data, keys or seeds) and the next launch offers to copy it.
 *  Kwallet has no internet permission, so nothing is ever sent anywhere. */
object KwCrash {
    private const val MAX = 32 * 1024
    private fun file(c: Context) = File(c.filesDir, "crash.txt")

    fun install(c: Context) {
        val app = c.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        if (prev is Handler) return
        Thread.setDefaultUncaughtExceptionHandler(Handler(app, prev))
    }

    private class Handler(val app: Context, val prev: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            try {
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                val head = "time: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) + "\n" +
                    "Kwallet " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ") · Android " + Build.VERSION.RELEASE +
                    " (API " + Build.VERSION.SDK_INT + ") · " + Build.MANUFACTURER + " " + Build.MODEL + "\nthread: " + t.name + "\n"
                file(app).writeText((head + sw.toString()).take(MAX))
            } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }

    fun read(c: Context): String = try { val f = file(c); if (f.exists()) f.readText().take(MAX) else "" } catch (_: Throwable) { "" }
    fun clear(c: Context) { try { file(c).delete() } catch (_: Throwable) {} }
}
