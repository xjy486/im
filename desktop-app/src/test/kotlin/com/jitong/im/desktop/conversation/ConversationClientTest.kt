package com.jitong.im.desktop.conversation

import com.jitong.im.desktop.local.InMemoryKeychain
import com.jitong.im.desktop.local.LocalConversation
import com.jitong.im.desktop.local.LocalDatabaseManager
import com.jitong.im.desktop.local.LocalMessage
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import java.util.UUID

class ConversationClientTest {
    @Test
    fun contact_and_message_requests_use_the_shared_v1_http_contract() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"version":1,"accountNo":"22345678902","displayName":"Bob","avatarUrl":null,"relationship":"NONE","pendingRequestId":null}"""))
        server.enqueue(MockResponse().setBody("""{"version":1,"requestId":"request-1","requesterId":"user-1","recipientId":"user-2","status":"PENDING","verification":"","expiresAt":"2026-08-27T00:00:00Z","conversationId":null}"""))
        server.enqueue(MockResponse().setBody("""{"messageId":"message-1","conversationId":"conversation-1","senderId":"user-1","clientMsgId":"client-1","conversationSeq":1,"type":"TEXT","state":"ACTIVE","text":"hello","serverAcceptedAt":"2026-08-20T00:00:00Z"}"""))
        server.start()
        try {
            val client = ConversationClient(
                baseUrl = server.url("/").toString(),
                httpClient = OkHttpClient())

            val result = client.search("access", "22345678902")
            val request = client.createContactRequest("access", "22345678902")
            val message = client.sendMessage("access", "conversation-1", "client-1", "hello")

            assertEquals("Bob", result.displayName)
            assertEquals("request-1", request.requestId)
            assertEquals(1, message.conversationSeq)
            val recorded = (1..3).map { server.takeRequest() }
            assertEquals(
                listOf(
                    "/api/v1/users/search?accountNo=22345678902",
                    "/api/v1/contact-requests",
                    "/api/v1/conversations/conversation-1/messages"),
                recorded.map { it.path })
            recorded.forEach {
                assertEquals("Bearer access", it.getHeader("Authorization"))
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun image_upload_send_and_recall_use_the_shared_v1_contract() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """{"version":1,"mediaId":"11111111-1111-4111-8111-111111111111","purpose":"MESSAGE_IMAGE","state":"TEMP","contentType":"image/jpeg","width":10,"height":20,"byteSize":4,"sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}"""))
        server.enqueue(
            MockResponse().setBody(
                """{"messageId":"22222222-2222-4222-8222-222222222222","conversationId":"conversation-1","senderId":"user-1","clientMsgId":"33333333-3333-4333-8333-333333333333","conversationSeq":2,"type":"IMAGE","state":"ACTIVE","text":null,"mediaId":"11111111-1111-4111-8111-111111111111","serverAcceptedAt":"2026-08-20T00:00:00Z","recalledAt":null}"""))
        server.enqueue(
            MockResponse().setBody(
                """{"messageId":"22222222-2222-4222-8222-222222222222","conversationId":"conversation-1","senderId":"user-1","clientMsgId":"33333333-3333-4333-8333-333333333333","conversationSeq":2,"type":"IMAGE","state":"RECALLED","text":null,"mediaId":null,"serverAcceptedAt":"2026-08-20T00:00:00Z","recalledAt":"2026-08-20T00:00:30Z"}"""))
        server.start()
        try {
            val client = ConversationClient(
                baseUrl = server.url("/").toString(),
                httpClient = OkHttpClient())

            val uploaded = client.uploadImage(
                "access",
                "photo.png",
                imageBytes(40, 20))
            val sent = client.sendImage(
                "access",
                "conversation-1",
                "33333333-3333-4333-8333-333333333333",
                uploaded.mediaId)
            val recalled = client.recallMessage("access", sent.messageId)

            assertEquals("MESSAGE_IMAGE", uploaded.purpose)
            assertEquals("IMAGE", sent.type)
            assertEquals("11111111-1111-4111-8111-111111111111", sent.mediaId)
            assertEquals("RECALLED", recalled.state)
            assertNull(recalled.mediaId)

            val recorded = (1..3).map { server.takeRequest() }
            assertEquals(
                listOf(
                    "/api/v1/media/images?uploadId=${recorded[0].path!!.substringAfter("uploadId=")}",
                    "/api/v1/conversations/conversation-1/messages",
                    "/api/v1/messages/22222222-2222-4222-8222-222222222222/recall"),
                recorded.map { it.path })
            recorded.forEach {
                assertEquals("Bearer access", it.getHeader("Authorization"))
            }
            assertEquals("IMAGE", recorded[1].body.readUtf8().substringAfter("\"type\":\"").substringBefore("\""))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun image_download_is_cached_encrypted_and_recalled_media_is_removed() {
        val server = MockWebServer()
        val image = "private-image".toByteArray()
        server.enqueue(MockResponse().setBody(image.toString(Charsets.ISO_8859_1)))
        server.start()
        val root = createTempDirectory("jitong-client-media")
        val manager = LocalDatabaseManager(root, InMemoryKeychain())
        try {
            val client = ConversationClient(
                baseUrl = server.url("/").toString(),
                httpClient = OkHttpClient())
            val local = manager.open("12345678903")
            val message = LocalMessage(
                messageId = "message-1",
                conversationId = "conversation-1",
                senderId = "user-1",
                clientMsgId = "client-1",
                conversationSeq = 1,
                type = "IMAGE",
                state = "ACTIVE",
                localState = "RECEIVED",
                text = "",
                mediaId = "media-1",
                serverAcceptedAt = "2026-08-20T00:00:00Z",
                createdAt = 1)
            local.upsertMessage(message)

            assertEquals(
                image.toList(),
                client.loadMedia("access", local, message, thumbnail = true)?.toList())
            assertEquals(
                image.toList(),
                client.loadMedia("access", local, message, thumbnail = true)?.toList())
            assertEquals(1, server.requestCount)
            val encryptedFile = root.resolve(
                "media-cache/12345678903/message-media-media-1-thumb.bin")
            assertFalse(encryptedFile.readBytes().contentEquals(image))

            local.mediaCache().put("message-media-media-1", image)
            client.applyRecalledMessage(
                local,
                DesktopMessage(
                    messageId = "message-1",
                    conversationId = "conversation-1",
                    senderId = "user-1",
                    clientMsgId = "client-1",
                    conversationSeq = 1,
                    type = "IMAGE",
                    state = "RECALLED",
                    text = null,
                    mediaId = null,
                    serverAcceptedAt = "2026-08-20T00:00:00Z",
                    recalledAt = "2026-08-20T00:00:30Z"),
                "user-1")
            assertNull(local.mediaCache().getOrNull("message-media-media-1"))
            assertNull(local.mediaCache().getOrNull("message-media-media-1-thumb"))
            local.close()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun avatar_profile_update_removes_old_versioned_cache_before_the_new_version_is_loaded() {
        val server = MockWebServer()
        server.start()
        val manager = LocalDatabaseManager(
            createTempDirectory("jitong-avatar-sync"),
            InMemoryKeychain())
        try {
            val client = ConversationClient(
                baseUrl = server.url("/").toString(),
                httpClient = OkHttpClient())
            val local = manager.open("12345678903")
            local.upsertConversation(
                LocalConversation(
                    conversationId = "conversation-1",
                    peerUserId = "user-2",
                    peerAccountNo = "22345678902",
                    peerDisplayName = "Bob",
                    peerAvatarUrl = "/old",
                    peerAvatarVersion = 1,
                    peerAvatarFallback = "B",
                    status = "ACTIVE",
                    relationship = "ACTIVE",
                    blockedByMe = false,
                    readSeq = 0,
                    peerReadSeq = 0,
                    updatedAt = 1))
            local.mediaCache().put("avatar-user-2-v1", byteArrayOf(1))
            server.enqueue(MockResponse().setBody(byteArrayOf(2, 3, 4).toString(Charsets.ISO_8859_1)))

            client.applyRealtime(
                local,
                DesktopRealtimeEnvelope(
                    version = 1,
                    operation = "user.profile.updated",
                    requestId = null,
                    body = DesktopRealtimeBody(
                        userId = "user-2",
                        displayName = "Robert",
                        avatarUrl = "/new",
                        avatarVersion = 2,
                        avatarFallback = "R",
                        syncSeq = 1)),
                currentUserId = "user-1")

            assertEquals(2, local.listConversations().single().peerAvatarVersion)
            assertEquals("Robert", local.listConversations().single().peerDisplayName)
            assertNull(local.mediaCache().getOrNull("avatar-user-2-v1"))
            assertEquals(
                listOf<Byte>(2, 3, 4),
                client.loadUserAvatar("access", local, "user-2", 2)?.toList())
            local.close()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun recalled_realtime_image_becomes_a_tombstone_and_deletes_both_media_variants() {
        val server = MockWebServer()
        server.start()
        val manager = LocalDatabaseManager(
            createTempDirectory("jitong-recall-sync"),
            InMemoryKeychain())
        try {
            val client = ConversationClient(
                baseUrl = server.url("/").toString(),
                httpClient = OkHttpClient())
            val local = manager.open("12345678903")
            local.upsertMessage(
                LocalMessage(
                    messageId = "message-1",
                    conversationId = "conversation-1",
                    senderId = "user-1",
                    clientMsgId = "client-1",
                    conversationSeq = 1,
                    type = "IMAGE",
                    state = "ACTIVE",
                    localState = "SENT",
                    text = "",
                    mediaId = "media-1",
                    serverAcceptedAt = "2026-08-20T00:00:00Z",
                    createdAt = 1))
            local.mediaCache().put("message-media-media-1", byteArrayOf(1))
            local.mediaCache().put("message-media-media-1-thumb", byteArrayOf(2))

            client.applyRealtime(
                local,
                DesktopRealtimeEnvelope(
                    version = 1,
                    operation = "message.recalled",
                    requestId = null,
                    body = DesktopRealtimeBody(
                        messageId = "message-1",
                        conversationId = "conversation-1",
                        senderId = "user-1",
                        clientMsgId = "client-1",
                        conversationSeq = 1,
                        type = "IMAGE",
                        state = "RECALLED",
                        text = null,
                        mediaId = null,
                        serverAcceptedAt = "2026-08-20T00:00:00Z",
                        recalledAt = "2026-08-20T00:00:30Z",
                        syncSeq = 1)),
                currentUserId = "user-1")

            val recalled = local.listMessages("conversation-1").single()
            assertEquals("RECALLED", recalled.state)
            assertEquals("", recalled.text)
            assertNull(recalled.mediaId)
            assertNull(local.mediaCache().getOrNull("message-media-media-1"))
            assertNull(local.mediaCache().getOrNull("message-media-media-1-thumb"))
            local.close()
        } finally {
            server.shutdown()
        }
    }

    private fun imageBytes(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color.MAGENTA
        graphics.fillRect(0, 0, width, height)
        graphics.dispose()
        return ByteArrayOutputStream().also { output ->
            check(ImageIO.write(image, "png", output))
        }.toByteArray()
    }
}
