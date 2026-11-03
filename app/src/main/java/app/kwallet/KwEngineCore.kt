package app.kwallet

import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * v3.99.50: TextAuth's crypto engine, pure JVM (no Android classes, so CI runs it as a JVM unit test).
 *
 * Kwallet keeps every TextAuth key (vault master, vault ICP identity key, Nostr key) and TextAuth only ever gets results.
 * The formats are exactly the ones TextAuth used to compute itself (web/lib/kacrypto.js), so every existing record keeps
 * opening:
 *  - item key  = HKDF-SHA256(ikm = vault master, salt 'TextAuth v1', info 'kwauth/<kind>/<hex id>', 32 B) -> AES-256-GCM
 *  - record v1 = iv(12) | AES-GCM(item key, padded plaintext), no additional data (TextAuth 1.0.43 and older)
 *  - record v2 = 0x02 | iv(12) | AES-GCM(item key, padded plaintext, AAD = 'kwauth/rec/<kind>/<hex id>/<ver>/<0|1>')
 *    so a server can't relabel a record with another version, kind, id or deleted flag (open() rejects a mismatch)
 *  - file part = iv(12) | AES-GCM(item key of kind 4, part, AAD = 'kwauth/chunk/<hex id>/<index>') (unchanged)
 *  - keyed blob = iv(12) | AES-GCM(HKDF(info), bytes, AAD as given) for the outbox, the cloud index and the fill blobs
 *  - ICP: Ed25519 over 0x0A 'ic-request' | request id (32 B) only; Nostr: BIP-340 over the NIP-01 event id Kwallet
 *    computes itself, for the event kinds TextAuth's sync uses only.
 * The plaintext padding stays TextAuth's job (it only ever sees its own plaintext); Kwallet never pads or parses it.
 */
object KwEngineCore {
    const val SALT = "TextAuth v1"
    const val REC_V2: Byte = 2
    private val rnd = SecureRandom()

    /* what TextAuth may ask for. derive: results only (a seed password, a log slot id, the public cloud namespace, the
       key of Nostr's opaque d tags, which only hides record names and can't open or sign anything). keyed: Kwallet seals /
       opens. mac: Kwallet computes the tag. Anything else is refused. */
    private val DERIVE = listOf("kwauth/pw/", "kwauth/log/", "kwauth/cloud-ns", "kwauth/nostr-d")
    private val KEYED = listOf("kwauth/outbox", "kwauth/cloud-index", "kwauth/pwfill/", "kwauth/afill/")
    private val MACS = setOf("kwauth/sitetag")
    /** Nostr event kinds TextAuth's sync signs: 30078 (records, index), 5 (own deletes), 22242 (relay AUTH), 24242 (media auth) */
    val NOSTR_KINDS = setOf(30078, 5, 22242, 24242)

    fun deriveOk(info: String): Boolean = info.length in 1..400 && DERIVE.any { if (it.endsWith("/")) info.startsWith(it) && info.length > it.length else info == it }
    fun keyedOk(info: String): Boolean = info.length in 1..400 && KEYED.any { if (it.endsWith("/")) info.startsWith(it) && info.length > it.length else info == it }
    fun macOk(info: String): Boolean = info in MACS

    fun hkdf(ikm: ByteArray, info: String, len: Int = 32): ByteArray {
        require(len in 1..8160) { "bad length" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(SALT.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(len); var t = ByteArray(0); var pos = 0; var i = 1
        val inf = info.toByteArray(Charsets.UTF_8)
        while (pos < len) {
            mac.update(t); mac.update(inf); mac.update(i.toByte()); t = mac.doFinal()
            val n = minOf(t.size, len - pos); System.arraycopy(t, 0, out, pos, n); pos += n; i++
        }
        prk.fill(0)
        return out
    }

    fun hex(b: ByteArray): String { val h = "0123456789abcdef"; val s = StringBuilder(b.size * 2); for (x in b) { s.append(h[(x.toInt() shr 4) and 15]); s.append(h[x.toInt() and 15]) }; return s.toString() }
    fun unhex(s: String): ByteArray {
        require(s.length % 2 == 0 && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "bad hex" }
        return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
    private val HEXID = Regex("^[0-9a-f]{2,128}$")
    private val VER = Regex("^[0-9]{1,30}$")
    fun idOk(id: String) = HEXID.matches(id)
    fun verOk(v: String) = VER.matches(v)
    fun kindOk(k: Int) = k in 1..16

    fun itemInfo(kind: Int, idHex: String): String { require(kindOk(kind) && idOk(idHex)) { "bad record" }; return "kwauth/$kind/$idHex" }
    fun recAad(kind: Int, idHex: String, ver: String, deleted: Boolean): ByteArray {
        require(kindOk(kind) && idOk(idHex) && verOk(ver)) { "bad record" }
        return "kwauth/rec/$kind/$idHex/${BigInteger(ver)}/${if (deleted) 1 else 0}".toByteArray(Charsets.UTF_8)
    }
    fun partAad(idHex: String, idx: Int): ByteArray { require(idOk(idHex) && idx >= 0) { "bad part" }; return "kwauth/chunk/$idHex/$idx".toByteArray(Charsets.UTF_8) }

    fun gcmSeal(key: ByteArray, pt: ByteArray, aad: ByteArray?): ByteArray {
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        if (aad != null) c.updateAAD(aad)
        return iv + c.doFinal(pt)
    }
    fun gcmOpen(key: ByteArray, b: ByteArray, off: Int, aad: ByteArray?): ByteArray {
        require(b.size - off > 12 + 16) { "short" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, b, off, 12))
        if (aad != null) c.updateAAD(aad)
        return c.doFinal(b, off + 12, b.size - off - 12)
    }

    /** a v2 record: version byte, then iv | ciphertext bound to kind, id, version and the deleted flag */
    fun sealRec(master: ByteArray, kind: Int, idHex: String, ver: String, deleted: Boolean, pt: ByteArray): ByteArray {
        val k = hkdf(master, itemInfo(kind, idHex))
        try { return byteArrayOf(REC_V2) + gcmSeal(k, pt, recAad(kind, idHex, ver, deleted)) } finally { k.fill(0) }
    }

    class Opened(val pt: ByteArray, val format: Int)
    class Refused(msg: String) : Exception(msg)

    /**
     * Opens a record for exactly (kind, id, ver, deleted). A v2 record must match all four. A v1 record (no binding) opens
     * only while the address is not fully migrated, or when its version is not newer than the migration point
     * (migratedAt): after migration an unbound record newer than that is a downgrade and is refused.
     */
    fun openRec(master: ByteArray, kind: Int, idHex: String, ver: String, deleted: Boolean, b: ByteArray, migratedAt: BigInteger?): Opened {
        val k = hkdf(master, itemInfo(kind, idHex))
        try {
            if (b.isNotEmpty() && b[0] == REC_V2 && b.size > 1 + 12 + 16) {
                try { return Opened(gcmOpen(k, b, 1, recAad(kind, idHex, ver, deleted)), 2) } catch (_: Exception) { /* maybe a v1 record whose iv starts with 2 */ }
            }
            if (migratedAt != null && BigInteger(ver) > migratedAt) throw Refused("unbound record after migration")
            try { return Opened(gcmOpen(k, b, 0, null), 1) } catch (_: Exception) { throw Refused("authentication failed") }
        } finally { k.fill(0) }
    }

    fun sealPart(master: ByteArray, idHex: String, idx: Int, pt: ByteArray): ByteArray {
        val k = hkdf(master, itemInfo(4, idHex)); try { return gcmSeal(k, pt, partAad(idHex, idx)) } finally { k.fill(0) }
    }
    fun openPart(master: ByteArray, idHex: String, idx: Int, b: ByteArray): ByteArray {
        val k = hkdf(master, itemInfo(4, idHex)); try { return gcmOpen(k, b, 0, partAad(idHex, idx)) } finally { k.fill(0) }
    }

    fun sealKeyed(master: ByteArray, info: String, pt: ByteArray, aad: ByteArray?): ByteArray {
        if (!keyedOk(info)) throw Refused("not allowed")
        val k = hkdf(master, info); try { return gcmSeal(k, pt, aad) } finally { k.fill(0) }
    }
    fun openKeyed(master: ByteArray, info: String, b: ByteArray, aad: ByteArray?): ByteArray {
        if (!keyedOk(info)) throw Refused("not allowed")
        val k = hkdf(master, info); try { return gcmOpen(k, b, 0, aad) } finally { k.fill(0) }
    }
    fun derive(master: ByteArray, info: String, n: Int): ByteArray {
        if (!deriveOk(info) || n !in 1..64) throw Refused("not allowed")
        return hkdf(master, info, n)
    }
    fun mac(master: ByteArray, info: String, msg: ByteArray): ByteArray {
        if (!macOk(info)) throw Refused("not allowed")
        val k = hkdf(master, info)
        try { val m = Mac.getInstance("HmacSHA256"); m.init(SecretKeySpec(k, "HmacSHA256")); return m.doFinal(msg) } finally { k.fill(0) }
    }

    /* ---------- ICP vault identity (Ed25519) ---------- */
    private val IC_REQ = byteArrayOf(0x0A) + "ic-request".toByteArray(Charsets.US_ASCII)
    fun edPub(sk: ByteArray): ByteArray = Ed25519PrivateKeyParameters(sk, 0).generatePublicKey().encoded
    /** signs an IC request id only: msg = 0x0A 'ic-request' | 32-byte request id. Anything else is refused. */
    fun icpSign(sk: ByteArray, msg: ByteArray): ByteArray {
        if (msg.size != IC_REQ.size + 32 || !msg.copyOfRange(0, IC_REQ.size).contentEquals(IC_REQ)) throw Refused("only IC requests are signed")
        val s = Ed25519Signer(); s.init(true, Ed25519PrivateKeyParameters(sk, 0)); s.update(msg, 0, msg.size)
        return s.generateSignature()
    }

    /* ---------- Nostr (BIP-340 on secp256k1) ---------- */
    private val K1 by lazy { CustomNamedCurves.getByName("secp256k1") }
    private fun b32(x: BigInteger): ByteArray { val b = x.toByteArray(); return when { b.size == 32 -> b; b.size > 32 -> b.copyOfRange(b.size - 32, b.size); else -> ByteArray(32 - b.size) + b } }
    private fun sha(vararg p: ByteArray): ByteArray { val d = MessageDigest.getInstance("SHA-256"); p.forEach { d.update(it) }; return d.digest() }
    private fun tagged(tag: String, vararg p: ByteArray): ByteArray { val t = sha(tag.toByteArray(Charsets.UTF_8)); return sha(t, t, *p) }
    fun nostrPub(sk: ByteArray): ByteArray {
        val d = BigInteger(1, sk); require(d.signum() > 0 && d < K1.n) { "bad key" }
        return b32(K1.g.multiply(d).normalize().affineXCoord.toBigInteger())
    }
    fun schnorrSign(sk: ByteArray, msg: ByteArray, aux: ByteArray): ByteArray {
        require(aux.size == 32) { "aux" }
        val n = K1.n; var d = BigInteger(1, sk); require(d.signum() > 0 && d < n) { "bad key" }
        val p = K1.g.multiply(d).normalize()
        if (p.affineYCoord.toBigInteger().testBit(0)) d = n.subtract(d)
        val px = b32(p.affineXCoord.toBigInteger())
        val t = b32(d).also { val h = tagged("BIP0340/aux", aux); for (i in 0 until 32) it[i] = (it[i].toInt() xor h[i].toInt()).toByte() }
        var k = BigInteger(1, tagged("BIP0340/nonce", t, px, msg)).mod(n); require(k.signum() != 0) { "nonce" }
        val r = K1.g.multiply(k).normalize()
        if (r.affineYCoord.toBigInteger().testBit(0)) k = n.subtract(k)
        val rx = b32(r.affineXCoord.toBigInteger())
        val e = BigInteger(1, tagged("BIP0340/challenge", rx, px, msg)).mod(n)
        return rx + b32(k.add(e.multiply(d)).mod(n))
    }
    fun schnorrSign(sk: ByteArray, msg: ByteArray): ByteArray = schnorrSign(sk, msg, ByteArray(32).also { rnd.nextBytes(it) })

    /** JSON.stringify of one string, exactly as JavaScript writes it (NIP-01 ids are computed over JS's output) */
    fun jsStr(s: String): String {
        val b = StringBuilder(s.length + 2); b.append('"')
        for (c in s) when (c) {
            '"' -> b.append("\\\""); '\\' -> b.append("\\\\"); '\b' -> b.append("\\b"); '\u000c' -> b.append("\\f")
            '\n' -> b.append("\\n"); '\r' -> b.append("\\r"); '\t' -> b.append("\\t")
            else -> if (c < ' ') b.append("\\u").append(String.format("%04x", c.code)) else b.append(c)
        }
        return b.append('"').toString()
    }
    /** NIP-01: [0, pubkey, created_at, kind, tags, content] */
    fun nostrSerialize(pk: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): String =
        "[0," + jsStr(pk) + "," + createdAt + "," + kind + ",[" + tags.joinToString(",") { t -> "[" + t.joinToString(",") { jsStr(it) } + "]" } + "]," + jsStr(content) + "]"
    class SignedEvent(val id: String, val pubkey: String, val sig: String)
    fun nostrSign(sk: ByteArray, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): SignedEvent {
        if (kind !in NOSTR_KINDS) throw Refused("Nostr kind $kind is not signed for TextAuth")
        if (createdAt < 0 || createdAt > 99999999999L) throw Refused("bad time")
        val pk = hex(nostrPub(sk))
        val id = sha(nostrSerialize(pk, createdAt, kind, tags, content).toByteArray(Charsets.UTF_8))
        return SignedEvent(hex(id), pk, hex(schnorrSign(sk, id)))
    }

    /* ---------- session limits ---------- */
    /**
     * Bulk-open limits for one engine session (one unlock). A "list" open (TextAuth reading its whole cache when an
     * address opens) is allowed once per address per unlock; every other open counts against a per-minute cap per address.
     * A second list, or going over the cap, is refused until the next unlock / the next minute.
     */
    class Limits(private val perMin: Int = PER_MIN, private val clock: () -> Long = { System.currentTimeMillis() }) {
        private val listed = HashSet<String>()
        private val win = HashMap<String, ArrayDeque<Long>>()
        @Synchronized fun list(slot: String): Boolean = listed.add(slot)
        @Synchronized fun take(slot: String, n: Int): Boolean {
            val now = clock(); val q = win.getOrPut(slot) { ArrayDeque() }
            while (q.isNotEmpty() && now - q.first() >= 60_000L) q.removeFirst()
            if (q.size + n > perMin) return false
            repeat(n) { q.addLast(now) }
            return true
        }
        @Synchronized fun reset() { listed.clear(); win.clear() }
        companion object { const val PER_MIN = 600; const val LIST_MAX = 20000 }
    }
}
