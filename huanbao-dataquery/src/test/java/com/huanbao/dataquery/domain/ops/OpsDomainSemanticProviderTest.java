package com.huanbao.dataquery.domain.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.huanbao.dataquery.core.spi.DataQueryRequest;
import com.huanbao.dataquery.core.spi.DomainSemanticProviderRegistry;
import com.huanbao.dataquery.core.spi.EntityMapping;
import com.huanbao.dataquery.core.spi.MetricDefinition;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpsDomainSemanticProviderTest {

    private final OpsDomainSemanticProvider provider = new OpsDomainSemanticProvider();

    @Test
    void loadsAll46MetricsFromSemanticDictionary() {
        assertEquals(46, provider.getMetrics().size());
        assertEquals(46, provider.getMetricRegistry().values().stream().distinct().count());

        MetricDefinition metric = provider.getMetric("吨入厂发电量");
        assertEquals("TON_ELEC_GEN", metric.indicatorCode());
        assertEquals("1001", metric.baseMetricRefs().get(0));
        assertEquals("1201", metric.baseMetricRefs().get(1));
    }

    @Test
    void resolvesCommonWasteProcessingAliasToFormalMetric() {
        MetricDefinition metric = provider.getMetric("垃圾处理量");

        assertEquals("1201", metric.indicatorCode());
        assertEquals("生活垃圾入厂量", metric.formalName());
    }

    @Test
    void packagesTheSemanticDictionaryWithTheRuntimeArtifact() throws Exception {
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("semantic/METRIC_SEMANTIC_DICTIONARY.json")) {
            assertTrue(input != null, "semantic dictionary must be packaged in the runtime classpath");
            JsonNode dictionary = new com.fasterxml.jackson.databind.ObjectMapper().readTree(input);
            assertTrue(dictionary.path("indicators").toString().contains("垃圾处理量"));
        }
    }

    @Test
    void resolvesQinhuangdaoToPreferredGarbagePlantCode() {
        EntityMapping mapping = provider.resolveEntity("秦皇岛电厂");
        OpsOrganization organization = provider.findOrganization("秦皇岛").orElseThrow();

        assertEquals("PROJECT_COMPANY", mapping.entityType());
        assertEquals("秦皇岛公司", mapping.canonicalName());
        assertEquals(OpsDomainSemanticProvider.QINHUANGDAO_CODE, organization.formalCode());
        assertEquals(OpsDomainSemanticProvider.GARBAGE_INCINERATION_BUSINESS_TYPE,
                organization.businessType());
        assertThrows(IllegalArgumentException.class, () -> provider.resolveEntity("10004011"));
    }

    @Test
    void buildsOnlySafeAtomicAggregationForDerivedMetric() {
        String sql = provider.getSqlStrategy().buildSql(new DataQueryRequest(
                "吨入厂发电量",
                "秦皇岛公司",
                Map.of("year", "2026", "half", "12")));

        assertTrue(sql.contains("SUM(CASE WHEN \"newIndicator\" = '1001'"));
        assertTrue(sql.contains("SUM(CASE WHEN \"newIndicator\" = '1201'"));
        assertTrue(sql.contains("\"ZBZ\" ~ '^-?[0-9]+([.][0-9]+)?$'"));
        assertTrue(sql.contains("CAST(\"ZBZ\" AS NUMERIC(18,2)) ELSE 0.00 END) AS val_1001"));
        assertTrue(sql.contains("CAST(\"ZBZ\" AS NUMERIC(18,2)) ELSE 0.00 END) AS val_1201"));
        assertTrue(sql.contains("o.formal_code = '10004024'"));
        assertFalse(sql.toUpperCase().contains("AVG("));
        assertFalse(sql.contains("CGXTAPPMISDate_2026_06"));
        assertTrue(sql.contains("CGXTAPPMISDate_2026_12"));
        assertTrue(sql.contains("\"newIndicator\", \"ZBRQ\", \"ZBZ\", \"orgcode\""));
        assertTrue(sql.contains(":startDate"));
        assertTrue(sql.contains(":endDate"));
        assertFalse(sql.contains("neworgcode"));

        String loadSql = provider.getSqlStrategy().buildSql(new DataQueryRequest(
                "发电负荷率", "秦皇岛公司", Map.of("year", "2026", "half", "12")));
        assertTrue(loadSql.contains("AS val_1001"));
        assertTrue(loadSql.contains("AS val_1707"));
        assertTrue(loadSql.contains("o.formal_code = '10004024'"));
    }

    @Test
    void isDiscoverableThroughJavaSpi() {
        DomainSemanticProviderRegistry registry = DomainSemanticProviderRegistry.discover();

        assertEquals(provider.getClass(), registry.require("ops").getClass());
    }
}
