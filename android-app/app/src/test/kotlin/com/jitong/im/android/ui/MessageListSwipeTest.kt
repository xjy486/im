package com.jitong.im.android.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class MessageListSwipeTest {

    @Test
    fun swipe_is_bounded_to_the_delete_action_width() {
        assertEquals(
            -MESSAGE_LIST_DELETE_ACTION_WIDTH_DP.toFloat(),
            messageListSwipeOffset(0f, -500f),
        )
        assertEquals(0f, messageListSwipeOffset(0f, 100f))
    }

    @Test
    fun swiping_right_closes_the_revealed_delete_action() {
        assertEquals(
            0f,
            messageListSwipeOffset(
                -MESSAGE_LIST_DELETE_ACTION_WIDTH_DP.toFloat(),
                MESSAGE_LIST_DELETE_ACTION_WIDTH_DP.toFloat(),
            ),
        )
    }

    @Test
    fun settling_reveals_the_action_only_after_crossing_half_width() {
        assertEquals(0f, settleMessageListSwipe(-20f))
        assertEquals(
            -MESSAGE_LIST_DELETE_ACTION_WIDTH_DP.toFloat(),
            settleMessageListSwipe(-60f),
        )
    }
}
