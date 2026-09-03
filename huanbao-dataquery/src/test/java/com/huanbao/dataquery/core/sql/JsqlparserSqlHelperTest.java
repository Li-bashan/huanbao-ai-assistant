package com.huanbao.dataquery.core.sql;

import com.huanbao.dataquery.core.security.JsqlparserSqlHelper;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JsqlparserSqlHelperTest {

    @Test
    void injectsPermissionPredicateIntoRootWhereWithGrouping() throws Exception {
        PlainSelect select = parsePlainSelect("SELECT id FROM orders WHERE status = 'OPEN' OR amount > 100");
        Expression dataScope = CCJSqlParserUtil.parseCondExpression("organization_code = 'OPS'");

        JsqlparserSqlHelper.injectRootWhere(select, dataScope);

        assertTrue(select.toString().contains("WHERE (status = 'OPEN' OR amount > 100) AND (organization_code = 'OPS')"));
    }

    @Test
    void createsRootWhereWhenBusinessQueryHasNoWhere() throws Exception {
        PlainSelect select = parsePlainSelect("SELECT id FROM orders");
        Expression dataScope = CCJSqlParserUtil.parseCondExpression("tenant_id = 'T1'");

        JsqlparserSqlHelper.injectRootWhere(select, dataScope);

        assertTrue(select.toString().contains("WHERE tenant_id = 'T1'"));
    }

    private static PlainSelect parsePlainSelect(String sql) throws Exception {
        return ((Select) CCJSqlParserUtil.parse(sql)).getPlainSelect();
    }
}
