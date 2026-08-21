package com.jitong.im.message;

import com.jitong.im.auth.AuthService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/messages")
class MessageRecallController {
    private final AuthService authService;
    private final MessageService messageService;

    MessageRecallController(AuthService authService, MessageService messageService) {
        this.authService = authService;
        this.messageService = messageService;
    }

    @PostMapping("/{messageId}/recall")
    MessageRecord recall(@RequestHeader(value = "Authorization", required = false) String authorization,
                         @PathVariable UUID messageId) {
        return messageService.recall(authService.requireUserId(authorization), messageId);
    }
}
