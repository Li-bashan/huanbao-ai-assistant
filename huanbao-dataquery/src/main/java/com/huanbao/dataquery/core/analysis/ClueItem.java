package com.huanbao.dataquery.core.analysis;

import java.math.BigDecimal;

/**
 * 相关线索，只表示同期指标的相关波动，不表示已经证明因果关系。
 */
public record ClueItem(
        String subject,
        String primaryMetric,
        String relatedMetric,
        String period,
        BigDecimal primaryChangePercent,
        BigDecimal relatedChangePercent,
        String relationship) {

    public ClueItem {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("subject must not be blank");
        }
        if (primaryMetric == null || primaryMetric.isBlank()) {
            throw new IllegalArgumentException("primaryMetric must not be blank");
        }
        if (relatedMetric == null || relatedMetric.isBlank()) {
            throw new IllegalArgumentException("relatedMetric must not be blank");
        }
        if (period == null || period.isBlank()) {
            throw new IllegalArgumentException("period must not be blank");
        }
        if (relationship == null || relationship.isBlank()) {
            throw new IllegalArgumentException("relationship must not be blank");
        }
    }

    public boolean isSameDirection() {
        return primaryChangePercent != null
                && relatedChangePercent != null
                && primaryChangePercent.signum() == relatedChangePercent.signum();
    }
}
