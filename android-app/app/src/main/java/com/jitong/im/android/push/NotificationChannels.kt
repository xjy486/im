package com.jitong.im.android.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

internal object NotificationChannels {
    const val NEW_MESSAGE = "new-message"

    fun ensure(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NEW_MESSAGE,
                "新消息",
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }
}
