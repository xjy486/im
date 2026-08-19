package com.jitong.im.android.security

import android.content.Context
import com.google.gson.Gson
import com.jitong.im.android.auth.SessionSnapshot

class SecureSessionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val box = AndroidSecretBox(KEY_ALIAS)
    private val gson = Gson()

    @Synchronized
    fun read(): SessionSnapshot? {
        val envelope = preferences.getString(SESSION_KEY, null) ?: return null
        return runCatching {
            gson.fromJson(box.decryptString(envelope), SessionSnapshot::class.java)
        }.getOrNull()
    }

    @Synchronized
    fun write(session: SessionSnapshot) {
        preferences.edit()
            .putString(SESSION_KEY, box.encrypt(gson.toJson(session)))
            .apply()
    }

    @Synchronized
    fun clear() {
        preferences.edit().remove(SESSION_KEY).apply()
    }

    private companion object {
        const val PREFERENCES = "jitong_secure_state"
        const val SESSION_KEY = "session"
        const val KEY_ALIAS = "jitong.session.key"
    }
}
