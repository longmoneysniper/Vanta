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
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
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
    val isPlayer = backStack?.destination?.route?.contains("Player") == true
    var tab by remember { mutableIntStateOf(0) }
    val items = listOf(
        Triple("Home", Icons.Rounded.Home, VantaRoute.Home),
        Triple("Search", Icons.Rounded.Search, VantaRoute.Library),
        Triple("Library", Icons.Rounded.VideoLibrary, VantaRoute.Library),
        Triple("Sources", Icons.Rounded.Dns, VantaRoute.Sources),
        Triple("Profile", Icons.Rounded.Person, VantaRoute.Settings)
    )

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        NavHost(navController = nav, startDestination = VantaRoute.Home, modifier = Modifier.fillMaxSize()) {
            composable<VantaRoute.Home> {
                CinemaHomeScreen(
                    onLaunchPlayer = { k, l -> nav.navigate(VantaRoute.Player(k, l)) },
                    onOpenSources = { tab = 3; nav.navigate(VantaRoute.Sources) { launchSingleTop = true } }
                )
            }
            composable<VantaRoute.Library> {
                CinemaLibraryScreen(isSearch = tab == 1) { k, l -> nav.navigate(VantaRoute.Player(k, l)) }
            }
            composable<VantaRoute.Sources> { SourcesScreen() }
            composable<VantaRoute.Settings> { SettingsScreen() }
            composable<VantaRoute.Player> { entry ->
                val args = entry.toRoute<VantaRoute.Player>()
                VantaPlayerScreen(args.canonicalKey, args.sourceLinkId) { nav.popBackStack() }
            }
        }

        if (!isPlayer) {
            Row(
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)
                    .clip(CircleShape).background(Color(0xE612131A)).border(1.dp, Color(0x2EFFFFFF), CircleShape).padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEachIndexed { idx, (label, icon, route) ->
                    val sel = tab == idx
                    Column(
                        modifier = Modifier.clip(CircleShape).background(if (sel) Color(0x26FF8A1F) else Color.Transparent)
                            .clickable { tab = idx; nav.navigate(route) { launchSingleTop = true } }.padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(icon, label, tint = if (sel) AmberCorona else TextSecondary, modifier = Modifier.size(20.dp))
                        Text(label, color = if (sel) TextPrimary else TextSecondary, fontSize = 10.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
        }
    }
}

@Composable
private fun CinemaHomeScreen(vm: HomeViewModel = hiltViewModel(), onLaunchPlayer: (String, String) -> Unit, onOpenSources: () -> Unit) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<UnifiedMediaEntity?>(null) }
    val rows = remember(state.libraryItems) { state.libraryItems.groupBy { it.catalogRow ?: "Trending" } }

    LazyColumn(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding(), contentPadding = PaddingValues(top = 12.dp, bottom = 104.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        if (state.continueWatching.isNotEmpty()) {
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.continueWatching, key = { it.canonicalKey }) { m -> WideCard(m) { selected = m } }
                }
            }
        }
        if (state.libraryItems.isEmpty() && state.continueWatching.isEmpty()) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 32.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color(0xFF101117)).border(1.dp, FrostedBorder, RoundedCornerShape(24.dp)).padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("VANTA • CINEMA, UNIFIED.", color = AmberCorona, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("Connect AIOMetadata or Jellyfin", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Open Sources to link your AIOMetadata manifest, AIOStreams/Torrentio, or Jellyfin server.", color = TextSecondary, fontSize = 13.sp)
                    Button(onClick = onOpenSources, colors = ButtonDefaults.buttonColors(containerColor = AmberCorona, contentColor = OledObsidian)) { Text("Open Sources") }
                }
            }
        }
        rows.forEach { (title, list) ->
            val wide = title.contains("Upcoming", true) || title.contains("Next Up", true)
            item(key = title) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(title, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Box(Modifier.size(30.dp).clip(CircleShape).background(Color(0xFF181A22)).border(1.dp, FrostedBorder, CircleShape).clickable { vm.refreshAllCatalogs() }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.ChevronRight, null, tint = TextSecondary, modifier = Modifier.size(18.dp))
                        }
                    }
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(list, key = { it.canonicalKey }) { m -> if (wide) WideCard(m) { selected = m } else PosterCard(m) { selected = m } }
                    }
                }
            }
        }
    }

    selected?.let { m ->
        MediaDetailsSheet(m, vm.dao, vm.stremioEngine, onDismiss = { selected = null }) { key, linkId ->
            selected = null
            onLaunchPlayer(key, linkId)
        }
    }
}

@Composable
private fun WideCard(m: UnifiedMediaEntity, onClick: () -> Unit) {
    val prog = if (m.durationMs > 0L) (m.playbackPositionMs.toFloat() / m.durationMs).coerceIn(0f, 1f) else 0f
    Box(Modifier.width(272.dp).height(154.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF12141C)).border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(18.dp)).clickable(onClick = onClick)) {
        AsyncImage(model = m.episodeStillUrl ?: m.backdropUrl ?: m.posterUrl, contentDescription = m.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x55000000), Color(0xEB050507)))))
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            if (m.seasonNumber != null && m.episodeNumber != null) Text("S${m.seasonNumber} E${m.episodeNumber}", color = AmberCorona, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(m.seriesTitle ?: m.title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(m.episodeTitle ?: m.genres ?: "Select Stream", color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (prog > 0f) LinearProgressIndicator(progress = { prog }, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp), color = AmberCorona, trackColor = Color(0x33FFFFFF))
    }
}

@Composable
private fun PosterCard(m: UnifiedMediaEntity, onClick: () -> Unit) {
    Box(Modifier.width(152.dp).height(228.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF12141C)).border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(18.dp)).clickable(onClick = onClick)) {
        AsyncImage(model = m.posterUrl, contentDescription = m.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun CinemaLibraryScreen(isSearch: Boolean, vm: HomeViewModel = hiltViewModel(), onLaunchPlayer: (String, String) -> Unit) {
