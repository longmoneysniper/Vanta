package com.vanta.media.data.backup

import android.content.Context
import android.net.Uri
import com.vanta.media.data.security.SecureVault
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class VantaBackupSnapshot(
    val version: Int = 1,
    val exportedAtMs: Long = System.currentTimeMillis(),
    val jellyfinServersJson: String? = null,
    val stremioAddonsJson: String? = null,
    val kodiAddonsJson: String? = null,
    val tmdbApiKey: String? = null,
    val rdApiToken: String? = null,
    val punchPlayToken: String? = null
)

@Singleton
class SafBackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureVault: SecureVault
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    suspend fun exportBackupToUri(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val snapshot = VantaBackupSnapshot(
                jellyfinServersJson = secureVault.getSecret("jellyfin_servers_json"),
                stremioAddonsJson = secureVault.getSecret("stremio_addons_json"),
                kodiAddonsJson = secureVault.getSecret("kodi_addons_json"),
                tmdbApiKey = secureVault.getSecret("tmdb_api_key"),
                rdApiToken = secureVault.getSecret("rd_api_token"),
                punchPlayToken = secureVault.getSecret("punchplay_access_token")
            )
            val serialized = json.encodeToString(snapshot)
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(serialized.toByteArray(Charsets.UTF_8))
            } ?: error("Unable to open SAF output stream")
            Unit
        }
    }

    suspend fun restoreBackupFromUri(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val content = context.contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader().readText()
            } ?: error("Unable to read SAF backup file")

            val snapshot = json.decodeFromString<VantaBackupSnapshot>(content)
            snapshot.jellyfinServersJson?.let { secureVault.putSecret("jellyfin_servers_json", it) }
            snapshot.stremioAddonsJson?.let { secureVault.putSecret("stremio_addons_json", it) }
            snapshot.kodiAddonsJson?.let { secureVault.putSecret("kodi_addons_json", it) }
            snapshot.tmdbApiKey?.let { secureVault.putSecret("tmdb_api_key", it) }
            snapshot.rdApiToken?.let { secureVault.putSecret("rd_api_token", it) }
            snapshot.punchPlayToken?.let { secureVault.putSecret("punchplay_access_token", it) }
            Unit
        }
    }
}
