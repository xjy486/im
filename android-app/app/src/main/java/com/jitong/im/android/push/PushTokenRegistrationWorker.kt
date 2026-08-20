package com.jitong.im.android.push

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jitong.im.android.JitongApplication

internal class PushTokenRegistrationWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val application = applicationContext as? JitongApplication ?: return Result.failure()
        val container = application.containerOrNull() ?: return Result.success()
        if (container.sessionSnapshot() == null) return Result.success()
        if (!container.restoreSessionForWorker()) return Result.success()
        val token = inputData.getString(PushTokenRegistrationScheduler.TOKEN_KEY)
        val registered = if (token.isNullOrBlank()) {
            container.registerCurrentPushTokenForWorker()
        } else {
            container.registerPushToken(
                token,
                inputData.getLong(
                    PushTokenRegistrationScheduler.TOKEN_VERSION_KEY,
                    System.currentTimeMillis()),
            )
        }
        return if (registered) {
            Result.success()
        } else {
            Result.retry()
        }
    }
}
