package app.hwallet

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/**
 * v1.7.4 (#108): pull to refresh for Browser tabs (https and http pages alike: it is native, nothing is injected into the page).
 * Drawn over the visible tab in the selected UI style (Settings > UI style, sent by the page through HwNative.brPtrUi):
 *  - "og":    classic detach. The page follows the finger with rubber-band resistance, a plain spinner turns with the pull and
 *             spins while the page reloads, then the page eases back.
 *  - "m3":    Material 3 (androidx.compose.material3 PullToRefresh): drag x0.5, 80dp positional threshold with the
 *             t - t^2/4 tension past it, a 40dp surface circle with the CircularArrowProgressIndicator (arc radius 5.5dp,
 *             stroke 2.5dp, arrow 10x5dp, sweep up to 0.8 turn, alpha 0.3 -> 1 at the threshold), indeterminate spinner while
 *             refreshing; the page itself does not move.
 *  - "miuix": Miuix (github.com/compose-miuix-ui/miuix, Apache-2.0, PullToRefresh.kt + SpringUtils.kt): damping x - x^2 + x^3/3
 *             over the view height, visual threshold H/24, trigger 0.25 x H/3, 20dp ring (stroke size/11) that fades in,
 *             stretches into a capsule past the threshold, orbiting dot while refreshing (800 ms per turn), shrinks and fades
 *             in 200 ms when done; texts "Pull down to refresh / Release to refresh / Refreshing... / Refreshed successfully";
 *             critical-damped spring with a 0.4 s period; haptic at the threshold; the page moves down by the header height.
 * The view never takes touches; HwBrowser feeds it the finger distance and moves the WebView by [contentOffset].
 */
class HwPull(ctx: Context) : View(ctx) {
    var style = "og"
    var dark = false
    var onOffset: (Float) -> Unit = {}

    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; textSize = 14f * ctx.resources.displayMetrics.density }
    private val arc = RectF()
    private val path = Path()

    /** idle, pull, arm, ref, done */
    var state = "idle"
        private set
    private var off = 0f          // px: og = band px, m3 = indicator offset, miuix = dragOffset
    private var comp = 1f         // miuix complete progress / m3 + og hide progress
    private var rot = 0f
    private var t0 = 0L
    private var anim: Runnable? = null

    val busy: Boolean get() = state == "ref"

    private fun h(): Float = max(1f, height.toFloat().takeIf { it > 0f } ?: (resources.displayMetrics.heightPixels.toFloat()))
    private fun mxThr() = h() / 24f
    private fun mxTrig() = 0.25f * damp(1f, h())
    private fun damp(x0: Float, range: Float): Float { val x = max(0f, min(x0, 1f)).toDouble(); return ((x - x.pow(2.0) + x.pow(3.0) / 3.0) * range).toFloat() }
    private fun mxVp(): Float { val e = min(mxThr(), mxTrig()); return if (e > 0f) (off / e).coerceIn(0f, 1f) else 1f }
    private fun m3Off(pulled: Float): Float { val th = 80f * d; if (pulled <= th) return pulled; val t = (pulled / th - 1f).coerceIn(0f, 2f); return th + th * (t - t * t / 4f) }
    private fun ogBand(dy: Float): Float { val mx = 120f * d; return mx * (1f - exp(-max(0f, dy) / (mx * 1.6f))) }

    fun contentOffset(): Float = when (style) {
        "m3" -> 0f
        "miuix" -> mxHeader()
        else -> if (state == "idle") 0f else off
    }
    private fun mxHeader(): Float {
        if (state == "idle") return 0f
        val c = 20f * d; val extra = off - mxThr()
        if (state == "done") return (c + 36f * d) * (1f - comp)
        return if (extra <= 0f) (c + 36f * d) * mxVp() else (c + 36f * d) + extra
    }

    /** finger distance (raw px, >= 0) -> true while past the threshold */
    fun drag(dy: Float): Boolean {
        if (state == "ref" || state == "done") return false
        stop()
        val was = state
        val armed: Boolean
        when (style) {
            "m3" -> { off = m3Off(dy * 0.5f); armed = off >= 80f * d }
            "miuix" -> { val H = h(); val t = dy.coerceIn(-H, H); off = max(0f, sign(t) * damp(min(abs(t) / H, 1f), H)); armed = off > 0f && off >= mxTrig() }
            else -> { off = ogBand(dy); armed = off >= 64f * d }
        }
        state = if (off <= 0f) "idle" else if (armed) "arm" else "pull"
        if (state == "arm" && was != "arm" && style != "m3") try { performHapticFeedback(if (style == "miuix") HapticFeedbackConstants.CONTEXT_CLICK else HapticFeedbackConstants.CLOCK_TICK) } catch (_: Throwable) {}
        changed()
        return armed
    }

    /** finger up: true = refresh now (the caller reloads, then calls [finish]) */
    fun release(): Boolean {
        if (state == "arm") { startRefresh(); return true }
        if (state == "pull") cancel()
        return false
    }

    fun startRefresh() {
        stop(); t0 = System.currentTimeMillis(); rot = (Math.random() * 360).toFloat()
        when (style) {
            "m3" -> { val from = off; val to = 80f * d; state = "ref"; tween(250) { k -> off = from + (to - from) * ease(k); true } }
            "miuix" -> { if (state != "arm") state = "ref"; spring(mxThr()) { state = "ref" } }
            else -> { val from = off; val to = 54f * d; state = "ref"; tween(380) { k -> off = from + (to - from) * ease(k); true } }
        }
        spin()
    }

    fun finish() {
        if (state != "ref" && state != "arm" && state != "pull") return
        stop()
        when (style) {
            "miuix" -> {
                val thr = mxThr(); val p0 = if (thr > 0f) 1f - (off / thr).coerceIn(0f, 1f) else 1f
                state = "done"; comp = p0
                tween(200) { k -> comp = p0 + (1f - p0) * bez037(k); off = thr * (1f - comp); if (k >= 1f) { state = "idle"; off = 0f }; true }
            }
            else -> { val from = off; state = "done"; tween(if (style == "m3") 300 else 380) { k -> off = from * (1f - ease(k)); if (k >= 1f) { state = "idle"; off = 0f }; true } }
        }
    }

    fun cancel() {
        stop()
        if (style == "miuix") { spring(0f) { state = "idle" }; return }
        val from = off; state = "done"
        tween(250) { k -> off = from * (1f - ease(k)); if (k >= 1f) { state = "idle"; off = 0f }; true }
    }

    fun reset() { stop(); state = "idle"; off = 0f; comp = 1f; changed() }

    private fun ease(k: Float): Float { val x = 1f - k; return 1f - x * x * x }
    /** CubicBezierEasing(0, 0, 0, 0.37): x(s) = s^3, y(s) = 3(1-s)s^2 x 0.37 + s^3 */
    private fun bez037(k: Float): Float { val s = Math.cbrt(k.toDouble()).toFloat(); return 3f * (1f - s) * s * s * 0.37f + s * s * s }

    private fun stop() { anim?.let { removeCallbacks(it) }; anim = null }
    private fun spin() {
        val r = object : Runnable { override fun run() { if (state != "ref" && state != "arm") return; changed(); postOnAnimation(this) } }
        postOnAnimation(r)
    }
    private fun tween(ms: Long, f: (Float) -> Boolean) {
        val start = System.currentTimeMillis()
        val r = object : Runnable { override fun run() { val k = min(1f, (System.currentTimeMillis() - start) / ms.toFloat()); f(k); changed(); if (k < 1f && anim === this) postOnAnimation(this) else if (anim === this) anim = null } }
        anim = r; postOnAnimation(r)
    }
    /** Miuix SpringEngine: critical damping, natural period 0.4 s, explicit steps with dt in [1 ms, 16 ms] */
    private fun spring(target: Float, end: () -> Unit) {
        val w = 2.0 * PI / 0.4; val k = w * w; val c = 2.0 * w
        var pos = off.toDouble(); var v = 0.0; val p0 = pos; var last = -1L
        val r = object : Runnable {
            override fun run() {
                val now = System.nanoTime()
                if (last < 0) { last = now; postOnAnimation(this); return }
                val dt = ((now - last) / 1e9).coerceIn(0.001, 0.016); last = now
                v = v * (1.0 - c * dt) + k * (target - pos) * dt; pos += dt * v
                val fin = (p0 < target && pos > target) || (if (p0 <= target || pos >= target) abs(pos - target) < 1.0 else true)
                if (fin) pos = target.toDouble()
                off = pos.toFloat(); changed()
                if (fin) { if (anim === this) anim = null; end(); changed() } else if (anim === this) postOnAnimation(this)
            }
        }
        anim = r; postOnAnimation(r)
    }

    private fun changed() { onOffset(contentOffset()); invalidate() }

    private fun fg(): Int = if (dark) Color.argb(255, 0xA0, 0xA8, 0xB8) else Color.argb(255, 0x5F, 0x63, 0x68)

    override fun onDraw(cv: Canvas) {
        super.onDraw(cv)
        if (state == "idle") return
        val gap = contentOffset()
        if (gap > 0f) { p.style = Paint.Style.FILL; p.color = if (dark) Color.rgb(0x0E, 0x11, 0x17) else Color.rgb(0xF8, 0xF9, 0xFA); cv.drawRect(0f, 0f, width.toFloat(), gap, p) }
        when (style) {
            "m3" -> drawM3(cv)
            "miuix" -> drawMx(cv)
            else -> drawOg(cv)
        }
    }

    private fun drawOg(cv: Canvas) {
        val k = min(1f, off / (64f * d)); val cx = width / 2f; val cy = max(0f, off - 22f * d) / 2f + 11f * d
        val r = 10f * d * (0.6f + 0.4f * k); val a = ((0.15f + 0.85f * k) * 255).toInt().coerceIn(0, 255)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d; p.strokeCap = Paint.Cap.BUTT
        p.color = fg(); p.alpha = (a * 0.16f).toInt(); cv.drawCircle(cx, cy, r, p)
        p.alpha = a; arc.set(cx - r, cy - r, cx + r, cy + r)
        val start = if (state == "ref") ((System.currentTimeMillis() - t0) % 700L) / 700f * 360f else off / d * 3.2f
        cv.drawArc(arc, start - 90f, 90f, false, p)
    }

    private fun drawM3(cv: Canvas) {
        val size = 40f * d; val cx = width / 2f; val f = off / (80f * d)
        val top = f * 80f * d - size; val cy = top + size / 2f
        // container: surfaceContainerHigh + elevation
        p.style = Paint.Style.FILL; p.color = if (dark) Color.rgb(0x2B, 0x29, 0x30) else Color.rgb(0xEC, 0xE6, 0xF0)
        p.setShadowLayer(4f * d, 0f, 2f * d, Color.argb(70, 0, 0, 0))
        cv.drawCircle(cx, cy, size / 2f, p); p.clearShadowLayer()
        p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f * d; p.color = fg()
        if (state == "ref") {
            val t = (System.currentTimeMillis() - t0)
            val rot = (t % 1568L) / 1568f * 360f; val ph = (t % 1333L) / 1333f
            val sweep = 10f + 260f * (if (ph < 0.5f) ph * 2f else (1f - ph) * 2f)
            val r = 8f * d; arc.set(cx - r, cy - r, cx + r, cy + r); p.strokeCap = Paint.Cap.ROUND
            cv.drawArc(arc, rot + ph * 360f, sweep, false, p); return
        }
        val ap = max(min(1f, f) - 0.4f, 0f) * 5f / 3f
        val lt = (abs(f) - 1f).coerceIn(0f, 2f); val tps = lt - lt * lt / 4f
        val end = ap * 0.8f; val rotT = (-0.25f + 0.4f * ap + tps) * 0.5f
        if (end <= 0.001f) return
        p.alpha = if (f >= 1f) 255 else 77; p.strokeCap = Paint.Cap.BUTT
        val r = 5.5f * d; arc.set(cx - r, cy - r, cx + r, cy + r)
        val a0 = rotT * 360f; val sw = end * 360f
        cv.drawArc(arc, a0, sw, false, p)
        val a1 = Math.toRadians((a0 + sw).toDouble()); val s = min(1f, ap)
        val x1 = cx + r * cos(a1).toFloat(); val y1 = cy + r * sin(a1).toFloat()
        val nx = cos(a1).toFloat(); val ny = sin(a1).toFloat(); val tx = -sin(a1).toFloat(); val ty = cos(a1).toFloat()
        val aw = 10f * d * s; val ah = 5f * d * s
        path.reset(); path.moveTo(x1 - nx * aw / 2f, y1 - ny * aw / 2f); path.lineTo(x1 + nx * aw / 2f, y1 + ny * aw / 2f); path.lineTo(x1 + tx * ah, y1 + ty * ah); path.close()
        p.style = Paint.Style.FILL; cv.drawPath(path, p); p.alpha = 255
    }

    private fun drawMx(cv: Canvas) {
        val c = 20f * d; val vp = mxVp(); val thr = mxThr(); val extra = off - thr
        val ih = if (state == "done") c * (1f - comp) else if (extra <= 0f) c * vp else c + extra
        val left = width / 2f - c / 2f
        val sw = c / 11f; val rad = max(c / 2f, c / 3.5f); val cx = left + c / 2f; val cy = c / 1.8f
        val col = fg()
        p.color = col; p.strokeWidth = sw; p.strokeCap = Paint.Cap.ROUND; p.style = Paint.Style.STROKE
        when {
            (state == "pull" || state == "arm") && off > thr -> {
                val L = (off - thr).coerceIn(0f, h() - thr); p.alpha = 255
                arc.set(cx - rad, cy - rad, cx + rad, cy + rad); cv.drawArc(arc, 180f, 180f, false, p)
                arc.set(cx - rad, cy + L - rad, cx + rad, cy + L + rad); cv.drawArc(arc, 0f, 180f, false, p)
                cv.drawLine(cx - rad, cy, cx - rad, cy + L, p); cv.drawLine(cx + rad, cy, cx + rad, cy + L, p)
            }
            state == "pull" -> { p.alpha = ((vp - 0.2f).coerceIn(0f, 1f) * 255).toInt(); cv.drawCircle(cx, cy, rad, p) }
            state == "ref" -> {
                val ea = ((vp - 0.2f) / 0.8f).coerceIn(0f, 1f); p.alpha = (ea * 255).toInt(); cv.drawCircle(cx, cy, rad, p)
                val an = Math.toRadians((rot + ((System.currentTimeMillis() - t0) % 800L) / 800f * 360f).toDouble())
                val orb = rad - 2f * sw; p.style = Paint.Style.FILL
                cv.drawCircle(cx + orb * cos(an).toFloat(), cy + orb * sin(an).toFloat(), sw, p)
            }
            state == "done" -> {
                val ar = rad * max(1f - comp, 0.9f); p.alpha = ((1f - comp - 0.35f).coerceIn(0f, 1f) * 255).toInt()
                cv.drawCircle(cx, cy - rad - sw + ar, ar, p)
            }
        }
        // text under the indicator
        val txt = when (state) { "pull" -> if (vp > 0.5f) "Pull down to refresh" else ""; "arm" -> "Release to refresh"; "ref" -> "Refreshing..."; "done" -> "Refreshed successfully"; else -> "" }
        val ta = when (state) { "pull" -> if (vp > 0.6f) (vp - 0.5f) * 2f else 0f; "arm" -> 1f; "ref" -> ((vp - 0.5f) * 2f).coerceIn(0f, 1f); "done" -> max(0f, 1f - comp * 1.95f); else -> 0f }
        if (txt.isNotEmpty() && ta > 0f) {
            tp.color = col; tp.alpha = (ta.coerceIn(0f, 1f) * 255).toInt()
            cv.drawText(txt, width / 2f, ih + 6f * d - tp.ascent(), tp)
        }
        p.alpha = 255
    }
}
