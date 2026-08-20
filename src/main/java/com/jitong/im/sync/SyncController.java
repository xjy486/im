package com.jitong.im.sync;

import com.jitong.im.auth.AuthService;
import com.jitong.im.auth.AuthenticatedDevice;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class SyncController {

    private final AuthService authService;
    private final SyncService syncService;

    SyncController(AuthService authService, SyncService syncService) {
        this.authService = authService;
        this.syncService = syncService;
    }

    @GetMapping("/sync")
    SyncPage page(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "0") long after,
            @RequestParam(required = false) Long until,
            @RequestParam(defaultValue = "200") int limit
    ) {
        AuthenticatedDevice device = authService.requireAuthenticatedDevice(authorization);
        return syncService.page(device.userId(), after, until, limit);
    }

    @PostMapping("/sync/ack")
    SyncAckResponse acknowledge(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody SyncAckRequest request
    ) {
        return syncService.acknowledge(authService.requireAuthenticatedDevice(authorization), request.syncSeq());
    }
}
