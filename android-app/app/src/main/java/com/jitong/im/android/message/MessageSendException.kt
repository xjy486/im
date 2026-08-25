package com.jitong.im.android.message

import java.io.IOException

internal class MessageSendException(
    val retryable: Boolean,
    val code: String? = null,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
