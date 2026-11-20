package com.vanta.media.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vanta.media.data.local.dao.VantaMediaDao
import com.vanta.media.data.local.entity.MediaSourceLinkEntity
import com.vanta.media.data.local.entity.MediaSourceType
import com.vanta.media.data.local.entity.UnifiedMediaEntity
import com.vanta.media.data.security.SecureVault
import com.vanta.media.domain.metadata.MetadataResolver
import com.vanta.media.domain.metadata.RawMediaHint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val continueWatching: List<UnifiedMediaEntity> = emptyList(),
    val libraryItems: List<UnifiedMediaEntity> = emptyList(),
    val kidsModeEnabled: Boolean = false,
    val hasTmdbKey: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val dao: VantaMediaDao,
    private val secureVault: SecureVault,
    private val metadataResolver: MetadataResolver
) : ViewModel() {

    private val _hasTmdbKey = MutableStateFlow(!secureVault.getSecret("tmdb_api_key").isNullOrBlank())

    val uiState: StateFlow<HomeUiState> = combine(
        secureVault.kidsModeFlow,
        secureVault.kidsModeFlow.flatMapLatest { dao.observeContinueWatching(it) },
        secureVault.kidsModeFlow.flatMapLatest { dao.observeLibrary(it) },
        _hasTmdbKey
    ) { kidsMode, cw, lib, hasKey ->
        HomeUiState(
            continueWatching = cw,
            libraryItems = lib,
            kidsModeEnabled = kidsMode,
            hasTmdbKey = hasKey
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun saveTmdbApiKey(key: String) {
        secureVault.putSecret("tmdb_api_key", key.trim())
        _hasTmdbKey.value = key.trim().isNotBlank()
    }

    fun toggleKidsMode(enabled: Boolean) {
        viewModelScope.launch { secureVault.setKidsMode(enabled) }
    }

    fun ingestSampleItems() {
        viewModelScope.launch {
            val samples = listOf(
                RawMediaHint("Dune.Part.Two.2024.2160p.WEB-DL.mkv", knownTmdbId = 693134),
                RawMediaHint("Severance.S02E01.1080p.mkv", knownTmdbId = 95396, season = 2, episode = 1),
                RawMediaHint("Severance.S02E02.1080p.mkv", knownTmdbId = 95396, season = 2, episode = 2),
                RawMediaHint("Spider.Man.Across.the.Spider.Verse.2023.mkv", knownTmdbId = 569094)
            )
            samples.forEachIndexed { idx, hint ->
                val entity = metadataResolver.resolveAndPersist(hint)
                dao.upsertSourceLink(
                    MediaSourceLinkEntity(
                        sourceLinkId = "demo_link_$idx",
                        canonicalKey = entity.canonicalKey,
                        sourceType = MediaSourceType.LOCAL_SMB,
                        serverId = "local",
                        displayLabel = "4K HDR10 • Direct Play",
                        streamUrl = "https://googleapis.com"
                    )
                )
                if (entity.seasonNumber != null) {
                    dao.updatePlaybackProgress(
                        canonicalKey = entity.canonicalKey,
                        positionMs = (idx + 1) * 420_000L,
                        durationMs = 3_100_000L,
                        watchedAt = System.currentTimeMillis() + (idx * 1000L)
                    )
                }
            }
        }
    }
}
