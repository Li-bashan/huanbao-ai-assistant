package com.huanbao.aigateway.dto;

import jakarta.validation.constraints.Size;

public record DataQueryUserContext(
    @Size(max = 100) String userId,
    @Size(max = 100) String userCode,
    @Size(max = 80) String userName,
    @Size(max = 100) String orgCode,
    @Size(max = 160) String orgName,
    @Size(max = 100) String tenantId
) {
}
