package com.vanta.media.ui

import android.content.res.Configuration
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
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
import androidx.navigation.compose.*
import androidx.navigation.toRoute
import coil3.compose.AsyncImage
import com.vanta.media.data.local.entity.UnifiedMediaEntity
import com.vanta.media.navigation.VantaRoute
import com.vanta.media.ui.components.AnamorphicEclipseLogo
import com.vanta.media.ui.home.HomeViewModel
import com.vanta.media.ui.player.VantaPlayerScreen
import com.vanta.media.ui.settings.SettingsScreen
import com.vanta.media.ui.sources.SourcesScreen
import com.vanta.media.ui.theme.*

@Composable
fun VantaAppShell() {
    val nav = rememberNavController()
    val cfg = LocalConfiguration.current
    val isTv = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE || cfg.screenWidthDp >= 840
    var tab by remember { mutableIntStateOf(0) }
    val items = listOf(
        Triple("Home", Icons.Rounded.Home, VantaRoute.Home),
        Triple("Library", Icons.Rounded.MovieFilter, VantaRoute.Library),
        Triple("Sources", Icons.Rounded.Dns, VantaRoute.Sources),
        Triple("Settings", Icons.Rounded.Tune, VantaRoute.Settings)
    )

    Scaffold(
        containerColor = OledObsidian,
        bottomBar = {
            if (!isTv) {
                NavigationBar(containerColor = ObsidianSurface.copy(alpha = 0.88f)) {
                    items.forEachIndexed { idx, (label, icon, route) ->
                        NavigationBarItem(
                            selected = tab == idx,
                            onClick = { tab = idx; nav.navigate(route) { launchSingleTop = true } },
                            icon = { Icon(icon, label) },
                            label = { Text(label) }
                        )
                    }
                }
            }
        }
    ) { pad ->
        Row(Modifier.fillMaxSize().background(OledObsidian).padding(pad)) {
            if (isTv) {
                NavigationRail(containerColor = ObsidianSurface.copy(alpha = 0.78f), header = { AnamorphicEclipseLogo(size = 30.dp) }) {
                    items.forEachIndexed { idx, (label, icon, route) ->
                        NavigationRailItem(
                            selected = tab == idx,
                            onClick = { tab = idx; nav.navigate(route) { launchSingleTop = true } },
                            icon = { Icon(icon, label) },
                            label = { Text(label) }
                        )
                    }
                }
            }
            NavHost(navController = nav, startDestination = VantaRoute.Home, modifier = Modifier.fillMaxSize()) {
                composable<VantaRoute.Home> { VantaHomeScreen { k, l -> nav.navigate(VantaRoute.Player(k, l)) } }
                composable<VantaRoute.Library> { VantaLibraryGrid { k, l -> nav.navigate(VantaRoute.Player(k, l)) } }
                composable<VantaRoute.Sources> { SourcesScreen() }
                composable<VantaRoute.Settings> { SettingsScreen() }
                composable<VantaRoute.Player> { entry ->
                    val args = entry.toRoute<VantaRoute.Player>()
                    VantaPlayerScreen(args.canonicalKey, args.sourceLinkId) { nav.popBackStack() }
                }
            }
        }
    }
}

@Composable
private fun VantaHomeScreen(vm: HomeViewModel = hiltViewModel(), onPlay: (String, String) -> Unit) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    var keyInput by remember { mutableStateOf("") }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), contentPadding = PaddingValues(vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AnamorphicEclipseLogo(size = 32.dp)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("VANTA", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text("Cinema, Unified.", style = MaterialTheme.typography.labelMedium, color = AmberCorona)
                    }
                }
                FilterChip(selected = state.kidsModeEnabled, onClick = { vm.toggleKidsMode(!state.kidsModeEnabled) }, label = { Text(if (state.kidsModeEnabled) "Kids ON" else "Kids") })
            }
        }
        item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Brush.linearGradient(listOf(FrostedGlassFill, CrimsonFlare.copy(alpha = 0.14f)))).border(1.dp, FrostedBorder, RoundedCornerShape(20.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("VANTA v1.0.0 • CINEMA, UNIFIED.", color = AmberCorona, style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = keyInput, onValueChange = { keyInput = it }, placeholder = { Text("TMDB API Key") }, singleLine = true, modifier = Modifier.weight(1f))
                    Button(onClick = { if (keyInput.isNotBlank()) { vm.saveTmdbApiKey(keyInput); keyInput = "" } }, colors = ButtonDefaults.buttonColors(containerColor = AmberCorona, contentColor = OledObsidian)) { Text("Save") }
                }
                OutlinedButton(onClick = { vm.ingestSampleItems() }) { Text("Load Demo Library") }
            }
        }
        if (state.continueWatching.isNotEmpty()) {
            item {
                Text("Continue Watching", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.continueWatching, key = { it.canonicalKey }) { m -> MediaCard(m, true) { onPlay(m.canonicalKey, "demo_link_1") } }
                }
            }
        }
        if (state.libraryItems.isNotEmpty()) {
            item {
                Text("Unified Library", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.libraryItems, key = { it.canonicalKey }) { m -> MediaCard(m, false) { onPlay(m.canonicalKey, "demo_link_0") } }
                }
            }
        }
    }
}

@Composable
private fun VantaLibraryGrid(vm: HomeViewModel = hiltViewModel(), onPlay: (String, String) -> Unit) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    LazyVerticalGrid(GridCells.Adaptive(136.dp), Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(state.libraryItems, key = { it.canonicalKey }) { m -> MediaCard(m, false) { onPlay(m.canonicalKey, "demo_link_0") } }
    }
}

@Composable
private fun MediaCard(m: UnifiedMediaEntity, wide: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(if (wide) 220.dp else 136.dp).clip(RoundedCornerShape(16.dp)).background(FrostedGlassFill).border(1.dp, FrostedBorder, RoundedCornerShape(16.dp)).clickable(onClick = onClick)) {
        AsyncImage(model = if (wide) (m.episodeStillUrl ?: m.backdropUrl ?: m.posterUrl) else m.posterUrl, contentDescription = m.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(if (wide) 124.dp else 190.dp).background(ObsidianSurface))
        Column(Modifier.padding(10.dp)) {
            Text(m.title, color = TextPrimary, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(m.canonicalKey, color = AmberCorona, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
