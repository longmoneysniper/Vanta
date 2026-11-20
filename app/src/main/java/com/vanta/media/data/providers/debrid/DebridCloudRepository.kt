package com.vanta.media.data.providers.debrid

import com.vanta.media.data.local.dao.VantaMediaDao
import com.vanta.media.data.local.entity.MediaSourceLinkEntity
import com.vanta.media.data.local.entity.MediaSourceType
import com.vanta.media.data.security.SecureVault
import com.vanta.media.domain.metadata.MetadataResolver
import com.vanta.media.domain.metadata.RawMediaHint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

data class DebridCloudItem(
    val id: String,
    val filename: String,
    val sizeBytes: Long,
    val downloadUrl: String
)

@Singleton
class DebridCloudRepository @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val secureVault: SecureVault,
    private val dao: VantaMediaDao,
    private val metadataResolver: MetadataResolver
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun saveRealDebridToken(token: String) {
        secureVault.putSecret("rd_api_token", token.trim())
    }

    fun hasRealDebridToken(): Boolean = !secureVault.getSecret("rd_api_token").isNullOrBlank()

    suspend fun fetchAndIndexCloudDownloads(): Result<List<DebridCloudItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val token = secureVault.getSecret("rd_api_token") ?: error("Real-Debrid token not configured")
            val req = Request.Builder()
                .url("https://real-debrid.com")
                .header("Authorization", "Bearer $token")
                .build()

            val body = okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("Debrid API HTTP ${resp.code}")
                resp.body?.string() ?: "[]"
            }

            val array = json.parseToJsonElement(body).jsonArray
            val results = mutableListOf<DebridCloudItem>()

            for (element in array) {
                val obj = element.jsonObject
                val id = obj["id"]?.jsonPrimitive?.content ?: continue
                val filename = obj["filename"]?.jsonPrimitive?.content ?: continue
                val downloadUrl = obj["download"]?.jsonPrimitive?.content ?: continue
                val size = obj["filesize"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L

                val item = DebridCloudItem(id, filename, size, downloadUrl)
                results.add(item)

                val unified = metadataResolver.resolveAndPersist(RawMediaHint(rawTitleOrFilename = filename))
                dao.upsertSourceLink(
                    MediaSourceLinkEntity(
                        sourceLinkId = "rd_$id",
                        canonicalKey = unified.canonicalKey,
                        sourceType = MediaSourceType.DEBRID,
                        serverId = "real-debrid",
                        displayLabel = "Real-Debrid Cloud • $filename",
                        streamUrl = downloadUrl
                    )
                )
            }
            results
        }
    }

    suspend fun unrestrictLink(hostLink: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val token = secureVault.getSecret("rd_api_token") ?: error("Real-Debrid token missing")
            val form = FormBody.Builder().add("link", hostLink).build()
            val req = Request.Builder()
                .url("https://real-debrid.com")
                .header("Authorization", "Bearer $token")
                .post(form)
                .build()
            val body = okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("Unrestrict failed: HTTP ${resp.code}")
                resp.body?.string() ?: error("Empty response")
            }
            json.parseToJsonElement(body).jsonObject["download"]?.jsonPrimitive?.content
                ?: error("Missing unrestricted URL")
        }
    }
}
