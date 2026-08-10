package com.huanbao.aigateway.controller;

import com.huanbao.aigateway.common.ApiResponse;
import com.huanbao.aigateway.config.DifyHttpProperties;
import com.huanbao.aigateway.dto.DifyDataQueryRequest;
import com.huanbao.aigateway.dto.DifyDataQueryResponse;
import com.huanbao.aigateway.service.DifyDataQueryService;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/data")
public class DifyDataQueryController {
    private final DifyDataQueryService queryService;
    private final DifyHttpProperties properties;

    public DifyDataQueryController(
        DifyDataQueryService queryService,
        DifyHttpProperties properties
    ) {
        this.queryService = queryService;
        this.properties = properties;
    }

    @PostMapping("/query")
    public ResponseEntity<ApiResponse<DifyDataQueryResponse>> query(
        @RequestHeader(value = "X-API-Key", required = false) String apiKey,
        @Valid @RequestBody DifyDataQueryRequest request
    ) {
        if (!hasValidApiKey(apiKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.<DifyDataQueryResponse>fail("UNAUTHORIZED", "invalid API key"));
        }
        return ResponseEntity.ok(ApiResponse.ok(queryService.query(request)));
    }

    private boolean hasValidApiKey(String provided) {
        String expected = properties.apiKey();
        if (!StringUtils.hasText(expected)) {
            return true;
        }
        if (!StringUtils.hasText(provided)) {
            return false;
        }
        return MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            provided.getBytes(StandardCharsets.UTF_8)
        );
    }
}
