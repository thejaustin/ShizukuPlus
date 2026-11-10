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
import af.shizuku.manager.utils.SamsungAutoBlocker
import af.shizuku.manager.utils.SettingsHelper
import af.shizuku.manager.utils.SettingsPage
import af.shizuku.manager.utils.ShizukuIssues
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
        // False for a fix that is only wanted when the user taps it, never in background healing.
        val autoHeal: Boolean = true,
        val optional: Boolean = false,
        val actionLabelRes: Int? = null,
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
        // Fixes that were tried and did not work; checks with no fix, or only a manual one, are
        // not in here (some never pass on some devices).
        val failedFixes: List<String> = emptyList(),
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

            // 3. Root Access: information, never a problem. Most devices can't be rooted, and ADB
            // mode does everything Shizuku+ needs. (Later: a device that can only be temp-rooted
            // could be offered adaptive repair that uses it while it lasts.)
            val isRooted = EnvironmentUtils.isRooted()
            val rootGranted =
                isRooted &&
                    runCatching {
                        com.topjohnwu.superuser.Shell
                            .isAppGrantedRoot() == true
                    }.getOrDefault(false)
            checks.add(
                CheckItem(
                    id = "root_access",
                    title = context.getString(R.string.doctor_check_root),
                    status =
                        context.getString(
                            when {
                                rootGranted -> R.string.doctor_status_ok
                                isRooted -> R.string.doctor_status_root_not_granted
                                else -> R.string.doctor_status_root_not_needed
                            },
                        ),
                    ok = true,
                    canAutoFix = false,
                ),
            )

            // 3b. Why recent starts failed (the most common causes in crash reports), with the
            // repair that fits. Tap-only: these change how Shizuku+ connects.
            if (!ShizukuStateMachine.isRunning()) {
                StartFailureLog.recent()?.let { recent -> startFailureCheck(context, recent)?.let(checks::add) }
            }

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
                    // Only when tapped. In background healing (run on every crash) this force-start
                    // raced Watchdog's own restart into a second server and could raise an ADB
                    // authorization dialog in the background. Restarts are Watchdog's job.
                    autoHeal = false,
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

            // 5b. Accessibility (optional pairing helper)
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
                    // Left to the user, who can allow it in Service Doctor.
                    autoHeal = ShizukuSettings.isDoctorHealPairingHelperEnabled(),
                    optional = true,
                    actionLabelRes = R.string.enable,
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
            // SU bridge is an optional root-style setting: if enabled, its presence in tmp is intentional
            // and must NOT be flagged as a defect or wiped by Service Doctor fixes.
            val isSuBridgeEnabled = ShizukuSettings.isSuBridgeEnabled()
            val hasTmpSu =
                File("/data/local/tmp/su").exists() ||
                    File("/data/local/su").exists() ||
                    File("/data/local/bin/su").exists() ||
                    File("/data/local/xbin/su").exists()
            val hasOrphanSu = hasTmpSu && !isSuBridgeEnabled
            val isMagiskMocking = ShizukuSettings.isRootMagiskMockingEnabled()
            val isBusyboxMocking = ShizukuSettings.isRootBusyboxMockingEnabled()
            val isSpoofing = ShizukuSettings.isSpoofDeviceEnabled()
            val walletSecurityOk = !hasOrphanSu && !isMagiskMocking && !isBusyboxMocking && !isSpoofing

            val walletStatus =
                when {
                    hasOrphanSu -> context.getString(R.string.doctor_wallet_risk_tmp_su)
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
                        // Clean up orphan su files only when SU bridge is NOT intentionally enabled
                        if (!ShizukuSettings.isSuBridgeEnabled()) {
                            RootCompatHelper.cleanupBridgeFromTmp(ctx)
                        }
                        if (isMagiskMocking) ShizukuSettings.setRootMagiskMockingEnabled(false)
                        if (isBusyboxMocking) ShizukuSettings.setRootBusyboxMockingEnabled(false)
                        // SU Bridge is an optional root style setting: DO NOT disable it in Service Doctor fixes
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

                // Auto Blocker is Samsung's own: writing its settings key only desyncs it, so the
                // fix opens its screen. Unknown (unreadable) is a manual check with that button,
                // never OK.
                fun statusText(
                    state: SamsungAutoBlocker.State,
                    onText: Int,
                ) = when (state) {
                    SamsungAutoBlocker.State.OFF -> context.getString(R.string.doctor_status_ok)
                    SamsungAutoBlocker.State.ON -> context.getString(onText)
                    SamsungAutoBlocker.State.UNKNOWN -> context.getString(R.string.doctor_status_manual_check)
                }
                val autoBlocker = SamsungAutoBlocker.state(context)
                checks.add(
                    CheckItem(
                        id = "samsung_autoblocker",
                        title = context.getString(R.string.doctor_check_samsung_autoblocker),
                        status = statusText(autoBlocker, R.string.doctor_status_autoblocker_on),
                        ok = autoBlocker == SamsungAutoBlocker.State.OFF,
                        manualAction = { ctx -> SettingsPage.Samsung.AutoBlocker.launch(ctx) },
                    ),
                )

                if (oneUi >= 8 && autoBlocker == SamsungAutoBlocker.State.ON) {
                    val maxRestrictions = SamsungAutoBlocker.maxRestrictionsState(context)
                    checks.add(
                        CheckItem(
                            id = "samsung_max_restrictions",
                            title = context.getString(R.string.doctor_check_samsung_max_restrictions),
                            status = statusText(maxRestrictions, R.string.doctor_status_samsung_max_restrictions_on),
                            ok = maxRestrictions == SamsungAutoBlocker.State.OFF,
                            manualAction = { ctx -> SettingsPage.Samsung.AutoBlocker.launch(ctx) },
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
            // Excluded from Service Doctor fixes: SU Bridge is an optional root style setting.
            // Only report informational status if SU Bridge is actively enabled by the user.
            if (Build.VERSION.SDK_INT >= 36 && ShizukuSettings.isSuBridgeEnabled()) {
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
                            ok = true,
                            canAutoFix = false,
                            tip = null,
                        ),
                    )
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

            val issues = checks.count { !it.ok && !it.optional }
            val fixable = checks.count { !it.ok && !it.optional && it.canAutoFix }
            DiagnosticResult(checks, issues, fixable, tips)
        }

    private fun startFailureCheck(
        context: Context,
        recent: StartFailureLog.Recent,
    ): CheckItem? =
        when (recent.kind) {
            StartFailureLog.Kind.MDNS_TIMEOUT ->
                if (recent.count < 2 || ShizukuSettings.getTcpMode()) {
                    null
                } else {
                    CheckItem(
                        id = "start_mdns_timeout",
                        title = context.getString(R.string.doctor_check_port_discovery),
                        status = context.getString(R.string.doctor_status_port_discovery_failed, recent.count),
                        ok = false,
                        canAutoFix = true,
                        autoHeal = false,
                        autoFix = {
                            ShizukuSettings.setTcpMode(true)
                            ShizukuSettings.setTcpPort(5555)
                            true
                        },
                        tip = "• " + context.getString(R.string.doctor_tip_port_discovery),
                    )
                }
            StartFailureLog.Kind.AUTH_TIMEOUT, StartFailureLog.Kind.ADB_KEY ->
                if (recent.kind == StartFailureLog.Kind.AUTH_TIMEOUT && recent.count < 2) {
                    null
                } else {
                    CheckItem(
                        id = "start_adb_key",
                        title = context.getString(R.string.doctor_check_adb_key),
                        status =
                            context.getString(
                                if (recent.kind == StartFailureLog.Kind.ADB_KEY) R.string.doctor_status_adb_key_broken else R.string.doctor_status_adb_auth_unanswered,
                            ),
                        ok = false,
                        canAutoFix = true,
                        autoHeal = false,
                        autoFix = {
                            af.shizuku.manager.adb.AdbKeyReset
                                .reset()
                                .also { if (it) StartFailureLog.clear() }
                        },
                        tip = "• " + context.getString(R.string.doctor_tip_adb_key),
                    )
                }
        }

    /** Fixes the user unchecked in "Fix all"; background self-healing skips them too. */
    fun excludedFixes(): Set<String> = ShizukuSettings.getPreferences()?.getStringSet(KEY_EXCLUDED_FIXES, null)?.toSet() ?: emptySet()

    fun setExcludedFixes(ids: Set<String>) {
        ShizukuSettings
            .getPreferences()
            ?.edit()
            ?.putStringSet(KEY_EXCLUDED_FIXES, ids)
            ?.apply()
    }

    private const val KEY_EXCLUDED_FIXES = "doctor_excluded_fixes"

    /** Checks "Fix all" can repair right now: failing, non-optional, with an automatic fix. */
    fun fixableChecks(diag: DiagnosticResult): List<CheckItem> = diag.checks.filter { !it.ok && !it.optional && it.canAutoFix && it.autoFix != null }

    /**
     * [tapped]: the user pressed "Fix all", so fixes kept out of background healing run too.
     * [only]: the fixes the user picked; null means all of them except [excludedFixes].
     */
    suspend fun autoHeal(
        context: Context,
        onProgress: ((step: String) -> Unit)? = null,
        tapped: Boolean = false,
        only: Set<String>? = null,
    ): AutoHealReport =
        withContext(Dispatchers.IO) {
            val applied = mutableListOf<String>()
            val remaining = mutableListOf<String>()
            val failed = mutableListOf<String>()

            val diag = runFullDiagnostics(context)
            val excluded = if (only == null) excludedFixes() else emptySet()
            for (check in diag.checks) {
                val chosen = if (only != null) check.id in only else check.id !in excluded
                if (!check.ok && check.canAutoFix && (check.autoHeal || tapped) && check.autoFix != null && chosen) {
                    onProgress?.invoke(check.title)
                    try {
                        val success = check.autoFix.invoke(context)
                        if (success) {
                            applied.add(check.title)
                            Timber.d("ServiceDoctor: Auto-healed ${check.id}")
                        } else {
                            remaining.add(check.title)
                            failed.add(check.title)
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "ServiceDoctor: Failed auto-healing ${check.id}")
                        remaining.add(check.title)
                        failed.add(check.title)
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

            AutoHealReport(resolvedCount, applied, remaining, failed)
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
                    reportFailedFixes(context, report.failedFixes)
                } catch (e: Exception) {
                    Timber.w(e, "ServiceDoctor adaptive healing exception")
                } finally {
                    isAutoHealingActive.set(false)
                }
            }
        }
    }

    /**
     * Background self-healing could not fix [failed] (empty: everything it tried worked). Shown
     * through the helper notifications when the user asked for it; a manual run is not reported,
     * as its results are already on screen.
     */
    fun reportFailedFixes(
        context: Context,
        failed: List<String>,
    ) {
        if (failed.isEmpty() || !ShizukuSettings.isDoctorReportFailedFixesEnabled()) {
            ShizukuIssues.clear(context, ShizukuIssues.KEY_DOCTOR)
            return
        }
        ShizukuIssues.report(
            context,
            ShizukuIssues.Issue(
                ShizukuIssues.KEY_DOCTOR,
                ShizukuIssues.Severity.PROBLEM,
                context.resources.getQuantityString(R.plurals.issue_doctor_failed_title, failed.size, failed.size),
                failed.joinToString(", "),
                listOf(ShizukuIssues.openDoctorAction(context)),
                ShizukuIssues.openDoctorAction(context).intent,
            ),
        )
    }
}
