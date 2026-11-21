package af.shizuku.manager.adb
import af.shizuku.core.ui.AppBarActivity
import af.shizuku.manager.AppConstants
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.databinding.AdbPairingTutorialActivityBinding
import af.shizuku.manager.home.StartWirelessAdbViewHolder
import af.shizuku.manager.home.showAccessibilityDialog
import af.shizuku.manager.home.tryEnableAccessibilityAutomatically
import af.shizuku.manager.utils.MotionUtils.applySpringTouch
import af.shizuku.manager.utils.SettingsHelper
import af.shizuku.manager.utils.SettingsPage
import af.shizuku.manager.utils.ShizukuStateMachine
import android.Manifest
import android.app.AppOpsManager
import android.app.ForegroundServiceStartNotAllowedException
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.compatibility.DeviceCompatibility
import timber.log.Timber

@RequiresApi(Build.VERSION_CODES.R)
class AdbPairingTutorialActivity : AppBarActivity() {
    private lateinit var binding: AdbPairingTutorialActivityBinding

    private var notificationEnabled: Boolean = false

    private lateinit var statePanel: PairingStatePanel

    // What blocked pairing at the last render; when it clears, the search is (re)started.
    private var lastBlocker: PairingBlocker? = null

    // Whether the local network request was already shown this visit: after a "don't ask again"
    // denial it returns at once, so the button switches to App info.
    private var localNetworkAsked = false

    // Developer options / Wireless debugging switched in Settings (split screen, or the user
    // returning) re-render at once instead of waiting for the next resume.
    private val settingsObserver =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                if (!isFinishing) renderState()
            }
        }

    // Only a request the user asked for (the "notification options" button) may send them to
    // system settings on denial; the automatic request on first open must never do that.
    private var notifRequestFromUser = false

    // Registered unconditionally (required before onStart); only invoked on API 33+.
    private val notifPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            notificationEnabled = PairingPreflight.notificationsEnabled(this)
            syncNotificationEnabled()
            renderState()
            val fromUser = notifRequestFromUser
            notifRequestFromUser = false
            if (notificationEnabled || granted) {
                startPairingService()
            } else if (
                fromUser &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
            ) {
                // Permanently denied — guide user to settings
                Toast.makeText(this, R.string.dialog_notif_permission_denied, Toast.LENGTH_LONG).show()
                SettingsPage.Notifications.NotificationSettings.launch(this)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val context = this

        if (!af.shizuku.manager.ShizukuApplication.isAdbNativeAvailable) {
            Toast.makeText(this, R.string.adb_native_unavailable, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        binding = AdbPairingTutorialActivityBinding.inflate(layoutInflater, rootView, true)
        // Before CardBorder, so the state card follows the same border setting as the others.
        statePanel = PairingStatePanel.inflate(binding.stateContainer)
        af.shizuku.manager.utils.CardBorder
            .applyAll(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars =
                insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
                )
            view.setPadding(bars.left, 0, bars.right, bars.bottom)
            insets
        }

        binding.header.apply {
            headerIcon.setImageResource(R.drawable.ic_wadb_24)
            // Same seedKey as the originating Home card (StartWirelessAdbViewHolder) so a
            // shared-element transition into this screen doesn't visibly snap the icon's
            // shape/color back to the static droplet default mid-animation.
            af.shizuku.manager.utils.IconStyleHelper.applyToCardIcon(
                headerIcon,
                headerIcon.drawable,
                "home_start_wireless_adb",
            )
            headerIcon.transitionName = "icon_wireless_adb"
            headerTitle.setText(R.string.home_wireless_adb_title)
        }

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // A failure or success from an earlier visit isn't news any more: start from the top.
        if (savedInstanceState == null) {
            val phase = AdbPairingService.state.value.phase
            if (phase == AdbPairingService.Phase.FAILED || phase == AdbPairingService.Phase.PAIRED) {
                AdbPairingService.setState(AdbPairingService.Phase.IDLE)
            }
        }

        notificationEnabled = PairingPreflight.notificationsEnabled(this)

        if (notificationEnabled) {
            startPairingService()
        } else if (savedInstanceState == null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            // Ask once per visit (not on rotation). A permanent denial comes back at once with no
            // dialog; the warning card then offers the way to settings.
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        binding.apply {
            notification.applySpringTouch()
            network.applySpringTouch()
            miui.applySpringTouch()
            developerOptions.applySpringTouch()
            manualPairing.applySpringTouch()
            autoFillCode.applySpringTouch()
            syncNotificationEnabled()

            if (DeviceCompatibility.isMiui()) {
                miui.isVisible = true
            }
            legacyPairingSwitch.isChecked = ShizukuSettings.getLegacyPairing()
            legacyPairingSwitch.setOnCheckedChangeListener { _, checked -> setLegacyPairing(checked) }

            developerOptions.setOnClickListener {
                SettingsHelper.launchOrHighlightWirelessDebugging(context)
            }

            manualPairing.setOnClickListener {
                af.shizuku.manager.home
                    .AdbPairDialogFragment()
                    .show(supportFragmentManager, null)
            }

            autoFillCode.setOnClickListener {
                enableCodeAutoFill()
            }

            startNow.applySpringTouch()
            startNow.setOnClickListener {
                StartWirelessAdbViewHolder.start(context, lifecycleScope)
            }
        }

        observePairingState()
    }

    private fun observePairingState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AdbPairingService.state.collect { renderPairingState(it) }
            }
        }
    }

    private fun renderPairingState(state: AdbPairingService.State) {
        statePanel.render(specFor(state))
    }

    private fun renderState() {
        if (::statePanel.isInitialized) renderPairingState(AdbPairingService.state.value)
    }

    // Before a pairing dialog is found, the first thing in the way wins; once one is open (or a
    // code was sent) the pairing itself is what matters.
    private fun specFor(state: AdbPairingService.State): PairingStateSpec {
        val phase = state.phase
        val waiting =
            phase == AdbPairingService.Phase.IDLE ||
                phase == AdbPairingService.Phase.SEARCHING ||
                (phase == AdbPairingService.Phase.FAILED && state.reason == AdbPairingService.Reason.TIMED_OUT)
        val blocker = if (waiting) PairingPreflight.check(this) else null
        if (lastBlocker != null && blocker == null && waiting && phase != AdbPairingService.Phase.SEARCHING) {
            // The user fixed what was in the way; start waiting for the pairing dialog again.
            window?.decorView?.post { if (!isFinishing) startPairingService() }
        }
        lastBlocker = blocker
        if (blocker != null) return blockerSpec(blocker)
        return when (phase) {
            AdbPairingService.Phase.IDLE, AdbPairingService.Phase.SEARCHING ->
                if (state.reason == AdbPairingService.Reason.CODE_EXPIRED) {
                    spec(
                        "expired",
                        R.drawable.ic_warning_24,
                        PairingTone.NEEDS_YOU,
                        R.string.pairing_v2_expired_title,
                        R.string.pairing_v2_expired_text,
                        primary = PairingAction(R.string.pairing_v2_action_new_code) { openWirelessDebugging() },
                    )
                } else {
                    spec(
                        "searching",
                        R.drawable.ic_wadb_24,
                        PairingTone.WORKING,
                        R.string.pairing_v2_searching_title,
                        R.string.pairing_v2_searching_text,
                        primary = PairingAction(R.string.pairing_v2_action_open_wd) { openWirelessDebugging() },
                    )
                }
            AdbPairingService.Phase.FOUND -> {
                val legacy = ShizukuSettings.getLegacyPairing()
                spec(
                    "found:$legacy",
                    R.drawable.ic_outline_notifications_active_24,
                    PairingTone.NEEDS_YOU,
                    R.string.pairing_v2_found_title,
                    if (legacy) R.string.pairing_v2_found_text_legacy else R.string.pairing_v2_found_text,
                    // Side by side with Settings, the code can go straight into the in-app dialog.
                    primary =
                        if (isInMultiWindowMode) PairingAction(R.string.pairing_v2_action_enter_here) { openCodeDialog() } else null,
                    secondary =
                        if (!legacy) PairingAction(R.string.pairing_v2_action_use_dialog) { setLegacyPairing(true) } else null,
                )
            }
            AdbPairingService.Phase.PAIRING ->
                spec(
                    "pairing",
                    R.drawable.ic_wadb_24,
                    PairingTone.WORKING,
                    R.string.pairing_v2_pairing_title,
                    R.string.pairing_v2_pairing_text,
                )
            AdbPairingService.Phase.PAIRED ->
                spec(
                    "paired",
                    R.drawable.ic_check_24,
                    PairingTone.DONE,
                    R.string.pairing_v2_paired_title,
                    R.string.pairing_v2_paired_text,
                    primary =
                        PairingAction(R.string.pairing_v2_action_start_now) {
                            StartWirelessAdbViewHolder.start(this, lifecycleScope)
                        },
                )
            AdbPairingService.Phase.STARTING ->
                spec(
                    "starting",
                    R.drawable.ic_check_24,
                    PairingTone.DONE,
                    R.string.pairing_v2_starting_title,
                    R.string.pairing_v2_starting_text,
                )
            AdbPairingService.Phase.FAILED -> failureSpec(state.reason)
        }
    }

    private fun failureSpec(reason: AdbPairingService.Reason): PairingStateSpec {
        val tryAgain = PairingAction(R.string.pairing_v2_action_try_again) { retryPairing() }
        val doctor = PairingAction(R.string.pairing_v2_action_service_doctor) { PairingPreflight.openServiceDoctor(this) }
        return when (reason) {
            AdbPairingService.Reason.WRONG_CODE ->
                spec(
                    "failed:wrong",
                    R.drawable.ic_code_24,
                    PairingTone.PROBLEM,
                    R.string.pairing_v2_wrong_code_title,
                    R.string.pairing_v2_wrong_code_text,
                    primary = tryAgain,
                )
            AdbPairingService.Reason.CANT_CONNECT, AdbPairingService.Reason.CODE_EXPIRED ->
                spec(
                    "failed:connect",
                    R.drawable.ic_server_error_24,
                    PairingTone.PROBLEM,
                    R.string.pairing_v2_cant_connect_title,
                    R.string.pairing_v2_cant_connect_text,
                    primary = PairingAction(R.string.pairing_v2_action_new_code) { retryPairing() },
                )
            AdbPairingService.Reason.KEY_STORE ->
                spec(
                    "failed:key",
                    R.drawable.ic_server_error_24,
                    PairingTone.PROBLEM,
                    R.string.pairing_v2_key_title,
                    R.string.pairing_v2_key_text,
                    primary = doctor,
                    secondary = tryAgain,
                )
            AdbPairingService.Reason.TIMED_OUT ->
                spec(
                    "failed:timeout",
                    R.drawable.ic_wadb_24,
                    PairingTone.NEEDS_YOU,
                    R.string.pairing_v2_timed_out_title,
                    R.string.pairing_v2_timed_out_text,
                    primary = PairingAction(R.string.pairing_v2_action_look_again) { startPairingService() },
                )
            else ->
                spec(
                    "failed:unknown",
                    R.drawable.ic_server_error_24,
                    PairingTone.PROBLEM,
                    R.string.pairing_v2_failed_title,
                    R.string.pairing_v2_failed_text,
                    primary = tryAgain,
                    secondary = doctor,
                )
        }
    }

    private fun blockerSpec(blocker: PairingBlocker): PairingStateSpec =
        when (blocker) {
            PairingBlocker.DEVELOPER_OPTIONS_OFF ->
                spec(
                    "blocker:devopts",
                    R.drawable.ic_settings_outline_24,
                    PairingTone.NEEDS_YOU,
                    R.string.pairing_v2_devopts_off_title,
                    R.string.pairing_v2_devopts_off_text,
                    primary = PairingAction(R.string.pairing_v2_action_open_about) { PairingPreflight.openAboutPhone(this) },
                )
            PairingBlocker.NO_WIFI -> {
                val hotspot = PairingPreflight.hotspotHelps
                spec(
                    "blocker:wifi",
                    R.drawable.ic_wifi_24,
                    PairingTone.NEEDS_YOU,
                    R.string.pairing_v2_no_wifi_title,
                    if (hotspot) R.string.pairing_v2_no_wifi_text_hotspot else R.string.pairing_v2_no_wifi_text,
                    primary = PairingAction(R.string.pairing_v2_action_wifi) { PairingPreflight.openWifi(this) },
                    secondary =
                        if (hotspot) PairingAction(R.string.pairing_v2_action_hotspot) { PairingPreflight.openHotspot(this) } else null,
                )
            }
            PairingBlocker.WIRELESS_DEBUGGING_OFF ->
                spec(
                    "blocker:wd",
                    R.drawable.ic_wadb_24,
                    PairingTone.NEEDS_YOU,
                    R.string.pairing_v2_wd_off_title,
                    R.string.pairing_v2_wd_off_text,
                    primary = PairingAction(R.string.pairing_v2_action_open_wd) { openWirelessDebugging() },
                )
            PairingBlocker.LOCAL_NETWORK_DENIED -> {
                val permission = LocalNetworkPermission.required()
                val askAgain = !localNetworkAsked || (permission != null && shouldShowRequestPermissionRationale(permission))
                spec(
                    "blocker:localnet",
                    R.drawable.ic_wifi_24,
                    PairingTone.NEEDS_YOU,
                    R.string.pairing_v2_local_net_title,
                    R.string.pairing_v2_local_net_text,
                    primary =
                        if (askAgain) {
                            PairingAction(R.string.pairing_v2_action_allow) { startPairingService() }
                        } else {
                            PairingAction(R.string.pairing_v2_action_app_info) { PairingPreflight.openAppInfo(this) }
                        },
                )
            }
            PairingBlocker.NOTIFICATIONS_BLOCKED ->
                spec(
                    "blocker:notifications",
                    R.drawable.ic_outline_notifications_active_24,
                    PairingTone.NEEDS_YOU,
                    R.string.pairing_v2_notif_blocked_title,
                    R.string.pairing_v2_notif_blocked_text,
                    primary = PairingAction(R.string.pairing_v2_action_allow_notifications) { requestOrOpenNotificationSettings() },
                    secondary = PairingAction(R.string.pairing_v2_action_enter_here) { openCodeDialog() },
                )
        }

    private fun spec(
        key: String,
        icon: Int,
        tone: PairingTone,
        title: Int,
        text: Int,
        primary: PairingAction? = null,
        secondary: PairingAction? = null,
    ) = PairingStateSpec(key, icon, tone, getText(title), getText(text), primary, secondary)

    private fun openWirelessDebugging() = SettingsHelper.launchOrHighlightWirelessDebugging(this)

    private fun openCodeDialog() {
        if (supportFragmentManager.isStateSaved) return
        af.shizuku.manager.home
            .AdbPairDialogFragment()
            .show(supportFragmentManager, null)
    }

    // A failed attempt ends the service; wait for a fresh pairing dialog and send the user to it.
    private fun retryPairing() {
        AdbPairingService.setState(AdbPairingService.Phase.IDLE)
        startPairingService()
        openWirelessDebugging()
    }

    // Legacy pairing: the notification opens a code dialog instead of an inline reply, for
    // notification shades (MIUI/HyperOS and some others) that drop the reply box.
    private fun setLegacyPairing(enabled: Boolean) {
        if (ShizukuSettings.getLegacyPairing() == enabled) return
        ShizukuSettings
            .getPreferences()
            .edit()
            .putBoolean(ShizukuSettings.Keys.KEY_LEGACY_PAIRING, enabled)
            .apply()
        if (binding.legacyPairingSwitch.isChecked != enabled) binding.legacyPairingSwitch.isChecked = enabled
        val phase = AdbPairingService.state.value.phase
        if (phase == AdbPairingService.Phase.SEARCHING || phase == AdbPairingService.Phase.FOUND) {
            runCatching { startService(AdbPairingService.refreshIntent(this)) }
                .onFailure { Timber.tag(AppConstants.TAG).w(it, "refresh pairing notification") }
        }
        if (enabled) Toast.makeText(this, R.string.pairing_v2_legacy_enabled, Toast.LENGTH_SHORT).show()
        renderState()
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        val targetTitle = getString(R.string.home_wireless_adb_title)
        supportActionBar?.title = ""
        toolbar?.title = ""
        binding.scrollView.setOnScrollChangeListener(
            androidx.core.widget.NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, _ ->
                val trigger = binding.header.root.bottom - (toolbar?.height ?: 0)
                if (scrollY >= trigger) {
                    if (supportActionBar?.title.isNullOrEmpty()) {
                        supportActionBar?.title = targetTitle
                        toolbar?.title = targetTitle
                    }
                } else {
                    if (!supportActionBar?.title.isNullOrEmpty()) {
                        supportActionBar?.title = ""
                        toolbar?.title = ""
                    }
                }
                val alpha =
                    (
                        1f - (
                            scrollY.toFloat() / (
                                binding.header.root.height
                                    .coerceAtLeast(1) * 0.8f
                            )
                        )
                    ).coerceIn(0f, 1f)
                binding.header.root.alpha = alpha
            },
        )
    }

    // Opt-in: reading the code off the Settings screen needs the pairing accessibility service.
    // It is switched on silently when the app can (WRITE_SECURE_SETTINGS from an earlier start, or
    // root); otherwise the shared dialog walks the user through turning it on.
    private fun enableCodeAutoFill() {
        lifecycleScope.launch {
            val enabled = withContext(Dispatchers.IO) { tryEnableAccessibilityAutomatically() }
            if (isFinishing) return@launch
            if (enabled) {
                Toast.makeText(this@AdbPairingTutorialActivity, R.string.adb_pairing_tutorial_autofill_ready, Toast.LENGTH_LONG).show()
                SettingsHelper.launchOrHighlightWirelessDebugging(this@AdbPairingTutorialActivity)
            } else {
                showAccessibilityDialog()
            }
        }
    }

    private fun requestOrOpenNotificationSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted =
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            if (!granted) {
                // Show rationale then request — or just request directly if first time
                notifRequestFromUser = true
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
            // Permission is granted but notifications are blocked at the channel level
        }
        // Pre-API 33 or channel-level block: open notification settings
        SettingsPage.Notifications.NotificationSettings.launch(this)
    }

    // The "code goes in a notification" note only helps when notifications work; when they
    // don't, the state card says so and offers the way out.
    private fun syncNotificationEnabled() {
        binding.notification.isVisible = notificationEnabled
    }

    private val runningListener: (ShizukuStateMachine.State) -> Unit = { state ->
        if (state == ShizukuStateMachine.State.RUNNING && !isFinishing) {
            window?.decorView?.post { if (!isFinishing) finish() }
        }
    }

    override fun onStart() {
        super.onStart()
        ShizukuStateMachine.addListener(runningListener)
        runCatching {
            contentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED),
                false,
                settingsObserver,
            )
            contentResolver.registerContentObserver(
                Settings.Global.getUriFor(PairingPreflight.ADB_WIFI_ENABLED),
                false,
                settingsObserver,
            )
        }
    }

    override fun onStop() {
        super.onStop()
        ShizukuStateMachine.removeListener(runningListener)
        runCatching { contentResolver.unregisterContentObserver(settingsObserver) }
    }

    override fun onResume() {
        super.onResume()

        // Paired while the user was in Settings, but the server start could not be queued in the
        // background (no WRITE_SECURE_SETTINGS yet): start it now that we are in front again.
        val state = ShizukuStateMachine.get()
        if (AdbPairingService.consumeRecentPairingNeedingStart() &&
            state != ShizukuStateMachine.State.RUNNING &&
            state != ShizukuStateMachine.State.STARTING
        ) {
            StartWirelessAdbViewHolder.start(this, lifecycleScope)
        }

        val newNotificationEnabled = PairingPreflight.notificationsEnabled(this)
        if (newNotificationEnabled != notificationEnabled) {
            notificationEnabled = newNotificationEnabled
            syncNotificationEnabled()

            if (newNotificationEnabled) {
                startPairingService()
            }
        }
        // Wi-Fi, Wireless debugging or a permission may have changed while the user was away.
        renderState()
    }

    override fun onMultiWindowModeChanged(
        isInMultiWindowMode: Boolean,
        newConfig: android.content.res.Configuration,
    ) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        renderState()
    }

    // Android 16+ gates mDNS discovery of the pairing service behind local-network access; request
    // it and only start pairing once the user has responded, so discovery can find the port (#317).
    private val localNetworkPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            renderState()
            // Start pairing whether or not the grant succeeded; a denial just means discovery/
            // connect fails and the service surfaces the error, rather than a silent no-op.
            doStartPairingService()
        }

    private fun startPairingService() {
        val permission = LocalNetworkPermission.required()
        if (permission != null && checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            localNetworkAsked = true
            localNetworkPermissionLauncher.launch(permission)
        } else {
            doStartPairingService()
        }
    }

    private fun doStartPairingService() {
        val intent = AdbPairingService.startIntent(this)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Throwable) {
            Timber.tag(AppConstants.TAG).e(e, "startForegroundService")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e is ForegroundServiceStartNotAllowedException
            ) {
                val appOps = getSystemService(AppOpsManager::class.java)
                val mode =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        appOps.unsafeCheckOpNoThrow("android:start_foreground", android.os.Process.myUid(), packageName)
                    } else {
                        @Suppress("DEPRECATION")
                        appOps.noteOpNoThrow("android:start_foreground", android.os.Process.myUid(), packageName, null, null)
                    }

                if (mode == AppOpsManager.MODE_ERRORED) {
                    Toast.makeText(this, R.string.adb_foreground_op_denied, Toast.LENGTH_LONG).show()
                }
                startService(intent)
            }
        }
    }
}
