# huanbao-dataquery

独立智能问数领域微服务，使用 Spring Boot 3.3.6 / Java 17，默认监听 `8089`。

## 架构边界

- 本工程物理独立于 `ai-gateway`，不在网关进程内执行公式求值、SQL AST 改写或 KingbaseES 查询。
- `com.huanbao.dataquery.core.spi.DomainSemanticProvider` 是跨部门领域插件契约。
- `JsqlparserSqlHelper` 只通过 AST 合并根 `WHERE` 权限条件。
- `AviatorEvaluatorInstance` 只开放纯数学表达式和 `safeDivide`，输出 `BigDecimal`。
- 当前工程只搭建核心边界与基础设施，真实领域 provider 和查询编排在后续按部门插件接入。

## 启动

先设置后端环境变量，不要把数据库密码提交到仓库：

```powershell
$env:DATAQUERY_DB_URL = 'jdbc:kingbase8://127.0.0.1:54321/huanbao'
$env:DATAQUERY_DB_USERNAME = 'ai_query_user'
$env:DATAQUERY_DB_PASSWORD = 'replace-me'
mvn spring-boot:run
```

常用配置：`DATAQUERY_SERVER_PORT`、`DATAQUERY_DB_MAX_POOL_SIZE`、`DATAQUERY_DB_CONNECTION_TIMEOUT_MS`。

## 测试

```powershell
mvn clean test
```
