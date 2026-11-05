package app.drivelauncher

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** Colour palette. Night (dark) is the default; [apply] switches to the day palette. */
object C {
    var BG = 0; var CARD = 0; var CARD2 = 0; var ROAD_BG = 0; var BORDER = 0; var BORDER2 = 0; var TRACK = 0
    var TEXT = 0; var TEXT2 = 0; var TEXT3 = 0; var MUTED = 0
    var BLUE = 0; var BLUE_TEXT = 0; var BLUE_BG = 0; var WARN = 0; var AMBER_TEXT = 0; var AMBER_BG = 0
    var MAP_BG = 0; var ROAD = 0; var ROAD_EDGE = 0; var HORIZON = 0; var ART_BG = 0; var SEP = 0
    var CAR_BODY = 0; var CAR_ROOF = 0; var CAR_GLASS = 0
    /** Text/icon colour on top of the accent colour (always dark for contrast). */
    const val ON_ACCENT = 0xFF0B0D10.toInt()
    var accent = 0xFFFF9F43.toInt()
    var night = true
        private set
    val ACCENTS = intArrayOf(0xFFFF9F43.toInt(), 0xFF4DA3FF.toInt(), 0xFF3DDC97.toInt(), 0xFFF2F4F7.toInt())

    init { apply(true, false) }

    /** @param translucent cards let a background picture show through */
    fun apply(night: Boolean, translucent: Boolean) {
        this.night = night
        val a = if (translucent) 0xE0 else 0xFF
        fun t(c: Int) = (a shl 24) or (c and 0xFFFFFF)
        if (night) {
            BG = 0xFF0B0D10.toInt(); CARD = t(0x14171C); CARD2 = 0xFF1B1F25.toInt(); ROAD_BG = t(0x101318)
            BORDER = 0xFF22262D.toInt(); BORDER2 = 0xFF2A2F37.toInt(); TRACK = 0xFF262B33.toInt()
            TEXT = 0xFFF2F4F7.toInt(); TEXT2 = 0xFFC9CED6.toInt(); TEXT3 = 0xFFE3E6EA.toInt(); MUTED = 0xFF9AA3AE.toInt()
            BLUE = 0xFF4DA3FF.toInt(); BLUE_TEXT = 0xFF8CC4FF.toInt(); BLUE_BG = 0xFF16263A.toInt()
            WARN = 0xFFFF5A4E.toInt(); AMBER_TEXT = 0xFFFFB877.toInt(); AMBER_BG = 0xFF33240F.toInt()
            MAP_BG = 0xFF181C22.toInt(); ROAD = 0xFF15191F.toInt(); ROAD_EDGE = 0xFF3A414B.toInt(); HORIZON = 0xFF1F242B.toInt()
            ART_BG = 0xFF232830.toInt(); SEP = 0xFF2F353E.toInt()
            CAR_BODY = 0xFFD7DBE1.toInt(); CAR_ROOF = 0xFFBFC5CD.toInt(); CAR_GLASS = 0xFF232830.toInt()
        } else {
            BG = 0xFFE6EAEE.toInt(); CARD = t(0xFFFFFF); CARD2 = 0xFFF0F2F5.toInt(); ROAD_BG = t(0xE9EDF1)
            BORDER = 0xFFD3D9E0.toInt(); BORDER2 = 0xFFC6CDD5.toInt(); TRACK = 0xFFDCE1E7.toInt()
            TEXT = 0xFF0E1116.toInt(); TEXT2 = 0xFF2B323B.toInt(); TEXT3 = 0xFF1C2229.toInt(); MUTED = 0xFF56606B.toInt()
            BLUE = 0xFF1F7AE0.toInt(); BLUE_TEXT = 0xFF14589F.toInt(); BLUE_BG = 0xFFDCEBFB.toInt()
            WARN = 0xFFD12F20.toInt(); AMBER_TEXT = 0xFF8A4A00.toInt(); AMBER_BG = 0xFFFCEAD3.toInt()
            MAP_BG = 0xFFE3E7EB.toInt(); ROAD = 0xFFCDD3DA.toInt(); ROAD_EDGE = 0xFF97A0AB.toInt(); HORIZON = 0xFFC6CDD5.toInt()
            ART_BG = 0xFFE3E7EC.toInt(); SEP = 0xFFC6CDD5.toInt()
            CAR_BODY = 0xFF3B434D.toInt(); CAR_ROOF = 0xFF59626D.toInt(); CAR_GLASS = 0xFF1A1F25.toInt()
        }
    }
}

/**
 * Auto-fit scale. Every size in the app is written in "design pixels" for a
 * 1280x720 screen and multiplied by [u], which is recalculated from the real
 * window size, so the same layout fits 1024x600, 1280x720, 1920x720, 2K and
 * portrait units.
 */
object S {
    var u = 1f
    fun p(v: Float): Float = v * u
    fun p(v: Int): Float = v * u
    fun i(v: Float): Int = if (v == 0f) 0 else max(1, (v * u).roundToInt())
    fun i(v: Int): Int = i(v.toFloat())
}

object F {
    var regular: Typeface = Typeface.DEFAULT
    var medium: Typeface = Typeface.DEFAULT
    var semibold: Typeface = Typeface.DEFAULT_BOLD
    var bold: Typeface = Typeface.DEFAULT_BOLD
    var numeric: Typeface = Typeface.DEFAULT_BOLD
    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        fun f(name: String, fb: Typeface): Typeface =
            try { Typeface.createFromAsset(ctx.assets, "fonts/$name") } catch (e: Exception) { fb }
        regular = f("Barlow-Regular.ttf", Typeface.DEFAULT)
        medium = f("Barlow-Medium.ttf", Typeface.DEFAULT)
        semibold = f("Barlow-SemiBold.ttf", Typeface.DEFAULT_BOLD)
        bold = f("Barlow-Bold.ttf", Typeface.DEFAULT_BOLD)
        numeric = f("BarlowSemiCondensed-SemiBold.ttf", Typeface.DEFAULT_BOLD)
        loaded = true
    }
}

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
fun flp(w: Int, h: Int, gravity: Int = Gravity.NO_GRAVITY) = FrameLayout.LayoutParams(w, h, gravity)

fun roundBg(color: Int, radius: Float, stroke: Int = 0): GradientDrawable = GradientDrawable().apply {
    setColor(color)
    cornerRadius = S.p(radius)
    if (stroke != 0) setStroke(max(1, S.i(1)), stroke)
}

fun cardBg(color: Int = C.CARD): GradientDrawable = roundBg(color, 20f, C.BORDER)

private fun gapDrawable(px: Int): GradientDrawable = GradientDrawable().apply {
    setSize(px, px)
    setColor(Color.TRANSPARENT)
}

fun Context.hbox(gap: Float = 0f, center: Boolean = true): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    if (center) gravity = Gravity.CENTER_VERTICAL
    if (gap > 0f) {
        dividerDrawable = gapDrawable(S.i(gap))
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
    }
}

fun Context.vbox(gap: Float = 0f): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    if (gap > 0f) {
        dividerDrawable = gapDrawable(S.i(gap))
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
    }
}

fun Context.label(
    size: Float,
    color: Int = C.TEXT,
    tf: Typeface = F.medium,
    caps: Boolean = false,
    lines: Int = 1,
): TextView = TextView(this).apply {
    setTextSize(TypedValue.COMPLEX_UNIT_PX, S.p(size))
    setTextColor(color)
    typeface = tf
    includeFontPadding = false
    if (lines == 1) isSingleLine = true else maxLines = lines
    ellipsize = TextUtils.TruncateAt.END
    if (caps) {
        isAllCaps = true // after setSingleLine, which would otherwise replace it
        letterSpacing = 0.08f
    }
}

fun Context.caption(text: String, size: Float = 13f): TextView =
    label(size, C.MUTED, F.semibold, caps = true).apply { this.text = text }

fun Context.spacer(): View = View(this)

fun TextView.tnum(): TextView = apply { fontFeatureSettings = "tnum" }

/** Only touch the TextView when the value really changed (avoids relayouts every tick). */
fun TextView.put(s: CharSequence) {
    if (text.toString() != s.toString()) text = s
}

fun View.pad(l: Float, t: Float = l, r: Float = l, b: Float = t): View = apply {
    setPadding(S.i(l), S.i(t), S.i(r), S.i(b))
}

fun View.showIf(v: Boolean) {
    val want = if (v) View.VISIBLE else View.GONE
    if (visibility != want) visibility = want
}

fun Context.divider(): View = View(this).apply {
    setBackgroundColor(C.BORDER2)
    layoutParams = lp(S.i(1), S.i(28)).apply { leftMargin = S.i(6); rightMargin = S.i(6) }
}

fun fmt(pattern: String, vararg args: Any?): String = String.format(Locale.US, pattern, *args)

fun withAlpha(color: Int, alpha: Float): Int =
    (color and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255).roundToInt() shl 24)
