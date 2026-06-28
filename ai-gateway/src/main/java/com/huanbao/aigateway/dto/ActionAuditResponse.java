package com.huanbao.aigateway.dto;

import java.time.OffsetDateTime;

public record ActionAuditResponse(
    Long id,
    String actionId,
    String userId,
    String userName,
    String query,
    String action,
    String formCode,
    String funcId,
    String status,
    Boolean hasFields,
    OffsetDateTime sentAt,
    String result,
    String errorMessage,
    String clientIp,
    String userAgent,
    OffsetDateTime createdAt
) {
}
