package af.shizuku.manager.home

import android.os.Bundle
import af.shizuku.core.ui.AppActivity

import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import af.shizuku.manager.home.compose.SystemHubScreen

class SystemHubActivity : AppActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.activity.enableEdgeToEdge()
        setContent {
            SystemHubScreen(
                onBackClick = { finish() }
            )
        }
    }
}
