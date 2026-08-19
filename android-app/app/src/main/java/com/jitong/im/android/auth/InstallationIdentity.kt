package com.jitong.im.android.auth

import android.content.Context
import java.util.UUID

class InstallationIdentity(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    val value: String
        @Synchronized get() = preferences.getString(KEY, null)
            ?: UUID.randomUUID().toString().also {
                preferences.edit().putString(KEY, it).apply()
            }

    private companion object {
        const val PREFERENCES = "jitong_device_identity"
        const val KEY = "installation_id"
    }
}
