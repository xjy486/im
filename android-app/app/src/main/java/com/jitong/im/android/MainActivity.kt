package com.jitong.im.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import com.jitong.im.android.ui.AuthViewModel
import com.jitong.im.android.ui.AiViewModel
import com.jitong.im.android.ui.ContactViewModel
import com.jitong.im.android.ui.GroupViewModel
import com.jitong.im.android.ui.JitongApp
import com.jitong.im.android.ui.MessageViewModel
import com.jitong.im.android.ui.AvatarViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: AuthViewModel by viewModels {
        val container = (application as JitongApplication).container
        AuthViewModel.Factory(container.authRepository, container.sessionState)
    }
    private val contactViewModel: ContactViewModel by viewModels {
        val container = (application as JitongApplication).container
        ContactViewModel.Factory(container.contactRepository)
    }
    private val messageViewModel: MessageViewModel by viewModels {
        val container = (application as JitongApplication).container
        MessageViewModel.Factory(container.messageRepository)
    }
    private val avatarViewModel: AvatarViewModel by viewModels {
        val container = (application as JitongApplication).container
        AvatarViewModel.Factory(container.avatarRepository)
    }
    private val groupViewModel: GroupViewModel by viewModels {
        val container = (application as JitongApplication).container
        GroupViewModel.Factory(container.groupRepository)
    }
    private val aiViewModel: AiViewModel by viewModels {
        val container = (application as JitongApplication).container
        AiViewModel.Factory(container.aiRepository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                JitongApp(
                    viewModel,
                    contactViewModel,
                    messageViewModel,
                    aiViewModel,
                    avatarViewModel,
                    groupViewModel,
                )
            }
        }
        handleInviteIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleInviteIntent(intent)
    }

    private fun handleInviteIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "https"
            || data.host != BuildConfig.INVITE_HOST
            || data.path != "/groups/invite"
        ) return
        val token = data.getQueryParameter("token") ?: return
        groupViewModel.openInviteToken(token)
    }

}
