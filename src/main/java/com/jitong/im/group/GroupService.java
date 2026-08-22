package com.jitong.im.group;

import com.jitong.im.auth.AuthService;
import com.jitong.im.auth.PublicNumberGenerator;
import com.jitong.im.audit.AuditOutcome;
import com.jitong.im.audit.AuditSubjectType;
import com.jitong.im.audit.SecurityAuditEvent;
import com.jitong.im.audit.SecurityAuditEventType;
import com.jitong.im.audit.SecurityAuditSink;
import com.jitong.im.auth.UuidV7;
import com.jitong.im.platform.error.ApiErrorDefinition;
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
    private final Clock clock;

    @Autowired
    GroupService(
            GroupRepository repository,
            AuthService authService,
            PublicNumberGenerator publicNumberGenerator,
            GroupRateLimiter rateLimiter,
            GroupInviteProperties inviteProperties,
            SecurityAuditSink auditSink
    ) {
        this(
                repository,
                authService,
                publicNumberGenerator,
                rateLimiter,
                inviteProperties,
                auditSink,
                Clock.systemUTC());
    }

    GroupService(
            GroupRepository repository,
            AuthService authService,
            PublicNumberGenerator publicNumberGenerator,
            GroupRateLimiter rateLimiter,
            GroupInviteProperties inviteProperties,
            SecurityAuditSink auditSink,
            Clock clock
    ) {
        this.repository = repository;
        this.authService = authService;
        this.publicNumberGenerator = publicNumberGenerator;
        this.rateLimiter = rateLimiter;
        this.inviteProperties = inviteProperties;
        this.auditSink = auditSink;
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
        return toCreateResponse(group);
    }

    @Transactional(readOnly = true)
    public List<GroupSummary> list(String authorization) {
        UUID userId = authService.requireUserId(authorization);
        return repository.listGroupsForUser(userId).stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional
    public GroupMemberAddResponse addMember(
            String authorization,
            UUID conversationId,
            GroupMemberAddRequest request
    ) {
        UUID actorId = authService.requireUserId(authorization);
        if (request == null || request.accountNo() == null
                || !request.accountNo().trim().matches("[1-9][0-9]{10}")) {
            throw new GroupException(ApiErrorDefinition.INVALID_REQUEST);
        }
        GroupRepository.GroupActor actor = repository.lockActor(conversationId, actorId);
        if (actor == null) {
            throw new GroupException(ApiErrorDefinition.FORBIDDEN);
        }
        UUID memberId = repository.findActiveUserByAccountNoForUpdate(request.accountNo().trim());
        if (memberId == null) {
            throw new GroupException(ApiErrorDefinition.USER_NOT_FOUND);
        }
        if (repository.isBanned(conversationId, memberId)) {
            throw new GroupException(ApiErrorDefinition.FORBIDDEN);
        }
        if (repository.isActiveMember(conversationId, memberId)) {
            throw new GroupException(ApiErrorDefinition.CONFLICT);
        }
        try {
            repository.addMember(conversationId, memberId);
        } catch (DataIntegrityViolationException exception) {
            throw new GroupException(ApiErrorDefinition.CONFLICT);
        }
        GroupRepository.JoinRequestRecord pending =
                repository.findPendingJoinRequest(conversationId, memberId);
        if (pending != null) {
            repository.updateJoinRequestStatus(
                    pending.requestId(),
                    "APPROVED",
                    actorId,
                    clock.instant());
        }
        recordAudit(
                SecurityAuditEventType.GROUP_MEMBERSHIP_CHANGE,
                actorId,
                conversationId,
                AuditOutcome.SUCCEEDED);
        return new GroupMemberAddResponse(
                1,
                conversationId,
                memberId,
                "MEMBER",
                actor.memberCount() + 1);
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
        if (!"MEMBER".equals(targetRole)) {
            throw new GroupException(ApiErrorDefinition.FORBIDDEN);
        }
        repository.removeMember(conversationId, userId, clock.instant());
        recordAudit(
                SecurityAuditEventType.GROUP_MEMBERSHIP_CHANGE,
                actorId,
                conversationId,
                AuditOutcome.SUCCEEDED);
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
            return toJoinRequestResponse(repository.findJoinRequest(requestId));
        }
        if (repository.isActiveMember(conversationId, request.userId())) {
            repository.updateJoinRequestStatus(
                    requestId,
                    "APPROVED",
                    actorId,
                    clock.instant());
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
        if (targetRole != null && !"MEMBER".equals(targetRole)) {
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
        }
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
                group.memberCount());
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
                group.memberCount());
    }

    private GroupSearchResult toSearchResult(GroupRepository.SearchGroupRecord group) {
        return new GroupSearchResult(
                group.name(),
                avatarUrl(group.conversationId(), group.avatarMediaId(), group.avatarVersion()),
                group.description(),
                group.memberCount());
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
