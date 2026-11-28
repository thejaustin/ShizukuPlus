package af.shizuku.manager.settings

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import android.content.Context
import android.content.SharedPreferences
import android.util.AttributeSet
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import kotlinx.coroutines.delay

/**
 * Live preview for the motion settings: a dot slides back and forth on the same MotionScheme the
 * app would use (Expressive overshoots and settles, Standard glides), and snaps when animations
 * are off. Re-reads the settings as they change, so toggling shows the difference straight away.
 */
class MotionPreviewPreference
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : Preference(context, attrs),
        SharedPreferences.OnSharedPreferenceChangeListener {
        private val expressive = mutableStateOf(ShizukuSettings.isExpressiveMotionScheme())
        private val motionOn = mutableStateOf(ShizukuSettings.scaledAnimationDuration(200) > 0)

        init {
            layoutResource = R.layout.preference_compose
            isSelectable = false
            sync()
        }

        private fun sync() {
            expressive.value = ShizukuSettings.isExpressiveMotionScheme()
            motionOn.value = ShizukuSettings.scaledAnimationDuration(200) > 0
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
            cv.setPreferenceContent { MotionPreview(expressive.value, motionOn.value) }
        }
    }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MotionPreview(
    expressive: Boolean,
    motionOn: Boolean,
) {
    var atEnd by remember { mutableStateOf(false) }
    LaunchedEffect(motionOn) {
        while (motionOn) {
            delay(1400)
            atEnd = !atEnd
        }
        atEnd = false
    }
    val scheme = if (expressive) MotionScheme.expressive() else MotionScheme.standard()
    val fraction by animateFloatAsState(
        targetValue = if (atEnd) 1f else 0f,
        animationSpec = if (motionOn) scheme.defaultSpatialSpec() else snap(),
        label = "motionPreview",
    )
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
        Text(
            text =
                stringResource(
                    when {
                        !motionOn -> R.string.settings_motion_preview_still
                        expressive -> R.string.settings_motion_preview_expressive
                        else -> R.string.settings_motion_preview_standard
                    },
                ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BoxWithConstraints(
            modifier =
                Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            val dot = 32.dp
            Box(
                modifier =
                    Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = 4.dp + (maxWidth - dot - 8.dp) * fraction)
                        .size(dot)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}
