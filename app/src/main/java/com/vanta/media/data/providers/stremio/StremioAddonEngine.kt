package com.vanta.media.data.providers.stremio

import com.vanta.media.data.local.dao.VantaMediaDao
import com.vanta.media.data.local.entity.MediaSourceLinkEntity
import com.vanta.media.data.local.entity.MediaSourceType
import com.vanta.media.data.security.SecureVault
import com.vanta.media.domain.metadata.MetadataResolver
import com.vanta.media.domain.metadata.RawMediaHint
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
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
    private val dao: VantaMediaDao,
    private val metadataResolver: MetadataResolver
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun getInstalledAddons(): List<InstalledAddon> {
        val raw = secureVault.getSecret("stremio_addons_json") ?: return emptyList()
        return runCatching { json.decodeFromString<List<InstalledAddon>>(raw) }.getOrDefault(emptyList())
    }

    suspend fun installAddonFromManifest(manifestUrl: String): Result<InstalledAddon> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanManifest = manifestUrl.trim().replace("stremio://", "https://")
            val body = httpGet(cleanManifest) ?: error("Unable to reach manifest")
            val obj = json.parseToJsonElement(body).jsonObject
            val id = obj["id"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString()
            val name = obj["name"]?.jsonPrimitive?.content ?: "Stremio Addon"
            val baseUrl = cleanManifest.substringBeforeLast("/manifest.json").trimEnd('/')
            val addon = InstalledAddon(id, name, baseUrl, name.contains("Nuvio", true) || cleanManifest.contains("nuvio", true))

            val updated = getInstalledAddons().filterNot { it.id == addon.id } + addon
            secureVault.putSecret("stremio_addons_json", json.encodeToString(updated))

            // Pull initial catalog items if the addon advertises catalogs
            val catalogs = obj["catalogs"]?.jsonArray.orEmpty()
            for (catEl in catalogs.take(2)) {
                val cObj = catEl.jsonObject
                val cType = cObj["type"]?.jsonPrimitive?.content ?: continue
                val cId = cObj["id"]?.jsonPrimitive?.content ?: continue
                syncAddonCatalog(addon, cType, cId)
            }
            addon
        }
    }

    private suspend fun syncAddonCatalog(addon: InstalledAddon, type: String, catalogId: String) {
        val body = httpGet("${addon.baseUrl}/catalog/$type/$catalogId.json") ?: return
        val metas = json.parseToJsonElement(body).jsonObject["metas"]?.jsonArray ?: return
        for (mEl in metas.take(15)) {
            val m = mEl.jsonObject
            val imdb = m["imdb_id"]?.jsonPrimitive?.content ?: m["id"]?.jsonPrimitive?.content?.takeIf { it.startsWith("tt") }
            val name = m["name"]?.jsonPrimitive?.content ?: continue
            val poster = m["poster"]?.jsonPrimitive?.content
            val isSeries = type == "series"
            val unified = metadataResolver.resolveAndPersist(
                RawMediaHint(
                    rawTitleOrFilename = if (isSeries) "$name S01E01" else name,
                    knownImdbId = imdb,
                    season = if (isSeries) 1 else null,
                    episode = if (isSeries) 1 else null,
                    fallbackPosterUrl = poster
                )
            )
            if (!imdb.isNullOrBlank()) {
                queryStreamsInParallel(unified.canonicalKey, imdb, isSeries, if (isSeries) 1 else null, if (isSeries) 1 else null)
            }
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
                    val body = httpGet("${addon.baseUrl}/stream/$type/$streamId.json") ?: return@runCatching emptyList()
                    val streams = json.parseToJsonElement(body).jsonObject["streams"]?.jsonArray ?: return@runCatching emptyList()
                    streams.mapNotNull { el ->
                        val sObj = el.jsonObject
                        val streamUrl = sObj["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val label = sObj["title"]?.jsonPrimitive?.content ?: sObj["name"]?.jsonPrimitive?.content ?: addon.name
                        val headers = mutableMapOf<String, String>()
                        sObj["behaviorHints"]?.jsonObject?.get("proxyHeaders")?.jsonObject?.get("request")?.jsonObject
                            ?.forEach { (k, v) -> headers[k] = v.jsonPrimitive.content }

                        dao.upsertSourceLink(
                            MediaSourceLinkEntity(
                                sourceLinkId = "addon_${addon.id}_${streamUrl.hashCode()}",
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

    private fun httpGet(url: String): String? = runCatching {
        okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        }
    }.getOrNull()
}
