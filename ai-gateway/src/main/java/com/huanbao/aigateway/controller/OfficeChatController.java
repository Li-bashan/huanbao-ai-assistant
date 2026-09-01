package com.huanbao.aigateway.controller;

import com.huanbao.aigateway.dto.ConversationMapping;
import com.huanbao.aigateway.dto.OfficeChatRequest;
import com.huanbao.aigateway.security.DataQueryIdentity;
import com.huanbao.aigateway.security.DataQueryIdentityService;
import com.huanbao.aigateway.service.ConversationMappingService;
import com.huanbao.aigateway.service.DataQueryRateLimiter;
import com.huanbao.aigateway.service.DifyOfficeChatService;
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
import org.springframework.util.StringUtils;

@RestController
@RequestMapping("/api/ai/office")
public class OfficeChatController {
    private final DataQueryIdentityService identityService;
    private final ConversationMappingService mappingService;
    private final DataQueryRateLimiter rateLimiter;
    private final DifyOfficeChatService chatService;

    public OfficeChatController(
        DataQueryIdentityService identityService,
        ConversationMappingService mappingService,
        DataQueryRateLimiter rateLimiter,
        DifyOfficeChatService chatService
    ) {
        this.identityService = identityService;
        this.mappingService = mappingService;
        this.rateLimiter = rateLimiter;
        this.chatService = chatService;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> chat(
        @RequestHeader(value = DataQueryIdentityService.IDENTITY_HEADER, required = false) String signedIdentity,
        @RequestHeader(value = DataQueryIdentityService.TIMESTAMP_HEADER, required = false) String timestamp,
        @RequestHeader(value = DataQueryIdentityService.SIGNATURE_HEADER, required = false) String signature,
        @Valid @RequestBody OfficeChatRequest request
    ) {
        DataQueryIdentity identity = identityService.resolve(request, signedIdentity, timestamp, signature);
        if (!rateLimiter.tryAcquire(identity.userId())) {
            throw new com.huanbao.aigateway.exception.BusinessException("RATE_LIMITED", "request rate limit exceeded");
        }
        String requestId = UUID.randomUUID().toString();
        String clientConversationId = StringUtils.hasText(request.conversationId())
            ? request.conversationId().trim()
            : "hb_" + UUID.randomUUID();
        OfficeChatRequest serverRequest = request.withConversationId(clientConversationId);
        ConversationMapping mapping = mappingService.claim(identity, "office-ai", clientConversationId, requestId);
        StreamingResponseBody body = output -> {
            boolean success = false;
            try {
                success = chatService.stream(serverRequest, identity, mapping, requestId, output);
            } finally {
                mappingService.release(mapping, requestId, success);
            }
        };
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONNECTION, "keep-alive")
            .header("X-Request-Id", requestId)
            .contentType(MediaType.TEXT_EVENT_STREAM).body(body);
    }
}
