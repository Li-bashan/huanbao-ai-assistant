package com.huanbao.aigateway.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record DataQueryUserResponse(
    Long id,
    String userId,
    String userCode,
    String userName,
    String tenantId,
    String tenantName,
    String orgId,
    String orgCode,
    String orgName,
    boolean enabled,
    String migrationStatus,
    String scopeType,
    List<String> allowedOrgCodes,
    List<String> allowedIndicatorCodes,
    boolean allowGroupRanking,
    boolean allowAllOrganizations,
    String remark,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
}
