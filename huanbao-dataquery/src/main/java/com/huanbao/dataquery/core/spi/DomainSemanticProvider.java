package com.huanbao.dataquery.core.spi;

import net.sf.jsqlparser.expression.Expression;

import java.util.List;

/**
 * 跨部门领域语义插件契约。
 *
 * <p>各部门只需要实现本接口并以 Java SPI 或 Spring Bean 方式注册，
 * 不需要修改问数核心、网关或其他部门的代码。</p>
 */
public interface DomainSemanticProvider {

    /**
     * 返回稳定的领域编码，例如 OPS、PROCUREMENT。
     */
    String getDomainCode();

    /**
     * 返回用于领域路由的关键词。
     */
    List<String> getDomainKeywords();

    /**
     * 按指标名称或别名解析指标定义。
     */
    MetricDefinition getMetric(String nameOrAlias);

    /**
     * 将用户输入的实体名称解析为受控实体映射。
     */
    EntityMapping resolveEntity(String rawName);

    /**
     * 返回本领域的 SQL 构建策略。
     */
    SqlBuildStrategy getSqlStrategy();

    /**
     * 在已解析的根 WHERE 表达式上追加当前用户的数据权限条件。
     */
    void applyDataScope(Expression whereClause, SecurityUserContext userContext);
}
