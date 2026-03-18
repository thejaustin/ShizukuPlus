package moe.shizuku.manager.home

import android.app.NotificationManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import android.os.Bundle
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbPairingService
import moe.shizuku.manager.app.AppBarActivity
import moe.shizuku.manager.app.SnackbarHelper
import moe.shizuku.manager.databinding.AboutDialogBinding
import moe.shizuku.manager.databinding.HomeActivityBinding
import moe.shizuku.manager.home.showAccessibilityDialog
import moe.shizuku.manager.ktx.toHtml
import moe.shizuku.manager.management.AppsViewModel
import moe.shizuku.manager.settings.SettingsActivity
import moe.shizuku.manager.utils.AppIconCache
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.SettingsHelper
import moe.shizuku.manager.utils.ShizukuStateMachine
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
        super.onCreate(savedInstanceState)

        val binding = HomeActivityBinding.inflate(layoutInflater, rootView, true)

        // Empty state view for when all cards are hidden
        val emptyStateView = binding.emptyStateView
        emptyStateView.setIcon(R.drawable.ic_empty_home_24)
        emptyStateView.setTitle(R.string.empty_state_title_no_home_cards)
        emptyStateView.setDescription(R.string.empty_state_description_no_home_cards)
        emptyStateView.setActionText(R.string.empty_state_action_restore_home_cards)
        emptyStateView.setActionClickListener {
            startActivity(android.content.Intent(this, moe.shizuku.manager.settings.SettingsActivity::class.java))
        }

        // Initial status load - MUST be called before observer
        checkServerStatus()

        homeModel.serviceStatus.observe(this) {
            if (it.status == Status.SUCCESS) {
                val status = homeModel.serviceStatus.value?.data ?: return@observe
                adapter.updateData()
                ShizukuSettings.setLastLaunchMode(if (status.uid == 0) ShizukuSettings.LaunchMethod.ROOT else ShizukuSettings.LaunchMethod.ADB)
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
                    action = { SettingsHelper.requestIgnoreBatteryOptimizations(this, null) }
                )
            }
        }
        homeModel.checkBatteryOptimization()

        appsModel.grantedCount.observe(this) {
            if (it.status == Status.SUCCESS) {
                adapter.updateData()
            }
        }

        val recyclerView = binding.list
        recyclerView.adapter = adapter
        (recyclerView.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
        recyclerView.fixEdgeEffect()

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
                return true
            }

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    adapter.isDragging = true
                } else if (actionState == ItemTouchHelper.ACTION_STATE_IDLE) {
                    adapter.isDragging = false
                }
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                adapter.isDragging = false
                adapter.persistCardOrder()
                adapter.updateData()
            }
        }
        val itemTouchHelper = ItemTouchHelper(dragCallback)
        itemTouchHelper.attachToRecyclerView(recyclerView)

        HomeEditMode.startDragCallback = { vh -> itemTouchHelper.startDrag(vh) }
        HomeEditMode.exit()

        // Predictive back support for edit mode
        val backCallback = object : androidx.activity.OnBackPressedCallback(HomeEditMode.isActive) {
            override fun handleOnBackPressed() {
                if (HomeEditMode.isActive) {
                    HomeEditMode.exit()
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
            menu.findItem(R.id.action_about)?.isVisible = false
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_about -> {
                val binding = AboutDialogBinding.inflate(LayoutInflater.from(this), null, false)
                binding.sourceCode.movementMethod = LinkMovementMethod.getInstance()
                binding.sourceCode.text = getString(
                    R.string.about_view_source_code,
                    "<b><a href=\"https://github.com/thejaustin/ShizukuPlus\">GitHub</a></b>"
                ).toHtml()
                binding.icon.setImageBitmap(
                    AppIconCache.getOrLoadBitmap(
                        this,
                        applicationInfo,
                        Process.myUid() / 100000,
                        resources.getDimensionPixelOffset(R.dimen.default_app_icon_size)
                    )
                )
                val versionName = packageManager.getPackageInfo(packageName, 0).versionName
                binding.versionName.text = versionName

                // Add click listener to version name for developer options
                binding.versionName.setOnClickListener {
                    if (ShizukuSettings.isVectorEnabled()) {
                        Toast.makeText(this, R.string.settings_developer_options_revealed, Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }

                    versionClickCount++
                    if (versionClickCount >= 7) {
                        ShizukuSettings.setVectorEnabled(true)
                        Toast.makeText(this, R.string.settings_developer_options_revealed, Toast.LENGTH_SHORT).show()
                        versionClickCount = 0
                    } else if (versionClickCount > 2) {
                        Toast.makeText(this, getString(R.string.settings_developer_options_click_more, 7 - versionClickCount), Toast.LENGTH_SHORT).show()
                    }
                }

                val dialog = MaterialAlertDialogBuilder(this)
                    .setView(binding.root)
                    .create()

                binding.btnClose.setOnClickListener {
                    dialog.dismiss()
                }

                dialog.show()
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

    companion object {
        const val EXTRA_SHOW_PAIRING_DIALOG = "show_pairing_dialog"
        const val EXTRA_START_SERVICE_VIA_WADB = "start_service_via_wadb"
    }

}
