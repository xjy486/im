package com.jitong.im.android.message

import kotlinx.coroutines.CancellationException
import java.util.UUID

internal class SyncReadyHandler(
    private val synchronize: suspend (UUID, Long) -> Unit,
    private val onFailure: (Throwable) -> Unit = {},
) {
    suspend fun handle(userId: UUID, watermark: Long) {
        try {
            synchronize(userId, watermark)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Throwable) {
            onFailure(exception)
        }
    }
}
