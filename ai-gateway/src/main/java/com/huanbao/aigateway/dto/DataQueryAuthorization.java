package com.huanbao.aigateway.dto;

import java.util.List;

public record DataQueryAuthorization(
    String userId,
    String userCode,
    String userName,
    String orgCode,
    String orgName,
    String tenantId,
    boolean identityVerified,
    String identitySource,
    String organizationScope,
    boolean allowGroupRanking,
    List<String> allowedIndicatorCodes,
    List<String> allowedOrgCodes,
    boolean allowAllOrganizations
) {
}
