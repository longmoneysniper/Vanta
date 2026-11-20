package com.vanta.media.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import coil3.compose.AsyncImage
import com.vanta.media.data.local.entity.UnifiedMediaEntity
import com.vanta.media.navigation.VantaRoute
import com.vanta.media.ui.components.AnamorphicEclipseLogo
import com.vanta.media.ui.home.HomeViewModel
import com.vanta.media.ui.player.VantaPlayerScreen
import com.vanta.media.ui.sources.SourcesScreen
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
                composable<VantaRoute.Home> {
                    Phase4HomeScreen(
                        onPlayMedia = { canonicalKey, linkId ->
                            navController.navigate(VantaRoute.Player(canonicalKey, linkId))
                        }
                    )
                }
                composable<VantaRoute.Library> { Phase4PlaceholderScreen("Unified Library") }
                composable<VantaRoute.Sources> { SourcesScreen() }
                composable<VantaRoute.Settings> { Phase4PlaceholderScreen("Vanta Settings & PunchPlay.tv (Phase 5)") }
                composable<VantaRoute.Player> { backStackEntry ->
                    val args = backStackEntry.toRoute<VantaRoute.Player>()
                    VantaPlayerScreen(
                        canonicalKey = args.canonicalKey,
                        sourceLinkId = args.sourceLinkId,
                        onBack = { navController.popBackStack() }
                    )
                }
            }
        }
    }
}

@Composable
private fun Phase4HomeScreen(
    onPlayMedia: (String, String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var tmdbInput by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AnamorphicEclipseLogo(size = 34.dp)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("VANTA", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text("Cinema, Unified.", style = MaterialTheme.typography.labelMedium, color = AmberCorona)
                    }
                }
                FilterChip(
                    selected = state.kidsModeEnabled,
                    onClick = { viewModel.toggleKidsMode(!state.kidsModeEnabled) },
                    label = { Text(if (state.kidsModeEnabled) "Kids Mode ON" else "Kids Mode") },
                    leadingIcon = { Icon(Icons.Rounded.ChildCare, contentDescription = null) }
                )
            }
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(Brush.linearGradient(listOf(FrostedGlassFill, CrimsonFlare.copy(alpha = 0.14f))))
                    .border(1.dp, FrostedBorder, RoundedCornerShape(22.dp))
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("PHASE 4 • JELLYFIN, STREMIO/NUVIO & DEBRID CONNECTED", color = AmberCorona, style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = tmdbInput,
                        onValueChange = { tmdbInput = it },
                        placeholder = { Text("TMDB v3 API Key") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            if (tmdbInput.isNotBlank()) {
                                viewModel.saveTmdbApiKey(tmdbInput)
                                tmdbInput = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AmberCorona, contentColor = OledObsidian)
                    ) {
                        Text("Save")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { viewModel.ingestSampleItems() }) {
