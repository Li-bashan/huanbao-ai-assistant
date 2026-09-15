package com.huanbao.dataquery.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huanbao.dataquery.core.analysis.AnalysisEngine;
import com.huanbao.dataquery.core.engine.CalculationEngine;
import com.huanbao.dataquery.core.repository.KingbaseQueryExecutor;
import com.huanbao.dataquery.core.repository.PartitionTablePruner;
import com.huanbao.dataquery.core.state.ConversationStateManager;
import com.huanbao.dataquery.domain.ops.OpsDomainSemanticProvider;
import com.huanbao.dataquery.domain.ops.OpsSqlBuildStrategy;
import com.huanbao.dataquery.router.AnalysisPlanDto;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DataToDocPipelineServiceTest {

    private static final String CONVERSATION_KEY = "tenant-a:user-7:pipeline-1";
    private static final String QUERY = "查近三个月垃圾处理量，帮我做一份分析简报";

    private MockRestServiceServer vllmServer;
    private DataToDocPipelineService service;
    private JdbcDataSource dataSource;

    @BeforeEach
    void setUp() {
        OpsDomainSemanticProvider provider = new OpsDomainSemanticProvider();
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:pipeline-" + System.nanoTime()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        createDatabase(dataSource);

        RestClient.Builder builder = RestClient.builder().baseUrl("http://vllm.test/v1");
        vllmServer = MockRestServiceServer.bindTo(builder).build();
        service = new DataToDocPipelineService(
                new ConversationStateManager(provider),
                new OpsSqlBuildStrategy(provider),
                new PartitionTablePruner(),
                new KingbaseQueryExecutor(dataSource),
                new CalculationEngine(provider),
                new AnalysisEngine(),
                provider,
                builder.build(),
                new ObjectMapper(),
                "Qwen3.8-27B",
                Clock.fixed(Instant.parse("2026-09-02T00:00:00Z"), ZoneOffset.UTC));
    }

    @AfterEach
    void verifyVllmRequests() {
        vllmServer.verify();
    }

    @Test
    void executesQueryAndDraftsBriefWithTrendChartAndRows() {
        vllmServer.expect(requestTo("http://vllm.test/v1/chat/completions"))
                .andExpect(jsonPath("$.model").value("Qwen3.8-27B"))
                .andExpect(jsonPath("$.messages[0].content", containsString("confirmedFacts")))
                .andExpect(jsonPath("$.messages[0].content", containsString("所有数字、单位、时间")))
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"content\":"
                                + "\"一、总体运行态势\\n\\n本期运行平稳。\\n\\n"
                                + "二、区域分布与结构特征\\n\\n数据见图表。\\n\\n"
                                + "三、重点关注线索与管理建议\\n\\n持续跟踪。\"}}]}",
                        MediaType.APPLICATION_JSON));

        CompositeExecutionResult result = service.execute(
                CONVERSATION_KEY,
                new AnalysisPlanDto(
                        "COMPOSITE",
                        List.of("垃圾处理量"),
                        List.of("项目公司"),
                        "近三个月",
                        "REPORT",
                        List.of("QUERY_DATA", "DRAFT_BRIEF")),
                QUERY,
                List.of("10004024"));

        assertFalse(result.degraded());
        assertTrue(result.documentMarkdown().contains("一、总体运行态势"));
        assertEquals("TREND", result.chartConfig().get("chartType"));
        assertEquals("line", result.chartConfig().get("type"));
        assertEquals(3, result.tableRows().size());
        assertEquals("2026-06", result.tableRows().get(0).get("period"));
        assertEquals("集团", result.tableRows().get(0).get("subject"));
        assertEquals(List.of("2026-06", "2026-07", "2026-08"),
                ((Map<?, ?>) result.chartConfig().get("xAxis")).get("data"));
        assertNotNull(result.tableRows().get(2).get("currentValue"), result.tableRows().toString());
    }

    @Test
    void keepsDeterministicDataWhenDocumentModelTimesOut() {
        vllmServer.expect(requestTo("http://vllm.test/v1/chat/completions"))
                .andRespond(withException(new java.net.SocketTimeoutException("document timeout")));

        CompositeExecutionResult result = service.execute(
                CONVERSATION_KEY,
                new AnalysisPlanDto(
                        "COMPOSITE",
                        List.of("垃圾处理量"),
                        List.of("项目公司"),
                        "近三个月",
                        "REPORT",
                        List.of("QUERY_DATA", "DRAFT_BRIEF")),
                QUERY,
                List.of("10004024"));

        assertTrue(result.degraded());
        assertEquals(DataToDocPipelineService.DEGRADED_MESSAGE, result.degradedMessage());
        assertEquals("", result.documentMarkdown());
        assertEquals("TREND", result.chartConfig().get("chartType"));
        assertEquals(3, result.tableRows().size());
        assertTrue(result.tableRows().stream().allMatch(row -> row.get("currentValue") != null));
    }

    @Test
    void returnsOnlyRequestedTopNRowsAndChartCategoriesForRanking() {
        org.springframework.jdbc.core.JdbcTemplate jdbc =
                new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        insertMapping(jdbc, "10004025", "测试甲公司");
        insertMapping(jdbc, "10004026", "测试乙公司");
        insert(jdbc, "CGXTAPPMISDate_2026_12", "2026-08-15", "500000", "10004025");
        insert(jdbc, "CGXTAPPMISDate_2026_12", "2026-08-15", "400000", "10004026");

        CompositeExecutionResult result = service.execute(
                CONVERSATION_KEY + "-ranking",
                new AnalysisPlanDto(
                        "DATA_QUERY", List.of("生活垃圾入厂量"), List.of("项目公司"),
                        "今年", "RANKING", List.of()),
                "查询今年各项目公司生活垃圾入厂量排名，展示前两名",
                List.of("10004024", "10004025", "10004026"));

        assertEquals(2, result.tableRows().size());
        assertEquals(2, ((List<?>) ((Map<?, ?>) result.chartConfig().get("xAxis")).get("data")).size());
        assertEquals("测试甲公司", result.tableRows().get(0).get("subject"));
        assertEquals(1, result.tableRows().get(0).get("rank"));
    }

    @Test
    void resolvesAbsoluteYearWithoutTreatingYearPrefixAsMonth() {
        CompositeExecutionResult result = service.execute(
                CONVERSATION_KEY + "-absolute-year",
                new AnalysisPlanDto(
                        "DATA_QUERY", List.of("生活垃圾入厂量"), List.of(),
                        "2025年", "FACT", List.of()),
                "2025年生活垃圾入厂量是多少",
                List.of("10004024"));

        assertEquals(12, result.tableRows().size());
        assertEquals("2025-01", result.tableRows().get(0).get("period"));
        assertEquals("2025-12", result.tableRows().get(11).get("period"));
    }

    private static void createDatabase(JdbcDataSource dataSource) {
        org.springframework.jdbc.core.JdbcTemplate jdbc =
                new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS MSOKFPT");
        jdbc.execute("CREATE TABLE IF NOT EXISTS MSOKFPT.\"dim_org_mapping\" ("
                + "formal_code VARCHAR(64), formal_name VARCHAR(255), short_name VARCHAR(255),"
                + "business_type VARCHAR(255), region VARCHAR(255), is_production_plant BOOLEAN)");
        jdbc.update("INSERT INTO MSOKFPT.\"dim_org_mapping\" VALUES (?, ?, ?, ?, ?, ?)",
                "10004024", "中节能（秦皇岛）环保能源有限公司", "秦皇岛公司",
                "垃圾焚烧发电项目", "华北大区", true);

        for (String table : List.of(
                "CGXTAPPMISDate_2024_06", "CGXTAPPMISDate_2024_12",
                "CGXTAPPMISDate_2025_06", "CGXTAPPMISDate_2025_12",
                "CGXTAPPMISDate_2026_06", "CGXTAPPMISDate_2026_12")) {
            jdbc.execute("CREATE TABLE IF NOT EXISTS MSOKFPT.\"" + table + "\" ("
                    + "\"newIndicator\" VARCHAR(64), \"ZBRQ\" DATE, \"ZBZ\" VARCHAR(255),"
                    + "\"orgcode\" VARCHAR(64))");
        }

        insert(jdbc, "CGXTAPPMISDate_2025_06", "2025-06-15", "8000");
        insert(jdbc, "CGXTAPPMISDate_2025_12", "2025-07-15", "9000");
        insert(jdbc, "CGXTAPPMISDate_2025_12", "2025-08-15", "10000");
        insert(jdbc, "CGXTAPPMISDate_2026_06", "2026-06-15", "10000");
        insert(jdbc, "CGXTAPPMISDate_2026_12", "2026-07-15", "12000");
        insert(jdbc, "CGXTAPPMISDate_2026_12", "2026-08-15", "15000");
    }

    private static void insert(
            org.springframework.jdbc.core.JdbcTemplate jdbc,
            String table,
            String date,
            String value) {
        insert(jdbc, table, date, value, "10004024");
    }

    private static void insert(
            org.springframework.jdbc.core.JdbcTemplate jdbc,
            String table,
            String date,
            String value,
            String orgCode) {
        jdbc.update("INSERT INTO MSOKFPT.\"" + table
                        + "\" (\"newIndicator\", \"ZBRQ\", \"ZBZ\", \"orgcode\")"
                        + " VALUES ('1201', ?, ?, ?)",
                java.sql.Date.valueOf(date), value, orgCode);
    }

    private static void insertMapping(
            org.springframework.jdbc.core.JdbcTemplate jdbc,
            String formalCode,
            String shortName) {
        jdbc.update("INSERT INTO MSOKFPT.\"dim_org_mapping\" VALUES (?, ?, ?, ?, ?, ?)",
                formalCode, shortName, shortName, "垃圾焚烧发电项目", "华北大区", true);
    }
}
