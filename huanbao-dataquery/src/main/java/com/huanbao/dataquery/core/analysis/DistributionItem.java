package com.huanbao.dataquery.core.analysis;

import java.math.BigDecimal;

/** 带有极值标记的公司指标值。 */
public record DistributionItem(
        String company,
        BigDecimal value,
        boolean maximum,
        boolean minimum) {

    public boolean isMaximum() {
        return maximum;
    }

    public boolean isMinimum() {
        return minimum;
    }
}
