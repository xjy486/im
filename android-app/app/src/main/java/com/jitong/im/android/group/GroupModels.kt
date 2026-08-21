package com.jitong.im.android.group

import java.util.UUID

internal data class CreateGroupRequest(
    val name: String,
    val description: String,
    val visibility: String,
)

internal data class GroupCreateResponse(
    val version: Int,
    val conversationId: UUID,
    val groupNo: String,
    val name: String,
    val description: String,
    val visibility: String,
    val ownerUserId: UUID,
    val role: String,
    val avatarUrl: String?,
    val avatarVersion: Long,
    val memberCount: Int,
)

internal data class GroupSummary(
    val version: Int,
    val conversationId: UUID,
    val groupNo: String,
    val name: String,
    val description: String,
    val visibility: String,
    val role: String,
    val avatarUrl: String?,
    val avatarVersion: Long,
    val memberCount: Int,
)

internal data class GroupSearchResult(
    val name: String,
    val description: String,
    val avatarUrl: String?,
    val memberCount: Int,
)

internal data class GroupSearchPage(
    val version: Int,
    val groups: List<GroupSearchResult>,
)
