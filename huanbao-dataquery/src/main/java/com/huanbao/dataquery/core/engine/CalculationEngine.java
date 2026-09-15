package com.huanbao.dataquery.core.engine;

import com.huanbao.dataquery.core.security.AviatorEvaluatorInstance;
import com.huanbao.dataquery.core.spi.DomainSemanticProvider;
import com.huanbao.dataquery.core.spi.DomainSemanticProviderRegistry;
import com.huanbao.dataquery.core.spi.MetricDefinition;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 派生指标二次计算引擎。
 *
 * <p>跨月和跨组织场景只接收 SQL 已经汇总好的原子指标，再执行字典里的 Aviator
 * 公式。这里没有 AVG 路径，派生指标永远不会做算术平均。</p>
 */
public final class CalculationEngine {

    private static final Pattern PARAM_VARIABLE =
            Pattern.compile("\\bparam_([A-Za-z0-9_]+)\\b");

    private final MetricDecomposer decomposer;
    private final AviatorEvaluatorInstance evaluator;

    public CalculationEngine(DomainSemanticProvider provider) {
        this(new MetricDecomposer(provider), new AviatorEvaluatorInstance());
    }

    public CalculationEngine(DomainSemanticProviderRegistry registry, String domainCode) {
        this(new MetricDecomposer(registry, domainCode), new AviatorEvaluatorInstance());
    }

    public CalculationEngine(MetricDecomposer decomposer) {
        this(decomposer, new AviatorEvaluatorInstance());
    }

    public CalculationEngine(MetricDecomposer decomposer, AviatorEvaluatorInstance evaluator) {
        this.decomposer = Objects.requireNonNull(decomposer, "decomposer");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
    }

    /**
     * 计算指标值。分母缺失、空值或为 0 时返回 null，而不是返回一个有误导性的 0。
     */
    public BigDecimal calculate(String metricNameOrAlias, Map<String, ?> aggregatedValues) {
        Objects.requireNonNull(aggregatedValues, "aggregatedValues");
        MetricDecomposition decomposition = decomposer.decompose(metricNameOrAlias);
        MetricDefinition metric = decomposition.requestedMetric();

        if (decomposition.isAtomic()) {
            BigDecimal rawValue = readNumber(aggregatedValues, metric.indicatorCode());
            if (rawValue == null) {
                return null;
            }
            // 原子指标也必须执行语义字典里的公式。例如 1201 的实库值按吨存储，
            // 展示口径是 base_1201 / 10000 万吨；不能用指标编码写特例绕过字典。
            return evaluator.evaluate(metric.formulaText(), Map.of(
                    "base_" + metric.indicatorCode(), rawValue));
        }

        Map<String, Object> variables = new LinkedHashMap<>();
        for (MetricDefinition atomicMetric : decomposition.atomicMetrics()) {
            BigDecimal value = readNumber(aggregatedValues, atomicMetric.indicatorCode());
            variables.put("base_" + atomicMetric.indicatorCode(), value == null ? BigDecimal.ZERO : value);
        }
        for (String parameterRef : metric.paramRefs()) {
            BigDecimal value = readParameter(aggregatedValues, parameterRef);
            variables.put("param_" + parameterRef, value == null ? BigDecimal.ZERO : value);
        }
        addContextVariables(metric, aggregatedValues, variables);

        if (hasZeroDenominator(decomposition, aggregatedValues, variables)) {
            return null;
        }

        return evaluator.evaluate(metric.formulaText(), variables);
    }

    public BigDecimal calculate(MetricDefinition metric, Map<String, ?> aggregatedValues) {
        Objects.requireNonNull(metric, "metric");
        return calculate(metric.indicatorCode(), aggregatedValues);
    }

    public CalculationResult calculateResult(String metricNameOrAlias, Map<String, ?> aggregatedValues) {
        BigDecimal value = calculate(metricNameOrAlias, aggregatedValues);
        if (value == null) {
            return new CalculationResult(
                    null,
                    CalculationResult.UNCOMPARABLE_DISPLAY,
                    CalculationResult.ZERO_DENOMINATOR);
        }
        return new CalculationResult(value, format(value), null);
    }

    public String display(String metricNameOrAlias, Map<String, ?> aggregatedValues) {
        return calculateResult(metricNameOrAlias, aggregatedValues).displayValue();
    }

    private boolean hasZeroDenominator(
            MetricDecomposition decomposition,
            Map<String, ?> aggregatedValues,
            Map<String, Object> variables) {
        String guard = zeroDenominatorGuard(decomposition.requestedMetric().formulaText());
        if (guard != null && evaluator.evaluateCondition(guard, variables)) {
            return true;
        }

        // 对没有显式三元保护的扩展指标，仍然检查拆解出的直接分母依赖。
        for (String baseMetricRef : decomposition.denominatorBaseMetricRefs()) {
            BigDecimal value = readNumber(aggregatedValues, baseMetricRef);
            if (value == null || value.signum() == 0) {
                return true;
            }
        }

        String expression = trueBranch(decomposition.requestedMetric().formulaText());
        int divisionIndex = findTopLevelDivision(expression);
        if (divisionIndex < 0) {
            return false;
        }
        Matcher matcher = PARAM_VARIABLE.matcher(expression.substring(divisionIndex + 1));
        while (matcher.find()) {
            String parameterRef = matcher.group(1);
            // 折标系数为 0 是字典明确允许的 fallback，不是停运不可比。
            if ("standard_coeff".equals(parameterRef)) {
                continue;
            }
            BigDecimal value = readParameter(aggregatedValues, parameterRef);
            if (value == null || value.signum() == 0) {
                return true;
            }
        }
        return false;
    }

    private static String zeroDenominatorGuard(String formula) {
        int questionIndex = findTopLevelCharacter(formula, '?', 0);
        return questionIndex < 0 ? null : formula.substring(0, questionIndex).trim();
    }

    private static void addContextVariables(
            MetricDefinition metric,
            Map<String, ?> aggregatedValues,
            Map<String, Object> variables) {
        Matcher matcher = Pattern.compile("\\bcontext_[A-Za-z0-9_]+\\b")
                .matcher(metric.formulaText());
        while (matcher.find()) {
            String variableName = matcher.group();
            BigDecimal value = readByKeys(aggregatedValues, Set.of(variableName));
            variables.put(variableName, value == null ? BigDecimal.ZERO : value);
        }
    }

    private static BigDecimal readParameter(Map<String, ?> values, String parameterRef) {
        Set<String> keys = switch (parameterRef) {
            case "incinerator_count" -> Set.of("param_incinerator_count", "incinerator_count", "furnace_count");
            default -> Set.of("param_" + parameterRef, parameterRef);
        };
        return readByKeys(values, keys);
    }

    private static BigDecimal readNumber(Map<String, ?> values, String metricCode) {
        return readByKeys(values, Set.of(
                "val_" + metricCode,
                "base_" + metricCode,
                metricCode));
    }

    private static BigDecimal readByKeys(Map<String, ?> values, Set<String> keys) {
        for (String key : keys) {
            if (values.containsKey(key)) {
                return toBigDecimal(values.get(key));
            }
        }
        return null;
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
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
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof BigInteger bigInteger) {
            return new BigDecimal(bigInteger);
        }
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return BigDecimal.valueOf(((Number) value).longValue());
        }
        if (value instanceof Float || value instanceof Double) {
            double doubleValue = ((Number) value).doubleValue();
            return Double.isFinite(doubleValue) ? BigDecimal.valueOf(doubleValue) : null;
        }
        if (value instanceof Number number) {
            try {
                return new BigDecimal(number.toString());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String format(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String trueBranch(String formula) {
        int questionIndex = findTopLevelCharacter(formula, '?', 0);
        if (questionIndex < 0) {
            return formula;
        }
        int colonIndex = findTopLevelCharacter(formula, ':', questionIndex + 1);
        if (colonIndex < 0) {
            return formula.substring(questionIndex + 1).trim();
        }
        return formula.substring(questionIndex + 1, colonIndex).trim();
    }

    private static int findTopLevelDivision(String expression) {
        for (int index = 0; index < expression.length(); index++) {
            if (expression.charAt(index) == '/') {
                return index;
            }
        }
        return -1;
    }

    private static int findTopLevelCharacter(String expression, char target, int startIndex) {
        int depth = 0;
        for (int index = startIndex; index < expression.length(); index++) {
            char current = expression.charAt(index);
            if (current == '(') {
                depth++;
            } else if (current == ')') {
                depth--;
            } else if (current == target && depth == 0) {
                return index;
            }
        }
        return -1;
    }
}
