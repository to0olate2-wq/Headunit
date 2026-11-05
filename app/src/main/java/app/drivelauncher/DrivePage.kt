package app.drivelauncher

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Home screen: speed, trip, road + next turn, gauges, map, now playing. */
class DrivePage(private val a: MainActivity, portrait: Boolean) {
    val view: LinearLayout

    // speed card
    private val speedSrc: TextView = a.label(12f, C.MUTED, F.semibold).apply { letterSpacing = 0.06f }
    private val speed = SpeedGauge(a)
    private val sTile1 = Tile(a, true, 20f)
    private val sTile2 = Tile(a, true, 20f)

    // trip card
    private val tripDist: TextView = a.label(52f, C.TEXT, F.numeric).tnum()
    private val tripUnit: TextView = a.label(18f, C.MUTED, F.regular)
    private val tripSub: TextView = a.label(14f, C.MUTED, F.regular)
    private val tTile1 = Tile(a, false)
    private val tTile2 = Tile(a, false)
    private val tTile3 = Tile(a, false)

    // road + nav
    private val road = RoadView(a)
    private val navChip: LinearLayout = a.hbox(14f)
    private val navIconBox = FrameLayout(a)
    private val navImg = ImageView(a)
    private val navGeneric = IconBtn(a, Ic.NAV, 28f).apply { isClickable = false; sw = 2.2f }
    private val navT1: TextView = a.label(28f, C.TEXT, F.numeric).tnum()
    private val navT2: TextView = a.label(15f, C.TEXT3, F.medium)
    private val navT3: TextView = a.label(13f, C.MUTED, F.regular)
    private val wxIcon = IconBtn(a, Ic.SUN, 18f).apply { isClickable = false }
    private val wxTemp: TextView = a.label(18f, C.TEXT, F.semibold)
    private val wxSep = View(a)
    private val wxTime: TextView = a.label(18f, C.TEXT, F.semibold).tnum()

    // gauges
    private val g = Array(4) { GaugeTile(a, false) }

    // map
    private val map = TileMapView(a)
    private val eta: TextView = a.label(15f, C.TEXT, F.semibold, lines = 2)

    // media
    private val media = MediaCard(a, false)

    private var navShown = -1
    private var navActiveShown: Boolean? = null

    init {
        view = if (portrait) a.vbox(12f) else a.hbox(12f, center = false)
        val speedCard = buildSpeed()
        val tripCard = buildTrip()
        val roadCard = buildRoad()
        val gauges = a.hbox(12f, center = false).apply { g.forEach { addView(it, lp(0, MATCH, 1f)) } }
        val mapCard = buildMap()

        if (portrait) {
            val top = a.hbox(12f, center = false)
            top.addView(speedCard, lp(0, MATCH, 1f))
            top.addView(tripCard, lp(0, MATCH, 1f))
            // weights sum to the design height so nothing is squeezed on 3:4 or 9:16 screens
            view.addView(top, lp(MATCH, 0, 250f))
            view.addView(roadCard, lp(MATCH, 0, 240f))
            view.addView(gauges, lp(MATCH, 0, 180f))
            view.addView(mapCard, lp(MATCH, 0, 200f))
            view.addView(media.view, lp(MATCH, 0, 210f))
        } else {
            val c1 = a.vbox(12f)
            c1.addView(speedCard, lp(MATCH, 0, 340f))
            c1.addView(tripCard, lp(MATCH, 0, 260f))
            val c2 = a.vbox(12f)
            c2.addView(roadCard, lp(MATCH, 0, 388f))
            c2.addView(gauges, lp(MATCH, 0, 212f))
            val c3 = a.vbox(12f)
            c3.addView(mapCard, lp(MATCH, 0, 388f))
            c3.addView(media.view, lp(MATCH, 0, 212f))
            view.addView(c1, lp(0, MATCH, 288f))
            view.addView(c2, lp(0, MATCH, 560f))
            view.addView(c3, lp(0, MATCH, 376f))
        }
    }

    private fun buildSpeed(): View {
        val card = a.vbox(8f)
        card.background = cardBg()
        card.pad(20f)
        val head = a.hbox()
        head.addView(a.caption("Speed"), lp(0, WRAP, 1f))
        head.addView(speedSrc, lp(WRAP, WRAP))
        card.addView(head, lp(MATCH, WRAP))
        card.addView(speed, lp(MATCH, 0, 1f))
        val tiles = a.hbox(8f, center = false)
        tiles.addView(sTile1, lp(0, WRAP, 1f))
        tiles.addView(sTile2, lp(0, WRAP, 1f))
        card.addView(tiles, lp(MATCH, WRAP))
        return card
    }

    private fun buildTrip(): View {
        val card = a.vbox(10f)
        card.background = cardBg()
        card.pad(20f, 16f, 20f, 20f)
        val head = a.hbox()
        head.addView(a.caption("Trip"), lp(0, WRAP, 1f))
        val reset = IconBtn(a, Ic.REFRESH, 20f).apply { bgColor = C.CARD2; radius = 12f; contentDescription = "Reset trip" }
        reset.setOnClickListener { a.confirm("Reset trip?", "Distance, time, average and max speed go back to zero.") { Gps.resetTrip() } }
        head.addView(reset, lp(S.i(44), S.i(44)))
        card.addView(head, lp(MATCH, WRAP))
        val dist = a.hbox(6f).apply { gravity = Gravity.BOTTOM }
        dist.addView(tripDist, lp(WRAP, WRAP))
        dist.addView(tripUnit, lp(WRAP, WRAP).apply { bottomMargin = S.i(6) })
        card.addView(dist, lp(MATCH, WRAP))
        card.addView(tripSub, lp(MATCH, WRAP))
        card.addView(a.spacer(), lp(MATCH, 0, 1f))
        val tiles = a.hbox(8f, center = false)
        tiles.addView(tTile1, lp(0, WRAP, 1f))
        tiles.addView(tTile2, lp(0, WRAP, 1f))
        tiles.addView(tTile3, lp(0, WRAP, 1f))
        card.addView(tiles, lp(MATCH, WRAP))
        return card
    }

    private fun buildRoad(): View {
        val card = FrameLayout(a)
        card.background = roundBg(C.ROAD_BG, 20f, C.BORDER)
        card.clipToOutline = true
        card.addView(road, flp(MATCH, MATCH))

        navChip.background = roundBg(C.CARD2, 16f, C.BORDER2)
        navChip.pad(12f, 12f, 18f, 12f)
        navIconBox.background = roundBg(C.accent, 12f)
        navIconBox.addView(navImg, flp(S.i(36), S.i(36), Gravity.CENTER))
        navIconBox.addView(navGeneric, flp(MATCH, MATCH))
        navImg.scaleType = ImageView.ScaleType.FIT_CENTER
        navChip.addView(navIconBox, lp(S.i(52), S.i(52)))
        val col = a.vbox(3f)
        navT2.maxWidth = S.i(250)
        navT3.maxWidth = S.i(250)
        col.addView(navT1, lp(WRAP, WRAP)); col.addView(navT2, lp(WRAP, WRAP)); col.addView(navT3, lp(WRAP, WRAP))
        navChip.addView(col, lp(WRAP, WRAP))
        navChip.setOnClickListener { a.openNav() }
        card.addView(navChip, flp(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { leftMargin = S.i(16); topMargin = S.i(16) })

        val wx = a.hbox(10f)
        wx.background = roundBg(C.CARD2, 999f, C.BORDER2)
        wx.pad(16f, 10f)
        wx.addView(wxIcon, lp(S.i(20), S.i(20)))
        wx.addView(wxTemp, lp(WRAP, WRAP))
        wxSep.setBackgroundColor(0xFF2F353E.toInt())
        wx.addView(wxSep, lp(S.i(1), S.i(16)))
        wx.addView(wxTime, lp(WRAP, WRAP))
        card.addView(wx, flp(WRAP, WRAP, Gravity.TOP or Gravity.END).apply { rightMargin = S.i(16); topMargin = S.i(16) })
        return card
    }

    private fun buildMap(): View {
        val card = FrameLayout(a)
        card.background = roundBg(0xFF181C22.toInt(), 20f, C.BORDER)
        card.clipToOutline = true
        map.zoom = Prefs.mapZoom
        card.addView(map, flp(MATCH, MATCH))
        map.setOnClickListener { a.openNav() }
        eta.background = roundBg(C.CARD2, 14f, C.BORDER2)
        eta.pad(14f, 10f)
        eta.maxWidth = S.i(240)
        card.addView(eta, flp(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { leftMargin = S.i(12); topMargin = S.i(12) })
        val zoom = a.vbox(8f)
        val zin = IconBtn(a, Ic.PLUS, 20f).apply { bgColor = C.CARD2; radius = 12f; fg = C.TEXT3; contentDescription = "Zoom in" }
        val zout = IconBtn(a, Ic.MINUS, 20f).apply { bgColor = C.CARD2; radius = 12f; fg = C.TEXT3; contentDescription = "Zoom out" }
        zin.setOnClickListener { map.zoom = map.zoom + 1; Prefs.mapZoom = map.zoom }
        zout.setOnClickListener { map.zoom = map.zoom - 1; Prefs.mapZoom = map.zoom }
        zoom.addView(zin, lp(S.i(44), S.i(44)))
        zoom.addView(zout, lp(S.i(44), S.i(44)))
        card.addView(zoom, flp(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply { rightMargin = S.i(12); bottomMargin = S.i(12) })
        return card
    }

    private val timeFmt12 = SimpleDateFormat("h:mm a", Locale.US)
    private val timeFmt24 = SimpleDateFormat("HH:mm", Locale.US)
    private val dateFmt = SimpleDateFormat("EEE d MMM", Locale.US)

    fun clock(): String = (if (android.text.format.DateFormat.is24HourFormat(a)) timeFmt24 else timeFmt12).format(Date())

    fun update() {
        // ---- speed (GPS first, OBD as fallback)
        val obdSpeed = Obd.v("speed")
        val kmh: Float?
        val srcName: String
        when {
            Gps.fresh -> { kmh = Gps.speedKmh; srcName = "GPS" }
            obdSpeed != null -> { kmh = obdSpeed; srcName = "OBD" }
            else -> { kmh = null; srcName = if (Gps.permission) "NO GPS FIX" else "NO GPS ACCESS" }
        }
        speedSrc.put(srcName)
        val shown = kmh?.let { Units.speed(it) }
        speed.set((shown ?: 0f) / (if (Units.imp) 120f else 200f), shown?.roundToInt()?.toString() ?: "--", Units.speedUnit, kmh == null)
        val rpm = Obd.v("rpm")
        if (rpm != null) sTile1.set(fmt("%,d", rpm.roundToInt()), "RPM")
        else sTile1.set(if (Gps.alt.isNaN()) "--" else fmt("%,.0f %s", Units.alt(Gps.alt), Units.altUnit), "Altitude")
        sTile2.set(if (Gps.fresh || Gps.bearing != 0f) Gps.compass(Gps.bearing) else "--", "Heading")

        // ---- trip
        tripDist.put(fmt("%.1f", Units.dist(Gps.tripMeters)))
        tripUnit.put(Units.distUnit)
        val mins = Gps.tripMovingMs / 60000
        tripSub.put(if (Gps.tripStart == 0L) "Starts when you drive" else "$mins min moving · since ${timeFmt(Gps.tripStart)}")
        tTile1.set(fmt("%.0f", Units.speed(Gps.avgKmh)), "Avg ${Units.speedUnitLower}")
        tTile2.set(fmt("%.0f", Units.speed(Gps.tripMaxKmh)), "Max ${Units.speedUnitLower}")
        val eco = Obd.economy()
        if (eco != null) tTile3.set(fmt("%.1f", eco), "L/100 km")
        else tTile3.set(fmt("%d:%02d", mins / 60, mins % 60), "Moving h:m")

        // ---- road, nav, weather, time
        road.speedKmh = kmh ?: 0f
        if (NavHub.version != navShown || navShown == -1) {
            navShown = NavHub.version
            if (NavHub.active) {
                navT1.put(NavHub.title.ifEmpty { NavHub.text })
                navT2.put(if (NavHub.title.isEmpty()) "" else NavHub.text)
                navT3.put("via " + NavHub.appName(a))
                val ic = NavHub.icon
                navImg.setImageBitmap(ic)
                navImg.showIf(ic != null)
                navGeneric.showIf(ic == null)
                navT2.showIf(navT2.text.isNotEmpty())
            } else {
                navT1.put("No route")
                navT2.put("Tap to open Maps")
                navT3.put("Turns show here during navigation")
                navImg.setImageBitmap(null)
                navImg.showIf(false)
                navGeneric.showIf(true)
                navT2.showIf(true)
            }
        }
        if (navActiveShown != NavHub.active) {
            navActiveShown = NavHub.active
            navIconBox.background = roundBg(if (NavHub.active) C.accent else C.TRACK, 12f)
            navGeneric.set(fg = if (NavHub.active) C.BG else C.TEXT2)
        }
        val temp = Weather.tempC
        wxTemp.put(if (temp.isNaN()) "" else fmt("%.0f°", Units.temp(temp)))
        wxTemp.showIf(!temp.isNaN())
        wxIcon.showIf(!temp.isNaN())
        wxSep.showIf(!temp.isNaN())
        wxIcon.set(icon = Weather.icon())
        wxTime.put(clock())

        // ---- gauges: OBD when connected, GPS info otherwise
        if (Obd.connected) {
            val r = Obd.v("rpm")
            g[0].set("RPM", (r ?: 0f) / 7000f, r?.let { fmt("%,d", it.roundToInt()) } ?: "--", "rpm", "0 – 7,000")
            val thr = Obd.v("throttle")
            val load = Obd.v("load")
            if (thr != null || load == null) g[1].set("Throttle", (thr ?: 0f) / 100f, thr?.let { fmt("%.0f", it) } ?: "--", "%", "0 – 100")
            else g[1].set("Engine load", load / 100f, fmt("%.0f", load), "%", "0 – 100")
            val v = Obd.v("volt")
            g[2].set("Battery", ((v ?: 10f) - 10f) / 6f, v?.let { fmt("%.1f", it) } ?: "--", "V", "10 – 16", warn = v != null && (v < 11.8f || v > 15.2f))
            val ct = Obd.v("coolant")
            g[3].set("Coolant", ((ct ?: 40f) - 40f) / 80f, ct?.let { fmt("%.0f", it) } ?: "--", "°C", "40 – 120", warn = ct != null && ct >= 108f)
        } else {
            g[0].set("Heading", 0f, if (Gps.fresh || Gps.bearing != 0f) Gps.compass(Gps.bearing) else "--",
                fmt("%.0f°", Gps.bearing), "GPS", ring = false)
            g[1].set("Altitude", 0f, if (Gps.alt.isNaN()) "--" else fmt("%,.0f", Units.alt(Gps.alt)), Units.altUnit, "GPS", ring = false)
            g[2].set("Satellites", 0f, if (Gps.satsSeen > 0) "${Gps.satsUsed}" else "--",
                if (Gps.satsSeen > 0) "of ${Gps.satsSeen} in view" else "searching", "GPS", ring = false)
            g[3].set(dateFmt.format(Date()), 0f, clock(), "", "Connect OBD2 for engine gauges", ring = false)
        }

        // ---- map
        map.setPosition(Gps.lat, Gps.lon, Gps.bearing)
        val etaText = if (NavHub.active) listOf(NavHub.sub, if (NavHub.sub.isEmpty()) NavHub.text else "").filter { it.isNotBlank() }.joinToString("\n") else ""
        eta.put(etaText)
        eta.showIf(etaText.isNotEmpty())

        media.update()
    }

    private fun timeFmt(ms: Long): String =
        (if (android.text.format.DateFormat.is24HourFormat(a)) timeFmt24 else timeFmt12).format(Date(ms))
}
