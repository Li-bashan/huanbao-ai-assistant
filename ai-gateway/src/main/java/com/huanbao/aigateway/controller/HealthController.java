package com.huanbao.aigateway.controller;

import com.huanbao.aigateway.common.ApiResponse;
import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai")
public class HealthController {
    private final String version;

    public HealthController(@Value("${app.version}") String version) {
        this.version = version;
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.ok(Map.of(
            "status", "UP",
            "time", OffsetDateTime.now().toString(),
            "version", version
        ));
    }
}
