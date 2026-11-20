package com.vanta.media.data.sync

import com.vanta.media.data.local.entity.MediaType
import com.vanta.media.data.local.entity.UnifiedMediaEntity
import com.vanta.media.data.security.SecureVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

data class PunchPlayDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String
)

@Singleton
class PunchPlaySyncRepository @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val secureVault: SecureVault
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun isLinked(): Boolean = !secureVault.getSecret("punchplay_access_token").isNullOrBlank()

    fun saveAccessToken(token: String) {
        secureVault.putSecret("punchplay_access_token", token.trim())
    }

    suspend fun requestDeviceCode(clientId: String): Result<PunchPlayDeviceCode> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = buildJsonObject { put("client_id", clientId) }.toString()
            val req = Request.Builder()
                .url("https://punchplay.tv")
                .post(payload.toRequestBody(jsonMediaType))
                .build()
            val body = okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("PunchPlay OAuth HTTP ${resp.code}")
                resp.body?.string() ?: error("Empty response")
            }
            val obj = json.parseToJsonElement(body).jsonObject
            PunchPlayDeviceCode(
                deviceCode = obj["device_code"]?.jsonPrimitive?.content ?: "",
                userCode = obj["user_code"]?.jsonPrimitive?.content ?: "VANTA-2026",
                verificationUrl = obj["verification_url"]?.jsonPrimitive?.content ?: "https://punchplay.tv"
            )
        }
    }

    suspend fun scrobblePlayback(
        media: UnifiedMediaEntity,
        progressPercent: Float,
        action: String // "start", "pause", or "stop"
    ) = withContext(Dispatchers.IO) {
        val token = secureVault.getSecret("punchplay_access_token") ?: return@withContext
        val tmdbId = media.tmdbId ?: return@withContext

        runCatching {
            val payload = buildJsonObject {
                put("tmdb_id", tmdbId)
                put("type", if (media.mediaType == MediaType.EPISODE) "episode" else "movie")
                media.seasonNumber?.let { put("season", it) }
                media.episodeNumber?.let { put("episode", it) }
                put("progress", progressPercent.coerceIn(0f, 100f))
                put("action", action)
            }.toString()

            val req = Request.Builder()
                .url("https://punchplay.tv")
                .header("Authorization", "Bearer $token")
                .post(payload.toRequestBody(jsonMediaType))
                .build()
            okHttpClient.newCall(req).execute().close()
        }
    }
}
