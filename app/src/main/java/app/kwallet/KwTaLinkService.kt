package app.kwallet

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.IInterface
import android.os.Looper
import android.os.Parcel
import android.os.RemoteCallbackList
import android.util.Log
import org.json.JSONObject

/**
 * v3.99.43: the K <-> TextAuth link, the same Binder protocol as the K-H link (KwLinkService), so TextAuth talks to Kwallet in
 * milliseconds with no polling and no web round trip. TextAuth binds it while it is on screen. Over it TextAuth can:
 *  - SVC_PING: protocol version, 1 when a sign page is ready (pre-loaded or open) else 0, and Kwallet's state stamp.
 *  - SVC_WARM: pre-load the sign page now, so the passkey / pairing / ICP sheet opens at once.
 *  - SVC_FEED: live data (fee, balance) for the open sheet, only when that sheet was opened by TextAuth.
 *  - SVC_QUIET: a headless identity read (icp_iiNames only: each passkey signs one read-only identity_info), for TextAuth's
 *    live id.ai identity names and creation dates. Same rules as for Hwallet: no window, no PIN, nothing else is signed.
 * Exported, but binding needs app.kwallet.permission.TEXTAUTH_LINK (signature|knownSigner: only Kwallet's key or the pinned
 * TextAuth certificate, Android 12+), and every transaction checks that the caller uid is app.textauth with the pinned cert.
 * No INTERNET here.
 *
 * v3.99.44: TextAuth 1.0.9 keeps this link bound all the time (its own foreground service) and registers a callback:
 *  - SVC_LISTEN: TextAuth's callback Binder (same uid + cert check). Kept in a RemoteCallbackList (linkToDeath: a dead TextAuth
 *    drops out by itself; registering the same Binder again replaces it). [emit] pushes one-way events on it, code EVT, one
 *    JSON string {ev, at, ...}: "seeds" (added / gone / renamed seed IDs from the seed mirror, with names), "wipe", "lock",
 *    "unlock", "sign" (st start | done, for TextAuth's own requests only, with method and status), "pair" (pk_pair answered ok).
 *    Public IDs, names and states only: never keys, addresses or request contents.
 *
 * v3.99.50 (PROTO 3): SVC_ENGINE, Kwallet as TextAuth's crypto engine (KwEngine): seal / open records, file parts, keyed
 *  blobs, IC request and Nostr event signatures, seed passwords. One JSON batch per call; the answer holds results only,
 *  never a key. Same caller check as every call here.
 */
class KwTaLinkService : Service() {
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var okUid = -1

    private fun callerOk(uid: Int): Boolean {
        if (uid == okUid) return true
        val ok = try {
            (packageManager.getPackagesForUid(uid) ?: emptyArray()).any { it == SignActivity.TEXTAUTH } && SignActivity.textAuthPinned(packageManager)
        } catch (_: Exception) { false }
        if (ok) okUid = uid
        return ok
    }

    private val link = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != SVC_PING && code != SVC_WARM && code != SVC_FEED && code != SVC_QUIET && code != SVC_LISTEN && code != SVC_ENGINE && code != SVC_ENGINE_BIG) return super.onTransact(code, data, reply, flags)
            if (!callerOk(Binder.getCallingUid())) return false
            when (code) {
                SVC_PING -> {
                    reply?.writeInt(PROTO)
                    reply?.writeInt(if (KwWarm.ready()) 1 else 0)
                    reply?.writeString(KwLinkState.stamp(applicationContext))
                }
                SVC_WARM -> ui.post { KwWarm.warm(applicationContext, "ta-link") }
                SVC_FEED -> {
                    val id = try { data.readString() ?: "" } catch (_: Exception) { "" }
                    val f = try { data.readString() ?: "" } catch (_: Exception) { "" }
                    if (id.isNotEmpty() && f.isNotEmpty()) ui.post { SignActivity.open?.let { if (it.callerPkgOf() == SignActivity.TEXTAUTH) it.onFeed(id, f) } }
                }
                SVC_QUIET -> {
                    val body = try { data.readString() ?: "" } catch (_: Exception) { "" }
                    var out = "{\"status\":\"error\",\"error\":\"empty\"}"
                    if (body.isNotEmpty() && body.length <= 65536) {
                        val latch = java.util.concurrent.CountDownLatch(1)
                        ui.post { KwWarm.quiet(applicationContext, body) { j -> out = j; latch.countDown() } }
                        try { if (!latch.await(9500, java.util.concurrent.TimeUnit.MILLISECONDS)) out = "{\"status\":\"timeout\"}" } catch (_: InterruptedException) {}
                    }
                    reply?.writeString(out)
                }
                SVC_ENGINE -> {   /* v3.99.50: TextAuth's crypto engine, one batched JSON call (KwEngine.handle); results only */
                    val body = try { data.readString() ?: "" } catch (_: Exception) { "" }
                    reply?.writeString(if (body.isEmpty() || body.length > 900_000) "{\"v\":1,\"err\":\"bad\"}" else KwEngine.handle(applicationContext, body))
                }
                SVC_ENGINE_BIG -> {   /* v3.99.50: one file part in shared memory (sealPart / openPart) */
                    if (android.os.Build.VERSION.SDK_INT < 27) { reply?.writeString("{\"err\":\"bad\"}"); reply?.writeInt(0); return true }
                    val head = try { data.readString() ?: "" } catch (_: Exception) { "" }
                    val inp = try { readShm(data) } catch (_: Exception) { null }
                    val r = if (head.isEmpty() || inp == null || inp.size > 4_000_000) Pair("{\"err\":\"bad\"}", null) else KwEngine.handleBig(applicationContext, head, inp)
                    reply?.writeString(r.first)
                    val ob = r.second
                    if (ob == null || reply == null) reply?.writeInt(0) else { reply.writeInt(1); writeShm(reply, ob) }
                }
                SVC_LISTEN -> {   /* v3.99.44 */
                    val cb = try { data.readStrongBinder() } catch (_: Exception) { null }
                    reply?.writeInt(if (cb != null && listen(cb)) 1 else 0)
                }
            }
            return true
        }
    }

    override fun onBind(intent: Intent?): IBinder { Log.i("KwTaLink", "TextAuth bound the link"); return link }
    override fun onUnbind(intent: Intent?): Boolean { Log.i("KwTaLink", "TextAuth unbound the link"); return false }

    companion object {
        const val PROTO = 3   /* v3.99.44: 2 = LISTEN + events; v3.99.50: 3 = SVC_ENGINE (TextAuth gets no keys) */
        /* same numbers in TextAuth's KwLink (KW_SVC_*) */
        const val SVC_PING = IBinder.FIRST_CALL_TRANSACTION + 21
        const val SVC_WARM = IBinder.FIRST_CALL_TRANSACTION + 22
        const val SVC_FEED = IBinder.FIRST_CALL_TRANSACTION + 23
        const val SVC_QUIET = IBinder.FIRST_CALL_TRANSACTION + 24
        /* v3.99.44: TextAuth's event callback (TextAuth KwLink SVC_LISTEN) and the code Kwallet calls on it (KwLink EVT) */
        const val SVC_LISTEN = IBinder.FIRST_CALL_TRANSACTION + 25
        /* v3.99.50: the engine (TextAuth KwLink SVC_ENGINE) */
        const val SVC_ENGINE = IBinder.FIRST_CALL_TRANSACTION + 26
        const val SVC_ENGINE_BIG = IBinder.FIRST_CALL_TRANSACTION + 27

        @androidx.annotation.RequiresApi(27)
        private fun readShm(p: Parcel): ByteArray {
            val m = android.os.SharedMemory.CREATOR.createFromParcel(p)
            try { val bb = m.mapReadOnly(); try { return ByteArray(bb.remaining()).also { bb.get(it) } } finally { android.os.SharedMemory.unmap(bb) } } finally { m.close() }
        }
        @androidx.annotation.RequiresApi(27)
        private fun writeShm(p: Parcel, b: ByteArray) {
            val m = android.os.SharedMemory.create("kweng", maxOf(1, b.size))
            val bb = m.mapReadWrite(); try { bb.put(b) } finally { android.os.SharedMemory.unmap(bb) }
            m.writeToParcel(p, 0); m.close()
        }
        const val EVT = IBinder.FIRST_CALL_TRANSACTION + 1
        private const val MAX_LISTENERS = 4

        private class Cb(val b: IBinder) : IInterface { override fun asBinder(): IBinder = b }
        private val cbs = RemoteCallbackList<Cb>()

        private fun listen(b: IBinder): Boolean {
            if (!b.isBinderAlive) return false
            synchronized(cbs) { if (cbs.registeredCallbackCount >= MAX_LISTENERS) return false }
            return cbs.register(Cb(b)).also { Log.i("KwTaLink", "TextAuth listens: $it") }
        }

        /** v3.99.44: one event to every listening TextAuth (one-way, never blocks; a failed send is dropped) */
        fun emit(ev: String, extra: JSONObject? = null) {
            val j = try { (extra ?: JSONObject()).put("ev", ev).put("at", System.currentTimeMillis()).toString() } catch (_: Exception) { return }
            if (j.length > 60000) return
            synchronized(cbs) {
                val n = try { cbs.beginBroadcast() } catch (_: Exception) { return }
                try {
                    for (i in 0 until n) {
                        val d = Parcel.obtain()
                        try { d.writeString(j); cbs.getBroadcastItem(i).b.transact(EVT, d, null, IBinder.FLAG_ONEWAY) } catch (_: Exception) {} finally { d.recycle() }
                    }
                } finally { try { cbs.finishBroadcast() } catch (_: Exception) {} }
            }
        }
    }
}
