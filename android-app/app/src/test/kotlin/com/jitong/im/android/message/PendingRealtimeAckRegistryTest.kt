package com.jitong.im.android.message

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PendingRealtimeAckRegistryTest {
    @Test
    fun a_not_contact_error_completes_the_waiting_send_without_throwing_into_the_event_loop() = runTest {
        val registry = PendingRealtimeAckRegistry()
        val clientMsgId = UUID.randomUUID()
        val requestId = UUID.randomUUID()
        val deferred = CompletableDeferred<MessageResponse>()

        registry.register(clientMsgId, requestId, deferred)

        registry.fail(requestId, "NOT_CONTACT", "The conversation is not available")

        assertFailsWith<MessageSendException> {
            deferred.await()
        }
    }
}
