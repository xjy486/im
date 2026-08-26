package com.jitong.im.contact;

import com.jitong.im.auth.AuthService;
import com.jitong.im.auth.UuidV7;
import com.jitong.im.audit.AuditOutcome;
import com.jitong.im.audit.AuditSubjectType;
import com.jitong.im.audit.SecurityAuditEvent;
import com.jitong.im.audit.SecurityAuditEventType;
import com.jitong.im.audit.SecurityAuditSink;
import com.jitong.im.message.ContactMessageService;
import com.jitong.im.sync.SyncService;
import org.springframework.beans.factory.annotation.Autowired;
import com.jitong.im.platform.error.ApiErrorDefinition;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ContactService {

    private static final Duration REQUEST_LIFETIME = Duration.ofDays(7);

    private final ContactRepository repository;
    private final AuthService authService;
    private final ContactRateLimiter rateLimiter;
    private final SecurityAuditSink auditSink;
    private final SyncService syncService;
    private final ContactMessageService contactMessageService;
    private final Clock clock;

    @Autowired
    public ContactService(
            ContactRepository repository,
            AuthService authService,
            ContactRateLimiter rateLimiter,
            SecurityAuditSink auditSink,
            SyncService syncService,
            ContactMessageService contactMessageService
    ) {
        this(
                repository,
                authService,
                rateLimiter,
                auditSink,
                syncService,
                contactMessageService,
                Clock.systemUTC());
    }

    ContactService(
            ContactRepository repository,
            AuthService authService,
            ContactRateLimiter rateLimiter,
            SecurityAuditSink auditSink,
            SyncService syncService,
            ContactMessageService contactMessageService,
            Clock clock
    ) {
        this.repository = repository;
        this.authService = authService;
        this.rateLimiter = rateLimiter;
        this.auditSink = auditSink;
        this.syncService = syncService;
        this.contactMessageService = contactMessageService;
        this.clock = clock;
    }

    ContactService(
            ContactRepository repository,
            AuthService authService,
            ContactRateLimiter rateLimiter,
            Clock clock
    ) {
        this(
                repository,
                authService,
                rateLimiter,
                event -> {
                },
                null,
                null,
                clock);
    }

    public ContactSearchResult search(String authorization, String accountNo, String ipAddress) {
        UUID currentUserId = authService.requireUserId(authorization);
        rateLimiter.check(currentUserId.toString(), ipAddress);
        rateLimiter.record(currentUserId.toString(), ipAddress);
        if (accountNo == null || !accountNo.matches("[1-9][0-9]{10}")) {
            throw new ContactException(ApiErrorDefinition.USER_NOT_FOUND);
        }
        ContactRepository.ContactUser user = repository.findSearchableUser(accountNo);
        if (user == null || currentUserId.equals(user.id())) {
            throw new ContactException(ApiErrorDefinition.USER_NOT_FOUND);
        }
        if (repository.isBlocked(currentUserId, user.id())
                || repository.isBlocked(user.id(), currentUserId)) {
            throw new ContactException(ApiErrorDefinition.USER_NOT_FOUND);
        }

        ContactRepository.ContactRecord contact = repository.findContact(currentUserId, user.id());
        ContactRepository.ContactRequestRecord pending =
                repository.findPendingRequestBetween(currentUserId, user.id());
        String relationship = contact != null
                ? contact.status()
                : pending == null
                ? "NONE"
                : currentUserId.equals(pending.requesterId())
                ? "PENDING_OUTGOING"
                : "PENDING_INCOMING";
        return new ContactSearchResult(
                1,
                user.accountNo(),
                user.displayName(),
                "ACTIVE".equals(relationship)
                        ? avatarUrl(user.id(), user.avatarMediaId(), user.avatarVersion())
                        : null,
                "ACTIVE".equals(relationship) ? user.avatarVersion() : 0,
                fallback(user.displayName()),
                relationship,
                pending == null ? null : pending.id().toString());
    }

    private String avatarUrl(UUID userId, UUID avatarMediaId, long avatarVersion) {
        return avatarMediaId == null || avatarVersion == 0
                ? null
                : "/api/v1/users/" + userId
                + "/avatar?variant=thumb&avatarVersion=" + avatarVersion;
    }

    private String fallback(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return "?";
        }
        int codePoint = displayName.codePointAt(0);
        return new String(Character.toChars(codePoint));
    }

    @Transactional
    public ContactRequestResponse createRequest(
            String authorization,
            ContactRequestCreate request,
            String ipAddress
    ) {
        UUID requesterId = authService.requireUserId(authorization);
        rateLimiter.check(requesterId.toString(), ipAddress);
        rateLimiter.record(requesterId.toString(), ipAddress);
        String accountNo = request.accountNo().trim();
        if (!accountNo.matches("[1-9][0-9]{10}")) {
            throw new ContactException(ApiErrorDefinition.USER_NOT_FOUND);
        }
        ContactRepository.ContactUser recipientByAccount = repository.findSearchableUser(accountNo);
        if (recipientByAccount == null) {
            throw new ContactException(ApiErrorDefinition.USER_NOT_FOUND);
        }
        UUID recipientId = recipientByAccount.id();
        ensureDifferentUsers(requesterId, recipientId);
        lockUsers(requesterId, recipientId);
        ContactRepository.ContactUser recipient = requireActiveUser(recipientId);
        ensureContactable(requesterId, recipientId, recipient);

        Instant now = clock.instant();
        repository.expirePendingRequestsBetween(requesterId, recipientId, now);
        ContactRepository.ContactRecord contact = repository.findContact(requesterId, recipientId);
        if (contact != null && "ACTIVE".equals(contact.status())) {
            throw new ContactException(ApiErrorDefinition.CONFLICT);
        }

        ContactRepository.ContactRequestRecord reverse = repository.findPendingRequest(recipientId, requesterId);
        if (reverse != null) {
            repository.updateRequestStatus(reverse.id(), "ACCEPTED", now);
            UUID conversationId = acceptContact(requesterId, recipientId);
            ContactRepository.ContactRequestRecord current = repository.findRequest(reverse.id());
            return response(current, conversationId);
        }

        ContactRepository.ContactRequestRecord existing = repository.findPendingRequest(requesterId, recipientId);
        if (existing != null) {
            return response(existing, null);
        }

        ContactRepository.ContactRequestRecord created = repository.insertRequest(
                UuidV7.random(),
                requesterId,
                recipientId,
                request.verification() == null ? "" : request.verification(),
                now.plus(REQUEST_LIFETIME));
        recordContactRequestCreated(recipientId, created.id());
        return response(created, null);
    }

    @Transactional
    public ContactRequestResponse acceptRequest(String authorization, UUID requestId) {
        UUID recipientId = authService.requireUserId(authorization);
        Instant now = clock.instant();
        ContactRepository.ContactRequestRecord request = lockAndLoadRequest(requestId);
        if (!recipientId.equals(request.recipientId())) {
            throw new ContactException(ApiErrorDefinition.FORBIDDEN);
        }
        ensureNotExpired(request, now);
        ensureContactable(request.requesterId(), request.recipientId(), requireActiveUser(request.requesterId()));
        repository.updateRequestStatus(request.id(), "ACCEPTED", now);
        UUID conversationId = acceptContact(request.requesterId(), request.recipientId());
        ContactRepository.ContactRequestRecord accepted = repository.findRequest(request.id());
        return response(accepted, conversationId);
    }

    @Transactional
    public ContactRequestResponse rejectRequest(String authorization, UUID requestId) {
        UUID recipientId = authService.requireUserId(authorization);
        Instant now = clock.instant();
        ContactRepository.ContactRequestRecord request = lockAndLoadRequest(requestId);
        if (!recipientId.equals(request.recipientId())) {
            throw new ContactException(ApiErrorDefinition.FORBIDDEN);
        }
        ensureNotExpired(request, now);
        repository.updateRequestStatus(request.id(), "REJECTED", now);
        return response(repository.findRequest(request.id()), null);
    }

    @Transactional
    public ContactRequestResponse cancelRequest(String authorization, UUID requestId) {
        UUID requesterId = authService.requireUserId(authorization);
        Instant now = clock.instant();
        ContactRepository.ContactRequestRecord request = lockAndLoadRequest(requestId);
        if (!requesterId.equals(request.requesterId())) {
            throw new ContactException(ApiErrorDefinition.FORBIDDEN);
        }
        ensureNotExpired(request, now);
        repository.updateRequestStatus(request.id(), "CANCELLED", now);
        return response(repository.findRequest(request.id()), null);
    }

    @Transactional
    public List<ContactRequestSummary> listRequests(String authorization) {
        UUID userId = authService.requireUserId(authorization);
        repository.expirePendingRequests(clock.instant());
        return repository.listRequests(userId);
    }

    @Transactional
    public void removeContact(String authorization, UUID peerUserId) {
        UUID userId = authService.requireUserId(authorization);
        ensureDifferentUsers(userId, peerUserId);
        lockUsers(userId, peerUserId);
        requireActiveUser(peerUserId);
        ContactRepository.ContactRecord contact = repository.findContact(userId, peerUserId);
        if (contact == null || !"ACTIVE".equals(contact.status())) {
            throw new ContactException(ApiErrorDefinition.USER_NOT_FOUND);
        }
        UUID conversationId = repository.findConversation(userId, peerUserId);
        repository.removeContact(userId, peerUserId, clock.instant());
        recordRelationshipChange(List.of(userId, peerUserId), conversationId);
    }

    @Transactional
    public void block(String authorization, UUID blockedUserId, UUID requestId) {
        UUID blockerId = authService.requireUserId(authorization);
        ensureDifferentUsers(blockerId, blockedUserId);
        lockUsers(blockerId, blockedUserId);
        requireActiveUser(blockedUserId);
        Instant now = clock.instant();
        repository.insertBlock(blockerId, blockedUserId);
        UUID conversationId = repository.findConversation(blockerId, blockedUserId);
        repository.removeContact(blockerId, blockedUserId, now);
        repository.cancelPendingRequests(blockerId, blockedUserId, now);
        recordRelationshipChange(List.of(blockerId, blockedUserId), conversationId);
        auditSink.record(new SecurityAuditEvent(
                UuidV7.random(),
                SecurityAuditEventType.CONTACT_BLOCK,
                AuditOutcome.SUCCEEDED,
                blockerId,
                null,
                AuditSubjectType.USER,
                blockedUserId,
                requestId,
                null,
                now));
    }

    @Transactional
    public void block(String authorization, UUID blockedUserId) {
        block(authorization, blockedUserId, null);
    }

    @Transactional
    public void unblock(String authorization, UUID blockedUserId, UUID requestId) {
        UUID blockerId = authService.requireUserId(authorization);
        ensureDifferentUsers(blockerId, blockedUserId);
        repository.deleteBlock(blockerId, blockedUserId);
        auditSink.record(new SecurityAuditEvent(
                UuidV7.random(),
                SecurityAuditEventType.CONTACT_UNBLOCK,
                AuditOutcome.SUCCEEDED,
                blockerId,
                null,
                AuditSubjectType.USER,
                blockedUserId,
                requestId,
                null,
                clock.instant()));
    }

    @Transactional
    public void unblock(String authorization, UUID blockedUserId) {
        unblock(authorization, blockedUserId, null);
    }

    @Transactional(readOnly = true)
    public List<ContactSummary> listContacts(String authorization) {
        UUID userId = authService.requireUserId(authorization);
        return repository.listContacts(userId);
    }

    @Transactional(readOnly = true)
    public List<ConversationSummary> listConversations(String authorization) {
        UUID userId = authService.requireUserId(authorization);
        return repository.listConversations(userId);
    }

    @Transactional
    public void updateSearchability(String authorization, boolean searchable) {
        UUID userId = authService.requireUserId(authorization);
        repository.updateSearchability(userId, searchable);
    }

    private void recordRelationshipChange(
            List<UUID> userIds,
            UUID conversationId
    ) {
        if (syncService == null || conversationId == null) {
            return;
        }
        syncService.recordEventForUsers(
                userIds,
                "CONTACT_RELATIONSHIP_CHANGED",
                conversationId,
                conversationId);
    }

    private void recordContactRequestCreated(UUID recipientId, UUID requestId) {
        if (syncService == null) {
            return;
        }
        syncService.recordEventForUsers(
                List.of(recipientId),
                "CONTACT_REQUEST_CREATED",
                requestId,
                null);
    }

    public boolean canSendC2c(UUID senderId, UUID recipientId) {
        ContactRepository.ContactRecord contact = !senderId.equals(recipientId)
                ? repository.findContact(senderId, recipientId)
                : null;
        return contact != null
                && "ACTIVE".equals(contact.status())
                && !repository.isBlocked(senderId, recipientId)
                && !repository.isBlocked(recipientId, senderId);
    }

    public void assertCanSendC2c(UUID senderId, UUID recipientId) {
        if (!canSendC2c(senderId, recipientId)) {
            throw new ContactException(ApiErrorDefinition.USER_NOT_FOUND);
        }
    }

    private UUID acceptContact(UUID firstUserId, UUID secondUserId) {
        Instant now = clock.instant();
        for (ContactRepository.ContactRequestRecord request
                : repository.findPendingRequestsBetween(firstUserId, secondUserId)) {
            repository.updateRequestStatus(request.id(), "ACCEPTED", now);
        }
        repository.upsertActiveContact(firstUserId, secondUserId);
        UUID conversationId = repository.findOrCreateConversation(firstUserId, secondUserId);
        contactMessageService.recordEstablished(
                conversationId,
                firstUserId,
                secondUserId,
                now);
        recordRelationshipChange(List.of(firstUserId, secondUserId), conversationId);
        return conversationId;
    }

    private ContactRepository.ContactRequestRecord lockAndLoadRequest(UUID requestId) {
        ContactRepository.ContactRequestRecord request = repository.findRequest(requestId);
        if (request == null) {
            throw new ContactException(ApiErrorDefinition.USER_NOT_FOUND);
        }
        lockUsers(request.requesterId(), request.recipientId());
        request = repository.findPendingRequestForUpdate(requestId);
        if (request == null) {
            throw new ContactException(ApiErrorDefinition.USER_NOT_FOUND);
        }
        return request;
    }

    private void ensureNotExpired(ContactRepository.ContactRequestRecord request, Instant now) {
        if (!"PENDING".equals(request.status())) {
            throw new ContactException(ApiErrorDefinition.CONFLICT);
        }
        if (!request.expiresAt().isAfter(now)) {
            repository.updateRequestStatus(request.id(), "EXPIRED", now);
            throw new ContactException(ApiErrorDefinition.CONFLICT);
        }
    }

    private void ensureContactable(UUID requesterId, UUID recipientId, ContactRepository.ContactUser recipient) {
        if (recipient == null
                || repository.isBlocked(requesterId, recipientId)
                || repository.isBlocked(recipientId, requesterId)) {
            throw new ContactException(ApiErrorDefinition.USER_NOT_FOUND);
        }
    }

    private ContactRepository.ContactUser requireActiveUser(UUID userId) {
        ContactRepository.ContactUser user = repository.findActiveUser(userId);
        if (user == null) {
            throw new ContactException(ApiErrorDefinition.USER_NOT_FOUND);
        }
        return user;
    }

    private void lockUsers(UUID firstUserId, UUID secondUserId) {
        ContactRepository.UserPair pair = ContactRepository.UserPair.of(firstUserId, secondUserId);
        repository.lockUser(pair.low());
        repository.lockUser(pair.high());
    }

    private void ensureDifferentUsers(UUID firstUserId, UUID secondUserId) {
        if (secondUserId == null || firstUserId.equals(secondUserId)) {
            throw new ContactException(ApiErrorDefinition.CONFLICT);
        }
    }

    private ContactRequestResponse response(ContactRepository.ContactRequestRecord request, UUID conversationId) {
        return new ContactRequestResponse(
                1,
                request.id(),
                request.requesterId(),
                request.recipientId(),
                request.status(),
                request.verification(),
                request.expiresAt(),
                conversationId);
    }
}
