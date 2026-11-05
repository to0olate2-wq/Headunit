package app.drivelauncher

import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max

/** All installed apps. */
class AppsPage(private val a: MainActivity, widthPx: Int) {
    val view: LinearLayout = a.vbox(12f)
    private val grid = GridView(a)
    private val count: TextView = a.label(14f, C.MUTED, F.regular)
    private var list: List<ResolveInfo> = emptyList()

    private val adapter = object : BaseAdapter() {
        override fun getCount() = list.size
        override fun getItem(position: Int): Any = list[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val cell = (convertView as? LinearLayout) ?: a.vbox(8f).apply {
                gravity = Gravity.CENTER
                background = roundBg(C.CARD, 18f, C.BORDER)
                pad(8f, 12f)
                addView(ImageView(a), lp(S.i(56), S.i(56)))
                addView(a.label(13f, C.TEXT2, F.medium, lines = 2).apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP))
                layoutParams = AbsListView.LayoutParams(MATCH, S.i(128))
            }
            val ri = list[position]
            (cell.getChildAt(0) as ImageView).setImageDrawable(Apps.icon(a, ri))
            (cell.getChildAt(1) as TextView).text = ri.loadLabel(a.packageManager)
            return cell
        }
    }

    init {
        val head = a.hbox(12f)
        head.addView(a.label(32f, C.TEXT, F.bold).apply { text = "Apps" }, lp(WRAP, WRAP))
        head.addView(count, lp(0, WRAP, 1f))
        view.addView(head, lp(MATCH, WRAP).apply { leftMargin = S.i(8) })
        val cols = max(3, (widthPx - S.i(32)) / S.i(136))
        grid.numColumns = cols
        grid.horizontalSpacing = S.i(12)
        grid.verticalSpacing = S.i(12)
        grid.stretchMode = GridView.STRETCH_COLUMN_WIDTH
        grid.selector = ColorDrawable(0)
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, pos, _ -> Apps.launch(a, list[pos]) }
        grid.setOnItemLongClickListener { _, _, pos, _ ->
            try {
                a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", list[pos].activityInfo.packageName, null)))
            } catch (_: Exception) {}
            true
        }
        view.addView(grid, lp(MATCH, 0, 1f))
    }

    fun refresh() {
        list = Apps.launchables(a)
        count.put("${list.size} apps · long-press for app info")
        adapter.notifyDataSetChanged()
    }
}

/** Bottom bar: home, volume, pages, status, shortcuts, clock. */
class Dock(private val a: MainActivity, portrait: Boolean) {
    val view: LinearLayout = a.hbox(4f)
    private val home = btn(Ic.HOME, "Home") { a.show(MainActivity.Page.DRIVE) }
    private val tele = btn(Ic.GAUGE, "Engine data") { a.show(MainActivity.Page.TELEMETRY) }
    private val apps = btn(Ic.GRID, "All apps") { a.show(MainActivity.Page.APPS) }
    private val settings = btn(Ic.SLIDERS, "Data sources and settings") { a.show(MainActivity.Page.SOURCES) }
    private val vol = Bar(a)
    private val gpsDot: TextView = status("GPS")
    private val obdDot: TextView = status("OBD")
    private val extra: TextView = a.label(13f, C.MUTED, F.regular).tnum()
    private val clock: TextView = a.label(18f, C.TEXT, F.semibold).tnum()
    private var lastPage: MainActivity.Page? = null

    private fun btn(ic: Ic, desc: String, click: () -> Unit) = IconBtn(a, ic, 24f).apply {
        contentDescription = desc
        setOnClickListener { click() }
    }

    private fun status(name: String): TextView = a.label(13f, C.TEXT2, F.semibold).apply {
        text = name
        compoundDrawablePadding = S.i(6)
    }

    private fun dot(tv: TextView, on: Boolean) {
        val tag = if (on) "on" else "off"
        if (tv.tag == tag) return
        tv.tag = tag
        val d = roundBg(if (on) C.BLUE else C.TRACK, 999f)
        d.setBounds(0, 0, S.i(8), S.i(8))
        tv.setCompoundDrawablesRelative(d, null, null, null)
    }

    init {
        view.background = roundBg(C.CARD, 18f, C.BORDER)
        view.pad(8f, 0f)
        val size = S.i(48)
        fun add(v: View) = view.addView(v, lp(size, size))
        add(home)
        if (!portrait) view.addView(a.divider())
        add(btn(Ic.MINUS, "Volume down") { Vol.adjust(a, -1); a.nudge() })
        if (!portrait) view.addView(vol, lp(S.i(72), S.i(4)))
        add(btn(Ic.PLUS, "Volume up") { Vol.adjust(a, 1); a.nudge() })
        if (!portrait) view.addView(a.divider())
        add(apps)
        add(tele)
        if (!portrait) {
            view.addView(a.divider())
            val st = a.hbox(16f)
            st.pad(8f, 0f)
            st.addView(gpsDot, lp(WRAP, WRAP)); st.addView(obdDot, lp(WRAP, WRAP)); st.addView(extra, lp(WRAP, WRAP))
            view.addView(st, lp(WRAP, WRAP))
        }
        view.addView(a.spacer(), lp(0, 1, 1f))
        add(btn(Ic.MIC, "Voice assistant") { a.voice() })
        if (!portrait) view.addView(a.divider())
        Apps.findZlink(a)?.let { z -> add(btn(Ic.LINK, "ZLink") { Apps.launch(a, z) }) }
        add(btn(Ic.PIN, "Maps") { a.openNav() })
        add(btn(Ic.MUSIC, "Music app") { MediaHub.openSourceApp(a) })
        add(settings)
        if (!portrait) view.addView(a.divider())
        view.addView(clock, lp(WRAP, WRAP).apply { leftMargin = S.i(6); rightMargin = S.i(12) })
    }

    fun update(page: MainActivity.Page, time: String) {
        if (page != lastPage) {
            lastPage = page
            for ((b, p) in listOf(home to MainActivity.Page.DRIVE, tele to MainActivity.Page.TELEMETRY,
                apps to MainActivity.Page.APPS, settings to MainActivity.Page.SOURCES)) {
                b.set(fg = if (p == page) C.accent else C.TEXT2, bg = if (p == page) C.CARD2 else 0)
            }
        }
        vol.frac = Vol.frac(a)
        dot(gpsDot, Gps.fresh)
        dot(obdDot, Obd.connected)
        val v = Obd.v("volt")
        val m = Obd.v("map")
        extra.put(when {
            m != null -> fmt("Intake %.0f kPa", m)
            v != null -> fmt("Battery %.1f V", v)
            else -> ""
        })
        clock.put(time)
    }
}
