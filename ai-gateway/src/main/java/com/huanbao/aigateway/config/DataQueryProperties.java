package com.huanbao.aigateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "data-query")
public record DataQueryProperties(
    String apiBase,
    String apiKey,
    int timeoutMs,
    String difyUser,
    String difyUserHmacSecret,
    String identityMode,
    String identitySecret,
    int identityClockSkewSeconds,
    boolean allowBodyIdentityTrial,
    int maxRequestsPerMinute,
    int maxConcurrentPerUser,
    String defaultOrganizationScope,
    boolean allowGroupRanking,
    String allowedIndicatorCodes,
    boolean auditEnabled
) {
    /** Compatibility constructor for the original gateway tests/config shape. */
    public DataQueryProperties(
        String apiBase,
        String apiKey,
        int timeoutMs,
        String identityMode,
        String identitySecret,
        int identityClockSkewSeconds,
        boolean allowBodyIdentityTrial,
        int maxRequestsPerMinute,
        String defaultOrganizationScope,
        boolean allowGroupRanking,
        String allowedIndicatorCodes,
        boolean auditEnabled
    ) {
        this(apiBase, apiKey, timeoutMs, null, identitySecret, identityMode, identitySecret,
            identityClockSkewSeconds, allowBodyIdentityTrial, maxRequestsPerMinute, 1,
            defaultOrganizationScope, allowGroupRanking, allowedIndicatorCodes, auditEnabled);
    }

    public int safeTimeoutMs() {
        return timeoutMs > 0 ? timeoutMs : 60000;
    }

    public int safeClockSkewSeconds() {
        return identityClockSkewSeconds > 0 ? identityClockSkewSeconds : 300;
    }

    public int safeMaxRequestsPerMinute() {
        return maxRequestsPerMinute > 0 ? maxRequestsPerMinute : 30;
    }

    public int safeMaxConcurrentPerUser() {
        return maxConcurrentPerUser > 0 ? maxConcurrentPerUser : 1;
    }

    public String safeIdentityMode() {
        return identityMode == null ? "SIGNED_HEADER" : identityMode.trim().toUpperCase();
    }
}
