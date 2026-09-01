package com.huanbao.aigateway.dto;

import java.util.List;

public record DataQueryAccessResponse(
    boolean covered,
    String userName,
    boolean identityVerified,
    String identitySource,
    String userId,
    String organizationScope,
    List<String> allowedOrgCodes,
    List<String> allowedIndicatorCodes,
    boolean allowGroupRanking,
    boolean allowAllOrganizations
) {
    public DataQueryAccessResponse(boolean covered, String userName) {
        this(covered, userName, false, "LEGACY", "", "NONE", List.of(), List.of(), false, false);
    }
}
