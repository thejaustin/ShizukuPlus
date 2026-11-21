package af.shizuku.manager.adb

import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.manager.utils.NetworkStateHelper
import af.shizuku.manager.utils.SettingsPage
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import timber.log.Timber

/** Things that stop wireless pairing before it can start, in the order the user has to fix them. */
enum class PairingBlocker {
    DEVELOPER_OPTIONS_OFF,
    NO_WIFI,
    WIRELESS_DEBUGGING_OFF,
    LOCAL_NETWORK_DENIED,
    NOTIFICATIONS_BLOCKED,
}

/**
 * Reads what can be read without privileges and returns the first [PairingBlocker], or null when
 * nothing obvious is in the way. Anything that can't be read counts as fine: a wrong "all good"
 * just leaves the user on the normal waiting state, a wrong blocker would stop them for nothing.
 */
object PairingPreflight {
    fun check(
        context: Context,
        includeNotifications: Boolean = true,
    ): PairingBlocker? {
        if (!developerOptionsEnabled(context)) return PairingBlocker.DEVELOPER_OPTIONS_OFF
        val wirelessDebugging = wirelessDebuggingSetting(context)
        // Wireless debugging switching itself on is the best proof the network is fine (a VPN or
        // an odd transport can fool the Wi-Fi check), so the network is only judged while it's off.
        if (wirelessDebugging != 1 && !NetworkStateHelper.isWirelessAdbSupportedNetwork(context)) {
            return PairingBlocker.NO_WIFI
        }
        if (wirelessDebugging == 0) return PairingBlocker.WIRELESS_DEBUGGING_OFF
        val permission = LocalNetworkPermission.required()
        if (permission != null && context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            return PairingBlocker.LOCAL_NETWORK_DENIED
        }
        if (includeNotifications && !notificationsEnabled(context)) return PairingBlocker.NOTIFICATIONS_BLOCKED
        return null
    }

    fun developerOptionsEnabled(context: Context): Boolean =
        runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1) != 0
        }.getOrDefault(true)

    /** 1 on, 0 off, -1 when the ROM doesn't expose it. */
    fun wirelessDebuggingSetting(context: Context): Int =
        runCatching {
            Settings.Global.getInt(context.contentResolver, ADB_WIFI_ENABLED, -1)
        }.getOrDefault(-1)

    /** App notifications on, and the pairing channel not switched off on its own. */
    fun notificationsEnabled(context: Context): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return true
        val channel = nm.getNotificationChannel(AdbPairingService.NOTIFICATION_CHANNEL)
        return nm.areNotificationsEnabled() &&
            (channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE)
    }

    /** Settings.Global key for the Wireless debugging switch (hidden in the SDK). */
    const val ADB_WIFI_ENABLED = "adb_wifi_enabled"

    // Only non-Samsung phones keep Wireless debugging on while their own hotspot is on
    // (NetworkStateHelper.isWirelessAdbSupportedNetwork), so only they get the hotspot tip.
    val hotspotHelps: Boolean get() = !EnvironmentUtils.isSamsung()

    fun openAboutPhone(context: Context) {
        launch(context, Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) { SettingsPage.Developer.Options.launch(context) }
    }

    fun openWifi(context: Context) {
        launch(context, Intent(Settings.Panel.ACTION_WIFI)) {
            launch(context, Intent(Settings.ACTION_WIFI_SETTINGS)) { SettingsPage.InternetPanel.launch(context) }
        }
    }

    fun openHotspot(context: Context) {
        // Same pair of intents the start dialog's hotspot button uses; OEMs name the page differently.
        launch(context, Intent("android.settings.TETHER_SETTINGS")) {
            launch(context, Intent(Settings.ACTION_WIRELESS_SETTINGS)) { openWifi(context) }
        }
    }

    fun openAppInfo(context: Context) {
        launch(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))) {}
    }

    fun openServiceDoctor(context: Context) {
        launch(context, Intent(context, af.shizuku.manager.settings.ServiceDoctorActivity::class.java)) {}
    }

    private fun launch(
        context: Context,
        intent: Intent,
        fallback: () -> Unit,
    ) {
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Timber.w("Pairing: can't open %s (%s)", intent.action ?: intent.component, e.javaClass.simpleName)
            fallback()
        }
    }
}
