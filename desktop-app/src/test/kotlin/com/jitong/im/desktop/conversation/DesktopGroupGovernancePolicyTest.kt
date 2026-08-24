package com.jitong.im.desktop.conversation

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopGroupGovernancePolicyTest {
    @Test
    fun owner_can_manage_privileged_roles_but_admin_cannot() {
        assertTrue(DesktopGroupGovernancePolicy.canChangeRole("OWNER", "ADMIN"))
        assertTrue(DesktopGroupGovernancePolicy.canTransferOwner("OWNER", "ADMIN"))
        assertFalse(DesktopGroupGovernancePolicy.canChangeRole("ADMIN", "MEMBER"))
        assertFalse(DesktopGroupGovernancePolicy.canTransferOwner("ADMIN", "MEMBER"))
    }

    @Test
    fun admins_can_manage_members_and_profile_without_touching_privileged_members() {
        assertTrue(DesktopGroupGovernancePolicy.canEditProfile("ADMIN"))
        assertTrue(DesktopGroupGovernancePolicy.canApproveJoinRequests("ADMIN"))
        assertTrue(DesktopGroupGovernancePolicy.canRemoveMember("ADMIN", "MEMBER"))
        assertFalse(DesktopGroupGovernancePolicy.canRemoveMember("ADMIN", "ADMIN"))
        assertFalse(DesktopGroupGovernancePolicy.canRemoveMember("ADMIN", "OWNER"))
    }
}
