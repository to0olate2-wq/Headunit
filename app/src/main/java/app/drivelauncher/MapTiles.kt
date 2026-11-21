package app.drivelauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.LruCache
import android.view.View
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * Lightweight dark street map from raster tiles, cached on disk so it keeps
 * working offline in areas you've driven before. Follows the car.
 * Sources (no API key needed), tried in order: Esri World Dark Gray, then
 * OpenStreetMap with a dark colour filter if the first one stops answering.
 */
class TileMapView(ctx: Context) : View(ctx) {
    var lat = Double.NaN
    var lon = Double.NaN
    var bearing = 0f
    var zoom = 16
        set(v) { field = v.coerceIn(3, 19); invalidate() }
    /** 0 follow the car, 1 fit [route], 2 centre on [fixed] */
    var mode = 0
        set(v) { field = v; invalidate() }
    var route: List<DoubleArray>? = null
        set(v) { field = v; invalidate() }
    var fixed: DoubleArray? = null
        set(v) { field = v; invalidate() }
    var showParking = true
    /** Turn the map so the road ahead points up (navigation). */
    var headingUp = false
    /** Where the car sits, as a share of the height (follow mode). */
    var carAt = 0.62f
    /** Point to mark (next turn), or null. */
    var mark: DoubleArray? = null

    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rf = RectF()
    private val cacheDir = File(ctx.cacheDir, "tiles2")

    fun setPosition(la: Double, lo: Double, br: Float) {
        if (la != lat || lo != lon || br != bearing) {
            lat = la; lon = lo; bearing = br
            invalidate()
        }
    }

    private fun ux(lon: Double) = (lon + 180.0) / 360.0
    private fun uy(lat: Double): Double {
        val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        path.reset(); rf.set(0f, 0f, w, h)
        path.addRoundRect(rf, S.p(Skin.radius - 1f), S.p(Skin.radius - 1f), Path.Direction.CW)
        c.clipPath(path)
        p.style = Paint.Style.FILL
        p.color = C.MAP_BG
        c.drawRect(0f, 0f, w, h, p)
        // the downloaded offline map when it covers where we look, else the online maps
        val lookLa = if (mode == 2 && fixed != null) fixed!![0] else lat
        val lookLo = if (mode == 2 && fixed != null) fixed!![1] else lon
        val offline = !lookLa.isNaN() && OfflineMaps.covers(lookLa, lookLo)
        val src = if (offline) (if (C.night) OFF_NIGHT else OFF_DAY) else (if (C.night) NIGHT else DAY)[srcIdx]
        tilePaint.colorFilter = if (src.dark) DARK else null
        val base = 256f * max(1f, S.u)

        // what to centre on, and at which zoom
        var cLat = lat; var cLon = lon; var z0 = zoom
        val r = route
        var ax = w / 2f; var ay = h * carAt
        when {
            mode == 1 && r != null && r.size >= 2 -> {
                val x0 = r.minOf { ux(it[1]) }; val x1 = r.maxOf { ux(it[1]) }
                val y0 = r.minOf { uy(it[0]) }; val y1 = r.maxOf { uy(it[0]) }
                val spanX = max(x1 - x0, 1e-7); val spanY = max(y1 - y0, 1e-7)
                val zf = min(ln(w * 0.8 / (spanX * base)) / ln(2.0), ln(h * 0.8 / (spanY * base)) / ln(2.0))
                z0 = floor(zf).toInt().coerceIn(3, 17)
                cLon = (x0 + x1) / 2 * 360.0 - 180.0
                val my = (y0 + y1) / 2
                cLat = Math.toDegrees(Math.atan(Math.sinh(PI * (1 - 2 * my))))
                ax = w / 2f; ay = h / 2f
            }
            mode == 2 && fixed != null -> {
                cLat = fixed!![0]; cLon = fixed!![1]; ax = w / 2f; ay = h / 2f
            }
        }
        if (cLat.isNaN() || cLon.isNaN()) {
            text(c, "Waiting for GPS position…", w / 2, h / 2)
            return
        }
        val z = min(z0, src.maxZ)
        val ts = base * (1 shl (z0 - z))
        val n = 1 shl z
        val world = n * ts.toDouble()
        val x0 = ux(cLon) * world - ax
        val y0 = uy(cLat) * world - ay
        val rotate = headingUp && mode == 0
        // when the map turns, the corners need tiles from further out
        val pad = if (rotate) kotlin.math.hypot(w, h) else 0f
        val tx0 = floor((x0 - pad) / ts).toInt()
        val tx1 = floor((x0 + w + pad) / ts).toInt()
        val ty0 = floor((y0 - pad) / ts).toInt()
        val ty1 = floor((y0 + h + pad) / ts).toInt()
        c.save()
        if (rotate) c.rotate(-bearing, ax, ay)
        var drawn = 0
        for (ty in ty0..ty1) {
            if (ty < 0 || ty >= n) continue
            for (tx in tx0..tx1) {
                val txw = ((tx % n) + n) % n
                val bmp = tile(src, z, txw, ty)
                val left = floor(tx * ts.toDouble() - x0).toFloat()
                val top = floor(ty * ts.toDouble() - y0).toFloat()
                // whole pixels plus 1 px overlap so no hairline seams show between tiles
                rf.set(left, top, left + ts.roundToInt() + 1f, top + ts.roundToInt() + 1f)
                if (bmp != null) { c.drawBitmap(bmp, null, rf, tilePaint); drawn++ }
            }
        }
        if (drawn == 0 && !offline) text(c, "Loading map… (needs internet the first time)", w / 2, h * 0.3f)
        fun sx(lo: Double) = (ux(lo) * world - x0).toFloat()
        fun sy(la: Double) = (uy(la) * world - y0).toFloat()

        // route
        if (r != null && r.size >= 2) {
            path.reset()
            path.moveTo(sx(r[0][1]), sy(r[0][0]))
            for (i in 1 until r.size) path.lineTo(sx(r[i][1]), sy(r[i][0]))
            p.style = Paint.Style.STROKE; p.strokeJoin = Paint.Join.ROUND; p.strokeCap = Paint.Cap.ROUND
            p.strokeWidth = S.p(8f); p.color = C.BG; c.drawPath(path, p)
            p.strokeWidth = S.p(5f); p.color = C.accent; c.drawPath(path, p)
            p.style = Paint.Style.FILL
            p.color = C.BLUE; c.drawCircle(sx(r[0][1]), sy(r[0][0]), S.p(7f), p)
            p.color = C.accent; c.drawCircle(sx(r.last()[1]), sy(r.last()[0]), S.p(7f), p)
        }
        mark?.let { m ->
            p.style = Paint.Style.FILL; p.color = C.BG
            c.drawCircle(sx(m[1]), sy(m[0]), S.p(9f), p)
            p.color = 0xFFFFFFFF.toInt(); c.drawCircle(sx(m[1]), sy(m[0]), S.p(6f), p)
        }
        // parking spot
        if (showParking && Parking.has && (mode != 0 || lat.isNaN() || Trips.dist(Parking.lat, Parking.lon, lat, lon) > 30)) {
            val px = sx(Parking.lon); val py = sy(Parking.lat)
            if (px > -50 && px < w + 50 && py > -50 && py < h + 50) {
                p.style = Paint.Style.FILL
                p.color = C.BLUE
                rf.set(px - S.p(13f), py - S.p(13f), px + S.p(13f), py + S.p(13f))
                c.drawRoundRect(rf, S.p(6f), S.p(6f), p)
                p.color = 0xFFFFFFFF.toInt(); p.textAlign = Paint.Align.CENTER; p.typeface = F.bold; p.textSize = S.p(17f)
                c.drawText("P", px, py + S.p(6f), p)
                p.textAlign = Paint.Align.LEFT
            }
        }
        c.restore()
        // car marker
        if (mode == 0) {
            p.style = Paint.Style.FILL
            p.color = withAlpha(C.accent, 0.22f)
            c.drawCircle(ax, ay, S.p(20f), p)
            c.save()
            c.translate(ax, ay)
            c.rotate(if (rotate) 0f else bearing)
            val k = S.p(1f)
            path.reset()
            path.moveTo(0f, -15f * k); path.lineTo(11f * k, 12f * k); path.lineTo(0f, 6f * k); path.lineTo(-11f * k, 12f * k); path.close()
            p.color = C.accent
            c.drawPath(path, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = S.p(1.5f)
            p.color = C.ON_ACCENT
            c.drawPath(path, p)
            c.restore()
        }
        // attribution
        p.style = Paint.Style.FILL
        p.typeface = F.regular
        p.textSize = S.p(10f)
        p.color = C.MUTED
        c.drawText(src.attribution, S.p(12f), h - S.p(10f), p)
    }

    private fun text(c: Canvas, t: String, x: Float, y: Float) {
        p.style = Paint.Style.FILL
        p.color = C.MUTED
        p.textAlign = Paint.Align.CENTER
        p.typeface = F.medium
        p.textSize = S.p(14f)
        c.drawText(t, x, y, p)
        p.textAlign = Paint.Align.LEFT
    }

    private fun tile(src: Src, z: Int, x: Int, y: Int): Bitmap? {
        val key = if (src.render != null) "off${OfflineMaps.version}/$z/$x/$y" else "${src.id}/$z/$x/$y"
        mem.get(key)?.let { return it }
        if (pending.contains(key)) return null
        val failedAt = failed[key]
        if (failedAt != null && SystemClock.elapsedRealtime() - failedAt < 8_000) return null
        pending.add(key)
        pool.execute {
            try {
                val f = File(cacheDir, key.replace('/', '_'))
                var bmp: Bitmap? = null
                if (src.render == null && f.exists()) bmp = BitmapFactory.decodeFile(f.path)
                val render = src.render
                if (render != null) bmp = render(z, x, y) ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(C.MAP_BG) }
                if (bmp == null) {
                    val con = URL(src.url(z, x, y)).openConnection() as HttpURLConnection
                    con.connectTimeout = 8000
                    con.readTimeout = 8000
                    con.setRequestProperty("User-Agent", UA)
                    val type = con.contentType ?: ""
                    if (con.responseCode == 200 && type.startsWith("image")) {
                        val bytes = con.inputStream.use { it.readBytes() }
                        bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) {
                            cacheDir.mkdirs()
                            f.writeBytes(bytes)
                        }
                    } else {
                        Diag.log("map ${src.id}: HTTP ${con.responseCode} $type")
                    }
                    con.disconnect()
                }
                if (bmp != null) {
                    srcFails.set(0)
                    mem.put(key, bmp)
                    postInvalidate()
                } else fail(src, key)
            } catch (e: Exception) {
                if (srcFails.get() < 3) Diag.log("map ${src.id} $key: ${e.javaClass.simpleName} ${e.message}")
                fail(src, key)
            } finally {
                pending.remove(key)
            }
        }
        return null
    }

    private fun fail(src: Src, key: String) {
        failed[key] = SystemClock.elapsedRealtime()
        val list = if (C.night) NIGHT else DAY
        if (srcFails.incrementAndGet() >= 8 && list[srcIdx] === src && srcIdx < list.size - 1) {
            srcIdx++
            srcFails.set(0)
            failed.clear()
            Diag.log("map: switching to ${list[srcIdx].id}")
            postInvalidate()
        }
    }

    private class Src(val id: String, val maxZ: Int, val attribution: String, val dark: Boolean,
                      val render: ((Int, Int, Int) -> Bitmap?)? = null, val url: (Int, Int, Int) -> String)

    companion object {
        const val UA = "DriveLauncher/0.2 (personal car head-unit launcher; github.com/to0olate2-wq/Headunit)"
        private val NIGHT = listOf(
            Src("esridg", 16, "Esri, HERE, Garmin, © OpenStreetMap contributors", false) { z, x, y ->
                "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Base/MapServer/tile/$z/$y/$x"
            },
            Src("osm", 19, "© OpenStreetMap contributors", true) { z, x, y -> "https://tile.openstreetmap.org/$z/$x/$y.png" },
        )
        private val DAY = listOf(
            Src("esrilg", 16, "Esri, HERE, Garmin, © OpenStreetMap contributors", false) { z, x, y ->
                "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Light_Gray_Base/MapServer/tile/$z/$y/$x"
            },
            Src("osm", 19, "© OpenStreetMap contributors", false) { z, x, y -> "https://tile.openstreetmap.org/$z/$x/$y.png" },
        )
        /** Offline map tiles (drawn on the unit); the key changes when map files change. */
        private val OFF_DAY = Src("offline", 19, "© OpenStreetMap contributors · offline map", false, { z, x, y -> OfflineMaps.render(z, x, y) }) { _, _, _ -> "" }
        private val OFF_NIGHT = Src("offline", 19, "© OpenStreetMap contributors · offline map", true, { z, x, y -> OfflineMaps.render(z, x, y) }) { _, _, _ -> "" }
        @Volatile private var srcIdx = 0
        private val srcFails = AtomicInteger(0)
        private val mem = LruCache<String, Bitmap>(90)
        private val pending: MutableSet<String> = Collections.synchronizedSet(HashSet())
        private val failed = ConcurrentHashMap<String, Long>()
        private val pool = Executors.newFixedThreadPool(3)

        /** Inverted, hue-rotated and dimmed: turns light OpenStreetMap tiles into a dark map. */
        private val DARK = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f,
        )).apply {
            postConcat(ColorMatrix(floatArrayOf(
                -0.574f, 1.430f, 0.144f, 0f, 0f, 0.426f, 0.430f, 0.144f, 0f, 0f,
                0.426f, 1.430f, -0.856f, 0f, 0f, 0f, 0f, 0f, 1f, 0f,
            )))
            postConcat(ColorMatrix(floatArrayOf(
                0.85f, 0f, 0f, 0f, 12f, 0f, 0.85f, 0f, 0f, 14f, 0f, 0f, 0.85f, 0f, 18f, 0f, 0f, 0f, 1f, 0f,
            )))
        })

        /** Keep the disk cache under ~4000 tiles. */
        fun prune(ctx: Context) {
            pool.execute {
                try {
                    File(ctx.cacheDir, "tiles").deleteRecursively()
                    val files = File(ctx.cacheDir, "tiles2").listFiles() ?: return@execute
                    if (files.size > 4000) {
                        files.sortBy { it.lastModified() }
                        for (i in 0 until files.size - 3000) files[i].delete()
                    }
                } catch (_: Exception) {
                }
            }
        }
    }
}
