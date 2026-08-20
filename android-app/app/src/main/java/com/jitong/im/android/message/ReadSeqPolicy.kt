package com.jitong.im.android.message

internal object ReadSeqPolicy {

    fun advance(current: Long, requested: Long): Long =
        maxOf(current, requested)
}
