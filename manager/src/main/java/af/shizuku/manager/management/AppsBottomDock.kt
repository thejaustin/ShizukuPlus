package af.shizuku.manager.management

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.utils.HapticUtils
import af.shizuku.manager.utils.MotionUtils
import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors

/**
 * "Search and filters at the bottom" (Personalization): the app list's search, filters and sort
 * move from the top app bar into a dock that floats above the navigation bar, within thumb reach.
 * While apps are selected the dock shows the selection actions instead, which otherwise sit in the
 * toolbar's overflow menu. The controls keep their views and ids, so the activity's listeners
 * work unchanged.
 */
class AppsBottomDock(
    private val activity: Activity,
    coordinator: CoordinatorLayout,
    private val controls: View,
    private val list: RecyclerView,
    /** Runs a selection action by the toolbar menu id it mirrors (10 select all ... 14 unhide). */
    private val onSelectionAction: (Int) -> Unit,
) {
    private val density = activity.resources.displayMetrics.density

    private fun dp(v: Int) = (v * density).toInt()

    val dock = MaterialCardView(activity)
    private val selectionRow: LinearLayout
    private val selectionCount: TextView
    private val hideButton: MaterialButton

    /** Distance from the window's bottom edge to the dock's bottom edge at rest. */
    private var baseBottom = 0
    private var hidden = false
    private var imeLift = 0
    private var inSelection = false

    /** How far up from the window's bottom edge the dock reaches; the list pads past it. */
    val reach: Int get() = baseBottom + dock.height

    init {
        (controls.parent as? ViewGroup)?.removeView(controls)
        selectionCount =
            TextView(activity).apply {
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
                setPadding(dp(20), dp(10), dp(20), 0)
            }
        hideButton = actionButton(R.string.app_management_bulk_hide, 13)
        selectionRow =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                addView(selectionCount)
                addView(
                    LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(dp(8), dp(2), dp(8), dp(6))
                        for (b in listOf(
                            actionButton(R.string.app_management_bulk_select_all, 10),
                            actionButton(R.string.app_management_bulk_grant, 11),
                            actionButton(R.string.app_management_bulk_revoke, 12),
                            hideButton,
                        )) {
                            addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        }
                    },
                )
            }
        dock.apply {
            radius = dp(28).toFloat()
            cardElevation = dp(3).toFloat()
            strokeWidth = 0
            setCardBackgroundColor(
                MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerHigh),
            )
            addView(
                LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(4), 0, dp(4))
                    addView(controls)
                    addView(selectionRow)
                },
            )
        }
        coordinator.addView(
            dock,
            CoordinatorLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM
                marginStart = dp(12)
                marginEnd = dp(12)
            },
        )
        dock.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            // The list pads past the dock; tell it when the dock's height changes.
            if (bottom - top != oldBottom - oldTop) ViewCompat.requestApplyInsets(list)
        }
        watchKeyboard()
        watchScrolling()
    }

    private fun actionButton(
        label: Int,
        id: Int,
    ) = MaterialButton(activity, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
        setText(label)
        maxLines = 1
        setOnClickListener {
            HapticUtils.tap(it)
            onSelectionAction(id)
        }
    }

    /**
     * Places the dock above the floating navigation pill when this screen shows one, otherwise
     * above the gesture/navigation bar ([systemBottom]). Called on every insets pass.
     */
    fun place(systemBottom: Int) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val nav = content?.findViewById<View>(R.id.floating_nav_root)
        val navReach =
            if (nav != null && nav.visibility == View.VISIBLE && nav.height > 0) content.height - nav.top else 0
        val newBase = maxOf(systemBottom, navReach) + dp(8)
        if (newBase != baseBottom) {
            baseBottom = newBase
            (dock.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = baseBottom
            dock.requestLayout()
        }
        // The pill may not be laid out yet on the first pass; measure again once it is.
        if (nav != null && nav.height == 0) nav.post { place(systemBottom) }
    }

    /** Shows the selection actions instead of the search and filters while apps are selected. */
    fun showSelection(
        selecting: Boolean,
        count: Int,
        hiddenFilter: Boolean,
    ) {
        selectionCount.text = activity.getString(R.string.app_management_selected_count, count)
        if (selecting == inSelection) return
        inSelection = selecting
        hideButton.setText(if (hiddenFilter) R.string.app_management_undo else R.string.app_management_bulk_hide)
        hideButton.setOnClickListener {
            HapticUtils.tap(it)
            onSelectionAction(if (hiddenFilter) 14 else 13)
        }
        val duration = ShizukuSettings.scaledAnimationDuration(250)
        if (duration > 0) {
            TransitionManager.beginDelayedTransition(
                dock,
                AutoTransition().setDuration(duration).setInterpolator(MotionUtils.EMPHASIZED_DECELERATE),
            )
        }
        controls.visibility = if (selecting) View.GONE else View.VISIBLE
        selectionRow.visibility = if (selecting) View.VISIBLE else View.GONE
        if (selecting) slide(hide = false)
    }

    private fun applyTranslation() {
        val hideBy = if (hidden) (baseBottom + dock.height).toFloat() else 0f
        dock.translationY = hideBy - imeLift
    }

    // Edge to edge, so the window doesn't resize for the keyboard: lift the dock above it, in step
    // with the keyboard's own animation.
    private fun watchKeyboard() {
        fun lift(insets: WindowInsetsCompat) {
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            imeLift = maxOf(0, ime + dp(8) - baseBottom)
            applyTranslation()
        }
        ViewCompat.setOnApplyWindowInsetsListener(dock) { _, insets ->
            lift(insets)
            insets
        }
        ViewCompat.setWindowInsetsAnimationCallback(
            dock,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_STOP) {
                override fun onProgress(
                    insets: WindowInsetsCompat,
                    running: List<WindowInsetsAnimationCompat>,
                ): WindowInsetsCompat {
                    lift(insets)
                    return insets
                }
            },
        )
    }

    // Same rules as the floating navigation bar: slide away on a real scroll down, come back on a
    // scroll up or at the end of the list, never with TalkBack, never while typing or selecting.
    private fun watchScrolling() {
        val autohide =
            ShizukuSettings.isFloatingNavAutohideEnabled() &&
                activity.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled != true
        if (!autohide) return
        list.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(
                    rv: RecyclerView,
                    dx: Int,
                    dy: Int,
                ) {
                    val canScrollDown = rv.canScrollVertically(1)
                    when {
                        !canScrollDown -> slide(hide = false)
                        dy > 12 && !inSelection && !controls.hasFocus() -> slide(hide = true)
                        dy < -12 -> slide(hide = false)
                    }
                }
            },
        )
    }

    private fun slide(hide: Boolean) {
        if (hide == hidden) return
        hidden = hide
        val hideBy = if (hide) (baseBottom + dock.height).toFloat() else 0f
        dock
            .animate()
            .translationY(hideBy - imeLift)
            .alpha(if (hide) 0f else 1f)
            .setDuration(ShizukuSettings.scaledAnimationDuration(if (hide) 200L else 280L))
            .setInterpolator(if (hide) MotionUtils.EMPHASIZED_ACCELERATE else MotionUtils.EMPHASIZED_DECELERATE)
            .start()
    }
}
