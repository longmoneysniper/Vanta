package com.vanta.media.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.vanta.media.navigation.VantaRoute
import com.vanta.media.ui.components.AnamorphicEclipseLogo
import com.vanta.media.ui.theme.*

@Composable
fun VantaAppShell() {
    val navController = rememberNavController()
    val configuration = LocalConfiguration.current
    val isTvOrLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ||
        configuration.screenWidthDp >= 840

    var selectedIndex by remember { mutableIntStateOf(0) }
    val navItems = listOf(
        Triple("Home", Icons.Rounded.Home, VantaRoute.Home),
        Triple("Library", Icons.Rounded.MovieFilter, VantaRoute.Library),
        Triple("Sources", Icons.Rounded.Dns, VantaRoute.Sources),
        Triple("Settings", Icons.Rounded.Tune, VantaRoute.Settings)
    )

    Scaffold(
        containerColor = OledObsidian,
        bottomBar = {
            if (!isTvOrLandscape) {
                NavigationBar(
                    containerColor = ObsidianSurface.copy(alpha = 0.88f),
                    tonalElevation = 0.dp
                ) {
                    navItems.forEachIndexed { index, (label, icon, route) ->
                        NavigationBarItem(
                            selected = selectedIndex == index,
                            onClick = {
                                selectedIndex = index
                                navController.navigate(route) { launchSingleTop = true }
                            },
                            icon = { Icon(icon, contentDescription = label) },
                            label = { Text(label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = AmberCorona,
                                selectedTextColor = AmberCorona,
                                indicatorColor = AmberCorona.copy(alpha = 0.16f),
                                unselectedIconColor = TextSecondary,
                                unselectedTextColor = TextSecondary
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(OledObsidian)
                .padding(innerPadding)
        ) {
            if (isTvOrLandscape) {
                NavigationRail(
                    containerColor = ObsidianSurface.copy(alpha = 0.78f),
                    header = {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(vertical = 16.dp)
                        ) {
                            AnamorphicEclipseLogo(size = 32.dp)
                        }
                    }
                ) {
                    navItems.forEachIndexed { index, (label, icon, route) ->
                        NavigationRailItem(
                            selected = selectedIndex == index,
                            onClick = {
                                selectedIndex = index
                                navController.navigate(route) { launchSingleTop = true }
                            },
                            icon = { Icon(icon, contentDescription = label) },
                            label = { Text(label) },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = AmberCorona,
                                selectedTextColor = AmberCorona,
                                indicatorColor = AmberCorona.copy(alpha = 0.16f),
                                unselectedIconColor = TextSecondary,
                                unselectedTextColor = TextSecondary
                            )
                        )
                    }
                }
            }

            NavHost(
                navController = navController,
                startDestination = VantaRoute.Home,
                modifier = Modifier.fillMaxSize()
            ) {
                composable<VantaRoute.Home> { Phase1HomeScreen() }
                composable<VantaRoute.Library> { Phase1PlaceholderScreen("Unified Library") }
                composable<VantaRoute.Sources> { Phase1PlaceholderScreen("Jellyfin • Stremio • Kodi • Debrid • SMB") }
                composable<VantaRoute.Settings> { Phase1PlaceholderScreen("Vanta Settings & PunchPlay.tv") }
            }
        }
    }
}

@Composable
private fun Phase1HomeScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnamorphicEclipseLogo(size = 38.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = "VANTA",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "Cinema, Unified.",
                    style = MaterialTheme.typography.labelMedium,
                    color = AmberCorona
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.linearGradient(
                        listOf(FrostedGlassFill, CrimsonFlare.copy(alpha = 0.14f))
                    )
                )
                .border(1.dp, FrostedBorder, RoundedCornerShape(24.dp))
                .padding(24.dp),
            contentAlignment = Alignment.BottomStart
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("PHASE 1 SHELL READY", color = AmberCorona, style = MaterialTheme.typography.labelSmall)
                Text(
                    "OLED Obsidian & Frosted Glass Architecture",
                    color = TextPrimary,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "arm64-v8a locked • Edge-to-Edge • Touch & Android TV D-Pad Ready",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun Phase1PlaceholderScreen(title: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = title, color = TextSecondary, style = MaterialTheme.typography.titleMedium)
    }
}
