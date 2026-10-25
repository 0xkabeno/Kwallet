package app.hwallet

import android.content.Context
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * v1.6: provider API keys (Alchemy, Etherscan V2, Helius, TronGrid, LI.FI, Jupiter, Reown).
 *
 * Two sources, the user's own key first:
 *  1. a key the user typed in Settings > API keys (kept in this app's private storage);
 *  2. the app's built-in key, sealed into the APK at build time (AES-GCM, per-build key split into masked pieces in
 *     two generated classes, see hwallet/app/build.gradle.kts). Opened only here, in memory.
 *
 * The page never receives a key. It writes a placeholder such as {{K:alchemy}} into a URL, header or body, and
 * HwActivity swaps it in natively right before the request leaves, and only for that provider's own hosts.
 * A key that answers 401/403/429 rests for 10 minutes and the page falls back to keyless public endpoints.
 * This is obfuscation, not unbreakable: a determined person with the APK can still extract a built-in key.
 */
object HwKeys {
    val NAMES = listOf("alchemy", "etherscan", "helius", "trongrid", "lifi", "jupiter", "reown", "koios")
    private val HOSTS = mapOf(
        "alchemy" to listOf(".g.alchemy.com"),
        "etherscan" to listOf("api.etherscan.io"),
        "helius" to listOf(".helius-rpc.com", "api.helius.xyz"),
        "trongrid" to listOf("api.trongrid.io"),
        "lifi" to listOf("li.quest"),
        "jupiter" to listOf("api.jup.ag"),
        "reown" to listOf("relay.walletconnect.org", "relay.walletconnect.com", "rpc.walletconnect.org", "rpc.walletconnect.com"),
        "koios" to listOf("api.koios.rest")   // v1.9.38: the user's own Koios key (Bearer), never sent to another host
    )
    private val PH = Regex("\\{\\{K:([a-z]+)\\}\\}")
    private val cache = HashMap<String, String>()
    private val rest = HashMap<String, Long>()
    @Volatile private var opened = false
    private const val PREF = "hw_keys"

    @Synchronized private fun open() {
        if (opened) return
        opened = true
        try {
            val m = HwSa.m
            val k = ByteArray(32) { i ->
                (((HwSa.q[i] - m) and 0xff) xor HwSb.e[i] xor ((HwSa.w[i] - (255 - m)) and 0xff) xor ((HwSb.r[i] - 101) and 0xff)).toByte()
            }
            for ((name, blob) in HwSa.d) {
                try {
                    val b = ByteArray(blob.size) { blob[it].toByte() }
                    val c = Cipher.getInstance("AES/GCM/NoPadding")
                    c.init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, b, 0, 12))
                    c.updateAAD(name.toByteArray())
                    cache[name] = String(c.doFinal(b, 12, b.size - 12))
                } catch (_: Throwable) {}
            }
            k.fill(0)
        } catch (_: Throwable) {}
    }

    private fun user(c: Context, name: String): String =
        try { c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(name, "") ?: "" } catch (_: Throwable) { "" }

    @Synchronized fun get(c: Context, name: String): String {
        if (name !in NAMES) return ""
        val until = rest[name] ?: 0L
        if (until > System.currentTimeMillis()) return ""
        val u = user(c, name); if (u.isNotEmpty()) return u
        open(); return cache[name] ?: ""
    }

    /** "user" (own key), "app" (built-in), or "" (keyless right now: none, or resting after a quota error). */
    @Synchronized fun state(c: Context, name: String): String {
        if (name !in NAMES) return ""
        if ((rest[name] ?: 0L) > System.currentTimeMillis()) return ""
        if (user(c, name).isNotEmpty()) return "user"
        open(); return if (cache[name].isNullOrEmpty()) "" else "app"
    }

    /** The user's own key (Settings > API keys). Empty clears it. Never logged, never handed back to the page. */
    fun setUser(c: Context, name: String, v: String) {
        if (name !in NAMES) return
        val t = v.trim().take(200)
        try { c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().apply { if (t.isEmpty()) remove(name) else putString(name, t) }.apply() } catch (_: Throwable) {}
        synchronized(this) { rest.remove(name) }
    }

    @Synchronized fun cool(name: String) { rest[name] = System.currentTimeMillis() + 10 * 60_000L }

    private fun hostOk(name: String, host: String): Boolean {
        val h = host.lowercase()
        return HOSTS[name]?.any { s -> if (s.startsWith(".")) h.endsWith(s) else h == s } == true
    }

    /** Swaps placeholders for keys. Returns the text and the providers used, or null when a placeholder may not be
     *  sent to this host (or the key is missing), so the caller fails the request and the page falls back. */
    fun fill(c: Context, host: String, text: String, used: MutableSet<String>): String? {
        if (!text.contains("{{K:")) return text
        var bad = false
        val out = PH.replace(text) { m ->
            val n = m.groupValues[1]; val k = if (hostOk(n, host)) get(c, n) else ""
            if (k.isEmpty()) { bad = true; "" } else { used.add(n); k }
        }
        return if (bad) null else out
    }
}
