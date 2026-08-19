package com.jitong.im.message;

import com.jitong.im.auth.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/conversations")
class MessageController {

    private final AuthService authService;
    private final MessageService messageService;

    MessageController(
            AuthService authService,
            MessageService messageService
    ) {
        this.authService = authService;
        this.messageService = messageService;
    }

    @PostMapping("/{conversationId}/messages")
    MessageRecord sendText(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable UUID conversationId,
            @Valid @RequestBody MessageSendRequest request
    ) {
        return messageService.sendText(
                authService.requireUserId(authorization),
                conversationId,
                request.clientMsgId(),
                request.text()).message();
    }

    @GetMapping("/{conversationId}/messages")
    ConversationMessagePage listMessages(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable UUID conversationId,
            @RequestParam(defaultValue = "0") long afterSeq,
            @RequestParam(defaultValue = "200") int limit
    ) {
        return messageService.listMessages(
                authService.requireUserId(authorization),
                conversationId,
                afterSeq,
                limit);
    }
}
