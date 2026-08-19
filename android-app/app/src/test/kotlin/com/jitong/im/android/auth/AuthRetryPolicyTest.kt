package com.jitong.im.android.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthRetryPolicyTest {
    @Test
    fun refreshes_once_when_the_failed_request_used_the_current_access_token() {
        assertTrue(AuthRetryPolicy.mayRefresh(1, "access-1", "access-1"))
    }

    @Test
    fun does_not_refresh_when_a_newer_token_is_already_stored() {
        assertFalse(AuthRetryPolicy.mayRefresh(1, "access-old", "access-new"))
    }

    @Test
    fun does_not_retry_a_second_unauthorized_response() {
        assertFalse(AuthRetryPolicy.mayRefresh(2, "access-1", "access-1"))
    }

    @Test
    fun does_not_refresh_without_a_bearer_token() {
        assertFalse(AuthRetryPolicy.mayRefresh(1, null, "access-1"))
    }
}
