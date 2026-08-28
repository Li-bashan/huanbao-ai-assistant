package com.huanbao.aigateway.controller;

import com.huanbao.aigateway.common.ApiResponse;
import com.huanbao.aigateway.dto.DataQueryAccessResponse;
import com.huanbao.aigateway.service.DataQueryAccessService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/data-query")
public class DataQueryAccessController {
    private final DataQueryAccessService accessService;

    public DataQueryAccessController(DataQueryAccessService accessService) {
        this.accessService = accessService;
    }

    @GetMapping("/access")
    public ApiResponse<DataQueryAccessResponse> access(
        @RequestParam(value = "userName", required = false) String userName
    ) {
        return ApiResponse.ok(accessService.check(userName));
    }
}
