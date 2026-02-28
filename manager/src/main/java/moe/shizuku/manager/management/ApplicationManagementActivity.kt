package moe.shizuku.manager.management

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
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.view.animation.DecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.doOnLayout
import androidx.core.widget.doOnTextChanged
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.AdapterDataObserver
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.app.AppBarActivity
import moe.shizuku.manager.authorization.AuthorizationManager
import moe.shizuku.manager.databinding.AppsActivityBinding
import moe.shizuku.manager.utils.ActivityLogManager
import moe.shizuku.manager.utils.ShizukuStateMachine
import rikka.lifecycle.Status
import rikka.recyclerview.addEdgeSpacing
import rikka.recyclerview.fixEdgeEffect
import java.util.Objects

class ApplicationManagementActivity : AppBarActivity(), AppViewHolder.Callbacks {

    private val viewModel: AppsViewModel by viewModels()
    private val adapter = AppsAdapter()
    private lateinit var recyclerView: RecyclerView
    private var firstLoad = true

    private val stateListener: (ShizukuStateMachine.State) -> Unit = {
        if (ShizukuStateMachine.isDead() && !isFinishing) finish()
    }

    override fun getLayoutId() = R.layout.apps_appbar_activity

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (adapter.isSelectionMode) {
            adapter.isSelectionMode = false
            invalidateOptionsMenu()
            supportActionBar?.title = getString(R.string.home_app_management_title)
            return
        }
        super.onBackPressed()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!ShizukuStateMachine.isRunning()) {
            finish()
            return
        }

        val binding = AppsActivityBinding.inflate(layoutInflater, rootView, true)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Search bar
        findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.search_edit_text)
            .doOnTextChanged { text, _, _, _ ->
                viewModel.setSearch(text?.toString() ?: "")
            }

        // Filter chips
        findViewById<Chip>(R.id.chip_all).setOnCheckedChangeListener { _, checked ->
            if (checked) viewModel.setFilter(FilterState.ALL)
        }
        findViewById<Chip>(R.id.chip_granted).setOnCheckedChangeListener { _, checked ->
            if (checked) viewModel.setFilter(FilterState.GRANTED)
        }
        findViewById<Chip>(R.id.chip_denied).setOnCheckedChangeListener { _, checked ->
            if (checked) viewModel.setFilter(FilterState.DENIED)
        }

        viewModel.packages.observe(this) {
            when (it.status) {
                Status.SUCCESS -> {
                    adapter.updateData(it.data as List<PackageInfo>)
                    if (firstLoad && !it.data.isNullOrEmpty()) {
                        firstLoad = false
                        recyclerView.scheduleLayoutAnimation()
                        maybeShowSwipeHint()
                    }
                }
                Status.ERROR -> {
                    finish()
                    Toast.makeText(this, Objects.toString(it.error, "unknown"), Toast.LENGTH_SHORT).show()
                    it.error?.printStackTrace()
                }
                Status.LOADING -> {}
            }
        }
        viewModel.load()

        recyclerView = binding.list
        recyclerView.adapter = adapter
        recyclerView.layoutAnimation = AnimationUtils.loadLayoutAnimation(
            this, R.anim.layout_animation_slide_bottom
        )
        recyclerView.fixEdgeEffect()
        recyclerView.addEdgeSpacing(top = 8f, bottom = 8f, unit = TypedValue.COMPLEX_UNIT_DIP)

        adapter.registerAdapterDataObserver(object : AdapterDataObserver() {
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
                viewModel.load(true)
            }

            override fun onChanged() {
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
            menu.add(0, 13, 0, R.string.app_management_bulk_hide).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            return true
        }
        menuInflater.inflate(R.menu.apps_management_menu, menu)
        val sortId = when (viewModel.sortOrder) {
            SortOrder.NAME_ASC -> R.id.sort_name
            SortOrder.LAST_INSTALLED -> R.id.sort_last_installed
            SortOrder.LAST_UPDATED -> R.id.sort_last_updated
        }
        menu.findItem(sortId)?.isChecked = true
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
                android.R.id.home -> {
                    adapter.isSelectionMode = false
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
            R.id.action_hidden_apps -> {
                startActivity(Intent(this, HiddenAppsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ----- Hide callback -----

    override fun onHideApp(packageName: String) {
        viewModel.hidePackage(packageName)
        Snackbar.make(rootView, R.string.app_management_hidden, Snackbar.LENGTH_LONG)
            .setAction(R.string.app_management_undo) {
                viewModel.unhidePackage(packageName)
                viewModel.load()
            }
            .show()
    }

    // ----- Swipe gestures -----

    private fun setupSwipe(rv: RecyclerView) {
        val swipeRightAction = ShizukuSettings.getSwipeRightAction()
        val swipeLeftAction = ShizukuSettings.getSwipeLeftAction()

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
                handleSwipeAction(item, action)
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

    private fun handleSwipeAction(item: PackageInfo, action: String) {
        val opts = ActivityOptions.makeCustomAnimation(
            this, android.R.anim.fade_in, android.R.anim.fade_out
        ).toBundle()
        
        val appLabel = item.applicationInfo?.loadLabel(packageManager)?.toString() ?: item.packageName
        ActivityLogManager.log(appLabel, item.packageName, "Swipe: $action")
        
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
                try {
                    if (AuthorizationManager.granted(item.packageName, uid)) {
                        AuthorizationManager.revoke(item.packageName, uid)
                    } else {
                        AuthorizationManager.grant(item.packageName, uid)
                    }
                    adapter.notifyItemChanged(0) // update summary
                } catch (e: SecurityException) {
                    Toast.makeText(this, R.string.app_management_dialog_adb_is_limited_title, Toast.LENGTH_SHORT).show()
                }
            }
            "hide_from_list" -> onHideApp(item.packageName)
        }
    }

    private fun drawSwipeBackground(c: Canvas, v: android.view.View, dX: Float, action: String) {
        val (color, iconRes) = when (action) {
            "open_app" -> Color.parseColor("#4CAF50") to R.drawable.ic_outline_play_arrow_24
            "app_info" -> Color.parseColor("#1976D2") to R.drawable.ic_outline_info_24
            "toggle_permission" -> Color.parseColor("#FF9800") to R.drawable.ic_server_ok_24dp
            "hide_from_list" -> Color.parseColor("#E53935") to R.drawable.ic_close_24
            else -> Color.GRAY to R.drawable.ic_outline_info_24
        }
        
        val bg = ColorDrawable(color)
        val icon = AppCompatResources.getDrawable(this, iconRes)!!.mutate().also { it.setTint(Color.WHITE) }
        val margin = v.height / 4
        val top = v.top + (v.height - icon.intrinsicHeight) / 2
        
        if (dX > 0) {
            bg.setBounds(v.left, v.top, v.left + dX.toInt(), v.bottom)
            bg.draw(c)
            icon.setBounds(v.left + margin, top, v.left + margin + icon.intrinsicWidth, top + icon.intrinsicHeight)
            icon.draw(c)
        } else {
            bg.setBounds(v.right + dX.toInt(), v.top, v.right, v.bottom)
            bg.draw(c)
            icon.setBounds(v.right - margin - icon.intrinsicWidth, top, v.right - margin, top + icon.intrinsicHeight)
            icon.draw(c)
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
        val hint = layoutInflater.inflate(R.layout.swipe_hint_overlay, rootView as ViewGroup, false)
        (rootView as ViewGroup).addView(hint)

        hint.doOnLayout { v ->
            // Start below screen, slide up
            v.translationY = v.height.toFloat()
            v.animate()
                .translationY(0f)
                .setDuration(350)
                .setInterpolator(DecelerateInterpolator())
                .start()

            // Bounce the right-swipe icon right to hint the gesture
            val iconRight = v.findViewById<android.view.View>(R.id.hint_icon_right)
            iconRight.postDelayed({
                iconRight.animate()
                    .translationX(dpToPx(18f))
                    .setDuration(350)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction {
                        iconRight.animate()
                            .translationX(0f)
                            .setDuration(250)
                            .setInterpolator(AccelerateInterpolator())
                            .start()
                    }.start()
            }, 500)

            // Bounce the left-swipe icon left
            val iconLeft = v.findViewById<android.view.View>(R.id.hint_icon_left)
            iconLeft.postDelayed({
                iconLeft.animate()
                    .translationX(-dpToPx(18f))
                    .setDuration(350)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction {
                        iconLeft.animate()
                            .translationX(0f)
                            .setDuration(250)
                            .setInterpolator(AccelerateInterpolator())
                            .start()
                    }.start()
            }, 1000)
        }

        val dismiss = {
            hint.animate()
                .translationY(hint.height.toFloat())
                .alpha(0f)
                .setDuration(280)
                .setInterpolator(AccelerateInterpolator())
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
        viewModel.refresh()
    }
}
