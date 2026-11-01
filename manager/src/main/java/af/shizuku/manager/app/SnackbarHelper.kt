package af.shizuku.manager.app

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.widget.FloatingNavBar
import android.animation.TimeInterpolator
import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.Snackbar
import java.lang.ref.WeakReference

/**
 * The app's snackbars. On a screen with the floating nav bar, the message sits just above the
 * bar, as wide as it and with its corners, and rides up and down with it when the bar autohides,
 * on the bar's own curve. MDC's anchorView is not used: it only re-measures on layout, so a bar
 * sliding away (a translation) left the message floating over nothing, and a manual margin on
 * top of the anchor lifted it twice.
 */
object SnackbarHelper {
    private var snackbar: Snackbar? = null
    private var bar: WeakReference<View>? = null

    // Gap between the message and the top of the bar.
    private const val GAP_DP = 8

    fun show(
        context: Context,
        view: View,
        msg: String,
        duration: Int = Snackbar.LENGTH_SHORT,
        actionText: String? = null,
        action: (() -> Unit)? = null,
        onDismiss: ((event: Int) -> Unit)? = null,
    ) {
        dismiss() // Dismiss any existing snackbar
        val newSnackbar = Snackbar.make(view, msg, duration).setDuration(duration)
        if (action != null) {
            newSnackbar.setAction(actionText ?: context.getString(android.R.string.ok)) { action() }
        }
        newSnackbar.addCallback(
            object : Snackbar.Callback() {
                override fun onDismissed(
                    transientBottomBar: Snackbar?,
                    event: Int,
                ) {
                    if (snackbar === transientBottomBar) snackbar = null
                    onDismiss?.invoke(event)
                }
            },
        )
        // MDC's default snackbar is the M3 spec: inverse-surface container, inverse-primary
        // action, corners from the theme's shape scale. A surface-coloured custom pill used to
        // replace it and sank into cards on AMOLED.

        // Ensure snackbar text never clips or truncates with ellipses
        val textView =
            newSnackbar.view.findViewById<android.widget.TextView>(com.google.android.material.R.id.snackbar_text)
        textView?.isSingleLine = false
        textView?.maxLines = 5
        textView?.ellipsize = null

        val liveBar =
            view.rootView?.findViewById<View>(R.id.floating_nav_root)
                ?: (context as? Activity)?.findViewById<View>(R.id.floating_nav_root)
        if (liveBar != null && ShizukuSettings.isFloatingNavBarEnabled()) {
            bar = WeakReference(liveBar)
            // Fade and scale in place: the position (translationY) is ours, to follow the bar.
            newSnackbar.animationMode = BaseTransientBottomBar.ANIMATION_MODE_FADE
            styleForBar(newSnackbar, liveBar)
        }

        // Placed once laid out (show() attaches it later), and again if its size changes.
        newSnackbar.view.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (top != oldTop || bottom != oldBottom) place(newSnackbar)
        }
        newSnackbar.show()
        snackbar = newSnackbar
    }

    /** The screen's nav bar appeared or went away (null). */
    fun attachBar(navBar: View?) {
        bar = navBar?.let { WeakReference(it) }
        snackbar?.let { place(it) }
    }

    /** The bar is sliding away ([hide]) or back; the message moves with it on the same curve. */
    fun followBar(
        navBar: View,
        hide: Boolean,
        duration: Long,
        interpolator: TimeInterpolator,
    ) {
        bar = WeakReference(navBar)
        val sb = snackbar ?: return
        val target = if (hide) 0f else -lift(sb.view, navBar)
        sb.view
            .animate()
            .translationY(target)
            .setDuration(duration)
            .setInterpolator(interpolator)
            .start()
    }

    private fun styleForBar(
        sb: Snackbar,
        navBar: View,
    ) {
        val context = sb.view.context
        (sb.view.background as? MaterialShapeDrawable)?.let {
            it.shapeAppearanceModel = it.shapeAppearanceModel.withCornerSize(FloatingNavBar.messageRadius(context))
        }
        // A floating bar: the message lines up with its edges. A docked one spans the screen,
        // so the message keeps its own width.
        if (!FloatingNavBar.isDocked() && navBar.width > 0) {
            val lp = sb.view.layoutParams
            lp.width = navBar.width
            if (lp is ViewGroup.MarginLayoutParams) {
                lp.leftMargin = 0
                lp.rightMargin = 0
            }
            if (lp is FrameLayout.LayoutParams) lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            sb.view.layoutParams = lp
        }
    }

    /** Puts the message above the bar when it is showing, at its own resting place otherwise. */
    private fun place(sb: Snackbar) {
        val navBar = bar?.get()
        val shown = navBar != null && navBar.isAttachedToWindow && navBar.visibility == View.VISIBLE && navBar.translationY == 0f
        sb.view.translationY = if (shown) -lift(sb.view, navBar!!) else 0f
    }

    /** How far the message, at rest, must rise to clear the top of the bar, ignoring both translations. */
    private fun lift(
        message: View,
        navBar: View,
    ): Float {
        if (message.height == 0 || navBar.height == 0) return 0f
        // Layout positions through the parents, so the message's fade-in scale and either
        // translation don't skew it.
        val messageParent = message.parent as? View ?: return 0f
        val barParent = navBar.parent as? View ?: return 0f
        val mp = IntArray(2).also { messageParent.getLocationInWindow(it) }
        val bp = IntArray(2).also { barParent.getLocationInWindow(it) }
        val messageBottom = (mp[1] + message.bottom).toFloat()
        val barTop = (bp[1] + navBar.top).toFloat()
        val gap = GAP_DP * message.resources.displayMetrics.density
        return (messageBottom - barTop + gap).coerceAtLeast(0f)
    }

    fun dismiss() {
        snackbar?.dismiss()
        snackbar = null
    }
}
