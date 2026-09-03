package com.huanbao.dataquery.domain.ops;

import com.huanbao.dataquery.core.engine.MetricDecomposer;
import com.huanbao.dataquery.core.engine.MetricDecomposition;
import com.huanbao.dataquery.core.engine.SafeMetricSqlBuilder;
import com.huanbao.dataquery.core.repository.PartitionTablePruner;
import com.huanbao.dataquery.core.spi.DataQueryRequest;
import com.huanbao.dataquery.core.spi.EntityMapping;
import com.huanbao.dataquery.core.spi.MetricDefinition;
import com.huanbao.dataquery.core.spi.SqlBuildStrategy;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** 运营领域的受控半年快照 SQL 生成器。 */
public final class OpsSqlBuildStrategy implements SqlBuildStrategy {

    private static final int DEFAULT_QUERY_YEAR = 2026;
    private static final Pattern YEAR = Pattern.compile("20[0-9]{2}");
    private static final Pattern DATE = Pattern.compile("20[0-9]{2}-[0-9]{2}-[0-9]{2}");
    private static final Pattern MONTH = Pattern.compile("20[0-9]{2}-[0-9]{2}");
    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final OpsDomainSemanticProvider provider;
    private final MetricDecomposer decomposer;
    private final PartitionTablePruner tablePruner;
    private final SafeMetricSqlBuilder aggregateBuilder = new SafeMetricSqlBuilder();

    public OpsSqlBuildStrategy(OpsDomainSemanticProvider provider) {
        this(provider, new PartitionTablePruner());
    }

    OpsSqlBuildStrategy(OpsDomainSemanticProvider provider, PartitionTablePruner tablePruner) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.decomposer = new MetricDecomposer(provider);
        this.tablePruner = Objects.requireNonNull(tablePruner, "tablePruner");
    }

    @Override
    public String buildSql(DataQueryRequest request) {
        Objects.requireNonNull(request, "request");
        MetricDefinition metric = provider.getMetric(request.metricName());
        if (metric == null) {
            throw new IllegalArgumentException("Unknown OPS metric: " + request.metricName());
        }
        MetricDecomposition decomposition = decomposer.decompose(metric.indicatorCode());
        EntityMapping entity = provider.resolveEntity(request.entityName());
        String controlledSource = buildControlledSource(entity, request.filters());
        return aggregateBuilder.buildAggregateSql(controlledSource, decomposition.atomicMetricCodes());
    }

    /**
     * 构造一次返回全部组织、全部月份原子值的矩阵 SQL。组织集合通过命名参数绑定，
     * 不拼接用户输入；根查询仍由 KingbaseQueryExecutor 追加授权范围谓词。
     */
    public String buildMatrixSql(
            DataQueryRequest request,
            Collection<String> atomicMetricCodes) {
        Objects.requireNonNull(request, "request");
        MetricDefinition metric = provider.getMetric(request.metricName());
        if (metric == null) {
            throw new IllegalArgumentException("Unknown OPS metric: " + request.metricName());
        }
        Objects.requireNonNull(atomicMetricCodes, "atomicMetricCodes");
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        for (String code : atomicMetricCodes) {
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("atomicMetricCodes must not contain blank values");
            }
            codes.add(code.trim());
        }
        if (codes.isEmpty()) {
            throw new IllegalArgumentException("atomicMetricCodes must not be empty");
        }
        List<String> organizationCodes = organizationCodes(request.filters());
        EntityMapping entity = provider.resolveEntity(request.entityName());
        String controlledSource = buildMatrixControlledSource(
                entity, request.filters(), organizationCodes, codes);
        return aggregateBuilder.buildMonthlyMatrixSql(controlledSource, codes);
    }

    /**
     * 先在分区数据上按组织、月份和原子指标预聚合，再连接组织语义映射。
     * 这样 63 家公司的全量查询不会在明细行上重复执行映射连接。
     */
    private String buildMatrixControlledSource(
            EntityMapping entity,
            Map<String, Object> filters,
            Collection<String> organizationCodes,
            Collection<String> metricCodes) {
        DateRange dateRange = resolveDateRange(filters);
        PartitionTablePruner.PruningResult pruning = tablePruner.prune(
                dateRange.startDate(), dateRange.endDate());
        String monthExpression = "SUBSTRING(CAST(p.\"ZBRQ\" AS VARCHAR), 1, 7)";
        String aggregatedSource = "SELECT p.\"orgcode\", " + monthExpression + " AS period, "
                + "p.\"newIndicator\", SUM(CASE WHEN p.\"ZBZ\" ~ '"
                + SafeMetricSqlBuilder.NUMERIC_TEXT_REGEX
                + "' THEN CAST(p.\"ZBZ\" AS NUMERIC(18,2)) ELSE 0.00 END) AS aggregated_value\n"
                + "FROM (\n"
                + pruning.controlledSourceSql()
                + "\n) p\n"
                + "WHERE p.\"orgcode\" IN (:matrixOrgCodes)\n"
                + "  AND p.\"newIndicator\" IN (:matrixMetricCodes)\n"
                + "GROUP BY p.\"orgcode\", " + monthExpression + ", p.\"newIndicator\"";

        StringBuilder source = new StringBuilder()
                .append("SELECT m.\"newIndicator\", CAST(m.aggregated_value AS VARCHAR) AS \"ZBZ\", ")
                .append("m.\"orgcode\", m.period AS \"ZBRQ\", o.short_name AS \"short_name\"\n")
                .append("FROM (\n")
                .append(aggregatedSource)
                .append("\n) m\n")
                .append("JOIN ").append(OpsDomainSemanticProvider.ORG_MAPPING_VIEW).append(" o\n")
                .append("  ON o.formal_code = NULLIF(TRIM(m.\"orgcode\"), '')\n")
                .append("WHERE o.business_type = '")
                .append(escapeLiteral(OpsDomainSemanticProvider.GARBAGE_INCINERATION_BUSINESS_TYPE))
                .append("' AND o.is_production_plant = TRUE");
        appendEntityScope(source, entity);
        StringBuilder metadata = new StringBuilder()
                .append("\nUNION ALL\n")
                .append("SELECT CAST(NULL AS VARCHAR) AS \"newIndicator\", ")
                .append("CAST(NULL AS VARCHAR) AS \"ZBZ\", o.formal_code AS \"orgcode\", ")
                .append("CAST(NULL AS VARCHAR) AS \"ZBRQ\", o.short_name AS \"short_name\"\n")
                .append("FROM ").append(OpsDomainSemanticProvider.ORG_MAPPING_VIEW).append(" o\n")
                .append("WHERE o.business_type = '")
                .append(escapeLiteral(OpsDomainSemanticProvider.GARBAGE_INCINERATION_BUSINESS_TYPE))
                .append("' AND o.is_production_plant = TRUE\n")
                .append("  AND o.formal_code IN (:matrixOrgCodes)");
        appendEntityScope(metadata, entity);
        source.append(metadata);
        return source.toString();
    }

    private String buildControlledSource(EntityMapping entity, Map<String, Object> filters) {
        return buildControlledSource(entity, filters, List.of());
    }

    private String buildControlledSource(
            EntityMapping entity,
            Map<String, Object> filters,
            Collection<String> organizationCodes) {
        return buildControlledSource(entity, filters, organizationCodes, List.of());
    }

    private String buildControlledSource(
            EntityMapping entity,
            Map<String, Object> filters,
            Collection<String> organizationCodes,
            Collection<String> metricCodes) {
        DateRange dateRange = resolveDateRange(filters);
        PartitionTablePruner.PruningResult pruning = tablePruner.prune(
                dateRange.startDate(), dateRange.endDate());

        StringBuilder source = new StringBuilder()
                .append("SELECT d.\"newIndicator\", d.\"ZBZ\", d.\"orgcode\", d.\"ZBRQ\", o.short_name AS \"short_name\"\n")
                .append("FROM (\n")
                .append("SELECT p.\"newIndicator\", p.\"ZBZ\", p.\"orgcode\", p.\"ZBRQ\"\n")
                .append("FROM (\n")
                .append(pruning.controlledSourceSql())
                .append("\n) p");
        boolean hasInnerFilter = false;
        if (!organizationCodes.isEmpty()) {
            source.append("\nWHERE p.\"orgcode\" IN (:matrixOrgCodes)");
            hasInnerFilter = true;
        }
        if (!metricCodes.isEmpty()) {
            source.append(hasInnerFilter ? "\n  AND " : "\nWHERE ")
                    .append("p.\"newIndicator\" IN (:matrixMetricCodes)");
        }
        source.append("\n")
                .append("\n) d\n")
                .append("JOIN ").append(OpsDomainSemanticProvider.ORG_MAPPING_VIEW).append(" o\n")
                .append("  ON o.formal_code = NULLIF(TRIM(d.\"orgcode\"), '')\n")
                .append("WHERE o.business_type = '")
                .append(escapeLiteral(OpsDomainSemanticProvider.GARBAGE_INCINERATION_BUSINESS_TYPE))
                .append("' AND o.is_production_plant = TRUE");

        appendEntityScope(source, entity);
        if (!organizationCodes.isEmpty()) {
            source.append("\n  AND o.formal_code IN (:matrixOrgCodes)");
        }
        return source.toString();
    }

    private static List<String> organizationCodes(Map<String, Object> filters) {
        Object value = filters.get("matrixOrgCodes");
        if (!(value instanceof Collection<?> collection)) {
            throw new IllegalArgumentException("matrixOrgCodes must be a non-empty collection");
        }
        List<String> codes = collection.stream()
                .map(item -> Objects.requireNonNull(item, "matrixOrgCodes must not contain null"))
                .map(Object::toString)
                .map(String::trim)
                .filter(code -> !code.isEmpty())
                .distinct()
                .toList();
        if (codes.isEmpty()) {
            throw new IllegalArgumentException("matrixOrgCodes must be a non-empty collection");
        }
        return codes;
    }

    private void appendEntityScope(StringBuilder source, EntityMapping entity) {
        switch (entity.entityType()) {
            case "GROUP" -> {
                // 生产类型条件已经限定到全部垃圾焚烧发电项目公司。
            }
            case "REGION" -> source.append("\n  AND o.region = '")
                    .append(escapeLiteral(entity.canonicalName()))
                    .append("'");
            case "PROJECT_COMPANY" -> {
                OpsOrganization organization = provider.findOrganization(entity.canonicalName())
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Unresolved OPS project company: " + entity.canonicalName()));
                source.append("\n  AND o.formal_code = ");
                if (organization.formalCode() != null
                        && organization.formalCode().matches("[0-9A-Za-z-]{2,64}")) {
                    source.append("'").append(escapeLiteral(organization.formalCode())).append("'");
                } else {
                    // 视图负责把正式名称映射成 formal_code；关联条件本身仍然只使用 formal_code。
                    source.append("(SELECT m.formal_code FROM ")
                            .append(OpsDomainSemanticProvider.ORG_MAPPING_VIEW)
                            .append(" m WHERE m.short_name = '")
                            .append(escapeLiteral(organization.shortName()))
                            .append("' AND m.business_type = '")
                            .append(escapeLiteral(OpsDomainSemanticProvider.GARBAGE_INCINERATION_BUSINESS_TYPE))
                            .append("' AND m.is_production_plant = TRUE ORDER BY m.formal_code FETCH FIRST 1 ROW ONLY)");
                }
            }
            default -> throw new IllegalArgumentException("Unsupported OPS entity type: " + entity.entityType());
        }
    }

    private static DateRange resolveDateRange(Map<String, Object> filters) {
        String requestedStart = optionalText(filters, "startDate");
        String requestedEnd = optionalText(filters, "endDate");
        if (requestedStart != null && requestedEnd != null
                && optionalText(filters, "month") == null
                && optionalText(filters, "year") == null
                && optionalText(filters, "periodYear") == null
                && optionalText(filters, "half") == null
                && optionalText(filters, "periodHalf") == null) {
            LocalDate startDate = parseDate(requestedStart, "startDate");
            LocalDate endDate = parseDate(requestedEnd, "endDate");
            return new DateRange(startDate, endDate);
        }
        String month = optionalText(filters, "month");
        LocalDate startDate;
        LocalDate endDate;
        if (month != null) {
            if (!MONTH.matcher(month).matches()) {
                throw new IllegalArgumentException("month must use yyyy-MM");
            }
            try {
                YearMonth yearMonth = YearMonth.parse(month, MONTH_FORMAT);
                startDate = yearMonth.atDay(1);
                endDate = yearMonth.atEndOfMonth();
            } catch (DateTimeParseException exception) {
                throw new IllegalArgumentException("Invalid month: " + month, exception);
            }
        } else {
            int year = resolveYear(filters);
            String half = resolveHalf(filters);
            if (half == null) {
                startDate = LocalDate.of(year, 1, 1);
                endDate = LocalDate.of(year, 12, 31);
            } else if (half.equals("06")) {
                startDate = LocalDate.of(year, 1, 1);
                endDate = LocalDate.of(year, 6, 30);
            } else {
                startDate = LocalDate.of(year, 7, 1);
                endDate = LocalDate.of(year, 12, 31);
            }
        }

        if (requestedStart != null) {
            LocalDate parsedStart = parseDate(requestedStart, "startDate");
            startDate = startDate.isAfter(parsedStart) ? startDate : parsedStart;
        }
        if (requestedEnd != null) {
            LocalDate parsedEnd = parseDate(requestedEnd, "endDate");
            endDate = endDate.isBefore(parsedEnd) ? endDate : parsedEnd;
        }
        return new DateRange(startDate, endDate);
    }

    private static int resolveYear(Map<String, Object> filters) {
        String year = optionalText(filters, "year");
        if (year == null) {
            year = optionalText(filters, "periodYear");
        }
        if (year == null) {
            String month = optionalText(filters, "month");
            year = month == null ? String.valueOf(DEFAULT_QUERY_YEAR) : month.substring(0, 4);
        }
        if (!YEAR.matcher(year).matches()) {
            throw new IllegalArgumentException("year must use yyyy");
        }
        return Integer.parseInt(year);
    }

    private static String resolveHalf(Map<String, Object> filters) {
        String half = optionalText(filters, "half");
        if (half == null) {
            half = optionalText(filters, "periodHalf");
        }
        if (half == null || half.isBlank()) {
            return null;
        }
        return switch (half.toUpperCase(java.util.Locale.ROOT)) {
            case "1", "H1", "06" -> "06";
            case "2", "H2", "12" -> "12";
            default -> throw new IllegalArgumentException("half must be 1/H1/06 or 2/H2/12");
        };
    }

    private static LocalDate parseDate(String value, String fieldName) {
        if (!DATE.matcher(value).matches()) {
            throw new IllegalArgumentException(fieldName + " must use yyyy-MM-dd");
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("Invalid " + fieldName + ": " + value, exception);
        }
    }

    private static String optionalText(Map<String, Object> filters, String key) {
        Object value = filters.get(key);
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private static String escapeLiteral(String value) {
        return value.replace("'", "''");
    }

    private record DateRange(LocalDate startDate, LocalDate endDate) {
    }
}
