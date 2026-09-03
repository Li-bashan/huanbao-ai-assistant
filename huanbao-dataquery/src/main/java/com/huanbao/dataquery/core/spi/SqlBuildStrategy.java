package com.huanbao.dataquery.core.spi;

/**
 * 领域 SQL 构建策略。领域插件只能根据受控查询规格构建 SQL，不能把前端原始 SQL 直接透传到数据库。
 */
@FunctionalInterface
public interface SqlBuildStrategy {

    String buildSql(DataQueryRequest request);
}
