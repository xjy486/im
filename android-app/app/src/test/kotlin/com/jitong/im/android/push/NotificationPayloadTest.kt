package com.jitong.im.android.push

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NotificationPayloadTest {
    @Test
    fun accepts_content_free_new_message_payload() {
        val payload = NotificationPayload.from(
            mapOf(
                "version" to "1",
                "type" to "NEW_MESSAGE",
            ),
        )

        assertEquals("NEW_MESSAGE", payload?.type)
    }

    @Test
    fun rejects_payloads_with_unknown_type_or_version() {
        assertNull(NotificationPayload.from(mapOf("version" to "1", "type" to "MESSAGE_BODY")))
        assertNull(NotificationPayload.from(mapOf("version" to "2", "type" to "NEW_MESSAGE")))
    }

    @Test
    fun rejects_payloads_with_extra_fields_that_could_leak_message_data() {
        assertNull(
            NotificationPayload.from(
                mapOf(
                    "version" to "1",
                    "type" to "NEW_MESSAGE",
                    "text" to "private message",
                    "mediaUrl" to "https://private.invalid/object",
                ),
            ),
        )
    }

}
