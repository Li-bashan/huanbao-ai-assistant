package com.huanbao.dataquery.router;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 总路由和槽位抽取结果。
 *
 * <p>字段值是受 {@link IntentClassifier} 约束后的有限集合，便于后续查询编排继续使用强类型对象。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalysisPlanDto(
        String primaryIntent,
        List<String> metricInputs,
        List<String> orgInputs,
        String timeExpression,
        String analysisType,
        List<String> subTasks) {

    public AnalysisPlanDto {
        primaryIntent = textOrEmpty(primaryIntent);
        metricInputs = copy(metricInputs);
        orgInputs = copy(orgInputs);
        timeExpression = textOrEmpty(timeExpression);
        analysisType = textOrEmpty(analysisType);
        subTasks = copy(subTasks);
    }

    private static List<String> copy(List<String> values) {
        return values == null ? List.of() : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList();
    }

    private static String textOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
