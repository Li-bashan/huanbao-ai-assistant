package com.huanbao.dataquery.core.security;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.statement.select.PlainSelect;

import java.util.Objects;

/**
 * SQL AST 安全辅助类。权限条件必须合并到根 WHERE，不允许通过字符串拼接注入。
 */
public final class JsqlparserSqlHelper {

    private JsqlparserSqlHelper() {
    }

    /**
     * 把数据权限条件注入 PlainSelect 的根 WHERE 节点。
     *
     * <p>已有业务条件和权限条件都加括号，避免 OR 优先级导致权限条件失效。</p>
     */
    public static PlainSelect injectRootWhere(PlainSelect select, Expression dataScopePredicate) {
        Objects.requireNonNull(select, "select");
        if (dataScopePredicate != null) {
            select.setWhere(mergeWhere(select.getWhere(), dataScopePredicate));
        }
        return select;
    }

    public static Expression mergeWhere(Expression existingWhere, Expression dataScopePredicate) {
        if (dataScopePredicate == null) {
            return existingWhere;
        }
        if (existingWhere == null) {
            return dataScopePredicate;
        }
        return new AndExpression(
                new Parenthesis(existingWhere),
                new Parenthesis(dataScopePredicate));
    }
}
