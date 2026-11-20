package com.vanta.media.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
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
import coil3.compose.AsyncImage
import com.vanta.media.data.local.dao.VantaMediaDao
import com.vanta.media.data.local.entity.MediaSourceLinkEntity
import com.vanta.media.data.local.entity.MediaType
import com.vanta.media.data.local.entity.UnifiedMediaEntity
import com.vanta.media.data.providers.stremio.StremioAddonEngine
import com.vanta.media.ui.theme.AmberCorona
import com.vanta.media.ui.theme.FrostedBorder
import com.vanta.media.ui.theme.OledObsidian
import com.vanta.media.ui.theme.TextPrimary
import com.vanta.media.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaDetailsSheet(
    media: UnifiedMediaEntity,
    dao: VantaMediaDao,
    stremioEngine: StremioAddonEngine,
    onDismiss: () -> Unit,
    onSelectStream: (canonicalKey: String, sourceLinkId: String) -> Unit
) {
    var selectedSeason by remember { mutableIntStateOf(media.seasonNumber ?: 1) }
    var selectedEpisode by remember { mutableIntStateOf(media.episodeNumber ?: 1) }
    var streams by remember { mutableStateOf<List<MediaSourceLinkEntity>>(emptyList()) }
    var loadingStreams by remember { mutableStateOf(true) }

    val isSeries = media.mediaType == MediaType.EPISODE

    LaunchedEffect(media.canonicalKey, selectedSeason, selectedEpisode) {
        loadingStreams = true
        val existing = dao.getSourceLinksForMedia(media.canonicalKey)
        if (existing.isNotEmpty() && (!isSeries || (selectedSeason == media.seasonNumber && selectedEpisode == media.episodeNumber))) {
            streams = existing
        } else if (!media.imdbId.isNullOrBlank()) {
            stremioEngine.queryStreamsInParallel(
                canonicalKey = media.canonicalKey,
                imdbId = media.imdbId,
                isSeries = isSeries,
                season = if (isSeries) selectedSeason else null,
                episode = if (isSeries) selectedEpisode else null
            )
            streams = dao.getSourceLinksForMedia(media.canonicalKey)
        } else {
            streams = existing
        }
        loadingStreams = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0B0C11),
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color(0x44FFFFFF)) }
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Backdrop Hero Header
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF14161F))
                ) {
                    AsyncImage(
                        model = media.backdropUrl ?: media.posterUrl,
                        contentDescription = media.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier.fillMaxSize().background(
                            Brush.verticalGradient(listOf(Color.Transparent, Color(0xE60B0C11)))
                        )
                    )
                    Column(modifier = Modifier.align(Alignment.BottomStart).padding(14.dp)) {
                        Text(
                            text = media.seriesTitle ?: media.title,
                            color = TextPrimary,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                        val sub = listOfNotNull(
                            media.releaseYear?.toString(),
                            media.genres,
                            media.rating?.takeIf { it > 0f }?.let { "★ ${String.format("%.1f", it)}" }
                        ).joinToString(" • ")
                        if (sub.isNotEmpty()) {
                            Text(sub, color = AmberCorona, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            if (media.overview.isNotBlank()) {
                item {
                    Text(
                        text = media.overview,
                        color = TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Season & Episode Picker for TV Series
            if (isSeries) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Season & Episode", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items((1..10).toList()) { s ->
                                FilterChip(
                                    selected = selectedSeason == s,
                                    onClick = { selectedSeason = s },
                                    label = { Text("S$s") }
                                )
                            }
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items((1..24).toList()) { e ->
                                FilterChip(
                                    selected = selectedEpisode == e,
                                    onClick = { selectedEpisode = e },
                                    label = { Text("E$e") }
                                )
                            }
                        }
                    }
                }
            }

            // Unified Streams List
            item {
                Text(
                    text = if (isSeries) "Streams for S${selectedSeason}E${selectedEpisode}" else "Available Streams",
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            if (loadingStreams) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(color = AmberCorona, modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Resolving Jellyfin & Addon streams...", color = TextSecondary, fontSize = 13.sp)
                    }
                }
            } else if (streams.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF14161F))
                            .border(1.dp, FrostedBorder, RoundedCornerShape(16.dp))
                            .padding(16.dp)
                    ) {
                        Text(
                            "No streams found yet. Add AIOStreams, Torrentio, Nuvio, Debrid, or Jellyfin in Sources.",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            } else {
                items(streams, key = { it.sourceLinkId }) { link ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF14161F))
                            .border(1.dp, FrostedBorder, RoundedCornerShape(16.dp))
                            .clickable { onSelectStream(media.canonicalKey, link.sourceLinkId) }
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                            Text(
                                text = link.sourceType.name,
                                color = AmberCorona,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = link.displayLabel,
