package com.jitong.im.android.ui

internal const val MESSAGE_LIST_DELETE_ACTION_WIDTH_DP = 88

internal fun messageListSwipeOffset(
    currentOffset: Float,
    dragAmount: Float,
): Float = (currentOffset + dragAmount).coerceIn(
    -MESSAGE_LIST_DELETE_ACTION_WIDTH_DP.toFloat(),
    0f,
)

internal fun settleMessageListSwipe(offset: Float): Float =
    if (offset <= -MESSAGE_LIST_DELETE_ACTION_WIDTH_DP / 2f) {
        -MESSAGE_LIST_DELETE_ACTION_WIDTH_DP.toFloat()
    } else {
        0f
    }
