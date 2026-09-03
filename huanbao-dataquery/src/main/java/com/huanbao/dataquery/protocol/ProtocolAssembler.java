package com.huanbao.dataquery.protocol;

import com.huanbao.dataquery.core.analysis.AnalysisEvidenceResult;
import com.huanbao.dataquery.core.analysis.ClueItem;
import com.huanbao.dataquery.core.analysis.FactItem;
import com.huanbao.dataquery.core.spi.MetricDefinition;
import com.huanbao.dataquery.core.repository.PartitionTablePruner;
import com.huanbao.dataquery.pipeline.CompositeExecutionResult;
import com.huanbao.dataquery.pipeline.StructuredFactPayload;
import com.huanbao.dataquery.domain.ops.OpsDomainSemanticProvider;
import com.huanbao.dataquery.router.AnalysisPlanDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
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
import java.util.stream.Collectors;

/**
 * 把确定性问数流水线结果装配为前端 Presentation Model v2。
 *
 * <p>这个类不重新计算业务指标，也不让文档模型改变表格和图表。它只消费
 * TASK-3.2 的结果，以及结果里保留的 TASK-2.4 三层证据链。</p>
 */
@Component
public final class ProtocolAssembler {

    private static final String DEFAULT_INDICATOR_NAME = "经营数据";
    private static final List<String> EVIDENCE_LAYERS = List.of(
            "confirmedFacts", "correlatedClues", "pendingVerification");

    private final OpsDomainSemanticProvider semanticProvider;

    public ProtocolAssembler() {
        this(new OpsDomainSemanticProvider());
    }

    @Autowired
    public ProtocolAssembler(OpsDomainSemanticProvider semanticProvider) {
        this.semanticProvider = Objects.requireNonNull(semanticProvider, "semanticProvider");
    }

    public PresentationResponseV2 assemble(
            CompositeExecutionResult result,
            UserOrganizationContext userContext) {
        return assemble(result, userContext, PartitionTablePruner.DEFAULT_MAX_DATA_DATE);
    }

    public PresentationResponseV2 assemble(
            CompositeExecutionResult result,
            String userId,
            Collection<String> allowedOrgs,
            LocalDate dataCutoffDate) {
        return assemble(
                result,
                new UserOrganizationContext(userId, allowedOrgs),
                dataCutoffDate);
    }

    public PresentationResponseV2 assemble(
            CompositeExecutionResult result,
            UserOrganizationContext userContext,
            LocalDate dataCutoffDate) {
        return assemble(result, userContext, dataCutoffDate, "", "");
    }

    /** 控制器使用的装配入口，补齐网关生成的 requestId 和会话句柄。 */
    public PresentationResponseV2 assemble(
            CompositeExecutionResult result,
            UserOrganizationContext userContext,
            LocalDate dataCutoffDate,
            String requestId,
            String conversationId) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(userContext, "userContext");
        Objects.requireNonNull(dataCutoffDate, "dataCutoffDate");

        AnalysisPlanDto plan = result.plan();
        MetricMetadata metric = resolveMetric(plan);
        String analysisType = protocolAnalysisType(plan.analysisType());
        List<FactView> facts = factViews(result);
        boolean hasData = hasData(result.tableRows(), facts);
        String status = hasData
                ? PresentationResponseV2.SUCCESS_WITH_DATA
                : PresentationResponseV2.NO_DATA;

        List<EvidenceV2Dto> evidence = assembleEvidence(result, metric.unit());
        List<InsightItemDto> insights = hasData
                ? assembleInsights(analysisType, facts, metric.unit())
                : List.of();
        boolean multiSubjectTrend = "TREND".equals(analysisType)
                && result.tableRows().stream()
                        .map(row -> text(row.get("subject"), ""))
                        .filter(value -> !value.isBlank())
                        .distinct()
                        .count() > 1;
        if (hasData && multiSubjectTrend) {
            List<InsightItemDto> enrichedInsights = new ArrayList<>(insights);
            String anomalyText = result.anomalySubjects().isEmpty()
                    ? "异常公司重点关注：本次历史序列异常检测未命中异常公司。"
                    : "异常公司重点关注：" + String.join("、", result.anomalySubjects())
                            + "。以上名单由历史序列异常检测命中，仅表示需要进一步核实。";
            enrichedInsights.add(new InsightItemDto("attention", anomalyText));
            insights = List.copyOf(enrichedInsights);
        }
        List<MetricCardDto> metrics = hasData
                ? assembleMetrics(facts, metric)
                : List.of();
        TableDataDto table = hasData
                ? assembleTable(analysisType, result.tableRows(), metric.unit())
                : null;
        ChartDataDto chart = hasData ? assembleChart(result.chartConfig()) : null;
        List<FollowUpDto> followUps = hasData ? assembleFollowUps(analysisType) : List.of();
        Map<String, Object> coverage = coverage(userContext, facts);
        List<String> warnings = result.degraded() && !result.degradedMessage().isBlank()
                ? List.of(result.degradedMessage())
                : List.of();
        DataInfoDto dataInfo = assembleDataInfo(
                plan, analysisType, metric, dataCutoffDate, userContext, result.tableRows(),
                facts, warnings, coverage, status);

        String summary = summary(result, metric.name(), status);
        ContentV2 content = new ContentV2(
                title(metric.name(), analysisType),
                summary,
                metrics,
                table,
                chart,
                insights,
                evidence,
                dataInfo,
                followUps);
        MetaDto meta = new MetaDto(
                status,
                hasData,
                auditSummary(result, facts, status),
                analysisState(plan),
                coverage,
                result.degraded(),
                result.degradedMessage());

        return new PresentationResponseV2(
                PresentationResponseV2.PROTOCOL_VERSION,
                requestId,
                conversationId,
                status,
                hasData ? "analysis" : "empty",
                analysisType,
                content,
                null,
                meta);
    }

    /** 将 TASK-2.4 结果展平为前端可渲染的三层证据项。 */
    public List<EvidenceV2Dto> assembleEvidence(
            AnalysisEvidenceResult evidenceResult,
            String unit) {
        Objects.requireNonNull(evidenceResult, "evidenceResult");
        List<EvidenceV2Dto> evidence = new ArrayList<>();
        for (FactItem fact : evidenceResult.confirmedFacts()) {
            evidence.add(EvidenceV2Dto.confirmed(
                    confirmedFactText(fact, unit),
                    fact.subject(),
                    fact.metric(),
                    fact.period(),
                    fact.currentValue(),
                    fact.yearOverYearPercent(),
                    fact.monthOverMonthPercent(),
                    fact.rank(),
                    fact.previousRank(),
                    fact.rankChange()));
        }
        for (ClueItem clue : evidenceResult.correlatedClues()) {
            evidence.add(EvidenceV2Dto.clue(
                    clueText(clue),
                    clue.subject(),
                    clue.primaryMetric(),
                    clue.relatedMetric(),
                    clue.period(),
                    clue.primaryChangePercent(),
                    clue.relatedChangePercent(),
                    clue.relationship()));
        }
        for (String pending : evidenceResult.pendingVerification()) {
            evidence.add(EvidenceV2Dto.pending(pending));
        }
        return List.copyOf(evidence);
    }

    public List<EvidenceV2Dto> assembleEvidence(AnalysisEvidenceResult evidenceResult) {
        return assembleEvidence(evidenceResult, "");
    }

    private List<EvidenceV2Dto> assembleEvidence(
            CompositeExecutionResult result,
            String unit) {
        List<EvidenceV2Dto> evidence = new ArrayList<>();
        if (!result.factPayloads().isEmpty()) {
            for (StructuredFactPayload payload : result.factPayloads()) {
                evidence.addAll(assembleEvidence(new AnalysisEvidenceResult(
                        payload.confirmedFacts(),
                        payload.correlatedClues(),
                        payload.pendingVerification()), unit));
            }
            return List.copyOf(evidence);
        }

        // 兼容未携带 factPayloads 的 TASK-3.2 旧结果。
        for (Map<String, Object> row : result.tableRows()) {
            FactItem fact = new FactItem(
                    text(row.get("subject"), "未知对象"),
                    text(row.get("metric"), "经营数据"),
                    text(row.get("period"), "未知周期"),
                    decimal(row.get("currentValue")),
                    decimal(row.get("yearOverYearPercent")),
                    decimal(row.get("monthOverMonthPercent")),
                    integer(row.get("rank")),
                    integer(row.get("previousRank")),
                    integer(row.get("rankChange")));
            List<String> pending = strings(row.get("pendingVerification"));
            List<ClueItem> clues = fact.yearOverYearPercent() != null && fact.monthOverMonthPercent() != null
                    ? List.of(new ClueItem(
                    fact.subject(), fact.metric(), "同比与环比变化", fact.period(),
                    fact.yearOverYearPercent(), fact.monthOverMonthPercent(),
                    "同一指标的变化方向线索，仅作相关提示，不代表因果"))
                    : List.of();
            evidence.addAll(assembleEvidence(
                    new AnalysisEvidenceResult(List.of(fact), clues, pending), unit));
        }
        return List.copyOf(evidence);
    }

    private List<MetricCardDto> assembleMetrics(List<FactView> facts, MetricMetadata metric) {
        String latestPeriod = facts.stream()
                .map(FactView::period)
                .filter(value -> !value.isBlank())
                .max(String::compareTo)
                .orElse("");
        List<FactView> latest = facts.stream()
                .filter(fact -> latestPeriod.equals(fact.period()) && fact.currentValue() != null)
                .collect(Collectors.toCollection(ArrayList::new));
        Set<String> subjects = latest.stream().map(FactView::subject).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> metricNames = latest.stream().map(FactView::metric)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<MetricCardDto> cards = new ArrayList<>();
        for (FactView fact : latest) {
            MetricMetadata factMetric = metricMetadata(fact.metric(), metric);
            String label;
            if (metricNames.size() > 1 && subjects.size() > 1) {
                label = fact.subject() + " · " + factMetric.name();
            } else if (metricNames.size() > 1) {
                label = factMetric.name();
            } else if (subjects.size() > 1) {
                label = fact.subject();
            } else {
                label = metric.name();
            }
            cards.add(new MetricCardDto(
                    label,
                    fact.currentValue(),
                    factMetric.unit(),
                    fact.yearOverYearPercent(),
                    fact.monthOverMonthPercent()));
        }
        return List.copyOf(cards);
    }

    private TableDataDto assembleTable(
            String analysisType,
            List<Map<String, Object>> sourceRows,
            String unit) {
        List<TableColumnDto> columns;
        if ("RANKING".equals(analysisType)) {
            columns = List.of(
                    new TableColumnDto("rank", "排名", "number", "位"),
                    new TableColumnDto("companyName", "公司名称", "text"),
                    new TableColumnDto("value", "当前值", "number", unit),
                    new TableColumnDto("yearOverYearPercent", "同比", "number", "%"),
                    new TableColumnDto("monthOverMonthPercent", "环比", "number", "%"));
        } else {
            columns = List.of(
                    new TableColumnDto("period", "周期", "text"),
                    new TableColumnDto("organization", "对象", "text"),
                    new TableColumnDto("indicator", "指标", "text"),
                    new TableColumnDto("value", "数值", "number", unit),
                    new TableColumnDto("yearOverYearPercent", "同比", "number", "%"),
                    new TableColumnDto("monthOverMonthPercent", "环比", "number", "%"));
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> source : sourceRows) {
            Map<String, Object> row = new LinkedHashMap<>();
            String subject = text(source.get("subject"), "");
            String metric = text(source.get("metric"), "");
            row.put("period", text(source.get("period"), ""));
            row.put("organization", subject);
            row.put("companyName", subject);
            row.put("indicator", metric);
            row.put("value", source.get("currentValue"));
            row.put("yearOverYearPercent", source.get("yearOverYearPercent"));
            row.put("monthOverMonthPercent", source.get("monthOverMonthPercent"));
            row.put("rank", source.get("rank"));
            row.put("previousRank", source.get("previousRank"));
            row.put("rankChange", source.get("rankChange"));
            rows.add(row);
        }
        return new TableDataDto(columns, rows, rows.size(), 10);
    }

    private ChartDataDto assembleChart(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return null;
        }
        String type = text(source.get("type"), "bar");
        String title = chartTitle(source.get("title"));
        Map<?, ?> xAxis = asMap(source.get("xAxis"));
        List<String> categories = asList(xAxis.get("data")).stream()
                .map(value -> value == null ? "" : String.valueOf(value))
                .toList();
        List<ChartSeriesDto> series = new ArrayList<>();
        for (Object value : asList(source.get("series"))) {
            Map<?, ?> item = asMap(value);
            if (item.isEmpty()) {
                continue;
            }
            series.add(new ChartSeriesDto(
                    text(item.get("name"), ""),
                    text(item.get("type"), type),
                    nullableList(asList(item.get("data")))));
        }
        return series.isEmpty() ? null : new ChartDataDto(
                type,
                title,
                "category",
                "value",
                categories,
                series);
    }

    private List<InsightItemDto> assembleInsights(
            String analysisType,
            List<FactView> facts,
            String unit) {
        List<InsightItemDto> insights = new ArrayList<>();
        List<FactView> currentFacts = facts.stream()
                .filter(fact -> fact.currentValue() != null)
                .sorted(Comparator.comparing(FactView::period).thenComparing(FactView::subject))
                .toList();
        for (FactView fact : currentFacts) {
            if (fact.yearOverYearPercent() != null || fact.monthOverMonthPercent() != null) {
                StringBuilder text = new StringBuilder(fact.subject())
                        .append("在").append(fact.period()).append("的")
                        .append(fact.metric()).append("为")
                        .append(format(fact.currentValue())).append(unit);
                if (fact.yearOverYearPercent() != null) {
                    text.append("，同比").append(direction(fact.yearOverYearPercent()))
                            .append(formatPercent(fact.yearOverYearPercent()));
                }
                if (fact.monthOverMonthPercent() != null) {
                    text.append("，环比").append(direction(fact.monthOverMonthPercent()))
                            .append(formatPercent(fact.monthOverMonthPercent()));
                }
                insights.add(new InsightItemDto(
                        Math.abs(value(fact.yearOverYearPercent(), fact.monthOverMonthPercent())) >= 20
                                ? "attention" : "fact",
                        text.toString()));
            }
            if ("RANKING".equals(analysisType) && fact.rank() != null) {
                insights.add(new InsightItemDto(
                        "fact",
                        fact.subject() + "在" + fact.period() + "排名第" + fact.rank() + "。"));
            }
        }
        if (insights.isEmpty() && !currentFacts.isEmpty()) {
            FactView first = currentFacts.get(0);
            insights.add(new InsightItemDto(
                    "fact",
                    first.metric() + "已完成数据校验，共返回" + currentFacts.size() + "个数据点。"));
        }
        if ("TREND".equals(analysisType) && !currentFacts.isEmpty()) {
            insights.add(new InsightItemDto(
                    "fact",
                    "已完成" + currentFacts.get(0).metric() + "的趋势变化核验，详见各期事实与环比同比。"));
        }
        return List.copyOf(insights.stream().limit(8).toList());
    }

    private List<FollowUpDto> assembleFollowUps(String analysisType) {
        List<String> labels = switch (analysisType) {
            case "RANKING" -> List.of("查看秦皇岛表现", "查看大区分布", "和去年同期相比");
            case "TREND" -> List.of("查看秦皇岛表现", "查看大区分布", "查看异常变化");
            case "OVERVIEW" -> List.of("查看秦皇岛表现", "查看大区分布", "和去年同期相比");
            default -> List.of("查看趋势", "查看大区分布", "查看秦皇岛表现");
        };
        List<FollowUpDto> followUps = new ArrayList<>();
        for (int index = 0; index < labels.size(); index++) {
            String label = labels.get(index);
            followUps.add(new FollowUpDto("follow-up-" + index, label, label));
        }
        return List.copyOf(followUps);
    }

    private DataInfoDto assembleDataInfo(
            AnalysisPlanDto plan,
            String analysisType,
            MetricMetadata metric,
            LocalDate dataCutoffDate,
            UserOrganizationContext userContext,
            List<Map<String, Object>> rows,
            List<FactView> facts,
            List<String> warnings,
            Map<String, Object> coverage,
            String status) {
        List<String> periods = rows.stream()
                .map(row -> text(row.get("period"), ""))
                .filter(value -> !value.isBlank())
                .distinct()
                .sorted()
                .toList();
        Map<String, Object> timeRange = new LinkedHashMap<>();
        timeRange.put("expression", plan.timeExpression());
        if (!periods.isEmpty()) {
            timeRange.put("start", periods.get(0));
            timeRange.put("end", periods.get(periods.size() - 1));
        }
        timeRange.put("endExclusive", true);

        Set<String> subjects = facts.stream()
                .map(FactView::subject)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, Object> organizationScope = new LinkedHashMap<>();
        String organizationType = "authorized";
        if (!plan.orgInputs().isEmpty()) {
            try {
                organizationType = semanticProvider.resolveEntity(plan.orgInputs().get(0)).entityType();
            } catch (IllegalArgumentException ignored) {
                organizationType = plan.orgInputs().get(0);
            }
        }
        organizationScope.put("type", organizationType);
        organizationScope.put("names", List.copyOf(subjects));
        organizationScope.put("codes", userContext.allowedOrgs());

        List<BigDecimal> values = facts.stream()
                .map(FactView::currentValue)
                .filter(Objects::nonNull)
                .toList();
        Map<String, Object> statistics = new LinkedHashMap<>();
        statistics.put("rowCount", rows.size());
        statistics.put("dataPointCount", values.size());
        statistics.put("periodCount", periods.size());
        if (!periods.isEmpty()) {
            statistics.put("latestPeriod", periods.get(periods.size() - 1));
        }
        if (!values.isEmpty()) {
            statistics.put("min", values.stream().min(BigDecimal::compareTo).orElseThrow());
            statistics.put("max", values.stream().max(BigDecimal::compareTo).orElseThrow());
        }

        Map<String, Object> comparison = new LinkedHashMap<>();
        comparison.put("yearOverYear", facts.stream().anyMatch(fact -> fact.yearOverYearPercent() != null));
        comparison.put("monthOverMonth", facts.stream().anyMatch(fact -> fact.monthOverMonthPercent() != null));

        Map<String, Object> validation = new LinkedHashMap<>();
        validation.put("status", status);
        validation.put("dataPresent", !values.isEmpty());
        validation.put("evidenceLayers", EVIDENCE_LAYERS);

        return new DataInfoDto(
                analysisType,
                metric.name(),
                metric.code(),
                metric.unit(),
                timeRange,
                dataCutoffDate.toString(),
                metric.aggregation(),
                organizationScope,
                rows.size(),
                statistics,
                comparison,
                sourceTables(periods, dataCutoffDate),
                warnings,
                coverage,
                validation);
    }

    private List<FactView> factViews(CompositeExecutionResult result) {
        if (!result.factPayloads().isEmpty()) {
            List<FactView> payloadFacts = result.factPayloads().stream()
                    .flatMap(payload -> payload.confirmedFacts().stream())
                    .map(ProtocolAssembler::factView)
                    .toList();
            if (!payloadFacts.isEmpty()) {
                return payloadFacts;
            }
        }
        return result.tableRows().stream().map(row -> new FactView(
                text(row.get("subject"), ""),
                text(row.get("metric"), DEFAULT_INDICATOR_NAME),
                text(row.get("period"), ""),
                decimal(row.get("currentValue")),
                decimal(row.get("yearOverYearPercent")),
                decimal(row.get("monthOverMonthPercent")),
                integer(row.get("rank")),
                integer(row.get("previousRank")),
                integer(row.get("rankChange")))).toList();
    }

    private static FactView factView(FactItem fact) {
        return new FactView(
                fact.subject(), fact.metric(), fact.period(), fact.currentValue(),
                fact.yearOverYearPercent(), fact.monthOverMonthPercent(),
                fact.rank(), fact.previousRank(), fact.rankChange());
    }

    private static boolean hasData(List<Map<String, Object>> rows, List<FactView> facts) {
        return rows.stream().anyMatch(row -> decimal(row.get("currentValue")) != null
                || decimal(row.get("value")) != null)
                || facts.stream().anyMatch(fact -> fact.currentValue() != null);
    }

    private MetricMetadata resolveMetric(AnalysisPlanDto plan) {
        String input = plan.metricInputs().isEmpty() ? "" : plan.metricInputs().get(0);
        MetricDefinition definition = semanticProvider.getMetric(input);
        if (definition == null) {
            return new MetricMetadata(
                    input.isBlank() ? DEFAULT_INDICATOR_NAME : input,
                    "",
                    "",
                    "");
        }
        return new MetricMetadata(
                definition.formalName(),
                definition.indicatorCode(),
                definition.unit(),
                definition.aggregationRule());
    }

    private MetricMetadata metricMetadata(String metricName, MetricMetadata fallback) {
        MetricDefinition definition = semanticProvider.getMetric(metricName);
        return definition == null
                ? fallback
                : new MetricMetadata(
                        definition.formalName(),
                        definition.indicatorCode(),
                        definition.unit(),
                        definition.aggregationRule());
    }

    private static String protocolAnalysisType(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "FACT", "DETAIL", "TREND", "RANKING", "COMPARISON", "RANKING_COMPARISON",
                    "DISTRIBUTION", "ANOMALY", "DRILLDOWN", "OVERVIEW" -> normalized;
            case "REPORT", "COMPOSITE" -> "OVERVIEW";
            default -> "FACT";
        };
    }

    private static String summary(CompositeExecutionResult result, String metricName, String status) {
        if (!PresentationResponseV2.SUCCESS_WITH_DATA.equals(status)) {
            return "本次查询在授权范围内没有找到符合条件的生产数据。";
        }
        if (!result.documentMarkdown().isBlank()) {
            return result.documentMarkdown();
        }
        return metricName + "查询完成，共返回" + result.tableRows().size() + "条经过校验的数据。";
    }

    private static String title(String metricName, String analysisType) {
        return "OVERVIEW".equals(analysisType)
                ? metricName + "经营分析简报"
                : metricName + "分析";
    }

    private static Map<String, Object> auditSummary(
            CompositeExecutionResult result,
            List<FactView> facts,
            String status) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", status);
        summary.put("rowCount", result.tableRows().size());
        summary.put("dataPointCount", facts.stream().filter(fact -> fact.currentValue() != null).count());
        summary.put("degraded", result.degraded());
        return summary;
    }

    private static Map<String, Object> analysisState(AnalysisPlanDto plan) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("primaryIntent", plan.primaryIntent());
        state.put("analysisType", plan.analysisType());
        state.put("metricInputs", plan.metricInputs());
        state.put("orgInputs", plan.orgInputs());
        state.put("timeExpression", plan.timeExpression());
        return state;
    }

    private static Map<String, Object> coverage(
            UserOrganizationContext userContext,
            List<FactView> facts) {
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("allowedOrgCount", userContext.allowedOrgs().size());
        coverage.put("returnedSubjectCount", facts.stream().map(FactView::subject).distinct().count());
        coverage.put("allowedOrgCodes", userContext.allowedOrgs());
        return coverage;
    }

    private static List<String> sourceTables(List<String> periods, LocalDate cutoff) {
        Set<String> tables = new LinkedHashSet<>();
        for (String period : periods) {
            try {
                YearMonth yearMonth = YearMonth.parse(period);
                LocalDate partitionStart = LocalDate.of(
                        yearMonth.getYear(), yearMonth.getMonthValue() <= 6 ? 1 : 7, 1);
                if (!partitionStart.isAfter(cutoff)) {
                    tables.add("CGXTAPPMISDate_" + yearMonth.getYear() + "_"
                            + (yearMonth.getMonthValue() <= 6 ? "06" : "12"));
                }
            } catch (DateTimeParseException ignored) {
                // 非标准周期仍可在表格显示，但不能拼接成物理表名。
            }
        }
        return List.copyOf(tables);
    }

    private static String confirmedFactText(FactItem fact, String unit) {
        StringBuilder text = new StringBuilder(fact.subject())
                .append("在").append(fact.period()).append("的")
                .append(fact.metric()).append("当前值为")
                .append(format(fact.currentValue())).append(unit == null ? "" : unit);
        if (fact.yearOverYearPercent() != null) {
            text.append("，同比").append(direction(fact.yearOverYearPercent()))
                    .append(formatPercent(fact.yearOverYearPercent()));
        }
        if (fact.monthOverMonthPercent() != null) {
            text.append("，环比").append(direction(fact.monthOverMonthPercent()))
                    .append(formatPercent(fact.monthOverMonthPercent()));
        }
        if (fact.rank() != null) {
            text.append("，排名第").append(fact.rank());
        }
        return text.append("。").toString();
    }

    private static String clueText(ClueItem clue) {
        return clue.subject() + "在" + clue.period() + "出现"
                + clue.primaryMetric() + "与" + clue.relatedMetric() + "的同期变化线索："
                + formatPercent(clue.primaryChangePercent()) + " / "
                + formatPercent(clue.relatedChangePercent()) + "。"
                + clue.relationship();
    }

    private static String direction(BigDecimal value) {
        return value.signum() > 0 ? "上升" : value.signum() < 0 ? "下降" : "持平";
    }

    private static String formatPercent(BigDecimal value) {
        return value == null ? "不可比" : format(value) + "%";
    }

    private static String format(BigDecimal value) {
        return value == null ? "暂无" : value.stripTrailingZeros().toPlainString();
    }

    private static double value(BigDecimal first, BigDecimal second) {
        BigDecimal selected = first != null ? first : second;
        return selected == null ? 0 : selected.abs().doubleValue();
    }

    private static Map<?, ?> asMap(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static List<?> asList(Object value) {
        return value instanceof Collection<?> collection
                ? java.util.Collections.unmodifiableList(new ArrayList<>(collection))
                : List.of();
    }

    private static List<Object> nullableList(List<?> values) {
        return java.util.Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static String chartTitle(Object value) {
        if (value instanceof Map<?, ?> map) {
            return text(map.get("text"), "");
        }
        return text(value, "");
    }

    private static String text(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Integer integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? null : Integer.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof Collection<?> collection)) {
            return List.of();
        }
        return collection.stream()
                .map(item -> text(item, ""))
                .filter(item -> !item.isBlank())
                .toList();
    }

    private record MetricMetadata(String name, String code, String unit, String aggregation) {
    }

    private record FactView(
            String subject,
            String metric,
            String period,
            BigDecimal currentValue,
            BigDecimal yearOverYearPercent,
            BigDecimal monthOverMonthPercent,
            Integer rank,
            Integer previousRank,
            Integer rankChange) {
    }
}
