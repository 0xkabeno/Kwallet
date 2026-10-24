package app.hwallet

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.9.19-dbg1: freeze catcher (test build only, never released).
 * The activity pings the page every second; the page answers through HwNative.pong. When no answer comes for 3 s
 * while Hwallet is in front, every thread's stack, the network pool, the Kwallet handoff state and the VPN state are
 * saved to filesDir/hw_stall.txt (last 3 dumps) and to logcat (tag HwStall). The page's long tasks and slow bridge calls,
 * and each step of a Kwallet sign handoff, are kept in memory for the Freeze report. Nothing secret is logged:
 * no request payloads, no keys, no addresses.
 */
object HwStall {
    private const val FILE = "hw_stall.txt"
    private const val SEP = "\n=========== HWSTALL ===========\n"
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val js = ArrayDeque<String>()
    private val kw = ArrayDeque<String>()
    @Volatile var lastPong = 0L
    @Volatile var mainBeat = 0L
    @Volatile var pingSeq = 0
    @Volatile var dumpedFor = -1L

    private fun now(): String = synchronized(fmt) { fmt.format(Date()) }

    fun jsLog(s: String) { synchronized(js) { js.addLast(now() + " " + s.take(600)); while (js.size > 40) js.removeFirst() } }

    /** One step of a Kwallet sign handoff (ids and status words only) */
    fun kw(step: String) {
        val l = now() + " +" + SystemClock.uptimeMillis() + " " + step.take(300)
        synchronized(kw) { kw.addLast(l); while (kw.size > 80) kw.removeFirst() }
        Log.i("HwKw", step.take(300))
    }

    fun jsLines(): String = synchronized(js) { if (js.isEmpty()) "(none)" else js.joinToString("\n") }
    fun kwLines(): String = synchronized(kw) { if (kw.isEmpty()) "(none)" else kw.joinToString("\n") }

    /** Every thread's stack; the main, JavaBridge, hw-kw and network threads first, with deeper stacks */
    fun threads(): String {
        val all = try { Thread.getAllStackTraces() } catch (e: Throwable) { return "threads: $e" }
        fun rank(t: Thread): Int { val n = t.name; return when {
            n == "main" -> 0; n.contains("JavaBridge") -> 1; n == "hw-kw" -> 2; n.startsWith("hw-net") -> 3; else -> 4 } }
        val sb = StringBuilder()
        val busyNet = all.entries.count { it.key.name.startsWith("hw-net") && it.key.state == Thread.State.RUNNABLE }
        sb.append("threads: ").append(all.size).append(" · hw-net RUNNABLE: ").append(busyNet).append('\n')
        for ((t, st) in all.entries.sortedWith(compareBy({ rank(it.key) }, { it.key.name }))) {
            val r = rank(t)
            val depth = when (r) { 0, 1, 2 -> 60; 3 -> 25; else -> 10 }
            sb.append("\n--- \"").append(t.name).append("\" ").append(t.state).append(" prio=").append(t.priority).append('\n')
            for (f in st.take(depth)) sb.append("    at ").append(f).append('\n')
            if (st.size > depth) sb.append("    … ").append(st.size - depth).append(" more\n")
        }
        return sb.toString()
    }

    /** Saves a dump (keeps the last 3) and writes it to logcat */
    fun save(ctx: Context, why: String, state: String) {
        val text = StringBuilder()
            .append("Freeze dump · ").append(Date()).append(" · Hwallet ").append(BuildConfig.VERSION_NAME).append('\n')
            .append("reason: ").append(why).append('\n')
            .append(state).append('\n')
            .append("\n## Kwallet handoff steps\n").append(kwLines()).append('\n')
            .append("\n## Page long tasks / slow bridge calls\n").append(jsLines()).append('\n')
            .append("\n## Threads\n").append(threads()).toString()
        try {
            val f = File(ctx.filesDir, FILE)
            val old = if (f.exists()) f.readText().split(SEP).filter { it.isNotBlank() } else emptyList()
            f.writeText((old.takeLast(2) + text).joinToString(SEP, prefix = SEP))
        } catch (e: Throwable) { Log.w("HwStall", "save failed: $e") }
        text.chunked(3500).forEach { Log.e("HwStall", it) }
    }

    /** Saved dumps, newest first */
    fun saved(ctx: Context): String = try {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) "(no freeze recorded yet)" else f.readText().split(SEP).filter { it.isNotBlank() }.reversed().joinToString("\n\n==========\n\n")
    } catch (e: Throwable) { "read failed: $e" }
}
