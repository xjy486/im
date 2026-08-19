package com.jitong.im.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jitong.im.android.auth.AuthRepository
import com.jitong.im.android.auth.AuthException
import com.jitong.im.android.auth.DeviceReplacementRequiredException
import com.jitong.im.android.auth.SessionState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal class AuthViewModel(
    private val repository: AuthRepository,
    val sessionState: StateFlow<SessionState>,
) : ViewModel() {
    init {
        if (sessionState.value is SessionState.Restoring) {
            viewModelScope.launch { repository.restore() }
        }
    }

    fun login(accountNo: String, password: String) {
        viewModelScope.launch {
            runCatching { repository.login(accountNo.trim(), password) }
                .onFailure { failure ->
                    if (failure is DeviceReplacementRequiredException) {
                        // The server-provided challenge is held only in process memory until
                        // the user explicitly confirms replacing the old MOBILE device.
                        repository.requireReplacement(failure)
                    } else {
                        repository.showError(failure.userMessage())
                    }
                }
        }
    }

    fun confirmReplacement(challenge: String) {
        viewModelScope.launch {
            runCatching { repository.confirmReplacement(challenge) }
                .onFailure { failure -> repository.showError(failure.userMessage()) }
        }
    }

    fun logout() = repository.logout()

    fun clearData() = repository.clearCurrentAccount()

    private fun Throwable.userMessage(): String = when (this) {
        is AuthException -> message
        else -> "无法连接服务，请稍后重试"
    }

    class Factory(
        private val repository: AuthRepository,
        private val state: StateFlow<SessionState>,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AuthViewModel(repository, state) as T
    }
}
