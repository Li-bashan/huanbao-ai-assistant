package com.huanbao.dataquery.core.security;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AviatorEvaluatorInstanceTest {

    @Test
    void evaluatesHighPrecisionFormulaWithSafeDivide() {
        AviatorEvaluatorInstance evaluator = new AviatorEvaluatorInstance();

        BigDecimal actual = evaluator.evaluate(
                "safeDivide(online, total) * 100",
                Map.of("online", new BigDecimal("10"), "total", new BigDecimal("3")));

        assertEquals(
                new BigDecimal("10").divide(new BigDecimal("3"), new MathContext(16, RoundingMode.HALF_UP))
                        .multiply(new BigDecimal("100"), new MathContext(16, RoundingMode.HALF_UP)),
                actual);
        assertTrue(evaluator.isSafeModeEnabled());
    }

    @Test
    void safeDivideReturnsZeroForZeroDenominator() {
        AviatorEvaluatorInstance evaluator = new AviatorEvaluatorInstance();

        assertEquals(BigDecimal.ZERO, evaluator.evaluate("safeDivide(10, 0)", Map.of()));
        assertEquals(BigDecimal.ZERO, evaluator.safeDivide(10, null));
        assertEquals(BigDecimal.ZERO, evaluator.safeDivide(null, 10));
    }

    @Test
    void evaluatesNonTerminatingDivisionWithExplicitHalfUpMathContext() {
        AviatorEvaluatorInstance evaluator = new AviatorEvaluatorInstance();

        BigDecimal actual = evaluator.evaluate("100 / 3", Map.of());

        assertEquals(
                new BigDecimal("100").divide(new BigDecimal("3"), new MathContext(16, RoundingMode.HALF_UP)),
                actual);

        Map<String, Object> nullableArguments = new HashMap<>();
        nullableArguments.put("numerator", new BigDecimal("100"));
        nullableArguments.put("denominator", null);
        assertEquals(BigDecimal.ZERO,
                evaluator.evaluate("safeDivide(numerator, denominator)", nullableArguments));
    }

    @Test
    void rejectsNonMathematicalExpressionsAndNonNumericVariables() {
        AviatorEvaluatorInstance evaluator = new AviatorEvaluatorInstance();

        assertThrows(IllegalArgumentException.class,
                () -> evaluator.evaluate("println(1)", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> evaluator.evaluate("amount + name", Map.of("amount", 1, "name", "unsafe")));
    }
}
