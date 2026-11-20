package com.vanta.media.domain.metadata

import com.vanta.media.data.local.dao.VantaMediaDao
import com.vanta.media.data.local.entity.MediaType
import com.vanta.media.data.local.entity.TmdbLookupCacheEntity
import com.vanta.media.data.local.entity.UnifiedMediaEntity
import com.vanta.media.data.security.SecureVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    private val tmdbSemaphore = Semaphore(4)
    private val keyMutexes = ConcurrentHashMap<String, Mutex>()
    private val json = Json { ignoreUnknownKeys = true }

    private fun mutexFor(key: String): Mutex = keyMutexes.computeIfAbsent(key) { Mutex() }

    suspend fun resolveAndPersist(hint: RawMediaHint): UnifiedMediaEntity = withContext(Dispatchers.IO) {
        val parsed = FilenameParser.parse(hint.rawTitleOrFilename)
        val season = hint.season ?: parsed.season
        val episode = hint.episode ?: parsed.episode
        val isTv = season != null && episode != null

        val lookupKey = buildString {
            if (!hint.knownImdbId.isNullOrBlank()) append("imdb:${hint.knownImdbId}")
            else if (hint.knownTmdbId != null) append("tmdb:${hint.knownTmdbId}:${if (isTv) "tv" else "movie"}")
            else append("q:${parsed.cleanTitle.lowercase()}:${parsed.year ?: ""}:${if (isTv) "tv" else "movie"}")
        }

        mutexFor(lookupKey).withLock {
            val cached = dao.getTmdbCache(lookupKey)
            if (cached != null && !cached.isExpired()) {
                if (cached.isNegativeMatch) {
                    return@withLock buildFallbackEntity(parsed, season, episode, hint.fallbackPosterUrl)
                }
            }

            val apiKey = secureVault.getSecret("tmdb_api_key").orEmpty()
            if (apiKey.isBlank()) {
                return@withLock buildFallbackEntity(parsed, season, episode, hint.fallbackPosterUrl)
            }

            val tmdbId = hint.knownTmdbId
                ?: hint.knownImdbId?.let { bridgeImdbToTmdb(it, isTv, apiKey) }
                ?: searchTmdbId(parsed.cleanTitle, parsed.year, isTv, apiKey)

            if (tmdbId == null) {
                dao.upsertTmdbCache(
                    TmdbLookupCacheEntity(
                        lookupKey = lookupKey,
                        tmdbId = null,
                        mediaType = if (isTv) MediaType.EPISODE else MediaType.MOVIE,
                        resolvedJson = null,
                        cachedAtMs = System.currentTimeMillis(),
                        isNegativeMatch = true
                    )
                )
                return@withLock buildFallbackEntity(parsed, season, episode, hint.fallbackPosterUrl)
            }

            val resolved = if (isTv && season != null && episode != null) {
                fetchTvEpisodeDetails(tmdbId, season, episode, apiKey)
            } else {
                fetchMovieDetails(tmdbId, apiKey)
            }

            val entity = resolved ?: buildFallbackEntity(parsed, season, episode, hint.fallbackPosterUrl)
            dao.upsertMedia(entity)
            dao.upsertTmdbCache(
                TmdbLookupCacheEntity(
                    lookupKey = lookupKey,
                    tmdbId = tmdbId,
                    mediaType = entity.mediaType,
                    resolvedJson = null,
                    cachedAtMs = System.currentTimeMillis(),
                    isNegativeMatch = false
                )
            )
            entity
        }
    }

    private suspend fun bridgeImdbToTmdb(imdbId: String, isTv: Boolean, apiKey: String): Int? {
        val url = "https://themoviedb.org".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", apiKey)
            .addQueryParameter("external_source", "imdb_id")
            .build()
        val body = executeRateLimitedGet(url.toString()) ?: return null
        val root = json.parseToJsonElement(body).jsonObject
        val arrayKey = if (isTv) "tv_results" else "movie_results"
        return root[arrayKey]?.jsonArray?.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.intOrNull
    }

    private suspend fun searchTmdbId(title: String, year: Int?, isTv: Boolean, apiKey: String): Int? {
        val endpoint = if (isTv) "search/tv" else "search/movie"
        val url = "https://themoviedb.org".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", apiKey)
            .addQueryParameter("query", title)
            .apply {
                if (year != null) addQueryParameter(if (isTv) "first_air_date_year" else "year", year.toString())
            }
            .build()
        val body = executeRateLimitedGet(url.toString()) ?: return null
        val root = json.parseToJsonElement(body).jsonObject
        return root["results"]?.jsonArray?.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.intOrNull
    }

    private suspend fun fetchMovieDetails(tmdbId: Int, apiKey: String): UnifiedMediaEntity? {
        val url = "https://themoviedb.org".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", apiKey)
            .build()
        val body = executeRateLimitedGet(url.toString()) ?: return null
        val obj = json.parseToJsonElement(body).jsonObject
        val title = obj["title"]?.jsonPrimitive?.content ?: return null
        val posterPath = obj["poster_path"]?.jsonPrimitive?.content
        val backdropPath = obj["backdrop_path"]?.jsonPrimitive?.content
        val adult = obj["adult"]?.jsonPrimitive?.booleanOrNull ?: false

        return UnifiedMediaEntity(
            canonicalKey = "movie:$tmdbId",
            tmdbId = tmdbId,
            imdbId = obj["imdb_id"]?.jsonPrimitive?.content,
            mediaType = MediaType.MOVIE,
            title = title,
            overview = obj["overview"]?.jsonPrimitive?.content.orEmpty(),
            posterUrl = posterPath?.let { "https://tmdb.org" },
            backdropUrl = backdropPath?.let { "https://tmdb.org" },
            releaseYear = obj["release_date"]?.jsonPrimitive?.content?.take(4)?.toIntOrNull(),
            rating = obj["vote_average"]?.jsonPrimitive?.floatOrNull,
            isFamilyFriendly = !adult
        )
    }

    private suspend fun fetchTvEpisodeDetails(tmdbId: Int, season: Int, episode: Int, apiKey: String): UnifiedMediaEntity? {
        val showUrl = "https://themoviedb.org".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", apiKey)
            .build()
        val epUrl = "https://themoviedb.org/season/$season/episode/$episode".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", apiKey)
            .build()

        val showBody = executeRateLimitedGet(showUrl.toString()) ?: return null
        val epBody = executeRateLimitedGet(epUrl.toString()) ?: return null

        val showObj = json.parseToJsonElement(showBody).jsonObject
        val epObj = json.parseToJsonElement(epBody).jsonObject

        val seriesTitle = showObj["name"]?.jsonPrimitive?.content ?: return null
        val epTitle = epObj["name"]?.jsonPrimitive?.content ?: "Episode $episode"
        val posterPath = showObj["poster_path"]?.jsonPrimitive?.content
        val backdropPath = showObj["backdrop_path"]?.jsonPrimitive?.content
        val stillPath = epObj["still_path"]?.jsonPrimitive?.content
        val adult = showObj["adult"]?.jsonPrimitive?.booleanOrNull ?: false

        return UnifiedMediaEntity(
            canonicalKey = "tv:$tmdbId:s${season.toString().padStart(2, '0')}:e${episode.toString().padStart(2, '0')}",
            tmdbId = tmdbId,
            imdbId = null,
            mediaType = MediaType.EPISODE,
            title = "$seriesTitle • S${season}E${episode}",
            seriesTitle = seriesTitle,
            seriesGroupKey = "tv:$tmdbId",
            seasonNumber = season,
            episodeNumber = episode,
            episodeTitle = epTitle,
            overview = epObj["overview"]?.jsonPrimitive?.content.orEmpty(),
            posterUrl = posterPath?.let { "https://tmdb.org" },
            backdropUrl = backdropPath?.let { "https://tmdb.org" },
            episodeStillUrl = stillPath?.let { "https://tmdb.org" },
            rating = epObj["vote_average"]?.jsonPrimitive?.floatOrNull,
            isFamilyFriendly = !adult
        )
    }

    private suspend fun executeRateLimitedGet(url: String): String? = tmdbSemaphore.withPermit {
        repeat(3) { attempt ->
            val req = Request.Builder().url(url).build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (resp.code == 429) {
                    val retrySec = resp.header("Retry-After")?.toLongOrNull() ?: (attempt + 1L)
                    delay(retrySec * 1000L)
