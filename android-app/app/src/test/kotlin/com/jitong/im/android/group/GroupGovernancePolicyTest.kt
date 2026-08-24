package com.jitong.im.android.group

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroupGovernancePolicyTest {
    @Test
    fun owner_can_change_non_owner_roles_and_transfer_ownership() {
        assertTrue(GroupGovernancePolicy.canChangeRole("OWNER", "ADMIN"))
        assertTrue(GroupGovernancePolicy.canChangeRole("OWNER", "MEMBER"))
        assertTrue(GroupGovernancePolicy.canTransferOwner("OWNER", "ADMIN"))
        assertFalse(GroupGovernancePolicy.canChangeRole("ADMIN", "MEMBER"))
        assertFalse(GroupGovernancePolicy.canTransferOwner("ADMIN", "MEMBER"))
    }

    @Test
    fun admins_can_manage_profile_and_members_but_not_privileged_members() {
        assertTrue(GroupGovernancePolicy.canEditProfile("ADMIN"))
        assertTrue(GroupGovernancePolicy.canApproveJoinRequests("ADMIN"))
        assertTrue(GroupGovernancePolicy.canRemoveMember("ADMIN", "MEMBER"))
        assertFalse(GroupGovernancePolicy.canRemoveMember("ADMIN", "ADMIN"))
        assertFalse(GroupGovernancePolicy.canRemoveMember("ADMIN", "OWNER"))
    }
}
