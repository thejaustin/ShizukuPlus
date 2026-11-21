package af.shizuku.manager.settings

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.utils.CustomTabsHelper
import android.content.Context
import android.util.AttributeSet
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder

/**
 * The Support development row: title and a calm summary on the left, and on the right a small
 * dropdown pill to pick GitHub Sponsors or Buy Me a Coffee. Tapping the row opens the pick; the pick is only a remembered link, nothing is locked.
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
            val choice = mutableStateOf(ShizukuSettings.getPreferences()?.getString(KEY, "sponsors") ?: "sponsors")
            cv.setPreferenceContent {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                val url = if (choice.value == "coffee") R.string.about_support_url else R.string.about_support_url_sponsors
                                CustomTabsHelper.launchUrlOrCopy(ctx, ctx.getString(url))
                            }.padding(horizontal = 16.dp, vertical = 12.dp),
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
                    SupportDropdown(
                        selected = choice.value,
                        onSelect = {
                            choice.value = it
                            ShizukuSettings
                                .getPreferences()
                                ?.edit()
                                ?.putString(KEY, it)
                                ?.apply()
                        },
                    )
                }
            }
        }
    }

private const val KEY = "support_link"

/** Compact pill on the right: shows where support goes and drops a two-item menu to change it. */
@androidx.compose.runtime.Composable
private fun SupportDropdown(
    selected: String,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val names = listOf("sponsors" to R.string.about_support_pill_sponsors, "coffee" to R.string.about_support_pill_coffee)
    Box(modifier = Modifier.padding(start = 12.dp)) {
        FilledTonalButton(
            onClick = { open = true },
            contentPadding = ButtonDefaults.ContentPadding,
        ) {
            Text(
                stringResource(names.first { it.first == selected }.second),
                style = MaterialTheme.typography.labelLarge,
            )
            Icon(
                androidx.compose.ui.res
                    .painterResource(R.drawable.ic_outline_expand_more_24),
                contentDescription = stringResource(R.string.about_support_link_title),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            names.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    onClick = {
                        onSelect(value)
                        open = false
                    },
                )
            }
        }
    }
}
