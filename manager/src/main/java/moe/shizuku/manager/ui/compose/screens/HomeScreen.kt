package moe.shizuku.manager.ui.compose.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.shizuku.manager.ui.compose.components.SettingsSectionHeader
import moe.shizuku.manager.ui.compose.components.SettingsSpacer

/**
 * M3E Home Screen - Main dashboard for Shizuku+
 * 
 * Replaces the XML-based HomeActivity with Jetpack Compose
 * Uses M3E shape tokens (12dp medium) and color palette
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    isServiceRunning: Boolean,
    serviceVersion: String?,
    serviceMode: String?,
    onStartServiceClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onAppsClick: () -> Unit,
    onAdbClick: () -> Unit,
    onTerminalClick: () -> Unit,
    onAutomationClick: () -> Unit,
    onLearnMoreClick: () -> Unit,
    onActivityLogClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Shizuku+",
                        style = MaterialTheme.typography.headlineSmall
                    )
                },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            color = MaterialTheme.colorScheme.background
        ) {
            HomeContent(
                isServiceRunning = isServiceRunning,
                serviceVersion = serviceVersion,
                serviceMode = serviceMode,
                onStartServiceClick = onStartServiceClick,
                onAppsClick = onAppsClick,
                onAdbClick = onAdbClick,
                onTerminalClick = onTerminalClick,
                onAutomationClick = onAutomationClick,
                onLearnMoreClick = onLearnMoreClick,
                onActivityLogClick = onActivityLogClick
            )
        }
    }
}

@Composable
private fun HomeContent(
    isServiceRunning: Boolean,
    serviceVersion: String?,
    serviceMode: String?,
    onStartServiceClick: () -> Unit,
    onAppsClick: () -> Unit,
    onAdbClick: () -> Unit,
    onTerminalClick: () -> Unit,
    onAutomationClick: () -> Unit,
    onLearnMoreClick: () -> Unit,
    onActivityLogClick: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = 16.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp) // M3E spacing
    ) {
        // Status Card
        item {
            StatusCard(
                isRunning = isServiceRunning,
                version = serviceVersion,
                mode = serviceMode,
                onStartClick = onStartServiceClick
            )
        }

        // Quick Actions
        item {
            SettingsSectionHeader(title = "Quick Actions")
        }

        item {
            HomeActionCard(
                title = "Applications",
                subtitle = "Manage app permissions",
                icon = Icons.Filled.Android,
                onClick = onAppsClick
            )
        }

        item {
            HomeActionCard(
                title = "Wireless ADB",
                subtitle = "Start via wireless debugging",
                icon = Icons.Filled.Wifi,
                onClick = onAdbClick
            )
        }

        item {
            HomeActionCard(
                title = "Terminal",
                subtitle = "Shell access and commands",
                icon = Icons.Filled.Terminal,
                onClick = onTerminalClick
            )
        }

        item {
            HomeActionCard(
                title = "Automation",
                subtitle = "Automate tasks and workflows",
                icon = Icons.Filled.Tune,
                onClick = onAutomationClick
            )
        }

        item {
            SettingsSpacer()
        }

        // Learn More
        item {
            SettingsSectionHeader(title = "Resources")
        }

        item {
            HomeActionCard(
                title = "Learn More",
                subtitle = "Documentation and guides",
                icon = Icons.Filled.Help,
                onClick = onLearnMoreClick
            )
        }

        item {
            HomeActionCard(
                title = "Activity Log",
                subtitle = "View API call history",
                icon = Icons.Filled.BugReport,
                onClick = onActivityLogClick
            )
        }
    }
}

@Composable
private fun StatusCard(
    isRunning: Boolean,
    version: String?,
    mode: String?,
    onStartClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isRunning) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            }
        ),
        shape = MaterialTheme.shapes.medium // 12dp M3E
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (isRunning) "Service Running" else "Service Stopped",
                        style = MaterialTheme.typography.titleLarge,
                        color = if (isRunning) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        }
                    )
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    if (version != null) {
                        Text(
                            text = "Version: $version",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isRunning) {
                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            } else {
                                MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                            }
                        )
                    }
                    
                    if (mode != null) {
                        Text(
                            text = "Mode: $mode",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isRunning) {
                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            } else {
                                MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                            }
                        )
                    }
                }
                
                Icon(
                    imageVector = if (isRunning) Icons.Filled.Shield else Icons.Filled.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = if (isRunning) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    }
                )
            }
            
            if (!isRunning) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onStartClick,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Power,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.padding(start = 8.dp))
                        Text(
                            text = "Start Service",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeActionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = MaterialTheme.shapes.medium // 12dp M3E
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
