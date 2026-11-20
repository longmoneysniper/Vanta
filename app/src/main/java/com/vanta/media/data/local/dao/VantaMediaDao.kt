package com.vanta.media.data.local.dao

import androidx.room.*
import com.vanta.media.data.local.entity.MediaSourceLinkEntity
import com.vanta.media.data.local.entity.TmdbLookupCacheEntity
import com.vanta.media.data.local.entity.UnifiedMediaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VantaMediaDao {

    @Upsert
    suspend fun upsertMedia(item: UnifiedMediaEntity)

    @Upsert
    suspend fun upsertSourceLink(link: MediaSourceLinkEntity)

    @Query("""
        SELECT * FROM unified_media 
        WHERE (:kidsOnly = 0 OR isFamilyFriendly = 1)
        ORDER BY addedAt DESC
    """)
    fun observeLibrary(kidsOnly: Boolean): Flow<List<UnifiedMediaEntity>>

    // Deduplicates Continue Watching by seriesGroupKey (collapsing multiple episodes of the same show into the latest active episode)
    @Query("""
        SELECT m.* FROM unified_media m
        WHERE m.playbackPositionMs > 0 
          AND m.lastWatchedAt IS NOT NULL
          AND (:kidsOnly = 0 OR m.isFamilyFriendly = 1)
          AND (
              m.seriesGroupKey IS NULL 
              OR m.lastWatchedAt = (
                  SELECT MAX(m2.lastWatchedAt) 
                  FROM unified_media m2 
                  WHERE m2.seriesGroupKey = m.seriesGroupKey 
                    AND m2.playbackPositionMs > 0
              )
          )
        ORDER BY m.lastWatchedAt DESC
        LIMIT 20
    """)
    fun observeContinueWatching(kidsOnly: Boolean): Flow<List<UnifiedMediaEntity>>

    @Query("SELECT * FROM unified_media WHERE canonicalKey = :canonicalKey LIMIT 1")
    suspend fun getMediaByKey(canonicalKey: String): UnifiedMediaEntity?

    @Query("SELECT * FROM media_source_links WHERE canonicalKey = :canonicalKey")
    suspend fun getSourceLinksForMedia(canonicalKey: String): List<MediaSourceLinkEntity>

    @Query("SELECT * FROM media_source_links WHERE canonicalKey = :canonicalKey AND sourceLinkId = :sourceLinkId LIMIT 1")
    suspend fun getSourceLink(canonicalKey: String, sourceLinkId: String): MediaSourceLinkEntity?

    @Query("UPDATE unified_media SET playbackPositionMs = :positionMs, durationMs = :durationMs, lastWatchedAt = :watchedAt WHERE canonicalKey = :canonicalKey")
    suspend fun updatePlaybackProgress(canonicalKey: String, positionMs: Long, durationMs: Long, watchedAt: Long = System.currentTimeMillis())

    // TMDB Lookup Cache
    @Query("SELECT * FROM tmdb_lookup_cache WHERE lookupKey = :lookupKey LIMIT 1")
    suspend fun getTmdbCache(lookupKey: String): TmdbLookupCacheEntity?

    @Upsert
    suspend fun upsertTmdbCache(entry: TmdbLookupCacheEntity)
}
