package com.vanta.media.player.engine

import android.content.Context
import android.view.Surface
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.jdtech.mpv.MPVLib
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

data class PlayerPipelineStats(
    val videoCodec: String = "HEVC / H.265",
    val hwDecoder: String = "mediacodec-copy",
    val fps: String = "23.976",
    val resolution: String = "3840x2160",
    val cachePercent: Int = 100,
    val droppedFrames: Int = 0
)

data class SubtitleStyleConfig(
    val scale: Float = 1.0f,
    val hexColor: String = "#FFFFFFFF",
    val delaySeconds: Double = 0.0
)

data class PlaybackUiState(
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val chapterTitle: String? = null,
    val chapterCount: Int = 0,
    val currentChapter: Int = 0,
    val isHwDecoding: Boolean = true,
    val showStatsOverlay: Boolean = false,
    val stats: PlayerPipelineStats = PlayerPipelineStats(),
    val subtitleStyle: SubtitleStyleConfig = SubtitleStyleConfig(),
    val watchdogAlert: String? = null
)

@Singleton
class VantaMpvController @Inject constructor(
    @ApplicationContext private val context: Context
) : MPVLib.EventObserver {

    private var initialized = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watchdogJob: Job? = null
    private var lastPosMs = -1L
    private var stallSec = 0

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    fun ensureInitialized() {
        if (initialized) return
        MPVLib.create(context)
        MPVLib.setOptionString("config", "no")
        MPVLib.setOptionString("vo", "gpu")
        MPVLib.setOptionString("gpu-context", "android")
        MPVLib.setOptionString("opengl-es", "yes")
        MPVLib.setOptionString("hwdec", "mediacodec-copy")
        MPVLib.setOptionString("hwdec-codecs", "all")
        MPVLib.setOptionString("cache", "yes")
        MPVLib.setOptionString("demuxer-max-bytes", "157286400")
        MPVLib.setOptionString("demuxer-max-back-bytes", "52428800")
        MPVLib.setOptionString("keep-open", "yes")
        MPVLib.setOptionString("sub-auto", "fuzzy")
        MPVLib.init()

        MPVLib.addObserver(this)
        MPVLib.observeProperty("time-pos", MPVLib.mpvFormat.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty("duration", MPVLib.mpvFormat.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty("pause", MPVLib.mpvFormat.MPV_FORMAT_FLAG)
        MPVLib.observeProperty("chapter", MPVLib.mpvFormat.MPV_FORMAT_INT64)
        MPVLib.observeProperty("chapters", MPVLib.mpvFormat.MPV_FORMAT_INT64)
        initialized = true
    }

    fun attachSurface(surface: Surface) {
        ensureInitialized()
        MPVLib.attachSurface(surface)
        MPVLib.setPropertyString("vid", "auto")
    }

    fun detachSurface() {
        if (!initialized) return
        MPVLib.setPropertyString("vid", "no")
        MPVLib.detachSurface()
    }

    fun loadStream(url: String, headers: Map<String, String> = emptyMap(), externalSubs: List<String> = emptyList(), resumePositionMs: Long = 0L) {
        ensureInitialized()
        if (headers.isNotEmpty()) {
            MPVLib.setOptionString("http-header-fields", headers.entries.joinToString(",") { "${it.key}: ${it.value}" })
        }
        if (resumePositionMs > 5_000L) {
            MPVLib.setOptionString("start", "+${resumePositionMs / 1000.0}")
        }
        MPVLib.command(arrayOf("loadfile", url, "replace"))
        externalSubs.forEach { MPVLib.command(arrayOf("sub-add", it, "select")) }
        startWatchdog()
    }

    fun togglePlayPause() {
        if (!initialized) return
        MPVLib.setPropertyBoolean("pause", !(MPVLib.getPropertyBoolean("pause") ?: false))
    }

    fun seekTo(posMs: Long) {
        if (initialized) MPVLib.command(arrayOf("seek", (posMs / 1000.0).toString(), "absolute"))
    }

    fun seekRelative(sec: Int) {
        if (initialized) MPVLib.command(arrayOf("seek", sec.toString(), "relative"))
    }

    fun skipChapter(delta: Int) {
        if (initialized) MPVLib.command(arrayOf("add", "chapter", delta.toString()))
    }

    fun toggleHardwareDecoderFallback() {
        if (!initialized) return
        val nextHw = !_state.value.isHwDecoding
        MPVLib.setPropertyString("hwdec", if (nextHw) "mediacodec-copy" else "no")
        _state.update { it.copy(isHwDecoding = nextHw, watchdogAlert = null) }
    }

    fun updateSubtitleStyle(style: SubtitleStyleConfig) {
        if (!initialized) return
        MPVLib.setPropertyDouble("sub-scale", style.scale.toDouble())
        MPVLib.setPropertyString("sub-color", style.hexColor)
        MPVLib.setPropertyDouble("sub-delay", style.delaySeconds)
        _state.update { it.copy(subtitleStyle = style) }
    }

    fun addExternalSubtitle(subUrl: String) {
        if (initialized) MPVLib.command(arrayOf("sub-add", subUrl, "select"))
    }

    fun toggleStatsOverlay() {
        refreshStats()
        _state.update { it.copy(showStatsOverlay = !it.showStatsOverlay) }
    }

    private fun refreshStats() {
        if (!initialized) return
        _state.update {
            it.copy(
                stats = PlayerPipelineStats(
                    videoCodec = MPVLib.getPropertyString("video-codec") ?: "HEVC",
                    hwDecoder = MPVLib.getPropertyString("hwdec-current") ?: "software",
                    fps = MPVLib.getPropertyDouble("estimated-vf-fps")?.let { f -> String.format("%.3f", f) } ?: "24.000",
                    resolution = "${MPVLib.getPropertyInt("width") ?: 1920}x${MPVLib.getPropertyInt("height") ?: 1080}",
                    cachePercent = MPVLib.getPropertyInt("cache-buffering-state") ?: 100,
                    droppedFrames = MPVLib.getPropertyInt("frame-drop-count") ?: 0
                )
            )
        }
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        stallSec = 0
        watchdogJob = scope.launch {
            while (isActive) {
                delay(2_000L)
                val cur = _state.value
                if (cur.isPlaying) {
                    if (cur.positionMs == lastPosMs) {
                        stallSec += 2
                        if (stallSec >= 8 && cur.isHwDecoding) {
                            toggleHardwareDecoderFallback()
                            _state.update { it.copy(watchdogAlert = "Switched to SW decoder") }
                            stallSec = 0
                        }
                    } else {
                        stallSec = 0
                        lastPosMs = cur.positionMs
                    }
                    if (cur.showStatsOverlay) refreshStats()
                }
            }
        }
    }

    override fun eventProperty(property: String) {}
    override fun eventProperty(property: String, value: Long) {
        if (property == "chapter") _state.update { it.copy(currentChapter = value.toInt()) }
        if (property == "chapters") _state.update { it.copy(chapterCount = value.toInt()) }
    }
    override fun eventProperty(property: String, value: Double) {
        if (property == "time-pos") _state.update { it.copy(positionMs = (value * 1000).toLong()) }
        if (property == "duration") _state.update { it.copy(durationMs = (value * 1000).toLong()) }
    }
    override fun eventProperty(property: String, value: Boolean) {
        if (property == "pause") _state.update { it.copy(isPlaying = !value) }
    }
    override fun eventProperty(property: String, value: String) {}
    override fun event(eventId: Int) {
        if (eventId == MPVLib.MPV_EVENT_FILE_LOADED) refreshStats()

    }
}
