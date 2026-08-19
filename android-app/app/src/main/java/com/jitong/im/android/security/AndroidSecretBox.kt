package com.jitong.im.android.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small Android Keystore-backed envelope encryptor.
 *
 * The Keystore key never leaves the device. Values stored in preferences or files are
 * ciphertext and can only be opened by this installation's Keystore key.
 */
class AndroidSecretBox(
    private val alias: String,
) {
    fun encrypt(value: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(value)
        return "${encode(iv)}.${encode(ciphertext)}"
    }

    fun decrypt(envelope: String): ByteArray {
        val parts = envelope.split('.', limit = 2)
        require(parts.size == 2) { "Invalid encrypted value" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, decode(parts[0])),
        )
        return cipher.doFinal(decode(parts[1]))
    }

    fun encrypt(value: String): String = encrypt(value.toByteArray(StandardCharsets.UTF_8))

    fun decryptString(envelope: String): String =
        decrypt(envelope).toString(StandardCharsets.UTF_8)

    fun deleteKey() {
        keyStore().deleteEntry(alias)
    }

    private fun key(): SecretKey {
        val store = keyStore()
        if (!store.containsAlias(alias)) {
            val generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE,
            )
            generator.init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generator.generateKey()
        }
        return (store.getKey(alias, null) as SecretKey)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).also { it.load(null) }

    private fun encode(value: ByteArray): String = Base64.encodeToString(value, Base64.NO_WRAP)

    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
    }
}
