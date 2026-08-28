package com.huanbao.aigateway.controller;

import com.huanbao.aigateway.common.ApiResponse;
import com.huanbao.aigateway.config.DataQueryAdminProperties;
import com.huanbao.aigateway.dto.DataQueryUserCreateRequest;
import com.huanbao.aigateway.dto.DataQueryUserResponse;
import com.huanbao.aigateway.dto.DataQueryUserUpdateRequest;
import com.huanbao.aigateway.exception.BusinessException;
import com.huanbao.aigateway.service.DataQueryUserService;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/data-query/users")
public class DataQueryUserAdminController {
    private final DataQueryUserService userService;
    private final DataQueryAdminProperties properties;

    public DataQueryUserAdminController(
        DataQueryUserService userService,
        DataQueryAdminProperties properties
    ) {
        this.userService = userService;
        this.properties = properties;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<DataQueryUserResponse>>> list(
        @RequestHeader(value = "X-Admin-Token", required = false) String adminToken,
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) Boolean enabled
    ) {
        ResponseEntity<ApiResponse<Void>> unauthorized = authorize(adminToken);
        if (unauthorized != null) {
            return ResponseEntity.status(unauthorized.getStatusCode())
                .body(ApiResponse.fail("UNAUTHORIZED", "invalid admin token"));
        }
        return ResponseEntity.ok(ApiResponse.ok(userService.list(keyword, enabled)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<DataQueryUserResponse>> create(
        @RequestHeader(value = "X-Admin-Token", required = false) String adminToken,
        @Valid @RequestBody DataQueryUserCreateRequest request
    ) {
        requireAuthorized(adminToken);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(userService.create(request)));
    }

    @PutMapping("/{id}")
    public ApiResponse<DataQueryUserResponse> update(
        @RequestHeader(value = "X-Admin-Token", required = false) String adminToken,
        @PathVariable long id,
        @Valid @RequestBody DataQueryUserUpdateRequest request
    ) {
        requireAuthorized(adminToken);
        return ApiResponse.ok(userService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(
        @RequestHeader(value = "X-Admin-Token", required = false) String adminToken,
        @PathVariable long id
    ) {
        requireAuthorized(adminToken);
        userService.delete(id);
        return ApiResponse.ok(null);
    }

    private void requireAuthorized(String adminToken) {
        ResponseEntity<ApiResponse<Void>> unauthorized = authorize(adminToken);
        if (unauthorized != null) {
            throw new BusinessException("UNAUTHORIZED", "invalid admin token");
        }
    }

    private ResponseEntity<ApiResponse<Void>> authorize(String provided) {
        String expected = properties.adminToken();
        if (!StringUtils.hasText(expected) || !StringUtils.hasText(provided)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.fail("UNAUTHORIZED", "invalid admin token"));
        }
        boolean valid = MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            provided.getBytes(StandardCharsets.UTF_8)
        );
        return valid ? null : ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(ApiResponse.fail("UNAUTHORIZED", "invalid admin token"));
    }
}
