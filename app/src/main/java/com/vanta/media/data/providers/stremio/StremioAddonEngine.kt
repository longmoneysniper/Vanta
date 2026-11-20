package com.vanta.media.data.providers.stremio

import com.vanta.media.data.local.dao.VantaMediaDao
import com.vanta.media.data.local.entity.MediaSourceLinkEntity
import com.vanta.media.data.local.entity.MediaSourceType
import com.vanta.media.data.security.SecureVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class InstalledAddon(
    val id: String,
    val name: String,
    val baseUrl: String,
    val isNuvio: Boolean = false
)

data class ResolvedAddonStream(
    val addonName: String,
    val title: String,
    val streamUrl: String,
    val headers: Map<String, String>
)

@Singleton
class StremioAddonEngine @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val secureVault: SecureVault,
    private val dao: VantaMediaDao
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun getInstalledAddons(): List<InstalledAddon> {
        val raw = secureVault.getSecret("stremio_addons_json") ?: return emptyList()
        return runCatching { json.decodeFromString<List<InstalledAddon>>(raw) }.getOrDefault(emptyList())
    }

    suspend fun installAddonFromManifest(manifestUrl: String): Result<InstalledAddon> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanManifest = manifestUrl.trim()
            val req = Request.Builder().url(cleanManifest).build()
            val body = okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                resp.body?.string() ?: error("Empty manifest")
            }
            val obj = json.parseToJsonElement(body).jsonObject
            val id = obj["id"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString()
            val name = obj["name"]?.jsonPrimitive?.content ?: "Stremio Addon"
            val baseUrl = cleanManifest.substringBeforeLast("/manifest.json").trimEnd('/')
            val addon = InstalledAddon(
                id = id,
                name = name,
                baseUrl = baseUrl,
                isNuvio = name.contains("Nuvio", ignoreCase = true) || cleanManifest.contains("nuvio", ignoreCase = true)
            )
            val updated = getInstalledAddons().filterNot { it.id == addon.id } + addon
            secureVault.putSecret("stremio_addons_json", json.encodeToString(updated))
            addon
        }
    }

    suspend fun queryStreamsInParallel(
        canonicalKey: String,
        imdbId: String,
        isSeries: Boolean,
        season: Int? = null,
        episode: Int? = null
    ): List<ResolvedAddonStream> = coroutineScope {
        val addons = getInstalledAddons()
        val type = if (isSeries) "series" else "movie"
        val streamId = if (isSeries && season != null && episode != null) "$imdbId:$season:$episode" else imdbId

        addons.map { addon ->
            async(Dispatchers.IO) {
                runCatching {
                    val url = "${addon.baseUrl}/stream/$type/$streamId.json"
                    val req = Request.Builder().url(url).build()
                    val body = okHttpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@runCatching emptyList()
                        resp.body?.string() ?: return@runCatching emptyList()
                    }
                    val root = json.parseToJsonElement(body).jsonObject
                    val streams = root["streams"]?.jsonArray ?: return@runCatching emptyList()

                    streams.mapNotNull { el ->
                        val sObj = el.jsonObject
                        val streamUrl = sObj["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val label = sObj["title"]?.jsonPrimitive?.content
                            ?: sObj["name"]?.jsonPrimitive?.content
                            ?: addon.name

                        val headers = mutableMapOf<String, String>()
                        sObj["behaviorHints"]?.jsonObject
                            ?.get("proxyHeaders")?.jsonObject
                            ?.get("request")?.jsonObject
                            ?.forEach { (k, v) -> headers[k] = v.jsonPrimitive.content }

                        val linkId = "addon_${addon.id}_${streamUrl.hashCode()}"
                        dao.upsertSourceLink(
                            MediaSourceLinkEntity(
                                sourceLinkId = linkId,
                                canonicalKey = canonicalKey,
                                sourceType = if (addon.isNuvio) MediaSourceType.NUVIO else MediaSourceType.STREMIO,
                                serverId = addon.id,
                                displayLabel = "${addon.name} • ${label.replace("\n", " ")}",
                                streamUrl = streamUrl,
                                customHeadersJson = json.encodeToString(headers)
                            )
                        )
                        ResolvedAddonStream(addon.name, label, streamUrl, headers)
                    }
                }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten()
    }
}
