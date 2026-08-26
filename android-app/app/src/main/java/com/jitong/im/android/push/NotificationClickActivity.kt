package com.jitong.im.android.push

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.jitong.im.android.JitongApplication
import com.jitong.im.android.MainActivity

internal class NotificationClickActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as JitongApplication).container.handleNotification(
            intent.getStringExtra("notification_type") ?: "NEW_MESSAGE",
        )
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
