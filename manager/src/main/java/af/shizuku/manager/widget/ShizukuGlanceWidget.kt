package af.shizuku.manager.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.*
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.glance.ColorFilter
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.text.FontWeight
import af.shizuku.manager.MainActivity
import af.shizuku.manager.R
import af.shizuku.manager.starter.StarterActivity
import af.shizuku.manager.utils.ShizukuStateMachine
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class ShizukuGlanceWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            content()
        }
    }

    @Composable
    private fun content() {
        val isRunning = ShizukuStateMachine.get() == ShizukuStateMachine.State.RUNNING

        Row(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(R.color.widget_background))
                .padding(16.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = GlanceModifier
                    .size(48.dp)
                    .background(
                        ColorProvider(
                            if (isRunning) R.color.widget_container_ok
                            else R.color.widget_container_error
                        )
                    )
                    .padding(10.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    provider = ImageProvider(
                        if (isRunning) R.drawable.ic_server_ok_24 else R.drawable.ic_server_error_24
                    ),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(
                        ColorProvider(
                            if (isRunning) R.color.widget_icon_ok else R.color.widget_icon_error
                        )
                    )
                )
            }

            Spacer(modifier = GlanceModifier.width(16.dp))

            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    text = "Shizuku+",
                    style = TextStyle(
                        color = ColorProvider(R.color.widget_title),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
                Text(
                    text = if (isRunning) "Running" else "Stopped",
                    style = TextStyle(
                        color = ColorProvider(R.color.widget_subtitle),
                        fontSize = 14.sp
                    )
                )
            }

            if (!isRunning) {
                Box(
                    modifier = GlanceModifier
                        .size(48.dp)
                        .background(ColorProvider(R.color.widget_start_container))
                        .padding(10.dp)
                        .clickable(actionStartActivity<StarterActivity>()),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_server_start_24),
                        contentDescription = "Start",
                        colorFilter = ColorFilter.tint(ColorProvider(R.color.widget_start_icon))
                    )
                }
            }
        }
    }
}
