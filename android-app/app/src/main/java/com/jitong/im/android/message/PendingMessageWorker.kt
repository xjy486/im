package com.jitong.im.android.message

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jitong.im.android.JitongApplication

internal class PendingMessageWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val application = applicationContext as? JitongApplication ?: return Result.failure()
        val container = application.containerOrNull() ?: return Result.success()
        if (container.sessionSnapshot() == null) {
            return Result.success()
        }
        return runCatching {
            if (!container.restoreSessionForWorker()) {
                return@runCatching Result.success()
            }
            container.syncLatestForWorker()
            val result = container.messageRepository.flushOnlinePending()
            if (result.retryableFailure) Result.retry() else Result.success()
        }.getOrElse {
            Result.retry()
        }
    }
}
