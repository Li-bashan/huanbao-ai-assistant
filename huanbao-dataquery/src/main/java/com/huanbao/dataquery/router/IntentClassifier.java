package com.huanbao.dataquery.router;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huanbao.dataquery.config.VllmProperties;
import com.huanbao.dataquery.core.spi.MetricDefinition;
import com.huanbao.dataquery.domain.ops.OpsDomainSemanticProvider;
import com.huanbao.dataquery.domain.ops.OpsOrganization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.IOException;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 轻量级总路由器：优先调用 vLLM 做语义分类，异常时用本地受控词表和正则规则降级。
 */
@Service
public class IntentClassifier {

    static final int MAX_SYSTEM_PROMPT_CHARS = 250;
    static final double TEMPERATURE = 0.1D;
    static final int MAX_TOKENS = 150;

    private static final Logger LOGGER = LoggerFactory.getLogger(IntentClassifier.class);
    private static final Set<String> PRIMARY_INTENTS = Set.of(
            "POLICY", "OFFICE", "DATA_QUERY", "COMPOSITE");
    private static final Set<String> ANALYSIS_TYPES = Set.of(
            "FACT", "RANKING", "TREND", "REPORT");
    private static final List<String> COMPOSITE_SUB_TASKS = List.of("QUERY_DATA", "DRAFT_BRIEF");
    private static final List<String> POLICY_KEYWORDS = List.of(
            "制度", "办法", "规定", "标准", "报销", "差旅", "住宿费", "伙食补助", "审批权限", "适用范围");
    private static final List<String> OFFICE_KEYWORDS = List.of(
            "起草", "草拟", "帮我写", "写一份", "写一个", "生成", "通知", "公告", "纪要", "简报",
            "公文", "报告", "汇报材料", "润色", "改写", "总结", "成文档");
    private static final List<String> DATA_QUERY_KEYWORDS = List.of(
            "查询", "查一下", "查查", "统计", "数据", "多少", "排名", "排行", "最高", "最低", "同比", "环比");
    private static final Pattern TIME_PATTERN = Pattern.compile(
            "近(?:最)?(?:半|[0-9一二三四五六七八九十百千万]+)(?:个月|月|年)|"
                    + "(?:今年|去年|前年)(?:[0-9]{1,2}个?月?份?|全年)?|"
                    + "[0-9]{4}年[0-9]{1,2}个?月?份?|"
                    + "[0-9一二三四五六七八九十]+月份?|本月|上月|本季度|上季度|去年同期|今年|去年");
    private static final Pattern RANKING_PATTERN = Pattern.compile(
            "排名|排行|最高|最低|top\\s*[0-9]+|前[0-9一二三四五六七八九十]+|倒数",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TREND_PATTERN = Pattern.compile(
            "趋势|走势|变化|增长|下降|同比|环比|近[0-9一二三四五六七八九十]+年|各月|月度");

    private final RestClient vllmRestClient;
    private final ObjectMapper objectMapper;
    private final List<String> metricTerms;
    private final List<String> organizationTerms;
    private final Set<String> metricTermSet;
    private final Set<String> organizationTermSet;
    private final String model;

    @Value("${dataquery.fast-path-enabled:false}")
    private boolean fastPathEnabled;

    public IntentClassifier(
            @Qualifier("vllmRestClient") RestClient vllmRestClient,
            ObjectMapper objectMapper,
            OpsDomainSemanticProvider semanticProvider) {
        this(vllmRestClient, objectMapper, semanticProvider, "Qwen3.8-27B");
    }

    @Autowired
    public IntentClassifier(
            @Qualifier("vllmRestClient") RestClient vllmRestClient,
            ObjectMapper objectMapper,
            OpsDomainSemanticProvider semanticProvider,
            VllmProperties vllmProperties) {
        this(vllmRestClient, objectMapper, semanticProvider,
                vllmProperties == null ? "Qwen3.8-27B" : vllmProperties.getModel());
    }

    private IntentClassifier(
            RestClient vllmRestClient,
            ObjectMapper objectMapper,
            OpsDomainSemanticProvider semanticProvider,
            String model) {
        this.vllmRestClient = Objects.requireNonNull(vllmRestClient, "vllmRestClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        Objects.requireNonNull(semanticProvider, "semanticProvider");
        this.metricTerms = buildMetricTerms(semanticProvider.getMetrics());
        this.organizationTerms = buildOrganizationTerms(semanticProvider.getOrganizations());
        this.metricTermSet = normalizedSet(metricTerms);
        this.organizationTermSet = normalizedSet(organizationTerms);
        this.model = model == null || model.isBlank() ? "Qwen3.8-27B" : model.trim();
    }

    /** 分类失败时不抛出给上层，始终返回可继续编排的计划。 */
    public AnalysisPlanDto classify(String rawQuery) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.isEmpty()) {
            return fallback(query);
        }

        AnalysisPlanDto fastPath = deterministicFastPath(query);
        if (fastPath != null) {
            return fastPath;
        }

        try {
            String systemPrompt = buildSystemPrompt(query);
            String responseBody = vllmRestClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(buildRequest(systemPrompt, query))
                    .retrieve()
                    .body(String.class);
            return normalizePlan(parseResponse(responseBody), query);
        } catch (Exception exception) {
            LOGGER.debug("vLLM intent classification unavailable; using rule fallback: {}",
                    exception.getClass().getSimpleName());
            return fallback(query);
        }
    }

    /**
     * 高频、低歧义经营问数走本地受控规则，避免每次都占用 vLLM 分类槽位。
     * 仅覆盖已验收的标准排名句式，其他自然语言仍由 vLLM 分类并保留规则降级。
     */
    private AnalysisPlanDto deterministicFastPath(String query) {
        if (!fastPathEnabled
                || !query.contains("今年")
                || !query.contains("项目公司")
                || !query.contains("发电量")
                || !RANKING_PATTERN.matcher(query).find()) {
            return null;
        }
        return new AnalysisPlanDto(
                "DATA_QUERY",
                extractMatches(query, metricTerms),
                extractMatches(query, organizationTerms),
                extractTimeExpression(query),
                "RANKING",
                List.of());
    }

    /** 暴露给同包测试和诊断使用，保证动态注入后的完整 Prompt 不超过约束。 */
    String buildSystemPrompt(String query) {
        String normalizedQuery = query == null ? "" : query.trim();
        List<String> metrics = matchingTerms(normalizedQuery, metricTerms, 4);
        List<String> organizations = matchingTerms(normalizedQuery, organizationTerms, 4);
        if (metrics.isEmpty()) {
            metrics = List.of("生活垃圾入厂量", "发电量");
        }
        if (organizations.isEmpty()) {
            organizations = List.of("集团", "项目公司");
        }

        String base = "你是环宝轻量总路由器。仅输出单行JSON，禁止解释。"
                + "意图:POLICY制度/OFFICE公文/DATA_QUERY经营问数/COMPOSITE问数+公文。"
                + "metricInputs、orgInputs只能复制候选词，timeExpression原样。"
                + "analysisType只取FACT/RANKING/TREND/REPORT。"
                + "COMPOSITE的subTasks固定为QUERY_DATA,DRAFT_BRIEF。";
        List<String> metricCandidates = new ArrayList<>(metrics);
        List<String> organizationCandidates = new ArrayList<>(organizations);
        String prompt = renderPrompt(base, metricCandidates, organizationCandidates);
        while (prompt.length() > MAX_SYSTEM_PROMPT_CHARS
                && (!metricCandidates.isEmpty() || !organizationCandidates.isEmpty())) {
            if (organizationCandidates.size() >= metricCandidates.size()
                    && !organizationCandidates.isEmpty()) {
                organizationCandidates.remove(organizationCandidates.size() - 1);
            } else {
                metricCandidates.remove(metricCandidates.size() - 1);
            }
            prompt = renderPrompt(base, metricCandidates, organizationCandidates);
        }
        return prompt;
    }

    private Map<String, Object> buildRequest(String systemPrompt, String query) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);
        request.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", query)));
        request.put("temperature", TEMPERATURE);
        request.put("max_tokens", MAX_TOKENS);
        request.put("stream", false);
        return request;
    }

    private String renderPrompt(String base, List<String> metrics, List<String> organizations) {
        return base + "指标候选[" + String.join("|", metrics) + "]；组织简称/大区候选["
                + String.join("|", organizations) + "]。";
    }

    private AnalysisPlanDto parseResponse(String responseBody) throws IOException {
        if (responseBody == null || responseBody.isBlank()) {
            throw new IOException("empty vLLM response");
        }
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        String modelContent = content.isMissingNode() ? root.toString() : contentText(content);
        String json = extractJsonObject(modelContent);
        return objectMapper.readValue(json, AnalysisPlanDto.class);
    }

    private static String contentText(JsonNode content) {
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
        return content.toString();
    }

    private static String extractJsonObject(String content) throws IOException {
        String cleaned = content == null ? "" : content
                .replaceAll("(?s)<think>.*?</think>", "")
                .replace("```json", "")
                .replace("```", "")
                .trim();
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IOException("vLLM response is not a JSON object");
        }
        return cleaned.substring(start, end + 1);
    }

    private AnalysisPlanDto normalizePlan(AnalysisPlanDto plan, String query) {
        String primaryIntent = enumValue(plan.primaryIntent(), PRIMARY_INTENTS);
        if (primaryIntent.isEmpty()) {
            primaryIntent = fallback(query).primaryIntent();
        }

        List<String> metrics = knownTerms(plan.metricInputs(), metricTermSet);
        if (metrics.isEmpty()) {
            metrics = extractMatches(query, metricTerms);
        }
        List<String> organizations = knownTerms(plan.orgInputs(), organizationTermSet);
        if (organizations.isEmpty()) {
            organizations = extractMatches(query, organizationTerms);
        }
        // 路由模型可能已将最近半年等时间表达式归一化为近6个月；不能因为
        // 归一化后的文本未逐字出现在原问题中而丢弃它，否则流水线会退化为单月锚点。
        String timeExpression = plan.timeExpression();
        if (timeExpression == null || timeExpression.isBlank()) {
            timeExpression = extractTimeExpression(query);
        }
        String analysisType = enumValue(plan.analysisType(), ANALYSIS_TYPES);
        if (analysisType.isEmpty()) {
            analysisType = fallbackAnalysisType(query);
        }
        List<String> subTasks = "COMPOSITE".equals(primaryIntent)
                ? COMPOSITE_SUB_TASKS
                : List.of();
        return new AnalysisPlanDto(
                primaryIntent, metrics, organizations, timeExpression, analysisType, subTasks);
    }

    private AnalysisPlanDto fallback(String query) {
        List<String> metrics = extractMatches(query, metricTerms);
        List<String> organizations = extractMatches(query, organizationTerms);
        boolean hasData = !metrics.isEmpty()
                || (containsAny(query, DATA_QUERY_KEYWORDS)
                && containsAny(query, List.of(
                "生产", "经营", "指标", "垃圾", "发电", "电量", "入厂", "产量", "负荷", "耗量", "单耗",
                "大区", "项目公司", "集团")))
                || (containsAny(query, List.of("今年", "去年", "本月", "近三个月", "大区", "项目公司"))
                && containsAny(query, List.of("量", "率", "耗", "电", "产量")));
        boolean hasPolicy = containsAny(query, POLICY_KEYWORDS);
        boolean hasOffice = containsAny(query, OFFICE_KEYWORDS);

        String primaryIntent;
        if (hasData && hasOffice && !hasPolicy) {
            primaryIntent = "COMPOSITE";
        } else if (hasPolicy) {
            primaryIntent = "POLICY";
        } else if (hasOffice) {
            primaryIntent = "OFFICE";
        } else {
            primaryIntent = "DATA_QUERY";
        }
        String analysisType = fallbackAnalysisType(query);
        return new AnalysisPlanDto(
                primaryIntent,
                metrics,
                organizations,
                extractTimeExpression(query),
                analysisType,
                "COMPOSITE".equals(primaryIntent) ? COMPOSITE_SUB_TASKS : List.of());
    }

    private String fallbackAnalysisType(String query) {
        if (RANKING_PATTERN.matcher(query).find()) {
            return "RANKING";
        }
        if (TREND_PATTERN.matcher(query).find()) {
            return "TREND";
        }
        if (containsAny(query, List.of("简报", "报告", "汇报材料"))) {
            return "REPORT";
        }
        return "FACT";
    }

    private static String extractTimeExpression(String query) {
        Matcher matcher = TIME_PATTERN.matcher(query);
        return matcher.find() ? matcher.group() : "";
    }

    private static List<String> buildMetricTerms(Collection<MetricDefinition> definitions) {
        return definitions.stream()
                .flatMap(definition -> Stream.concat(
                        Stream.of(definition.formalName()), definition.aliases().stream()))
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(term -> !term.isEmpty())
                .distinct()
                .sorted(Comparator.comparingInt(String::length).thenComparing(String::compareTo))
                .toList();
    }

    private static List<String> buildOrganizationTerms(Collection<OpsOrganization> organizations) {
        Stream<String> names = organizations.stream().flatMap(organization -> Stream.of(
                organization.shortName(),
                stripSuffix(organization.shortName()),
                organization.formalName(),
                organization.region(),
                stripRegionSuffix(organization.region())));
        return Stream.concat(names, Stream.of("集团", "全集团", "集团汇总", "全部项目公司", "项目公司"))
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(term -> !term.isEmpty())
                .distinct()
                .sorted(Comparator.comparingInt(String::length).thenComparing(String::compareTo))
                .toList();
    }

    private static String stripSuffix(String value) {
        return value != null && value.endsWith("公司") ? value.substring(0, value.length() - 2) : value;
    }

    private static String stripRegionSuffix(String value) {
        return value != null && value.endsWith("大区") ? value.substring(0, value.length() - 2) : value;
    }

    private static List<String> matchingTerms(String query, List<String> terms, int max) {
        return terms.stream()
                .filter(term -> !query.isEmpty() && query.contains(term))
                .limit(max)
                .toList();
    }

    private static List<String> extractMatches(String query, List<String> terms) {
        if (query.isEmpty()) {
            return List.of();
        }
        List<TermMatch> matches = new ArrayList<>();
        for (String term : terms) {
            int start = query.indexOf(term);
            if (start >= 0) {
                matches.add(new TermMatch(start, term));
            }
        }
        matches.sort(Comparator.comparingInt(TermMatch::start)
                .thenComparing((left, right) -> Integer.compare(right.term().length(), left.term().length()))
                .thenComparing(TermMatch::term));

        List<TermMatch> selected = new ArrayList<>();
        for (TermMatch match : matches) {
            int end = match.start() + match.term().length();
            boolean overlaps = selected.stream().anyMatch(existing ->
                    match.start() < existing.start() + existing.term().length()
                            && existing.start() < end);
            if (!overlaps) {
                selected.add(match);
            }
        }
        selected.sort(Comparator.comparingInt(TermMatch::start));
        return selected.stream().map(TermMatch::term).toList();
    }

    private static List<String> knownTerms(List<String> values, Set<String> knownTerms) {
        return values.stream()
                .map(String::trim)
                .filter(value -> knownTerms.contains(value.toLowerCase(Locale.ROOT)))
                .distinct()
                .toList();
    }

    private static Set<String> normalizedSet(Collection<String> values) {
        return values.stream().map(value -> value.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
    }

    private static String enumValue(String value, Set<String> allowed) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return allowed.contains(normalized) ? normalized : "";
    }

    private static boolean containsAny(String query, Collection<String> keywords) {
        return keywords.stream().anyMatch(query::contains);
    }

    private record TermMatch(int start, String term) {
    }
}
