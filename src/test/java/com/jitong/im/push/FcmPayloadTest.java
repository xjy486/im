package com.jitong.im.push;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FcmPayloadTest {

    @Test
    void new_message_payload_contains_only_version_and_event_type() {
        assertThat(FcmPayload.newMessageData())
                .containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                        "version", "1",
                "type", "NEW_MESSAGE"));
    }

    @Test
    void profile_changed_payload_contains_only_version_and_event_type() {
        assertThat(FcmPayload.profileChangedData())
                .containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                        "version", "1",
                "type", "PROFILE_CHANGED"));
    }

    @Test
    void contact_request_payload_contains_only_version_and_event_type() {
        assertThat(FcmPayload.contactRequestData())
                .containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                        "version", "1",
                        "type", "CONTACT_REQUEST"));
    }

    @Test
    void group_invite_payload_contains_only_version_and_event_type() {
        assertThat(FcmPayload.groupInviteData())
                .containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                        "version", "1",
                        "type", "GROUP_INVITE"));
    }

}
