package af.shizuku.manager.doctor

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.adb.AdbPairingAccessibilityService
import af.shizuku.manager.adb.LocalNetworkPermission
import af.shizuku.manager.database.ActivityLogManager
import af.shizuku.manager.database.RootCompatHelper
import af.shizuku.manager.home.tryEnableAccessibilityAutomatically
import af.shizuku.manager.receiver.ShizukuReceiverStarter
import af.shizuku.manager.utils.DeviceOptimizer
import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.manager.utils.SettingsHelper
import af.shizuku.manager.utils.ShizukuStateMachine
import android.Manifest
import android.content.Context
import android.os.Build
import android.provider.Settings
import io.sentry.Breadcrumb
import io.sentry.Sentry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

object ServiceDoctor {
    private val doctorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isAutoHealingActive = AtomicBoolean(false)

    data class CheckItem(
        val id: String,
        val title: String,
        val status: String,
        val ok: Boolean,
        val canAutoFix: Boolean = false,
        val autoFix: (suspend (Context) -> Boolean)? = null,
        val manualAction: ((Context) -> Unit)? = null,
        val tip: String? = null,
        // False for a fix that is only wanted when the user taps it, never in a bulk auto-heal.
        val autoHeal: Boolean = true,
    )

    data class DiagnosticResult(
        val checks: List<CheckItem>,
        val issuesCount: Int,
        val fixableCount: Int,
        val tips: List<String>,
    )

    data class AutoHealReport(
        val resolvedCount: Int,
        val appliedFixes: List<String>,
        val remainingIssues: List<String>,
    )

    private fun trySecureSettingOff(
        context: Context,
        key: String,
    ): Boolean {
        if (!SettingsHelper.hasWriteSecureSettings(context)) return false
        return runCatching {
            Settings.Secure.putInt(context.contentResolver, key, 0)
            Settings.Secure.getInt(context.contentResolver, key, 1) == 0
        }.getOrDefault(false)
    }

    suspend fun runFullDiagnostics(context: Context): DiagnosticResult =
        withContext(Dispatchers.IO) {
            val checks = mutableListOf<CheckItem>()
            val tips = mutableListOf<String>()

            // 1. Background protection (battery optimization + Samsung sleeping apps)
            val bgState = DeviceOptimizer.backgroundState(context)
            val isIgnoring = bgState == DeviceOptimizer.BackgroundState.PROTECTED
            val bgCanFix = !isIgnoring && (DeviceOptimizer.hasPrivilegedShell() || SettingsHelper.hasWriteSecureSettings(context))
            checks.add(
                CheckItem(
                    id = "battery_optimization",
                    title = context.getString(R.string.doctor_check_battery),
                    status =
                        context.getString(
                            when (bgState) {
                                DeviceOptimizer.BackgroundState.PROTECTED -> R.string.doctor_status_ok
                                DeviceOptimizer.BackgroundState.RESTRICTED -> R.string.doctor_status_samsung_sleeping_restricted
                                DeviceOptimizer.BackgroundState.UNPROTECTED -> R.string.doctor_status_optimized
                            },
                        ),
                    ok = isIgnoring,
                    canAutoFix = bgCanFix,
                    autoFix = { ctx -> DeviceOptimizer.protectSelf(ctx) },
                    tip = if (!isIgnoring) "• " + context.getString(R.string.doctor_tip_battery) else null,
                ),
            )
            if (!isIgnoring) tips.add("• " + context.getString(R.string.doctor_tip_battery))

            // 2. Wireless ADB
            val adbPort = EnvironmentUtils.getAdbTcpPort()
            val adbOk = adbPort > 0
            val hasSecure = SettingsHelper.hasWriteSecureSettings(context)
            checks.add(
                CheckItem(
                    id = "wireless_adb",
                    title = context.getString(R.string.doctor_check_adb),
                    status = if (adbOk) "${context.getString(R.string.doctor_status_ok)} ($adbPort)" else context.getString(R.string.doctor_status_not_enabled),
                    ok = adbOk,
                    canAutoFix = !adbOk && hasSecure,
                    autoFix = { ctx ->
                        runCatching {
                            Settings.Global.putInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, 1)
                            Settings.Global.putInt(ctx.contentResolver, "adb_wifi_enabled", 1)
                            true
                        }.getOrDefault(false)
                    },
                    tip = if (!adbOk && !EnvironmentUtils.isRooted()) "• " + context.getString(R.string.doctor_tip_adb) else null,
                ),
            )
            if (!adbOk && !EnvironmentUtils.isRooted()) tips.add("• " + context.getString(R.string.doctor_tip_adb))

            // 2b. Local Network Permission (Android 16+ / 17+)
            if (Build.VERSION.SDK_INT >= 36) {
                val lnpGranted = LocalNetworkPermission.granted(context)
                val lnpPermName = if (Build.VERSION.SDK_INT >= 37) "ACCESS_LOCAL_NETWORK" else "NEARBY_WIFI_DEVICES"
                val canAutoGrantLnp = !lnpGranted && DeviceOptimizer.hasPrivilegedShell()
                checks.add(
                    CheckItem(
                        id = "local_network",
                        title = context.getString(R.string.doctor_check_local_network),
                        status = if (lnpGranted) context.getString(R.string.doctor_status_ok) else context.getString(R.string.doctor_status_not_enabled),
                        ok = lnpGranted,
                        canAutoFix = canAutoGrantLnp,
                        autoFix = { ctx ->
                            val perm = LocalNetworkPermission.required()
                            if (perm != null) DeviceOptimizer.grantSelf(ctx, perm) else false
                        },
                        tip = if (!lnpGranted) "• " + context.getString(R.string.doctor_tip_local_network, lnpPermName) else null,
                    ),
                )
                if (!lnpGranted) tips.add("• " + context.getString(R.string.doctor_tip_local_network, lnpPermName))
            }

            // 3. Root Access
            val isRooted = EnvironmentUtils.isRooted()
            checks.add(
                CheckItem(
                    id = "root_access",
                    title = context.getString(R.string.doctor_check_root),
                    status = if (isRooted) context.getString(R.string.doctor_status_ok) else context.getString(R.string.doctor_status_not_enabled),
                    ok = isRooted,
                    canAutoFix = false,
                ),
            )

            // 4. Shizuku Server
            val isRunning = ShizukuStateMachine.isRunning()
            val canRestartServer = !isRunning && (isRooted || adbOk)
            checks.add(
                CheckItem(
                    id = "shizuku_server",
                    title = context.getString(R.string.doctor_check_server),
                    status = if (isRunning) context.getString(R.string.doctor_status_running) else context.getString(R.string.doctor_status_stopped),
                    ok = isRunning,
                    canAutoFix = canRestartServer,
                    autoFix = { ctx ->
                        ShizukuReceiverStarter.start(ctx, forceStart = true)
                        delay(3000)
                        ShizukuStateMachine.isRunning()
                    },
                ),
            )

            // 5. Secure Settings (WRITE_SECURE_SETTINGS)
            val canAutoGrantSecure = !hasSecure && DeviceOptimizer.hasPrivilegedShell()
            checks.add(
                CheckItem(
                    id = "secure_settings",
                    title = context.getString(R.string.doctor_check_secure_settings),
                    status = if (hasSecure) context.getString(R.string.doctor_status_ok) else context.getString(R.string.doctor_status_not_enabled),
                    ok = hasSecure,
                    canAutoFix = canAutoGrantSecure,
                    autoFix = { ctx ->
                        DeviceOptimizer.grantSelf(ctx, Manifest.permission.WRITE_SECURE_SETTINGS)
                    },
                ),
            )

            // 5b. Accessibility
            val isAccessibilityEnabled = SettingsHelper.isAccessibilityServiceEnabled(context, AdbPairingAccessibilityService::class.java)
            checks.add(
                CheckItem(
                    id = "accessibility_service",
                    title = context.getString(R.string.doctor_check_accessibility),
                    status = if (isAccessibilityEnabled) context.getString(R.string.doctor_status_ok) else context.getString(R.string.doctor_status_not_enabled),
                    ok = isAccessibilityEnabled,
                    canAutoFix = !isAccessibilityEnabled && (hasSecure || DeviceOptimizer.hasPrivilegedShell()),
                    autoFix = { ctx ->
                        ctx.tryEnableAccessibilityAutomatically()
                    },
                    // The pairing helper turns itself off a minute after Settings is left, so
                    // enabling it whenever the server stopped only re-armed it, over and over.
                    autoHeal = false,
                ),
            )

            // 5c. Device Hardening & Doze Whitelisting
            val isHardeningActive = isIgnoring && hasSecure
            checks.add(
                CheckItem(
                    id = "device_hardening",
                    title = context.getString(R.string.doctor_check_device_hardening),
                    status = if (isHardeningActive) context.getString(R.string.doctor_status_ok) else context.getString(R.string.doctor_status_fix_available),
                    ok = isHardeningActive,
                    canAutoFix = !isHardeningActive && DeviceOptimizer.hasPrivilegedShell(),
                    autoFix = { ctx -> DeviceOptimizer.applyFixes(ctx) },
                ),
            )

            // 5d. Google Wallet & Play Integrity
            val hasTmpSu =
                File("/data/local/tmp/su").exists() ||
                    File("/data/local/su").exists() ||
                    File("/data/local/bin/su").exists() ||
                    File("/data/local/xbin/su").exists()
            val isMagiskMocking = ShizukuSettings.isRootMagiskMockingEnabled()
            val isBusyboxMocking = ShizukuSettings.isRootBusyboxMockingEnabled()
            val isSpoofing = ShizukuSettings.isSpoofDeviceEnabled()
            val walletSecurityOk = !hasTmpSu && !isMagiskMocking && !isBusyboxMocking && !isSpoofing

            val walletStatus =
                when {
                    hasTmpSu -> context.getString(R.string.doctor_wallet_risk_tmp_su)
                    isMagiskMocking -> context.getString(R.string.doctor_wallet_risk_magisk)
                    isBusyboxMocking -> context.getString(R.string.doctor_wallet_risk_busybox)
                    isSpoofing -> context.getString(R.string.doctor_wallet_risk_spoof)
                    else -> context.getString(R.string.doctor_wallet_ok)
                }
            checks.add(
                CheckItem(
                    id = "wallet_integrity",
                    title = context.getString(R.string.doctor_check_wallet_integrity),
                    status = walletStatus,
                    ok = walletSecurityOk,
                    canAutoFix = !walletSecurityOk && DeviceOptimizer.hasPrivilegedShell(),
                    autoFix = { ctx ->
                        RootCompatHelper.cleanupBridgeFromTmp(ctx)
                        if (isMagiskMocking) ShizukuSettings.setRootMagiskMockingEnabled(false)
                        if (isBusyboxMocking) ShizukuSettings.setRootBusyboxMockingEnabled(false)
                        if (ShizukuSettings.isSuBridgeEnabled()) ShizukuSettings.setSuBridgeEnabled(false)
                        if (isSpoofing) ShizukuSettings.setSpoofDeviceEnabled(false)
                        ShizukuSettings.syncAllPlusFeaturesToServer()
                        RootCompatHelper.refreshGoogleWalletAttestation(ctx)
                        true
                    },
                    tip = if (!walletSecurityOk) "• " + context.getString(R.string.doctor_tip_wallet_integrity) else null,
                ),
            )
            if (!walletSecurityOk) tips.add("• " + context.getString(R.string.doctor_tip_wallet_integrity))

            // 6. Xiaomi Restricted ADB + HyperOS background kill
            if (EnvironmentUtils.isXiaomi()) {
                checks.add(
                    CheckItem(
                        id = "xiaomi_adb",
                        title = context.getString(R.string.doctor_check_xiaomi_adb),
                        status = context.getString(R.string.doctor_status_xiaomi_adb_unknown),
                        ok = false,
                        canAutoFix = false,
                        tip = "• " + context.getString(R.string.doctor_tip_xiaomi),
                    ),
                )
                tips.add("• " + context.getString(R.string.doctor_tip_xiaomi))

                checks.add(
                    CheckItem(
                        id = "xiaomi_autostart",
                        title = context.getString(R.string.doctor_check_xiaomi_autostart),
                        status = context.getString(R.string.doctor_status_xiaomi_autostart_unknown),
                        ok = false,
                        canAutoFix = false,
                        tip = "• " + context.getString(R.string.doctor_tip_xiaomi_battery),
                    ),
                )
                tips.add("• " + context.getString(R.string.doctor_tip_xiaomi_battery))
            }

            // 6b. Oppo/OnePlus Restricted ADB (ColorOS/OxygenOS)
            if (EnvironmentUtils.isOppo() || EnvironmentUtils.isOnePlus()) {
                checks.add(
                    CheckItem(
                        id = "oppo_permission",
                        title = context.getString(R.string.doctor_check_oppo_permission),
                        status = context.getString(R.string.doctor_status_manual_check),
                        ok = false,
                        canAutoFix = false,
                        tip = "• " + context.getString(R.string.doctor_tip_oppo_permission),
                    ),
                )
                tips.add("• " + context.getString(R.string.doctor_tip_oppo_permission))

                checks.add(
                    CheckItem(
                        id = "oppo_battery",
                        title = context.getString(R.string.doctor_check_oppo_battery),
                        status = context.getString(R.string.doctor_status_oppo_battery_unknown),
                        ok = false,
                        canAutoFix = false,
                        tip = "• " + context.getString(R.string.doctor_tip_oppo_battery),
                    ),
                )
                tips.add("• " + context.getString(R.string.doctor_tip_oppo_battery))
            }

            // 6c. TCL Device Polish
            if (EnvironmentUtils.isTCL()) {
                checks.add(
                    CheckItem(
                        id = "tcl_autostart",
                        title = context.getString(R.string.doctor_check_tcl_autostart),
                        status = context.getString(R.string.doctor_status_tcl_autostart_unknown),
                        ok = false,
                        canAutoFix = false,
                        tip = "• " + context.getString(R.string.doctor_tip_tcl_background),
                    ),
                )
                tips.add("• " + context.getString(R.string.doctor_tip_tcl_background))
            }

            // 7. Samsung Auto Blocker (One UI 6.1+)
            if (EnvironmentUtils.isSamsung()) {
                val oneUi = EnvironmentUtils.getOneUiVersion()
                val isAutoBlockerOff = SettingsHelper.isSamsungAutoBlockerDisabled(context)
                checks.add(
                    CheckItem(
                        id = "samsung_autoblocker",
                        title = context.getString(R.string.doctor_check_samsung_autoblocker),
                        status = if (isAutoBlockerOff) context.getString(R.string.doctor_status_ok) else context.getString(R.string.doctor_status_autoblocker_on),
                        ok = isAutoBlockerOff,
                        canAutoFix = !isAutoBlockerOff && hasSecure,
                        autoFix = { ctx -> trySecureSettingOff(ctx, "rampart_enabled") },
                    ),
                )

                if (oneUi >= 8) {
                    val isMaxRestrictionsOff = SettingsHelper.isSamsungMaxRestrictionsDisabled(context)
                    checks.add(
                        CheckItem(
                            id = "samsung_max_restrictions",
                            title = context.getString(R.string.doctor_check_samsung_max_restrictions),
                            status = if (isMaxRestrictionsOff) context.getString(R.string.doctor_status_ok) else context.getString(R.string.doctor_status_samsung_max_restrictions_on),
                            ok = isMaxRestrictionsOff,
                            canAutoFix = !isMaxRestrictionsOff && hasSecure,
                            autoFix = { ctx -> trySecureSettingOff(ctx, "rampart_max_restrictions_enabled") },
                        ),
                    )
                }

                if (oneUi >= 6) {
                    tips.add("• " + context.getString(R.string.doctor_tip_samsung_autoblocker))
                    if (oneUi >= 8) tips.add("• " + context.getString(R.string.doctor_tip_oneui8_max_restrictions))
                    tips.add("• " + context.getString(R.string.doctor_tip_oneui_connectivity))
                    tips.add("• " + context.getString(R.string.doctor_tip_s22_ultra))
                }
            }

            // 8. Secondary User detection
            if (EnvironmentUtils.isSecondaryUser()) {
                checks.add(
                    CheckItem(
                        id = "secondary_user",
                        title = context.getString(R.string.doctor_check_secondary_user),
                        status = context.getString(R.string.doctor_status_detected),
                        ok = false,
                        canAutoFix = false,
                        tip = "• " + context.getString(R.string.doctor_tip_secure_folder),
                    ),
                )
                tips.add("• " + context.getString(R.string.doctor_tip_secure_folder))
            }

            // 8b. SU Bridge — Android 16+ ADB mode limitation
            if (Build.VERSION.SDK_INT >= 36) {
                val isAdbMode =
                    try {
                        Shizuku.pingBinder() && Shizuku.getUid() == 2000
                    } catch (_: Exception) {
                        false
                    }
                if (isAdbMode) {
                    val hasExportedPath = ShizukuSettings.getExportDirUri() != null
                    checks.add(
                        CheckItem(
                            id = "su_bridge_a16",
                            title = context.getString(R.string.doctor_check_su_bridge_a16),
                            status =
                                if (hasExportedPath) {
                                    context.getString(R.string.doctor_status_ok) + " — " + context.getString(R.string.doctor_status_su_bridge_path_set)
                                } else {
                                    context.getString(R.string.doctor_status_su_bridge_no_path)
                                },
                            ok = hasExportedPath,
                            canAutoFix = false,
                            tip = if (!hasExportedPath) "• " + context.getString(R.string.doctor_tip_su_bridge_a16) else null,
                        ),
                    )
                    if (!hasExportedPath) {
                        tips.add("• " + context.getString(R.string.doctor_tip_su_bridge_a16))
                    }
                }
            }

            // 9. Background Limits (Android 9+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val am = context.getSystemService(android.app.ActivityManager::class.java)
                val isRestricted = am?.isBackgroundRestricted == true
                checks.add(
                    CheckItem(
                        id = "background_limits",
                        title = context.getString(R.string.doctor_check_background),
                        status = if (isRestricted) context.getString(R.string.doctor_status_optimized) else context.getString(R.string.doctor_status_ok),
                        ok = !isRestricted,
                        canAutoFix = isRestricted && DeviceOptimizer.hasPrivilegedShell(),
                        autoFix = { ctx -> DeviceOptimizer.protectSelf(ctx) },
                    ),
                )
                if (isRestricted) tips.add("• " + context.getString(R.string.doctor_tip_background_restricted))
            }

            // 10. Phantom Process Killer (Android 12+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                checks.add(
                    CheckItem(
                        id = "phantom_process",
                        title = context.getString(R.string.doctor_check_phantom_process),
                        status = context.getString(R.string.doctor_status_manual_check),
                        ok = true,
                        canAutoFix = ShizukuStateMachine.isRunning(),
                        autoFix = {
                            try {
                                if (Shizuku.pingBinder()) {
                                    val p = Shizuku.newProcess(arrayOf("device_config", "put", "activity_manager", "max_phantom_processes", "2147483647"), null, null)
                                    p?.waitFor()
                                    p?.destroy()
                                    true
                                } else {
                                    false
                                }
                            } catch (e: Exception) {
                                Timber.w(e, "Doctor auto-fix phantom process failed")
                                false
                            }
                        },
                    ),
                )
                tips.add("• " + context.getString(R.string.doctor_tip_phantom_process))
            }

            // 11. Samsung Auto Restart & Sleeping Apps
            if (EnvironmentUtils.isSamsung()) {
                tips.add("• " + context.getString(R.string.doctor_tip_samsung_optimization))
                tips.add("• " + context.getString(R.string.doctor_tip_samsung_sleeping))

                val board = Build.HARDWARE.lowercase()
                if (board.contains("exynos")) {
                    tips.add("• " + context.getString(R.string.doctor_tip_s22_ultra_exynos))
                } else if (board.contains("qcom") || board.contains("snapdragon")) {
                    tips.add("• " + context.getString(R.string.doctor_tip_s22_ultra_snapdragon))
                }
            }

            val issues = checks.count { !it.ok }
            val fixable = checks.count { !it.ok && it.canAutoFix }
            DiagnosticResult(checks, issues, fixable, tips)
        }

    suspend fun autoHeal(
        context: Context,
        onProgress: ((step: String) -> Unit)? = null,
    ): AutoHealReport =
        withContext(Dispatchers.IO) {
            val applied = mutableListOf<String>()
            val remaining = mutableListOf<String>()

            val diag = runFullDiagnostics(context)
            for (check in diag.checks) {
                if (!check.ok && check.canAutoFix && check.autoHeal && check.autoFix != null) {
                    onProgress?.invoke(check.title)
                    try {
                        val success = check.autoFix.invoke(context)
                        if (success) {
                            applied.add(check.title)
                            Timber.d("ServiceDoctor: Auto-healed ${check.id}")
                        } else {
                            remaining.add(check.title)
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "ServiceDoctor: Failed auto-healing ${check.id}")
                        remaining.add(check.title)
                    }
                } else if (!check.ok) {
                    remaining.add(check.title)
                }
            }

            // Post-healing verification
            val postDiag = runFullDiagnostics(context)
            val resolvedCount = applied.size

            try {
                ActivityLogManager.log(
                    "ServiceDoctor",
                    context.packageName,
                    "Adaptive Auto-Repair completed: $resolvedCount fixed, ${postDiag.issuesCount} remaining",
                )
            } catch (_: Exception) {
            }

            AutoHealReport(resolvedCount, applied, remaining)
        }

    fun triggerAdaptiveAutoHealAsync(context: Context) {
        if (!ShizukuSettings.isAdaptiveDoctorAutoHealEnabled()) return
        if (isAutoHealingActive.compareAndSet(false, true)) {
            doctorScope.launch {
                try {
                    Sentry.addBreadcrumb(
                        Breadcrumb("ServiceDoctor: Triggering adaptive background self-healing").apply {
                            category = "shizuku.doctor"
                        },
                    )
                    delay(1500) // Brief delay to let transient state settle
                    val report = autoHeal(context)
                    Timber.i("ServiceDoctor adaptive background healing: ${report.resolvedCount} fixed")
                } catch (e: Exception) {
                    Timber.w(e, "ServiceDoctor adaptive healing exception")
                } finally {
                    isAutoHealingActive.set(false)
                }
            }
        }
    }
}
