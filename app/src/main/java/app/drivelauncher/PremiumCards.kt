package app.drivelauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Where the speed comes from: GPS first, then the OBD2 adapter. */
object Drive {
    /** Speed in km/h (null when unknown) and a short source label. */
    fun speed(): Pair<Float?, String> {
        val obd = Obd.v("speed")
        return when {
            Gps.fresh -> Gps.speedKmh to "GPS"
            obd != null -> obd to "OBD"
            else -> null to (if (Gps.permission) "NO GPS FIX" else "NO GPS ACCESS")
        }
    }

    fun sourceLine(name: String): String {
        val lim = Prefs.speedLimit
        return if (lim > 0) "$name · LIMIT $lim" else name
    }
}

// ------------------------------------------------------------------ car picture

/** Drawn side view of a saloon car: used only if the rendered pictures can't be loaded. */
private object VectorCar {
    private val body = Path()
    private val roof = Path()
    private val rearWin = Path()
    private val frontWin = Path()
    private val shine = Path()
    private val dash = Path()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rf = RectF()
    private var key = ""
    private var bodySh: Shader? = null
    private var winSh: Shader? = null
    private var rimSh: Shader? = null
    private var shineSh: Shader? = null
    private var glowSh: Shader? = null
    private var groundSh: Shader? = null

    init {
        fun top(path: Path) {
            path.moveTo(34f, 150f); path.cubicTo(30f, 134f, 36f, 121f, 60f, 117f); path.lineTo(150f, 108f)
            path.cubicTo(190f, 104f, 215f, 78f, 260f, 66f); path.cubicTo(300f, 56f, 380f, 54f, 420f, 62f)
            path.cubicTo(450f, 70f, 480f, 92f, 500f, 104f); path.lineTo(572f, 114f); path.cubicTo(596f, 118f, 606f, 128f, 606f, 140f)
        }
        top(body)
        body.lineTo(604f, 152f); body.lineTo(540f, 152f)
        body.arcTo(RectF(452f, 108f, 540f, 196f), 0f, -180f, false)
        body.lineTo(196f, 152f)
        body.arcTo(RectF(108f, 108f, 196f, 196f), 0f, -180f, false)
        body.lineTo(36f, 152f); body.close()
        top(roof)
        rearWin.moveTo(198f, 104f); rearWin.cubicTo(228f, 84f, 252f, 74f, 280f, 70f); rearWin.lineTo(340f, 66f); rearWin.lineTo(342f, 104f); rearWin.close()
        frontWin.moveTo(352f, 104f); frontWin.lineTo(350f, 65f); frontWin.lineTo(405f, 66f); frontWin.cubicTo(430f, 72f, 452f, 88f, 470f, 104f); frontWin.close()
        shine.moveTo(62f, 124f); shine.cubicTo(200f, 115f, 420f, 113f, 592f, 121f)
        dash.moveTo(0f, 201f); dash.lineTo(640f, 201f)
    }

    private fun shaders() {
        val k = "${C.night}${C.accent}"
        if (k == key) return
        key = k
        bodySh = LinearGradient(0f, 54f, 0f, 152f,
            if (C.night) intArrayOf(0xFF46505F.toInt(), 0xFF1B2029.toInt(), 0xFF0B0E13.toInt())
            else intArrayOf(0xFFFAFBFC.toInt(), 0xFFC6CDD7.toInt(), 0xFF7A8492.toInt()),
            floatArrayOf(0f, 0.42f, 1f), Shader.TileMode.CLAMP)
        winSh = LinearGradient(198f, 62f, 470f, 104f, intArrayOf(0xFF33405A.toInt(), 0xFF121722.toInt(), 0xFF07090D.toInt()),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        rimSh = LinearGradient(-27f, -27f, 27f, 27f, 0xFFF1F4F8.toInt(), 0xFF5E6673.toInt(), Shader.TileMode.CLAMP)
        shineSh = LinearGradient(62f, 0f, 592f, 0f, intArrayOf(0x00FFFFFF, if (C.night) 0x99FFFFFF.toInt() else 0xFFFFFFFF.toInt(), 0x00FFFFFF),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        glowSh = RadialGradient(322f, 188f, 330f, withAlpha(C.accent, if (C.night) 0.42f else 0.30f), withAlpha(C.accent, 0f), Shader.TileMode.CLAMP)
        val g = if (C.night) 0x59FFFFFF else 0x401B2A44
        groundSh = LinearGradient(10f, 0f, 630f, 0f, intArrayOf(g and 0x00FFFFFF, g, g and 0x00FFFFFF), null, Shader.TileMode.CLAMP)
    }

    /** Draw the car with its bottom at [bottom], centred on [cx], as big as fits in maxW x maxH. */
    fun draw(c: Canvas, cx: Float, bottom: Float, maxW: Float, maxH: Float, wheelDeg: Float, dashPhase: Float, moving: Boolean) {
        if (maxW <= 0f || maxH <= 0f) return
        shaders()
        val s = min(maxW / 640f, maxH / 214f)
        c.save()
        c.translate(cx - 320f * s, bottom - 214f * s)
        c.scale(s, s)
        p.pathEffect = null
        p.strokeCap = Paint.Cap.ROUND
        // floor glow and ground line
        p.style = Paint.Style.FILL
        c.save(); c.scale(1f, 0.2f, 322f, 188f)
        p.color = 0xFF000000.toInt(); p.shader = glowSh; c.drawCircle(322f, 188f, 330f, p)
        c.restore()
        p.color = 0xFF000000.toInt(); p.shader = groundSh; c.drawRect(10f, 191f, 630f, 192f, p)
        if (moving) {
            p.shader = null
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f
            p.color = if (C.night) 0x24FFFFFF else 0x261B2A44
            p.pathEffect = DashPathEffect(floatArrayOf(22f, 28f), dashPhase)
            c.drawPath(dash, p)
            p.pathEffect = null
            p.style = Paint.Style.FILL
        }
        p.shader = null
        p.color = if (C.night) 0xB3000000.toInt() else 0x40000000
        rf.set(22f, 182f, 622f, 200f); c.drawOval(rf, p)
        // body
        p.color = 0xFF000000.toInt(); p.shader = bodySh; c.drawPath(body, p)
        p.color = 0xFF000000.toInt(); p.shader = winSh; c.drawPath(rearWin, p); c.drawPath(frontWin, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 10f; p.color = 0x1FFFFFFF
        c.drawLine(214f, 100f, 300f, 69f, p)
        p.strokeWidth = 1.6f; p.color = withAlpha(C.accent, 0.6f)
        c.drawPath(roof, p)
        p.strokeWidth = 2f; p.color = 0xFF000000.toInt(); p.shader = shineSh
        c.drawPath(shine, p)
        p.shader = null
        p.strokeWidth = 1.5f; p.color = 0x8C000000.toInt()
        c.drawLine(347f, 66f, 346f, 146f, p)
        p.strokeWidth = 2f; p.color = 0x59000000
        c.drawLine(44f, 145f, 600f, 145f, p)
        p.style = Paint.Style.FILL; p.color = 0x99AEB6C2.toInt()
        rf.set(286f, 113f, 308f, 117f); c.drawRoundRect(rf, 2f, 2f, p)
        rf.set(402f, 113f, 424f, 117f); c.drawRoundRect(rf, 2f, 2f, p)
        // lamps
        lamp(c, 574f, 117f, 601f, 124f, C.accent)
        lamp(c, 39f, 123f, 66f, 119f, 0xFFFF3B4A.toInt())
        wheel(c, 152f, 153f, wheelDeg)
        wheel(c, 496f, 153f, wheelDeg)
        c.restore()
        p.shader = null
    }

    private fun lamp(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, color: Int) {
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        for ((w, a) in listOf(14f to 0.12f, 8f to 0.28f, 3.5f to 1f)) {
            p.strokeWidth = w; p.color = withAlpha(color, a)
            c.drawLine(x1, y1, x2, y2, p)
        }
    }

    private fun wheel(c: Canvas, x: Float, y: Float, deg: Float) {
        c.save()
        c.translate(x, y)
        p.style = Paint.Style.FILL
        p.shader = null; p.color = 0xFF08090C.toInt(); c.drawCircle(0f, 0f, 37f, p)
        p.color = 0xFF000000.toInt(); p.shader = rimSh; c.drawCircle(0f, 0f, 27f, p)
        p.shader = null; p.color = 0xFF2A2F37.toInt(); c.drawCircle(0f, 0f, 21f, p)
        c.rotate(deg)
        p.style = Paint.Style.STROKE; p.strokeWidth = 4f; p.strokeCap = Paint.Cap.ROUND; p.color = 0xFF000000.toInt(); p.shader = rimSh
        for (i in 0 until 5) { c.drawLine(0f, -20f, 0f, 20f, p); c.rotate(36f) }
        p.shader = null
        p.style = Paint.Style.FILL; p.color = 0xFF14171C.toInt(); c.drawCircle(0f, 0f, 6f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f; p.color = 0xFFC8CED7.toInt(); c.drawCircle(0f, 0f, 6f, p)
        c.restore()
    }
}


/**
 * The car on the home screen: a 3D-rendered saloon (original design, made with tools/car3d) in the owner's
 * paint colour with wheels that turn while driving, or the owner's own photo of their car.
 */
object CarArt {
    // picture frame and wheel positions in metres (must match tools/car3d/car.js)
    private const val X0 = -0.04f
    private const val X1 = 4.90f
    private const val Y0 = -0.02f
    private const val Y1 = 1.56f
    private val WHEELS = floatArrayOf(0.98f, 3.86f)
    private const val WY = 0.345f
    private const val WHEEL_HALF = 0.349f
    private val HEAD = floatArrayOf(4.80f, 0.80f)
    private val TAIL = floatArrayOf(0.07f, 0.97f)

    /** Paint colours: key to (name, swatch colour). */
    val COLORS = linkedMapOf(
        "silver" to ("Silver" to 0xFFB9BFC7.toInt()), "white" to ("White" to 0xFFE8EAED.toInt()),
        "grey" to ("Grey" to 0xFF5B616A.toInt()), "black" to ("Black" to 0xFF16181B.toInt()),
        "blue" to ("Blue" to 0xFF1D3F8F.toInt()), "red" to ("Red" to 0xFF8E1616.toInt()),
        "bronze" to ("Bronze" to 0xFF6E4B2F.toInt()), "green" to ("Green" to 0xFF1D3A30.toInt()),
    )

    private var app: android.content.Context? = null
    private var bodyKey = ""
    private var body: android.graphics.Bitmap? = null
    private var wheel: android.graphics.Bitmap? = null
    private var photoKey = -1L
    private var photo: android.graphics.Bitmap? = null
    private val bp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rf = RectF()
    private val dash = Path()
    private val clear = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT)

    fun init(ctx: android.content.Context) { app = ctx.applicationContext }

    /** Forget loaded pictures (after the colour or photo changed). */
    fun reset() { bodyKey = ""; body = null; photoKey = -1L; photo = null }

    fun photoFile(ctx: android.content.Context) = java.io.File(ctx.filesDir, "car.png")

    private fun asset(name: String): android.graphics.Bitmap? = try {
        app?.assets?.open(name)?.use { android.graphics.BitmapFactory.decodeStream(it) }
    } catch (e: Exception) { Diag.log("car picture $name: ${e.message}"); null }

    private fun bodyBitmap(): android.graphics.Bitmap? {
        val k = Prefs.carColor
        if (k != bodyKey) {
            bodyKey = k
            body = asset("car/car_${if (k in COLORS) k else "silver"}.webp")
            if (wheel == null) wheel = asset("car/wheel.webp")
        }
        return body
    }

    private fun photoBitmap(): android.graphics.Bitmap? {
        val ctx = app ?: return null
        if (!Prefs.carPhoto) return null
        val f = photoFile(ctx)
        val stamp = if (f.exists()) f.lastModified() else 0L
        if (stamp != photoKey) {
            photoKey = stamp
            photo = if (stamp == 0L) null else try { android.graphics.BitmapFactory.decodeFile(f.path) } catch (_: Exception) { null }
        }
        return photo
    }

    fun draw(c: Canvas, cx: Float, bottom: Float, maxW: Float, maxH: Float, wheelDeg: Float, dashPhase: Float, moving: Boolean) {
        if (maxW <= 0f || maxH <= 0f) return
        val ph = photoBitmap()
        if (ph != null) { drawPhoto(c, ph, cx, bottom, maxW, maxH); return }
        val b = bodyBitmap()
        val w = wheel
        if (b == null || w == null) { VectorCar.draw(c, cx, bottom, maxW, maxH, wheelDeg, dashPhase, moving); return }
        val fw = X1 - X0; val fh = Y1 - Y0
        val s = min(maxW / fw, maxH / fh)
        val left = cx - fw * s / 2f
        val top = bottom - fh * s
        fun px(x: Float) = left + (x - X0) * s
        fun py(y: Float) = top + (Y1 - y) * s
        val ground = py(0f)
        floor(c, cx, ground, fw * s, s, dashPhase, moving)
        // soft contact shadow under the whole car, darker under the tyres
        p.xfermode = null; p.style = Paint.Style.FILL
        shadow(c, cx, ground, fw * s * 0.47f, 0.11f * s, if (C.night) 0.85f else 0.45f)
        for (x in WHEELS) shadow(c, px(x), ground, 0.42f * s, 0.05f * s, if (C.night) 0.9f else 0.55f)
        // lamps glow behind the body edges
        glow(c, px(HEAD[0]), py(HEAD[1]), 0.34f * s, C.accent, if (C.night) 0.40f else 0.22f)
        glow(c, px(TAIL[0]), py(TAIL[1]), 0.30f * s, 0xFFFF3B4A.toInt(), if (C.night) 0.38f else 0.18f)
        rf.set(left, top, left + fw * s, top + fh * s)
        c.drawBitmap(b, null, rf, bp)
        val r = WHEEL_HALF * s
        for (x in WHEELS) {
            val wx = px(x); val wy = py(WY)
            c.save()
            c.rotate(wheelDeg, wx, wy)
            rf.set(wx - r, wy - r, wx + r, wy + r)
            c.drawBitmap(w, null, rf, bp)
            c.restore()
        }
    }

    private fun floor(c: Canvas, cx: Float, ground: Float, width: Float, s: Float, dashPhase: Float, moving: Boolean) {
        p.style = Paint.Style.FILL; p.xfermode = null
        c.save(); c.scale(1f, 0.16f, cx, ground)
        p.color = 0xFF000000.toInt()
        p.shader = RadialGradient(cx, ground, width * 0.56f, withAlpha(C.accent, if (C.night) 0.42f else 0.28f), withAlpha(C.accent, 0f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, ground, width * 0.56f, p)
        c.restore()
        val g = if (C.night) 0x59FFFFFF else 0x401B2A44
        p.shader = LinearGradient(cx - width * 0.5f, 0f, cx + width * 0.5f, 0f, intArrayOf(g and 0x00FFFFFF, g, g and 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        c.drawRect(cx - width * 0.5f, ground, cx + width * 0.5f, ground + max(1f, 0.006f * s), p)
        p.shader = null
        if (moving) {
            p.style = Paint.Style.STROKE; p.strokeWidth = max(1f, 0.01f * s)
            p.color = if (C.night) 0x24FFFFFF else 0x261B2A44
            val k = s / 130f // dash pattern scaled to the car (phase is in 640-wide drawing units)
            p.pathEffect = DashPathEffect(floatArrayOf(22f * k, 28f * k), dashPhase * k)
            dash.reset(); dash.moveTo(cx - width * 0.52f, ground + 0.06f * s); dash.lineTo(cx + width * 0.52f, ground + 0.06f * s)
            c.drawPath(dash, p)
            p.pathEffect = null; p.style = Paint.Style.FILL
        }
    }

    private fun shadow(c: Canvas, x: Float, y: Float, rx: Float, ry: Float, a: Float) {
        c.save(); c.scale(1f, ry / rx, x, y)
        p.color = 0xFF000000.toInt()
        p.shader = RadialGradient(x, y, rx, intArrayOf(withAlpha(0xFF000000.toInt(), a), withAlpha(0xFF000000.toInt(), a * 0.6f), 0), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, rx, p)
        c.restore(); p.shader = null
    }

    private fun glow(c: Canvas, x: Float, y: Float, r: Float, color: Int, a: Float) {
        p.color = 0xFF000000.toInt()
        p.shader = RadialGradient(x, y, r, withAlpha(color, a), withAlpha(color, 0f), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, p)
        p.shader = null
    }

    /** The owner's own photo: cut-out pictures stand on the floor; normal photos get soft edges. */
    private fun drawPhoto(c: Canvas, b: android.graphics.Bitmap, cx: Float, bottom: Float, maxW: Float, maxH: Float) {
        val s = min(maxW / b.width, maxH / b.height)
        val w = b.width * s; val h = b.height * s
        rf.set(cx - w / 2, bottom - h, cx + w / 2, bottom)
        if (b.hasAlpha()) {
            floor(c, cx, bottom - h * 0.02f, w, h / 1.5f, 0f, false)
            shadow(c, cx, bottom - h * 0.03f, w * 0.46f, h * 0.06f, if (C.night) 0.8f else 0.4f)
            c.drawBitmap(b, null, rf, bp)
            return
        }
        val layer = c.saveLayer(rf, null)
        c.drawBitmap(b, null, rf, bp)
        p.xfermode = clear
        val fx = w * 0.12f; val fy = h * 0.14f
        fun fade(l: Float, t: Float, r: Float, bt: Float, x0: Float, y0: Float, x1: Float, y1: Float) {
            p.color = 0xFF000000.toInt()
            p.shader = LinearGradient(x0, y0, x1, y1, 0xFF000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
            c.drawRect(l, t, r, bt, p)
        }
        fade(rf.left, rf.top, rf.left + fx, rf.bottom, rf.left, 0f, rf.left + fx, 0f)
        fade(rf.right - fx, rf.top, rf.right, rf.bottom, rf.right, 0f, rf.right - fx, 0f)
        fade(rf.left, rf.top, rf.right, rf.top + fy, 0f, rf.top, 0f, rf.top + fy)
        fade(rf.left, rf.bottom - fy, rf.right, rf.bottom, 0f, rf.bottom, 0f, rf.bottom - fy)
        p.shader = null; p.xfermode = null
        c.restoreToCount(layer)
    }
}

// ------------------------------------------------------------------ hero

/** Big "drive hero": clock, huge speed, three trip numbers and the car. Drawn as one picture so it fits any size. */
class HeroView(ctx: Context) : View(ctx) {
    private var key = ""
    private var time = ""; private var ampm = ""; private var date = ""; private var temp = ""; private var wx = Ic.SUN
    private var speed = "--"; private var unit = ""; private var src = ""; private var frac = 0f; private var warn = false; private var dim = true
    private val stats = Array(3) { arrayOf("", "", "") }
    private var lowRange = false
    private var kmh = 0f
    private var wheel = 0f
    private var phase = 0f
    private var last = 0L
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rf = RectF()

    fun update() {
        time = Fmt.timeShort(); ampm = Fmt.ampm(); date = Fmt.dateLong()
        val tc = Weather.tempC
        temp = if (tc.isNaN()) "" else fmt("%.0f°", Units.temp(tc)); wx = Weather.icon()
        val (k, name) = Drive.speed()
        kmh = k ?: 0f
        val shown = k?.let { Units.speed(it) }
        speed = shown?.roundToInt()?.toString() ?: "--"
        unit = Units.speedUnitLower
        dim = k == null
        warn = SpeedAlert.over
        src = Drive.sourceLine(name)
        val lim = Prefs.speedLimit
        frac = ((shown ?: 0f) / (if (lim > 0) lim * 1.25f else if (Units.imp) 100f else 160f)).coerceIn(0f, 1f)
        stats[0][0] = "TRIP"; stats[0][1] = fmt("%.1f", Units.dist(Gps.tripMeters)); stats[0][2] = Units.distUnit
        val range = FuelRange.now()
        if (range != null && (Obd.connected || !range.approx || FuelLog.list().isNotEmpty())) {
            stats[1][0] = "RANGE"; stats[1][1] = FuelRange.text(range); stats[1][2] = Units.distUnit
        } else {
            stats[1][0] = "AVERAGE"; stats[1][1] = fmt("%.0f", Units.speed(Gps.avgKmh)); stats[1][2] = Units.speedUnitLower
        }
        lowRange = range != null && range.km < 60
        val ct = Obd.v("coolant")
        if (Obd.connected && ct != null) {
            stats[2][0] = "COOLANT"; stats[2][1] = fmt("%.0f", ct); stats[2][2] = "°C"
        } else {
            stats[2][0] = "HEADING"
            stats[2][1] = if (Gps.fresh || Gps.bearing != 0f) Gps.compass(Gps.bearing) else "--"
            stats[2][2] = if (Gps.fresh || Gps.bearing != 0f) fmt("%.0f°", Gps.bearing) else ""
        }
        val nk = "$time|$ampm|$date|$temp|$wx|$speed|$unit|$src|$frac|$warn|$dim|${stats.joinToString { it.joinToString() }}|${C.night}|" +
            "${RoadLimit.signNumber()}|${SpeedAlert.overRoad}|$lowRange"
        if (nk != key) { key = nk; invalidate() }
        if (kmh > 1f && Motion.on && last == 0L) invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val s = min(w / 780f, h / 600f)
        val pad = 6f * s
        tp.letterSpacing = 0f
        tp.textAlign = Paint.Align.LEFT

        // clock, date, weather
        tp.typeface = F.medium; tp.textSize = 34f * s; tp.color = C.TEXT
        var x = pad
        c.drawText(time, x, 38f * s, tp); x += tp.measureText(time) + 7f * s
        if (ampm.isNotEmpty()) {
            tp.typeface = F.semibold; tp.textSize = 15f * s; tp.color = C.MUTED
            c.drawText(ampm, x, 38f * s, tp); x += tp.measureText(ampm) + 16f * s
        } else x += 9f * s
        tp.typeface = F.regular; tp.textSize = 17f * s; tp.color = C.MUTED
        c.drawText(date, x, 38f * s, tp)
        if (temp.isNotEmpty()) {
            tp.typeface = F.medium; tp.textSize = 19f * s
            val tw = tp.measureText(temp)
            val pw = 18f * s + 8f * s + tw + 34f * s
            rf.set(w - pad - pw, 8f * s, w - pad, 48f * s)
            p.style = Paint.Style.FILL; p.shader = null; p.color = C.CARD2
            c.drawRoundRect(rf, 20f * s, 20f * s, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = max(1f, s); p.color = C.BORDER2
            c.drawRoundRect(rf, 20f * s, 20f * s, p)
            Icons.draw(c, wx, rf.left + 17f * s + 9f * s, rf.centerY(), 18f * s, C.TEXT2)
            tp.color = C.TEXT
            c.drawText(temp, rf.left + 17f * s + 26f * s, rf.centerY() + 7f * s, tp)
        }

        // stats column on the right
        val sx1 = w - pad
        val sx0 = sx1 - min(330f * s, w * 0.42f)
        for (i in 0 until 3) {
            val top = 96f * s + i * 66f * s
            tp.typeface = F.semibold; tp.textSize = 12f * s; tp.color = C.MUTED; tp.letterSpacing = 0.2f; tp.textAlign = Paint.Align.LEFT
            c.drawText(stats[i][0], sx0, top + 40f * s, tp)
            tp.letterSpacing = 0f; tp.textAlign = Paint.Align.RIGHT
            tp.typeface = F.regular; tp.textSize = 15f * s; tp.color = C.MUTED
            val uw = if (stats[i][2].isEmpty()) 0f else tp.measureText(stats[i][2]) + 7f * s
            c.drawText(stats[i][2], sx1, top + 42f * s, tp)
            tp.typeface = F.numeric; tp.textSize = 32f * s; tp.color = if (i == 1 && lowRange && stats[1][0] == "RANGE") C.WARN else C.TEXT
            c.drawText(stats[i][1], sx1 - uw, top + 42f * s, tp)
            if (i < 2) {
                p.style = Paint.Style.FILL; p.shader = null; p.color = C.SEP
                c.drawRect(sx0, top + 64f * s, sx1, top + 64f * s + max(1f, s), p)
            }
        }
        tp.textAlign = Paint.Align.LEFT

        // speed
        tp.typeface = F.semibold; tp.textSize = 13f * s; tp.color = C.MUTED; tp.letterSpacing = 0.22f
        c.drawText("SPEED", pad + 4f * s, 112f * s, tp)
        tp.letterSpacing = if (Skin.glass) -0.04f else 0f
        tp.typeface = F.display
        tp.textSize = when (Skin.id) { Skin.GLASS -> 172f; Skin.LUX -> 184f; Skin.NEON -> 132f; else -> 150f } * s
        tp.textSize *= min(1f, (sx0 - pad - 90f * s) / max(1f, tp.measureText(speed)))
        tp.color = if (warn) C.WARN else if (dim) C.MUTED else C.TEXT
        val base = 128f * s + tp.textSize * 0.72f
        c.drawText(speed, pad, base, tp)
        val numSize = tp.textSize
        val nx = pad + tp.measureText(speed) + 12f * s
        tp.letterSpacing = 0f
        tp.typeface = F.medium; tp.textSize = 22f * s; tp.color = C.MUTED
        c.drawText(unit, nx, base, tp)
        val sign = RoadLimit.signNumber()
        if (sign != null) {
            val r = 34f * s
            val sx = nx + tp.measureText(unit) + 22f * s + r
            if (sx + r < sx0 - 8f * s) SpeedSign.draw(c, sx, base - numSize * 0.5f, r, sign, RoadLimit.postedMph, SpeedAlert.overRoad)
        }
        // speed bar
        val by = base + 30f * s
        val bx0 = pad + 4f * s
        val bx1 = bx0 + min(300f * s, sx0 - bx0 - 30f * s)
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.shader = null
        p.strokeWidth = 4f * s; p.color = C.TRACK
        c.drawLine(bx0, by, bx1, by, p)
        val fx = bx0 + (bx1 - bx0) * frac
        if (frac > 0.005f) {
            val col = if (warn) C.WARN else C.accent
            if (Skin.glowy || Skin.lux) {
                p.strokeWidth = 12f * s; p.color = withAlpha(col, 0.16f); c.drawLine(bx0, by, fx, by, p)
                p.strokeWidth = 4f * s
                p.color = 0xFF000000.toInt(); p.shader = LinearGradient(bx0, 0f, bx1, 0f, col, if (warn) col else Skin.accent2, Shader.TileMode.CLAMP)
                c.drawLine(bx0, by, fx, by, p)
                p.shader = null
            } else {
                p.strokeWidth = 4f * s; p.color = col; c.drawLine(bx0, by, fx, by, p)
            }
            p.style = Paint.Style.FILL; p.color = if (C.night) 0xFFFFFFFF.toInt() else C.TEXT
            c.drawCircle(fx, by, 6f * s, p)
        }
        tp.typeface = F.semibold; tp.textSize = 12f * s; tp.color = C.MUTED; tp.letterSpacing = 0.14f
        c.drawText(src, bx0, by + 30f * s, tp)
        tp.letterSpacing = 0f

        // car
        val carTop = max(by + 44f * s, h * 0.5f)
        val moving = kmh > 1f && Motion.on
        if (moving) {
            val now = SystemClock.uptimeMillis()
            if (last != 0L) {
                val dt = ((now - last) / 1000f).coerceAtMost(0.1f)
                wheel = (wheel + min(kmh * 6f, 240f) * dt) % 360f
                phase = (phase - min(kmh * 3f, 260f) * dt) % 50f
                if (phase < 0f) phase += 50f
            }
            last = now
        } else last = 0L
        CarArt.draw(c, w / 2f, h - 2f * s, w * 0.96f, h - 2f * s - carTop, wheel, phase, moving)
        if (moving && isShown) postInvalidateDelayed(40)
    }
}

class HeroCard(private val a: MainActivity) : Card {
    override val view = FrameLayout(a)
    private val hero = HeroView(a)

    init {
        if (!Skin.floatingHero) { view.background = cardBg(); view.pad(20f, 14f) } else view.pad(4f, 0f)
        hero.contentDescription = "Speed, trip and clock"
        view.addView(hero, flp(MATCH, MATCH))
        view.setOnClickListener { a.show(MainActivity.Page.TELEMETRY) }
    }

    override fun update() = hero.update()
}

// ------------------------------------------------------------------ dials

/**
 * Round dial. Carbon Sport: real analog instrument (metal bezel, ticks, needle, red line).
 * Other themes: a slim arc with a glowing end.
 */
class DialView(ctx: Context, private val maxV: Float, private val labels: Array<String>, private val redFrom: Float = -1f) : View(ctx) {
    private var target = 0f
    private var shown = 0f
    private var big = "--"
    private var unit = ""
    private var warn = false
    private var dim = true
    private var last = 0L
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rf = RectF()
    private val m = Matrix()
    /** Height kept free below the dial (for its footer) when measuring a square. */
    var reserve = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) w
            else min(w, MeasureSpec.getSize(heightMeasureSpec) - reserve)
        setMeasuredDimension(w, max(0, h))
    }

    fun set(v: Float?, big: String, unit: String, warn: Boolean = false) {
        val t = (v ?: 0f).coerceIn(0f, maxV)
        val d = v == null
        if (t != target || big != this.big || unit != this.unit || warn != this.warn || d != dim) {
            target = t; this.big = big; this.unit = unit; this.warn = warn; dim = d
            invalidate()
        }
    }

    private fun arc(c: Canvas, cx: Float, cy: Float, r: Float, from: Float, sweep: Float) {
        rf.set(cx - r, cy - r, cx + r, cy + r)
        c.drawArc(rf, 135f + 270f * from, 270f * sweep, false, p)
    }

    override fun onDraw(c: Canvas) {
        if (Motion.on) {
            val now = SystemClock.uptimeMillis()
            val dt = if (last == 0L) 16L else (now - last).coerceIn(1L, 100L)
            last = now
            shown += (target - shown) * (1f - exp(-dt / 140f))
            if (abs(target - shown) > maxV * 0.002f) postInvalidateOnAnimation() else { shown = target; last = 0L }
        } else shown = target
        val w = width.toFloat(); val h = height.toFloat()
        val size = min(w, h)
        if (size <= 0f) return
        val cx = w / 2f; val cy = h / 2f
        val s = size / 340f
        val frac = shown / maxV
        val col = if (warn) C.WARN else C.accent
        p.shader = null; p.pathEffect = null
        if (Skin.analog) {
            val lx = Skin.lux
            // bezel and face
            p.style = Paint.Style.STROKE; p.strokeWidth = 5f * s
            val metal = SweepGradient(cx, cy, if (lx) intArrayOf(0xFFF3DFA9.toInt(), 0xFF7A5C26.toInt(), 0xFFE9CF8E.toInt(), 0xFF6E5223.toInt(), 0xFFF3DFA9.toInt())
                else intArrayOf(0xFF9AA1AC.toInt(), 0xFF2A2E35.toInt(), 0xFF7C838E.toInt(), 0xFF2A2E35.toInt(), 0xFF9AA1AC.toInt()), null)
            m.setRotate(-45f, cx, cy); metal.setLocalMatrix(m)
            p.color = 0xFF000000.toInt(); p.shader = metal; c.drawCircle(cx, cy, 162f * s, p)
            p.style = Paint.Style.FILL
            p.color = 0xFF000000.toInt(); p.shader = RadialGradient(cx, cy - 34f * s, 200f * s,
                when {
                    lx && C.night -> intArrayOf(0xFF1C1A16.toInt(), 0xFF050504.toInt())
                    lx -> intArrayOf(0xFFFFFDF7.toInt(), 0xFFE9E1D0.toInt())
                    C.night -> intArrayOf(0xFF1A1D22.toInt(), 0xFF07080A.toInt())
                    else -> intArrayOf(0xFFFFFFFF.toInt(), 0xFFD9DCE1.toInt())
                }, null, Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, 158f * s, p)
            p.shader = null
            // red line band
            p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.BUTT
            if (redFrom > 0f) {
                p.strokeWidth = (if (lx) 5f else 12f) * s; p.color = if (lx) withAlpha(0xFFB23A2E.toInt(), 0.9f) else withAlpha(C.accent, 0.8f)
                arc(c, cx, cy, 142f * s, redFrom / maxV, 1f - redFrom / maxV)
            }
            // ticks
            val majors = labels.size - 1
            val minors = majors * 5
            for (i in 0..minors) {
                val major = i % 5 == 0
                val a = Math.toRadians(135.0 + 270.0 * i / minors)
                val r0 = (if (major) 133f else 138f) * s; val r1 = 148f * s
                val v = maxV * i / minors
                p.strokeWidth = (if (major) 3.2f else 1.6f) * s
                p.color = when {
                    lx -> if (major) C.accent else withAlpha(C.accent, 0.45f)
                    redFrom > 0f && v >= redFrom -> if (major) 0xFFFFFFFF.toInt() else withAlpha(0xFFFFFFFF.toInt(), 0.6f)
                    major -> if (C.night) 0xFFE6E9EE.toInt() else 0xFF2A2F36.toInt()
                    else -> if (C.night) 0xFF5A606A.toInt() else 0xFF9AA1AC.toInt()
                }
                c.drawLine(cx + r0 * cos(a).toFloat(), cy + r0 * sin(a).toFloat(), cx + r1 * cos(a).toFloat(), cy + r1 * sin(a).toFloat(), p)
            }
            // value arc
            p.strokeCap = Paint.Cap.ROUND
            p.strokeWidth = (if (lx) 4f else 10f) * s; p.color = C.TRACK
            arc(c, cx, cy, 124f * s, 0f, 1f)
            if (frac > 0.003f) {
                p.strokeWidth = (if (lx) 10f else 22f) * s; p.color = withAlpha(col, if (lx) 0.18f else 0.14f); arc(c, cx, cy, 124f * s, 0f, frac)
                val g = SweepGradient(cx, cy, intArrayOf(if (lx) Skin.accent2 else 0xFFFF7A45.toInt(), col, col), floatArrayOf(0f, 0.75f, 1f))
                m.setRotate(135f, cx, cy); g.setLocalMatrix(m)
                p.color = 0xFF000000.toInt(); p.shader = g; p.strokeWidth = (if (lx) 4f else 10f) * s; arc(c, cx, cy, 124f * s, 0f, frac)
                p.shader = null
            }
            // labels
            p.style = Paint.Style.FILL
            tp.typeface = F.numeric; tp.textSize = 21f * s; tp.letterSpacing = 0f
            for ((i, l) in labels.withIndex()) {
                val a = Math.toRadians(135.0 + 270.0 * i / majors)
                tp.color = when {
                    redFrom > 0f && maxV * i / majors >= redFrom -> if (lx) 0xFFE5533D.toInt() else C.accent
                    lx -> C.TEXT2
                    C.night -> 0xFFC9CED6.toInt()
                    else -> 0xFF2A2F36.toInt()
                }
                c.drawText(l, cx + 104f * s * cos(a).toFloat(), cy + 104f * s * sin(a).toFloat() + 7f * s, tp)
            }
            // needle
            val na = Math.toRadians(135.0 + 270.0 * frac)
            val ex = cx + 134f * s * cos(na).toFloat(); val ey = cy + 134f * s * sin(na).toFloat()
            val bx = cx - 30f * s * cos(na).toFloat(); val by = cy - 30f * s * sin(na).toFloat()
            p.style = Paint.Style.STROKE
            for ((sw, al) in listOf(12f to 0.14f, 4f to 1f)) {
                p.strokeWidth = sw * s; p.color = withAlpha(col, al); c.drawLine(bx, by, ex, ey, p)
            }
            p.style = Paint.Style.FILL; p.color = if (lx) 0xFF16130E.toInt() else 0xFF2A2E35.toInt(); c.drawCircle(cx, cy, 15f * s, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 3f * s; p.color = 0xFF000000.toInt(); p.shader = metal; c.drawCircle(cx, cy, 15f * s, p)
            p.shader = null
            // readout
            tp.typeface = F.numeric; tp.textSize = 54f * s
            tp.color = if (warn) C.WARN else if (dim) C.MUTED else C.TEXT
            c.drawText(big, cx, cy + 84f * s, tp)
            tp.typeface = F.semibold; tp.textSize = 12f * s; tp.letterSpacing = 0.16f; tp.color = C.MUTED
            c.drawText(unit, cx, cy + 108f * s, tp)
            tp.letterSpacing = 0f
        } else {
            val r = 150f * s
            p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
            p.strokeWidth = 8f * s; p.color = C.TRACK
            arc(c, cx, cy, r, 0f, 1f)
            // ticks inside the arc
            val majors = labels.size - 1
            p.strokeCap = Paint.Cap.ROUND
            for (i in 0..majors * 2) {
                val a = Math.toRadians(135.0 + 270.0 * i / (majors * 2))
                val major = i % 2 == 0
                val r0 = r - (if (major) 24f else 20f) * s; val r1 = r - 14f * s
                p.strokeWidth = (if (major) 2.4f else 1.4f) * s
                p.color = if (major) C.MUTED else withAlpha(C.MUTED, 0.5f)
                c.drawLine(cx + r0 * cos(a).toFloat(), cy + r0 * sin(a).toFloat(), cx + r1 * cos(a).toFloat(), cy + r1 * sin(a).toFloat(), p)
            }
            if (frac > 0.003f) {
                if (Skin.glowy) {
                    p.strokeWidth = (if (Skin.neon) 26f else 20f) * s; p.color = withAlpha(col, if (Skin.neon) 0.2f else 0.14f); arc(c, cx, cy, r, 0f, frac)
                    val g = SweepGradient(cx, cy, intArrayOf(col, if (warn) col else Skin.accent2, if (warn) col else Skin.accent2), floatArrayOf(0f, 0.75f, 1f))
                    m.setRotate(135f, cx, cy); g.setLocalMatrix(m)
                    p.color = 0xFF000000.toInt(); p.shader = g
                } else p.color = col
                p.strokeWidth = 8f * s; arc(c, cx, cy, r, 0f, frac)
                p.shader = null
                val a = Math.toRadians(135.0 + 270.0 * frac)
                p.style = Paint.Style.FILL; p.color = if (C.night) 0xFFFFFFFF.toInt() else C.TEXT
                c.drawCircle(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat(), 7f * s, p)
            }
            p.style = Paint.Style.FILL
            tp.typeface = F.numeric; tp.textSize = 16f * s; tp.color = C.MUTED
            for ((i, l) in labels.withIndex()) {
                val a = Math.toRadians(135.0 + 270.0 * i / majors)
                c.drawText(l, cx + (r - 44f * s) * cos(a).toFloat(), cy + (r - 44f * s) * sin(a).toFloat() + 6f * s, tp)
            }
            tp.typeface = F.display; tp.textSize = 92f * s
            tp.color = if (warn) C.WARN else if (dim) C.MUTED else C.TEXT
            c.drawText(big, cx, cy + 30f * s, tp)
            tp.typeface = F.semibold; tp.textSize = 13f * s; tp.letterSpacing = 0.16f; tp.color = C.MUTED
            c.drawText(unit, cx, cy + 66f * s, tp)
            tp.letterSpacing = 0f
        }
    }
}

/** Two small "LABEL value" pairs under a dial. */
private class DialFoot(a: MainActivity) {
    val view: LinearLayout = a.hbox(26f).apply { gravity = Gravity.CENTER }
    private val k = Array(2) { a.label(12f, C.MUTED, F.semibold, caps = true) }
    private val v = Array(2) { a.label(22f, C.TEXT, F.numeric).tnum() }

    init {
        for (i in 0..1) {
            val pair = a.hbox(8f).apply { gravity = Gravity.BOTTOM }
            pair.addView(k[i], lp(WRAP, WRAP).apply { bottomMargin = S.i(3) })
            pair.addView(v[i], lp(WRAP, WRAP))
            view.addView(pair, lp(WRAP, WRAP))
        }
    }

    fun set(i: Int, key: String, value: String, color: Int = C.TEXT) {
        k[i].put(key); v[i].put(value)
        if (v[i].currentTextColor != color) v[i].setTextColor(color)
    }
}

/** RPM dial (needs OBD2). */
class RpmCard(private val a: MainActivity) : Card {
    override val view: LinearLayout = a.vbox(6f)
    private val dial = DialView(a, 8000f, arrayOf("0", "1", "2", "3", "4", "5", "6", "7", "8"), 6500f)
    private val foot = DialFoot(a)

    init {
        if (!Skin.analog) { view.background = cardBg(); view.pad(14f) }
        view.gravity = Gravity.CENTER
        dial.contentDescription = "Engine RPM"
        dial.reserve = S.i(44)
        view.addView(dial, lp(MATCH, WRAP))
        view.addView(foot.view, lp(MATCH, WRAP))
        view.setOnClickListener { a.show(MainActivity.Page.TELEMETRY) }
    }

    override fun update() {
        val r = Obd.v("rpm")
        dial.set(r, r?.let { fmt("%.1f", it / 1000f) } ?: "--", if (r != null) "× 1000 RPM" else "CONNECT OBD2")
        foot.set(0, "Avg", fmt("%.0f", Units.speed(Gps.avgKmh)))
        foot.set(1, "Max", fmt("%.0f", Units.speed(Gps.tripMaxKmh)))
    }
}

/** Speed as a dial (Carbon Sport's speed card). */
class SpeedDialCard(private val a: MainActivity) : Card {
    override val view: LinearLayout = a.vbox(6f)
    private val maxV = if (Units.imp) 160f else 240f
    private val dial = DialView(a, maxV, if (Units.imp) arrayOf("0", "20", "40", "60", "80", "100", "120", "140", "160")
        else arrayOf("0", "40", "80", "120", "160", "200", "240"))
    private val foot = DialFoot(a)

    init {
        if (!Skin.analog) { view.background = cardBg(); view.pad(14f) }
        view.gravity = Gravity.CENTER
        dial.contentDescription = "Speed"
        dial.reserve = S.i(44)
        view.addView(dial, lp(MATCH, WRAP))
        view.addView(foot.view, lp(MATCH, WRAP))
    }

    override fun update() {
        val (k, _) = Drive.speed()
        val shown = k?.let { Units.speed(it) }
        dial.set(shown, shown?.roundToInt()?.toString() ?: "--", Units.speedUnit, SpeedAlert.over)
        foot.set(0, "Heading", if (Gps.fresh || Gps.bearing != 0f) Gps.compass(Gps.bearing) else "--")
        val road = RoadLimit.signNumber()
        val lim = Prefs.speedLimit
        when {
            road != null -> foot.set(1, "Road limit", "$road", if (SpeedAlert.overRoad) C.WARN else C.accent)
            lim > 0 -> foot.set(1, "Limit", "$lim", C.accent)
            else -> foot.set(1, "Limit", "Off", C.MUTED)
        }
    }
}

// ------------------------------------------------------------------ next turn

/** Large next-turn card (Google Maps / Waze). */
class NavCard(private val a: MainActivity) : Card {
    override val view: LinearLayout = a.vbox(12f)
    private val box = FrameLayout(a)
    private val img = ImageView(a)
    private val generic = IconBtn(a, Ic.TURN_RIGHT, 34f).apply { isClickable = false; sw = 2.4f }
    private val dist: TextView = a.label(when (Skin.id) { Skin.SPORT -> 46f; Skin.LUX -> 46f; Skin.NEON -> 32f; else -> 40f }, C.TEXT,
        if (Skin.glass) F.medium else F.numeric).tnum()
    private val street: TextView = a.label(17f, C.TEXT3, F.medium)
    private val eta: TextView = a.label(14f, C.TEXT, F.semibold)
    private val app: TextView = a.label(12f, C.MUTED, F.semibold, caps = true)
    private var shown = -1
    private var activeShown: Boolean? = null
    private val sign = SpeedSignView(a)
    private val foot = a.hbox(10f)
    private val chips = a.hbox(8f)

    init {
        view.background = cardBg()
        view.pad(22f, 18f)
        view.gravity = Gravity.CENTER_VERTICAL
        val top = a.hbox(18f)
        box.addView(img, flp(S.i(44), S.i(44), Gravity.CENTER))
        box.addView(generic, flp(MATCH, MATCH))
        img.scaleType = ImageView.ScaleType.FIT_CENTER
        top.addView(box, lp(S.i(72), S.i(72)))
        val col = a.vbox(4f)
        col.addView(dist, lp(MATCH, WRAP)); col.addView(street, lp(MATCH, WRAP))
        top.addView(col, lp(0, WRAP, 1f))
        sign.contentDescription = "Speed limit"
        top.addView(sign, lp(S.i(58), S.i(58)))
        view.addView(top, lp(MATCH, WRAP))
        foot.addView(eta, lp(0, WRAP, 1f)); foot.addView(app, lp(WRAP, WRAP))
        view.addView(foot, lp(MATCH, WRAP))
        // quick destinations while there is no route
        val quick = Places.saved().take(2) + Places.NEARBY.take(2)
        for (p in quick) chips.addView(a.placeChip(p, false), lp(0, S.i(40), 1f))
        view.addView(chips, lp(MATCH, WRAP))
        view.setOnClickListener { a.openNav() }
    }

    override fun update() {
        sign.update()
        foot.showIf(NavHub.active)
        chips.showIf(!NavHub.active)
        if (NavHub.version != shown) {
            shown = NavHub.version
            if (NavHub.active) {
                dist.put(NavHub.title.ifEmpty { NavHub.text })
                street.put(if (NavHub.title.isEmpty()) "" else NavHub.text)
                eta.put(NavHub.sub.ifEmpty { "On route" })
                app.put(NavHub.appName(a))
                img.setImageBitmap(NavHub.icon)
                img.showIf(NavHub.icon != null); generic.showIf(NavHub.icon == null)
            } else {
                dist.put("Where to?"); street.put(if (RoadLimit.road.isNotEmpty()) RoadLimit.road else "Tap to open Maps")
                eta.put("Turns from Google Maps or Waze show here"); app.put("")
                img.setImageBitmap(null); img.showIf(false); generic.showIf(true)
            }
            street.showIf(street.text.isNotEmpty())
        }
        if (activeShown != NavHub.active) {
            activeShown = NavHub.active
            val on = NavHub.active
            box.background = when {
                !on -> Skin.tile(C.CARD2)
                Skin.glowy || Skin.lux -> Skin.gradient(20f)
                Skin.sport -> ChamferBg(C.accent, 12f, flat = true)
                else -> roundBg(C.accent, 14f)
            }
            generic.set(icon = if (on) Ic.TURN_RIGHT else Ic.NAV, fg = if (!on) C.TEXT2 else if (Skin.id == Skin.CLASSIC || Skin.lux) C.ON_ACCENT else C.ON_GRAD)
            eta.setTextColor(if (on) C.TEXT else C.MUTED)
            eta.typeface = if (on) F.semibold else F.regular
        }
    }
}

// ------------------------------------------------------------------ readouts

/** Four small readouts: engine numbers with OBD2, GPS numbers without. */
class StatsCard(private val a: MainActivity) : Card {
    override val view: LinearLayout = a.hbox(10f, center = false)
    private val k = Array(4) { a.label(11f, C.MUTED, F.semibold, caps = true) }
    private val v = Array(4) { a.label(30f, C.TEXT, F.numeric).tnum() }
    private val u = Array(4) { a.label(13f, C.MUTED, F.regular) }

    init {
        for (i in 0 until 4) {
            val t = a.vbox(2f)
            t.background = if (Skin.id == Skin.CLASSIC) cardBg() else Skin.panel(C.CARD, 20f)
            t.pad(14f, 10f)
            t.gravity = Gravity.CENTER_VERTICAL
            t.addView(k[i], lp(MATCH, WRAP))
            val row = a.hbox(4f).apply { gravity = Gravity.BOTTOM }
            row.addView(v[i], lp(WRAP, WRAP)); row.addView(u[i], lp(WRAP, WRAP).apply { bottomMargin = S.i(4) })
            t.addView(row, lp(MATCH, WRAP))
            view.addView(t, lp(0, MATCH, 1f))
        }
        view.setOnClickListener { a.show(MainActivity.Page.TELEMETRY) }
    }

    private fun set(i: Int, key: String, value: String, unit: String, warn: Boolean = false) {
        k[i].put(key); v[i].put(value); u[i].put(unit)
        val col = if (warn) C.WARN else C.TEXT
        if (v[i].currentTextColor != col) v[i].setTextColor(col)
    }

    override fun update() {
        val trip = fmt("%.1f", Units.dist(Gps.tripMeters))
        if (Obd.connected) {
            val ct = Obd.v("coolant"); val volt = Obd.v("volt"); val map = Obd.v("map"); val load = Obd.v("load")
            set(0, "Coolant", ct?.let { fmt("%.0f", it) } ?: "--", "°C", ct != null && ct >= 108f)
            set(1, "Battery", volt?.let { fmt("%.1f", it) } ?: "--", "V", volt != null && (volt < 11.8f || volt > 15.2f))
            if (map != null || load == null) set(2, "Intake", map?.let { fmt("%.0f", it) } ?: "--", "kPa")
            else set(2, "Load", fmt("%.0f", load), "%")
        } else {
            set(0, "Heading", if (Gps.fresh || Gps.bearing != 0f) Gps.compass(Gps.bearing) else "--", "")
            set(1, "Altitude", if (Gps.alt.isNaN()) "--" else fmt("%,.0f", Units.alt(Gps.alt)), Units.altUnit)
            set(2, "Satellites", if (Gps.satsSeen > 0) "${Gps.satsUsed}" else "--", if (Gps.satsSeen > 0) "/${Gps.satsSeen}" else "")
        }
        val range = FuelRange.now()
        if (range != null && Obd.connected && Obd.v("fuel") != null) set(3, "Range", FuelRange.text(range), Units.distUnit, range.km < 60)
        else set(3, "Trip", trip, Units.distUnit)
    }
}
