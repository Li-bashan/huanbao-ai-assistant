package com.huanbao.dataquery.core.engine;

import com.huanbao.dataquery.domain.ops.OpsDomainSemanticProvider;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CalculationEngineTest {

    private final CalculationEngine engine = new CalculationEngine(new OpsDomainSemanticProvider());

    @Test
    void appliesDictionaryFormulaToAtomicInboundWasteMetric() {
        BigDecimal result = engine.calculate("生活垃圾入厂量", Map.of(
                "val_1201", new BigDecimal("21818500")));

        assertEquals(0, new BigDecimal("2181.85").compareTo(result));
    }

    @Test
    void calculatesTonInboundGenerationByTotalsNotArithmeticAverage() {
        // 两个项目分别为 100000 和 33333.33 度/吨；正确加权值是总发电量/总入厂量。
        BigDecimal result = engine.calculate("吨入厂发电量", Map.of(
                "val_1001", new BigDecimal("400"),
                "val_1201", new BigDecimal("100")));

        assertEquals(0, new BigDecimal("40000").compareTo(result));
    }

    @Test
    void calculatesGenerationLoadRateFromAggregatedBaseMetricsAndParameters() {
        BigDecimal result = engine.calculate("发电负荷率", Map.of(
                "val_1001", new BigDecimal("3000"),
                "val_1707", new BigDecimal("2000"),
                "incinerator_count", new BigDecimal("2"),
                "installed_capacity", new BigDecimal("1000")));

        assertEquals(0, new BigDecimal("0.003").compareTo(result));
    }

    @Test
    void dirtyNumericRowsAreConvertedToZeroByTheSameRegexContract() {
        Pattern numeric = Pattern.compile(SafeMetricSqlBuilder.NUMERIC_TEXT_REGEX);
        List<String> mockZbzRows = List.of("100.00", "", "N/A", "  ", "-2.50");

        BigDecimal sum = mockZbzRows.stream()
                .filter(value -> numeric.matcher(value).matches())
                .map(BigDecimal::new)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertEquals(new BigDecimal("97.50"), sum);
    }

    @Test
    void returnsComparableDisplayForValidValueAndUncomparableDisplayForZeroDenominator() {
        assertEquals("40000", engine.display("吨入厂发电量", Map.of(
                "val_1001", new BigDecimal("400"),
                "val_1201", new BigDecimal("100"))));
        assertNull(engine.calculate("吨入厂发电量", Map.of(
                "val_1001", new BigDecimal("400"),
                "val_1201", "")));
        assertEquals(CalculationResult.UNCOMPARABLE_DISPLAY,
                engine.display("吨入厂发电量", Map.of(
                        "val_1001", new BigDecimal("400"),
                        "val_1201", "")));
    }
}
