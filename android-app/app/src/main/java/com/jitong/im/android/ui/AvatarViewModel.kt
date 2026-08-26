package com.jitong.im.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jitong.im.android.media.AvatarProfileResponse
import com.jitong.im.android.media.AvatarRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class AvatarUiState(
    val loading: Boolean = false,
    val profile: AvatarProfileResponse? = null,
    val bytes: ByteArray? = null,
    val message: String? = null,
)

internal class AvatarViewModel(
    private val repository: AvatarRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(AvatarUiState())
    val state: StateFlow<AvatarUiState> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, message = null)
            runCatching {
                val profile = repository.currentProfile()
                val bytes = repository.loadUserAvatar(profile.userId, profile.avatarVersion)
                _state.value = _state.value.copy(profile = profile, bytes = bytes)
            }.onFailure {
                _state.value = _state.value.copy(message = "头像加载失败")
            }
            _state.value = _state.value.copy(loading = false)
        }
    }

    fun replace(
        source: ByteArray,
        cropX: Int? = null,
        cropY: Int? = null,
        cropWidth: Int? = null,
        cropHeight: Int? = null,
    ) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, message = null)
            runCatching {
                repository.replaceUserAvatar(source, cropX, cropY, cropWidth, cropHeight)
                val profile = repository.currentProfile()
                val bytes = repository.loadUserAvatar(profile.userId, profile.avatarVersion)
                _state.value = _state.value.copy(profile = profile, bytes = bytes)
            }.onFailure {
                _state.value = _state.value.copy(message = "头像更新失败")
            }
            _state.value = _state.value.copy(loading = false)
        }
    }

    fun remove() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, message = null)
            runCatching {
                repository.removeUserAvatar()
                val profile = repository.currentProfile()
                _state.value = _state.value.copy(profile = profile, bytes = null)
            }.onFailure {
                _state.value = _state.value.copy(message = "头像移除失败")
            }
            _state.value = _state.value.copy(loading = false)
        }
    }

    fun updateDisplayName(displayName: String) {
        val normalizedDisplayName = displayName.trim()
        if (normalizedDisplayName.isEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, message = null)
            runCatching {
                val profile = repository.updateUserProfile(normalizedDisplayName)
                _state.value = _state.value.copy(profile = profile)
            }.onFailure {
                _state.value = _state.value.copy(message = "账户名更新失败")
            }
            _state.value = _state.value.copy(loading = false)
        }
    }

    suspend fun loadUserAvatar(userId: java.util.UUID, avatarVersion: Long): ByteArray? =
        repository.loadUserAvatar(userId, avatarVersion)

    suspend fun loadGroupAvatar(conversationId: java.util.UUID, avatarVersion: Long): ByteArray? =
        repository.loadGroupAvatar(conversationId, avatarVersion)

    suspend fun loadGroupAvatarUrl(avatarUrl: String): ByteArray? =
        repository.loadAvatarUrl(avatarUrl)

    class Factory(private val repository: AvatarRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AvatarViewModel(repository) as T
    }
}
