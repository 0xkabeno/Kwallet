package app.kwallet

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * v3.98.32: what Hwallet may know about this Kwallet, so it never keeps wallets of seeds Kwallet no longer holds.
 *
 *  - install id: 128 random bits made on first use and kept in Kwallet's own SharedPreferences. Android's "Clear data" and an
 *    uninstall delete it; an in-app erase replaces it. Hwallet stores it at pairing time: a different id later means this
 *    Kwallet was reset, so Hwallet erases what it paired.
 *  - seed mirror: the public seed IDs (10 digits, a hash of a public address) of every seed paired with Hwallet. Kwallet's page
 *    writes it through HarkNative.kwMirror whenever its pairings change and when it starts. No PIN, no keys, no addresses.
 *
 * Hwallet reads both through [KwLinkProvider] (exported, signature permission, plus a caller uid + signature check). Kwallet
 * also tells Hwallet at once when seeds go or Kwallet is erased: an explicit broadcast to Hwallet's KwEventReceiver that only
 * an app signed with the Kwallet key can send or receive.
 */
object KwLinkState {
    const val HWALLET = "app.hwallet"
    const val PERM = "app.kwallet.permission.SIGN_FEED"
    const val EVENT = "app.hwallet.KW_EVENT"
    private const val PREF = "kw_link"
    private val SID = Regex("^\\d{10}$")
    private val lock = Any()

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private fun newId(): String { val b = ByteArray(16); SecureRandom().nextBytes(b); return b.joinToString("") { "%02x".format(it) } }

    fun installId(c: Context): String = synchronized(lock) {
        val p = prefs(c)
        p.getString("kid", null)?.takeIf { it.length == 32 } ?: newId().also { p.edit().putString("kid", it).commit() }
    }

    /** the stored mirror: {sids:[...], partial, at}; null until Kwallet's page wrote one */
    fun mirror(c: Context): JSONObject? = try { prefs(c).getString("mirror", null)?.let { JSONObject(it) } } catch (_: Exception) { null }

    private fun sidsOf(o: JSONObject?): LinkedHashSet<String> {
        val out = LinkedHashSet<String>(); val a = o?.optJSONArray("sids") ?: return out
        for (i in 0 until a.length()) { val s = a.optString(i); if (SID.matches(s)) out.add(s) }
        return out
    }

    /** from the page: {"sids":["4821093752",...],"partial":false}. Seeds that left the mirror are announced to Hwallet. */
    fun setMirror(c: Context, json: String) {
        if (json.length > 64_000) return
        val inp = try { JSONObject(json) } catch (_: Exception) { return }
        val gone: List<String>
        synchronized(lock) {
            val old = mirror(c)
            val now = sidsOf(inp)
            val o = JSONObject().put("sids", JSONArray(now.toList())).put("partial", inp.optBoolean("partial", false)).put("at", System.currentTimeMillis())
            prefs(c).edit().putString("mirror", o.toString()).commit()
            gone = if (old == null) emptyList() else sidsOf(old).filter { it !in now }
        }
        if (gone.isNotEmpty()) notifyH(c, "gone", gone)
    }

    /** an in-app erase (Clear > Erase everything, the forgotten-PIN erase, Export + erase): new install id, empty mirror,
     *  and Hwallet is told to erase every wallet it paired with this Kwallet */
    fun wiped(c: Context) {
        val old: List<String>
        synchronized(lock) {
            old = sidsOf(mirror(c)).toList()
            prefs(c).edit().putString("kid", newId()).putString("mirror", JSONObject().put("sids", JSONArray()).put("partial", false).put("at", System.currentTimeMillis()).toString()).commit()
        }
        notifyH(c, "wipe", old)
    }

    private fun hwalletOk(c: Context): Boolean = try {
        c.packageManager.getPackageInfo(HWALLET, 0)
        c.packageManager.checkSignatures(c.packageName, HWALLET) == PackageManager.SIGNATURE_MATCH
    } catch (_: Exception) { false }

    /** explicit broadcast to Hwallet only (component + package), receivable only with the signature permission */
    fun notifyH(c: Context, ev: String, sids: List<String>) {
        if (!hwalletOk(c)) return
        try {
            val i = Intent(EVENT).setClassName(HWALLET, "$HWALLET.KwEventReceiver")
                .putExtra("ev", ev).putExtra("sids", sids.filter { SID.matches(it) }.take(500).toTypedArray()).putExtra("at", System.currentTimeMillis())
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
            c.applicationContext.sendBroadcast(i, PERM)
            Log.i("KwLink", "told Hwallet: $ev (${sids.size})")
        } catch (e: Exception) { Log.w("KwLink", "notify failed: $e") }
    }
}

/**
 * v3.98.32: Hwallet asks this provider (ContentResolver.call "state") every time it starts or comes back to the front.
 * Exported, but the manifest requires the signature permission and every call checks that the caller's uid is Hwallet's and
 * that Hwallet is signed with Kwallet's key. The answer holds only the install id and public seed IDs.
 */
class KwLinkProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    private fun callerOk(c: Context): Boolean = try {
        val uid = Binder.getCallingUid()
        (c.packageManager.getPackagesForUid(uid) ?: emptyArray()).any { it == KwLinkState.HWALLET } &&
            c.packageManager.checkSignatures(c.packageName, KwLinkState.HWALLET) == PackageManager.SIGNATURE_MATCH
    } catch (_: Exception) { false }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val c = context ?: return null
        if (!callerOk(c)) throw SecurityException("Kwallet link: caller is not Hwallet")
        if (method != "state") return null
        val m = KwLinkState.mirror(c)
        val sids = ArrayList<String>()
        m?.optJSONArray("sids")?.let { a -> for (i in 0 until a.length()) sids.add(a.optString(i)) }
        return Bundle().apply {
            putInt("v", 1)
            putString("kid", KwLinkState.installId(c))
            putBoolean("ready", m != null)
            putBoolean("partial", m?.optBoolean("partial", false) ?: false)
            putStringArray("sids", sids.toTypedArray())
        }
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<out String>?): Int = 0
}
