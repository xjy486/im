package com.jitong.im.android.push

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf

internal object PushTokenRegistrationScheduler {
    private const val WORK_NAME = "jitong-push-token-registration"
    const val TOKEN_KEY = "fcm-token"
    const val TOKEN_VERSION_KEY = "fcm-token-version"

    fun enqueue(context: Context, token: String? = null, tokenVersion: Long? = null) {
        val builder = OneTimeWorkRequestBuilder<PushTokenRegistrationWorker>()
        if (!token.isNullOrBlank()) {
            builder.setInputData(
                workDataOf(
                    TOKEN_KEY to token,
                    TOKEN_VERSION_KEY to (tokenVersion ?: System.currentTimeMillis()),
                ),
            )
        }
        val request = builder
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
    }
}
