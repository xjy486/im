package com.jitong.im.group;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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
            @RequestBody GroupMemberAddRequest request
    ) {
        return service.addMember(authorization, conversationId, request);
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
