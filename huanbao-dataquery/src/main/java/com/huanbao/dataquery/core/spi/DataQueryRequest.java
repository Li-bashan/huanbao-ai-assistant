package com.huanbao.dataquery.core.spi;

import java.util.Map;
import java.util.Objects;

/**
 * 领域 SQL 策略的受控输入，不包含任意 SQL 字符串。
 */
public record DataQueryRequest(
        String metricName,
        String entityName,
        Map<String, Object> filters) {

    public DataQueryRequest {
        metricName = requireText(metricName, "metricName");
        entityName = requireText(entityName, "entityName");
        filters = Map.copyOf(Objects.requireNonNull(filters, "filters"));
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
