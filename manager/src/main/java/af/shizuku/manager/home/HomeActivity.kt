package af.shizuku.manager.home
import af.shizuku.core.ui.EmptyStateView

import android.app.NotificationManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.text.method.LinkMovementMethod
import timber.log.Timber
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import com.airbnb.mvrx.MavericksView
import com.airbnb.mvrx.viewModel
import com.airbnb.mvrx.withState
import com.airbnb.mvrx.Success
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.adb.AdbPairingService
import af.shizuku.core.ui.AppBarActivity
import af.shizuku.manager.app.SnackbarHelper
import af.shizuku.manager.databinding.AboutDialogBinding
import af.shizuku.manager.databinding.HomeActivityBinding
import af.shizuku.manager.home.showAccessibilityDialog
import af.shizuku.manager.ktx.toHtml
import af.shizuku.manager.management.AppsViewModel
import af.shizuku.manager.settings.SettingsActivity
import af.shizuku.manager.update.UpdateChecker
import af.shizuku.manager.update.UpdateManager
import af.shizuku.manager.utils.AppIconCache
import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.manager.utils.HapticUtils
import af.shizuku.manager.utils.SettingsHelper
import af.shizuku.manager.utils.SettingsPage
import af.shizuku.manager.utils.ShizukuStateMachine
import rikka.core.content.asActivity
import rikka.core.ktx.unsafeLazy
import rikka.lifecycle.Status
import rikka.recyclerview.addEdgeSpacing
import rikka.recyclerview.addItemSpacing
import rikka.recyclerview.fixEdgeEffect
import rikka.shizuku.Shizuku

import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.platform.LocalContext
import af.shizuku.core.ui.AppActivity
import af.shizuku.manager.home.compose.HomeScreen

abstract class HomeActivity : AppActivity(), MavericksView {

    private val homeModel: HomeViewModel by viewModel()
    private val appsModel: AppsViewModel by viewModels()
    private val adapter by unsafeLazy { HomeAdapter(homeModel, appsModel, lifecycleScope) }
    private var versionClickCount = 0

    // Removed getLayoutId

    private val stateListener: (ShizukuStateMachine.State) -> Unit = { state ->
        when (state) {
            ShizukuStateMachine.State.RUNNING -> {
                // Shizuku started - refresh everything
                checkServerStatus()
                appsModel.load()
                ShizukuSettings.syncAllPlusFeaturesToServer()
            }
            ShizukuStateMachine.State.STOPPED,
            ShizukuStateMachine.State.CRASHED -> {
                // Shizuku stopped or crashed - refresh status display
                checkServerStatus()
            }
            else -> {
                // Starting or stopping - optional refresh
                checkServerStatus()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        splash.setOnExitAnimationListener { provider ->
            // Some OEM/Android 15 builds return a non-null typed View that is actually null
            // at runtime (platform nullability contract violation).
            // We use safe casting to Any? to prevent R8 from eliding the null check.
            try {
                val iconViewAny: Any? = provider.iconView
                if (iconViewAny is android.view.View) {
                    iconViewAny.animate()
                        ?.alpha(0f)
                        ?.scaleX(0.8f)
                        ?.scaleY(0.8f)
                        ?.setDuration(ShizukuSettings.scaledAnimationDuration(220))
                        ?.setInterpolator(android.view.animation.PathInterpolator(0.4f, 0f, 1f, 1f))
                        ?.withEndAction { provider.remove() }
                        ?.start()
                } else {
                    provider.remove()
                }
            } catch (e: Exception) {
                runCatching { provider.remove() }
            }
        }
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        var showEmptyState by mutableStateOf(false)
        var isEditMode by mutableStateOf(HomeEditMode.isActive)
        val recyclerView = RecyclerView(this).apply {
            id = android.R.id.list
            clipToPadding = false
        }

        setContent {
            val context = LocalContext.current
            af.shizuku.core.ui.compose.AppTheme(
                darkTheme = androidx.compose.foundation.isSystemInDarkTheme(),
                isBlackNightTheme = af.shizuku.manager.app.ThemeHelper.isBlackNightTheme(context)
            ) {
                HomeScreen(
                isEditMode = isEditMode,
                showEmptyState = showEmptyState,
                onStopClick = {
                    if (ShizukuStateMachine.isRunning()) {
                        MaterialAlertDialogBuilder(this)
                            .setMessage(R.string.dialog_stop_message)
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPING)
                                runCatching { Shizuku.exit() }
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                },
                onSettingsClick = {
                    startActivity(Intent(this, SettingsActivity::class.java))
                },
                onHelpClick = {
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.settings_shizuku_plus_features)
                        .setMessage(getString(R.string.help_general_plus_summary).toHtml())
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                },
                onRestoreHomeCards = { HomeEditMode.enter() },
                recyclerViewProvider = { ctx, paddingValues ->
                    val density = ctx.resources.displayMetrics.density
                    recyclerView.apply {
                        setPadding(
                            paddingLeft,
                            (paddingValues.calculateTopPadding().value * density).toInt(),
                            paddingRight,
                            (paddingValues.calculateBottomPadding().value * density).toInt()
                        )
                    }
                }
            )
            }
        }

        when (intent?.getStringExtra("shortcut_action")) {
            "start_wireless_adb" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                AdbDialogFragment().show(supportFragmentManager)
            }
            "open_terminal" -> startActivity(android.content.Intent(this, af.shizuku.manager.shell.ShellTutorialActivity::class.java))
        }

        // Initial status load
        homeModel.reload()

        homeModel.onEach(HomeState::serviceStatus) {
            if (it is Success) {
                val status = it.invoke()
                val wasRunning = adapter.itemCount > 0 && (adapter.getItemId(0) == HomeAdapter.ID_STATUS) &&
                                (withState(homeModel) { s -> s.serviceStatus.invoke()?.isRunning == true })

                adapter.updateData()
                ShizukuSettings.setLastLaunchMode(if (status.uid == 0) ShizukuSettings.LaunchMethod.ROOT else ShizukuSettings.LaunchMethod.ADB)

                if (status.isRunning && !wasRunning && ShizukuSettings.isExpressiveAnimationsEnabled()) {
                    recyclerView.post {
                        val view = recyclerView
                        if (!view.isAttachedToWindow) return@post
                        val statusCard = view.findViewHolderForAdapterPosition(0)?.itemView
                        val cx = view.width / 2
                        val cy = statusCard?.let { it.top + it.height / 2 } ?: 100
                        val finalRadius = Math.hypot(view.width.toDouble(), view.height.toDouble()).toFloat()

                        // OneUI 8+ uses more "elastic" easing (0.22, 1, 0.36, 1)
                        val interpolator = if (EnvironmentUtils.isOneUi8())
                            androidx.core.view.animation.PathInterpolatorCompat.create(0.22f, 1f, 0.36f, 1f)
                        else
                            androidx.core.view.animation.PathInterpolatorCompat.create(0.2f, 0f, 0f, 1f)

                        android.view.ViewAnimationUtils.createCircularReveal(view, cx, cy, 0f, finalRadius).apply {
                            duration = ShizukuSettings.scaledAnimationDuration(if (EnvironmentUtils.isOneUi8()) 800L else 600L)
                            this.interpolator = interpolator
                            start()
                        }

                        if (EnvironmentUtils.isOneUi8()) {
                            HapticUtils.success(view)
                        }
                    }
                }
            }
        }

        homeModel.onEach(HomeState::shouldShowBatteryOptimizationSnackbar) {
            if (it) {
                SnackbarHelper.show(
                    this,
                    findViewById(android.R.id.content) ?: window.decorView,
                    msg = getString(R.string.snackbar_battery_optimization_home),
                    duration = Snackbar.LENGTH_INDEFINITE,
                    actionText = getString(R.string.snackbar_action_fix),
                    action = {
                        if (EnvironmentUtils.isSamsung()) {
                            SettingsPage.Samsung.DeviceCareBattery.launch(this)
                        } else {
                            SettingsHelper.requestIgnoreBatteryOptimizations(this, null)
                        }
                    }
                )
            }
        }
        homeModel.checkBatteryOptimization()

        // Samsung Auto Blocker check for One UI 7/8+
        if (EnvironmentUtils.isSamsung() && EnvironmentUtils.getOneUiVersion() >= 6) {
            homeModel.onEach(HomeState::serviceStatus) {
                if (it is Success && it.invoke().isRunning == false) {
                    SnackbarHelper.show(
                        this,
                        findViewById(android.R.id.content) ?: window.decorView,
                        msg = "Samsung Auto Blocker may block ADB on One UI 7/8. Check Security settings.",
                        duration = Snackbar.LENGTH_LONG,
                        actionText = "Check",
                        action = {
                            try {
                                startActivity(Intent("android.settings.SECURITY_ADVANCED_SETTINGS"))
                            } catch (e: Exception) {
                                startActivity(Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS))
                            }
                        }
                    )
                }
            }
        }

        appsModel.grantedCount.observe(this) {
            if (it.status == Status.SUCCESS) {
                homeModel.updateGrantedAppCount(it.data ?: 0)
                adapter.updateData()
            }
        }

        // Check for updates on app startup (if enabled)
        checkForUpdates()

        // Force single column for original Shizuku look
        val spanCount = 1
        val layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, spanCount)
        layoutManager.spanSizeLookup = object : androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int = 1
        }
        recyclerView.layoutManager = layoutManager

        // Samsung DeX Specific: add 'sidebar' feel with larger horizontal margins
        val isDeX = EnvironmentUtils.isSamsung() && EnvironmentUtils.isDeX(this)
        val dexPadding = if (isDeX) (48 * resources.displayMetrics.density).toInt() else 0

        recyclerView.adapter = adapter
        (recyclerView.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
        recyclerView.fixEdgeEffect()

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(recyclerView) { v, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            v.setPadding(
                systemBars.left + dexPadding,
                v.paddingTop,
                systemBars.right + dexPadding,
                v.paddingBottom
            )
            insets
        }

        // Listen for empty state changes
        adapter.onEmptyStateChanged = { isEmpty ->
            showEmptyState = isEmpty
        }

        val cardSpacing = resources.getDimension(R.dimen.card_spacing)
        val marginHorizontal = resources.getDimension(R.dimen.margin_horizontal)
        val marginVertical = resources.getDimension(R.dimen.margin_vertical)

        val itemSpacing = cardSpacing / 2f
        val edgeSpacingH = marginHorizontal
        val edgeSpacingV = marginVertical - itemSpacing

        recyclerView.addItemSpacing(top = itemSpacing, bottom = itemSpacing)
        recyclerView.addEdgeSpacing(top = edgeSpacingV, bottom = edgeSpacingV, left = edgeSpacingH, right = edgeSpacingH)

        // Drag-to-reorder support
        val dragCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun isLongPressDragEnabled() = false

            override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
                return if (adapter.isDraggable(vh.adapterPosition))
                    makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
                    else
                    makeMovementFlags(0, 0)
                    }

                    override fun onMove(rv: RecyclerView, src: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                if (!adapter.isDraggable(target.adapterPosition)) return false
                adapter.moveItem(src.adapterPosition, target.adapterPosition)
                if (ShizukuSettings.isExpressiveAnimationsEnabled()) {
                    HapticUtils.tap(target.itemView)
                }
                return true
            }

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    adapter.isDragging = true
                    if (ShizukuSettings.isExpressiveAnimationsEnabled()) {
                        viewHolder?.itemView?.let { HapticUtils.gestureStart(it) }
                        viewHolder?.itemView?.animate()
                            ?.scaleX(1.04f)
                            ?.scaleY(1.04f)
                            ?.translationZ(16f)
                            ?.setDuration(ShizukuSettings.scaledAnimationDuration(200))
                            ?.setInterpolator(android.view.animation.DecelerateInterpolator())
                            ?.start()
                    }
                } else if (actionState == ItemTouchHelper.ACTION_STATE_IDLE) {
                    adapter.isDragging = false
                }
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                adapter.isDragging = false
                if (ShizukuSettings.isExpressiveAnimationsEnabled()) {
                    // Guard against NPE: animate() returns null when the view is
                    // no longer attached to a window (OEM/Android 15+ nullability
                    // contract violation observed in crash reports).
                    if (vh.itemView.isAttachedToWindow) {
                        vh.itemView.animate()
                            ?.scaleX(1f)
                            ?.scaleY(1f)
                            ?.translationZ(0f)
                            ?.setDuration(ShizukuSettings.scaledAnimationDuration(250))
                            ?.setInterpolator(android.view.animation.OvershootInterpolator(0.8f))
                            ?.start()
                    }
                }
                adapter.persistCardOrder()
                adapter.updateData()
            }
        }
        val itemTouchHelper = ItemTouchHelper(dragCallback)
        itemTouchHelper.attachToRecyclerView(recyclerView)

        HomeEditMode.startDragCallback = { vh -> itemTouchHelper.startDrag(vh) }
        HomeEditMode.exit()

        // Predictive back support for edit mode with expressive M3 scaling
        val backCallback = object : androidx.activity.OnBackPressedCallback(HomeEditMode.isActive) {
            private var backThresholdReached = false

            override fun handleOnBackProgressed(backEvent: androidx.activity.BackEventCompat) {
                if (ShizukuSettings.isExpressiveAnimationsEnabled()) {
                    val progress = backEvent.progress

                    if (progress > 0.1f && !backThresholdReached) {
                        backThresholdReached = true
                        HapticUtils.gestureThreshold(recyclerView)
                    } else if (progress < 0.1f) {
                        backThresholdReached = false
                    }

                    // Subtle parabolic scale and alpha for more "physical" feel
                    val scale = 1f - (0.08f * progress * progress)
                    recyclerView.scaleX = scale
                    recyclerView.scaleY = scale
                    recyclerView.alpha = 1f - (0.15f * progress)
                }
            }

            override fun handleOnBackPressed() {
                backThresholdReached = false
                if (HomeEditMode.isActive) {
                    HomeEditMode.exit()
                    if (ShizukuSettings.isExpressiveAnimationsEnabled()) {
                        recyclerView.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .alpha(1f)
                            .setDuration(ShizukuSettings.scaledAnimationDuration(400))
                            .setInterpolator(androidx.core.view.animation.PathInterpolatorCompat.create(0.2f, 0f, 0f, 1f))
                            .start()
                    }
                }
            }

            override fun handleOnBackCancelled() {
                if (ShizukuSettings.isExpressiveAnimationsEnabled()) {
                    recyclerView.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(1f)
                        .setDuration(ShizukuSettings.scaledAnimationDuration(300))
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .start()
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        HomeEditMode.onChanged = {
            lifecycleScope.launch {
                delay(150)
                isEditMode = HomeEditMode.isActive
                homeModel.setEditMode(HomeEditMode.isActive)
                adapter.updateData()
                backCallback.isEnabled = HomeEditMode.isActive
            }
        }

        ShizukuStateMachine.addListener(stateListener)

        // Handle cold-start launch from the ADB pairing success notification.
        // onNewIntent() is only called when the activity already exists; when the app
        // is not running the system calls onCreate() with the launch intent instead.
        if (intent?.getBooleanExtra(HomeActivity.EXTRA_START_SERVICE_VIA_WADB, false) == true) {
            handleStartViaMdnsNotification()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val showDialog = intent.getBooleanExtra(HomeActivity.EXTRA_SHOW_PAIRING_DIALOG, false)
        if (showDialog) showAccessibilityDialog()

        if (intent.getBooleanExtra(HomeActivity.EXTRA_START_SERVICE_VIA_WADB, false)) {
            handleStartViaMdnsNotification()
        }
    }

    private fun handleStartViaMdnsNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(AdbPairingService.NOTIFICATION_ID)
        val discoveredPort = withState(homeModel) { s -> s.discoveredAdbPort }
        StartWirelessAdbViewHolder.start(this, lifecycleScope, discoveredPort)
    }

    override fun onResume() {
        super.onResume()
        // Force refresh status on resume
        checkServerStatus()
        // Also reload apps list
        appsModel.load()
    }

    override fun onPause() {
        super.onPause()
        SnackbarHelper.dismiss()
    }

    override fun invalidate() {
        // Mavericks state changed - individual property observers (onEach) handle specific updates
    }

    private fun checkServerStatus() {
        homeModel.reload()
    }

    override fun onDestroy() {
        HomeEditMode.onChanged = null
        HomeEditMode.startDragCallback = null
        HomeEditMode.removeCardCallback = null
        ShizukuStateMachine.removeListener(stateListener)
        super.onDestroy()
    }


    /**
     * Check for updates on app startup and show popup dialog
     */
    private fun checkForUpdates() {
        if (!ShizukuSettings.isAutoUpdateEnabled()) {
            return
        }

        // Check if we've already checked today
        val lastCheckTime = ShizukuSettings.getLastUpdateCheckTime()
        val now = System.currentTimeMillis()
        val oneDayInMillis = 24 * 60 * 60 * 1000L

        if (now - lastCheckTime < oneDayInMillis) {
            return
        }

        if (isFinishing || isDestroyed) return

        // Check for updates silently in background
        lifecycleScope.launch {
            try {
                val result = UpdateChecker.checkForUpdate(ShizukuSettings.getUpdateChannel())

                when (result) {
                    is UpdateChecker.CheckResult.UpdateAvailable -> {
                        ShizukuSettings.setLastUpdateCheckTime(now)
                        ShizukuSettings.setLastUpdateCheckFailed(false)
                        if (!isFinishing && !isDestroyed) showUpdateAvailableDialog(result.info)
                    }
                    is UpdateChecker.CheckResult.UpToDate -> {
                        ShizukuSettings.setLastUpdateCheckTime(now)
                        ShizukuSettings.setLastUpdateCheckFailed(false)
                    }
                    is UpdateChecker.CheckResult.NetworkError -> {
                        ShizukuSettings.setLastUpdateCheckFailed(true)
                    }
                }
            } catch (e: Exception) {
                Timber.tag("HomeActivity").e(e, "Unexpected error checking for update")
                ShizukuSettings.setLastUpdateCheckFailed(true)
            }
        }
    }

    /**
     * Show update available popup dialog
     */
    private fun showUpdateAvailableDialog(updateInfo: UpdateChecker.UpdateInfo) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_update_available, null)
        dialogView.findViewById<TextView>(R.id.update_version_name).text = "Version ${updateInfo.versionName}"
        dialogView.findViewById<TextView>(R.id.update_published_date).text =
            if (updateInfo.publishedAt.isNotEmpty())
                "Published: ${UpdateChecker.formatPublishedDate(updateInfo.publishedAt)}"
            else ""
        dialogView.findViewById<TextView>(R.id.update_release_notes).text =
            updateInfo.releaseNotes.ifEmpty { getString(R.string.update_no_release_notes) }

        val openReleases = {
            startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://github.com/thejaustin/ShizukuPlus/releases"))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_available_title)
            .setView(dialogView)
            .setNegativeButton(R.string.update_later, null)
            .setNeutralButton(R.string.update_release_notes) { _, _ -> openReleases() }

        if (updateInfo.requiresManualDownload) {
            builder.setPositiveButton(R.string.update_view_on_github) { _, _ -> openReleases() }
        } else {
            builder.setPositiveButton(R.string.update_download) { _, _ ->
                UpdateManager(this).downloadUpdate(updateInfo.downloadUrl, updateInfo.versionName)
            }
        }

        builder.show()
    }

    companion object {
        const val EXTRA_SHOW_PAIRING_DIALOG = "show_pairing_dialog"
        const val EXTRA_START_SERVICE_VIA_WADB = "start_service_via_wadb"
    }

}
