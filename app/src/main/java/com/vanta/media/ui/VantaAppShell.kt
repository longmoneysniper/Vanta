package com.vanta.media.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.*
import androidx.navigation.toRoute
import coil3.compose.AsyncImage
import com.vanta.media.data.local.entity.UnifiedMediaEntity
import com.vanta.media.navigation.VantaRoute
import com.vanta.media.ui.details.MediaDetailsSheet
import com.vanta.media.ui.home.HomeViewModel
import com.vanta.media.ui.player.VantaPlayerScreen
import com.vanta.media.ui.settings.SettingsScreen
import com.vanta.media.ui.sources.SourcesScreen
import com.vanta.media.ui.theme.*

@Composable
fun VantaAppShell() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val isPlayerRoute = backStack?.destination?.route?.contains("Player") == true
    var tab by remember { mutableIntStateOf(0) }
    val navItems = listOf(
        Triple("Home", Icons.Rounded.Home, VantaRoute.Home),
        Triple("Search", Icons.Rounded.Search, VantaRoute.Library),
        Triple("Library", Icons.Rounded.VideoLibrary, VantaRoute.Library),
        Triple("Sources", Icons.Rounded.Dns, VantaRoute.Sources),
        Triple("Profile", Icons.Rounded.Person, VantaRoute.Settings)
    )

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        NavHost(
            navController = nav,
            startDestination = VantaRoute.Home,
            modifier = Modifier.fillMaxSize()
        ) {
            composable<VantaRoute.Home> {
                CinemaHomeScreen(
                    onLaunchPlayer = { k, l -> nav.navigate(VantaRoute.Player(k, l)) },
                    onOpenSources = { tab = 3; nav.navigate(VantaRoute.Sources) { launchSingleTop = true } }
                )
            }
            composable<VantaRoute.Library> {
                CinemaLibraryScreen(
                    isSearchMode = tab == 1,
                    onLaunchPlayer = { k, l -> nav.navigate(VantaRoute.Player(k, l)) }
                )
            }
            composable<VantaRoute.Sources> { SourcesScreen() }
            composable<VantaRoute.Settings> { SettingsScreen() }
            composable<VantaRoute.Player> { entry ->
                val args = entry.toRoute<VantaRoute.Player>()
                VantaPlayerScreen(
                    canonicalKey = args.canonicalKey,
                    sourceLinkId = args.sourceLinkId,
                    onBack = { nav.popBackStack() }
                )
            }
        }

        if (!isPlayerRoute) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .clip(CircleShape)
                    .background(Color(0xE612131A))
                    .border(1.dp, Color(0x2EFFFFFF), CircleShape)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                navItems.forEachIndexed { idx, (label, icon, route) ->
                    val selected = tab == idx
                    Column(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (selected) Color(0x26FF8A1F) else Color.Transparent)
                            .clickable {
                                tab = idx
                                nav.navigate(route) { launchSingleTop = true }
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(icon, label, tint = if (selected) AmberCorona else TextSecondary, modifier = Modifier.size(20.dp))
                        Text(label, color = if (selected) TextPrimary else TextSecondary, fontSize = 10.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
        }
    }
}

@Composable
private fun CinemaHomeScreen(
    vm: HomeViewModel = hiltViewModel(),
    onLaunchPlayer: (String, String) -> Unit,
    onOpenSources: () -> Unit
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    var selectedMedia by remember { mutableStateOf<UnifiedMediaEntity?>(null) }
    val groupedRows = remember(state.libraryItems) {
        state.libraryItems.groupBy { it.catalogRow ?: "Trending" }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Color.Black).statusBarsPadding(),
        contentPadding = PaddingValues(top = 12.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        // 1. Continue Watching Wide 16:9 Row
        if (state.continueWatching.isNotEmpty()) {
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(state.continueWatching, key = { it.canonicalKey }) { m ->
                        WideEpisodeCard(m) { selectedMedia = m }
                    }
                }
            }
        }

        // Empty State Only When No Addons/Servers Connected
        if (state.libraryItems.isEmpty() && state.continueWatching.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 32.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFF101117))
                        .border(1.dp, FrostedBorder, RoundedCornerShape(24.dp))
                        .padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("VANTA • CINEMA, UNIFIED.", color = AmberCorona, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("Connect AIOMetadata or Jellyfin", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Open Sources to link your AIOMetadata manifest, AIOStreams/Torrentio, or Jellyfin server.", color = TextSecondary, fontSize = 13.sp)
                    Button(
                        onClick = onOpenSources,
                        colors = ButtonDefaults.buttonColors(containerColor = AmberCorona, contentColor = OledObsidian)
                    ) { Text("Open Sources") }
                }
            }
        }

        // 2. Clean AIOMetadata & Jellyfin Rows
        groupedRows.forEach { (rowTitle, itemsInRow) ->
            val isWideRow = rowTitle.contains("Upcoming", true) || rowTitle.contains("Next Up", true)
            item(key = rowTitle) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(rowTitle, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF181A22))
                                .border(1.dp, FrostedBorder, CircleShape)
                                .clickable { vm.refreshAllCatalogs() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.ChevronRight, null, tint = TextSecondary, modifier = Modifier.size(18.dp))
                        }
                    }

                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(itemsInRow, key = { it.canonicalKey }) { media ->
                            if (isWideRow) {
                                WideEpisodeCard(media) { selectedMedia = media }
                            } else {
                                FullBleedAioPosterCard(media) { selectedMedia = media }
                            }
                        }
                    }
                }
            }
        }
    }

    selectedMedia?.let { media ->
        MediaDetailsSheet(
            media = media,
            dao = vm.dao,
            stremioEngine = vm.stremioEngine,
            onDismiss = { selectedMedia = null },
            onSelectStream = { key, linkId ->
