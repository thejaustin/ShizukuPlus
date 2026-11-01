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
                    morphShapeOf(this)?.progress = if (!ShizukuSettings.isIconMorphOnToggle() || isChecked) 1f else 0f
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

/** The morphing background layer of a two-tone icon, if the icon uses one. */
private fun morphShapeOf(iv: ImageView): af.shizuku.manager.utils.MorphShapeDrawable? =
    ((iv.drawable as? android.graphics.drawable.LayerDrawable)?.getDrawable(0)) as? af.shizuku.manager.utils.MorphShapeDrawable
