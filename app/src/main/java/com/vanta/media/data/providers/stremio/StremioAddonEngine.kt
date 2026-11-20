package com.vanta.media.data.providers.stremio

import com.vanta.media.data.local.dao.VantaMediaDao
import com.vanta.media.data.local.entity.*
import com.vanta.media.data.security.SecureVault
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
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

    fun getInstalledAddons(): List<InstalledAddon> =
        secureVault.getSecret("stremio_addons_json")
            ?.let { runCatching { json.decodeFromString<List<InstalledAddon>>(it) }.getOrNull() }
            .orEmpty()

    suspend fun installAddonFromManifest(manifestUrl: String): Result<InstalledAddon> = withContext(Dispatchers.IO) {
        runCatching {
            val clean = manifestUrl.trim().replace("stremio://", "https://")
            val body = httpGet(clean) ?: error("Cannot reach manifest")
            val obj = json.parseToJsonElement(body).jsonObject
            val id = obj["id"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString()
            val name = obj["name"]?.jsonPrimitive?.content ?: "AIOMetadata"
            val base = clean.substringBeforeLast("/manifest.json").trimEnd('/')
            val addon = InstalledAddon(id, name, base, name.contains("Nuvio", true) || clean.contains("nuvio", true))

            val updated = getInstalledAddons().filterNot { it.id == addon.id } + addon
            secureVault.putSecret("stremio_addons_json", json.encodeToString(updated))

            // Fetch ALL catalogs from AIOMetadata in parallel
            val catalogs = obj["catalogs"]?.jsonArray.orEmpty()
            coroutineScope {
                catalogs.mapIndexed { orderIdx, catEl ->
                    async(Dispatchers.IO) {
                        val c = catEl.jsonObject
                        val cType = c["type"]?.jsonPrimitive?.content ?: return@async
                        val cId = c["id"]?.jsonPrimitive?.content ?: return@async
                        val cName = c["name"]?.jsonPrimitive?.content ?: cId.replaceFirstChar { it.uppercase() }
                        val searchRequired = c["extra"]?.jsonArray?.any {
                            it.jsonObject["name"]?.jsonPrimitive?.content == "search" &&
                                it.jsonObject["isRequired"]?.jsonPrimitive?.booleanOrNull == true
                        } == true
                        if (!searchRequired) {
                            syncAddonCatalog(addon, cType, cId, cName, orderIdx)
                        }
                    }
                }.awaitAll()
            }
            addon
        }
    }

    private suspend fun syncAddonCatalog(
        addon: InstalledAddon,
        type: String,
        catalogId: String,
        rowTitle: String,
        orderIdx: Int
    ) {
        val body = httpGet("${addon.baseUrl}/catalog/$type/$catalogId.json") ?: return
        val metas = json.parseToJsonElement(body).jsonObject["metas"]?.jsonArray ?: return
        val uniqueRowId = "${addon.id}:$type:$catalogId"

        metas.take(25).forEachIndexed { index, mEl ->
            val m = mEl.jsonObject
            val rawId = m["id"]?.jsonPrimitive?.content ?: return@forEachIndexed
            val imdb = m["imdb_id"]?.jsonPrimitive?.content ?: rawId.takeIf { it.startsWith("tt") }
            val tmdb = rawId.removePrefix("tmdb:").toIntOrNull()
            val name = m["name"]?.jsonPrimitive?.content ?: return@forEachIndexed
            val poster = m["poster"]?.jsonPrimitive?.content
            val bg = m["background"]?.jsonPrimitive?.content ?: poster
            val logo = m["logo"]?.jsonPrimitive?.content
            val rating = m["imdbRating"]?.jsonPrimitive?.content?.toFloatOrNull()
            val genre = m["genres"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content
                ?: m["genre"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content
            val year = m["releaseInfo"]?.jsonPrimitive?.content?.take(4)?.toIntOrNull()
            val isSeries = type == "series"
            val baseMediaKey = when {
                tmdb != null && isSeries -> "tv:$tmdb:s01:e01"
                tmdb != null -> "movie:$tmdb"
                !imdb.isNullOrBlank() -> "imdb:$imdb"
                else -> "aio:${rawId.hashCode()}"
            }
            // Scope key per catalog row so items appearing in multiple catalogs populate every row
            val rowScopedKey = "$uniqueRowId::$baseMediaKey"

            dao.upsertMedia(
                UnifiedMediaEntity(
                    canonicalKey = rowScopedKey,
                    tmdbId = tmdb,
                    imdbId = imdb,
                    mediaType = if (isSeries) MediaType.EPISODE else MediaType.MOVIE,
                    title = name,
                    seriesTitle = if (isSeries) name else null,
                    seriesGroupKey = if (isSeries) "series:$baseMediaKey" else null,
                    seasonNumber = if (isSeries) 1 else null,
                    episodeNumber = if (isSeries) 1 else null,
                    episodeTitle = m["releaseInfo"]?.jsonPrimitive?.content ?: m["description"]?.jsonPrimitive?.content?.take(60),
                    overview = m["description"]?.jsonPrimitive?.content.orEmpty(),
                    posterUrl = poster,
                    backdropUrl = bg,
                    episodeStillUrl = bg,
                    logoUrl = logo,
                    catalogId = uniqueRowId,
                    catalogRow = rowTitle,
                    catalogOrder = orderIdx,
                    genres = genre,
                    releaseYear = year,
                    rating = rating,
                    addedAt = System.currentTimeMillis() - (orderIdx * 10_000L) - (index * 10L)
                )
            )
        }
    }

    suspend fun searchCatalogs(query: String): List<UnifiedMediaEntity> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        getInstalledAddons().flatMap { addon ->
            listOf("movie", "series").flatMap { type ->
                val body = httpGet("${addon.baseUrl}/catalog/$type/top/search=$q.json") ?: return@flatMap emptyList()
                val metas = json.parseToJsonElement(body).jsonObject["metas"]?.jsonArray ?: return@flatMap emptyList()
                metas.take(12).mapNotNull { el ->
                    val m = el.jsonObject
                    val rawId = m["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val imdb = m["imdb_id"]?.jsonPrimitive?.content ?: rawId.takeIf { it.startsWith("tt") }
                    val name = m["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val poster = m["poster"]?.jsonPrimitive?.content
                    val isSeries = type == "series"
                    val key = if (!imdb.isNullOrBlank()) "search:imdb:$imdb" else "search:aio:${rawId.hashCode()}"
                    val entity = UnifiedMediaEntity(
                        canonicalKey = key, tmdbId = null, imdbId = imdb,
                        mediaType = if (isSeries) MediaType.EPISODE else MediaType.MOVIE,
                        title = name, posterUrl = poster, backdropUrl = m["background"]?.jsonPrimitive?.content ?: poster,
                        logoUrl = m["logo"]?.jsonPrimitive?.content,
                        catalogId = "search", catalogRow = "Search Results",
                        genres = m["genres"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content,
                        rating = m["imdbRating"]?.jsonPrimitive?.content?.toFloatOrNull()
                    )
                    dao.upsertMedia(entity)
                    entity
                }
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
        val type = if (isSeries) "series" else "movie"
        val streamId = if (isSeries && season != null && episode != null) "$imdbId:$season:$episode" else imdbId

        getInstalledAddons().map { addon ->
            async(Dispatchers.IO) {
                runCatching {
                    val body = httpGet("${addon.baseUrl}/stream/$type/$streamId.json") ?: return@runCatching emptyList()
                    val streams = json.parseToJsonElement(body).jsonObject["streams"]?.jsonArray ?: return@runCatching emptyList()
                    streams.mapNotNull { el ->
                        val s = el.jsonObject
                        val url = s["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val label = s["title"]?.jsonPrimitive?.content ?: s["name"]?.jsonPrimitive?.content ?: addon.name
                        val headers = mutableMapOf<String, String>()
                        s["behaviorHints"]?.jsonObject?.get("proxyHeaders")?.jsonObject?.get("request")?.jsonObject
                            ?.forEach { (k, v) -> headers[k] = v.jsonPrimitive.content }

                        dao.upsertSourceLink(
                            MediaSourceLinkEntity(
                                sourceLinkId = "addon_${addon.id}_${url.hashCode()}",
                                canonicalKey = canonicalKey,
