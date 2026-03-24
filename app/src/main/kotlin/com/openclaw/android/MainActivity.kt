package com.openclaw.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openclaw.android.service.OpenClawService
import com.openclaw.android.ui.screens.*
import com.openclaw.android.ui.theme.OpenClawTheme
import com.openclaw.android.util.PermissionHelper

sealed class NavTab(val label: String, val icon: ImageVector) {
    data object Dashboard : NavTab("Dashboard", Icons.Default.Dashboard)
    data object Connectors : NavTab("Connect", Icons.Default.Extension)
    data object Files : NavTab("Files", Icons.Default.Description)
    data object Logs : NavTab("Logs", Icons.Default.Terminal)
    data object Settings : NavTab("Settings", Icons.Default.Settings)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OpenClawTheme {
                MainApp(
                    onStartService = { startOpenClawService() },
                    onStopService = { stopOpenClawService() },
                    onRequestPermissions = { PermissionHelper.requestAllPermissions(this) }
                )
            }
        }
    }

    private fun startOpenClawService() {
        val intent = Intent(this, OpenClawService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopOpenClawService() {
        stopService(Intent(this, OpenClawService::class.java))
    }
}

@Composable
fun MainApp(
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onRequestPermissions: () -> Unit
) {
    val tabs = listOf(NavTab.Dashboard, NavTab.Connectors, NavTab.Files, NavTab.Logs, NavTab.Settings)
    var selectedTab by remember { mutableStateOf<NavTab>(NavTab.Dashboard) }

    val BG = Color(0xFF0D1117)
    val SURFACE = Color(0xFF161B22)
    val CYAN = Color(0xFF58A6FF)
    val TEXT2 = Color(0xFF8B949E)

    Scaffold(
        containerColor = BG,
        bottomBar = {
            NavigationBar(containerColor = SURFACE, tonalElevation = 0.dp) {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { Icon(tab.icon, tab.label) },
                        label = { Text(tab.label, fontFamily = FontFamily.Monospace, fontSize = 10.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = CYAN,
                            selectedTextColor = CYAN,
                            unselectedIconColor = TEXT2,
                            unselectedTextColor = TEXT2,
                            indicatorColor = CYAN.copy(alpha = 0.1f)
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).background(BG)) {
            when (selectedTab) {
                NavTab.Dashboard -> DashboardScreen()
                NavTab.Connectors -> ConnectorsScreen()
                NavTab.Files -> FilesScreen()
                NavTab.Logs -> LogScreen()
                NavTab.Settings -> SettingsScreen(onStartService = onStartService, onStopService = onStopService)
            }
        }
    }
}
