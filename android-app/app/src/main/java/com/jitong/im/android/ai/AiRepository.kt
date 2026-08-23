package com.jitong.im.android.ai

import androidx.room.withTransaction
import com.jitong.im.android.local.AccountDatabase
import com.jitong.im.android.local.LocalAiActionItemEntity
import com.jitong.im.android.local.LocalAiArtifactEntity
import com.jitong.im.android.message.AiActionItemResponse
import com.jitong.im.android.message.AiArtifactResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import retrofit2.Response
import java.io.IOException
import java.time.Instant
import java.util.UUID

internal class AiRepository(
    private val api: AiApi,
    private val database: () -> AccountDatabase?,
) {
    suspend fun consent(conversationId: UUID): AiConsentResponse =
        api.consent(conversationId).bodyOrThrow()

    suspend fun updateConsent(conversationId: UUID, enabled: Boolean): AiConsentResponse =
        api.updateConsent(conversationId, AiConsentUpdate(enabled)).bodyOrThrow()

    suspend fun requestSmartReplies(conversationId: UUID): List<AiDraft> {
        val completed = await(api.smartReplies(conversationId, AiRequest()).bodyOrThrow())
        refresh()
        return completed.result?.asJsonObject?.getAsJsonArray("replies")
            ?.map { draft ->
                val value = draft.asJsonObject
                AiDraft(value.get("text").asString, value.get("tone").asString)
            }.orEmpty()
    }

    suspend fun requestExtraction(conversationId: UUID, messageIds: List<UUID>) {
        require(messageIds.isNotEmpty() && messageIds.size <= 200)
        await(api.extract(conversationId, AiExtractionRequest(messageIds = messageIds)).bodyOrThrow())
        refresh()
    }

    suspend fun deleteArtifact(artifactId: UUID) {
        api.deleteArtifact(artifactId).successOrThrow()
        refresh()
    }

    suspend fun updateActionItem(actionItemId: UUID, status: String) {
        api.updateActionItem(actionItemId, AiActionItemUpdate(status)).bodyOrThrow()
        refresh()
    }

    suspend fun deleteActionItem(actionItemId: UUID) {
        api.deleteActionItem(actionItemId).successOrThrow()
        refresh()
    }

    suspend fun refresh() {
        val artifacts = api.artifacts().bodyOrThrow()
        val items = api.actionItems().bodyOrThrow()
        val db = database() ?: return
        withContext(Dispatchers.IO) {
            db.withTransaction {
                db.aiArtifactDao().clearAll()
                db.aiActionItemDao().clearAll()
                db.aiArtifactDao().upsertAll(artifacts.map { it.toEntity() })
                db.aiActionItemDao().upsertAll(items.map { it.toEntity() })
            }
        }
    }

    suspend fun artifacts(conversationId: UUID): List<LocalAiArtifactEntity> =
        withContext(Dispatchers.IO) {
            val db = database() ?: return@withContext emptyList()
            val now = Instant.now().toString()
            db.aiArtifactDao().deleteExpired(now)
            db.aiArtifactDao().listActive(now)
                .filter { it.conversationId == conversationId.toString() }
        }

    suspend fun actionItems(conversationId: UUID): List<LocalAiActionItemEntity> =
        withContext(Dispatchers.IO) {
            database()?.aiActionItemDao()?.listForConversation(conversationId.toString()).orEmpty()
        }

    private suspend fun await(initial: AiJobResponse): AiJobResponse {
        var current = initial
        repeat(120) {
            if (current.status == "SUCCEEDED") return current
            if (current.status in setOf("FAILED", "CANCELLED", "EXPIRED")) {
                throw IOException(current.errorCode ?: "AI request failed")
            }
            delay(250)
            current = api.job(current.jobId).bodyOrThrow()
        }
        throw IOException("AI request timed out")
    }

    private fun AiArtifactResponse.toEntity() = LocalAiArtifactEntity(
        artifactId = artifactId.toString(),
        jobId = jobId.toString(),
        conversationId = conversationId.toString(),
        artifactType = artifactType,
        contentJson = content.toString(),
        createdAt = createdAt,
        expiresAt = expiresAt,
    )

    private fun AiActionItemResponse.toEntity() = LocalAiActionItemEntity(
        actionItemId = actionItemId.toString(),
        sourceJobId = sourceJobId?.toString(),
        ownerUserId = ownerUserId.toString(),
        conversationId = conversationId.toString(),
        assigneeUserId = assigneeUserId?.toString(),
        title = title,
        details = details,
        dueAt = dueAt,
        priority = priority,
        confidence = confidence,
        sourceMessageIdsJson = sourceMessageIds.joinToString(
            prefix = "[\"",
            separator = "\",\"",
            postfix = "\"]",
        ),
        status = status,
        createdAt = createdAt,
        completedAt = completedAt,
    )

    private fun <T> Response<T>.bodyOrThrow(): T = body()
        ?.takeIf { isSuccessful }
        ?: throw IOException("AI request failed with HTTP ${code()}")

    private fun Response<*>.successOrThrow() {
        if (!isSuccessful) throw IOException("AI request failed with HTTP ${code()}")
    }
}
