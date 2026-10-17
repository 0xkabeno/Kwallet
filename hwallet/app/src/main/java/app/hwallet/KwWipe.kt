package app.hwallet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * v1.8.34: Hwallet never keeps the wallets of seeds Kwallet no longer holds.
 *
 * Paths that erase (and only these; Kwallet being locked, stopped or killed changes nothing):
 *  1. Android "Clear data" / "Clear storage" of Kwallet, or Kwallet uninstalled: PACKAGE_DATA_CLEARED / PACKAGE_FULLY_REMOVED
 *     ([KwPkgReceiver], both allowed for manifest receivers since Android 8, Kwallet visible through <queries>).
 *  2. Kwallet erased in the app, or a paired seed removed there: Kwallet's explicit broadcast ([KwEventReceiver], signature
 *     permission).
 *  3. Backstop for anything missed (Hwallet force-stopped, phone off): on every start and resume the page asks Kwallet's
 *     KwLinkProvider for {install id, seed IDs} ([query]). Another install id or a missing seed erases; Kwallet installed but
 *     not answering erases nothing and is asked again later.
 *
 * WebView storage cannot be edited while the page is not running, so 1 and 2 leave a "purge pending" marker that the page
 * applies synchronously at start, before it reads or draws any wallet, and erase the native copies (Monero view-key wallet
 * files, activity cache, live-service watch list, Kwallet handoff files) at once, here. If the page is up it is told to
 * apply the marker now ([onPurge]).
 */
object KwWipe {
    const val KWALLET = "app.kwallet"
    const val AUTH = "app.kwallet.link"
    private const val PREF = "hw_kwlink"
    private val SID = Regex("^\\d{10}$")
    private val lock = Any()
    private val io = Executors.newCachedThreadPool { r -> Thread(r, "hwkwlink").apply { isDaemon = true } }

    /** set by the open activity: apply the pending purge in the page now */
    @Volatile var onPurge: (() -> Unit)? = null

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /* ---------- the pending marker: {at, all, why, sids[]} ---------- */
    fun pending(c: Context): String = try { prefs(c).getString("pend", "") ?: "" } catch (_: Throwable) { "" }

    /** the page applied the marker it read (same at): clear it; a newer mark stays */
    fun done(c: Context, at: Long) {
        synchronized(lock) {
            val p = try { JSONObject(pending(c).ifEmpty { "{}" }) } catch (_: Throwable) { JSONObject() }
            if (p.optLong("at") == at) prefs(c).edit().remove("pend").commit()
        }
    }

    /** why: cleared | removed | wipe | gone */
    fun mark(c: Context, all: Boolean, sids: Collection<String>, why: String) {
        val clean = sids.filter { SID.matches(it) }
        if (!all && clean.isEmpty()) return
        synchronized(lock) {
            val p = try { JSONObject(pending(c).ifEmpty { "{}" }) } catch (_: Throwable) { JSONObject() }
            val set = LinkedHashSet<String>(); p.optJSONArray("sids")?.let { a -> for (i in 0 until a.length()) set.add(a.optString(i)) }
            set.addAll(clean)
            val wasAll = p.optBoolean("all")
            val o = JSONObject().put("at", System.currentTimeMillis()).put("all", all || wasAll)
                .put("why", if (all) why else if (wasAll) p.optString("why", why) else why).put("sids", JSONArray(set.toList()))
            prefs(c).edit().putString("pend", o.toString()).commit()   // commit: a receiver's process may die right after
        }
        try { eraseNative(c, all, clean) } catch (e: Throwable) { HwApp.write(c, "note: kwallet wipe (native) failed", e.toString()) }
        try { onPurge?.invoke() } catch (_: Throwable) {}
    }

    /* ---------- which wallets came from Kwallet (written by the page whenever its wallets change) ----------
       [{id, sid, kid, x:[Monero primary / address]}], Kwallet-paired wallets only; watch-only wallets added by hand are not in it */
    fun setMap(c: Context, json: String) {
        if (json.length > 400_000) return
        try { JSONArray(json); prefs(c).edit().putString("map", json).apply() } catch (_: Throwable) {}
    }
    private fun map(c: Context): JSONArray? = try { prefs(c).getString("map", null)?.let { JSONArray(it) } } catch (_: Throwable) { null }

    /** the native copies of Kwallet-derived wallets: all of them, or those of the given seeds */
    fun eraseNative(c: Context, all: Boolean, sids: Collection<String>) {
        val m = map(c)
        val ids = HashSet<String>(); val xmr = HashSet<String>()
        if (m != null) for (i in 0 until m.length()) {
            val w = m.optJSONObject(i) ?: continue
            if (!all && w.optString("sid") !in sids) continue
            ids.add(w.optString("id"))
            w.optJSONArray("x")?.let { a -> for (j in 0 until a.length()) a.optString(j).takeIf { it.isNotEmpty() }?.let { xmr.add(it) } }
        }
        /* no map yet (Hwallet updated and not opened since): a full erase cannot tell Kwallet's Monero wallet from a hand-added
           one, so the native Monero sync data goes (a watch-only Monero wallet simply scans again; its keys stay in the page) */
        eraseIds(c, ids, xmr, all && m == null)
    }

    /** also used by the page after a live purge (HwNative.kwErased) */
    fun eraseIds(c: Context, ids: Set<String>, xmr: Set<String>, anyXmr: Boolean) {
        val app = c.applicationContext
        if (anyXmr || xmr.isNotEmpty()) HwXmrN.forgetFor(app, xmr, anyXmr)
        val db = try { HwActDb.get(app) } catch (_: Throwable) { null }
        ids.forEach { id -> if (id.isNotEmpty()) try { db?.forget(id) } catch (_: Throwable) {} }
        /* the always-on service watches one wallet: stop it when that wallet is erased (the page restarts it for what is left) */
        try {
            val lp = app.getSharedPreferences(HwLiveService.PREF, Context.MODE_PRIVATE)
            val cfg = lp.getString("cfg", null)?.let { JSONObject(it) }
            if (cfg != null && (anyXmr || cfg.optString("wid") in ids)) {
                cfg.put("on", false).put("watch", JSONArray()).put("wid", "").put("wallet", "")
                lp.edit().putString("cfg", cfg.toString()).remove("snap").commit()
                HwLiveService.stop(app)
            }
        } catch (_: Throwable) {}
        /* Kwallet handoff files (requests / answers in flight) */
        try { KwxProvider.dir(app).listFiles()?.forEach { it.delete() } } catch (_: Throwable) {}
    }

    /* ---------- the backstop: ask Kwallet ---------- */
    fun kwInstalled(c: Context): Boolean = try { c.packageManager.getPackageInfo(KWALLET, 0); true } catch (_: PackageManager.NameNotFoundException) { false } catch (_: Throwable) { true }

    /** {st: ok | absent | unreachable, kid, ready, partial, sids[]}; never throws, never blocks longer than [ms] */
    fun query(c: Context, ms: Long = 4000L): JSONObject {
        val app = c.applicationContext
        if (!kwInstalled(app)) {
            /* a second look a moment later: never act on a package that is only being replaced */
            try { Thread.sleep(1500) } catch (_: InterruptedException) {}
            if (!kwInstalled(app)) return JSONObject().put("st", "absent")
        }
        val pm = app.packageManager
        val signed = try { pm.checkSignatures(app.packageName, KWALLET) == PackageManager.SIGNATURE_MATCH } catch (_: Throwable) { false }
        val owner = try { pm.resolveContentProvider(AUTH, 0)?.packageName } catch (_: Throwable) { null }
        /* an older Kwallet (no link yet) or a package that is not ours: no answer, so nothing is erased */
        if (!signed || owner != KWALLET) return JSONObject().put("st", "unreachable").put("why", if (!signed) "signature" else "old")
        val f = io.submit<JSONObject> {
            val cl = app.contentResolver.acquireUnstableContentProviderClient(Uri.parse("content://$AUTH"))
                ?: return@submit JSONObject().put("st", "unreachable").put("why", "client")
            try {
                val b = cl.call("state", null, null) ?: return@submit JSONObject().put("st", "unreachable").put("why", "empty")
                val kid = b.getString("kid") ?: ""
                if (kid.length != 32) return@submit JSONObject().put("st", "unreachable").put("why", "kid")
                JSONObject().put("st", "ok").put("kid", kid).put("ready", b.getBoolean("ready", false)).put("partial", b.getBoolean("partial", false))
                    .put("sids", JSONArray((b.getStringArray("sids") ?: emptyArray()).filter { SID.matches(it) }))
            } finally { try { cl.close() } catch (_: Throwable) {} }
        }
        return try { f.get(ms, TimeUnit.MILLISECONDS) } catch (e: Throwable) { f.cancel(true); JSONObject().put("st", "unreachable").put("why", e.javaClass.simpleName) }
    }
}

/** Kwallet's data cleared (Settings > Storage > Clear data) or Kwallet uninstalled. Only the system can send these. */
class KwPkgReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        try {
            val a = i.action ?: return
            if (a != Intent.ACTION_PACKAGE_DATA_CLEARED && a != Intent.ACTION_PACKAGE_FULLY_REMOVED) return
            if (i.data?.schemeSpecificPart != KwWipe.KWALLET) return
            val r = goAsync()
            Thread {
                try { KwWipe.mark(c, true, emptyList(), if (a == Intent.ACTION_PACKAGE_DATA_CLEARED) "cleared" else "removed") }
                catch (e: Throwable) { HwApp.write(c, "note: kwallet wipe receiver failed", e.toString()) }
                finally { r.finish() }
            }.start()
        } catch (e: Throwable) { HwApp.write(c, "note: kwallet wipe receiver failed", e.toString()) }
    }
}

/** Kwallet: "wipe" (erased in the app) or "gone" (paired seeds removed). Signature permission: only Kwallet can send it. */
class KwEventReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        try {
            if (i.action != "app.hwallet.KW_EVENT") return
            val ev = i.getStringExtra("ev") ?: return
            val sids = (i.getStringArrayExtra("sids") ?: emptyArray()).toList()
            val r = goAsync()
            Thread {
                try {
                    when (ev) {
                        "wipe" -> KwWipe.mark(c, true, sids, "wipe")
                        "gone" -> KwWipe.mark(c, false, sids, "gone")
                    }
                } catch (e: Throwable) { HwApp.write(c, "note: kwallet event failed", e.toString()) }
                finally { r.finish() }
            }.start()
        } catch (e: Throwable) { HwApp.write(c, "note: kwallet event failed", e.toString()) }
    }
}
