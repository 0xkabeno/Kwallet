package app.hwallet

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * v1.4 (#55): the persistent activity cache. Every sent / received / pending transaction the live service or the
 * page sees is stored here (one row per wallet + row key), so the Activity tab opens from disk with no network
 * call, also after the app or the service was killed. Per stream it keeps a last-seen cursor (the newest tx hash
 * seen), which lets the service catch up on what happened while it was not running.
 * Rows are the page's own row objects (ch, net, hash, t, dir, key, s, d, raw, cp, st ... and k = the row key).
 */
class HwActDb private constructor(c: Context) : SQLiteOpenHelper(c.applicationContext, "hw_act.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE act (wid TEXT NOT NULL, k TEXT NOT NULL, ch TEXT, hash TEXT, t INTEGER, st TEXT, j TEXT, PRIMARY KEY (wid, k))")
        db.execSQL("CREATE INDEX act_t ON act (wid, t DESC)")
        db.execSQL("CREATE INDEX act_h ON act (wid, hash)")
        db.execSQL("CREATE TABLE cur (wid TEXT NOT NULL, sid TEXT NOT NULL, top TEXT, at INTEGER, PRIMARY KEY (wid, sid))")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        try { db.enableWriteAheadLogging() } catch (_: Throwable) {}
    }

    companion object {
        @Volatile private var inst: HwActDb? = null
        fun get(c: Context): HwActDb = inst ?: synchronized(this) { inst ?: HwActDb(c).also { inst = it } }
        /** set by the open activity: (wallet id, JSON array of new or changed rows) -> patch the Activity list in place */
        @Volatile var listener: ((String, String) -> Unit)? = null
        const val KEEP = 3000
    }

    /** newest rows first, as one JSON array string (read straight into the page) */
    fun rows(wid: String, limit: Int): String {
        val sb = StringBuilder(64 * 1024).append('[')
        try {
            readableDatabase.rawQuery("SELECT j FROM act WHERE wid=? ORDER BY t DESC LIMIT ?", arrayOf(wid, limit.coerceIn(1, KEEP).toString())).use { c ->
                var first = true
                while (c.moveToNext()) { if (!first) sb.append(','); sb.append(c.getString(0)); first = false }
            }
        } catch (_: Throwable) {}
        return sb.append(']').toString()
    }

    /** stores rows; returns the rows that are new or changed. A confirmed row replaces the pending row of the same tx,
     *  a pending row never overwrites a confirmed one. */
    fun put(wid: String, items: List<JSONObject>, notify: Boolean = true): JSONArray {
        val changed = JSONArray()
        if (wid.isEmpty() || items.isEmpty()) return changed
        try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                for (o in items) {
                    val k = o.optString("k"); if (k.isEmpty()) continue
                    val st = o.optString("st"); val hash = o.optString("hash"); val ch = o.optString("ch")
                    val j = o.toString()
                    var oldSt: String? = null; var oldJ: String? = null
                    db.rawQuery("SELECT st, j FROM act WHERE wid=? AND k=?", arrayOf(wid, k)).use { c -> if (c.moveToFirst()) { oldSt = c.getString(0); oldJ = c.getString(1) } }
                    if (oldJ == j) continue
                    if (oldSt != null && oldSt != "pending" && st == "pending") continue
                    if (st == "pending" && hash.isNotEmpty()) {
                        var done = false
                        db.rawQuery("SELECT 1 FROM act WHERE wid=? AND ch=? AND hash=? AND st<>'pending' LIMIT 1", arrayOf(wid, ch, hash)).use { c -> done = c.moveToFirst() }
                        if (done) continue
                    }
                    val cv = ContentValues()
                    cv.put("wid", wid); cv.put("k", k); cv.put("ch", ch); cv.put("hash", hash); cv.put("t", o.optLong("t")); cv.put("st", st); cv.put("j", j)
                    db.insertWithOnConflict("act", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                    if (st != "pending" && hash.isNotEmpty()) db.delete("act", "wid=? AND ch=? AND hash=? AND st='pending' AND k<>?", arrayOf(wid, ch, hash, k))
                    if (oldSt == null || oldSt != st) changed.put(o)
                }
                db.execSQL("DELETE FROM act WHERE wid=? AND k NOT IN (SELECT k FROM act WHERE wid=? ORDER BY t DESC LIMIT $KEEP)", arrayOf(wid, wid))
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        } catch (e: Throwable) { return changed }
        if (notify && changed.length() > 0) try { listener?.invoke(wid, changed.toString()) } catch (_: Throwable) {}
        return changed
    }

    /** chains that still have a pending send from Hwallet (the service then checks them every few seconds) */
    fun pendingChains(wid: String): Set<String> {
        val out = HashSet<String>()
        try {
            readableDatabase.rawQuery("SELECT DISTINCT ch, j FROM act WHERE wid=? AND st='pending'", arrayOf(wid)).use { c ->
                while (c.moveToNext()) {
                    val ch = c.getString(0) ?: continue
                    val net = try { JSONObject(c.getString(1)).optString("net") } catch (_: Throwable) { "" }
                    out.add(if (ch == "eth" && net.isNotEmpty()) net else ch)
                }
            }
        } catch (_: Throwable) {}
        return out
    }

    fun cursor(wid: String, sid: String): String? = try {
        readableDatabase.rawQuery("SELECT top FROM cur WHERE wid=? AND sid=?", arrayOf(wid, sid)).use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (_: Throwable) { null }

    fun setCursor(wid: String, sid: String, top: String) {
        try {
            val cv = ContentValues(); cv.put("wid", wid); cv.put("sid", sid); cv.put("top", top); cv.put("at", System.currentTimeMillis())
            writableDatabase.insertWithOnConflict("cur", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (_: Throwable) {}
    }

    /** when the service last wrote anything for this wallet (ms), 0 = never */
    fun lastAt(wid: String): Long = try {
        readableDatabase.rawQuery("SELECT MAX(at) FROM cur WHERE wid=?", arrayOf(wid)).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
    } catch (_: Throwable) { 0L }

    fun forget(wid: String) {
        try { writableDatabase.delete("act", "wid=?", arrayOf(wid)); writableDatabase.delete("cur", "wid=?", arrayOf(wid)) } catch (_: Throwable) {}
    }
}
