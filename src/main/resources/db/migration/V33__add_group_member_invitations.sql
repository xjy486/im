CREATE TABLE group_member_invitations (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES groups (conversation_id),
    inviter_user_id UUID NOT NULL REFERENCES users (id),
    invitee_user_id UUID NOT NULL REFERENCES users (id),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'CANCELLED', 'EXPIRED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMPTZ,
    resolved_by_user_id UUID REFERENCES users (id),
    CHECK (inviter_user_id <> invitee_user_id),
    CHECK ((status = 'PENDING' AND resolved_at IS NULL AND resolved_by_user_id IS NULL)
        OR (status IN ('ACCEPTED', 'REJECTED', 'CANCELLED', 'EXPIRED')
            AND resolved_at IS NOT NULL
            AND resolved_by_user_id IS NOT NULL))
);

CREATE UNIQUE INDEX uq_pending_group_member_invitation
    ON group_member_invitations (conversation_id, invitee_user_id)
    WHERE status = 'PENDING';

CREATE INDEX group_member_invitations_invitee_idx
    ON group_member_invitations (invitee_user_id, status, created_at DESC);

CREATE INDEX group_member_invitations_group_idx
    ON group_member_invitations (conversation_id, status, created_at DESC);
