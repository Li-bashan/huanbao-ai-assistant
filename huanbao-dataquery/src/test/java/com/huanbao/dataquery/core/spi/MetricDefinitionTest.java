package com.huanbao.dataquery.core.spi;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricDefinitionTest {

    @Test
    void matchesIndicatorCodeFormalNameAndAlias() {
        MetricDefinition metric = metric();

        assertTrue(metric.matches("OPS_ONLINE_RATE"));
        assertTrue(metric.matches("设备在线率"));
        assertTrue(metric.matches("在线率"));
        assertFalse(metric.matches("不存在的指标"));
        assertFalse(metric.matches(null));
    }

    @Test
    void normalizesEmptyCollectionsAndDefensivelyCopiesValues() {
        Set<String> aliases = new HashSet<>(Set.of("在线率"));
        List<String> baseMetricRefs = new ArrayList<>(List.of("ONLINE"));
        List<Map<String, Object>> fallbackRules = new ArrayList<>(List.of(
                new java.util.LinkedHashMap<>(Map.of("action", "return 0"))));

        MetricDefinition metric = new MetricDefinition(
                "OPS_ONLINE_RATE",
                "设备在线率",
                aliases,
                "效率",
                "DERIVED",
                true,
                baseMetricRefs,
                null,
                "safeDivide(base_ONLINE, base_TOTAL)",
                "%",
                "WEIGHTED_RECALCULATE",
                null,
                List.of(),
                fallbackRules,
                "PRESENT_IN_DB_FORMULA",
                null);

        aliases.add("新别名");
        baseMetricRefs.add("TOTAL");
        fallbackRules.get(0).put("qualityFlag", "MUTATED");

        assertFalse(metric.aliases().contains("新别名"));
        assertFalse(metric.baseMetricRefs().contains("TOTAL"));
        assertFalse(metric.fallbackRules().get(0).containsKey("qualityFlag"));
        assertTrue(metric.paramRefs().isEmpty());
        assertTrue(metric.applicableBusinessTypes().isEmpty());
        assertTrue(metric.contextRefs().isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> metric.fallbackRules().get(0).put("action", "changed"));
    }

    private static MetricDefinition metric() {
        return new MetricDefinition(
                "OPS_ONLINE_RATE",
                "设备在线率",
                Set.of("在线率"),
                "效率",
                "DERIVED",
                true,
                List.of("ONLINE", "TOTAL"),
                List.of(),
                "safeDivide(base_ONLINE, base_TOTAL)",
                "%",
                "WEIGHTED_RECALCULATE",
                List.of("垃圾焚烧发电项目"),
                List.of("GROUP"),
                List.of(),
                "PRESENT_IN_DB_FORMULA",
                List.of());
    }
}
