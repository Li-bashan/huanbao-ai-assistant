package com.huanbao.aigateway.controller;

import com.huanbao.aigateway.common.ApiResponse;
import com.huanbao.aigateway.dto.DataQueryAccessResponse;
import com.huanbao.aigateway.dto.DataQueryAccessRequest;
import com.huanbao.aigateway.security.DataQueryIdentity;
import com.huanbao.aigateway.security.DataQueryIdentityService;
import com.huanbao.aigateway.service.DataQueryAccessService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/data-query")
public class DataQueryAccessController {
    private final DataQueryAccessService accessService;
    private final DataQueryIdentityService identityService;

    public DataQueryAccessController(
        DataQueryAccessService accessService,
        DataQueryIdentityService identityService
    ) {
        this.accessService = accessService;
        this.identityService = identityService;
    }

    @PostMapping("/access")
    public ApiResponse<DataQueryAccessResponse> access(
        @RequestHeader(value = DataQueryIdentityService.IDENTITY_HEADER, required = false) String signedIdentity,
        @RequestHeader(value = DataQueryIdentityService.TIMESTAMP_HEADER, required = false) String timestamp,
        @RequestHeader(value = DataQueryIdentityService.SIGNATURE_HEADER, required = false) String signature,
        @Valid @RequestBody DataQueryAccessRequest request
    ) {
        DataQueryIdentity identity = identityService.resolve(request, signedIdentity, timestamp, signature);
        return ApiResponse.ok(accessService.check(identity));
    }
}
