package app.drivelauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/** Round or rounded-square touch button with a drawn icon. Always >= 44 design px. */
class IconBtn(ctx: Context, var icon: Ic, private val iconSize: Float = 24f) : View(ctx) {
    var fg = C.TEXT2
    var bgColor = 0
    var radius = 14f
    var circle = false
    var sw = 1.8f
    private val bgp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rf = RectF()

    init {
        isClickable = true
        isFocusable = true
    }

    fun set(icon: Ic = this.icon, fg: Int = this.fg, bg: Int = this.bgColor) {
        if (icon != this.icon || fg != this.fg || bg != this.bgColor) {
            this.icon = icon; this.fg = fg; this.bgColor = bg
            invalidate()
        }
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rf.set(0f, 0f, w, h)
        if (bgColor != 0) {
            bgp.color = bgColor
            if (circle) c.drawOval(rf, bgp) else c.drawRoundRect(rf, S.p(radius), S.p(radius), bgp)
        }
        if (isPressed && isClickable) {
            bgp.color = 0x26FFFFFF
            if (circle) c.drawOval(rf, bgp) else c.drawRoundRect(rf, S.p(radius), S.p(radius), bgp)
        }
        Icons.draw(c, icon, w / 2f, h / 2f, S.p(iconSize), fg, sw)
    }
}

/** Shared 270-degree arc used by every gauge. */
object Arc {
    private val rf = RectF()

    /** Radius and centre that fit a 270-degree arc (with stroke) inside w x h. */
    fun fit(w: Float, h: Float, sw: Float): FloatArray {
        val r = max(4f, min(w / 2f - sw / 2f, (h - sw) / 1.7071f))
        val cx = w / 2f
        val cy = (h - (1.7071f * r + sw)) / 2f + sw / 2f + r
        return floatArrayOf(cx, cy, r)
    }

    fun draw(c: Canvas, cx: Float, cy: Float, r: Float, frac: Float, sw: Float, color: Int, p: Paint) {
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = sw
        p.shader = null
        rf.set(cx - r, cy - r, cx + r, cy + r)
        p.color = C.TRACK
        c.drawArc(rf, 135f, 270f, false, p)
        val f = frac.coerceIn(0f, 1f)
        if (f > 0.002f) {
            p.color = color
            c.drawArc(rf, 135f, 270f * f, false, p)
        }
    }
}

class SpeedGauge(ctx: Context) : View(ctx) {
    var frac = 0f
    var text = "0"
    var unit = "KM/H"
    var dim = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    fun set(frac: Float, text: String, unit: String, dim: Boolean) {
        if (frac != this.frac || text != this.text || unit != this.unit || dim != this.dim) {
            this.frac = frac; this.text = text; this.unit = unit; this.dim = dim
            invalidate()
        }
    }

    override fun onDraw(c: Canvas) {
        val sw = S.p(12f)
        val (cx, cy, r) = Arc.fit(width.toFloat(), height.toFloat(), sw).let { Triple(it[0], it[1], it[2]) }
        Arc.draw(c, cx, cy, r, frac, sw, C.accent, p)
        tp.typeface = F.numeric
        tp.color = if (dim) C.MUTED else C.TEXT
        tp.textSize = r * 0.9f
        tp.letterSpacing = 0f
        val maxW = r * 1.45f
        val tw = tp.measureText(text)
        if (tw > maxW) tp.textSize *= maxW / tw
        c.drawText(text, cx, cy + tp.textSize * 0.34f, tp)
        tp.typeface = F.semibold
        tp.color = C.MUTED
        tp.textSize = max(S.p(11f), r * 0.15f)
        tp.letterSpacing = 0.12f
        c.drawText(unit, cx, cy + r * 0.55f, tp)
    }
}

/** Ring gauge, or (ring = false) a plain big-number tile used when the data has no range. */
class RingGauge(ctx: Context) : View(ctx) {
    var frac = 0f
    var value = "--"
    var unit = ""
    var ring = true
    var warn = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    fun set(frac: Float, value: String, unit: String, ring: Boolean, warn: Boolean) {
        if (frac != this.frac || value != this.value || unit != this.unit || ring != this.ring || warn != this.warn) {
            this.frac = frac; this.value = value; this.unit = unit; this.ring = ring; this.warn = warn
            invalidate()
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (ring) {
            val sw = max(S.p(6f), min(w, h) * 0.075f)
            val f = Arc.fit(w, h, sw)
            val cx = f[0]; val cy = f[1]; val r = f[2]
            Arc.draw(c, cx, cy, r, frac, sw, if (warn) C.WARN else C.accent, p)
            tp.typeface = F.numeric
            tp.color = if (warn) C.WARN else C.TEXT
            tp.textSize = r * 0.6f
            val maxW = r * 1.4f
            val tw = tp.measureText(value)
            if (tw > maxW) tp.textSize *= maxW / tw
            c.drawText(value, cx, cy + tp.textSize * 0.3f, tp)
            tp.typeface = F.regular
            tp.color = C.MUTED
            tp.textSize = max(S.p(11f), r * 0.22f)
            c.drawText(unit, cx, cy + r * 0.5f, tp)
        } else {
            tp.typeface = F.numeric
            tp.color = C.TEXT
            tp.textSize = min(h * 0.38f, S.p(44f))
            val maxW = w * 0.92f
            val tw = tp.measureText(value)
            if (tw > maxW) tp.textSize *= maxW / tw
            val base = h / 2f + tp.textSize * 0.2f
            c.drawText(value, w / 2f, base, tp)
            tp.typeface = F.regular
            tp.color = C.MUTED
            tp.textSize = S.p(13f)
            c.drawText(unit, w / 2f, base + S.p(24f), tp)
        }
    }
}

/** Card with caption, gauge and footer text. */
class GaugeTile(ctx: Context, big: Boolean) : LinearLayout(ctx) {
    val title: TextView = ctx.caption("", if (big) 13f else 12f)
    val gauge = RingGauge(ctx)
    val foot: TextView = ctx.label(if (big) 13f else 12f, C.MUTED, F.regular)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = cardBg()
        pad(10f, if (big) 18f else 14f)
        addView(title, lp(WRAP, WRAP))
        addView(gauge, lp(MATCH, 0, 1f).apply { topMargin = S.i(6); bottomMargin = S.i(6) })
        addView(foot, lp(WRAP, WRAP))
    }

    fun set(t: String, frac: Float, value: String, unit: String, footer: String, ring: Boolean = true, warn: Boolean = false) {
        title.put(t)
        foot.put(footer)
        gauge.set(frac, value, unit, ring, warn)
    }
}

/** Small stat tile (value + label) used in Speed and Trip cards. */
class Tile(ctx: Context, labelOnTop: Boolean, valueSize: Float = 22f) : LinearLayout(ctx) {
    val value: TextView = ctx.label(valueSize, C.TEXT, F.semibold).tnum()
    val name: TextView = if (labelOnTop) ctx.caption("", 11f) else ctx.label(11f, C.MUTED, F.regular)

    init {
        orientation = VERTICAL
        background = roundBg(C.CARD2, 12f)
        pad(10f, 9f)
        if (labelOnTop) {
            addView(name, lp(MATCH, WRAP))
            addView(value, lp(MATCH, WRAP).apply { topMargin = S.i(3) })
        } else {
            addView(value, lp(MATCH, WRAP))
            addView(name, lp(MATCH, WRAP).apply { topMargin = S.i(3) })
        }
    }

    fun set(v: String, n: String) { value.put(v); name.put(n) }
}

/** Thin progress bar. */
class Bar(ctx: Context) : View(ctx) {
    var frac = 0f
        set(v) { if (field != v) { field = v; invalidate() } }
    var color = 0
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rf = RectF()

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        rf.set(0f, 0f, width.toFloat(), h)
        p.color = C.TRACK
        c.drawRoundRect(rf, h / 2, h / 2, p)
        val f = frac.coerceIn(0f, 1f)
        if (f > 0f) {
            rf.set(0f, 0f, width * f, h)
            p.color = if (color != 0) color else C.accent
            c.drawRoundRect(rf, h / 2, h / 2, p)
        }
    }
}

/** Album art with rounded corners; shows a music note when there is no art. */
class ArtView(ctx: Context) : View(ctx) {
    /** Draw a centred square instead of filling the whole view. */
    var fitSquare = false
    private var bmp: Bitmap? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rf = RectF()
    private val m = Matrix()

    fun set(b: Bitmap?) {
        if (b !== bmp) { bmp = b; invalidate() }
    }

    override fun onDraw(c: Canvas) {
        var w = width.toFloat()
        var h = height.toFloat()
        if (fitSquare) {
            val s = min(w, h)
            c.translate((w - s) / 2f, (h - s) / 2f)
            w = s; h = s
        }
        val rad = min(w, h) * 0.12f
        rf.set(0f, 0f, w, h)
        val b = bmp
        if (b == null || b.isRecycled) {
            p.shader = null
            p.color = 0xFF232830.toInt()
            c.drawRoundRect(rf, rad, rad, p)
            Icons.draw(c, Ic.MUSIC, w / 2, h / 2, min(w, h) * 0.42f, C.MUTED)
            return
        }
        val sh = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val scale = max(w / b.width, h / b.height)
        m.reset()
        m.setScale(scale, scale)
        m.postTranslate((w - b.width * scale) / 2f, (h - b.height * scale) / 2f)
        sh.setLocalMatrix(m)
        p.shader = sh
        c.drawRoundRect(rf, rad, rad, p)
        p.shader = null
    }
}

/** Status pill: ok (blue), warn (amber) or off (grey). */
fun Context.pill(): TextView = label(13f, C.BLUE_TEXT, F.semibold).apply {
    pad(10f, 6f)
    gravity = Gravity.CENTER
}

fun TextView.pillState(text: String, kind: Int) {
    put(text)
    val (fgc, bgc) = when (kind) {
        0 -> C.BLUE_TEXT to C.BLUE_BG
        1 -> C.AMBER_TEXT to C.AMBER_BG
        else -> C.MUTED to C.CARD2
    }
    if (currentTextColor != fgc) setTextColor(fgc)
    val tag = "pill$kind"
    if (getTag() != tag) { background = roundBg(bgc, 999f); setTag(tag) }
}

/** Perspective road with the car, lanes moving with speed. */
class RoadView(ctx: Context) : View(ctx) {
    var speedKmh = 0f
    private val clip = Path()
    private val clipRect = RectF()
    private var phase = 0f
    private var lastFrame = 0L
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()
    private val rf = RectF()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val sx = w / 560f
        val sy = h / 388f
        val s = min(sx, sy)
        val hy = 128f * sy
        // rounded corners even when the unit draws without hardware acceleration
        clip.reset(); clipRect.set(0f, 0f, w, h)
        clip.addRoundRect(clipRect, S.p(19f), S.p(19f), Path.Direction.CW)
        c.clipPath(clip)
        fill.color = C.ROAD_BG
        c.drawRect(0f, 0f, w, h, fill)
        line.pathEffect = null
        line.color = 0xFF1F242B.toInt(); line.strokeWidth = max(1f, s)
        c.drawLine(0f, hy, w, hy, line)

        fun quad(x1: Float, x2: Float, x3: Float, x4: Float) {
            path.reset()
            path.moveTo(x1 * sx, h); path.lineTo(x2 * sx, h); path.lineTo(x3 * sx, hy); path.lineTo(x4 * sx, hy); path.close()
        }
        quad(40f, 520f, 310f, 250f); fill.color = 0xFF15191F.toInt(); c.drawPath(path, fill)
        quad(200f, 360f, 290f, 270f); fill.color = withAlpha(C.accent, 0.14f); c.drawPath(path, fill)

        line.strokeWidth = 3f * s
        line.color = 0xFF3A414B.toInt()
        c.drawLine(40f * sx, h, 250f * sx, hy, line)
        c.drawLine(520f * sx, h, 310f * sx, hy, line)

        // moving lane dashes
        val now = System.nanoTime()
        if (lastFrame != 0L) {
            val dt = ((now - lastFrame) / 1e9f).coerceAtMost(0.1f)
            phase = (phase + speedKmh * 2.2f * dt * s) % (34f * s)
        }
        lastFrame = now
        line.color = withAlpha(C.accent, 0.65f)
        line.pathEffect = DashPathEffect(floatArrayOf(18f * s, 16f * s), phase)
        path.reset(); path.moveTo(200f * sx, h); path.lineTo(270f * sx, hy); c.drawPath(path, line)
        path.reset(); path.moveTo(360f * sx, h); path.lineTo(290f * sx, hy); c.drawPath(path, line)
        line.pathEffect = null

        // car (design coords centred on x=280, bottom at 388)
        c.save()
        c.translate(w / 2f - 280f * s, h - 388f * s)
        c.scale(s, s)
        fill.color = 0x66000000; rf.set(240f, 364f, 320f, 380f); c.drawOval(rf, fill)
        fill.color = 0xFFD7DBE1.toInt()
        rf.set(244f, 304f, 251f, 314f); c.drawRoundRect(rf, 2f, 2f, fill)
        rf.set(309f, 304f, 316f, 314f); c.drawRoundRect(rf, 2f, 2f, fill)
        rf.set(250f, 280f, 310f, 372f); c.drawRoundRect(rf, 16f, 16f, fill)
        fill.color = 0xFF232830.toInt(); rf.set(257f, 300f, 303f, 318f); c.drawRoundRect(rf, 5f, 5f, fill)
        fill.color = 0xFFBFC5CD.toInt(); rf.set(258f, 320f, 302f, 348f); c.drawRoundRect(rf, 6f, 6f, fill)
        fill.color = 0xFF232830.toInt(); rf.set(258f, 350f, 302f, 360f); c.drawRoundRect(rf, 3f, 3f, fill)
        fill.color = C.WARN
        rf.set(252f, 364f, 266f, 369f); c.drawRoundRect(rf, 2f, 2f, fill)
        rf.set(294f, 364f, 308f, 369f); c.drawRoundRect(rf, 2f, 2f, fill)
        c.restore()

        if (speedKmh > 1f && isShown) postInvalidateDelayed(40) else lastFrame = 0L
    }
}
