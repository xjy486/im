package com.jitong.im.desktop.conversation

internal object DesktopGroupGovernancePolicy {
    fun canEditProfile(actorRole: String): Boolean =
        actorRole == "OWNER" || actorRole == "ADMIN"

    fun canApproveJoinRequests(actorRole: String): Boolean =
        canEditProfile(actorRole)

    fun canChangeRole(actorRole: String, targetRole: String): Boolean =
        actorRole == "OWNER" && targetRole != "OWNER"

    fun canTransferOwner(actorRole: String, targetRole: String): Boolean =
        actorRole == "OWNER" && targetRole != "OWNER"

    fun canRemoveMember(actorRole: String, targetRole: String): Boolean =
        (targetRole == "MEMBER" && (actorRole == "OWNER" || actorRole == "ADMIN")) ||
            (actorRole == "OWNER" && targetRole == "ADMIN")
}
