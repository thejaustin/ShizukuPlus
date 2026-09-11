package af.shizuku.manager.management

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.doOnLayout
import androidx.core.widget.doOnTextChanged
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.AdapterDataObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.core.ui.AppBarActivity
import af.shizuku.manager.authorization.AuthorizationManager
import af.shizuku.manager.databinding.AppsActivityBinding
import af.shizuku.manager.databinding.AppsAppbarActivityBinding
import af.shizuku.manager.databinding.SwipeHintOverlayBinding
import timber.log.Timber
import af.shizuku.manager.management.AppViewHolder.Callbacks
import af.shizuku.manager.database.ActivityLogManager
import af.shizuku.manager.utils.HapticUtils
import af.shizuku.manager.utils.ShizukuStateMachine
import rikka.lifecycle.Status
import rikka.recyclerview.addEdgeSpacing
import rikka.recyclerview.fixEdgeEffect
import java.util.Objects

open class ApplicationManagementActivity : AppBarActivity(), AppViewHolder.Callbacks {

    private val viewModel: AppsViewModel by viewModels()
    private val adapter = AppsAdapter()
    private lateinit var recyclerView: RecyclerView
    private var firstLoad = true
    private var backCallback: androidx.activity.OnBackPressedCallback? = null
    private var swipeRightAction = "none"
    private var swipeLeftAction = "none"
    private var isFirstResume = true

    private val stateListener: (ShizukuStateMachine.State) -> Unit = { state ->
        when {
            ShizukuStateMachine.isDead() && !isFinishing -> finish()
            state == ShizukuStateMachine.State.RUNNING -> viewModel.load()
        }
    }

    override fun getLayoutId() = R.layout.apps_appbar_activity

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Close immediately only when Shizuku is definitively stopped/crashed; allow the
        // activity to open during STARTING so it can display a "waiting" state and load the
        // list as soon as the binder arrives (fixes #471 — tap during startup transition).
        if (ShizukuStateMachine.isDead()) {
            finish()
            return
        }

        val binding = AppsActivityBinding.inflate(layoutInflater, rootView, false)
        setContentView(binding.root)

        // Assign before observe() — LiveData delivers synchronously on config change
        // and the observer references recyclerView, so it must be ready first.
        recyclerView = binding.list

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Empty state view — also used as "waiting for Shizuku" placeholder while STARTING.
        val emptyStateView = binding.emptyStateView
        emptyStateView.setIcon(R.drawable.ic_empty_search_24)
        emptyStateView.setTitle(R.string.empty_state_title_no_results)
        emptyStateView.setDescription(R.string.empty_state_description_no_results)
        emptyStateView.hideActionButton()

        // If the binder isn't up yet (STARTING state), show a "waiting" placeholder and let
        // the stateListener trigger the real load once RUNNING is reached.
        if (!ShizukuStateMachine.isRunning()) {
            emptyStateView.setTitle(R.string.empty_state_title_service_starting)
            emptyStateView.setDescription(R.string.empty_state_description_service_starting)
            emptyStateView.visibility = View.VISIBLE
        }

        // Predictive back support for selection mode
        backCallback = object : androidx.activity.OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (adapter.isSelectionMode) {
                    adapter.isSelectionMode = false
                    invalidateOptionsMenu()
                    supportActionBar?.title = getString(R.string.home_app_management_title)
                    this.isEnabled = false
                }
            }
        }
        backCallback?.let {
            onBackPressedDispatcher.addCallback(this, it)
        }

        // Search bar
        rootView.findViewById<android.widget.EditText>(R.id.search_edit_text).doOnTextChanged { text, _, _, _ ->
            viewModel.setSearch(text?.toString() ?: "")
        }

        rootView.findViewById<com.google.android.material.chip.ChipGroup>(R.id.filter_chip_group).setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            when (checkedIds.first()) {
                R.id.chip_all -> viewModel.setFilter(FilterState.ALL)
                R.id.chip_granted -> viewModel.setFilter(FilterState.GRANTED)
                R.id.chip_denied -> viewModel.setFilter(FilterState.DENIED)
                R.id.chip_hidden -> viewModel.setFilter(FilterState.HIDDEN)
            }
        }

        viewModel.packages.observe(this) {
            when (it.status) {
                Status.SUCCESS -> {
                    val data = it.data ?: emptyList()
                    adapter.updateData(data)

                    // Reset empty state to "no results" variant in case it was showing the
                    // "waiting for Shizuku" placeholder while the service was STARTING.
                    emptyStateView.setTitle(R.string.empty_state_title_no_results)
                    emptyStateView.setDescription(R.string.empty_state_description_no_results)

                    // Show empty state when filtered results are empty
                    val hasData = data.isNotEmpty()
                    emptyStateView.visibility = if (hasData) View.GONE else View.VISIBLE
                    recyclerView.visibility = if (hasData) View.VISIBLE else View.GONE

                    if (firstLoad && !data.isNullOrEmpty()) {
                        firstLoad = false
                        recyclerView.scheduleLayoutAnimation()
                        maybeShowSwipeHint()
                    }
                }
                Status.ERROR -> {
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        finish()
                        Toast.makeText(this, Objects.toString(it.error, "unknown"), Toast.LENGTH_SHORT).show()
                    }
                    Timber.w("load apps failed", it.error)
                }
                Status.LOADING -> {}
            }
        }
        // Only trigger an initial load if Shizuku is actually running; otherwise the
        // stateListener will fire viewModel.load() once the binder arrives.
        if (ShizukuStateMachine.isRunning()) viewModel.load()

        recyclerView.adapter = adapter
        recyclerView.clipToPadding = false

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(recyclerView) { v, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            val extraBottomPadding = (72 * resources.displayMetrics.density).toInt()
            val oneHandedTopPadding = if (ShizukuSettings.isOneHandedModeEnabled()) {
                (resources.displayMetrics.heightPixels * 0.16f).toInt()
            } else {
                0
            }
            v.setPadding(bars.left, oneHandedTopPadding, bars.right, bars.bottom + extraBottomPadding)
            insets
        }

        // M3E 2026 Layout Animation for fluid motion on entry
        recyclerView.layoutAnimation = AnimationUtils.loadLayoutAnimation(
            this, R.anim.layout_animation_slide_bottom
        )

        recyclerView.setBackgroundColor(Color.TRANSPARENT)
        recyclerView.addItemDecoration(AppListItemDecoration(this))
        recyclerView.fixEdgeEffect()
        recyclerView.addEdgeSpacing(top = 8f, bottom = 8f, unit = TypedValue.COMPLEX_UNIT_DIP)

        adapter.registerAdapterDataObserver(object : AdapterDataObserver() {
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
                viewModel.load(true)
            }

            override fun onChanged() {
                backCallback?.isEnabled = adapter.isSelectionMode
                if (adapter.isSelectionMode) {
                    supportActionBar?.title = getString(R.string.app_management_selected, adapter.selectedPackages.size)
                    invalidateOptionsMenu()
                }
            }
        })

        setupSwipe(recyclerView)
        ShizukuStateMachine.addListener(stateListener)
    }

    // ----- Options menu: sort -----

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (adapter.isSelectionMode) {
            menu.add(0, 10, 0, R.string.app_management_bulk_select_all).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            menu.add(0, 11, 0, R.string.app_management_bulk_grant).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            menu.add(0, 12, 0, R.string.app_management_bulk_revoke).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)

            if (viewModel.filterState == FilterState.HIDDEN) {
                menu.add(0, 14, 0, R.string.app_management_undo).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            } else {
                menu.add(0, 13, 0, R.string.app_management_bulk_hide).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            }
            return true
        }
        menuInflater.inflate(R.menu.apps_management_menu, menu)
        val sortId = when (viewModel.sortOrder) {
            SortOrder.NAME_ASC -> R.id.sort_name
            SortOrder.LAST_INSTALLED -> R.id.sort_last_installed
            SortOrder.LAST_UPDATED -> R.id.sort_last_updated
        }
        menu.findItem(sortId)?.isChecked = true
        menu.findItem(R.id.action_hidden_apps)?.isVisible = false
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (adapter.isSelectionMode) {
            when (item.itemId) {
                10 -> { // Select all
                    viewModel.packages.value?.data?.forEach { adapter.selectedPackages.add(it.packageName) }
                    adapter.notifyDataSetChanged()
                }
                11 -> { // Grant all
                    adapter.selectedPackages.toList().forEach { pkg ->
                        val pi = viewModel.packages.value?.data?.find { it.packageName == pkg }
                        pi?.applicationInfo?.uid?.let { uid -> AuthorizationManager.grant(pkg, uid) }
                    }
                    adapter.isSelectionMode = false
                    viewModel.load()
                }
                12 -> { // Revoke all
                    adapter.selectedPackages.toList().forEach { pkg ->
                        val pi = viewModel.packages.value?.data?.find { it.packageName == pkg }
                        pi?.applicationInfo?.uid?.let { uid -> AuthorizationManager.revoke(pkg, uid) }
                    }
                    adapter.isSelectionMode = false
                    viewModel.load()
                }
                13 -> { // Hide all
                    adapter.selectedPackages.toList().forEach { viewModel.hidePackage(it) }
                    adapter.isSelectionMode = false
                    viewModel.load()
                }
                14 -> { // Unhide all
                    adapter.selectedPackages.toList().forEach { viewModel.unhidePackage(it) }
                    adapter.isSelectionMode = false
                    viewModel.load()
                }
                android.R.id.home -> {
                    adapter.isSelectionMode = false
                    backCallback?.isEnabled = false
                    invalidateOptionsMenu()
                    supportActionBar?.title = getString(R.string.home_app_management_title)
                }
            }
            return true
        }
        return when (item.itemId) {
            android.R.id.home -> { finish(); true }
            R.id.sort_name -> { item.isChecked = true; viewModel.setSortOrder(SortOrder.NAME_ASC); true }
            R.id.sort_last_installed -> { item.isChecked = true; viewModel.setSortOrder(SortOrder.LAST_INSTALLED); true }
            R.id.sort_last_updated -> { item.isChecked = true; viewModel.setSortOrder(SortOrder.LAST_UPDATED); true }
            R.id.action_root_compat -> {
                startActivity(Intent(this, af.shizuku.manager.settings.RootCompatibilityActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ----- Hide callback -----

    override fun onHideApp(packageName: String) {
        if (viewModel.filterState == FilterState.HIDDEN) {
            viewModel.unhidePackage(packageName)
            Snackbar.make(rootView, R.string.app_management_undo, Snackbar.LENGTH_LONG).show()
            viewModel.load()
        } else {
            viewModel.hidePackage(packageName)
            Snackbar.make(rootView, R.string.app_management_hidden, Snackbar.LENGTH_LONG)
                .setAction(R.string.app_management_undo) {
                    viewModel.unhidePackage(packageName)
                    viewModel.load()
                }
                .show()
        }
    }

    // ----- Swipe gestures -----

    private fun setupSwipe(rv: RecyclerView) {
        swipeRightAction = ShizukuSettings.getSwipeRightAction()
        swipeLeftAction = ShizukuSettings.getSwipeLeftAction()

        val cb = object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun getSwipeDirs(r: RecyclerView, vh: RecyclerView.ViewHolder): Int {
                if (vh !is AppViewHolder) return 0
                var dirs = 0
                if (swipeRightAction != "none") dirs = dirs or ItemTouchHelper.RIGHT
                if (swipeLeftAction != "none") dirs = dirs or ItemTouchHelper.LEFT
                return dirs
            }

            override fun onMove(r: RecyclerView, v: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val pos = vh.adapterPosition
                val items = adapter.getItems<Any>()
                val item = items.getOrNull(pos) as? PackageInfo
                adapter.notifyItemChanged(pos) // snap back
                item ?: return

                val action = if (direction == ItemTouchHelper.RIGHT) swipeRightAction else swipeLeftAction
                handleSwipeAction(item, action, vh.itemView)
            }

            override fun onChildDraw(
                c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder,
                dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
            ) {
                val v = vh.itemView
                val action = if (dX > 0) swipeRightAction else swipeLeftAction
                if (action != "none") {
                    drawSwipeBackground(c, v, dX, action)
                }
                super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive)
            }
        }
        ItemTouchHelper(cb).attachToRecyclerView(rv)
    }

    // internal (not private): also invoked from AppViewHolder for the TalkBack-accessible
    // equivalent of the swipe gestures (#41) - swiping to trigger an action has no screen-reader
    // affordance, so AppViewHolder exposes the same actions via custom accessibility actions.
    internal fun handleSwipeAction(item: PackageInfo, action: String, itemView: View) {
        val opts = ActivityOptions.makeCustomAnimation(
            this, android.R.anim.fade_in, android.R.anim.fade_out
        ).toBundle()

        val appLabel = item.applicationInfo?.loadLabel(packageManager)?.toString() ?: item.packageName
        ActivityLogManager.log(appLabel, item.packageName, "Swipe: $action")

        if (action == "none") {
            HapticUtils.error(itemView)
        } else {
            HapticUtils.success(itemView)
        }

        when (action) {
            "open_app" -> {
                val intent = packageManager.getLaunchIntentForPackage(item.packageName)
                if (intent != null) startActivity(intent, opts)
                else Toast.makeText(this, R.string.app_management_no_launcher, Toast.LENGTH_SHORT).show()
            }
            "app_info" -> startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", item.packageName, null)), opts
            )
            "toggle_permission" -> {
                val uid = item.applicationInfo?.uid ?: return
                // Binder IPC (granted + grant/revoke) must not block the main thread — an ANR
                // risk that showed up on slow devices during rapid swipe gestures.
                // After the toggle, post a targeted payload-rebind so the switch reflects the
                // new state: the snap-back notifyItemChanged(pos) from onSwiped may have started
                // a grantedLoadJob that raced against this IO work and read the pre-toggle state.
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        if (AuthorizationManager.granted(item.packageName, uid)) {
                            AuthorizationManager.revoke(item.packageName, uid)
                        } else {
                            AuthorizationManager.grant(item.packageName, uid)
                        }
                        withContext(Dispatchers.Main) {
                            val items = adapter.getItems<Any>()
                            val pos = items.indexOfFirst {
                                it is PackageInfo && it.packageName == item.packageName
                            }
                            if (pos >= 0) adapter.notifyItemChanged(pos, Any())
                            adapter.notifyItemChanged(0) // update toggle-all header
                        }
                    } catch (e: SecurityException) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@ApplicationManagementActivity,
                                R.string.app_management_dialog_adb_is_limited_title, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            "hide_from_list" -> onHideApp(item.packageName)
        }
    }

    private fun drawSwipeBackground(c: Canvas, v: android.view.View, dX: Float, action: String) {
        val (bgAttr, onAttr, iconRes) = when (action) {
            "open_app" -> Triple(android.R.attr.colorPrimary, com.google.android.material.R.attr.colorOnPrimary, R.drawable.ic_outline_play_arrow_24)
            "app_info" -> Triple(com.google.android.material.R.attr.colorSecondary, com.google.android.material.R.attr.colorOnSecondary, R.drawable.ic_outline_info_24)
            "toggle_permission" -> Triple(com.google.android.material.R.attr.colorTertiary, com.google.android.material.R.attr.colorOnTertiary, R.drawable.ic_shield_24)
            "hide_from_list" -> Triple(android.R.attr.colorError, com.google.android.material.R.attr.colorOnError, R.drawable.ic_visibility_off_24)
            else -> Triple(com.google.android.material.R.attr.colorSecondary, com.google.android.material.R.attr.colorOnSecondary, R.drawable.ic_outline_info_24)
        }
        val tv = TypedValue()
        theme.resolveAttribute(bgAttr, tv, true)
        val bgColor = tv.data
        theme.resolveAttribute(onAttr, tv, true)
        val iconTint = tv.data

        val bg = ColorDrawable(bgColor)
        val icon = AppCompatResources.getDrawable(this, iconRes)?.mutate()?.also { it.setTint(iconTint) }
        val margin = v.height / 4
        val intrinsicHeight = icon?.intrinsicHeight ?: 0
        val intrinsicWidth = icon?.intrinsicWidth ?: 0
        val top = v.top + (v.height - intrinsicHeight) / 2

        if (dX > 0) {
            bg.setBounds(v.left, v.top, v.left + dX.toInt(), v.bottom)
            bg.draw(c)
            icon?.setBounds(v.left + margin, top, v.left + margin + intrinsicWidth, top + intrinsicHeight)
            icon?.draw(c)
        } else {
            bg.setBounds(v.right + dX.toInt(), v.top, v.right, v.bottom)
            bg.draw(c)
            icon?.setBounds(v.right - margin - intrinsicWidth, top, v.right - margin, top + intrinsicHeight)
            icon?.draw(c)
        }
    }

    // ----- First-run swipe hint -----

    private fun maybeShowSwipeHint() {
        val prefs = getSharedPreferences("app_management_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("swipe_hint_shown", false)) return
        prefs.edit().putBoolean("swipe_hint_shown", true).apply()
        recyclerView.postDelayed({ if (!isFinishing) showSwipeHint() }, 500)
    }

    private fun showSwipeHint() {
        val hintBinding = SwipeHintOverlayBinding.inflate(layoutInflater, rootView as ViewGroup, false)
        val hint = hintBinding.root
        (rootView as ViewGroup).addView(hint)

        hint.doOnLayout { v ->
            // Start below screen, slide up
            v.translationY = v.height.toFloat()
            v.animate()
                .translationY(0f)
                .setDuration(ShizukuSettings.scaledAnimationDuration(350))
                .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
                .start()

            // Bounce the right-swipe icon right to hint the gesture
            val iconRight = hintBinding.hintIconRight
            iconRight.postDelayed({
                iconRight.animate()
                    .translationX(dpToPx(18f))
                    .setDuration(ShizukuSettings.scaledAnimationDuration(350))
                    .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
                    .withEndAction {
                        iconRight.animate()
                            .translationX(0f)
                            .setDuration(ShizukuSettings.scaledAnimationDuration(250))
                            .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
                            .start()
                    }.start()
            }, 500)

            // Bounce the left-swipe icon left
            val iconLeft = hintBinding.hintIconLeft
            iconLeft.postDelayed({
                iconLeft.animate()
                    .translationX(-dpToPx(18f))
                    .setDuration(ShizukuSettings.scaledAnimationDuration(350))
                    .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
                    .withEndAction {
                        iconLeft.animate()
                            .translationX(0f)
                            .setDuration(ShizukuSettings.scaledAnimationDuration(250))
                            .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
                            .start()
                    }.start()
            }, 1000)
        }

        val dismiss = {
            hint.animate()
                .translationY(hint.height.toFloat())
                .alpha(0f)
                .setDuration(ShizukuSettings.scaledAnimationDuration(280))
                .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
                .withEndAction { (hint.parent as? ViewGroup)?.removeView(hint) }
                .start()
        }

        hint.setOnClickListener { dismiss() }
        hint.postDelayed(dismiss, 4000)
    }

    private fun dpToPx(dp: Float) = dp * resources.displayMetrics.density

    // ----- Lifecycle -----

    override fun onDestroy() {
        ShizukuStateMachine.removeListener(stateListener)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // Skip the very first resume (right after onCreate) — onCreate already called
        // viewModel.load() when Shizuku was running, so a second load here is redundant.
        // Subsequent resumes (returning from Settings, app-info, etc.) still refresh so a
        // permission change made outside the activity is reflected immediately.
        if (isFirstResume) {
            isFirstResume = false
        } else {
            viewModel.refresh()
        }
        // Re-read swipe settings: setupSwipe()'s callback closes over these fields, so a
        // change made in Settings while this activity was backgrounded takes effect here
        // without needing to rebuild the ItemTouchHelper.
        swipeRightAction = ShizukuSettings.getSwipeRightAction()
        swipeLeftAction = ShizukuSettings.getSwipeLeftAction()
        androidx.core.view.ViewCompat.requestApplyInsets(recyclerView)
    }
}

class AppListItemDecoration(context: Context) : af.shizuku.manager.widget.M3ECardItemDecoration(context) {
    override val cardMargin: Float = 28f * density
    private val dividerInset = 124f * density // 28dp cardMargin + 24dp inner padding + 48dp icon + 24dp gap

    override fun shouldDecorate(view: View): Boolean {
        // The toggle-all header is a standalone MaterialCardView with its own floating margins and background
        return view !is com.google.android.material.card.MaterialCardView
    }

    override fun shouldDrawDivider(parent: RecyclerView, index: Int, count: Int): Boolean {
        for (i in index + 1 until count) {
            val next = parent.getChildAt(i) ?: continue
            if (next.visibility != View.VISIBLE) continue
            return shouldDecorate(next)
        }
        return false
    }

    override fun getDividerInset(view: View): Float = dividerInset
    override fun getDividerEndInset(view: View): Float = cardMargin + (24f * density)
}
