package com.jitong.im.push;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Primary
@Component
class ConfiguredFcmSender implements FcmSender {

    private final PushProperties properties;
    private final FirebaseMessaging messaging;

    ConfiguredFcmSender(PushProperties properties) {
        this.properties = properties;
        this.messaging = initialize(properties);
    }

    @Override
    public FcmDeliveryResult send(String token, String eventType) {
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
                            .putAllData(Map.of(
                                    "version", "1",
                                    "type", eventType))
                            .build());
            return FcmDeliveryResult.SENT;
        } catch (FirebaseMessagingException | RuntimeException exception) {
            if (exception instanceof FirebaseMessagingException messagingException
                    && messagingException.getMessagingErrorCode()
                    == com.google.firebase.messaging.MessagingErrorCode.UNREGISTERED) {
                return FcmDeliveryResult.PERMANENT_FAILURE;
            }
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
