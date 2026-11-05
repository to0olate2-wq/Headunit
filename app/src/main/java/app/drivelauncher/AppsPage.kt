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
