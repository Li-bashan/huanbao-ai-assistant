package com.huanbao.dataquery.pipeline;

import com.huanbao.dataquery.core.analysis.ClueItem;
import com.huanbao.dataquery.core.analysis.FactItem;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 交给公文阶段的结构化事实载荷。
 *
 * <p>数值字段只来自确定性查询、计算和分析阶段。三层证据链在这里原样保留，
 * 便于 Prompt 明确区分已确认事实、相关线索和待核实事项。</p>
 */
public record StructuredFactPayload(
        String subject,
        String metric,
        String period,
        BigDecimal currentValue,
        BigDecimal yearOverYearPercent,
        BigDecimal monthOverMonthPercent,
        Integer rank,
        Integer previousRank,
        Integer rankChange,
        List<FactItem> confirmedFacts,
        List<ClueItem> correlatedClues,
        List<String> pendingVerification) {

    public StructuredFactPayload {
        subject = requireText(subject, "subject");
        metric = requireText(metric, "metric");
        period = requireText(period, "period");
        confirmedFacts = List.copyOf(Objects.requireNonNull(confirmedFacts, "confirmedFacts"));
        correlatedClues = List.copyOf(Objects.requireNonNull(correlatedClues, "correlatedClues"));
        pendingVerification = List.copyOf(Objects.requireNonNull(pendingVerification, "pendingVerification"));
    }

    /** 返回与公文事实 JSON 相同语义的扁平视图，便于日志和测试查看。 */
    public Map<String, Object> toFactMap() {
        Map<String, Object> fact = new LinkedHashMap<>();
        fact.put("subject", subject);
        fact.put("metric", metric);
        fact.put("period", period);
        fact.put("currentValue", currentValue);
        fact.put("yearOverYearPercent", yearOverYearPercent);
        fact.put("monthOverMonthPercent", monthOverMonthPercent);
        fact.put("rank", rank);
        fact.put("previousRank", previousRank);
        fact.put("rankChange", rankChange);
        fact.put("confirmedFacts", confirmedFacts);
        fact.put("correlatedClues", correlatedClues);
        fact.put("pendingVerification", pendingVerification);
        return Collections.unmodifiableMap(fact);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
