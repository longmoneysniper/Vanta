package com.vanta.media.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class MediaType { MOVIE, EPISODE }

enum class MediaSourceType { JELLYFIN, KODI, STREMIO, NUVIO, DEBRID, LOCAL_SMB }

@Entity(
    tableName = "unified_media",
    indices = [
        Index(value = ["seriesGroupKey"]),
        Index(value = ["tmdbId"]),
        Index(value = ["isFamilyFriendly"]),
        Index(value = ["lastWatchedAt"])
    ]
)
data class UnifiedMediaEntity(
    @PrimaryKey val canonicalKey: String, // e.g. "movie:27205" or "tv:1396:s01:e01"
    val tmdbId: Int?,
    val imdbId: String?,
    val mediaType: MediaType,
    val title: String,
    val seriesTitle: String? = null,
    val seriesGroupKey: String? = null, // e.g. "tv:1396" to deduplicate Continue Watching
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val overview: String = "",
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val episodeStillUrl: String? = null,
    val releaseYear: Int? = null,
    val rating: Float? = null,
    val isFamilyFriendly: Boolean = true, // Zero-migration Kids Mode SQL filter
    val playbackPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val lastWatchedAt: Long? = null,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "media_source_links",
    indices = [Index(value = ["canonicalKey"])]
)
data class MediaSourceLinkEntity(
    @PrimaryKey val sourceLinkId: String,
    val canonicalKey: String,
    val sourceType: MediaSourceType,
    val serverId: String,
    val displayLabel: String, // e.g., "4K HDR10 • TrueHD Atmos • Jellyfin"
    val streamUrl: String,
    val customHeadersJson: String = "{}", // Serialized Map<String, String>
    val externalSubtitlesJson: String = "[]", // Serialized List<ExternalSubtitle>
    val introStartMs: Long? = null,
    val introEndMs: Long? = null,
    val outroStartMs: Long? = null
)

@Entity(tableName = "tmdb_lookup_cache")
data class TmdbLookupCacheEntity(
    @PrimaryKey val lookupKey: String, // Normalized title+year or imdbId
    val tmdbId: Int?, // Null indicates a negative match (unmatchable NAS file)
    val mediaType: MediaType?,
    val resolvedJson: String?,
    val cachedAtMs: Long,
    val isNegativeMatch: Boolean
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean {
        val ttlMs = if (isNegativeMatch) {
            7L * 24 * 60 * 60 * 1000 // 7-day negative cache for unmatchable NAS files
        } else {
            30L * 24 * 60 * 60 * 1000 // 30-day positive metadata cache
        }
        return (nowMs - cachedAtMs) > ttlMs
    }
}
