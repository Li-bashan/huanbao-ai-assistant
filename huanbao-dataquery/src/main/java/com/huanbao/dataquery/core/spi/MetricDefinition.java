package com.huanbao.dataquery.core.spi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 与 docs/METRIC_SEMANTIC_DICTIONARY.json 的 indicators 契约一一对应的指标定义。
 */
public record MetricDefinition(
        String indicatorCode,
        String formalName,
        Set<String> aliases,
        String category,
        String metricType,
        boolean isNonAdditive,
        List<String> baseMetricRefs,
        List<String> paramRefs,
        String formulaText,
        String unit,
        String aggregationRule,
        List<String> applicableBusinessTypes,
        List<String> supportedOrgLevels,
        List<Map<String, Object>> fallbackRules,
        String formulaStatus,
        List<String> contextRefs) {

    public MetricDefinition {
        indicatorCode = requireText(indicatorCode, "indicatorCode");
        formalName = requireText(formalName, "formalName");
        aliases = copyStringsAsSet(aliases, "aliases");
        category = requireText(category, "category");
        metricType = requireText(metricType, "metricType");
        baseMetricRefs = copyStrings(baseMetricRefs, "baseMetricRefs");
        paramRefs = copyStrings(paramRefs, "paramRefs");
        formulaText = requireText(formulaText, "formulaText");
        unit = requireText(unit, "unit");
        aggregationRule = requireText(aggregationRule, "aggregationRule");
        applicableBusinessTypes = copyStrings(applicableBusinessTypes, "applicableBusinessTypes");
        supportedOrgLevels = copyStrings(supportedOrgLevels, "supportedOrgLevels");
        fallbackRules = copyFallbackRules(fallbackRules);
        formulaStatus = requireText(formulaStatus, "formulaStatus");
        contextRefs = copyStrings(contextRefs, "contextRefs");
    }

    /**
     * 支持指标编码、正式名称以及口语别名匹配。
     */
    public boolean matches(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        String normalizedCandidate = candidate.trim().toLowerCase(Locale.ROOT);
        return indicatorCode.toLowerCase(Locale.ROOT).equals(normalizedCandidate)
                || formalName.toLowerCase(Locale.ROOT).equals(normalizedCandidate)
                || aliases.stream().anyMatch(alias -> alias.toLowerCase(Locale.ROOT).equals(normalizedCandidate));
    }

    private static Set<String> copyStringsAsSet(Set<String> values, String fieldName) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(values.stream()
                .map(value -> requireText(value, fieldName + " item"))
                .toList());
    }

    private static List<String> copyStrings(List<String> values, String fieldName) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return List.copyOf(values.stream()
                .map(value -> requireText(value, fieldName + " item"))
                .toList());
    }

    private static List<Map<String, Object>> copyFallbackRules(List<Map<String, Object>> rules) {
        if (rules == null || rules.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> copiedRules = new ArrayList<>(rules.size());
        for (Map<String, Object> rule : rules) {
            Objects.requireNonNull(rule, "fallbackRules item");
            copiedRules.add(Collections.unmodifiableMap(new LinkedHashMap<>(rule)));
        }
        return List.copyOf(copiedRules);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
