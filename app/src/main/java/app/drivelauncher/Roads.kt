package app.drivelauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioManager
import android.os.SystemClock
import android.view.View
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Posted speed limit of the road the car is on, from OpenStreetMap (Overpass API).
 * Google Maps and Waze don't share their speed limits with other apps.
 * Roads around the car (about 2 x 2 km) are downloaded once and matched locally on every GPS fix.
 */
object RoadLimit {
    /** Limit of the current road in km/h, or null when unknown. */
    @Volatile var kmh: Int? = null
        private set
    /** True when the road's sign is in mph (show the number as posted). */
    @Volatile var postedMph = false
        private set
    @Volatile var road = ""
        private set
    @Volatile var status = "not started"
        private set

    private class Way(val lat: DoubleArray, val lon: DoubleArray, val all: Int?, val fwd: Int?, val bwd: Int?, val mph: Boolean,
                      val oneway: Boolean, val name: String)

    @Volatile private var ways: List<Way> = emptyList()
    @Volatile private var box: DoubleArray? = null // south, west, north, east
    @Volatile private var fetching = false
    private var failedAt = 0L
    private var candidate: Int? = null
    private var candidateCount = 0
    private var lastMatch = 0L
    private val ENDPOINTS = listOf("https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter")
    private const val TYPES = "motorway|trunk|primary|secondary|tertiary|unclassified|residential|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link|living_street|road"

    /** For tests: pretend the current road has this limit. */
    fun setForTest(limitKmh: Int?, name: String = "") { kmh = limitKmh; road = name; postedMph = false; status = "test" }

    fun onFix(lat: Double, lon: Double, bearing: Float, speedKmh: Float, accuracy: Float) {
        if (!Prefs.roadLimits) { kmh = null; status = "off"; return }
        val b = box
        val margin = 0.0028 // ~300 m
        if (b == null || lat < b[0] + margin || lat > b[2] - margin || lon < b[1] + margin / cos(Math.toRadians(lat)) || lon > b[3] - margin / cos(Math.toRadians(lat))) {
            fetch(lat, lon, bearing, speedKmh)
        }
        if (accuracy > 40f) return
        match(lat, lon, bearing, speedKmh)
    }

    private fun fetch(lat: Double, lon: Double, bearing: Float, speedKmh: Float) {
        if (fetching) return
        val now = SystemClock.elapsedRealtime()
        if (failedAt != 0L && now - failedAt < 60_000) return
        fetching = true
        // centre the box a little ahead of the car
        val ahead = if (speedKmh > 20f) 0.004 else 0.0
        val cLat = lat + ahead * cos(Math.toRadians(bearing.toDouble()))
        val k = cos(Math.toRadians(lat))
        val cLon = lon + ahead * kotlin.math.sin(Math.toRadians(bearing.toDouble())) / k
        val dLat = 0.010; val dLon = 0.010 / k
        val nb = doubleArrayOf(cLat - dLat, cLon - dLon, cLat + dLat, cLon + dLon)
        status = "downloading roads"
        Thread {
            try {
                val q = "[out:json][timeout:20];way[\"highway\"~\"^($TYPES)$\"](${fmt("%.5f,%.5f,%.5f,%.5f", nb[0], nb[1], nb[2], nb[3])});out tags geom qt;"
                var err: Exception? = null
                for (ep in ENDPOINTS) {
                    try {
                        val list = parse(post(ep, q))
                        ways = list; box = nb; failedAt = 0L
                        status = "${list.size} roads around you"
                        err = null
                        break
                    } catch (e: Exception) { err = e }
                }
                if (err != null) throw err
            } catch (e: Exception) {
                failedAt = SystemClock.elapsedRealtime()
                status = "road data unavailable (${e.javaClass.simpleName})"
                Diag.log("speed limits: ${e.javaClass.simpleName} ${e.message}")
            } finally {
                fetching = false
            }
        }.start()
    }

    private fun post(endpoint: String, query: String): String {
        val c = URL(endpoint).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("User-Agent", "DriveLauncher/0.5 (car head unit launcher; github.com/to0olate2-wq/Headunit)")
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.outputStream.use { it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray()) }
        if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    /** "50", "30 mph", "50;30" → number and unit; anything else (zones, "none", "walk") → unknown. */
    fun parseLimit(s: String?): Pair<Int, Boolean>? {
        if (s.isNullOrBlank()) return null
        val first = s.split(';', '|').first().trim().lowercase()
        val mph = first.endsWith("mph")
        val n = first.removeSuffix("mph").removeSuffix("km/h").removeSuffix("kmh").trim().toIntOrNull() ?: return null
        if (n <= 0 || n > 200) return null
        return n to mph
    }

    private fun parse(json: String): List<Way> {
        val els = JSONObject(json).optJSONArray("elements") ?: return emptyList()
        val out = ArrayList<Way>(els.length())
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            val geo = e.optJSONArray("geometry") ?: continue
            if (geo.length() < 2) continue
            val t = e.optJSONObject("tags") ?: JSONObject()
            val all = parseLimit(t.optString("maxspeed", ""))
            val fwd = parseLimit(t.optString("maxspeed:forward", ""))
            val bwd = parseLimit(t.optString("maxspeed:backward", ""))
            val mph = (all ?: fwd ?: bwd)?.second ?: false
            fun kmh(p: Pair<Int, Boolean>?) = p?.let { if (it.second) (it.first * 1.609344).roundToInt() else it.first }
            val la = DoubleArray(geo.length()); val lo = DoubleArray(geo.length())
            for (k in 0 until geo.length()) { val g = geo.getJSONObject(k); la[k] = g.getDouble("lat"); lo[k] = g.getDouble("lon") }
            val ow = t.optString("oneway", "") in setOf("yes", "true", "1") || t.optString("highway") == "motorway" || t.optString("junction") == "roundabout"
            out.add(Way(la, lo, kmh(all), kmh(fwd), kmh(bwd), mph, ow, t.optString("name", t.optString("ref", ""))))
        }
        return out
    }

    private fun match(lat: Double, lon: Double, bearing: Float, speedKmh: Float) {
        val list = ways
        if (list.isEmpty()) return
        val k = cos(Math.toRadians(lat)) * 111_320.0
        var best: Way? = null
        var bestScore = Double.MAX_VALUE
        var bestForward = true
        val moving = speedKmh > 8f
        for (w in list) {
            for (i in 0 until w.lat.size - 1) {
                // segment in metres around the car
                val ax = (w.lon[i] - lon) * k; val ay = (w.lat[i] - lat) * 110_540.0
                val bx = (w.lon[i + 1] - lon) * k; val by = (w.lat[i + 1] - lat) * 110_540.0
                val dx = bx - ax; val dy = by - ay
                val len2 = dx * dx + dy * dy
                if (len2 < 0.01) continue
                val t = ((-ax) * dx + (-ay) * dy) / len2
                val tt = t.coerceIn(0.0, 1.0)
                val px = ax + dx * tt; val py = ay + dy * tt
                val d = sqrt(px * px + py * py)
                if (d > 30.0) continue
                var score = d
                var forward = true
                if (moving) {
                    val segBearing = (Math.toDegrees(kotlin.math.atan2(dx, dy)) + 360.0) % 360.0
                    var diff = abs(segBearing - bearing) % 360.0
                    if (diff > 180) diff = 360 - diff
                    forward = diff <= 90
                    val off = if (forward) diff else 180 - diff
                    if (!forward && w.oneway) continue
                    if (off > 45) continue
                    score += off * 0.4
                }
                if (score < bestScore) { bestScore = score; best = w; bestForward = forward }
            }
        }
        val now = SystemClock.elapsedRealtime()
        if (best == null) {
            if (now - lastMatch > 15_000) { kmh = null; road = "" }
            return
        }
        lastMatch = now
        val lim = (if (bestForward) best.fwd else best.bwd) ?: best.all
        // the same answer twice in a row before changing (no flicker at junctions)
        if (lim == candidate) candidateCount++ else { candidate = lim; candidateCount = 1 }
        if (candidateCount >= 2 || kmh == null) {
            kmh = candidate
            postedMph = best.mph
            road = best.name
        }
    }

    /** The number printed on the sign, in the sign's own unit. */
    fun signNumber(): Int? = kmh?.let { if (postedMph) (it / 1.609344).roundToInt() else it }
}

/** Speed limit sign: red ring (or a white "SPEED LIMIT" plate for mph roads). */
object SpeedSign {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rf = RectF()

    fun draw(c: Canvas, cx: Float, cy: Float, r: Float, number: Int, mph: Boolean, over: Boolean) {
        p.shader = null
        if (mph) {
            val w = r * 1.5f; val h = r * 1.9f
            rf.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
            p.style = Paint.Style.FILL; p.color = 0xFFFFFFFF.toInt()
            c.drawRoundRect(rf, r * 0.16f, r * 0.16f, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.07f; p.color = 0xFF111111.toInt()
            rf.inset(r * 0.09f, r * 0.09f); c.drawRoundRect(rf, r * 0.1f, r * 0.1f, p)
            p.style = Paint.Style.FILL; p.typeface = F.bold
            p.textSize = r * 0.3f; c.drawText("SPEED", cx, cy - h * 0.24f, p); c.drawText("LIMIT", cx, cy - h * 0.06f, p)
            p.textSize = r * 0.82f; p.color = if (over) 0xFFD12F20.toInt() else 0xFF111111.toInt()
            c.drawText(number.toString(), cx, cy + h * 0.36f, p)
            return
        }
        p.style = Paint.Style.FILL
        if (over) { p.color = withAlpha(0xFFE53935.toInt(), 0.35f); c.drawCircle(cx, cy, r * 1.22f, p) }
        p.color = 0xFFFFFFFF.toInt(); c.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.2f; p.color = 0xFFE53935.toInt()
        c.drawCircle(cx, cy, r * 0.88f, p)
        p.style = Paint.Style.FILL; p.color = 0xFF111111.toInt(); p.typeface = F.bold
        val s = number.toString()
        p.textSize = r * (if (s.length >= 3) 0.72f else 0.92f)
        c.drawText(s, cx, cy + p.textSize * 0.36f, p)
    }
}

/** Small view with the road's speed limit sign; hidden when the limit is unknown. */
class SpeedSignView(ctx: Context) : View(ctx) {
    private var n: Int? = null
    private var mph = false
    private var over = false

    fun update() {
        val nn = RoadLimit.signNumber()
        val o = SpeedAlert.overRoad
        if (nn != n || RoadLimit.postedMph != mph || o != over) { n = nn; mph = RoadLimit.postedMph; over = o; invalidate() }
        showIf(nn != null)
    }

    override fun onDraw(c: Canvas) {
        val v = n ?: return
        val r = min(width, height) / 2f - S.p(2f)
        SpeedSign.draw(c, width / 2f, height / 2f, r * (if (mph) 0.8f else 1f), v, mph, over)
    }
}

/** Music gets a little louder as the car goes faster (and back down when it slows). */
object SpeedVolume {
    private var applied = 0
    private var lastSet = -1
    private var band = 0
    private var bandSince = 0L

    fun update(ctx: Context) {
        val level = Prefs.speedVolume // 0 off, 1 low, 2 medium, 3 high
        val am = try { ctx.getSystemService(AudioManager::class.java) } catch (_: Exception) { null } ?: return
        val stream = AudioManager.STREAM_MUSIC
        val cur = try { am.getStreamVolume(stream) } catch (_: Exception) { return }
        val maxV = try { am.getStreamMaxVolume(stream) } catch (_: Exception) { 15 }
        // the owner changed the volume by hand: keep their new level as the base
        if (lastSet >= 0 && cur != lastSet) { lastSet = -1 }
        val kmh = if (Gps.fresh) Gps.speedKmh else Obd.v("speed") ?: 0f
        val want = if (level == 0) 0 else {
            val b = when { kmh > 100f -> 3; kmh > 70f -> 2; kmh > 40f -> 1; else -> 0 }
            // hysteresis: a new speed band must last 4 seconds
            val now = SystemClock.elapsedRealtime()
            if (b != band) { if (bandSince == 0L) bandSince = now; if (now - bandSince > 4000) { band = b; bandSince = 0L } } else bandSince = 0L
            (band * level * maxV / 30f).roundToInt().coerceAtMost(maxV / 3)
        }
        if (want == applied) return
        val base = cur - applied
        val nv = (base + want).coerceIn(0, maxV)
        try {
            am.setStreamVolume(stream, nv, 0)
            applied = nv - base
            lastSet = nv
        } catch (e: Exception) { Diag.log("speed volume: ${e.message}") }
    }
}
