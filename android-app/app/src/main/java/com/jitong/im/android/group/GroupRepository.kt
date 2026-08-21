package com.jitong.im.android.group

import retrofit2.Response
import java.io.IOException

internal class GroupRepository(
    private val api: GroupApi,
    private val avatarUploader: GroupAvatarUploader,
) {
    suspend fun create(
        name: String,
        description: String,
        visibility: String,
        avatar: ByteArray?,
    ): GroupCreateResponse {
        val group = api.create(CreateGroupRequest(name, description, visibility))
            .bodyOrThrow("Group creation")
        if (avatar != null) {
            avatarUploader.replaceGroupAvatar(group.conversationId, avatar)
        }
        return group
    }

    suspend fun list(): List<GroupSummary> =
        api.list().bodyOrThrow("Group list")

    suspend fun search(query: String): List<GroupSearchResult> =
        api.search(query).bodyOrThrow("Group search").groups

    private fun <T> Response<T>.bodyOrThrow(operation: String): T {
        if (isSuccessful && body() != null) return body()!!
        throw IOException("$operation failed with HTTP ${code()}")
    }
}
