package app.hwallet

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.3 (#42): crash log. Any uncaught exception (activity, WebView bridge, the live service, the boot receiver)
 * is written to files/crash.txt before the process dies, and the reasons Android itself recorded for earlier
 * process deaths (ApplicationExitInfo: crashes, ANRs, native crashes, "did not start foreground in time")
 * are added on the next start. Settings > About > Crash log shows it with Copy.
 */
class HwApp : Application() {
    override fun onCreate() {
        super.onCreate()
        install(this)
        try { HwNet.load(this) } catch (_: Throwable) {}
    }

    companion object {
        private const val MAX = 64 * 1024
        fun file(c: Context) = File(c.filesDir, "crash.txt")
        private fun stamp(t: Long = System.currentTimeMillis()) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(t))
        private fun device() = "Hwallet " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ") · Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ") · " + Build.MANUFACTURER + " " + Build.MODEL

        fun write(c: Context, title: String, body: String, at: Long = System.currentTimeMillis()) {
            try {
                val f = file(c)
                val old = if (f.exists()) f.readText() else ""
                val entry = "time: " + stamp(at) + "\n" + title + "\n" + device() + "\n" + body.trim() + "\n"
                f.writeText((entry + (if (old.isNotEmpty()) "\n----\n" + old else "")).take(MAX))
                /* v1.5 (#64): a real crash or ANR (not a note) asks once on the next launch */
                if (!title.startsWith("note:")) c.getSharedPreferences("hw_crash", Context.MODE_PRIVATE).edit().putLong("pending", at).commit()
            } catch (_: Throwable) {}
        }

        fun install(app: Application) {
            val prev = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { th, e ->
                try {
                    val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                    write(app, "thread: " + th.name, sw.toString())
                } catch (_: Throwable) {}
                prev?.uncaughtException(th, e)
            }
            /* what Android saw when earlier processes died (also catches deaths no Java handler sees) */
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    val p = app.getSharedPreferences("hw_crash", Context.MODE_PRIVATE)
                    val seen = p.getLong("exitSeen", 0L)
                    val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    var newest = seen
                    for (x in am.getHistoricalProcessExitReasons(app.packageName, 0, 8)) {
                        if (x.timestamp <= seen) continue
                        if (x.timestamp > newest) newest = x.timestamp
                        val bad = x.reason == android.app.ApplicationExitInfo.REASON_CRASH || x.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE ||
                            x.reason == android.app.ApplicationExitInfo.REASON_ANR || x.reason == android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE
                        if (!bad) continue
                        var trace = ""
                        if (x.reason == android.app.ApplicationExitInfo.REASON_ANR) {
                            try { trace = x.traceInputStream?.bufferedReader()?.use { it.readText().take(12000) } ?: "" } catch (_: Throwable) {}
                        }
                        val kind = when (x.reason) { android.app.ApplicationExitInfo.REASON_ANR -> "ANR"; android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"; android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "start-up failure"; else -> "crash" }
                        /* a Java crash already wrote its full stack; add Android's own record only when it says more */
                        val already = try { file(app).readText().contains("time: " + stamp(x.timestamp).take(16)) } catch (_: Throwable) { false }
                        if (!already || trace.isNotEmpty()) write(app, "system record: " + kind + " in " + x.processName, (x.description ?: "") + (if (trace.isNotEmpty()) "\n" + trace else ""), x.timestamp)
                    }
                    if (newest > seen) p.edit().putLong("exitSeen", newest).apply()
                } catch (_: Throwable) {}
            }
        }

        /** v1.5 (#64): the newest crash entry when it has not been prompted yet, else "" */
        fun pending(c: Context): String = try {
            val p = c.getSharedPreferences("hw_crash", Context.MODE_PRIVATE)
            if (p.getLong("pending", 0L) > p.getLong("acked", 0L)) read(c).substringBefore("\n----\n").ifEmpty { read(c) } else ""
        } catch (_: Throwable) { "" }
        fun ack(c: Context) { try { val p = c.getSharedPreferences("hw_crash", Context.MODE_PRIVATE); p.edit().putLong("acked", p.getLong("pending", 0L)).apply() } catch (_: Throwable) {} }

        fun read(c: Context): String = try { val f = file(c); if (f.exists()) f.readText() else "" } catch (_: Throwable) { "" }
        fun clear(c: Context) { try { file(c).delete() } catch (_: Throwable) {} }
    }
}
