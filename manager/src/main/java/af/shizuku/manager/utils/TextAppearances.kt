package af.shizuku.manager.utils

import android.util.TypedValue
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.core.widget.TextViewCompat

/**
 * Applies a theme type-scale role (e.g. textAppearanceTitleLarge) to a view built in code, so it
 * matches the XML layouts and the theme's font. Call before setting a colour: it resets it.
 */
fun TextView.useTextAppearance(
    @AttrRes attr: Int,
) {
    val value = TypedValue()
    if (context.theme.resolveAttribute(attr, value, true) && value.resourceId != 0) {
        TextViewCompat.setTextAppearance(this, value.resourceId)
    }
}
