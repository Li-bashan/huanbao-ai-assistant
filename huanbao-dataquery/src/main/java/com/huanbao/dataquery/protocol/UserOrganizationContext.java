package com.huanbao.dataquery.protocol;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** 已由内部网关校验过的用户及其组织范围。 */
public record UserOrganizationContext(String userId, List<String> allowedOrgs) {

    public UserOrganizationContext(String userId, Collection<String> allowedOrgs) {
        this(userId, normalize(userId, allowedOrgs));
    }

    public UserOrganizationContext {
        userId = requireText(userId, "userId");
        allowedOrgs = List.copyOf(Objects.requireNonNull(allowedOrgs, "allowedOrgs"));
    }

    private static List<String> normalize(String userId, Collection<String> allowedOrgs) {
        requireText(userId, "userId");
        Objects.requireNonNull(allowedOrgs, "allowedOrgs");
        return allowedOrgs.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
