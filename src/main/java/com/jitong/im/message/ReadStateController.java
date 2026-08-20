package com.jitong.im.message;

import com.jitong.im.auth.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/conversations")
class ReadStateController {

    private final AuthService authService;
    private final ReadStateService readStateService;

    ReadStateController(
            AuthService authService,
            ReadStateService readStateService
    ) {
        this.authService = authService;
        this.readStateService = readStateService;
    }

    @GetMapping("/{conversationId}/read")
    ConversationReadStatePage states(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable UUID conversationId
    ) {
        return readStateService.states(
                authService.requireUserId(authorization),
                conversationId);
    }

    @PostMapping("/{conversationId}/read")
    ConversationReadStatePage markRead(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable UUID conversationId,
            @Valid @RequestBody ReadStateRequest request
    ) {
        return readStateService.markRead(
                authService.requireUserId(authorization),
                conversationId,
                request.readSeq());
    }
}
