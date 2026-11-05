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

/** Colour palette (matches the design mockup). */
object C {
    const val BG = 0xFF0B0D10.toInt()
    const val CARD = 0xFF14171C.toInt()
    const val CARD2 = 0xFF1B1F25.toInt()
    const val ROAD_BG = 0xFF101318.toInt()
    const val BORDER = 0xFF22262D.toInt()
    const val BORDER2 = 0xFF2A2F37.toInt()
    const val TRACK = 0xFF262B33.toInt()
    const val TEXT = 0xFFF2F4F7.toInt()
    const val TEXT2 = 0xFFC9CED6.toInt()
    const val TEXT3 = 0xFFE3E6EA.toInt()
    const val MUTED = 0xFF9AA3AE.toInt()
    const val BLUE = 0xFF4DA3FF.toInt()
    const val BLUE_TEXT = 0xFF8CC4FF.toInt()
    const val BLUE_BG = 0xFF16263A.toInt()
    const val WARN = 0xFFFF5A4E.toInt()
    const val AMBER_TEXT = 0xFFFFB877.toInt()
    const val AMBER_BG = 0xFF33240F.toInt()
    var accent = 0xFFFF9F43.toInt()
    val ACCENTS = intArrayOf(0xFFFF9F43.toInt(), 0xFF4DA3FF.toInt(), 0xFF3DDC97.toInt(), 0xFFF2F4F7.toInt())
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
