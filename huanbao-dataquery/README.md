# huanbao-dataquery

独立智能问数领域微服务，使用 Spring Boot 3.3.6 / Java 17，默认监听 `8089`。

## 架构边界

- 本工程物理独立于 `ai-gateway`，不在网关进程内执行公式求值、SQL AST 改写或 KingbaseES 查询。
- `com.huanbao.dataquery.core.spi.DomainSemanticProvider` 是跨部门领域插件契约。
- `JsqlparserSqlHelper` 只通过 AST 合并根 `WHERE` 权限条件。
- `AviatorEvaluatorInstance` 只开放纯数学表达式和 `safeDivide`，输出 `BigDecimal`。
- 当前已接入垃圾焚烧发电经营指标领域 provider 和查询编排，生产默认监听 8089；新增领域仍应通过 `DomainSemanticProvider` 插件契约接入，不得把业务 SQL 散落到控制器。

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

`CoreScenarioRegressionTest` 只有在提供真实 KingbaseES 只读密码时才执行实库矩阵；缺少 `DATAQUERY_DB_PASSWORD` 时会明确标记为 BLOCKED，不会用 H2 或硬编码数字冒充生产回归。指标语义字典和组织映射源文件位于仓库根目录的 `docs/`，具体路径和数据口径见[数据源与指标基线](../docs/数据源与指标基线.md)。
