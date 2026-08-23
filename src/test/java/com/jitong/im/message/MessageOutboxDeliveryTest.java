package com.jitong.im.message;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jitong.im.auth.DevicePushTokenService;
import com.jitong.im.push.FcmDeliveryResult;
import com.jitong.im.push.FcmSender;
import com.jitong.im.sync.OutboxRecord;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

class MessageOutboxDeliveryTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final MessageRepository messageRepository = mock(MessageRepository.class);
    private final ReadStateRepository readStateRepository = mock(ReadStateRepository.class);
    private final DevicePushTokenService pushTokenService = mock(DevicePushTokenService.class);
    private final FcmSender fcmSender = mock(FcmSender.class);
    private final MessageOutboxDelivery delivery = new MessageOutboxDelivery(
            objectMapper,
            messageRepository,
            readStateRepository,
            pushTokenService,
            fcmSender);

    @Test
    void sends_a_content_free_fcm_fallback_when_no_mobile_websocket_is_connected() {
        OutboxRecord record = record("MESSAGE_CREATED");
        when(messageRepository.findUserIdForDevice(record.targetDeviceId())).thenReturn(UUID.randomUUID());
        when(pushTokenService.isMobile(record.targetDeviceId())).thenReturn(true);
        when(pushTokenService.find(record.targetDeviceId())).thenReturn("fcm-token");
        when(fcmSender.sendNewMessage("fcm-token")).thenReturn(FcmDeliveryResult.SENT);

        assertThat(delivery.deliver(record)).isTrue();

        verify(fcmSender).sendNewMessage("fcm-token");
        verifyNoInteractions(readStateRepository);
    }

    @Test
    void sends_fcm_when_every_connected_websocket_write_fails() throws Exception {
        OutboxRecord record = record("MESSAGE_CREATED");
        WebSocketSession session = mock(WebSocketSession.class);
        when(messageRepository.findUserIdForDevice(record.targetDeviceId())).thenReturn(UUID.randomUUID());
        when(messageRepository.findById(record.entityId())).thenReturn(message());
        when(pushTokenService.isMobile(record.targetDeviceId())).thenReturn(true);
        when(pushTokenService.find(record.targetDeviceId())).thenReturn("fcm-token");
        when(fcmSender.sendNewMessage("fcm-token")).thenReturn(FcmDeliveryResult.SENT);
        when(session.isOpen()).thenReturn(true);
        doThrow(new IOException("socket closed")).when(session).sendMessage(any());
        delivery.register(record.targetDeviceId(), session);

        assertThat(delivery.deliver(record)).isTrue();

        verify(fcmSender).sendNewMessage("fcm-token");
        verify(session).sendMessage(any());
    }

    @Test
    void does_not_send_fcm_when_the_mobile_websocket_write_succeeds() throws Exception {
        OutboxRecord record = record("MESSAGE_CREATED");
        WebSocketSession session = mock(WebSocketSession.class);
        when(messageRepository.findUserIdForDevice(record.targetDeviceId())).thenReturn(UUID.randomUUID());
        when(messageRepository.findById(record.entityId())).thenReturn(message());
        when(session.isOpen()).thenReturn(true);
        delivery.register(record.targetDeviceId(), session);

        assertThat(delivery.deliver(record)).isTrue();

        verify(session).sendMessage(any());
        verifyNoInteractions(fcmSender);
    }

    @Test
    void does_not_send_fcm_to_pc_devices() {
        OutboxRecord record = record("MESSAGE_CREATED");
        when(messageRepository.findUserIdForDevice(record.targetDeviceId())).thenReturn(UUID.randomUUID());
        when(pushTokenService.isMobile(record.targetDeviceId())).thenReturn(false);

        assertThat(delivery.deliver(record)).isTrue();

        verifyNoInteractions(fcmSender);
    }

    @Test
    void retries_the_outbox_row_when_fcm_temporarily_fails() {
        OutboxRecord record = record("MESSAGE_CREATED");
        when(messageRepository.findUserIdForDevice(record.targetDeviceId())).thenReturn(UUID.randomUUID());
        when(pushTokenService.isMobile(record.targetDeviceId())).thenReturn(true);
        when(pushTokenService.find(record.targetDeviceId())).thenReturn("fcm-token");
        when(fcmSender.sendNewMessage("fcm-token")).thenReturn(FcmDeliveryResult.RETRYABLE_FAILURE);

        assertThat(delivery.deliver(record)).isFalse();
    }

    @Test
    void sends_a_profile_changed_fcm_fallback_for_offline_avatar_updates() {
        OutboxRecord record = record("USER_PROFILE_UPDATED");
        when(messageRepository.findUserIdForDevice(record.targetDeviceId())).thenReturn(UUID.randomUUID());
        when(pushTokenService.isMobile(record.targetDeviceId())).thenReturn(true);
        when(pushTokenService.find(record.targetDeviceId())).thenReturn("fcm-token");
        when(fcmSender.sendProfileChanged("fcm-token")).thenReturn(FcmDeliveryResult.SENT);

        assertThat(delivery.deliver(record)).isTrue();

        verify(fcmSender).sendProfileChanged("fcm-token");
    }

    @Test
    void sends_a_profile_changed_fcm_fallback_for_offline_group_cleanup() {
        OutboxRecord record = record("GROUP_DISSOLVED");
        when(messageRepository.findUserIdForDevice(record.targetDeviceId())).thenReturn(UUID.randomUUID());
        when(pushTokenService.isMobile(record.targetDeviceId())).thenReturn(true);
        when(pushTokenService.find(record.targetDeviceId())).thenReturn("fcm-token");
        when(fcmSender.sendProfileChanged("fcm-token")).thenReturn(FcmDeliveryResult.SENT);

        assertThat(delivery.deliver(record)).isTrue();

        verify(fcmSender).sendProfileChanged("fcm-token");
    }

    @Test
    void clears_a_permanently_invalid_token_without_revoking_the_device() {
        OutboxRecord record = record("MESSAGE_CREATED");
        when(messageRepository.findUserIdForDevice(record.targetDeviceId())).thenReturn(UUID.randomUUID());
        when(pushTokenService.isMobile(record.targetDeviceId())).thenReturn(true);
        when(pushTokenService.find(record.targetDeviceId())).thenReturn("stale-token");
        when(fcmSender.sendNewMessage("stale-token"))
                .thenReturn(FcmDeliveryResult.PERMANENT_TOKEN_FAILURE);

        assertThat(delivery.deliver(record)).isTrue();

        verify(pushTokenService).clearIfCurrent(record.targetDeviceId(), "stale-token");
    }

    @Test
    void delivers_private_ai_deletion_events_without_reloading_deleted_content() throws Exception {
        assertDeletionEnvelope("AI_ARTIFACT_DELETED", "ai.artifact.deleted");
        assertDeletionEnvelope("AI_JOB_DELETED", "ai.job.deleted");
    }

    private void assertDeletionEnvelope(String eventType, String operation) throws Exception {
        OutboxRecord record = record(eventType);
        WebSocketSession session = mock(WebSocketSession.class);
        AtomicReference<String> payload = new AtomicReference<>();
        when(messageRepository.findUserIdForDevice(record.targetDeviceId())).thenReturn(UUID.randomUUID());
        when(session.isOpen()).thenReturn(true);
        doAnswer(invocation -> {
            org.springframework.web.socket.TextMessage message = invocation.getArgument(0);
            payload.set(message.getPayload());
            return null;
        }).when(session).sendMessage(any());
        delivery.register(record.targetDeviceId(), session);

        assertThat(delivery.deliver(record)).isTrue();

        assertThat(objectMapper.readTree(payload.get()).get("operation").asText()).isEqualTo(operation);
        assertThat(objectMapper.readTree(payload.get()).get("body").get("entityId").asText())
                .isEqualTo(record.entityId().toString());
        assertDeletionPayloadMatchesSchema(objectMapper.readTree(payload.get()));
    }

    private void assertDeletionPayloadMatchesSchema(JsonNode payload) throws IOException {
        JsonNode schema = objectMapper.readTree(
                Path.of("contracts/schemas/realtime-v1.schema.json").toFile());
        JsonNode definitions = schema.required("$defs");
        JsonNode baseEnvelope = definitions.required("baseEnvelope");
        JsonNode deletionEnvelope = definitions.required("aiDeletionEnvelope");
        JsonNode deletionBody = definitions.required("aiDeletionBody");

        assertThat(schema.required("oneOf"))
                .anySatisfy(candidate -> assertThat(candidate.required("$ref").asText())
                        .isEqualTo("#/$defs/aiDeletionEnvelope"));
        assertThat(fieldNames(payload)).isEqualTo(fieldNames(baseEnvelope.required("properties")));
        assertThat(payload.required("version").asInt())
                .isEqualTo(baseEnvelope.required("properties").required("version").required("const").asInt());
        assertThat(deletionEnvelope.required("allOf").get(1)
                .required("properties").required("operation").required("enum"))
                .anySatisfy(operation -> assertThat(operation.asText())
                        .isEqualTo(payload.required("operation").asText()));
        assertThat(payload.get("requestId").isNull()).isTrue();

        JsonNode body = payload.required("body");
        assertThat(fieldNames(body)).isEqualTo(fieldNames(deletionBody.required("properties")));
        UUID.fromString(body.required("entityId").asText());
        UUID.fromString(body.required("conversationId").asText());
        assertThat(body.required("syncSeq").asLong()).isPositive();
    }

    private Set<String> fieldNames(JsonNode object) {
        Set<String> names = new HashSet<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private OutboxRecord record(String eventType) {
        return new OutboxRecord(
                UUID.randomUUID(),
                eventType,
                UUID.randomUUID(),
                UUID.randomUUID(),
                7,
                UUID.randomUUID(),
                1,
                Instant.EPOCH);
    }

    private MessageRecord message() {
        return new MessageRecord(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                3,
                "TEXT",
                "ACTIVE",
                "private message that must never enter FCM",
                Instant.EPOCH);
    }
}
