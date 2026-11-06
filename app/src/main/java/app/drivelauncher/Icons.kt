package app.drivelauncher

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

enum class Ic {
    HOME, MINUS, PLUS, GRID, MIC, PIN, MUSIC, PHONE, SLIDERS, PLAY, PAUSE, PREV, NEXT,
    SHUFFLE, QUEUE, REFRESH, TURN_RIGHT, NAV, LOCATE, MOON, SUN, CLOUD, RAIN, CHECK, PLUG,
    CAR, TIRE, LINK, GAUGE, BT, CLOSE, OPEN, SOURCE, DASH, SPLIT, BACK, FUEL, WRENCH, ENGINE, PARK, ROUTE, TRASH, EDIT,
    WORK, STAR, COFFEE, FOOD, SEARCH, FOLDER, HIDE, SORT, LEAF, SPEAKER, SEND, VIDEO
}

/** Stroke icons drawn on a 24x24 grid (same shapes as the mockup). */
object Icons {
    private val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val tmp = Path()
    private val rf = RectF()

    fun draw(c: Canvas, ic: Ic, cx: Float, cy: Float, size: Float, color: Int, sw: Float = 1.8f) {
        val k = size / 24f
        c.save()
        c.translate(cx - size / 2f, cy - size / 2f)
        c.scale(k, k)
        sp.color = color
        sp.strokeWidth = sw
        fp.color = color
        when (ic) {
            Ic.HOME -> {
                poly(c, 3f, 11f, 12f, 3f, 21f, 11f)
                poly(c, 5f, 10f, 5f, 20f, 19f, 20f, 19f, 10f)
            }
            Ic.MINUS -> c.drawLine(5f, 12f, 19f, 12f, sp)
            Ic.PLUS -> { c.drawLine(12f, 5f, 12f, 19f, sp); c.drawLine(5f, 12f, 19f, 12f, sp) }
            Ic.GRID -> {
                rr(c, 4f, 4f, 10f, 10f, 1.5f); rr(c, 14f, 4f, 20f, 10f, 1.5f)
                rr(c, 4f, 14f, 10f, 20f, 1.5f); rr(c, 14f, 14f, 20f, 20f, 1.5f)
            }
            Ic.MIC -> {
                rr(c, 9f, 3f, 15f, 14f, 3f)
                rf.set(5f, 4f, 19f, 18f); c.drawArc(rf, 0f, 180f, false, sp)
                c.drawLine(12f, 18f, 12f, 21f, sp)
            }
            Ic.PIN -> {
                path.reset()
                path.moveTo(12f, 21f)
                path.lineTo(6.6f, 14.45f)
                rf.set(5f, 3f, 19f, 17f)
                path.arcTo(rf, 140.5f, 259f)
                path.close()
                c.drawPath(path, sp)
                c.drawCircle(12f, 10f, 2.5f, sp)
            }
            Ic.NAV -> {
                path.reset()
                path.moveTo(12f, 3f); path.lineTo(19f, 20f); path.lineTo(12f, 16f); path.lineTo(5f, 20f); path.close()
                c.drawPath(path, sp)
            }
            Ic.MUSIC -> {
                poly(c, 9f, 18f, 9f, 5f, 20f, 3f, 20f, 16f)
                c.drawCircle(6f, 18f, 3f, sp); c.drawCircle(17f, 16f, 3f, sp)
            }
            Ic.PHONE -> {
                path.reset()
                path.moveTo(5f, 4f); path.lineTo(9f, 4f); path.lineTo(11f, 9f); path.lineTo(8.5f, 10.5f)
                path.cubicTo(9.5f, 12.8f, 11.2f, 14.5f, 13.5f, 15.5f)
                path.lineTo(15f, 13f); path.lineTo(20f, 15f); path.lineTo(20f, 19f)
                path.cubicTo(20f, 20.1f, 19.1f, 21f, 18f, 21f)
                path.cubicTo(10f, 21f, 3f, 14f, 3f, 6f)
                path.cubicTo(3f, 4.9f, 3.9f, 4f, 5f, 4f)
                path.close()
                c.drawPath(path, sp)
            }
            Ic.SLIDERS -> {
                c.drawLine(4f, 6f, 14f, 6f, sp); c.drawLine(18f, 6f, 20f, 6f, sp)
                c.drawLine(4f, 12f, 8f, 12f, sp); c.drawLine(12f, 12f, 20f, 12f, sp)
                c.drawLine(4f, 18f, 16f, 18f, sp)
                c.drawCircle(16f, 6f, 2f, sp); c.drawCircle(10f, 12f, 2f, sp); c.drawCircle(18f, 18f, 2f, sp)
            }
            Ic.PLAY -> fillPoly(c, 8f, 5f, 19f, 12f, 8f, 19f)
            Ic.PAUSE -> {
                rf.set(7f, 5f, 10.5f, 19f); c.drawRoundRect(rf, 1f, 1f, fp)
                rf.set(13.5f, 5f, 17f, 19f); c.drawRoundRect(rf, 1f, 1f, fp)
            }
            Ic.PREV -> { fillPoly(c, 18f, 6f, 18f, 18f, 9f, 12f); c.drawLine(6f, 6f, 6f, 18f, sp) }
            Ic.NEXT -> { fillPoly(c, 6f, 6f, 6f, 18f, 15f, 12f); c.drawLine(18f, 6f, 18f, 18f, sp) }
            Ic.SHUFFLE -> {
                path.reset(); path.moveTo(3f, 7f); path.lineTo(6f, 7f); path.cubicTo(10f, 7f, 12f, 17f, 16f, 17f); path.lineTo(21f, 17f)
                c.drawPath(path, sp)
                poly(c, 18f, 14f, 21f, 17f, 18f, 20f)
                path.reset(); path.moveTo(3f, 17f); path.lineTo(6f, 17f); path.cubicTo(7.6f, 17f, 8.8f, 15.4f, 10f, 13.5f)
                c.drawPath(path, sp)
                path.reset(); path.moveTo(14f, 9.5f); path.cubicTo(15.2f, 8f, 16.4f, 7f, 18f, 7f); path.lineTo(21f, 7f)
                c.drawPath(path, sp)
                poly(c, 18f, 4f, 21f, 7f, 18f, 10f)
            }
            Ic.QUEUE -> {
                c.drawLine(4f, 6f, 16f, 6f, sp); c.drawLine(4f, 12f, 16f, 12f, sp); c.drawLine(4f, 18f, 12f, 18f, sp)
                fillPoly(c, 16f, 15f, 16f, 21f, 21f, 18f)
            }
            Ic.REFRESH -> {
                rf.set(4f, 4f, 20f, 20f); c.drawArc(rf, 40f, 300f, false, sp)
                poly(c, 20f, 4f, 20f, 9.5f, 14.5f, 9.5f)
            }
            Ic.TURN_RIGHT -> {
                path.reset(); path.moveTo(6f, 20f); path.lineTo(6f, 13f); path.cubicTo(6f, 10.8f, 7.8f, 9f, 10f, 9f); path.lineTo(19f, 9f)
                c.drawPath(path, sp)
                poly(c, 15f, 5f, 19f, 9f, 15f, 13f)
            }
            Ic.LOCATE -> {
                c.drawCircle(12f, 12f, 7f, sp); c.drawCircle(12f, 12f, 2.5f, sp)
                c.drawLine(12f, 2f, 12f, 5f, sp); c.drawLine(12f, 19f, 12f, 22f, sp)
                c.drawLine(2f, 12f, 5f, 12f, sp); c.drawLine(19f, 12f, 22f, 12f, sp)
            }
            Ic.MOON -> {
                path.reset(); path.addCircle(11f, 13f, 8f, Path.Direction.CW)
                tmp.reset(); tmp.addCircle(17f, 8f, 7f, Path.Direction.CW)
                path.op(tmp, Path.Op.DIFFERENCE)
                c.drawPath(path, sp)
            }
            Ic.SUN -> {
                c.drawCircle(12f, 12f, 4f, sp)
                c.drawLine(12f, 2f, 12f, 4.5f, sp); c.drawLine(12f, 19.5f, 12f, 22f, sp)
                c.drawLine(2f, 12f, 4.5f, 12f, sp); c.drawLine(19.5f, 12f, 22f, 12f, sp)
                c.drawLine(4.9f, 4.9f, 6.6f, 6.6f, sp); c.drawLine(17.4f, 17.4f, 19.1f, 19.1f, sp)
                c.drawLine(4.9f, 19.1f, 6.6f, 17.4f, sp); c.drawLine(17.4f, 6.6f, 19.1f, 4.9f, sp)
            }
            Ic.CLOUD -> cloud(c)
            Ic.RAIN -> {
                c.save(); c.translate(0f, -3f); cloud(c); c.restore()
                c.drawLine(9f, 19f, 8f, 22f, sp); c.drawLine(13f, 19f, 12f, 22f, sp); c.drawLine(17f, 19f, 16f, 22f, sp)
            }
            Ic.CHECK -> poly(c, 5f, 12.5f, 9.5f, 17f, 19f, 7.5f)
            Ic.PLUG -> {
                c.drawLine(9f, 3f, 9f, 8f, sp); c.drawLine(15f, 3f, 15f, 8f, sp)
                path.reset(); path.moveTo(7f, 8f); path.lineTo(17f, 8f); path.lineTo(17f, 12f)
                rf.set(7f, 7f, 17f, 17f); path.arcTo(rf, 0f, 180f); path.close()
                c.drawPath(path, sp)
                c.drawLine(12f, 17f, 12f, 21f, sp)
            }
            Ic.CAR -> {
                poly(c, 3f, 15f, 3f, 12f, 5.4f, 7f, 18.6f, 7f, 21f, 12f, 21f, 15f, close = true)
                c.drawLine(3f, 12f, 21f, 12f, sp)
                c.drawCircle(7f, 17.5f, 1.8f, sp); c.drawCircle(17f, 17.5f, 1.8f, sp)
            }
            Ic.TIRE -> {
                c.drawCircle(12f, 12f, 8f, sp); c.drawCircle(12f, 12f, 3.5f, sp)
                c.drawLine(12f, 4f, 12f, 8.5f, sp); c.drawLine(12f, 15.5f, 12f, 20f, sp)
                c.drawLine(4f, 12f, 8.5f, 12f, sp); c.drawLine(15.5f, 12f, 20f, 12f, sp)
            }
            Ic.LINK -> {
                rr(c, 7f, 2.5f, 17f, 21.5f, 2.5f)
                c.drawLine(10.5f, 18f, 13.5f, 18f, sp)
            }
            Ic.GAUGE -> {
                rf.set(3f, 5f, 21f, 23f); c.drawArc(rf, 180f, 180f, false, sp)
                c.drawLine(12f, 14f, 16.5f, 9f, sp)
                c.drawCircle(12f, 14f, 1.2f, fp)
            }
            Ic.BT -> poly(c, 7f, 7f, 17f, 17f, 12f, 21f, 12f, 3f, 17f, 7f, 7f, 17f)
            Ic.CLOSE -> { c.drawLine(6f, 6f, 18f, 18f, sp); c.drawLine(18f, 6f, 6f, 18f, sp) }
            Ic.OPEN -> {
                poly(c, 14f, 4f, 20f, 4f, 20f, 10f)
                c.drawLine(20f, 4f, 11f, 13f, sp)
                poly(c, 18f, 14f, 18f, 20f, 4f, 20f, 4f, 6f, 10f, 6f)
            }
            Ic.SOURCE -> {
                poly(c, 4f, 8f, 18f, 8f)
                poly(c, 15f, 4f, 19f, 8f, 15f, 12f)
                poly(c, 20f, 16f, 6f, 16f)
                poly(c, 9f, 12f, 5f, 16f, 9f, 20f)
            }
            Ic.DASH -> c.drawLine(6f, 12f, 18f, 12f, sp)
            Ic.SPLIT -> { rr(c, 3f, 5f, 21f, 19f, 2f); c.drawLine(12f, 5f, 12f, 19f, sp) }
            Ic.BACK -> poly(c, 15f, 5f, 8f, 12f, 15f, 19f)
            Ic.FUEL -> {
                rr(c, 5f, 4f, 13f, 20f, 1.5f); c.drawLine(5f, 10f, 13f, 10f, sp)
                poly(c, 13f, 8f, 17f, 11f, 17f, 17f); c.drawCircle(18f, 17.5f, 1.5f, sp)
                c.drawLine(3.5f, 20f, 14.5f, 20f, sp)
            }
            Ic.WRENCH -> {
                path.reset(); path.moveTo(14.5f, 3.5f); path.cubicTo(11.5f, 3.5f, 10f, 6.5f, 11f, 9f)
                path.lineTo(4f, 16f); path.lineTo(8f, 20f); path.lineTo(15f, 13f); path.cubicTo(17.5f, 14f, 20.5f, 12.5f, 20.5f, 9.5f)
                path.lineTo(17.5f, 11.5f); path.lineTo(15f, 9f); path.lineTo(17f, 6f); path.close()
                c.drawPath(path, sp)
            }
            Ic.ENGINE -> {
                poly(c, 4f, 10f, 4f, 16f, 7f, 16f, 9f, 19f, 17f, 19f, 17f, 16f, 20f, 16f, 20f, 10f, 17f, 10f, 15f, 7f, 9f, 7f, 7f, 10f, close = true)
                c.drawLine(10f, 4f, 14f, 4f, sp); c.drawLine(12f, 4f, 12f, 7f, sp)
            }
            Ic.SPEAKER -> {
                poly(c, 4f, 9.5f, 8f, 9.5f, 13f, 5f, 13f, 19f, 8f, 14.5f, 4f, 14.5f, close = true)
                path.reset(); path.moveTo(16f, 9f); path.cubicTo(17.8f, 10.5f, 17.8f, 13.5f, 16f, 15f); c.drawPath(path, sp)
                path.reset(); path.moveTo(18.5f, 6.5f); path.cubicTo(22f, 9.5f, 22f, 14.5f, 18.5f, 17.5f); c.drawPath(path, sp)
            }
            Ic.SEND -> {
                poly(c, 3.5f, 11.5f, 20.5f, 4f, 15f, 20f, 11.5f, 13f, close = true)
                c.drawLine(11.5f, 13f, 20.5f, 4f, sp)
            }
            Ic.VIDEO -> { rr(c, 3f, 6f, 16f, 18f, 2f); poly(c, 16f, 10.5f, 21f, 7.5f, 21f, 16.5f, 16f, 13.5f) }
            Ic.WORK -> {
                rr(c, 3f, 7f, 21f, 19f, 2f)
                poly(c, 9f, 7f, 9f, 5f, 15f, 5f, 15f, 7f)
                c.drawLine(3f, 12.5f, 21f, 12.5f, sp)
            }
            Ic.STAR -> poly(c, 12f, 3.5f, 14.6f, 9f, 20.5f, 9.6f, 16f, 13.6f, 17.3f, 19.5f, 12f, 16.5f, 6.7f, 19.5f, 8f, 13.6f, 3.5f, 9.6f, 9.4f, 9f, close = true)
            Ic.COFFEE -> {
                poly(c, 5f, 9f, 6f, 19f, 15f, 19f, 16f, 9f, close = true)
                path.reset(); path.moveTo(16f, 11f); path.cubicTo(20f, 11f, 20f, 16f, 15.5f, 16f); c.drawPath(path, sp)
                c.drawLine(8f, 3.5f, 8f, 6f, sp); c.drawLine(11f, 3.5f, 11f, 6f, sp); c.drawLine(14f, 3.5f, 14f, 6f, sp)
            }
            Ic.FOOD -> {
                c.drawLine(7f, 3f, 7f, 21f, sp); poly(c, 4.5f, 3f, 4.5f, 8f, 9.5f, 8f, 9.5f, 3f)
                path.reset(); path.moveTo(16f, 21f); path.lineTo(16f, 3f); path.cubicTo(19.5f, 5f, 19.5f, 11f, 16f, 12f); c.drawPath(path, sp)
            }
            Ic.SEARCH -> { c.drawCircle(10.5f, 10.5f, 6f, sp); c.drawLine(15f, 15f, 20f, 20f, sp) }
            Ic.FOLDER -> poly(c, 3f, 6f, 9f, 6f, 11f, 8f, 21f, 8f, 21f, 19f, 3f, 19f, close = true)
            Ic.HIDE -> {
                path.reset(); path.moveTo(3f, 12f); path.cubicTo(7f, 5f, 17f, 5f, 21f, 12f); path.cubicTo(17f, 19f, 7f, 19f, 3f, 12f); c.drawPath(path, sp)
                c.drawCircle(12f, 12f, 2.5f, sp); c.drawLine(4f, 4f, 20f, 20f, sp)
            }
            Ic.SORT -> { c.drawLine(4f, 7f, 20f, 7f, sp); c.drawLine(4f, 12f, 15f, 12f, sp); c.drawLine(4f, 17f, 10f, 17f, sp) }
            Ic.LEAF -> {
                path.reset(); path.moveTo(5f, 19f); path.cubicTo(4f, 9f, 11f, 4f, 20f, 4f); path.cubicTo(20f, 13f, 15f, 20f, 5f, 19f); c.drawPath(path, sp)
                c.drawLine(5f, 19f, 13f, 11f, sp)
            }
            Ic.PARK -> {
                rr(c, 4f, 4f, 20f, 20f, 3f)
                path.reset(); path.moveTo(10f, 17f); path.lineTo(10f, 8f); path.lineTo(13.5f, 8f)
                path.cubicTo(16f, 8f, 16f, 12.5f, 13.5f, 12.5f); path.lineTo(10f, 12.5f)
                c.drawPath(path, sp)
            }
            Ic.ROUTE -> {
                c.drawCircle(6f, 18f, 2.5f, sp); c.drawCircle(18f, 6f, 2.5f, sp)
                path.reset(); path.moveTo(8.5f, 18f); path.lineTo(15f, 18f); path.cubicTo(19f, 18f, 19f, 12f, 15f, 12f)
                path.lineTo(9f, 12f); path.cubicTo(5f, 12f, 5f, 6f, 9f, 6f); path.lineTo(15.5f, 6f)
                c.drawPath(path, sp)
            }
            Ic.TRASH -> {
                c.drawLine(4f, 7f, 20f, 7f, sp); poly(c, 9f, 7f, 9f, 4f, 15f, 4f, 15f, 7f)
                poly(c, 6f, 7f, 7f, 20f, 17f, 20f, 18f, 7f)
            }
            Ic.EDIT -> {
                poly(c, 4f, 20f, 4.5f, 16f, 16f, 4.5f, 19.5f, 8f, 8f, 19.5f, close = true)
                c.drawLine(13.5f, 7f, 17f, 10.5f, sp)
            }
        }
        c.restore()
    }

    private fun cloud(c: Canvas) {
        path.reset(); path.addCircle(8.5f, 14.5f, 4f, Path.Direction.CW)
        tmp.reset(); tmp.addCircle(13.5f, 11f, 5.5f, Path.Direction.CW); path.op(tmp, Path.Op.UNION)
        tmp.reset(); tmp.addCircle(17.5f, 15f, 3.5f, Path.Direction.CW); path.op(tmp, Path.Op.UNION)
        tmp.reset(); tmp.addRect(8.5f, 14f, 17.5f, 18.5f, Path.Direction.CW); path.op(tmp, Path.Op.UNION)
        c.drawPath(path, sp)
    }

    private fun rr(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float) {
        rf.set(l, t, r, b); c.drawRoundRect(rf, rad, rad, sp)
    }

    private fun poly(c: Canvas, vararg p: Float, close: Boolean = false) {
        path.reset()
        path.moveTo(p[0], p[1])
        var i = 2
        while (i + 1 < p.size) { path.lineTo(p[i], p[i + 1]); i += 2 }
        if (close) path.close()
        c.drawPath(path, sp)
    }

    private fun fillPoly(c: Canvas, vararg p: Float) {
        path.reset()
        path.moveTo(p[0], p[1])
        var i = 2
        while (i + 1 < p.size) { path.lineTo(p[i], p[i + 1]); i += 2 }
        path.close()
        c.drawPath(path, fp)
    }
}
