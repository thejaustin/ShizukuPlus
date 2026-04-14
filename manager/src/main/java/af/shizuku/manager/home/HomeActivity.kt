package af.shizuku.manager.home

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
import af.shizuku.manager.app.AppBarActivity
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

abstract class HomeActivity : AppBarActivity() {

    private val homeModel: HomeViewModel by viewModels()
    private val appsModel: AppsViewModel by viewModels()
    private val adapter by unsafeLazy { HomeAdapter(homeModel, appsModel, lifecycleScope) }
    private var versionClickCount = 0

    override fun getLayoutId(): Int {
        return R.layout.home_activity
    }

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
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // Bind to the rootView that was created by AppBarActivity.setContentView(getLayoutId())
        val binding = HomeActivityBinding.bind(rootView)

        // Handle Shortcut Intent
        if (intent?.getStringExtra("shortcut_action") == "start_wireless_adb") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                AdbDialogFragment().show(supportFragmentManager)
            }
        }

        // Empty state view for when all cards are hidden
        val emptyStateView = binding.emptyStateView
        emptyStateView.setIcon(R.drawable.ic_empty_home_24)
        emptyStateView.setTitle(R.string.empty_state_title_no_home_cards)
        emptyStateView.setDescription(R.string.empty_state_description_no_home_cards)
        emptyStateView.setActionText(R.string.empty_state_action_restore_home_cards)
        emptyStateView.setActionClickListener {
            startActivity(android.content.Intent(this, af.shizuku.manager.settings.SettingsActivity::class.java))
        }

        // Initial status load - MUST be called before observer
        checkServerStatus()

        homeModel.serviceStatus.observe(this) {
            if (it.status == Status.SUCCESS) {
                val status = it.data ?: return@observe
                val wasRunning = adapter.itemCount > 0 && (adapter.getItemId(0) == HomeAdapter.ID_STATUS) && 
                                (homeModel.serviceStatus.value?.data?.isRunning == true)
                
                adapter.updateData()
                ShizukuSettings.setLastLaunchMode(if (status.uid == 0) ShizukuSettings.LaunchMethod.ROOT else ShizukuSettings.LaunchMethod.ADB)

                // Expressive Ripple: If service just started, perform a circular reveal
                if (status.isRunning && !wasRunning && ShizukuSettings.isExpressiveAnimationsEnabled()) {
                    binding.list.post {
                        val view = binding.list
                        val statusCard = view.findViewHolderForAdapterPosition(0)?.itemView
                        val cx = view.width / 2
                        val cy = statusCard?.let { it.top + it.height / 2 } ?: 100
                        val finalRadius = Math.hypot(view.width.toDouble(), view.height.toDouble()).toFloat()
                        
                        android.view.ViewAnimationUtils.createCircularReveal(view, cx, cy, 0f, finalRadius).apply {
                            duration = 600
                            interpolator = androidx.core.view.animation.PathInterpolatorCompat.create(0.2f, 0f, 0f, 1f)
                            start()
                        }
                    }
                }
            }
        }

        homeModel.shouldShowBatteryOptimizationSnackbar.observe(this) {
            if (it) {
                SnackbarHelper.show(
                    this,
                    binding.root,
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
            homeModel.serviceStatus.observe(this) {
                if (it.status == Status.SUCCESS && it.data?.isRunning == false) {
                    SnackbarHelper.show(
                        this,
                        binding.root,
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
                adapter.updateData()
            }
        }

        // Check for updates on app startup (if enabled)
        checkForUpdates()

        val recyclerView = binding.list
        
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
                systemBars.bottom
            )
            insets
        }

        // Listen for empty state changes
        adapter.onEmptyStateChanged = { isEmpty ->
            emptyStateView.visibility = if (isEmpty) View.VISIBLE else View.GONE
            recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
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
                    target.itemView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                }
                return true
            }

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    adapter.isDragging = true
                    if (ShizukuSettings.isExpressiveAnimationsEnabled()) {
                        val haptic = if (Build.VERSION.SDK_INT >= 30) android.view.HapticFeedbackConstants.GESTURE_START else android.view.HapticFeedbackConstants.LONG_PRESS
                        viewHolder?.itemView?.performHapticFeedback(haptic)
                        viewHolder?.itemView?.animate()
                            ?.scaleX(1.04f)
                            ?.scaleY(1.04f)
                            ?.translationZ(16f)
                            ?.setDuration(200)
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
                    vh.itemView.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .translationZ(0f)
                        .setDuration(250)
                        .setInterpolator(android.view.animation.OvershootInterpolator(0.8f))
                        .start()
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
            override fun handleOnBackProgressed(backEvent: androidx.activity.BackEventCompat) {
                if (ShizukuSettings.isExpressiveAnimationsEnabled()) {
                    val progress = backEvent.progress
                    // Subtle parabolic scale and alpha for more "physical" feel
                    val scale = 1f - (0.08f * progress * progress)
                    recyclerView.scaleX = scale
                    recyclerView.scaleY = scale
                    recyclerView.alpha = 1f - (0.15f * progress)
                }
            }
            
            override fun handleOnBackPressed() {
                if (HomeEditMode.isActive) {
                    HomeEditMode.exit()
                    if (ShizukuSettings.isExpressiveAnimationsEnabled()) {
                        recyclerView.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .alpha(1f)
                            .setDuration(400)
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
                        .setDuration(300)
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .start()
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        HomeEditMode.onChanged = {
            lifecycleScope.launch {
                delay(150)
                adapter.updateData()
                invalidateOptionsMenu()
                backCallback.isEnabled = HomeEditMode.isActive
                if (HomeEditMode.isActive) {
                    supportActionBar?.setTitle(R.string.home_edit_mode_hint)
                } else {
                    supportActionBar?.setTitle(R.string.app_name)
                }
            }
        }

        ShizukuStateMachine.addListener(stateListener)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.let {
            val showDialog = it.getBooleanExtra(HomeActivity.EXTRA_SHOW_PAIRING_DIALOG, false)
            if (showDialog) showAccessibilityDialog()

            val startWadb = it.getBooleanExtra(HomeActivity.EXTRA_START_SERVICE_VIA_WADB, false)
            if (startWadb) {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(AdbPairingService.NOTIFICATION_ID)
                StartWirelessAdbViewHolder.start(this, lifecycleScope)
            }
        }
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

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        if (HomeEditMode.isActive) {
            menu.findItem(R.id.action_stop)?.isVisible = false
            menu.findItem(R.id.action_settings)?.isVisible = false
            menu.findItem(R.id.action_help)?.isVisible = false
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_help -> {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.settings_shizuku_plus_features)
                    .setMessage(R.string.help_general_plus_summary)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                true
            }
            R.id.action_stop -> {
                if (ShizukuStateMachine.isRunning()) {
                    MaterialAlertDialogBuilder(this)
                        .setMessage(R.string.dialog_stop_message)
                        .setPositiveButton(android.R.string.ok) { _: DialogInterface?, _: Int ->
                            ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPING)
                            runCatching { Shizuku.exit() }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
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

        // Show checking dialog briefly
        val checkingDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_checking)
            .setCancelable(false)
            .show()

        // Check for updates in background
        lifecycleScope.launch {
            try {
                val result = UpdateChecker.checkForUpdate(ShizukuSettings.getUpdateChannel())

                try {
                    if (checkingDialog.isShowing && !isFinishing && !isDestroyed) {
                        checkingDialog.dismiss()
                    }
                } catch (e: IllegalArgumentException) {
                    // Window already detached by the time the coroutine resumed — safe to ignore
                    Timber.tag("HomeActivity").w("checkingDialog dismiss failed: window already detached")
                }

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
                try {
                    if (checkingDialog.isShowing && !isFinishing && !isDestroyed) {
                        checkingDialog.dismiss()
                    }
                } catch (ex: IllegalArgumentException) {
                    Timber.tag("HomeActivity").w("checkingDialog dismiss failed in catch: window already detached")
                }
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
        dialogView.findViewById<TextView>(R.id.update_release_notes).text = updateInfo.releaseNotes

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
