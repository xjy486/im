package com.jitong.im.android.message

import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncReadyHandlerTest {

    @Test
    fun sync_failure_does_not_escape_the_websocket_event_loop() = runTest {
        var failure: Throwable? = null
        val handler = SyncReadyHandler(
            synchronize = { _, _ ->
                throw IOException("Sync request failed with HTTP 404")
            },
            onFailure = { failure = it },
        )

        handler.handle(UUID.randomUUID(), watermark = 7)

        assertEquals("Sync request failed with HTTP 404", failure?.message)
    }
}
