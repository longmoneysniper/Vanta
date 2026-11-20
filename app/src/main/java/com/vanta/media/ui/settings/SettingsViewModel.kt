package com.vanta.media.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vanta.media.data.backup.SafBackupManager
import com.vanta.media.data.providers.kodi.KodiChaquopyBridge
import com.vanta.media.data.providers.kodi.KodiInstalledAddon
import com.vanta.media.data.security.SecureVault
import com.vanta.media.data.sync.PunchPlayDeviceCode
import com.vanta.media.data.sync.PunchPlaySyncRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val kidsModeEnabled: Boolean = false,
    val hasKidsPin: Boolean = false,
    val spoilerFreeEnabled: Boolean = false,
    val punchPlayLinked: Boolean = false,
    val punchPlayCode: PunchPlayDeviceCode? = null,
    val kodiAddons: List<KodiInstalledAddon> = emptyList(),
    val statusBanner: String = "Vanta v1.0.0 • All Engines Active"
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val secureVault: SecureVault,
    private val kodiBridge: KodiChaquopyBridge,
    private val punchPlayRepo: PunchPlaySyncRepository,
    private val safBackupManager: SafBackupManager
) : ViewModel() {

    private val _localState = MutableStateFlow(
        SettingsUiState(
            hasKidsPin = !secureVault.getSecret("kids_pin_code").isNullOrBlank(),
            punchPlayLinked = punchPlayRepo.isLinked(),
            kodiAddons = kodiBridge.getInstalledKodiAddons()
        )
    )

    val uiState: StateFlow<SettingsUiState> = combine(
        _localState,
        secureVault.kidsModeFlow,
        secureVault.spoilerFreeFlow
    ) { local, kids, spoiler ->
        local.copy(kidsModeEnabled = kids, spoilerFreeEnabled = spoiler)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setKidsPin(pin: String) {
        if (pin.length == 4) {
            secureVault.putSecret("kids_pin_code", pin)
            _localState.update { it.copy(hasKidsPin = true, statusBanner = "4-Digit Kids PIN Saved") }
        }
    }

    fun toggleKidsModeWithPinCheck(targetState: Boolean, enteredPin: String): Boolean {
        val savedPin = secureVault.getSecret("kids_pin_code")
        if (!targetState && !savedPin.isNullOrBlank() && savedPin != enteredPin) {
            _localState.update { it.copy(statusBanner = "Incorrect Kids PIN") }
            return false
        }
        viewModelScope.launch { secureVault.setKidsMode(targetState) }
        _localState.update { it.copy(statusBanner = if (targetState) "Kids Mode Locked ON" else "Kids Mode Unlocked") }
        return true
    }

    fun toggleSpoilerFree(enabled: Boolean) {
        viewModelScope.launch { secureVault.setSpoilerFree(enabled) }
    }

    fun installKodiRepo(zipUrl: String) {
        viewModelScope.launch {
            _localState.update { it.copy(statusBanner = "Installing Kodi Repository via Python 3.11...") }
            kodiBridge.installRepositoryZip(zipUrl)
                .onSuccess { list ->
                    _localState.update { it.copy(kodiAddons = list, statusBanner = "Installed ${list.size} Kodi Addon(s)") }
                }
                .onFailure { err ->
                    _localState.update { it.copy(statusBanner = "Kodi Zip Error: ${err.message}") }
                }
        }
    }

    fun startPunchPlayOAuth() {
        viewModelScope.launch {
            punchPlayRepo.requestDeviceCode("vanta-android-tv")
                .onSuccess { code ->
                    _localState.update { it.copy(punchPlayCode = code, statusBanner = "Enter code ${code.userCode} at ${code.verificationUrl}") }
                }
                .onFailure { err ->
                    _localState.update { it.copy(statusBanner = "PunchPlay OAuth: ${err.message}") }
                }
        }
    }

    fun savePunchPlayManualToken(token: String) {
        punchPlayRepo.saveAccessToken(token)
        _localState.update { it.copy(punchPlayLinked = true, statusBanner = "PunchPlay.tv Scrobbling Connected") }
    }

    fun exportSafBackup(uri: Uri) {
        viewModelScope.launch {
            safBackupManager.exportBackupToUri(uri)
                .onSuccess { _localState.update { it.copy(statusBanner = "SAF Backup Exported Successfully") } }
                .onFailure { err -> _localState.update { it.copy(statusBanner = "Export Error: ${err.message}") } }
        }
    }

    fun restoreSafBackup(uri: Uri) {
        viewModelScope.launch {
            safBackupManager.restoreBackupFromUri(uri)
                .onSuccess {
                    _localState.update {
                        it.copy(
                            kodiAddons = kodiBridge.getInstalledKodiAddons(),
                            punchPlayLinked = punchPlayRepo.isLinked(),
                            statusBanner = "SAF Backup Restored!"
                        )
                    }
                }
                .onFailure { err -> _localState.update { it.copy(statusBanner = "Restore Error: ${err.message}") } }
        }
    }
}
