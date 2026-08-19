package com.jitong.im.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import com.jitong.im.android.ui.AuthViewModel
import com.jitong.im.android.ui.ContactViewModel
import com.jitong.im.android.ui.JitongApp

class MainActivity : ComponentActivity() {
    private val viewModel: AuthViewModel by viewModels {
        val container = (application as JitongApplication).container
        AuthViewModel.Factory(container.authRepository, container.sessionState)
    }
    private val contactViewModel: ContactViewModel by viewModels {
        val container = (application as JitongApplication).container
        ContactViewModel.Factory(container.contactRepository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                JitongApp(viewModel, contactViewModel)
            }
        }
    }
}
