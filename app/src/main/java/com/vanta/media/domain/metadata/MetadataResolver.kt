package com.vanta.media.domain.metadata

import com.vanta.media.data.local.dao.VantaMediaDao
import com.vanta.media.data.local.entity.*
import com.vanta.media.data.security.SecureVault
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.*
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class RawMediaHint(
    val rawTitleOrFilename: String,
    val knownTmdbId: Int? = null,
    val knownImdbId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val fallbackPosterUrl: String? = null
)

@Singleton
class MetadataResolver @Inject constructor(
    private val dao: VantaMediaDao,
    private val secureVault: SecureVault,
    private val okHttpClient: OkHttpClient
) {
    private val sem = Semaphore(4)
    private val mutexes = ConcurrentHashMap<String, Mutex>()
    private val json = Json { ignoreUnknownKeys = true }
    private val apiBase = "https://" + "api.themoviedb" + ".org/3/"
    private val imgBase = "https://" + "image.tmdb" + ".org/t/p/"

    suspend fun resolveAndPersist(hint: RawMediaHint): UnifiedMediaEntity = withContext(Dispatchers.IO) {
        val p = FilenameParser.parse(hint.rawTitleOrFilename)
        val s = hint.season ?: p.season
        val e = hint.episode ?: p.episode
        val isTv = s != null && e != null
        val key = when {
            !hint.knownImdbId.isNullOrBlank() -> "imdb:${hint.knownImdbId}"
            hint.knownTmdbId != null -> "tmdb:${hint.knownTmdbId}:${if (isTv) "tv" else "movie"}"
            else -> "q:${p.cleanTitle.lowercase()}:${p.year ?: ""}:${if (isTv) "tv" else "movie"}"
        }
        mutexes.computeIfAbsent(key) { Mutex() }.withLock {
            val apiKey = secureVault.getSecret("tmdb_api_key").orEmpty()
            if (apiKey.isBlank()) {
                val fb = fallback(p, s, e, hint.fallbackPosterUrl, hint.knownTmdbId, hint.knownImdbId)
                dao.upsertMedia(fb)
                return@withLock fb
            }

            val cached = dao.getTmdbCache(key)
            if (cached != null && !cached.isExpired() && cached.isNegativeMatch) {
                val fb = fallback(p, s, e, hint.fallbackPosterUrl, hint.knownTmdbId, hint.knownImdbId)
                dao.upsertMedia(fb)
                return@withLock fb
            }

            val tmdbId = hint.knownTmdbId
                ?: hint.knownImdbId?.let { bridgeImdb(it, isTv, apiKey) }
                ?: searchTmdb(p.cleanTitle, p.year, isTv, apiKey)

            if (tmdbId == null) {
                dao.upsertTmdbCache(TmdbLookupCacheEntity(key, null, if (isTv) MediaType.EPISODE else MediaType.MOVIE, null, System.currentTimeMillis(), true))
                val fb = fallback(p, s, e, hint.fallbackPosterUrl, null, hint.knownImdbId)
                dao.upsertMedia(fb)
                return@withLock fb
            }
            val entity = (if (isTv && s != null && e != null) fetchEp(tmdbId, s, e, apiKey) else fetchMovie(tmdbId, apiKey))
                ?: fallback(p, s, e, hint.fallbackPosterUrl, tmdbId, hint.knownImdbId)
            dao.upsertMedia(entity)
            dao.upsertTmdbCache(TmdbLookupCacheEntity(key, tmdbId, entity.mediaType, null, System.currentTimeMillis(), false))
            entity
        }
    }

    private suspend fun bridgeImdb(imdb: String, isTv: Boolean, key: String): Int? {
        val url = "${apiBase}find/$imdb".toHttpUrl().newBuilder().addQueryParameter("api_key", key).addQueryParameter("external_source", "imdb_id").build()
        val body = get(url.toString()) ?: return null
        return json.parseToJsonElement(body).jsonObject[if (isTv) "tv_results" else "movie_results"]?.jsonArray?.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.intOrNull
    }

    private suspend fun searchTmdb(q: String, year: Int?, isTv: Boolean, key: String): Int? {
        val url = "${apiBase}${if (isTv) "search/tv" else "search/movie"}".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", key).addQueryParameter("query", q)
            .apply { if (year != null) addQueryParameter(if (isTv) "first_air_date_year" else "year", year.toString()) }.build()
        val body = get(url.toString()) ?: return null
        return json.parseToJsonElement(body).jsonObject["results"]?.jsonArray?.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.intOrNull
    }

    private suspend fun fetchMovie(id: Int, key: String): UnifiedMediaEntity? {
        val body = get("${apiBase}movie/$id?api_key=$key") ?: return null
        val o = json.parseToJsonElement(body).jsonObject
        val title = o["title"]?.jsonPrimitive?.content ?: return null
        return UnifiedMediaEntity(
            canonicalKey = "movie:$id", tmdbId = id, imdbId = o["imdb_id"]?.jsonPrimitive?.content,
            mediaType = MediaType.MOVIE, title = title, overview = o["overview"]?.jsonPrimitive?.content.orEmpty(),
            posterUrl = o["poster_path"]?.jsonPrimitive?.content?.let { "${imgBase}w500$it" },
            backdropUrl = o["backdrop_path"]?.jsonPrimitive?.content?.let { "${imgBase}w1280$it" },
            releaseYear = o["release_date"]?.jsonPrimitive?.content?.take(4)?.toIntOrNull(),
            rating = o["vote_average"]?.jsonPrimitive?.floatOrNull, isFamilyFriendly = !(o["adult"]?.jsonPrimitive?.booleanOrNull ?: false)
        )
    }

    private suspend fun fetchEp(id: Int, s: Int, e: Int, key: String): UnifiedMediaEntity? {
        val showBody = get("${apiBase}tv/$id?api_key=$key") ?: return null
        val epBody = get("${apiBase}tv/$id/season/$s/episode/$e?api_key=$key") ?: return null
        val show = json.parseToJsonElement(showBody).jsonObject
        val ep = json.parseToJsonElement(epBody).jsonObject
        val series = show["name"]?.jsonPrimitive?.content ?: return null
        return UnifiedMediaEntity(
            canonicalKey = "tv:$id:s${s.toString().padStart(2, '0')}:e${e.toString().padStart(2, '0')}",
            tmdbId = id, imdbId = null, mediaType = MediaType.EPISODE, title = "$series • S${s}E${e}",
            seriesTitle = series, seriesGroupKey = "tv:$id", seasonNumber = s, episodeNumber = e,
            episodeTitle = ep["name"]?.jsonPrimitive?.content ?: "Episode $e", overview = ep["overview"]?.jsonPrimitive?.content.orEmpty(),
            posterUrl = show["poster_path"]?.jsonPrimitive?.content?.let { "${imgBase}w500$it" },
            backdropUrl = show["backdrop_path"]?.jsonPrimitive?.content?.let { "${imgBase}w1280$it" },
            episodeStillUrl = ep["still_path"]?.jsonPrimitive?.content?.let { "${imgBase}w780$it" },
            rating = ep["vote_average"]?.jsonPrimitive?.floatOrNull, isFamilyFriendly = !(show["adult"]?.jsonPrimitive?.booleanOrNull ?: false)
        )
    }

    private suspend fun get(url: String): String? = sem.withPermit {
        repeat(3) { i ->
            okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (r.code == 429) delay((r.header("Retry-After")?.toLongOrNull() ?: (i + 1L)) * 1000L)
                else if (r.isSuccessful) return@withPermit r.body?.string() else return@withPermit null
            }
        }
        null
    }

    private fun fallback(p: ParsedFilename, s: Int?, e: Int?, poster: String?, tmdbId: Int?, imdbId: String?): UnifiedMediaEntity {
        val slug = p.cleanTitle.lowercase().replace(Regex("[^a-z0-9]+"), "-")
        val isTv = s != null && e != null
        val key = when {
            tmdbId != null && isTv -> "tv:$tmdbId:s${s.toString().padStart(2, '0')}:e${e.toString().padStart(2, '0')}"
            tmdbId != null -> "movie:$tmdbId"
            !imdbId.isNullOrBlank() -> "imdb:$imdbId"
            isTv -> "unmatched:tv:$slug:s$s:e$e"
            else -> "unmatched:movie:$slug"
        }
        return UnifiedMediaEntity(
            canonicalKey = key, tmdbId = tmdbId, imdbId = imdbId, mediaType = if (isTv) MediaType.EPISODE else MediaType.MOVIE,
            title = if (isTv) "${p.cleanTitle} • S${s}E${e}" else p.cleanTitle,
            seriesTitle = if (isTv) p.cleanTitle else null, seriesGroupKey = if (isTv) "series:$slug" else null,
            seasonNumber = s, episodeNumber = e, posterUrl = poster, releaseYear = p.year
        )
    }
}
