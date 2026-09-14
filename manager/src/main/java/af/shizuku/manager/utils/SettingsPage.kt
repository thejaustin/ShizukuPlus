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

    // Themed (Material You) icons has no dedicated Settings.ACTION_* — it's a per-launcher
    // toggle (Pixel Launcher's "Wallpaper & style", One UI Home, Nova, etc. each store and
    // surface it differently), so there's no page we can reliably deep-link into. This just
    // gets the user as close as AOSP guarantees: the Home app picker/settings entry point.
    object ThemedIcons : SettingsPage(Settings.ACTION_HOME_SETTINGS) {
        override fun launch(context: Context) {
            runCatching {
                super.launch(context)
            }.recoverCatching {
                val intent = Intent(Settings.ACTION_SETTINGS).apply { flags = defaultFlags }
                context.startActivity(intent)
            }.onFailure { Timber.w(it, "Unable to open any Settings page for themed icons") }
        }
    }

    object Samsung {
        object AutoBlocker : SettingsPage("android.settings.SECURITY_ADVANCED_SETTINGS") {
            override fun launch(context: Context) {
                runCatching {
                    // Auto Blocker moved out of com.android.settings entirely at some point
                    // after One UI 6, into its own dedicated package - confirmed live on a
                    // One UI 8/Android 16 S26 Ultra: this lands directly on the exact Auto
                    // Blocker toggle screen, no permission issues, no fallback needed. The two
                    // attempts below it (old in-settings action/component) are dead on current
                    // software but kept for older devices that might still route through them.
                    val intent = Intent().apply {
                        setComponent(ComponentName("com.samsung.android.rampart", "com.samsung.android.rampart.ui.MainSettingActivity"))
                        flags = defaultFlags
                    }
                    context.startActivity(intent)
                }.recoverCatching {
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
                    // Samsung moved this component's package from .sm.ui.battery.* to
                    // .sm.battery.ui.* at some point after One UI 6 - confirmed broken
                    // (ActivityNotFoundException) on a One UI 8/Android 16 S26 Ultra;
                    // the action string itself is unchanged. Try the current path first.
                    val intent = Intent("com.samsung.android.sm.ACTION_BATTERY").apply {
                        setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"))
                        flags = defaultFlags
                    }
                    context.startActivity(intent)
                }.recoverCatching {
                    // Old One UI path, in case an older device still uses it.
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
        object BackgroundUsageLimits : SettingsPage("com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY") {
            override fun launch(context: Context) {
                runCatching {
                    // The categorized "App Power Management" hub
                    // (.sm.battery.ui.setting.AppPowerManagementActivity) requires
                    // android.permission.READ_SEARCH_INDEXABLES - a protected system
                    // permission no third-party app can hold (confirmed via SecurityException
                    // on a real One UI 8/Android 16 S26 Ultra - don't route through it again).
                    // But the actual list screen underneath it (CheckableAppListActivity) has
                    // no such guard and opens directly - confirmed live on the same device.
                    // This activity backs THREE lists (Sleeping / Deep sleeping / Never
                    // sleeping apps), selected via the "activity_type" int extra - documented
                    // by Samsung itself (developer.samsung.com/mobile/app-management.html,
                    // "Deeplink API"): 0=sleeping, 1=deep sleeping, 2=never sleeping. We want
                    // "Never sleeping apps" specifically - that's the actual exemption list
                    // this fix-button's guidance describes, not the default (0) restriction
                    // list. No per-app-package extra is documented, so this lands on the list,
                    // not scrolled/highlighted to Shizuku+ specifically.
                    val intent = Intent("com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY").apply {
                        setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.usage.CheckableAppListActivity"))
                        putExtra("activity_type", 2)
                        flags = defaultFlags
                    }
                    context.startActivity(intent)
                }.recoverCatching {
                    // Old One UI action + component, in case an older device still uses it.
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

    object Oppo {
        /** Opens ColorOS/OxygenOS per-app battery settings (Auto-Launch + No restrictions toggle). */
        object BatterySettings : SettingsPage(Settings.ACTION_APPLICATION_DETAILS_SETTINGS) {
            override fun buildIntent(context: Context): Intent {
                return super.buildIntent(context).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                }
            }
            override fun launch(context: Context) {
                runCatching {
                    // ColorOS 14+ / OplusOS — per-app battery optimization page
                    val intent = Intent().apply {
                        setClassName("com.oplus.battery", "com.oplus.battery.ui.app_manage.AppPowerManagerActivity")
                        putExtra("package_name", context.packageName)
                        flags = defaultFlags
                    }
                    context.startActivity(intent)
                }.recoverCatching {
                    // ColorOS 13 / older — PhoneManager per-app battery page
                    val intent = Intent().apply {
                        setClassName("com.coloros.phonemanager", "com.coloros.phonemanager.feature.battery.PerAppBatteryPowerActivity")
                        putExtra("package_name", context.packageName)
                        flags = defaultFlags
                    }
                    context.startActivity(intent)
                }.recoverCatching {
                    // Older ColorOS action string
                    val intent = Intent("com.coloros.powermanager.action.APP_POWER_MANAGER").apply {
                        flags = defaultFlags
                    }
                    context.startActivity(intent)
                }.recoverCatching {
                    // Last resort: standard app-details page
                    super.launch(context)
                }.onFailure { e ->
                    Timber.tag("SettingsUtils").w("Failed to open Oppo/OnePlus battery settings: ${e.message}")
                }
            }
        }
    }

    object TCL {
        /** Opens TCL System Manager's Auto-Start / battery whitelist screen. */
        object AutoStart : SettingsPage(Settings.ACTION_APPLICATION_DETAILS_SETTINGS) {
            override fun buildIntent(context: Context): Intent {
                return super.buildIntent(context).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                }
            }
            override fun launch(context: Context) {
                runCatching {
                    // TCL System Manager — Auto-start list (T1 / NxtPaper / Revvl)
                    val intent = Intent().apply {
                        setClassName("com.tcl.systemmanager", "com.tcl.systemmanager.ui.autorun.AutoRunActivity")
                        flags = defaultFlags
                    }
                    context.startActivity(intent)
                }.recoverCatching {
                    // Older TCL path
                    val intent = Intent().apply {
                        setClassName("com.tcl.systemmanager", "com.tcl.systemmanager.MainActivity")
                        flags = defaultFlags
                    }
                    context.startActivity(intent)
                }.recoverCatching {
                    // Last resort: standard app-details page
                    super.launch(context)
                }.onFailure { e ->
                    Timber.tag("SettingsUtils").w("Failed to open TCL auto-start settings: ${e.message}")
                }
            }
        }
    }

    object Xiaomi {
        /** Opens MIUI/HyperOS per-app battery settings (No restrictions toggle + Autostart). */
        object BatterySettings : SettingsPage(Settings.ACTION_APPLICATION_DETAILS_SETTINGS) {
            override fun buildIntent(context: Context): Intent {
                return super.buildIntent(context).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                }
            }
            override fun launch(context: Context) {
                runCatching {
                    // HyperOS PowerKeeper — direct per-app battery page (most specific)
                    val intent = Intent().apply {
                        setClassName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HoldApplicationsDetailActivity")
                        putExtra("package_name", context.packageName)
                        putExtra("package_label", context.getString(af.shizuku.manager.R.string.app_name))
                        flags = defaultFlags
                    }
                    context.startActivity(intent)
                }.recoverCatching {
                    // MIUI Security Center — Autostart + battery page fallback
                    val intent = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                        setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.AppPermissionsEditorActivity")
                        putExtra("extra_pkgname", context.packageName)
                        flags = defaultFlags
                    }
                    context.startActivity(intent)
                }.recoverCatching {
                    // Standard app-details page — user can navigate to Battery manually
                    super.launch(context)
                }.onFailure { e ->
                    Timber.tag("SettingsUtils").w("Failed to open Xiaomi battery settings: ${e.message}")
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
