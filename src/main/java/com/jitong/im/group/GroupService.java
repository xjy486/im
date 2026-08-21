package com.jitong.im.group;

import com.jitong.im.auth.AuthService;
import com.jitong.im.auth.PublicNumberGenerator;
import com.jitong.im.platform.error.ApiErrorDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.UUID;

@Service
public class GroupService {

    private final GroupRepository repository;
    private final AuthService authService;
    private final PublicNumberGenerator publicNumberGenerator;
    private final GroupRateLimiter rateLimiter;

    @Autowired
    GroupService(
            GroupRepository repository,
            AuthService authService,
            PublicNumberGenerator publicNumberGenerator,
            GroupRateLimiter rateLimiter
    ) {
        this.repository = repository;
        this.authService = authService;
        this.publicNumberGenerator = publicNumberGenerator;
        this.rateLimiter = rateLimiter;
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
        if (repository.isActiveMember(conversationId, memberId)) {
            throw new GroupException(ApiErrorDefinition.CONFLICT);
        }
        try {
            repository.addMember(conversationId, memberId);
        } catch (DataIntegrityViolationException exception) {
            throw new GroupException(ApiErrorDefinition.CONFLICT);
        }
        return new GroupMemberAddResponse(
                1,
                conversationId,
                memberId,
                "MEMBER",
                actor.memberCount() + 1);
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
}
