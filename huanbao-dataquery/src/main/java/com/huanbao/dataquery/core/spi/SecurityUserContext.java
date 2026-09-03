package com.huanbao.dataquery.core.spi;

import java.util.Objects;
import java.util.Set;

/**
 * 已由上游身份边界确认的服务端用户上下文。浏览器提交的同名字段不能直接构造该对象。
 */
public record SecurityUserContext(
        String userId,
        String tenantId,
        Set<String> organizationCodes,
        Set<String> roles) {

    public SecurityUserContext {
        userId = requireText(userId, "userId");
        tenantId = requireText(tenantId, "tenantId");
        organizationCodes = Set.copyOf(Objects.requireNonNull(organizationCodes, "organizationCodes"));
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
