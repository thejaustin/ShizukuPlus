package af.shizuku.manager.home

import af.shizuku.manager.R
import af.shizuku.manager.databinding.HomeItemContainerBinding
import af.shizuku.manager.utils.CardBorder
import af.shizuku.manager.utils.MotionUtils.applySpringTouch
import af.shizuku.manager.utils.MotionUtils.cancelSpring
import af.shizuku.manager.utils.MotionUtils.springTo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.core.graphics.ColorUtils
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.recyclerview.widget.RecyclerView

object HomeEditMode {
    /** Drag handle / remove button appearing: grows in on the spatial spring, fades in without overshoot. */
    private fun springIn(view: android.view.View) {
        listOf(SpringAnimation.SCALE_X, SpringAnimation.SCALE_Y, SpringAnimation.ALPHA).forEach { view.cancelSpring(it) }
        view.scaleX = 0.5f
        view.scaleY = 0.5f
        view.alpha = 0f
        view.springTo(SpringAnimation.SCALE_X, 1f, com.google.android.material.R.attr.motionSpringFastSpatial)
        view.springTo(SpringAnimation.SCALE_Y, 1f, com.google.android.material.R.attr.motionSpringFastSpatial)
        view.springTo(SpringAnimation.ALPHA, 1f, com.google.android.material.R.attr.motionSpringFastEffects)
    }

    /** Leaving: shrinks and fades on the no-bounce effects spring, then hides and resets. */
    private fun springOut(view: android.view.View) {
        val effects = com.google.android.material.R.attr.motionSpringFastEffects
        view.springTo(SpringAnimation.SCALE_X, 0.5f, effects)
        view.springTo(SpringAnimation.SCALE_Y, 0.5f, effects)
        view.springTo(SpringAnimation.ALPHA, 0f, effects) {
            view.cancelSpring(SpringAnimation.SCALE_X)
            view.cancelSpring(SpringAnimation.SCALE_Y)
            view.isVisible = false
            view.scaleX = 1f
            view.scaleY = 1f
            view.alpha = 1f
        }
    }

    var isActive: Boolean = false
        private set

    var wasForced: Boolean = false
        private set

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    var onChanged: (() -> Unit)?
        get() = listeners.firstOrNull()
        set(value) {
            if (value != null && !listeners.contains(value)) {
                listeners.add(value)
            }
        }

    private fun notifyChanged() {
        listeners.forEach { runCatching { it.invoke() } }
    }

    var startDragCallback: ((RecyclerView.ViewHolder) -> Unit)? = null
    var removeCardCallback: ((Long) -> Unit)? = null

    /** Moves the card hosting the given view by [delta] positions (-1 up, +1 down); false if not possible. */
    var moveCardCallback: ((android.view.View, Int) -> Boolean)? = null

    /**
     * The gestures every draggable Home card shares: hold the card or its handle to edit the
     * layout, press the handle to drag, springy touch feedback. The system long-press haptic is
     * off on both, since the drag that follows plays its own and they buzzed twice together.
     */
    fun bindCardGestures(
        container: HomeItemContainerBinding,
        holder: RecyclerView.ViewHolder,
    ) {
        val enterEditMode =
            android.view.View.OnLongClickListener {
                enter()
                true
            }
        container.root.apply {
            applySpringTouch()
            isHapticFeedbackEnabled = false
            setOnLongClickListener(enterEditMode)
        }
        container.dragHandle.apply {
            isHapticFeedbackEnabled = false
            setOnLongClickListener(enterEditMode)
            setOnTouchListener { _, event ->
                if (event.action == android.view.MotionEvent.ACTION_DOWN) startDragCallback?.invoke(holder)
                false
            }
        }
    }

    @JvmOverloads
    fun enter(force: Boolean = false) {
        if (!force &&
            af.shizuku.manager.ShizukuSettings
                .isHomeLayoutLocked()
        ) {
            return
        }
        if (force) {
            wasForced = true
        }
        if (!isActive) {
            isActive = true
            io.sentry.Sentry.addBreadcrumb("HomeEditMode: enter()")
            notifyChanged()
        }
    }

    fun exit() {
        wasForced = false
        if (isActive) {
            isActive = false
            notifyChanged()
        }
    }

    fun toggle() {
        if (isActive) exit() else enter()
    }

    /**
     * Lightweight overlay apply for mid-drag use — finds handle/remove views directly by ID and
     * shows them with a spring animation. Backgrounds and tints are already set at bind time, so
     * only visibility + animation needs to be handled here.
     */
    fun applyOverlayDirect(itemView: android.view.View) {
        (itemView as? com.google.android.material.card.MaterialCardView)?.let { CardBorder.apply(it) }
        val removeBtn = itemView.findViewById<android.view.View>(R.id.remove_btn) ?: return
        val dragHandle = itemView.findViewById<android.view.View>(R.id.drag_handle) ?: return
        val wasVisible = removeBtn.isVisible
        if (isActive) {
            removeBtn.isVisible = true
            dragHandle.isVisible = true
        }
        if (isActive && !wasVisible) {
            listOf(dragHandle, removeBtn).forEach(::springIn)
        } else if (!isActive && wasVisible) {
            listOf(dragHandle, removeBtn).forEach(::springOut)
        } else if (!isActive) {
            removeBtn.isVisible = false
            dragHandle.isVisible = false
        }
    }

    /** Toggle drag handle / remove button visibility AND reserve end-padding so
     *  the overlay icons don't sit on top of card title/summary text. */
    fun applyOverlay(binding: HomeItemContainerBinding) {
        CardBorder.apply(binding.root)
        val wasVisible = binding.removeBtn.isVisible
        if (isActive) {
            binding.removeBtn.isVisible = true
            binding.dragHandle.isVisible = true
        }

        // Spring-in when controls first appear; scale-out when they leave.
        if (isActive && !wasVisible) {
            listOf(binding.dragHandle, binding.removeBtn).forEach(::springIn)
        } else if (!isActive && wasVisible) {
            listOf(binding.dragHandle, binding.removeBtn).forEach(::springOut)
        } else if (!isActive) {
            binding.removeBtn.isVisible = false
            binding.dragHandle.isVisible = false
        }

        val isHidden = binding.root.tag as? Boolean ?: false
        val ctx = binding.root.context
        val density = binding.root.resources.displayMetrics.density

        val defaultPadding = (20 * density).toInt()
        val endPadding = if (isActive) (116 * density).toInt() else defaultPadding
        binding.cardContent.setPaddingRelative(
            defaultPadding,
            binding.cardContent.paddingTop,
            endPadding,
            binding.cardContent.paddingBottom,
        )

        fun attrColor(attr: Int): Int {
            val tv = android.util.TypedValue()
            ctx.theme.resolveAttribute(attr, tv, true)
            return tv.data
        }

        val onSurfaceVariant = attrColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        val outlineVariant = attrColor(com.google.android.material.R.attr.colorOutlineVariant)

        // Darkened, almost-hollow button (subtle hairline outline + darkened translucent fill)
        // so it stays unobtrusive and never clashes or overwhelms in contrast.
        fun darkenedHollowChip(alphaMultiplier: Float = 1f): GradientDrawable {
            val strokeAlpha = (110 * alphaMultiplier).toInt().coerceIn(0, 255)
            val fillAlpha = (0x33 * alphaMultiplier).toInt().coerceIn(0, 255)
            return GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16f * density
                setStroke((1 * density).toInt().coerceAtLeast(1), ColorUtils.setAlphaComponent(outlineVariant, strokeAlpha))
                setColor(Color.argb(fillAlpha, 0, 0, 0))
            }
        }

        val alphaMultiplier = if (isHidden) 0.6f else 1f
        val chipBg = darkenedHollowChip(alphaMultiplier)
        val fgColor = if (isHidden) ColorUtils.setAlphaComponent(onSurfaceVariant, 130) else onSurfaceVariant
        val fgTintList = ColorStateList.valueOf(fgColor)

        if (isActive && isHidden) {
            binding.cardContent.alpha = 0.45f
            binding.dragHandle.alpha = 0.35f
            binding.removeBtn.setImageResource(R.drawable.ic_visibility_off_24)
            binding.removeBtn.contentDescription = ctx.getString(R.string.accessibility_card_show)
        } else {
            binding.cardContent.alpha = 1.0f
            binding.dragHandle.alpha = 0.85f
            binding.removeBtn.setImageResource(R.drawable.ic_visibility_24)
            binding.removeBtn.contentDescription = ctx.getString(R.string.accessibility_card_hide)
        }

        // Dragging is not possible with TalkBack/switch access; offer move actions on the card and drag handle.
        val a11yDelegate =
            object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: android.view.View,
                    info: AccessibilityNodeInfoCompat,
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    if (!isActive) return
                    info.addAction(
                        AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                            R.id.action_move_card_up,
                            host.context.getString(R.string.accessibility_move_card_up),
                        ),
                    )
                    info.addAction(
                        AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                            R.id.action_move_card_down,
                            host.context.getString(R.string.accessibility_move_card_down),
                        ),
                    )
                }

                override fun performAccessibilityAction(
                    host: android.view.View,
                    action: Int,
                    args: android.os.Bundle?,
                ): Boolean {
                    if (isActive) {
                        val delta =
                            when (action) {
                                R.id.action_move_card_up -> -1
                                R.id.action_move_card_down -> 1
                                else -> 0
                            }
                        if (delta != 0) return moveCardCallback?.invoke(binding.root, delta) ?: false
                    }
                    return super.performAccessibilityAction(host, action, args)
                }
            }
        ViewCompat.setAccessibilityDelegate(binding.root, a11yDelegate)
        ViewCompat.setAccessibilityDelegate(binding.dragHandle, a11yDelegate)
        binding.dragHandle.isFocusable = isActive

        binding.removeBtn.background = chipBg
        binding.removeBtn.imageTintList = fgTintList

        // Drag handle: matching darkened hollow chip and on-surface-variant tint
        binding.dragHandle.background = darkenedHollowChip(alphaMultiplier)
        binding.dragHandle.imageTintList = fgTintList
    }
}
