package com.vanta.media.data.providers.kodi

import android.content.Context
import com.chaquo.python.Python
import com.vanta.media.data.security.SecureVault
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class KodiInstalledAddon(
    val id: String,
    val name: String,
    val version: String
)

@Singleton
class KodiChaquopyBridge @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureVault: SecureVault
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun getInstalledKodiAddons(): List<KodiInstalledAddon> {
        val raw = secureVault.getSecret("kodi_addons_json") ?: return emptyList()
        return runCatching { json.decodeFromString<List<KodiInstalledAddon>>(raw) }.getOrDefault(emptyList())
    }

    suspend fun installRepositoryZip(zipUrl: String): Result<List<KodiInstalledAddon>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val py = Python.getInstance()
                val module = py.getModule("kodi_bridge")
                val targetDir = File(context.filesDir, "kodi_addons").absolutePath
                val rawJson = module.callAttr("install_kodi_repo_zip", zipUrl.trim(), targetDir).toString()
                val obj = json.parseToJsonElement(rawJson).jsonObject
                val parsed = obj["addons"]?.jsonArray?.mapNotNull { el ->
                    val item = el.jsonObject
                    KodiInstalledAddon(
                        id = item["id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                        name = item["name"]?.jsonPrimitive?.content ?: "Kodi Addon",
                        version = item["version"]?.jsonPrimitive?.content ?: "1.0.0"
                    )
                }.orEmpty()

                val merged = (getInstalledKodiAddons() + parsed).distinctBy { it.id }
                secureVault.putSecret("kodi_addons_json", json.encodeToString(merged))
                merged
            }
        }
}
