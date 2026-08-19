package com.jitong.im.android.security

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/** Stores one independently wrapped SQLCipher passphrase per account. */
class AccountKeyStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val box = AndroidSecretBox(KEY_ALIAS)
    private val random = SecureRandom()

    @Synchronized
    fun databasePassphrase(accountNo: String): String {
        val key = preferenceKey(accountNo)
        val envelope = preferences.getString(key, null)
        if (envelope != null) {
            return String(box.decrypt(envelope), Charsets.UTF_8)
        }

        val bytes = ByteArray(32).also(random::nextBytes)
        val passphrase = Base64.encodeToString(bytes, Base64.NO_WRAP)
        preferences.edit()
            .putString(key, box.encrypt(passphrase))
            .apply()
        return passphrase
    }

    @Synchronized
    fun forget(accountNo: String) {
        preferences.edit().remove(preferenceKey(accountNo)).apply()
    }

    fun databaseName(accountNo: String): String = "account_${digest(accountNo)}.db"

    fun mediaDirectoryName(accountNo: String): String = "account-media/${digest(accountNo)}"

    private fun preferenceKey(accountNo: String): String = "db_key_${digest(accountNo)}"

    private fun digest(value: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val PREFERENCES = "jitong_secure_state"
        const val KEY_ALIAS = "jitong.account.keyring"
    }
}
