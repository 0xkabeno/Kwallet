package app.kwallet

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * v3.99.50: Kwallet is TextAuth's crypto engine. TextAuth never receives a private key any more: it sends requests over
 * the pinned K <-> TextAuth link (KwTaLinkService SVC_ENGINE, one batched JSON call) and gets results only.
 *
 * Process: Kwallet's own process. The whole app has no INTERNET permission (the manifest removes it and CI checks the APK).
 * An isolatedProcess service was not used: an isolated process runs under a separate uid that can't open Kwallet's
 * Keystore keys or its files, so it could not hold the wrapped keys.
 *
 * Storage: per address ("<seed id>.<address #>") one file in no-backup storage: RSA-OAEP(Keystore) wrapped AES key |
 * AES-GCM(that key, JSON {m, i, n} = vault master, vault ICP identity key, Nostr key, AAD = the slot name). The Keystore RSA
 * key (StrongBox when the phone has one) decrypts only after the user's biometric / screen lock (30 s window), so the stored
 * keys can't be read without the user. Wrapping (at pairing) needs no auth, which is why the outer key is RSA.
 * A phone with no screen lock gets the same key without the auth requirement (and the unlock sheet asks for a tap).
 *
 * Session: unlocking TextAuth opens a time-limited engine session through Kwallet's unlock sheet (KwEngineUnlockActivity):
 * the keys of every paired address sit in memory for SESSION_IDLE_MS after the last use, at most SESSION_MAX_MS, then are
 * zeroed. Routine sync (seal / sign) runs inside the session; bulk opens are rate-limited (KwEngineCore.Limits).
 */
object KwEngine {
    const val PROTO = 3
    private const val TAG = "KwEngine"
    private const val ALIAS = "kw_engine_rsa_v1"
    private const val DIR = "kweng"
    const val SESSION_IDLE_MS = 15 * 60_000L
    const val SESSION_MAX_MS = 4 * 60 * 60_000L
    private val SLOT = Regex("^[0-9A-Za-z_-]{1,40}\\.[0-9]{1,10}$")

    private class Keys(val m: ByteArray, val i: ByteArray, val n: ByteArray?) { fun zero() { m.fill(0); i.fill(0); n?.fill(0) } }
    private val open = HashMap<String, Keys>()
    @Volatile private var openedAt = 0L
    @Volatile private var usedAt = 0L
    private var limits = KwEngineCore.Limits()
    @Volatile var strongBox = false; private set

    fun slotOk(s: String) = SLOT.matches(s)

    /* ---------- Keystore ---------- */
    private fun ks(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun secure(c: Context): Boolean = try { (c.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure } catch (_: Exception) { false }

    @Synchronized private fun pubKey(c: Context): java.security.PublicKey {
        val k = ks()
        if (!k.containsAlias(ALIAS)) {
            fun gen(sb: Boolean) {
                val b = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_DECRYPT or KeyProperties.PURPOSE_ENCRYPT)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                if (secure(c)) {
                    b.setUserAuthenticationRequired(true)
                    if (Build.VERSION.SDK_INT >= 30) b.setUserAuthenticationParameters(30, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                    else { @Suppress("DEPRECATION") b.setUserAuthenticationValidityDurationSeconds(30) }
                    if (Build.VERSION.SDK_INT >= 24) b.setInvalidatedByBiometricEnrollment(false)
                }
                if (sb && Build.VERSION.SDK_INT >= 28) b.setIsStrongBoxBacked(true)
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply { initialize(b.build()) }.generateKeyPair()
            }
            strongBox = try { gen(true); Build.VERSION.SDK_INT >= 28 } catch (e: Exception) { try { k.deleteEntry(ALIAS) } catch (_: Exception) {}; gen(false); false }
            c.getSharedPreferences("kweng", Context.MODE_PRIVATE).edit().putBoolean("sb", strongBox).apply()
        } else strongBox = c.getSharedPreferences("kweng", Context.MODE_PRIVATE).getBoolean("sb", false)
        val p = k.getCertificate(ALIAS).publicKey
        return KeyFactory.getInstance(p.algorithm).generatePublic(X509EncodedKeySpec(p.encoded))   // software copy: wrapping needs no auth
    }
    private val OAEP = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)

    private fun dir(c: Context) = File(c.noBackupFilesDir, DIR).apply { mkdirs() }
    private fun file(c: Context, slot: String) = File(dir(c), "$slot.k")

    /** pairing (SignActivity, right after Kwallet's own PIN / biometrics): store one address's keys and open them now */
    @Synchronized fun keep(c: Context, slot: String, m: ByteArray, i: ByteArray, n: ByteArray?): Boolean {
        if (!slotOk(slot) || m.size != 32 || i.size != 32 || (n != null && n.size != 32)) return false
        return try {
            val dk = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            val js = JSONObject().put("m", KwEngineCore.hex(m)).put("i", KwEngineCore.hex(i)).apply { if (n != null) put("n", KwEngineCore.hex(n)) }.toString().toByteArray(Charsets.UTF_8)
            val body = KwEngineCore.gcmSeal(dk, js, slot.toByteArray(Charsets.UTF_8)); js.fill(0)
            val rc = Cipher.getInstance("RSA/ECB/OAEPPadding"); rc.init(Cipher.ENCRYPT_MODE, pubKey(c), OAEP)
            val wk = rc.doFinal(dk); dk.fill(0)
            val out = ByteArray(2) + wk + body; out[0] = (wk.size shr 8).toByte(); out[1] = wk.size.toByte()
            val f = file(c, slot); val tmp = File(f.path + ".tmp"); tmp.writeBytes(out); if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            open.remove(slot)?.zero()
            open[slot] = Keys(m.copyOf(), i.copyOf(), n?.copyOf())
            val now = System.currentTimeMillis(); if (openedAt == 0L) openedAt = now; usedAt = now
            clearMig(c, slot)
            true
        } catch (e: Exception) { Log.w(TAG, "keep failed: $e"); false }
    }

    fun slots(c: Context): List<String> = (dir(c).list() ?: emptyArray()).filter { it.endsWith(".k") }.map { it.removeSuffix(".k") }.filter { slotOk(it) }.sorted()
    fun needsAuth(c: Context): Boolean = secure(c)

    /** right after the unlock sheet's biometric / screen lock: decrypt every paired address's keys into the session */
    @Synchronized fun unlockAll(c: Context): Int {
        val pk = try { ks().getKey(ALIAS, null) as? PrivateKey } catch (_: Exception) { null } ?: return 0
        var n = 0
        for (s in slots(c)) {
            try {
                val b = file(c, s).readBytes(); val wl = ((b[0].toInt() and 255) shl 8) or (b[1].toInt() and 255)
                val rc = Cipher.getInstance("RSA/ECB/OAEPPadding"); rc.init(Cipher.DECRYPT_MODE, pk, OAEP)
                val dk = rc.doFinal(b, 2, wl)
                val js = KwEngineCore.gcmOpen(dk, b, 2 + wl, s.toByteArray(Charsets.UTF_8)); dk.fill(0)
                val o = JSONObject(String(js, Charsets.UTF_8)); js.fill(0)
                open.remove(s)?.zero()
                open[s] = Keys(KwEngineCore.unhex(o.getString("m")), KwEngineCore.unhex(o.getString("i")), o.optString("n", "").let { if (it.length == 64) KwEngineCore.unhex(it) else null })
                n++
            } catch (e: Exception) { Log.w(TAG, "slot $s did not open: ${e.javaClass.simpleName}") }
        }
        if (n > 0) { val now = System.currentTimeMillis(); openedAt = now; usedAt = now; limits.reset() }
        return n
    }

    @Synchronized fun lock() { open.values.forEach { it.zero() }; open.clear(); openedAt = 0L; limits.reset() }
    @Synchronized fun isOpen(): Boolean { expire(); return open.isNotEmpty() }
    private fun expire() {
        val now = System.currentTimeMillis()
        if (open.isNotEmpty() && (now - usedAt > SESSION_IDLE_MS || now - openedAt > SESSION_MAX_MS)) lock()
    }
    /** a seed removed / Kwallet erased: its keys go too (all of them on an erase) */
    @Synchronized fun forget(c: Context, sids: Collection<String>?) {
        for (s in slots(c)) if (sids == null || sids.any { s.startsWith("$it.") }) { file(c, s).delete(); open.remove(s)?.zero(); clearMig(c, s) }
        if (sids == null) { lock(); try { ks().deleteEntry(ALIAS) } catch (_: Exception) {} }
    }

    /* anti-downgrade: the version up to which unbound (v1) records are still accepted, per address, set by TextAuth when its
       background re-seal finished (only inside an open session) */
    private fun migP(c: Context) = c.getSharedPreferences("kweng", Context.MODE_PRIVATE)
    private fun migOf(c: Context, slot: String): BigInteger? = migP(c).getString("mig:$slot", null)?.let { try { BigInteger(it) } catch (_: Exception) { null } }
    private fun clearMig(c: Context, slot: String) { migP(c).edit().remove("mig:$slot").apply() }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun ub64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
    private fun err(e: String) = JSONObject().put("err", e)

    /**
     * One batched call from TextAuth: {"v":1,"ops":[{op, slot, ...}, ...]} -> {"v":1,"proto":3,"open":bool,"res":[...]}.
     * Every op answers {..result} or {"err": "locked" | "noslot" | "relock" | "rate" | "refused" | "bad" | "auth"}.
     */
    @Synchronized fun handle(c: Context, req: String): String {
        expire()
        val out = JSONObject().put("v", 1).put("proto", PROTO)
        val q = try { JSONObject(req) } catch (_: Exception) { return out.put("err", "bad").toString() }
        val ops = q.optJSONArray("ops") ?: JSONArray()
        val res = JSONArray()
        if (ops.length() > 4000) return out.put("err", "bad").toString()
        var used = false
        for (x in 0 until ops.length()) {
            val o = ops.optJSONObject(x) ?: JSONObject()
            val r = try { op(c, o) } catch (e: KwEngineCore.Refused) { err("refused").put("why", e.message ?: "") } catch (e: Exception) { err("bad").put("why", e.javaClass.simpleName) }
            if (!r.has("err") && o.optString("op") != "st") used = true
            res.put(r)
        }
        if (used) usedAt = System.currentTimeMillis()
        return out.put("open", open.isNotEmpty()).put("res", res).toString()
    }

    /**
     * A file part (up to 1.8 MB) is too large for one Binder call as JSON, so it travels in shared memory (KwTaLinkService
     * SVC_ENGINE_BIG): head = {"op":"sealPart"|"openPart","slot","id","idx"}, bytes = the part. -> (answer head, bytes or null)
     */
    @Synchronized fun handleBig(c: Context, head: String, data: ByteArray): Pair<String, ByteArray?> {
        expire()
        val o = try { JSONObject(head) } catch (_: Exception) { return Pair("{\"err\":\"bad\"}", null) }
        val slot = o.optString("slot")
        if (!slotOk(slot)) return Pair("{\"err\":\"bad\"}", null)
        if (!file(c, slot).exists()) return Pair("{\"err\":\"noslot\"}", null)
        val k = open[slot] ?: return Pair("{\"err\":\"locked\"}", null)
        return try {
            val id = o.getString("id"); val idx = o.getInt("idx")
            val out = when (o.optString("op")) {
                "sealPart" -> KwEngineCore.sealPart(k.m, id, idx, data)
                "openPart" -> KwEngineCore.openPart(k.m, id, idx, data)
                else -> return Pair("{\"err\":\"bad\"}", null)
            }
            usedAt = System.currentTimeMillis()
            Pair("{\"ok\":true}", out)
        } catch (e: Exception) { Pair("{\"err\":\"refused\"}", null) }
    }

    private fun op(c: Context, o: JSONObject): JSONObject {
        val name = o.optString("op")
        if (name == "st") return JSONObject().put("open", open.isNotEmpty()).put("slots", JSONArray(slots(c))).put("sb", strongBox)
            .put("exp", if (open.isEmpty()) 0 else minOf(usedAt + SESSION_IDLE_MS, openedAt + SESSION_MAX_MS))
        if (name == "lock") { lock(); return JSONObject().put("ok", true) }
        val slot = o.optString("slot")
        if (!slotOk(slot)) return err("bad")
        if (!file(c, slot).exists()) return err("noslot")
        val k = open[slot] ?: return err("locked")
        return when (name) {
            "pub" -> JSONObject().put("pk", KwEngineCore.hex(KwEngineCore.edPub(k.i))).apply { if (k.n != null) put("np", KwEngineCore.hex(KwEngineCore.nostrPub(k.n))) }
                .put("mig", migOf(c, slot)?.toString() ?: "")
            "seal" -> {
                val kind = o.getInt("kind"); val id = o.getString("id"); val ver = o.getString("ver")
                JSONObject().put("b", b64(KwEngineCore.sealRec(k.m, kind, id, ver, o.optBoolean("del"), ub64(o.getString("pt")))))
            }
            "open" -> {
                /* one record (sync, viewing); a whole cache is one openList op (once per address per unlock) */
                if (!limits.take(slot, 1)) return err("rate")
                val kind = o.getInt("kind"); val id = o.getString("id"); val ver = o.getString("ver")
                val r = KwEngineCore.openRec(k.m, kind, id, ver, o.optBoolean("del"), ub64(o.getString("b")), migOf(c, slot))
                JSONObject().put("pt", b64(r.pt)).put("f", r.format)
            }
            "openList" -> {
                val L = o.getJSONArray("recs"); if (L.length() > KwEngineCore.Limits.LIST_MAX) return err("bad")
                /* first part of a whole-cache read: once per address per unlock; its later parts (cont) within 2 minutes */
                if (!(if (o.optBoolean("cont")) limits.listMore(slot, L.length()) else limits.list(slot, L.length()))) return err("relock")
                val mig = migOf(c, slot); val a = JSONArray()
                for (j in 0 until L.length()) {
                    val e = L.getJSONObject(j)
                    a.put(try { val r = KwEngineCore.openRec(k.m, e.getInt("kind"), e.getString("id"), e.getString("ver"), e.optBoolean("del"), ub64(e.getString("b")), mig); JSONObject().put("pt", b64(r.pt)).put("f", r.format) }
                        catch (x: KwEngineCore.Refused) { err("refused") } catch (x: Exception) { err("bad") })
                }
                JSONObject().put("list", a)
            }
            "sealPart" -> JSONObject().put("b", b64(KwEngineCore.sealPart(k.m, o.getString("id"), o.getInt("idx"), ub64(o.getString("pt")))))
            "openPart" -> JSONObject().put("pt", b64(KwEngineCore.openPart(k.m, o.getString("id"), o.getInt("idx"), ub64(o.getString("b")))))
            "sealK" -> JSONObject().put("b", b64(KwEngineCore.sealKeyed(k.m, o.getString("info"), ub64(o.getString("pt")), o.optString("aad", "").let { if (it.isEmpty()) null else ub64(it) })))
            "openK" -> {
                val info = o.getString("info")
                if ((info.startsWith("kwauth/pwfill/") || info.startsWith("kwauth/afill/")) && !limits.take(slot, 1)) return err("rate")
                JSONObject().put("pt", b64(KwEngineCore.openKeyed(k.m, info, ub64(o.getString("b")), o.optString("aad", "").let { if (it.isEmpty()) null else ub64(it) })))
            }
            "derive" -> JSONObject().put("b", b64(KwEngineCore.derive(k.m, o.getString("info"), o.getInt("n"))))
            "mac" -> JSONObject().put("b", b64(KwEngineCore.mac(k.m, o.getString("info"), ub64(o.getString("msg")))))
            "icpSign" -> JSONObject().put("sig", b64(KwEngineCore.icpSign(k.i, ub64(o.getString("msg"))))).put("pk", KwEngineCore.hex(KwEngineCore.edPub(k.i)))
            "nostrSign" -> {
                val nk = k.n ?: return err("nonostr")
                val ev = o.getJSONObject("ev"); val tg = ev.optJSONArray("tags") ?: JSONArray()
                val tags = (0 until tg.length()).map { t -> val a = tg.getJSONArray(t); (0 until a.length()).map { a.getString(it) } }
                val s = KwEngineCore.nostrSign(nk, ev.getLong("created_at"), ev.getInt("kind"), tags, ev.optString("content", ""))
                JSONObject().put("id", s.id).put("pubkey", s.pubkey).put("sig", s.sig)
            }
            "migDone" -> {
                val v = o.getString("ver"); if (!KwEngineCore.verOk(v)) return err("bad")
                val cur = migOf(c, slot); if (cur == null || BigInteger(v) > cur) migP(c).edit().putString("mig:$slot", BigInteger(v).toString()).apply()
                JSONObject().put("ok", true)
            }
            else -> err("bad")
        }
    }
}
