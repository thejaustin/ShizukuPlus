package af.shizuku.manager.widget

import af.shizuku.manager.MainActivity
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.home.HomeActivity
import af.shizuku.manager.home.SystemHubActivity
import af.shizuku.manager.ktx.startWithSceneTransition
import af.shizuku.manager.management.ApplicationManagementActivity
import af.shizuku.manager.settings.NavCustomizerSheet
import af.shizuku.manager.settings.RootCompatibilityActivity
import af.shizuku.manager.settings.ServiceDoctorActivity
import af.shizuku.manager.settings.SettingsActivity
import af.shizuku.manager.shell.ShellTutorialActivity
import af.shizuku.manager.utils.HapticUtils
import af.shizuku.manager.utils.MotionUtils.applySpringTouch
import af.shizuku.manager.utils.ShizukuStateMachine
import android.app.Activity
import android.app.ActivityOptions
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import rikka.core.util.ResourceUtils
import timber.log.Timber
import kotlin.math.min

/**
 * Optional floating navigation bar (#498).
 *
 * Attached from an [Application.ActivityLifecycleCallbacks] to the content root of the top-level
 * screens, supporting 2 to 5 customizable, reorderable destinations (e.g. Home centered).
 */
object FloatingNavBar : Application.ActivityLifecycleCallbacks {
    const val KEY_ENABLED = "floating_nav_enabled"
    const val KEY_STYLE = "floating_nav_style"
    const val KEY_TABS = "floating_nav_tabs"
    const val KEY_TAB_ORDER = "floating_nav_tab_order"
    private const val DEFAULT_TAB_ORDER = "home,apps,hub,settings"
    private const val COMPACT_MAX_WIDTH_DP = 300

    data class TabSpec(
        val key: String,
        val id: Int,
        @StringRes val titleRes: Int,
        @StringRes val descRes: Int,
        @DrawableRes val iconRes: Int,
        val targetClass: Class<out Activity>,
    )

    val PRESET_CENTER_5 = listOf("apps", "hub", "home", "doctor", "settings")
    val PRESET_CENTER_3 = listOf("apps", "home", "settings")
    val PRESET_DEFAULT_4 = listOf("home", "apps", "hub", "settings")
    val PRESET_ALL_5 = listOf("home", "apps", "hub", "doctor", "settings")
    val PRESET_TERMINAL_5 = listOf("home", "apps", "hub", "terminal", "settings")

    val ALL_TABS: List<TabSpec> =
        listOf(
            TabSpec("home", R.id.nav_home, R.string.nav_home, R.string.nav_tab_home_desc, R.drawable.ic_nav_home_24, MainActivity::class.java),
            TabSpec("apps", R.id.nav_apps, R.string.nav_apps, R.string.nav_tab_apps_desc, R.drawable.ic_nav_apps_24, ApplicationManagementActivity::class.java),
            TabSpec("hub", R.id.nav_hub, R.string.nav_hub, R.string.nav_tab_hub_desc, R.drawable.ic_nav_hub_24, SystemHubActivity::class.java),
            TabSpec("doctor", R.id.nav_doctor, R.string.home_service_doctor_title, R.string.nav_tab_doctor_desc, R.drawable.ic_medical_services_24, ServiceDoctorActivity::class.java),
            TabSpec("terminal", R.id.nav_terminal, R.string.home_terminal_title, R.string.nav_tab_terminal_desc, R.drawable.ic_terminal_24, ShellTutorialActivity::class.java),
            TabSpec("settings", R.id.nav_settings, R.string.nav_settings, R.string.nav_tab_settings_desc, R.drawable.ic_settings_outline_24, SettingsActivity::class.java),
        )

    fun tabForKey(key: String): TabSpec? = ALL_TABS.firstOrNull { it.key == key }

    fun tabForId(id: Int): TabSpec? = ALL_TABS.firstOrNull { it.id == id }

    fun getActiveTabs(): List<TabSpec> {
        val prefs = ShizukuSettings.getPreferences()
        val rawOrder = runCatching { prefs.getString(KEY_TAB_ORDER, null) }.getOrNull()
        val keys =
            if (!rawOrder.isNullOrBlank()) {
                rawOrder.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            } else {
                val legacyTabs = runCatching { prefs.getStringSet(KEY_TABS, null) }.getOrNull()
                if (legacyTabs != null) {
                    val list = mutableListOf("home")
                    if ("apps" in legacyTabs) list.add("apps")
                    if ("hub" in legacyTabs) list.add("hub")
                    list.add("settings")
                    list
                } else {
                    DEFAULT_TAB_ORDER.split(",")
                }
            }
        val resolved = keys.mapNotNull { tabForKey(it) }.distinctBy { it.key }
        val clamped = resolved.take(5)
        return if (clamped.size >= 2) clamped else listOfNotNull(tabForKey("home"), tabForKey("apps"), tabForKey("settings"))
    }

    fun setActiveTabOrder(keys: List<String>) {
        val valid = keys.mapNotNull { tabForKey(it) }.distinctBy { it.key }.take(5)
        if (valid.size < 2) return
        val raw = valid.joinToString(",") { it.key }
        ShizukuSettings
            .getPreferences()
            .edit()
            .putString(KEY_TAB_ORDER, raw)
            .putStringSet(KEY_TABS, valid.map { it.key }.toSet())
            .apply()
    }

    fun getActiveTabOrderNames(context: Context): List<String> =
        getActiveTabs().map { context.getString(it.titleRes) }

    fun currentOrder(): List<Int> = getActiveTabs().map { it.id }

    private fun style(): String =
        runCatching { ShizukuSettings.getPreferences().getString(KEY_STYLE, "docked") }.getOrNull() ?: "docked"

    private fun isTabShown(itemId: Int): Boolean = itemId in currentOrder()

    /** Which tab the user came from, so the new screen can slide the indicator to its own tab. */
    private const val EXTRA_FROM_ITEM = "floating_nav_from_item"

    private const val TAG = "FloatingNavBar"
    private const val MAX_WIDTH_DP = 440
    private const val SIDE_MARGIN_DP = 24
    private const val BOTTOM_MARGIN_DP = 12

    private var current: java.lang.ref.WeakReference<Activity>? = null

    // Held strongly: SharedPreferences keeps listeners in a weak map.
    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_ENABLED ||
                key == KEY_STYLE ||
                key == KEY_TABS ||
                key == KEY_TAB_ORDER ||
                key == ShizukuSettings.Keys.KEY_FLOATING_NAV_LABEL_MODE ||
                key == ShizukuSettings.Keys.KEY_FLOATING_NAV_AUTOHIDE ||
                key == ShizukuSettings.Keys.KEY_SHAPE_STYLE ||
                key == ShizukuSettings.Keys.KEY_LAST_GRANTED_APP_COUNT ||
                key == ShizukuSettings.Keys.KEY_AMOLED_PLUS ||
                key == "has_update"
            ) {
                current?.get()?.let { sync(it) }
            }
        }

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(this)
        runCatching { ShizukuSettings.getPreferences().registerOnSharedPreferenceChangeListener(prefsListener) }
            .onFailure { Timber.tag(TAG).w(it, "Could not observe floating-nav setting") }
    }

    fun isEnabled(): Boolean = runCatching { ShizukuSettings.getPreferences().getBoolean(KEY_ENABLED, false) }.getOrDefault(false)

    /**
     * True when this screen is a tab on the bar right now (bar on and its tab kept): it's then an
     * M3 top-level destination and shows no back arrow. Same test sync() uses to attach the bar.
     */
    fun isTopLevel(activity: Activity): Boolean = isEnabled() && itemFor(activity)?.let { isTabShown(it) } == true

    private fun itemFor(activity: Activity): Int? =
        when (activity) {
            is MainActivity, is HomeActivity -> R.id.nav_home
            is ApplicationManagementActivity -> R.id.nav_apps
            is SystemHubActivity -> R.id.nav_hub
            is ServiceDoctorActivity -> R.id.nav_doctor
            is ShellTutorialActivity -> R.id.nav_terminal
            is SettingsActivity -> R.id.nav_settings
            else -> null
        }

    private fun targetFor(itemId: Int): Class<out Activity>? =
        when (itemId) {
            R.id.nav_home -> MainActivity::class.java
            R.id.nav_apps -> ApplicationManagementActivity::class.java
            R.id.nav_hub -> SystemHubActivity::class.java
            R.id.nav_doctor -> ServiceDoctorActivity::class.java
            R.id.nav_terminal -> ShellTutorialActivity::class.java
            R.id.nav_settings -> SettingsActivity::class.java
            else -> null
        }

    private fun sync(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val existing = content.findViewById<View>(R.id.floating_nav_root)
        val activeTabs = getActiveTabs()
        val order = activeTabs.map { it.id }
        val selected = itemFor(activity)?.takeIf { it in order }
        if (!isEnabled() || selected == null) {
            existing?.let { content.removeView(it) }
            af.shizuku.manager.app.SnackbarHelper
                .attachBar(null)
            return
        }
        val isNew = existing == null
        val root =
            existing ?: activity.layoutInflater.inflate(R.layout.floating_nav_bar, content, false).also {
                content.addView(it)
                it.applySpringTouch(scale = 0.98f)
            }
        if (!isNew) {
            root.animate().cancel()
            root.alpha = 1f
            root.translationY = 0f
            root.visibility = View.VISIBLE
        }
        val nav = root.findViewById<BottomNavigationView>(R.id.floating_nav)

        // Ensure menu contains the active tabs in exact order
        val currentMenuIds = (0 until nav.menu.size()).map { nav.menu.getItem(it).itemId }
        if (currentMenuIds != order) {
            nav.menu.clear()
            activeTabs.forEach { tab ->
                val item = nav.menu.add(android.view.Menu.NONE, tab.id, android.view.Menu.NONE, tab.titleRes)
                item.setIcon(tab.iconRes)
            }
        }

        // Arriving from another tab: start on that tab and slide the indicator across, so the bar
        // reads as one persistent control even though each screen owns its own copy.
        val from = activity.intent?.getIntExtra(EXTRA_FROM_ITEM, 0) ?: 0
        activity.intent?.removeExtra(EXTRA_FROM_ITEM)
        nav.setOnItemSelectedListener(null)
        if (isNew && from in order && from != selected) {
            nav.selectedItemId = from
            nav.postDelayed({
                nav.setOnItemSelectedListener(null)
                nav.selectedItemId = selected
                attachSelectListener(activity, nav, selected)
                attachLongPressListeners(activity, nav)
            }, 90)
        } else {
            nav.selectedItemId = selected
            attachSelectListener(activity, nav, selected)
            attachLongPressListeners(activity, nav)
        }
        updateBadges(activity, nav)
        if (isNew && from == 0 && ShizukuSettings.isExpressiveAnimationsEnabled()) playEntrance(root)
        if (isNew) watchScrolling(content, root)

        layoutBar(activity, root)
        root.bringToFront()
        ViewCompat.requestApplyInsets(root)
        root.post {
            reserveSpace(content, root)
            af.shizuku.manager.app.SnackbarHelper
                .attachBar(root)
        }
    }

    private fun updateBadges(
        activity: Activity,
        nav: BottomNavigationView,
    ) {
        val grantedCount = ShizukuSettings.getLastGrantedAppCount()
        if (grantedCount > 0 && isTabShown(R.id.nav_apps)) {
            val badge = nav.getOrCreateBadge(R.id.nav_apps)
            badge.number = grantedCount
            badge.maxCharacterCount = 3
            badge.backgroundColor = MaterialColors.getColor(activity, androidx.appcompat.R.attr.colorPrimary, 0xFF4285F4.toInt())
            badge.badgeTextColor = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorOnPrimary, Color.WHITE)
            badge.isVisible = true
        } else {
            nav.removeBadge(R.id.nav_apps)
        }

        val hasUpdate = runCatching { ShizukuSettings.getPreferences().getBoolean("has_update", false) }.getOrDefault(false)
        val hasSkew = ShizukuStateMachine.isServerVersionSkewed()
        if ((hasUpdate || hasSkew) && isTabShown(R.id.nav_settings)) {
            val badge = nav.getOrCreateBadge(R.id.nav_settings)
            badge.backgroundColor = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorError, Color.RED)
            badge.clearNumber()
            badge.isVisible = true
        } else {
            nav.removeBadge(R.id.nav_settings)
        }
    }

    private fun attachLongPressListeners(
        activity: Activity,
        nav: BottomNavigationView,
    ) {
        val activeTabs = getActiveTabs()
        activeTabs.forEach { tab ->
            val itemView = nav.findViewById<View>(tab.id) ?: return@forEach
            itemView.setOnLongClickListener { view ->
                HapticUtils.gestureThreshold(view)
                val popup = androidx.appcompat.widget.PopupMenu(activity, view, android.view.Gravity.TOP)
                when (tab.id) {
                    R.id.nav_home -> {
                        popup.menu.add(0, 1, 0, activity.getString(R.string.home_service_doctor_title))
                        popup.menu.add(0, 2, 1, activity.getString(R.string.home_terminal_title))
                    }
                    R.id.nav_apps -> {
                        popup.menu.add(0, 1, 0, activity.getString(R.string.home_permission_manager_title))
                        popup.menu.add(0, 2, 1, activity.getString(R.string.app_profiles_title))
                        popup.menu.add(0, 3, 2, activity.getString(R.string.home_backup_title))
                    }
                    R.id.nav_hub -> {
                        popup.menu.add(0, 1, 0, activity.getString(R.string.home_device_control_title))
                        popup.menu.add(0, 2, 1, activity.getString(R.string.scripting_title))
                    }
                    R.id.nav_doctor -> {
                        popup.menu.add(0, 1, 0, activity.getString(R.string.settings_main_nav_root_compat_title))
                        popup.menu.add(0, 2, 1, activity.getString(R.string.home_service_doctor_title))
                    }
                    R.id.nav_terminal -> {
                        popup.menu.add(0, 1, 0, activity.getString(R.string.scripting_title))
                    }
                    R.id.nav_settings -> {
                        popup.menu.add(0, 1, 0, activity.getString(R.string.settings_main_nav_root_compat_title))
                        popup.menu.add(0, 2, 1, activity.getString(R.string.home_service_doctor_title))
                    }
                }
                // Customization shortcut accessible from any tab
                popup.menu.add(0, 999, 99, activity.getString(R.string.nav_customizer_title))
                popup.setOnMenuItemClickListener { mi ->
                    if (mi.itemId == 999) {
                        if (activity is FragmentActivity) {
                            NavCustomizerSheet.show(activity.supportFragmentManager)
                        }
                        return@setOnMenuItemClickListener true
                    }
                    when (tab.id) {
                        R.id.nav_home -> {
                            when (mi.itemId) {
                                1 -> activity.startWithSceneTransition(Intent(activity, ServiceDoctorActivity::class.java))
                                2 -> activity.startWithSceneTransition(Intent(activity, ShellTutorialActivity::class.java))
                            }
                        }
                        R.id.nav_apps -> {
                            when (mi.itemId) {
                                1 -> activity.startWithSceneTransition(Intent(activity, af.shizuku.manager.permissionmanager.PermissionManagerActivity::class.java))
                                2 -> activity.startWithSceneTransition(Intent(activity, af.shizuku.manager.automation.AppProfilesActivity::class.java))
                                3 -> activity.startWithSceneTransition(Intent(activity, af.shizuku.manager.backup.AppBackupActivity::class.java))
                            }
                        }
                        R.id.nav_hub -> {
                            when (mi.itemId) {
                                1 -> activity.startWithSceneTransition(Intent(activity, af.shizuku.manager.devicecontrol.DeviceControlActivity::class.java))
                                2 -> activity.startWithSceneTransition(Intent(activity, af.shizuku.manager.scripting.ScriptingActivity::class.java))
                            }
                        }
                        R.id.nav_doctor -> {
                            when (mi.itemId) {
                                1 -> activity.startWithSceneTransition(Intent(activity, RootCompatibilityActivity::class.java))
                                2 -> activity.startWithSceneTransition(Intent(activity, ServiceDoctorActivity::class.java))
                            }
                        }
                        R.id.nav_terminal -> {
                            when (mi.itemId) {
                                1 -> activity.startWithSceneTransition(Intent(activity, af.shizuku.manager.scripting.ScriptingActivity::class.java))
                            }
                        }
                        R.id.nav_settings -> {
                            when (mi.itemId) {
                                1 -> activity.startWithSceneTransition(Intent(activity, RootCompatibilityActivity::class.java))
                                2 -> activity.startWithSceneTransition(Intent(activity, ServiceDoctorActivity::class.java))
                            }
                        }
                    }
                    true
                }
                popup.show()
                true
            }
        }
    }

    private fun attachSelectListener(
        activity: Activity,
        nav: BottomNavigationView,
        selected: Int,
    ) {
        // Tapping the current tab again scrolls its page back to the top (M3 reselect).
        nav.setOnItemReselectedListener { item ->
            af.shizuku.manager.utils.HapticUtils
                .tap(nav)
            animateTabSelection(nav, item.itemId)
            val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return@setOnItemReselectedListener
            findRecyclerView(content)?.smoothScrollToPosition(0)
            findNestedScrollView(content)?.smoothScrollTo(0, 0)
            findScrollView(content)?.smoothScrollTo(0, 0)
        }
        nav.setOnItemSelectedListener { item ->
            if (item.itemId != selected) {
                af.shizuku.manager.utils.HapticUtils
                    .segmentTick(nav)
                animateTabSelection(nav, item.itemId)
                navigate(activity, item.itemId, selected)
                true
            } else {
                false
            }
        }
    }

    private fun animateTabSelection(
        nav: BottomNavigationView,
        itemId: Int,
    ) {
        val itemView = nav.findViewById<View>(itemId) ?: return
        val iconView = itemView.findViewById<View>(com.google.android.material.R.id.navigation_bar_item_icon_container) ?: itemView
        iconView
            .animate()
            .scaleX(1.10f)
            .scaleY(1.10f)
            .setDuration(120)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withEndAction {
                iconView
                    .animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(220)
                    .setInterpolator(android.view.animation.OvershootInterpolator(2.0f))
                    .start()
            }.start()
    }

    /** First appearance: rise from below with a short fade. Respects the system animation scale. */
    private fun playEntrance(root: View) {
        val rise = 56 * root.resources.displayMetrics.density
        root.alpha = 0f
        root.translationY = rise
        root
            .animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(ShizukuSettings.scaledAnimationDuration(300))
            .setInterpolator(af.shizuku.manager.utils.MotionUtils.EMPHASIZED_DECELERATE)
            .start()
    }

    /** Observes scrolling to settle the bar into place at the base of the page and handle autohide. */
    private fun watchScrolling(
        content: ViewGroup,
        bar: View,
    ) {
        content.post {
            var hidden = false
            var atBottom = false
            // No autohide with TalkBack: a bar that slides away under touch exploration is lost.
            val autohide =
                ShizukuSettings.isFloatingNavAutohideEnabled() &&
                    content.context
                        .getSystemService(android.view.accessibility.AccessibilityManager::class.java)
                        ?.isTouchExplorationEnabled != true

            findRecyclerView(content)?.addOnScrollListener(
                object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(
                        rv: RecyclerView,
                        dx: Int,
                        dy: Int,
                    ) {
                        val canScrollDown = rv.canScrollVertically(1)
                        if (!canScrollDown && !atBottom) {
                            atBottom = true
                            // Subtle haptic tick and settle into place at base
                            af.shizuku.manager.utils.HapticUtils
                                .segmentTick(bar)
                            if (hidden) {
                                hidden = false
                                slideBar(bar, hide = false)
                            }
                        } else if (canScrollDown && atBottom && dy < -12) {
                            // Re-arm only on a real scroll back up: canScrollVertically() flickers
                            // at the end of a fling and would replay the bounce + haptic tick.
                            atBottom = false
                        }

                        if (autohide) {
                            if (dy > 12 && !hidden && canScrollDown) {
                                hidden = true
                                slideBar(bar, hide = true)
                            } else if (dy < -12 && hidden) {
                                hidden = false
                                slideBar(bar, hide = false)
                            }
                        }
                    }
                },
            )
            findNestedScrollView(content)?.setOnScrollChangeListener(
                NestedScrollView.OnScrollChangeListener { v, _, scrollY, _, oldScrollY ->
                    val dy = scrollY - oldScrollY
                    val canScrollDown = v.canScrollVertically(1)
                    if (!canScrollDown && !atBottom) {
                        atBottom = true
                        af.shizuku.manager.utils.HapticUtils
                            .segmentTick(bar)
                        if (hidden) {
                            hidden = false
                            slideBar(bar, hide = false)
                        }
                    } else if (canScrollDown && atBottom && dy < -12) {
                        atBottom = false
                    }

                    if (autohide) {
                        if (dy > 12 && !hidden && canScrollDown) {
                            hidden = true
                            slideBar(bar, hide = true)
                        } else if (dy < -12 && hidden) {
                            hidden = false
                            slideBar(bar, hide = false)
                        }
                    }
                },
            )
        }
    }

    private fun slideBar(
        bar: View,
        hide: Boolean,
    ) {
        val distance = bar.height + (bar.layoutParams as FrameLayout.LayoutParams).bottomMargin
        val duration = ShizukuSettings.scaledAnimationDuration(if (hide) 200 else 280)
        val interpolator =
            if (hide) {
                af.shizuku.manager.utils.MotionUtils.EMPHASIZED_ACCELERATE
            } else {
                af.shizuku.manager.utils.MotionUtils.EMPHASIZED_DECELERATE
            }
        bar
            .animate()
            .translationY(if (hide) distance.toFloat() else 0f)
            .alpha(if (hide) 0f else 1f)
            .setDuration(duration)
            .setInterpolator(interpolator)
            .start()
        // A message sitting on the bar moves with it, on the same curve.
        af.shizuku.manager.app.SnackbarHelper
            .followBar(bar, hide, duration, interpolator)
    }

    private fun findRecyclerView(view: View): RecyclerView? {
        if (view is RecyclerView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findRecyclerView(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun findNestedScrollView(view: View): NestedScrollView? {
        if (view is NestedScrollView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findNestedScrollView(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun findScrollView(view: View): ScrollView? {
        if (view is ScrollView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findScrollView(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    /** The bar's corner radius from the rounding settings, before the docked/capsule shapes. */
    private fun baseRadius(context: Context): Float {
        val density = context.resources.displayMetrics.density
        if (!ShizukuSettings.isRoundedEdgesEnabled()) return 0f
        if (ShizukuSettings.isOneUiThemeEnabled()) return 36f * density
        val cornerOverride =
            ShizukuSettings
                .getPreferences()
                .getString(ShizukuSettings.Keys.KEY_CORNER_ROUNDING_OVERRIDE, "default")
        return when (cornerOverride) {
            "gentle" -> 20f * density
            "standard" -> 28f * density
            "large" -> 36f * density
            "maximum" -> 44f * density
            else -> 32f * density
        }
    }

    /** The corner radius a message sitting on the bar uses, so the two read as one stack. */
    fun messageRadius(context: Context): Float {
        val density = context.resources.displayMetrics.density
        return when (style()) {
            "capsule" -> 38f * density
            "docked" -> baseRadius(context).coerceAtMost(28f * density)
            else -> baseRadius(context)
        }
    }

    /** Whether the bar spans the screen width (a message then keeps its own width). */
    fun isDocked(): Boolean = style() == "docked"

    private fun layoutBar(
        activity: Activity,
        root: View,
    ) {
        val density = activity.resources.displayMetrics.density
        val screenWidth = activity.resources.displayMetrics.widthPixels
        val barStyle = style()
        val width =
            when (barStyle) {
                // Docked: edge to edge along the bottom, like a classic navigation bar.
                "docked" -> screenWidth
                "compact" -> min((COMPACT_MAX_WIDTH_DP * density).toInt(), screenWidth - (2 * SIDE_MARGIN_DP * density).toInt())
                "capsule" -> min((330 * density).toInt(), screenWidth - (2 * 16 * density).toInt())
                else -> min((MAX_WIDTH_DP * density).toInt(), screenWidth - (2 * SIDE_MARGIN_DP * density).toInt())
            }
        val lp = root.layoutParams as FrameLayout.LayoutParams
        lp.width = width
        lp.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
        root.layoutParams = lp

        // M3 Expressive card adaptation: Dynamic curvature and AMOLED+ true black
        val card = root as? MaterialCardView
        if (card != null) {
            card.radius = baseRadius(activity)

            val isNight = ResourceUtils.isNightMode(activity.resources.configuration)
            val isBlur = ShizukuSettings.isBlurUiEnabled()
            val primary = MaterialColors.getColor(activity, androidx.appcompat.R.attr.colorPrimary, 0xFF4285F4.toInt())
            val isAmoled = ShizukuSettings.isAmoledPlusEnabled() && isNight

            // NEVER use opaque pitch black for the floating nav bar. A floating dock must look
            // like an elevated, frosted glass surface that floats cleanly above the content below.
            val baseSurface =
                if (isAmoled) {
                    ColorUtils.blendARGB(0xFF0F1115.toInt(), primary, 0.08f)
                } else if (isNight) {
                    // Refined dark glass: deep slate-charcoal subtly harmonized with the accent
                    ColorUtils.blendARGB(0xFF202227.toInt(), primary, 0.12f)
                } else {
                    // Refined light glass: luminous off-white subtly harmonized with the accent
                    ColorUtils.blendARGB(0xFFF6F7F9.toInt(), primary, 0.08f)
                }

            val blurActive = isBlur && setupBlur(activity, root)
            // With real blur behind it the tint can be lighter; without, keep it denser so labels
            // stay legible over busy content.
            val alphaFraction =
                if (blurActive) {
                    0.55f
                } else if (isAmoled) {
                    0.90f
                } else {
                    0.85f
                }
            val cardBg = ColorUtils.setAlphaComponent(baseSurface, (255 * alphaFraction).toInt())
            card.setCardBackgroundColor(cardBg)
            card.clipToOutline = true

            // Specular luminous glass rim
            val glassRim =
                if (isAmoled) {
                    Color.argb(85, 255, 255, 255)
                } else if (isNight) {
                    Color.argb(70, 255, 255, 255)
                } else {
                    Color.argb(120, 255, 255, 255)
                }
            card.strokeColor = glassRim
            card.strokeWidth = (1.0f * density).toInt()
            if (barStyle == "docked") {
                // Only the top corners round off; the bottom sits flush on the screen edge.
                val top = card.radius.coerceAtMost(28f * density)
                card.shapeAppearanceModel =
                    card.shapeAppearanceModel
                        .toBuilder()
                        .setTopLeftCornerSize(top)
                        .setTopRightCornerSize(top)
                        .setBottomLeftCornerSize(0f)
                        .setBottomRightCornerSize(0f)
                        .build()
            } else {
                val r = if (barStyle == "capsule") 38f * density else card.radius
                card.shapeAppearanceModel =
                    card.shapeAppearanceModel
                        .toBuilder()
                        .setAllCornerSizes(r)
                        .build()
            }
            // No elevation: the shadow of a translucent card renders *through* its fill as a
            // dark band under the bar (the recurring "black bar"). The rim defines the edge.
            card.cardElevation = 0f

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                card.outlineAmbientShadowColor = Color.argb(30, 0, 0, 0)
                card.outlineSpotShadowColor = Color.argb(50, 0, 0, 0)
            }
        }

        val nav = root.findViewById<BottomNavigationView>(R.id.floating_nav)
        if (nav != null) {
            val labelMode = ShizukuSettings.getFloatingNavLabelMode()
            nav.labelVisibilityMode =
                when (labelMode) {
                    "labeled" -> com.google.android.material.navigation.NavigationBarView.LABEL_VISIBILITY_LABELED
                    "unlabeled" -> com.google.android.material.navigation.NavigationBarView.LABEL_VISIBILITY_UNLABELED
                    else -> com.google.android.material.navigation.NavigationBarView.LABEL_VISIBILITY_SELECTED
                }

            val navLp = nav.layoutParams
            val desiredHeight = if (labelMode == "unlabeled") (64 * density).toInt() else (80 * density).toInt()
            if (navLp != null && navLp.height != desiredHeight) {
                navLp.height = desiredHeight
                nav.layoutParams = navLp
            }

            // Material 3 Expressive active indicator shape morphing
            val shapeStyle = ShizukuSettings.getShapeStyle()
            val indicatorShape =
                when (shapeStyle) {
                    "squircle" ->
                        com.google.android.material.shape.ShapeAppearanceModel
                            .builder()
                            .setAllCornerSizes(16f * density)
                            .build()
                    "cut" ->
                        com.google.android.material.shape.ShapeAppearanceModel
                            .builder()
                            .setAllCorners(com.google.android.material.shape.CornerFamily.CUT, 10f * density)
                            .build()
                    "zen" ->
                        com.google.android.material.shape.ShapeAppearanceModel
                            .builder()
                            .setTopLeftCornerSize(
                                com.google.android.material.shape
                                    .RelativeCornerSize(0.5f),
                            ).setBottomRightCornerSize(
                                com.google.android.material.shape
                                    .RelativeCornerSize(0.5f),
                            ).setTopRightCornerSize(8f * density)
                            .setBottomLeftCornerSize(8f * density)
                            .build()
                    "classic" ->
                        com.google.android.material.shape.ShapeAppearanceModel
                            .builder()
                            .setAllCornerSizes(6f * density)
                            .build()
                    else ->
                        com.google.android.material.shape.ShapeAppearanceModel
                            .builder()
                            .setAllCornerSizes(
                                com.google.android.material.shape
                                    .RelativeCornerSize(0.5f),
                            ).build()
                }
            nav.itemActiveIndicatorShapeAppearance = indicatorShape

            // M3 navigation bar roles: secondaryContainer indicator, onSecondaryContainer active
            // icon, secondary active label, onSurfaceVariant inactive (was primary at 22-35% alpha).
            fun role(attr: Int) = MaterialColors.getColor(activity, attr, Color.GRAY)
            val inactive = role(com.google.android.material.R.attr.colorOnSurfaceVariant)
            val checked = intArrayOf(android.R.attr.state_checked)
            nav.itemActiveIndicatorColor =
                ColorStateList.valueOf(role(com.google.android.material.R.attr.colorSecondaryContainer))
            nav.itemIconTintList =
                ColorStateList(
                    arrayOf(checked, intArrayOf()),
                    intArrayOf(role(com.google.android.material.R.attr.colorOnSecondaryContainer), inactive),
                )
            nav.itemTextColor =
                ColorStateList(
                    arrayOf(checked, intArrayOf()),
                    intArrayOf(role(com.google.android.material.R.attr.colorSecondary), inactive),
                )
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            // Read the root insets: a parent may already have consumed the ones dispatched to us.
            val rootInsets = ViewCompat.getRootWindowInsets(v) ?: insets
            val bottom = rootInsets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            val imeVisible = rootInsets.isVisible(WindowInsetsCompat.Type.ime())
            val params = v.layoutParams as FrameLayout.LayoutParams
            val docked = style() == "docked"
            // Docked sits on the screen edge and pads its content above the gesture/nav bar;
            // floating styles hover above it with a margin.
            val margin = if (docked) 0 else bottom + (BOTTOM_MARGIN_DP * density).toInt()
            (v as? MaterialCardView)?.setContentPadding(0, 0, 0, if (docked) bottom else 0)
            if (params.bottomMargin != margin) {
                params.bottomMargin = margin
                v.layoutParams = params
            }
            v.visibility = if (imeVisible) View.GONE else View.VISIBLE
            insets
        }
    }

    private fun navigate(
        activity: Activity,
        itemId: Int,
        fromItem: Int,
    ) {
        val target = targetFor(itemId) ?: return
        val intent = Intent(activity, target).putExtra(EXTRA_FROM_ITEM, fromItem)
        // Slide towards the tab that was tapped: screens to the right enter from the right.
        val order = currentOrder()
        val fromIndex = order.indexOf(fromItem).takeIf { it >= 0 } ?: 0
        val toIndex = order.indexOf(itemId).takeIf { it >= 0 } ?: 0
        val forward = toIndex > fromIndex
        val enterAnim = if (forward) R.anim.floating_nav_enter_forward else R.anim.floating_nav_enter_back
        val exitAnim = if (forward) R.anim.floating_nav_exit_forward else R.anim.floating_nav_exit_back
        val options =
            ActivityOptions.makeCustomAnimation(
                activity,
                enterAnim,
                exitAnim,
            )
        if (itemId == R.id.nav_home) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        } else {
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching {
            activity.startActivity(intent, options.toBundle())
        }.onFailure {
            Timber.tag(TAG).w(it, "Floating nav navigation failed")
            activity.startActivity(intent)
        }
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, enterAnim, exitAnim)
        } else {
            @Suppress("DEPRECATION")
            activity.overridePendingTransition(enterAnim, exitAnim)
        }
    }

    /**
     * Lets lists scroll their last row clear of the bar. Scrollables already padded at least that
     * much (the settings list reserves its own clearance) are left alone.
     */

    /**
     * Points the bar's BlurView at the screen's BlurTarget. Returns false (plain translucent card)
     * below API 31 - the RenderScript path is deprecated and too slow for a always-visible bar - or
     * when the screen wasn't wrapped (setting turned on after the screen was created).
     */
    private fun setupBlur(
        activity: Activity,
        root: View,
    ): Boolean {
        val blurView = root.findViewById<eightbitlab.com.blurview.BlurView>(R.id.floating_nav_blur) ?: return false
        val target =
            (activity.findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0) as? eightbitlab.com.blurview.BlurTarget)
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S || target == null) {
            blurView.setBlurEnabled(false)
            return false
        }
        return runCatching {
            blurView
                .setupWith(target)
                .setFrameClearDrawable(activity.window.decorView.background)
                .setBlurRadius(24f)
            blurView.setBlurEnabled(true)
            true
        }.getOrElse {
            Timber.w(it, "floating nav blur unavailable")
            blurView.setBlurEnabled(false)
            false
        }
    }

    /**
     * Wraps the screen's content in a BlurTarget so the bar can blur what's behind it. Runs in
     * onActivityPostCreated - after setContentView, before the window attaches - so no view
     * (notably a ComposeView) is ever detached and loses its composition.
     */
    private fun wrapInBlurTarget(activity: Activity) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return
        if (!isEnabled() || !ShizukuSettings.isBlurUiEnabled() || itemFor(activity) == null) return
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (content.isAttachedToWindow || content.getChildAt(0) is eightbitlab.com.blurview.BlurTarget) return
        val children = (0 until content.childCount).map { content.getChildAt(it) }
        val target = eightbitlab.com.blurview.BlurTarget(activity)
        children.forEach { child ->
            val lp = child.layoutParams
            content.removeView(child)
            target.addView(child, lp)
        }
        content.addView(
            target,
            0,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    private fun reserveSpace(
        content: ViewGroup,
        bar: View,
    ) {
        if (bar.height == 0) return
        val needed =
            bar.height +
                (bar.layoutParams as FrameLayout.LayoutParams).bottomMargin +
                (24 * bar.resources.displayMetrics.density).toInt()

        fun visit(view: View) {
            if (view === bar) return
            val scrollable = view is RecyclerView || view is NestedScrollView || view is ScrollView || view is AbsListView
            if (scrollable && view.paddingBottom < needed) {
                view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, needed)
                if (view is ViewGroup) view.clipToPadding = false
            }
            if (!scrollable && view is ViewGroup) {
                for (i in 0 until view.childCount) visit(view.getChildAt(i))
            }
        }
        visit(content)
    }

    override fun onActivityResumed(activity: Activity) {
        current = java.lang.ref.WeakReference(activity)
        sync(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (current?.get() === activity) current = null
    }

    override fun onActivityCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) {
        activity.window?.decorView?.post {
            if (!activity.isFinishing && !activity.isDestroyed) {
                sync(activity)
            }
        }
    }

    override fun onActivityPostCreated(
        activity: Activity,
        savedInstanceState: Bundle?,
    ) {
        runCatching { wrapInBlurTarget(activity) }.onFailure { Timber.w(it, "BlurTarget wrap failed") }
    }

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(
        activity: Activity,
        outState: Bundle,
    ) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        if (current?.get() === activity) current = null
    }
}
