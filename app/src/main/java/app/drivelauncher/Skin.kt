package app.drivelauncher

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import kotlin.math.max
import kotlin.math.min

/**
 * Premium themes ("skins"). Each one changes the colours, fonts, card shapes,
 * background, dock and gauges:
 *  - Aurora Glass: frosted glass cards on glowing colour, thin big numbers, colourful app tiles.
 *  - Carbon Sport: carbon-fibre background, analog dials, angled metal panels, red accent.
 *  - Classic: the original flat look.
 */
object Skin {
    const val GLASS = "glass"
    const val SPORT = "sport"
    const val NEON = "neon"
    const val LUX = "luxury"
    const val CLASSIC = "classic"
    val NAMES = linkedMapOf(GLASS to "Aurora Glass", SPORT to "Carbon Sport", NEON to "Neon Night", LUX to "Luxury", CLASSIC to "Classic")

    var id = GLASS
        private set
    val glass: Boolean get() = id == GLASS
    val sport: Boolean get() = id == SPORT
    val neon: Boolean get() = id == NEON
    val lux: Boolean get() = id == LUX
    /** Soft gradients and glows (Aurora Glass, Neon Night). */
    val glowy: Boolean get() = id == GLASS || id == NEON
    /** Real-looking analog dials (Carbon Sport, Luxury). */
    val analog: Boolean get() = id == SPORT || id == LUX
    /** The drive hero floats on the background without a card (all premium themes but Carbon Sport). */
    val floatingHero: Boolean get() = id == GLASS || id == NEON || id == LUX

    fun set(v: String) { id = if (v in NAMES) v else GLASS }

    fun accents(skin: String = id): IntArray = when (skin) {
        GLASS -> intArrayOf(0xFF6CB8FF.toInt(), 0xFFB48CFF.toInt(), 0xFF3DE0B4.toInt(), 0xFFFF8A5C.toInt())
        SPORT -> intArrayOf(0xFFFF3B30.toInt(), 0xFFFF8A1F.toInt(), 0xFF2F9BFF.toInt(), 0xFFD4FF3A.toInt())
        NEON -> intArrayOf(0xFF00E5FF.toInt(), 0xFFFF2BD6.toInt(), 0xFFB6FF3B.toInt(), 0xFF8A5CFF.toInt())
        LUX -> intArrayOf(0xFFD4AF6A.toInt(), 0xFFE8A98F.toInt(), 0xFFD9DEE5.toInt(), 0xFF3FB68B.toInt())
        else -> intArrayOf(0xFFFF9F43.toInt(), 0xFF4DA3FF.toInt(), 0xFF3DDC97.toInt(), 0xFFF2F4F7.toInt())
    }

    fun defaultAccent(skin: String): Int = accents(skin)[0]

    /** Card corner radius in design px. */
    val radius: Float get() = when (id) { GLASS -> 26f; SPORT -> 6f; NEON -> 18f; LUX -> 14f; else -> 20f }
    /** Corner cut of Carbon Sport panels. */
    const val CUT = 14f
    val dockHeight: Int get() = when (id) { GLASS, NEON, LUX -> 80; SPORT -> 72; else -> 64 }

    /** Second colour of accent gradients (Aurora Glass blends towards violet). */
    val accent2: Int get() = when (id) {
        GLASS -> blend(C.accent, 0xFF6A4BFF.toInt(), 0.62f)
        SPORT -> blend(C.accent, 0xFF5A0A06.toInt(), 0.55f)
        NEON -> if (C.accent == 0xFFFF2BD6.toInt()) 0xFF00E5FF.toInt() else blend(C.accent, 0xFFFF2BD6.toInt(), 0.75f)
        LUX -> blend(C.accent, 0xFF5E4219.toInt(), 0.5f)
        else -> C.accent
    }

    /** Main card background. [clip] = a shape views can be clipped to (maps, road). */
    fun card(color: Int = C.CARD, clip: Boolean = false): Drawable = when {
        glass -> GlassBg(color, radius)
        neon -> NeonBg(color, radius)
        lux -> LuxBg(color, radius)
        sport && !clip -> ChamferBg(color, CUT)
        sport -> roundBg(color, radius, C.BORDER)
        else -> roundBg(color, 20f, C.BORDER)
    }

    /** Small tile inside a card. */
    fun tile(color: Int = C.CARD2): Drawable = when {
        glass -> roundBg(color, 16f)
        neon -> roundBg(color, 12f, C.BORDER)
        lux -> roundBg(color, 10f, C.BORDER)
        sport -> ChamferBg(color, 8f, flat = true)
        else -> roundBg(color, 12f)
    }

    /** A smaller panel in the theme's style (app tiles, readouts). */
    fun panel(color: Int, r: Float): Drawable = when {
        glass -> GlassBg(color, r)
        neon -> NeonBg(color, r)
        lux -> LuxBg(color, r)
        sport -> ChamferBg(color, 10f)
        else -> roundBg(color, r, C.BORDER)
    }

    /** Icon colour on top of [gradient] (dark on gold, white elsewhere). */
    val onGradient: Int get() = if (lux) C.ON_ACCENT else C.ON_GRAD

    /** Accent gradient (top-left to bottom-right) used by Aurora Glass buttons and the turn tile. */
    fun gradient(radius: Float, c1: Int = C.accent, c2: Int = accent2): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c1, c2)).apply { cornerRadius = S.p(radius) }

    fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = (((a shr s) and 0xFF) * (1 - t) + ((b shr s) and 0xFF) * t).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Chamfered polygon (top-left and bottom-right corners cut). */
    fun chamfer(p: Path, l: Float, t: Float, r: Float, b: Float, cut: Float) {
        val k = min(cut, min(r - l, b - t) / 3f)
        p.reset()
        p.moveTo(l + k, t); p.lineTo(r, t); p.lineTo(r, b - k); p.lineTo(r - k, b); p.lineTo(l, b); p.lineTo(l, t + k); p.close()
    }
}

/** Turns page transitions, the moving background and the spinning wheels on or off. */
object Motion {
    /** Tests switch this off so screenshots are not taken in the middle of an animation. */
    var allowed = true
    val on: Boolean get() = allowed && Prefs.motion
}

/** Frosted-glass card: see-through fill, light top edge, soft border. */
class GlassBg(private val fill: Int, private val r: Float, var glow: Int = 0) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rf = RectF()
    private val clip = Path()

    override fun draw(c: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val rad = S.p(r)
        rf.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        p.style = Paint.Style.FILL
        p.shader = null
        p.color = fill
        c.drawRoundRect(rf, rad, rad, p)
        if (glow != 0) {
            c.save()
            clip.reset(); clip.addRoundRect(rf, rad, rad, Path.Direction.CW)
            c.clipPath(clip)
            val gr = max(rf.width(), rf.height()) * 0.75f
            p.color = 0xFF000000.toInt(); p.shader = RadialGradient(rf.left + rf.width() * 0.12f, rf.top + rf.height() * 0.1f, gr,
                intArrayOf(withAlpha(glow, if (C.night) 0.38f else 0.30f), withAlpha(glow, 0f)), null, Shader.TileMode.CLAMP)
            c.drawRect(rf, p)
            c.restore()
        }
        // light falling on the top edge
        p.color = 0xFF000000.toInt(); p.shader = LinearGradient(0f, rf.top, 0f, rf.top + min(rf.height(), S.p(140f)),
            if (C.night) 0x14FFFFFF else 0x40FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawRoundRect(rf, rad, rad, p)
        // border: brighter at the top
        val sw = max(1f, S.p(1f))
        rf.inset(sw / 2, sw / 2)
        p.style = Paint.Style.STROKE
        p.strokeWidth = sw
        p.color = 0xFF000000.toInt(); p.shader = LinearGradient(0f, rf.top, 0f, rf.bottom,
            if (C.night) 0x38FFFFFF else 0xF2FFFFFF.toInt(), if (C.night) 0x0DFFFFFF else 0x80FFFFFF.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(rf, rad, rad, p)
        p.shader = null
    }

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, S.p(r))
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Carbon Sport panel: angled corners, brushed top-to-bottom shading, thin edge. */
class ChamferBg(private val fill: Int, private val cut: Float, private val flat: Boolean = false, var underline: Int = 0) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun draw(c: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val l = b.left.toFloat(); val t = b.top.toFloat(); val r = b.right.toFloat(); val bt = b.bottom.toFloat()
        Skin.chamfer(path, l, t, r, bt, S.p(cut))
        p.style = Paint.Style.FILL
        p.shader = null
        p.color = fill
        c.drawPath(path, p)
        if (!flat) {
            p.color = 0xFF000000.toInt(); p.shader = LinearGradient(0f, t, 0f, bt, if (C.night) 0x12FFFFFF else 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
            c.drawPath(path, p)
            p.shader = null
        }
        if (underline != 0) {
            c.save(); c.clipPath(path)
            p.color = underline
            c.drawRect(l, bt - S.p(3f), r, bt, p)
            c.restore()
        }
        val sw = max(1f, S.p(1f))
        Skin.chamfer(path, l + sw / 2, t + sw / 2, r - sw / 2, bt - sw / 2, S.p(cut))
        p.style = Paint.Style.STROKE
        p.strokeWidth = sw
        p.color = if (flat) C.BORDER else C.BORDER2
        c.drawPath(path, p)
    }

    override fun getOutline(outline: Outline) {
        outline.setRect(bounds)
        outline.alpha = 0f
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Neon Night card: dark see-through panel with a glowing two-colour outline. */
class NeonBg(private val fill: Int, private val r: Float) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rf = RectF()

    override fun draw(c: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val g = S.p(3f)
        rf.set(b.left + g, b.top + g, b.right - g, b.bottom - g)
        val rad = S.p(r)
        p.style = Paint.Style.FILL; p.shader = null; p.color = fill
        c.drawRoundRect(rf, rad, rad, p)
        val c1 = C.accent; val c2 = Skin.accent2
        p.style = Paint.Style.STROKE
        for ((w, a) in listOf(S.p(6f) to 0.10f, S.p(3f) to 0.22f, max(1f, S.p(1.3f)) to 1f)) {
            p.strokeWidth = w
            p.color = 0xFF000000.toInt()
            p.shader = LinearGradient(rf.left, rf.top, rf.right, rf.bottom, withAlpha(c1, a * (if (C.night) 0.9f else 0.7f)),
                withAlpha(c2, a * (if (C.night) 0.9f else 0.7f)), Shader.TileMode.CLAMP)
            c.drawRoundRect(rf, rad, rad, p)
        }
        p.shader = null
    }

    override fun getOutline(outline: Outline) {
        val g = S.p(3f).toInt()
        outline.setRoundRect(bounds.left + g, bounds.top + g, bounds.right - g, bounds.bottom - g, S.p(r))
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Luxury card: deep warm black with a fine gold double hairline, like a watch box. */
class LuxBg(private val fill: Int, private val r: Float) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rf = RectF()

    override fun draw(c: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val rad = S.p(r)
        rf.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        p.style = Paint.Style.FILL; p.shader = null; p.color = fill
        c.drawRoundRect(rf, rad, rad, p)
        p.color = 0xFF000000.toInt()
        p.shader = LinearGradient(0f, rf.top, 0f, rf.top + min(rf.height(), S.p(160f)),
            if (C.night) 0x10F3DFA9 else 0x30FFFFFF, 0x00F3DFA9, Shader.TileMode.CLAMP)
        c.drawRoundRect(rf, rad, rad, p)
        val sw = max(1f, S.p(1f))
        rf.inset(sw / 2, sw / 2)
        p.style = Paint.Style.STROKE; p.strokeWidth = sw
        val gold = C.accent
        p.shader = LinearGradient(rf.left, rf.top, rf.right, rf.bottom,
            intArrayOf(withAlpha(gold, 0.85f), withAlpha(gold, 0.25f), withAlpha(gold, 0.6f)), null, Shader.TileMode.CLAMP)
        c.drawRoundRect(rf, rad, rad, p)
        val k = S.p(5f)
        rf.inset(k, k)
        p.shader = null; p.color = withAlpha(gold, if (C.night) 0.12f else 0.2f)
        c.drawRoundRect(rf, max(0f, rad - k), max(0f, rad - k), p)
    }

    override fun getOutline(outline: Outline) { outline.setRoundRect(bounds, S.p(r)) }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Small picture of a theme for the theme chooser. */
class ThemePreview(ctx: android.content.Context, private val skin: String) : android.view.View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rf = RectF()
    private val path = Path()
    var chosen = false
        set(v) { field = v; invalidate() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val rad = S.p(14f)
        rf.set(0f, 0f, w, h)
        val acc = Skin.accents(skin)[0]
        val (bg, card) = when (skin) {
            Skin.GLASS -> 0xFF05070B.toInt() to 0x1FFFFFFF
            Skin.SPORT -> 0xFF0A0B0D.toInt() to 0x1AFFFFFF
            Skin.NEON -> 0xFF07041A.toInt() to 0x40140A2E
            Skin.LUX -> 0xFF0A0907.toInt() to 0xFF15130F.toInt()
            else -> 0xFF0B0D10.toInt() to 0xFF1B1F25.toInt()
        }
        c.save()
        path.reset(); path.addRoundRect(rf, rad, rad, Path.Direction.CW); c.clipPath(path)
        p.style = Paint.Style.FILL; p.shader = null; p.color = bg
        c.drawRect(rf, p)
        p.color = 0xFF000000.toInt()
        when (skin) {
            Skin.GLASS -> {
                p.shader = RadialGradient(w * 0.15f, 0f, w * 0.7f, 0x665C9CFF, 0x005C9CFF, Shader.TileMode.CLAMP); c.drawRect(rf, p)
                p.shader = RadialGradient(w, h, w * 0.7f, 0x55966EFF, 0x00966EFF, Shader.TileMode.CLAMP); c.drawRect(rf, p)
            }
            Skin.SPORT -> { p.shader = RadialGradient(w / 2, h, w * 0.5f, withAlpha(acc, 0.35f), withAlpha(acc, 0f), Shader.TileMode.CLAMP); c.drawRect(rf, p) }
            Skin.NEON -> {
                p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = max(1f, S.p(1f)); p.color = withAlpha(0xFFFF2BD6.toInt(), 0.6f)
                val hy = h * 0.62f
                for (i in -6..6) c.drawLine(w / 2, hy, w / 2 + i * w * 0.16f, h, p)
                var y = hy; var d = h * 0.04f
                while (y < h) { c.drawLine(0f, y, w, y, p); y += d; d *= 1.5f }
                p.style = Paint.Style.FILL
            }
            Skin.LUX -> { p.shader = RadialGradient(w / 2, 0f, w * 0.6f, withAlpha(acc, 0.28f), withAlpha(acc, 0f), Shader.TileMode.CLAMP); c.drawRect(rf, p) }
        }
        p.shader = null; p.style = Paint.Style.FILL
        // two little cards and a dial
        val m = w * 0.08f
        rf.set(m, h * 0.2f, w * 0.5f, h * 0.8f); p.color = card
        val cr = if (skin == Skin.SPORT) 0f else S.p(6f)
        c.drawRoundRect(rf, cr, cr, p)
        if (skin == Skin.NEON || skin == Skin.LUX) {
            p.style = Paint.Style.STROKE; p.strokeWidth = max(1f, S.p(1f)); p.color = withAlpha(acc, 0.8f)
            c.drawRoundRect(rf, cr, cr, p); p.style = Paint.Style.FILL
        }
        rf.set(w * 0.56f, h * 0.2f, w - m, h * 0.8f); p.color = card
        c.drawRoundRect(rf, cr, cr, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = S.p(3f); p.strokeCap = Paint.Cap.ROUND; p.color = acc
        val r = (w * 0.42f - m) * 0.32f
        val cx = (m + w * 0.5f) / 2; val cy = h * 0.52f
        rf.set(cx - r, cy - r, cx + r, cy + r)
        c.drawArc(rf, 135f, 190f, false, p)
        p.strokeWidth = S.p(2.5f)
        c.drawLine(w * 0.62f, h * 0.38f, w * 0.86f, h * 0.38f, p)
        p.color = withAlpha(acc, 0.5f); c.drawLine(w * 0.62f, h * 0.52f, w * 0.78f, h * 0.52f, p)
        p.style = Paint.Style.FILL
        c.restore()
        if (chosen) {
            p.style = Paint.Style.STROKE; p.strokeWidth = S.p(3f); p.color = C.accent
            rf.set(S.p(1.5f), S.p(1.5f), w - S.p(1.5f), h - S.p(1.5f))
            c.drawRoundRect(rf, rad, rad, p)
        }
    }
}
