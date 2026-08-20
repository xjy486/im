package com.jitong.im.android.message

import kotlin.test.Test
import kotlin.test.assertEquals

class ReadSeqPolicyTest {

    @Test
    fun an_older_read_update_cannot_move_local_progress_backwards() {
        assertEquals(7L, ReadSeqPolicy.advance(current = 7L, requested = 3L))
    }

    @Test
    fun a_newer_read_update_advances_local_progress() {
        assertEquals(9L, ReadSeqPolicy.advance(current = 7L, requested = 9L))
    }
}
