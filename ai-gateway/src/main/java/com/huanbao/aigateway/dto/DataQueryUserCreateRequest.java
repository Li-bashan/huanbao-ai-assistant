package com.huanbao.aigateway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public record DataQueryUserCreateRequest(
    @NotBlank @Size(max = 120) String userId,
    @Size(max = 120) String userCode,
    @NotBlank @Size(max = 80) String userName,
    @Size(max = 120) String tenantId,
    @Size(max = 160) String tenantName,
    @Size(max = 120) String orgId,
    @Size(max = 120) String orgCode,
    @Size(max = 160) String orgName,
    Boolean enabled,
    @Size(max = 255) String remark,
    @Size(max = 40) String scopeType,
    List<@Size(max = 120) String> allowedOrgCodes,
    List<@Size(max = 120) String> allowedIndicatorCodes,
    Boolean allowGroupRanking,
    Boolean allowAllOrganizations
) {
}
