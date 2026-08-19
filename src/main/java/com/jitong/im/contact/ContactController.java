package com.jitong.im.contact;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
class ContactController {

    private final ContactService service;

    ContactController(ContactService service) {
        this.service = service;
    }

    @GetMapping("/users/search")
    ContactSearchResult search(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam String accountNo,
            HttpServletRequest request
    ) {
        return service.search(authorization, accountNo.trim(), clientIp(request));
    }

    @PostMapping("/users/me/searchability")
    ResponseEntity<Void> updateSearchability(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody SearchabilityUpdate request
    ) {
        service.updateSearchability(authorization, request.searchable());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/contact-requests")
    ContactRequestResponse createRequest(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody ContactRequestCreate request,
            HttpServletRequest servletRequest
    ) {
        return service.createRequest(authorization, request, clientIp(servletRequest));
    }

    @GetMapping("/contact-requests")
    List<ContactRequestSummary> requests(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return service.listRequests(authorization);
    }

    @PostMapping("/contact-requests/{requestId}/accept")
    ContactRequestResponse accept(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable UUID requestId
    ) {
        return service.acceptRequest(authorization, requestId);
    }

    @PostMapping("/contact-requests/{requestId}/reject")
    ContactRequestResponse reject(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable UUID requestId
    ) {
        return service.rejectRequest(authorization, requestId);
    }

    @PostMapping("/contact-requests/{requestId}/cancel")
    ContactRequestResponse cancel(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable UUID requestId
    ) {
        return service.cancelRequest(authorization, requestId);
    }

    @GetMapping("/contacts")
    List<ContactSummary> contacts(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return service.listContacts(authorization);
    }

    @DeleteMapping("/contacts/{userId}")
    ResponseEntity<Void> remove(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable UUID userId
    ) {
        service.removeContact(authorization, userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/blocks/{userId}")
    ResponseEntity<Void> block(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable UUID userId
    ) {
        service.block(authorization, userId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/blocks/{userId}")
    ResponseEntity<Void> unblock(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable UUID userId
    ) {
        service.unblock(authorization, userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/conversations")
    List<ConversationSummary> conversations(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return service.listConversations(authorization);
    }

    private String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }
}
