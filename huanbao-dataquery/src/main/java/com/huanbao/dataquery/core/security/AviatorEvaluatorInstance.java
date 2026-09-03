package com.huanbao.dataquery.core.security;

import com.googlecode.aviator.AviatorEvaluator;
import com.googlecode.aviator.Feature;
import com.googlecode.aviator.Options;
import com.googlecode.aviator.runtime.function.AbstractFunction;
import com.googlecode.aviator.runtime.type.AviatorDecimal;
import com.googlecode.aviator.runtime.type.AviatorObject;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 面向指标公式的 Aviator 安全封装。
 *
 * <p>Aviator 5.4.1 没有公开名为 SAFE_MODE 的选项，因此这里同时做引擎特性收口和表达式词法白名单：
 * 关闭可选语言特性，只允许数值、变量、四则运算、取模、幂、括号以及 safeDivide。</p>
 */
public final class AviatorEvaluatorInstance {

    private static final MathContext MATH_CONTEXT = new MathContext(16, RoundingMode.HALF_UP);
    private static final Pattern SAFE_MATH_EXPRESSION =
            Pattern.compile("[0-9A-Za-z_\\s+\\-*/%().,?:=!&|]+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern FUNCTION_CALL =
            Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");
    private static final String SAFE_DIVIDE = "safeDivide";

    private final com.googlecode.aviator.AviatorEvaluatorInstance delegate;
    private final boolean safeModeEnabled;

    public AviatorEvaluatorInstance() {
        this.delegate = AviatorEvaluator.newInstance();
        this.delegate.setOption(Options.FEATURE_SET, Set.<Feature>of());
        this.delegate.setOption(Options.MATH_CONTEXT, MATH_CONTEXT);
        this.delegate.setOption(Options.ALWAYS_PARSE_FLOATING_POINT_NUMBER_INTO_DECIMAL, true);
        this.delegate.setOption(Options.ALWAYS_PARSE_INTEGRAL_NUMBER_INTO_DECIMAL, true);
        this.delegate.addFunction(new SafeDivideFunction());
        this.safeModeEnabled = true;
    }

    public boolean isSafeModeEnabled() {
        return safeModeEnabled;
    }

    public BigDecimal evaluate(String expression, Map<String, ?> variables) {
        validateExpression(expression);
        Objects.requireNonNull(variables, "variables");

        Map<String, Object> numericEnvironment = numericEnvironment(variables);
        Object result = delegate.execute(expression, numericEnvironment);
        return toBigDecimal(result);
    }

    /** 只用于判断字典公式里的零分母保护条件。 */
    public boolean evaluateCondition(String expression, Map<String, ?> variables) {
        validateExpression(expression);
        Objects.requireNonNull(variables, "variables");
        Object result = delegate.execute(expression, numericEnvironment(variables));
        if (!(result instanceof Boolean booleanResult)) {
            throw new IllegalArgumentException("Expression is not a boolean condition");
        }
        return booleanResult;
    }

    public BigDecimal safeDivide(Number dividend, Number divisor) {
        try {
            BigDecimal left = toBigDecimal(dividend);
            BigDecimal right = toBigDecimal(divisor);
            return left == null || right == null || right.signum() == 0
                    ? BigDecimal.ZERO
                    : left.divide(right, MATH_CONTEXT);
        } catch (RuntimeException ignored) {
            return BigDecimal.ZERO;
        }
    }

    private static void validateExpression(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("Expression must not be blank");
        }
        String normalized = expression.trim();
        if (!SAFE_MATH_EXPRESSION.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Only safe mathematical expressions are allowed");
        }
        Matcher matcher = FUNCTION_CALL.matcher(normalized);
        while (matcher.find()) {
            if (!SAFE_DIVIDE.equals(matcher.group(1))) {
                throw new IllegalArgumentException("Function is not allowed in safe mode: " + matcher.group(1));
            }
        }
    }

    private static Map<String, Object> numericEnvironment(Map<String, ?> variables) {
        Map<String, Object> environment = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : variables.entrySet()) {
            environment.put(entry.getKey(), entry.getValue() == null
                    ? null
                    : toBigDecimal(entry.getValue()));
        }
        return Collections.unmodifiableMap(environment);
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("Only numeric values are allowed in safe formulas");
        }
        if (number instanceof BigDecimal decimal) {
            return decimal;
        }
        if (number instanceof java.math.BigInteger bigInteger) {
            return new BigDecimal(bigInteger);
        }
        if (number instanceof Byte || number instanceof Short
                || number instanceof Integer || number instanceof Long) {
            return BigDecimal.valueOf(number.longValue());
        }
        if (number instanceof Float || number instanceof Double) {
            double valueAsDouble = number.doubleValue();
            if (!Double.isFinite(valueAsDouble)) {
                throw new IllegalArgumentException("Non-finite numeric values are not allowed");
            }
            return BigDecimal.valueOf(valueAsDouble);
        }
        return new BigDecimal(number.toString());
    }

    private static final class SafeDivideFunction extends AbstractFunction {

        @Override
        public String getName() {
            return SAFE_DIVIDE;
        }

        @Override
        public AviatorObject call(Map<String, Object> env, AviatorObject dividend, AviatorObject divisor) {
            try {
                Object leftValue = dividend == null ? null : dividend.getValue(env);
                Object rightValue = divisor == null ? null : divisor.getValue(env);
                BigDecimal left = leftValue == null ? null : toBigDecimal(leftValue);
                BigDecimal right = rightValue == null ? null : toBigDecimal(rightValue);
                BigDecimal result = left == null || right == null || right.signum() == 0
                        ? BigDecimal.ZERO
                        : left.divide(right, MATH_CONTEXT);
                return AviatorDecimal.valueOf(result);
            } catch (RuntimeException ignored) {
                return AviatorDecimal.valueOf(BigDecimal.ZERO);
            }
        }
    }
}
