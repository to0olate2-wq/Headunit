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

/** Colour palette. Night (dark) is the default; [apply] switches to the day palette and follows the [Skin]. */
object C {
    var BG = 0; var CARD = 0; var CARD2 = 0; var ROAD_BG = 0; var BORDER = 0; var BORDER2 = 0; var TRACK = 0
    var TEXT = 0; var TEXT2 = 0; var TEXT3 = 0; var MUTED = 0
    var BLUE = 0; var BLUE_TEXT = 0; var BLUE_BG = 0; var WARN = 0; var AMBER_TEXT = 0; var AMBER_BG = 0
    var MAP_BG = 0; var ROAD = 0; var ROAD_EDGE = 0; var HORIZON = 0; var ART_BG = 0; var SEP = 0
    var CAR_BODY = 0; var CAR_ROOF = 0; var CAR_GLASS = 0
    /** Chips and buttons floating over a map or the road (stay readable on any picture). */
    var FLOAT = 0
    /** Text/icon colour on top of a flat accent colour (always dark for contrast). */
    const val ON_ACCENT = 0xFF0B0D10.toInt()
    /** Icon colour on accent gradients and colourful app tiles. */
    const val ON_GRAD = 0xFFFFFFFF.toInt()
    var accent = 0xFF6CB8FF.toInt()
    var night = true
        private set
    val ACCENTS: IntArray get() = Skin.accents()

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
        FLOAT = CARD2
        when {
            Skin.glass && night -> {
                BG = 0xFF05070B.toInt(); CARD = 0x12FFFFFF; CARD2 = 0x17FFFFFF; ROAD_BG = 0x10FFFFFF
                BORDER = 0x1AFFFFFF; BORDER2 = 0x26FFFFFF; TRACK = 0x24FFFFFF
                TEXT = 0xFFF4F6FA.toInt(); TEXT2 = 0xFFCBD3DE.toInt(); TEXT3 = 0xFFE3E8EF.toInt(); MUTED = 0xFF919CAB.toInt()
                BLUE = 0xFF6CB8FF.toInt(); BLUE_TEXT = 0xFFA4D0FF.toInt(); BLUE_BG = 0x336CB8FF
                WARN = 0xFFFF5A6A.toInt(); AMBER_TEXT = 0xFFFFC36B.toInt(); AMBER_BG = 0x33FFB44D
                MAP_BG = 0xFF0E1218.toInt(); ROAD = 0x55000000; ROAD_EDGE = 0x47FFFFFF; HORIZON = 0x1FFFFFFF
                ART_BG = 0x1FFFFFFF; SEP = 0x2EFFFFFF; FLOAT = 0xE6121722.toInt()
            }
            Skin.glass -> {
                BG = 0xFFE7ECF4.toInt(); CARD = 0xA6FFFFFF.toInt(); CARD2 = 0x0F1B2A44; ROAD_BG = 0x80FFFFFF.toInt()
                BORDER = 0x1F1B2A44; BORDER2 = 0x2E1B2A44; TRACK = 0x241B2A44
                TEXT = 0xFF0D1220.toInt(); TEXT2 = 0xFF283245.toInt(); TEXT3 = 0xFF1A2232.toInt(); MUTED = 0xFF566175.toInt()
                BLUE = 0xFF2F7FE0.toInt(); BLUE_TEXT = 0xFF14589F.toInt(); BLUE_BG = 0x332F7FE0
                WARN = 0xFFD12F3A.toInt(); AMBER_TEXT = 0xFF8A4A00.toInt(); AMBER_BG = 0x33F2A33A
                MAP_BG = 0xFFE3E8EF.toInt(); ROAD = 0x331B2A44; ROAD_EDGE = 0x661B2A44; HORIZON = 0x261B2A44
                ART_BG = 0x1F1B2A44; SEP = 0x331B2A44; FLOAT = 0xEBFFFFFF.toInt()
            }
            Skin.sport && night -> {
                BG = 0xFF07080A.toInt(); CARD = 0x0FFFFFFF; CARD2 = 0x14FFFFFF; ROAD_BG = 0xFF0C0D10.toInt()
                BORDER = 0x17FFFFFF; BORDER2 = 0x24FFFFFF; TRACK = 0xFF1E2126.toInt()
                TEXT = 0xFFF4F6FA.toInt(); TEXT2 = 0xFFC9CED6.toInt(); TEXT3 = 0xFFDDE1E7.toInt(); MUTED = 0xFF8E96A3.toInt()
                WARN = 0xFFFF5A4E.toInt()
                MAP_BG = 0xFF101215.toInt(); ROAD = 0xFF121418.toInt(); ROAD_EDGE = 0xFF3A3F47.toInt(); HORIZON = 0xFF1C1F24.toInt()
                ART_BG = 0xFF1C1F24.toInt(); SEP = 0x2EFFFFFF; FLOAT = 0xE6121418.toInt()
            }
            Skin.neon && night -> {
                BG = 0xFF06040F.toInt(); CARD = 0xA60C0820.toInt(); CARD2 = 0x1FFFFFFF; ROAD_BG = 0x660C0820
                BORDER = 0x2EFFFFFF; BORDER2 = 0x3DFFFFFF; TRACK = 0x338A5CFF
                TEXT = 0xFFF5F2FF.toInt(); TEXT2 = 0xFFD8CFF5.toInt(); TEXT3 = 0xFFEAE5FF.toInt(); MUTED = 0xFFA195CC.toInt()
                BLUE = 0xFF00E5FF.toInt(); BLUE_TEXT = 0xFF7DF2FF.toInt(); BLUE_BG = 0x3300E5FF
                WARN = 0xFFFF4D6D.toInt(); AMBER_TEXT = 0xFFFFD166.toInt(); AMBER_BG = 0x33FFD166
                MAP_BG = 0xFF0B0718.toInt(); ROAD = 0x66000000; ROAD_EDGE = 0x66FF2BD6; HORIZON = 0x338A5CFF
                ART_BG = 0x26FFFFFF; SEP = 0x33FFFFFF; FLOAT = 0xE60B0718.toInt()
            }
            Skin.neon -> {
                BG = 0xFFF1EEFA.toInt(); CARD = 0xBFFFFFFF.toInt(); CARD2 = 0x142A1060; ROAD_BG = 0x80FFFFFF.toInt()
                BORDER = 0x262A1060; BORDER2 = 0x382A1060; TRACK = 0x2E8A5CFF
                TEXT = 0xFF140A2E.toInt(); TEXT2 = 0xFF2E2350.toInt(); TEXT3 = 0xFF1E143D.toInt(); MUTED = 0xFF5F5585.toInt()
                BLUE = 0xFF008FA8.toInt(); BLUE_TEXT = 0xFF006A7D.toInt(); BLUE_BG = 0x26008FA8
                WARN = 0xFFD1204A.toInt(); AMBER_TEXT = 0xFF8A4A00.toInt(); AMBER_BG = 0x33F2A33A
                MAP_BG = 0xFFE6E2F2.toInt(); ROAD = 0x332A1060; ROAD_EDGE = 0x66FF2BD6; HORIZON = 0x262A1060
                ART_BG = 0x1F2A1060; SEP = 0x332A1060; FLOAT = 0xEBFFFFFF.toInt()
            }
            Skin.lux && night -> {
                BG = 0xFF070605.toInt(); CARD = 0xE60F0E0B.toInt(); CARD2 = 0xFF1A1814.toInt(); ROAD_BG = 0xFF0D0C0A.toInt()
                BORDER = 0x33D4AF6A; BORDER2 = 0x4DD4AF6A; TRACK = 0xFF27231C.toInt()
                TEXT = 0xFFF6F0E4.toInt(); TEXT2 = 0xFFDCD2BF.toInt(); TEXT3 = 0xFFEEE6D6.toInt(); MUTED = 0xFFA69B85.toInt()
                BLUE = 0xFFD4AF6A.toInt(); BLUE_TEXT = 0xFFE9CF98.toInt(); BLUE_BG = 0x33D4AF6A
                WARN = 0xFFE5533D.toInt(); AMBER_TEXT = 0xFFF1C27D.toInt(); AMBER_BG = 0x33F1C27D
                MAP_BG = 0xFF12110E.toInt(); ROAD = 0xFF141310.toInt(); ROAD_EDGE = 0x80D4AF6A.toInt(); HORIZON = 0xFF221F19.toInt()
                ART_BG = 0xFF1E1B16.toInt(); SEP = 0x33D4AF6A; FLOAT = 0xEB0F0E0B.toInt()
            }
            Skin.lux -> {
                BG = 0xFFF3EEE4.toInt(); CARD = 0xF2FFFDF8.toInt(); CARD2 = 0xFFF0E9DC.toInt(); ROAD_BG = 0xFFF0EADF.toInt()
                BORDER = 0x66B08D4A; BORDER2 = 0x80B08D4A.toInt(); TRACK = 0xFFE4DBCB.toInt()
                TEXT = 0xFF1B1712.toInt(); TEXT2 = 0xFF3A3328.toInt(); TEXT3 = 0xFF29231B.toInt(); MUTED = 0xFF6E6453.toInt()
                BLUE = 0xFF9C7A3C.toInt(); BLUE_TEXT = 0xFF6E5423.toInt(); BLUE_BG = 0x339C7A3C
                WARN = 0xFFB8321F.toInt(); AMBER_TEXT = 0xFF8A4A00.toInt(); AMBER_BG = 0x33F2A33A
                MAP_BG = 0xFFEAE3D6.toInt(); ROAD = 0xFFDCD3C3.toInt(); ROAD_EDGE = 0xFFB08D4A.toInt(); HORIZON = 0xFFD8CDB8.toInt()
                ART_BG = 0xFFE7DFD0.toInt(); SEP = 0x4DB08D4A; FLOAT = 0xF2FFFDF8.toInt()
            }
            Skin.sport -> {
                BG = 0xFFE3E5E9.toInt(); CARD = 0xD9FFFFFF.toInt(); CARD2 = 0x12000000; ROAD_BG = 0xFFEDEFF2.toInt()
                BORDER = 0x1F000000; BORDER2 = 0x2E000000; TRACK = 0xFFD5D9DF.toInt()
                TEXT = 0xFF0E1013.toInt(); TEXT2 = 0xFF2A2F36.toInt(); TEXT3 = 0xFF1B1F25.toInt(); MUTED = 0xFF555D68.toInt()
                ART_BG = 0xFFDDE1E6.toInt(); SEP = 0x2E000000; FLOAT = 0xEBFFFFFF.toInt()
            }
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
    /** Very large numbers (speed, clock). */
    var display: Typeface = Typeface.DEFAULT
    private val all = HashMap<String, Typeface>()

    fun load(ctx: Context) {
        if (all.isEmpty()) {
            for (n in listOf("Barlow-Regular", "Barlow-Medium", "Barlow-SemiBold", "Barlow-Bold", "BarlowSemiCondensed-SemiBold",
                "Outfit-ExtraLight", "Outfit-Light", "Outfit-Regular", "Outfit-Medium", "Outfit-SemiBold",
                "Rajdhani-Medium", "Rajdhani-SemiBold", "Rajdhani-Bold", "CormorantGaramond-Light", "CormorantGaramond-SemiBold")) {
                try { all[n] = Typeface.createFromAsset(ctx.assets, "fonts/$n.ttf") } catch (_: Exception) {}
            }
            // Orbitron ships unmodified as one variable font: pick weights at run time
            for ((n, w) in listOf("Orbitron-Medium" to 500, "Orbitron-Bold" to 700)) {
                all[n] = try {
                    Typeface.Builder(ctx.assets, "fonts/Orbitron-Variable.ttf").setFontVariationSettings("'wght' $w").build()
                } catch (_: Exception) { null } ?: try { Typeface.createFromAsset(ctx.assets, "fonts/Orbitron-Variable.ttf") } catch (_: Exception) { continue }
            }
        }
        apply()
    }

    /** Pick the fonts of the current [Skin]. */
    fun apply() {
        fun f(name: String, fb: Typeface): Typeface = all[name] ?: fb
        regular = f("Barlow-Regular", Typeface.DEFAULT)
        medium = f("Barlow-Medium", Typeface.DEFAULT)
        semibold = f("Barlow-SemiBold", Typeface.DEFAULT_BOLD)
        bold = f("Barlow-Bold", Typeface.DEFAULT_BOLD)
        numeric = f("BarlowSemiCondensed-SemiBold", Typeface.DEFAULT_BOLD)
        display = numeric
        when (Skin.id) {
            Skin.GLASS -> {
                regular = f("Outfit-Regular", regular); medium = f("Outfit-Medium", medium)
                semibold = f("Outfit-SemiBold", semibold); bold = f("Outfit-SemiBold", bold)
                numeric = f("Outfit-Light", numeric); display = f("Outfit-ExtraLight", numeric)
            }
            Skin.SPORT -> {
                numeric = f("Rajdhani-Bold", numeric); display = numeric
            }
            Skin.NEON -> {
                regular = f("Outfit-Regular", regular); medium = f("Outfit-Medium", medium)
                semibold = f("Outfit-SemiBold", semibold); bold = f("Outfit-SemiBold", bold)
                numeric = f("Orbitron-Medium", numeric); display = f("Orbitron-Bold", numeric)
            }
            Skin.LUX -> {
                regular = f("Outfit-Light", regular); medium = f("Outfit-Regular", medium)
                semibold = f("Outfit-Medium", semibold); bold = f("Outfit-SemiBold", bold)
                numeric = f("CormorantGaramond-SemiBold", numeric); display = f("CormorantGaramond-Light", numeric)
            }
        }
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

fun cardBg(color: Int = C.CARD, clip: Boolean = false): android.graphics.drawable.Drawable = Skin.card(color, clip)

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
        letterSpacing = when (Skin.id) { Skin.CLASSIC -> 0.08f; Skin.LUX -> 0.24f; else -> 0.16f }
    }
}

fun Context.caption(text: String, size: Float = 13f): TextView =
    label(size, C.MUTED, F.semibold, caps = true).apply { this.text = text }

fun Context.spacer(): View = View(this)

/** Same-width digits, and lining (not old-style) figures for the Luxury serif. */
fun TextView.tnum(): TextView = apply { fontFeatureSettings = "tnum, lnum" }

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
