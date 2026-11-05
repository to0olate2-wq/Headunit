package app.drivelauncher

import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Car care: trip history, fuel log, service reminders, check-engine codes, parking spot. */
class CarPage(private val a: MainActivity, portrait: Boolean) {
    val view: LinearLayout = if (portrait) a.vbox(12f) else a.hbox(12f, center = false)
    private val names = listOf("Trips", "Fuel", "Service", "Engine", "Parking")
    private val icons = listOf(Ic.ROUTE, Ic.FUEL, Ic.WRENCH, Ic.ENGINE, Ic.PARK)
    private val tabBtns = ArrayList<LinearLayout>()
    private val content = FrameLayout(a)
    private var tab = 0
    private var routeTrip: TripRecord? = null
    private var builtFor = ""
    private val odo: TextView = a.label(14f, C.MUTED, F.regular, lines = 2)

    init {
        val side = if (portrait) a.hbox(8f) else a.vbox(8f)
        side.background = cardBg()
        side.pad(if (portrait) 8f else 14f)
        if (!portrait) side.addView(a.caption("Car").apply { pad(8f, 6f, 8f, 6f) }, lp(MATCH, WRAP))
        names.forEachIndexed { i, n ->
            val b = a.hbox(12f)
            b.pad(14f, 0f)
            b.addView(IconBtn(a, icons[i], 22f).apply { isClickable = false }, lp(S.i(28), S.i(28)))
            b.addView(a.label(17f, C.TEXT2, F.semibold).apply { text = n }, lp(WRAP, WRAP))
            b.isClickable = true
            b.setOnClickListener { showTab(i) }
            tabBtns.add(b)
            side.addView(b, if (portrait) lp(0, S.i(56), 1f) else lp(MATCH, S.i(56)))
        }
        if (!portrait) {
            side.addView(a.spacer(), lp(MATCH, 0, 1f))
            side.addView(odo.apply { pad(8f) }, lp(MATCH, WRAP))
        }
        content.background = cardBg()
        if (portrait) {
            view.addView(side, lp(MATCH, S.i(80)))
            view.addView(content, lp(MATCH, 0, 1f))
        } else {
            view.addView(side, lp(0, MATCH, 260f))
            view.addView(content, lp(0, MATCH, 976f))
        }
        paintTabs()
    }

    fun showTab(i: Int) {
        tab = i
        routeTrip = null
        builtFor = ""
        paintTabs()
        update()
    }

    private fun paintTabs() {
        tabBtns.forEachIndexed { i, b ->
            b.background = roundBg(if (i == tab) C.CARD2 else 0, 12f)
            ((b.getChildAt(0)) as IconBtn).set(fg = if (i == tab) C.accent else C.TEXT2)
            (b.getChildAt(1) as TextView).setTextColor(if (i == tab) C.TEXT else C.TEXT2)
        }
    }

    fun update() {
        odo.put(fmt("Odometer\n%,.0f %s", Units.dist(Odometer.km * 1000), Units.distUnit))
        val sig = "$tab|${routeTrip?.start}|${Trips.version}|${FuelLog.version}|${ServiceLog.version}|${Obd.version}|" +
            "${Obd.connected}|${Parking.time}|${Units.imp}|${(Odometer.km / 10).toInt()}"
        if (sig == builtFor) return
        builtFor = sig
        content.removeAllViews()
        val v = when (tab) {
            0 -> routeTrip?.let { routeView(it) } ?: tripsView()
            1 -> fuelView()
            2 -> serviceView()
            3 -> engineView()
            else -> parkingView()
        }
        content.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    // ---------------------------------------------------------------- helpers

    private fun scroll(body: LinearLayout): View = ScrollView(a).apply {
        isFillViewport = true
        addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
    }

    private fun body(): LinearLayout = a.vbox(12f).apply { pad(24f, 20f) }

    private fun header(title: String, vararg actions: View): LinearLayout = a.hbox(10f).apply {
        addView(a.label(26f, C.TEXT, F.bold).apply { text = title }, lp(0, WRAP, 1f))
        actions.forEach { addView(it, lp(WRAP, S.i(44))) }
    }

    fun button(text: String, primary: Boolean = false, click: () -> Unit): TextView = a.label(15f, if (primary) C.ON_ACCENT else C.TEXT3, F.semibold).apply {
        this.text = text
        gravity = Gravity.CENTER
        background = roundBg(if (primary) C.accent else C.CARD2, 12f, if (primary) 0 else C.BORDER2)
        pad(18f, 0f)
        isClickable = true
        setOnClickListener { click() }
    }

    private fun tiles(vararg kv: Pair<String, String>): LinearLayout = a.hbox(10f, center = false).apply {
        kv.forEach { (k, v) ->
            val t = Tile(a, true, 24f)
            t.set(v, k)
            addView(t, lp(0, WRAP, 1f))
        }
    }

    private fun row(left: String, right: String, sub: String? = null, rightColor: Int = C.TEXT): LinearLayout {
        val r = a.hbox(12f)
        r.background = roundBg(C.CARD2, 12f)
        r.pad(16f, 12f)
        val col = a.vbox(3f)
        col.addView(a.label(16f, C.TEXT, F.semibold).apply { text = left }, lp(MATCH, WRAP))
        if (sub != null) col.addView(a.label(13f, C.MUTED, F.regular, lines = 2).apply { text = sub }, lp(MATCH, WRAP))
        r.addView(col, lp(0, WRAP, 1f))
        r.addView(a.label(16f, rightColor, F.semibold).apply { text = right }, lp(WRAP, WRAP))
        return r
    }

    private fun empty(text: String): TextView = a.label(16f, C.MUTED, F.regular, lines = 6).apply {
        this.text = text
        pad(4f, 12f)
    }

    // ---------------------------------------------------------------- trips

    private fun tripsView(): View {
        val b = body()
        val trips = Trips.list()
        b.addView(header("Trip history"), lp(MATCH, WRAP))
        val now = System.currentTimeMillis()
        val weekAgo = now - 7 * 86_400_000L
        val c = Calendar.getInstance(); c.set(Calendar.DAY_OF_MONTH, 1); c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
        val week = trips.filter { it.start >= weekAgo }
        val month = trips.filter { it.start >= c.timeInMillis }
        val scores = week.mapNotNull { s -> s.score?.let { it to s.meters } }
        val weekScore = if (scores.isEmpty()) null else (scores.sumOf { it.first * it.second } / max(1.0, scores.sumOf { it.second })).roundToInt()
        b.addView(tiles(
            "Last 7 days" to fmt("%,.0f %s", Units.dist(week.sumOf { it.meters }), Units.distUnit),
            "Driving time" to dur(week.sumOf { it.movingMs }),
            "Drive score (7 days)" to (weekScore?.let { "$it · ${DriveScore.label(it)}" } ?: "--"),
            "This month" to fmt("%,.0f %s", Units.dist(month.sumOf { it.meters }), Units.distUnit) + " · ${month.size}",
        ), lp(MATCH, WRAP))
        Trips.current?.let { cur ->
            b.addView(row("Driving now", fmt("%.1f %s", Units.dist(cur.meters), Units.distUnit),
                "Started ${Fmt.time(cur.start)} · score so far ${cur.score ?: "--"}", C.accent), lp(MATCH, WRAP))
        }
        if (trips.isEmpty()) {
            b.addView(empty("Drives are saved here automatically — each one with its route, distance and time. Nothing recorded yet."), lp(MATCH, WRAP))
        }
        var lastDay = ""
        for (t in trips.take(150)) {
            val day = Fmt.dateLong(t.start)
            if (day != lastDay) {
                lastDay = day
                b.addView(a.caption(day).apply { pad(4f, 10f, 4f, 0f) }, lp(MATCH, WRAP))
            }
            val r = row(
                "${Fmt.time(t.start)} → ${Fmt.time(t.end)}",
                fmt("%.1f %s", Units.dist(t.meters), Units.distUnit),
                "${dur(t.movingMs)} driving · avg ${fmt("%.0f", Units.speed(t.avgKmh))} · max ${fmt("%.0f", Units.speed(t.maxKmh))} ${Units.speedUnitLower}" +
                    (if (t.fuelL > 0.05) fmt(" · %.1f L", t.fuelL) else "") + (t.score?.let { " · score $it" } ?: ""),
            )
            r.isClickable = true
            r.setOnClickListener { routeTrip = t; builtFor = ""; update() }
            b.addView(r, lp(MATCH, WRAP))
        }
        return scroll(b)
    }

    private fun routeView(t: TripRecord): View {
        val b = a.vbox(12f).apply { pad(20f) }
        val back = IconBtn(a, Ic.BACK, 22f).apply { bgColor = C.CARD2; radius = 12f; contentDescription = "Back" }
        back.setOnClickListener { routeTrip = null; builtFor = ""; update() }
        val h = a.hbox(12f)
        h.addView(back, lp(S.i(44), S.i(44)))
        h.addView(a.label(22f, C.TEXT, F.bold).apply { text = "${Fmt.date(t.start)} · ${Fmt.time(t.start)} → ${Fmt.time(t.end)}" }, lp(0, WRAP, 1f))
        h.addView(button("Delete") {
            a.confirm("Delete this drive?", "It will be removed from the history.") { Trips.delete(t.start); routeTrip = null; builtFor = ""; update() }
        }, lp(WRAP, S.i(44)))
        b.addView(h, lp(MATCH, WRAP))
        val map = TileMapView(a)
        map.mode = 1
        map.showParking = false
        map.route = Trips.route(t.start).ifEmpty { listOf(doubleArrayOf(t.startLat, t.startLon), doubleArrayOf(t.endLat, t.endLon)) }
        val mapBox = FrameLayout(a).apply { background = roundBg(C.MAP_BG, 16f, C.BORDER); clipToOutline = true; addView(map, flp(MATCH, MATCH)) }
        b.addView(mapBox, lp(MATCH, 0, 1f))
        b.addView(tiles(
            "Distance" to fmt("%.1f %s", Units.dist(t.meters), Units.distUnit),
            "Driving time" to dur(t.movingMs),
            "Average" to fmt("%.0f %s", Units.speed(t.avgKmh), Units.speedUnitLower),
            "Max" to fmt("%.0f %s", Units.speed(t.maxKmh), Units.speedUnitLower),
            "Fuel" to if (t.fuelL > 0.05) fmt("%.1f L", t.fuelL) else "--",
            "Drive score" to (t.score?.let { "$it · ${DriveScore.label(it)}" } ?: "--"),
        ), lp(MATCH, WRAP))
        if (t.score != null) b.addView(a.label(15f, C.TEXT2, F.regular, lines = 2).apply { text = DriveScore.tip(t) }, lp(MATCH, WRAP))
        return b
    }

    // ---------------------------------------------------------------- fuel

    private fun tankSize() {
        val opts = listOf(45, 50, 55, 60, 65, 70, 75, 80, 90)
        a.dialog().setTitle("Fuel tank size").setItems(opts.map { "$it L" + if (it == 70) "  (2015 Sonata)" else "" }.toTypedArray()) { _, i ->
            Prefs.tankL = opts[i]; builtFor = ""; update()
        }.setNegativeButton("Cancel", null).show()
    }

    private fun fuelView(): View {
        val b = body()
        val list = FuelLog.list()
        val cur = Prefs.currency
        b.addView(header("Fuel log", button("Tank ${Prefs.tankL} L") { tankSize() }, button("Add fill-up", true) { addFuel() }), lp(MATCH, WRAP))
        val now = System.currentTimeMillis()
        val avg = FuelStats.avgL100(list)
        val cpk = FuelStats.costPerKm(list)
        val range = FuelRange.now()
        b.addView(tiles(
            "Range left" to (range?.let { FuelRange.text(it) + " " + Units.distUnit + fmt(" · %.0f%%", it.pct) } ?: "--"),
            "This month" to cur + fmt("%,.0f", FuelStats.monthCost(list, now)),
            "Average use" to (avg?.let { fmt("%.1f L/100 km", it) } ?: "--"),
            "Cost per ${Units.distUnit}" to (cpk?.let { cur + fmt("%.2f", if (Units.imp) it * 1.609344 else it) } ?: "--"),
            "Last fill-up" to (list.firstOrNull()?.let { Fmt.date(it.t) } ?: "--"),
        ), lp(MATCH, WRAP))
        if (range == null && !Obd.connected) b.addView(a.label(13f, C.MUTED, F.regular, lines = 2).apply {
            text = "Range appears after your first full-tank fill-up (or with an OBD2 adapter if the car reports its fuel level)."
        }, lp(MATCH, WRAP))
        else if (range?.approx == true) b.addView(a.label(13f, C.MUTED, F.regular, lines = 2).apply {
            text = "~ = estimate. It gets accurate after two full-tank fill-ups in the log."
        }, lp(MATCH, WRAP))
        if (list.isEmpty()) b.addView(empty("Add each fill-up (litres and price). Average use is worked out between two full-tank fill-ups."), lp(MATCH, WRAP))
        for (e in list.take(60)) {
            val r = row("${Fmt.date(e.t)} · ${fmt("%.1f L", e.liters)}", cur + fmt("%,.2f", e.cost),
                fmt("Odometer %,.0f km", e.odoKm) + (if (e.full) " · full tank" else " · partial"))
            r.isClickable = true
            r.setOnLongClickListener { a.confirm("Delete this fill-up?", "") { FuelLog.remove(e.t); builtFor = ""; update() }; true }
            b.addView(r, lp(MATCH, WRAP))
        }
        if (list.isNotEmpty()) b.addView(a.label(13f, C.MUTED, F.regular).apply { text = "Tap and hold a fill-up to delete it." }, lp(MATCH, WRAP))
        return scroll(b)
    }

    private fun addFuel() {
        a.form("Add fill-up", listOf(
            Field("Litres", "", true),
            Field("Total price (${Prefs.currency})", "", true),
            Field("Odometer (km)", fmt("%.0f", Odometer.km), true),
        ), "Full tank") { v, full ->
            val l = v[0].toDoubleOrNull(); val c = v[1].toDoubleOrNull() ?: 0.0; val o = v[2].toDoubleOrNull() ?: Odometer.km
            if (l != null && l > 0) {
                FuelLog.add(FuelEntry(System.currentTimeMillis(), l, c, o, full))
                if (abs(o - Odometer.km) > 5) Odometer.set(o)
                builtFor = ""; update()
            }
        }
    }

    // ---------------------------------------------------------------- service

    private fun serviceView(): View {
        val b = body()
        b.addView(header("Service reminders", button("Add reminder") { editService(null) }), lp(MATCH, WRAP))
        val odoKm = Odometer.km
        val odoRow = row("Odometer", fmt("%,.0f km", odoKm), "Counted by GPS. Set it to match the car's odometer once.")
        odoRow.addView(button("Set") { setOdometer() }, lp(WRAP, S.i(44)).apply { leftMargin = S.i(12) })
        b.addView(odoRow, lp(MATCH, WRAP))
        val now = System.currentTimeMillis()
        for (i in ServiceLog.list()) {
            val st = i.state(odoKm, now)
            val every = listOfNotNull(if (i.everyKm > 0) fmt("%,d km", i.everyKm) else null,
                if (i.everyMonths > 0) "${i.everyMonths} months" else null).joinToString(" or ")
            val r = row(i.name, dueText(i, odoKm, now), "Every $every · last done ${Fmt.date(i.lastT)} at ${fmt("%,.0f km", i.lastKm)}",
                when (st) { 2 -> C.WARN; 1 -> C.AMBER_TEXT; else -> C.TEXT })
            r.addView(button("Done") {
                a.confirm("Mark \"${i.name}\" as done?", "It will count again from today and the current odometer.") {
                    ServiceLog.done(i); builtFor = ""; update()
                }
            }, lp(WRAP, S.i(44)).apply { leftMargin = S.i(12) })
            r.isClickable = true
            r.setOnClickListener { editService(i) }
            b.addView(r, lp(MATCH, WRAP))
        }
        b.addView(a.label(13f, C.MUTED, F.regular, lines = 3).apply {
            text = "Starting intervals are common defaults, not Hyundai's schedule — tap a reminder to change it to your owner's manual."
        }, lp(MATCH, WRAP))
        return scroll(b)
    }

    private fun setOdometer() {
        a.form("Set odometer", listOf(Field("Odometer reading (km)", fmt("%.0f", Odometer.km), true)), null) { v, _ ->
            v[0].toDoubleOrNull()?.let { Odometer.set(it); builtFor = ""; update() }
        }
    }

    private fun editService(i: ServiceItem?) {
        a.form(if (i == null) "Add reminder" else "Edit reminder", listOf(
            Field("Name", i?.name ?: "", false),
            Field("Every … km (0 = not by distance)", (i?.everyKm ?: 10000).toString(), true),
            Field("Every … months (0 = not by time)", (i?.everyMonths ?: 12).toString(), true),
            Field("Last done at (km)", fmt("%.0f", i?.lastKm ?: Odometer.km), true),
        ), null, if (i != null) "Delete" to { ServiceLog.remove(i); builtFor = ""; update() } else null) { v, _ ->
            val name = v[0].trim().ifEmpty { "Service" }
            val km = v[1].toDoubleOrNull()?.roundToInt() ?: 0
            val mo = v[2].toDoubleOrNull()?.roundToInt() ?: 0
            val last = v[3].toDoubleOrNull() ?: Odometer.km
            if (i == null) ServiceLog.add(ServiceItem(name, km, mo, last, System.currentTimeMillis()))
            else { i.name = name; i.everyKm = km; i.everyMonths = mo; i.lastKm = last; ServiceLog.save() }
            builtFor = ""; update()
        }
    }

    // ---------------------------------------------------------------- engine

    private fun engineView(): View {
        val b = body()
        b.addView(header("Check engine",
            button("Read codes", true) { if (Obd.connected) Obd.requestReadCodes() else a.show(MainActivity.Page.SOURCES) },
            button("Clear codes") {
                if (Obd.connected) a.confirm("Clear trouble codes?",
                    "This turns off the check-engine light and resets the car's emission self-tests. Only clear codes after the problem is fixed.") { Obd.requestClearCodes() }
            }), lp(MATCH, WRAP))
        if (!Obd.connected) {
            b.addView(empty("Connect an OBD2 adapter to read the car's trouble codes. Status: ${Obd.detail}"), lp(MATCH, WRAP))
            return scroll(b)
        }
        val light = when (Obd.mil) { true -> "ON"; false -> "Off"; null -> "--" }
        b.addView(tiles("Check-engine light" to light, "Stored codes" to "${Obd.dtcStored.size}", "Pending codes" to "${Obd.dtcPending.size}",
            "Last checked" to (if (Obd.dtcAt > 0) Fmt.time(Obd.dtcAt) else "--")), lp(MATCH, WRAP))
        b.addView(a.label(14f, C.MUTED, F.regular).apply { text = Obd.dtcStatus }, lp(MATCH, WRAP))
        for (c in Obd.dtcStored) b.addView(row(c, "Stored", DtcInfo.describe(c), C.WARN), lp(MATCH, WRAP))
        for (c in Obd.dtcPending) b.addView(row(c, "Pending", DtcInfo.describe(c), C.AMBER_TEXT), lp(MATCH, WRAP))
        if (Obd.dtcStored.isEmpty() && Obd.dtcPending.isEmpty() && Obd.dtcAt > 0)
            b.addView(empty("No trouble codes."), lp(MATCH, WRAP))
        b.addView(a.label(13f, C.MUTED, F.regular, lines = 3).apply {
            text = "Descriptions are the standard generic meanings. Codes starting P1 are Hyundai-specific; a workshop can look them up."
        }, lp(MATCH, WRAP))
        return scroll(b)
    }

    // ---------------------------------------------------------------- parking

    private fun parkingView(): View {
        val b = a.vbox(12f).apply { pad(24f, 20f) }
        if (!Parking.has) {
            b.addView(header("Parking spot"), lp(MATCH, WRAP))
            b.addView(empty("When the car is switched off, the spot is saved here and marked P on the map."), lp(MATCH, WRAP))
            return b
        }
        val open = button("Open in Maps", true) {
            val u = Uri.parse(fmt("geo:%.6f,%.6f?q=%.6f,%.6f(Parked car)", Parking.lat, Parking.lon, Parking.lat, Parking.lon))
            try { a.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }
        b.addView(header("Parking spot", open), lp(MATCH, WRAP))
        b.addView(a.label(16f, C.TEXT2, F.regular).apply {
            text = "Parked ${Fmt.dateLong(Parking.time)} at ${Fmt.time(Parking.time)} · " + fmt("%.5f, %.5f", Parking.lat, Parking.lon)
        }, lp(MATCH, WRAP))
        val map = TileMapView(a)
        map.mode = 2
        map.zoom = 16
        map.fixed = doubleArrayOf(Parking.lat, Parking.lon)
        val box = FrameLayout(a).apply { background = roundBg(C.MAP_BG, 16f, C.BORDER); clipToOutline = true; addView(map, flp(MATCH, MATCH)) }
        b.addView(box, lp(MATCH, 0, 1f))
        return b
    }

    private fun dur(ms: Long): String {
        val m = ms / 60000
        return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
    }

    companion object {
        fun dueText(i: ServiceItem, odo: Double, now: Long): String {
            val parts = ArrayList<String>()
            i.kmLeft(odo)?.let { k -> parts.add(if (k <= 0) fmt("%,.0f km overdue", -k) else fmt("in %,.0f km", k)) }
            i.daysLeft(now)?.let { d ->
                parts.add(when {
                    d <= 0 -> "${-d} days overdue"
                    d < 60 -> "in $d days"
                    else -> "in ${d / 30} months"
                })
            }
            return parts.joinToString(" or ").ifEmpty { "--" }
        }
    }
}
