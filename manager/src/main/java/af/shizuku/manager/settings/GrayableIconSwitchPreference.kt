package af.shizuku.manager.settings

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.utils.MorphMode
import af.shizuku.manager.utils.MorphShapeDrawable
import af.shizuku.manager.utils.MotionUtils.applySpringTouch
import af.shizuku.manager.utils.MotionUtils.springTo
import af.shizuku.manager.utils.springTo
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.R as MaterialR

open class GrayableIconSwitchPreference
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : SwitchPreferenceCompat(context, attrs) {
        private var lastItemView: java.lang.ref.WeakReference<View>? = null

        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            lastItemView = java.lang.ref.WeakReference(holder.itemView)
            // Rows are recycled between preferences: remember who owns this view now, so a click
            // never animates a row that has since been bound to another setting.
            holder.itemView.setTag(R.id.tag_switch_row_owner, this)
            holder.itemView.applySpringTouch(scale = 0.985f)

            val icon = holder.itemView.findViewById<ImageView>(android.R.id.icon)
            val morph = icon?.let { morphShapeOf(it) }
            // "Icon is the switch" needs a shape container to fill; rows without one keep a switch.
            val design = ShizukuSettings.getSwitchRowDesign()
            val iconSwitch = design == DESIGN_ICON_SWITCH && morph != null
            bindSwitch(holder, if (iconSwitch) DESIGN_ICON_SWITCH else design, morph)

            if (icon != null) {
                val showShape = !ShizukuSettings.isIconMorphOnToggle() || isChecked
                morph?.mode = MorphMode.of(ShizukuSettings.getIconMorphMode())
                morph?.progress = if (showShape) 1f else 0f
                if (iconSwitch) {
                    // The hollow-or-filled container carries the state, so no dimming.
                    morph!!.strokeColor = MaterialColors.getColor(icon, MaterialR.attr.colorOutline)
                    morph.strokeWidthPx = 1.5f * icon.resources.displayMetrics.density
                    morph.fillFraction = if (isChecked) 1f else 0f
                    icon.alpha = 1f
                    icon.scaleX = 1f
                    icon.scaleY = 1f
                } else {
                    morph?.fillFraction = 1f
                    icon.alpha = if (isChecked) 1.0f else OFF_ICON_ALPHA
                    icon.scaleX = if (isChecked) 1.0f else 0.90f
                    icon.scaleY = if (isChecked) 1.0f else 0.90f
                }
            }
        }

        /** The trailing control for [design]: the M3E switch, a shape thumb, or an On/Off label. */
        private fun bindSwitch(
            holder: PreferenceViewHolder,
            design: String,
            morph: MorphShapeDrawable?,
        ) {
            val switchView = holder.findViewById(R.id.switchWidget) as? MaterialSwitch ?: return
            val frame = switchView.parent as? ViewGroup
            var label = frame?.findViewWithTag<TextView>(STATE_LABEL_TAG)
            when (design) {
                DESIGN_ICON_SWITCH -> {
                    switchView.visibility = View.GONE
                    if (label == null && frame != null) {
                        label =
                            TextView(frame.context).apply {
                                tag = STATE_LABEL_TAG
                                setTextAppearance(MaterialR.style.TextAppearance_Material3_LabelMedium)
                            }
                        frame.addView(label)
                    }
                    label?.visibility = View.VISIBLE
                    label?.let { updateStateLabel(it, isChecked) }
                    // The switch is gone, so the row itself reports the switch and its state.
                    ViewCompat.setAccessibilityDelegate(
                        holder.itemView,
                        object : AccessibilityDelegateCompat() {
                            override fun onInitializeAccessibilityNodeInfo(
                                host: View,
                                info: AccessibilityNodeInfoCompat,
                            ) {
                                super.onInitializeAccessibilityNodeInfo(host, info)
                                info.className = android.widget.Switch::class.java.name
                                info.isCheckable = true
                                info.isChecked = isChecked
                            }
                        },
                    )
                }
                else -> {
                    switchView.visibility = View.VISIBLE
                    label?.visibility = View.GONE
                    ViewCompat.setAccessibilityDelegate(holder.itemView, null)
                }
            }
            af.shizuku.manager.utils.SwitchDesign
                .applyThumb(switchView, morph?.shapeKey, morph?.offKey)
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
                if (isChecked) {
                    af.shizuku.manager.utils.HapticUtils
                        .toggleOn(row)
                } else {
                    af.shizuku.manager.utils.HapticUtils
                        .toggleOff(row)
                }
                row.findViewWithTag<TextView>(STATE_LABEL_TAG)?.let { updateStateLabel(it, isChecked) }
                val icon = row.findViewById<ImageView>(android.R.id.icon) ?: return
                val morph = morphShapeOf(icon)
                // Off shape <-> on shape, moving in the chosen morph mode.
                if (morph != null && ShizukuSettings.isIconMorphOnToggle()) {
                    morph.mode = MorphMode.of(ShizukuSettings.getIconMorphMode())
                    morph.springTo(if (isChecked) 1f else 0f)
                }
                if (morph != null && ShizukuSettings.getSwitchRowDesign() == DESIGN_ICON_SWITCH) {
                    animateFill(morph, if (isChecked) 1f else 0f)
                } else {
                    // Icon scale springs (may bounce); its dimming fades without overshoot.
                    val scale = if (isChecked) 1.0f else 0.90f
                    icon.springTo(SpringAnimation.SCALE_X, scale, MaterialR.attr.motionSpringFastSpatial)
                    icon.springTo(SpringAnimation.SCALE_Y, scale, MaterialR.attr.motionSpringFastSpatial)
                    icon.springTo(SpringAnimation.ALPHA, if (isChecked) 1.0f else OFF_ICON_ALPHA, MaterialR.attr.motionSpringFastEffects)
                }
            }
        }
    }

/**
 * An off row's icon: dimmer, but well above the 38% M3 uses for disabled, so it never reads as
 * unavailable. The container also morphs from the off shape to the on shape when switched on.
 */
private const val OFF_ICON_ALPHA = 0.6f

private const val DESIGN_ICON_SWITCH = "icon_switch"
private const val STATE_LABEL_TAG = "switch_state_label"

private fun updateStateLabel(
    label: TextView,
    checked: Boolean,
) {
    label.setText(if (checked) R.string.switch_state_on else R.string.switch_state_off)
    label.setTextColor(
        MaterialColors.getColor(label, if (checked) androidx.appcompat.R.attr.colorPrimary else MaterialR.attr.colorOnSurfaceVariant),
    )
}

/** Fades the container between an outline (0) and filled (1). */
private fun animateFill(
    morph: MorphShapeDrawable,
    target: Float,
) {
    val duration = ShizukuSettings.scaledAnimationDuration(200)
    if (duration <= 0L) {
        morph.fillFraction = target
        return
    }
    ValueAnimator.ofFloat(morph.fillFraction, target).apply {
        this.duration = duration
        interpolator = af.shizuku.manager.utils.MotionUtils.EMPHASIZED_DECELERATE
        addUpdateListener { morph.fillFraction = it.animatedValue as Float }
        start()
    }
}

/** The morphing background layer of a two-tone icon, if the icon uses one. */
private fun morphShapeOf(iv: ImageView): MorphShapeDrawable? = ((iv.drawable as? android.graphics.drawable.LayerDrawable)?.getDrawable(0)) as? MorphShapeDrawable
