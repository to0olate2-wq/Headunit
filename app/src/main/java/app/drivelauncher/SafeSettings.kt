package app.drivelauncher

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast

/**
 * Opening Android's own Settings app is risky on some head units: on a TS10 (UIS7862S, Android 14) the hidden
 * Android Settings froze the whole screen black until a restart (reported by another launcher's users). So on
 * Android 10+ the general Settings screens (Wi-Fi, Bluetooth, Home app) only open when the unit's own app list
 * shows Android's Settings; otherwise the unit's own settings app opens.
 */
object SafeSettings {
    private const val AOSP = "com.android.settings"

    private fun launchable(ctx: Context): List<ResolveInfo> = try {
        ctx.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
    } catch (_: Exception) { emptyList() }

    /** Is Android's own Settings app shown in the unit's app list (so it's safe to open)? */
    fun androidSettingsShown(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 29 || launchable(ctx).any { it.activityInfo.packageName == AOSP }

    /** The unit maker's settings app, if there is one in the app list. */
    fun brandSettings(ctx: Context): String? = launchable(ctx).firstOrNull {
        val p = it.activityInfo.packageName
        p != AOSP && p != ctx.packageName &&
            (it.loadLabel(ctx.packageManager).toString().contains("setting", true) || p.contains("setting", true))
    }?.activityInfo?.packageName

    /** Opens a Settings screen (e.g. Wi-Fi) only where it's safe; otherwise the unit's own settings app. */
    fun open(a: MainActivity, action: String) {
        if (androidSettingsShown(a)) {
            try { a.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) {}
        }
        val b = brandSettings(a)
        if (b != null && Apps.launch(a, b)) return
        Toast.makeText(a, "Use the head unit's own Settings for this", Toast.LENGTH_LONG).show()
    }

    // ---- home screen

    fun otherHomes(ctx: Context): List<ResolveInfo> = try {
        ctx.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .filter { it.activityInfo.packageName != ctx.packageName && it.activityInfo.packageName != "com.android.settings" }
    } catch (_: Exception) { emptyList() }

    /** Android 8–9: a second (empty) home screen appears for a moment, so Android forgets the old choice and asks. */
    fun askHomeOldAndroid(a: MainActivity) {
        val pm = a.packageManager
        val fake = ComponentName(a, FakeHome::class.java)
        try {
            pm.setComponentEnabledSetting(fake, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            a.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Toast.makeText(a, "Choose Drive Launcher, then Always", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Diag.log("home chooser: ${e.message}")
        } finally {
            Handler(Looper.getMainLooper()).postDelayed({
                try { pm.setComponentEnabledSetting(fake, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP) } catch (_: Exception) {}
            }, 1500)
        }
    }

    /** Hand the home screen back to the unit's own launcher. */
    fun stopBeingHome(a: MainActivity, home: ResolveInfo) {
        try { a.packageManager.clearPackagePreferredActivities(a.packageName) } catch (_: Exception) {}
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .setClassName(home.activityInfo.packageName, home.activityInfo.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 29 && Setup.isHome(a)) {
            // Android 10+ keeps the home choice itself: show how to change it, then open the unit's launcher
            a.dialog().setTitle("One more step")
                .setMessage("Android keeps Drive Launcher as the home app until you pick another. " +
                    "Press the Home button: if Android asks, choose the unit's own launcher. Otherwise use the unit's " +
                    "Settings → Apps → Default apps → Home app.")
                .setPositiveButton("Open the unit's home screen") { _, _ -> try { a.startActivity(i) } catch (_: Exception) {} }
                .setNegativeButton("Cancel", null).show()
            return
        }
        try { a.startActivity(i) } catch (_: Exception) {}
    }

    // ---- Wi-Fi and Bluetooth

    /** 0 = off, 1 = on but not connected, 2 = connected. */
    fun wifiState(ctx: Context): Int = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> 2
            ctx.applicationContext.getSystemService(WifiManager::class.java)?.isWifiEnabled == true -> 1
            else -> 0
        }
    } catch (_: Exception) { 0 }

    @Suppress("DEPRECATION")
    fun btState(ctx: Context): Int = try {
        val ad = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        when {
            ad == null || !ad.isEnabled -> 0
            Build.VERSION.SDK_INT >= 31 && ctx.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED -> 1
            listOf(android.bluetooth.BluetoothProfile.A2DP, android.bluetooth.BluetoothProfile.HEADSET)
                .any { ad.getProfileConnectionState(it) == android.bluetooth.BluetoothProfile.STATE_CONNECTED } -> 2
            else -> 1
        }
    } catch (_: Exception) { 0 }

    /** Tap on the Wi-Fi icon: switch it (Android 9 and older) or open Wi-Fi settings safely. */
    @Suppress("DEPRECATION")
    fun tapWifi(a: MainActivity) {
        if (Build.VERSION.SDK_INT < 29) {
            try {
                val wm = a.applicationContext.getSystemService(WifiManager::class.java)
                if (wm != null && wm.setWifiEnabled(!wm.isWifiEnabled)) return
            } catch (_: Exception) {}
        }
        open(a, if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS)
    }

    fun tapBt(a: MainActivity) = open(a, Settings.ACTION_BLUETOOTH_SETTINGS)
}

/** Empty, normally switched off: only used for a moment to make Android 8–9 ask "Which home app?". */
class FakeHome : Activity() {
    override fun onCreate(b: android.os.Bundle?) { super.onCreate(b); finish() }
}
