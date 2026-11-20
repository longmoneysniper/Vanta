package com.vanta.media.ui.sources

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vanta.media.data.local.dao.VantaMediaDao
import com.vanta.media.data.local.entity.MediaSourceLinkEntity
import com.vanta.media.data.local.entity.MediaSourceType
import com.vanta.media.data.providers.debrid.DebridCloudItem
import com.vanta.media.data.providers.debrid.DebridCloudRepository
import com.vanta.media.data.providers.jellyfin.JellyfinRepository
import com.vanta.media.data.providers.jellyfin.JellyfinServerProfile
import com.vanta.media.data.providers.jellyfin.QuickConnectChallenge
import com.vanta.media.data.providers.stremio.InstalledAddon
import com.vanta.media.data.providers.stremio.StremioAddonEngine
import com.vanta.media.domain.metadata.MetadataResolver
import com.vanta.media.domain.metadata.RawMediaHint
import com.vanta.media.player.proxy.LocalRangeProxyServer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SourcesUiState(
    val jellyfinServers: List<JellyfinServerProfile> = emptyList(),
    val quickConnect: QuickConnectChallenge? = null,
    val installedAddons: List<InstalledAddon> = emptyList(),
    val debridItems: List<DebridCloudItem> = emptyList(),
    val hasDebridToken: Boolean = false,
    val statusMessage: String = "All Phase 4 Providers Ready"
)

@HiltViewModel
class SourcesViewModel @Inject constructor(
    private val jellyfinRepo: JellyfinRepository,
    private val stremioEngine: StremioAddonEngine,
    private val debridRepo: DebridCloudRepository,
    private val localProxy: LocalRangeProxyServer,
    private val metadataResolver: MetadataResolver,
    private val dao: VantaMediaDao
) : ViewModel() {

    private val _state = MutableStateFlow(SourcesUiState())
    val state: StateFlow<SourcesUiState> = _state.asStateFlow()

    init {
        refreshAll()
    }

    fun refreshAll() {
        _state.update {
            it.copy(
                jellyfinServers = jellyfinRepo.getSavedServers(),
                installedAddons = stremioEngine.getInstalledAddons(),
                hasDebridToken = debridRepo.hasRealDebridToken()
            )
        }
    }

    fun connectJellyfinPassword(url: String, user: String, pass: String) {
        viewModelScope.launch {
            _state.update { it.copy(statusMessage = "Connecting to Jellyfin...") }
            jellyfinRepo.loginWithPassword(url, user, pass)
                .onSuccess { profile ->
                    val count = jellyfinRepo.syncServerLibraries(profile)
                    refreshAll()
                    _state.update { it.copy(statusMessage = "Synced $count items from ${profile.name}") }
                }
                .onFailure { err ->
                    _state.update { it.copy(statusMessage = "Jellyfin Login Error: ${err.message}") }
                }
        }
    }

    fun startJellyfinQuickConnect(url: String) {
        viewModelScope.launch {
            jellyfinRepo.initiateQuickConnect(url)
                .onSuccess { challenge ->
                    _state.update {
                        it.copy(
                            quickConnect = challenge,
                            statusMessage = "Enter Quick Connect Code ${challenge.code} on your Jellyfin dashboard"
                        )
                    }
                }
                .onFailure { err ->
                    _state.update { it.copy(statusMessage = "Quick Connect Error: ${err.message}") }
                }
        }
    }

    fun verifyQuickConnect(url: String) {
        val secret = _state.value.quickConnect?.secret ?: return
        viewModelScope.launch {
            jellyfinRepo.checkQuickConnect(url, secret)
                .onSuccess { profile ->
                    if (profile != null) {
                        val count = jellyfinRepo.syncServerLibraries(profile)
                        refreshAll()
                        _state.update { it.copy(quickConnect = null, statusMessage = "Quick Connect Verified! Synced $count items") }
                    } else {
                        _state.update { it.copy(statusMessage = "Waiting for Quick Connect approval...") }
                    }
                }
        }
    }

    fun installStremioAddon(manifestUrl: String) {
        viewModelScope.launch {
            stremioEngine.installAddonFromManifest(manifestUrl)
                .onSuccess { addon ->
                    refreshAll()
                    _state.update { it.copy(statusMessage = "Installed ${addon.name}") }
                }
                .onFailure { err ->
                    _state.update { it.copy(statusMessage = "Addon Error: ${err.message}") }
                }
        }
    }

    fun saveAndSyncDebrid(token: String) {
        viewModelScope.launch {
            debridRepo.saveRealDebridToken(token)
            refreshAll()
            debridRepo.fetchAndIndexCloudDownloads()
                .onSuccess { list ->
                    _state.update { it.copy(debridItems = list, statusMessage = "Indexed ${list.size} Debrid cloud files") }
                }
                .onFailure { err ->
                    _state.update { it.copy(statusMessage = "Debrid Sync Error: ${err.message}") }
                }
        }
    }

    fun indexLocalVideoUri(uri: Uri, displayName: String) {
        viewModelScope.launch {
            val proxyUrl = localProxy.registerSafUri(uri)
            val unified = metadataResolver.resolveAndPersist(RawMediaHint(rawTitleOrFilename = displayName))
            dao.upsertSourceLink(
                MediaSourceLinkEntity(
                    sourceLinkId = "saf_${uri.hashCode()}",
                    canonicalKey = unified.canonicalKey,
                    sourceType = MediaSourceType.LOCAL_SMB,
                    serverId = "device-storage",
                    displayLabel = "Local SAF Proxy • $displayName",
                    streamUrl = proxyUrl
                )
            )
            _state.update { it.copy(statusMessage = "Added $displayName via Local Range Proxy") }
        }
    }
}
