package com.jitong.im.android.push

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.jitong.im.android.BuildConfig

internal object FirebaseInitializer {
    fun initialize(context: Context) {
        if (FirebaseApp.getApps(context).isNotEmpty()) return
        val applicationId = BuildConfig.FIREBASE_APPLICATION_ID
        val apiKey = BuildConfig.FIREBASE_API_KEY
        val senderId = BuildConfig.FIREBASE_GCM_SENDER_ID
        val projectId = BuildConfig.FIREBASE_PROJECT_ID
        if (applicationId.isBlank() || apiKey.isBlank() || senderId.isBlank() || projectId.isBlank()) {
            return
        }
        FirebaseApp.initializeApp(
            context,
            FirebaseOptions.Builder()
                .setApplicationId(applicationId)
                .setApiKey(apiKey)
                .setGcmSenderId(senderId)
                .setProjectId(projectId)
                .setStorageBucket(BuildConfig.FIREBASE_STORAGE_BUCKET.ifBlank { null })
                .build(),
        )
    }
}
