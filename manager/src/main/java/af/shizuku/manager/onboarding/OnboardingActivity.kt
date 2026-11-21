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
import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.manager.utils.HapticUtils
import af.shizuku.manager.utils.MotionUtils.applySpringTouch
import af.shizuku.manager.utils.NetworkTrust
import af.shizuku.manager.utils.SettingsBackupManager
import af.shizuku.manager.utils.ShizukuStateMachine
import android.Manifest
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
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
    }

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

    private var pendingPairLaunch: Boolean = false

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

        setupDots()

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

        updateDots(binding.pager.currentItem)
        updateButtons(binding.pager.currentItem)

        ShizukuStateMachine.addListener(stateListener)
    }

    override fun onDestroy() {
        ShizukuStateMachine.removeListener(stateListener)
        super.onDestroy()
    }

    private fun updateSetupPageState(state: ShizukuStateMachine.State) {
        val isRunning = state == ShizukuStateMachine.State.RUNNING
        setupStatusRunning?.visibility = if (isRunning) View.VISIBLE else View.GONE
        val setupIdx = pages.indexOf(PageType.SETUP)
        if (setupIdx >= 0 && isRunning && binding.pager.currentItem == setupIdx && !isFinishing) {
            binding.pager.postDelayed({
                if (!isFinishing && binding.pager.currentItem == setupIdx && setupIdx + 1 < pages.size) {
                    binding.pager.currentItem = setupIdx + 1
                }
            }, 1800)
        }
    }

    private fun setupDots() {
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
                    val w = if (i == 0) pillWidth else dotWidth
                    val lp = LinearLayout.LayoutParams(w, heightPx)
                    lp.setMargins(marginPx, 0, marginPx, 0)
                    layoutParams = lp
                    background =
                        GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = cornerPx
                        }
                }
            dots.add(dot)
            binding.dotsContainer.addView(dot)
        }
        updateDots(0)
    }

    private fun updateDots(selected: Int) {
        val active = resolveThemeColor(android.R.attr.colorPrimary)
        val inactive = resolveThemeColor(com.google.android.material.R.attr.colorSurfaceVariant)
        val pillWidth = dpToPx(24)
        val dotWidth = dpToPx(8)
        val heightPx = dpToPx(8)
        val cornerPx = dpToPx(4).toFloat()

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

            if (currentWidth != targetWidth) {
                val anim =
                    ValueAnimator.ofInt(currentWidth, targetWidth).apply {
                        duration = 260
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
            if (position < pages.size - 1) {
                getString(R.string.onboarding_next)
            } else {
                getString(R.string.onboarding_get_started)
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

            binding.cardFeatM3e.setOnClickListener {
                HapticUtils.segmentTick(it)
                try {
                    startActivity(Intent(this@OnboardingActivity, af.shizuku.manager.settings.SettingsActivity::class.java))
                } catch (_: Exception) {
                }
            }

            binding.cardFeatLayoutNav.setOnClickListener {
                HapticUtils.segmentTick(it)
                try {
                    startActivity(Intent(this@OnboardingActivity, af.shizuku.manager.settings.SettingsActivity::class.java))
                } catch (_: Exception) {
                }
            }

            binding.cardFeatGestures.setOnClickListener {
                HapticUtils.segmentTick(it)
                try {
                    startActivity(Intent(this@OnboardingActivity, af.shizuku.manager.settings.SettingsActivity::class.java))
                } catch (_: Exception) {
                }
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

            // Show/hide method cards based on device
            val showWadb =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ||
                    EnvironmentUtils.isTelevision() ||
                    EnvironmentUtils.getAdbTcpPort() > 0
            val showRoot = EnvironmentUtils.isRooted()

            binding.cardWadb.visibility = if (showWadb) View.VISIBLE else View.GONE
            binding.cardRoot.visibility = if (showRoot) View.VISIBLE else View.GONE

            // Wireless ADB buttons
            if (showWadb) {
                if (EnvironmentUtils.isTlsSupported()) {
                    binding.btnWadbPair.visibility = View.VISIBLE
                    binding.btnWadbPair.setOnClickListener { onPairClicked() }
                }

                binding.btnWadbGuide.setOnClickListener {
                    af.shizuku.manager.knowledgebase.KnowledgebaseHelper.show(
                        this@OnboardingActivity,
                        af.shizuku.manager.knowledgebase.KnowledgebaseRegistry.TOPIC_WIRELESS_ADB,
                    )
                }
                binding.btnWadbStart.setOnClickListener {
                    StartWirelessAdbViewHolder.start(this@OnboardingActivity, lifecycleScope)
                }
            }

            // Root button
            if (showRoot) {
                binding.btnRootStart.setOnClickListener {
                    startActivity(
                        Intent(this@OnboardingActivity, StarterActivity::class.java).apply {
                            putExtra(StarterActivity.EXTRA_IS_ROOT, true)
                        },
                    )
                }
            }

            // PC/ADB button
            binding.btnAdbCommand.setOnClickListener {
                showAdbCommandDialog()
            }

            // Interactive touch springs
            binding.cardWadb.applySpringTouch()
            binding.btnWadbStart.applySpringTouch()
            binding.btnWadbPair.applySpringTouch()
            binding.btnWadbGuide.applySpringTouch()
            binding.cardRoot.applySpringTouch()
            binding.btnRootStart.applySpringTouch()
            binding.cardAdb.applySpringTouch()
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
            // Someone who set the switches one by one keeps them until they pick a level here.
            val startLevel = AssistantLevel.current(prefs).takeIf { it != AssistantLevel.CUSTOM } ?: AssistantLevel.BALANCED
            binding.assistantLevelDesc.setText(levelDescs.getValue(startLevel))
            if (AssistantLevel.current(prefs) != AssistantLevel.CUSTOM) levelButtons.getValue(startLevel).isChecked = true
            binding.assistantLevelGroup.addOnButtonCheckedListener { group, id, checked ->
                if (!checked) return@addOnButtonCheckedListener
                val level = levelButtons.entries.first { it.value.id == id }.key
                HapticUtils.segmentTick(group)
                level.apply(prefs)
                binding.assistantLevelDesc.setText(levelDescs.getValue(level))
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
