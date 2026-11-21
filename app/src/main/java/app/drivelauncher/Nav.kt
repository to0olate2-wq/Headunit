package app.drivelauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// ------------------------------------------------------------------ geometry

object Geo {
    private const val R = 6371000.0

    fun dist(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val dLa = Math.toRadians(la2 - la1); val dLo = Math.toRadians(lo2 - lo1)
        val a = sin(dLa / 2) * sin(dLa / 2) + cos(Math.toRadians(la1)) * cos(Math.toRadians(la2)) * sin(dLo / 2) * sin(dLo / 2)
        return 2 * R * asin(sqrt(min(1.0, a)))
    }

    /** Compass bearing from point 1 to point 2, 0–360. */
    fun bearing(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val p1 = Math.toRadians(la1); val p2 = Math.toRadians(la2); val dl = Math.toRadians(lo2 - lo1)
        val y = sin(dl) * cos(p2); val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Signed change of direction, -180…180 (positive = to the right). */
    fun turn(from: Double, to: Double): Double {
        var d = to - from
        while (d > 180) d -= 360
        while (d < -180) d += 360
        return d
    }

    /**
     * Where the point (la, lo) falls on segment a–b: returns (distance to the segment in metres, fraction 0…1 along it).
     * Uses a flat projection, fine for the short segments of a road.
     */
    fun toSegment(la: Double, lo: Double, aLa: Double, aLo: Double, bLa: Double, bLo: Double): DoubleArray {
        val k = cos(Math.toRadians(la))
        val ax = (aLo - lo) * k; val ay = aLa - la
        val bx = (bLo - lo) * k; val by = bLa - la
        val dx = bx - ax; val dy = by - ay
        val l2 = dx * dx + dy * dy
        val t = if (l2 <= 0.0) 0.0 else (-(ax * dx + ay * dy) / l2).coerceIn(0.0, 1.0)
        val px = ax + t * dx; val py = ay + t * dy
        return doubleArrayOf(sqrt(px * px + py * py) * 111_195.0, t)
    }
}

// ------------------------------------------------------------------ route model

/** One instruction along the route: where it is, what to do, and the road name after it. */
class Maneuver(
    val lat: Double, val lon: Double,
    /** depart, turn, fork, merge, ramp, exit, roundabout, uturn, continue, arrive */
    val type: String,
    /** left, right, slight left, slight right, sharp left, sharp right, straight, uturn */
    val modifier: String,
    val exit: Int,
    val name: String,
    /** Metres from the start of the route. */
    var at: Double = 0.0,
)

class Route(
    /** (lat, lon) points. */
    val points: List<DoubleArray>,
    val maneuvers: List<Maneuver>,
    val meters: Double,
    val seconds: Double,
    /** "online", "tomtom" (with live traffic) or "offline" (rough, from the downloaded map). */
    val source: String,
) {
    /** Metres from the start to each point. */
    val cum: DoubleArray = DoubleArray(points.size).also { c ->
        for (i in 1 until points.size) c[i] = c[i - 1] + Geo.dist(points[i - 1][0], points[i - 1][1], points[i][0], points[i][1])
    }
    val length: Double get() = if (cum.isEmpty()) 0.0 else cum.last()

    init {
        // place each maneuver at its distance along the line
        var from = 0
        for (m in maneuvers) {
            var best = from; var bestD = Double.MAX_VALUE
            for (i in from until min(points.size, from + 4000)) {
                val d = Geo.dist(m.lat, m.lon, points[i][0], points[i][1])
                if (d < bestD) { bestD = d; best = i }
                if (bestD < 1.0) break
            }
            m.at = cum.getOrElse(best) { 0.0 }
            from = best
        }
    }
}

/** A search result or a saved place to drive to. */
class Dest(val name: String, val detail: String, val lat: Double, val lon: Double)

// ------------------------------------------------------------------ words

/** Instruction text in plain English, shown and spoken. */
object Words {
    fun ordinal(n: Int) = when (n) { 1 -> "1st"; 2 -> "2nd"; 3 -> "3rd"; else -> "${n}th" }

    fun instruction(m: Maneuver): String {
        val onto = if (m.name.isNotBlank()) " onto ${m.name}" else ""
        val side = if (m.modifier.contains("left")) "left" else "right"
        return when (m.type) {
            "depart" -> if (m.name.isNotBlank()) "Start on ${m.name}" else "Start the route"
            "arrive" -> "You have arrived"
            "roundabout" -> "At the roundabout, take the ${ordinal(max(1, m.exit))} exit$onto"
            "fork" -> "Keep $side$onto"
            "merge" -> "Merge $side$onto"
            "ramp" -> "Take the ramp on the $side$onto"
            "exit" -> "Take the exit on the $side$onto"
            "uturn" -> "Make a U-turn$onto"
            "continue" -> if (m.name.isNotBlank()) "Continue on ${m.name}" else "Continue straight"
            else -> when (m.modifier) {
                "straight" -> "Continue straight$onto"
                "uturn" -> "Make a U-turn$onto"
                "" -> "Continue$onto"
                else -> "Turn ${m.modifier}$onto"
            }
        }
    }

    /** "300 m", "1.2 km", "0.3 mi", "500 ft". */
    fun distance(m: Double): String = if (Units.imp) {
        val mi = m / 1609.344
        if (mi >= 0.2) fmt(if (mi >= 10) "%.0f mi" else "%.1f mi", mi) else fmt("%.0f ft", (m * 3.28084 / 50).roundToInt() * 50.0)
    } else {
        if (m >= 1000) fmt(if (m >= 10_000) "%.0f km" else "%.1f km", m / 1000) else fmt("%.0f m", if (m >= 100) (m / 50).roundToInt() * 50.0 else (m / 10).roundToInt() * 10.0)
    }

    /** Spoken distance: "In 300 metres", "In 1.5 kilometres", "In half a mile". */
    fun spokenIn(m: Double): String = if (Units.imp) {
        val mi = m / 1609.344
        when {
            mi >= 0.9 -> "In ${fmt("%.0f", mi)} ${if (mi.roundToInt() == 1) "mile" else "miles"}"
            mi >= 0.4 -> "In half a mile"
            mi >= 0.2 -> "In a quarter of a mile"
            else -> "In ${((m * 3.28084) / 100).roundToInt() * 100} feet"
        }
    } else when {
        m >= 1500 -> "In ${fmt("%.1f", m / 1000).removeSuffix(".0")} kilometres"
        m >= 950 -> "In 1 kilometre"
        else -> "In ${max(50, (m / 50).roundToInt() * 50)} metres"
    }

    fun duration(s: Double): String {
        val min = (s / 60).roundToInt()
        return if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
    }
}

// ------------------------------------------------------------------ online services

object NavNet {
    private fun get(url: String, timeout: Int = 12_000): String {
        val con = URL(url).openConnection() as HttpURLConnection
        con.connectTimeout = timeout; con.readTimeout = timeout
        con.setRequestProperty("User-Agent", TileMapView.UA)
        con.setRequestProperty("Accept-Language", "en")
        try {
            if (con.responseCode != 200) throw java.io.IOException("server answered ${con.responseCode}")
            return con.inputStream.bufferedReader().use { it.readText() }
        } finally { con.disconnect() }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Places matching [q], nearest to (lat, lon) first. Photon, then Nominatim. "13.75, 100.5" also works. */
    fun search(q: String, lat: Double, lon: Double): List<Dest> {
        Regex("""^\s*(-?\d{1,2}(\.\d+)?)\s*[, ]\s*(-?\d{1,3}(\.\d+)?)\s*$""").find(q)?.let { m ->
            val la = m.groupValues[1].toDouble(); val lo = m.groupValues[3].toDouble()
            return listOf(Dest(fmt("%.5f, %.5f", la, lo), "Coordinates", la, lo))
        }
        val bias = if (!lat.isNaN()) "&lat=$lat&lon=$lon" else ""
        try {
            val out = parsePhoton(get("https://photon.komoot.io/api/?q=${enc(q)}&limit=10&lang=en$bias"))
            if (out.isNotEmpty()) return sortNear(out, lat, lon)
        } catch (e: Exception) { Diag.log("search photon: ${e.message}") }
        val near = if (!lat.isNaN()) "&viewbox=${lon - 1},${lat + 1},${lon + 1},${lat - 1}" else ""
        return sortNear(parseNominatim(get("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=10&q=${enc(q)}$near")), lat, lon)
    }

    private fun sortNear(l: List<Dest>, lat: Double, lon: Double): List<Dest> =
        if (lat.isNaN()) l else l.sortedBy { Geo.dist(lat, lon, it.lat, it.lon).let { d -> if (d < 80_000) d else d * 3 } }

    fun parsePhoton(js: String): List<Dest> {
        val f = JSONObject(js).optJSONArray("features") ?: return emptyList()
        val out = ArrayList<Dest>()
        for (i in 0 until f.length()) {
            val o = f.getJSONObject(i)
            val c = o.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            val p = o.optJSONObject("properties") ?: JSONObject()
            val street = listOf(p.optString("street"), p.optString("housenumber")).filter { it.isNotBlank() }.joinToString(" ")
            val name = p.optString("name").ifBlank { street }.ifBlank { p.optString("city") }
            if (name.isBlank()) continue
            val detail = listOf(if (name != street) street else "", p.optString("city"), p.optString("state"), p.optString("country"))
                .filter { it.isNotBlank() && it != name }.distinct().joinToString(", ")
            out.add(Dest(name, detail, c.getDouble(1), c.getDouble(0)))
        }
        return out
    }

    fun parseNominatim(js: String): List<Dest> {
        val a = JSONArray(js)
        val out = ArrayList<Dest>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val full = o.optString("display_name")
            val name = o.optString("name").ifBlank { full.substringBefore(',') }
            out.add(Dest(name, full.removePrefix(name).trim(' ', ','), o.getString("lat").toDouble(), o.getString("lon").toDouble()))
        }
        return out
    }

    // ---- routes

    /** Driving route. TomTom (live traffic) when the owner gave a key, else the free OSRM servers. */
    fun route(fromLa: Double, fromLo: Double, heading: Float?, toLa: Double, toLo: Double): Route {
        val key = Prefs.gs("tomtomKey").trim()
        if (key.isNotEmpty()) {
            try { return parseTomTom(get(tomtomUrl(key, fromLa, fromLo, heading, toLa, toLo), 15_000)) }
            catch (e: Exception) { Diag.log("route tomtom: ${e.message}") }
        }
        val avoid = listOfNotNull(if (Prefs.gb("avoidTolls", false)) "toll" else null, if (Prefs.gb("avoidMotorways", false)) "motorway" else null)
        val ex = if (avoid.isEmpty()) "" else "&exclude=" + avoid.joinToString(",")
        val br = if (heading != null) "&bearings=${heading.roundToInt() % 360},60;" else ""
        val path = "$fromLo,$fromLa;$toLo,$toLa?overview=full&geometries=polyline6&steps=true$br"
        var last: Exception? = null
        for (base in listOf("https://router.project-osrm.org/route/v1/driving/", "https://routing.openstreetmap.de/routed-car/route/v1/driving/")) {
            try { return parseOsrm(get(base + path + ex)) }
            catch (e: Exception) {
                last = e
                Diag.log("route ${URL(base).host}: ${e.message}")
                if (ex.isNotEmpty()) try { return parseOsrm(get(base + path)) } catch (_: Exception) {} // server without "avoid"
            }
        }
        throw last ?: java.io.IOException("no route")
    }

    /** Google's encoded polyline (precision 6 for OSRM). */
    fun decodePolyline(s: String, precision: Int = 6): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        val f = Math.pow(10.0, precision.toDouble())
        var i = 0; var la = 0L; var lo = 0L
        while (i < s.length) {
            for (k in 0..1) {
                var shift = 0; var res = 0L
                while (true) {
                    val b = s[i++].code - 63
                    res = res or ((b and 0x1f).toLong() shl shift)
                    shift += 5
                    if (b < 0x20) break
                }
                val d = if (res and 1L != 0L) (res shr 1).inv() else res shr 1
                if (k == 0) la += d else lo += d
            }
            out.add(doubleArrayOf(la / f, lo / f))
        }
        return out
    }

    fun parseOsrm(js: String): Route {
        val o = JSONObject(js)
        if (o.optString("code") != "Ok") throw java.io.IOException(o.optString("message", o.optString("code")))
        val r = o.getJSONArray("routes").getJSONObject(0)
        val pts = decodePolyline(r.getString("geometry"))
        val mans = ArrayList<Maneuver>()
        val legs = r.getJSONArray("legs")
        for (li in 0 until legs.length()) {
            val steps = legs.getJSONObject(li).getJSONArray("steps")
            for (si in 0 until steps.length()) {
                val st = steps.getJSONObject(si)
                val m = st.getJSONObject("maneuver")
                val loc = m.getJSONArray("location")
                var type = m.optString("type")
                val mod = m.optString("modifier")
                type = when (type) {
                    "depart", "arrive", "fork", "merge" -> type
                    "on ramp" -> "ramp"
                    "off ramp" -> "exit"
                    "roundabout", "rotary", "roundabout turn" -> "roundabout"
                    "exit roundabout", "exit rotary" -> "skip" // "you left the roundabout": not its own instruction
                    "new name", "continue", "notification" -> if (mod == "straight" || mod.isEmpty()) "continue" else "turn"
                    else -> if (mod == "uturn") "uturn" else "turn"
                }
                if (type == "skip") continue
                val name = st.optString("name").ifBlank { st.optString("ref") }
                mans.add(Maneuver(loc.getDouble(1), loc.getDouble(0), type, mod, m.optInt("exit", 0), name))
            }
        }
        return Route(pts, mans, r.optDouble("distance"), r.optDouble("duration"), "online")
    }

    private fun tomtomUrl(key: String, fLa: Double, fLo: Double, heading: Float?, tLa: Double, tLo: Double): String {
        val avoid = StringBuilder()
        if (Prefs.gb("avoidTolls", false)) avoid.append("&avoid=tollRoads")
        if (Prefs.gb("avoidMotorways", false)) avoid.append("&avoid=motorways")
        val h = if (heading != null) "&vehicleHeading=${heading.roundToInt() % 360}" else ""
        return "https://api.tomtom.com/routing/1/calculateRoute/$fLa,$fLo:$tLa,$tLo/json?key=${enc(key)}" +
            "&traffic=true&instructionsType=coded&routeRepresentation=polyline&travelMode=car$h$avoid"
    }

    fun parseTomTom(js: String): Route {
        val r = JSONObject(js).getJSONArray("routes").getJSONObject(0)
        val pts = ArrayList<DoubleArray>()
        val legs = r.getJSONArray("legs")
        for (li in 0 until legs.length()) {
            val p = legs.getJSONObject(li).getJSONArray("points")
            for (i in 0 until p.length()) { val q = p.getJSONObject(i); pts.add(doubleArrayOf(q.getDouble("latitude"), q.getDouble("longitude"))) }
        }
        val mans = ArrayList<Maneuver>()
        val ins = r.optJSONObject("guidance")?.optJSONArray("instructions") ?: JSONArray()
        for (i in 0 until ins.length()) {
            val o = ins.getJSONObject(i)
            val pt = o.getJSONObject("point")
            val code = o.optString("maneuver")
            val (type, mod) = when (code) {
                "DEPART" -> "depart" to ""
                "ARRIVE", "ARRIVE_LEFT", "ARRIVE_RIGHT" -> "arrive" to ""
                "STRAIGHT" -> "continue" to "straight"
                "KEEP_LEFT", "BEAR_LEFT" -> "fork" to "slight left"
                "KEEP_RIGHT", "BEAR_RIGHT" -> "fork" to "slight right"
                "TURN_LEFT" -> "turn" to "left"
                "TURN_RIGHT" -> "turn" to "right"
                "SHARP_LEFT" -> "turn" to "sharp left"
                "SHARP_RIGHT" -> "turn" to "sharp right"
                "MAKE_UTURN", "TRY_MAKE_UTURN" -> "uturn" to "uturn"
                "ENTER_MOTORWAY", "ENTER_HIGHWAY", "ENTER_FREEWAY" -> "ramp" to (if (o.optString("drivingSide") == "LEFT") "left" else "right")
                "TAKE_EXIT", "MOTORWAY_EXIT_LEFT", "MOTORWAY_EXIT_RIGHT" -> "exit" to (if (code.endsWith("LEFT")) "left" else "right")
                "ROUNDABOUT_CROSS", "ROUNDABOUT_RIGHT", "ROUNDABOUT_LEFT", "ROUNDABOUT_BACK" -> "roundabout" to ""
                "SWITCH_MOTORWAY_LEFT", "SWITCH_PARALLEL_ROAD" -> "fork" to "slight left"
                "SWITCH_MOTORWAY_RIGHT" -> "fork" to "slight right"
                else -> "continue" to ""
            }
            val name = o.optString("street").ifBlank { o.optString("roadNumbers").trim('[', ']', '"') }
            mans.add(Maneuver(pt.getDouble("latitude"), pt.getDouble("longitude"), type, mod, o.optInt("roundaboutExitNumber", 0), name))
        }
        val sum = r.getJSONObject("summary")
        return Route(pts, mans, sum.optDouble("lengthInMeters"), sum.optDouble("travelTimeInSeconds"), "tomtom")
    }
}

// ------------------------------------------------------------------ guidance

/**
 * Turn-by-turn guidance on the route: follows the GPS, speaks the next instruction in time, finds a new route when
 * you leave this one (online, else rough offline), and fills [NavHub] so the home cards and the floating pill show it.
 */
object Guide {
    const val PKG = "drive-launcher-nav"

    @Volatile var route: Route? = null
        private set
    var dest: Dest? = null
        private set
    @Volatile var active = false
        private set
    @Volatile var version = 0
        private set
    /** Metres done along the route. */
    var done = 0.0
        private set
    /** Index of the next instruction. */
    var next = 0
        private set
    var toNext = 0.0
        private set
    var status = ""
        private set
    var muted: Boolean
        get() = Prefs.gb("navMuted", false)
        set(v) = Prefs.sb("navMuted", v)

    private var app: Context? = null
    private var seg = 0
    private var offCount = 0
    private var lastReroute = 0L
    private var rerouting = false
    private val said = HashSet<String>()
    private val main = Handler(Looper.getMainLooper())
    private var arrivedAt = 0L

    fun start(ctx: Context, d: Dest, r: Route) {
        app = ctx.applicationContext
        dest = d; route = r; active = true
        seg = 0; done = 0.0; next = 0; offCount = 0; said.clear(); arrivedAt = 0L
        status = if (r.source == "offline") "Rough route from the offline map" else ""
        Diag.log("nav: start to ${d.name}, ${fmt("%.1f", r.meters / 1000)} km (${r.source})")
        update(Gps.lat, Gps.lon, Gps.speedKmh)
        first()
        version++
    }

    fun stop() {
        if (!active) return
        active = false; route = null
        if (NavHub.pkg == PKG) { NavHub.active = false; NavHub.icon = null; NavHub.pkg = ""; NavHub.version++ }
        Diag.log("nav: stopped")
        version++
    }

    private fun say(t: String) { val c = app ?: return; if (!muted && Prefs.gb("navVoice", true)) Speaker.say(c, t) }

    private fun first() {
        val r = route ?: return
        val m = r.maneuvers.getOrNull(next) ?: return
        say("${Words.instruction(m)}. ${Words.distance(r.meters)} to go.".replace("Start the route. ", ""))
        said.add("$next:far")
    }

    /** From every GPS fix. */
    fun onFix(lat: Double, lon: Double, kmh: Float, bearing: Float) {
        if (!active || lat.isNaN()) return
        try { update(lat, lon, kmh); checkOff(lat, lon, kmh, bearing) } catch (e: Exception) { Diag.log("nav: ${e.message}") }
    }

    /** Follow the car along the route and speak instructions in time. */
    fun update(lat: Double, lon: Double, kmh: Float) {
        val r = route ?: return
        if (lat.isNaN() || r.points.size < 2) { publish(); return }
        // nearest segment, looking a little back and well ahead of the last one
        var best = seg; var bestD = Double.MAX_VALUE; var bestT = 0.0
        val from = max(0, seg - 3); val to = min(r.points.size - 2, seg + 80)
        for (i in from..to) {
            val p = r.points[i]; val q = r.points[i + 1]
            val st = Geo.toSegment(lat, lon, p[0], p[1], q[0], q[1])
            if (st[0] < bestD) { bestD = st[0]; best = i; bestT = st[1] }
        }
        seg = best
        offBy = bestD
        done = r.cum[best] + (r.cum[best + 1] - r.cum[best]) * bestT
        while (next < r.maneuvers.size - 1 && r.maneuvers[next].at <= done + 8) next++
        val m = r.maneuvers.getOrNull(next)
        toNext = if (m != null) max(0.0, m.at - done) else 0.0
        if (m != null) speak(m, kmh)
        if (r.length - done < 30 || Geo.dist(lat, lon, r.points.last()[0], r.points.last()[1]) < 25) arrive()
        publish()
    }

    private var offBy = 0.0

    private fun speak(m: Maneuver, kmh: Float) {
        val v = max(kmh / 3.6, 8.0) // m/s
        val near = max(45.0, v * 7)
        val far = (v * 28).coerceIn(300.0, 2000.0)
        val k = next
        when {
            toNext <= near && "$k:near" !in said -> {
                said.add("$k:near"); said.add("$k:far")
                if (m.type != "arrive") say(Words.instruction(m) + thenAlso(k))
            }
            toNext <= far && toNext > near + 120 && "$k:far" !in said -> {
                said.add("$k:far")
                say("${Words.spokenIn(toNext)}, ${Words.instruction(m).replaceFirstChar { it.lowercase() }}")
            }
        }
    }

    /** "…, then turn left" when the next one comes right after. */
    private fun thenAlso(k: Int): String {
        val r = route ?: return ""
        val a = r.maneuvers.getOrNull(k) ?: return ""
        val b = r.maneuvers.getOrNull(k + 1) ?: return ""
        return if (b.type != "arrive" && b.at - a.at < 150) { said.add("${k + 1}:far"); ", then ${Words.instruction(b).replaceFirstChar { it.lowercase() }}" } else ""
    }

    private fun arrive() {
        if (arrivedAt != 0L) {
            if (SystemClock.elapsedRealtime() - arrivedAt > 15_000) stop()
            return
        }
        arrivedAt = SystemClock.elapsedRealtime()
        say("You have arrived" + (dest?.name?.let { ", $it" } ?: ""))
        status = "Arrived"
        next = (route?.maneuvers?.size ?: 1) - 1
    }

    /** Left the route for a few fixes while moving: find a new one. */
    private fun checkOff(lat: Double, lon: Double, kmh: Float, bearing: Float) {
        if (arrivedAt != 0L) return
        val limit = 45.0 + min(Gps.accuracy.toDouble(), 40.0)
        offCount = if (offBy > limit && kmh > 6f) offCount + 1 else 0
        if (offCount < 3 || rerouting) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastReroute < 12_000) return
        lastReroute = now; rerouting = true
        val d = dest ?: return
        status = "Finding a new route…"
        version++
        Thread {
            val r = try { NavNet.route(lat, lon, if (kmh > 6f) bearing else null, d.lat, d.lon) }
                catch (e: Exception) { Diag.log("reroute online: ${e.message}"); try { OfflineRoute.route(lat, lon, d.lat, d.lon) } catch (e2: Exception) { Diag.log("reroute offline: ${e2.message}"); null } }
            main.post {
                rerouting = false
                if (!active) return@post
                if (r == null) { status = "No new route (no internet). Head back to the blue line."; version++; return@post }
                route = r; seg = 0; next = 0; offCount = 0
                said.clear(); said.add("0:far"); said.add("0:near")
                status = if (r.source == "offline") "Rough route from the offline map" else ""
                say("New route.")
                update(Gps.lat, Gps.lon, Gps.speedKmh)
                version++
            }
        }.start()
    }

    /** Home cards and the floating pill read the next turn from NavHub. */
    private fun publish() {
        val r = route ?: return
        val m = r.maneuvers.getOrNull(next)
        NavHub.pkg = PKG
        NavHub.active = true
        NavHub.title = if (arrivedAt != 0L) "Arrived" else Words.distance(toNext)
        NavHub.text = if (arrivedAt != 0L) (dest?.name ?: "") else m?.let { Words.instruction(it) } ?: ""
        NavHub.sub = eta()
        if (iconFor != next) { iconFor = next; NavHub.icon = m?.let { TurnArrow.bitmap(it, 96) } }
        NavHub.version++
        version++
    }

    private var iconFor = -1

    fun remaining(): Double = max(0.0, (route?.length ?: 0.0) - done)

    fun remainingSeconds(): Double {
        val r = route ?: return 0.0
        return if (r.length <= 0) 0.0 else r.seconds * remaining() / r.length
    }

    fun eta(): String {
        val s = remainingSeconds()
        return "${Words.duration(s)} · ${Words.distance(remaining())} · arrive ${Fmt.time(System.currentTimeMillis() + (s * 1000).toLong())}"
    }

    /** For the screen tests: a route on screen without GPS. */
    fun setForTest(ctx: Context, d: Dest?, r: Route?, doneMeters: Double = 0.0) {
        app = ctx.applicationContext
        if (r == null || d == null) { stop(); return }
        dest = d; route = r; active = true; seg = 0; next = 0; said.clear(); arrivedAt = 0L
        status = if (r.source == "offline") "Rough route from the offline map" else ""
        said.addAll(listOf("0:far", "0:near"))
        var i = 0
        while (i < r.points.size - 2 && r.cum[i + 1] < doneMeters) i++
        val p = r.points[i]
        update(p[0], p[1], 50f)
        said.clear()
    }
}

// ------------------------------------------------------------------ arrows

/** The turn arrow (instruction panel, home card, floating pill). */
object TurnArrow {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    fun bitmap(m: Maneuver, size: Int): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        draw(Canvas(b), m, size / 2f, size / 2f, size.toFloat(), 0xFFFFFFFF.toInt())
        return b
    }

    /** Arrow for [m] in a box of [size] centred on (cx, cy). */
    fun draw(c: Canvas, m: Maneuver, cx: Float, cy: Float, size: Float, color: Int) {
        val k = size / 24f
        c.save()
        c.translate(cx - 12 * k, cy - 12 * k)
        c.scale(k, k)
        p.color = color
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2.6f
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        val mod = m.modifier
        val left = mod.contains("left")
        when {
            m.type == "arrive" -> {
                c.drawCircle(12f, 10f, 4.5f, p)
                path.reset(); path.moveTo(12f, 14.5f); path.lineTo(12f, 21f); c.drawPath(path, p)
            }
            m.type == "roundabout" -> {
                c.drawCircle(12f, 11f, 4.5f, p)
                path.reset(); path.moveTo(12f, 22f); path.lineTo(12f, 15.5f); c.drawPath(path, p)
                path.reset(); path.moveTo(16f, 8.5f); path.lineTo(20.5f, 4f); c.drawPath(path, p)
                head(c, 20.5f, 4f, -45f)
                if (m.exit > 0) {
                    p.style = Paint.Style.FILL; p.textSize = 7f; p.textAlign = Paint.Align.CENTER; p.typeface = F.bold
                    c.drawText(m.exit.toString(), 12f, 13.5f, p)
                    p.style = Paint.Style.STROKE
                }
            }
            m.type == "uturn" || mod == "uturn" -> {
                path.reset(); path.moveTo(15f, 21f); path.lineTo(15f, 9f)
                path.cubicTo(15f, 3.5f, 7f, 3.5f, 7f, 9f); path.lineTo(7f, 16f)
                c.drawPath(path, p); head(c, 7f, 16f, 180f)
            }
            mod == "straight" || mod.isEmpty() || m.type == "depart" || m.type == "continue" -> {
                path.reset(); path.moveTo(12f, 21f); path.lineTo(12f, 4f); c.drawPath(path, p); head(c, 12f, 4f, 0f)
            }
            else -> {
                val sgn = if (left) -1f else 1f
                val ang = when {
                    mod.startsWith("slight") -> 45f
                    mod.startsWith("sharp") -> 135f
                    else -> 90f
                }
                path.reset(); path.moveTo(12f - sgn * 2f, 21f); path.lineTo(12f - sgn * 2f, 12f)
                val rad = Math.toRadians(ang.toDouble())
                val ex = 12f - sgn * 2f + sgn * (8f * sin(rad)).toFloat()
                val ey = 12f - (8f * cos(rad)).toFloat()
                path.lineTo(ex, ey)
                c.drawPath(path, p)
                head(c, ex, ey, sgn * ang)
            }
        }
        c.restore()
    }

    private fun head(c: Canvas, x: Float, y: Float, deg: Float) {
        c.save(); c.rotate(deg, x, y)
        path.reset(); path.moveTo(x - 4.5f, y + 4.5f); path.lineTo(x, y); path.lineTo(x + 4.5f, y + 4.5f)
        c.drawPath(path, p)
        c.restore()
    }
}
