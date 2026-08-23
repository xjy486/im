package com.jitong.im.desktop.local

import com.jitong.im.desktop.conversation.ConversationClient
import com.jitong.im.desktop.conversation.DesktopSyncEvent
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class LocalDatabaseAiArtifactTest {
    @Test
    fun mobile_ai_completion_is_imported_by_sync_and_survives_pc_restart() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """{"version":1,"jobId":"job-1","conversationId":"conversation-1","kind":"SUMMARY","status":"SUCCEEDED","errorCode":null,"result":{"overview":"Synced from mobile","keyPoints":[],"decisions":[],"openQuestions":[],"sourceMessageIds":[]},"createdAt":"2026-08-23T00:00:00Z","expiresAt":"2027-09-22T00:00:00Z"}"""))
        server.enqueue(
            MockResponse().setBody(
                """[{"version":1,"artifactId":"artifact-1","jobId":"job-1","conversationId":"conversation-1","artifactType":"SUMMARY","content":{"overview":"Synced from mobile","keyPoints":[],"decisions":[],"openQuestions":[],"sourceMessageIds":[]},"createdAt":"2026-08-23T00:00:00Z","expiresAt":"2027-09-22T00:00:00Z"}]"""))
        server.enqueue(MockResponse().setBody("[]"))
        server.start()
        val manager = LocalDatabaseManager(
            createTempDirectory("jitong-ai-mobile-sync"),
            InMemoryKeychain())
        try {
            manager.open("12345678903").use { database ->
                ConversationClient(server.url("/").toString()).applyAiSyncEvents(
                    "access",
                    database,
                    listOf(
                        DesktopSyncEvent(
                            1,
                            "AI_JOB_COMPLETED",
                            "job-1",
                            "conversation-1",
                            "now")))

                assertEquals("SUCCEEDED", database.listAiJobs().single().status)
                assertEquals("artifact-1", database.listAiArtifacts().single().artifactId)
            }

            manager.open("12345678903").use { reopened ->
                assertEquals("job-1", reopened.listAiJobs().single().jobId)
                assertTrue(reopened.listAiArtifacts().single().contentJson.contains("Synced from mobile"))
            }
        } finally {
            server.shutdown()
            manager.clear("12345678903")
        }
    }

    @Test
    fun deleted_or_expired_ai_jobs_are_not_redisplayed_after_pc_restart() {
        val manager = LocalDatabaseManager(
            createTempDirectory("jitong-ai-job-retention"),
            InMemoryKeychain())
        try {
            manager.open("12345678904").use { database ->
                database.upsertAiJob(job("job-deleted", "2027-09-22T00:00:00Z"))
                database.upsertAiJob(job("job-expired", "2026-08-22T00:00:00Z"))
                database.upsertAiArtifact(
                    artifact("artifact-deleted", "job-deleted").copy(
                        expiresAt = "2027-09-22T00:00:00Z"))
                database.upsertAiArtifact(
                    artifact("artifact-expired", "job-expired").copy(
                        expiresAt = "2026-08-22T00:00:00Z"))
                database.deleteAiJob("job-deleted")
                database.deleteAiArtifact("artifact-deleted")
            }

            manager.open("12345678904").use { reopened ->
                assertEquals(emptyList(), reopened.listAiJobs("2026-08-23T00:00:00Z"))
                assertEquals(emptyList(), reopened.listAiArtifacts("2026-08-23T00:00:00Z"))
            }
        } finally {
            manager.clear("12345678904")
        }
    }

    @Test
    fun deletes_private_ai_copies_by_artifact_or_job_identity() {
        val manager = LocalDatabaseManager(createTempDirectory("jitong-ai-artifacts"), InMemoryKeychain())
        val database = manager.open("12345678901")
        try {
            database.upsertAiArtifact(
                LocalAiArtifact(
                    artifactId = "artifact-1",
                    jobId = "job-1",
                    conversationId = "conversation-1",
                    artifactType = "SMART_REPLY",
                    contentJson = "{\"replies\":[]}",
                    createdAt = "2026-08-23T00:00:00Z",
                    expiresAt = "2026-08-23T00:10:00Z"))
            database.upsertAiArtifact(
                LocalAiArtifact(
                    artifactId = "artifact-2",
                    jobId = "job-2",
                    conversationId = "conversation-1",
                    artifactType = "EXTRACTION",
                    contentJson = "{\"actionItems\":[],\"keyFacts\":[]}",
                    createdAt = "2026-08-23T00:00:00Z",
                    expiresAt = "2026-09-22T00:00:00Z"))

            database.deleteAiArtifact("artifact-1")
            assertEquals(listOf("artifact-2"), database.listAiArtifacts().map { it.artifactId })

            database.deleteAiArtifactsForJob("job-2")
            assertEquals(emptyList(), database.listAiArtifacts())
        } finally {
            database.close()
            manager.clear("12345678901")
        }
    }

    @Test
    fun desktop_sync_deletion_events_remove_private_ai_copies() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("[]"))
        server.enqueue(MockResponse().setBody("[]"))
        server.start()
        val manager = LocalDatabaseManager(createTempDirectory("jitong-ai-sync"), InMemoryKeychain())
        try {
            manager.open("12345678902").use { database ->
                database.upsertAiArtifact(artifact("artifact-1", "job-1"))
                database.upsertAiArtifact(artifact("artifact-2", "job-2"))
                database.upsertAiJob(job("job-2", "2027-09-22T00:00:00Z"))
                database.upsertAiActionItem(actionItem())
                val client = ConversationClient(server.url("/").toString())

                client.applyAiSyncEvents(
                    "access",
                    database,
                    listOf(
                        DesktopSyncEvent(1, "AI_ARTIFACT_DELETED", "artifact-1", null, "now"),
                        DesktopSyncEvent(2, "AI_JOB_DELETED", "job-2", null, "now")))

                assertEquals(emptyList(), database.listAiArtifacts())
                assertEquals(emptyList(), database.listAiJobs())
                assertEquals(emptyList(), database.listAiActionItems())
            }
        } finally {
            server.shutdown()
            manager.clear("12345678902")
        }
    }

    private fun artifact(artifactId: String, jobId: String) = LocalAiArtifact(
        artifactId = artifactId,
        jobId = jobId,
        conversationId = "conversation-1",
        artifactType = "EXTRACTION",
        contentJson = "{\"actionItems\":[],\"keyFacts\":[]}",
        createdAt = "2026-08-23T00:00:00Z",
        expiresAt = "2027-09-22T00:00:00Z",
    )

    private fun actionItem() = LocalAiActionItem(
        actionItemId = "action-1",
        sourceJobId = "job-2",
        ownerUserId = "user-1",
        conversationId = "conversation-1",
        assigneeUserId = "user-2",
        title = "Send proposal",
        details = "Friday",
        dueAt = null,
        priority = "HIGH",
        confidence = 0.9,
        sourceMessageIdsJson = "[\"message-1\"]",
        status = "OPEN",
        createdAt = "2026-08-23T00:00:00Z",
        completedAt = null,
    )

    private fun job(jobId: String, expiresAt: String) = LocalAiJob(
        jobId = jobId,
        conversationId = "conversation-1",
        kind = "SUMMARY",
        status = "SUCCEEDED",
        errorCode = null,
        createdAt = "2026-08-23T00:00:00Z",
        expiresAt = expiresAt,
    )
}
