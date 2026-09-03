package com.huanbao.dataquery.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;

/** Presentation Model v2 的核心指标卡。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record MetricCardDto(
        String label,
        Object value,
        String unit,
        BigDecimal yearOverYearPercent,
        BigDecimal monthOverMonthPercent,
        String yearOverYearLabel,
        String monthOverMonthLabel) {

    public MetricCardDto(String label, Object value, String unit) {
        this(label, value, unit, null, null, "", "");
    }

    public MetricCardDto(
            String label,
            Object value,
            String unit,
            BigDecimal yearOverYearPercent,
            BigDecimal monthOverMonthPercent) {
        this(label, value, unit, yearOverYearPercent, monthOverMonthPercent,
                percentLabel("同比", yearOverYearPercent),
                percentLabel("环比", monthOverMonthPercent));
    }

    public MetricCardDto {
        label = text(label);
        unit = unit == null ? "" : unit.trim();
        yearOverYearLabel = yearOverYearLabel == null ? "" : yearOverYearLabel.trim();
        monthOverMonthLabel = monthOverMonthLabel == null ? "" : monthOverMonthLabel.trim();
    }

    public String yoyLabel() {
        return yearOverYearLabel;
    }

    public String momLabel() {
        return monthOverMonthLabel;
    }

    private static String percentLabel(String prefix, BigDecimal value) {
        if (value == null) {
            return "";
        }
        return prefix + (value.signum() > 0 ? " +" : " ") + value.stripTrailingZeros().toPlainString() + "%";
    }

    private static String text(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("label must not be blank");
        }
        return value.trim();
    }
}
