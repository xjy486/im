package com.jitong.im.group;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;

@RestController
@RequestMapping("/api/v1/groups")
class GroupController {

    private final GroupService service;

    GroupController(GroupService service) {
        this.service = service;
    }

    @PostMapping
    GroupCreateResponse create(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody CreateGroupRequest request
    ) {
        return service.create(authorization, request);
    }

    @GetMapping
    List<GroupSummary> list(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return service.list(authorization);
    }

    @PostMapping("/{conversationId}/members")
    GroupMemberAddResponse addMember(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable java.util.UUID conversationId,
            @Valid @RequestBody GroupMemberAddRequest request
    ) {
        return service.addMember(authorization, conversationId, request);
    }

    @DeleteMapping("/{conversationId}/members/{userId}")
    org.springframework.http.ResponseEntity<Void> removeMember(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable java.util.UUID conversationId,
            @PathVariable java.util.UUID userId
    ) {
        service.removeMember(authorization, conversationId, userId);
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    @PutMapping("/{conversationId}/members/{userId}/role")
    GroupRoleChangeResponse changeRole(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable java.util.UUID conversationId,
            @PathVariable java.util.UUID userId,
            @Valid @RequestBody GroupRoleChangeRequest request
    ) {
        return service.changeRole(authorization, conversationId, userId, request);
    }

    @PostMapping("/{conversationId}/owner-transfer")
    GroupOwnerTransferResponse transferOwner(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable java.util.UUID conversationId,
            @Valid @RequestBody GroupOwnerTransferRequest request
    ) {
        return service.transferOwner(authorization, conversationId, request);
    }

    @PutMapping("/{conversationId}/profile")
    GroupSummary updateProfile(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable java.util.UUID conversationId,
            @Valid @RequestBody GroupProfileUpdateRequest request
    ) {
        return service.updateProfile(authorization, conversationId, request);
    }

    @PostMapping("/{conversationId}/leave")
    org.springframework.http.ResponseEntity<Void> leave(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable java.util.UUID conversationId
    ) {
        service.leave(authorization, conversationId);
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    @PostMapping("/{conversationId}/invites")
    GroupInviteResponse createInvite(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable java.util.UUID conversationId,
            @Valid @RequestBody(required = false) GroupInviteCreateRequest request
    ) {
        return service.createInvite(authorization, conversationId, request);
    }

    @GetMapping("/invites/resolve")
    GroupInviteResolveResponse resolveInvite(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam String token,
            HttpServletRequest request
    ) {
        return service.resolveInvite(authorization, token, clientIp(request));
    }

    @DeleteMapping("/{conversationId}/invites/{inviteId}")
    org.springframework.http.ResponseEntity<Void> revokeInvite(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable java.util.UUID conversationId,
            @PathVariable java.util.UUID inviteId
    ) {
        service.revokeInvite(authorization, conversationId, inviteId);
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    @PostMapping("/{conversationId}/join-requests")
    GroupJoinRequestResponse createJoinRequest(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable java.util.UUID conversationId,
            @Valid @RequestBody(required = false) GroupJoinRequestCreateRequest request,
            HttpServletRequest servletRequest
    ) {
        return service.createJoinRequest(authorization, conversationId, request, clientIp(servletRequest));
    }

    @GetMapping("/{conversationId}/join-requests")
    List<GroupJoinRequestSummary> listJoinRequests(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable java.util.UUID conversationId
    ) {
        return service.listJoinRequests(authorization, conversationId);
    }

    @PostMapping("/{conversationId}/join-requests/{requestId}/approve")
    GroupJoinRequestResponse approveJoinRequest(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable java.util.UUID conversationId,
            @PathVariable java.util.UUID requestId
    ) {
        return service.approveJoinRequest(authorization, conversationId, requestId);
    }

    @PostMapping("/{conversationId}/join-requests/{requestId}/reject")
    GroupJoinRequestResponse rejectJoinRequest(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable java.util.UUID conversationId,
            @PathVariable java.util.UUID requestId
    ) {
        return service.rejectJoinRequest(authorization, conversationId, requestId);
    }

    @PostMapping("/{conversationId}/join-requests/{requestId}/cancel")
    GroupJoinRequestResponse cancelJoinRequest(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable java.util.UUID conversationId,
            @PathVariable java.util.UUID requestId
    ) {
        return service.cancelJoinRequest(authorization, conversationId, requestId);
    }

    @PostMapping("/{conversationId}/bans/{userId}")
    org.springframework.http.ResponseEntity<Void> banUser(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable java.util.UUID conversationId,
            @PathVariable java.util.UUID userId,
            @Valid @RequestBody(required = false) GroupBanRequest request
    ) {
        service.banUser(authorization, conversationId, userId, request);
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{conversationId}/bans/{userId}")
    org.springframework.http.ResponseEntity<Void> unbanUser(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable java.util.UUID conversationId,
            @PathVariable java.util.UUID userId
    ) {
        service.unbanUser(authorization, conversationId, userId);
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    @GetMapping("/search")
    GroupSearchPage search(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam String query,
            HttpServletRequest request
    ) {
        return service.search(authorization, query, clientIp(request));
    }

    private String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }
}
