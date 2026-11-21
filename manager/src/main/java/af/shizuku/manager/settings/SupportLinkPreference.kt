package af.shizuku.manager.settings

import af.shizuku.manager.R
import af.shizuku.manager.utils.CustomTabsHelper
import android.content.Context
import android.util.AttributeSet
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder

/**
 * The Support development row: title and a calm summary on the left, and on the right a small
 * pill pair that opens GitHub Sponsors or Buy Me a Coffee directly. Nothing is stored or locked.
 */
class SupportLinkPreference
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : Preference(context, attrs) {
        init {
            layoutResource = R.layout.preference_compose
            isSelectable = false
        }

        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            val cv =
                (holder.itemView as? ComposeView)
                    ?: (holder.findViewById(R.id.preference_compose_view) as? ComposeView)
                    ?: return
            val title = title
            val summary = summary
            val prefIcon = icon
            val ctx = context
            cv.setPreferenceContent {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (prefIcon != null) {
                        PreferenceIconView(
                            drawable = prefIcon,
                            enabled = true,
                            modifier = Modifier.padding(end = 16.dp),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title.toString(),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (!summary.isNullOrEmpty()) {
                            Text(
                                text = summary.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Column(
                        modifier = Modifier.padding(start = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        SupportPill(R.string.about_support_pill_sponsors) {
                            CustomTabsHelper.launchUrlOrCopy(ctx, ctx.getString(R.string.about_support_url_sponsors))
                        }
                        SupportPill(R.string.about_support_pill_coffee) {
                            CustomTabsHelper.launchUrlOrCopy(ctx, ctx.getString(R.string.about_support_url))
                        }
                    }
                }
            }
        }
    }

@androidx.compose.runtime.Composable
private fun SupportPill(
    label: Int,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        contentPadding = ButtonDefaults.ExtraSmallContentPadding,
        modifier = Modifier.width(96.dp),
    ) {
        Text(
            androidx.compose.ui.res
                .stringResource(label),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
