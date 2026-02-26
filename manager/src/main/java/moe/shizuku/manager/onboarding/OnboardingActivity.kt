package moe.shizuku.manager.onboarding

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import moe.shizuku.manager.Helps
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbPairingTutorialActivity
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.home.AdbDialogFragment
import moe.shizuku.manager.home.AdbPairDialogFragment
import moe.shizuku.manager.home.WadbEnableUsbDebuggingDialogFragment
import moe.shizuku.manager.home.WadbNotEnabledDialogFragment
import moe.shizuku.manager.home.StartWirelessAdbViewHolder
import moe.shizuku.manager.home.showAccessibilityDialog
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.starter.StarterActivity
import moe.shizuku.manager.utils.CustomTabsHelper
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.ShizukuStateMachine
import rikka.core.util.ClipboardUtils

class OnboardingActivity : AppActivity() {

    private lateinit var pager: ViewPager2
    private lateinit var dotsContainer: LinearLayout
    private lateinit var btnNext: MaterialButton
    private lateinit var btnSkip: MaterialButton

    private val pageCount = 4
    private val dots = mutableListOf<View>()
    private val onboardingAdapter = OnboardingPagerAdapter()

    // Setup page live refs
    private var setupStatusRunning: View? = null

    // Swipe page live refs
    private var swipeIconRight: ImageView? = null
    private var swipeIconLeft: ImageView? = null

    private val stateListener: (ShizukuStateMachine.State) -> Unit = { state ->
        runOnUiThread { updateSetupPageState(state) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        pager = findViewById(R.id.pager)
        dotsContainer = findViewById(R.id.dots_container)
        btnNext = findViewById(R.id.btn_next)
        btnSkip = findViewById(R.id.btn_skip)

        pager.adapter = onboardingAdapter
        pager.offscreenPageLimit = 3 // keep all pages in memory

        setupDots()

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateDots(position)
                updateButtons(position)
                if (position == 2) {
                    pager.postDelayed({ animateSwipeIcons() }, 300)
                }
            }
        })

        btnNext.setOnClickListener {
            val current = pager.currentItem
            if (current < pageCount - 1) {
                pager.currentItem = current + 1
            } else {
                completeOnboarding()
            }
        }

        btnSkip.setOnClickListener { completeOnboarding() }

        updateDots(0)
        updateButtons(0)

        ShizukuStateMachine.addListener(stateListener)
    }

    override fun onDestroy() {
        ShizukuStateMachine.removeListener(stateListener)
        super.onDestroy()
    }

    private fun updateSetupPageState(state: ShizukuStateMachine.State) {
        val isRunning = state == ShizukuStateMachine.State.RUNNING
        setupStatusRunning?.visibility = if (isRunning) View.VISIBLE else View.GONE
        if (isRunning && pager.currentItem == 1 && !isFinishing) {
            pager.postDelayed({
                if (!isFinishing && pager.currentItem == 1) pager.currentItem = 2
            }, 1800)
        }
    }

    private fun setupDots() {
        val sizePx = dpToPx(8)
        val marginPx = dpToPx(5)
        repeat(pageCount) {
            val dot = View(this).apply {
                val lp = LinearLayout.LayoutParams(sizePx, sizePx)
                lp.setMargins(marginPx, 0, marginPx, 0)
                layoutParams = lp
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            }
            dots.add(dot)
            dotsContainer.addView(dot)
        }
        updateDots(0)
    }

    private fun updateDots(selected: Int) {
        val active = resolveThemeColor(android.R.attr.colorPrimary)
        val inactive = resolveThemeColor(com.google.android.material.R.attr.colorSurfaceVariant)
        dots.forEachIndexed { i, dot ->
            (dot.background as GradientDrawable).setColor(if (i == selected) active else inactive)
            val sizePx = if (i == selected) dpToPx(10) else dpToPx(8)
            dot.layoutParams = (dot.layoutParams as LinearLayout.LayoutParams).also {
                it.width = sizePx
                it.height = sizePx
            }
        }
    }

    private fun updateButtons(position: Int) {
        btnSkip.visibility = if (position < pageCount - 1) View.VISIBLE else View.INVISIBLE
        btnNext.text = if (position < pageCount - 1)
            getString(R.string.onboarding_next)
        else
            getString(R.string.onboarding_get_started)
    }

    private fun animateSwipeIcons() {
        val iconRight = swipeIconRight ?: return
        val iconLeft = swipeIconLeft ?: return
        val shift = dpToPx(22).toFloat()

        iconRight.translationX = 0f
        iconRight.animate()
            .translationX(shift)
            .setDuration(350)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                iconRight.animate()
                    .translationX(0f)
                    .setDuration(250)
                    .setInterpolator(AccelerateInterpolator())
                    .start()
            }.start()

        iconLeft.translationX = 0f
        iconLeft.postDelayed({
            iconLeft.animate()
                .translationX(-shift)
                .setDuration(350)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    iconLeft.animate()
                        .translationX(0f)
                        .setDuration(250)
                        .setInterpolator(AccelerateInterpolator())
                        .start()
                }.start()
        }, 400)
    }

    private fun completeOnboarding() {
        ShizukuSettings.setOnboardingSeen()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun resolveThemeColor(attr: Int): Int {
        val tv = TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    // ---- Adapter ----

    inner class OnboardingPagerAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount() = pageCount
        override fun getItemViewType(position: Int) = position

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val view = when (viewType) {
                0 -> inflater.inflate(R.layout.page_onboarding_welcome, parent, false)
                1 -> inflater.inflate(R.layout.page_onboarding_setup, parent, false)
                2 -> inflater.inflate(R.layout.page_onboarding_swipe, parent, false)
                else -> inflater.inflate(R.layout.page_onboarding_longpress, parent, false)
            }
            return object : RecyclerView.ViewHolder(view) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (position) {
                1 -> bindSetupPage(holder.itemView)
                2 -> {
                    swipeIconRight = holder.itemView.findViewById(R.id.hint_icon_right)
                    swipeIconLeft = holder.itemView.findViewById(R.id.hint_icon_left)
                }
                3 -> bindLongPressPage(holder.itemView)
            }
        }

        private fun bindSetupPage(view: View) {
            setupStatusRunning = view.findViewById(R.id.status_running)

            // Show/hide method cards based on device
            val showWadb = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    || EnvironmentUtils.isTelevision()
                    || EnvironmentUtils.getAdbTcpPort() > 0
            val showRoot = EnvironmentUtils.isRooted()

            val cardWadb = view.findViewById<View>(R.id.card_wadb)
            val cardRoot = view.findViewById<View>(R.id.card_root)

            cardWadb.visibility = if (showWadb) View.VISIBLE else View.GONE
            cardRoot.visibility = if (showRoot) View.VISIBLE else View.GONE

            // Wireless ADB buttons
            if (showWadb) {
                val btnGuide = view.findViewById<MaterialButton>(R.id.btn_wadb_guide)
                val btnPair = view.findViewById<MaterialButton>(R.id.btn_wadb_pair)
                val btnStart = view.findViewById<MaterialButton>(R.id.btn_wadb_start)

                if (EnvironmentUtils.isTlsSupported()) {
                    btnPair.visibility = View.VISIBLE
                    btnPair.setOnClickListener { onPairClicked() }
                }

                btnGuide.setOnClickListener {
                    CustomTabsHelper.launchUrlOrCopy(this@OnboardingActivity, Helps.ADB_ANDROID11.get())
                }
                btnStart.setOnClickListener {
                    StartWirelessAdbViewHolder.start(this@OnboardingActivity, lifecycleScope)
                }
            }

            // Root button
            if (showRoot) {
                view.findViewById<MaterialButton>(R.id.btn_root_start).setOnClickListener {
                    startActivity(Intent(this@OnboardingActivity, StarterActivity::class.java).apply {
                        putExtra(StarterActivity.EXTRA_IS_ROOT, true)
                    })
                }
            }

            // PC/ADB button
            view.findViewById<MaterialButton>(R.id.btn_adb_command).setOnClickListener {
                showAdbCommandDialog()
            }

            // Skip
            view.findViewById<MaterialButton>(R.id.btn_setup_later).setOnClickListener {
                pager.currentItem = 2
            }

            // Apply current state immediately
            updateSetupPageState(ShizukuStateMachine.get())
        }

        private fun bindLongPressPage(view: View) {
            view.findViewById<MaterialSwitch>(R.id.switch_open_app).apply {
                isChecked = ShizukuSettings.getLongPressOpenApp()
                setOnCheckedChangeListener { _, checked ->
                    ShizukuSettings.getPreferences()
                        ?.edit()?.putBoolean(ShizukuSettings.Keys.KEY_LP_OPEN_APP, checked)?.apply()
                }
            }
            view.findViewById<MaterialSwitch>(R.id.switch_app_info).apply {
                isChecked = ShizukuSettings.getLongPressAppInfo()
                setOnCheckedChangeListener { _, checked ->
                    ShizukuSettings.getPreferences()
                        ?.edit()?.putBoolean(ShizukuSettings.Keys.KEY_LP_APP_INFO, checked)?.apply()
                }
            }
            view.findViewById<MaterialSwitch>(R.id.switch_toggle_permission).apply {
                isChecked = ShizukuSettings.getLongPressTogglePermission()
                setOnCheckedChangeListener { _, checked ->
                    ShizukuSettings.getPreferences()
                        ?.edit()?.putBoolean(ShizukuSettings.Keys.KEY_LP_TOGGLE_PERMISSION, checked)?.apply()
                }
            }
            view.findViewById<MaterialSwitch>(R.id.switch_hide_from_list).apply {
                isChecked = ShizukuSettings.getLongPressHideFromList()
                setOnCheckedChangeListener { _, checked ->
                    ShizukuSettings.getPreferences()
                        ?.edit()?.putBoolean(ShizukuSettings.Keys.KEY_LP_HIDE_FROM_LIST, checked)?.apply()
                }
            }
            view.findViewById<MaterialSwitch>(R.id.switch_dhizuku).apply {
                isChecked = ShizukuSettings.isDhizukuModeEnabled()
                setOnCheckedChangeListener { _, checked ->
                    ShizukuSettings.setDhizukuModeEnabled(checked)
                }
            }
            view.findViewById<MaterialSwitch>(R.id.switch_enhanced_api).apply {
                isChecked = ShizukuSettings.isCustomApiEnabled()
                setOnCheckedChangeListener { _, checked ->
                    ShizukuSettings.setCustomApiEnabled(checked)
                }
            }
        }
    }

    private fun onPairClicked() {
        if (EnvironmentUtils.isTelevision()) {
            showAccessibilityDialog()
        } else if (ShizukuSettings.getLegacyPairing()) {
            AdbPairDialogFragment().show(supportFragmentManager, null)
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
                    android.text.Html.FROM_HTML_MODE_LEGACY
                )
            )
            .setPositiveButton(R.string.home_adb_dialog_view_command_copy_button) { _, _ ->
                if (ClipboardUtils.put(this, Starter.adbCommand)) {
                    Toast.makeText(this, R.string.toast_copied_to_clipboard, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.home_adb_dialog_view_command_button_send) { _, _ ->
                var intent = Intent(Intent.ACTION_SEND)
                intent.type = "text/plain"
                intent.putExtra(Intent.EXTRA_TEXT, Starter.adbCommand)
                intent = Intent.createChooser(intent, getString(R.string.home_adb_dialog_view_command_button_send))
                startActivity(intent)
            }
            .show()
    }
}
