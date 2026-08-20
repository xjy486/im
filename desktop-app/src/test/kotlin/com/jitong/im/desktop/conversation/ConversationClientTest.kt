package com.jitong.im.desktop.conversation

import com.jitong.im.desktop.local.InMemoryKeychain
import com.jitong.im.desktop.local.LocalDatabaseManager
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

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
}
