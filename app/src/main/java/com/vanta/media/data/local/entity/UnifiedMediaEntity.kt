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
        Index(value = ["lastWatchedAt"]),
        Index(value = ["catalogRow"])
    ]
)
data class UnifiedMediaEntity(
    @PrimaryKey val canonicalKey: String,
    val tmdbId: Int?,
    val imdbId: String?,
    val mediaType: MediaType,
    val title: String,
    val seriesTitle: String? = null,
    val seriesGroupKey: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val overview: String = "",
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val episodeStillUrl: String? = null,
    val logoUrl: String? = null,
    val catalogRow: String? = null,
    val rankBadge: String? = null,
    val certification: String? = null,
    val genres: String? = null,
    val releaseYear: Int? = null,
    val rating: Float? = null,
    val isFamilyFriendly: Boolean = true,
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
    val displayLabel: String,
    val streamUrl: String,
    val customHeadersJson: String = "{}",
    val externalSubtitlesJson: String = "[]",
    val introStartMs: Long? = null,
    val introEndMs: Long? = null,
    val outroStartMs: Long? = null
)

@Entity(tableName = "tmdb_lookup_cache")
data class TmdbLookupCacheEntity(
    @PrimaryKey val lookupKey: String,
    val tmdbId: Int?,
    val mediaType: MediaType?,
    val resolvedJson: String?,
    val cachedAtMs: Long,
    val isNegativeMatch: Boolean
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean {
        val ttlMs = if (isNegativeMatch) 7L * 24 * 60 * 60 * 1000 else 30L * 24 * 60 * 60 * 1000
        return (nowMs - cachedAtMs) > ttlMs
    }
}
