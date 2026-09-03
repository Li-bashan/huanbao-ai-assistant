package com.huanbao.dataquery.core.analysis;

import java.math.BigDecimal;

/**
 * 已确认事实，只承载数据库直接计算出的值和确定性统计结果。
 *
 * <p>该模型没有因果描述字段，因而因果推测只能进入相关线索或待核实层。</p>
 */
public record FactItem(
        String subject,
        String metric,
        String period,
        BigDecimal currentValue,
        BigDecimal yearOverYearPercent,
        BigDecimal monthOverMonthPercent,
        Integer rank,
        Integer previousRank,
        Integer rankChange) {

    public FactItem {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("subject must not be blank");
        }
        if (metric == null || metric.isBlank()) {
            throw new IllegalArgumentException("metric must not be blank");
        }
        if (period == null || period.isBlank()) {
            throw new IllegalArgumentException("period must not be blank");
        }
    }

    public static FactItem metric(
            String subject,
            String metric,
            String period,
            BigDecimal currentValue,
            BigDecimal yearOverYearPercent,
            BigDecimal monthOverMonthPercent) {
        return new FactItem(
                subject,
                metric,
                period,
                currentValue,
                yearOverYearPercent,
                monthOverMonthPercent,
                null,
                null,
                null);
    }

    public static FactItem ranking(
            String subject,
            String metric,
            String period,
            BigDecimal currentValue,
            int rank,
            Integer previousRank,
            Integer rankChange) {
        return new FactItem(
                subject,
                metric,
                period,
                currentValue,
                null,
                null,
                rank,
                previousRank,
                rankChange);
    }
}
