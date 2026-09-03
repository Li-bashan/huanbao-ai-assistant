package com.huanbao.dataquery.core.analysis;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 纯内存、确定性的统计分析引擎。
 *
 * <p>这里的所有计算都使用 {@link BigDecimal}。LLM 只能负责解释调用方已经得到的结果，
 * 不参与数值计算、排序或异常判定。</p>
 */
public final class AnalysisEngine {

    public static final int RESULT_SCALE = 2;
    public static final BigDecimal DEFAULT_ANOMALY_THRESHOLD_PERCENT = new BigDecimal("20.00");

    private static final int CALCULATION_SCALE = 16;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal P25 = new BigDecimal("0.25");
    private static final BigDecimal P50 = new BigDecimal("0.50");
    private static final BigDecimal P75 = new BigDecimal("0.75");

    /**
     * 计算同比百分数：{@code (本期 - 上年同期) / 上年同期 * 100}。
     * 基期为空或为 0 时返回 null，表示不可比。
     */
    public BigDecimal calculateYoY(BigDecimal currentValue, BigDecimal samePeriodLastYear) {
        return calculateChangeRate(currentValue, samePeriodLastYear);
    }

    /** 计算环比百分数：{@code (本期 - 上期) / 上期 * 100}。 */
    public BigDecimal calculateMoM(BigDecimal currentValue, BigDecimal previousMonthValue) {
        return calculateChangeRate(currentValue, previousMonthValue);
    }

    /** 同比的简短别名，便于分析编排代码直接表达业务语义。 */
    public BigDecimal yoy(BigDecimal currentValue, BigDecimal samePeriodLastYear) {
        return calculateYoY(currentValue, samePeriodLastYear);
    }

    /** 环比的简短别名，便于分析编排代码直接表达业务语义。 */
    public BigDecimal mom(BigDecimal currentValue, BigDecimal previousMonthValue) {
        return calculateMoM(currentValue, previousMonthValue);
    }

    /**
     * 计算百分数变化率，结果固定保留两位小数并使用 HALF_UP。
     */
    public BigDecimal calculateChangeRate(BigDecimal currentValue, BigDecimal baseValue) {
        BigDecimal preciseRate = calculateChangeRatePrecise(currentValue, baseValue);
        return preciseRate == null
                ? null
                : preciseRate.setScale(RESULT_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateChangeRatePrecise(BigDecimal currentValue, BigDecimal baseValue) {
        if (currentValue == null || baseValue == null || baseValue.signum() == 0) {
            return null;
        }
        return currentValue
                .subtract(baseValue)
                .divide(baseValue, CALCULATION_SCALE, RoundingMode.HALF_UP)
                .multiply(HUNDRED);
    }

    /**
     * 对当前值做降序排名。同值并列，采用竞赛排名，例如 1、1、3；公司名作为稳定的二级排序键。
     * 当前值为空的公司不进入排名。
     */
    public RankingResult rank(Map<String, ?> currentValues) {
        return rank(currentValues, Map.of());
    }

    /**
     * 对当前值排名，并与上期排名对比。排名变化为 {@code 本期排名 - 上期排名}，
     * 正数表示名次下降，负数表示名次上升。
     */
    public RankingResult rank(Map<String, ?> currentValues, Map<String, ?> previousValues) {
        Map<String, BigDecimal> current = numericCompanyValues(currentValues);
        Map<String, Integer> previousRanks = buildRanks(numericCompanyValues(previousValues));
        List<Map.Entry<String, BigDecimal>> sorted = new ArrayList<>(current.entrySet());
        sorted.sort(valueDescendingThenCompany());

        List<RankingItem> result = new ArrayList<>();
        BigDecimal lastValue = null;
        int lastRank = 0;
        for (int index = 0; index < sorted.size(); index++) {
            Map.Entry<String, BigDecimal> entry = sorted.get(index);
            if (lastValue == null || entry.getValue().compareTo(lastValue) != 0) {
                lastRank = index + 1;
                lastValue = entry.getValue();
            }
            Integer previousRank = previousRanks.get(entry.getKey());
            Integer rankChange = previousRank == null ? null : lastRank - previousRank;
            result.add(new RankingItem(
                    entry.getKey(),
                    entry.getValue(),
                    lastRank,
                    previousRank,
                    rankChange));
        }
        return new RankingResult(result);
    }

    /** 显式命名的排名方法，和 {@link #rank(Map, Map)} 结果相同。 */
    public RankingResult calculateRanking(
            Map<String, ?> currentValues,
            Map<String, ?> previousValues) {
        return rank(currentValues, previousValues);
    }

    /**
     * 计算公司分布。分位数采用线性插值法（R-7）：位置为 {@code (n - 1) * p}，
     * 最终 P25、中位数和 P75 固定保留两位小数。空指标不参与分布。
     */
    public DistributionResult distribution(Map<String, ?> companyValues) {
        Map<String, BigDecimal> numericValues = numericCompanyValues(companyValues);
        List<BigDecimal> sortedValues = numericValues.values().stream()
                .sorted()
                .toList();
        if (sortedValues.isEmpty()) {
            return new DistributionResult(null, null, null, List.of());
        }

        BigDecimal minimum = sortedValues.get(0);
        BigDecimal maximum = sortedValues.get(sortedValues.size() - 1);
        List<DistributionItem> items = numericValues.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new DistributionItem(
                        entry.getKey(),
                        entry.getValue(),
                        entry.getValue().compareTo(maximum) == 0,
                        entry.getValue().compareTo(minimum) == 0))
                .toList();
        return new DistributionResult(
                percentile(sortedValues, P25),
                percentile(sortedValues, P50),
                percentile(sortedValues, P75),
                items);
    }

    /** 显式命名的分布方法，和 {@link #distribution(Map)} 结果相同。 */
    public DistributionResult calculateDistribution(Map<String, ?> companyValues) {
        return distribution(companyValues);
    }

    /**
     * 检测按时间升序排列的序列，序列最后一个值视为本期值。
     * 规则一：本期相对历史均值或最近一期的变化率绝对值超过阈值；
     * 规则二：末尾连续三个时间间隔严格下滑。任一规则命中即异常。
     */
    public AnomalyResult detectAnomaly(List<BigDecimal> orderedValues) {
        return detectAnomaly(orderedValues, DEFAULT_ANOMALY_THRESHOLD_PERCENT);
    }

    public AnomalyResult detectAnomaly(
            List<BigDecimal> orderedValues,
            BigDecimal thresholdPercent) {
        Objects.requireNonNull(orderedValues, "orderedValues");
        validateThreshold(thresholdPercent);

        BigDecimal currentValue = last(orderedValues);
        BigDecimal previousValue = orderedValues.size() < 2
                ? null
                : orderedValues.get(orderedValues.size() - 2);
        List<BigDecimal> history = orderedValues.size() < 2
                ? List.of()
                : orderedValues.subList(0, orderedValues.size() - 1).stream()
                        .filter(Objects::nonNull)
                        .toList();
        BigDecimal preciseHistoricalMean = average(history);
        BigDecimal historicalMean = roundResult(preciseHistoricalMean);
        BigDecimal preciseDeviation = calculateChangeRatePrecise(currentValue, preciseHistoricalMean);
        BigDecimal preciseLatestChange = calculateChangeRatePrecise(currentValue, previousValue);
        BigDecimal deviation = roundResult(preciseDeviation);
        BigDecimal latestChange = roundResult(preciseLatestChange);
        boolean deviationExceeded = exceedsThreshold(preciseDeviation, thresholdPercent);
        boolean latestChangeExceeded = exceedsThreshold(preciseLatestChange, thresholdPercent);
        boolean consecutiveDecline = hasThreeConsecutiveDeclines(orderedValues);

        List<String> reasons = new ArrayList<>();
        if (deviationExceeded) {
            reasons.add("本期值偏离历史均值超过阈值");
        }
        if (latestChangeExceeded) {
            reasons.add("最近一期变动率绝对值超过阈值");
        }
        if (consecutiveDecline) {
            reasons.add("连续3期下滑");
        }
        return new AnomalyResult(
                deviationExceeded || latestChangeExceeded || consecutiveDecline,
                historicalMean,
                deviation,
                latestChange,
                deviationExceeded || latestChangeExceeded,
                consecutiveDecline,
                reasons);
    }

    /**
     * 当调用方把历史值和本期值分开提供时使用的重载，内部仍按时间顺序执行同一套算法。
     */
    public AnomalyResult detectAnomaly(
            List<BigDecimal> historicalValues,
            BigDecimal currentValue,
            BigDecimal thresholdPercent) {
        Objects.requireNonNull(historicalValues, "historicalValues");
        List<BigDecimal> orderedValues = new ArrayList<>(historicalValues);
        orderedValues.add(currentValue);
        return detectAnomaly(orderedValues, thresholdPercent);
    }

    public boolean isAnomalous(List<BigDecimal> orderedValues, BigDecimal thresholdPercent) {
        return detectAnomaly(orderedValues, thresholdPercent).anomalous();
    }

    private static Map<String, BigDecimal> numericCompanyValues(Map<String, ?> values) {
        Objects.requireNonNull(values, "values");
        Map<String, BigDecimal> result = new HashMap<>();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            BigDecimal value = toBigDecimal(entry.getValue());
            if (value != null) {
                result.put(entry.getKey(), value);
            }
        }
        return result;
    }

    private static Map<String, Integer> buildRanks(Map<String, BigDecimal> values) {
        List<Map.Entry<String, BigDecimal>> sorted = new ArrayList<>(values.entrySet());
        sorted.sort(valueDescendingThenCompany());
        Map<String, Integer> ranks = new HashMap<>();
        BigDecimal lastValue = null;
        int lastRank = 0;
        for (int index = 0; index < sorted.size(); index++) {
            Map.Entry<String, BigDecimal> entry = sorted.get(index);
            if (lastValue == null || entry.getValue().compareTo(lastValue) != 0) {
                lastRank = index + 1;
                lastValue = entry.getValue();
            }
            ranks.put(entry.getKey(), lastRank);
        }
        return ranks;
    }

    private static Comparator<Map.Entry<String, BigDecimal>> valueDescendingThenCompany() {
        return (left, right) -> {
            int valueComparison = right.getValue().compareTo(left.getValue());
            return valueComparison != 0
                    ? valueComparison
                    : left.getKey().compareTo(right.getKey());
        };
    }

    private static BigDecimal percentile(List<BigDecimal> sortedValues, BigDecimal percentile) {
        if (sortedValues.size() == 1) {
            return sortedValues.get(0).setScale(RESULT_SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal position = new BigDecimal(sortedValues.size() - 1).multiply(percentile);
        int lowerIndex = position.intValue();
        int upperIndex = Math.min(lowerIndex + 1, sortedValues.size() - 1);
        BigDecimal fraction = position.subtract(new BigDecimal(lowerIndex));
        BigDecimal interpolated = sortedValues.get(lowerIndex).add(
                sortedValues.get(upperIndex)
                        .subtract(sortedValues.get(lowerIndex))
                        .multiply(fraction));
        return interpolated.setScale(RESULT_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal average(List<BigDecimal> values) {
        if (values.isEmpty()) {
            return null;
        }
        BigDecimal total = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return total.divide(new BigDecimal(values.size()), CALCULATION_SCALE, RoundingMode.HALF_UP);
    }

    private static boolean hasThreeConsecutiveDeclines(List<BigDecimal> orderedValues) {
        if (orderedValues.size() < 4) {
            return false;
        }
        for (int index = orderedValues.size() - 1; index >= orderedValues.size() - 3; index--) {
            BigDecimal later = orderedValues.get(index);
            BigDecimal earlier = orderedValues.get(index - 1);
            if (later == null || earlier == null || later.compareTo(earlier) >= 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean exceedsThreshold(BigDecimal value, BigDecimal thresholdPercent) {
        return value != null && value.abs().compareTo(thresholdPercent.abs()) > 0;
    }

    private static BigDecimal roundResult(BigDecimal value) {
        return value == null ? null : value.setScale(RESULT_SCALE, RoundingMode.HALF_UP);
    }

    private static void validateThreshold(BigDecimal thresholdPercent) {
        Objects.requireNonNull(thresholdPercent, "thresholdPercent");
        if (thresholdPercent.signum() < 0) {
            throw new IllegalArgumentException("thresholdPercent must not be negative");
        }
    }

    private static BigDecimal last(List<BigDecimal> values) {
        return values.isEmpty() ? null : values.get(values.size() - 1);
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return BigDecimal.valueOf(((Number) value).longValue());
        }
        if (value instanceof Float || value instanceof Double) {
            double number = ((Number) value).doubleValue();
            return Double.isFinite(number) ? BigDecimal.valueOf(number) : null;
        }
        if (value instanceof Number number) {
            try {
                return new BigDecimal(number.toString());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (value instanceof CharSequence text) {
            String normalized = text.toString().trim();
            if (normalized.isEmpty()) {
                return null;
            }
            try {
                return new BigDecimal(normalized);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
