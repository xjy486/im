package com.jitong.im.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContactRealtimeEventPolicyTest {

    @Test
    fun contact_request_created_requires_immediate_request_list_refresh() {
        assertTrue(shouldRefreshContactRequests("contact.request.created"))
    }

    @Test
    fun unrelated_realtime_events_do_not_refresh_request_list() {
        assertFalse(shouldRefreshContactRequests("message.created"))
    }
}
