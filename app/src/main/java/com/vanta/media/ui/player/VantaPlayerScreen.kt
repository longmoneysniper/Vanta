package com.vanta.media.ui.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.os.Build
import android.util.Rational
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.*
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
    val activity = LocalContext.current as? Activity
    val state by viewModel.mpvController.state.collectAsStateWithLifecycle()
    val meta by viewModel.meta.collectAsStateWithLifecycle()
    var hud by remember { mutableStateOf(true) }
    var subs by remember { mutableStateOf(false) }
    var qr by remember { mutableStateOf(false) }
    var hz24 by remember { mutableStateOf(true) }

    LaunchedEffect(canonicalKey, sourceLinkId) { viewModel.loadFromRoom(canonicalKey, sourceLinkId) }
    DisposableEffect(hz24) {
        activity?.let { RefreshRateHelper.lockCinema24Hz(it, hz24) }
        onDispose { viewModel.persistProgress(canonicalKey); activity?.let { RefreshRateHelper.lockCinema24Hz(it, false) } }
    }

    Box(Modifier.fillMaxSize().background(Color.Black).clickable { hud = !hud }) {
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) = viewModel.mpvController.attachSurface(h.surface)
                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
                        override fun surfaceDestroyed(h: SurfaceHolder) = viewModel.mpvController.detachSurface()
                    })
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (state.showStatsOverlay) {
            Column(Modifier.align(Alignment.TopEnd).padding(16.dp).clip(RoundedCornerShape(14.dp)).background(ObsidianSurface.copy(alpha = 0.85f)).border(1.dp, FrostedBorder, RoundedCornerShape(14.dp)).padding(12.dp)) {
                Text("PIPELINE STATS", color = AmberCorona, style = MaterialTheme.typography.labelSmall)
                Text("${state.stats.videoCodec} • ${state.stats.hwDecoder}", color = TextPrimary, style = MaterialTheme.typography.bodySmall)
                Text("${state.stats.resolution} @ ${state.stats.fps}fps", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (hud) {
            Column(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { viewModel.persistProgress(canonicalKey); onBack() }) { Icon(Icons.Rounded.ArrowBack, null, tint = TextPrimary) }
                        Text(meta.media?.title ?: "Vanta Player", color = TextPrimary, fontWeight = FontWeight.Bold)
                    }
                    Row {
                        IconButton(onClick = { hz24 = !hz24 }) { Icon(Icons.Rounded.Movie, null, tint = if (hz24) AmberCorona else TextSecondary) }
                        IconButton(onClick = { viewModel.mpvController.toggleStatsOverlay() }) { Icon(Icons.Rounded.Analytics, null, tint = AmberCorona) }
                        IconButton(onClick = { qr = true }) { Icon(Icons.Rounded.QrCode2, null, tint = TextPrimary) }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity != null) {
                            IconButton(onClick = { activity.enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) }) {
                                Icon(Icons.Rounded.PictureInPictureAlt, null, tint = TextPrimary)
                            }
                        }
                    }
                }

                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(ObsidianSurface.copy(alpha = 0.85f)).border(1.dp, FrostedBorder, RoundedCornerShape(20.dp)).padding(14.dp)) {
                    val prog = if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
                    Slider(value = prog, onValueChange = { viewModel.mpvController.seekTo((it * state.durationMs).toLong()) }, colors = SliderDefaults.colors(thumbColor = AmberCorona, activeTrackColor = AmberCorona))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { viewModel.mpvController.seekRelative(-15) }) { Icon(Icons.Rounded.Replay10, null, tint = TextPrimary) }
                            FilledIconButton(onClick = { viewModel.mpvController.togglePlayPause() }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = AmberCorona, contentColor = OledObsidian)) {
                                Icon(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null)
                            }
                            IconButton(onClick = { viewModel.mpvController.seekRelative(30) }) { Icon(Icons.Rounded.Forward30, null, tint = TextPrimary) }
                            if (state.chapterCount > 0) TextButton(onClick = { viewModel.mpvController.skipChapter(1) }) { Text("Chapter+", color = AmberCorona) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = { subs = !subs }) { Text("Subs") }
                            OutlinedButton(onClick = { viewModel.mpvController.toggleHardwareDecoderFallback() }) { Text(if (state.isHwDecoding) "HW" else "SW") }
                        }
                    }
                    if (subs) {
                        var subUrl by remember { mutableStateOf("") }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Slider(value = state.subtitleStyle.scale, onValueChange = { viewModel.mpvController.updateSubtitleStyle(state.subtitleStyle.copy(scale = it)) }, valueRange = 0.6f..1.8f, modifier = Modifier.weight(1f))
                            OutlinedTextField(value = subUrl, onValueChange = { subUrl = it }, placeholder = { Text("Sub URL") }, singleLine = true, modifier = Modifier.weight(1f))
                            Button(onClick = { if (subUrl.isNotBlank()) { viewModel.mpvController.addExternalSubtitle(subUrl); subUrl = "" } }, colors = ButtonDefaults.buttonColors(containerColor = AmberCorona, contentColor = OledObsidian)) { Text("Load") }
                        }
                    }
                }
            }
        }

        if (qr) {
            AlertDialog(
                onDismissRequest = { qr = false },
                containerColor = ObsidianSurface,
                title = { Text("LAN Companion Remote", color = TextPrimary) },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        meta.companionQr?.let { Image(bitmap = it.asImageBitmap(), contentDescription = null, modifier = Modifier.size(180.dp)) }
                        Text(meta.companionUrl, color = AmberCorona, style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = { TextButton(onClick = { qr = false }) { Text("Close", color = AmberCorona) } }
            )
        }
    }
}
