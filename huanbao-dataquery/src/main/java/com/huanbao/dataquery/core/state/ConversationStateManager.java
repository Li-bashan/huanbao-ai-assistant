package com.huanbao.dataquery.core.state;

import com.huanbao.dataquery.domain.ops.OpsDomainSemanticProvider;
import com.huanbao.dataquery.domain.ops.OpsOrganization;
import com.huanbao.dataquery.core.spi.EntityMapping;
import com.huanbao.dataquery.core.spi.MetricDefinition;
import com.huanbao.dataquery.router.AnalysisPlanDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 企业级多轮经营分析状态机。
 *
 * <p>状态以 tenant:user:conversationId 作为隔离键。单个键的转移使用
 * {@link ConcurrentHashMap#compute(Object, java.util.function.BiFunction)} 原子完成，
 * 因此并发请求不会把同一会话写成半个状态。状态只在读取或写入时续期，空闲超过 30 分钟后失效。</p>
 */
@Service
public class ConversationStateManager {

    public static final Duration STATE_TTL = Duration.ofMinutes(30);

    private static final Pattern TOP_N_PATTERN = Pattern.compile(
            "(?:前\\s*([0-9]+|[一二三四五六七八九十百千万]+)|top\\s*([0-9]+|[一二三四五六七八九十百千万]+))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME_PATTERN = Pattern.compile(
            "近(?:最)?(?:半|[0-9一二三四五六七八九十百千万]+)(?:个月|月|年)|"
                    + "(?:今年|去年|前年)(?:[0-9]{1,2}个?月?份?|全年)?|"
                    + "[0-9]{4}年[0-9]{1,2}个?月?份?|"
                    + "[0-9一二三四五六七八九十百千万]+月份?|本月|上月|本季度|上季度|去年同期|今年|去年",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RANKING_PATTERN = Pattern.compile(
            "排名|排行|top\\s*[0-9一二三四五六七八九十百千万]+|前[0-9一二三四五六七八九十百千万]+|"
                    + "倒数|最高|最低",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern REGION_SCOPE_PATTERN = Pattern.compile(
            "各大区|大区排名|按大区|区域排名|各区域", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMPARISON_YOY_PATTERN = Pattern.compile(
            "同比|跟去年(?:比|相比)|和去年(?:比|相比)|与去年(?:相比|对比)|相比去年|去年同期|同上年",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COMPARISON_MOM_PATTERN = Pattern.compile(
            "环比|跟上月(?:比|相比)|和上月(?:比|相比)|与上月(?:相比|对比)|相比上月|上月同期",
            Pattern.CASE_INSENSITIVE);

    private static final List<String> GROUP_TERMS = List.of(
            "全集团", "集团汇总", "全部项目公司", "所有公司", "全部公司", "项目公司", "集团");

    private final OpsDomainSemanticProvider semanticProvider;
    private final Clock clock;
    private final long ttlMillis;
    private final ConcurrentHashMap<String, StateEntry> states = new ConcurrentHashMap<>();

    public ConversationStateManager() {
        this(new OpsDomainSemanticProvider(), Clock.systemUTC());
    }

    @Autowired
    public ConversationStateManager(OpsDomainSemanticProvider semanticProvider) {
        this(semanticProvider, Clock.systemUTC());
    }

    public ConversationStateManager(OpsDomainSemanticProvider semanticProvider, Clock clock) {
        this.semanticProvider = Objects.requireNonNull(semanticProvider, "semanticProvider");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ttlMillis = STATE_TTL.toMillis();
    }

    /**
     * 将 {@link com.huanbao.dataquery.router.IntentClassifier} 当前轮产出的计划和上一轮状态
     * 合并成新计划，并原子提交新状态。
     */
    public AnalysisPlanDto applyState(
            String conversationKey,
            AnalysisPlanDto plan,
            String rawQuery) {
        String key = requireConversationKey(conversationKey);
        Objects.requireNonNull(plan, "plan");
        String query = rawQuery == null ? "" : rawQuery.trim();
        long now = clock.millis();
        AtomicReference<ConversationAnalysisState> applied = new AtomicReference<>();

        states.compute(key, (ignored, existing) -> {
            ConversationAnalysisState previous = existing == null || existing.isExpired(now, ttlMillis)
                    ? ConversationAnalysisState.empty(conversationIdFromKey(key))
                    : existing.state;
            ConversationAnalysisState next = transition(previous, plan, query);
            applied.set(next);
            return new StateEntry(next, now);
        });
        purgeExpired(now);

        return projectPlan(plan, applied.get(), query);
    }

    /**
     * 读取状态快照并刷新滑动过期时间；没有状态或已过期时返回 null。
     */
    public ConversationAnalysisState getState(String conversationKey) {
        String key = requireConversationKey(conversationKey);
        long now = clock.millis();
        AtomicReference<ConversationAnalysisState> found = new AtomicReference<>();
        states.computeIfPresent(key, (ignored, entry) -> {
            if (entry.isExpired(now, ttlMillis)) {
                return null;
            }
            entry.lastAccessMillis = now;
            found.set(entry.state);
            return entry;
        });
        purgeExpired(now);
        return found.get();
    }

    public Optional<ConversationAnalysisState> findState(String conversationKey) {
        return Optional.ofNullable(getState(conversationKey));
    }

    /** 主动清理过期快照，便于定时任务或测试调用。 */
    public int purgeExpiredStates() {
        return purgeExpired(clock.millis());
    }

    public void clearState(String conversationKey) {
        states.remove(requireConversationKey(conversationKey));
    }

    int stateCount() {
        return states.size();
    }

    private ConversationAnalysisState transition(
            ConversationAnalysisState previous,
            AnalysisPlanDto plan,
            String query) {
        ResolvedMetric resolvedMetric = resolveMetric(plan.metricInputs(), query);
        boolean hasMetric = resolvedMetric != null;
        String metricCode = hasMetric ? resolvedMetric.definition().indicatorCode() : previous.currentMetricCode();
        String metricName = hasMetric ? resolvedMetric.definition().formalName() : previous.currentMetricName();

        String comparison = explicitComparison(query);
        boolean hasExplicitTime = hasExplicitTime(plan.timeExpression(), query, comparison != null);
        String timeExpression = hasExplicitTime
                ? normalizedTime(plan.timeExpression(), query)
                : previous.timeExpression();

        Integer topN = parseTopN(query);
        String planAnalysisType = normalizeAnalysisType(plan.analysisType());
        boolean rankingSignal = ConversationAnalysisState.RANKING.equals(planAnalysisType)
                || RANKING_PATTERN.matcher(query).find();
        boolean explicitOrganization = hasResolvedOrganization(plan.orgInputs(), query);

        boolean topNOnly = topN != null
                && !hasMetric
                && !hasExplicitTime
                && !explicitOrganization
                && comparison == null;
        boolean comparisonOnly = comparison != null
                && !hasMetric
                && !hasExplicitTime
                && !explicitOrganization
                && topN == null;

        String analysisType = planAnalysisType;
        if ((topNOnly || comparisonOnly) && previous.lastAnalysisType() != null) {
            analysisType = previous.lastAnalysisType();
        }
        if (analysisType == null) {
            analysisType = previous.lastAnalysisType() == null
                    ? ConversationAnalysisState.FACT
                    : previous.lastAnalysisType();
        }
        if (rankingSignal) {
            analysisType = ConversationAnalysisState.RANKING;
        }

        ScopeResolution scope = resolveScope(plan.orgInputs(), query);
        boolean collisionReset = previous.focusOrgCode() != null && scope.isBroadScope();
        String scopeType = previous.currentScopeType();
        String focusOrgCode = previous.focusOrgCode();
        String focusOrgName = previous.focusOrgName();

        if (rankingSignal || collisionReset) {
            focusOrgCode = null;
            focusOrgName = null;
            scopeType = scope.preferredBroadScopeType();
        } else if (!scope.isEmpty()) {
            scopeType = scope.scopeType();
            if (ConversationAnalysisState.PROJECT_COMPANY.equals(scope.scopeType())) {
                focusOrgCode = scope.orgCode();
                focusOrgName = scope.orgName();
            } else {
                focusOrgCode = null;
                focusOrgName = null;
            }
        }

        if (scopeType == null && rankingSignal) {
            scopeType = scope.preferredBroadScopeType();
        }

        Integer nextTopN = topN == null ? previous.topN() : topN;
        if (ConversationAnalysisState.PROJECT_COMPANY.equals(scopeType)
                && ConversationAnalysisState.FACT.equals(analysisType)) {
            nextTopN = null;
        }

        return new ConversationAnalysisState(
                previous.conversationId(),
                metricCode,
                metricName,
                timeExpression,
                scopeType,
                focusOrgCode,
                focusOrgName,
                nextTopN,
                comparison == null ? previous.comparisonType() : comparison,
                analysisType);
    }

    private AnalysisPlanDto projectPlan(
            AnalysisPlanDto original,
            ConversationAnalysisState state,
            String query) {
        List<String> metrics = original.metricInputs().size() > 1
                ? original.metricInputs()
                : state.currentMetricName() == null
                ? original.metricInputs()
                : List.of(state.currentMetricName());
        List<String> organizations = projectOrganizations(original.orgInputs(), state, query);
        String analysisType = state.lastAnalysisType() == null
                ? normalizeAnalysisType(original.analysisType())
                : state.lastAnalysisType();
        return new AnalysisPlanDto(
                original.primaryIntent(),
                metrics,
                organizations,
                state.timeExpression() == null ? "" : state.timeExpression(),
                analysisType == null ? "" : analysisType,
                original.subTasks());
    }

    private List<String> projectOrganizations(
            List<String> original,
            ConversationAnalysisState state,
            String query) {
        if (original.size() > 1) {
            return List.copyOf(original);
        }
        if (state.focusOrgName() != null) {
            return List.of(state.focusOrgName());
        }
        if (ConversationAnalysisState.REGION.equals(state.currentScopeType())) {
            List<String> resolved = resolveRegionNames(original, query);
            return resolved.isEmpty() ? List.of("大区") : resolved;
        }
        if (ConversationAnalysisState.GROUP.equals(state.currentScopeType())) {
            return List.of("项目公司");
        }
        return original;
    }

    private List<String> resolveRegionNames(List<String> original, String query) {
        List<String> candidates = new ArrayList<>();
        candidates.addAll(original);
        for (String term : regionTerms()) {
            if (query.contains(term)) {
                candidates.add(term);
            }
        }
        return candidates.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> {
                    try {
                        EntityMapping mapping = semanticProvider.resolveEntity(value);
                        return ConversationAnalysisState.REGION.equals(mapping.entityType())
                                ? mapping.canonicalName()
                                : null;
                    } catch (IllegalArgumentException ignored) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private ResolvedMetric resolveMetric(List<String> planInputs, String query) {
        for (String input : planInputs) {
            MetricDefinition definition = semanticProvider.getMetric(input);
            if (definition != null) {
                return new ResolvedMetric(definition);
            }
        }

        List<MetricDefinition> definitions = semanticProvider.getMetrics().stream()
                .filter(definition -> containsMetricTerm(query, definition))
                .sorted(Comparator.comparingInt((MetricDefinition definition) -> longestMetricTerm(definition))
                        .reversed())
                .toList();
        if (!definitions.isEmpty()) {
            return new ResolvedMetric(definitions.get(0));
        }

        // 语义字典里的正式口径是“生活垃圾入厂量”，业务追问常缩写为“垃圾量”。
        // 仍然只从 provider 已注册的指标中选取，不将自然语言直接变成指标编码。
        if (query.contains("垃圾量")) {
            return semanticProvider.getMetrics().stream()
                    .filter(definition -> definition.formalName().contains("生活垃圾")
                            && definition.formalName().contains("入厂量"))
                    .findFirst()
                    .map(ResolvedMetric::new)
                    .orElse(null);
        }
        return null;
    }

    private ScopeResolution resolveScope(List<String> planInputs, String query) {
        List<String> candidates = new ArrayList<>();
        candidates.addAll(planInputs);
        for (String term : organizationTerms()) {
            if (query.contains(term)) {
                candidates.add(term);
            }
        }

        ScopeResolution broad = ScopeResolution.empty();
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            ScopeResolution resolved = resolveOneScope(candidate);
            if (ConversationAnalysisState.PROJECT_COMPANY.equals(resolved.scopeType())) {
                return resolved;
            }
            if (!resolved.isEmpty()) {
                broad = resolved;
            }
        }
        if (REGION_SCOPE_PATTERN.matcher(query).find()) {
            return ScopeResolution.region("大区");
        }
        if (containsAny(query, GROUP_TERMS)) {
            return ScopeResolution.group();
        }
        return broad;
    }

    private ScopeResolution resolveOneScope(String candidate) {
        String normalized = candidate.trim();
        if (isGroupTerm(normalized)) {
            return ScopeResolution.group();
        }
        try {
            EntityMapping mapping = semanticProvider.resolveEntity(normalized);
            String type = mapping.entityType().toUpperCase(Locale.ROOT);
            if (ConversationAnalysisState.REGION.equals(type)) {
                return ScopeResolution.region(mapping.canonicalName());
            }
            if (ConversationAnalysisState.GROUP.equals(type)) {
                return ScopeResolution.group();
            }
            if (ConversationAnalysisState.PROJECT_COMPANY.equals(type)) {
                Optional<OpsOrganization> organization = semanticProvider.findOrganization(mapping.canonicalName());
                return new ScopeResolution(
                        ConversationAnalysisState.PROJECT_COMPANY,
                        organization.map(OpsOrganization::formalCode).orElse(null),
                        mapping.canonicalName(),
                        false);
            }
        } catch (IllegalArgumentException ignored) {
            // 未经语义 provider 确认的组织不能污染当前状态。
        }
        return ScopeResolution.empty();
    }

    private boolean hasResolvedOrganization(List<String> planInputs, String query) {
        return !resolveScope(planInputs, query).isEmpty();
    }

    private boolean hasExplicitTime(String planTime, String query, boolean comparisonPresent) {
        if (planTime != null && !planTime.isBlank()
                && (!comparisonPresent || !isComparisonReference(planTime))) {
            // 路由计划已经经过 IntentClassifier 的受控时间归一化；允许它把
            // 最近半年等自然语言别名归一成近6个月，即使原句没有逐字出现。
            return true;
        }
        Matcher matcher = TIME_PATTERN.matcher(query);
        while (matcher.find()) {
            if (!comparisonPresent || !isComparisonReference(matcher.group())) {
                return true;
            }
        }
        return false;
    }

    private String normalizedTime(String planTime, String query) {
        String comparison = explicitComparison(query);
        if (planTime != null && !planTime.isBlank() && query.contains(planTime)
                && (comparison == null || !isComparisonReference(planTime))) {
            return planTime.trim();
        }
        Matcher matcher = TIME_PATTERN.matcher(query);
        while (matcher.find()) {
            if (comparison == null || !isComparisonReference(matcher.group())) {
                return matcher.group().trim();
            }
        }
        return planTime == null || planTime.isBlank() ? null : planTime.trim();
    }

    private boolean isComparisonReference(String time) {
        return "去年".equals(time) || "去年同期".equals(time) || "上月".equals(time);
    }

    private String explicitComparison(String query) {
        if (COMPARISON_YOY_PATTERN.matcher(query).find()) {
            return ConversationAnalysisState.YOY;
        }
        if (COMPARISON_MOM_PATTERN.matcher(query).find()) {
            return ConversationAnalysisState.MOM;
        }
        return null;
    }

    private Integer parseTopN(String query) {
        Matcher matcher = TOP_N_PATTERN.matcher(query);
        if (!matcher.find()) {
            return null;
        }
        String number = matcher.group(1) == null ? matcher.group(2) : matcher.group(1);
        int parsed = parseNumber(number);
        return parsed > 0 ? parsed : null;
    }

    private int parseNumber(String value) {
        if (value.chars().allMatch(Character::isDigit)) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        Map<Character, Integer> digits = Map.of(
                '一', 1, '二', 2, '三', 3, '四', 4, '五', 5,
                '六', 6, '七', 7, '八', 8, '九', 9, '零', 0);
        if (value.length() == 1 && digits.containsKey(value.charAt(0))) {
            return digits.get(value.charAt(0));
        }
        int total = 0;
        int section = 0;
        int number = 0;
        for (char character : value.toCharArray()) {
            if (digits.containsKey(character)) {
                number = digits.get(character);
            } else if (character == '十' || character == '百' || character == '千' || character == '万') {
                int unit = character == '十' ? 10 : character == '百' ? 100 : character == '千' ? 1000 : 10000;
                section += (number == 0 ? 1 : number) * unit;
                number = 0;
                if (unit == 10000) {
                    total += section;
                    section = 0;
                }
            } else {
                return 0;
            }
        }
        return total + section + number;
    }

    private boolean containsMetricTerm(String query, MetricDefinition definition) {
        if (query == null || query.isEmpty()) {
            return false;
        }
        return query.contains(definition.formalName())
                || definition.aliases().stream().anyMatch(query::contains);
    }

    private int longestMetricTerm(MetricDefinition definition) {
        return Math.max(
                definition.formalName().length(),
                definition.aliases().stream().mapToInt(String::length).max().orElse(0));
    }

    private List<String> organizationTerms() {
        return semanticProvider.getOrganizations().stream()
                .flatMap(organization -> java.util.stream.Stream.of(
                        organization.shortName(),
                        stripCompanySuffix(organization.shortName()),
                        organization.formalName(),
                        organization.region(),
                        stripRegionSuffix(organization.region())))
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
    }

    private List<String> regionTerms() {
        return semanticProvider.getOrganizations().stream()
                .map(OpsOrganization::region)
                .filter(Objects::nonNull)
                .flatMap(value -> java.util.stream.Stream.of(value, stripRegionSuffix(value)))
                .filter(value -> !value.isBlank())
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
    }

    private boolean isGroupTerm(String value) {
        return GROUP_TERMS.stream().anyMatch(value::equals);
    }

    private boolean containsAny(String query, Collection<String> terms) {
        return terms.stream().anyMatch(query::contains);
    }

    private String normalizeAnalysisType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return List.of(
                        ConversationAnalysisState.RANKING,
                        ConversationAnalysisState.TREND,
                        ConversationAnalysisState.FACT,
                        ConversationAnalysisState.REPORT)
                .contains(normalized) ? normalized : null;
    }

    private String requireConversationKey(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("conversationKey must not be blank");
        }
        return value.trim();
    }

    private String conversationIdFromKey(String key) {
        int separator = key.lastIndexOf(':');
        return separator >= 0 && separator < key.length() - 1
                ? key.substring(separator + 1)
                : key;
    }

    private int purgeExpired(long now) {
        int removed = 0;
        for (Map.Entry<String, StateEntry> entry : states.entrySet()) {
            if (entry.getValue().isExpired(now, ttlMillis)
                    && states.remove(entry.getKey(), entry.getValue())) {
                removed++;
            }
        }
        return removed;
    }

    private static String stripCompanySuffix(String value) {
        return value != null && value.endsWith("公司")
                ? value.substring(0, value.length() - 2)
                : value;
    }

    private static String stripRegionSuffix(String value) {
        return value != null && value.endsWith("大区")
                ? value.substring(0, value.length() - 2)
                : value;
    }

    private record ResolvedMetric(MetricDefinition definition) {
    }

    private record ScopeResolution(
            String scopeType,
            String orgCode,
            String orgName,
            boolean broadScope) {

        private static ScopeResolution empty() {
            return new ScopeResolution(null, null, null, false);
        }

        private static ScopeResolution group() {
            return new ScopeResolution(ConversationAnalysisState.GROUP, null, null, true);
        }

        private static ScopeResolution region(String name) {
            return new ScopeResolution(ConversationAnalysisState.REGION, null, name, true);
        }

        private boolean isEmpty() {
            return scopeType == null;
        }

        private boolean isBroadScope() {
            return broadScope;
        }

        private String preferredBroadScopeType() {
            return ConversationAnalysisState.REGION.equals(scopeType)
                    ? ConversationAnalysisState.REGION
                    : ConversationAnalysisState.GROUP;
        }
    }

    private static final class StateEntry {

        private final ConversationAnalysisState state;
        private volatile long lastAccessMillis;

        private StateEntry(ConversationAnalysisState state, long lastAccessMillis) {
            this.state = state;
            this.lastAccessMillis = lastAccessMillis;
        }

        private boolean isExpired(long now, long ttlMillis) {
            return now - lastAccessMillis >= ttlMillis;
        }
    }
}
