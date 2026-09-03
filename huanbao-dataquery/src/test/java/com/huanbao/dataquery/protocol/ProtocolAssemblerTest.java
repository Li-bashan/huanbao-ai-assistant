package com.huanbao.dataquery.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huanbao.dataquery.core.analysis.AnalysisEvidenceResult;
import com.huanbao.dataquery.core.analysis.ClueItem;
import com.huanbao.dataquery.core.analysis.FactItem;
import com.huanbao.dataquery.domain.ops.OpsDomainSemanticProvider;
import com.huanbao.dataquery.pipeline.CompositeExecutionResult;
import com.huanbao.dataquery.pipeline.StructuredFactPayload;
import com.huanbao.dataquery.router.AnalysisPlanDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolAssemblerTest {

    @Test
    void assemblesCompleteV2ContractWithThreeEvidenceLayers() throws Exception {
        FactItem fact = new FactItem(
                "秦皇岛公司", "全厂发电量", "2026-08",
                new BigDecimal("15000"), new BigDecimal("25"), new BigDecimal("10"),
                1, 2, 1);
        ClueItem clue = new ClueItem(
                "秦皇岛公司", "全厂发电量", "同比与环比变化", "2026-08",
                new BigDecimal("25"), new BigDecimal("10"), "仅表示同期变化线索");
        AnalysisEvidenceResult evidence = new AnalysisEvidenceResult(
                List.of(fact), List.of(clue), List.of("需核实设备停运台账"));
        StructuredFactPayload payload = new StructuredFactPayload(
                "秦皇岛公司", "全厂发电量", "2026-08",
                fact.currentValue(), fact.yearOverYearPercent(), fact.monthOverMonthPercent(),
                fact.rank(), fact.previousRank(), fact.rankChange(),
                evidence.confirmedFacts(), evidence.correlatedClues(), evidence.pendingVerification());

        Map<String, Object> chart = new LinkedHashMap<>();
        chart.put("type", "line");
        chart.put("title", Map.of("text", "全厂发电量趋势"));
        chart.put("xAxis", Map.of("data", List.of("2026-08")));
        chart.put("series", List.of(Map.of(
                "name", "秦皇岛公司", "type", "line", "data", List.of(new BigDecimal("15000")))));
        CompositeExecutionResult result = new CompositeExecutionResult(
                new AnalysisPlanDto("COMPOSITE", List.of("全厂发电量"), List.of("秦皇岛公司"),
                        "今年", "REPORT", List.of("QUERY_DATA", "DRAFT_BRIEF")),
                chart,
                List.of(Map.of(
                        "subject", "秦皇岛公司", "metric", "全厂发电量", "period", "2026-08",
                        "currentValue", fact.currentValue(), "yearOverYearPercent", fact.yearOverYearPercent(),
                        "monthOverMonthPercent", fact.monthOverMonthPercent(), "rank", 1)),
                "一、总体运行态势\n\n本期运行平稳。",
                false,
                "",
                List.of(payload));

        PresentationResponseV2 response = new ProtocolAssembler(new OpsDomainSemanticProvider()).assemble(
                result,
                new UserOrganizationContext("user-7", List.of("10004024")),
                LocalDate.of(2026, 8, 31),
                "request-1",
                "conversation-1");
        JsonNode json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(response));

        assertEquals("2.0", json.path("protocolVersion").asText());
        assertEquals("SUCCESS_WITH_DATA", json.path("status").asText());
        assertEquals("OVERVIEW", json.path("analysisType").asText());
        JsonNode content = json.path("content");
        for (String field : List.of("title", "summary", "metrics", "table", "chart", "insights",
                "evidence", "dataInfo", "followUps")) {
            assertTrue(content.has(field), "missing content field: " + field);
        }
        assertTrue(content.path("metrics").isArray());
        assertTrue(content.path("table").path("columns").isArray());
        assertTrue(content.path("chart").path("series").isArray());
        assertEquals("2026-08-31", content.path("dataInfo").path("dataCutoffDate").asText());
        assertTrue(content.path("dataInfo").path("sourceTables").toString()
                .contains("CGXTAPPMISDate_2026_12"));
        assertEquals(3, content.path("evidence").size());
        assertEquals("confirmedFacts", content.path("evidence").get(0).path("layer").asText());
        assertEquals("correlatedClues", content.path("evidence").get(1).path("layer").asText());
        assertEquals("pendingVerification", content.path("evidence").get(2).path("layer").asText());
        assertEquals(3, content.path("followUps").size());
        assertTrue(content.path("followUps").toString().contains("查看秦皇岛表现"));
    }

    @Test
    void emitsEmptyCollectionsAndNoDataStatusWhenRowsHaveNoValues() throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("subject", "集团");
        row.put("metric", "全厂发电量");
        row.put("period", "2026-08");
        row.put("currentValue", null);
        row.put("pendingVerification", List.of("基期缺失"));
        CompositeExecutionResult result = new CompositeExecutionResult(
                new AnalysisPlanDto("DATA_QUERY", List.of("全厂发电量"), List.of("集团"),
                        "本月", "FACT", List.of()),
                Map.of(),
                List.of(row),
                "",
                false,
                "");

        PresentationResponseV2 response = new ProtocolAssembler().assemble(
                result,
                new UserOrganizationContext("user-7", List.of("10004024")),
                LocalDate.of(2026, 8, 31));
        JsonNode content = new ObjectMapper().valueToTree(response).path("content");

        assertEquals("NO_DATA", response.status());
        assertTrue(content.path("metrics").isArray());
        assertEquals(0, content.path("metrics").size());
        assertTrue(content.path("insights").isArray());
        assertTrue(content.path("followUps").isArray());
        assertEquals(0, content.path("followUps").size());
    }
}
