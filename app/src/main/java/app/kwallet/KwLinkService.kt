package app.kwallet

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log

/**
 * K-H link: the persistent K <-> H link. Hwallet binds this service while it is on screen (onStart .. onStop) and keeps the
 * connection alive with a ping every ~5 s; nothing runs when Hwallet is not in front (no foreground service, no wake lock,
 * no timers here). Over this Binder Hwallet can:
 *  - SVC_PING: heartbeat. Reply: protocol version, then 1 when a sign page is ready (pre-loaded or open), else 0.
 *  - SVC_WARM: pre-load the sign page now (the same KwWarm path as the WARM broadcast).
 *  - SVC_FEED: live fee / balance / price data for the open sign page (id, feed json), delivered through the same entry
 *    point as the SIGN_FEED broadcast (SignActivity.onFeed), so the page sees identical data. The broadcast stays as the
 *    fallback while this service is not bound.
 * Exported, but binding needs the signature permission app.kwallet.permission.SIGN_FEED, and every transaction checks that the
 * caller's uid is app.hwallet signed with Kwallet's key. No INTERNET: this only moves data between the two apps.
 */
class KwLinkService : Service() {
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var okUid = -1

    private fun callerOk(uid: Int): Boolean {
        if (uid == okUid) return true
        val ok = try {
            (packageManager.getPackagesForUid(uid) ?: emptyArray()).any { it == SignActivity.HWALLET } &&
                packageManager.checkSignatures(packageName, SignActivity.HWALLET) == PackageManager.SIGNATURE_MATCH
        } catch (_: Exception) { false }
        if (ok) okUid = uid
        return ok
    }

    private val link = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != SVC_PING && code != SVC_WARM && code != SVC_FEED && code != SVC_QUIET) return super.onTransact(code, data, reply, flags)
            if (!callerOk(Binder.getCallingUid())) return false
            when (code) {
                SVC_PING -> {
                    reply?.writeInt(PROTO)
                    reply?.writeInt(if (KwWarm.ready()) 1 else 0)
                }
                SVC_WARM -> ui.post { KwWarm.warm(applicationContext, "link") }
                SVC_FEED -> {
                    val id = try { data.readString() ?: "" } catch (_: Exception) { "" }
                    val f = try { data.readString() ?: "" } catch (_: Exception) { "" }
                    if (id.isNotEmpty() && f.isNotEmpty()) ui.post { SignActivity.open?.onFeed(id, f) }
                }
                /* v3.99.23: a headless identity read (blocking call from Hwallet's link thread, at most ~9 s). Only icp_iiNames is
                   signed, by the spare sign page, with no window and no PIN; the answer is the page's JSON. */
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
            }
            return true
        }
    }

    /* onBind runs on the main thread from the system, not from the client, so the caller cannot be identified here: the
       manifest's signature permission guards the bind, and onTransact checks the caller's uid on every call. */
    override fun onBind(intent: Intent?): IBinder {
        Log.i("KwLink", "Hwallet bound the link")
        return link
    }
    override fun onUnbind(intent: Intent?): Boolean { Log.i("KwLink", "Hwallet unbound the link"); return false }

    companion object {
        const val PROTO = 2   // v3.99.23: 2 = SVC_QUIET
        /* same numbers in Hwallet's HwActivity (KW_SVC_*) */
        const val SVC_PING = IBinder.FIRST_CALL_TRANSACTION + 11
        const val SVC_WARM = IBinder.FIRST_CALL_TRANSACTION + 12
        const val SVC_FEED = IBinder.FIRST_CALL_TRANSACTION + 13
        const val SVC_QUIET = IBinder.FIRST_CALL_TRANSACTION + 14
    }
}
