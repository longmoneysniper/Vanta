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
import org.jellyfin.sdk.api.client.extensions.authenticateWithQuickConnect
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.api.client.extensions.quickConnectApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.RepeatMode
import org.jellyfin.sdk.model.api.SortOrder
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
        clientInfo = ClientInfo("Vanta", "1.0.0")
        deviceInfo = DeviceInfo("vanta-android", "Vanta Player")
    }

    fun getSavedServers(): List<JellyfinServerProfile> =
        secureVault.getSecret("jellyfin_servers_json")
            ?.let { runCatching { json.decodeFromString<List<JellyfinServerProfile>>(it) }.getOrNull() }
            .orEmpty()

    private fun saveServer(p: JellyfinServerProfile) {
        val list = getSavedServers().filterNot { it.serverId == p.serverId } + p
        secureVault.putSecret("jellyfin_servers_json", json.encodeToString(list))
    }

    suspend fun loginWithPassword(url: String, user: String, pass: String): Result<JellyfinServerProfile> = withContext(Dispatchers.IO) {
        runCatching {
            val base = url.trim().trimEnd('/')
            val api = jellyfin.createApi(baseUrl = base)
            val auth by api.userApi.authenticateUserByName(username = user, password = pass)
            val p = JellyfinServerProfile(auth.serverId ?: UUID.randomUUID().toString(), auth.user?.name ?: "Jellyfin", base, auth.user?.id.toString(), auth.accessToken ?: error("No token"))
            saveServer(p)
            p
        }
    }

    suspend fun initiateQuickConnect(url: String): Result<QuickConnectChallenge> = withContext(Dispatchers.IO) {
        runCatching {
            val res by jellyfin.createApi(baseUrl = url.trim().trimEnd('/')).quickConnectApi.initiateQuickConnect()
            QuickConnectChallenge(res.secret, res.code)
        }
    }

    suspend fun checkQuickConnect(url: String, secret: String): Result<JellyfinServerProfile?> = withContext(Dispatchers.IO) {
        runCatching {
            val base = url.trim().trimEnd('/')
            val api = jellyfin.createApi(baseUrl = base)
            val st by api.quickConnectApi.getQuickConnectState(secret = secret)
            if (!st.authenticated) return@runCatching null
            val auth by api.userApi.authenticateWithQuickConnect(secret = secret)
            val p = JellyfinServerProfile(auth.serverId ?: UUID.randomUUID().toString(), auth.user?.name ?: "Jellyfin QC", base, auth.user?.id.toString(), auth.accessToken ?: error("No token"))
            saveServer(p)
            p
        }
    }

    suspend fun syncServerLibraries(profile: JellyfinServerProfile): Int = withContext(Dispatchers.IO) {
        val api = jellyfin.createApi(baseUrl = profile.baseUrl, accessToken = profile.accessToken)
        val uid = UUID.fromString(profile.userId)
        val fields = listOf(ItemFields.PROVIDER_IDS, ItemFields.MEDIA_SOURCES, ItemFields.CHAPTERS)
        val resume by api.itemsApi.getResumeItems(userId = uid, limit = 20, fields = fields)
        val nextUp by api.tvShowsApi.getNextUp(userId = uid, limit = 20, fields = fields)
        val latest by api.itemsApi.getItems(userId = uid, recursive = true, includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE), sortBy = listOf(ItemSortBy.DATE_CREATED), sortOrder = listOf(SortOrder.DESCENDING), limit = 30, fields = fields)

        val items = (resume.items.orEmpty() + nextUp.items.orEmpty() + latest.items.orEmpty()).distinctBy { it.id }
        for (item in items) {
            val isEp = item.type == BaseItemKind.EPISODE
            val hint = if (isEp) "${item.seriesName ?: item.name} S${item.parentIndexNumber ?: 1}E${item.indexNumber ?: 1}" else (item.name ?: continue)
            val unified = metadataResolver.resolveAndPersist(RawMediaHint(hint, item.providerIds?.get("Tmdb")?.toIntOrNull(), item.providerIds?.get("Imdb"), item.parentIndexNumber, item.indexNumber, "${profile.baseUrl}/Items/${item.id}/Images/Primary?api_key=${profile.accessToken}"))
            val intro = item.chapters?.firstOrNull { it.name?.contains("Intro", true) == true }?.startPositionTicks?.div(10_000L)
            val outro = item.chapters?.firstOrNull { it.name?.contains("Credits", true) == true || it.name?.contains("Outro", true) == true }?.startPositionTicks?.div(10_000L)
            dao.upsertSourceLink(MediaSourceLinkEntity("jf_${profile.serverId}_${item.id}", unified.canonicalKey, MediaSourceType.JELLYFIN, profile.serverId, "Jellyfin Direct • ${profile.name}", "${profile.baseUrl}/Videos/${item.id}/stream?static=true&api_key=${profile.accessToken}", json.encodeToString(mapOf("X-Emby-Token" to profile.accessToken)), "[]", intro, null, outro))
            val pos = item.userData?.playbackPositionTicks ?: 0L
            val dur = item.runTimeTicks ?: 0L
            if (pos > 0L && dur > 0L) dao.updatePlaybackProgress(unified.canonicalKey, pos / 10_000L, dur / 10_000L)
        }
        items.size
    }

    suspend fun reportPlaybackProgress(profile: JellyfinServerProfile, itemId: String, posMs: Long, paused: Boolean) = withContext(Dispatchers.IO) {
        runCatching {
            jellyfin.createApi(baseUrl = profile.baseUrl, accessToken = profile.accessToken).playStateApi.reportPlaybackProgress(
                PlaybackProgressInfo(
                    itemId = UUID.fromString(itemId),
                    positionTicks = posMs * 10_000L,
                    isPaused = paused,
                    isMuted = false,
                    canSeek = true,
                    playMethod = PlayMethod.DIRECT_PLAY,
                    repeatMode = RepeatMode.REPEAT_NONE
                )
            )
            Unit
        }
    }
}
