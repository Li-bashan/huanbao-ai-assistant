package com.huanbao.dataquery.protocol;

import java.math.BigDecimal;

/**
 * 三层证据模型在 v2 中的扁平可渲染表示。layer 只取 confirmedFacts、
 * correlatedClues 或 pendingVerification。
 */
public record EvidenceV2Dto(
        String layer,
        String type,
        String text,
        String subject,
        String metric,
        String relatedMetric,
        String period,
        BigDecimal currentValue,
        BigDecimal yearOverYearPercent,
        BigDecimal monthOverMonthPercent,
        Integer rank,
        Integer previousRank,
        Integer rankChange,
        String relationship) {

    public EvidenceV2Dto {
        layer = text(layer, "layer");
        type = text(type, "type");
        text = text(text, "text");
        subject = optional(subject);
        metric = optional(metric);
        relatedMetric = optional(relatedMetric);
        period = optional(period);
        relationship = optional(relationship);
    }

    public static EvidenceV2Dto confirmed(
            String text,
            String subject,
            String metric,
            String period,
            BigDecimal currentValue,
            BigDecimal yearOverYearPercent,
            BigDecimal monthOverMonthPercent,
            Integer rank,
            Integer previousRank,
            Integer rankChange) {
        return new EvidenceV2Dto(
                "confirmedFacts", "confirmed_fact", text, subject, metric, "", period,
                currentValue, yearOverYearPercent, monthOverMonthPercent,
                rank, previousRank, rankChange, "");
    }

    public static EvidenceV2Dto clue(
            String text,
            String subject,
            String metric,
            String relatedMetric,
            String period,
            BigDecimal yearOverYearPercent,
            BigDecimal monthOverMonthPercent,
            String relationship) {
        return new EvidenceV2Dto(
                "correlatedClues", "correlated_clue", text, subject, metric, relatedMetric, period,
                null, yearOverYearPercent, monthOverMonthPercent,
                null, null, null, relationship);
    }

    public static EvidenceV2Dto pending(String text) {
        return new EvidenceV2Dto(
                "pendingVerification", "pending_verification", text, "", "", "", "",
                null, null, null, null, null, null, "");
    }

    private static String text(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static String optional(String value) {
        return value == null ? "" : value.trim();
    }
}
