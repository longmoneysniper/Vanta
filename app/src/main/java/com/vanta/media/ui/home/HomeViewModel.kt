package com.vanta.media.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vanta.media.data.local.dao.VantaMediaDao
import com.vanta.media.data.local.entity.UnifiedMediaEntity
import com.vanta.media.data.providers.jellyfin.JellyfinRepository
import com.vanta.media.data.providers.stremio.StremioAddonEngine
import com.vanta.media.data.security.SecureVault
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val continueWatching: List<UnifiedMediaEntity> = emptyList(),
    val libraryItems: List<UnifiedMediaEntity> = emptyList(),
    val kidsModeEnabled: Boolean = false,
    val isRefreshing: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    val dao: VantaMediaDao,
    val stremioEngine: StremioAddonEngine,
    private val jellyfinRepo: JellyfinRepository,
    private val secureVault: SecureVault
) : ViewModel() {

    private val _isRefreshing = MutableStateFlow(false)

    val uiState: StateFlow<HomeUiState> = combine(
        secureVault.kidsModeFlow,
        secureVault.kidsModeFlow.flatMapLatest { dao.observeContinueWatching(it) },
        secureVault.kidsModeFlow.flatMapLatest { dao.observeLibrary(it) },
        _isRefreshing
    ) { kidsMode, cw, lib, refreshing ->
        HomeUiState(
            continueWatching = cw,
            libraryItems = lib,
            kidsModeEnabled = kidsMode,
            isRefreshing = refreshing
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun saveTmdbApiKey(key: String) {
        secureVault.putSecret("tmdb_api_key", key.trim())
    }

    fun toggleKidsMode(enabled: Boolean) {
        viewModelScope.launch { secureVault.setKidsMode(enabled) }
    }

    fun refreshAllCatalogs() {
        viewModelScope.launch {
            _isRefreshing.value = true
            runCatching {
                jellyfinRepo.getSavedServers().forEach { jellyfinRepo.syncServerLibraries(it) }
                stremioEngine.getInstalledAddons().forEach {
                    stremioEngine.installAddonFromManifest("${it.baseUrl}/manifest.json")
                }
            }
            _isRefreshing.value = false
        }
    }
}
