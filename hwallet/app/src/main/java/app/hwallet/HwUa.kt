package app.hwallet

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * v1.8.7 (push 12): user-agent manager for Hwallet's dApp browser.
 * Off = the WebView's own user agent (with "; wv" removed, as before). On = one preset (a phone, tablet or desktop
 * with its browser) or a Custom string. The page also sees a matching navigator.platform / navigator.userAgentData
 * (removed for Safari and Firefox, which have none), so sites don't detect a mismatch in script.
 * Version numbers are current as of October 2026 (Chrome 141, Edge 141, Firefox 144, Safari 26, Samsung Internet 28).
 */
object HwUa {
    class P(val id: String, val group: String, val label: String, val ua: String, val platform: String, val mobile: Boolean, val hints: String)

    private const val CH = "141.0.0.0"
    private const val AND = "Mozilla/5.0 (Linux; Android %s; %s) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$CH %sSafari/537.36"
    private fun chromeAnd(v: String, m: String, mob: Boolean = true) = String.format(AND, v, m, if (mob) "Mobile " else "")

    val PRESETS = listOf(
        // phones
        P("pixel10", "Phones", "Pixel 10 Pro · Chrome", chromeAnd("16", "Pixel 10 Pro"), "Linux armv8l", true, "chrome"),
        P("pixel9", "Phones", "Pixel 9 · Chrome", chromeAnd("16", "Pixel 9"), "Linux armv8l", true, "chrome"),
        P("s26u", "Phones", "Galaxy S26 Ultra · Chrome", chromeAnd("16", "SM-S948B"), "Linux armv8l", true, "chrome"),
        P("s25u", "Phones", "Galaxy S25 Ultra · Samsung Internet", "Mozilla/5.0 (Linux; Android 15; SM-S938B) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/28.0 Chrome/130.0.0.0 Mobile Safari/537.36", "Linux armv8l", true, "samsung"),
        P("xiaomi", "Phones", "Xiaomi 15 Ultra · Chrome", chromeAnd("15", "25019PNF3G"), "Linux armv8l", true, "chrome"),
        P("redmi", "Phones", "Redmi Note 14 Pro · Chrome", chromeAnd("15", "24115RA8EG"), "Linux armv8l", true, "chrome"),
        P("oneplus", "Phones", "OnePlus 13 · Chrome", chromeAnd("15", "CPH2653"), "Linux armv8l", true, "chrome"),
        P("iphone17", "Phones", "iPhone 17 Pro · Safari", "Mozilla/5.0 (iPhone; CPU iPhone OS 26_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Mobile/15E148 Safari/604.1", "iPhone", true, "none"),
        P("iphone16", "Phones", "iPhone 16 · Safari", "Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.6 Mobile/15E148 Safari/604.1", "iPhone", true, "none"),
        P("ffand", "Phones", "Android · Firefox", "Mozilla/5.0 (Android 16; Mobile; rv:144.0) Gecko/144.0 Firefox/144.0", "Linux armv8l", true, "none"),
        P("edgeand", "Phones", "Android · Edge", chromeAnd("16", "K") + " EdgA/141.0.0.0", "Linux armv8l", true, "edge"),
        P("operaand", "Phones", "Android · Opera", chromeAnd("16", "K") + " OPR/91.0.0.0", "Linux armv8l", true, "opera"),
        P("brave", "Phones", "Android · Brave", chromeAnd("16", "K"), "Linux armv8l", true, "brave"),
        P("ddg", "Phones", "Android · DuckDuckGo", chromeAnd("16", "K") + " DuckDuckGo/5", "Linux armv8l", true, "chrome"),
        // tablets
        P("tabs10", "Tablets", "Galaxy Tab S10 · Chrome", chromeAnd("15", "SM-X920", false), "Linux armv8l", false, "chrome"),
        P("ipadpro", "Tablets", "iPad Pro · Safari", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15", "MacIntel", false, "none"),
        // desktop
        P("winchrome", "Desktop", "Windows · Chrome", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$CH Safari/537.36", "Win32", false, "chrome"),
        P("winedge", "Desktop", "Windows · Edge", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$CH Safari/537.36 Edg/141.0.0.0", "Win32", false, "edge"),
        P("winff", "Desktop", "Windows · Firefox", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:144.0) Gecko/20100101 Firefox/144.0", "Win32", false, "none"),
        P("winopera", "Desktop", "Windows · Opera", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$CH Safari/537.36 OPR/122.0.0.0", "Win32", false, "opera"),
        P("macchrome", "Desktop", "macOS · Chrome", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$CH Safari/537.36", "MacIntel", false, "chrome"),
        P("macsafari", "Desktop", "macOS · Safari", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15", "MacIntel", false, "none"),
        P("macff", "Desktop", "macOS · Firefox", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:144.0) Gecko/20100101 Firefox/144.0", "MacIntel", false, "none"),
        P("linuxchrome", "Desktop", "Linux · Chrome", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$CH Safari/537.36", "Linux x86_64", false, "chrome"),
        P("linuxff", "Desktop", "Linux · Firefox", "Mozilla/5.0 (X11; Linux x86_64; rv:144.0) Gecko/20100101 Firefox/144.0", "Linux x86_64", false, "none")
    )

    private lateinit var prefs: android.content.SharedPreferences
    fun init(ctx: Context) { if (!::prefs.isInitialized) prefs = ctx.getSharedPreferences("hwua", Context.MODE_PRIVATE) }
    fun on(): Boolean = ::prefs.isInitialized && prefs.getBoolean("on", false)
    fun id(): String = if (::prefs.isInitialized) prefs.getString("id", "pixel10") ?: "pixel10" else "pixel10"
    fun custom(): String = if (::prefs.isInitialized) prefs.getString("custom", "") ?: "" else ""
    fun set(on: Boolean, id: String, custom: String) {
        prefs.edit().putBoolean("on", on).putString("id", if (id == "custom" || PRESETS.any { it.id == id }) id else "pixel10")
            .putString("custom", custom.take(400).replace(Regex("[\\r\\n]"), " ")).apply()
    }
    private fun cur(): P? = if (!on()) null else if (id() == "custom") custom().takeIf { it.length > 10 }?.let { fromString(it) } else PRESETS.firstOrNull { it.id == id() }

    /** v1.8.9 (R18): a Custom string read like a preset, so navigator.platform / maxTouchPoints / userAgentData agree with it */
    fun fromString(ua: String): P {
        val plat = when {
            ua.contains("Windows") -> "Win32"
            ua.contains("iPhone") -> "iPhone"
            ua.contains("iPad") -> "iPad"
            ua.contains("Macintosh") || ua.contains("Mac OS X") -> "MacIntel"
            ua.contains("Android") -> "Linux armv8l"
            ua.contains("CrOS") -> "Linux x86_64"
            ua.contains("X11") || ua.contains("Linux") -> "Linux x86_64"
            else -> ""
        }
        val mobile = ua.contains("Mobile") || ua.contains("iPhone") || (ua.contains("Android") && !ua.contains("Tablet") && ua.contains("Mobile"))
        val chromium = Regex("Chrome/\\d+").containsMatchIn(ua) && !ua.contains("Firefox/")
        val hints = when { !chromium -> "none"; ua.contains("Edg") -> "edge"; ua.contains("OPR/") -> "opera"; ua.contains("SamsungBrowser") -> "samsung"; else -> "chrome" }
        return P("custom", "Custom", "Custom", ua, plat, mobile, hints)
    }
    /** a desktop user agent: no Android / iPhone / iPad / "Mobile" in it */
    fun isDesktop(ua: String): Boolean = !(ua.contains("Android") || ua.contains("iPhone") || ua.contains("iPad") || ua.contains("Mobile"))
    /** v1.8.9 (R18): Desktop view as the default for new tabs (the last state the user chose in the menu or in Settings) */
    fun deskDef(): Boolean = ::prefs.isInitialized && prefs.getBoolean("deskDef", false)
    fun setDeskDef(on: Boolean) { if (::prefs.isInitialized) prefs.edit().putBoolean("deskDef", on).apply() }
    /** the chosen user agent is itself a desktop one (Settings > User agent = a Desktop preset or a desktop Custom string) */
    fun chosenIsDesktop(): Boolean = cur()?.let { isDesktop(it.ua) } ?: false
    /** the preset a tab uses: desktop view = the chosen user agent if it is a desktop one, else Windows Chrome;
     *  phone view = the chosen user agent if it is a phone / tablet one, else the WebView's own (null) */
    fun presetFor(desk: Boolean): P? { val c = cur(); return if (desk) (if (c != null && isDesktop(c.ua)) c else DESK) else (if (c != null && !isDesktop(c.ua)) c else null) }
    fun uaFor(desk: Boolean, base: String): String = presetFor(desk)?.ua ?: base.replace("; wv)", ")")

    /** the user agent for a new or updated tab (base = the WebView's default) */
    fun ua(base: String): String = cur()?.ua ?: base.replace("; wv)", ")")
    fun label(): String = if (!on()) "Off" else cur()?.label ?: "Off"

    /** document-start script so navigator.* agrees with the header; null = nothing to change */
    /** v1.8.8 (R4b): the one-tap desktop view of a tab (Windows Chrome) */
    val DESK: P get() = PRESETS.first { it.id == "winchrome" }
    fun script(): String? { val p = cur() ?: return null; return scriptFor(p) }
    fun scriptFor(p: P, nativeHints: Boolean = false): String {
        val sb = StringBuilder("(function(){try{var N=Navigator.prototype,d=function(k,v){try{Object.defineProperty(N,k,{get:function(){return v},configurable:true})}catch(e){}};")
        if (p.platform.isNotEmpty()) sb.append("d('platform',").append(JSONObject.quote(p.platform)).append(");")
        sb.append("d('maxTouchPoints',").append(if (p.mobile || p.id == "ipadpro" || p.platform == "iPad") 5 else 0).append(");")
        when (if (nativeHints) "keep" else p.hints) {
            "none" -> sb.append("d('userAgentData',undefined);")
            "keep" -> {}
            else -> {
                val brand = when (p.hints) { "edge" -> "Microsoft Edge"; "opera" -> "Opera"; "samsung" -> "Samsung Internet"; "brave" -> "Brave"; else -> "Google Chrome" }
                val ver = when (p.hints) { "opera" -> if (p.mobile) "91" else "122"; "samsung" -> "28"; else -> "141" }
                val plat = when { p.platform.startsWith("Win") -> "Windows"; p.platform.startsWith("Mac") -> "macOS"; p.platform.contains("x86_64") -> "Linux"; else -> "Android" }
                sb.append("var B=[{brand:'Not)A;Brand',version:'8'},{brand:'Chromium',version:'141'},{brand:").append(JSONObject.quote(brand)).append(",version:'").append(ver).append("'}];")
                sb.append("var U={brands:B,mobile:").append(p.mobile).append(",platform:").append(JSONObject.quote(plat))
                    .append(",getHighEntropyValues:function(){return Promise.resolve({brands:B,mobile:").append(p.mobile).append(",platform:").append(JSONObject.quote(plat))
                    .append(",fullVersionList:B,uaFullVersion:'141.0.0.0'})},toJSON:function(){return{brands:B,mobile:").append(p.mobile).append(",platform:").append(JSONObject.quote(plat)).append("}}};d('userAgentData',U);")
            }
        }
        sb.append("}catch(e){}})();")
        return sb.toString()
    }

    /** v1.8.9 (R18): desktop layout. Sites size to a 1024 px wide viewport whatever their meta viewport says (it is
     *  rewritten at document start and whenever the page changes it); the WebView's wide viewport + overview mode then
     *  fit that width to the screen, like Chrome's "Desktop site". */
    const val VIEWPORT_JS = "(function(){try{if(window.top!==window)return;var W='width=1024',fix=function(){var h=document.head||document.documentElement;if(!h)return;" +
        "var ms=document.querySelectorAll('meta[name=viewport]');if(!ms.length){var m=document.createElement('meta');m.setAttribute('name','viewport');m.setAttribute('content',W);h.appendChild(m);return}" +
        "for(var i=0;i<ms.length;i++)if(ms[i].getAttribute('content')!==W)ms[i].setAttribute('content',W)};fix();" +
        "var mo=new MutationObserver(function(rs){for(var i=0;i<rs.length;i++){var r=rs[i];if(r.type==='attributes'){fix();return}for(var j=0;j<r.addedNodes.length;j++){var n=r.addedNodes[j];if(n.nodeName==='META'||n.nodeName==='HEAD'){fix();return}}}});" +
        "mo.observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['content']});" +
        "document.addEventListener('DOMContentLoaded',function(){fix();setTimeout(function(){fix();mo.disconnect();mo.observe(document.head||document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['content']})},4000)})}catch(e){}})();"

    fun state(): String {
        val a = JSONArray(); PRESETS.forEach { a.put(JSONObject().put("id", it.id).put("g", it.group).put("l", it.label)) }
        return JSONObject().put("on", on()).put("id", id()).put("custom", custom()).put("label", label()).put("deskDef", deskDef()).put("presets", a).toString()
    }
}
