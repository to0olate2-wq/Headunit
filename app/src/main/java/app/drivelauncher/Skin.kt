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
    const val CLASSIC = "classic"
    val NAMES = linkedMapOf(GLASS to "Aurora Glass", SPORT to "Carbon Sport", CLASSIC to "Classic")

    var id = GLASS
        private set
    val glass: Boolean get() = id == GLASS
    val sport: Boolean get() = id == SPORT

    fun set(v: String) { id = if (v in NAMES) v else GLASS }

    fun accents(skin: String = id): IntArray = when (skin) {
        GLASS -> intArrayOf(0xFF6CB8FF.toInt(), 0xFFB48CFF.toInt(), 0xFF3DE0B4.toInt(), 0xFFFF8A5C.toInt())
        SPORT -> intArrayOf(0xFFFF3B30.toInt(), 0xFFFF8A1F.toInt(), 0xFF2F9BFF.toInt(), 0xFFD4FF3A.toInt())
        else -> intArrayOf(0xFFFF9F43.toInt(), 0xFF4DA3FF.toInt(), 0xFF3DDC97.toInt(), 0xFFF2F4F7.toInt())
    }

    fun defaultAccent(skin: String): Int = accents(skin)[0]

    /** Card corner radius in design px. */
    val radius: Float get() = when (id) { GLASS -> 26f; SPORT -> 6f; else -> 20f }
    /** Corner cut of Carbon Sport panels. */
    const val CUT = 14f
    val dockHeight: Int get() = when (id) { GLASS -> 80; SPORT -> 72; else -> 64 }

    /** Second colour of accent gradients (Aurora Glass blends towards violet). */
    val accent2: Int get() = when (id) {
        GLASS -> blend(C.accent, 0xFF6A4BFF.toInt(), 0.62f)
        SPORT -> blend(C.accent, 0xFF5A0A06.toInt(), 0.55f)
        else -> C.accent
    }

    /** Main card background. [clip] = a shape views can be clipped to (maps, road). */
    fun card(color: Int = C.CARD, clip: Boolean = false): Drawable = when {
        glass -> GlassBg(color, radius)
        sport && !clip -> ChamferBg(color, CUT)
        sport -> roundBg(color, radius, C.BORDER)
        else -> roundBg(color, 20f, C.BORDER)
    }

    /** Small tile inside a card. */
    fun tile(color: Int = C.CARD2): Drawable = when {
        glass -> roundBg(color, 16f)
        sport -> ChamferBg(color, 8f, flat = true)
        else -> roundBg(color, 12f)
    }

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
            p.shader = RadialGradient(rf.left + rf.width() * 0.12f, rf.top + rf.height() * 0.1f, gr,
                intArrayOf(withAlpha(glow, if (C.night) 0.38f else 0.30f), withAlpha(glow, 0f)), null, Shader.TileMode.CLAMP)
            c.drawRect(rf, p)
            c.restore()
        }
        // light falling on the top edge
        p.shader = LinearGradient(0f, rf.top, 0f, rf.top + min(rf.height(), S.p(140f)),
            if (C.night) 0x14FFFFFF else 0x40FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawRoundRect(rf, rad, rad, p)
        // border: brighter at the top
        val sw = max(1f, S.p(1f))
        rf.inset(sw / 2, sw / 2)
        p.style = Paint.Style.STROKE
        p.strokeWidth = sw
        p.shader = LinearGradient(0f, rf.top, 0f, rf.bottom,
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
            p.shader = LinearGradient(0f, t, 0f, bt, if (C.night) 0x12FFFFFF else 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
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
