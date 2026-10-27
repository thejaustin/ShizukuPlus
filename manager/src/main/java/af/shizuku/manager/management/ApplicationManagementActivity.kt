package af.shizuku.manager.management

import af.shizuku.core.ui.AppBarActivity
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.authorization.AuthorizationManager
import af.shizuku.manager.database.ActivityLogManager
import af.shizuku.manager.databinding.AppsActivityBinding
import af.shizuku.manager.databinding.SwipeHintOverlayBinding
import af.shizuku.manager.management.AppViewHolder.Callbacks
import af.shizuku.manager.utils.HapticUtils
import af.shizuku.manager.utils.ShizukuStateMachine
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextPaint
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.ColorUtils
import androidx.core.view.doOnLayout
import androidx.core.widget.doOnTextChanged
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.AdapterDataObserver
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.lifecycle.Status
import rikka.recyclerview.addEdgeSpacing
import rikka.recyclerview.fixEdgeEffect
import timber.log.Timber
import java.util.Objects

open class ApplicationManagementActivity :
    AppBarActivity(),
    AppViewHolder.Callbacks {
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

        // No back arrow when Apps is a floating-nav tab (M3 top-level destination).
        supportActionBar?.setDisplayHomeAsUpEnabled(
            !af.shizuku.manager.widget.FloatingNavBar
                .isTopLevel(this),
        )

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
        backCallback =
            object : androidx.activity.OnBackPressedCallback(false) {
                // Predictive back preview: the list eases back like Home's edit mode does.
                override fun handleOnBackProgressed(backEvent: androidx.activity.BackEventCompat) {
                    if (!ShizukuSettings.isExpressiveAnimationsEnabled()) return
                    val p = backEvent.progress
                    val scale = 1f - 0.08f * p * p
                    recyclerView.scaleX = scale
                    recyclerView.scaleY = scale
                    recyclerView.alpha = 1f - 0.15f * p
                }

                override fun handleOnBackCancelled() = resetListPreview()

                override fun handleOnBackPressed() {
                    resetListPreview()
                    if (adapter.isSelectionMode) {
                        adapter.isSelectionMode = false
                        invalidateOptionsMenu()
                        val isOneUi = ShizukuSettings.isOneUiThemeEnabled()
                        val isOneHanded = ShizukuSettings.isOneHandedModeEnabled()
                        supportActionBar?.title = if (isOneUi || isOneHanded) "" else getString(R.string.home_app_management_title)
                        this.isEnabled = false
                    }
                }
            }
        backCallback?.let {
            onBackPressedDispatcher.addCallback(this, it)
        }

        // One UI & One-Handed mode: spacious header with live authorized app count
        val isOneUi = ShizukuSettings.isOneUiThemeEnabled()
        val isOneHanded = ShizukuSettings.isOneHandedModeEnabled()
        val oneUiHeader = rootView.findViewById<View>(R.id.oneui_header_container)
        val oneUiSubtitle = rootView.findViewById<TextView>(R.id.oneui_header_subtitle)

        if (isOneUi || isOneHanded) {
            oneUiHeader?.visibility = View.VISIBLE
            val screenHeight = resources.displayMetrics.heightPixels
            val minHeaderHeight = if (isOneHanded) (screenHeight * 0.16f).toInt() else 0
            oneUiHeader?.minimumHeight = minHeaderHeight
            supportActionBar?.title = ""
            toolbar?.title = ""

            viewModel.grantedCount.observe(this) { res ->
                // Unknown until the first count (null data while loading): keep the subtitle as is
                // rather than flash "0 authorized apps".
                val count = res?.data ?: return@observe
                oneUiSubtitle?.text = resources.getQuantityString(R.plurals.home_app_management_authorized_apps_count, count, count)
            }
        }

        // Search bar: M3 Expressive shape morphing (28dp capsule pill -> 16dp active squircle)
        val searchLayout = rootView.findViewById<TextInputLayout>(R.id.search_layout)
        val searchEditText = rootView.findViewById<EditText>(R.id.search_edit_text)
        val pillRadius = 28f * resources.displayMetrics.density
        val squircleRadius = 16f * resources.displayMetrics.density
        var cornerAnimator: android.animation.ValueAnimator? = null

        fun animateSearchCorners(toRadius: Float) {
            cornerAnimator?.cancel()
            val currentRadius = searchLayout.boxCornerRadiusTopStart
            if (kotlin.math.abs(currentRadius - toRadius) < 0.5f) return
            cornerAnimator =
                android.animation.ValueAnimator.ofFloat(currentRadius, toRadius).apply {
                    duration = 250L
                    interpolator =
                        androidx.interpolator.view.animation
                            .FastOutSlowInInterpolator()
                    addUpdateListener { animator ->
                        val r = animator.animatedValue as Float
                        searchLayout.setBoxCornerRadii(r, r, r, r)
                    }
                    start()
                }
        }

        searchEditText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                af.shizuku.manager.utils.HapticUtils
                    .tick(searchEditText)
                animateSearchCorners(squircleRadius)
            } else if (searchEditText.text.isNullOrEmpty()) {
                animateSearchCorners(pillRadius)
            }
        }

        searchEditText.doOnTextChanged { text, _, _, _ ->
            val query = text?.toString() ?: ""
            viewModel.setSearch(query)
            if (query.isNotEmpty() && !searchEditText.hasFocus()) {
                animateSearchCorners(squircleRadius)
            } else if (query.isEmpty() && !searchEditText.hasFocus()) {
                animateSearchCorners(pillRadius)
            }
        }

        val chipGroup = rootView.findViewById<ChipGroup>(R.id.filter_chip_group)
        chipGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            af.shizuku.manager.utils.HapticUtils
                .segmentTick(group)
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
            val bars =
                insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type
                        .systemBars() or
                        androidx.core.view.WindowInsetsCompat.Type
                            .displayCutout(),
                )
            val extraBottomPadding =
                if (af.shizuku.manager.widget.FloatingNavBar
                        .isTopLevel(this)
                ) {
                    (160 * resources.displayMetrics.density).toInt()
                } else {
                    (24 * resources.displayMetrics.density).toInt()
                }
            v.setPadding(bars.left, 0, bars.right, bars.bottom + extraBottomPadding)
            insets
        }

        // M3E 2026 Layout Animation for fluid motion on entry
        recyclerView.layoutAnimation =
            AnimationUtils.loadLayoutAnimation(
                this,
                R.anim.layout_animation_slide_bottom,
            )

        recyclerView.setBackgroundColor(Color.TRANSPARENT)
        recyclerView.addItemDecoration(AppListItemDecoration(this))
        recyclerView.fixEdgeEffect()
        recyclerView.addEdgeSpacing(top = 8f, bottom = 8f, unit = TypedValue.COMPLEX_UNIT_DIP)

        adapter.registerAdapterDataObserver(
            object : AdapterDataObserver() {
                override fun onItemRangeChanged(
                    positionStart: Int,
                    itemCount: Int,
                    payload: Any?,
                ) {
                    viewModel.load(true)
                }

                override fun onChanged() {
                    backCallback?.isEnabled = adapter.isSelectionMode
                    if (adapter.isSelectionMode) {
                        supportActionBar?.title = getString(R.string.app_management_selected, adapter.selectedPackages.size)
                        invalidateOptionsMenu()
                    }
                }
            },
        )

        setupSwipe(recyclerView)
        ShizukuStateMachine.addListener(stateListener)
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        val isOneUi = ShizukuSettings.isOneUiThemeEnabled()
        val isOneHanded = ShizukuSettings.isOneHandedModeEnabled()
        if (isOneUi || isOneHanded) {
            supportActionBar?.title = ""
            toolbar?.title = ""
            setupOneUiHeaderCollapse()
        }
    }

    private fun setupOneUiHeaderCollapse() {
        val appBar = toolbarContainer ?: return
        val oneUiTitle = rootView.findViewById<TextView>(R.id.oneui_header_title)
        val oneUiSubtitle = rootView.findViewById<TextView>(R.id.oneui_header_subtitle)

        appBar.addOnOffsetChangedListener { appBarLayout, verticalOffset ->
            if (adapter.isSelectionMode) return@addOnOffsetChangedListener
            val totalScrollRange = appBarLayout.totalScrollRange
            if (totalScrollRange == 0) return@addOnOffsetChangedListener
            val fraction = kotlin.math.abs(verticalOffset).toFloat() / totalScrollRange

            if (fraction >= 0.75f) {
                if (supportActionBar?.title.isNullOrEmpty()) {
                    supportActionBar?.title = getString(R.string.home_app_management_title)
                    toolbar?.title = getString(R.string.home_app_management_title)
                }
            } else {
                if (!supportActionBar?.title.isNullOrEmpty()) {
                    supportActionBar?.title = ""
                    toolbar?.title = ""
                }
            }

            val alpha = (1f - fraction * 1.8f).coerceIn(0f, 1f)
            oneUiTitle?.alpha = alpha
            oneUiSubtitle?.alpha = alpha
        }
    }

    // ----- Options menu: sort -----

    private fun resetListPreview() {
        recyclerView.scaleX = 1f
        recyclerView.scaleY = 1f
        recyclerView.alpha = 1f
    }

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
        val sortId =
            when (viewModel.sortOrder) {
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
                    viewModel.packages.value
                        ?.data
                        ?.forEach { adapter.selectedPackages.add(it.packageName) }
                    adapter.notifyItemRangeChanged(0, adapter.itemCount)
                }
                11 -> { // Grant all
                    adapter.selectedPackages.toList().forEach { pkg ->
                        val pi =
                            viewModel.packages.value
                                ?.data
                                ?.find { it.packageName == pkg }
                        pi?.applicationInfo?.uid?.let { uid -> AuthorizationManager.grant(pkg, uid) }
                    }
                    adapter.isSelectionMode = false
                    viewModel.load()
                }
                12 -> { // Revoke all
                    adapter.selectedPackages.toList().forEach { pkg ->
                        val pi =
                            viewModel.packages.value
                                ?.data
                                ?.find { it.packageName == pkg }
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
                    val isOneUi = ShizukuSettings.isOneUiThemeEnabled()
                    val isOneHanded = ShizukuSettings.isOneHandedModeEnabled()
                    supportActionBar?.title = if (isOneUi || isOneHanded) "" else getString(R.string.home_app_management_title)
                }
            }
            return true
        }
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.sort_name -> {
                item.isChecked = true
                viewModel.setSortOrder(SortOrder.NAME_ASC)
                true
            }
            R.id.sort_last_installed -> {
                item.isChecked = true
                viewModel.setSortOrder(SortOrder.LAST_INSTALLED)
                true
            }
            R.id.sort_last_updated -> {
                item.isChecked = true
                viewModel.setSortOrder(SortOrder.LAST_UPDATED)
                true
            }
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
            af.shizuku.manager.app.SnackbarHelper.show(
                context = this,
                view = rootView,
                msg = getString(R.string.app_management_undo),
                duration = Snackbar.LENGTH_LONG,
            )
            viewModel.load()
        } else {
            viewModel.hidePackage(packageName)
            af.shizuku.manager.app.SnackbarHelper.show(
                context = this,
                view = rootView,
                msg = getString(R.string.app_management_hidden),
                duration = Snackbar.LENGTH_LONG,
                actionText = getString(R.string.app_management_undo),
                action = {
                    viewModel.unhidePackage(packageName)
                    viewModel.load()
                },
            )
        }
    }

    // ----- Swipe gestures -----

    private val swipePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val swipeBadgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val swipeTextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    private val swipeRect = RectF()
    private val swipeBadgeRect = RectF()

    private data class SwipeActionConfig(
        val bgAttr: Int,
        val onAttr: Int,
        val iconRes: Int,
        val labelRes: Int,
    )

    private fun setupSwipe(rv: RecyclerView) {
        swipeRightAction = ShizukuSettings.getSwipeRightAction()
        swipeLeftAction = ShizukuSettings.getSwipeLeftAction()

        val cb =
            object : ItemTouchHelper.SimpleCallback(
                0,
                ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
            ) {
                private var thresholdActivated = false

                override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float = 0.38f

                override fun getSwipeEscapeVelocity(defaultValue: Float): Float = defaultValue * 1.4f

                override fun getSwipeVelocityThreshold(defaultValue: Float): Float = defaultValue * 0.8f

                override fun getSwipeDirs(
                    r: RecyclerView,
                    vh: RecyclerView.ViewHolder,
                ): Int {
                    if (vh !is AppViewHolder) return 0
                    var dirs = 0
                    if (swipeRightAction != "none") dirs = dirs or ItemTouchHelper.RIGHT
                    if (swipeLeftAction != "none") dirs = dirs or ItemTouchHelper.LEFT
                    return dirs
                }

                override fun onMove(
                    r: RecyclerView,
                    v: RecyclerView.ViewHolder,
                    t: RecyclerView.ViewHolder,
                ) = false

                override fun onSwiped(
                    vh: RecyclerView.ViewHolder,
                    direction: Int,
                ) {
                    thresholdActivated = false
                    HapticUtils.gestureEnd(vh.itemView)
                    val pos = vh.adapterPosition
                    val items = adapter.getItems<Any>()
                    val item = items.getOrNull(pos) as? PackageInfo
                    adapter.notifyItemChanged(pos) // snap back
                    item ?: return

                    val action = if (direction == ItemTouchHelper.RIGHT) swipeRightAction else swipeLeftAction
                    handleSwipeAction(item, action, vh.itemView)
                }

                override fun onChildDraw(
                    c: Canvas,
                    rv: RecyclerView,
                    vh: RecyclerView.ViewHolder,
                    dX: Float,
                    dY: Float,
                    actionState: Int,
                    isCurrentlyActive: Boolean,
                ) {
                    val v = vh.itemView
                    val action = if (dX > 0) swipeRightAction else swipeLeftAction
                    if (action != "none") {
                        val threshold = 0.38f * v.width
                        val absDx = kotlin.math.abs(dX)
                        val pastThreshold = absDx >= threshold

                        if (isCurrentlyActive) {
                            if (pastThreshold && !thresholdActivated) {
                                thresholdActivated = true
                                HapticUtils.gestureThreshold(v)
                            } else if (!pastThreshold && thresholdActivated) {
                                thresholdActivated = false
                                HapticUtils.tick(v)
                            }
                        } else {
                            thresholdActivated = false
                        }

                        drawSwipeBackground(c, v, dX, action, pastThreshold)
                    }
                    super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive)
                }
            }
        ItemTouchHelper(cb).attachToRecyclerView(rv)
    }

    // internal (not private): also invoked from AppViewHolder for the TalkBack-accessible
    // equivalent of the swipe gestures (#41) - swiping to trigger an action has no screen-reader
    // affordance, so AppViewHolder exposes the same actions via custom accessibility actions.
    internal fun handleSwipeAction(
        item: PackageInfo,
        action: String,
        itemView: View,
    ) {
        val opts =
            ActivityOptions
                .makeCustomAnimation(
                    this,
                    android.R.anim.fade_in,
                    android.R.anim.fade_out,
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
                if (intent != null) {
                    startActivity(intent, opts)
                } else {
                    af.shizuku.manager.app.SnackbarHelper
                        .show(this, findViewById(android.R.id.content), getString(R.string.app_management_no_launcher))
                }
            }
            "app_info" ->
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", item.packageName, null),
                    ),
                    opts,
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
                            val pos =
                                items.indexOfFirst {
                                    it is PackageInfo && it.packageName == item.packageName
                                }
                            if (pos >= 0) adapter.notifyItemChanged(pos, Any())
                            adapter.notifyItemChanged(0) // update toggle-all header
                        }
                    } catch (_: SecurityException) {
                        withContext(Dispatchers.Main) {
                            Toast
                                .makeText(
                                    this@ApplicationManagementActivity,
                                    R.string.app_management_dialog_adb_is_limited_title,
                                    Toast.LENGTH_SHORT,
                                ).show()
                        }
                    }
                }
            }
            "hide_from_list" -> onHideApp(item.packageName)
        }
    }

    private fun drawSwipeBackground(
        c: Canvas,
        v: android.view.View,
        dX: Float,
        action: String,
        isPastThreshold: Boolean = false,
    ) {
        val absDx = kotlin.math.abs(dX)
        if (absDx < 4f) return

        val config =
            when (action) {
                "open_app" ->
                    SwipeActionConfig(
                        com.google.android.material.R.attr.colorPrimaryContainer,
                        com.google.android.material.R.attr.colorOnPrimaryContainer,
                        R.drawable.ic_outline_play_arrow_24,
                        R.string.settings_action_open_app,
                    )
                "app_info" ->
                    SwipeActionConfig(
                        com.google.android.material.R.attr.colorSecondaryContainer,
                        com.google.android.material.R.attr.colorOnSecondaryContainer,
                        R.drawable.ic_outline_info_24,
                        R.string.settings_action_app_info,
                    )
                "toggle_permission" ->
                    SwipeActionConfig(
                        com.google.android.material.R.attr.colorTertiaryContainer,
                        com.google.android.material.R.attr.colorOnTertiaryContainer,
                        R.drawable.ic_shield_24,
                        R.string.settings_action_toggle_permission,
                    )
                "hide_from_list" ->
                    SwipeActionConfig(
                        com.google.android.material.R.attr.colorErrorContainer,
                        com.google.android.material.R.attr.colorOnErrorContainer,
                        R.drawable.ic_visibility_off_24,
                        R.string.settings_action_hide_from_list,
                    )
                else ->
                    SwipeActionConfig(
                        com.google.android.material.R.attr.colorSecondaryContainer,
                        com.google.android.material.R.attr.colorOnSecondaryContainer,
                        R.drawable.ic_outline_info_24,
                        R.string.settings_action_none,
                    )
            }

        val bgColor = MaterialColors.getColor(v, config.bgAttr)
        val onColor = MaterialColors.getColor(v, config.onAttr)
        val density = resources.displayMetrics.density
        val threshold = 0.38f * v.width
        val progress = (absDx / threshold).coerceIn(0f, 1f)

        // Rounded container background
        val cornerRadius = 16f * density
        swipePaint.color = bgColor
        if (dX > 0) {
            swipeRect.set(v.left.toFloat(), v.top.toFloat() + 2f * density, v.left + dX, v.bottom.toFloat() - 2f * density)
        } else {
            swipeRect.set(v.right + dX, v.top.toFloat() + 2f * density, v.right.toFloat(), v.bottom.toFloat() - 2f * density)
        }
        c.drawRoundRect(swipeRect, cornerRadius, cornerRadius, swipePaint)

        // Dynamic badge size with spring pop when threshold reached
        val scale = if (isPastThreshold) 1.15f else (0.75f + 0.25f * progress)
        val badgeSize = 44f * density * scale
        val iconSize = 24f * density * scale
        val centerY = v.top + v.height / 2f
        val margin = 20f * density

        val icon = AppCompatResources.getDrawable(this, config.iconRes)?.mutate()
        icon?.setTint(onColor)
        icon?.alpha = (255 * (0.35f + 0.65f * progress)).toInt()

        val label = getString(config.labelRes)
        swipeTextPaint.textSize = 13f * density * resources.configuration.fontScale
        val textBaseline = centerY - (swipeTextPaint.descent() + swipeTextPaint.ascent()) / 2f

        if (dX > 0) {
            val badgeCenterX =
                if (absDx < margin * 2 + badgeSize) {
                    v.left + absDx / 2f
                } else {
                    v.left + margin + badgeSize / 2f
                }
            swipeBadgeRect.set(
                badgeCenterX - badgeSize / 2f,
                centerY - badgeSize / 2f,
                badgeCenterX + badgeSize / 2f,
                centerY + badgeSize / 2f,
            )
            swipeBadgePaint.color = ColorUtils.setAlphaComponent(onColor, if (isPastThreshold) 50 else 25)
            c.drawRoundRect(swipeBadgeRect, badgeSize / 2f, badgeSize / 2f, swipeBadgePaint)

            icon?.setBounds(
                (badgeCenterX - iconSize / 2f).toInt(),
                (centerY - iconSize / 2f).toInt(),
                (badgeCenterX + iconSize / 2f).toInt(),
                (centerY + iconSize / 2f).toInt(),
            )
            icon?.draw(c)

            if (isPastThreshold) {
                swipeTextPaint.color = onColor
                val textX = badgeCenterX + badgeSize / 2f + 12f * density
                if (textX + swipeTextPaint.measureText(label) < v.left + dX - 8f * density) {
                    c.drawText(label, textX, textBaseline, swipeTextPaint)
                }
            }
        } else {
            val badgeCenterX =
                if (absDx < margin * 2 + badgeSize) {
                    v.right - absDx / 2f
                } else {
                    v.right - margin - badgeSize / 2f
                }
            swipeBadgeRect.set(
                badgeCenterX - badgeSize / 2f,
                centerY - badgeSize / 2f,
                badgeCenterX + badgeSize / 2f,
                centerY + badgeSize / 2f,
            )
            swipeBadgePaint.color = ColorUtils.setAlphaComponent(onColor, if (isPastThreshold) 50 else 25)
            c.drawRoundRect(swipeBadgeRect, badgeSize / 2f, badgeSize / 2f, swipeBadgePaint)

            icon?.setBounds(
                (badgeCenterX - iconSize / 2f).toInt(),
                (centerY - iconSize / 2f).toInt(),
                (badgeCenterX + iconSize / 2f).toInt(),
                (centerY + iconSize / 2f).toInt(),
            )
            icon?.draw(c)

            if (isPastThreshold) {
                swipeTextPaint.color = onColor
                val textWidth = swipeTextPaint.measureText(label)
                val textX = badgeCenterX - badgeSize / 2f - 12f * density - textWidth
                if (textX > v.right + dX + 8f * density) {
                    c.drawText(label, textX, textBaseline, swipeTextPaint)
                }
            }
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
            v
                .animate()
                .translationY(0f)
                .setDuration(ShizukuSettings.scaledAnimationDuration(350))
                .setInterpolator(af.shizuku.manager.utils.MotionUtils.STANDARD)
                .start()

            // Bounce the right-swipe icon right to hint the gesture
            val iconRight = hintBinding.hintIconRight
            iconRight.postDelayed({
                iconRight
                    .animate()
                    .translationX(dpToPx(18f))
                    .setDuration(ShizukuSettings.scaledAnimationDuration(350))
                    .setInterpolator(af.shizuku.manager.utils.MotionUtils.STANDARD)
                    .withEndAction {
                        iconRight
                            .animate()
                            .translationX(0f)
                            .setDuration(ShizukuSettings.scaledAnimationDuration(250))
                            .setInterpolator(af.shizuku.manager.utils.MotionUtils.STANDARD)
                            .start()
                    }.start()
            }, 500)

            // Bounce the left-swipe icon left
            val iconLeft = hintBinding.hintIconLeft
            iconLeft.postDelayed({
                iconLeft
                    .animate()
                    .translationX(-dpToPx(18f))
                    .setDuration(ShizukuSettings.scaledAnimationDuration(350))
                    .setInterpolator(af.shizuku.manager.utils.MotionUtils.STANDARD)
                    .withEndAction {
                        iconLeft
                            .animate()
                            .translationX(0f)
                            .setDuration(ShizukuSettings.scaledAnimationDuration(250))
                            .setInterpolator(af.shizuku.manager.utils.MotionUtils.STANDARD)
                            .start()
                    }.start()
            }, 1000)
        }

        val dismiss = {
            hint
                .animate()
                .translationY(hint.height.toFloat())
                .alpha(0f)
                .setDuration(ShizukuSettings.scaledAnimationDuration(300))
                .setInterpolator(af.shizuku.manager.utils.MotionUtils.STANDARD)
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
        androidx.core.view.ViewCompat
            .requestApplyInsets(recyclerView)
    }
}

class AppListItemDecoration(
    context: Context,
) : af.shizuku.manager.widget.M3ECardItemDecoration(context) {
    // 16dp matches @dimen/margin_horizontal (item paddingStart/End) so the card rect
    // aligns exactly with the visible content bounds.
    override val cardMargin: Float = 16f * density

    // 16dp (item paddingStart) + 48dp (icon) + 16dp (@dimen/m3e_spacing_medium, icon-to-text gap)
    // = 80dp from child.left — aligns the divider with the text start.
    private val dividerInset = 80f * density

    override fun shouldDecorate(view: View): Boolean {
        // The toggle-all header is a standalone MaterialCardView with its own floating margins and background
        return view !is com.google.android.material.card.MaterialCardView
    }

    override fun shouldDrawDivider(
        parent: RecyclerView,
        index: Int,
        count: Int,
    ): Boolean {
        for (i in index + 1 until count) {
            val next = parent.getChildAt(i) ?: continue
            if (next.visibility != View.VISIBLE) continue
            return shouldDecorate(next)
        }
        return false
    }

    override fun getDividerInset(view: View): Float = dividerInset

    // Mirror the card's right edge (16dp from child.right).
    override fun getDividerEndInset(view: View): Float = 16f * density
}
