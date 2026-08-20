package com.jitong.im.auth;

import com.jitong.im.push.PushProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DevicePushTokenService {

    private static final int GCM_TAG_BITS = 128;
    private static final int IV_LENGTH = 12;

    private final AuthRepository repository;
    private final PushProperties properties;
    private final SecureRandom random = new SecureRandom();

    DevicePushTokenService(AuthRepository repository, PushProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Transactional
    public void update(UUID deviceId, UUID sessionId, String token, long tokenVersion) {
        if (sessionId == null) {
            throw new IllegalArgumentException("Authenticated session is required");
        }
        String digest = TokenDigests.sha256(token);
        if (repository.updatePushToken(
                deviceId,
                sessionId,
                encrypt(token),
                digest,
                tokenVersion) > 0) {
            repository.clearOtherPushTokens(deviceId, digest);
        }
    }

    public boolean isConfigured() {
        return properties.enabled()
                && properties.tokenEncryptionKey() != null
                && !properties.tokenEncryptionKey().isBlank();
    }

    public String find(UUID deviceId) {
        if (!hasEncryptionKey()) {
            return null;
        }
        String ciphertext = repository.findPushToken(deviceId);
        if (ciphertext == null) {
            return null;
        }
        try {
            return decrypt(ciphertext);
        } catch (IllegalStateException exception) {
            repository.clearPushToken(deviceId);
            return null;
        }
    }

    public void clear(UUID deviceId) {
        repository.clearPushToken(deviceId);
    }

    public void clearIfCurrent(UUID deviceId, String token) {
        repository.clearPushTokenIfMatches(deviceId, TokenDigests.sha256(token));
    }

    public boolean isMobile(UUID deviceId) {
        return "MOBILE".equals(repository.findActiveDeviceClass(deviceId));
    }

    private String encrypt(String token) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    key(),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + ciphertext.length)
                            .put(iv)
                            .put(ciphertext)
                            .array());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not encrypt push token", exception);
        }
    }

    private String decrypt(String encoded) {
        try {
            byte[] packed = Base64.getDecoder().decode(encoded);
            if (packed.length < IV_LENGTH + 16) {
                throw new IllegalArgumentException("Encrypted push token is too short");
            }
            byte[] iv = new byte[IV_LENGTH];
            byte[] ciphertext = new byte[packed.length - IV_LENGTH];
            System.arraycopy(packed, 0, iv, 0, iv.length);
            System.arraycopy(packed, iv.length, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    key(),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | RuntimeException exception) {
            throw new IllegalStateException("Could not decrypt push token", exception);
        }
    }

    private SecretKeySpec key() {
        if (!hasEncryptionKey()) {
            throw new IllegalStateException("FCM token encryption key is not configured");
        }
        try {
            return new SecretKeySpec(
                    MessageDigest.getInstance("SHA-256")
                            .digest(properties.tokenEncryptionKey().getBytes(StandardCharsets.UTF_8)),
                    "AES");
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not derive push token encryption key", exception);
        }
    }

    private boolean hasEncryptionKey() {
        return properties.tokenEncryptionKey() != null
                && !properties.tokenEncryptionKey().isBlank();
    }
}
