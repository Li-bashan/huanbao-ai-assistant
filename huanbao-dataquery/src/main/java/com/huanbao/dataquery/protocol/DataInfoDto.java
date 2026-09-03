package com.huanbao.dataquery.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 数据口径、截止期和结果校验信息。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DataInfoDto(
        String analysisType,
        String indicatorName,
        String indicatorCode,
        String unit,
        Map<String, Object> timeRange,
        String dataCutoffDate,
        String aggregation,
        Map<String, Object> organizationScope,
        int rowCount,
        Map<String, Object> statistics,
        Map<String, Object> comparison,
        List<String> sourceTables,
        List<String> warnings,
        Map<String, Object> coverage,
        Map<String, Object> validation) {

    public DataInfoDto {
        analysisType = optional(analysisType);
        indicatorName = optional(indicatorName);
        indicatorCode = optional(indicatorCode);
        unit = optional(unit);
        timeRange = immutableMap(timeRange);
        dataCutoffDate = optional(dataCutoffDate);
        aggregation = optional(aggregation);
        organizationScope = immutableMap(organizationScope);
        rowCount = Math.max(0, rowCount);
        statistics = immutableMap(statistics);
        comparison = immutableMap(comparison);
        sourceTables = List.copyOf(Objects.requireNonNull(sourceTables, "sourceTables"));
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
        coverage = immutableMap(coverage);
        validation = immutableMap(validation);
    }

    public static DataInfoDto empty() {
        return new DataInfoDto(
                "", "", "", "", Map.of(), "", "", Map.of(), 0,
                Map.of(), Map.of(), List.of(), List.of(), Map.of(), Map.of());
    }

    private static Map<String, Object> immutableMap(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    private static String optional(String value) {
        return value == null ? "" : value.trim();
    }
}
