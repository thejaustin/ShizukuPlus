package moe.shizuku.manager.onboarding

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.app.AppActivity

class OnboardingActivity : AppActivity() {

    private lateinit var pager: ViewPager2
    private lateinit var dotsContainer: LinearLayout
    private lateinit var btnNext: MaterialButton
    private lateinit var btnSkip: MaterialButton

    private val pageCount = 3
    private val dots = mutableListOf<View>()
    private val onboardingAdapter = OnboardingPagerAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        pager = findViewById(R.id.pager)
        dotsContainer = findViewById(R.id.dots_container)
        btnNext = findViewById(R.id.btn_next)
        btnSkip = findViewById(R.id.btn_skip)

        pager.adapter = onboardingAdapter

        setupDots()

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateDots(position)
                updateButtons(position)
                if (position == 1) {
                    // Brief delay so the page is fully settled before animating
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
        val active = resolveThemeColor(com.google.android.material.R.attr.colorPrimary)
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
        val iconRight = onboardingAdapter.swipeIconRight ?: return
        val iconLeft = onboardingAdapter.swipeIconLeft ?: return
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

    inner class OnboardingPagerAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        var swipeIconRight: ImageView? = null
        var swipeIconLeft: ImageView? = null

        override fun getItemCount() = pageCount
        override fun getItemViewType(position: Int) = position

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val view = when (viewType) {
                0 -> inflater.inflate(R.layout.page_onboarding_welcome, parent, false)
                1 -> inflater.inflate(R.layout.page_onboarding_swipe, parent, false)
                else -> inflater.inflate(R.layout.page_onboarding_longpress, parent, false)
            }
            return object : RecyclerView.ViewHolder(view) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (position) {
                1 -> {
                    swipeIconRight = holder.itemView.findViewById(R.id.hint_icon_right)
                    swipeIconLeft = holder.itemView.findViewById(R.id.hint_icon_left)
                }
                2 -> bindLongPressPage(holder.itemView)
            }
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
        }
    }
}
