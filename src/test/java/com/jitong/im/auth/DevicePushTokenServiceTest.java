package com.jitong.im.auth;

import com.jitong.im.push.PushProperties;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DevicePushTokenServiceTest {

    private static final UUID DEVICE_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();

    @Test
    void encrypts_tokens_at_rest_and_round_trips_the_current_token() {
        AuthRepository repository = mock(AuthRepository.class);
        PushProperties properties = new PushProperties(true, "project", "credentials", "test-key");
        DevicePushTokenService service = new DevicePushTokenService(repository, properties);

        var ciphertext = new java.util.concurrent.atomic.AtomicReference<String>();
        doAnswer(invocation -> {
            ciphertext.set(invocation.getArgument(2));
            return 1;
        }).when(repository).updatePushToken(eq(DEVICE_ID), eq(SESSION_ID), any(), any(), eq(11L));
        service.update(DEVICE_ID, SESSION_ID, "current-fcm-token", 11L);
        verify(repository).updatePushToken(eq(DEVICE_ID), eq(SESSION_ID), any(), any(), eq(11L));
        assertThat(ciphertext.get()).doesNotContain("current-fcm-token");

        when(repository.findPushToken(DEVICE_ID)).thenReturn(ciphertext.get());
        assertThat(service.find(DEVICE_ID)).isEqualTo("current-fcm-token");
    }

    @Test
    void clears_and_ignores_corrupted_ciphertext() {
        AuthRepository repository = mock(AuthRepository.class);
        PushProperties properties = new PushProperties(true, "project", "credentials", "test-key");
        DevicePushTokenService service = new DevicePushTokenService(repository, properties);
        when(repository.findPushToken(DEVICE_ID)).thenReturn("not-valid-ciphertext");

        assertThat(service.find(DEVICE_ID)).isNull();

        verify(repository).clearPushToken(DEVICE_ID);
    }
}
