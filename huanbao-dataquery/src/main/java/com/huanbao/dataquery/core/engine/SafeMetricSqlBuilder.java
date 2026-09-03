package com.huanbao.dataquery.core.engine;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 生成原子指标聚合 SQL 的公共安全边界。
 *
 * <p>KingbaseES 的 ZBZ 是 varchar，所有数值转换都必须先通过正则校验。</p>
 */
public final class SafeMetricSqlBuilder {

    public static final String NUMERIC_TEXT_REGEX = "^-?[0-9]+([.][0-9]+)?$";
    private static final Pattern SAFE_METRIC_CODE = Pattern.compile("[A-Za-z0-9_]+");

    public String buildAggregateSql(String controlledSourceSql, Collection<String> atomicMetricCodes) {
        Objects.requireNonNull(controlledSourceSql, "controlledSourceSql");
        if (controlledSourceSql.isBlank()) {
            throw new IllegalArgumentException("controlledSourceSql must not be blank");
        }
        Objects.requireNonNull(atomicMetricCodes, "atomicMetricCodes");
        LinkedHashSet<String> codes = new LinkedHashSet<>(atomicMetricCodes);
        if (codes.isEmpty()) {
            throw new IllegalArgumentException("atomicMetricCodes must not be empty");
        }

        String selectList = codes.stream()
                .map(SafeMetricSqlBuilder::buildNumericSum)
                .reduce((left, right) -> left + ",\n       " + right)
                .orElseThrow();
        return "SELECT\n       " + selectList
                + "\nFROM (\n"
                + controlledSourceSql.stripTrailing()
                + "\n) metric_rows";
    }

    /**
     * 构造按组织和自然月聚合的矩阵查询。这里保留原子指标列，派生指标由 Java
     * 计算引擎在一次查询返回后统一求值，避免把公式计算拆成大量单点 SQL。
     */
    public String buildMonthlyMatrixSql(
            String controlledSourceSql,
            Collection<String> atomicMetricCodes) {
        Objects.requireNonNull(controlledSourceSql, "controlledSourceSql");
        if (controlledSourceSql.isBlank()) {
            throw new IllegalArgumentException("controlledSourceSql must not be blank");
        }
        Objects.requireNonNull(atomicMetricCodes, "atomicMetricCodes");
        LinkedHashSet<String> codes = new LinkedHashSet<>(atomicMetricCodes);
        if (codes.isEmpty()) {
            throw new IllegalArgumentException("atomicMetricCodes must not be empty");
        }

        String monthExpression = "SUBSTRING(CAST(\"ZBRQ\" AS VARCHAR), 1, 7)";
        String selectList = codes.stream()
                .map(SafeMetricSqlBuilder::buildNumericSum)
                .reduce((left, right) -> left + ",\n       " + right)
                .orElseThrow();
        return "SELECT\n       \"orgcode\",\n       \"short_name\",\n       " + monthExpression + " AS period,\n       "
                + selectList
                + "\nFROM (\n"
                + controlledSourceSql.stripTrailing()
                + "\n) metric_rows\n"
                + "GROUP BY \"orgcode\", \"short_name\", " + monthExpression + "\n"
                + "ORDER BY \"orgcode\", period";
    }

    private static String buildNumericSum(String metricCode) {
        Objects.requireNonNull(metricCode, "metricCode");
        if (!SAFE_METRIC_CODE.matcher(metricCode).matches()) {
            throw new IllegalArgumentException("Unsafe metric code: " + metricCode);
        }
        return "SUM(CASE WHEN \"newIndicator\" = '" + escapeLiteral(metricCode)
                + "' AND \"ZBZ\" ~ '" + NUMERIC_TEXT_REGEX
                + "' THEN CAST(\"ZBZ\" AS NUMERIC(18,2)) ELSE 0.00 END) AS val_"
                + metricCode;
    }

    private static String escapeLiteral(String value) {
        return value.replace("'", "''");
    }
}
