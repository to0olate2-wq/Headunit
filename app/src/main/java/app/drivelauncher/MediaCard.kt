package app.drivelauncher

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * "Now playing" card. Small on the Drive screen, big (with up-next list) on Telemetry.
 * [roomy]: larger album art (big home slots). Aurora Glass tints the card with the album art's colour.
 */
class MediaCard(private val a: MainActivity, private val big: Boolean, private val roomy: Boolean = false) : Card {
    override val view: LinearLayout = a.vbox(if (big) 14f else 12f)
    private val art = ArtView(a)
    private val kicker: TextView = a.label(11f, when {
        Skin.sport || Skin.lux -> C.accent
        Skin.glowy && C.night -> Skin.blend(Skin.accent2, 0xFFFFFFFF.toInt(), 0.5f)
        Skin.glowy -> Skin.blend(Skin.accent2, 0xFF000000.toInt(), 0.25f)
        else -> C.MUTED
    }, F.semibold, caps = true)
    private val bg = cardBg()
    private var artShown: android.graphics.Bitmap? = null
    private var tinted = false
    private val title: TextView = a.label(if (big) 20f else 18f, C.TEXT, F.semibold, lines = if (big || roomy) 2 else 1)
    private val sub: TextView = a.label(if (big) 15f else 14f, C.MUTED, F.regular)
    private val src: TextView = a.label(12f, C.MUTED, F.regular)
    private val bar = Bar(a)
    private val tPos: TextView = a.label(12f, C.MUTED, F.regular).tnum()
    private val tDur: TextView = a.label(12f, C.MUTED, F.regular).tnum()
    private val timeRow: LinearLayout = a.hbox()
    private val play = IconBtn(a, Ic.PLAY, if (big) 26f else 24f).apply {
        circle = true; sw = 2f
        grad = Skin.glowy || Skin.lux; chamfer = Skin.sport
    }
    private val prev = IconBtn(a, Ic.PREV, if (big) 26f else 24f).apply { fg = C.TEXT3 }
    private val next = IconBtn(a, Ic.NEXT, if (big) 26f else 24f).apply { fg = C.TEXT3 }
    private val srcBtn = IconBtn(a, Ic.SOURCE, 22f)
    private val openBtn = IconBtn(a, Ic.OPEN, 22f)
    private val queueHead: TextView = a.caption("Up next")
    private val queueBox: LinearLayout = a.vbox(4f)
    private val queueRows = ArrayList<Pair<TextView, TextView>>()
    private val bigArt = ArtView(a).apply { fitSquare = true }
    private var shown = -1

    init {
        view.background = bg
        view.pad(if (big) 20f else if (Skin.glowy || Skin.lux) 20f else 16f)
        val artSize = if (big) 112f else if (roomy) 176f else 64f
        val head = a.hbox(if (big) 16f else if (roomy) 18f else 14f)
        head.addView(art, lp(S.i(artSize), S.i(artSize)))
        val col = a.vbox(if (big || roomy) 4f else 2f)
        if (roomy && !big && Skin.id != Skin.CLASSIC) col.addView(kicker, lp(MATCH, WRAP).apply { bottomMargin = S.i(2) })
        if (roomy && !big) title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, S.p(22f))
        col.addView(title, lp(MATCH, WRAP))
        col.addView(sub, lp(MATCH, WRAP))
        if (big) col.addView(src, lp(MATCH, WRAP).apply { topMargin = S.i(4) })
        head.addView(col, lp(0, WRAP, 1f))
        view.addView(head, lp(MATCH, WRAP))

        if (roomy && !big) view.addView(a.spacer(), lp(MATCH, 0, 1f))
        val prog = a.vbox(6f)
        bar.knob = Skin.id != Skin.CLASSIC
        prog.addView(bar, lp(MATCH, S.i(if (bar.knob) 12 else 4)))
        timeRow.addView(tPos, lp(WRAP, WRAP))
        timeRow.addView(a.spacer(), lp(0, 1, 1f))
        timeRow.addView(tDur, lp(WRAP, WRAP))
        prog.addView(timeRow, lp(MATCH, WRAP))
        view.addView(prog, lp(MATCH, WRAP))

        if (!big && !roomy) view.addView(a.spacer(), lp(MATCH, 0, 1f))
        val ctl = a.hbox()
        val b = if (big) 52 else 48
        val pb = if (big) 64 else if (Skin.glowy || Skin.lux) 68 else 56
        if (Skin.id != Skin.CLASSIC) for (x in listOf(prev, next)) {
            if (Skin.sport) x.chamfer = true else if (Skin.lux) x.jewel = true else { x.bgColor = C.CARD2; x.radius = 16f }
        }
        fun add(v: View, size: Int, desc: String) {
            v.contentDescription = desc
            ctl.addView(v, lp(S.i(size), S.i(size)))
        }
        add(srcBtn, 48, "Switch music source")
        ctl.addView(a.spacer(), lp(0, 1, 1f))
        add(prev, b, "Previous track")
        ctl.addView(a.spacer(), lp(0, 1, 1f))
        add(play, pb, "Play or pause")
        ctl.addView(a.spacer(), lp(0, 1, 1f))
        add(next, b, "Next track")
        ctl.addView(a.spacer(), lp(0, 1, 1f))
        add(openBtn, 48, "Open music app")
        view.addView(ctl, lp(MATCH, WRAP))

        if (big) {
            view.addView(queueHead, lp(MATCH, WRAP).apply { topMargin = S.i(4) })
            for (k in 0 until 6) {
                val row = a.hbox(14f)
                row.background = roundBg(if (k == 0) C.CARD2 else 0, 12f)
                row.pad(14f, 0f)
                val num = a.label(13f, C.MUTED, F.regular).tnum().apply { text = (k + 1).toString() }
                val c2 = a.vbox()
                val t = a.label(15f, C.TEXT, F.semibold)
                val s = a.label(12f, C.MUTED, F.regular)
                c2.addView(t, lp(MATCH, WRAP)); c2.addView(s, lp(MATCH, WRAP))
                row.addView(num, lp(S.i(16), WRAP)); row.addView(c2, lp(0, WRAP, 1f))
                queueBox.addView(row, lp(MATCH, S.i(52)))
                queueRows.add(t to s)
            }
            val area = android.widget.FrameLayout(a)
            area.addView(queueBox, flp(MATCH, WRAP))
            area.addView(bigArt, flp(MATCH, MATCH))
            view.addView(area, lp(MATCH, 0, 1f))
        }

        play.setOnClickListener { MediaHub.playPause(a); a.nudge() }
        prev.setOnClickListener { MediaHub.prev(a); a.nudge() }
        next.setOnClickListener { MediaHub.next(a); a.nudge() }
        openBtn.setOnClickListener { MediaHub.openSourceApp(a) }
        srcBtn.setOnClickListener {
            val l = MediaHub.cycleSource(a)
            Toast.makeText(a, "Music source: $l", Toast.LENGTH_SHORT).show()
        }
        view.setOnClickListener { if (!MediaHub.accessGranted) a.show(MainActivity.Page.SOURCES) }
    }

    override fun update() {
        val t = MediaHub.current
        val icon = if (t?.playing == true) Ic.PAUSE else Ic.PLAY
        when {
            Skin.glowy -> play.set(fg = C.ON_GRAD, icon = icon)
            Skin.lux -> play.set(fg = C.ON_ACCENT, icon = icon)
            Skin.sport -> play.set(fg = C.ON_GRAD, bg = C.accent, icon = icon)
            else -> play.set(fg = C.ON_ACCENT, bg = C.accent, icon = icon)
        }
        if (MediaHub.version != shown) {
            shown = MediaHub.version
            if (t == null) {
                kicker.put("Music")
                title.put("Nothing playing")
                sub.put(if (MediaHub.accessGranted) "Play music on your phone (ZLink) or any app" else "Tap to allow music info (notification access)")
                src.put("")
                art.set(null)
            } else {
                title.put(t.title)
                val from = MediaHub.sourceName(a, t)
                sub.put(if (big) t.artist.ifEmpty { from } else listOf(t.artist, from).filter { it.isNotEmpty() }.joinToString(" · "))
                src.put("Playing from $from")
                kicker.put("Now playing · $from")
                art.set(t.art)
            }
            tint(t?.art)
            if (big) {
                val q = MediaHub.queue()
                queueHead.showIf(q.isNotEmpty())
                queueBox.showIf(q.isNotEmpty())
                // no queue shared: use the space for large album art instead of the small one
                bigArt.showIf(q.isEmpty())
                art.showIf(q.isNotEmpty())
                bigArt.set(t?.art)
                for ((k, row) in queueRows.withIndex()) {
                    val item = q.getOrNull(k)
                    (row.first.parent.parent as View).showIf(item != null)
                    if (item != null) { row.first.put(item.first); row.second.put(item.second) }
                }
            }
        }
        if (t != null && t.duration > 0) {
            val pos = t.positionNow().coerceIn(0, t.duration)
            bar.frac = pos.toFloat() / t.duration
            tPos.put(time(pos)); tDur.put(time(t.duration))
            timeRow.showIf(true)
        } else {
            bar.frac = if (t != null) 0f else 0f
            timeRow.showIf(false)
        }
    }

    /** Aurora Glass: a soft glow in the album art's colour behind the card. */
    private fun tint(b: android.graphics.Bitmap?) {
        if ((tinted && b === artShown) || bg !is GlassBg) return
        artShown = b; tinted = true
        bg.glow = if (b == null || b.isRecycled) Skin.accent2 else artColor(b)
        bg.invalidateSelf()
    }

    private fun time(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) fmt("%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60) else fmt("%d:%02d", s / 60, s % 60)
    }
}

/** Lively average colour of a picture (for glows). */
fun artColor(b: android.graphics.Bitmap): Int = try {
    val s = android.graphics.Bitmap.createScaledBitmap(b, 8, 8, true)
    var r = 0L; var g = 0L; var bl = 0L; var n = 0L
    for (x in 0 until 8) for (y in 0 until 8) {
        val c = s.getPixel(x, y)
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(c, hsv)
        val wgt = (1 + hsv[1] * 4 * hsv[2]).toLong() // colourful pixels count more than grey ones
        r += ((c shr 16) and 0xFF) * wgt; g += ((c shr 8) and 0xFF) * wgt; bl += (c and 0xFF) * wgt; n += wgt
    }
    if (s !== b) s.recycle()
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(android.graphics.Color.rgb((r / n).toInt(), (g / n).toInt(), (bl / n).toInt()), hsv)
    hsv[1] = (hsv[1] * 1.4f).coerceIn(0.35f, 0.9f)
    hsv[2] = hsv[2].coerceIn(0.75f, 1f)
    android.graphics.Color.HSVToColor(hsv)
} catch (_: Exception) { C.accent }
