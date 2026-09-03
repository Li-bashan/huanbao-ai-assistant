package com.huanbao.dataquery.core.engine;

import com.huanbao.dataquery.core.spi.MetricDefinition;

import java.util.List;
import java.util.Objects;

/**
 * 指标拆解结果。SQL 层只需要 {@link #atomicMetrics()}，计算层使用原始公式和
 * 分子/分母依赖完成二次求值。
 */
public record MetricDecomposition(
        MetricDefinition requestedMetric,
        List<MetricDefinition> atomicMetrics,
        List<String> baseMetricRefs,
        List<String> numeratorBaseMetricRefs,
        List<String> denominatorBaseMetricRefs) {

    public MetricDecomposition {
        requestedMetric = Objects.requireNonNull(requestedMetric, "requestedMetric");
        atomicMetrics = List.copyOf(Objects.requireNonNull(atomicMetrics, "atomicMetrics"));
        baseMetricRefs = List.copyOf(Objects.requireNonNull(baseMetricRefs, "baseMetricRefs"));
        numeratorBaseMetricRefs = List.copyOf(
                Objects.requireNonNull(numeratorBaseMetricRefs, "numeratorBaseMetricRefs"));
        denominatorBaseMetricRefs = List.copyOf(
                Objects.requireNonNull(denominatorBaseMetricRefs, "denominatorBaseMetricRefs"));
    }

    public boolean isAtomic() {
        return "ATOMIC".equalsIgnoreCase(requestedMetric.metricType());
    }

    public List<String> atomicMetricCodes() {
        return atomicMetrics.stream().map(MetricDefinition::indicatorCode).toList();
    }
}
