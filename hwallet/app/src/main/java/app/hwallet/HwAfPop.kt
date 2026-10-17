package app.hwallet

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.webkit.WebView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.PathParser
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * v1.8.35 (B13): the Browser's autofill dropdown, drawn natively OVER the tab (like Chrome's), so nothing saved is ever in
 * the page's DOM. The page script only reports where the focused field is (#hw-af: af_focus / af_move / af_blur); Hwallet's
 * page decides the rows (labels only: username, masked password, email, address name) and calls HwNative.brAfPop. A tap
 * answers window.hwAfPicked(tab, tok, index): index >= 0 = that row, -1 = "Manage…", -2 = closed without a pick.
 * Values (and passwords, after device auth) go to the page only then. Two parts: the dropdown under (or above) the field
 * and, while the keyboard is up, an accessory chip bar right above the keyboard (Android Chrome).
 * Spec JSON: {tok, r:{x,y,w,h,W} (visual-viewport CSS px; W = viewport width in the same units), rows:[{t,s,p}] (p = 24-unit
 * SVG path of the row icon), foot, fp, acc, c:{bg,fg,mu,ln,c2,ac}}. Nothing here is stored.
 */
class HwAfPop(private val act: Activity, private val cb: (String, Int, Int) -> Unit) {
    private var drop: PopupWindow? = null
    private var acc: PopupWindow? = null
    private var dropBox: View? = null
    private var tab: String? = null
    private var tok = 0
    private var web: WebView? = null
    private var rect: JSONObject? = null
    private var quiet = false

    val isOpen: Boolean get() = drop?.isShowing == true || acc?.isShowing == true
    fun tabId(): String? = tab

    private fun dp(v: Float): Int = (v * act.resources.displayMetrics.density).roundToInt()
    private fun col(c: JSONObject?, k: String, def: Int): Int = try { Color.parseColor(c?.optString(k) ?: "") } catch (_: Throwable) { def }

    /** a 24×24 SVG path (Material icon) as a drawable */
    private class Ico(d: String, private val color: Int) : Drawable() {
        private val path: Path? = try { PathParser.createPathFromPathData(d) } catch (_: Throwable) { null }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = this@Ico.color; style = Paint.Style.FILL }
        override fun draw(c: Canvas) { val p = path ?: return; val b = bounds; c.save(); c.translate(b.left.toFloat(), b.top.toFloat()); c.scale(b.width() / 24f, b.height() / 24f); c.drawPath(p, paint); c.restore() }
        override fun setAlpha(a: Int) { paint.alpha = a }
        override fun setColorFilter(f: ColorFilter?) { paint.colorFilter = f }
        @Deprecated("Deprecated in Java") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private fun ripple(ink: Int, radius: Float): Drawable = RippleDrawable(ColorStateList.valueOf(ink), null, GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = radius })

    private fun iconBox(d: String, fg: Int, bg: Int, size: Int, inner: Int): View {
        val v = View(act)
        val bgD = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(bg) }
        val ic = Ico(d, fg)
        v.background = object : Drawable() {
            override fun draw(c: Canvas) { val b = bounds; bgD.bounds = b; bgD.draw(c); val o = (b.width() - inner) / 2; ic.setBounds(b.left + o, b.top + o, b.left + o + inner, b.top + o + inner); ic.draw(c) }
            override fun setAlpha(a: Int) {}
            override fun setColorFilter(f: ColorFilter?) {}
            @Deprecated("Deprecated in Java") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
        v.layoutParams = LinearLayout.LayoutParams(size, size)
        return v
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean): TextView = TextView(act).apply {
        text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        typeface = android.graphics.Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", android.graphics.Typeface.NORMAL)
        includeFontPadding = false
    }

    private fun pick(i: Int) {
        val t = tab ?: return; val k = tok
        closeQuiet(); cb(t, k, i)
    }

    fun show(id: String, w: WebView, json: String) {
        val o = try { JSONObject(json) } catch (_: Throwable) { closeQuiet(); return }
        val rows = o.optJSONArray("rows")
        if (rows == null || rows.length() == 0) { closeQuiet(); return }
        closeQuiet()
        tab = id; tok = o.optInt("tok"); web = w; rect = o.optJSONObject("r")
        val c = o.optJSONObject("c")
        val bg = col(c, "bg", Color.WHITE); val fg = col(c, "fg", 0xFF202124.toInt()); val mu = col(c, "mu", 0xFF5F6368.toInt())
        val ln = col(c, "ln", 0xFFE6E8EB.toInt()); val c2 = col(c, "c2", 0xFFF1F3F4.toInt()); val ac = col(c, "ac", fg)
        val ink = (ac and 0x00FFFFFF) or 0x33000000
        val n = min(rows.length(), 12)

        // ---- the dropdown
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(16f).toFloat(); setStroke(max(1, dp(1f)), ln) }
            elevation = dp(10f).toFloat(); clipToOutline = true; outlineProvider = ViewOutlineProvider.BACKGROUND
        }
        val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6f), 0, dp(6f)) }
        for (i in 0 until n) {
            val r = rows.optJSONObject(i) ?: continue
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(56f)
                setPadding(dp(14f), dp(8f), dp(16f), dp(8f)); background = ripple(ink, 0f); isClickable = true
                contentDescription = r.optString("t") + ", " + r.optString("s")
                setOnClickListener { pick(i) }
            }
            row.addView(iconBox(r.optString("p"), ac, c2, dp(36f), dp(20f)))
            val col2 = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14f), 0, 0, 0) }
            col2.addView(text(r.optString("t"), 15.5f, fg, true))
            val sub = r.optString("s"); if (sub.isNotEmpty()) col2.addView(text(sub, 13f, mu, false).apply { setPadding(0, dp(3f), 0, 0) })
            row.addView(col2, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val sc = ScrollView(act).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(list) }
        box.addView(sc, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { weight = 1f })
        val foot = o.optString("foot")
        if (foot.isNotEmpty()) {
            box.addView(View(act).apply { setBackgroundColor(ln) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, max(1, dp(1f))))
            val fr = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(50f)
                setPadding(dp(14f), 0, dp(16f), 0); background = ripple(ink, 0f); isClickable = true; contentDescription = foot
                setOnClickListener { pick(-1) }
            }
            fr.addView(iconBox(o.optString("fp"), mu, bg, dp(36f), dp(20f)))
            fr.addView(text(foot, 14.5f, ac, true).apply { setPadding(dp(14f), 0, 0, 0) })
            box.addView(fr, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        dropBox = box
        drop = PopupWindow(box, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); isOutsideTouchable = false; isTouchable = true
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED; elevation = dp(10f).toFloat(); isClippingEnabled = true
            setOnDismissListener { if (!quiet) gone() }
        }

        // ---- the keyboard accessory bar (chips), shown only while the keyboard is up
        if (o.optBoolean("acc", true)) {
            val bar = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8f), 0, dp(8f), 0) }
            for (i in 0 until n) {
                val r = rows.optJSONObject(i) ?: continue
                val chip = LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10f), 0, dp(14f), 0)
                    background = RippleDrawable(ColorStateList.valueOf(ink), GradientDrawable().apply { setColor(c2); cornerRadius = dp(18f).toFloat() }, null)
                    isClickable = true; contentDescription = r.optString("t"); setOnClickListener { pick(i) }
                }
                chip.addView(View(act).apply { background = Ico(r.optString("p"), ac) }, LinearLayout.LayoutParams(dp(18f), dp(18f)))
                chip.addView(text(r.optString("t"), 14f, fg, true).apply { setPadding(dp(8f), 0, 0, 0); maxWidth = dp(220f) })
                bar.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36f)).apply { marginEnd = dp(8f) })
            }
            if (foot.isNotEmpty()) {
                val m = LinearLayout(act).apply {
                    gravity = Gravity.CENTER; setPadding(dp(9f), 0, dp(9f), 0); isClickable = true; contentDescription = foot
                    background = RippleDrawable(ColorStateList.valueOf(ink), GradientDrawable().apply { setColor(bg); setStroke(max(1, dp(1f)), ln); cornerRadius = dp(18f).toFloat() }, null)
                    setOnClickListener { pick(-1) }
                }
                m.addView(View(act).apply { background = Ico(o.optString("fp"), mu) }, LinearLayout.LayoutParams(dp(18f), dp(18f)))
                bar.addView(m, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36f)))
            }
            val hs = HorizontalScrollView(act).apply {
                isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
                background = GradientDrawable().apply { setColor(bg) }
                addView(bar, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            val wrap = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
            wrap.addView(View(act).apply { setBackgroundColor(ln) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, max(1, dp(1f))))
            wrap.addView(hs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            acc = PopupWindow(wrap, ViewGroup.LayoutParams.MATCH_PARENT, dp(52f), false).apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); isOutsideTouchable = false; isTouchable = true
                inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED; isClippingEnabled = true
                setOnDismissListener { if (!quiet) gone() }
            }
        }
        place()
    }

    /** the field moved (scroll, zoom, keyboard): follow it */
    fun move(id: String, json: String) {
        if (id != tab || drop == null) return
        rect = try { JSONObject(json) } catch (_: Throwable) { return }
        place()
    }

    private fun hideQuiet(p: PopupWindow?) { if (p?.isShowing == true) { quiet = true; try { p.dismiss() } catch (_: Throwable) {}; quiet = false } }

    private fun place() {
        val w = web ?: return; val d = drop ?: return; val r = rect ?: return; val box = dropBox ?: return
        if (!w.isAttachedToWindow || w.visibility != View.VISIBLE || w.windowToken == null) { hideQuiet(d); hideQuiet(acc); return }
        val vw = r.optDouble("W", 0.0)
        val k = if (vw > 1.0 && w.width > 0) w.width / vw else act.resources.displayMetrics.density.toDouble()
        val loc = IntArray(2); w.getLocationOnScreen(loc)
        val fx = loc[0] + r.optDouble("x", 0.0) * k; val fy = loc[1] + r.optDouble("y", 0.0) * k
        val fw = r.optDouble("w", 0.0) * k; val fh = r.optDouble("h", 0.0) * k
        val vis = Rect(); w.getWindowVisibleDisplayFrame(vis)
        val webTop = max(loc[1], vis.top); val webBot = min(loc[1] + w.height, vis.bottom)
        val imeUp = vis.bottom < w.rootView.height - dp(100f)
        val a = acc
        val accH = if (a != null && imeUp) dp(52f) else 0
        val limit = webBot - accH
        // the accessory bar sits right above the keyboard while it is up
        if (a != null) {
            if (imeUp) { if (a.isShowing) a.update(loc[0], limit, w.width, accH) else try { a.width = w.width; a.height = accH; a.showAtLocation(w, Gravity.NO_GRAVITY, loc[0], limit) } catch (_: Throwable) {} }
            else hideQuiet(a)
        }
        if (fy + fh < webTop || fy > limit) { hideQuiet(d); return }   // the field scrolled out of view: hidden until it is back
        val width = min(max(fw.roundToInt(), dp(264f)), w.width - dp(16f))
        val x = fx.roundToInt().coerceIn(loc[0] + dp(8f), max(loc[0] + dp(8f), loc[0] + w.width - width - dp(8f)))
        box.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(dp(2000f), View.MeasureSpec.AT_MOST))
        var h = min(box.measuredHeight, dp(4 * 56f + 12f + 51f))
        val below = (limit - (fy + fh) - dp(6f)).toInt(); val above = (fy - webTop - dp(6f)).toInt()
        val y: Int
        if (h <= below || below >= above) { h = min(h, max(below, dp(64f))); y = (fy + fh).roundToInt() + dp(4f) }
        else { h = min(h, above); y = fy.roundToInt() - dp(4f) - h }
        if (h < dp(56f)) { hideQuiet(d); return }
        if (d.isShowing) d.update(x, y, width, h)
        else try { d.width = width; d.height = h; d.showAtLocation(w, Gravity.NO_GRAVITY, x, y) } catch (_: Throwable) {}
    }

    private fun gone() {
        val t = tab; val k = tok
        hideQuiet(drop); hideQuiet(acc); clear()
        if (t != null) cb(t, k, -2)
    }

    private fun clear() { drop = null; acc = null; dropBox = null; tab = null; web = null; rect = null }

    /** closed by Hwallet (blur, navigation, tab hidden, a pick): no -2 answer, the caller already knows */
    fun closeQuiet() { hideQuiet(drop); hideQuiet(acc); clear() }

    /** closed by the user (Back) or because the tab went away: the page's pending request gets -2 */
    fun close() { if (tab != null) gone() else closeQuiet() }
}
