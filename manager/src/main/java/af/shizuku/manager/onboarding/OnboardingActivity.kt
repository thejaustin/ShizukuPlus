package af.shizuku.manager.onboarding

import af.shizuku.core.ui.AppActivity
import af.shizuku.manager.MainActivity
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.adb.AdbPairingTutorialActivity
import af.shizuku.manager.databinding.ActivityOnboardingBinding
import af.shizuku.manager.databinding.PageOnboardingSecurityDoctorBinding
import af.shizuku.manager.databinding.PageOnboardingSetupBinding
import af.shizuku.manager.databinding.PageOnboardingWelcomeBinding
import af.shizuku.manager.databinding.PageOnboardingWelcomeReturningBinding
import af.shizuku.manager.home.StartWirelessAdbViewHolder
import af.shizuku.manager.home.showAccessibilityDialog
import af.shizuku.manager.ktx.themeColor
import af.shizuku.manager.starter.Starter
import af.shizuku.manager.starter.StarterActivity
import af.shizuku.manager.update.UpdateInstaller
import af.shizuku.manager.utils.AssistantLevel
import af.shizuku.manager.utils.CardBorder
import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.manager.utils.HapticUtils
import af.shizuku.manager.utils.MotionUtils.applySpringTouch
import af.shizuku.manager.utils.MotionUtils.springTo
import af.shizuku.manager.utils.NetworkStateHelper
import af.shizuku.manager.utils.NetworkTrust
import af.shizuku.manager.utils.SettingsBackupManager
import af.shizuku.manager.utils.ShizukuStateMachine
import android.Manifest
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.core.util.ClipboardUtils
import timber.log.Timber
import java.io.File

class OnboardingActivity : AppActivity() {
    companion object {
        const val EXTRA_NEW_FEATURES_ONLY = "extra_new_features_only"
        const val EXTRA_SINCE_VERSION_CODE = "extra_since_version_code"

        private const val STATE_PAGE = "onboarding_page"
        private const val STATE_PENDING_PAIR = "onboarding_pending_pair"
        private const val STATE_PENDING_SUCCESS = "onboarding_pending_success"
        private const val STATE_AUTO_ADVANCED = "onboarding_auto_advanced"
        private const val STATE_START_METHOD = "onboarding_start_method"
        private const val STATE_START_FAILED = "onboarding_start_failed"
    }

    /** What the setup page's main card shows, picked from the phone's state in [computeSetupUi]. */
    private enum class SetupUi {
        RUNNING,
        STARTING,
        FAILED,
        ROOT,
        OLD_ANDROID,
        DEV_OFF,
        WIFI_OFF,
        NOT_PAIRED,
        PAIRED,
    }

    /** The way a start was tried from this screen, so Try again repeats it. */
    private enum class StartMethod { NONE, ROOT, WIRELESS }

    enum class PageType {
        WELCOME,
        WELCOME_RETURNING,
        SETUP,
        SECURITY_DOCTOR,
    }

    private lateinit var binding: ActivityOnboardingBinding
    private var isNewFeaturesOnly = false
    private var sinceVersionCode = -1
    private val dots = mutableListOf<View>()
    private val onboardingAdapter = OnboardingPagerAdapter()

    private val pages: List<PageType> by lazy {
        if (isNewFeaturesOnly) {
            listOf(
                PageType.WELCOME_RETURNING,
            )
        } else {
            listOf(
                PageType.WELCOME,
                PageType.SETUP,
                PageType.SECURITY_DOCTOR,
            )
        }
    }

    // Setup page live refs
    private var setupStatusRunning: View? = null
    private var setupPage: PageOnboardingSetupBinding? = null

    // Only a start seen on this screen moves on by itself, and only once.
    private var lastSeenRunning = false
    private var autoAdvanced = false

    // Started while the Starter or pairing screen was in front: play the success on return.
    private var pendingSetupSuccess = false
    private val advanceFromSetup =
        Runnable {
            val setupIdx = pages.indexOf(PageType.SETUP)
            if (!isFinishing && setupIdx >= 0 && binding.pager.currentItem == setupIdx && setupIdx + 1 < pages.size) {
                binding.pager.currentItem = setupIdx + 1
            }
        }

    private var pendingPairLaunch: Boolean = false

    // A start made from this screen, and whether it ended without the service running.
    private var startMethod = StartMethod.NONE
    private var startFailed = false
    private var awayDuringStart = false
    private var lastState: ShizukuStateMachine.State? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val notifPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { _ ->
            if (pendingPairLaunch) {
                pendingPairLaunch = false
                startActivity(Intent(this, AdbPairingTutorialActivity::class.java))
            }
        }

    private val backupPickerLauncher =
        registerForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                lifecycleScope.launch {
                    try {
                        val json =
                            withContext(Dispatchers.IO) {
                                contentResolver.openInputStream(uri)?.use { stream ->
                                    stream.bufferedReader().use { it.readText() }
                                }
                            }
                        if (json != null) {
                            if (SettingsBackupManager.importAndCommit(this@OnboardingActivity, json, trustPrivileged = false)) {
                                onBackupRestored(null)
                            } else {
                                Toast.makeText(this@OnboardingActivity, getString(R.string.restore_failed_generic, getString(R.string.onboarding_restore_invalid)), Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to restore backup from picked document")
                        Toast.makeText(this@OnboardingActivity, getString(R.string.restore_failed_generic, e.localizedMessage ?: ""), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

    private fun findAvailableBackupFile(): File? {
        val candidates = UpdateInstaller.trustedBackupCandidates(this) + UpdateInstaller.publicBackupCandidates()
        for (candidate in candidates) {
            try {
                if (candidate.exists() &&
                    candidate.isFile &&
                    candidate.length() > 10 &&
                    !UpdateInstaller.wasRestored(this, candidate) &&
                    // The reinstall copy is this install's own snapshot once the check ran.
                    !(candidate == UpdateInstaller.reinstallBackupFile(this) && SettingsBackupManager.isRestoreChecked()) &&
                    !SettingsBackupManager.matchesCurrent(this, candidate.readText())
                ) {
                    return candidate
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private val stateListener: (ShizukuStateMachine.State) -> Unit = { state ->
        if (!isFinishing && !isDestroyed) {
            runOnUiThread { updateSetupPageState(state) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        isNewFeaturesOnly = intent.getBooleanExtra(EXTRA_NEW_FEATURES_ONLY, false)
        sinceVersionCode = intent.getIntExtra(EXTRA_SINCE_VERSION_CODE, -1)
        if (savedInstanceState != null) {
            pendingPairLaunch = savedInstanceState.getBoolean(STATE_PENDING_PAIR)
            pendingSetupSuccess = savedInstanceState.getBoolean(STATE_PENDING_SUCCESS)
            autoAdvanced = savedInstanceState.getBoolean(STATE_AUTO_ADVANCED)
            startMethod = StartMethod.values().getOrElse(savedInstanceState.getInt(STATE_START_METHOD)) { StartMethod.NONE }
            startFailed = savedInstanceState.getBoolean(STATE_START_FAILED)
        }
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars =
                insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
                )
            binding.root.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            binding.btnRow.setPadding(
                binding.btnRow.paddingLeft,
                binding.btnRow.paddingTop,
                binding.btnRow.paddingRight,
                systemBars.bottom.coerceAtLeast(dpToPx(16)),
            )
            insets
        }

        binding.pager.adapter = onboardingAdapter
        binding.pager.offscreenPageLimit = pages.size

        // Back on the page the user was on before a rotation or a trip through low memory.
        val restoredPage = savedInstanceState?.getInt(STATE_PAGE, 0)?.coerceIn(0, pages.size - 1) ?: 0
        if (restoredPage > 0) binding.pager.setCurrentItem(restoredPage, false)

        setupDots(restoredPage)
        // One page needs no position dots.
        binding.dotsContainer.visibility = if (pages.size > 1) View.VISIBLE else View.GONE

        binding.pager.registerOnPageChangeCallback(
            object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    updateDots(position)
                    updateButtons(position)
                }
            },
        )

        binding.btnNext.applySpringTouch()
        binding.btnSkip.applySpringTouch()

        binding.btnNext.setOnClickListener {
            val current = binding.pager.currentItem
            if (current < pages.size - 1) {
                binding.pager.currentItem = current + 1
            } else {
                completeOnboarding()
            }
        }

        binding.btnSkip.setOnClickListener { completeOnboarding() }

        // Back steps to the previous page; only the first page leaves.
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val current = binding.pager.currentItem
                    if (current > 0) {
                        binding.pager.currentItem = current - 1
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            },
        )

        updateButtons(binding.pager.currentItem)

        // Running already when the tour opened is not a start the user just made.
        lastSeenRunning = ShizukuStateMachine.get() == ShizukuStateMachine.State.RUNNING
        lastState = ShizukuStateMachine.get()
        ShizukuStateMachine.addListener(stateListener)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::binding.isInitialized) outState.putInt(STATE_PAGE, binding.pager.currentItem)
        outState.putBoolean(STATE_PENDING_PAIR, pendingPairLaunch)
        outState.putBoolean(STATE_PENDING_SUCCESS, pendingSetupSuccess)
        outState.putBoolean(STATE_AUTO_ADVANCED, autoAdvanced)
        outState.putInt(STATE_START_METHOD, startMethod.ordinal)
        outState.putBoolean(STATE_START_FAILED, startFailed)
    }

    override fun onStart() {
        super.onStart()
        // Wi-Fi coming and going changes which setup card is the right one.
        if (networkCallback == null && pages.contains(PageType.SETUP)) {
            val cm = getSystemService(ConnectivityManager::class.java) ?: return
            val callback =
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = postRenderSetup()

                    override fun onLost(network: Network) = postRenderSetup()
                }
            try {
                cm.registerDefaultNetworkCallback(callback)
                networkCallback = callback
            } catch (e: Exception) {
                Timber.w(e, "Couldn't watch the network for the setup page")
            }
        }
    }

    override fun onStop() {
        networkCallback?.let {
            try {
                getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        networkCallback = null
        super.onStop()
    }

    override fun onPause() {
        super.onPause()
        // The Starter screen is in front now; what state we come back to decides if it worked.
        if (startMethod != StartMethod.NONE && !startFailed) awayDuringStart = true
    }

    private fun postRenderSetup() {
        runOnUiThread { if (!isFinishing && !isDestroyed) renderSetup() }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the pairing screen: Pair may now be done.
        refreshWadbButtons()
        val state = ShizukuStateMachine.get()
        if (awayDuringStart) {
            awayDuringStart = false
            // Back from the Starter and still not up: that start didn't work.
            if (state != ShizukuStateMachine.State.RUNNING && state != ShizukuStateMachine.State.STARTING) startFailed = true
        }
        if (pendingSetupSuccess) {
            pendingSetupSuccess = false
            if (state == ShizukuStateMachine.State.RUNNING) playSetupSuccess()
        }
        renderSetup()
    }

    override fun onDestroy() {
        ShizukuStateMachine.removeListener(stateListener)
        if (::binding.isInitialized) binding.pager.removeCallbacks(advanceFromSetup)
        super.onDestroy()
    }

    /**
     * Nothing stores "paired" as such, so this goes by what only a pairing (or TCP mode) allows:
     * a port saved after a successful ADB connect, a TCP port, or a server already running.
     */
    private fun hasAdbPairing(): Boolean =
        ShizukuSettings.getLastPort() > 0 ||
            EnvironmentUtils.getAdbTcpPort() > 0 ||
            ShizukuStateMachine.get() == ShizukuStateMachine.State.RUNNING

    /** Pair is the filled button until this device has a pairing, then Start takes its place. */
    private fun refreshWadbButtons() {
        val page = setupPage ?: return
        if (page.cardWadb.visibility != View.VISIBLE) return
        // Before Android 11 there is no pairing step, so Start is the only action.
        val tls = EnvironmentUtils.isTlsSupported()
        val needsPair = tls && !hasAdbPairing()
        val start = View.OnClickListener { startWireless() }
        val pair = View.OnClickListener { onPairClicked() }
        page.btnWadbPrimary.setText(if (needsPair) R.string.onboarding_setup_pair else R.string.home_root_button_start)
        page.btnWadbPrimary.setOnClickListener(if (needsPair) pair else start)
        page.btnWadbSecondary.visibility = if (tls) View.VISIBLE else View.GONE
        page.btnWadbSecondary.setText(if (needsPair) R.string.home_root_button_start else R.string.adb_pairing)
        page.btnWadbSecondary.setOnClickListener(if (needsPair) start else pair)
        page.textWadbStep.visibility = if (tls) View.VISIBLE else View.GONE
        page.textWadbStep.setText(if (needsPair) R.string.onboarding_setup_step_pair else R.string.onboarding_setup_step_start)
        fitButtonRow(page.wadbActions)
    }

    private fun updateSetupPageState(state: ShizukuStateMachine.State) {
        val previous = lastState
        lastState = state
        when (state) {
            ShizukuStateMachine.State.RUNNING, ShizukuStateMachine.State.STARTING -> startFailed = false
            ShizukuStateMachine.State.CRASHED -> if (startMethod != StartMethod.NONE) startFailed = true
            ShizukuStateMachine.State.STOPPED ->
                if (startMethod != StartMethod.NONE && previous == ShizukuStateMachine.State.STARTING) startFailed = true
            else -> Unit
        }
        updateSuccessRow(state)
        renderSetup()
    }

    private fun updateSuccessRow(state: ShizukuStateMachine.State) {
        val isRunning = state == ShizukuStateMachine.State.RUNNING
        val justStarted = isRunning && !lastSeenRunning
        lastSeenRunning = isRunning
        refreshWadbButtons()
        val status = setupStatusRunning ?: return
        if (!isRunning) {
            pendingSetupSuccess = false
            status.visibility = View.GONE
            return
        }
        if (justStarted) {
            val setupIdx = pages.indexOf(PageType.SETUP)
            val onSetupPage = setupIdx >= 0 && binding.pager.currentItem == setupIdx
            if (onSetupPage && !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                // Hidden behind another screen: keep the row hidden so it can play on return.
                pendingSetupSuccess = true
                status.visibility = View.GONE
                return
            }
            if (onSetupPage) {
                playSetupSuccess()
            } else {
                // Off screen it just appears; no buzz for a page the user isn't looking at.
                showSetupSuccess(status, animate = false)
            }
        } else if (!pendingSetupSuccess && status.visibility != View.VISIBLE) {
            // Already running: show it and let the user tap Next.
            showSetupSuccess(status, animate = false)
        }
    }

    /** Developer options as far as an app can tell; a hidden value counts as on. */
    private fun isDeveloperOptionsOn(): Boolean =
        try {
            Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) > 0 ||
                Settings.Global.getInt(contentResolver, Settings.Global.ADB_ENABLED, 0) > 0 ||
                // Android 17 reads these as 0 for apps, so 0 says nothing there.
                Build.VERSION.SDK_INT >= 37
        } catch (_: Exception) {
            true
        }

    /** Picks the one card that fits this phone right now, most pressing first. */
    private fun computeSetupUi(state: ShizukuStateMachine.State): SetupUi {
        if (state == ShizukuStateMachine.State.RUNNING) {
            // Started behind the Starter screen: keep "Starting" up until the success plays on return.
            return if (pendingSetupSuccess) SetupUi.STARTING else SetupUi.RUNNING
        }
        if (state == ShizukuStateMachine.State.STARTING) return SetupUi.STARTING
        if (startFailed) return SetupUi.FAILED
        if (EnvironmentUtils.isRooted()) return SetupUi.ROOT
        val tls = EnvironmentUtils.isTlsSupported()
        // A TCP port (adb tcpip, or a TV) skips pairing, Wi-Fi checks and Developer options.
        val tcp = EnvironmentUtils.getAdbTcpPort() > 0 || ShizukuSettings.getTcpMode()
        if (!tls && !EnvironmentUtils.isTelevision() && !tcp) return SetupUi.OLD_ANDROID
        if (!tcp && !isDeveloperOptionsOn()) return SetupUi.DEV_OFF
        if (!tcp && tls && !NetworkStateHelper.isWirelessAdbSupportedNetwork(this)) return SetupUi.WIFI_OFF
        return if (tls && !hasAdbPairing()) SetupUi.NOT_PAIRED else SetupUi.PAIRED
    }

    private fun startWithRoot() {
        startMethod = StartMethod.ROOT
        startFailed = false
        startActivity(
            Intent(this, StarterActivity::class.java).apply {
                putExtra(StarterActivity.EXTRA_IS_ROOT, true)
            },
        )
    }

    private fun startWireless() {
        startMethod = StartMethod.WIRELESS
        startFailed = false
        StartWirelessAdbViewHolder.start(this, lifecycleScope)
    }

    private fun retryStart() {
        when (startMethod) {
            StartMethod.ROOT -> startWithRoot()
            else -> startWireless()
        }
        renderSetup()
    }

    private fun openGuide() {
        af.shizuku.manager.knowledgebase.KnowledgebaseHelper.show(
            this,
            af.shizuku.manager.knowledgebase.KnowledgebaseRegistry.TOPIC_WIRELESS_ADB,
        )
    }

    private fun openSafely(vararg intents: Intent) {
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }
        Timber.w("No screen on this phone handles %s", intents.firstOrNull()?.action)
    }

    private fun openWifiSettings() {
        val panel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Intent(Settings.Panel.ACTION_WIFI) else null
        openSafely(*listOfNotNull(panel, Intent(Settings.ACTION_WIFI_SETTINGS)).toTypedArray())
    }

    private class SetupAction(
        val text: CharSequence,
        val onClick: () -> Unit,
    )

    /**
     * Draws the setup page for [computeSetupUi]: one card with an icon, a status word, a short
     * reason and one filled action, then every other way that works on this phone, root first.
     */
    private fun renderSetup() {
        val page = setupPage ?: return
        val ui = computeSetupUi(ShizukuStateMachine.get())
        val appName = getString(R.string.app_name)
        val showWadb =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ||
                EnvironmentUtils.isTelevision() ||
                EnvironmentUtils.getAdbTcpPort() > 0
        val rooted = EnvironmentUtils.isRooted()
        val busy = ui == SetupUi.RUNNING || ui == SetupUi.STARTING

        page.cardState.isVisible = ui != SetupUi.RUNNING
        page.textStateLabel.isVisible = !busy && ui != SetupUi.FAILED
        page.btnSetupLater.isVisible = ui != SetupUi.RUNNING

        val guide = SetupAction(getString(R.string.home_wireless_adb_view_guide_button)) { openGuide() }
        var icon = R.drawable.ic_wadb_24
        var word = R.string.onboarding_v2_status_not_running
        val title: String
        val desc: String
        var primary: SetupAction? = null
        var secondary: SetupAction? = null
        var tertiary: SetupAction? = null
        when (ui) {
            SetupUi.RUNNING, SetupUi.STARTING -> {
                icon = R.drawable.ic_server_start_24
                word = R.string.onboarding_v2_status_starting
                title = getString(R.string.onboarding_v2_setup_starting_title, appName)
                desc = getString(R.string.onboarding_v2_setup_starting_desc)
            }
            SetupUi.FAILED -> {
                icon = R.drawable.ic_server_error_24
                word = R.string.onboarding_v2_status_failed
                title = getString(R.string.onboarding_v2_setup_failed_title)
                desc = getString(R.string.onboarding_v2_setup_failed_desc)
                primary = SetupAction(getString(R.string.onboarding_v2_setup_retry)) { retryStart() }
                secondary =
                    SetupAction(getString(R.string.onboarding_v2_setup_open_doctor)) {
                        openSafely(Intent(this, af.shizuku.manager.settings.ServiceDoctorActivity::class.java))
                    }
            }
            SetupUi.ROOT -> {
                icon = R.drawable.ic_root_24
                title = getString(R.string.onboarding_v2_setup_root_title)
                desc = getString(R.string.onboarding_setup_root_desc)
                primary = SetupAction(getString(R.string.home_root_button_start)) { startWithRoot() }
            }
            SetupUi.OLD_ANDROID -> {
                icon = R.drawable.ic_smartphone_24
                word = R.string.onboarding_v2_status_blocked
                title = getString(R.string.onboarding_v2_setup_old_android_title)
                desc = getString(R.string.onboarding_v2_setup_old_android_desc, Build.VERSION.RELEASE)
                primary = SetupAction(getString(R.string.home_adb_button_view_command)) { showAdbCommandDialog() }
            }
            SetupUi.DEV_OFF -> {
                icon = R.drawable.ic_code_24
                word = R.string.onboarding_v2_status_blocked
                title = getString(R.string.onboarding_v2_setup_dev_off_title)
                desc = getString(R.string.onboarding_v2_setup_dev_off_desc)
                primary =
                    SetupAction(getString(R.string.onboarding_v2_setup_open_about)) {
                        openSafely(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS), Intent(Settings.ACTION_SETTINGS))
                    }
                secondary = guide
            }
            SetupUi.WIFI_OFF -> {
                icon = R.drawable.ic_wifi_24
                word = R.string.onboarding_v2_status_blocked
                title = getString(R.string.onboarding_v2_setup_wifi_off_title)
                desc = getString(R.string.onboarding_v2_setup_wifi_off_desc)
                primary = SetupAction(getString(R.string.onboarding_v2_setup_open_wifi)) { openWifiSettings() }
                secondary = guide
            }
            SetupUi.NOT_PAIRED -> {
                title = getString(R.string.onboarding_v2_setup_pair_title)
                desc = getString(R.string.onboarding_v2_setup_pair_desc)
                primary = SetupAction(getString(R.string.onboarding_setup_pair)) { onPairClicked() }
                secondary = guide
            }
            SetupUi.PAIRED -> {
                title = getString(R.string.onboarding_v2_setup_start_title, appName)
                desc = getString(R.string.onboarding_v2_setup_start_desc)
                primary = SetupAction(getString(R.string.home_root_button_start)) { startWireless() }
                if (EnvironmentUtils.isTlsSupported()) {
                    secondary = SetupAction(getString(R.string.onboarding_v2_setup_pair_again)) { onPairClicked() }
                }
                tertiary = guide
            }
        }

        // The icon tile: red only for a failed start, and always next to a word.
        val failed = ui == SetupUi.FAILED
        page.stateIcon.setImageResource(icon)
        page.stateIcon.backgroundTintList =
            ColorStateList.valueOf(
                themeColor(if (failed) com.google.android.material.R.attr.colorErrorContainer else com.google.android.material.R.attr.colorTertiaryContainer),
            )
        page.stateIcon.imageTintList =
            ColorStateList.valueOf(
                themeColor(if (failed) com.google.android.material.R.attr.colorOnErrorContainer else com.google.android.material.R.attr.colorOnTertiaryContainer),
            )
        page.stateIcon.isVisible = ui != SetupUi.STARTING
        page.stateProgress.isVisible = ui == SetupUi.STARTING
        page.stateWord.setText(word)
        page.stateWord.setTextColor(
            themeColor(if (failed) androidx.appcompat.R.attr.colorError else androidx.appcompat.R.attr.colorPrimary),
        )
        page.stateTitle.text = title
        page.stateDesc.text = desc
        page.stateHeader.contentDescription = "${getString(word)}. $title"

        fun MaterialButton.show(action: SetupAction?) {
            isVisible = action != null
            if (action == null) {
                setOnClickListener(null)
                return
            }
            text = action.text
            setOnClickListener {
                HapticUtils.tap(it)
                action.onClick()
            }
        }
        page.btnStatePrimary.show(primary)
        page.btnStateSecondary.show(secondary)
        page.btnStateTertiary.show(tertiary)
        page.stateActions.isVisible = primary != null
        if (primary != null) fitButtonRow(page.stateActions)

        // Every other way that works here; the main card's own way isn't repeated below it.
        val wadbOnCard = ui == SetupUi.NOT_PAIRED || ui == SetupUi.PAIRED || ui == SetupUi.WIFI_OFF || ui == SetupUi.DEV_OFF
        page.cardRoot.isVisible = !busy && rooted && ui != SetupUi.ROOT
        page.cardWadb.isVisible = !busy && showWadb && !wadbOnCard
        page.cardAdb.isVisible = !busy && ui != SetupUi.OLD_ANDROID
        page.textOtherWays.isVisible = page.cardRoot.isVisible || page.cardWadb.isVisible || page.cardAdb.isVisible
        refreshWadbButtons()
    }

    private val savedRowParams = java.util.WeakHashMap<View, IntArray>()

    /**
     * Keeps a row of buttons inside the screen: side by side while they fit, stacked full width
     * when they don't (large font sizes, long translations, narrow windows).
     */
    private fun fitButtonRow(row: LinearLayout) {
        val gap = dpToPx(8)
        val children = (0 until row.childCount).map { row.getChildAt(it) }
        children.forEach { child ->
            val lp = child.layoutParams as LinearLayout.LayoutParams
            savedRowParams.getOrPut(child) { intArrayOf(lp.width, (lp.weight * 1000).toInt(), lp.marginStart, lp.marginEnd, lp.topMargin) }
        }

        fun layOut(stacked: Boolean) {
            row.orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            var first = true
            children.forEach { child ->
                val saved = savedRowParams.getValue(child)
                val lp = child.layoutParams as LinearLayout.LayoutParams
                if (stacked) {
                    lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                    lp.weight = 0f
                    lp.marginStart = 0
                    lp.marginEnd = 0
                    lp.topMargin = if (first || child.visibility == View.GONE) 0 else gap
                } else {
                    lp.width = saved[0]
                    lp.weight = saved[1] / 1000f
                    lp.marginStart = saved[2]
                    lp.marginEnd = saved[3]
                    lp.topMargin = saved[4]
                }
                if (child.visibility != View.GONE) first = false
                child.layoutParams = lp
            }
        }

        if (resources.configuration.fontScale >= 1.3f) {
            layOut(stacked = true)
            return
        }
        layOut(stacked = false)
        row.post {
            val available = row.width - row.paddingStart - row.paddingEnd
            if (available <= 0) return@post
            val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            val needed =
                children.filter { it.visibility != View.GONE }.sumOf { child ->
                    child.measure(unspecified, unspecified)
                    val lp = child.layoutParams as LinearLayout.LayoutParams
                    child.measuredWidth + lp.marginStart + lp.marginEnd
                }
            if (needed > available) layOut(stacked = true)
        }
    }

    /** Success on the visible setup page: animate, then move on once. */
    private fun playSetupSuccess() {
        val status = setupStatusRunning ?: return
        showSetupSuccess(status, animate = true)
        val setupIdx = pages.indexOf(PageType.SETUP)
        if (!autoAdvanced && setupIdx >= 0 && binding.pager.currentItem == setupIdx && !isFinishing) {
            autoAdvanced = true
            binding.pager.removeCallbacks(advanceFromSetup)
            binding.pager.postDelayed(advanceFromSetup, 1800)
        }
    }

    /** The "it worked" row: the check scales in with a success haptic, or just appears. */
    private fun showSetupSuccess(
        status: View,
        animate: Boolean,
    ) {
        val icon = setupPage?.statusRunningIcon
        status.animate().cancel()
        icon?.animate()?.cancel()
        status.visibility = View.VISIBLE
        val duration = ShizukuSettings.scaledAnimationDuration(300)
        if (animate) HapticUtils.success(status)
        if (!animate || duration <= 0L) {
            status.alpha = 1f
            icon?.scaleX = 1f
            icon?.scaleY = 1f
            return
        }
        status.alpha = 0f
        status
            .animate()
            .alpha(1f)
            .setDuration(duration)
            .start()
        // The check springs in (it may overshoot); the fade above never does.
        icon?.scaleX = 0.4f
        icon?.scaleY = 0.4f
        icon?.springTo(SpringAnimation.SCALE_X, 1f, com.google.android.material.R.attr.motionSpringFastSpatial)
        icon?.springTo(SpringAnimation.SCALE_Y, 1f, com.google.android.material.R.attr.motionSpringFastSpatial)
    }

    private fun setupDots(selected: Int) {
        binding.dotsContainer.removeAllViews()
        dots.clear()
        val dotWidth = dpToPx(8)
        val pillWidth = dpToPx(24)
        val heightPx = dpToPx(8)
        val marginPx = dpToPx(4)
        val cornerPx = dpToPx(4).toFloat()

        repeat(pages.size) { i ->
            val dot =
                View(this).apply {
                    val w = if (i == selected) pillWidth else dotWidth
                    val lp = LinearLayout.LayoutParams(w, heightPx)
                    lp.setMargins(marginPx, 0, marginPx, 0)
                    layoutParams = lp
                    // The row reads as one "Page X of N"; single dots say nothing.
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    background =
                        GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = cornerPx
                        }
                }
            dots.add(dot)
            binding.dotsContainer.addView(dot)
        }
        updateDots(selected)
    }

    private fun updateDots(selected: Int) {
        val active = resolveThemeColor(android.R.attr.colorPrimary)
        val inactive = resolveThemeColor(com.google.android.material.R.attr.colorSurfaceVariant)
        val pillWidth = dpToPx(24)
        val dotWidth = dpToPx(8)
        val heightPx = dpToPx(8)
        val cornerPx = dpToPx(4).toFloat()
        val duration = ShizukuSettings.scaledAnimationDuration(260)
        binding.dotsContainer.contentDescription = getString(R.string.onboarding_v2_page_position, selected + 1, pages.size)

        dots.forEachIndexed { i, dot ->
            val isSelected = i == selected
            val targetWidth = if (isSelected) pillWidth else dotWidth
            val targetColor = if (isSelected) active else inactive
            val currentWidth = dot.layoutParams.width

            (dot.background as? GradientDrawable)?.apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = cornerPx
                setColor(targetColor)
            }

            (dot.tag as? ValueAnimator)?.cancel()

            if (currentWidth != targetWidth && duration <= 0L) {
                dot.layoutParams = (dot.layoutParams as LinearLayout.LayoutParams).also { it.width = targetWidth }
            } else if (currentWidth != targetWidth) {
                val anim =
                    ValueAnimator.ofInt(currentWidth, targetWidth).apply {
                        this.duration = duration
                        interpolator = FastOutSlowInInterpolator()
                        addUpdateListener { va ->
                            val w = va.animatedValue as Int
                            dot.layoutParams =
                                (dot.layoutParams as LinearLayout.LayoutParams).also { lp ->
                                    lp.width = w
                                    lp.height = heightPx
                                }
                        }
                    }
                dot.tag = anim
                anim.start()
            }
        }
    }

    private fun updateButtons(position: Int) {
        // The setup page has its own "later" button; a global Skip beside it also skipped the Doctor choice.
        val canSkip = position < pages.size - 1 && pages.getOrNull(position) != PageType.SETUP
        binding.btnSkip.visibility = if (canSkip) View.VISIBLE else View.INVISIBLE
        binding.btnNext.text =
            when {
                position < pages.size - 1 -> getString(R.string.onboarding_next)
                // The single What's New page: one button, named for where it goes.
                pages.size == 1 -> getString(R.string.onboarding_returning_action_jump)
                else -> getString(R.string.onboarding_get_started)
            }
    }

    /**
     * The user chose to restore: apply everything now (theme, language, boot start, icon, Plus
     * features), ask about privileged settings the backup would switch on, then go home. No
     * restart needed.
     */
    private fun onBackupRestored(file: File?) {
        if (file != null) UpdateInstaller.consumeBackup(this, file)
        SettingsBackupManager.markRestoreChecked()
        SettingsBackupManager.applyRestoredLive(this)
        af.shizuku.manager.utils
            .showRestoreSummary(this) { confirmHeldBackAndFinish() }
    }

    private fun confirmHeldBackAndFinish() {
        val held = SettingsBackupManager.heldBack
        if (held.isEmpty()) {
            completeOnboarding()
            return
        }
        val names =
            held.keys.joinToString("\n") { key ->
                "\u2022 " + (SettingsBackupManager.PRIVILEGED_KEYS[key]?.let { getString(it) } ?: key)
            }
        com.google.android.material.dialog
            .MaterialAlertDialogBuilder(this)
            .setTitle(R.string.restore_privileged_title)
            .setMessage(getString(R.string.restore_privileged_message, names))
            .setNegativeButton(R.string.restore_privileged_keep_off) { _, _ ->
                SettingsBackupManager.discardHeldBack()
                completeOnboarding()
            }.setPositiveButton(R.string.restore_privileged_turn_on) { _, _ ->
                SettingsBackupManager.applyHeldBack()
                SettingsBackupManager.applyRestoredLive(this)
                completeOnboarding()
            }.setCancelable(false)
            .show()
    }

    private var completed = false

    private fun completeOnboarding() {
        if (completed) return
        completed = true
        // Restoring was offered on the welcome page; from here on this install's own settings
        // are what the reinstall copy keeps.
        SettingsBackupManager.markRestoreChecked()
        ShizukuSettings.setOnboardingSeen()
        ShizukuSettings.setOnboardingV2Seen()
        val currentCode =
            try {
                packageManager.getPackageInfo(packageName, 0).versionCode
            } catch (_: Exception) {
                af.shizuku.manager.BuildConfig.VERSION_CODE
            }
        ShizukuSettings.setLastSeenOnboardingVersion(currentCode)
        // A new install has nothing to catch up on: What's New starts with the next update.
        ShizukuSettings.setLastSeenChangelogVersion(currentCode)
        if (isTaskRoot || !isNewFeaturesOnly) {
            startActivity(Intent(this, MainActivity::class.java))
        }
        finish()
    }

    private fun resolveThemeColor(attr: Int): Int = themeColor(attr)

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    // ---- Adapter ----

    inner class OnboardingPagerAdapter : RecyclerView.Adapter<OnboardingPageViewHolder>() {
        override fun getItemCount() = pages.size

        override fun getItemViewType(position: Int) = pages[position].ordinal

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): OnboardingPageViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (PageType.values()[viewType]) {
                PageType.WELCOME -> OnboardingPageViewHolder.Welcome(PageOnboardingWelcomeBinding.inflate(inflater, parent, false))
                PageType.WELCOME_RETURNING -> OnboardingPageViewHolder.WelcomeReturning(PageOnboardingWelcomeReturningBinding.inflate(inflater, parent, false))
                PageType.SETUP -> OnboardingPageViewHolder.Setup(PageOnboardingSetupBinding.inflate(inflater, parent, false))
                PageType.SECURITY_DOCTOR -> OnboardingPageViewHolder.SecurityDoctor(PageOnboardingSecurityDoctorBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(
            holder: OnboardingPageViewHolder,
            position: Int,
        ) {
            when (holder) {
                is OnboardingPageViewHolder.Welcome -> bindWelcomePage(holder.binding)
                is OnboardingPageViewHolder.WelcomeReturning -> bindWelcomeReturningPage(holder.binding)
                is OnboardingPageViewHolder.Setup -> bindSetupPage(holder.binding)
                is OnboardingPageViewHolder.SecurityDoctor -> bindSecurityDoctorPage(holder.binding)
            }
        }

        private fun bindWelcomePage(binding: PageOnboardingWelcomeBinding) {
            CardBorder.applyAll(binding.root)
            binding.cardRestoreBackup.applySpringTouch()
            binding.btnRestoreBackup.applySpringTouch()
            binding.btnPickBackupFile.applySpringTouch()

            val foundBackup = findAvailableBackupFile()
            if (foundBackup != null) {
                binding.cardRestoreBackup.visibility = View.VISIBLE
                binding.btnRestoreBackup.setOnClickListener {
                    lifecycleScope.launch {
                        try {
                            val json = withContext(Dispatchers.IO) { foundBackup.readText() }
                            // A copy in public Downloads may have been written by another app: hold
                            // its privileged settings back so they ask, as for a picked file.
                            val trusted = UpdateInstaller.isTrustedBackupLocation(this@OnboardingActivity, foundBackup)
                            if (SettingsBackupManager.importAndCommit(this@OnboardingActivity, json, trustPrivileged = trusted)) {
                                onBackupRestored(foundBackup)
                            } else {
                                Toast.makeText(this@OnboardingActivity, getString(R.string.restore_failed_generic, getString(R.string.onboarding_restore_invalid)), Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to restore found backup during onboarding")
                            Toast.makeText(this@OnboardingActivity, getString(R.string.restore_failed_generic, e.localizedMessage ?: ""), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } else {
                binding.cardRestoreBackup.visibility = View.GONE
            }

            binding.btnPickBackupFile.setOnClickListener {
                try {
                    backupPickerLauncher.launch(arrayOf("application/json", "*/*"))
                } catch (e: Exception) {
                    Timber.e(e, "Failed to launch backup file picker")
                }
            }
        }

        private fun bindWelcomeReturningPage(binding: PageOnboardingWelcomeReturningBinding) {
            CardBorder.applyAll(binding.root)
            binding.heroIcon.applySpringTouch()
            binding.cardFeatM3e.applySpringTouch()
            binding.cardFeatLayoutNav.applySpringTouch()
            binding.cardFeatGestures.applySpringTouch()
            binding.cardFeatDoctor.applySpringTouch()
            binding.cardFeatWireless.applySpringTouch()
            binding.btnJumpToApp.applySpringTouch()

            binding.btnJumpToApp.setOnClickListener {
                HapticUtils.tap(it)
                completeOnboarding()
            }
            // The bottom bar already has this button when this is the only page.
            binding.btnJumpToApp.visibility = if (pages.size == 1) View.GONE else View.VISIBLE

            // Each card opens the settings group it talks about (scrolled to and highlighted).
            fun openSettingsSection(key: String) {
                try {
                    startActivity(
                        Intent(this@OnboardingActivity, af.shizuku.manager.settings.SettingsActivity::class.java)
                            .putExtra("highlight_key", key),
                    )
                } catch (_: Exception) {
                }
            }

            binding.cardFeatM3e.setOnClickListener {
                HapticUtils.segmentTick(it)
                openSettingsSection("category_shape_style")
            }

            binding.cardFeatLayoutNav.setOnClickListener {
                HapticUtils.segmentTick(it)
                openSettingsSection("category_floating_nav")
            }

            binding.cardFeatGestures.setOnClickListener {
                HapticUtils.segmentTick(it)
                openSettingsSection("category_app_interactions")
            }

            binding.cardFeatDoctor.setOnClickListener {
                HapticUtils.segmentTick(it)
                try {
                    startActivity(Intent(this@OnboardingActivity, af.shizuku.manager.settings.ServiceDoctorActivity::class.java))
                } catch (_: Exception) {
                }
            }

            binding.cardFeatWireless.setOnClickListener {
                HapticUtils.segmentTick(it)
                try {
                    startActivity(Intent(this@OnboardingActivity, AdbPairingTutorialActivity::class.java))
                } catch (_: Exception) {
                }
            }
        }

        private fun bindSetupPage(binding: PageOnboardingSetupBinding) {
            setupStatusRunning = binding.statusRunning
            setupPage = binding
            CardBorder.applyAll(binding.root)

            binding.btnWadbGuide.setOnClickListener { openGuide() }
            binding.btnRootStart.setOnClickListener { startWithRoot() }
            binding.btnAdbCommand.setOnClickListener { showAdbCommandDialog() }

            // Interactive touch springs
            binding.btnStatePrimary.applySpringTouch()
            binding.btnStateSecondary.applySpringTouch()
            binding.btnStateTertiary.applySpringTouch()
            binding.btnWadbPrimary.applySpringTouch()
            binding.btnWadbSecondary.applySpringTouch()
            binding.btnWadbGuide.applySpringTouch()
            binding.btnRootStart.applySpringTouch()
            binding.btnAdbCommand.applySpringTouch()
            binding.btnSetupLater.applySpringTouch()

            // Skip
            binding.btnSetupLater.setOnClickListener {
                val setupIdx = pages.indexOf(PageType.SETUP)
                if (setupIdx >= 0 && setupIdx + 1 < pages.size) {
                    this@OnboardingActivity.binding.pager.currentItem = setupIdx + 1
                }
            }

            // Apply current state immediately
            updateSetupPageState(ShizukuStateMachine.get())
        }

        private fun bindSecurityDoctorPage(binding: PageOnboardingSecurityDoctorBinding) {
            CardBorder.applyAll(binding.root)
            binding.previewNotificationApp.text =
                getString(R.string.app_name) + " \u00b7 " + getString(R.string.onboarding_v2_preview_notification_time)

            // The whole row is the switch, for touch and for screen readers.
            fun linkRow(
                row: View,
                switch: CompoundButton,
            ) {
                row.setOnClickListener { switch.toggle() }
                ViewCompat.setAccessibilityDelegate(
                    row,
                    object : AccessibilityDelegateCompat() {
                        override fun onInitializeAccessibilityNodeInfo(
                            host: View,
                            info: AccessibilityNodeInfoCompat,
                        ) {
                            super.onInitializeAccessibilityNodeInfo(host, info)
                            info.className = android.widget.Switch::class.java.name
                            info.isCheckable = true
                            info.isChecked = switch.isChecked
                        }
                    },
                )
            }
            linkRow(binding.rowNetworkGuard, binding.switchNetworkGuard)
            linkRow(binding.rowWatchdog, binding.switchWatchdog)

            // Four labels side by side don't fit at very large font sizes: stack them.
            if (resources.configuration.fontScale >= 1.6f) {
                binding.assistantLevelGroup.orientation = LinearLayout.VERTICAL
                for (i in 0 until binding.assistantLevelGroup.childCount) {
                    val button = binding.assistantLevelGroup.getChildAt(i)
                    button.layoutParams =
                        (button.layoutParams as LinearLayout.LayoutParams).also {
                            it.width = ViewGroup.LayoutParams.MATCH_PARENT
                            it.weight = 0f
                        }
                    (button as? MaterialButton)?.maxLines = 2
                }
            }
            val networkListener =
                CompoundButton.OnCheckedChangeListener { view, checked ->
                    if (checked) HapticUtils.toggleOn(view) else HapticUtils.toggleOff(view)
                    val edit = ShizukuSettings.getPreferences()?.edit() ?: return@OnCheckedChangeListener
                    // Turning it on must not replace a mode the user already chose (trusted networks).
                    if (!checked) {
                        edit.putString(NetworkTrust.KEY_MODE, NetworkTrust.MODE_OFF)
                    } else if (NetworkTrust.mode() == NetworkTrust.MODE_OFF) {
                        edit.putString(NetworkTrust.KEY_MODE, NetworkTrust.MODE_OPEN)
                    }
                    edit.putBoolean(NetworkTrust.KEY_AUTO_CLOSE, checked).apply()
                }
            val watchdogListener =
                CompoundButton.OnCheckedChangeListener { view, checked ->
                    if (checked) HapticUtils.toggleOn(view) else HapticUtils.toggleOff(view)
                    ShizukuSettings.setWatchdog(this@OnboardingActivity, checked)
                }

            // Show what is stored without firing the listeners (they would write it back).
            fun syncSwitches() {
                binding.switchNetworkGuard.setOnCheckedChangeListener(null)
                binding.switchNetworkGuard.isChecked = NetworkTrust.mode() != NetworkTrust.MODE_OFF
                binding.switchNetworkGuard.setOnCheckedChangeListener(networkListener)
                binding.switchWatchdog.setOnCheckedChangeListener(null)
                // A new install's Watchdog turns on with its first server start, so show that.
                binding.switchWatchdog.isChecked = ShizukuSettings.getWatchdog() || ShizukuSettings.isWatchdogDefaultPending()
                binding.switchWatchdog.setOnCheckedChangeListener(watchdogListener)
            }
            syncSwitches()

            val prefs = ShizukuSettings.getPreferences()
            val levelButtons =
                mapOf(
                    AssistantLevel.OFF to binding.assistantOff,
                    AssistantLevel.QUIET to binding.assistantQuiet,
                    AssistantLevel.BALANCED to binding.assistantBalanced,
                    AssistantLevel.ACTIVE to binding.assistantActive,
                )
            val levelDescs =
                mapOf(
                    AssistantLevel.OFF to R.string.onboarding_assistant_desc_off,
                    AssistantLevel.QUIET to R.string.onboarding_assistant_desc_quiet,
                    AssistantLevel.BALANCED to R.string.onboarding_assistant_desc_balanced,
                    AssistantLevel.ACTIVE to R.string.onboarding_assistant_desc_active,
                )

            // A stand-in for what each level shows; nothing in it is live.
            fun showPreview(
                level: AssistantLevel,
                animate: Boolean,
            ) {
                val preview = binding.assistantPreview
                val applyLevel = {
                    binding.previewOff.isVisible = level == AssistantLevel.OFF
                    binding.previewChip.isVisible = level == AssistantLevel.QUIET
                    binding.previewFixCard.isVisible = level == AssistantLevel.BALANCED || level == AssistantLevel.ACTIVE
                    binding.previewNotification.isVisible = level == AssistantLevel.ACTIVE
                    binding.assistantPreviewCard.contentDescription =
                        getString(
                            when (level) {
                                AssistantLevel.OFF -> R.string.onboarding_v2_preview_a11y_off
                                AssistantLevel.QUIET -> R.string.onboarding_v2_preview_a11y_quiet
                                AssistantLevel.ACTIVE -> R.string.onboarding_v2_preview_a11y_active
                                else -> R.string.onboarding_v2_preview_a11y_balanced
                            },
                        )
                }
                // A cancelled fade skips its end action; the new one below applies this level.
                preview.animate().cancel()
                val duration = ShizukuSettings.scaledAnimationDuration(200)
                if (!animate || duration <= 0L) {
                    applyLevel()
                    preview.alpha = 1f
                    preview.translationY = 0f
                    return
                }
                val shift = dpToPx(8).toFloat()
                preview
                    .animate()
                    .alpha(0f)
                    .translationY(-shift)
                    .setDuration(duration / 2)
                    .setInterpolator(FastOutSlowInInterpolator())
                    .withEndAction {
                        applyLevel()
                        preview.translationY = shift
                        preview
                            .animate()
                            .alpha(1f)
                            .translationY(0f)
                            .setDuration(duration / 2)
                            .setInterpolator(FastOutSlowInInterpolator())
                            .start()
                    }.start()
            }

            // Someone who set the switches one by one keeps them until they pick a level here.
            val startLevel = AssistantLevel.current(prefs).takeIf { it != AssistantLevel.CUSTOM } ?: AssistantLevel.BALANCED
            binding.assistantLevelDesc.setText(levelDescs.getValue(startLevel))
            showPreview(startLevel, animate = false)
            if (AssistantLevel.current(prefs) != AssistantLevel.CUSTOM) levelButtons.getValue(startLevel).isChecked = true
            binding.assistantLevelGroup.addOnButtonCheckedListener { group, id, checked ->
                if (!checked) return@addOnButtonCheckedListener
                val level = levelButtons.entries.first { it.value.id == id }.key
                HapticUtils.segmentTick(group)
                level.apply(prefs)
                binding.assistantLevelDesc.setText(levelDescs.getValue(level))
                showPreview(level, animate = true)
                syncSwitches()
            }
        }
    }

    sealed class OnboardingPageViewHolder(
        root: View,
    ) : RecyclerView.ViewHolder(root) {
        class Welcome(
            val binding: PageOnboardingWelcomeBinding,
        ) : OnboardingPageViewHolder(binding.root)

        class WelcomeReturning(
            val binding: PageOnboardingWelcomeReturningBinding,
        ) : OnboardingPageViewHolder(binding.root)

        class Setup(
            val binding: PageOnboardingSetupBinding,
        ) : OnboardingPageViewHolder(binding.root)

        class SecurityDoctor(
            val binding: PageOnboardingSecurityDoctorBinding,
        ) : OnboardingPageViewHolder(binding.root)
    }

    private fun onPairClicked() {
        if (EnvironmentUtils.isTelevision()) {
            showAccessibilityDialog()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingPairLaunch = true
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startActivity(Intent(this, AdbPairingTutorialActivity::class.java))
        }
    }

    private fun showAdbCommandDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.home_adb_button_view_command)
            .setMessage(
                android.text.Html.fromHtml(
                    getString(R.string.home_adb_dialog_view_command_message, Starter.adbCommand),
                    android.text.Html.FROM_HTML_MODE_LEGACY,
                ),
            ).setPositiveButton(R.string.home_adb_dialog_view_command_copy_button) { _, _ ->
                if (ClipboardUtils.put(this, Starter.adbCommand)) {
                    Toast.makeText(this, R.string.toast_copied_to_clipboard, Toast.LENGTH_SHORT).show()
                }
            }.setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.home_adb_dialog_view_command_button_send) { _, _ ->
                var intent = Intent(Intent.ACTION_SEND)
                intent.type = "text/plain"
                intent.putExtra(Intent.EXTRA_TEXT, Starter.adbCommand)
                intent = Intent.createChooser(intent, getString(R.string.home_adb_dialog_view_command_button_send))
                startActivity(intent)
            }.show()
    }
}
