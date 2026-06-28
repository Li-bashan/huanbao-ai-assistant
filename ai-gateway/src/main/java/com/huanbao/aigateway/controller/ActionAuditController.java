package com.huanbao.aigateway.controller;

import com.huanbao.aigateway.common.ApiResponse;
import com.huanbao.aigateway.dto.ActionAuditCreateRequest;
import com.huanbao.aigateway.dto.ActionAuditResponse;
import com.huanbao.aigateway.dto.PageResponse;
import com.huanbao.aigateway.service.ActionAuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.OffsetDateTime;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/action-audits")
@Validated
public class ActionAuditController {
    private final ActionAuditService actionAuditService;

    public ActionAuditController(ActionAuditService actionAuditService) {
        this.actionAuditService = actionAuditService;
    }

    @PostMapping
    public ApiResponse<ActionAuditResponse> create(
        @Valid @RequestBody ActionAuditCreateRequest request,
        HttpServletRequest servletRequest
    ) {
        String clientIp = truncate(resolveClientIp(servletRequest), 80);
        String userAgent = truncate(servletRequest.getHeader("User-Agent"), 500);
        return ApiResponse.ok(actionAuditService.save(request, clientIp, userAgent));
    }

    @GetMapping
    public ApiResponse<PageResponse<ActionAuditResponse>> search(
        @RequestParam(required = false) String userId,
        @RequestParam(required = false) String action,
        @RequestParam(required = false) String formCode,
        @RequestParam(required = false) String result,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime startTime,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime endTime,
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.ok(actionAuditService.search(userId, action, formCode, result, startTime, endTime, page, size));
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
