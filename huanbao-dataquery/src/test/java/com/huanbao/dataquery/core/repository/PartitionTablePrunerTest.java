package com.huanbao.dataquery.core.repository;

import com.huanbao.dataquery.core.engine.SafeMetricSqlBuilder;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PartitionTablePrunerTest {

    private final PartitionTablePruner pruner = new PartitionTablePruner();

    @Test
    void selectsOnlyTheJunePartitionForAFirstHalfMonth() {
        PartitionTablePruner.PruningResult result = pruner.prune(
                LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 3, 31));

        assertEquals(List.of("CGXTAPPMISDate_2026_06"), result.selectedTableNames());
        assertFalse(result.boundaryClipped());
        assertEquals(
                "(SELECT \"newIndicator\", \"ZBRQ\", \"ZBZ\", \"orgcode\""
                        + " FROM MSOKFPT.\"CGXTAPPMISDate_2026_06\""
                        + " WHERE \"ZBRQ\" >= :startDate AND \"ZBRQ\" <= :endDate)",
                result.controlledSourceSql());
        assertEquals(LocalDate.of(2026, 3, 1), result.parameters().get("startDate"));
        assertEquals(LocalDate.of(2026, 3, 31), result.parameters().get("endDate"));
    }

    @Test
    void unionsBothPartitionsForA跨HalfYearRange() {
        PartitionTablePruner.PruningResult result = pruner.prune(
                LocalDate.of(2026, 5, 1),
                LocalDate.of(2026, 8, 31));

        assertEquals(
                List.of("CGXTAPPMISDate_2026_06", "CGXTAPPMISDate_2026_12"),
                result.selectedTableNames());
        assertEquals(2, count(result.controlledSourceSql(), "SELECT \"newIndicator\""));
        assertTrue(result.controlledSourceSql().contains("\nUNION ALL\n"));
        assertTrue(result.controlledSourceSql().contains("CGXTAPPMISDate_2026_06"));
        assertTrue(result.controlledSourceSql().contains("CGXTAPPMISDate_2026_12"));
    }

    @Test
    void unionsTheDecemberPartitionOf2025AndJunePartitionOf2026() {
        PartitionTablePruner.PruningResult result = pruner.prune(
                LocalDate.of(2025, 11, 1),
                LocalDate.of(2026, 3, 31));

        assertEquals(
                List.of("CGXTAPPMISDate_2025_12", "CGXTAPPMISDate_2026_06"),
                result.selectedTableNames());
        assertTrue(result.controlledSourceSql().indexOf("CGXTAPPMISDate_2025_12")
                < result.controlledSourceSql().indexOf("CGXTAPPMISDate_2026_06"));
    }

    @Test
    void clipsRequestsBeyondTheReconciledLatestDataDate() {
        PartitionTablePruner.PruningResult result = pruner.prune(
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 12, 31));

        assertEquals(List.of("CGXTAPPMISDate_2026_12"), result.selectedTableNames());
        assertTrue(result.boundaryClipped());
        assertTrue(result.boundaryNotice().contains("2026-08-31"));
        assertEquals(LocalDate.of(2026, 8, 31), result.effectiveEndDate());
        assertEquals(LocalDate.of(2026, 8, 31), result.parameters().get("endDate"));
        assertThrows(IllegalArgumentException.class, () -> pruner.prune(
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 12, 31)));
        assertFalse(result.controlledSourceSql().contains("2027_"));
    }

    @Test
    void passesTheControlledSourceToTheSafeMetricBuilder() {
        PartitionTablePruner.PruningResult result = pruner.prune(
                LocalDate.of(2025, 11, 1),
                LocalDate.of(2026, 3, 31));
        String aggregateSql = new SafeMetricSqlBuilder().buildAggregateSql(
                result.controlledSourceSql(), List.of("1001"));

        assertTrue(aggregateSql.contains("FROM (\n" + result.controlledSourceSql()));
        assertTrue(aggregateSql.contains("SUM(CASE WHEN \"newIndicator\" = '1001'"));
        assertFalse(aggregateSql.contains("CGXTAPPMISDate_2027"));
    }

    @Test
    void rejectsANameOutsideTheReconciledPhysicalTablePattern() {
        assertThrows(IllegalArgumentException.class, () -> new PartitionTablePruner(
                List.of("MSOKFPT.CGXTAPPMISDate_2026_06"),
                PartitionTablePruner.DEFAULT_MAX_DATA_DATE));
    }

    private static int count(String text, String fragment) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(fragment, offset)) >= 0) {
            count++;
            offset += fragment.length();
        }
        return count;
    }
}
