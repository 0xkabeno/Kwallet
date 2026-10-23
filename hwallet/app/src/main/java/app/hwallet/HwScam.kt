package app.hwallet

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v1.9.14: deceptive-site warning for the Browser tab (top-level navigations only).
 *
 * Lists (downloaded, never bundled; refreshed at most once a day while the warning is on):
 *  - MetaMask eth-phishing-detect config.json: blacklist, fuzzylist, whitelist, tolerance (DBAD licence / MIT)
 *  - ScamSniffer scam-database blacklist/domains.json (GPL-3.0)
 * Both are stream-parsed with JsonReader (config.json is several MB) and kept in filesDir/scam as sorted 64-bit hashes
 * (bl.bin = MetaMask blacklist + ScamSniffer, wl.bin = whitelist) plus fuzzy.txt (the few hundred fuzzylist names).
 *
 * check(host): the host and every parent domain; a whitelisted name wins, then the blacklists, then the fuzzylist
 * (Levenshtein distance <= tolerance between the names without their top-level domain, as eth-phishing-detect does; an exact
 * match is the real site and never flagged).
 * Nothing is fetched or checked while the warning is off (Browser settings), and hosts the user chose to continue to are
 * remembered for this app session only.
 */
object HwScam {
    private const val TAG = "HwScam"
    private const val DAY = 24L * 3600 * 1000
    const val MM = "https://raw.githubusercontent.com/MetaMask/eth-phishing-detect/main/src/config.json"
    const val SS = "https://raw.githubusercontent.com/scamsniffer/scam-database/main/blacklist/domains.json"

    @Volatile private var bl = LongArray(0)
    @Volatile private var wl = LongArray(0)
    @Volatile private var fuzzy: List<String> = emptyList()
    @Volatile private var tol = 3
    @Volatile private var ready = false
    private val loading = AtomicBoolean(false)
    private val allowed: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private lateinit var dir: File
    private lateinit var prefs: android.content.SharedPreferences

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.getSharedPreferences("hwscam", Context.MODE_PRIVATE)
        dir = File(ctx.filesDir, "scam").apply { mkdirs() }
        if (on()) warm()
    }

    fun on(): Boolean = ::prefs.isInitialized && prefs.getBoolean("on", true)
    fun setOn(v: Boolean) { if (!::prefs.isInitialized) return; prefs.edit().putBoolean("on", v).apply(); if (v) warm() }
    fun allow(host: String) { val h = norm(host); if (h.isNotEmpty()) allowed.add(h) }
    fun isAllowed(host: String): Boolean = allowed.contains(norm(host))

    fun state(): String = org.json.JSONObject().put("on", on()).put("ready", ready).put("loading", loading.get())
        .put("updated", if (::prefs.isInitialized) prefs.getLong("upd", 0L) else 0L).put("n", bl.size).put("fuzzy", fuzzy.size).toString()

    /** load the cached hashes now; download when they are missing or older than a day */
    fun warm() {
        if (!::prefs.isInitialized || !on()) return
        val stale = System.currentTimeMillis() - prefs.getLong("upd", 0L) > DAY
        if (!ready || stale) reload(stale)
    }
    fun update() { if (::prefs.isInitialized && on()) reload(true) }

    private fun reload(fetch: Boolean) {
        if (!loading.compareAndSet(false, true)) return
        Thread {
            try {
                if (!ready) loadFiles()
                if (fetch && on()) download()
            } catch (e: Throwable) { Log.w(TAG, "scam lists", e) } finally { loading.set(false) }
        }.apply { priority = Thread.MIN_PRIORITY; isDaemon = true }.start()
    }

    private fun client() = HwNet.withTimeout(60000)
    private fun req(u: String) = okhttp3.Request.Builder().url(u).header("User-Agent", "Hwallet/" + BuildConfig.VERSION_NAME).build()

    private fun download() {
        val black = HashSet<Long>(); val white = HashSet<Long>(); val fz = ArrayList<String>(); var t = 3
        var okMm = false; var okSs = false
        try {
            client().newCall(req(MM)).execute().use { r ->
                val b = r.body
                if (r.isSuccessful && b != null) JsonReader(b.charStream()).use { j ->
                    j.beginObject()
                    while (j.hasNext()) {
                        when (j.nextName()) {
                            "blacklist" -> strings(j) { black.add(h64(it)) }
                            "whitelist" -> strings(j) { white.add(h64(it)) }
                            "fuzzylist" -> strings(j) { if (fz.size < 5000) fz.add(it) }
                            "tolerance" -> if (j.peek() == JsonToken.NUMBER) t = j.nextInt().coerceIn(0, 5) else j.skipValue()
                            else -> j.skipValue()
                        }
                    }
                    j.endObject(); okMm = true
                }
            }
        } catch (e: Throwable) { Log.w(TAG, "MetaMask list not updated", e) }
        try {
            client().newCall(req(SS)).execute().use { r ->
                val b = r.body
                if (r.isSuccessful && b != null) JsonReader(b.charStream()).use { j -> strings(j) { black.add(h64(it)) }; okSs = true }
            }
        } catch (e: Throwable) { Log.w(TAG, "ScamSniffer list not updated", e) }
        if (!okMm && !okSs) return
        // a list that failed keeps its last copy: merge the old hashes when only one of the two downloaded
        if (!(okMm && okSs)) { bl.forEach { black.add(it) }; if (!okMm) { wl.forEach { white.add(it) }; fz.addAll(fuzzy); t = tol } }
        val b2 = black.toLongArray().also { it.sort() }; val w2 = white.toLongArray().also { it.sort() }
        writeLongs(File(dir, "bl.bin"), b2); writeLongs(File(dir, "wl.bin"), w2)
        File(dir, "fuzzy.tmp").writeText(fz.joinToString("\n")); File(dir, "fuzzy.tmp").renameTo(File(dir, "fuzzy.txt"))
        prefs.edit().putLong("upd", System.currentTimeMillis()).putInt("tol", t).apply()
        synchronized(this) { bl = b2; wl = w2; fuzzy = fz.toList(); tol = t; ready = true }
    }

    private inline fun strings(j: JsonReader, f: (String) -> Unit) {
        if (j.peek() != JsonToken.BEGIN_ARRAY) { j.skipValue(); return }
        j.beginArray()
        while (j.hasNext()) { if (j.peek() == JsonToken.STRING) { val s = norm(j.nextString()); if (s.isNotEmpty()) f(s) } else j.skipValue() }
        j.endArray()
    }

    private fun loadFiles() {
        val b = readLongs(File(dir, "bl.bin")); val w = readLongs(File(dir, "wl.bin"))
        val fz = try { File(dir, "fuzzy.txt").takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() } ?: emptyList() } catch (_: Throwable) { emptyList() }
        synchronized(this) { bl = b; wl = w; fuzzy = fz; tol = prefs.getInt("tol", 3); ready = b.isNotEmpty() || fz.isNotEmpty() }
    }

    private fun writeLongs(f: File, a: LongArray) {
        val tmp = File(f.path + ".tmp")
        DataOutputStream(BufferedOutputStream(FileOutputStream(tmp), 1 shl 16)).use { o -> o.writeInt(a.size); a.forEach { o.writeLong(it) } }
        tmp.renameTo(f)
    }
    private fun readLongs(f: File): LongArray = try {
        if (!f.exists()) LongArray(0) else DataInputStream(BufferedInputStream(FileInputStream(f), 1 shl 16)).use { i ->
            val n = i.readInt(); if (n < 0 || n > 5_000_000) LongArray(0) else LongArray(n) { i.readLong() }
        }
    } catch (_: Throwable) { LongArray(0) }

    /** FNV-1a 64 over the lowercase name: collisions are negligible at a few hundred thousand names */
    private fun h64(s: String): Long {
        var h = -0x340d631b7bdddcdbL
        for (c in s) { h = h xor (c.code.toLong() and 0xffff); h *= 0x100000001b3L }
        return h
    }
    private fun norm(s: String): String = s.trim().lowercase().removePrefix("*.").trimEnd('.')

    private fun parents(h: String): List<String> {
        val out = ArrayList<String>(4); var x = h
        while (true) { out.add(x); val i = x.indexOf('.'); if (i < 0) break; x = x.substring(i + 1); if (!x.contains('.')) break }
        return out
    }
    private fun noTld(h: String): String { val i = h.lastIndexOf('.'); return if (i <= 0) h else h.substring(0, i) }
    private fun lev(a: String, b: String, max: Int): Int {
        if (Math.abs(a.length - b.length) > max) return max + 1
        var prev = IntArray(b.length + 1) { it }; var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i; var best = cur[0]
            for (j in 1..b.length) { cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1); if (cur[j] < best) best = cur[j] }
            if (best > max) return max + 1
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** null = fine; otherwise why it is flagged: "metamask+scamsniffer" (blacklist) or "fuzzy:<the real site it imitates>" */
    fun check(host0: String?): String? {
        if (!ready || !on()) return null
        val host = norm(host0 ?: return null); if (host.isEmpty() || !host.contains('.')) return null
        if (allowed.contains(host)) return null
        val ps = parents(host)
        val b = bl; val w = wl
        if (ps.any { java.util.Arrays.binarySearch(w, h64(it)) >= 0 }) return null
        if (ps.any { java.util.Arrays.binarySearch(b, h64(it)) >= 0 }) return "blacklist"
        // eth-phishing-detect's fuzzy form: the name without its top-level domain ("app.metamask.io" -> "app.metamask")
        val form = noTld(host); val t = tol
        if (t > 0 && form.length >= 3) for (f in fuzzy) {
            val ff = noTld(f); if (ff.isEmpty() || ff == form) continue   // an exact match is the real site
            if (lev(form, ff, t) <= t) return "fuzzy:$f"
        }
        return null
    }
}
