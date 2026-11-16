package af.shizuku.manager.home

import af.shizuku.manager.R
import android.content.Context
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors

/**
 * The status card's outline colour. "status" = semantic green / red, harmonized with the theme;
 * anything else = the theme's primary / error roles. Shared by the card and its setting, so the
 * setting previews exactly what the card shows.
 */
object StatusOutlineColors {
    fun color(
        context: Context,
        running: Boolean,
        style: String,
    ): Int {
        val semantic = ContextCompat.getColor(context, if (running) R.color.status_ok else R.color.status_error)
        return if (style == "status") {
            MaterialColors.harmonizeWithPrimary(context, semantic)
        } else {
            MaterialColors.getColor(context, if (running) R.attr.colorPrimary else R.attr.colorError, semantic)
        }
    }
}
