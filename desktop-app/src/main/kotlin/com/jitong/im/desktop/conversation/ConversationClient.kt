package com.jitong.im.desktop.conversation

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

@Serializable
data class DesktopConversationSummary(
    val version: Int,
    val conversationId: String,
    val peerUserId: String,
    val peerAccountNo: String,
    val peerDisplayName: String,
    val status: String,
    val relationship: String,
    val blockedByMe: Boolean,
    val readSeq: Long,
    val peerReadSeq: Long,
)

@Serializable
data class DesktopSyncEvent(
    val syncSeq: Long,
    val eventType: String,
    val entityId: String,
    val conversationId: String?,
    val createdAt: String,
)

@Serializable
data class DesktopSyncPage(
    val version: Int,
    val afterSeq: Long,
    val highWatermark: Long,
    val untilSeq: Long,
    val nextAfterSeq: Long,
    val hasMore: Boolean,
    val events: List<DesktopSyncEvent>,
)

@Serializable
data class DesktopSyncAckRequest(val syncSeq: Long)

class ConversationClient(
    private val baseUrl: String,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun list(accessToken: String): List<DesktopConversationSummary> {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/api/v1/conversations")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Conversation request failed with HTTP ${response.code}")
            }
            return json.decodeFromString(body)
        }
    }

    fun sync(
        accessToken: String,
        afterSeq: Long,
        untilSeq: Long? = null,
    ): DesktopSyncPage {
        val query = buildString {
            append("?after=")
            append(afterSeq)
            untilSeq?.let {
                append("&until=")
                append(it)
            }
            append("&limit=200")
        }
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/api/v1/sync$query")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Sync request failed with HTTP ${response.code}")
            }
            return json.decodeFromString(body)
        }
    }

    fun acknowledge(accessToken: String, syncSeq: Long) {
        val mediaType = "application/json".toMediaType()
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/api/v1/sync/ack")
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", mediaType.toString())
            .post(json.encodeToString(DesktopSyncAckRequest(syncSeq)).toRequestBody(mediaType))
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Sync acknowledgement failed with HTTP ${response.code}")
            }
        }
    }
}
