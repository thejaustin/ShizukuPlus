package af.shizuku.manager.settings

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.ShizukuSettings.Keys.*
import af.shizuku.manager.adb.AdbStarter
import af.shizuku.manager.app.SnackbarHelper
import af.shizuku.manager.receiver.NotifCancelReceiver
import af.shizuku.manager.receiver.ShizukuReceiverStarter
import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.manager.utils.HapticUtils
import af.shizuku.manager.utils.IconStyleHelper
import af.shizuku.manager.utils.SettingsHelper
import af.shizuku.manager.utils.ShizukuStateMachine
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat.Type
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import rikka.html.text.HtmlCompat
import rikka.recyclerview.fixEdgeEffect
import kotlin.coroutines.resume

abstract class BaseSettingsFragment : PreferenceFragmentCompat() {
    protected lateinit var batteryOptimizationListener: ActivityResultLauncher<Intent>
    protected var batteryOptimizationContinuation: CancellableContinuation<Boolean>? = null
    private val activeDialogs = mutableListOf<android.app.Dialog>()

    // Drawables captured from preference XML before any icon styling is applied. Re-applying
    // from originals (not from already-styled drawables) avoids stacked tints when the user
    // changes icon style while staying on the settings page.
    private val originalIcons = HashMap<String, android.graphics.drawable.Drawable?>()

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        preferenceManager.setStorageDeviceProtected()
        preferenceManager.sharedPreferencesName = ShizukuSettings.NAME
        preferenceManager.sharedPreferencesMode = android.content.Context.MODE_PRIVATE

        batteryOptimizationListener =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                val accepted = SettingsHelper.isIgnoringBatteryOptimizations(requireContext())
                batteryOptimizationContinuation?.resume(accepted)
            }

        onCreateSettingsPreferences(savedInstanceState, rootKey)

        preferenceScreen?.let {
            captureOriginalIcons(it)
            IconStyleHelper.applyToTree(requireContext(), it)
            fixDeprecatedListPreferenceSummaries(it)
        }
    }

    private fun getPrefIconCacheKey(
        pref: androidx.preference.Preference,
        index: Int,
    ): String = pref.key ?: "pref_${index}_${pref.title?.toString().hashCode()}"

    private fun captureOriginalIcons(group: androidx.preference.PreferenceGroup) {
        for (i in 0 until group.preferenceCount) {
            val pref = group.getPreference(i)
            if (pref is androidx.preference.PreferenceGroup) {
                captureOriginalIcons(pref)
                if (pref is androidx.preference.PreferenceCategory) continue
            }
            pref.icon?.let { icon ->
                val key = getPrefIconCacheKey(pref, i)
                originalIcons[key] = icon.constantState?.newDrawable() ?: icon
            }
        }
    }

    /**
     * Re-applies the current icon style to all preferences from their original (unstyled)
     * drawables. Safe to call any number of times — always starts from the original, so
     * repeated calls don't stack tints or wrap LayerDrawables in more LayerDrawables.
     */
    fun refreshIconStyles() {
        val screen = preferenceScreen ?: return
        val ctx = context ?: return
        val style = IconStyleHelper.current()
        val colorMode = IconStyleHelper.currentColorMode()
        reapplyIconStyles(ctx, screen, style, colorMode)
    }

    private fun reapplyIconStyles(
        ctx: Context,
        group: androidx.preference.PreferenceGroup,
        style: IconStyleHelper.Style,
        colorMode: IconStyleHelper.ColorMode,
    ) {
        var count = 0
        for (i in 0 until group.preferenceCount) {
            val pref = group.getPreference(i)
            if (pref is androidx.preference.PreferenceGroup) {
                reapplyIconStyles(ctx, pref, style, colorMode)
                if (pref is androidx.preference.PreferenceCategory) continue
            }
            val key = getPrefIconCacheKey(pref, i)
            val original = originalIcons[key] ?: continue
            pref.icon = IconStyleHelper.stylize(ctx, original.mutate(), style, colorMode, pref.key)
            count++
        }
        timber.log.Timber.tag("BaseSettings").d(
            "reapplyIconStyles: updated %d icons in group '%s'",
            count,
            group.key ?: group.title ?: "root",
        )
    }

    /**
     * `android:summary="%s"` on a ListPreference (used throughout the settings XML to show the
     * selected entry's label as the summary) logs "Setting a summary with a String formatting
     * marker is no longer supported" on every bind - AndroidX deprecated the format-string
     * summary in favor of ListPreference.SimpleSummaryProvider, which renders identically.
     * Walking the tree here fixes every occurrence at once instead of touching each preference's
     * binding code individually.
     */
    private fun fixDeprecatedListPreferenceSummaries(group: androidx.preference.PreferenceGroup) {
        for (i in 0 until group.preferenceCount) {
            when (val pref = group.getPreference(i)) {
                is androidx.preference.ListPreference -> {
                    // Guard: some rikka SimpleMenuPreference instances with useSimpleSummaryProvider
                    // already have SimpleSummaryProvider installed by the time we reach here. Their
                    // getSummary() can return "%s" in edge cases (e.g. value doesn't match any entry),
                    // which would trick this check into calling setSummaryProvider() a second time —
                    // throwing "Preference already has a SummaryProvider set." (#529 crash).
                    if (pref.summary == "%s" && pref.summaryProvider == null) {
                        pref.summaryProvider =
                            androidx.preference.ListPreference.SimpleSummaryProvider
                                .getInstance()
                    }
                }
                is androidx.preference.PreferenceGroup -> fixDeprecatedListPreferenceSummaries(pref)
            }
        }
    }

    abstract fun onCreateSettingsPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    )

    override fun onPreferenceTreeClick(preference: androidx.preference.Preference): Boolean {
        // Switches play their own toggle-on/off haptic (GrayableIconSwitchPreference); a generic
        // tap on top made every toggle a muddy double buzz.
        if (preference !is androidx.preference.TwoStatePreference) HapticUtils.tap(requireView())
        return super.onPreferenceTreeClick(preference)
    }

    open fun getTitle(): CharSequence? = null

    override fun onResume() {
        super.onResume()

        val title = getTitle()
        if (title != null) {
            (activity as? SettingsActivity)?.updateTitle(title.toString())
        }

        val highlightKey = arguments?.getString("highlight_key")
        if (!highlightKey.isNullOrEmpty()) {
            listView?.post {
                // Auto-expand the parent CollapsiblePreferenceCategory if it's collapsed so the
                // target preference is actually visible in the adapter before we try to scroll.
                val targetPref = findPreference<androidx.preference.Preference>(highlightKey)
                (targetPref?.parent as? CollapsiblePreferenceCategory)?.expand()

                val adapter = listView?.adapter
                if (adapter != null) {
                    var position = -1
                    for (i in 0 until adapter.itemCount) {
                        val pref = (adapter as? androidx.preference.PreferenceGroupAdapter)?.getItem(i)
                        if (pref?.key == highlightKey) {
                            position = i
                            break
                        }
                    }

                    if (position >= 0) {
                        listView?.smoothScrollToPosition(position)
                        listView?.postDelayed({
                            val holder = listView?.findViewHolderForAdapterPosition(position)
                            holder?.itemView?.let { itemView ->
                                val defaultBg = itemView.background
                                val tintColor = TypedValue()
                                requireContext().theme.resolveAttribute(R.attr.colorPrimaryContainer, tintColor, true)
                                val highlight = android.graphics.drawable.ColorDrawable(tintColor.data)
                                itemView.background = highlight
                                android.animation.ValueAnimator.ofInt(255, 0).apply {
                                    duration = ShizukuSettings.scaledAnimationDuration(700)
                                    startDelay = ShizukuSettings.scaledAnimationDuration(350)
                                    interpolator = android.view.animation.DecelerateInterpolator()
                                    addUpdateListener { highlight.alpha = it.animatedValue as Int }
                                    addListener(
                                        object : android.animation.AnimatorListenerAdapter() {
                                            override fun onAnimationEnd(animation: android.animation.Animator) {
                                                itemView.background = defaultBg
                                            }
                                        },
                                    )
                                    start()
                                }
                            }
                        }, 400)
                    }
                }
            }
            arguments?.remove("highlight_key")
        }
    }

    override fun onDestroyView() {
        activeDialogs.forEach { if (it.isShowing) it.dismiss() }
        activeDialogs.clear()
        listView?.let { rv ->
            rv.adapter = null
            for (i in rv.itemDecorationCount - 1 downTo 0) {
                rv.removeItemDecorationAt(i)
            }
        }
        super.onDestroyView()
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        setDivider(null)
    }

    /**
     * Confirms an action with an M3 snackbar on this page; falls back to a toast if the page is
     * gone by the time an async result arrives (or isn't showing).
     */
    protected fun notifyUser(
        message: CharSequence?,
        long: Boolean = false,
    ) {
        if (message.isNullOrEmpty()) return
        val v = view
        if (v != null && isAdded && v.isAttachedToWindow) {
            af.shizuku.manager.app.SnackbarHelper.show(
                v.context,
                v,
                message.toString(),
                if (long) com.google.android.material.snackbar.Snackbar.LENGTH_LONG else com.google.android.material.snackbar.Snackbar.LENGTH_SHORT,
            )
        } else {
            val ctx = context ?: return
            android.widget.Toast
                .makeText(ctx, message, if (long) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT)
                .show()
        }
    }

    protected fun notifyUser(
        @androidx.annotation.StringRes message: Int,
        long: Boolean = false,
    ) = notifyUser(context?.getString(message), long)

    /**
     * Helper to show a dialog and track it for proper dismissal.
     */
    protected fun showDialog(builder: MaterialAlertDialogBuilder) {
        val dialog = builder.show()
        activeDialogs.add(dialog)
        dialog.setOnDismissListener { activeDialogs.remove(dialog) }
    }

    override fun onCreateRecyclerView(
        inflater: LayoutInflater,
        parent: ViewGroup,
        savedInstanceState: Bundle?,
    ): RecyclerView {
        val recyclerView = super.onCreateRecyclerView(inflater, parent, savedInstanceState)
        val context = recyclerView.context
        val cardMarginPx = context.resources.getDimensionPixelSize(R.dimen.margin_horizontal)

        // Fix: Disable LayoutTransition to prevent IllegalArgumentException
        // "Providing a LayoutTransition into RecyclerView is not supported"
        recyclerView.layoutTransition = null
        recyclerView.itemAnimator =
            androidx.recyclerview.widget.DefaultItemAnimator().apply {
                supportsChangeAnimations = false
            }

        val headerTopPx = (activity as? SettingsActivity)?.getExpandedHeaderHeightPx() ?: 0
        val isOled =
            (
                af.shizuku.manager.app.ThemeHelper
                    .isBlackNightTheme(context) ||
                    af.shizuku.manager.ShizukuSettings
                        .isAmoledPlusEnabled()
            ) &&
                rikka.core.util.ResourceUtils
                    .isNightMode(context.resources.configuration)
        val pageBgColor =
            if (isOled) {
                android.graphics.Color.BLACK
            } else {
                val pageBgValue = TypedValue()
                context.theme.resolveAttribute(R.attr.colorSurfaceContainerLow, pageBgValue, true)
                pageBgValue.data
            }
        recyclerView.setBackgroundColor(pageBgColor)
        timber.log.Timber.tag("BaseSettings").d(
            "onCreateRecyclerView: isOled=%s, pageBgColor=0x%08X",
            isOled,
            pageBgColor,
        )
        recyclerView.setPadding(cardMarginPx, headerTopPx, cardMarginPx, 0)
        recyclerView.clipToPadding = false
        recyclerView.addItemDecoration(SettingsItemDecoration(context))

        ViewCompat.setOnApplyWindowInsetsListener(recyclerView) { _, insets ->
            val systemBarsInsets = insets.getInsets(Type.systemBars() or Type.displayCutout())
            val navBarClearancePx =
                if (ShizukuSettings.isFloatingNavBarEnabled()) {
                    (160 * context.resources.displayMetrics.density).toInt()
                } else {
                    (20 * context.resources.displayMetrics.density).toInt()
                }
            val currentHeaderTop = (activity as? SettingsActivity)?.getExpandedHeaderHeightPx() ?: 0
            recyclerView.setPadding(
                cardMarginPx + systemBarsInsets.left,
                currentHeaderTop + systemBarsInsets.top,
                cardMarginPx + systemBarsInsets.right,
                systemBarsInsets.bottom + navBarClearancePx,
            )
            insets
        }

        recyclerView.fixEdgeEffect()
        recyclerView.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(
                    rv: RecyclerView,
                    dx: Int,
                    dy: Int,
                ) {
                    val canScrollUp = rv.canScrollVertically(-1)
                    (activity as? SettingsActivity)?.onPreferenceListScrolled(dy, canScrollUp, rv.computeVerticalScrollOffset())
                }

                override fun onScrollStateChanged(
                    rv: RecyclerView,
                    newState: Int,
                ) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        (activity as? SettingsActivity)?.onPreferenceListScrollIdle()
                    }
                }
            },
        )
        return recyclerView
    }

    override fun onCreateAdapter(preferenceScreen: androidx.preference.PreferenceScreen): RecyclerView.Adapter<*> {
        val adapter = super.onCreateAdapter(preferenceScreen)
        return object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            init {
                adapter.registerAdapterDataObserver(
                    object : RecyclerView.AdapterDataObserver() {
                        override fun onChanged() = notifyDataSetChanged()

                        override fun onItemRangeChanged(
                            positionStart: Int,
                            itemCount: Int,
                        ) = notifyItemRangeChanged(positionStart, itemCount)

                        override fun onItemRangeInserted(
                            positionStart: Int,
                            itemCount: Int,
                        ) = notifyItemRangeInserted(positionStart, itemCount)

                        override fun onItemRangeRemoved(
                            positionStart: Int,
                            itemCount: Int,
                        ) = notifyItemRangeRemoved(positionStart, itemCount)

                        override fun onItemRangeMoved(
                            fromPosition: Int,
                            toPosition: Int,
                            itemCount: Int,
                        ) {
                            for (i in 0 until itemCount) notifyItemMoved(fromPosition + i, toPosition + i)
                        }
                    },
                )
            }

            override fun getItemCount(): Int = adapter.itemCount

            override fun getItemViewType(position: Int): Int = adapter.getItemViewType(position)

            override fun getItemId(position: Int): Long = adapter.getItemId(position)

            override fun onCreateViewHolder(
                parent: ViewGroup,
                viewType: Int,
            ): RecyclerView.ViewHolder = adapter.onCreateViewHolder(parent, viewType)

            override fun onBindViewHolder(
                holder: RecyclerView.ViewHolder,
                position: Int,
            ) {
                adapter.onBindViewHolder(holder, position)
                val pref = (adapter as? androidx.preference.PreferenceGroupAdapter)?.getItem(position)
                if (pref != null && pref !is PlusFeaturePreference) {
                    // No tooltip: the row's title and summary are already visible (M3 keeps
                    // tooltips for unlabelled controls) and TalkBack read them twice.
                    val hasHelp =
                        af.shizuku.manager.knowledgebase.KnowledgebaseRegistry
                            .topicForPrefKey(pref.key) != null
                    // Long-press opens help: name the action so TalkBack announces it.
                    androidx.core.view.ViewCompat.replaceAccessibilityAction(
                        holder.itemView,
                        androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK,
                        if (hasHelp) holder.itemView.context.getString(R.string.settings_help) else null,
                        null,
                    )
                    holder.itemView.setOnLongClickListener {
                        val topicId =
                            af.shizuku.manager.knowledgebase.KnowledgebaseRegistry
                                .topicForPrefKey(pref.key)
                        if (topicId != null) {
                            HapticUtils.tap(holder.itemView)
                            af.shizuku.manager.knowledgebase.KnowledgebaseHelper
                                .show(holder.itemView.context, topicId)
                            true
                        } else {
                            false
                        }
                    }
                }
            }
        }
    }

    protected fun needsRestart(
        setting: String,
        newValue: Any? = null,
    ): Boolean {
        val currentPort = EnvironmentUtils.getAdbTcpPort()
        return when (setting) {
            KEY_TCP_MODE -> {
                val newMode = newValue as? Boolean ?: ShizukuSettings.getTcpMode()
                (currentPort > 0) != newMode
            }
            KEY_TCP_PORT -> {
                val newPort = newValue as? Int ?: ShizukuSettings.getTcpPort()
                (currentPort > 0) && (currentPort != newPort)
            }
            KEY_DHIZUKU_MODE, KEY_CUSTOM_API_ENABLED -> true
            else -> false
        }
    }

    protected fun maybeGetRestartIcon(
        setting: String,
        @androidx.annotation.DrawableRes fallback: Int = 0,
    ): Drawable? {
        if (!needsRestart(setting)) {
            // Never return null for a setting with a static icon: assigning null wipes it.
            return if (fallback != 0) {
                tint(
                    androidx.core.content.ContextCompat
                        .getDrawable(requireContext(), fallback),
                )
            } else {
                null
            }
        }
        return tint(
            androidx.core.content.ContextCompat
                .getDrawable(requireContext(), R.drawable.ic_server_restart),
        )
    }

    protected fun tint(icon: Drawable?): Drawable? {
        val tintColor = TypedValue()
        requireContext().theme.resolveAttribute(R.attr.colorOnSurfaceVariant, tintColor, true)
        icon?.mutate()?.setTint(tintColor.data)
        return icon
    }

    /**
     * Confirms closing the live ADB TCP port, then closes it. [onClosing] fires while the port is
     * being closed (so the UI can show progress), [onFinished] always fires afterwards, and
     * [applyChange] only runs when the port actually closed.
     */
    protected fun promptStopTcp(
        onClosing: () -> Unit,
        onFinished: () -> Unit,
        applyChange: () -> Unit,
    ) {
        val context = requireContext()
        showDialog(
            MaterialAlertDialogBuilder(context)
                .setTitle(android.R.string.dialog_alert_title)
                .setMessage(context.getString(R.string.settings_tcp_mode_dialog_close_port))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    lifecycleScope.launch {
                        onClosing()
                        try {
                            AdbStarter.stopTcp(context, EnvironmentUtils.getAdbTcpPort())
                            if (EnvironmentUtils.getAdbTcpPort() <= 0) applyChange()
                        } finally {
                            onFinished()
                        }
                    }
                }.setNegativeButton(android.R.string.cancel, null),
        )
    }

    protected fun maybePromptRestart(
        setting: String,
        newValue: Any? = null,
        applyChange: () -> Unit,
    ) {
        val context = requireContext()
        if (!ShizukuStateMachine.isRunning() || !needsRestart(setting, newValue)) {
            applyChange()
            context.sendBroadcast(Intent(context, NotifCancelReceiver::class.java))
        } else {
            val message =
                buildString {
                    append(context.getString(R.string.settings_restart_dialog_message))
                    if (setting == KEY_TCP_MODE) {
                        append(context.getString(R.string.settings_restart_dialog_message_wifi_required))
                    }
                }
            showDialog(
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.settings_restart_dialog_title)
                    .setMessage(HtmlCompat.fromHtml(message))
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        applyChange()
                        ShizukuReceiverStarter.start(context, true)
                    }.setNegativeButton(android.R.string.cancel, null),
            )
        }
    }

    protected fun maybeToggleBatterySensitiveSetting(
        newValue: Boolean,
        onResult: (Boolean) -> Unit,
    ) {
        val context = requireContext()
        if (!newValue || SettingsHelper.isIgnoringBatteryOptimizations(context) || EnvironmentUtils.isTelevision()) {
            onResult(true)
            return
        }
        lifecycleScope.launch {
            val result =
                suspendCancellableCoroutine<Boolean> { continuation ->
                    batteryOptimizationContinuation = continuation
                    SnackbarHelper.show(
                        context,
                        requireView(),
                        msg = context.getString(R.string.snackbar_battery_optimization_settings),
                        duration = 6000,
                        actionText = context.getString(R.string.snackbar_action_fix),
                        action = { SettingsHelper.requestIgnoreBatteryOptimizations(context, batteryOptimizationListener) },
                        onDismiss = { event ->
                            if (event != Snackbar.Callback.DISMISS_EVENT_ACTION && continuation.isActive) {
                                continuation.resume(false)
                            }
                        },
                    )
                }
            onResult(result)
        }
    }

    protected fun maybeToggleSecureSetting(
        newValue: Boolean,
        onResult: (Boolean) -> Unit,
    ) {
        val context = requireContext()
        if (!newValue || SettingsHelper.hasWriteSecureSettings(context) || EnvironmentUtils.isRooted()) {
            onResult(true)
            return
        }
        SettingsHelper.promptWriteSecureSettings(context)
        onResult(false)
    }

    /**
     * M3E segmented list: every row is its own segment separated by a small gap instead of one
     * big card with hairline dividers. Outer corners of a category use the ExtraLarge token,
     * inner corners are tight, and a pressed row morphs toward a rounder shape. Each row's outline
     * is clipped to its current shape so the ripple follows the morph.
     */
    protected class SettingsItemDecoration(
        context: Context,
    ) : af.shizuku.manager.widget.M3ECardItemDecoration(context) {
        private val effectiveCornerRadius: Float =
            if (!ShizukuSettings.isRoundedEdgesEnabled()) {
                0f
            } else {
                val cornerStyle = ShizukuSettings.getPreferences()?.getString("settings_card_corner_style", "global") ?: "global"
                when (cornerStyle) {
                    "sharp" -> 0f
                    "rounded" -> 12f * density
                    "large" -> 24f * density
                    "squircle" -> 28f * density
                    "xlarge" -> 36f * density
                    else -> cornerRadius
                }
            }
        private val gap = context.resources.getDimension(R.dimen.settings_row_gap)
        private val innerRadius = if (!ShizukuSettings.isRoundedEdgesEnabled()) 0f else minOf(context.resources.getDimension(R.dimen.settings_inner_radius), effectiveCornerRadius)
        private val pressedRadius = if (!ShizukuSettings.isRoundedEdgesEnabled()) 0f else maxOf(context.resources.getDimension(R.dimen.settings_pressed_radius), effectiveCornerRadius)

        // Status banner (pos 0) gets a tinted card so it reads as "status" not "navigation".
        private val statusPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).also {
                val tv = android.util.TypedValue()
                context.theme.resolveAttribute(R.attr.colorSecondaryContainer, tv, true)
                it.color = tv.data
            }
        private val progress = java.util.WeakHashMap<View, Float>()
        private val shapes = java.util.WeakHashMap<View, FloatArray>()
        private val path = android.graphics.Path()
        private val rect = android.graphics.RectF()

        private val outlineProvider =
            object : android.view.ViewOutlineProvider() {
                override fun getOutline(
                    view: View,
                    outline: android.graphics.Outline,
                ) {
                    val r = shapes[view]
                    if (r == null) {
                        outline.setRect(0, 0, view.width, view.height)
                        return
                    }
                    val p = android.graphics.Path()
                    p.addRoundRect(
                        android.graphics.RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()),
                        floatArrayOf(r[0], r[0], r[1], r[1], r[2], r[2], r[3], r[3]),
                        android.graphics.Path.Direction.CW,
                    )
                    @Suppress("DEPRECATION")
                    outline.setConvexPath(p)
                }
            }

        override fun getItemOffsets(
            outRect: android.graphics.Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State,
        ) {
            val pos = parent.getChildAdapterPosition(view)
            if (pos == RecyclerView.NO_POSITION) return

            if (pos == 0) {
                outRect.top = 0
            } else if (followsSectionLabel(parent, pos)) {
                // Keep a section's cards tight under its label.
                outRect.top = (4 * density).toInt()
            } else if (isHeader(view) || !shouldDecorate(view) || startsGroup(parent, pos)) {
                outRect.top = context.resources.getDimensionPixelSize(R.dimen.settings_section_top_offset)
            } else {
                outRect.top = gap.toInt()
            }
        }

        override fun onDraw(
            c: android.graphics.Canvas,
            parent: RecyclerView,
            state: RecyclerView.State,
        ) {
            val adapter = parent.adapter ?: return
            var animating = false
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i) ?: continue
                if (child.visibility != View.VISIBLE || !shouldDecorate(child)) continue
                val pos = parent.getChildAdapterPosition(child)
                if (pos == RecyclerView.NO_POSITION) continue

                // A group starts at its header - or, for headerless groups (e.g. the first rows of
                // Feature Hub), at the first visible row after nothing/a non-card view. Hidden (GONE)
                // rows such as the diagnostics dashboard don't count as neighbours either way.
                val first = isHeader(child) || startsGroup(parent, pos)
                val last = endsGroup(parent, adapter.itemCount, pos)
                val top = if (first) effectiveCornerRadius else innerRadius
                val bottom = if (last) effectiveCornerRadius else innerRadius

                val target = if (child.isPressed) 1f else 0f
                val current = progress[child] ?: 0f
                // Eased toward target (not a fixed linear step) so the morph decelerates into
                // place - reads as a soft, springy squish rather than a mechanical sweep, matching
                // the M3 Expressive motion language used elsewhere (overshoot on card entrance,
                // decelerate on the live drag reflow).
                val next =
                    when {
                        current == target -> target
                        else -> {
                            val stepped = current + (target - current) * EASE_FACTOR
                            if (kotlin.math.abs(target - stepped) < SNAP_EPSILON) target else stepped
                        }
                    }
                if (next != target) animating = true
                progress[child] = next

                val maxRadius = child.height / 2f

                fun morph(base: Float) = minOf(base + (pressedRadius - base) * next, maxRadius).coerceAtLeast(minOf(base, maxRadius))
                val radii = floatArrayOf(morph(top), morph(top), morph(bottom), morph(bottom))

                val previous = shapes[child]
                if (previous == null || !previous.contentEquals(radii)) {
                    shapes[child] = radii
                    child.outlineProvider = outlineProvider
                    child.clipToOutline = true
                    child.invalidateOutline()
                }

                rect.set(
                    child.left + child.translationX,
                    child.top + child.translationY,
                    child.right + child.translationX,
                    child.bottom + child.translationY,
                )
                path.rewind()
                path.addRoundRect(
                    rect,
                    floatArrayOf(radii[0], radii[0], radii[1], radii[1], radii[2], radii[2], radii[3], radii[3]),
                    android.graphics.Path.Direction.CW,
                )
                // Position 0 is the status banner — draw it with the secondary container
                // color so it reads as "live status" rather than a plain navigation card.
                c.drawPath(path, if (pos == 0 && !isHeader(child)) statusPaint else cardPaint)
            }
            if (animating) parent.postInvalidateOnAnimation()
        }

        override fun isHeader(view: View): Boolean = view.tag == "category_header"

        override fun shouldDecorate(view: View): Boolean =
            view.tag != "standalone_card" &&
                view.tag != "section_label" &&
                view !is com.google.android.material.card.MaterialCardView

        private fun followsSectionLabel(
            parent: RecyclerView,
            pos: Int,
        ): Boolean {
            var p = pos - 1
            while (p >= 0) {
                val prev = parent.findViewHolderForAdapterPosition(p)?.itemView ?: return false
                if (prev.visibility == View.GONE) {
                    p--
                    continue
                }
                return prev.tag == "section_label"
            }
            return false
        }

        /** True when no visible card row sits directly above [pos] (unknown/off-screen => mid-group). */
        private fun startsGroup(
            parent: RecyclerView,
            pos: Int,
        ): Boolean {
            var p = pos - 1
            while (p >= 0) {
                val prev = parent.findViewHolderForAdapterPosition(p)?.itemView ?: return false
                if (prev.visibility == View.GONE) {
                    p--
                    continue
                }
                return !shouldDecorate(prev)
            }
            return true
        }

        /** True when the next visible row is a header/non-card view, or there is none. */
        private fun endsGroup(
            parent: RecyclerView,
            itemCount: Int,
            pos: Int,
        ): Boolean {
            var q = pos + 1
            while (q < itemCount) {
                val next = parent.findViewHolderForAdapterPosition(q)?.itemView ?: return false
                if (next.visibility == View.GONE) {
                    q++
                    continue
                }
                return isHeader(next) || !shouldDecorate(next)
            }
            return true
        }

        private companion object {
            /** Fraction of the remaining distance to target closed per frame - exponential ease. */
            const val EASE_FACTOR = 0.3f

            /** Below this remaining distance, snap to target instead of asymptotically crawling. */
            const val SNAP_EPSILON = 0.01f
        }
    }
}

/**
 * Safe setter for Preference summary that prevents AndroidX's
 * `IllegalStateException: Preference already has a SummaryProvider set.` (#587).
 * When dynamic code overrides a preference's summary, any pre-existing SummaryProvider
 * (from XML `app:useSimpleSummaryProvider="true"` or programmatic setup) is cleared first.
 */
fun Preference.setCustomSummary(text: CharSequence?) {
    if (this.summaryProvider != null) {
        this.summaryProvider = null
    }
    this.summary = text
}

fun Preference.setCustomSummary(
    @StringRes resId: Int,
) {
    if (this.summaryProvider != null) {
        this.summaryProvider = null
    }
    this.setSummary(resId)
}
