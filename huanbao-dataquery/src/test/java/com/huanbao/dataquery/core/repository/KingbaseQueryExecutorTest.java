package com.huanbao.dataquery.core.repository;

import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KingbaseQueryExecutorTest {

    private final KingbaseQueryExecutor executor = new KingbaseQueryExecutor(
            new NamedParameterJdbcTemplate(new JdbcTemplate()));

    @Test
    void injectsAnOrgInPredicateIntoTheRootWhereAst() throws Exception {
        String securedSql = executor.secureSql(
                "SELECT \"newIndicator\" FROM metric_rows WHERE \"ZBZ\" > :minimum"
                        + " OR \"ZBZ\" IS NULL");
        PlainSelect select = ((Select) CCJSqlParserUtil.parse(securedSql)).getPlainSelect();

        AndExpression rootWhere = assertInstanceOf(AndExpression.class, select.getWhere());
        assertTrue(rootWhere.toString().contains("\"ZBZ\" > :minimum"));
        assertTrue(rootWhere.toString().contains("\"orgcode\" IN (:allowedOrgs)"));
        Parenthesis scopeGroup = assertInstanceOf(Parenthesis.class, rootWhere.getRightExpression());
        assertInstanceOf(InExpression.class, scopeGroup.getExpression());
        assertEquals(KingbaseQueryExecutor.MAX_ROWS, select.getLimit().getRowCount(LongValue.class).getValue());
    }

    @Test
    void createsAWhereClauseWhenTheBusinessQueryHasNone() throws Exception {
        String securedSql = executor.secureSql("SELECT \"orgcode\" FROM metric_rows");
        PlainSelect select = ((Select) CCJSqlParserUtil.parse(securedSql)).getPlainSelect();

        assertEquals("\"orgcode\" IN (:allowedOrgs)", select.getWhere().toString());
        assertEquals(KingbaseQueryExecutor.MAX_ROWS, select.getLimit().getRowCount(LongValue.class).getValue());
    }

    @Test
    void rejectsNonPlainOrLockingStatementsBeforeExecution() {
        assertThrows(IllegalArgumentException.class, () -> executor.secureSql(
                "SELECT \"orgcode\" FROM metric_rows FOR UPDATE"));
        assertThrows(IllegalArgumentException.class, () -> executor.secureSql(
                "DELETE FROM metric_rows"));
        assertThrows(IllegalArgumentException.class, () -> executor.secureSql(
                "SELECT \"orgcode\" FROM metric_rows UNION ALL"
                        + " SELECT \"orgcode\" FROM metric_rows"));
    }

    @Test
    void rejectsAnEmptyOrganizationScopeFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> executor.queryForList(
                "SELECT \"orgcode\" FROM metric_rows", Map.of(), List.of()));
    }
}
