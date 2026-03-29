package moe.shizuku.manager.ui.compose.activity

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import moe.shizuku.manager.Helps
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.management.AppsViewModel
import moe.shizuku.manager.settings.SettingsActivity
import moe.shizuku.manager.ui.compose.screens.HomeScreen
import moe.shizuku.manager.ui.compose.theme.ShizukuPlusTheme
import moe.shizuku.manager.utils.ShizukuStateMachine
import rikka.shizuku.Shizuku

/**
 * Compose-based Home Activity
 * 
 * Main entry point for the app using Jetpack Compose with M3E theme
 */
class ComposeHomeActivity : ComponentActivity() {

    private val homeViewModel: HomeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Enable edge-to-edge display (M3E best practice)
        enableEdgeToEdge()
        
        setContent {
            val isDarkTheme = when (ShizukuSettings.getNightMode()) {
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES -> true
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO -> false
                else -> isSystemInDarkTheme()
            }
            
            val uiState by homeViewModel.uiState.collectAsState()
            
            ShizukuPlusTheme(
                darkTheme = isDarkTheme,
                dynamicColor = true
            ) {
                HomeScreen(
                    isServiceRunning = uiState.isServiceRunning,
                    serviceVersion = uiState.serviceVersion,
                    serviceMode = uiState.serviceMode,
                    onStartServiceClick = {
                        // Start service
                        val intent = Intent(this, moe.shizuku.manager.starter.StarterActivity::class.java)
                        startActivity(intent)
                    },
                    onSettingsClick = {
                        startActivity(Intent(this, SettingsActivity::class.java))
                    },
                    onAppsClick = {
                        startActivity(Intent(this, moe.shizuku.manager.management.ApplicationManagementActivity::class.java))
                    },
                    onAdbClick = {
                        // Start ADB activity directly
                        startActivity(Intent(this, moe.shizuku.manager.starter.StarterActivity::class.java))
                    },
                    onTerminalClick = {
                        startActivity(Intent(this, moe.shizuku.manager.shell.ShellTutorialActivity::class.java))
                    },
                    onAutomationClick = {
                        // Navigate to automation
                    },
                    onLearnMoreClick = {
                        Helps.openUrl(this, Helps.getHelpUrl(null))
                    },
                    onActivityLogClick = {
                        startActivity(Intent(this, moe.shizuku.manager.settings.ActivityLogActivity::class.java))
                    }
                )
            }
        }
    }
}

/**
 * Home ViewModel - Manages service state and UI
 */
class HomeViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState

    private val stateListener: (ShizukuStateMachine.State) -> Unit = { state ->
        when (state) {
            ShizukuStateMachine.State.RUNNING -> {
                updateServiceStatus()
            }
            ShizukuStateMachine.State.STOPPED,
            ShizukuStateMachine.State.CRASHED -> {
                updateServiceStatus()
            }
            else -> {
                updateServiceStatus()
            }
        }
    }

    init {
        // Observe service state changes
        Shizuku.addBinderReceivedListenerSticky(
            Shizuku.OnBinderReceivedListener {
                updateServiceStatus()
            }
        )
        Shizuku.addBinderDeadListener(
            Shizuku.OnBinderDeadListener {
                updateServiceStatus()
            }
        )
        
        // Initial status load
        updateServiceStatus()
    }

    private fun updateServiceStatus() {
        viewModelScope.launch {
            try {
                val isRunning = Shizuku.pingBinder()
                val version = if (isRunning) {
                    "v${Shizuku.getVersion()}"
                } else {
                    null
                }
                val mode = if (isRunning) {
                    // Check if running as root (uid 0) or ADB (uid 2000)
                    try {
                        val uid = Shizuku.getUid()
                        if (uid == 0) "Root" else "ADB"
                    } catch (e: Exception) {
                        "Unknown"
                    }
                } else {
                    null
                }

                _uiState.value = HomeUiState(
                    isServiceRunning = isRunning,
                    serviceVersion = version,
                    serviceMode = mode
                )
            } catch (e: Exception) {
                _uiState.value = HomeUiState(
                    isServiceRunning = false,
                    serviceVersion = null,
                    serviceMode = null
                )
            }
        }
    }
}

data class HomeUiState(
    val isServiceRunning: Boolean = false,
    val serviceVersion: String? = null,
    val serviceMode: String? = null
)
