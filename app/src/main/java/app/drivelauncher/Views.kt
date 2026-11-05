package app.drivelauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.RadialGradient
import android.graphics.SweepGradient
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
    /** An app icon to show instead of the drawn icon. */
    var drawable: android.graphics.drawable.Drawable? = null
        set(v) { field = v; invalidate() }
    var fg = C.TEXT2
    var bgColor = 0
    var radius = 14f
    var circle = false
    var sw = 1.8f
    /** Aurora Glass app tile: two gradient colours. */
    var tile: IntArray? = null
        set(v) { field = v; invalidate() }
    /** Aurora Glass: white ring around the tile (the page you are on). */
    var ring = false
        set(v) { if (field != v) { field = v; invalidate() } }
    /** Accent gradient button with a soft glow (Aurora Glass play button). */
    var grad = false
    /** Carbon Sport: angled panel; [underline] = colour of the bar under the active button. */
    var chamfer = false
    var underline = 0
        set(v) { if (field != v) { field = v; invalidate() } }
    /** Luxury: round button with a fine gold ring ([ring] = filled gold, for the page you are on). */
    var jewel = false
    /** Small warning dot in the corner (0 = none). */
    var badge = 0
        set(v) { if (field != v) { field = v; invalidate() } }
    private val bgp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rf = RectF()
    private val path = Path()

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
        val want = if (isPressed && isClickable) 0.92f else 1f
        if (Motion.on && scaleX != want) animate().scaleX(want).scaleY(want).setDuration(if (want < 1f) 80L else 160L).start()
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rf.set(0f, 0f, w, h)
        val t = tile
        bgp.shader = null
        bgp.style = Paint.Style.FILL
        var iconColor = fg
        when {
            t != null && Skin.neon -> {
                val inset = S.p(5f)
                rf.inset(inset, inset)
                val rad = rf.width() * 0.26f
                bgp.color = withAlpha(t[0], if (ring) 0.34f else 0.10f)
                c.drawRoundRect(rf, rad, rad, bgp)
                bgp.style = Paint.Style.STROKE
                for ((sw2, a) in listOf(S.p(7f) to 0.12f, S.p(3.5f) to 0.28f, max(1f, S.p(1.6f)) to 1f)) {
                    bgp.strokeWidth = sw2
                    bgp.color = 0xFF000000.toInt()
                    bgp.shader = LinearGradient(rf.left, rf.top, rf.right, rf.bottom, withAlpha(t[0], a), withAlpha(t[1], a), Shader.TileMode.CLAMP)
                    c.drawRoundRect(rf, rad, rad, bgp)
                }
                bgp.shader = null; bgp.style = Paint.Style.FILL
                iconColor = if (ring) 0xFFFFFFFF.toInt() else if (C.night) Skin.blend(t[0], 0xFFFFFFFF.toInt(), 0.3f) else t[1]
            }
            jewel -> {
                val r = min(w, h) / 2f - S.p(4f)
                bgp.color = 0xFF000000.toInt()
                bgp.shader = if (ring) LinearGradient(w / 2 - r, h / 2 - r, w / 2 + r, h / 2 + r, C.accent, Skin.accent2, Shader.TileMode.CLAMP) else null
                if (!ring) bgp.color = C.CARD2
                c.drawCircle(w / 2, h / 2, r, bgp)
                bgp.style = Paint.Style.STROKE; bgp.strokeWidth = max(1f, S.p(1.4f))
                bgp.color = 0xFF000000.toInt()
                bgp.shader = LinearGradient(w / 2 - r, h / 2 - r, w / 2 + r, h / 2 + r,
                    intArrayOf(withAlpha(C.accent, 0.95f), withAlpha(C.accent, 0.3f), withAlpha(C.accent, 0.8f)), null, Shader.TileMode.CLAMP)
                c.drawCircle(w / 2, h / 2, r, bgp)
                bgp.shader = null; bgp.style = Paint.Style.FILL
            }
            t != null -> {
                val inset = S.p(4f)
                rf.inset(inset, inset)
                val rad = rf.width() * 0.29f
                if (ring) {
                    bgp.color = withAlpha(t[0], 0.35f)
                    rf.inset(-S.p(4f), -S.p(4f)); c.drawRoundRect(rf, rad + S.p(4f), rad + S.p(4f), bgp); rf.inset(S.p(4f), S.p(4f))
                }
                bgp.color = 0xFF000000.toInt(); bgp.shader = LinearGradient(rf.left, rf.top, rf.right, rf.bottom, t[0], t[1], Shader.TileMode.CLAMP)
                c.drawRoundRect(rf, rad, rad, bgp)
                bgp.color = 0xFF000000.toInt(); bgp.shader = LinearGradient(0f, rf.top, 0f, rf.centerY(), 0x40FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
                c.drawRoundRect(rf, rad, rad, bgp)
                bgp.shader = null
                if (ring) {
                    bgp.style = Paint.Style.STROKE; bgp.strokeWidth = S.p(2f); bgp.color = if (C.night) 0xE6FFFFFF.toInt() else 0xFF1B2A44.toInt()
                    rf.inset(-S.p(1f), -S.p(1f)); c.drawRoundRect(rf, rad + S.p(1f), rad + S.p(1f), bgp)
                    bgp.style = Paint.Style.FILL
                }
            }
            grad -> {
                val inset = S.p(6f)
                val r = min(w, h) / 2f - inset
                bgp.color = withAlpha(C.accent, 0.10f); c.drawCircle(w / 2, h / 2, r + S.p(6f), bgp)
                bgp.color = withAlpha(C.accent, 0.18f); c.drawCircle(w / 2, h / 2, r + S.p(3f), bgp)
                bgp.color = 0xFF000000.toInt(); bgp.shader = LinearGradient(w / 2 - r, h / 2 - r, w / 2 + r, h / 2 + r, C.accent, Skin.accent2, Shader.TileMode.CLAMP)
                c.drawCircle(w / 2, h / 2, r, bgp)
                bgp.color = 0xFF000000.toInt(); bgp.shader = LinearGradient(0f, h / 2 - r, 0f, h / 2, 0x40FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
                c.drawCircle(w / 2, h / 2, r, bgp)
                bgp.shader = null
            }
            chamfer -> {
                Skin.chamfer(path, 0f, 0f, w, h, S.p(10f))
                bgp.color = if (bgColor != 0) bgColor else C.CARD2
                c.drawPath(path, bgp)
                if (bgColor == 0 || bgColor == C.CARD2) {
                    bgp.color = 0xFF000000.toInt(); bgp.shader = LinearGradient(0f, 0f, 0f, h, if (C.night) 0x14FFFFFF else 0x59FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
                    c.drawPath(path, bgp); bgp.shader = null
                }
                if (underline != 0) {
                    c.save(); c.clipPath(path)
                    bgp.color = underline; c.drawRect(0f, h - S.p(3f), w, h, bgp)
                    c.restore()
                }
                val s1 = max(1f, S.p(1f))
                Skin.chamfer(path, s1 / 2, s1 / 2, w - s1 / 2, h - s1 / 2, S.p(10f))
                bgp.style = Paint.Style.STROKE; bgp.strokeWidth = s1; bgp.color = C.BORDER2
                c.drawPath(path, bgp)
                bgp.style = Paint.Style.FILL
                Skin.chamfer(path, 0f, 0f, w, h, S.p(10f))
            }
            bgColor != 0 -> {
                bgp.color = bgColor
                if (circle) c.drawOval(rf, bgp) else c.drawRoundRect(rf, S.p(radius), S.p(radius), bgp)
            }
        }
        if (isPressed && isClickable) {
            bgp.color = if (C.night) 0x26FFFFFF else 0x1A000000
            rf.set(0f, 0f, w, h)
            when {
                chamfer -> c.drawPath(path, bgp)
                circle || grad || jewel -> c.drawOval(rf, bgp)
                else -> c.drawRoundRect(rf, S.p(radius), S.p(radius), bgp)
            }
        }
        val d = drawable
        if (d != null) {
            val sz = S.p(iconSize + 6f)
            d.setBounds(((w - sz) / 2).toInt(), ((h - sz) / 2).toInt(), ((w + sz) / 2).toInt(), ((h + sz) / 2).toInt())
            d.draw(c)
        } else {
            Icons.draw(c, icon, w / 2f, h / 2f, S.p(iconSize), iconColor, sw)
        }
        if (badge != 0) {
            val r = S.p(5f)
            val bx = w - S.p(10f); val by = S.p(10f)
            bgp.color = C.BG; c.drawCircle(bx, by, r + S.p(2f), bgp)
            bgp.color = badge; c.drawCircle(bx, by, r, bgp)
        }
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
            if (Skin.glowy && color == C.accent) {
                p.color = withAlpha(color, 0.14f)
                p.strokeWidth = sw * 2.4f
                c.drawArc(rf, 135f, 270f * f, false, p)
                p.strokeWidth = sw
                val g = SweepGradient(cx, cy, intArrayOf(color, Skin.accent2, Skin.accent2), floatArrayOf(0f, 0.75f, 1f))
                m.setRotate(135f, cx, cy); g.setLocalMatrix(m)
                p.color = 0xFF000000.toInt(); p.shader = g
                c.drawArc(rf, 135f, 270f * f, false, p)
                p.shader = null
            } else {
                p.color = color
                c.drawArc(rf, 135f, 270f * f, false, p)
            }
        }
    }

    private val m = Matrix()
}

class SpeedGauge(ctx: Context) : View(ctx) {
    var frac = 0f
    var text = "0"
    var unit = "KM/H"
    var dim = false
    var warn = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; fontFeatureSettings = "lnum" }

    fun set(frac: Float, text: String, unit: String, dim: Boolean, warn: Boolean = false) {
        if (frac != this.frac || text != this.text || unit != this.unit || dim != this.dim || warn != this.warn) {
            this.frac = frac; this.text = text; this.unit = unit; this.dim = dim; this.warn = warn
            invalidate()
        }
    }

    override fun onDraw(c: Canvas) {
        val sw = S.p(if (Skin.glowy || Skin.lux) 8f else 12f)
        val (cx, cy, r) = Arc.fit(width.toFloat(), height.toFloat(), sw).let { Triple(it[0], it[1], it[2]) }
        Arc.draw(c, cx, cy, r, frac, sw, if (warn) C.WARN else C.accent, p)
        tp.typeface = F.numeric
        tp.color = if (warn) C.WARN else if (dim) C.MUTED else C.TEXT
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
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; fontFeatureSettings = "lnum" }

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
            val sw = max(S.p(if (Skin.glowy || Skin.lux) 5f else 6f), min(w, h) * (if (Skin.glowy || Skin.lux) 0.05f else 0.075f))
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
        background = Skin.tile()
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

/** Thin progress bar. Aurora Glass: gradient with a glow and a round knob; Carbon Sport: square ends. */
class Bar(ctx: Context) : View(ctx) {
    var frac = 0f
        set(v) { if (field != v) { field = v; invalidate() } }
    var color = 0
    /** Round knob at the end (music progress). */
    var knob = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rf = RectF()

    override fun onDraw(c: Canvas) {
        val h = if (knob) min(height.toFloat(), S.p(4f)) else height.toFloat()
        val top = (height - h) / 2f
        val kr = if (knob) min(height / 2f, S.p(6f)) else 0f
        val x0 = kr; val x1 = width - kr
        val rr = if (Skin.sport) 0f else h / 2
        p.shader = null
        rf.set(x0, top, x1, top + h)
        p.color = C.TRACK
        c.drawRoundRect(rf, rr, rr, p)
        val f = frac.coerceIn(0f, 1f)
        if (f > 0f) {
            val col = if (color != 0) color else C.accent
            val fx = x0 + (x1 - x0) * f
            if ((Skin.glowy || Skin.lux) && color == 0) {
                rf.set(x0 - h, top - h, fx + h, top + 2 * h)
                p.color = withAlpha(col, 0.16f)
                c.drawRoundRect(rf, h * 1.5f, h * 1.5f, p)
                p.color = 0xFF000000.toInt(); p.shader = LinearGradient(x0, 0f, x1, 0f, col, Skin.accent2, Shader.TileMode.CLAMP)
            } else p.color = col
            rf.set(x0, top, fx, top + h)
            c.drawRoundRect(rf, rr, rr, p)
            p.shader = null
            if (knob) {
                p.color = if (C.night) 0xFFFFFFFF.toInt() else C.TEXT
                if (Skin.sport) c.drawRect(fx - S.p(2f), top - S.p(4f), fx + S.p(2f), top + h + S.p(4f), p)
                else c.drawCircle(fx, top + h / 2, kr, p)
            }
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
            if (Skin.glowy) {
                p.color = 0xFF000000.toInt()
                p.shader = LinearGradient(0f, 0f, w, h, withAlpha(Dock.MUSIC[0], 0.85f), withAlpha(Dock.MUSIC[1], 0.85f), Shader.TileMode.CLAMP)
            } else p.color = C.ART_BG
            c.drawRoundRect(rf, rad, rad, p)
            p.shader = null
            Icons.draw(c, Ic.MUSIC, w / 2, h / 2, min(w, h) * 0.42f, if (Skin.glowy) C.ON_GRAD else if (Skin.lux) C.accent else C.MUTED)
            return
        }
        val sh = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val scale = max(w / b.width, h / b.height)
        m.reset()
        m.setScale(scale, scale)
        m.postTranslate((w - b.width * scale) / 2f, (h - b.height * scale) / 2f)
        sh.setLocalMatrix(m)
        p.color = 0xFF000000.toInt(); p.shader = sh
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
        clip.addRoundRect(clipRect, S.p(Skin.radius - 1f), S.p(Skin.radius - 1f), Path.Direction.CW)
        c.clipPath(clip)
        fill.color = C.ROAD_BG
        c.drawRect(0f, 0f, w, h, fill)
        line.pathEffect = null
        line.color = C.HORIZON; line.strokeWidth = max(1f, s)
        c.drawLine(0f, hy, w, hy, line)

        fun quad(x1: Float, x2: Float, x3: Float, x4: Float) {
            path.reset()
            path.moveTo(x1 * sx, h); path.lineTo(x2 * sx, h); path.lineTo(x3 * sx, hy); path.lineTo(x4 * sx, hy); path.close()
        }
        quad(40f, 520f, 310f, 250f); fill.color = C.ROAD; c.drawPath(path, fill)
        quad(200f, 360f, 290f, 270f); fill.color = withAlpha(C.accent, 0.14f); c.drawPath(path, fill)

        line.strokeWidth = 3f * s
        line.color = C.ROAD_EDGE
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
        fill.color = C.CAR_BODY
        rf.set(244f, 304f, 251f, 314f); c.drawRoundRect(rf, 2f, 2f, fill)
        rf.set(309f, 304f, 316f, 314f); c.drawRoundRect(rf, 2f, 2f, fill)
        rf.set(250f, 280f, 310f, 372f); c.drawRoundRect(rf, 16f, 16f, fill)
        fill.color = C.CAR_GLASS; rf.set(257f, 300f, 303f, 318f); c.drawRoundRect(rf, 5f, 5f, fill)
        fill.color = C.CAR_ROOF; rf.set(258f, 320f, 302f, 348f); c.drawRoundRect(rf, 6f, 6f, fill)
        fill.color = C.CAR_GLASS; rf.set(258f, 350f, 302f, 360f); c.drawRoundRect(rf, 3f, 3f, fill)
        fill.color = C.WARN
        rf.set(252f, 364f, 266f, 369f); c.drawRoundRect(rf, 2f, 2f, fill)
        rf.set(294f, 364f, 308f, 369f); c.drawRoundRect(rf, 2f, 2f, fill)
        c.restore()

        if (speedKmh > 1f && isShown) postInvalidateDelayed(40) else lastFrame = 0L
    }
}


/**
 * Behind the cards: the theme's backdrop (Aurora Glass glows, Carbon Sport weave, or plain colour),
 * then optional stars (still or slowly moving) or the owner's photo.
 */
class BackgroundView(ctx: Context, private val kind: String, private val photo: Bitmap?) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val stars = FloatArray(3 * 160).also { a ->
        val r = java.util.Random(7)
        for (i in 0 until 160) { a[i * 3] = r.nextFloat(); a[i * 3 + 1] = r.nextFloat(); a[i * 3 + 2] = r.nextFloat() }
    }
    private var t0 = 0L
    private val m = Matrix()
    private var weave: BitmapShader? = null

    private fun glow(c: Canvas, x: Float, y: Float, r: Float, color: Int, alpha: Float) {
        p.color = 0xFF000000.toInt(); p.shader = RadialGradient(x, y, r, withAlpha(color, alpha), withAlpha(color, 0f), Shader.TileMode.CLAMP)
        c.drawRect(x - r, y - r, x + r, y + r, p)
        p.shader = null
    }

    private fun aurora(c: Canvas, w: Float, h: Float, k: Float) {
        // k: 0..1 slow cycle; the glows drift and breathe a little
        val a = (k * 2 * Math.PI).toFloat()
        val dx = kotlin.math.sin(a) * w * 0.035f
        val dy = kotlin.math.cos(a) * h * 0.04f
        val br = 0.88f + 0.12f * kotlin.math.sin(a * 2)
        if (C.night) {
            glow(c, w * 0.08f + dx, h * 0.02f + dy, w * 0.55f, 0xFF5C9CFF.toInt(), 0.30f * br)
            glow(c, w * 0.94f - dx, h * 1.02f - dy, w * 0.60f, 0xFF966EFF.toInt(), 0.24f * (2 - br))
            glow(c, w * 0.42f - dx * 0.6f, h * 0.86f, w * 0.34f, C.accent, 0.10f * br)
        } else {
            glow(c, w * 0.08f + dx, h * 0.02f + dy, w * 0.60f, 0xFF8EC5FF.toInt(), 0.55f * br)
            glow(c, w * 0.94f - dx, h * 1.02f - dy, w * 0.62f, 0xFFC7B4FF.toInt(), 0.55f * (2 - br))
            glow(c, w * 0.42f - dx * 0.6f, h * 0.86f, w * 0.34f, C.accent, 0.18f * br)
        }
    }

    private fun carbon(c: Canvas, w: Float, h: Float) {
        val sh = weave ?: run {
            val n = max(4, S.i(7f))
            val b = Bitmap.createBitmap(2 * n, 2 * n, Bitmap.Config.ARGB_8888)
            val bc = Canvas(b)
            val q = Paint()
            val hi = if (C.night) 0xFF121418.toInt() else 0xFFEEF0F3.toInt()
            val lo = if (C.night) 0xFF060708.toInt() else 0xFFD9DCE1.toInt()
            for (i in 0..1) for (j in 0..1) {
                val l = (i * n).toFloat(); val tp = (j * n).toFloat()
                q.color = 0xFF000000.toInt(); q.shader = if ((i + j) % 2 == 0) LinearGradient(l, tp, l, tp + n, hi, lo, Shader.TileMode.CLAMP)
                    else LinearGradient(l, tp, l + n, tp, hi, lo, Shader.TileMode.CLAMP)
                bc.drawRect(l, tp, l + n, tp + n, q)
            }
            BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).also { weave = it }
        }
        p.color = 0xFF000000.toInt(); p.shader = sh
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null
        if (C.night) {
            p.color = 0xFF000000.toInt(); p.shader = RadialGradient(w / 2, h * 0.45f, max(w, h) * 0.75f, intArrayOf(0x00000000, 0x00000000, 0xC0000000.toInt()),
                floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, p)
            p.shader = null
        }
        glow(c, w / 2, h * 0.8f, w * 0.36f, C.accent, if (C.night) 0.13f else 0.10f)
    }

    private var gridPhase = 0f
    private var gridT = 0L

    /** Neon Night: glowing grid floor running to the horizon (it moves faster while driving). */
    private fun synthwave(c: Canvas, w: Float, h: Float, now: Long) {
        val hy = h * 0.64f
        if (Motion.on) {
            val dt = if (gridT == 0L) 0f else ((now - gridT) / 1000f).coerceAtMost(0.2f)
            val kmh = if (Gps.fresh) Gps.speedKmh else 0f
            gridPhase = (gridPhase + dt * (0.12f + kmh * 0.012f)) % 1f
        }
        gridT = now
        val mag = 0xFFFF2BD6.toInt(); val cyan = 0xFF00E5FF.toInt()
        p.color = 0xFF000000.toInt()
        p.shader = LinearGradient(0f, 0f, 0f, hy, C.BG, if (C.night) 0xFF1C0A3A.toInt() else 0xFFE4D8FF.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, hy, p)
        p.shader = null
        glow(c, w * 0.12f, h * 0.05f, w * 0.45f, cyan, if (C.night) 0.16f else 0.22f)
        glow(c, w * 0.9f, h * 0.3f, w * 0.45f, mag, if (C.night) 0.16f else 0.22f)
        glow(c, w * 0.5f, hy, w * 0.5f, mag, if (C.night) 0.18f else 0.2f)
        p.style = Paint.Style.STROKE
        val base = if (C.night) mag else 0xFF8A2BE2.toInt()
        // horizon
        p.strokeWidth = S.p(6f); p.color = withAlpha(base, 0.15f); c.drawLine(0f, hy, w, hy, p)
        p.strokeWidth = max(1f, S.p(1.5f)); p.color = withAlpha(base, 0.8f); c.drawLine(0f, hy, w, hy, p)
        // lines towards the viewer
        p.strokeWidth = max(1f, S.p(1.2f))
        for (i in -14..14) {
            p.color = withAlpha(base, 0.32f)
            c.drawLine(w / 2 + i * w * 0.012f, hy, w / 2 + i * w * 0.13f, h, p)
        }
        // cross lines, closer together near the horizon
        val n = 12
        for (k in 0 until n) {
            val d = ((k + gridPhase) / n)
            val y = hy + (h - hy) * d * d
            p.color = withAlpha(base, 0.12f + 0.4f * d)
            c.drawLine(0f, y, w, y, p)
        }
        p.style = Paint.Style.FILL
    }

    private var hair: BitmapShader? = null

    /** Luxury: warm black with a fine brushed texture and a soft gold light from above. */
    private fun luxe(c: Canvas, w: Float, h: Float) {
        val sh = hair ?: run {
            val n = max(4, S.i(6f))
            val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
            val q = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (C.night) 0x0AFFFFFF else 0x0A000000; strokeWidth = 1f }
            Canvas(b).drawLine(0f, n.toFloat(), n.toFloat(), 0f, q)
            BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).also { hair = it }
        }
        p.color = 0xFF000000.toInt(); p.shader = sh
        c.drawRect(0f, 0f, w, h, p); p.shader = null
        glow(c, w * 0.5f, -h * 0.15f, w * 0.65f, C.accent, if (C.night) 0.14f else 0.22f)
        glow(c, w * 0.15f, h * 1.05f, w * 0.4f, C.accent, if (C.night) 0.06f else 0.12f)
        if (C.night) {
            p.color = 0xFF000000.toInt()
            p.shader = RadialGradient(w / 2, h * 0.4f, max(w, h) * 0.8f, intArrayOf(0x00000000, 0x00000000, 0xB0000000.toInt()),
                floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, p); p.shader = null
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        p.shader = null
        p.color = C.BG
        c.drawRect(0f, 0f, w, h, p)
        val now = System.currentTimeMillis()
        if (t0 == 0L) t0 = now
        var animate = false
        if (kind != "photo") when {
            Skin.glass -> { aurora(c, w, h, if (Motion.on) ((now - t0) % 40_000L) / 40_000f else 0f); animate = Motion.on }
            Skin.sport -> carbon(c, w, h)
            Skin.neon -> { synthwave(c, w, h, now); animate = Motion.on }
            Skin.lux -> luxe(c, w, h)
        }
        when (kind) {
            "photo" -> {
                val b = photo ?: return
                val sc = max(w / b.width, h / b.height)
                m.reset(); m.setScale(sc, sc); m.postTranslate((w - b.width * sc) / 2f, (h - b.height * sc) / 2f)
                c.drawBitmap(b, m, p)
                p.color = if (C.night) 0x99000000.toInt() else 0x66FFFFFF
                c.drawRect(0f, 0f, w, h, p)
            }
            "stars", "moving" -> {
                val drift = if (kind == "moving") ((now - t0) % 600_000L) / 600_000f else 0f
                for (i in 0 until stars.size / 3) {
                    val depth = stars[i * 3 + 2]
                    val x = ((stars[i * 3] + drift * (0.2f + depth)) % 1f) * w
                    val y = stars[i * 3 + 1] * h
                    p.color = if (C.night) withAlpha(0xFFFFFFFF.toInt(), 0.15f + depth * 0.5f) else withAlpha(0xFF8A95A3.toInt(), 0.25f + depth * 0.4f)
                    c.drawCircle(x, y, S.p(0.6f + depth * 1.4f), p)
                }
                if (kind == "moving") animate = true
            }
        }
        if (animate && isShown) postInvalidateDelayed(if (kind == "moving") 80L else 66L)
    }
}

/** Compass rose that turns with the car's heading. */
class CompassView(ctx: Context) : View(ctx) {
    var heading = 0f
        set(v) { if (field != v) { field = v; invalidate() } }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val path = Path()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val r = min(w, h) / 2f * 0.9f
        val cx = w / 2; val cy = h / 2
        p.style = Paint.Style.STROKE; p.strokeWidth = S.p(3f); p.color = C.TRACK
        c.drawCircle(cx, cy, r, p)
        c.save()
        c.rotate(-heading, cx, cy)
        for (i in 0 until 36) {
            val a = Math.toRadians(i * 10.0)
            val long = i % 9 == 0
            val r1 = r * (if (long) 0.82f else 0.9f)
            p.strokeWidth = S.p(if (long) 3f else 1.5f)
            p.color = if (long) C.TEXT2 else C.MUTED
            c.drawLine(cx + (r1 * Math.sin(a)).toFloat(), cy - (r1 * Math.cos(a)).toFloat(),
                cx + (r * 0.97f * Math.sin(a)).toFloat(), cy - (r * 0.97f * Math.cos(a)).toFloat(), p)
        }
        p.style = Paint.Style.FILL
        p.typeface = F.semibold
        p.textSize = r * 0.18f
        val labels = arrayOf("N", "E", "S", "W")
        for (i in 0 until 4) {
            val a = Math.toRadians(i * 90.0)
            p.color = if (i == 0) C.accent else C.TEXT2
            val rr = r * 0.62f
            c.drawText(labels[i], cx + (rr * Math.sin(a)).toFloat(), cy - (rr * Math.cos(a)).toFloat() + p.textSize * 0.35f, p)
        }
        c.restore()
        // fixed pointer for the car's direction
        path.reset()
        path.moveTo(cx, cy - r * 0.38f); path.lineTo(cx + r * 0.14f, cy + r * 0.18f); path.lineTo(cx, cy + r * 0.08f); path.lineTo(cx - r * 0.14f, cy + r * 0.18f); path.close()
        p.color = C.accent
        c.drawPath(path, p)
    }
}
