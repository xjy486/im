package com.jitong.im.android

import android.app.Application
import com.jitong.im.android.push.FirebaseInitializer

internal class JitongApplication : Application() {
    lateinit var container: AppContainer
        private set

    fun containerOrNull(): AppContainer? = if (::container.isInitialized) container else null

    override fun onCreate() {
        super.onCreate()
        FirebaseInitializer.initialize(this)
        container = AppContainer(this)
    }
}
