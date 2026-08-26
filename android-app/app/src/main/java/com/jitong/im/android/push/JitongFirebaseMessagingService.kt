package com.jitong.im.android.push

import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.jitong.im.android.JitongApplication
import com.jitong.im.android.R

internal class JitongFirebaseMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val payload = NotificationPayload.from(message.data) ?: return
        val application = application as? JitongApplication
        if (payload.type == "PROFILE_CHANGED") {
            application?.containerOrNull()?.handleNotification(payload.type)
            return
        }
        if (payload.type == "CONTACT_REQUEST") {
            application?.containerOrNull()?.handleNotification(payload.type)
        }
        NotificationChannels.ensure(this)
        val notificationId = (System.currentTimeMillis() and 0x7fffffff).toInt()
        val intent = Intent(this, NotificationClickActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("notification_type", payload.type)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        NotificationManagerCompat.from(this).notify(
            notificationId,
            NotificationCompat.Builder(this, NotificationChannels.NEW_MESSAGE)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("即通")
                .setContentText(
                    if (payload.type == "CONTACT_REQUEST") {
                        "你收到一条好友申请，点击查看"
                    } else {
                        "你有一条新消息，点击查看"
                    },
                )
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build(),
        )
    }

    override fun onNewToken(token: String) {
        val application = application as? JitongApplication ?: return
        val container = application.containerOrNull() ?: return
        if (container.sessionSnapshot() == null) return
        PushTokenRegistrationScheduler.enqueue(application, token, System.currentTimeMillis())
    }
}
