package com.huanbao.aigateway.security;

public record DataQueryIdentity(
    String userId,
    String userCode,
    String userName,
    String orgCode,
    String orgName,
    String tenantId,
    boolean verified,
    String source
) {
}
