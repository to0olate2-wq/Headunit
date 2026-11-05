package app.drivelauncher

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import kotlin.math.max
import kotlin.math.roundToInt

/** Lifetime distance measured by GPS. Can be set to match the car's own odometer. */
object Odometer {
    private var meters = -1.0
    private var lastSave = 0L

    private fun load() {
        if (meters < 0) meters = try { Prefs.gs("odoM", "0").toDouble() } catch (_: Exception) { 0.0 }
    }

    val km: Double get() { load(); return meters / 1000.0 }

    fun add(m: Double) {
        load()
        meters += m
        if (SystemClock.elapsedRealtime() - lastSave > 30_000) save()
    }

    /** Set to the car's real odometer. Service reminders move with it so they don't jump to "overdue". */
    fun set(km: Double) {
        load()
        val delta = km - meters / 1000.0
        meters = km * 1000.0
        save()
        if (kotlin.math.abs(delta) > 0.5) ServiceLog.shift(delta)
    }

    fun save() {
        if (meters < 0) return
        lastSave = SystemClock.elapsedRealtime()
        try { Prefs.ss("odoM", meters.toString()) } catch (_: Exception) {}
    }
}

/** Decides when a drive starts and ends (pure logic, unit-tested). */
class TripDetector(private val startKmh: Float = 8f, private val stopMs: Long = 180_000L) {
    var active = false
        private set
    private var lastMoving = 0L

    /** @return 1 when a trip starts, 2 when it ends (time = last moment moving), else 0 */
    fun update(now: Long, kmh: Float): Int {
        if (!active) {
            if (kmh >= startKmh) { active = true; lastMoving = now; return 1 }
            return 0
        }
        if (kmh > 3f) lastMoving = now
        if (now - lastMoving >= stopMs) { active = false; return 2 }
        return 0
    }

    val endedAt: Long get() = lastMoving

    fun forceEnd(): Boolean {
        val was = active
        active = false
        return was
    }
}

class TripRecord(
    val start: Long, var end: Long, var meters: Double, var movingMs: Long, var maxKmh: Float,
    val startLat: Double, val startLon: Double, var endLat: Double, var endLon: Double, var fuelL: Double,
    /** hard brakes, hard starts, seconds over the limit; scored = false for drives saved before scoring existed */
    var hardBrakes: Int = 0, var hardAccels: Int = 0, var speedingS: Int = 0, var scored: Boolean = true,
) {
    val avgKmh: Float get() = if (movingMs > 5000) (meters / (movingMs / 1000.0) * 3.6).toFloat() else 0f
    /** 0–100, or null for old drives. */
    val score: Int? get() = if (scored) DriveScore.score(meters / 1000.0, hardBrakes, hardAccels, speedingS, movingMs / 1000) else null

    fun toJson(): JSONObject = JSONObject().put("s", start).put("e", end).put("m", meters).put("mv", movingMs)
        .put("mx", maxKmh.toDouble()).put("sa", startLat).put("so", startLon).put("ea", endLat).put("eo", endLon).put("f", fuelL)
        .apply { if (scored) put("hb", hardBrakes).put("ha", hardAccels).put("sp", speedingS) }

    companion object {
        fun from(o: JSONObject) = TripRecord(
            o.getLong("s"), o.getLong("e"), o.getDouble("m"), o.getLong("mv"), o.getDouble("mx").toFloat(),
            o.optDouble("sa"), o.optDouble("so"), o.optDouble("ea"), o.optDouble("eo"), o.optDouble("f", 0.0),
            o.optInt("hb", 0), o.optInt("ha", 0), o.optInt("sp", 0), o.has("hb"),
        )
    }
}

/**
 * How smooth a drive was: hard brakes and hard starts (from the speed, once a second) and time over the
 * speed limit. Pure functions are unit-tested.
 */
object DriveScore {
    /** Hard brake: slowing faster than about 0.3 g; hard start: speeding up faster than about 0.26 g. */
    const val BRAKE = -3.0f
    const val ACCEL = 2.6f

    fun score(km: Double, brakes: Int, accels: Int, speedingS: Int, movingS: Long): Int {
        if (km < 0.3) return 100
        val events = (8.0 * brakes + 6.0 * accels) * 10.0 / max(10.0, km)
        val speeding = if (movingS > 60) speedingS.toDouble() / movingS * 40.0 else 0.0
        return (100.0 - events - speeding).coerceIn(0.0, 100.0).roundToInt()
    }

    fun label(s: Int): String = when { s >= 90 -> "Smooth"; s >= 75 -> "Good"; s >= 60 -> "Fair"; else -> "Rough" }

    fun tip(t: TripRecord): String {
        val parts = ArrayList<String>()
        if (t.hardBrakes > 0) parts.add("${t.hardBrakes} hard ${if (t.hardBrakes == 1) "brake" else "brakes"}: leave more distance and ease off earlier")
        if (t.hardAccels > 0) parts.add("${t.hardAccels} hard ${if (t.hardAccels == 1) "start" else "starts"}: press the pedal more gently")
        if (t.movingMs > 60_000 && t.speedingS > 0) parts.add("over the limit for ${max(1, (t.speedingS * 100 / (t.movingMs / 1000)).toInt())}% of the time")
        return if (parts.isEmpty()) "Very smooth drive — no hard brakes or hard starts." else parts.joinToString("; ").replaceFirstChar { it.uppercase() } + "."
    }

    /** Live detector: feed one speed sample per second. */
    class Detector {
        private val v = FloatArray(3)
        private var n = 0
        private var cool = 0
        /** @return -1 hard brake, 1 hard start, 0 nothing */
        fun feed(kmh: Float): Int {
            v[0] = v[1]; v[1] = v[2]; v[2] = kmh
            if (n < 3) { n++; return 0 }
            if (cool > 0) { cool--; return 0 }
            val a = (v[2] - v[0]) / 3.6f / 2f // m/s² over two seconds
            return when {
                a <= BRAKE && v[0] > 15f -> { cool = 5; -1 }
                a >= ACCEL && v[2] > 10f -> { cool = 5; 1 }
                else -> 0
            }
        }
        fun reset() { n = 0; cool = 0 }
    }
}

/** How far the car can go on what is left in the tank. */
object FuelRange {
    class Est(val km: Double, val litres: Double, val pct: Double, val approx: Boolean)

    /** Average use in L/100 km: fuel log, else OBD2-measured drives, else a typical value (marked approximate). */
    fun avgUse(entries: List<FuelEntry>, trips: List<TripRecord>): Pair<Double, Boolean> {
        FuelStats.avgL100(entries)?.let { return it to false }
        val t = trips.filter { it.fuelL > 0.05 }
        val km = t.sumOf { it.meters } / 1000.0
        if (km >= 30) return t.sumOf { it.fuelL } / km * 100.0 to false
        return 9.0 to true
    }

    /** [fuelPct] from OBD2 when the car reports it; otherwise worked out from the last full-tank fill-up. */
    fun compute(tankL: Double, use: Pair<Double, Boolean>, fuelPct: Float?, entries: List<FuelEntry>, odoKm: Double): Est? {
        if (tankL <= 0 || use.first <= 0) return null
        val litres = if (fuelPct != null) fuelPct / 100.0 * tankL else {
            val byOdo = entries.sortedBy { it.odoKm }
            val lastFull = byOdo.lastOrNull { it.full } ?: return null
            val km = odoKm - lastFull.odoKm
            if (km < 0) return null
            val added = byOdo.filter { it.odoKm > lastFull.odoKm }.sumOf { it.liters }
            (tankL - km * use.first / 100.0 + added).coerceIn(0.0, tankL)
        }
        return Est(litres / use.first * 100.0, litres, litres / tankL * 100.0, use.second || fuelPct == null)
    }

    private var cached: Est? = null
    private var cachedAt = 0L

    /** Current estimate (worked out at most every 5 seconds). */
    fun now(): Est? {
        val t = android.os.SystemClock.elapsedRealtime()
        if (cachedAt != 0L && t - cachedAt < 5000) return cached
        cachedAt = t
        cached = try {
            compute(Prefs.tankL.toDouble(), avgUse(FuelLog.list(), Trips.list()), if (Obd.connected) Obd.v("fuel") else null, FuelLog.list(), Odometer.km)
        } catch (_: Exception) { null }
        return cached
    }

    fun forget() { cachedAt = 0L }

    /** "420 km" / "~420 km" in the display unit. */
    fun text(e: Est): String = (if (e.approx) "~" else "") + fmt("%,.0f", Units.dist(e.km * 1000))
}

/** Saves every drive automatically, with its route. Runs in the background service. */
object Trips {
    private var dir: File? = null
    private val det = TripDetector()
    var current: TripRecord? = null
        private set
    private val pts = StringBuilder()
    private var lastPtT = 0L
    private var lastPtLat = Double.NaN
    private var lastPtLon = Double.NaN
    private var prevLat = Double.NaN
    private var prevLon = Double.NaN
    private var prevT = 0L
    private var fuelAtStart = 0.0
    private var lastFlush = 0L
    private val score = DriveScore.Detector()
    private var cache: MutableList<TripRecord>? = null
    @Volatile var version = 0
        private set

    fun init(ctx: Context) {
        val d = File(ctx.filesDir, "trips")
        if (dir?.path != d.path) { d.mkdirs(); dir = d; cache = null }
    }

    /** Called for every GPS fix. */
    fun onFix(lat: Double, lon: Double, kmh: Float, accuracy: Float, t: Long) {
        val now = System.currentTimeMillis()
        when (det.update(now, kmh)) {
            1 -> begin(lat, lon, now)
            2 -> finish(det.endedAt)
        }
        val c = current ?: run { prevLat = lat; prevLon = lon; prevT = t; return }
        if (!prevLat.isNaN() && accuracy < 35f && kmh > 3f) {
            val d = dist(prevLat, prevLon, lat, lon)
            if (d < 500) {
                c.meters += d
                c.movingMs += (t - prevT).coerceIn(0L, 5000L)
            }
        }
        if (kmh > c.maxKmh) c.maxKmh = kmh
        c.endLat = lat; c.endLon = lon; c.end = now
        prevLat = lat; prevLon = lon; prevT = t
        if (lastPtLat.isNaN() || now - lastPtT > 10_000 || dist(lastPtLat, lastPtLon, lat, lon) > 40) {
            pts.append(fmt("%.6f,%.6f,%.0f,%d\n", lat, lon, kmh, now / 1000))
            lastPtT = now; lastPtLat = lat; lastPtLon = lon
        }
        if (now - lastFlush > 30_000) flush()
    }

    /** Called every second by the service so a drive can end even without new fixes. */
    fun tick() {
        val kmh = if (Gps.fresh) Gps.speedKmh else 0f
        if (det.active && det.update(System.currentTimeMillis(), kmh) == 2) finish(det.endedAt)
        val c = current ?: return
        // OBD speed is steadier than GPS for spotting hard brakes
        val v = Obd.v("speed") ?: if (Gps.fresh) Gps.speedKmh else return
        when (score.feed(v)) { -1 -> c.hardBrakes++; 1 -> c.hardAccels++ }
        val road = RoadLimit.kmh
        val fixed = SpeedAlert.limitKmh()
        if ((road != null && v > road + 5f) || (road == null && fixed != null && v > fixed + 1f)) c.speedingS++
    }

    /** The head unit is turning off: close the current drive. */
    fun powerOff() {
        if (det.forceEnd()) finish(System.currentTimeMillis())
    }

    private fun begin(lat: Double, lon: Double, now: Long) {
        val sLat = if (Parking.has && dist(Parking.lat, Parking.lon, lat, lon) < 2000) Parking.lat else lat
        val sLon = if (Parking.has && dist(Parking.lat, Parking.lon, lat, lon) < 2000) Parking.lon else lon
        current = TripRecord(now, now, 0.0, 0L, 0f, sLat, sLon, lat, lon, 0.0)
        fuelAtStart = Obd.fuelLitres
        score.reset()
        pts.setLength(0)
        lastPtLat = Double.NaN
        Diag.log("trip started")
        version++
    }

    private fun finish(end: Long) {
        val c = current ?: return
        current = null
        c.end = max(c.start, end)
        c.fuelL = (Obd.fuelLitres - fuelAtStart).coerceAtLeast(0.0)
        flush(c)
        Parking.save(c.endLat, c.endLon, c.end)
        if (c.meters > 200) {
            val list = list().toMutableList()
            list.add(0, c)
            while (list.size > 1000) {
                val old = list.removeAt(list.size - 1)
                file(old.start).delete()
            }
            cache = list
            saveIndex()
            Diag.log(fmt("trip saved: %.1f km", c.meters / 1000))
        } else file(c.start).delete()
        version++
    }

    private fun file(start: Long) = File(dir, "$start.csv")

    private fun flush(c: TripRecord? = current) {
        lastFlush = System.currentTimeMillis()
        val trip = c ?: return
        if (pts.isEmpty() || dir == null) return
        try { file(trip.start).appendText(pts.toString()) } catch (_: Exception) {}
        pts.setLength(0)
    }

    private fun saveIndex() {
        val d = dir ?: return
        val arr = JSONArray()
        for (t in cache ?: return) arr.put(t.toJson())
        try { File(d, "index.json").writeText(arr.toString()) } catch (_: Exception) {}
    }

    fun list(): List<TripRecord> {
        cache?.let { return it }
        val out = ArrayList<TripRecord>()
        try {
            val f = File(dir ?: return out, "index.json")
            if (f.exists()) {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) out.add(TripRecord.from(arr.getJSONObject(i)))
            }
        } catch (_: Exception) {}
        cache = out
        return out
    }

    fun route(start: Long): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        try {
            file(start).forEachLine { ln ->
                val p = ln.split(',')
                if (p.size >= 2) {
                    val la = p[0].toDoubleOrNull(); val lo = p[1].toDoubleOrNull()
                    if (la != null && lo != null) out.add(doubleArrayOf(la, lo))
                }
            }
        } catch (_: Exception) {}
        return out
    }

    fun delete(start: Long) {
        val l = list().toMutableList()
        l.removeAll { it.start == start }
        cache = l
        file(start).delete()
        saveIndex()
        version++
    }

    /** Used by the screen tests. */
    fun addForTest(t: TripRecord, route: List<DoubleArray>) {
        val l = list().toMutableList()
        l.add(t); l.sortByDescending { it.start }
        cache = l
        try { file(t.start).writeText(route.joinToString("") { fmt("%.6f,%.6f,0,0\n", it[0], it[1]) }) } catch (_: Exception) {}
        saveIndex()
        version++
    }

    fun dist(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val r = 6371000.0
        val dLa = Math.toRadians(la2 - la1)
        val dLo = Math.toRadians(lo2 - lo1)
        val a = Math.sin(dLa / 2) * Math.sin(dLa / 2) +
            Math.cos(Math.toRadians(la1)) * Math.cos(Math.toRadians(la2)) * Math.sin(dLo / 2) * Math.sin(dLo / 2)
        return 2 * r * Math.asin(Math.sqrt(a.coerceIn(0.0, 1.0)))
    }
}

/** Where the car was last switched off / parked. */
object Parking {
    var lat = Double.NaN
        private set
    var lon = Double.NaN
        private set
    var time = 0L
        private set
    private var loaded = false

    val has: Boolean get() { load(); return !lat.isNaN() }

    private fun load() {
        if (loaded) return
        loaded = true
        try {
            val s = Prefs.gs("park", "")
            if (s.isNotEmpty()) {
                val p = s.split(',')
                lat = p[0].toDouble(); lon = p[1].toDouble(); time = p[2].toLong()
            }
        } catch (_: Exception) {}
    }

    fun save(la: Double, lo: Double, t: Long) {
        if (la.isNaN() || lo.isNaN()) return
        loaded = true
        lat = la; lon = lo; time = t
        try { Prefs.ss("park", fmt("%.6f,%.6f,%d", la, lo, t)) } catch (_: Exception) {}
    }
}

class FuelEntry(val t: Long, val liters: Double, val cost: Double, val odoKm: Double, val full: Boolean) {
    fun toJson(): JSONObject = JSONObject().put("t", t).put("l", liters).put("c", cost).put("o", odoKm).put("f", full)
    companion object {
        fun from(o: JSONObject) = FuelEntry(o.getLong("t"), o.getDouble("l"), o.getDouble("c"), o.getDouble("o"), o.optBoolean("f", true))
    }
}

/** Fuel statistics (pure, unit-tested). */
object FuelStats {
    /** Average L/100 km from the first to the last full-tank fill-up. */
    fun avgL100(entries: List<FuelEntry>): Double? {
        val byOdo = entries.sortedBy { it.odoKm }
        val fulls = byOdo.filter { it.full }
        if (fulls.size < 2) return null
        val first = fulls.first(); val last = fulls.last()
        val km = last.odoKm - first.odoKm
        if (km < 50) return null
        val liters = byOdo.filter { it.odoKm > first.odoKm && it.odoKm <= last.odoKm }.sumOf { it.liters }
        return liters / km * 100.0
    }

    fun costPerKm(entries: List<FuelEntry>): Double? {
        val byOdo = entries.sortedBy { it.odoKm }
        if (byOdo.size < 2) return null
        val km = byOdo.last().odoKm - byOdo.first().odoKm
        if (km < 50) return null
        return byOdo.drop(1).sumOf { it.cost } / km
    }

    fun monthCost(entries: List<FuelEntry>, now: Long): Double {
        val c = Calendar.getInstance().apply { timeInMillis = now }
        val y = c.get(Calendar.YEAR); val m = c.get(Calendar.MONTH)
        return entries.filter {
            val e = Calendar.getInstance().apply { timeInMillis = it.t }
            e.get(Calendar.YEAR) == y && e.get(Calendar.MONTH) == m
        }.sumOf { it.cost }
    }
}

object FuelLog {
    private var dir: File? = null
    private var cache: MutableList<FuelEntry>? = null
    var version = 0
        private set

    fun init(ctx: Context) { if (dir?.path != ctx.filesDir.path) { dir = ctx.filesDir; cache = null } }

    fun list(): List<FuelEntry> {
        cache?.let { return it }
        val out = ArrayList<FuelEntry>()
        try {
            val f = File(dir ?: return out, "fuel.json")
            if (f.exists()) {
                val a = JSONArray(f.readText())
                for (i in 0 until a.length()) out.add(FuelEntry.from(a.getJSONObject(i)))
            }
        } catch (_: Exception) {}
        out.sortByDescending { it.t }
        cache = out
        return out
    }

    fun add(e: FuelEntry) { val l = list().toMutableList(); l.add(e); l.sortByDescending { it.t }; cache = l; save() }
    fun remove(t: Long) { val l = list().toMutableList(); l.removeAll { it.t == t }; cache = l; save() }

    private fun save() {
        FuelRange.forget()
        version++
        val a = JSONArray()
        cache?.forEach { a.put(it.toJson()) }
        try { File(dir ?: return, "fuel.json").writeText(a.toString()) } catch (_: Exception) {}
    }
}

class ServiceItem(var name: String, var everyKm: Int, var everyMonths: Int, var lastKm: Double, var lastT: Long) {
    fun toJson(): JSONObject = JSONObject().put("n", name).put("k", everyKm).put("mo", everyMonths).put("lk", lastKm).put("lt", lastT)

    /** km left (null if not by distance) */
    fun kmLeft(odo: Double): Double? = if (everyKm > 0) lastKm + everyKm - odo else null

    /** days left (null if not by time) */
    fun daysLeft(now: Long): Long? {
        if (everyMonths <= 0) return null
        val c = Calendar.getInstance().apply { timeInMillis = lastT; add(Calendar.MONTH, everyMonths) }
        return (c.timeInMillis - now) / 86_400_000L
    }

    /** 0 ok, 1 due soon, 2 overdue */
    fun state(odo: Double, now: Long): Int {
        val k = kmLeft(odo); val d = daysLeft(now)
        if ((k != null && k <= 0) || (d != null && d <= 0)) return 2
        if ((k != null && k <= 500) || (d != null && d <= 14)) return 1
        return 0
    }

    companion object {
        fun from(o: JSONObject) = ServiceItem(o.getString("n"), o.getInt("k"), o.getInt("mo"), o.getDouble("lk"), o.getLong("lt"))
    }
}

/** Service reminders. Defaults are common intervals, not the maker's schedule — the owner can edit them. */
object ServiceLog {
    private var dir: File? = null
    private var cache: MutableList<ServiceItem>? = null
    var version = 0
        private set

    fun init(ctx: Context) { if (dir?.path != ctx.filesDir.path) { dir = ctx.filesDir; cache = null } }

    /** The odometer was corrected by [delta] km: keep every reminder the same distance away. */
    fun shift(delta: Double) {
        list().forEach { it.lastKm += delta }
        save()
    }

    fun list(): List<ServiceItem> {
        cache?.let { return it }
        val out = ArrayList<ServiceItem>()
        try {
            val f = File(dir ?: return out, "service.json")
            if (f.exists()) {
                val a = JSONArray(f.readText())
                for (i in 0 until a.length()) out.add(ServiceItem.from(a.getJSONObject(i)))
            } else {
                val odo = Odometer.km; val now = System.currentTimeMillis()
                out.add(ServiceItem("Engine oil & filter", 10000, 12, odo, now))
                out.add(ServiceItem("Tire rotation", 10000, 0, odo, now))
                out.add(ServiceItem("Engine air filter", 20000, 24, odo, now))
                out.add(ServiceItem("Brake fluid", 0, 24, odo, now))
            }
        } catch (_: Exception) {}
        cache = out
        if (dir != null && !File(dir, "service.json").exists()) save()
        return out
    }

    fun save() {
        version++
        val a = JSONArray()
        cache?.forEach { a.put(it.toJson()) }
        try { File(dir ?: return, "service.json").writeText(a.toString()) } catch (_: Exception) {}
    }

    fun add(i: ServiceItem) { list(); cache?.add(i); save() }
    fun remove(i: ServiceItem) { list(); cache?.remove(i); save() }
    fun done(i: ServiceItem) { i.lastKm = Odometer.km; i.lastT = System.currentTimeMillis(); save() }

    fun dueCount(): Int { val o = Odometer.km; val n = System.currentTimeMillis(); return list().count { it.state(o, n) >= 1 } }
}

/** Plain-language names for common check-engine codes (generic SAE titles). */
object DtcInfo {
    private val names = mapOf(
        "P0010" to "Camshaft position actuator circuit (bank 1)",
        "P0011" to "Camshaft timing over-advanced (bank 1)",
        "P0016" to "Crankshaft / camshaft position correlation (bank 1)",
        "P0030" to "Oxygen sensor heater circuit (bank 1, sensor 1)",
        "P0101" to "Mass air flow sensor range / performance",
        "P0102" to "Mass air flow sensor low input",
        "P0103" to "Mass air flow sensor high input",
        "P0106" to "Manifold pressure (MAP) sensor range / performance",
        "P0107" to "Manifold pressure (MAP) sensor low input",
        "P0108" to "Manifold pressure (MAP) sensor high input",
        "P0110" to "Intake air temperature sensor circuit",
        "P0113" to "Intake air temperature sensor high input",
        "P0115" to "Coolant temperature sensor circuit",
        "P0117" to "Coolant temperature sensor low input",
        "P0118" to "Coolant temperature sensor high input",
        "P0120" to "Throttle position sensor circuit",
        "P0121" to "Throttle position sensor range / performance",
        "P0122" to "Throttle position sensor low input",
        "P0123" to "Throttle position sensor high input",
        "P0128" to "Thermostat: coolant below normal temperature",
        "P0130" to "Oxygen sensor circuit (bank 1, sensor 1)",
        "P0131" to "Oxygen sensor low voltage (bank 1, sensor 1)",
        "P0132" to "Oxygen sensor high voltage (bank 1, sensor 1)",
        "P0133" to "Oxygen sensor slow response (bank 1, sensor 1)",
        "P0134" to "Oxygen sensor no activity (bank 1, sensor 1)",
        "P0135" to "Oxygen sensor heater circuit (bank 1, sensor 1)",
        "P0136" to "Oxygen sensor circuit (bank 1, sensor 2)",
        "P0137" to "Oxygen sensor low voltage (bank 1, sensor 2)",
        "P0138" to "Oxygen sensor high voltage (bank 1, sensor 2)",
        "P0141" to "Oxygen sensor heater circuit (bank 1, sensor 2)",
        "P0171" to "System too lean (bank 1)",
        "P0172" to "System too rich (bank 1)",
        "P0174" to "System too lean (bank 2)",
        "P0175" to "System too rich (bank 2)",
        "P0191" to "Fuel rail pressure sensor range / performance",
        "P0201" to "Injector circuit, cylinder 1",
        "P0202" to "Injector circuit, cylinder 2",
        "P0203" to "Injector circuit, cylinder 3",
        "P0204" to "Injector circuit, cylinder 4",
        "P0221" to "Throttle position sensor B range / performance",
        "P0300" to "Random / multiple cylinder misfire",
        "P0301" to "Cylinder 1 misfire",
        "P0302" to "Cylinder 2 misfire",
        "P0303" to "Cylinder 3 misfire",
        "P0304" to "Cylinder 4 misfire",
        "P0325" to "Knock sensor circuit (bank 1)",
        "P0335" to "Crankshaft position sensor circuit",
        "P0340" to "Camshaft position sensor circuit (bank 1)",
        "P0400" to "Exhaust gas recirculation (EGR) flow",
        "P0401" to "EGR flow insufficient",
        "P0402" to "EGR flow excessive",
        "P0411" to "Secondary air injection incorrect flow",
        "P0420" to "Catalytic converter efficiency below threshold (bank 1)",
        "P0430" to "Catalytic converter efficiency below threshold (bank 2)",
        "P0440" to "Evaporative emission (EVAP) system",
        "P0441" to "EVAP system incorrect purge flow",
        "P0442" to "EVAP system small leak (check the fuel cap)",
        "P0443" to "EVAP purge control valve circuit",
        "P0446" to "EVAP vent control circuit",
        "P0449" to "EVAP vent valve / solenoid circuit",
        "P0455" to "EVAP system large leak (check the fuel cap)",
        "P0456" to "EVAP system very small leak",
        "P0496" to "EVAP flow during non-purge condition",
        "P0500" to "Vehicle speed sensor",
        "P0505" to "Idle control system",
        "P0506" to "Idle speed lower than expected",
        "P0507" to "Idle speed higher than expected",
        "P0562" to "System voltage low",
        "P0563" to "System voltage high",
        "P0600" to "Serial communication link",
        "P0601" to "Engine computer memory error",
        "P0606" to "Engine computer processor",
        "P0700" to "Transmission control system (see transmission codes)",
        "P0705" to "Transmission range sensor circuit",
        "P0715" to "Transmission input speed sensor circuit",
        "P0720" to "Transmission output speed sensor circuit",
        "P0741" to "Torque converter clutch performance",
        "P0750" to "Shift solenoid A",
        "P0755" to "Shift solenoid B",
    )

    fun describe(code: String): String {
        names[code]?.let { return it }
        if (code.length < 5) return "Unknown code"
        val area = when (code[0]) {
            'P' -> if (code[1] != '0') "Maker-specific engine / transmission code" else when (code[2]) {
                '0', '1', '2' -> "Fuel and air metering"
                '3' -> "Ignition system or misfire"
                '4' -> "Emission controls"
                '5' -> "Speed, idle control or electrical inputs"
                '6' -> "Engine computer or its outputs"
                '7', '8', '9' -> "Transmission"
                else -> "Engine / transmission"
            }
            'C' -> "Chassis (brakes, steering, suspension)"
            'B' -> "Body (airbags, doors, comfort)"
            'U' -> "Network communication between modules"
            else -> "Unknown area"
        }
        return area
    }
}
