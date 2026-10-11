package app.kwallet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.webkit.WebView

/**
 * P2a (#97a): Hwallet sends app.kwallet.WARM (signature permission) when a dApp connects or a request is coming.
 * This starts Kwallet's process and loads the WebView engine once, so the sign sheet's first frame does not pay for it.
 * Nothing is shown and nothing is loaded from the network (Kwallet has no INTERNET permission; the page is about:blank).
 */
class WarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { KwWarm.warm(c.applicationContext) }
}

object KwWarm {
    @Volatile private var web: WebView? = null
    /** main thread (receivers run there). The one blank WebView keeps the engine loaded while the process lives. */
    fun warm(ctx: Context) {
        if (web != null) return
        try { web = WebView(ctx).also { it.settings.javaScriptEnabled = false; it.loadUrl("about:blank") } } catch (_: Throwable) {}
    }
}
