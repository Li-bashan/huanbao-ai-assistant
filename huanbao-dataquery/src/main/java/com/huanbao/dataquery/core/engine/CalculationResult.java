package com.huanbao.dataquery.core.engine;

import java.math.BigDecimal;

/** 计算结果及面向用户的降级展示文本。 */
public record CalculationResult(
        BigDecimal value,
        String displayValue,
        String qualityFlag) {

    public static final String UNCOMPARABLE_DISPLAY = "--（停运不可比）";
    public static final String ZERO_DENOMINATOR = "ZERO_DENOMINATOR";
    public static final String NO_DATA = "NO_DATA";

    public boolean isComparable() {
        return value != null;
    }
}
