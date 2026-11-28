package af.shizuku.manager.settings

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import android.content.Context
import android.content.SharedPreferences
import android.util.AttributeSet
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder

/**
 * Live preview for the Wavy progress toggle: a linear bar and a spinner drawn flat or wavy as the
 * switch flips. The View indicators elsewhere in the app only change after screens are rebuilt;
 * this row is Compose, so it reacts immediately.
 */
class WavyProgressPreviewPreference
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : Preference(context, attrs),
        SharedPreferences.OnSharedPreferenceChangeListener {
        private val wavy = mutableStateOf(ShizukuSettings.isWavyProgressEnabled())
        private val animated = mutableStateOf(ShizukuSettings.scaledAnimationDuration(200) > 0)

        init {
            layoutResource = R.layout.preference_compose
            isSelectable = false
        }

        private fun sync() {
            wavy.value = ShizukuSettings.isWavyProgressEnabled()
            animated.value = ShizukuSettings.scaledAnimationDuration(200) > 0
        }

        override fun onAttached() {
            super.onAttached()
            ShizukuSettings.getPreferences()?.registerOnSharedPreferenceChangeListener(this)
            sync()
        }

        override fun onDetached() {
            super.onDetached()
            ShizukuSettings.getPreferences()?.unregisterOnSharedPreferenceChangeListener(this)
        }

        override fun onSharedPreferenceChanged(
            prefs: SharedPreferences?,
            key: String?,
        ) = sync()

        override fun onBindViewHolder(holder: PreferenceViewHolder) {
            super.onBindViewHolder(holder)
            val cv =
                holder.findViewById(R.id.preference_compose_view) as? ComposeView
                    ?: holder.itemView as? ComposeView ?: return
            cv.setPreferenceContent { WavyProgressPreview(wavy.value, animated.value) }
        }
    }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun WavyProgressPreview(
    wavy: Boolean,
    animated: Boolean,
) {
    // Animations off or scale 0: draw the wave but keep it still.
    val speed = if (animated) 10.dp else 0.dp
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (wavy) {
            LinearWavyProgressIndicator(
                progress = { 0.6f },
                modifier = Modifier.weight(1f),
                waveSpeed = speed,
            )
            CircularWavyProgressIndicator(
                progress = { 0.6f },
                modifier = Modifier.size(40.dp),
                waveSpeed = speed,
            )
        } else {
            LinearProgressIndicator(progress = { 0.6f }, modifier = Modifier.weight(1f))
            CircularProgressIndicator(progress = { 0.6f }, modifier = Modifier.size(40.dp))
        }
    }
}
