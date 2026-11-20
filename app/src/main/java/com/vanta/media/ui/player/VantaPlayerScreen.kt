package com.vanta.media.ui.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.os.Build
import android.util.Rational
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vanta.media.player.display.RefreshRateHelper
import com.vanta.media.ui.theme.*

@Composable
fun VantaPlayerScreen(
    canonicalKey: String,
    sourceLinkId: String,
    onBack: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val playbackState by viewModel.mpvController.state.collectAsStateWithLifecycle()
    val meta by viewModel.meta.collectAsStateWithLifecycle()

    var hudVisible by remember { mutableStateOf(true) }
    var showSubtitleStudio by remember { mutableStateOf(false) }
    var showQrModal by remember { mutableStateOf(false) }
    var lock24Hz by remember { mutableStateOf(true) }

    LaunchedEffect(canonicalKey, sourceLinkId) {
        viewModel.loadFromRoom(canonicalKey, sourceLinkId)
    }

    DisposableEffect(lock24Hz) {
        activity?.let { RefreshRateHelper.lockCinema24Hz(it, lock24Hz) }
        onDispose {
            viewModel.persistProgress(canonicalKey)
            activity?.let { RefreshRateHelper.lockCinema24Hz(it, false) }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable { hudVisible = !hudVisible }
    ) {
        // libmpv Video Surface
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            viewModel.mpvController.attachSurface(holder.surface)
                        }
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            viewModel.mpvController.detachSurface()
                        }
                    })
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Real-Time Pipeline Stats Overlay
        if (playbackState.showStatsOverlay) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(20.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(ObsidianSurface.copy(alpha = 0.86f))
                    .border(1.dp, FrostedBorder, RoundedCornerShape(16.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("VANTA PIPELINE STATS", color = AmberCorona, style = MaterialTheme.typography.labelSmall)
                Text("Codec: ${playbackState.stats.videoCodec}", color = TextPrimary, style = MaterialTheme.typography.bodySmall)
                Text("Decoder: ${playbackState.stats.hwDecoder}", color = TextPrimary, style = MaterialTheme.typography.bodySmall)
                Text("Res / FPS: ${playbackState.stats.resolution} @ ${playbackState.stats.fps}", color = TextPrimary, style = MaterialTheme.typography.bodySmall)
                Text("Cache: ${playbackState.stats.cachePercent}% • Drops: ${playbackState.stats.droppedFrames}", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }

        // Frosted-Glass Touch & D-Pad HUD
        if (hudVisible) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .padding(20.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            viewModel.persistProgress(canonicalKey)
                            onBack()
                        }) {
                            Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                        }
                        Column {
                            Text(meta.media?.title ?: "Vanta Cinema", color = TextPrimary, fontWeight = FontWeight.Bold)
                            Text(meta.link?.displayLabel ?: "libmpv Direct", color = AmberCorona, style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        IconButton(onClick = { lock24Hz = !lock24Hz }) {
                            Icon(Icons.Rounded.Movie, contentDescription = "24Hz Lock", tint = if (lock24Hz) AmberCorona else TextSecondary)
                        }
                        IconButton(onClick = { viewModel.mpvController.toggleStatsOverlay() }) {
                            Icon(Icons.Rounded.Analytics, contentDescription = "Pipeline Stats", tint = AmberCorona)
                        }
                        IconButton(onClick = { showQrModal = true }) {
                            Icon(Icons.Rounded.QrCode2, contentDescription = "LAN Companion", tint = TextPrimary)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity != null) {
                            IconButton(onClick = {
                                val params = PictureInPictureParams.Builder()
                                    .setAspectRatio(Rational(16, 9))
                                    .build()
                                activity.enterPictureInPictureMode(params)
                            }) {
                                Icon(Icons.Rounded.PictureInPictureAlt, contentDescription = "PiP", tint = TextPrimary)
                            }
                        }
                    }
                }

                // Bottom Transport & Subtitle Studio Bar
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(ObsidianSurface.copy(alpha = 0.85f))
                        .border(1.dp, FrostedBorder, RoundedCornerShape(22.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val progress = if (playbackState.durationMs > 0) {
                        (playbackState.positionMs.toFloat() / playbackState.durationMs).coerceIn(0f, 1f)
                    } else 0f

                    Slider(
                        value = progress,
                        onValueChange = { ratio ->
                            viewModel.mpvController.seekTo((ratio * playbackState.durationMs).toLong())
                        },
                        colors = SliderDefaults.colors(thumbColor = AmberCorona, activeTrackColor = AmberCorona)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { viewModel.mpvController.seekRelative(-15) }) {
                                Icon(Icons.Rounded.Replay10, contentDescription = "Rewind 15s", tint = TextPrimary)
                            }
                            FilledIconButton(
                                onClick = { viewModel.mpvController.togglePlayPause() },
                                colors = IconButtonDefaults.filledIconButtonColors(containerColor = AmberCorona, contentColor = OledObsidian)
                            ) {
                                Icon(
                                    if (playbackState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = "Play/Pause"
                                )
                            }
                            IconButton(onClick = { viewModel.mpvController.seekRelative(30) }) {
                                Icon(Icons.Rounded.Forward30, contentDescription = "Forward 30s", tint = TextPrimary)
                            }
                            if (playbackState.chapterCount > 0) {
