package app.drivelauncher

import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** Engine data from the OBD2 adapter + big music player. */
class TelemetryPage(private val a: MainActivity, portrait: Boolean) {
    val view: LinearLayout

    private class Row(val box: LinearLayout, val name: TextView, val value: TextView, val unit: TextView, val bar: Bar)

    private data class Item(val key: String, val label: String, val unit: String, val min: Float, val max: Float, val format: String)

    private val items = listOf(
        Item("speed", "Speed", "km/h", 0f, 200f, "%.0f"),
        Item("rpm", "RPM", "rpm", 0f, 7000f, "%,.0f"),
        Item("coolant", "Coolant", "°C", 40f, 120f, "%.0f"),
        Item("iat", "Intake air", "°C", -20f, 80f, "%.0f"),
        Item("maf", "Mass air flow", "g/s", 0f, 150f, "%.1f"),
        Item("throttle", "Throttle", "%", 0f, 100f, "%.0f"),
        Item("load", "Engine load", "%", 0f, 100f, "%.0f"),
        Item("map", "Intake pressure", "kPa", 0f, 250f, "%.0f"),
        Item("fuel", "Fuel level", "%", 0f, 100f, "%.0f"),
        Item("volt", "Battery", "V", 10f, 16f, "%.1f"),
        Item("oil", "Oil temp", "°C", 40f, 150f, "%.0f"),
    )

    private val listCard: LinearLayout = a.vbox()
    private val status: TextView = a.label(12f, C.TEXT2, F.semibold)
    private val rows = ArrayList<Row>()
    private val empty: LinearLayout = a.vbox(12f)
    private val emptyText: TextView = a.label(16f, C.TEXT2, F.regular, lines = 6)
    private val g = Array(4) { GaugeTile(a, true) }
    private val media = MediaCard(a, true)

    init {
        view = if (portrait) a.vbox(12f) else a.hbox(12f, center = false)
        listCard.background = cardBg()
        listCard.pad(22f, 18f)
        val head = a.hbox()
        head.addView(a.caption("Live telemetry"), lp(0, WRAP, 1f))
        head.addView(status, lp(WRAP, WRAP))
        listCard.addView(head, lp(MATCH, S.i(32)))
        for (k in 0 until 7) {
            val box = a.vbox(8f).apply { gravity = Gravity.CENTER_VERTICAL }
            val top = a.hbox().apply { gravity = Gravity.BOTTOM }
            val name = a.label(14f, C.MUTED, F.semibold, caps = true)
            val value = a.label(34f, C.TEXT, F.numeric).tnum()
            val unit = a.label(14f, C.MUTED, F.regular)
            top.addView(name, lp(0, WRAP, 1f).apply { bottomMargin = S.i(6) })
            top.addView(value, lp(WRAP, WRAP))
            top.addView(unit, lp(WRAP, WRAP).apply { leftMargin = S.i(4); bottomMargin = S.i(6) })
            val bar = Bar(a)
            box.addView(top, lp(MATCH, WRAP))
            box.addView(bar, lp(MATCH, S.i(4)))
            box.setBackgroundColor(0)
            listCard.addView(box, lp(MATCH, 0, 1f))
            rows.add(Row(box, name, value, unit, bar))
        }
        empty.gravity = Gravity.CENTER_VERTICAL
        val plug = IconBtn(a, Ic.PLUG, 40f).apply { isClickable = false; fg = C.MUTED }
        empty.addView(plug, lp(S.i(64), S.i(64)))
        empty.addView(a.label(22f, C.TEXT, F.semibold).apply { text = "Connect an OBD2 adapter" }, lp(MATCH, WRAP))
        empty.addView(emptyText, lp(MATCH, WRAP))
        val setup = a.label(15f, C.TEXT3, F.semibold).apply {
            text = "Set up adapter"
            gravity = Gravity.CENTER
            background = roundBg(C.CARD2, 12f, C.BORDER2)
            pad(16f, 0f)
            isClickable = true
            setOnClickListener { a.show(MainActivity.Page.SOURCES) }
        }
        empty.addView(setup, lp(WRAP, S.i(44)))
        listCard.addView(empty, lp(MATCH, 0, 7f))

        val grid = a.vbox(12f)
        val r1 = a.hbox(12f, center = false)
        val r2 = a.hbox(12f, center = false)
        r1.addView(g[0], lp(0, MATCH, 1f)); r1.addView(g[1], lp(0, MATCH, 1f))
        r2.addView(g[2], lp(0, MATCH, 1f)); r2.addView(g[3], lp(0, MATCH, 1f))
        grid.addView(r1, lp(MATCH, 0, 1f)); grid.addView(r2, lp(MATCH, 0, 1f))

        if (portrait) {
            view.addView(grid, lp(MATCH, 0, 430f))
            view.addView(listCard, lp(MATCH, 0, 420f))
            view.addView(media.view, lp(MATCH, 0, 280f))
        } else {
            view.addView(listCard, lp(0, MATCH, 400f))
            view.addView(grid, lp(0, MATCH, 448f))
            view.addView(media.view, lp(0, MATCH, 376f))
        }
    }

    fun update() {
        val on = Obd.connected
        status.put(if (on) "OBD2 · ${Obd.adapterName}" else "OBD2 · not connected")
        status.setTextColor(if (on) C.BLUE_TEXT else C.MUTED)
        empty.showIf(!on)
        val avail = if (on) items.filter { Obd.values.containsKey(it.key) }.take(7) else emptyList()
        for ((k, r) in rows.withIndex()) {
            val it = avail.getOrNull(k)
            r.box.showIf(it != null)
            if (it == null) continue
            var v = Obd.values[it.key] ?: continue
            var unit = it.unit
            var max = it.max
            if (it.key == "speed") { v = Units.speed(v); unit = Units.speedUnitLower; if (Units.imp) max = 120f }
            r.name.put(it.label)
            r.value.put(fmt(it.format, v))
            r.unit.put(unit)
            r.bar.frac = (v - it.min) / (max - it.min)
            r.bar.color = if ((it.key == "coolant" && v >= 108f) || (it.key == "volt" && (v < 11.8f || v > 15.2f))) C.WARN else 0
        }
        emptyText.put(Obd.detail.let { d ->
            "Status: $d\n\nPlug a Bluetooth (or Wi-Fi) ELM327 OBD2 adapter into the port under the dashboard, " +
                "pair it with this unit, and gauges appear here by themselves."
        })

        val map = Obd.v("map")
        val load = Obd.v("load")
        if (map != null || load == null) g[0].set("Intake pressure", (map ?: 0f) / 250f, map?.let { fmt("%.0f", it) } ?: "--", "kPa", "0 – 250 kPa")
        else g[0].set("Engine load", load / 100f, fmt("%.0f", load), "%", "0 – 100 %")
        val rpm = Obd.v("rpm")
        g[1].set("RPM", (rpm ?: 0f) / 7000f, rpm?.let { fmt("%,d", it.roundToInt()) } ?: "--", "rpm", "0 – 7,000")
        val v = Obd.v("volt")
        g[2].set("Battery", ((v ?: 10f) - 10f) / 6f, v?.let { fmt("%.1f", it) } ?: "--", "V", "10 – 16 V", warn = v != null && (v < 11.8f || v > 15.2f))
        val eco = Obd.economy()
        val fuel = Obd.v("fuel")
        if (eco != null) g[3].set("Fuel economy", eco / 20f, fmt("%.1f", eco), "L/100 km", "This trip · from MAF")
        else if (fuel != null) g[3].set("Fuel level", fuel / 100f, fmt("%.0f", fuel), "%", "0 – 100 %")
        else {
            val ct = Obd.v("coolant")
            g[3].set("Coolant", ((ct ?: 40f) - 40f) / 80f, ct?.let { fmt("%.0f", it) } ?: "--", "°C", "40 – 120 °C", warn = ct != null && ct >= 108f)
        }
        media.update()
    }
}
