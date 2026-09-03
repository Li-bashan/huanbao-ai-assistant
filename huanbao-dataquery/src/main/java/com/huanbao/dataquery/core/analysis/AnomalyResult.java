package com.huanbao.dataquery.core.analysis;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** 确定性异常检测结果及触发规则。 */
public record AnomalyResult(
        boolean anomalous,
        BigDecimal historicalMean,
        BigDecimal deviationFromMeanPercent,
        BigDecimal latestChangeRatePercent,
        boolean thresholdExceeded,
        boolean consecutiveThreePeriodDecline,
        List<String> reasons) {

    public AnomalyResult {
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
    }
}
