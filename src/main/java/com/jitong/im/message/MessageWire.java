package com.jitong.im.message;

import com.jitong.im.ai.AiDelivery;
import com.jitong.im.platform.error.ApiErrorDefinition;

import java.util.UUID;

final class MessageWire {

    private MessageWire() {
    }

    static WireEnvelope ack(UUID requestId, MessageRecord message) {
        return new WireEnvelope(
                1,
                "message.ack",
                requestId,
                body(message));
    }

    static WireEnvelope created(MessageRecord message) {
        return created(message, null);
    }

    static WireEnvelope created(MessageRecord message, Long syncSeq) {
        return new WireEnvelope(
                1,
                "message.created",
                null,
                body(message, syncSeq));
    }

    static WireEnvelope recalled(MessageRecord message, Long syncSeq) {
        return new WireEnvelope(
                1,
                "message.recalled",
                null,
                body(message, syncSeq));
    }

    static WireEnvelope moderated(MessageRecord message, Long syncSeq) {
        return new WireEnvelope(
                1,
                "message.moderated",
                null,
                body(message, syncSeq));
    }

    private static MessageBody body(MessageRecord message) {
        return body(message, null);
    }

    private static MessageBody body(MessageRecord message, Long syncSeq) {
        return new MessageBody(
                message.messageId(),
                message.conversationId(),
                message.senderId(),
                message.clientMsgId(),
                message.conversationSeq(),
                message.type(),
                message.state(),
                message.text(),
                message.mediaId(),
                message.serverAcceptedAt(),
                message.recalledAt(),
                message.systemEventType(),
                message.systemTargetUserId(),
                message.systemRole(),
                message.moderatedByUserId(),
                message.moderatedReason(),
                message.moderatedAt(),
                message.senderDisplayName(),
                syncSeq);
    }

    static WireEnvelope error(UUID requestId, ApiErrorDefinition definition) {
        return new WireEnvelope(
                1,
                "error",
                requestId,
                new ErrorBody(definition.code(), definition.message()));
    }

    static WireEnvelope syncReady(UUID deviceId, String deviceClass, long highWatermark) {
        return new WireEnvelope(
                1,
                "sync.ready",
                null,
                new SyncReadyBody(deviceId, deviceClass, highWatermark));
    }

    static WireEnvelope conversationRead(ConversationReadState state, Long syncSeq) {
        return new WireEnvelope(
                1,
                "conversation.read",
                null,
                new ConversationReadBody(
                        state.conversationId(),
                        state.userId(),
                        state.readSeq(),
                        syncSeq));
    }

    static WireEnvelope userProfileUpdated(
            UUID userId,
            String displayName,
            String avatarUrl,
            long avatarVersion,
            String avatarFallback,
            long syncSeq
    ) {
        return new WireEnvelope(
                1,
                "user.profile.updated",
                null,
                new UserProfileBody(
                        userId,
                        displayName,
                        avatarUrl,
                        avatarVersion,
                        avatarFallback,
                        syncSeq));
    }

    static WireEnvelope groupProfileUpdated(
            UUID conversationId,
            String avatarUrl,
            long avatarVersion,
            long syncSeq
    ) {
        return new WireEnvelope(
                1,
                "group.profile.updated",
                null,
                new GroupProfileBody(
                        conversationId,
                        avatarUrl,
                        avatarVersion,
                        syncSeq));
    }

    static WireEnvelope membershipRevoked(UUID conversationId, long syncSeq) {
        return new WireEnvelope(
                1,
                "membership.revoked",
                null,
                new MembershipBody(conversationId, syncSeq));
    }

    static WireEnvelope membershipGranted(UUID conversationId, long syncSeq) {
        return new WireEnvelope(
                1,
                "membership.granted",
                null,
                new MembershipBody(conversationId, syncSeq));
    }

    static WireEnvelope groupDissolved(UUID conversationId, long syncSeq) {
        return new WireEnvelope(
                1,
                "group.dissolved",
                null,
                new MembershipBody(conversationId, syncSeq));
    }

    static WireEnvelope contactRelationshipChanged(UUID conversationId, long syncSeq) {
        return new WireEnvelope(
                1,
                "contact.relationship.changed",
                null,
                new RelationshipBody(conversationId, syncSeq));
    }

    static WireEnvelope contactRequestCreated(UUID requestId, long syncSeq) {
        return new WireEnvelope(
                1,
                "contact.request.created",
                null,
                new ContactRequestBody(requestId, syncSeq));
    }

    static WireEnvelope groupInviteCreated(UUID invitationId, UUID conversationId, long syncSeq) {
        return new WireEnvelope(
                1,
                "group.invite.created",
                null,
                new GroupInviteBody(invitationId, conversationId, syncSeq));
    }

    static WireEnvelope conversationAiPolicyChanged(UUID conversationId, long syncSeq) {
        return new WireEnvelope(
                1,
                "conversation.ai.policy.changed",
                null,
                new RelationshipBody(conversationId, syncSeq));
    }

    static WireEnvelope aiJob(AiDelivery delivery, long syncSeq) {
        return new WireEnvelope(
                1,
                "ai.job.updated",
                null,
                new AiJobBody(
                        delivery.jobId(),
                        delivery.conversationId(),
                        delivery.kind(),
                        delivery.status(),
                        delivery.errorCode(),
                        delivery.result(),
                        syncSeq));
    }

    static WireEnvelope aiArtifactDeleted(
            UUID artifactId,
            UUID conversationId,
            long syncSeq
    ) {
        return new WireEnvelope(
                1,
                "ai.artifact.deleted",
                null,
                new AiDeletionBody(artifactId, conversationId, syncSeq));
    }

    static WireEnvelope aiJobDeleted(
            UUID jobId,
            UUID conversationId,
            long syncSeq
    ) {
        return new WireEnvelope(
                1,
                "ai.job.deleted",
                null,
                new AiDeletionBody(jobId, conversationId, syncSeq));
    }

    static WireEnvelope aiActionItemChanged(
            String operation,
            UUID actionItemId,
            UUID conversationId,
            long syncSeq
    ) {
        return new WireEnvelope(
                1,
                operation,
                null,
                new AiDeletionBody(actionItemId, conversationId, syncSeq));
    }

    record WireEnvelope(
            int version,
            String operation,
            UUID requestId,
            Object body
    ) {
    }

    record MessageBody(
            UUID messageId,
            UUID conversationId,
            UUID senderId,
            UUID clientMsgId,
            long conversationSeq,
            String type,
            String state,
            String text,
            UUID mediaId,
            java.time.Instant serverAcceptedAt,
            java.time.Instant recalledAt,
            String systemEventType,
            UUID systemTargetUserId,
            String systemRole,
            UUID moderatedByUserId,
            String moderatedReason,
            java.time.Instant moderatedAt,
            String senderDisplayName,
            Long syncSeq
    ) {
    }

    record ErrorBody(
            String code,
            String message
    ) {
    }

    record SyncReadyBody(
            UUID deviceId,
            String deviceClass,
            long highWatermark
    ) {
    }

    record ConversationReadBody(
            UUID conversationId,
            UUID userId,
            long readSeq,
            Long syncSeq
    ) {
    }

    record UserProfileBody(
            UUID userId,
            String displayName,
            String avatarUrl,
            long avatarVersion,
            String avatarFallback,
            long syncSeq
    ) {
    }

    record GroupProfileBody(
            UUID conversationId,
            String avatarUrl,
            long avatarVersion,
            long syncSeq
    ) {
    }

    record MembershipBody(
            UUID conversationId,
            long syncSeq
    ) {
    }

    record RelationshipBody(
            UUID conversationId,
            long syncSeq
    ) {
    }

    record ContactRequestBody(
            UUID requestId,
            long syncSeq
    ) {
    }

    record GroupInviteBody(
            UUID invitationId,
            UUID conversationId,
            long syncSeq
    ) {
    }

    record AiJobBody(
            UUID jobId,
            UUID conversationId,
            String kind,
            String status,
            String errorCode,
            Object result,
            long syncSeq
    ) {
    }

    record AiDeletionBody(
            UUID entityId,
            UUID conversationId,
            long syncSeq
    ) {
    }
}
