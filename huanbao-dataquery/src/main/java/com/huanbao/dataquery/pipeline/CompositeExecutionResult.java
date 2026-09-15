package com.huanbao.dataquery.pipeline;

import com.huanbao.dataquery.router.AnalysisPlanDto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 问数查库和公文拟写两阶段复合任务的统一交付结果。 */
public record CompositeExecutionResult(
        AnalysisPlanDto plan,
        Map<String, Object> chartConfig,
        List<Map<String, Object>> tableRows,
        String documentMarkdown,
        boolean degraded,
        String degradedMessage,
        List<StructuredFactPayload> factPayloads,
        List<String> anomalySubjects,
        DataValueSemantics valueSemantics) {

    /**
     * 保持 TASK-3.2 原有构造器兼容；旧调用方没有结构化事实时由协议层回退重建证据。
     */
    public CompositeExecutionResult(
            AnalysisPlanDto plan,
            Map<String, Object> chartConfig,
            List<Map<String, Object>> tableRows,
            String documentMarkdown,
            boolean degraded,
            String degradedMessage) {
        this(plan, chartConfig, tableRows, documentMarkdown, degraded, degradedMessage,
                List.of(), List.of(), DataValueSemantics.unknown());
    }

    /** 保持携带结构化事实的旧调用方兼容。 */
    public CompositeExecutionResult(
            AnalysisPlanDto plan,
            Map<String, Object> chartConfig,
            List<Map<String, Object>> tableRows,
            String documentMarkdown,
            boolean degraded,
            String degradedMessage,
            List<StructuredFactPayload> factPayloads) {
        this(plan, chartConfig, tableRows, documentMarkdown, degraded, degradedMessage,
                factPayloads, List.of(), DataValueSemantics.unknown());
    }

    /** 保持带异常主体的旧调用方兼容。 */
    public CompositeExecutionResult(
            AnalysisPlanDto plan,
            Map<String, Object> chartConfig,
            List<Map<String, Object>> tableRows,
            String documentMarkdown,
            boolean degraded,
            String degradedMessage,
            List<StructuredFactPayload> factPayloads,
            List<String> anomalySubjects) {
        this(plan, chartConfig, tableRows, documentMarkdown, degraded, degradedMessage,
                factPayloads, anomalySubjects, DataValueSemantics.unknown());
    }

    public CompositeExecutionResult {
        plan = Objects.requireNonNull(plan, "plan");
        chartConfig = immutableMap(chartConfig, "chartConfig");
        tableRows = immutableRows(tableRows);
        documentMarkdown = documentMarkdown == null ? "" : documentMarkdown;
        degradedMessage = degradedMessage == null ? "" : degradedMessage;
        factPayloads = List.copyOf(Objects.requireNonNull(factPayloads, "factPayloads"));
        anomalySubjects = List.copyOf(Objects.requireNonNull(anomalySubjects, "anomalySubjects"));
        valueSemantics = Objects.requireNonNull(valueSemantics, "valueSemantics");
    }

    private static Map<String, Object> immutableMap(Map<String, Object> values, String fieldName) {
        Objects.requireNonNull(values, fieldName);
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    private static List<Map<String, Object>> immutableRows(List<Map<String, Object>> rows) {
        Objects.requireNonNull(rows, "tableRows");
        List<Map<String, Object>> copied = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Objects.requireNonNull(row, "tableRows item");
            copied.add(Collections.unmodifiableMap(new LinkedHashMap<>(row)));
        }
        return List.copyOf(copied);
    }
}
