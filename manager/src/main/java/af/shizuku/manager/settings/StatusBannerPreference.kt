package af.shizuku.manager.settings

import android.content.Context
import android.util.AttributeSet
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.color.MaterialColors

/**
 * The status row at the top of Settings. BaseSettingsFragment's card decoration paints it with
 * colorSecondaryContainer, so its text uses the matching "on" colour: with high-contrast schemes
 * that container is light even in a dark theme, and the usual surface text colours turned
 * unreadable on it (#687).
 */
class StatusBannerPreference
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : Preference(context, attrs) {
        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            val on = MaterialColors.getColor(holder.itemView, com.google.android.material.R.attr.colorOnSecondaryContainer)
            (holder.findViewById(android.R.id.title) as? TextView)?.setTextColor(on)
            (holder.findViewById(android.R.id.summary) as? TextView)?.setTextColor(on)
        }
    }
