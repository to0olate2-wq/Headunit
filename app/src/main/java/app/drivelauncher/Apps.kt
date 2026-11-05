package app.drivelauncher

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.widget.Toast

object Apps {
    private val labels = HashMap<String, String>()
    private val icons = HashMap<String, Drawable>()

    fun label(ctx: Context, pkg: String): String = labels.getOrPut(pkg) {
        try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg
        }
    }

    fun icon(ctx: Context, ri: ResolveInfo): Drawable? {
        val k = ri.activityInfo.packageName + "/" + ri.activityInfo.name
        icons[k]?.let { return it }
        return try { ri.loadIcon(ctx.packageManager).also { icons[k] = it } } catch (_: Exception) { null }
    }

    fun canLaunch(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getLaunchIntentForPackage(pkg) != null
    } catch (_: Exception) { false }

    fun launch(ctx: Context, pkg: String): Boolean {
        val i = try { ctx.packageManager.getLaunchIntentForPackage(pkg) } catch (_: Exception) { null } ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { ctx.startActivity(i); true } catch (_: Exception) { false }
    }

    fun launch(ctx: Context, ri: ResolveInfo) {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try { ctx.startActivity(i) } catch (_: Exception) {
            Toast.makeText(ctx, "Can't open this app", Toast.LENGTH_SHORT).show()
        }
    }

    fun launchables(ctx: Context): List<ResolveInfo> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return try {
            pm.queryIntentActivities(i, 0)
                .filter { it.activityInfo.packageName != ctx.packageName }
                .sortedBy { it.loadLabel(pm).toString().lowercase() }
        } catch (_: Exception) { emptyList() }
    }

    private var zlinkCache: String? = null
    private var zlinkChecked = 0L

    /** ZLink / CarPlay / Android Auto host app on this unit, found by name. */
    fun findZlink(ctx: Context): String? {
        val now = System.currentTimeMillis()
        if (now - zlinkChecked < 60_000) return zlinkCache
        zlinkChecked = now
        val pm = ctx.packageManager
        val all = launchables(ctx)
        val words = listOf("zlink", "carlink", "autokit", "tlink", "carplay")
        zlinkCache = all.firstOrNull { ri ->
            val p = ri.activityInfo.packageName.lowercase()
            val l = ri.loadLabel(pm).toString().lowercase()
            words.any { p.contains(it) || l.contains(it) }
        }?.activityInfo?.packageName
        return zlinkCache
    }

    fun findMaps(ctx: Context): String? =
        listOf("com.google.android.apps.maps", "com.waze", "com.here.app.maps", "app.organicmaps", "net.osmand", "com.sygic.aura")
            .firstOrNull { canLaunch(ctx, it) }

    fun findMusic(ctx: Context): String? =
        listOf("com.anghami", "com.spotify.music", "com.google.android.apps.youtube.music", "com.apple.android.music", "com.deezer.android.app")
            .firstOrNull { canLaunch(ctx, it) }
}

object Vol {
    fun adjust(ctx: Context, dir: Int) {
        try {
            ctx.getSystemService(AudioManager::class.java)
                ?.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
        } catch (_: Exception) {}
    }

    fun frac(ctx: Context): Float = try {
        val am = ctx.getSystemService(AudioManager::class.java)
        am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    } catch (_: Exception) { 0f }
}

/** Gives the launcher access to "now playing" and navigation notifications. */
class MediaListenerService : NotificationListenerService() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        MediaHub.init(this)
    }

    override fun onListenerConnected() {
        Diag.log("notification access connected")
        MediaHub.onListenerConnected(this)
        try { activeNotifications?.forEach { handle(it) } } catch (_: Exception) {}
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null) handle(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val p = sbn?.packageName ?: return
        NavHub.onRemoved(p)
        MediaHub.onRemoved(p)
    }

    private fun handle(sbn: StatusBarNotification) {
        try {
            val n = sbn.notification ?: return
            val ex = n.extras
            if (sbn.packageName != packageName && ex != null) {
                val title = ex.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: ""
                val text = ex.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""
                val flags = buildList {
                    ex.getString(android.app.Notification.EXTRA_TEMPLATE)?.substringAfterLast('$')?.let { add(it) }
                    n.category?.let { add("cat=$it") }
                    if (ex.containsKey(android.app.Notification.EXTRA_MEDIA_SESSION)) add("SESSION")
                    if (n.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0) add("ongoing")
                    n.actions?.let { a -> add("actions=" + a.joinToString("/") { (it.title ?: "").toString() }) }
                }.joinToString(" ")
                Diag.note(sbn.packageName, title, text, flags)
            }
            if (!NavHub.onPosted(this, sbn)) MediaHub.onNotification(this, sbn)
        } catch (e: Exception) {
            Diag.log("notification handling: ${e.javaClass.simpleName} ${e.message}")
        }
    }
}
