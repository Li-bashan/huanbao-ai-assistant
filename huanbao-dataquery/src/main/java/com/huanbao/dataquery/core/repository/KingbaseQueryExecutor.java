package com.huanbao.dataquery.core.repository;

import com.huanbao.dataquery.core.security.JsqlparserSqlHelper;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * KingbaseES 只读查询执行边界。
 *
 * <p>执行前只接受单个 SELECT，并用 JsqlParser 在根 WHERE AST 注入组织权限。
 * 数据库连接、PreparedStatement 超时和最大行数是三道独立的物理防线。</p>
 */
@Repository
public class KingbaseQueryExecutor {

    public static final int QUERY_TIMEOUT_SECONDS = 15;
    public static final int MAX_ROWS = 5000;
    public static final String ALLOWED_ORGS_PARAMETER = "allowedOrgs";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    @Autowired
    public KingbaseQueryExecutor(DataSource dataSource) {
        this(createTemplate(dataSource));
    }

    /**
     * 便于测试或由已有 Spring JDBC 装配复用。若底层存在 DataSource，仍会重新套上只读代理。
     */
    public KingbaseQueryExecutor(NamedParameterJdbcTemplate jdbcTemplate) {
        Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        DataSource dataSource = jdbcTemplate.getJdbcTemplate().getDataSource();
        this.jdbcTemplate = dataSource == null
                ? configure(jdbcTemplate)
                : createTemplate(dataSource);
    }

    /**
     * 只注入 AST，不把 allowedOrgs 值拼进 SQL；值在执行时由 NamedParameterJdbcTemplate 绑定。
     */
    public String secureSql(String sql) {
        PlainSelect rootSelect = parseRootSelect(sql);
        Expression dataScope = parseOrganizationPredicate();
        return JsqlparserSqlHelper.injectRootWhere(rootSelect, dataScope).toString();
    }

    public String injectAllowedOrganizations(String sql) {
        return secureSql(sql);
    }

    public <T> List<T> query(
            String sql,
            Map<String, ?> parameters,
            Collection<String> allowedOrgs,
            RowMapper<T> rowMapper) {
        Objects.requireNonNull(rowMapper, "rowMapper");
        return jdbcTemplate.query(
                secureSql(sql),
                bindParameters(parameters, allowedOrgs),
                rowMapper);
    }

    public <T> List<T> query(
            String sql,
            SqlParameterSource parameters,
            Collection<String> allowedOrgs,
            RowMapper<T> rowMapper) {
        Objects.requireNonNull(rowMapper, "rowMapper");
        return jdbcTemplate.query(
                secureSql(sql),
                bindParameters(parameters, allowedOrgs),
                rowMapper);
    }

    public List<Map<String, Object>> queryForList(
            String sql,
            Map<String, ?> parameters,
            Collection<String> allowedOrgs) {
        return jdbcTemplate.queryForList(
                secureSql(sql),
                bindParameters(parameters, allowedOrgs));
    }

    public List<Map<String, Object>> queryForList(
            String sql,
            Collection<String> allowedOrgs) {
        return queryForList(sql, Map.of(), allowedOrgs);
    }

    private static NamedParameterJdbcTemplate createTemplate(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        JdbcTemplate readOnlyJdbcTemplate = new JdbcTemplate(new ReadOnlyDataSource(dataSource));
        return configure(new NamedParameterJdbcTemplate(readOnlyJdbcTemplate));
    }

    private static NamedParameterJdbcTemplate configure(NamedParameterJdbcTemplate template) {
        JdbcTemplate jdbcOperations = template.getJdbcTemplate();
        jdbcOperations.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
        jdbcOperations.setMaxRows(MAX_ROWS);
        return template;
    }

    private static MapSqlParameterSource bindParameters(
            Map<String, ?> parameters,
            Collection<String> allowedOrgs) {
        Objects.requireNonNull(parameters, "parameters");
        return bindParameters(new MapSqlParameterSource(parameters), allowedOrgs);
    }

    private static MapSqlParameterSource bindParameters(
            SqlParameterSource parameters,
            Collection<String> allowedOrgs) {
        Objects.requireNonNull(parameters, "parameters");
        List<String> normalizedAllowedOrgs = normalizeAllowedOrgs(allowedOrgs);
        Map<String, Object> values = new LinkedHashMap<>();
        String[] parameterNames = parameters.getParameterNames();
        if (parameterNames == null) {
            throw new IllegalArgumentException("parameters must expose named values");
        }
        for (String parameterName : parameterNames) {
            values.put(parameterName, parameters.getValue(parameterName));
        }
        values.put(ALLOWED_ORGS_PARAMETER, normalizedAllowedOrgs);
        return new MapSqlParameterSource(values);
    }

    private static List<String> normalizeAllowedOrgs(Collection<String> allowedOrgs) {
        Objects.requireNonNull(allowedOrgs, "allowedOrgs");
        List<String> normalized = allowedOrgs.stream()
                .map(value -> Objects.requireNonNull(value, "allowedOrgs must not contain null"))
                .map(String::trim)
                .distinct()
                .toList();
        if (normalized.isEmpty() || normalized.stream().anyMatch(String::isEmpty)) {
            throw new IllegalArgumentException("allowedOrgs must contain at least one non-blank organization code");
        }
        return normalized;
    }

    private static PlainSelect parseRootSelect(String sql) {
        Objects.requireNonNull(sql, "sql");
        if (sql.isBlank()) {
            throw new IllegalArgumentException("sql must not be blank");
        }
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            if (!(statement instanceof Select select) || !(select instanceof PlainSelect plainSelect)) {
                throw new IllegalArgumentException("Only a single plain SELECT is allowed");
            }
            if (plainSelect.getForUpdateTable() != null
                    || plainSelect.getForClause() != null
                    || plainSelect.getForMode() != null
                    || plainSelect.getIntoTempTable() != null) {
                throw new IllegalArgumentException("Locking or SELECT INTO queries are not allowed");
            }
            enforceMaxRows(plainSelect);
            return plainSelect;
        } catch (JSQLParserException exception) {
            throw new IllegalArgumentException("Invalid SQL", exception);
        }
    }

    private static void enforceMaxRows(PlainSelect select) {
        Limit limit = select.getLimit();
        if (limit == null) {
            select.setLimit(new Limit().withRowCount(new LongValue(MAX_ROWS)));
            return;
        }
        if (!(limit.getRowCount() instanceof LongValue rowCount)
                || rowCount.getValue() > MAX_ROWS) {
            limit.setRowCount(new LongValue(MAX_ROWS));
        }
    }

    private static Expression parseOrganizationPredicate() {
        try {
            return CCJSqlParserUtil.parseCondExpression(
                    "\"orgcode\" IN (:" + ALLOWED_ORGS_PARAMETER + ")");
        } catch (JSQLParserException exception) {
            throw new IllegalStateException("Cannot build organization permission predicate", exception);
        }
    }

    private static final class ReadOnlyDataSource extends DelegatingDataSource {

        private ReadOnlyDataSource(DataSource targetDataSource) {
            super(targetDataSource);
        }

        @Override
        public Connection getConnection() throws SQLException {
            return markReadOnly(super.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return markReadOnly(super.getConnection(username, password));
        }

        private static Connection markReadOnly(Connection connection) throws SQLException {
            connection.setReadOnly(true);
            return connection;
        }
    }
}
