package com.huanbao.dataquery.regression;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huanbao.dataquery.core.analysis.AnalysisEngine;
import com.huanbao.dataquery.core.repository.KingbaseQueryExecutor;
import com.huanbao.dataquery.core.repository.PartitionTablePruner;
import com.huanbao.dataquery.core.state.ConversationAnalysisState;
import com.huanbao.dataquery.core.state.ConversationStateManager;
import com.huanbao.dataquery.domain.ops.OpsDomainSemanticProvider;
import com.huanbao.dataquery.domain.ops.OpsSqlBuildStrategy;
import com.huanbao.dataquery.pipeline.DataToDocPipelineService;
import com.huanbao.dataquery.protocol.ChartDataDto;
import com.huanbao.dataquery.protocol.DataQueryExecutionController;
import com.huanbao.dataquery.protocol.EvidenceV2Dto;
import com.huanbao.dataquery.protocol.MetricCardDto;
import com.huanbao.dataquery.protocol.PresentationResponseV2;
import com.huanbao.dataquery.protocol.ProtocolAssembler;
import com.huanbao.dataquery.protocol.QueryExecuteRequest;
import com.huanbao.dataquery.protocol.SseStreamDispatcher;
import com.huanbao.dataquery.router.AnalysisPlanDto;
import com.huanbao.dataquery.router.IntentClassifier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * TASK-4.1 建设方案第三十五条：10 大核心业务场景真实库端到端回归。
 *
 * <p>本套件故意不使用 H2、fixture 或硬编码业务数值。只有设置了真实 Kingbase
 * 只读连接密码后才执行；数据库连接失败或客观基准不满足时直接阻断，不能降级为虚拟数据回归。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CoreScenarioRegressionTest {

    private static final String VLLM_BASE_URL = "http://core-scenario-regression-vllm.test/v1";
    private static final LocalDate DATA_CUTOFF = LocalDate.of(2026, 8, 31);
    private static final String QINHUANGDAO_CODE = "10004024";
    private static final String BAODING_CODE = "10004025";
    private static final String GENERATION_CODE = "1001";
    private static final String GARBAGE_CODE = "1201";
    private static final String BUSINESS_TYPE = "垃圾焚烧发电项目";
    private static final String NUMERIC_TEXT_REGEX = "^-?[0-9]+([.][0-9]+)?$";
    private static final List<String> REAL_PARTITION_TABLES = List.of(
            "CGXTAPPMISDate_2024_06",
            "CGXTAPPMISDate_2024_12",
            "CGXTAPPMISDate_2025_06",
            "CGXTAPPMISDate_2025_12",
            "CGXTAPPMISDate_2026_06",
            "CGXTAPPMISDate_2026_12");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<ScenarioLog> scenarioLogs = new ArrayList<>();
    private String preflightBlockReason;

    private NamedParameterJdbcTemplate baselineJdbc;
    private DataSource dataSource;
    private OpsDomainSemanticProvider semanticProvider;
    private ConversationStateManager stateManager;
    private IntentClassifier intentClassifier;
    private DataToDocPipelineService pipelineService;
    private ProtocolAssembler protocolAssembler;
    private DataQueryExecutionController executionController;
    private RecordingSseStreamDispatcher recordingDispatcher;
    private MockRestServiceServer vllmServer;
    private List<OrgRow> productionOrganizations;
    private List<String> allowedOrgCodes;
    private Map<String, OrgRow> organizationByCode;

    @BeforeAll
    void setUpRealDatabaseRegression() throws Exception {
        String reconciliationReport = readReconciliationReport();
        assertTrue(reconciliationReport.contains("2026-08-31"),
                "客观基准缺少数据截止日期 2026-08-31");
        assertTrue(reconciliationReport.contains(QINHUANGDAO_CODE),
                "客观基准缺少秦皇岛实体厂编码 10004024");
        assertTrue(reconciliationReport.contains(GENERATION_CODE)
                        && reconciliationReport.contains(GARBAGE_CODE),
                "客观基准缺少指标编码 1001/1201");

        RealDbConfig config = RealDbConfig.fromEnvironment();
        if (!config.passwordPresent()) {
            preflightBlockReason = "未执行真实库回归：请设置 DATAQUERY_DB_PASSWORD；禁止自动退回 H2 或虚拟数据";
            Assumptions.abort(preflightBlockReason);
        }

        DriverManagerDataSource configuredDataSource = new DriverManagerDataSource();
        configuredDataSource.setDriverClassName(config.driverClassName());
        configuredDataSource.setUrl(config.url());
        configuredDataSource.setUsername(config.username());
        configuredDataSource.setPassword(config.password());
        dataSource = configuredDataSource;
        baselineJdbc = new NamedParameterJdbcTemplate(dataSource);
        try {
            Integer one = baselineJdbc.getJdbcTemplate().queryForObject("SELECT 1", Integer.class);
            assertEquals(1, one, "真实 Kingbase 只读连接校验失败");
        } catch (RuntimeException exception) {
            throw new AssertionError("真实 Kingbase 连接失败；本次不能以虚拟数据替代", exception);
        }

        productionOrganizations = loadProductionOrganizations();
        assertEquals(63, productionOrganizations.size(),
                "实库组织映射的垃圾焚烧发电项目必须覆盖 63 家");
        allowedOrgCodes = productionOrganizations.stream()
                .map(OrgRow::formalCode)
                .toList();
        assertEquals(63, new LinkedHashSet<>(allowedOrgCodes).size(),
                "63 家实库项目公司的 formal_code 必须唯一");
        organizationByCode = productionOrganizations.stream()
                .collect(Collectors.toUnmodifiableMap(OrgRow::formalCode, row -> row));

        semanticProvider = new OpsDomainSemanticProvider();
        assertEquals(GENERATION_CODE,
                semanticProvider.getMetric("发电量").indicatorCode(),
                "语义资产中的发电量编码不符合客观基准");
        assertEquals(GARBAGE_CODE,
                semanticProvider.getMetric("生活垃圾入厂量").indicatorCode(),
                "语义资产中的生活垃圾入厂量编码不符合客观基准");
        assertEquals(QINHUANGDAO_CODE,
                semanticProvider.findOrganization("秦皇岛").orElseThrow().formalCode(),
                "秦皇岛简称没有锁定实库正式厂编码 10004024");

        RestClient.Builder vllmBuilder = RestClient.builder().baseUrl(VLLM_BASE_URL);
        vllmServer = MockRestServiceServer.bindTo(vllmBuilder).build();
        stateManager = new ConversationStateManager(
                semanticProvider,
                Clock.fixed(Instant.parse("2026-09-02T00:00:00Z"), ZoneOffset.UTC));
        intentClassifier = new IntentClassifier(vllmBuilder.build(), objectMapper, semanticProvider);
        pipelineService = new DataToDocPipelineService(
                stateManager,
                new OpsSqlBuildStrategy(semanticProvider),
                new PartitionTablePruner(DATA_CUTOFF),
                new KingbaseQueryExecutor(dataSource),
                new com.huanbao.dataquery.core.engine.CalculationEngine(semanticProvider),
                new AnalysisEngine(),
                semanticProvider,
                vllmBuilder.build(),
                objectMapper,
                "Qwen3.8-27B",
                Clock.fixed(Instant.parse("2026-09-02T00:00:00Z"), ZoneOffset.UTC));
        protocolAssembler = new ProtocolAssembler(semanticProvider);
        recordingDispatcher = new RecordingSseStreamDispatcher();
        executionController = new DataQueryExecutionController(
                intentClassifier, pipelineService, protocolAssembler, recordingDispatcher);
    }

    @BeforeEach
    void resetVllmContract() {
        if (vllmServer != null) {
            vllmServer.reset();
        }
    }

    @AfterEach
    void verifyVllmContract() {
        if (vllmServer != null) {
            vllmServer.verify();
        }
    }

    @AfterAll
    void printAcceptanceMatrix() {
        if (recordingDispatcher != null) {
            recordingDispatcher.shutdown();
        }
        System.out.println("CORE_SCENARIO_REGRESSION_MATRIX");
        if (scenarioLogs.isEmpty() && preflightBlockReason != null) {
            for (ScenarioDefinition scenario : SCENARIOS) {
                System.out.printf("%s | %s | %s | BLOCKED | 0 ms | %s%n",
                        scenario.id(), scenario.id(), scenario.query(), preflightBlockReason);
            }
        }
        for (ScenarioLog log : scenarioLogs) {
            System.out.printf("%s | %s | %s | %s | %d ms | %s%n",
                    log.id(), log.name(), log.query(), log.status(), log.elapsedMillis(), log.detail());
        }
    }

    @Test
    void scenario01_factQueryUsesRealQinhuangdaoAugustBaseline() throws Throwable {
        runScenario("场景1-事实查询", "秦皇岛8月份发电量是多少", () -> {
            RunResult run = execute(
                    "秦皇岛8月份发电量是多少",
                    plan("DATA_QUERY", List.of("发电量"), List.of("秦皇岛"), "8月份", "FACT"),
                    false);
            assertEquals("DATA_QUERY", run.classifiedPlan().primaryIntent());
            assertEquals("FACT", run.response().analysisType());
            assertEquals(DATA_CUTOFF.toString(), run.response().content().dataInfo().dataCutoffDate());

            BigDecimal current = realValue(GENERATION_CODE, QINHUANGDAO_CODE,
                    YearMonth.of(2026, 8));
            BigDecimal previousYear = realValue(GENERATION_CODE, QINHUANGDAO_CODE,
                    YearMonth.of(2025, 8));
            BigDecimal previousMonth = realValue(GENERATION_CODE, QINHUANGDAO_CODE,
                    YearMonth.of(2026, 7));
            EvidenceV2Dto fact = confirmedFact(run.response(), "秦皇岛公司", "2026-08");
            assertDecimalEquals(current, fact.currentValue(), "秦皇岛 2026-08 发电量");
            assertDecimalEquals(new AnalysisEngine().calculateYoY(current, previousYear),
                    fact.yearOverYearPercent(), "秦皇岛发电量 YoY");
            assertDecimalEquals(new AnalysisEngine().calculateMoM(current, previousMonth),
                    fact.monthOverMonthPercent(), "秦皇岛发电量 MoM");

            MetricCardDto card = run.response().content().metrics().stream()
                    .filter(item -> item.label().equals("发电量"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("缺少发电量 KPI 卡片"));
            assertDecimalEquals(current, decimal(card.value()), "KPI 卡片当前值");
            assertNotNull(card.yearOverYearPercent(), "KPI 卡片缺少 YoY");
            assertNotNull(card.monthOverMonthPercent(), "KPI 卡片缺少 MoM");
        });
    }

    @Test
    void scenario02_rankingCovers63RealGarbagePlantsAndTopTen() throws Throwable {
        runScenario("场景2-多公司排名", "今年项目公司发电量排名", () -> {
            RunResult run = execute(
                    "今年项目公司发电量排名",
                    plan("DATA_QUERY", List.of("发电量"), List.of("项目公司"), "今年", "RANKING"),
                    false);
            assertEquals("RANKING", run.response().analysisType());
            ChartDataDto chart = run.response().content().chart();
            assertNotNull(chart, "排名结果缺少图表");
            assertEquals("bar", chart.type());

            Map<String, BigDecimal> yearToDate = realValues(
                    GENERATION_CODE, LocalDate.of(2026, 1, 1), DATA_CUTOFF, allowedOrgCodes);
            List<String> expectedTopTen = yearToDate.entrySet().stream()
                    .sorted(Map.Entry.<String, BigDecimal>comparingByValue(Comparator.reverseOrder())
                            .thenComparing(entry -> organizationByCode.get(entry.getKey()).shortName()))
                    .limit(10)
                    .map(entry -> organizationByCode.get(entry.getKey()).shortName())
                    .toList();
            List<Map<String, Object>> latestRows = run.response().content().table().rows().stream()
                    .filter(row -> "2026-08".equals(row.get("period")))
                    .toList();
            assertEquals(63, latestRows.size(), "排名明细必须覆盖 63 家公司");
            assertEquals(63, chart.categories().size(), "bar 图必须覆盖 63 家公司");
            assertEquals(63, run.response().content().table().total(), "排名表格总数必须为 63");
            assertTrue(expectedTopTen.stream().allMatch(name -> chart.categories().contains(name)),
                    "Top10 排名没有完整出现在 bar 图中");
            for (Map<String, Object> row : latestRows) {
                String company = String.valueOf(row.get("companyName"));
                String code = organizationByCode.values().stream()
                        .filter(item -> item.shortName().equals(company))
                        .map(OrgRow::formalCode)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("排名出现未在实库映射中的公司: " + company));
                assertDecimalEquals(yearToDate.getOrDefault(code, BigDecimal.ZERO), decimal(row.get("value")),
                        "排名年度值 " + company);
            }
        });
    }

    @Test
    void scenario03_halfYearTrendReturnsMarchThroughAugust() throws Throwable {
        runScenario("场景3-趋势变化", "秦皇岛最近半年发电量怎么样", () -> {
            RunResult run = execute(
                    "秦皇岛最近半年发电量怎么样",
                    plan("DATA_QUERY", List.of("发电量"), List.of("秦皇岛"), "近6个月", "TREND"),
                    false);
            assertEquals("TREND", run.response().analysisType());
            ChartDataDto chart = run.response().content().chart();
            assertNotNull(chart, "趋势结果缺少图表");
            assertEquals("line", chart.type());
            List<String> periods = List.of("2026-03", "2026-04", "2026-05", "2026-06", "2026-07", "2026-08");
            assertEquals(periods, chart.categories(), "趋势周期必须是 2026-03 至 2026-08");
            for (String period : periods) {
                EvidenceV2Dto fact = confirmedFact(run.response(), "秦皇岛公司", period);
                assertDecimalEquals(realValue(GENERATION_CODE, QINHUANGDAO_CODE, YearMonth.parse(period)),
                        fact.currentValue(), "趋势值 " + period);
            }
            assertTrue(run.response().content().insights().stream()
                            .anyMatch(item -> item.text().contains("趋势") || item.text().contains("变化")),
                    "趋势结果缺少拐点/变化洞察");
        });
    }

    @Test
    void scenario04_overviewContainsAnnualMultiMetricDashboard() throws Throwable {
        runScenario("场景4-公司画像", "秦皇岛今年怎么样", () -> {
            RunResult run = execute(
                    "秦皇岛今年怎么样",
                    plan("DATA_QUERY", List.of("发电量", "生活垃圾入厂量"), List.of("秦皇岛"), "今年", "REPORT"),
                    true);
            assertEquals("OVERVIEW", run.response().analysisType());
            assertNotNull(run.response().content().chart(), "公司画像缺少趋势图");
            assertTrue(run.response().content().metrics().size() >= 2,
                    "公司画像必须输出年度多指标看板");
            Set<String> metrics = run.response().content().table().rows().stream()
                    .map(row -> String.valueOf(row.get("indicator")))
                    .collect(Collectors.toSet());
            assertTrue(metrics.contains("发电量") && metrics.contains("生活垃圾入厂量"),
                    "公司画像没有同时返回发电量和生活垃圾入厂量");
            assertEquals("2026-08", run.response().content().dataInfo().statistics().get("latestPeriod"));
            assertTrue(run.response().content().table().rows().stream()
                            .allMatch(row -> String.valueOf(row.get("period")).compareTo(DATA_CUTOFF.toString()) <= 0),
                    "公司画像越过 2026-08-31 数据截止日");
        });
    }

    @Test
    void scenario05_anomalyListMatchesAnalysisEngineOnRealSeries() throws Throwable {
        runScenario("场景5-异常发现", "最近哪些公司需要重点关注", () -> {
            List<YearMonth> periods = List.of(
                    YearMonth.of(2026, 5), YearMonth.of(2026, 6),
                    YearMonth.of(2026, 7), YearMonth.of(2026, 8));
            Map<String, List<BigDecimal>> series = new TreeMap<>();
            for (YearMonth period : periods) {
                Map<String, BigDecimal> values = realValues(
                        GENERATION_CODE, period.atDay(1), period.atEndOfMonth(), allowedOrgCodes);
                values.forEach((code, value) -> series.computeIfAbsent(code, ignored -> new ArrayList<>()).add(value));
            }
            AnalysisEngine engine = new AnalysisEngine();
            Set<String> expectedAnomalies = series.entrySet().stream()
                    .filter(entry -> engine.detectAnomaly(entry.getValue()).anomalous())
                    .map(entry -> organizationByCode.get(entry.getKey()).shortName())
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            RunResult run = execute(
                    "最近哪些公司需要重点关注",
                    plan("DATA_QUERY", List.of("发电量"), List.of("项目公司"), "近4个月", "TREND"),
                    false);
            String insightText = run.response().content().insights().stream()
                    .map(item -> item.text())
                    .collect(Collectors.joining("\n"));
            assertTrue(insightText.contains("异常") || insightText.contains("关注"),
                    "响应没有显式交付基于 AnalysisEngine 的异常清单");
            assertTrue(expectedAnomalies.stream().allMatch(insightText::contains),
                    "异常清单漏报实库算法命中的公司: " + expectedAnomalies);
        });
    }

    @Test
    void scenario06_reasonQueryKeepsThreeEvidenceLayersAndNoCausalFabrication() throws Throwable {
        runScenario("场景6-原因线索", "秦皇岛8月为什么发电量下降", () -> {
            RunResult run = execute(
                    "秦皇岛8月为什么发电量下降",
                    plan("DATA_QUERY", List.of("发电量"), List.of("秦皇岛"), "8月份", "FACT"),
                    false);
            EvidenceV2Dto fact = confirmedFact(run.response(), "秦皇岛公司", "2026-08");
            assertDecimalEquals(realValue(GENERATION_CODE, QINHUANGDAO_CODE, YearMonth.of(2026, 8)),
                    fact.currentValue(), "原因线索的确认事实当前值");
            Set<String> layers = run.response().content().evidence().stream()
                    .map(EvidenceV2Dto::layer)
                    .collect(Collectors.toSet());
            assertTrue(layers.containsAll(Set.of(
                            "confirmedFacts", "correlatedClues", "pendingVerification")),
                    "原因分析必须交付 confirmedFacts/correlatedClues/pendingVerification 三层证据");
            assertTrue(run.response().content().evidence().stream()
                            .filter(item -> "correlatedClues".equals(item.layer()))
                            .allMatch(item -> item.relationship().contains("不代表因果")),
                    "相关线索被包装成了主观因果结论");
        });
    }

    @Test
    void scenario07_comparisonChecksQinhuangdaoAndBaodingForKeyMetrics() throws Throwable {
        runScenario("场景7-综合比较", "秦皇岛和保定今年谁表现更好", () -> {
            RunResult run = execute(
                    "秦皇岛和保定今年谁表现更好",
                    plan("DATA_QUERY", List.of("发电量", "生活垃圾入厂量"),
                            List.of("秦皇岛", "保定"), "今年", "REPORT"),
                    true);
            assertEquals("OVERVIEW", run.response().analysisType());
            Set<String> subjects = run.response().content().table().rows().stream()
                    .map(row -> String.valueOf(row.get("organization")))
                    .collect(Collectors.toSet());
            Set<String> metrics = run.response().content().table().rows().stream()
                    .map(row -> String.valueOf(row.get("indicator")))
                    .collect(Collectors.toSet());
            assertTrue(subjects.containsAll(Set.of("秦皇岛公司", "保定公司")),
                    "并排比较缺少秦皇岛或保定");
            assertTrue(metrics.containsAll(Set.of("发电量", "生活垃圾入厂量")),
                    "并排比较缺少关键生产指标");
            assertNotNull(run.response().content().table(), "并排比较缺少明细表");
        });
    }

    @Test
    void scenario08_regionOverviewUsesRealWeightedScope() throws Throwable {
        runScenario("场景8-大区分析", "雄安大区今年整体怎么样", () -> {
            RunResult run = execute(
                    "雄安大区今年整体怎么样",
                    plan("DATA_QUERY", List.of("发电量"), List.of("雄安大区"), "今年", "REPORT"),
                    true);
            assertEquals("REGION", semanticProvider.resolveEntity("雄安大区").entityType());
            assertEquals("OVERVIEW", run.response().analysisType());
            List<String> regionCodes = productionOrganizations.stream()
                    .filter(row -> "雄安大区".equals(row.region()))
                    .map(OrgRow::formalCode)
                    .toList();
            assertFalse(regionCodes.isEmpty(), "雄安大区实库映射为空");
            BigDecimal expected = realValues(
                    GENERATION_CODE, LocalDate.of(2026, 1, 1), DATA_CUTOFF, regionCodes)
                    .values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            EvidenceV2Dto latest = confirmedFact(run.response(), "雄安大区", "2026-08");
            assertDecimalEquals(expected, latest.currentValue(), "雄安大区发电量加权重算");
            assertEquals("REGION", run.response().content().dataInfo().organizationScope().get("type"),
                    "大区结果没有保留 REGION 范围语义");
        });
    }

    @Test
    void scenario09_compositeBriefUsesRealThreeMonthTrendAndOfficialStructure() throws Throwable {
        runScenario("场景9-经营简报", "查近三个月垃圾入厂量，帮我做一份分析简报", () -> {
            RunResult run = execute(
                    "查近三个月垃圾入厂量，帮我做一份分析简报",
                    plan("COMPOSITE", List.of("垃圾入厂量"), List.of(), "近三个月", "REPORT"),
                    true);
            assertEquals("COMPOSITE", run.classifiedPlan().primaryIntent());
            assertEquals("OVERVIEW", run.response().analysisType());
            assertEquals("line", run.response().content().chart().type());
            assertEquals(List.of("2026-06", "2026-07", "2026-08"),
                    run.response().content().chart().categories());
            assertTrue(run.response().content().summary().contains("一、总体运行态势"));
            assertTrue(run.response().content().summary().contains("二、区域分布与结构特征"));
            assertTrue(run.response().content().summary().contains("三、重点关注线索与管理建议"));
            for (String period : List.of("2026-06", "2026-07", "2026-08")) {
                Map<String, Object> fact = run.response().content().table().rows().stream()
                        .filter(row -> "集团".equals(row.get("organization"))
                                && period.equals(row.get("period")))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("缺少简报事实: " + period));
                BigDecimal expectedTons = realValues(
                        GARBAGE_CODE, YearMonth.parse(period).atDay(1),
                        YearMonth.parse(period).atEndOfMonth(), allowedOrgCodes)
                        .values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                assertDecimalEquals(expectedTons, decimal(fact.get("value")), "垃圾入厂量（吨） " + period);
            }
        });
    }

    @Test
    void scenario10_fiveTurnConversationUpdatesOnlyRequestedStateSlots() throws Throwable {
        runScenario("场景10-连续多轮追问", "今年项目公司发电量排名 → 只看前5 → 秦皇岛呢 → 跟去年比呢 → 看看垃圾量", () -> {
            String conversationKey = "core-regression:user:scenario-10";
            stateManager.clearState(conversationKey);

            stateManager.applyState(conversationKey,
                    plan("DATA_QUERY", List.of("发电量"), List.of("项目公司"), "今年", "RANKING"),
                    "今年项目公司发电量排名");
            ConversationAnalysisState round1 = requireState(conversationKey);
            assertEquals(GENERATION_CODE, round1.currentMetricCode());
            assertEquals("RANKING", round1.lastAnalysisType());
            assertEquals("GROUP", round1.currentScopeType());
            assertEquals("今年", round1.timeExpression());

            stateManager.applyState(conversationKey,
                    plan("DATA_QUERY", List.of(), List.of(), "", "FACT"), "只看前5");
            ConversationAnalysisState round2 = requireState(conversationKey);
            assertEquals(5, round2.topN());
            assertEquals(GENERATION_CODE, round2.currentMetricCode());
            assertEquals("今年", round2.timeExpression());
            assertEquals("RANKING", round2.lastAnalysisType());

            stateManager.applyState(conversationKey,
                    plan("DATA_QUERY", List.of(), List.of("秦皇岛"), "", "FACT"), "秦皇岛呢");
            ConversationAnalysisState round3 = requireState(conversationKey);
            assertEquals(QINHUANGDAO_CODE, round3.focusOrgCode());
            assertEquals("PROJECT_COMPANY", round3.currentScopeType());
            assertNull(round3.topN());

            stateManager.applyState(conversationKey,
                    plan("DATA_QUERY", List.of(), List.of(), "去年", "FACT"), "跟去年比呢");
            ConversationAnalysisState round4 = requireState(conversationKey);
            assertEquals("YOY", round4.comparisonType());
            assertEquals("今年", round4.timeExpression());
            assertEquals(QINHUANGDAO_CODE, round4.focusOrgCode());

            stateManager.applyState(conversationKey,
                    plan("DATA_QUERY", List.of(), List.of(), "", "FACT"), "看看垃圾量");
            ConversationAnalysisState round5 = requireState(conversationKey);
            assertEquals(GARBAGE_CODE, round5.currentMetricCode());
            assertEquals("生活垃圾入厂量", round5.currentMetricName());
            assertEquals(QINHUANGDAO_CODE, round5.focusOrgCode());
            assertEquals("YOY", round5.comparisonType());
            assertEquals("今年", round5.timeExpression());
        });
    }

    private RunResult execute(String query, AnalysisPlanDto expectedPlan, boolean composite) {
        expectRoutePlan(expectedPlan);
        if (composite) {
            expectDocument();
        }
        recordingDispatcher.resetEvents();
        executionController.execute(
                new QueryExecuteRequest(
                        query, "core-regression-conversation-" + Integer.toHexString(query.hashCode())),
                "core-regression-user",
                String.join(",", allowedOrgCodes));
        assertTrue(recordingDispatcher.eventNames().contains("analysis_result"),
                "SSE 缺少 analysis_result 事件");
        assertTrue(recordingDispatcher.eventNames().contains("completed"),
                "SSE 缺少 completed 事件");
        PresentationResponseV2 response = recordingDispatcher.eventPayloads().stream()
                .filter(payload -> payload instanceof Map<?, ?>)
                .map(payload -> ((Map<?, ?>) payload).get("response"))
                .filter(PresentationResponseV2.class::isInstance)
                .map(PresentationResponseV2.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("SSE analysis_result 没有合法 response"));
        return new RunResult(expectedPlan, response);
    }

    private void expectRoutePlan(AnalysisPlanDto plan) {
        String content;
        try {
            content = objectMapper.writeValueAsString(plan);
        } catch (Exception exception) {
            throw new AssertionError("无法序列化回归路由计划", exception);
        }
        try {
            vllmServer.expect(requestTo(VLLM_BASE_URL + "/chat/completions"))
                    .andExpect(jsonPath("$.messages[0].content", containsString("单行JSON")))
                    .andRespond(withSuccess(
                            "{\"choices\":[{\"message\":{\"content\":"
                                    + objectMapper.writeValueAsString(content)
                                    + "}}]}",
                            MediaType.APPLICATION_JSON));
        } catch (Exception exception) {
            throw new AssertionError("无法构造回归路由响应", exception);
        }
    }

    private void expectDocument() {
        vllmServer.expect(requestTo(VLLM_BASE_URL + "/chat/completions"))
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"content\":"
                                + objectMapper.valueToTree(
                                "一、总体运行态势\n\n基于已核验事实。\n\n"
                                        + "二、区域分布与结构特征\n\n数据见图表。\n\n"
                                        + "三、重点关注线索与管理建议\n\n持续跟踪待核实事项。")
                                + "}}]}",
                        MediaType.APPLICATION_JSON));
    }

    private AnalysisPlanDto plan(
            String primaryIntent,
            List<String> metrics,
            List<String> organizations,
            String time,
            String analysisType) {
        return new AnalysisPlanDto(
                primaryIntent, metrics, organizations, time, analysisType,
                "COMPOSITE".equals(primaryIntent)
                        ? List.of("QUERY_DATA", "DRAFT_BRIEF")
                        : List.of());
    }

    private EvidenceV2Dto confirmedFact(
            PresentationResponseV2 response,
            String subject,
            String period) {
        return response.content().evidence().stream()
                .filter(item -> "confirmedFacts".equals(item.layer())
                        && subject.equals(item.subject()) && period.equals(item.period()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "缺少事实: subject=" + subject + ", period=" + period));
    }

    private ConversationAnalysisState requireState(String conversationKey) {
        ConversationAnalysisState state = stateManager.getState(conversationKey);
        assertNotNull(state, "多轮状态不应为空");
        return state;
    }

    private BigDecimal realValue(String metricCode, String orgCode, YearMonth period) {
        return realValues(metricCode, period.atDay(1), period.atEndOfMonth(), List.of(orgCode))
                .getOrDefault(orgCode, BigDecimal.ZERO);
    }

    private Map<String, BigDecimal> realValues(
            String metricCode,
            LocalDate startDate,
            LocalDate endDate,
            List<String> orgCodes) {
        if (orgCodes.isEmpty()) {
            return Map.of();
        }
        LocalDate effectiveStart = startDate.isBefore(LocalDate.of(2024, 1, 1))
                ? LocalDate.of(2024, 1, 1) : startDate;
        LocalDate effectiveEnd = endDate.isAfter(DATA_CUTOFF) ? DATA_CUTOFF : endDate;
        if (effectiveStart.isAfter(effectiveEnd)) {
            return Map.of();
        }
        String source = REAL_PARTITION_TABLES.stream()
                .filter(table -> covers(table, effectiveStart, effectiveEnd))
                .map(table -> "(SELECT \"newIndicator\", \"ZBRQ\", \"ZBZ\", \"orgcode\" "
                        + "FROM MSOKFPT.\"" + table + "\" "
                        + "WHERE \"ZBRQ\" >= :startDate AND \"ZBRQ\" <= :endDate)")
                .collect(Collectors.joining("\nUNION ALL\n"));
        if (source.isBlank()) {
            throw new AssertionError("实库基线没有覆盖日期范围 " + effectiveStart + " 至 " + effectiveEnd);
        }
        String unitConversion = GENERATION_CODE.equals(metricCode) ? " / 10000" : "";
        String sql = "SELECT \"orgcode\", "
                + "COALESCE(SUM(CASE WHEN \"newIndicator\" = :metricCode "
                + "AND \"ZBZ\" ~ '" + NUMERIC_TEXT_REGEX + "' "
                + "THEN CAST(\"ZBZ\" AS NUMERIC(18,2)) ELSE 0.00 END)"
                + unitConversion + ", 0) AS value "
                + "FROM (" + source + ") real_rows "
                + "WHERE \"orgcode\" IN (:orgCodes) GROUP BY \"orgcode\"";
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("metricCode", metricCode);
        parameters.put("startDate", effectiveStart);
        parameters.put("endDate", effectiveEnd);
        parameters.put("orgCodes", orgCodes);
        return baselineJdbc.query(sql, parameters, (ResultSet resultSet) -> {
            Map<String, BigDecimal> values = new LinkedHashMap<>();
            while (resultSet.next()) {
                values.put(resultSet.getString("orgcode"), resultSet.getBigDecimal("value"));
            }
            return values;
        });
    }

    private List<OrgRow> loadProductionOrganizations() {
        String sql = "SELECT formal_code, short_name, region "
                + "FROM MSOKFPT.\"dim_org_mapping\" "
                + "WHERE business_type = :businessType AND is_production_plant = TRUE "
                + "AND formal_code IS NOT NULL ORDER BY formal_code";
        return baselineJdbc.query(sql, Map.of("businessType", BUSINESS_TYPE), (ResultSet resultSet) -> {
            List<OrgRow> rows = new ArrayList<>();
            while (resultSet.next()) {
                rows.add(new OrgRow(
                        resultSet.getString("formal_code"),
                        resultSet.getString("short_name"),
                        resultSet.getString("region")));
            }
            return rows;
        });
    }

    private static boolean covers(String table, LocalDate start, LocalDate end) {
        String[] parts = table.substring("CGXTAPPMISDate_".length()).split("_");
        int year = Integer.parseInt(parts[0]);
        int half = Integer.parseInt(parts[1]);
        LocalDate partitionStart = LocalDate.of(year, half == 6 ? 1 : 7, 1);
        LocalDate partitionEnd = LocalDate.of(year, half == 6 ? 6 : 12,
                half == 6 ? 30 : 31);
        return !partitionEnd.isBefore(start) && !partitionStart.isAfter(end);
    }

    private static void assertDecimalEquals(BigDecimal expected, BigDecimal actual, String message) {
        assertNotNull(expected, message + " 的实库基线为空");
        assertNotNull(actual, message + " 的系统结果为空");
        assertEquals(0, expected.compareTo(actual), message + " 不一致，expected="
                + expected.toPlainString() + ", actual=" + actual.toPlainString());
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        return value == null ? null : new BigDecimal(String.valueOf(value));
    }

    private String readReconciliationReport() throws Exception {
        for (Path path : List.of(
                Path.of("docs", "DATABASE_RECONCILIATION_REPORT.md"),
                Path.of("..", "docs", "DATABASE_RECONCILIATION_REPORT.md"))) {
            if (Files.exists(path)) {
                return Files.readString(path);
            }
        }
        throw new AssertionError("找不到强制客观基准 docs/DATABASE_RECONCILIATION_REPORT.md");
    }

    private void runScenario(String id, String query, org.junit.jupiter.api.function.Executable executable)
            throws Throwable {
        long start = System.nanoTime();
        try {
            executable.execute();
            scenarioLogs.add(new ScenarioLog(id, id, query, "PASS",
                    elapsedMillis(start), "real Kingbase baseline matched"));
        } catch (Throwable failure) {
            scenarioLogs.add(new ScenarioLog(id, id, query, "FAIL",
                    elapsedMillis(start), failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage()));
            throw failure;
        }
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private record RealDbConfig(
            String url,
            String username,
            String password,
            String driverClassName) {

        private static RealDbConfig fromEnvironment() {
            return new RealDbConfig(
                    envOrDefault("DATAQUERY_DB_URL", "jdbc:kingbase8://172.18.10.2:54321/MSOKFPT"),
                    envOrDefault("DATAQUERY_DB_USERNAME", "Ai"),
                    System.getenv("DATAQUERY_DB_PASSWORD"),
                    envOrDefault("DATAQUERY_DB_DRIVER", "com.kingbase8.Driver"));
        }

        private boolean passwordPresent() {
            return password != null && !password.isBlank();
        }

        private static String envOrDefault(String name, String fallback) {
            String value = System.getenv(name);
            return value == null || value.isBlank() ? fallback : value.trim();
        }
    }

    private record OrgRow(String formalCode, String shortName, String region) {
        private OrgRow {
            Objects.requireNonNull(formalCode, "formalCode");
            Objects.requireNonNull(shortName, "shortName");
        }
    }

    /** 将真实控制器的异步执行折叠为可断言的 SSE 事件流，不替换业务数据源。 */
    private static final class RecordingSseStreamDispatcher extends SseStreamDispatcher {

        private final List<String> eventNames = new ArrayList<>();
        private final List<Object> eventPayloads = new ArrayList<>();

        @Override
        public SseEmitter createEmitter() {
            return new SseEmitter(0L);
        }

        @Override
        public void dispatch(SseEmitter emitter, Runnable task) {
            task.run();
        }

        @Override
        public boolean send(SseEmitter emitter, String eventName, Object payload) {
            eventNames.add(eventName);
            eventPayloads.add(payload);
            return true;
        }

        @Override
        public void complete(SseEmitter emitter) {
            // 事件已经在 send 中捕获；测试不需要触发 servlet 生命周期回调。
        }

        private void resetEvents() {
            eventNames.clear();
            eventPayloads.clear();
        }

        private List<String> eventNames() {
            return List.copyOf(eventNames);
        }

        private List<Object> eventPayloads() {
            return List.copyOf(eventPayloads);
        }
    }

    private record RunResult(
            AnalysisPlanDto classifiedPlan,
            PresentationResponseV2 response) {
    }

    private record ScenarioLog(
            String id,
            String name,
            String query,
            String status,
            long elapsedMillis,
            String detail) {
    }

    private static final List<ScenarioDefinition> SCENARIOS = List.of(
            new ScenarioDefinition("场景1-事实查询", "秦皇岛8月份发电量是多少"),
            new ScenarioDefinition("场景2-多公司排名", "今年项目公司发电量排名"),
            new ScenarioDefinition("场景3-趋势变化", "秦皇岛最近半年发电量怎么样"),
            new ScenarioDefinition("场景4-公司画像", "秦皇岛今年怎么样"),
            new ScenarioDefinition("场景5-异常发现", "最近哪些公司需要重点关注"),
            new ScenarioDefinition("场景6-原因线索", "秦皇岛8月为什么发电量下降"),
            new ScenarioDefinition("场景7-综合比较", "秦皇岛和保定今年谁表现更好"),
            new ScenarioDefinition("场景8-大区分析", "雄安大区今年整体怎么样"),
            new ScenarioDefinition("场景9-经营简报", "查近三个月垃圾入厂量，帮我做一份分析简报"),
            new ScenarioDefinition("场景10-连续多轮追问", "今年项目公司发电量排名 → 只看前5 → 秦皇岛呢 → 跟去年比呢 → 看看垃圾量"));

    private record ScenarioDefinition(String id, String query) {
    }
}
