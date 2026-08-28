package com.huanbao.aigateway.dto;

import java.time.OffsetDateTime;

public record DataQueryUserResponse(
    Long id,
    String userName,
    boolean enabled,
    String remark,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
}
