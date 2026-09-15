package com.huanbao.dataquery.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huanbao.dataquery.config.VllmProperties;
import com.huanbao.dataquery.core.analysis.AnalysisEngine;
import com.huanbao.dataquery.core.analysis.AnalysisEvidenceResult;
import com.huanbao.dataquery.core.analysis.ClueItem;
import com.huanbao.dataquery.core.analysis.EvidenceChainBuilder;
import com.huanbao.dataquery.core.analysis.FactItem;
import com.huanbao.dataquery.core.analysis.RankingItem;
import com.huanbao.dataquery.core.analysis.RankingResult;
import com.huanbao.dataquery.core.engine.CalculationEngine;
import com.huanbao.dataquery.core.engine.CalculationResult;
import com.huanbao.dataquery.core.engine.MetricDecomposer;
import com.huanbao.dataquery.core.repository.KingbaseQueryExecutor;
import com.huanbao.dataquery.core.repository.PartitionTablePruner;
import com.huanbao.dataquery.core.spi.DataQueryRequest;
import com.huanbao.dataquery.core.spi.EntityMapping;
import com.huanbao.dataquery.core.spi.MetricDefinition;
import com.huanbao.dataquery.core.state.ConversationStateManager;
import com.huanbao.dataquery.domain.ops.OpsDomainSemanticProvider;
import com.huanbao.dataquery.domain.ops.OpsOrganization;
import com.huanbao.dataquery.domain.ops.OpsSqlBuildStrategy;
import com.huanbao.dataquery.router.AnalysisPlanDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 问数查库到办公拟写简报的双阶段流水线。
 *
 * <p>数据库查询、加权计算、比较和排名全部在确定性阶段完成。vLLM 只接收已经
 * 结构化的事实，不参与数值计算；公文服务不可用时，仍返回完整图表和明细。</p>
 */
@Service
public final class DataToDocPipelineService {

    public static final int DOCUMENT_REQUEST_TIMEOUT_SECONDS = 10;
    public static final String DEGRADED_MESSAGE =
            "简报生成稍有延迟，您可先查阅右侧图表与明细数据";

    private static final Logger LOGGER = LoggerFactory.getLogger(DataToDocPipelineService.class);
    private static final DateTimeFormatter PERIOD_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final Pattern RECENT_PATTERN = Pattern.compile(
            "近(?:最)?\\s*(半|\\d+|[一二三四五六七八九十百千万两]+)\\s*(个?月|年)");
    private static final Pattern RELATIVE_MONTH_PATTERN = Pattern.compile(
            "(今年|去年|前年)\\s*([0-9]{1,2})个?月?份?");
    private static final Pattern ABSOLUTE_MONTH_PATTERN = Pattern.compile(
            "(20[0-9]{2})年\\s*([0-9]{1,2})个?月?份?");
    private static final Pattern ABSOLUTE_YEAR_PATTERN = Pattern.compile(
            "20[0-9]{2}年(?:全年)?(?!\\s*[0-9]{1,2}个?月(?:份?)?)");
    private static final Pattern ISO_MONTH_PATTERN = Pattern.compile("20[0-9]{2}-[0-9]{2}");
    private static final Pattern MONTH_ONLY_PATTERN = Pattern.compile(
            "(?<![0-9])([0-9]{1,2})个?月(?:份?)?");
    private static final Pattern YEAR_PATTERN = Pattern.compile("(今年|去年|前年)(?:全年)?");

    private final ConversationStateManager stateManager;
    private final OpsSqlBuildStrategy sqlBuildStrategy;
    private final PartitionTablePruner tablePruner;
    private final KingbaseQueryExecutor queryExecutor;
    private final CalculationEngine calculationEngine;
    private final MetricDecomposer metricDecomposer;
    private final AnalysisEngine analysisEngine;
    private final OpsDomainSemanticProvider semanticProvider;
    private final RestClient vllmRestClient;
    private final ObjectMapper objectMapper;
    private final String model;
    private final Clock clock;

    /** Spring 装配入口，沿用现有 vllmRestClient。其 HTTP 读取超时由配置设为 10 秒。 */
    @Autowired
    public DataToDocPipelineService(
            ConversationStateManager stateManager,
            OpsDomainSemanticProvider semanticProvider,
            KingbaseQueryExecutor queryExecutor,
            @Qualifier("vllmRestClient") RestClient vllmRestClient,
            ObjectMapper objectMapper,
            VllmProperties vllmProperties) {
        this(
                stateManager,
                new OpsSqlBuildStrategy(semanticProvider),
                new PartitionTablePruner(),
                queryExecutor,
                new CalculationEngine(semanticProvider),
                new AnalysisEngine(),
                semanticProvider,
                vllmRestClient,
                objectMapper,
                vllmProperties == null ? "Qwen3.8-27B" : vllmProperties.getModel(),
                Clock.system(ZoneId.of("Asia/Shanghai")));
    }

    /** 便于单元测试和离线编排使用的完整依赖构造器。 */
    public DataToDocPipelineService(
            ConversationStateManager stateManager,
            OpsSqlBuildStrategy sqlBuildStrategy,
            PartitionTablePruner tablePruner,
            KingbaseQueryExecutor queryExecutor,
            CalculationEngine calculationEngine,
            AnalysisEngine analysisEngine,
            OpsDomainSemanticProvider semanticProvider,
            RestClient vllmRestClient,
            ObjectMapper objectMapper,
            String model,
            Clock clock) {
        this.stateManager = Objects.requireNonNull(stateManager, "stateManager");
        this.sqlBuildStrategy = Objects.requireNonNull(sqlBuildStrategy, "sqlBuildStrategy");
        this.tablePruner = Objects.requireNonNull(tablePruner, "tablePruner");
        this.queryExecutor = Objects.requireNonNull(queryExecutor, "queryExecutor");
        this.calculationEngine = Objects.requireNonNull(calculationEngine, "calculationEngine");
        this.metricDecomposer = new MetricDecomposer(semanticProvider);
        this.analysisEngine = Objects.requireNonNull(analysisEngine, "analysisEngine");
        this.semanticProvider = Objects.requireNonNull(semanticProvider, "semanticProvider");
        this.vllmRestClient = Objects.requireNonNull(vllmRestClient, "vllmRestClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.model = model == null || model.isBlank() ? "Qwen3.8-27B" : model.trim();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 执行完整流水线。传入的 plan 是路由器本轮产物，方法会先让状态机合并上下文，
     * 后续只消费状态机返回的最新计划。
     */
    public CompositeExecutionResult execute(
            String conversationKey,
            AnalysisPlanDto plan,
            String rawQuery,
            Collection<String> allowedOrgs) {
        Objects.requireNonNull(plan, "plan");
        Collection<String> normalizedAllowedOrgs = requireAllowedOrgs(allowedOrgs);
        AnalysisPlanDto latestPlan = stateManager.applyState(conversationKey, plan, rawQuery);
        List<MetricDefinition> metrics = resolveMetrics(latestPlan);
        MetricDefinition primaryMetric = metrics.get(0);
        Integer topN = latestPlan.topN();
        List<YearMonth> targetPeriods = resolveTargetPeriods(
                latestPlan.timeExpression(), rawQuery);
        List<SubjectScope> subjects = resolveSubjects(latestPlan, normalizedAllowedOrgs);
        String queryEntity = resolveQueryEntity(latestPlan);
        boolean ranking = "RANKING".equalsIgnoreCase(latestPlan.analysisType());
        boolean yearToDate = shouldUseYearToDate(latestPlan, rawQuery);

        Set<YearMonth> queryPeriods = comparisonPeriods(targetPeriods);
        MatrixFetchResult matrix = fetchValues(
                metrics,
                subjects,
                queryPeriods,
                normalizedAllowedOrgs,
                queryEntity,
                yearToDate);
        subjects = subjectsWithDatabaseNames(subjects, matrix.subjectNames());
        Map<String, Map<YearMonth, Map<String, CalculationResult>>> values = remapSubjects(
                matrix.values(), matrix.originalSubjects(), subjects);

        List<StructuredFactPayload> factPayloads = new ArrayList<>();
        List<Map<String, Object>> tableRows = new ArrayList<>();
        List<YearMonth> outputPeriods = ranking
                ? List.of(targetPeriods.get(targetPeriods.size() - 1))
                : targetPeriods;
        for (MetricDefinition metric : metrics) {
            for (YearMonth period : outputPeriods) {
                RankingResult rankingResult = ranking
                        ? rankingForPeriod(subjects, values, period, metric.indicatorCode())
                        : new RankingResult(List.of());
                List<SubjectScope> outputSubjects = ranking
                        ? rankedSubjects(subjects, rankingResult, topN)
                        : subjects;
                for (SubjectScope subject : outputSubjects) {
                    CalculationResult current = resultAt(
                            values, subject.subject(), period, metric.indicatorCode());
                    if (ranking && current.value() == null) {
                        current = new CalculationResult(
                                BigDecimal.ZERO, "0", CalculationResult.NO_DATA);
                    }
                    BigDecimal currentValue = current.value();
                    BigDecimal yoy = analysisEngine.calculateYoY(
                            currentValue,
                            valueAt(values, subject.subject(), period.minusYears(1), metric.indicatorCode()));
                    BigDecimal mom = analysisEngine.calculateMoM(
                            currentValue,
                            valueAt(values, subject.subject(), period.minusMonths(1), metric.indicatorCode()));
                    RankingItem rank = findRankingItem(rankingResult, subject.subject());

                    AnalysisEvidenceResult evidenceResult = buildEvidence(
                            subject.subject(), metric.formalName(), period, currentValue, yoy, mom, rank,
                            isReasonQuery(rawQuery));
                    StructuredFactPayload payload = new StructuredFactPayload(
                            subject.subject(),
                            metric.formalName(),
                            period.toString(),
                            currentValue,
                            yoy,
                            mom,
                            rank == null ? null : rank.rank(),
                            rank == null ? null : rank.previousRank(),
                            rank == null ? null : rank.rankChange(),
                            evidenceResult.confirmedFacts(),
                            evidenceResult.correlatedClues(),
                            evidenceResult.pendingVerification());
                    factPayloads.add(payload);
                    tableRows.add(toTableRow(payload, current));
                }
            }
        }

        Map<String, Object> chartConfig = ranking
                ? buildRankingChart(
                        primaryMetric, subjects, values, targetPeriods.get(targetPeriods.size() - 1), topN)
                : buildTrendChart(primaryMetric, subjects, values, targetPeriods);
        boolean needDocument = "COMPOSITE".equalsIgnoreCase(latestPlan.primaryIntent())
                || latestPlan.subTasks().contains("DRAFT_BRIEF")
                || "REPORT".equalsIgnoreCase(latestPlan.analysisType());
        String documentMarkdown = needDocument ? generateDocument(latestPlan, factPayloads) : "";
        boolean degraded = documentMarkdown.isEmpty();
        List<String> anomalySubjects = detectAnomalySubjects(
                latestPlan, targetPeriods, subjects, values, primaryMetric.indicatorCode());
        return new CompositeExecutionResult(
                latestPlan,
                chartConfig,
                tableRows,
                documentMarkdown,
                degraded,
                degraded ? DEGRADED_MESSAGE : "",
                factPayloads,
                anomalySubjects);
    }

    /** 构造公文阶段 Prompt，供诊断和单元测试检查事实注入及硬约束。 */
    public String buildDocumentPrompt(
            AnalysisPlanDto plan,
            List<StructuredFactPayload> factPayloads) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(factPayloads, "factPayloads");
        String factsJson;
        try {
            factsJson = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(factPayloads);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize structured facts", exception);
        }
        return "你是国企资深公文秘书，请依据给定的结构化事实起草经营运行分析简报。\n"
                + "你的唯一数据来源是事实 JSON。所有数字、单位、时间、同比、环比和排名必须 100% 忠实于事实，"
                + "严禁捏造、补齐、四舍五入改写或推断任何数据；事实为空或标记不可比时，必须如实写明，"
                + "遇到停运不可比只能表述为停运不可比或基期缺失，不得擅自补数。\n"
                + "证据规则：confirmedFacts 是已确认事实，correlatedClues 只能写成相关线索，"
                + "pendingVerification 只能写成待核实事项，禁止把线索写成已证实因果。\n"
                + "正文必须严格使用以下结构：\n"
                + "一、总体运行态势\n"
                + "二、区域分布与结构特征\n"
                + "三、重点关注线索与管理建议\n"
                + "管理建议不得新增事实数字。只输出公文正文 Markdown，不输出 JSON、SQL、Prompt 或思考过程。\n"
                + "路由计划：\n"
                + planJson(plan)
                + "\n结构化事实 JSON：\n"
                + factsJson;
    }

    private String generateDocument(AnalysisPlanDto plan, List<StructuredFactPayload> facts) {
        String prompt = buildDocumentPrompt(plan, facts);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);
        request.put("messages", List.of(
                Map.of("role", "system", "content", prompt),
                Map.of("role", "user", "content", "请严格依据上述事实输出简报正文。")));
        request.put("temperature", 0.1D);
        request.put("max_tokens", 1800);
        request.put("stream", false);

        try {
            String responseBody = vllmRestClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(String.class);
            String content = stripThink(extractCompletionText(responseBody));
            if (content.isBlank()) {
                throw new IllegalStateException("vLLM returned an empty document");
            }
            return content;
        } catch (Exception exception) {
            // RestClient 的读取超时由 vllmRestClient 的 request factory 硬限制在 10 秒内。
            LOGGER.warn("Document generation unavailable after {} seconds; keep data result",
                    DOCUMENT_REQUEST_TIMEOUT_SECONDS, exception.getClass().getSimpleName());
            return "";
        }
    }

    private MatrixFetchResult fetchValues(
            List<MetricDefinition> metrics,
            List<SubjectScope> subjects,
            Set<YearMonth> periods,
            Collection<String> allowedOrgs,
            String queryEntity,
            boolean yearToDate) {
        if (periods.isEmpty()) {
            throw new IllegalArgumentException("periods must not be empty");
        }
        List<String> atomicMetricCodes = metrics.stream()
                .flatMap(metric -> metricDecomposer.decompose(metric.indicatorCode())
                        .atomicMetricCodes().stream())
                .distinct()
                .toList();
        YearMonth firstPeriod = periods.stream().min(Comparator.naturalOrder()).orElseThrow();
        YearMonth lastPeriod = periods.stream().max(Comparator.naturalOrder()).orElseThrow();
        PartitionTablePruner.PruningResult pruning = tablePruner.prune(
                firstPeriod.atDay(1), lastPeriod.atEndOfMonth());
        List<String> matrixOrgCodes = subjects.stream()
                .flatMap(subject -> subject.organizationCodes().stream())
                .distinct()
                .toList();
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("startDate", pruning.effectiveStartDate().toString());
        filters.put("endDate", pruning.effectiveEndDate().toString());
        filters.put("matrixOrgCodes", matrixOrgCodes);
        filters.put("matrixMetricCodes", atomicMetricCodes);
        DataQueryRequest request = new DataQueryRequest(
                metrics.get(0).formalName(), queryEntity, filters);
        String sql = sqlBuildStrategy.buildMatrixSql(request, atomicMetricCodes);
        List<Map<String, Object>> rows = queryExecutor.queryForList(
                sql, filters, allowedOrgs);

        Map<String, Map<YearMonth, Map<String, BigDecimal>>> rawByOrg = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> normalized = normalizeKeys(row);
            String orgCode = textValue(normalized.get("orgcode"));
            String periodText = textValue(normalized.get("period"));
            if (orgCode == null || periodText == null) {
                continue;
            }
            YearMonth period = parseReturnedPeriod(periodText);
            Map<String, BigDecimal> rawValues = rawByOrg
                    .computeIfAbsent(orgCode, ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(period, ignored -> new LinkedHashMap<>());
            for (String metricCode : atomicMetricCodes) {
                BigDecimal raw = decimalValue(normalized.get("val_" + metricCode));
                if (raw != null) {
                    rawValues.merge(metricCode, raw, BigDecimal::add);
                }
            }
        }

        Map<String, Map<YearMonth, Map<String, BigDecimal>>> subjectRaw = new LinkedHashMap<>();
        for (SubjectScope subject : subjects) {
            Map<YearMonth, Map<String, BigDecimal>> monthly = new LinkedHashMap<>();
            rawByOrg.forEach((orgCode, orgPeriods) -> {
                if (!subject.organizationCodes().contains(orgCode)) {
                    return;
                }
                orgPeriods.forEach((period, rawValues) -> {
                    Map<String, BigDecimal> target = monthly.computeIfAbsent(
                            period, ignored -> new LinkedHashMap<>());
                    rawValues.forEach((code, value) -> target.merge(code, value, BigDecimal::add));
                });
            });
            subjectRaw.put(subject.subject(), yearToDate
                    ? cumulativeByYear(monthly)
                    : monthly);
        }

        Map<String, Map<YearMonth, Map<String, CalculationResult>>> values = new LinkedHashMap<>();
        for (SubjectScope subject : subjects) {
            Map<YearMonth, Map<String, CalculationResult>> subjectValues = new LinkedHashMap<>();
            for (YearMonth period : periods.stream().sorted().toList()) {
                Map<String, BigDecimal> raw = subjectRaw
                        .getOrDefault(subject.subject(), Map.of())
                        .getOrDefault(period, Map.of());
                Map<String, CalculationResult> metricValues = new LinkedHashMap<>();
                for (MetricDefinition metric : metrics) {
                    metricValues.put(metric.indicatorCode(), calculationEngine.calculateResult(
                            metric.formalName(), raw));
                }
                subjectValues.put(period, Map.copyOf(metricValues));
            }
            values.put(subject.subject(), subjectValues);
        }
        Map<String, String> subjectNames = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> normalized = normalizeKeys(row);
            String orgCode = textValue(normalized.get("orgcode"));
            String shortName = textValue(normalized.get("short_name"));
            if (orgCode != null && shortName != null) {
                subjectNames.putIfAbsent(orgCode, shortName);
            }
        }
        return new MatrixFetchResult(values, subjects, subjectNames);
    }

    private List<MetricDefinition> resolveMetrics(AnalysisPlanDto plan) {
        if (plan.metricInputs().isEmpty()) {
            throw new IllegalArgumentException("The analysis plan has no metric input");
        }
        Map<String, MetricDefinition> metrics = new LinkedHashMap<>();
        for (String input : plan.metricInputs()) {
            MetricDefinition metric = semanticProvider.getMetric(input);
            if (metric == null) {
                throw new IllegalArgumentException("Unknown OPS metric: " + input);
            }
            metrics.putIfAbsent(metric.indicatorCode(), metric);
        }
        return List.copyOf(metrics.values());
    }

    private List<SubjectScope> resolveSubjects(
            AnalysisPlanDto plan,
            Collection<String> allowedOrgs) {
        String requested = plan.orgInputs().isEmpty() ? "集团" : plan.orgInputs().get(0);
        EntityMapping mapping = semanticProvider.resolveEntity(requested);
        boolean ranking = "RANKING".equalsIgnoreCase(plan.analysisType());
        boolean companyList = "GROUP".equalsIgnoreCase(mapping.entityType())
                && (ranking || "TREND".equalsIgnoreCase(plan.analysisType()))
                && !plan.orgInputs().isEmpty();
        if (companyList) {
            return allAuthorizedSubjects(allowedOrgs);
        }
        if (plan.orgInputs().size() > 1) {
            List<SubjectScope> scopes = new ArrayList<>();
            Set<String> seenCodes = new LinkedHashSet<>();
            for (String input : plan.orgInputs()) {
                EntityMapping company = semanticProvider.resolveEntity(input);
                if (!"PROJECT_COMPANY".equalsIgnoreCase(company.entityType())) {
                    throw new IllegalArgumentException("Only project companies can be compared side by side");
                }
                OpsOrganization organization = semanticProvider.findOrganization(company.canonicalName())
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Unresolved OPS project company: " + company.canonicalName()));
                if (organization.formalCode() != null
                        && allowedOrgs.contains(organization.formalCode())
                        && seenCodes.add(organization.formalCode())) {
                    scopes.add(new SubjectScope(
                            organization.shortName(), organization.formalCode(), List.of(organization.formalCode())));
                }
            }
            if (scopes.isEmpty()) {
                throw new IllegalArgumentException("No compared company is within the authorized scope");
            }
            return List.copyOf(scopes);
        }
        if ("REGION".equalsIgnoreCase(mapping.entityType())) {
            // 区域归属以实库 dim_org_mapping 为准。静态语义资产中部分 formal_code
            // 为空，不能用它裁剪授权集合，否则会漏掉区域内的真实电厂。
            return List.of(new SubjectScope(
                    mapping.canonicalName(), mapping.canonicalName(), List.copyOf(allowedOrgs)));
        }
        if ("GROUP".equalsIgnoreCase(mapping.entityType())) {
            return List.of(new SubjectScope("集团", "集团", List.copyOf(allowedOrgs)));
        }
        OpsOrganization organization = semanticProvider.findOrganization(mapping.canonicalName())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unresolved OPS project company: " + mapping.canonicalName()));
        if (organization.formalCode() == null || !allowedOrgs.contains(organization.formalCode())) {
            throw new IllegalArgumentException("Requested company is outside the authorized scope");
        }
        return List.of(new SubjectScope(
                organization.shortName(), organization.shortName(), List.of(organization.formalCode())));
    }

    private List<SubjectScope> allAuthorizedSubjects(Collection<String> allowedOrgs) {
        Map<String, SubjectScope> knownSubjects = new LinkedHashMap<>();
        semanticProvider.getOrganizations().stream()
                .filter(organization -> organization.formalCode() != null
                        && allowedOrgs.contains(organization.formalCode()))
                .sorted(Comparator.comparing(
                        organization -> organization.shortName().toLowerCase(Locale.ROOT)))
                .forEach(organization -> knownSubjects.put(
                        organization.formalCode(),
                        new SubjectScope(
                                organization.shortName(),
                                organization.shortName(),
                                List.of(organization.formalCode()))));
        // 允许集合是实库权限真相，语义资产只负责已知别名。未能静态反查名称的
        // 编码先作为占位主体，矩阵 SQL 返回的 dim_org_mapping.short_name 再补齐显示名。
        for (String code : allowedOrgs) {
            knownSubjects.putIfAbsent(code, new SubjectScope(code, code, List.of(code)));
        }
        List<SubjectScope> subjects = knownSubjects.values().stream()
                .sorted(Comparator.comparing(subject -> subject.subject().toLowerCase(Locale.ROOT)))
                .toList();
        if (subjects.isEmpty()) {
            throw new IllegalArgumentException("No authorized OPS project company is available");
        }
        return subjects;
    }

    private static String resolveQueryEntity(AnalysisPlanDto plan) {
        return plan.orgInputs().size() == 1
                ? plan.orgInputs().get(0)
                : "集团";
    }

    private static List<SubjectScope> subjectsWithDatabaseNames(
            List<SubjectScope> subjects,
            Map<String, String> subjectNames) {
        return subjects.stream()
                .map(subject -> {
                    if (subject.organizationCodes().size() != 1
                            || "集团".equals(subject.entityName())
                            || subject.entityName().endsWith("大区")) {
                        return subject;
                    }
                    String name = subjectNames.get(subject.organizationCodes().get(0));
                    return name == null || name.isBlank()
                            ? subject
                            : new SubjectScope(name, subject.entityName(), subject.organizationCodes());
                })
                .toList();
    }

    private static Map<String, Map<YearMonth, Map<String, CalculationResult>>>
            remapSubjects(
                    Map<String, Map<YearMonth, Map<String, CalculationResult>>> values,
                    List<SubjectScope> originalSubjects,
                    List<SubjectScope> displaySubjects) {
        Map<String, Map<YearMonth, Map<String, CalculationResult>>> remapped = new LinkedHashMap<>();
        for (int index = 0; index < originalSubjects.size(); index++) {
            String original = originalSubjects.get(index).subject();
            String display = displaySubjects.get(index).subject();
            remapped.put(display, values.getOrDefault(original, Map.of()));
        }
        return remapped;
    }

    private List<String> organizationsForRegion(
            String region,
            Collection<String> allowedOrgs) {
        List<String> codes = semanticProvider.getOrganizations().stream()
                .filter(organization -> region.equals(organization.region()))
                .map(OpsOrganization::formalCode)
                .filter(Objects::nonNull)
                .filter(allowedOrgs::contains)
                .distinct()
                .toList();
        if (codes.isEmpty()) {
            throw new IllegalArgumentException("No authorized organization is available in region: " + region);
        }
        return codes;
    }

    private static boolean isYearExpression(String timeExpression, String rawQuery) {
        String expression = timeExpression == null || timeExpression.isBlank()
                ? rawQuery == null ? "" : rawQuery.trim()
                : timeExpression.trim();
        return YEAR_PATTERN.matcher(expression).find()
                || ABSOLUTE_YEAR_PATTERN.matcher(expression).find();
    }

    private static boolean shouldUseYearToDate(AnalysisPlanDto plan, String rawQuery) {
        if (!isYearExpression(plan.timeExpression(), rawQuery)) {
            return false;
        }
        // 排名和经营概览需要在最后一个期间展示年度累计值；普通事实/趋势结果
        // 返回月度增量，避免前端把累计序列再次求和。
        return "RANKING".equalsIgnoreCase(plan.analysisType())
                || "REPORT".equalsIgnoreCase(plan.analysisType())
                || "COMPOSITE".equalsIgnoreCase(plan.primaryIntent());
    }

    private List<YearMonth> resolveTargetPeriods(String timeExpression, String rawQuery) {
        String expression = timeExpression == null || timeExpression.isBlank()
                ? rawQuery == null ? "" : rawQuery.trim()
                : timeExpression.trim();
        LocalDate anchorDate = min(LocalDate.now(clock), tablePruner.maxDataDate());
        YearMonth anchor = YearMonth.from(anchorDate);
        Matcher recent = RECENT_PATTERN.matcher(expression);
        if (recent.find()) {
            int amount = "半".equals(recent.group(1)) ? 6 : parseChineseNumber(recent.group(1));
            if (amount <= 0) {
                throw new IllegalArgumentException("Invalid recent period: " + expression);
            }
            int months = "半".equals(recent.group(1))
                    ? 6
                    : "年".equals(recent.group(2)) ? amount * 12 : amount;
            return monthsBetween(anchor.minusMonths(months - 1L), anchor);
        }

        Matcher relativeMonth = RELATIVE_MONTH_PATTERN.matcher(expression);
        if (relativeMonth.find()) {
            int yearOffset = switch (relativeMonth.group(1)) {
                case "今年" -> 0;
                case "去年" -> -1;
                case "前年" -> -2;
                default -> throw new IllegalArgumentException("Unsupported relative year");
            };
            return List.of(YearMonth.of(anchor.getYear() + yearOffset,
                    parseMonth(relativeMonth.group(2), expression)));
        }

        Matcher absoluteMonth = ABSOLUTE_MONTH_PATTERN.matcher(expression);
        if (absoluteMonth.find()) {
            return List.of(YearMonth.of(
                    Integer.parseInt(absoluteMonth.group(1)),
                    parseMonth(absoluteMonth.group(2), expression)));
        }
        Matcher absoluteYear = ABSOLUTE_YEAR_PATTERN.matcher(expression);
        if (absoluteYear.find()) {
            int year = Integer.parseInt(absoluteYear.group().substring(0, 4));
            YearMonth first = YearMonth.of(year, 1);
            YearMonth maxAvailableMonth = YearMonth.from(tablePruner.maxDataDate());
            YearMonth last = min(YearMonth.of(year, 12), maxAvailableMonth);
            return monthsBetween(first, last);
        }
        if (ISO_MONTH_PATTERN.matcher(expression).find()) {
            try {
                return List.of(YearMonth.parse(
                        ISO_MONTH_PATTERN.matcher(expression).group(), PERIOD_FORMAT));
            } catch (DateTimeParseException exception) {
                throw new IllegalArgumentException("Invalid month: " + expression, exception);
            }
        }
        if (expression.contains("上季度") || expression.contains("本季度")) {
            int quarter = (anchor.getMonthValue() - 1) / 3 + 1;
            if (expression.contains("上季度")) {
                quarter--;
                if (quarter == 0) {
                    quarter = 4;
                    anchor = anchor.minusYears(1);
                }
            }
            YearMonth first = YearMonth.of(anchor.getYear(), (quarter - 1) * 3 + 1);
            return monthsBetween(first, first.plusMonths(2));
        }
        if (YEAR_PATTERN.matcher(expression).find()) {
            int offset = expression.contains("前年") ? -2 : expression.contains("去年") ? -1 : 0;
            YearMonth first = YearMonth.of(anchor.getYear() + offset, 1);
            YearMonth maxAvailableMonth = YearMonth.from(tablePruner.maxDataDate());
            YearMonth last = min(YearMonth.of(anchor.getYear() + offset, 12), maxAvailableMonth);
            return monthsBetween(first, last);
        }
        Matcher monthOnly = MONTH_ONLY_PATTERN.matcher(expression);
        if (monthOnly.find()) {
            return List.of(YearMonth.of(anchor.getYear(), parseMonth(monthOnly.group(1), expression)));
        }
        return List.of(anchor);
    }

    private Set<YearMonth> comparisonPeriods(List<YearMonth> targetPeriods) {
        LinkedHashSet<YearMonth> periods = new LinkedHashSet<>();
        for (YearMonth target : targetPeriods) {
            periods.add(target.minusMonths(1));
            periods.add(target.minusYears(1));
            periods.add(target);
        }
        return periods;
    }

    private static Map<YearMonth, Map<String, BigDecimal>> cumulativeByYear(
            Map<YearMonth, Map<String, BigDecimal>> monthly) {
        Map<YearMonth, Map<String, BigDecimal>> cumulative = new LinkedHashMap<>();
        Map<Integer, Map<String, BigDecimal>> accumulators = new LinkedHashMap<>();
        monthly.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    Map<String, BigDecimal> accumulator = accumulators.computeIfAbsent(
                            entry.getKey().getYear(), ignored -> new LinkedHashMap<>());
                    entry.getValue().forEach((code, value) -> accumulator.merge(code, value, BigDecimal::add));
                    cumulative.put(entry.getKey(), Map.copyOf(new LinkedHashMap<>(accumulator)));
                });
        return cumulative;
    }

    private List<String> detectAnomalySubjects(
            AnalysisPlanDto plan,
            List<YearMonth> targetPeriods,
            List<SubjectScope> subjects,
            Map<String, Map<YearMonth, Map<String, CalculationResult>>> values,
            String metricCode) {
        if (!"TREND".equalsIgnoreCase(plan.analysisType()) || subjects.size() <= 1) {
            return List.of();
        }
        List<String> anomalies = new ArrayList<>();
        for (SubjectScope subject : subjects) {
            List<BigDecimal> series = targetPeriods.stream()
                    .map(period -> valueAt(values, subject.subject(), period, metricCode))
                    .filter(Objects::nonNull)
                    .toList();
            boolean anomalous = analysisEngine.detectAnomaly(series).anomalous();
            if (anomalous) {
                anomalies.add(subject.subject());
            }
        }
        return List.copyOf(anomalies);
    }

    private RankingResult rankingForPeriod(
            List<SubjectScope> subjects,
            Map<String, Map<YearMonth, Map<String, CalculationResult>>> values,
            YearMonth period,
            String metricCode) {
        Map<String, BigDecimal> current = new LinkedHashMap<>();
        Map<String, BigDecimal> previous = new LinkedHashMap<>();
        for (SubjectScope subject : subjects) {
            BigDecimal currentValue = valueAt(values, subject.subject(), period, metricCode);
            BigDecimal previousValue = valueAt(
                    values, subject.subject(), period.minusMonths(1), metricCode);
            // 排名矩阵必须覆盖完整授权主体；本期没有可核验记录的公司按 0
            // 进入排序，表格仍保留其原始空值和质量语义。
            current.put(subject.subject(), currentValue == null ? BigDecimal.ZERO : currentValue);
            if (previousValue != null) {
                previous.put(subject.subject(), previousValue);
            }
        }
        return analysisEngine.calculateRanking(current, previous);
    }

    private List<SubjectScope> rankedSubjects(
            List<SubjectScope> subjects,
            RankingResult ranking,
            Integer topN) {
        if (topN == null) {
            return subjects;
        }
        Map<String, SubjectScope> subjectsByName = subjects.stream()
                .collect(Collectors.toMap(
                        SubjectScope::subject,
                        subject -> subject,
                        (left, right) -> left,
                        LinkedHashMap::new));
        return ranking.stream()
                .limit(topN)
                .map(RankingItem::company)
                .map(subjectsByName::get)
                .filter(Objects::nonNull)
                .toList();
    }

    private AnalysisEvidenceResult buildEvidence(
            String subject,
            String metric,
            YearMonth period,
            BigDecimal current,
            BigDecimal yoy,
            BigDecimal mom,
            RankingItem rank,
            boolean reasonQuery) {
        FactItem fact = new FactItem(
                subject,
                metric,
                period.toString(),
                current,
                yoy,
                mom,
                rank == null ? null : rank.rank(),
                rank == null ? null : rank.previousRank(),
                rank == null ? null : rank.rankChange());
        EvidenceChainBuilder builder = new EvidenceChainBuilder().addConfirmedFact(fact);
        if (yoy != null && mom != null) {
            builder.addCorrelatedClue(new ClueItem(
                    subject,
                    metric,
                    "同比与环比变化",
                    period.toString(),
                    yoy,
                    mom,
                    "同一指标的变化方向线索，仅作相关提示，不代表因果"));
        }
        if (current == null) {
            builder.addPendingVerification(
                    "本期数值缺失，无法形成可比结论；如涉及停运，请以停运或数据上报台账核实");
        }
        if (yoy == null) {
            builder.addPendingVerification("同比基期缺失或为 0，按不可比处理，不擅自推断原因");
        }
        if (mom == null) {
            builder.addPendingVerification("环比基期缺失或为 0，按不可比处理，不擅自推断原因");
        }
        if (reasonQuery) {
            builder.addPendingVerification("变化原因仅作为相关线索，需结合停运、检修和生产台账进一步核实");
        }
        return builder.build();
    }

    private static boolean isReasonQuery(String rawQuery) {
        return rawQuery != null && (rawQuery.contains("为什么") || rawQuery.contains("原因"));
    }

    private Map<String, Object> buildTrendChart(
            MetricDefinition metric,
            List<SubjectScope> subjects,
            Map<String, Map<YearMonth, Map<String, CalculationResult>>> values,
            List<YearMonth> periods) {
        Map<String, Object> chart = baseChart(
                "TREND", "line", metric.formalName() + "趋势", metric.unit());
        chart.put("xAxis", Map.of("type", "category", "data", periods.stream()
                .map(YearMonth::toString).toList()));
        chart.put("yAxis", Map.of("type", "value", "name", metric.unit()));
        List<Map<String, Object>> series = new ArrayList<>();
        for (SubjectScope subject : subjects) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("name", subject.subject());
            line.put("type", "line");
            line.put("data", periods.stream()
                    .map(period -> valueAt(values, subject.subject(), period, metric.indicatorCode())).toList());
            series.add(line);
        }
        chart.put("series", series);
        return chart;
    }

    private Map<String, Object> buildRankingChart(
            MetricDefinition metric,
            List<SubjectScope> subjects,
            Map<String, Map<YearMonth, Map<String, CalculationResult>>> values,
            YearMonth period,
            Integer topN) {
        Map<String, Object> chart = baseChart(
                "RANKING", "bar", metric.formalName() + "排名（" + period + "）", metric.unit());
        RankingResult ranking = rankingForPeriod(subjects, values, period, metric.indicatorCode());
        List<RankingItem> visibleRanking = ranking.stream()
                .limit(topN == null ? ranking.size() : topN)
                .toList();
        chart.put("xAxis", Map.of("type", "category", "data", visibleRanking.stream()
                .map(RankingItem::company).toList()));
        chart.put("yAxis", Map.of("type", "value", "name", metric.unit()));
        Map<String, Object> bar = new LinkedHashMap<>();
        bar.put("name", metric.formalName());
        bar.put("type", "bar");
        bar.put("data", visibleRanking.stream().map(RankingItem::value).toList());
        chart.put("series", List.of(bar));
        return chart;
    }

    private static Map<String, Object> baseChart(
            String chartType,
            String echartsType,
            String title,
            String unit) {
        Map<String, Object> chart = new LinkedHashMap<>();
        chart.put("chartType", chartType);
        chart.put("type", echartsType);
        chart.put("title", Map.of("text", title));
        chart.put("tooltip", Map.of("trigger", "axis"));
        chart.put("unit", unit);
        return chart;
    }

    private static Map<String, Object> toTableRow(
            StructuredFactPayload payload,
            CalculationResult calculation) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("subject", payload.subject());
        row.put("metric", payload.metric());
        row.put("period", payload.period());
        row.put("currentValue", payload.currentValue());
        row.put("displayValue", calculation.displayValue());
        row.put("yearOverYearPercent", payload.yearOverYearPercent());
        row.put("monthOverMonthPercent", payload.monthOverMonthPercent());
        row.put("rank", payload.rank());
        row.put("previousRank", payload.previousRank());
        row.put("rankChange", payload.rankChange());
        row.put("qualityFlag", calculation.qualityFlag());
        row.put("pendingVerification", payload.pendingVerification());
        return row;
    }

    private String planJson(AnalysisPlanDto plan) {
        try {
            return objectMapper.writeValueAsString(plan);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize analysis plan", exception);
        }
    }

    private String extractCompletionText(String responseBody) throws JsonProcessingException {
        if (responseBody == null || responseBody.isBlank()) {
            return "";
        }
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode()) {
            return responseBody;
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder text = new StringBuilder();
            content.forEach(part -> {
                if (part.isTextual()) {
                    text.append(part.asText());
                } else if (part.has("text")) {
                    text.append(part.path("text").asText(""));
                }
            });
            return text.toString();
        }
        return content.asText("");
    }

    private static String stripThink(String content) {
        return content.replaceAll("(?s)<think>.*?</think>", "")
                .replaceAll("(?s)<think>.*$", "")
                .trim();
    }

    private static Map<String, Object> normalizeKeys(Map<String, Object> row) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        row.forEach((key, value) -> normalized.put(key.toLowerCase(Locale.ROOT), value));
        return normalized;
    }

    private static String textValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private static BigDecimal decimalValue(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        String text = textValue(value);
        if (text == null) {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static YearMonth parseReturnedPeriod(String value) {
        String normalized = value.length() >= 7 ? value.substring(0, 7) : value;
        try {
            return YearMonth.parse(normalized, PERIOD_FORMAT);
        } catch (DateTimeParseException exception) {
            throw new IllegalStateException("Invalid month returned by matrix query: " + value, exception);
        }
    }

    private static CalculationResult resultAt(
            Map<String, Map<YearMonth, Map<String, CalculationResult>>> values,
            String subject,
            YearMonth period,
            String metricCode) {
        CalculationResult result = values.getOrDefault(subject, Map.of())
                .getOrDefault(period, Map.of())
                .get(metricCode);
        return result == null
                ? new CalculationResult(null, CalculationResult.UNCOMPARABLE_DISPLAY,
                CalculationResult.ZERO_DENOMINATOR)
                : result;
    }

    private static BigDecimal valueAt(
            Map<String, Map<YearMonth, Map<String, CalculationResult>>> values,
            String subject,
            YearMonth period,
            String metricCode) {
        return resultAt(values, subject, period, metricCode).value();
    }

    private static RankingItem findRankingItem(RankingResult ranking, String subject) {
        return ranking.stream()
                .filter(item -> item.company().equals(subject))
                .findFirst()
                .orElse(null);
    }

    private static Collection<String> requireAllowedOrgs(Collection<String> allowedOrgs) {
        Objects.requireNonNull(allowedOrgs, "allowedOrgs");
        List<String> normalized = allowedOrgs.stream()
                .map(value -> Objects.requireNonNull(value, "allowedOrgs must not contain null"))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("allowedOrgs must contain at least one organization code");
        }
        return normalized;
    }

    private static List<YearMonth> monthsBetween(YearMonth first, YearMonth last) {
        if (first.isAfter(last)) {
            throw new IllegalArgumentException("period start must not be after period end");
        }
        List<YearMonth> months = new ArrayList<>();
        for (YearMonth current = first; !current.isAfter(last); current = current.plusMonths(1)) {
            months.add(current);
        }
        return List.copyOf(months);
    }

    private static int parseMonth(String value, String expression) {
        int month;
        try {
            month = Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid month: " + expression, exception);
        }
        if (month < 1 || month > 12) {
            throw new IllegalArgumentException("Invalid month: " + expression);
        }
        return month;
    }

    private static int parseChineseNumber(String value) {
        if (value.chars().allMatch(Character::isDigit)) {
            return Integer.parseInt(value);
        }
        Map<Character, Integer> digits = Map.ofEntries(
                Map.entry('一', 1), Map.entry('二', 2), Map.entry('三', 3),
                Map.entry('四', 4), Map.entry('五', 5), Map.entry('六', 6),
                Map.entry('七', 7), Map.entry('八', 8), Map.entry('九', 9),
                Map.entry('零', 0), Map.entry('两', 2));
        int total = 0;
        int section = 0;
        int number = 0;
        for (char character : value.toCharArray()) {
            if (digits.containsKey(character)) {
                number = digits.get(character);
                continue;
            }
            int unit = switch (character) {
                case '十' -> 10;
                case '百' -> 100;
                case '千' -> 1000;
                case '万' -> 10000;
                default -> 0;
            };
            if (unit == 0) {
                return 0;
            }
            section += (number == 0 ? 1 : number) * unit;
            number = 0;
            if (unit == 10000) {
                total += section;
                section = 0;
            }
        }
        return total + section + number;
    }

    private static LocalDate min(LocalDate left, LocalDate right) {
        return left.isBefore(right) ? left : right;
    }

    private static YearMonth min(YearMonth left, YearMonth right) {
        return left.isBefore(right) ? left : right;
    }

    private record MatrixFetchResult(
            Map<String, Map<YearMonth, Map<String, CalculationResult>>> values,
            List<SubjectScope> originalSubjects,
            Map<String, String> subjectNames) {

        private MatrixFetchResult {
            values = Map.copyOf(Objects.requireNonNull(values, "values"));
            originalSubjects = List.copyOf(Objects.requireNonNull(originalSubjects, "originalSubjects"));
            subjectNames = Map.copyOf(Objects.requireNonNull(subjectNames, "subjectNames"));
        }
    }

    private record SubjectScope(
            String subject,
            String entityName,
            List<String> organizationCodes) {

        private SubjectScope {
            subject = Objects.requireNonNull(subject, "subject");
            entityName = Objects.requireNonNull(entityName, "entityName");
            organizationCodes = List.copyOf(Objects.requireNonNull(organizationCodes, "organizationCodes"));
            if (organizationCodes.isEmpty()) {
                throw new IllegalArgumentException("organizationCodes must not be empty");
            }
        }
    }

}
