package com.jitong.im.android.push

import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.jitong.im.android.JitongApplication
import com.jitong.im.android.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal class JitongFirebaseMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val payload = NotificationPayload.from(message.data) ?: return
        NotificationChannels.ensure(this)
        val intent = Intent(this, NotificationClickActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            payload.type.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        NotificationManagerCompat.from(this).notify(
            payload.type.hashCode(),
            NotificationCompat.Builder(this, NotificationChannels.NEW_MESSAGE)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("即通")
                .setContentText("你有一条新消息，点击查看")
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build(),
        )
    }

    override fun onNewToken(token: String) {
        val application = application as? JitongApplication ?: return
        val container = application.containerOrNull() ?: return
        if (container.sessionSnapshot() == null) return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { container.pushTokenRepository.register(token) }
        }
    }
}
