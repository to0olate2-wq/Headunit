package app.drivelauncher

import android.appwidget.AppWidgetHostView
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** One tile on a home page. */
interface Card {
    val view: View
    fun update()
}

object Fmt {
    private val t12 = SimpleDateFormat("h:mm a", Locale.US)
    private val t24 = SimpleDateFormat("HH:mm", Locale.US)
    private val t12s = SimpleDateFormat("h:mm", Locale.US)
    private val ampm = SimpleDateFormat("a", Locale.US)
    private val date = SimpleDateFormat("EEE d MMM", Locale.US)
    private val dateLong = SimpleDateFormat("EEEE, d MMMM", Locale.US)
    var h24 = false
    fun time(ms: Long = System.currentTimeMillis()): String = (if (h24) t24 else t12).format(Date(ms))
    fun timeShort(ms: Long = System.currentTimeMillis()): String = (if (h24) t24 else t12s).format(Date(ms))
    fun ampm(ms: Long = System.currentTimeMillis()): String = if (h24) "" else ampm.format(Date(ms))
    fun date(ms: Long = System.currentTimeMillis()): String = date.format(Date(ms))
    fun dateLong(ms: Long = System.currentTimeMillis()): String = dateLong.format(Date(ms))
}

// ------------------------------------------------------------------ cards

class SpeedCard(private val a: MainActivity) : Card {
    override val view: LinearLayout = a.vbox(8f)
    private val src: TextView = a.label(12f, C.MUTED, F.semibold).apply { letterSpacing = 0.06f }
    private val gauge = SpeedGauge(a)
    private val t1 = Tile(a, true, 20f)
    private val t2 = Tile(a, true, 20f)
    private val sign = SpeedSignView(a)

    init {
        view.background = cardBg()
        view.pad(20f)
        val head = a.hbox()
        head.addView(a.caption("Speed"), lp(0, WRAP, 1f))
        head.addView(src, lp(WRAP, WRAP))
        view.addView(head, lp(MATCH, WRAP))
        val g = FrameLayout(a)
        g.addView(gauge, flp(MATCH, MATCH))
        sign.contentDescription = "Speed limit"
        g.addView(sign, flp(S.i(52), S.i(52), Gravity.TOP or Gravity.END))
        view.addView(g, lp(MATCH, 0, 1f))
        val tiles = a.hbox(8f, center = false)
        tiles.addView(t1, lp(0, WRAP, 1f))
        tiles.addView(t2, lp(0, WRAP, 1f))
        view.addView(tiles, lp(MATCH, WRAP))
    }

    override fun update() {
        sign.update()
        val obdSpeed = Obd.v("speed")
        val kmh: Float?
        val name: String
        when {
            Gps.fresh -> { kmh = Gps.speedKmh; name = "GPS" }
            obdSpeed != null -> { kmh = obdSpeed; name = "OBD" }
            else -> { kmh = null; name = if (Gps.permission) "NO GPS FIX" else "NO GPS ACCESS" }
        }
        val lim = Prefs.speedLimit
        src.put(if (lim > 0) "$name · LIMIT $lim" else name)
        val shown = kmh?.let { Units.speed(it) }
        gauge.set((shown ?: 0f) / (if (Units.imp) 120f else 200f), shown?.roundToInt()?.toString() ?: "--",
            Units.speedUnit, kmh == null, SpeedAlert.over)
        val rpm = Obd.v("rpm")
        if (rpm != null) t1.set(fmt("%,d", rpm.roundToInt()), "RPM")
        else t1.set(if (Gps.alt.isNaN()) "--" else fmt("%,.0f %s", Units.alt(Gps.alt), Units.altUnit), "Altitude")
        t2.set(if (Gps.fresh || Gps.bearing != 0f) Gps.compass(Gps.bearing) else "--", "Heading")
    }
}

class TripCard(private val a: MainActivity) : Card {
    override val view: LinearLayout = a.vbox(10f)
    private val dist: TextView = a.label(52f, C.TEXT, F.numeric).tnum()
    private val unit: TextView = a.label(18f, C.MUTED, F.regular)
    private val sub: TextView = a.label(14f, C.MUTED, F.regular)
    private val t1 = Tile(a, false)
    private val t2 = Tile(a, false)
    private val t3 = Tile(a, false)

    init {
        view.background = cardBg()
        view.pad(20f, 16f, 20f, 20f)
        val head = a.hbox()
        head.addView(a.caption("Trip"), lp(0, WRAP, 1f))
        val reset = IconBtn(a, Ic.REFRESH, 20f).apply { bgColor = C.CARD2; radius = 12f; contentDescription = "Reset trip" }
        reset.setOnClickListener { a.confirm("Reset trip?", "Distance, time, average and max speed go back to zero.") { Gps.resetTrip() } }
        head.addView(reset, lp(S.i(44), S.i(44)))
        view.addView(head, lp(MATCH, WRAP))
        val d = a.hbox(6f).apply { gravity = Gravity.BOTTOM }
        d.addView(dist, lp(WRAP, WRAP))
        d.addView(unit, lp(WRAP, WRAP).apply { bottomMargin = S.i(6) })
        view.addView(d, lp(MATCH, WRAP))
        view.addView(sub, lp(MATCH, WRAP))
        view.addView(a.spacer(), lp(MATCH, 0, 1f))
        val tiles = a.hbox(8f, center = false)
        tiles.addView(t1, lp(0, WRAP, 1f)); tiles.addView(t2, lp(0, WRAP, 1f)); tiles.addView(t3, lp(0, WRAP, 1f))
        view.addView(tiles, lp(MATCH, WRAP))
    }

    override fun update() {
        dist.put(fmt("%.1f", Units.dist(Gps.tripMeters)))
        unit.put(Units.distUnit)
        val mins = Gps.tripMovingMs / 60000
        sub.put(if (Gps.tripStart == 0L) "Starts when you drive" else "$mins min moving · since ${Fmt.time(Gps.tripStart)}")
        t1.set(fmt("%.0f", Units.speed(Gps.avgKmh)), "Avg ${Units.speedUnitLower}")
        t2.set(fmt("%.0f", Units.speed(Gps.tripMaxKmh)), "Max ${Units.speedUnitLower}")
        val eco = Obd.economy()
        if (eco != null) t3.set(fmt("%.1f", eco), "L/100 km") else t3.set(fmt("%d:%02d", mins / 60, mins % 60), "Moving h:m")
    }
}

/** Small chip with the next turn from Google Maps / Waze. Used by the road and map cards. */
class TurnChip(private val a: MainActivity) {
    val view: LinearLayout = a.hbox(14f)
    private val box = FrameLayout(a)
    private val img = ImageView(a)
    private val generic = IconBtn(a, Ic.NAV, 28f).apply { isClickable = false; sw = 2.2f }
    private val t1: TextView = a.label(28f, C.TEXT, F.numeric).tnum()
    private val t2: TextView = a.label(15f, C.TEXT3, F.medium)
    private val t3: TextView = a.label(13f, C.MUTED, F.regular)
    private var shown = -1
    private var activeShown: Boolean? = null

    init {
        view.background = roundBg(C.FLOAT, 16f, C.BORDER2)
        view.pad(12f, 12f, 18f, 12f)
        box.addView(img, flp(S.i(36), S.i(36), Gravity.CENTER))
        box.addView(generic, flp(MATCH, MATCH))
        img.scaleType = ImageView.ScaleType.FIT_CENTER
        view.addView(box, lp(S.i(52), S.i(52)))
        val col = a.vbox(3f)
        t2.maxWidth = S.i(250); t3.maxWidth = S.i(250)
        col.addView(t1, lp(WRAP, WRAP)); col.addView(t2, lp(WRAP, WRAP)); col.addView(t3, lp(WRAP, WRAP))
        view.addView(col, lp(WRAP, WRAP))
        view.setOnClickListener { a.openNav() }
    }

    fun update() {
        if (NavHub.version != shown) {
            shown = NavHub.version
            if (NavHub.active) {
                t1.put(NavHub.title.ifEmpty { NavHub.text })
                t2.put(if (NavHub.title.isEmpty()) "" else NavHub.text)
                t3.put("via " + NavHub.appName(a))
                img.setImageBitmap(NavHub.icon)
                img.showIf(NavHub.icon != null)
                generic.showIf(NavHub.icon == null)
            } else {
                t1.put("No route"); t2.put("Tap to open Maps"); t3.put("Turns show here during navigation")
                img.setImageBitmap(null); img.showIf(false); generic.showIf(true)
            }
            t2.showIf(t2.text.isNotEmpty())
        }
        if (activeShown != NavHub.active) {
            activeShown = NavHub.active
            box.background = if (NavHub.active && (Skin.glowy || Skin.lux)) Skin.gradient(12f) else roundBg(if (NavHub.active) C.accent else C.TRACK, if (Skin.sport) 4f else 12f)
            generic.set(fg = if (!NavHub.active) C.TEXT2 else if (Skin.glowy) C.ON_GRAD else C.ON_ACCENT)
        }
    }
}

class RoadCard(private val a: MainActivity) : Card {
    override val view = FrameLayout(a)
    private val road = RoadView(a)
    private val turn = TurnChip(a)
    private val wxIcon = IconBtn(a, Ic.SUN, 18f).apply { isClickable = false }
    private val wxTemp: TextView = a.label(18f, C.TEXT, F.semibold)
    private val wxSep = View(a)
    private val wxTime: TextView = a.label(18f, C.TEXT, F.semibold).tnum()

    init {
        view.background = cardBg(C.ROAD_BG, clip = true)
        view.clipToOutline = true
        view.addView(road, flp(MATCH, MATCH))
        view.addView(turn.view, flp(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { leftMargin = S.i(16); topMargin = S.i(16) })
        val wx = a.hbox(10f)
        wx.background = roundBg(C.FLOAT, 999f, C.BORDER2)
        wx.pad(16f, 10f)
        wx.addView(wxIcon, lp(S.i(20), S.i(20)))
        wx.addView(wxTemp, lp(WRAP, WRAP))
        wxSep.setBackgroundColor(C.SEP)
        wx.addView(wxSep, lp(S.i(1), S.i(16)))
        wx.addView(wxTime, lp(WRAP, WRAP))
        view.addView(wx, flp(WRAP, WRAP, Gravity.TOP or Gravity.END).apply { rightMargin = S.i(16); topMargin = S.i(16) })
    }

    override fun update() {
        road.speedKmh = if (Gps.fresh) Gps.speedKmh else Obd.v("speed") ?: 0f
        turn.update()
        val temp = Weather.tempC
        wxTemp.put(if (temp.isNaN()) "" else fmt("%.0f°", Units.temp(temp)))
        wxTemp.showIf(!temp.isNaN()); wxIcon.showIf(!temp.isNaN()); wxSep.showIf(!temp.isNaN())
        wxIcon.set(icon = Weather.icon())
        wxTime.put(Fmt.time())
    }
}

class GaugesCard(private val a: MainActivity) : Card {
    override val view: LinearLayout = a.hbox(12f, center = false)
    private val g = Array(4) { GaugeTile(a, false) }

    init { g.forEach { view.addView(it, lp(0, MATCH, 1f)) } }

    override fun update() {
        if (Obd.connected) {
            val r = Obd.v("rpm")
            g[0].set("RPM", (r ?: 0f) / 7000f, r?.let { fmt("%,d", it.roundToInt()) } ?: "--", "rpm", "0 – 7,000")
            val thr = Obd.v("throttle"); val load = Obd.v("load")
            if (thr != null || load == null) g[1].set("Throttle", (thr ?: 0f) / 100f, thr?.let { fmt("%.0f", it) } ?: "--", "%", "0 – 100")
            else g[1].set("Engine load", load / 100f, fmt("%.0f", load), "%", "0 – 100")
            val v = Obd.v("volt")
            g[2].set("Battery", ((v ?: 10f) - 10f) / 6f, v?.let { fmt("%.1f", it) } ?: "--", "V", "10 – 16", warn = v != null && (v < 11.8f || v > 15.2f))
            val ct = Obd.v("coolant")
            g[3].set("Coolant", ((ct ?: 40f) - 40f) / 80f, ct?.let { fmt("%.0f", it) } ?: "--", "°C", "40 – 120", warn = ct != null && ct >= 108f)
        } else {
            g[0].set("Heading", 0f, if (Gps.fresh || Gps.bearing != 0f) Gps.compass(Gps.bearing) else "--", fmt("%.0f°", Gps.bearing), "GPS", ring = false)
            g[1].set("Altitude", 0f, if (Gps.alt.isNaN()) "--" else fmt("%,.0f", Units.alt(Gps.alt)), Units.altUnit, "GPS", ring = false)
            g[2].set("Satellites", 0f, if (Gps.satsSeen > 0) "${Gps.satsUsed}" else "--",
                if (Gps.satsSeen > 0) "of ${Gps.satsSeen} in view" else "searching", "GPS", ring = false)
            g[3].set(Fmt.date(), 0f, Fmt.time(), "", "Connect OBD2 for engine gauges", ring = false)
        }
    }
}

class MapCard(private val a: MainActivity, showTurn: Boolean) : Card {
    override val view = FrameLayout(a)
    private val map = TileMapView(a)
    private val turn: TurnChip? = if (showTurn) TurnChip(a) else null
    private val eta: TextView = a.label(15f, C.TEXT, F.semibold, lines = 2)

    init {
        view.background = cardBg(C.MAP_BG, clip = true)
        view.clipToOutline = true
        map.zoom = Prefs.mapZoom
        view.addView(map, flp(MATCH, MATCH))
        map.setOnClickListener { a.openNav() }
        val top = a.vbox(8f)
        turn?.let { top.addView(it.view, lp(WRAP, WRAP)) }
        eta.background = roundBg(C.FLOAT, 14f, C.BORDER2)
        eta.pad(14f, 10f)
        eta.maxWidth = S.i(300)
        top.addView(eta, lp(WRAP, WRAP))
        view.addView(top, flp(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { leftMargin = S.i(12); topMargin = S.i(12) })
        val col = a.vbox(8f)
        fun b(ic: Ic, desc: String, f: () -> Unit) = IconBtn(a, ic, 20f).apply {
            bgColor = C.FLOAT; radius = 12f; fg = C.TEXT3; contentDescription = desc
            setOnClickListener { f() }
            col.addView(this, lp(S.i(44), S.i(44)))
        }
        b(Ic.SPLIT, "Split screen with Maps") { a.splitScreen() }
        b(Ic.PLUS, "Zoom in") { map.zoom = map.zoom + 1; Prefs.mapZoom = map.zoom }
        b(Ic.MINUS, "Zoom out") { map.zoom = map.zoom - 1; Prefs.mapZoom = map.zoom }
        view.addView(col, flp(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply { rightMargin = S.i(12); bottomMargin = S.i(12) })
    }

    override fun update() {
        turn?.update()
        turn?.view?.showIf(NavHub.active)
        map.setPosition(Gps.lat, Gps.lon, Gps.bearing)
        val e = if (NavHub.active) listOf(NavHub.sub, if (NavHub.sub.isEmpty() && turn == null) NavHub.text else "").filter { it.isNotBlank() }.joinToString("\n") else ""
        eta.put(e)
        eta.showIf(e.isNotEmpty())
    }
}

class ClockCard(private val a: MainActivity) : Card {
    override val view: LinearLayout = a.vbox(6f)
    private val time: TextView = a.label(when (Skin.id) { Skin.GLASS -> 110f; Skin.LUX -> 124f; else -> 96f }, C.TEXT, F.display).tnum()
    private val ampm: TextView = a.label(24f, C.MUTED, F.semibold)
    private val date: TextView = a.label(20f, C.TEXT2, F.medium)
    private val wxIcon = IconBtn(a, Ic.SUN, 22f).apply { isClickable = false }
    private val wx: TextView = a.label(20f, C.TEXT2, F.medium)

    init {
        view.background = cardBg()
        view.gravity = Gravity.CENTER
        view.pad(20f)
        val t = a.hbox(8f).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL }
        t.addView(time, lp(WRAP, WRAP)); t.addView(ampm, lp(WRAP, WRAP).apply { bottomMargin = S.i(14) })
        view.addView(t, lp(WRAP, WRAP))
        view.addView(date, lp(WRAP, WRAP))
        val w = a.hbox(8f)
        w.addView(wxIcon, lp(S.i(24), S.i(24))); w.addView(wx, lp(WRAP, WRAP))
        view.addView(w, lp(WRAP, WRAP).apply { topMargin = S.i(6) })
    }

    override fun update() {
        time.put(Fmt.timeShort()); ampm.put(Fmt.ampm()); ampm.showIf(!Fmt.h24)
        date.put(Fmt.dateLong())
        val tc = Weather.tempC
        wx.put(if (tc.isNaN()) "" else fmt("%.0f°", Units.temp(tc)))
        wxIcon.set(icon = Weather.icon()); wxIcon.showIf(!tc.isNaN())
    }
}

class CompassCard(private val a: MainActivity) : Card {
    override val view: LinearLayout = a.vbox(8f)
    private val rose = CompassView(a)
    private val heading: TextView = a.label(28f, C.TEXT, F.numeric).tnum()
    private val alt: TextView = a.label(14f, C.MUTED, F.regular)

    init {
        view.background = cardBg()
        view.gravity = Gravity.CENTER_HORIZONTAL
        view.pad(16f)
        view.addView(a.caption("Compass"), lp(MATCH, WRAP))
        view.addView(rose, lp(MATCH, 0, 1f))
        view.addView(heading, lp(WRAP, WRAP))
        view.addView(alt, lp(WRAP, WRAP))
    }

    override fun update() {
        rose.heading = Gps.bearing
        heading.put(if (Gps.fresh || Gps.bearing != 0f) fmt("%s  %.0f°", Gps.compass(Gps.bearing), Gps.bearing) else "--")
        alt.put(if (Gps.alt.isNaN()) "Altitude --" else fmt("Altitude %,.0f %s", Units.alt(Gps.alt), Units.altUnit))
    }
}

/** Summary of the Car page: odometer, next service, fuel this month, check-engine light, last drive. */
class CarCareCard(private val a: MainActivity) : Card {
    override val view: LinearLayout = a.vbox(10f)
    private val rows = Array(6) { a.label(15f, C.TEXT2, F.regular) to a.label(15f, C.TEXT, F.semibold) }

    init {
        view.background = cardBg()
        view.pad(20f)
        view.addView(a.caption("Car care"), lp(MATCH, WRAP))
        for ((k, v) in rows) {
            val r = a.hbox(8f)
            r.addView(k, lp(0, WRAP, 1f)); r.addView(v, lp(WRAP, WRAP))
            view.addView(r, lp(MATCH, 0, 1f))
        }
        view.setOnClickListener { a.show(MainActivity.Page.CAR) }
    }

    override fun update() {
        val odo = Odometer.km
        rows[0].first.put("Odometer"); rows[0].second.put(fmt("%,.0f %s", Units.dist(odo * 1000), Units.distUnit))
        val now = System.currentTimeMillis()
        val next = ServiceLog.list().minByOrNull { s -> val k = s.kmLeft(odo); val d = s.daysLeft(now); minOf(k ?: 1e9, (d ?: 100000L) * 50.0) }
        rows[1].first.put(next?.name ?: "Service")
        rows[1].second.put(next?.let { CarPage.dueText(it, odo, now) } ?: "--")
        rows[1].second.setTextColor(when (next?.state(odo, now)) { 2 -> C.WARN; 1 -> C.AMBER_TEXT; else -> C.TEXT })
        rows[2].first.put("Fuel this month"); rows[2].second.put(Prefs.currency + fmt("%,.0f", FuelStats.monthCost(FuelLog.list(), now)))
        rows[3].first.put("Check engine")
        rows[3].second.put(when { !Obd.connected -> "OBD2 not connected"; Obd.mil == true -> "ON · ${Obd.dtcStored.size} code(s)"; else -> "OK" })
        rows[3].second.setTextColor(if (Obd.mil == true) C.WARN else C.TEXT)
        val last = Trips.list().firstOrNull()
        rows[4].first.put("Last drive")
        rows[4].second.put(last?.let { fmt("%.1f %s · %s", Units.dist(it.meters), Units.distUnit, Fmt.date(it.start)) +
            (it.score?.let { s -> " · score $s" } ?: "") } ?: "--")
        val range = FuelRange.now()
        rows[5].first.put("Fuel range")
        rows[5].second.put(range?.let { FuelRange.text(it) + " " + Units.distUnit } ?: "Add a full-tank fill-up")
        rows[5].second.setTextColor(if (range != null && range.km < 60) C.WARN else if (range == null) C.MUTED else C.TEXT)
    }
}

class LogoCard(private val a: MainActivity) : Card {
    override val view = FrameLayout(a)
    private val img = ImageView(a)
    private val hint: TextView = a.label(15f, C.MUTED, F.regular, lines = 3)

    init {
        view.background = cardBg()
        view.pad(20f)
        img.scaleType = ImageView.ScaleType.FIT_CENTER
        view.addView(img, flp(MATCH, MATCH))
        hint.gravity = Gravity.CENTER
        hint.text = "Tap to choose your logo or photo"
        view.addView(hint, flp(MATCH, WRAP, Gravity.CENTER))
        val f = File(a.filesDir, "logo.png")
        if (f.exists()) {
            img.setImageBitmap(BitmapFactory.decodeFile(f.path)); hint.showIf(false)
        }
        view.setOnClickListener { a.pickImage(MainActivity.REQ_LOGO) }
    }

    override fun update() {}
}

class WidgetCard(private val a: MainActivity, private val id: Int) : Card {
    override val view = FrameLayout(a)

    init {
        view.background = cardBg()
        view.clipToOutline = true
        val info = try { a.widgetManager.getAppWidgetInfo(id) } catch (_: Exception) { null }
        if (info == null) {
            view.addView(a.label(15f, C.MUTED, F.regular, lines = 3).apply {
                text = "This widget is no longer available.\nTap and hold to choose another card."
                gravity = Gravity.CENTER
            }, flp(MATCH, WRAP, Gravity.CENTER))
        } else {
            val hv: AppWidgetHostView = a.widgetHost.createView(a.applicationContext, id, info)
            hv.setAppWidget(id, info)
            view.addView(hv, flp(MATCH, MATCH))
            hv.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
                val d = a.resources.displayMetrics.density
                val wdp = ((r - l) / d).toInt(); val hdp = ((b - t) / d).toInt()
                if (wdp > 0 && hdp > 0) try { (v as AppWidgetHostView).updateAppWidgetSize(Bundle(), wdp, hdp, wdp, hdp) } catch (_: Exception) {}
            }
        }
        val edit = IconBtn(a, Ic.SLIDERS, 16f).apply { bgColor = C.CARD2; radius = 12f; contentDescription = "Change this card" }
        view.addView(edit, flp(S.i(44), S.i(44), Gravity.TOP or Gravity.END))
        edit.tag = "edit"
    }

    override fun update() {}
}

// ------------------------------------------------------------------ pages

class PageCfg(var layout: String, val cards: MutableList<String>) {
    fun toJson(): JSONObject = JSONObject().put("layout", layout).put("cards", JSONArray(cards))
}

object HomePages {
    val CARD_TYPES = linkedMapOf(
        "hero" to "Drive hero (speed, clock, car)", "speed" to "Speed", "rpm" to "RPM dial", "trip" to "Trip computer",
        "nav" to "Next turn", "road" to "Road & next turn", "gauges" to "Engine gauges", "stats" to "Readouts (4 numbers)",
        "places" to "Quick destinations", "phone" to "Phone", "messages" to "Messages", "map" to "Map", "media" to "Music", "clock" to "Clock & weather", "compass" to "Compass",
        "car" to "Car care", "logo" to "Your logo",
    )
    val LAYOUTS = linkedMapOf(
        "aurora" to "Hero — 1 big + 2 cards", "cockpit" to "Cockpit — 2 dials + 3 cards",
        "classic" to "Classic — 6 cards", "map" to "Big map — 3 cards", "grid" to "Grid — 4 cards",
        "duo" to "Two halves — 2 cards", "dash" to "Column — 3 cards",
    )

    fun slots(layout: String) = when (layout) { "classic" -> 6; "map" -> 3; "grid" -> 4; "duo" -> 2; "cockpit" -> 5; else -> 3 }

    fun defaults(layout: String): List<String> = when (layout) {
        "aurora" -> listOf("hero", "nav", "media")
        "cockpit" -> listOf("rpm", "nav", "media", "stats", "speed")
        "classic" -> listOf("speed", "trip", "road", "gauges", "map", "media")
        "map" -> listOf("speed", "media", "map")
        "grid" -> listOf("speed", "media", "clock", "car")
        "duo" -> listOf("road", "media")
        else -> listOf("speed", "road", "media")
    }

    /** Standard pages of the current theme. */
    fun standard(): MutableList<PageCfg> {
        fun pg(l: String) = PageCfg(l, defaults(l).toMutableList())
        return when (Skin.id) {
            Skin.GLASS -> mutableListOf(pg("aurora"), pg("map"), pg("classic"))
            Skin.SPORT, Skin.NEON -> mutableListOf(pg("cockpit"), pg("map"), pg("classic"))
            Skin.LUX -> mutableListOf(pg("aurora"), pg("map"), pg("classic"))
            else -> mutableListOf(pg("classic"), pg("map"))
        }
    }

    fun load(): MutableList<PageCfg> {
        val out = ArrayList<PageCfg>()
        try {
            val s = Prefs.pages
            if (s.isNotEmpty()) {
                val a = JSONArray(s)
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val layout = o.getString("layout")
                    val c = o.getJSONArray("cards")
                    val cards = MutableList(c.length()) { c.getString(it) }
                    val want = slots(layout)
                    val d = defaults(layout)
                    while (cards.size < want) cards.add(d[cards.size])
                    out.add(PageCfg(layout, cards.take(want).toMutableList()))
                }
            }
        } catch (_: Exception) {}
        if (out.isEmpty()) out.addAll(standard())
        return out
    }

    fun save(pages: List<PageCfg>) {
        val a = JSONArray()
        pages.forEach { a.put(it.toJson()) }
        Prefs.pages = a.toString()
    }

    fun label(type: String): String = if (type.startsWith("widget:")) "Android widget" else CARD_TYPES[type] ?: type
}

/** A home page built from a layout and its cards. */
class HomePage(private val a: MainActivity, val index: Int, portrait: Boolean, cfg: PageCfg) {
    val view: LinearLayout
    private val cards = ArrayList<Card>()
    /** This page shows the time itself, so the dock clock can stay empty. */
    val hasClock = "hero" in cfg.cards || "clock" in cfg.cards

    init {
        val showTurn = "road" !in cfg.cards && "nav" !in cfg.cards
        val roomy = cfg.layout == "aurora" || cfg.layout == "duo"
        cfg.cards.forEachIndexed { slot, type ->
            val c: Card = try {
                when {
                    type == "hero" -> HeroCard(a)
                    type == "speed" -> if (Skin.analog) SpeedDialCard(a) else SpeedCard(a)
                    type == "rpm" -> RpmCard(a)
                    type == "nav" -> NavCard(a)
                    type == "stats" -> StatsCard(a)
                    type == "places" -> PlacesCard(a)
                    type == "phone" -> PhoneCard(a)
                    type == "messages" -> MessagesCard(a)
                    type == "trip" -> TripCard(a)
                    type == "road" -> RoadCard(a)
                    type == "gauges" -> GaugesCard(a)
                    type == "map" -> MapCard(a, showTurn)
                    type == "media" -> MediaCard(a, false, roomy)
                    type == "clock" -> ClockCard(a)
                    type == "compass" -> CompassCard(a)
                    type == "car" -> CarCareCard(a)
                    type == "logo" -> LogoCard(a)
                    type.startsWith("widget:") -> WidgetCard(a, type.substringAfter(':').toIntOrNull() ?: -1)
                    else -> ClockCard(a)
                }
            } catch (e: Exception) {
                Diag.log("card $type: ${e.javaClass.simpleName} ${e.message}")
                ClockCard(a)
            }
            a.attachEdit(c.view, index, slot)
            cards.add(c)
        }
        val v = cards.map { it.view }
        view = when (cfg.layout) {
            "aurora" -> if (portrait) col(v[0] to 560f, v[1] to 170f, v[2] to 330f)
                else row(v[0] to 780f, col(v[1] to 170f, v[2] to 414f) to 456f)
            "cockpit" -> if (portrait) col(row(v[0] to 1f, v[4] to 1f) to 380f, v[1] to 160f, v[2] to 210f, v[3] to 120f)
                else row(v[0] to 350f, col(v[1] to 150f, v[2] to 200f, v[3] to 110f) to 500f, v[4] to 350f)
            "map" -> if (portrait) col(row(v[0] to 1f, v[1] to 1f) to 260f, v[2] to 820f)
                else row(col(v[0] to 340f, v[1] to 260f) to 330f, v[2] to 906f)
            "grid" -> col(row(v[0] to 1f, v[1] to 1f) to 1f, row(v[2] to 1f, v[3] to 1f) to 1f)
            "duo" -> if (portrait) col(v[0] to 1f, v[1] to 1f) else row(v[0] to 1f, v[1] to 1f)
            "dash" -> if (portrait) col(v[0] to 1f, v[1] to 1f, v[2] to 1f) else row(v[0] to 1f, v[1] to 1f, v[2] to 1f)
            else -> if (portrait) col(row(v[0] to 1f, v[1] to 1f) to 250f, v[2] to 240f, v[3] to 180f, v[4] to 200f, v[5] to 210f)
                else row(col(v[0] to 340f, v[1] to 260f) to 288f, col(v[2] to 388f, v[3] to 212f) to 560f, col(v[4] to 388f, v[5] to 212f) to 376f)
        }
    }

    private fun row(vararg items: Pair<View, Float>): LinearLayout = a.hbox(12f, center = false).apply {
        items.forEach { (v, w) -> addView(v, lp(0, MATCH, w)) }
    }

    private fun col(vararg items: Pair<View, Float>): LinearLayout = a.vbox(12f).apply {
        items.forEach { (v, w) -> addView(v, lp(MATCH, 0, w)) }
    }

    fun update() {
        for (c in cards) try { c.update() } catch (e: Exception) { Diag.log("card update: ${e.javaClass.simpleName} ${e.message}") }
    }

}
