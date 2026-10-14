package app.hwallet

import android.content.Context
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v1.8.7 (push 12): Hwallet's content blocker for the dApp browser.
 *
 * It reads uBlock Origin's own default filter lists (uBlock filters, privacy, badware, quick fixes, unbreak, EasyList,
 * EasyPrivacy, Peter Lowe's list, URLhaus) in uBO's filter syntax and applies them natively inside the WebView:
 *  - network filters: `||host^` anchored rules with `$third-party` / `$1p` / `$domain=` / `$important`, `@@` exceptions,
 *    hosts-file entries, and `||host/path*` prefix rules;
 *  - cosmetic filters: site-specific `site##selector` (and `#@#` exceptions) plus a capped set of generic `##selector`.
 * Scriptlets (`+js(...)`), procedural (`#?#`) and redirect/removeparam rules are not run.
 *
 * The lists are downloaded (never bundled), cached in filesDir/shield and refreshed weekly. Nothing loads until the
 * blocker is on. Wallet, RPC and dApp-critical hosts are always allowed, and a page's own document is never blocked.
 * Filter lists: uBlock Origin uAssets (GPL-3.0), EasyList/EasyPrivacy (GPL-3.0 / CC BY-SA 3.0), Peter Lowe (McRae GPL),
 * URLhaus filter (CC0 / MIT). See hwallet/NOTICE.
 */
object HwShield {
    private const val TAG = "HwShield"
    private const val WEEK = 7L * 24 * 3600 * 1000
    private const val MAX_GENERIC = 2500

    val LISTS = linkedMapOf(
        "ubo" to "https://ublockorigin.github.io/uAssets/filters/filters.min.txt",
        "ubo-privacy" to "https://ublockorigin.github.io/uAssets/filters/privacy.min.txt",
        "ubo-badware" to "https://ublockorigin.github.io/uAssets/filters/badware.min.txt",
        "ubo-quick" to "https://ublockorigin.github.io/uAssets/filters/quick-fixes.min.txt",
        "ubo-unbreak" to "https://ublockorigin.github.io/uAssets/filters/unbreak.min.txt",
        "easylist" to "https://ublockorigin.github.io/uAssets/thirdparties/easylist.txt",
        "easyprivacy" to "https://ublockorigin.github.io/uAssets/thirdparties/easyprivacy.txt",
        "pgl" to "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext",
        "urlhaus" to "https://malware-filter.gitlab.io/malware-filter/urlhaus-filter-ag-online.txt"
    )

    /** wallet / RPC / dApp infrastructure: never blocked, whatever a list says */
    private val ALLOW = arrayOf(
        "walletconnect.com", "walletconnect.org", "reown.com", "web3modal.org", "web3modal.com", "infura.io", "alchemy.com", "alchemyapi.io",
        "quiknode.pro", "quicknode.com", "ankr.com", "llamarpc.com", "publicnode.com", "drpc.org", "blastapi.io", "chainstack.com",
        "helius-rpc.com", "helius.xyz", "solana.com", "mainnet-beta.solana.com", "jup.ag", "jito.wtf", "trongrid.io", "tronweb.network",
        "1rpc.io", "cloudflare-eth.com", "base.org", "arbitrum.io", "optimism.io", "polygon.technology", "binance.org", "bnbchain.org",
        "coingecko.com", "li.fi", "li.quest", "0x.org", "1inch.io", "1inch.dev", "uniswap.org", "pancakeswap.finance", "raydium.io",
        "metamask.io", "phantom.app", "coinbase.com", "privy.io", "dynamic.xyz", "magic.link", "particle.network", "web3auth.io",
        "safe.global", "ens.domains", "thegraph.com", "moralis.io", "covalenthq.com", "etherscan.io", "solscan.io", "tronscan.org",
        "gstatic.com", "recaptcha.net", "hcaptcha.com", "challenges.cloudflare.com", "turnstile.cloudflare.com"
    )

    private class Net(val third: Int, val doms: Array<String>?, val notDoms: Array<String>?, val path: String?, val important: Boolean)

    private val block = HashMap<String, MutableList<Net>>()
    private val allowRules = HashMap<String, MutableList<Net>>()
    private val cosSite = HashMap<String, MutableList<String>>()
    private val cosExc = HashMap<String, MutableSet<String>>()
    private val generic = ArrayList<String>()
    @Volatile private var ready = false
    private val loading = AtomicBoolean(false)
    private lateinit var dir: File
    private lateinit var prefs: android.content.SharedPreferences
    val blocked = ConcurrentHashMap<String, Int>()   // per page origin host, since the page last loaded
    @Volatile var total = 0L

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.getSharedPreferences("hwshield", Context.MODE_PRIVATE)
        dir = File(ctx.filesDir, "shield").apply { mkdirs() }
        total = prefs.getLong("total", 0L)
        if (on()) warm()
    }

    fun on(): Boolean = ::prefs.isInitialized && prefs.getBoolean("on", true)
    fun setOn(v: Boolean) { prefs.edit().putBoolean("on", v).apply(); if (v) warm() }
    fun siteOff(host: String): Boolean = prefs.getStringSet("off", emptySet())!!.contains(host)
    fun setSite(host: String, enabled: Boolean) {
        val s = HashSet(prefs.getStringSet("off", emptySet())!!); if (enabled) s.remove(host) else s.add(host)
        prefs.edit().putStringSet("off", s).apply()
    }
    fun listOn(k: String): Boolean = prefs.getBoolean("l_$k", true)
    fun setList(k: String, v: Boolean) { prefs.edit().putBoolean("l_$k", v).apply(); reload(false) }

    fun state(): String {
        val o = JSONObject().put("on", on()).put("ready", ready).put("loading", loading.get()).put("total", total)
            .put("updated", prefs.getLong("upd", 0L)).put("rules", block.size + allowRules.size)
        val a = JSONArray(); LISTS.keys.forEach { k -> a.put(JSONObject().put("k", k).put("on", listOn(k)).put("cached", File(dir, "$k.txt").exists())) }
        o.put("lists", a)
        val off = JSONArray(); prefs.getStringSet("off", emptySet())!!.forEach { off.put(it) }; o.put("off", off)
        return o.toString()
    }

    /** load cached lists now; download any that are missing or older than a week */
    fun warm() { if (!ready) reload(false) else if (System.currentTimeMillis() - prefs.getLong("upd", 0L) > WEEK) reload(true) }
    fun update() = reload(true)

    private fun reload(force: Boolean) {
        if (!loading.compareAndSet(false, true)) return
        Thread {
            try {
                val stale = force || System.currentTimeMillis() - prefs.getLong("upd", 0L) > WEEK
                var fetched = false
                LISTS.forEach { (k, u) ->
                    if (!listOn(k)) return@forEach
                    val f = File(dir, "$k.txt")
                    if (stale || !f.exists()) try {
                        val r = HwNet.withTimeout(45000).newCall(okhttp3.Request.Builder().url(u).header("User-Agent", "Hwallet/" + BuildConfig.VERSION_NAME).build()).execute()
                        r.use { if (it.isSuccessful) { val b = it.body?.string(); if (b != null && b.length > 200) { File(dir, "$k.tmp").writeText(b); File(dir, "$k.tmp").renameTo(f); fetched = true } } }
                    } catch (e: Throwable) { Log.w(TAG, "list $k not updated", e) }
                }
                if (fetched) prefs.edit().putLong("upd", System.currentTimeMillis()).apply()
                compile()
            } catch (e: Throwable) { Log.w(TAG, "shield load failed", e) } finally { loading.set(false) }
        }.apply { priority = Thread.MIN_PRIORITY; isDaemon = true }.start()
    }

    @Synchronized private fun compile() {
        val b = HashMap<String, MutableList<Net>>(); val a = HashMap<String, MutableList<Net>>()
        val cs = HashMap<String, MutableList<String>>(); val ce = HashMap<String, MutableSet<String>>(); val g = LinkedHashSet<String>()
        LISTS.keys.forEach { k ->
            if (!listOn(k)) return@forEach
            val f = File(dir, "$k.txt"); if (!f.exists()) return@forEach
            try { f.forEachLine { parse(it.trim(), b, a, cs, ce, g) } } catch (e: Throwable) { Log.w(TAG, "parse $k", e) }
        }
        synchronized(this) {
            block.clear(); block.putAll(b); allowRules.clear(); allowRules.putAll(a)
            cosSite.clear(); cosSite.putAll(cs); cosExc.clear(); cosExc.putAll(ce)
            generic.clear(); generic.addAll(g.take(MAX_GENERIC))
            ready = true
        }
    }

    private val HOSTRE = Regex("^[a-z0-9.-]+$")
    private fun parse(l: String, b: HashMap<String, MutableList<Net>>, a: HashMap<String, MutableList<Net>>,
                      cs: HashMap<String, MutableList<String>>, ce: HashMap<String, MutableSet<String>>, g: MutableSet<String>) {
        if (l.isEmpty() || l[0] == '!' || l[0] == '[' || l[0] == '#' && !l.startsWith("##")) return
        // hosts-file lines
        if (l.startsWith("0.0.0.0 ") || l.startsWith("127.0.0.1 ")) {
            val h = l.substringAfter(' ').trim().substringBefore(' ').substringBefore('#').lowercase()
            if (h.contains('.') && h != "localhost" && HOSTRE.matches(h)) b.getOrPut(h) { ArrayList(1) }.add(Net(0, null, null, null, false))
            return
        }
        // cosmetic
        val ci = l.indexOf("##"); val ei = l.indexOf("#@#")
        if (ei >= 0) { val sel = l.substring(ei + 3); if (sel.startsWith("+js") || sel.startsWith("^")) return
            l.substring(0, ei).split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() && !it.startsWith("~") }.forEach { ce.getOrPut(it) { HashSet() }.add(sel) }; return }
        if (l.contains("#?#") || l.contains("#$#") || l.contains("#%#")) return
        if (ci >= 0) {
            val sel = l.substring(ci + 2)
            if (sel.isEmpty() || sel.startsWith("+js") || sel.startsWith("^") || sel.contains(":has-text(") || sel.contains(":-abp-") || sel.contains(":matches-") || sel.contains(":upward(") || sel.contains(":xpath(") || sel.contains(":style(") || sel.contains(":remove(") || sel.contains(":watch-attr(") || sel.contains(":min-text-length(") || sel.contains(":others(")) return
            val d = l.substring(0, ci)
            if (d.isEmpty()) { if (g.size < MAX_GENERIC * 2) g.add(sel); return }
            d.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() && !it.startsWith("~") && !it.contains('*') }.forEach { cs.getOrPut(it) { ArrayList(2) }.add(sel) }
            return
        }
        // network
        var s = l; val exc = s.startsWith("@@"); if (exc) s = s.substring(2)
        var opts = ""; val di = s.lastIndexOf('$'); if (di > 0 && !s.substring(di).contains('/')) { opts = s.substring(di + 1); s = s.substring(0, di) }
        if (!s.startsWith("||")) return
        s = s.substring(2)
        var third = 0; var doms: MutableList<String>? = null; var notD: MutableList<String>? = null; var imp = false
        if (opts.isNotEmpty()) for (o0 in opts.split(',')) {
            val o = o0.trim()
            when {
                o == "third-party" || o == "3p" -> third = 1
                o == "~third-party" || o == "1p" || o == "first-party" -> third = -1
                o == "important" -> imp = true
                o.startsWith("domain=") || o.startsWith("from=") -> o.substringAfter('=').split('|').forEach { d -> val x = d.trim().lowercase(); if (x.startsWith("~")) (notD ?: ArrayList<String>().also { notD = it }).add(x.substring(1)) else if (x.isNotEmpty()) (doms ?: ArrayList<String>().also { doms = it }).add(x) }
                o == "document" || o == "doc" || o == "all" || o == "popup" || o == "popunder" -> return   // page-level rules are not applied
                o.startsWith("redirect") || o.startsWith("removeparam") || o.startsWith("csp") || o.startsWith("replace") || o.startsWith("header") || o.startsWith("permissions") || o.startsWith("urlskip") || o.startsWith("uritransform") || o == "badfilter" || o.startsWith("denyallow") || o.startsWith("to=") || o.startsWith("method") || o == "generichide" || o == "ghide" || o == "elemhide" || o == "ehide" || o == "specifichide" || o == "shide" || o.startsWith("ipaddress") -> return
                o.startsWith("~") -> {}   // negated resource types: still applies to the rest
                else -> {}               // resource types (script, image, xhr, ...): the WebView doesn't say, so the rule applies
            }
        }
        val end = s.indexOfFirst { it == '^' || it == '/' || it == '*' || it == '|' || it == ':' || it == '?' }
        val host = (if (end < 0) s else s.substring(0, end)).lowercase()
        if (!host.contains('.') || !HOSTRE.matches(host)) return
        var path: String? = null
        if (end >= 0) {
            val rest = s.substring(end).trimEnd('|')
            if (rest != "^" && rest.isNotEmpty()) {
                val p = rest.removePrefix("^").removePrefix(":443")
                if (p.startsWith("/")) path = p.replace("^", "").trimEnd('*') else if (p.isNotEmpty() && p != "*") return
                if (path != null && path!!.contains('*') && path!!.indexOf('*') < 2) return
            }
        }
        val n = Net(third, doms?.toTypedArray(), notD?.toTypedArray(), path, imp)
        (if (exc) a else b).getOrPut(host) { ArrayList(1) }.add(n)
    }

    private fun suffixes(h: String): Sequence<String> = sequence { var x = h; while (true) { yield(x); val i = x.indexOf('.'); if (i < 0 || i == x.length - 1) break; x = x.substring(i + 1); if (!x.contains('.')) break } }
    private fun base(h: String): String { val p = h.split('.'); return if (p.size <= 2) h else p.takeLast(if (p[p.size - 2].length <= 3 && p.last().length == 2) 3 else 2).joinToString(".") }
    private fun inDom(page: String, list: Array<String>): Boolean = list.any { page == it || page.endsWith(".$it") }
    private fun hit(rules: List<Net>?, page: String, third: Boolean, path: String): Net? = rules?.firstOrNull { r ->
        (r.third == 0 || (r.third == 1) == third) && (r.doms == null || inDom(page, r.doms)) && (r.notDoms == null || !inDom(page, r.notDoms)) &&
            (r.path == null || pathMatch(path, r.path))
    }
    private fun pathMatch(p: String, pat: String): Boolean {
        if (!pat.contains('*')) return p.startsWith(pat)
        var i = 0; var first = true
        for (seg in pat.split('*')) { if (seg.isEmpty()) { first = false; continue }; val j = if (first) (if (p.startsWith(seg)) 0 else -1) else p.indexOf(seg, i); if (j < 0) return false; i = j + seg.length; first = false }
        return true
    }

    private val EMPTY = ByteArray(0)
    /** called from shouldInterceptRequest (WebView background thread). null = let it load */
    fun check(req: WebResourceRequest, pageUrl: String?): WebResourceResponse? {
        if (!ready || !on() || req.isForMainFrame) return null
        val u = req.url; val h = (u.host ?: return null).lowercase(); val sch = u.scheme ?: return null
        if (sch != "https" && sch != "http" && sch != "wss" && sch != "ws") return null
        val page = try { android.net.Uri.parse(pageUrl ?: "").host?.lowercase() ?: "" } catch (_: Throwable) { "" }
        if (page.isNotEmpty() && siteOff(page)) return null
        if (ALLOW.any { h == it || h.endsWith(".$it") }) return null
        val third = page.isEmpty() || base(page) != base(h)
        val path = (u.encodedPath ?: "/") + (u.encodedQuery?.let { "?$it" } ?: "")
        var bad: Net? = null
        synchronized(this) {
            for (sfx in suffixes(h)) { bad = hit(block[sfx], page, third, path); if (bad != null) break }
            if (bad == null) return null
            if (!bad!!.important) for (sfx in suffixes(h)) if (hit(allowRules[sfx], page, third, path) != null) return null
        }
        if (page.isNotEmpty()) blocked.merge(page, 1) { x, y -> x + y }
        total++; if (total % 25 == 0L) prefs.edit().putLong("total", total).apply()
        return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", mapOf("Access-Control-Allow-Origin" to "*"), ByteArrayInputStream(EMPTY))
    }

    /** the CSS a page gets (site-specific + generic hides), injected after the page commits */
    fun css(pageUrl: String?): String {
        if (!ready || !on()) return ""
        val page = try { android.net.Uri.parse(pageUrl ?: "").host?.lowercase() ?: "" } catch (_: Throwable) { "" }
        if (page.isEmpty() || siteOff(page)) return ""
        val sel = LinkedHashSet<String>(); val exc = HashSet<String>()
        synchronized(this) {
            for (sfx in suffixes(page)) { cosSite[sfx]?.let { sel.addAll(it) }; cosExc[sfx]?.let { exc.addAll(it) } }
            if (ALLOW.none { page == it || page.endsWith(".$it") }) sel.addAll(generic)
        }
        sel.removeAll(exc)
        if (sel.isEmpty()) return ""
        val sb = StringBuilder()
        // one rule per selector: a selector this WebView doesn't know drops only its own rule
        sel.forEach { c -> if (c.length < 600 && !c.contains('{') && !c.contains('}')) sb.append(c).append("{display:none!important}\n") }
        return sb.toString()
    }

    fun resetCount(page: String) { blocked.remove(page) }
    fun count(page: String): Int = blocked[page] ?: 0
}
