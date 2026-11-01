package af.shizuku.manager.settings

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.utils.MotionUtils.applySpringTouch
import af.shizuku.manager.utils.MotionUtils.springTo
import af.shizuku.manager.utils.springTo
import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.R as MaterialR

open class GrayableIconSwitchPreference
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : SwitchPreferenceCompat(context, attrs) {
        private var lastItemView: java.lang.ref.WeakReference<android.view.View>? = null

        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            lastItemView = java.lang.ref.WeakReference(holder.itemView)
            // Rows are recycled between preferences: remember who owns this view now, so a click
            // never animates a row that has since been bound to another setting.
            holder.itemView.setTag(R.id.tag_switch_row_owner, this)
            holder.itemView.applySpringTouch(scale = 0.985f)

            // Standard M3E switch: check / close icon in the thumb, whatever the row.
            (holder.findViewById(R.id.switchWidget) as? MaterialSwitch)?.setThumbIconResource(R.drawable.m3_switch_thumb_icon)

            holder.itemView
                .findViewById<ImageView>(android.R.id.icon)
                ?.apply {
                    alpha = if (isChecked) 1.0f else OFF_ICON_ALPHA
                    scaleX = if (isChecked) 1.0f else 0.90f
                    scaleY = if (isChecked) 1.0f else 0.90f
                    val showShape = !ShizukuSettings.isIconMorphOnToggle() || isChecked
                    morphShapeOf(this)?.progress = if (showShape) 1f else 0f
                    roundedContainerOf(this)?.let { setContainerRound(it, if (showShape) 0f else 1f) }
                }
        }

        override fun onClick() {
            val willBeChecked = !isChecked
            super.onClick()
            if (isChecked == willBeChecked) {
                val row = lastItemView?.get()
                if (row == null || row.getTag(R.id.tag_switch_row_owner) !== this) {
                    // Our last view now belongs to another row: rebind ours instead of animating it.
                    notifyChanged()
                    return
                }
                row.let { v ->
                    if (isChecked) {
                        af.shizuku.manager.utils.HapticUtils
                            .toggleOn(v)
                    } else {
                        af.shizuku.manager.utils.HapticUtils
                            .toggleOff(v)
                    }
                    // Icon container morphs circle <-> chosen M3E shape on a spring.
                    v.findViewById<ImageView>(android.R.id.icon)?.let { iv ->
                        val morph = morphShapeOf(iv)
                        if (morph != null && ShizukuSettings.isIconMorphOnToggle()) {
                            morph.springTo(if (isChecked) 1f else 0f)
                        }
                        // Without an icon shape picked: the Shape style's rounded square
                        // rounds into a circle when off, on the same spring feel.
                        val rounded = roundedContainerOf(iv)
                        if (rounded != null && ShizukuSettings.isIconMorphOnToggle()) {
                            springContainerRound(iv, rounded, if (isChecked) 0f else 1f)
                        }
                    }
                    // Icon scale springs (may bounce); its dimming fades without overshoot.
                    v.findViewById<ImageView>(android.R.id.icon)?.let { icon ->
                        val scale = if (isChecked) 1.0f else 0.90f
                        icon.springTo(SpringAnimation.SCALE_X, scale, MaterialR.attr.motionSpringFastSpatial)
                        icon.springTo(SpringAnimation.SCALE_Y, scale, MaterialR.attr.motionSpringFastSpatial)
                        icon.springTo(SpringAnimation.ALPHA, if (isChecked) 1.0f else OFF_ICON_ALPHA, MaterialR.attr.motionSpringFastEffects)
                    }
                }
            }
        }
    }

/**
 * An off row's icon: dimmer, but well above the 38% M3 uses for disabled, so it never reads as
 * unavailable. With an icon shape picked, the container also morphs circle -> shape when on.
 */
private const val OFF_ICON_ALPHA = 0.6f

/**
 * The rounded-rectangle background layer of a two-tone icon (the Shape style's pill when no icon
 * shape is picked), if it has rounded corners to morph. Oval and sharp containers have none.
 */
private fun roundedContainerOf(iv: ImageView): android.graphics.drawable.GradientDrawable? {
    val bg =
        (iv.drawable as? android.graphics.drawable.LayerDrawable)?.getDrawable(0) as? android.graphics.drawable.GradientDrawable
            ?: return null
    if (bg.shape != android.graphics.drawable.GradientDrawable.RECTANGLE) return null
    // The style's own radius, read once before any morph changes it.
    val base = (iv.getTag(R.id.tag_icon_container_radius) as? Float) ?: bg.cornerRadius.also { iv.setTag(R.id.tag_icon_container_radius, it) }
    return bg.takeIf { base > 0f }
}

/** [round] 0 = the style's corner radius, 1 = a circle. */
private fun setContainerRound(
    bg: android.graphics.drawable.GradientDrawable,
    round: Float,
    base: Float,
) {
    val b = bg.bounds
    val circle = if (b.isEmpty) base * 4f else minOf(b.width(), b.height()) / 2f
    bg.cornerRadius = base + (maxOf(circle, base) - base) * round.coerceIn(0f, 1f)
}

private fun ImageView.setContainerRound(
    bg: android.graphics.drawable.GradientDrawable,
    round: Float,
) {
    val base = getTag(R.id.tag_icon_container_radius) as? Float ?: return
    setTag(R.id.tag_icon_container_round, round)
    setContainerRound(bg, round, base)
}

/** Springs the container between its style's radius and a circle, like the shape morph. */
private fun springContainerRound(
    iv: ImageView,
    bg: android.graphics.drawable.GradientDrawable,
    target: Float,
) {
    if (ShizukuSettings.getAnimationDurationScale() <= 0f) {
        iv.setContainerRound(bg, target)
        return
    }
    (iv.getTag(R.id.tag_icon_container_spring) as? SpringAnimation)?.cancel()
    val holder =
        androidx.dynamicanimation.animation
            .FloatValueHolder((iv.getTag(R.id.tag_icon_container_round) as? Float) ?: (1f - target))
    SpringAnimation(holder)
        .setSpring(
            androidx.dynamicanimation.animation.SpringForce(target).apply {
                dampingRatio = androidx.dynamicanimation.animation.SpringForce.DAMPING_RATIO_NO_BOUNCY
                stiffness = androidx.dynamicanimation.animation.SpringForce.STIFFNESS_LOW
            },
        ).addUpdateListener { _, value, _ -> iv.setContainerRound(bg, value) }
        .also { iv.setTag(R.id.tag_icon_container_spring, it) }
        .start()
}

/** The morphing background layer of a two-tone icon, if the icon uses one. */
private fun morphShapeOf(iv: ImageView): af.shizuku.manager.utils.MorphShapeDrawable? =
    ((iv.drawable as? android.graphics.drawable.LayerDrawable)?.getDrawable(0)) as? af.shizuku.manager.utils.MorphShapeDrawable
