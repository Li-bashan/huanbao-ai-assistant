package com.huanbao.aigateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record DifyDataQueryRequest(
    @NotBlank @Size(max = 64) String queryCode,
    Map<String, Object> params,
    @Size(max = 80) String userName
) {
    public Map<String, Object> safeParams() {
        return params == null ? Map.of() : params;
    }
}
