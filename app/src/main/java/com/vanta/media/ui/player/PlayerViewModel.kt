package com.vanta.media.ui.player

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vanta.media.data.local.dao.VantaMediaDao
import com.vanta.media.data.local.entity.MediaSourceLinkEntity
import com.vanta.media.data.local.entity.MediaType
import com.vanta.media.data.local.entity.UnifiedMediaEntity
import com.vanta.media.data.providers.stremio.StremioAddonEngine
import com.vanta.media.player.companion.LanCompanionServer
import com.vanta.media.player.engine.VantaMpvController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

data class PlayerScreenMetadata(
    val media: UnifiedMediaEntity? = null,
    val link: MediaSourceLinkEntity? = null,
    val companionUrl: String = "",
    val companionQr: Bitmap? = null
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val dao: VantaMediaDao,
    val mpvController: VantaMpvController,
    private val companionServer: LanCompanionServer,
    private val stremioEngine: StremioAddonEngine
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }
    private val _meta = MutableStateFlow(PlayerScreenMetadata())
    val meta: StateFlow<PlayerScreenMetadata> = _meta.asStateFlow()

    fun loadFromRoom(canonicalKey: String, sourceLinkId: String) {
        viewModelScope.launch {
            val media = dao.getMediaByKey(canonicalKey)
            var link = dao.getSourceLink(canonicalKey, sourceLinkId)
                ?: dao.getSourceLinksForMedia(canonicalKey).firstOrNull()

            if (link == null && media?.imdbId != null) {
                stremioEngine.queryStreamsInParallel(
                    canonicalKey = canonicalKey,
                    imdbId = media.imdbId,
                    isSeries = media.mediaType == MediaType.EPISODE,
                    season = media.seasonNumber,
                    episode = media.episodeNumber
                )
                link = dao.getSourceLinksForMedia(canonicalKey).firstOrNull()
            }

            val url = companionServer.start()
            val qr = companionServer.generateQrBitmap(url)
            _meta.value = PlayerScreenMetadata(media, link, url, qr)

            if (link != null) {
                val headers = runCatching {
                    json.decodeFromString<Map<String, String>>(link.customHeadersJson)
                }.getOrDefault(emptyMap())
                val subs = runCatching {
                    json.decodeFromString<List<String>>(link.externalSubtitlesJson)
                }.getOrDefault(emptyList())

                mpvController.loadStream(
                    url = link.streamUrl,
                    headers = headers,
                    externalSubs = subs,
                    resumePositionMs = media?.playbackPositionMs ?: 0L
                )
            }
        }
    }

    fun persistProgress(canonicalKey: String) {
        viewModelScope.launch {
            val state = mpvController.state.value
            if (state.durationMs > 0L) {
                dao.updatePlaybackProgress(canonicalKey, state.positionMs, state.durationMs)
            }
        }
    }
}
