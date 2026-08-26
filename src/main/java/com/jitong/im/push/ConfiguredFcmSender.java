package com.jitong.im.push;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.jitong.im.platform.observability.OperationalMetrics;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Primary
@Component
class ConfiguredFcmSender implements FcmSender {

    private final PushProperties properties;
    private final FirebaseMessaging messaging;
    private final OperationalMetrics metrics;

    ConfiguredFcmSender(PushProperties properties, OperationalMetrics metrics) {
        this.properties = properties;
        this.messaging = initialize(properties);
        this.metrics = metrics;
    }

    @Override
    public FcmDeliveryResult sendNewMessage(String token) {
        return send(token, FcmPayload.newMessageData());
    }

    @Override
    public FcmDeliveryResult sendContactRequest(String token) {
        return send(token, FcmPayload.contactRequestData());
    }

    @Override
    public FcmDeliveryResult sendGroupInvite(String token) {
        return send(token, FcmPayload.groupInviteData());
    }

    @Override
    public FcmDeliveryResult sendProfileChanged(String token) {
        return send(token, FcmPayload.profileChangedData());
    }

    private FcmDeliveryResult send(String token, java.util.Map<String, String> payload) {
        if (!properties.enabled()) {
            return FcmDeliveryResult.NOT_CONFIGURED;
        }
        if (token == null || token.isBlank()) {
            return FcmDeliveryResult.NO_TOKEN;
        }
        try {
            messaging.send(
                    Message.builder()
                            .setToken(token)
                            .setAndroidConfig(AndroidConfig.builder()
                                    .setPriority(AndroidConfig.Priority.HIGH)
                                    .build())
                            .putAllData(payload)
                            .build());
            return FcmDeliveryResult.SENT;
        } catch (FirebaseMessagingException | RuntimeException exception) {
            if (exception instanceof FirebaseMessagingException messagingException
                    && messagingException.getMessagingErrorCode()
                    == com.google.firebase.messaging.MessagingErrorCode.UNREGISTERED) {
                metrics.fcmFailures().increment();
                return FcmDeliveryResult.PERMANENT_TOKEN_FAILURE;
            }
            metrics.fcmFailures().increment();
            return FcmDeliveryResult.RETRYABLE_FAILURE;
        }
    }

    private FirebaseMessaging initialize(PushProperties properties) {
        if (!properties.enabled()) {
            return null;
        }
        try {
            FirebaseApp app = FirebaseApp.getApps().stream()
                    .filter(candidate -> candidate.getName().equals("[DEFAULT]"))
                    .findFirst()
                    .orElseGet(() -> initializeApp(properties));
            return FirebaseMessaging.getInstance(app);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("FCM is enabled but not configured", exception);
        }
    }

    private FirebaseApp initializeApp(PushProperties properties) {
        try {
            return FirebaseApp.initializeApp(
                    FirebaseOptions.builder()
                            .setCredentials(GoogleCredentials.fromStream(
                                    new ByteArrayInputStream(
                                            properties.serviceAccountJson()
                                                    .getBytes(StandardCharsets.UTF_8))))
                            .setProjectId(properties.projectId())
                            .build());
        } catch (IOException exception) {
            throw new IllegalStateException("FCM service account JSON is invalid", exception);
        }
    }
}
