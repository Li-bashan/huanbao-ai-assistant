package com.huanbao.dataquery.core.engine;

import com.huanbao.dataquery.core.spi.DomainSemanticProvider;
import com.huanbao.dataquery.core.spi.DomainSemanticProviderRegistry;
import com.huanbao.dataquery.core.spi.MetricDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通过领域 SPI 拆解指标，避免核心引擎依赖某个部门的指标编码。
 */
public final class MetricDecomposer {

    private static final Pattern BASE_VARIABLE =
            Pattern.compile("\\bbase_([A-Za-z0-9_]+)\\b");
    private static final Pattern RAW_DAILY_METRIC_CODE = Pattern.compile("[0-9]+");

    private final DomainSemanticProvider provider;

    public MetricDecomposer(DomainSemanticProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    public MetricDecomposer(DomainSemanticProviderRegistry registry, String domainCode) {
        this(Objects.requireNonNull(registry, "registry").require(domainCode));
    }

    public MetricDecomposition decompose(String nameOrAlias) {
        MetricDefinition requestedMetric = provider.getMetric(nameOrAlias);
        if (requestedMetric == null) {
            throw new IllegalArgumentException("Unknown metric: " + nameOrAlias);
        }

        Map<String, MetricDefinition> atomicMetrics = new LinkedHashMap<>();
        collectAtomicMetrics(requestedMetric, new LinkedHashSet<>(), atomicMetrics);

        List<String> baseMetricRefs = requestedMetric.metricType().equalsIgnoreCase("ATOMIC")
                ? List.of(requestedMetric.indicatorCode())
                : requestedMetric.baseMetricRefs();
        DivisionDependencies dependencies = splitDivisionDependencies(requestedMetric.formulaText());
        return new MetricDecomposition(
                requestedMetric,
                new ArrayList<>(atomicMetrics.values()),
                baseMetricRefs,
                dependencies.numeratorBaseMetricRefs(),
                dependencies.denominatorBaseMetricRefs());
    }

    private void collectAtomicMetrics(
            MetricDefinition metric,
            Set<String> visiting,
            Map<String, MetricDefinition> atomicMetrics) {
        String metricKey = metric.indicatorCode().trim().toUpperCase(Locale.ROOT);
        if (!visiting.add(metricKey)) {
            throw new IllegalArgumentException("Cyclic metric dependency: " + metric.indicatorCode());
        }

        if (metric.metricType().equalsIgnoreCase("ATOMIC")) {
            atomicMetrics.putIfAbsent(metricKey, metric);
        } else {
            for (String baseMetricRef : metric.baseMetricRefs()) {
                MetricDefinition dependency = provider.getMetric(baseMetricRef);
                if (dependency == null) {
                    if (!RAW_DAILY_METRIC_CODE.matcher(baseMetricRef).matches()) {
                        throw new IllegalArgumentException(
                                "Metric " + metric.indicatorCode()
                                        + " depends on unknown metric " + baseMetricRef);
                    }
                    dependency = rawDailyMetric(baseMetricRef);
                }
                collectAtomicMetrics(dependency, visiting, atomicMetrics);
            }
        }
        visiting.remove(metricKey);
    }

    private static MetricDefinition rawDailyMetric(String metricCode) {
        return new MetricDefinition(
                metricCode,
                metricCode,
                Set.of(),
                "RAW_DAILY_METRIC",
                "ATOMIC",
                false,
                List.of(metricCode),
                List.of(),
                "base_" + metricCode,
                "raw",
                "SUM",
                List.of(),
                List.of(),
                List.of(),
                "SOURCE_ONLY",
                List.of());
    }

    private static DivisionDependencies splitDivisionDependencies(String formula) {
        String expression = calculationBranch(formula);
        int divisionIndex = findTopLevelDivision(expression);
        if (divisionIndex < 0) {
            return new DivisionDependencies(extractBaseRefs(expression), List.of());
        }
        return new DivisionDependencies(
                extractBaseRefs(expression.substring(0, divisionIndex)),
                extractBaseRefs(expression.substring(divisionIndex + 1)));
    }

    private static String trueBranch(String formula) {
        int questionIndex = findTopLevelCharacter(formula, '?');
        if (questionIndex < 0) {
            return formula;
        }
        int colonIndex = findTopLevelCharacter(formula, ':', questionIndex + 1);
        if (colonIndex < 0) {
            return formula.substring(questionIndex + 1).trim();
        }
        return formula.substring(questionIndex + 1, colonIndex).trim();
    }

    private static String calculationBranch(String formula) {
        String selectedBranch = trueBranch(formula);
        if (findTopLevelDivision(selectedBranch) >= 0 || !hasZeroGuard(formula)) {
            return selectedBranch;
        }
        int questionIndex = findTopLevelCharacter(formula, '?');
        int colonIndex = findTopLevelCharacter(formula, ':', questionIndex + 1);
        return colonIndex < 0 ? selectedBranch : formula.substring(colonIndex + 1).trim();
    }

    private static boolean hasZeroGuard(String formula) {
        int questionIndex = findTopLevelCharacter(formula, '?');
        if (questionIndex < 0) {
            return false;
        }
        return formula.substring(0, questionIndex).contains("== 0");
    }

    private static int findTopLevelDivision(String expression) {
        for (int index = 0; index < expression.length(); index++) {
            if (expression.charAt(index) == '/') {
                return index;
            }
        }
        return -1;
    }

    private static int findTopLevelCharacter(String expression, char target) {
        return findTopLevelCharacter(expression, target, 0);
    }

    private static int findTopLevelCharacter(String expression, char target, int startIndex) {
        int depth = 0;
        for (int index = startIndex; index < expression.length(); index++) {
            char current = expression.charAt(index);
            if (current == '(') {
                depth++;
            } else if (current == ')') {
                depth--;
            } else if (current == target && depth == 0) {
                return index;
            }
        }
        return -1;
    }

    private static List<String> extractBaseRefs(String expression) {
        LinkedHashSet<String> refs = new LinkedHashSet<>();
        Matcher matcher = BASE_VARIABLE.matcher(expression);
        while (matcher.find()) {
            refs.add(matcher.group(1));
        }
        return List.copyOf(refs);
    }

    private record DivisionDependencies(
            List<String> numeratorBaseMetricRefs,
            List<String> denominatorBaseMetricRefs) {
    }
}
