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

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        path.reset(); rf.set(0f, 0f, w, h)
        path.addRoundRect(rf, S.p(19f), S.p(19f), Path.Direction.CW)
        c.clipPath(path)
        p.style = Paint.Style.FILL
        p.color = 0xFF181C22.toInt()
        c.drawRect(0f, 0f, w, h, p)
        if (lat.isNaN() || lon.isNaN()) {
            p.color = C.MUTED
            p.textAlign = Paint.Align.CENTER
            p.typeface = F.medium
            p.textSize = S.p(15f)
            c.drawText("Waiting for GPS position…", w / 2, h / 2, p)
            p.textAlign = Paint.Align.LEFT
            return
        }
        val src = SOURCES[srcIdx]
        tilePaint.colorFilter = if (src.dark) DARK else null
        val z = min(zoom, src.maxZ)
        val ts = 256f * max(1f, S.u) * (1 shl (zoom - z))
        val n = 1 shl z
        val latR = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        val wx = (lon + 180.0) / 360.0 * n * ts
        val wy = (1.0 - ln(tan(latR) + 1.0 / cos(latR)) / PI) / 2.0 * n * ts
        val ax = w / 2f
        val ay = h * 0.62f
        val x0 = wx - ax
        val y0 = wy - ay
        val tx0 = floor(x0 / ts).toInt()
        val tx1 = floor((x0 + w) / ts).toInt()
        val ty0 = floor(y0 / ts).toInt()
        val ty1 = floor((y0 + h) / ts).toInt()
        var drawn = 0
        for (ty in ty0..ty1) {
            if (ty < 0 || ty >= n) continue
            for (tx in tx0..tx1) {
                val txw = ((tx % n) + n) % n
                val bmp = tile(src, z, txw, ty)
                val left = (tx * ts - x0).toFloat()
                val top = (ty * ts - y0).toFloat()
                rf.set(left, top, left + ts, top + ts)
                if (bmp != null) { c.drawBitmap(bmp, null, rf, tilePaint); drawn++ }
            }
        }
        if (drawn == 0) {
            p.style = Paint.Style.FILL
            p.color = C.MUTED
            p.textAlign = Paint.Align.CENTER
            p.typeface = F.medium
            p.textSize = S.p(14f)
            c.drawText("Loading map… (needs internet the first time)", w / 2, h * 0.3f, p)
            p.textAlign = Paint.Align.LEFT
        }
        // car marker
        p.style = Paint.Style.FILL
        p.color = withAlpha(C.accent, 0.22f)
        c.drawCircle(ax, ay, S.p(20f), p)
        c.save()
        c.translate(ax, ay)
        c.rotate(bearing)
        val k = S.p(1f)
        path.reset()
        path.moveTo(0f, -15f * k); path.lineTo(11f * k, 12f * k); path.lineTo(0f, 6f * k); path.lineTo(-11f * k, 12f * k); path.close()
        p.color = C.accent
        c.drawPath(path, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = S.p(1.5f)
        p.color = C.BG
        c.drawPath(path, p)
        c.restore()
        // attribution
        p.style = Paint.Style.FILL
        p.typeface = F.regular
        p.textSize = S.p(10f)
        p.color = C.MUTED
        c.drawText(src.attribution, S.p(12f), h - S.p(10f), p)
    }

    private fun tile(src: Src, z: Int, x: Int, y: Int): Bitmap? {
        val key = "${src.id}/$z/$x/$y"
        mem.get(key)?.let { return it }
        if (pending.contains(key)) return null
        val failedAt = failed[key]
        if (failedAt != null && SystemClock.elapsedRealtime() - failedAt < 8_000) return null
        pending.add(key)
        pool.execute {
            try {
                val f = File(cacheDir, key.replace('/', '_'))
                var bmp: Bitmap? = null
                if (f.exists()) bmp = BitmapFactory.decodeFile(f.path)
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
        if (srcFails.incrementAndGet() >= 8 && SOURCES[srcIdx] === src && srcIdx < SOURCES.size - 1) {
            srcIdx++
            srcFails.set(0)
            failed.clear()
            Diag.log("map: switching to ${SOURCES[srcIdx].id}")
            postInvalidate()
        }
    }

    private class Src(val id: String, val maxZ: Int, val attribution: String, val dark: Boolean, val url: (Int, Int, Int) -> String)

    companion object {
        const val UA = "DriveLauncher/0.2 (personal car head-unit launcher; github.com/to0olate2-wq/Headunit)"
        private val SOURCES = listOf(
            Src("esridg", 16, "Esri, HERE, Garmin, © OpenStreetMap contributors", false) { z, x, y ->
                "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Base/MapServer/tile/$z/$y/$x"
            },
            Src("osm", 19, "© OpenStreetMap contributors", true) { z, x, y -> "https://tile.openstreetmap.org/$z/$x/$y.png" },
        )
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
