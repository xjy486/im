package com.jitong.im.group;

import com.jitong.im.ai.AiService;
import com.jitong.im.auth.AuthService;
import com.jitong.im.auth.PublicNumberGenerator;
import com.jitong.im.audit.AuditOutcome;
import com.jitong.im.audit.AuditSubjectType;
import com.jitong.im.audit.SecurityAuditEvent;
import com.jitong.im.audit.SecurityAuditEventType;
import com.jitong.im.audit.SecurityAuditSink;
import com.jitong.im.auth.UuidV7;
import com.jitong.im.message.GroupMessageService;
import com.jitong.im.platform.error.ApiErrorDefinition;
import com.jitong.im.sync.SyncService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class GroupService {

    private static final Duration DEFAULT_INVITE_LIFETIME = Duration.ofDays(7);
    private static final int DEFAULT_INVITE_MAX_USES = 100;

    private final GroupRepository repository;
    private final AuthService authService;
    private final PublicNumberGenerator publicNumberGenerator;
    private final GroupRateLimiter rateLimiter;
    private final GroupInviteProperties inviteProperties;
    private final SecurityAuditSink auditSink;
    private final GroupMessageService groupMessageService;
    private final SyncService syncService;
    private final AiService aiService;
    private final Clock clock;

    @Autowired
    GroupService(
            GroupRepository repository,
            AuthService authService,
            PublicNumberGenerator publicNumberGenerator,
            GroupRateLimiter rateLimiter,
            GroupInviteProperties inviteProperties,
            SecurityAuditSink auditSink,
            GroupMessageService groupMessageService,
            SyncService syncService,
            AiService aiService
    ) {
        this(
                repository,
                authService,
                publicNumberGenerator,
                rateLimiter,
                inviteProperties,
                auditSink,
                groupMessageService,
                syncService,
                aiService,
                Clock.systemUTC());
    }

    GroupService(
            GroupRepository repository,
            AuthService authService,
            PublicNumberGenerator publicNumberGenerator,
            GroupRateLimiter rateLimiter,
            GroupInviteProperties inviteProperties,
            SecurityAuditSink auditSink,
            GroupMessageService groupMessageService,
            SyncService syncService,
            AiService aiService,
            Clock clock
    ) {
        this.repository = repository;
        this.authService = authService;
        this.publicNumberGenerator = publicNumberGenerator;
        this.rateLimiter = rateLimiter;
        this.inviteProperties = inviteProperties;
        this.auditSink = auditSink;
        this.groupMessageService = groupMessageService;
        this.syncService = syncService;
        this.aiService = aiService;
        this.clock = clock;
    }

    @Transactional
    public GroupCreateResponse create(
            String authorization,
            CreateGroupRequest request
    ) {
        UUID ownerUserId = authService.requireUserId(authorization);
        if (request == null || request.name() == null || request.visibility() == null) {
            throw new GroupException(ApiErrorDefinition.INVALID_REQUEST);
        }
        GroupVisibility visibility = GroupVisibility.parse(request.visibility());
        if (visibility == null) {
            throw new GroupException(ApiErrorDefinition.INVALID_REQUEST);
        }
        String name = request.name().trim();
        if (name.isEmpty()) {
            throw new GroupException(ApiErrorDefinition.INVALID_REQUEST);
        }
        if (name.codePointCount(0, name.length()) > 128) {
            throw new GroupException(ApiErrorDefinition.INVALID_REQUEST);
        }
        String description = request.description() == null
                ? ""
                : request.description().trim();
        if (description.codePointCount(0, description.length()) > 1000) {
            throw new GroupException(ApiErrorDefinition.INVALID_REQUEST);
        }
        repository.lockOwner(ownerUserId);
        GroupRepository.GroupRecord group = repository.createGroup(
                ownerUserId,
                name,
                description,
                visibility,
                publicNumberGenerator);
        groupMessageService.recordGroupCreated(group.conversationId(), ownerUserId);
        return toCreateResponse(group);
    }

    @Transactional(readOnly = true)
    public List<GroupSummary> list(String authorization) {
        UUID userId = authService.requireUserId(authorization);
        return repository.listGroupsForUser(userId).stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public GroupAiPolicyResponse aiPolicy(String authorization, UUID conversationId) {
        UUID userId = authService.requireUserId(authorization);
        GroupRepository.GroupRecord group = repository.findGroupForUser(conversationId, userId);
        if (group == null) {
            throw new GroupException(ApiErrorDefinition.NOT_MEMBER);
        }
        return new GroupAiPolicyResponse(
                1,
                conversationId,
                group.aiEnabled(),
                group.aiPolicyVersion());
    }

    @Transactional
    public GroupAiPolicyResponse updateAiPolicy(
            String authorization,
            UUID conversationId,
            GroupAiPolicyUpdate request
    ) {
        UUID ownerUserId = authService.requireUserId(authorization);
        if (request == null || request.enabled() == null) {
            throw new GroupException(ApiErrorDefinition.INVALID_REQUEST);
        }
        GroupRepository.GroupActor actor = repository.lockActor(conversationId, ownerUserId);
        if (actor == null || !"OWNER".equals(actor.role())) {
            throw new GroupException(ApiErrorDefinition.FORBIDDEN_ROLE);
        }
        AiService.GroupPolicyUpdateResult result = aiService.updateGroupPolicy(
                ownerUserId,
                conversationId,
                request.enabled());
        if (result.changed()) {
            groupMessageService.recordAiPolicyChanged(conversationId, ownerUserId);
            recordAudit(
                    SecurityAuditEventType.GROUP_AI_POLICY_CHANGE,
                    ownerUserId,
                    conversationId,
                    AuditOutcome.SUCCEEDED);
        }
        return new GroupAiPolicyResponse(
                1,
                conversationId,
                request.enabled(),
                result.policyVersion());
    }

    @Transactional
    public GroupMemberInvitationResponse inviteMember(
            String authorization,
            UUID conversationId,
            GroupMemberInvitationRequest request
    ) {
        UUID inviterUserId = authService.requireUserId(authorization);
        if (request == null || request.accountNo() == null
                || !request.accountNo().trim().matches("[1-9][0-9]{10}")) {
            throw new GroupException(ApiErrorDefinition.INVALID_REQUEST);
        }
        GroupRepository.GroupActor actor = repository.lockActor(conversationId, inviterUserId);
        if (actor == null) {
            throw new GroupException(ApiErrorDefinition.FORBIDDEN);
        }
        UUID inviteeUserId = repository.findActiveUserByAccountNoForUpdate(
                request.accountNo().trim());
        if (inviteeUserId == null) {
            throw new GroupException(ApiErrorDefinition.USER_NOT_FOUND);
        }
        if (inviterUserId.equals(inviteeUserId)
                || repository.isBanned(conversationId, inviteeUserId)) {
            throw new GroupException(ApiErrorDefinition.FORBIDDEN);
        }
        if (repository.isActiveMember(conversationId, inviteeUserId)) {
            throw new GroupException(ApiErrorDefinition.CONFLICT);
        }
        GroupRepository.GroupMemberInvitationRecord existing =
                repository.findPendingMemberInvitation(conversationId, inviteeUserId);
        if (existing != null) {
            return toMemberInvitationResponse(existing);
        }
        GroupRepository.GroupMemberInvitationRecord created;
        try {
            created = repository.insertMemberInvitation(
                    UuidV7.random(),
                    conversationId,
                    inviterUserId,
                    inviteeUserId,
                    clock.instant());
        } catch (DataIntegrityViolationException exception) {
            created = repository.findPendingMemberInvitation(conversationId, inviteeUserId);
        }
        if (created == null) {
            throw new GroupException(ApiErrorDefinition.CONFLICT);
        }
        syncService.recordEventForUsers(
                List.of(inviteeUserId),
                "GROUP_INVITE",
                created.invitationId(),
                conversationId);
        return toMemberInvitationResponse(created);
    }

    @Transactional(readOnly = true)
    public List<GroupMemberInvitationSummary> listMemberInvitations(String authorization) {
        UUID inviteeUserId = authService.requireUserId(authorization);
        return repository.listMemberInvitations(inviteeUserId).stream()
                .map(invitation -> new GroupMemberInvitationSummary(
                        1,
                        invitation.invitationId(),
                        invitation.conversationId(),
                        invitation.groupNo(),
                        invitation.groupName(),
                        invitation.inviterUserId(),
                        invitation.inviterAccountNo(),
                        invitation.inviterDisplayName(),
                        invitation.status(),
                        invitation.createdAt(),
                        invitation.resolvedAt()))
                .toList();
    }

    @Transactional
    public GroupMemberInvitationResponse acceptMemberInvitation(
            String authorization,
            UUID conversationId,
            UUID invitationId
    ) {
        return acceptPendingMemberInvitation(authorization, conversationId, invitationId);
    }

    @Transactional
    public GroupMemberInvitationResponse rejectMemberInvitation(
            String authorization,
            UUID conversationId,
            UUID invitationId
    ) {
        UUID inviteeUserId = authService.requireUserId(authorization);
        GroupRepository.GroupMemberInvitationRecord invitation =
                repository.lockMemberInvitation(invitationId);
        if (invitation == null
                || !conversationId.equals(invitation.conversationId())
                || !inviteeUserId.equals(invitation.inviteeUserId())) {
            throw new GroupException(ApiErrorDefinition.FORBIDDEN);
        }
        if ("PENDING".equals(invitation.status())) {
            repository.updateMemberInvitationStatus(
                    invitationId,
                    "REJECTED",
                    inviteeUserId,
                    clock.instant());
            invitation = repository.findMemberInvitation(invitationId);
        }
        return toMemberInvitationResponse(invitation);
    }

    private GroupMemberInvitationResponse acceptPendingMemberInvitation(
            String authorization,
            UUID conversationId,
            UUID invitationId
    ) {
        UUID inviteeUserId = authService.requireUserId(authorization);
        GroupRepository.GroupMemberInvitationRecord invitation =
                repository.lockMemberInvitation(invitationId);
        if (invitation == null
                || !conversationId.equals(invitation.conversationId())
                || !inviteeUserId.equals(invitation.inviteeUserId())) {
            throw new GroupException(ApiErrorDefinition.FORBIDDEN);
        }
        if (!"PENDING".equals(invitation.status())) {
            return toMemberInvitationResponse(invitation);
        }
        GroupRepository.GroupRecord group = repository.lockGroup(conversationId);
        if (group == null) {
            throw new GroupException(ApiErrorDefinition.RESOURCE_NOT_FOUND);
        }
        if (repository.isBanned(conversationId, inviteeUserId)) {
            repository.updateMemberInvitationStatus(
                    invitationId,
                    "REJECTED",
                    inviteeUserId,
                    clock.instant());
            return toMemberInvitationResponse(repository.findMemberInvitation(invitationId));
        }
        if (!repository.isActiveMember(conversationId, inviteeUserId)) {
            try {
                repository.addMember(conversationId, inviteeUserId);
            } catch (DataIntegrityViolationException exception) {
                throw new GroupException(ApiErrorDefinition.CONFLICT);
            }
            groupMessageService.recordMemberJoinedAfterMembershipChange(
                    conversationId,
                    inviteeUserId,
                    inviteeUserId);
        }
        repository.updateMemberInvitationStatus(
                invitationId,
                "ACCEPTED",
                inviteeUserId,
                clock.instant());
        recordAudit(
                SecurityAuditEventType.GROUP_MEMBERSHIP_CHANGE,
                inviteeUserId,
                conversationId,
                AuditOutcome.SUCCEEDED);
        return toMemberInvitationResponse(repository.findMemberInvitation(invitationId));
    }

    @Transactional
    public void removeMember(
            String authorization,
            UUID conversationId,
            UUID userId
    ) {
        UUID actorId = authService.requireUserId(authorization);
        GroupRepository.GroupActor actor = repository.lockActor(conversationId, actorId);
        if (actor == null) {
            throw new GroupException(ApiErrorDefinition.FORBIDDEN);
        }
        String targetRole = repository.memberRole(conversationId, userId);
        if (!"MEMBER".equals(targetRole)
                && !("OWNER".equals(actor.role()) && "ADMIN".equals(targetRole))) {
            throw new GroupException(ApiErrorDefinition.FORBIDDEN);
        }
        repository.removeMember(conversationId, userId, clock.instant());
        groupMessageService.recordMemberRemoved(conversationId, actorId, userId);
        aiService.invalidateGroupMemberJobs(conversationId, userId);
        recordAudit(
                SecurityAuditEventType.GROUP_MEMBERSHIP_CHANGE,
                actorId,
                conversationId,
                AuditOutcome.SUCCEEDED);
    }

    @Transactional
    public GroupRoleChangeResponse changeRole(
            String authorization,
            UUID conversationId,
            UUID userId,
            GroupRoleChangeRequest request
    ) {
        UUID actorId = authService.requireUserId(authorization);
        GroupRepository.GroupActor actor = repository.lockActor(conversationId, actorId);
        if (actor == null) {
            throw new GroupException(ApiErrorDefinition.FORBIDDEN);
        }
        if (!"OWNER".equals(actor.role())) {
            throw new GroupException(ApiErrorDefinition.FORBIDDEN_ROLE);
        }
        if (request == null || request.role() == null
                || (!"ADMIN".equals(request.role()) && !"MEMBER".equals(request.role()))) {
            throw new GroupException(ApiErrorDefinition.INVALID_REQUEST);
        }
        String currentRole = repository.memberRole(conversationId, userId);
        if (currentRole == null) {
            throw new GroupException(ApiErrorDefinition.NOT_MEMBER);
        }
        if ("OWNER".equals(currentRole)) {
            throw new GroupException(ApiErrorDefinition.FORBIDDEN_ROLE);
        }
        if (currentRole.equals(request.role())) {
            return new GroupRoleChangeResponse(1, conversationId, userId, currentRole);
        }
        if (repository.updateMemberRole(conversationId, userId, request.role()) != 1) {
            throw new GroupException(ApiErrorDefinition.CONFLICT);
        }
        groupMessageService.recordRoleChanged(
                conversationId,
                actorId,
                userId,
                request.role());
        recordAudit(
                SecurityAuditEventType.GROUP_ROLE_CHANGE,
                actorId,
                conversationId,
                AuditOutcome.SUCCEEDED);
        return new GroupRoleChangeResponse(1, conversationId, userId, request.role());
    }

    @Transactional
    public GroupOwnerTransferResponse transferOwner(
            String authorization,
            UUID conversationId,
            GroupOwnerTransferRequest request
    ) {
        UUID actorId = authService.requireUserId(authorization);
        GroupRepository.GroupActor actor = repository.lockActor(conversationId, actorId);
        if (actor == null) {
            throw new GroupException(ApiErrorDefinition.FORBIDDEN);
        }
        if (!"OWNER".equals(actor.role())) {
            throw new GroupException(ApiErrorDefinition.FORBIDDEN_ROLE);
        }
        if (request == null || request.userId() == null || actorId.equals(request.userId())) {
            throw new GroupException(ApiErrorDefinition.INVALID_REQUEST);
        }
        String targetRole = repository.memberRole(conversationId, request.userId());
        if (targetRole == null) {
            throw new GroupException(ApiErrorDefinition.NOT_MEMBER);
        }
        if (repository.transferOwnership(conversationId, actorId, request.userId()) != 1) {
            throw new GroupException(ApiErrorDefinition.CONFLICT);
        }
        aiService.resetGroupPolicyForOwnershipTransfer(actorId, conversationId);
        groupMessageService.recordOwnerTransferred(
                conversationId,
                actorId,
                request.userId());
        groupMessageService.recordAiPolicyChanged(conversationId, actorId);
        recordAudit(
                SecurityAuditEventType.GROUP_ROLE_CHANGE,
                actorId,
                conversationId,
                AuditOutcome.SUCCEEDED);
        recordAudit(
                SecurityAuditEventType.GROUP_AI_POLICY_CHANGE,
                actorId,
                conversationId,
                AuditOutcome.SUCCEEDED);
        return new GroupOwnerTransferResponse(
                1,
                conversationId,
                actorId,
                request.userId());
    }

    @Transactional
    public GroupSummary updateProfile(
            String authorization,
            UUID conversationId,
            GroupProfileUpdateRequest request
    ) {
        UUID actorId = authService.requireUserId(authorization);
        GroupRepository.GroupActor actor = repository.lockActor(conversationId, actorId);
        if (actor == null) {
            throw new GroupException(ApiErrorDefinition.FORBIDDEN);
        }
        if (request == null || request.name() == null || request.visibility() == null) {
            throw new GroupException(ApiErrorDefinition.INVALID_REQUEST);
        }
        String name = request.name().trim();
        String description = request.description() == null ? "" : request.description().trim();
        GroupVisibility visibility = GroupVisibility.parse(request.visibility());
        if (name.isEmpty()
                || name.codePointCount(0, name.length()) > 128
                || description.codePointCount(0, description.length()) > 1000
                || visibility == null) {
            throw new GroupException(ApiErrorDefinition.INVALID_REQUEST);
        }
        repository.updateGroupProfile(conversationId, name, description, visibility);
        groupMessageService.recordGroupProfileUpdated(conversationId, actorId);
        recordAudit(
                SecurityAuditEventType.GROUP_PROFILE_CHANGE,
                actorId,
                conversationId,
                AuditOutcome.SUCCEEDED);
        GroupRepository.GroupRecord updated = repository.findGroupForUser(conversationId, actorId);
        if (updated == null) {
            throw new GroupException(ApiErrorDefinition.RESOURCE_NOT_FOUND);
        }
        return toSummary(updated);
    }

    @Transactional
    public GroupInviteResponse createInvite(
            String authorization,
            UUID conversationId,
            GroupInviteCreateRequest request
    ) {
        UUID actorId = authService.requireUserId(authorization);
        GroupRepository.GroupActor actor = repository.lockActor(conversationId, actorId);
        if (actor == null) {
            throw new GroupException(ApiErrorDefinition.FORBIDDEN);
        }
        int maxUses = request == null || request.maxUses() == null
                ? DEFAULT_INVITE_MAX_USES
                : request.maxUses();
        long expiresInSeconds = request == null || request.expiresInSeconds() == null
                ? DEFAULT_INVITE_LIFETIME.toSeconds()
                : request.expiresInSeconds();
        if (maxUses < 1 || maxUses > 10000 || expiresInSeconds < 60
                || expiresInSeconds > Duration.ofDays(30).toSeconds()) {
            throw new GroupException(ApiErrorDefinition.INVALID_REQUEST);
        }

        String token = com.jitong.im.auth.TokenDigests.newOpaqueToken();
        Instant expiresAt = clock.instant().plusSeconds(expiresInSeconds);
        GroupRepository.GroupInviteRecord invite = repository.insertInvite(
                com.jitong.im.auth.UuidV7.random(),
                conversationId,
                actorId,
                com.jitong.im.auth.TokenDigests.sha256(token),
                expiresAt,
                maxUses);
        return toInviteResponse(invite, token);
    }

    @Transactional(readOnly = true)
    public GroupInviteResolveResponse resolveInvite(
            String authorization,
            String rawToken,
            String ipAddress
    ) {
        UUID userId = authService.requireUserId(authorization);
        rateLimiter.check(userId.toString(), ipAddress);
        rateLimiter.record(userId.toString(), ipAddress);
        GroupRepository.GroupInviteRecord invite = loadUsableInviteForRead(rawToken, clock.instant());
        if (repository.isBanned(invite.conversationId(), userId)) {
            throw new GroupException(ApiErrorDefinition.RESOURCE_NOT_FOUND);
        }
        GroupRepository.GroupRecord groupDetails = repository.findGroup(invite.conversationId());
        if (groupDetails == null) {
            throw new GroupException(ApiErrorDefinition.RESOURCE_NOT_FOUND);
        }
        return new GroupInviteResolveResponse(
                1,
                invite.conversationId(),
                findGroupNoByConversation(invite.conversationId()),
                groupDetails.name(),
                groupDetails.description(),
                groupDetails.visibility(),
                avatarUrl(
                        groupDetails.conversationId(),
                        groupDetails.avatarMediaId(),
                        groupDetails.avatarVersion()),
                groupDetails.avatarVersion(),
                groupDetails.memberCount(),
                invite.expiresAt());
    }

    @Transactional
    public GroupJoinRequestResponse createJoinRequest(
            String authorization,
            UUID conversationId,
            GroupJoinRequestCreateRequest request,
            String ipAddress
    ) {
        UUID userId = authService.requireUserId(authorization);
        rateLimiter.check(userId.toString(), ipAddress);
        rateLimiter.record(userId.toString(), ipAddress);
        GroupRepository.GroupRecord group = repository.lockGroup(conversationId);
        if (group == null) {
            throw new GroupException(ApiErrorDefinition.RESOURCE_NOT_FOUND);
        }
        boolean activeMember = repository.isActiveMember(conversationId, userId);
        if (activeMember) {
            throw new GroupException(ApiErrorDefinition.CONFLICT);
        }
        if (repository.isBanned(conversationId, userId)) {
            throw new GroupException(ApiErrorDefinition.FORBIDDEN);
        }
        GroupRepository.JoinRequestRecord existing =
                repository.findPendingJoinRequest(conversationId, userId);
        if (existing != null) {
            return toJoinRequestResponse(existing);
        }
        String rawToken = request == null ? null : request.inviteToken();
        GroupRepository.GroupInviteRecord invite = null;
        if (rawToken != null && !rawToken.isBlank()) {
            invite = loadUsableInvite(rawToken, clock.instant());
            if (!invite.conversationId().equals(conversationId)) {
                throw new GroupException(ApiErrorDefinition.RESOURCE_NOT_FOUND);
            }
        } else {
            if (!"PUBLIC".equals(group.visibility())) {
                throw new GroupException(ApiErrorDefinition.RESOURCE_NOT_FOUND);
            }
        }
        GroupRepository.JoinRequestRecord created = repository.insertJoinRequest(
                com.jitong.im.auth.UuidV7.random(),
                conversationId,
                userId,
                invite == null ? null : invite.inviteId(),
                clock.instant());
        if (created == null) {
            return toJoinRequestResponse(
                    repository.findPendingJoinRequest(conversationId, userId));
        }
        if (invite != null) {
            repository.incrementInviteUse(invite.inviteId());
        }
        return toJoinRequestResponse(created);
    }

    @Transactional
    public GroupJoinRequestResponse createJoinRequestByGroupNo(
            String authorization,
            GroupJoinRequestByGroupNoRequest request,
            String ipAddress
    ) {
        UUID userId = authService.requireUserId(authorization);
        rateLimiter.check(userId.toString(), ipAddress);
        rateLimiter.record(userId.toString(), ipAddress);
        if (request == null || request.groupNo() == null
                || !request.groupNo().trim().matches("[1-9][0-9]{10}")) {
            throw new GroupException(ApiErrorDefinition.RESOURCE_NOT_FOUND);
        }
        GroupRepository.GroupRecord group =
                repository.findActiveGroupByGroupNo(request.groupNo().trim());
        if (group == null) {
            throw new GroupException(ApiErrorDefinition.RESOURCE_NOT_FOUND);
        }
        return createJoinRequestForGroup(
                userId,
                group,
                request.inviteToken());
    }

    @Transactional(readOnly = true)
    public List<GroupMyJoinRequestSummary> listMyJoinRequests(String authorization) {
        UUID userId = authService.requireUserId(authorization);
        return repository.listJoinRequestsForUser(userId).stream()
                .map(request -> new GroupMyJoinRequestSummary(
                        1,
                        request.requestId(),
                        request.conversationId(),
                        request.groupNo(),
                        request.groupName(),
                        request.status(),
                        request.createdAt(),
                        request.resolvedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<GroupMemberSummary> listMembers(
            String authorization,
            UUID conversationId
    ) {
        UUID userId = authService.requireUserId(authorization);
        if (repository.findGroupForUser(conversationId, userId) == null) {
            throw new GroupException(ApiErrorDefinition.FORBIDDEN);
        }
        return repository.listMembers(conversationId, userId).stream()
                .map(member -> new GroupMemberSummary(
                        1,
                        member.userId(),
                        member.accountNo(),
                        member.displayName(),
                        member.role(),
                        userAvatarUrl(member.userId(), member.avatarMediaId(), member.avatarVersion()),
                        member.avatarVersion(),
                        fallback(member.displayName())))
                .toList();
    }

    @Transactional
    public List<GroupJoinRequestSummary> listJoinRequests(
            String authorization,
            UUID conversationId
    ) {
        UUID actorId = authService.requireUserId(authorization);
        GroupRepository.GroupActor actor = repository.lockActor(conversationId, actorId);
        if (actor == null) {
            throw new GroupException(ApiErrorDefinition.FORBIDDEN);
        }
        return repository.listJoinRequests(conversationId).stream()
                .map(this::toJoinRequestSummary)
                .toList();
    }

    @Transactional
    public GroupJoinRequestResponse approveJoinRequest(
            String authorization,
            UUID conversationId,
            UUID requestId
    ) {
        UUID actorId = authService.requireUserId(authorization);
        GroupRepository.GroupActor actor = repository.lockActor(conversationId, actorId);
        if (actor == null) {
            throw new GroupException(ApiErrorDefinition.FORBIDDEN);
        }
        GroupRepository.JoinRequestRecord request = repository.lockJoinRequest(requestId);
        if (request == null || !conversationId.equals(request.conversationId())) {
            throw new GroupException(ApiErrorDefinition.RESOURCE_NOT_FOUND);
        }
        if (!"PENDING".equals(request.status())) {
            return toJoinRequestResponse(request);
        }
        if (repository.isBanned(conversationId, request.userId())) {
            repository.updateJoinRequestStatus(
                    requestId,
                    "REJECTED",
                    actorId,
                    clock.instant());
            recordAudit(
                    SecurityAuditEventType.GROUP_MEMBERSHIP_CHANGE,
                    actorId,
                    conversationId,
                    AuditOutcome.SUCCEEDED);
            return toJoinRequestResponse(repository.findJoinRequest(requestId));
        }
        if (repository.isActiveMember(conversationId, request.userId())) {
            repository.updateJoinRequestStatus(
                    requestId,
                    "APPROVED",
                    actorId,
                    clock.instant());
            recordAudit(
                    SecurityAuditEventType.GROUP_MEMBERSHIP_CHANGE,
                    actorId,
                    conversationId,
                    AuditOutcome.SUCCEEDED);
            return toJoinRequestResponse(repository.findJoinRequest(requestId));
        }
        try {
            repository.addMember(conversationId, request.userId());
        } catch (DataIntegrityViolationException exception) {
            throw new GroupException(ApiErrorDefinition.CONFLICT);
        }
        repository.updateJoinRequestStatus(
                requestId,
                "APPROVED",
                actorId,
                clock.instant());
        groupMessageService.recordMemberJoinedAfterMembershipChange(
                conversationId,
                actorId,
                request.userId());
        recordAudit(
                SecurityAuditEventType.GROUP_MEMBERSHIP_CHANGE,
                actorId,
                conversationId,
                AuditOutcome.SUCCEEDED);
        return toJoinRequestResponse(repository.findJoinRequest(requestId));
    }

    @Transactional
    public GroupJoinRequestResponse rejectJoinRequest(
            String authorization,
            UUID conversationId,
            UUID requestId
    ) {
        UUID actorId = authService.requireUserId(authorization);
        GroupRepository.GroupActor actor = repository.lockActor(conversationId, actorId);
        if (actor == null) {
            throw new GroupException(ApiErrorDefinition.FORBIDDEN);
        }
        GroupRepository.JoinRequestRecord request = repository.lockJoinRequest(requestId);
        if (request == null || !conversationId.equals(request.conversationId())) {
            throw new GroupException(ApiErrorDefinition.RESOURCE_NOT_FOUND);
        }
        if (!"PENDING".equals(request.status())) {
            return toJoinRequestResponse(request);
        }
        repository.updateJoinRequestStatus(requestId, "REJECTED", actorId, clock.instant());
        recordAudit(
                SecurityAuditEventType.GROUP_MEMBERSHIP_CHANGE,
                actorId,
                conversationId,
                AuditOutcome.SUCCEEDED);
        return toJoinRequestResponse(repository.findJoinRequest(requestId));
    }

    @Transactional
    public GroupJoinRequestResponse cancelJoinRequest(
            String authorization,
            UUID conversationId,
            UUID requestId
    ) {
        UUID userId = authService.requireUserId(authorization);
        GroupRepository.JoinRequestRecord request = repository.lockJoinRequest(requestId);
        if (request == null
                || !conversationId.equals(request.conversationId())
                || !userId.equals(request.userId())) {
            throw new GroupException(ApiErrorDefinition.FORBIDDEN);
        }
        if (!"PENDING".equals(request.status())) {
            return toJoinRequestResponse(request);
        }
        repository.updateJoinRequestStatus(requestId, "CANCELLED", userId, clock.instant());
        recordAudit(
                SecurityAuditEventType.GROUP_MEMBERSHIP_CHANGE,
                userId,
                conversationId,
                AuditOutcome.SUCCEEDED);
        return toJoinRequestResponse(repository.findJoinRequest(requestId));
    }

    @Transactional
    public void revokeInvite(
            String authorization,
            UUID conversationId,
            UUID inviteId
    ) {
        UUID actorId = authService.requireUserId(authorization);
        GroupRepository.GroupActor actor = repository.lockActor(conversationId, actorId);
        if (actor == null) {
            throw new GroupException(ApiErrorDefinition.FORBIDDEN);
        }
        GroupRepository.GroupInviteRecord invite = repository.lockInvite(inviteId);
        if (invite == null || !conversationId.equals(invite.conversationId())) {
            throw new GroupException(ApiErrorDefinition.RESOURCE_NOT_FOUND);
        }
        repository.revokeInvite(conversationId, inviteId, clock.instant());
    }

    @Transactional
    public void banUser(
            String authorization,
            UUID conversationId,
            UUID userId,
            GroupBanRequest request
    ) {
        UUID actorId = authService.requireUserId(authorization);
        GroupRepository.GroupActor actor = repository.lockActor(conversationId, actorId);
        if (actor == null || actorId.equals(userId)) {
            throw new GroupException(ApiErrorDefinition.FORBIDDEN);
        }
        String targetRole = repository.memberRole(conversationId, userId);
        if (targetRole != null
                && !"MEMBER".equals(targetRole)
                && !("OWNER".equals(actor.role()) && "ADMIN".equals(targetRole))) {
            throw new GroupException(ApiErrorDefinition.FORBIDDEN);
        }
        if (!repository.isActiveMember(conversationId, userId)
                && !hasActiveUser(userId)) {
            throw new GroupException(ApiErrorDefinition.USER_NOT_FOUND);
        }
        repository.ban(
                conversationId,
                userId,
                actorId,
                request == null || request.reason() == null
                        ? ""
                        : request.reason().trim(),
                clock.instant());
        GroupRepository.JoinRequestRecord pending =
                repository.findPendingJoinRequest(conversationId, userId);
        if (pending != null) {
            repository.updateJoinRequestStatus(
                    pending.requestId(),
                    "REJECTED",
                    actorId,
                    clock.instant());
        }
        recordAudit(
                SecurityAuditEventType.GROUP_BLACKLIST_CHANGE,
                actorId,
                conversationId,
                AuditOutcome.SUCCEEDED);
        if (repository.isActiveMember(conversationId, userId)) {
            repository.removeMember(conversationId, userId, clock.instant());
            groupMessageService.recordMemberRemoved(conversationId, actorId, userId);
            aiService.invalidateGroupMemberJobs(conversationId, userId);
        }
    }

    @Transactional
    public void leave(String authorization, UUID conversationId) {
        UUID userId = authService.requireUserId(authorization);
        String role = repository.memberRole(conversationId, userId);
        if (role == null) {
            throw new GroupException(ApiErrorDefinition.NOT_MEMBER);
        }
        if ("OWNER".equals(role)) {
            throw new GroupException(ApiErrorDefinition.FORBIDDEN);
        }
        groupMessageService.recordMemberLeft(conversationId, userId);
        repository.removeMember(conversationId, userId, clock.instant());
        aiService.invalidateGroupMemberJobs(conversationId, userId);
        recordAudit(
                SecurityAuditEventType.GROUP_MEMBERSHIP_CHANGE,
                userId,
                conversationId,
                AuditOutcome.SUCCEEDED);
    }

    @Transactional
    public void dissolve(String authorization, UUID conversationId) {
        UUID ownerUserId = authService.requireUserId(authorization);
        GroupRepository.GroupActor actor = repository.lockActor(conversationId, ownerUserId);
        if (actor == null || !"OWNER".equals(actor.role())) {
            throw new GroupException(ApiErrorDefinition.FORBIDDEN_ROLE);
        }
        List<UUID> memberIds = repository.activeMemberIds(conversationId);
        Instant now = clock.instant();
        GroupRepository.DissolutionRecord group = repository.dissolve(
                conversationId,
                ownerUserId,
                now,
                now.plus(Duration.ofDays(30)));
        if (group == null) {
            throw new GroupException(ApiErrorDefinition.RESOURCE_NOT_FOUND);
        }
        groupMessageService.recordGroupDissolved(conversationId, ownerUserId, memberIds);
        recordAudit(
                SecurityAuditEventType.GROUP_DISSOLUTION,
                ownerUserId,
                conversationId,
                AuditOutcome.SUCCEEDED);
    }

    @Transactional
    public void unbanUser(String authorization, UUID conversationId, UUID userId) {
        UUID actorId = authService.requireUserId(authorization);
        GroupRepository.GroupActor actor = repository.lockActor(conversationId, actorId);
        if (actor == null) {
            throw new GroupException(ApiErrorDefinition.FORBIDDEN);
        }
        repository.removeBan(conversationId, userId);
        recordAudit(
                SecurityAuditEventType.GROUP_BLACKLIST_CHANGE,
                actorId,
                conversationId,
                AuditOutcome.SUCCEEDED);
    }

    @Transactional(readOnly = true)
    public GroupSearchPage search(
            String authorization,
            String query,
            String ipAddress
    ) {
        UUID userId = authService.requireUserId(authorization);
        rateLimiter.check(userId.toString(), ipAddress);
        rateLimiter.record(userId.toString(), ipAddress);

        String normalizedQuery = GroupText.normalize(query);
        if (normalizedQuery.isBlank()) {
            throw new GroupException(ApiErrorDefinition.RESOURCE_NOT_FOUND);
        }
        if (normalizedQuery.codePointCount(0, normalizedQuery.length()) > 128) {
            throw new GroupException(ApiErrorDefinition.RESOURCE_NOT_FOUND);
        }

        List<GroupRepository.SearchGroupRecord> matches;
        if (normalizedQuery.matches("[1-9][0-9]{10}")) {
            GroupRepository.SearchGroupRecord exact =
                    repository.findSearchableByGroupNo(normalizedQuery);
            matches = exact == null ? List.of() : List.of(exact);
        } else {
            matches = repository.searchPublicGroups(normalizedQuery);
        }
        matches = matches.stream()
                .filter(match -> !repository.isBanned(match.conversationId(), userId))
                .toList();
        if (matches.isEmpty()) {
            throw new GroupException(ApiErrorDefinition.RESOURCE_NOT_FOUND);
        }
        return new GroupSearchPage(
                1,
                matches.stream().map(this::toSearchResult).toList());
    }

    private GroupCreateResponse toCreateResponse(GroupRepository.GroupRecord group) {
        return new GroupCreateResponse(
                1,
                group.conversationId(),
                group.groupNo(),
                group.name(),
                group.description(),
                group.visibility(),
                group.ownerUserId(),
                group.role(),
                avatarUrl(group.conversationId(), group.avatarMediaId(), group.avatarVersion()),
                group.avatarVersion(),
                group.memberCount(),
                group.aiEnabled(),
                group.aiPolicyVersion());
    }

    private GroupSummary toSummary(GroupRepository.GroupRecord group) {
        return new GroupSummary(
                1,
                group.conversationId(),
                group.groupNo(),
                group.name(),
                group.description(),
                group.visibility(),
                group.role(),
                avatarUrl(group.conversationId(), group.avatarMediaId(), group.avatarVersion()),
                group.avatarVersion(),
                group.memberCount(),
                group.aiEnabled(),
                group.aiPolicyVersion());
    }

    private GroupSearchResult toSearchResult(GroupRepository.SearchGroupRecord group) {
        return new GroupSearchResult(
                group.name(),
                group.groupNo(),
                avatarUrl(group.conversationId(), group.avatarMediaId(), group.avatarVersion()),
                group.description(),
                group.memberCount());
    }

    private GroupMemberInvitationResponse toMemberInvitationResponse(
            GroupRepository.GroupMemberInvitationRecord invitation
    ) {
        return new GroupMemberInvitationResponse(
                1,
                invitation.invitationId(),
                invitation.conversationId(),
                invitation.inviterUserId(),
                invitation.inviteeUserId(),
                invitation.status(),
                invitation.createdAt(),
                invitation.resolvedAt());
    }

    private String avatarUrl(UUID conversationId, UUID avatarMediaId, long avatarVersion) {
        return avatarMediaId == null || avatarVersion == 0
                ? null
                : "/api/v1/groups/" + conversationId
                + "/avatar?variant=thumb&avatarVersion=" + avatarVersion;
    }

    private GroupInviteResponse toInviteResponse(
            GroupRepository.GroupInviteRecord invite,
            String rawToken
    ) {
        String deepLink = inviteProperties.inviteDeepLinkBase()
                + "?token=" + java.net.URLEncoder.encode(
                        rawToken,
                        java.nio.charset.StandardCharsets.UTF_8);
        return new GroupInviteResponse(
                1,
                invite.inviteId(),
                invite.conversationId(),
                invite.maxUses(),
                invite.useCount(),
                invite.expiresAt(),
                deepLink,
                deepLink);
    }

    private GroupJoinRequestResponse toJoinRequestResponse(
            GroupRepository.JoinRequestRecord request
    ) {
        return new GroupJoinRequestResponse(
                1,
                request.requestId(),
                request.conversationId(),
                request.userId(),
                request.status(),
                request.inviteId(),
                request.createdAt(),
                request.resolvedAt());
    }

    private GroupJoinRequestResponse createJoinRequestForGroup(
            UUID userId,
            GroupRepository.GroupRecord group,
            String rawToken
    ) {
        if (repository.isActiveMember(group.conversationId(), userId)) {
            throw new GroupException(ApiErrorDefinition.CONFLICT);
        }
        if (repository.isBanned(group.conversationId(), userId)) {
            throw new GroupException(ApiErrorDefinition.FORBIDDEN);
        }
        GroupRepository.JoinRequestRecord existing =
                repository.findPendingJoinRequest(group.conversationId(), userId);
        if (existing != null) {
            return toJoinRequestResponse(existing);
        }
        GroupRepository.GroupInviteRecord invite = null;
        if (rawToken != null && !rawToken.isBlank()) {
            invite = loadUsableInvite(rawToken, clock.instant());
            if (!invite.conversationId().equals(group.conversationId())) {
                throw new GroupException(ApiErrorDefinition.RESOURCE_NOT_FOUND);
            }
        } else if ("PRIVATE".equals(group.visibility())) {
            throw new GroupException(ApiErrorDefinition.RESOURCE_NOT_FOUND);
        }
        GroupRepository.JoinRequestRecord created = repository.insertJoinRequest(
                UuidV7.random(),
                group.conversationId(),
                userId,
                invite == null ? null : invite.inviteId(),
                clock.instant());
        if (created == null) {
            return toJoinRequestResponse(
                    repository.findPendingJoinRequest(group.conversationId(), userId));
        }
        if (invite != null) {
            repository.incrementInviteUse(invite.inviteId());
        }
        return toJoinRequestResponse(created);
    }

    private String fallback(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return "?";
        }
        int codePoint = displayName.codePointAt(0);
        return new String(Character.toChars(codePoint));
    }

    private String userAvatarUrl(UUID userId, UUID avatarMediaId, long avatarVersion) {
        return avatarMediaId == null || avatarVersion == 0
                ? null
                : "/api/v1/users/" + userId
                + "/avatar?variant=thumb&avatarVersion=" + avatarVersion;
    }

    private GroupJoinRequestSummary toJoinRequestSummary(
            GroupRepository.JoinRequestRecord request
    ) {
        return new GroupJoinRequestSummary(
                1,
                request.requestId(),
                request.conversationId(),
                request.userId(),
                request.accountNo(),
                request.displayName(),
                request.status(),
                request.inviteId(),
                request.createdAt(),
                request.resolvedAt());
    }

    private GroupRepository.GroupInviteRecord loadUsableInvite(
            String rawToken,
            Instant now
    ) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new GroupException(ApiErrorDefinition.RESOURCE_NOT_FOUND);
        }
        GroupRepository.GroupInviteRecord invite = repository.lockInviteByTokenHash(
                com.jitong.im.auth.TokenDigests.sha256(rawToken.trim()));
        if (invite == null
                || !"ACTIVE".equals(invite.status())
                || !invite.expiresAt().isAfter(now)
                || invite.useCount() >= invite.maxUses()) {
            throw new GroupException(ApiErrorDefinition.RESOURCE_NOT_FOUND);
        }
        return invite;
    }

    private GroupRepository.GroupInviteRecord loadUsableInviteForRead(
            String rawToken,
            Instant now
    ) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new GroupException(ApiErrorDefinition.RESOURCE_NOT_FOUND);
        }
        GroupRepository.GroupInviteRecord invite = repository.findInviteByTokenHash(
                com.jitong.im.auth.TokenDigests.sha256(rawToken.trim()));
        if (invite == null
                || !"ACTIVE".equals(invite.status())
                || !invite.expiresAt().isAfter(now)
                || invite.useCount() >= invite.maxUses()) {
            throw new GroupException(ApiErrorDefinition.RESOURCE_NOT_FOUND);
        }
        return invite;
    }

    private String findGroupNoByConversation(UUID conversationId) {
        return repository.groupNo(conversationId);
    }

    private boolean hasActiveUser(UUID userId) {
        return repository.activeUserExists(userId);
    }

    private void recordAudit(
            SecurityAuditEventType type,
            UUID actorUserId,
            UUID subjectId,
            AuditOutcome outcome
    ) {
        auditSink.record(new SecurityAuditEvent(
                UuidV7.random(),
                type,
                outcome,
                actorUserId,
                null,
                AuditSubjectType.GROUP,
                subjectId,
                null,
                null,
                clock.instant()));
    }
}
