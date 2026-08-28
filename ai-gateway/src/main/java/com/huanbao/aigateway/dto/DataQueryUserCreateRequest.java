package com.huanbao.aigateway.dto;

import jakarta.validation.constraints.Size;

public record DataQueryUserCreateRequest(
    @Size(max = 80) String userName,
    Boolean enabled,
    @Size(max = 255) String remark
) {
}
