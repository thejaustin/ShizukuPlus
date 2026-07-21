package af.shizuku.manager.utils

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.TileService
import timber.log.Timber
import af.shizuku.manager.adb.AdbPairingAccessibilityService
import af.shizuku.manager.service.WatchdogService

sealed class SettingsPage(
    private val action: String,
    private val fragmentArg: String? = null
) {

    sealed class Developer(
        action: String = Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
        fragmentArg: String? = null
    ) : SettingsPage(action, fragmentArg) {

        object Options : Developer()
        object HighlightUsbDebugging : Developer(fragmentArg = "enable_adb")
        object HighlightWirelessDebugging : Developer(fragmentArg = "toggle_adb_wireless")

        object WirelessDebugging : Developer() {
            // Brands that ship MIUI/HyperOS and cannot handle ACTION_QS_TILE_PREFERENCES
            // for WirelessDebugging — the QS tile intent causes a NullPointerException
            // in their Settings app (see GitHub issue #241). Fall back to the
            // fragment-highlight intent which works on all ROMs.
            private val MIUI_BRANDS = setOf("xiaomi", "redmi", "poco")

            override fun buildIntent(context: Context): Intent {
                if (Build.BRAND.lowercase() in MIUI_BRANDS) {
                    // MIUI / HyperOS: ACTION_QS_TILE_PREFERENCES crashes Settings.
                    // Use the fragment-highlight intent instead.
                    return HighlightWirelessDebugging.buildIntent(context)
                }

                return Intent(TileService.ACTION_QS_TILE_PREFERENCES).apply {
                    val packageName = "com.android.settings"
                    setPackage(packageName)
                    putExtra(
                        Intent.EXTRA_COMPONENT_NAME,
                        ComponentName(
                            packageName,
                            "com.android.settings.development.qstile.DevelopmentTiles\$WirelessDebugging"
                        )
                    )
                    addFlags(defaultFlags)
                }
            }

            override fun launch(context: Context) {
                runCatching {
                    context.startActivity(buildIntent(context))
                }.recoverCatching {
                    // First fallback: highlight wireless debugging in developer options
                    HighlightWirelessDebugging.launch(context)
                }.recoverCatching {
                    // Second fallback: open developer options root
                    Options.launch(context)
                }.onFailure { e ->
                    Timber.tag("SettingsUtils").w("Failed to start Settings activity (${e.javaClass.simpleName}): ${e.message}")
                }
            }
        }

    }

    sealed class Notifications(
        action: String = Settings.ACTION_APP_NOTIFICATION_SETTINGS,
        fragmentArg: String? = null
    ) : SettingsPage(action, fragmentArg) {
        override fun buildIntent(context: Context): Intent {
            return super.buildIntent(context).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
        }

        object NotificationSettings : Notifications()
        object NotificationChannel : Notifications() {
            override fun buildIntent(context: Context): Intent {
                return super.buildIntent(context).apply {
                    putExtra(Settings.EXTRA_CHANNEL_ID, WatchdogService.CRASH_CHANNEL_ID)
                }
            }
        }
    }

    object InternetPanel : SettingsPage(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
    object Accessibility : SettingsPage(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    object Samsung {
        object AutoBlocker : SettingsPage("android.settings.SECURITY_ADVANCED_SETTINGS") {
            override fun launch(context: Context) {
                runCatching {
                    val intent = Intent("com.samsung.android.settings.AUTO_BLOCKER").apply {
                        flags = defaultFlags
                    }
                    context.startActivity(intent)
                }.recoverCatching {
                    val intent = Intent().apply {
                        setComponent(ComponentName("com.android.settings", "com.samsung.android.settings.autoblocker.AutoBlockerSettingsActivity"))
                        flags = defaultFlags
                    }
                    context.startActivity(intent)
                }.recoverCatching {
                    val intent = Intent("android.settings.SECURITY_ADVANCED_SETTINGS").apply {
                        flags = defaultFlags
                    }
                    context.startActivity(intent)
                }.recoverCatching {
                    val intent = Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                        flags = defaultFlags
                    }
                    context.startActivity(intent)
                }.onFailure { e ->
                    Timber.tag("SettingsUtils").w("Failed to start AutoBlocker Settings activity: ${e.message}")
                }
            }
        }
        object DeviceCareBattery : SettingsPage("com.samsung.android.sm.ACTION_BATTERY") {
            override fun launch(context: Context) {
                runCatching {
                    val intent = Intent("com.samsung.android.sm.ACTION_BATTERY").apply {
                        setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"))
                        flags = defaultFlags
                    }
                    context.startActivity(intent)
                }.onFailure {
                    super.launch(context)
                }
            }
        }
        object BackgroundUsageLimits : SettingsPage("com.samsung.android.sm.ACTION_BACKGROUND_USAGE_LIMITS") {
            override fun launch(context: Context) {
                runCatching {
                    val intent = Intent("com.samsung.android.sm.ACTION_BACKGROUND_USAGE_LIMITS").apply {
                        setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BackgroundUsageLimitsActivity"))
                        flags = defaultFlags
                    }
                    context.startActivity(intent)
                }.onFailure {
                    // Fallback to general battery page
                    DeviceCareBattery.launch(context)
                }
            }
        }
    }

    protected val defaultFlags =
        Intent.FLAG_ACTIVITY_NEW_TASK or
        Intent.FLAG_ACTIVITY_NO_HISTORY or
        Intent.FLAG_ACTIVITY_CLEAR_TASK or
        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS

    open fun buildIntent(context: Context): Intent = Intent(action).apply {
        fragmentArg?.let {
            val fragmentArgKey = ":settings:fragment_args_key"
            putExtra(fragmentArgKey, it)
        }
        flags = defaultFlags
    }

    open fun launch(context: Context) {
        runCatching {
            context.startActivity(buildIntent(context))
        }.onFailure { e ->
            // ActivityNotFoundException is expected on devices that don't have this settings page
            // (e.g. SECURITY_ADVANCED_SETTINGS on non-Samsung, or TV devices). Use w() not e()
            // so it doesn't get reported to Sentry as a crash.
            Timber.tag("SettingsUtils").w("Failed to start Settings activity (${e.javaClass.simpleName}): ${e.message}")
        }
    }

}
