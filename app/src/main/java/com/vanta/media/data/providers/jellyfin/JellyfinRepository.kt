package com.vanta.media.data.providers.jellyfin

import android.content.Context
import com.vanta.media.data.local.dao.VantaMediaDao
import com.vanta.media.data.local.entity.MediaSourceLinkEntity
import com.vanta.media.data.local.entity.MediaSourceType
import com.vanta.media.data.security.SecureVault
import com.vanta.media.domain.metadata.MetadataResolver
import com.vanta.media.domain.metadata.RawMediaHint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.api.client.extensions.authenticateUserByName
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.api.client.extensions.quickConnectApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.api.client.extensions.userViewsApi
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class JellyfinRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureVault: SecureVault,
    private val dao: VantaMediaDao,
    private val metadataResolver: MetadataResolver
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val jellyfin = createJellyfin {
        this.context = this@JellyfinRepository.context
        clientInfo = ClientInfo(name = "Vanta", version = "0.4.0")
        deviceInfo = DeviceInfo(id = "vanta-android-client", name = "Vanta Player")
    }

    fun getSavedServers(): List<JellyfinServerProfile> {
        val raw = secureVault.getSecret("jellyfin_servers_json") ?: return emptyList()
        return runCatching { json.decodeFromString<List<JellyfinServerProfile>>(raw) }.getOrDefault(emptyList())
    }

    private fun saveServerProfile(profile: JellyfinServerProfile) {
        val updated = getSavedServers().filterNot { it.serverId == profile.serverId } + profile
        secureVault.putSecret("jellyfin_servers_json", json.encodeToString(updated))
    }

    suspend fun loginWithPassword(serverUrl: String, username: String, password: String): Result<JellyfinServerProfile> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cleanUrl = serverUrl.trim().trimEnd('/')
                val api = jellyfin.createApi(baseUrl = cleanUrl)
                val auth by api.userApi.authenticateUserByName(username = username, password = password)
                val token = auth.accessToken ?: error("Missing access token")
                val userId = auth.user?.id?.toString() ?: error("Missing user ID")
                val profile = JellyfinServerProfile(
                    serverId = auth.serverId ?: UUID.randomUUID().toString(),
                    name = auth.user?.name ?: "Jellyfin Server",
                    baseUrl = cleanUrl,
                    userId = userId,
                    accessToken = token
                )
                saveServerProfile(profile)
                profile
            }
        }

    suspend fun initiateQuickConnect(serverUrl: String): Result<QuickConnectChallenge> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cleanUrl = serverUrl.trim().trimEnd('/')
                val api = jellyfin.createApi(baseUrl = cleanUrl)
                val result by api.quickConnectApi.initiateQuickConnect()
                QuickConnectChallenge(secret = result.secret, code = result.code)
            }
        }

    suspend fun checkQuickConnect(serverUrl: String, secret: String): Result<JellyfinServerProfile?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cleanUrl = serverUrl.trim().trimEnd('/')
                val api = jellyfin.createApi(baseUrl = cleanUrl)
                val state by api.quickConnectApi.getQuickConnectState(secret = secret)
                if (!state.authenticated) return@runCatching null
                val auth by api.userApi.authenticateWithQuickConnect(secret = secret)
                val token = auth.accessToken ?: error("Missing Quick Connect token")
                val userId = auth.user?.id?.toString() ?: error("Missing user ID")
                val profile = JellyfinServerProfile(
                    serverId = auth.serverId ?: UUID.randomUUID().toString(),
                    name = auth.user?.name ?: "Jellyfin QuickConnect",
                    baseUrl = cleanUrl,
                    userId = userId,
                    accessToken = token
                )
                saveServerProfile(profile)
                profile
            }
        }

    suspend fun syncServerLibraries(profile: JellyfinServerProfile): Int = withContext(Dispatchers.IO) {
        val api = jellyfin.createApi(baseUrl = profile.baseUrl, accessToken = profile.accessToken)
        val userUuid = UUID.fromString(profile.userId)
        var syncedCount = 0

        // 1. Fetch User Views
        val views by api.userViewsApi.getUserViews(userId = userUuid)

        // 2. Fetch Resume / Continue Watching
        val resume by api.itemsApi.getResumeItems(
            userId = userUuid,
            limit = 20,
            fields = listOf(ItemFields.PROVIDER_IDS, ItemFields.MEDIA_SOURCES, ItemFields.CHAPTERS)
        )

        // 3. Fetch Next Up Episodes
        val nextUp by api.tvShowsApi.getNextUp(
            userId = userUuid,
            limit = 20,
            fields = listOf(ItemFields.PROVIDER_IDS, ItemFields.MEDIA_SOURCES, ItemFields.CHAPTERS)
        )

        // 4. Fetch Latest Media across views
        val latest by api.itemsApi.getItems(
            userId = userUuid,
            recursive = true,
            includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE),
            sortBy = listOf(org.jellyfin.sdk.model.api.ItemSortBy.DATE_CREATED),
            sortOrder = listOf(org.jellyfin.sdk.model.api.SortOrder.DESCENDING),
            limit = 30,
            fields = listOf(ItemFields.PROVIDER_IDS, ItemFields.MEDIA_SOURCES, ItemFields.CHAPTERS)
        )

        val combinedItems = (resume.items.orEmpty() + nextUp.items.orEmpty() + latest.items.orEmpty())
            .distinctBy { it.id }

        for (item in combinedItems) {
            val tmdbId = item.providerIds?.get("Tmdb")?.toIntOrNull()
            val imdbId = item.providerIds?.get("Imdb")
            val isEpisode = item.type == BaseItemKind.EPISODE
            val titleHint = if (isEpisode) {
                "${item.seriesName ?: item.name} S${item.parentIndexNumber ?: 1}E${item.indexNumber ?: 1}"
            } else {
                item.name ?: continue
            }

            val unified = metadataResolver.resolveAndPersist(
                RawMediaHint(
                    rawTitleOrFilename = titleHint,
                    knownTmdbId = tmdbId,
                    knownImdbId = imdbId,
                    season = item.parentIndexNumber,
                    episode = item.indexNumber,
                    fallbackPosterUrl = "${profile.baseUrl}/Items/${item.id}/Images/Primary?api_key=${profile.accessToken}"
                )
            )

            // Extract Intro/Outro segments from Jellyfin chapters if labeled
            val chapters = item.chapters.orEmpty()
            val introChapter = chapters.firstOrNull { it.name?.contains("Intro", ignoreCase = true) == true }
            val outroChapter = chapters.firstOrNull { it.name?.contains("Credits", ignoreCase = true) == true || it.name?.contains("Outro", ignoreCase = true) == true }

            val losslessStreamUrl = "${profile.baseUrl}/Videos/${item.id}/stream?static=true&api_key=${profile.accessToken}"
            val headersJson = json.encodeToString(mapOf("X-Emby-Token" to profile.accessToken))

            dao.upsertSourceLink(
                MediaSourceLinkEntity(
                    sourceLinkId = "jf_${profile.serverId}_${item.id}",
                    canonicalKey = unified.canonicalKey,
                    sourceType = MediaSourceType.JELLYFIN,
                    serverId = profile.serverId,
                    displayLabel = "Jellyfin Direct • ${profile.name}",
                    streamUrl = losslessStreamUrl,
                    customHeadersJson = headersJson,
                    introStartMs = introChapter?.startPositionTicks?.div(10_000L),
                    outroStartMs = outroChapter?.startPositionTicks?.div(10_000L)
                )
            )

            val resumeTicks = item.userData?.playbackPositionTicks ?: 0L
            val runtimeTicks = item.runTimeTicks ?: 0L
            if (resumeTicks > 0L && runtimeTicks > 0L) {
                dao.updatePlaybackProgress(
                    canonicalKey = unified.canonicalKey,
                    positionMs = resumeTicks / 10_000L,
                    durationMs = runtimeTicks / 10_000L
                )
            }
            syncedCount++
        }
        syncedCount
    }

    suspend fun reportPlaybackProgress(
        profile: JellyfinServerProfile,
        jellyfinItemId: String,
        positionMs: Long,
        isPaused: Boolean
    ) = withContext(Dispatchers.IO) {
        runCatching {
            val api = jellyfin.createApi(baseUrl = profile.baseUrl, accessToken = profile.accessToken)
            api.playStateApi.reportPlaybackProgress(
                PlaybackProgressInfo(
                    itemId = UUID.fromString(jellyfinItemId),
                    positionTicks = positionMs * 10_000L,
                    isPaused = isPaused
                )
            )
