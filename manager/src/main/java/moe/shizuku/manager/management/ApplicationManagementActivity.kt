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
import moe.shizuku.manager.app.AppBarActivity
import moe.shizuku.manager.databinding.AppsActivityBinding
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
        })

        setupSwipe(recyclerView)
        ShizukuStateMachine.addListener(stateListener)
    }

    // ----- Options menu: sort -----

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.apps_management_menu, menu)
        val sortId = when (viewModel.sortOrder) {
            SortOrder.NAME_ASC -> R.id.sort_name
            SortOrder.LAST_INSTALLED -> R.id.sort_last_installed
            SortOrder.LAST_UPDATED -> R.id.sort_last_updated
        }
        menu.findItem(sortId)?.isChecked = true
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        R.id.sort_name -> { item.isChecked = true; viewModel.setSortOrder(SortOrder.NAME_ASC); true }
        R.id.sort_last_installed -> { item.isChecked = true; viewModel.setSortOrder(SortOrder.LAST_INSTALLED); true }
        R.id.sort_last_updated -> { item.isChecked = true; viewModel.setSortOrder(SortOrder.LAST_UPDATED); true }
        else -> super.onOptionsItemSelected(item)
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
        val openAppBg = ColorDrawable(Color.parseColor("#4CAF50"))
        val appInfoBg = ColorDrawable(Color.parseColor("#1976D2"))

        val openIcon = AppCompatResources.getDrawable(this, R.drawable.ic_outline_play_arrow_24)!!
            .mutate().also { it.setTint(Color.WHITE) }
        val infoIcon = AppCompatResources.getDrawable(this, R.drawable.ic_outline_info_24)!!
            .mutate().also { it.setTint(Color.WHITE) }

        val cb = object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun getSwipeDirs(r: RecyclerView, vh: RecyclerView.ViewHolder): Int =
                if (vh is AppViewHolder) super.getSwipeDirs(r, vh) else 0

            override fun onMove(r: RecyclerView, v: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val pos = vh.adapterPosition
                @Suppress("UNCHECKED_CAST")
                val items = adapter.getItems() as ArrayList<*>
                val item = items.getOrNull(pos) as? PackageInfo
                adapter.notifyItemChanged(pos) // snap back
                item ?: return
                val opts = ActivityOptions.makeCustomAnimation(
                    this@ApplicationManagementActivity,
                    android.R.anim.fade_in, android.R.anim.fade_out
                ).toBundle()
                when (direction) {
                    ItemTouchHelper.RIGHT -> {
                        val intent = packageManager.getLaunchIntentForPackage(item.packageName)
                        if (intent != null) startActivity(intent, opts)
                        else Toast.makeText(this@ApplicationManagementActivity,
                            R.string.app_management_no_launcher, Toast.LENGTH_SHORT).show()
                    }
                    ItemTouchHelper.LEFT -> startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", item.packageName, null)), opts
                    )
                }
            }

            override fun onChildDraw(
                c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder,
                dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
            ) {
                val v = vh.itemView
                val margin = v.height / 4
                when {
                    dX > 0 -> {
                        openAppBg.setBounds(v.left, v.top, v.left + dX.toInt(), v.bottom)
                        openAppBg.draw(c)
                        val top = v.top + (v.height - openIcon.intrinsicHeight) / 2
                        openIcon.setBounds(v.left + margin, top,
                            v.left + margin + openIcon.intrinsicWidth, top + openIcon.intrinsicHeight)
                        openIcon.draw(c)
                    }
                    dX < 0 -> {
                        appInfoBg.setBounds(v.right + dX.toInt(), v.top, v.right, v.bottom)
                        appInfoBg.draw(c)
                        val top = v.top + (v.height - infoIcon.intrinsicHeight) / 2
                        infoIcon.setBounds(v.right - margin - infoIcon.intrinsicWidth, top,
                            v.right - margin, top + infoIcon.intrinsicHeight)
                        infoIcon.draw(c)
                    }
                }
                super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive)
            }
        }
        ItemTouchHelper(cb).attachToRecyclerView(rv)
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
