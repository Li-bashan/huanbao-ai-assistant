package com.huanbao.dataquery.pipeline;

import com.huanbao.dataquery.core.spi.MetricDefinition;
import com.huanbao.dataquery.router.AnalysisPlanDto;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 后端结果的最小数值语义，防止展示层重新猜测单位或时间口径。
 */
public record DataValueSemantics(
        String valueType,
        String timeSemantics,
        String requestedMeasure,
        String displayUnit,
        String aggregationRule) {

    public static final String PERIOD_INCREMENT = "PERIOD_INCREMENT";
    public static final String PERIOD_VALUE = "PERIOD_VALUE";
    public static final String YTD = "YTD";
    public static final String ANNUAL_SUM = "ANNUAL_SUM";

    public DataValueSemantics {
        valueType = text(valueType);
        timeSemantics = text(timeSemantics);
        requestedMeasure = text(requestedMeasure);
        displayUnit = text(displayUnit);
        aggregationRule = text(aggregationRule);
    }

    public static DataValueSemantics forPlan(
            MetricDefinition metric,
            AnalysisPlanDto plan,
            boolean annualExpression,
            boolean yearToDate) {
        boolean annualSum = annualExpression
                && !yearToDate
                && "FACT".equalsIgnoreCase(plan.analysisType())
                && !metric.isNonAdditive();
        String timeSemantics = yearToDate
                ? YTD
                : metric.isNonAdditive() ? PERIOD_VALUE : PERIOD_INCREMENT;
        String requestedMeasure = yearToDate
                ? YTD
                : annualSum ? ANNUAL_SUM : PERIOD_VALUE;
        return new DataValueSemantics(
                metric.isNonAdditive() ? "RATIO" : "FLOW",
                timeSemantics,
                requestedMeasure,
                metric.unit(),
                metric.aggregationRule());
    }

    public static DataValueSemantics unknown() {
        return new DataValueSemantics("", "", "", "", "");
    }

    public Map<String, Object> asMap() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("valueType", valueType);
        values.put("timeSemantics", timeSemantics);
        values.put("requestedMeasure", requestedMeasure);
        values.put("displayUnit", displayUnit);
        values.put("aggregationRule", aggregationRule);
        return Map.copyOf(values);
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
