package af.shizuku.manager.adb
import af.shizuku.core.ui.AppBarActivity
import af.shizuku.manager.AppConstants
import af.shizuku.manager.R
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
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
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

    // Only a request the user asked for (the "notification options" button) may send them to
    // system settings on denial; the automatic request on first open must never do that.
    private var notifRequestFromUser = false

    // Registered unconditionally (required before onStart); only invoked on API 33+.
    private val notifPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            notificationEnabled = isNotificationEnabled()
            syncNotificationEnabled()
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

        notificationEnabled = isNotificationEnabled()

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
            notificationDisabled.applySpringTouch()
            network.applySpringTouch()
            miui.applySpringTouch()
            developerOptions.applySpringTouch()
            manualPairing.applySpringTouch()
            autoFillCode.applySpringTouch()
            syncNotificationEnabled()

            if (DeviceCompatibility.isMiui()) {
                miui.isVisible = true
            }

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

            notificationOptions.setOnClickListener {
                requestOrOpenNotificationSettings()
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
        val phase = state.phase
        binding.apply {
            pairingStatus.isVisible = phase != AdbPairingService.Phase.IDLE
            if (phase == AdbPairingService.Phase.IDLE) return
            val (title, text) =
                when (phase) {
                    AdbPairingService.Phase.SEARCHING ->
                        R.string.notification_adb_pairing_searching_for_service_title to
                            getString(R.string.adb_pairing_status_searching_text)
                    AdbPairingService.Phase.FOUND ->
                        R.string.notification_adb_pairing_service_found_title to
                            getString(R.string.adb_pairing_status_found_text)
                    AdbPairingService.Phase.PAIRING -> R.string.notification_adb_pairing_working_title to null
                    AdbPairingService.Phase.PAIRED ->
                        R.string.notification_adb_pairing_succeed_title to
                            getString(R.string.notification_adb_pairing_succeed_text)
                    AdbPairingService.Phase.STARTING ->
                        R.string.notification_adb_pairing_succeed_title to
                            getString(R.string.notification_adb_pairing_succeed_starting)
                    else -> R.string.notification_adb_pairing_failed_title to state.detail
                }
            pairingStatusTitle.setText(title)
            pairingStatusText.text = text
            pairingStatusText.isVisible = !text.isNullOrEmpty()
            val busy =
                phase == AdbPairingService.Phase.SEARCHING ||
                    phase == AdbPairingService.Phase.PAIRING ||
                    phase == AdbPairingService.Phase.STARTING
            pairingStatusProgress.isVisible = busy
            pairingStatusIcon.isVisible = !busy
            when (phase) {
                AdbPairingService.Phase.FOUND -> pairingStatusIcon.setImageResource(R.drawable.ic_outline_notifications_active_24)
                AdbPairingService.Phase.PAIRED -> pairingStatusIcon.setImageResource(R.drawable.ic_server_ok_24)
                AdbPairingService.Phase.FAILED -> pairingStatusIcon.setImageResource(R.drawable.ic_server_error_24)
                else -> Unit
            }
        }
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

    private fun syncNotificationEnabled() {
        binding.apply {
            // Keep steps visible so user can prepare, but show warning
            notificationDisabled.isGone = notificationEnabled
            // notification is the "success" card, only show if enabled
            notification.isVisible = notificationEnabled
        }
    }

    private fun isNotificationEnabled(): Boolean {
        val context = this

        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = nm.getNotificationChannel(AdbPairingService.NOTIFICATION_CHANNEL)
        return nm.areNotificationsEnabled() &&
            (channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE)
    }

    private val runningListener: (ShizukuStateMachine.State) -> Unit = { state ->
        if (state == ShizukuStateMachine.State.RUNNING && !isFinishing) {
            window?.decorView?.post { if (!isFinishing) finish() }
        }
    }

    override fun onStart() {
        super.onStart()
        ShizukuStateMachine.addListener(runningListener)
    }

    override fun onStop() {
        super.onStop()
        ShizukuStateMachine.removeListener(runningListener)
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

        val newNotificationEnabled = isNotificationEnabled()
        if (newNotificationEnabled != notificationEnabled) {
            notificationEnabled = newNotificationEnabled
            syncNotificationEnabled()

            if (newNotificationEnabled) {
                startPairingService()
            }
        }
    }

    // Android 16+ gates mDNS discovery of the pairing service behind local-network access; request
    // it and only start pairing once the user has responded, so discovery can find the port (#317).
    private val localNetworkPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Start pairing whether or not the grant succeeded; a denial just means discovery/
            // connect fails and the service surfaces the error, rather than a silent no-op.
            doStartPairingService()
        }

    private fun startPairingService() {
        val permission = LocalNetworkPermission.required()
        if (permission != null && checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
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
