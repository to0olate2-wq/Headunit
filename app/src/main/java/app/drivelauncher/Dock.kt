package app.drivelauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Bottom bar: home, volume, pages, status, your app buttons, settings, clock. */
class Dock(private val a: MainActivity, portrait: Boolean) {
    val view: LinearLayout = a.hbox(4f)
    private val home = btn(Ic.HOME, "Home", HOME) { a.showHome(a.homeIdx) }
    private val tele = btn(Ic.GAUGE, "Engine data", ENGINE) { a.show(MainActivity.Page.TELEMETRY) }
    private val car = btn(Ic.CAR, "Car care", CAR) { a.show(MainActivity.Page.CAR) }
    private val apps = btn(Ic.GRID, "All apps", GREY) { a.show(MainActivity.Page.APPS) }
    private val settings = btn(Ic.SLIDERS, "Settings and data sources", STEEL) { a.show(MainActivity.Page.SOURCES) }
    private val vol = Bar(a)
    private val gpsDot: TextView = status("GPS")
    private val obdDot: TextView = status("OBD")
    private val extra: TextView = a.label(13f, C.MUTED, F.regular).tnum()
    private val dots = Dots(a)
    private val clock: TextView = a.label(when (Skin.id) { Skin.SPORT -> 24f; Skin.LUX -> 26f; Skin.GLASS -> 20f; else -> 18f }, C.TEXT,
        when (Skin.id) { Skin.SPORT, Skin.NEON, Skin.LUX -> F.numeric; Skin.GLASS -> F.medium; else -> F.semibold }).tnum()
    private var lastKey = ""
    private val clockSep: View = a.divider()

    companion object {
        // Aurora Glass app tile colours (top-left, bottom-right)
        val HOME = intArrayOf(0, 0) // accent gradient, filled in at build time
        val MAPS = intArrayOf(0xFF3DD6C4.toInt(), 0xFF1E7FD8.toInt())
        val MUSIC = intArrayOf(0xFFFF6FA8.toInt(), 0xFF9B3FE0.toInt())
        val LINK = intArrayOf(0xFF5AC8FA.toInt(), 0xFF2C6BED.toInt())
        val SPLIT = intArrayOf(0xFF8C7CFF.toInt(), 0xFF4B3FD6.toInt())
        val ENGINE = intArrayOf(0xFFFF8A5C.toInt(), 0xFFE0415A.toInt())
        val CAR = intArrayOf(0xFFFFC15E.toInt(), 0xFFF2672E.toInt())
        val GREY = intArrayOf(0xFF8E9AAF.toInt(), 0xFF485264.toInt())
        val STEEL = intArrayOf(0xFFA7AEBA.toInt(), 0xFF535A66.toInt())
    }

    /** Dock button styled for the theme: colourful tile (Aurora Glass), angled panel (Carbon Sport) or plain icon. */
    private fun btn(ic: Ic, desc: String, colors: IntArray? = null, click: () -> Unit) = IconBtn(a, ic, if (Skin.glowy && colors != null) 26f else 24f).apply {
        contentDescription = desc
        setOnClickListener { click() }
        when {
            Skin.glowy && colors != null -> {
                tile = if (colors === HOME) intArrayOf(C.accent, Skin.accent2) else colors
                fg = C.ON_GRAD; sw = 2f
            }
            Skin.glowy -> fg = C.TEXT3
            Skin.lux -> { jewel = true; fg = C.TEXT2 }
            Skin.sport -> chamfer = true
        }
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
        when {
            Skin.glass -> { view.background = GlassBg(C.CARD, 28f); view.pad(12f, 0f) }
            Skin.neon -> { view.background = NeonBg(C.CARD, 24f); view.pad(14f, 0f) }
            Skin.lux -> { view.background = LuxBg(C.CARD, 22f); view.pad(14f, 0f) }
            Skin.sport -> view.pad(0f)
            else -> { view.background = roundBg(C.CARD, 18f, C.BORDER); view.pad(8f, 0f) }
        }
        if (Skin.id != Skin.CLASSIC) view.dividerDrawable = GradientDrawable().apply { setSize(S.i(5), S.i(5)) }
        val size = S.i(if (Skin.id == Skin.CLASSIC) 48 else 52)
        fun add(v: View) = view.addView(v, lp(size, size))
        add(home)
        if (!portrait) view.addView(a.divider())
        add(btn(Ic.MINUS, "Volume down") { Vol.adjust(a, -1); a.nudge() })
        if (!portrait) view.addView(vol, lp(S.i(64), S.i(4)))
        add(btn(Ic.PLUS, "Volume up") { Vol.adjust(a, 1); a.nudge() })
        if (!portrait) view.addView(a.divider())
        add(apps)
        add(tele)
        add(car)
        if (!portrait) {
            view.addView(a.divider())
            val st = a.hbox(14f)
            st.pad(6f, 0f)
            st.addView(gpsDot, lp(WRAP, WRAP)); st.addView(obdDot, lp(WRAP, WRAP))
            if (Skin.id == Skin.CLASSIC) st.addView(extra, lp(WRAP, WRAP))
            view.addView(st, lp(WRAP, WRAP))
        }
        view.addView(a.spacer(), lp(0, 1, 1f))
        view.addView(dots, lp(WRAP, size))
        dots.setOnClickListener { a.nextHome() }
        view.addView(a.spacer(), lp(0, 1, 1f))
        if (!portrait) add(btn(Ic.MIC, "Voice assistant") { a.voice() })
        if (!portrait) view.addView(a.divider())
        for (slot in 0 until (if (portrait) 2 else 4)) slotButton(slot)?.let { add(it) }
        add(settings)
        if (!portrait) view.addView(clockSep)
        view.addView(clock, lp(WRAP, WRAP).apply { leftMargin = S.i(6); rightMargin = S.i(if (Skin.id == Skin.CLASSIC) 12 else 8) })
    }

    /** One of the owner's app buttons. Tap and hold to change it. */
    private fun slotButton(slot: Int): IconBtn? {
        val v = Prefs.gs("dock$slot", "")
        val b: IconBtn = when {
            v == "none" -> return null
            v.isNotEmpty() -> {
                if (!Apps.canLaunch(a, v)) return null
                btn(Ic.GRID, Apps.label(a, v)) { Apps.launch(a, v) }.apply {
                    drawable = Apps.iconFor(a, v)
                    bgColor = 0
                }
            }
            slot == 0 -> { val z = Apps.findZlink(a) ?: return null; btn(Ic.LINK, "ZLink", LINK) { Apps.launch(a, z) } }
            slot == 1 -> btn(Ic.PIN, "Maps", MAPS) { a.openNav() }
            slot == 2 -> btn(Ic.MUSIC, "Music app", MUSIC) { MediaHub.openSourceApp(a) }
            else -> btn(Ic.SPLIT, "Split screen with Maps", SPLIT) { a.splitScreen() }
        }
        b.setOnLongClickListener {
            a.chooseApp("Dock button ${slot + 1}", listOf("Default" to "", "Empty" to "none")) { pkg ->
                Prefs.ss("dock$slot", pkg)
                a.rebuild()
            }
            true
        }
        return b
    }

    fun update(page: MainActivity.Page, time: String) {
        val key = "$page|${a.homeIdx}|${a.homeCount}"
        if (key != lastKey) {
            lastKey = key
            for ((b, p) in listOf(home to MainActivity.Page.HOME, tele to MainActivity.Page.TELEMETRY, car to MainActivity.Page.CAR,
                apps to MainActivity.Page.APPS, settings to MainActivity.Page.SOURCES)) {
                when {
                    Skin.glowy -> b.ring = p == page
                    Skin.lux -> { b.ring = p == page; b.set(fg = if (p == page) C.ON_ACCENT else C.TEXT2) }
                    Skin.sport -> { b.underline = if (p == page) C.accent else 0; b.set(fg = if (p == page) C.accent else C.TEXT2) }
                    else -> b.set(fg = if (p == page) C.accent else C.TEXT2, bg = if (p == page) C.CARD2 else 0)
                }
            }
            dots.set(a.homeCount, if (page == MainActivity.Page.HOME) a.homeIdx else -1)
        }
        val due = ServiceLog.dueCount() > 0 || Obd.mil == true
        car.badge = if (due && Skin.id != Skin.CLASSIC) C.WARN else 0
        if (!Skin.glowy && !Skin.lux) car.set(icon = Ic.CAR, fg = if (page == MainActivity.Page.CAR) C.accent else if (due) C.AMBER_TEXT else C.TEXT2)
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
        clock.showIf(time.isNotEmpty())
        clockSep.showIf(time.isNotEmpty())
    }
}

/** Page dots for the home screens. */
class Dots(ctx: Context) : View(ctx) {
    private var n = 1
    private var cur = 0
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    init { isClickable = true; contentDescription = "Next home page" }

    fun set(count: Int, current: Int) {
        if (count != n || current != cur) { n = count; cur = current; requestLayout(); invalidate() }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = if (n <= 1) 0 else S.i(16 * n + 16)
        setMeasuredDimension(w, View.getDefaultSize(S.i(48), heightMeasureSpec))
    }

    override fun onDraw(c: Canvas) {
        if (n <= 1) return
        val cy = height / 2f
        for (i in 0 until n) {
            p.color = if (i == cur) C.accent else C.TRACK
            c.drawCircle(S.p(16f) + i * S.p(16f), cy, S.p(if (i == cur) 5f else 4f), p)
        }
    }
}
