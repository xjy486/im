package com.jitong.im.desktop

internal fun shouldRefreshContactRequests(operation: String?): Boolean =
    operation == "contact.request.created"
