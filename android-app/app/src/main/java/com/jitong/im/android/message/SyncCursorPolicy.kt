package com.jitong.im.android.message

internal object SyncCursorPolicy {

    fun validatePage(page: SyncPageResponse, afterSeq: Long) {
        if (page.afterSeq != afterSeq
            || page.untilSeq < page.afterSeq
            || page.untilSeq > page.highWatermark
            || page.nextAfterSeq < page.afterSeq
            || page.nextAfterSeq > page.untilSeq
            || page.hasMore != (page.nextAfterSeq < page.untilSeq)
        ) {
            throw SyncResetRequiredException()
        }
        if (page.events.isEmpty()) {
            if (page.hasMore) throw SyncResetRequiredException()
            return
        }
        if (page.events.first().syncSeq != afterSeq + 1L
            || page.events.last().syncSeq != page.nextAfterSeq
            || page.events.zipWithNext().any { (left, right) -> right.syncSeq != left.syncSeq + 1L }
        ) {
            throw SyncResetRequiredException()
        }
    }
}

internal class SyncResetRequiredException : java.io.IOException()
