package com.jitong.im.abuse;

import com.jitong.im.platform.observability.RequestContextFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/abuse-reports")
class AbuseController {

    private final AbuseReportService service;

    AbuseController(AbuseReportService service) {
        this.service = service;
    }

    @PostMapping
    AbuseReportResponse create(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody AbuseReportCreateRequest request,
            HttpServletRequest servletRequest
    ) {
        return service.create(
                authorization,
                request,
                UUID.fromString(RequestContextFilter.requestId(servletRequest)));
    }

    @GetMapping
    List<AbuseReportResponse> listMine(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return service.listMine(authorization);
    }
}
