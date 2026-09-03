package com.huanbao.dataquery.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 结果审计和降级元数据。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record MetaDto(
        String auditStatus,
        boolean validated,
        Map<String, Object> auditSummary,
        Map<String, Object> analysisState,
        Map<String, Object> analysisCoverage,
        boolean degraded,
        String degradedMessage) {

    public MetaDto {
        auditStatus = auditStatus == null ? "" : auditStatus.trim();
        auditSummary = immutableMap(auditSummary);
        analysisState = immutableMap(analysisState);
        analysisCoverage = immutableMap(analysisCoverage);
        degradedMessage = degradedMessage == null ? "" : degradedMessage.trim();
    }

    public static MetaDto empty() {
        return new MetaDto("", false, Map.of(), Map.of(), Map.of(), false, "");
    }

    private static Map<String, Object> immutableMap(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
