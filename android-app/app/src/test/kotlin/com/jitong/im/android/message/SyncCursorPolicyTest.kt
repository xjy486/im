package com.jitong.im.android.message

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith

class SyncCursorPolicyTest {

    @Test
    fun accepts_a_contiguous_page_at_the_requested_boundary() {
        SyncCursorPolicy.validatePage(
            page = page(
                afterSeq = 2,
                untilSeq = 6,
                highWatermark = 6,
                nextAfterSeq = 4,
                hasMore = true,
                events = listOf(3, 4),
            ),
            afterSeq = 2,
        )
    }

    @Test
    fun rejects_a_page_that_skips_a_sync_sequence() {
        assertFailsWith<SyncResetRequiredException> {
            SyncCursorPolicy.validatePage(
                page = page(
                    afterSeq = 0,
                    untilSeq = 3,
                    highWatermark = 3,
                    nextAfterSeq = 3,
                    hasMore = false,
                    events = listOf(1, 3),
                ),
                afterSeq = 0,
            )
        }
    }

    @Test
    fun rejects_a_page_with_a_mismatched_cursor_boundary() {
        assertFailsWith<SyncResetRequiredException> {
            SyncCursorPolicy.validatePage(
                page = page(
                    afterSeq = 1,
                    untilSeq = 2,
                    highWatermark = 2,
                    nextAfterSeq = 2,
                    hasMore = false,
                    events = listOf(2),
                ),
                afterSeq = 0,
            )
        }
    }

    private fun page(
        afterSeq: Long,
        untilSeq: Long,
        highWatermark: Long,
        nextAfterSeq: Long,
        hasMore: Boolean,
        events: List<Long>,
    ) = SyncPageResponse(
        version = 1,
        afterSeq = afterSeq,
        highWatermark = highWatermark,
        untilSeq = untilSeq,
        nextAfterSeq = nextAfterSeq,
        hasMore = hasMore,
        events = events.map { sequence ->
            SyncEventResponse(
                syncSeq = sequence,
                eventType = "MESSAGE_CREATED",
                entityId = UUID.randomUUID(),
                conversationId = UUID.randomUUID(),
                createdAt = "2026-08-20T00:00:00Z",
            )
        },
    )
}
