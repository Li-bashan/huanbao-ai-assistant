package com.huanbao.aigateway.controller;

import com.huanbao.aigateway.dto.DataQueryAuthorization;
import com.huanbao.aigateway.dto.DataQueryChatRequest;
import com.huanbao.aigateway.dto.ConversationMapping;
import com.huanbao.aigateway.security.DataQueryIdentity;
import com.huanbao.aigateway.security.DataQueryIdentityService;
import com.huanbao.aigateway.service.DataQueryAccessService;
import com.huanbao.aigateway.service.DataQueryRateLimiter;
import com.huanbao.aigateway.service.ConversationMappingService;
import com.huanbao.aigateway.service.DifyDataQueryChatService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

@RestController
@RequestMapping("/api/ai/data-query")
public class DataQueryChatController {
    private final DataQueryIdentityService identityService;
    private final DataQueryAccessService accessService;
    private final DataQueryRateLimiter rateLimiter;
    private final DifyDataQueryChatService chatService;
    private final ConversationMappingService mappingService;

    public DataQueryChatController(
        DataQueryIdentityService identityService,
        DataQueryAccessService accessService,
        DataQueryRateLimiter rateLimiter,
        DifyDataQueryChatService chatService,
        ConversationMappingService mappingService
    ) {
        this.identityService = identityService;
        this.accessService = accessService;
        this.rateLimiter = rateLimiter;
        this.chatService = chatService;
        this.mappingService = mappingService;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> chat(
        @RequestHeader(value = DataQueryIdentityService.IDENTITY_HEADER, required = false) String signedIdentity,
        @RequestHeader(value = DataQueryIdentityService.TIMESTAMP_HEADER, required = false) String timestamp,
        @RequestHeader(value = DataQueryIdentityService.SIGNATURE_HEADER, required = false) String signature,
        @Valid @RequestBody DataQueryChatRequest request,
        HttpServletRequest servletRequest
    ) {
        DataQueryIdentity identity = identityService.resolve(request, signedIdentity, timestamp, signature);
        DataQueryAuthorization authorization = accessService.requireCovered(identity);
        if (!rateLimiter.tryAcquire(authorization.userId())) {
            throw new com.huanbao.aigateway.exception.BusinessException(
                "RATE_LIMITED", "data query rate limit exceeded"
            );
        }

        // The browser request id is only a client hint. The Gateway owns the
        // authoritative id used for audit, rate-limit diagnostics and SSE.
        String requestId = UUID.randomUUID().toString();
        // The first turn may omit conversationId. The Gateway creates an
        // opaque browser-facing handle; Dify's internal id stays server-side.
        String clientConversationId = StringUtils.hasText(request.conversationId())
            ? request.conversationId().trim()
            : "hb_" + UUID.randomUUID();
        DataQueryChatRequest serverRequest = request
            .withConversationId(clientConversationId)
            .withRequestId(requestId);
        ConversationMapping mapping = mappingService.claim(
            identity, "data-query", clientConversationId, requestId
        );
        String clientIp = servletRequest.getRemoteAddr();
        String userAgent = servletRequest.getHeader(HttpHeaders.USER_AGENT);
        StreamingResponseBody body = output -> {
            boolean success = false;
            try {
                success = chatService.stream(
                    serverRequest, identity, authorization, mapping, clientIp, userAgent, output
                );
            } finally {
                mappingService.release(mapping, requestId, success);
            }
        };
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONNECTION, "keep-alive")
            .header("X-Request-Id", requestId)
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .body(body);
    }

}
