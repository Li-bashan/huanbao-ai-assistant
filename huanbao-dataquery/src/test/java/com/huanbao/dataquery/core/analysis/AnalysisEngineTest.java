package com.huanbao.dataquery.core.analysis;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisEngineTest {

    private final AnalysisEngine engine = new AnalysisEngine();

    @Test
    void scenarioA_calculatesQinhuangdaoGenerationDropAndBuildsThreeEvidenceLayers() {
        BigDecimal currentGeneration = decimal("800");
        BigDecimal samePeriodLastYear = decimal("1000");
        BigDecimal previousMonthGeneration = decimal("900");

        BigDecimal yoy = engine.calculateYoY(currentGeneration, samePeriodLastYear);
        BigDecimal mom = engine.calculateMoM(currentGeneration, previousMonthGeneration);

        AnalysisEvidenceResult result = new EvidenceChainBuilder()
                .addConfirmedFact(FactItem.metric(
                        "秦皇岛公司",
                        "发电量",
                        "2026-08",
                        currentGeneration,
                        yoy,
                        mom))
                .addCorrelatedClue(new ClueItem(
                        "秦皇岛公司",
                        "发电量",
                        "入厂量",
                        "2026-08",
                        yoy,
                        engine.calculateMoM(decimal("700"), decimal("800")),
                        "同期同向波动，仅作为相关线索，不代表因果"))
                .addCorrelatedClue(new ClueItem(
                        "秦皇岛公司",
                        "发电量",
                        "运行小时",
                        "2026-08",
                        yoy,
                        engine.calculateMoM(decimal("100"), decimal("120")),
                        "同期同向波动，仅作为相关线索，不代表因果"))
                .addPendingVerification("系统没有物理记录，建议核实设备启停或检修台账")
                .build();

        assertEquals(decimal("-20.00"), result.confirmedFacts().get(0).yearOverYearPercent());
        assertEquals(decimal("-11.11"), result.confirmedFacts().get(0).monthOverMonthPercent());
        assertEquals(1, result.confirmedFacts().size());
        assertEquals(2, result.correlatedClues().size());
        assertTrue(result.correlatedClues().stream().allMatch(ClueItem::isSameDirection));
        assertEquals(1, result.pendingVerification().size());
        assertTrue(result.pendingVerification().get(0).contains("设备启停或检修台账"));
    }

    @Test
    void scenarioB_calculatesPercentileDistributionAndMarksExtremes() {
        DistributionResult result = engine.distribution(Map.of(
                "甲项目公司", decimal("10"),
                "乙项目公司", decimal("20"),
                "丙项目公司", decimal("30"),
                "丁项目公司", decimal("40")));

        assertEquals(decimal("17.50"), result.p25());
        assertEquals(decimal("25.00"), result.median());
        assertEquals(decimal("32.50"), result.p75());
        assertEquals(decimal("10"), result.minimumValue());
        assertEquals(decimal("40"), result.maximumValue());
        assertEquals(Set.of("甲项目公司"), result.minimumCompanies());
        assertEquals(Set.of("丁项目公司"), result.maximumCompanies());
        assertTrue(result.items().stream()
                .filter(item -> item.company().equals("甲项目公司"))
                .findFirst()
                .orElseThrow()
                .isMinimum());
        assertTrue(result.items().stream()
                .filter(item -> item.company().equals("丁项目公司"))
                .findFirst()
                .orElseThrow()
                .isMaximum());
    }

    @Test
    void scenarioC_degradesGracefullyForZeroAndMissingBasePeriods() {
        assertNull(engine.calculateYoY(decimal("100"), decimal("0")));
        assertNull(engine.calculateMoM(decimal("100"), null));

        AnomalyResult missingCurrent = engine.detectAnomaly(
                Arrays.asList(decimal("100"), decimal("90"), null),
                decimal("20"));
        assertNull(missingCurrent.latestChangeRatePercent());
        assertNull(missingCurrent.deviationFromMeanPercent());
        assertFalse(missingCurrent.anomalous());

        assertTrue(engine.rank(Map.of(
                "甲项目公司", decimal("100"),
                "乙项目公司", ""))
                .stream()
                .allMatch(item -> item.company().equals("甲项目公司")));

        Map<String, Object> missingValues = new HashMap<>();
        missingValues.put("甲项目公司", null);
        DistributionResult empty = engine.distribution(missingValues);
        assertNull(empty.p25());
        assertTrue(empty.items().isEmpty());
    }

    @Test
    void ranksDynamicallyAndReportsDownwardMovementInRankNumber() {
        RankingResult result = engine.rank(
                Map.of("甲公司", decimal("70"), "乙公司", decimal("90"), "丙公司", decimal("80")),
                Map.of("甲公司", decimal("95"), "乙公司", decimal("90"), "丙公司", decimal("80")));

        RankingItem qinhuangdao = result.stream()
                .filter(item -> item.company().equals("甲公司"))
                .findFirst()
                .orElseThrow();
        assertEquals(3, qinhuangdao.rank());
        assertEquals(1, qinhuangdao.previousRank());
        assertEquals(2, qinhuangdao.rankChange());
        assertTrue(qinhuangdao.isDeclined());
    }

    @Test
    void detectsThreeConsecutiveDeclinesWithoutExternalReasoning() {
        AnomalyResult result = engine.detectAnomaly(
                List.of(decimal("100"), decimal("95"), decimal("90"), decimal("85")),
                decimal("20"));

        assertTrue(result.anomalous());
        assertTrue(result.consecutiveThreePeriodDecline());
        assertFalse(result.thresholdExceeded());
        assertTrue(result.reasons().contains("连续3期下滑"));
    }

    @Test
    void detectsChangeBeyondTheBusinessThreshold() {
        AnomalyResult result = engine.detectAnomaly(
                List.of(decimal("100"), decimal("100"), decimal("100"), decimal("70")),
                decimal("20"));

        assertTrue(result.anomalous());
        assertTrue(result.thresholdExceeded());
        assertFalse(result.consecutiveThreePeriodDecline());
        assertEquals(decimal("-30.00"), result.deviationFromMeanPercent());
        assertTrue(result.reasons().contains("本期值偏离历史均值超过阈值"));
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

}
