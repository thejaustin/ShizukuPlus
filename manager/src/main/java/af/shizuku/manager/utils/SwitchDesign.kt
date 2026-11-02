package af.shizuku.manager.utils

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import android.graphics.drawable.Drawable
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * The experimental switch designs that apply to any MaterialSwitch, so Settings rows and the app
 * list look the same. "Icon is the switch" needs an icon container and stays with settings rows.
 */
object SwitchDesign {
    const val STANDARD = "standard"
    const val ICON_SWITCH = "icon_switch"
    const val SHAPE_THUMB = "shape_thumb"

    private object NoDrawable

    /** Gives [switch] the shape thumb when that design is on, or puts its own thumb back. */
    fun applyThumb(
        switch: MaterialSwitch,
        onKey: String? = null,
        offKey: String? = null,
    ) {
        if (ShizukuSettings.getSwitchRowDesign() == SHAPE_THUMB) {
            if (switch.getTag(R.id.tag_switch_default_thumb) == null) {
                switch.setTag(R.id.tag_switch_default_thumb, switch.thumbDrawable ?: NoDrawable)
            }
            switch.thumbDrawable = shapeThumb(switch, onKey, offKey)
        } else {
            val defaultThumb = switch.getTag(R.id.tag_switch_default_thumb)
            if (defaultThumb != null && defaultThumb !== NoDrawable) {
                switch.thumbDrawable = defaultThumb as? Drawable
            }
        }
        // Standard M3E switch: check / close icon in the thumb.
        switch.setThumbIconResource(R.drawable.m3_switch_thumb_icon)
    }

    /**
     * A thumb drawn in the icon shapes: the off shape while off, morphing into the on shape as it
     * slides (MaterialSwitch tints it and passes the checked state), and smaller while off like the
     * M3 thumb. The check / close icon still sits on top.
     */
    private fun shapeThumb(
        switch: MaterialSwitch,
        onKey: String?,
        offKey: String?,
    ): Drawable {
        val density = switch.resources.displayMetrics.density
        val picked = ShizukuSettings.getIconMorphShape().takeIf { it != ExpressiveShapes.OFF }
        return MorphShapeDrawable(
            MaterialColors.getColor(switch, com.google.android.material.R.attr.colorOnPrimary),
            onKey ?: picked ?: ExpressiveShapes.rectKey(0.5f),
            offKey ?: ShizukuSettings.getIconMorphOffShape(),
        ).apply {
            mode = MorphMode.of(ShizukuSettings.getIconMorphMode())
            intrinsicSizePx = (24 * density).toInt()
            shrinkWhenOff = true
            followsCheckedState = true
        }
    }
}
