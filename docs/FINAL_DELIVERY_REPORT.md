# TASK-4.3 Final Sign-off 交付验收报告

- 验收时间：2026-09-03（Asia/Shanghai）
- 对账基准：docs/DATABASE_RECONCILIATION_REPORT.md，基准日期 2026-09-02
- 压测脚本：tools/perf_data_query.mjs
- 发布提交：24e9c92，已推送 origin/main；服务器 pull、重建和 Nginx reload 未完成
- 当前签收结论：阻断，不予最终生产签收；本地构建已通过，生产全链路和有效用户问数尚未闭环

## 验收摘要

| 项目 | 当前证据 | 判定 |
|---|---|---|
| 前端生产构建 | npm run build 成功，Vite 8.0.16；dist 已生成，问数异步 chunk 约 715.58 kB | 通过 |
| AI Gateway 生产 Jar | mvn clean package -DskipTests 成功，ai-gateway-0.1.0.jar 已生成 | 通过 |
| huanbao-dataquery 生产 Jar | mvn clean package -DskipTests 成功，huanbao-dataquery-0.1.0.jar 已生成 | 通过 |
| 门户入口 | 192.168.245.138:8090 首页 HTTP 200；但返回的是旧静态资源 | 不通过 |
| Gateway 健康 | 121.237.178.23:9002/api/ai/health HTTP 200，status=UP，version=0.1.0 | 健康通过，发布同步未证实 |
| 独立问数健康 | 192.168.245.138:8089/actuator/health HTTP 200，status=UP | 健康通过 |
| 有效问数 | 直接调用 8089/api/query/execute 返回 HTTP 500；公开 Gateway 未授权问数也返回 HTTP 500 | 不通过 |
| 20 并发有效问数 | 因缺少生产签名密钥、有效授权用户和数据库只读凭据，未执行有效授权压测 | 阻断 |
| HikariCP 占用 | 4 个 hikaricp.connections.* actuator 指标均返回 HTTP 500，未暴露运行时占用 | 未验收 |
| 10 大核心场景 | 当前 CoreScenarioRegressionTest 因缺少 DATAQUERY_DB_PASSWORD 全部 BLOCKED，未执行实库断言 | 阻断 |

说明：源码默认端口为 Gateway 8088、huanbao-dataquery 8089；本次从外部可确认的 Gateway 入口为 121.237.178.23:9002，9002 应视为对外 Nginx 入口，8088 的内网监听未能通过目标服务器登录独立核验。门户 8090 的 /api/ai/health 被 Nginx fallback 成前端 HTML，不是 API 反向代理响应。

## 一、建设背景与微内核架构演进

### 1.1 建设背景

旧方案把业务语义、公式计算、SQL 组织过滤和 Dify 编排混在 Dify 沙箱或工作流中。这个方案在单问题演示时能跑，但在企业生产场景下有四个根本问题：

1. 数据权限容易停留在提示词或上下文，不足以证明已经进入数据库执行边界。
2. 公式、单位、组织层级和数据截止日无法形成可版本化的代码资产。
3. 多公司排名会放大网络调用或 N+1 查询，响应时延不可控。
4. 采购、财务等领域扩展需要改动既有工作流，难以独立验收和回滚。

本次重构的目标，是把 Dify 降级为可替换的智能编排组件，把安全、语义、SQL、计算、协议和展示拆成可测试的服务边界。

### 1.2 当前微内核拓扑

门户前端
  -> AI Gateway：身份签名、授权范围、限流、会话句柄、SSE 代理和审计
  -> huanbao-dataquery：意图分类、领域语义、SQL 构建、KingbaseES 只读查询、计算、证据链和 v2 协议
  -> KingbaseES：只读数据源和 HikariCP 连接池

制度问答和办公智能仍可由 Gateway 代理 Dify Master；流程助手保持前端动作卡片，不直接调用 Dify。

huanbao-dataquery 的核心内核已经抽象出 DomainSemanticProvider、DomainSemanticProviderRegistry、SqlBuildStrategy、CalculationEngine 和 ProtocolAssembler。数据权限在 KingbaseQueryExecutor 执行前通过 SQL AST 注入，连接只读、查询超时和最大行数分别形成物理防线。

### 1.3 重构前基准与当前覆盖口径

DATABASE_RECONCILIATION_REPORT.md 的 46 项公式 Delta Matrix 是本次重构前基准：

| 指标 | 重构前实库基准 | 当前代码资产 | 交付解释 |
|---|---:|---:|---|
| 公式对账基线 | 46 项 | 语义 DSL metricCount=46 | 语义条目齐全，但不等于数据库公式行已回写 |
| 精确命中且有公式正文 | 24 项 | 24/46，约 52.17% | 可作为实库已具备正文的基线 |
| 精确名称缺失 | 21 项 | 通过当前 DSL 的名称、别名和公式定义覆盖 21 项语义差额 | 仍需在实库、SQL 样本和生产结果中验收 |
| 已存在但公式正文为空 | 1 项，生活垃圾入厂量 | 当前 DSL 给出 base_1201/10000 | 不能把 DSL 视为已补齐数据库 indicator_formula |
| 入厂单耗差额 | 5 项 | 当前 DSL 明确给出 5 项入厂单耗公式 | 仍需有效用户实库回归 |
| 组织目标 | TASK-4.1 目标 63 家垃圾焚烧发电项目公司 | current semantic contract baseline=63 | 需通过实库 mapping view 和排名结果再次证明 |

因此，本报告不把当前语义 DSL 的 46 项声明写成 46 项实库公式已闭环。实库闭环的必要条件是：指标精确映射、组织 formal_code 唯一、授权范围生效、SQL 返回真实数据、v2 图表和证据断言全部通过。

## 二、生产部署组件拓扑与运行状态矩阵

### 2.1 三端运行端口

| 端 | 端口或入口 | 作用 | 本次观察 |
|---|---|---|---|
| 前端门户 Nginx | 192.168.245.138:8090 | 托管 Vue/Vite dist 静态文件 | 首页 HTTP 200；资源 hash 与本地新构建不一致 |
| AI Gateway | 源码默认 8088；外部入口 121.237.178.23:9002 | 门户安全反向代理 | 外部健康 HTTP 200/UP；8088 内网监听未独立核验 |
| huanbao-dataquery | 192.168.245.138:8089 | 独立问数执行服务 | actuator health HTTP 200/UP；有效查询返回 500 |
| KingbaseES | 172.18.10.2:54321 | 生产业务实库 | 基准报告曾在 2026-09-02 只读连通；本次未重新注入密码执行 SQL |

### 2.2 构建与运行状态

| 组件 | 构建证据 | 运行/发布证据 | 状态 |
|---|---|---|---|
| 前端 | npm run build：成功；dist/index.html、index-CRzxlC43.js、DataQueryResult-CYJoLzVH.js | 8090 仍返回 index-CSOSpz8D.js；/api 路径 fallback HTML | 本地通过，生产未同步 |
| AI Gateway | target/ai-gateway-0.1.0.jar，mvn BUILD SUCCESS | 9002/api/ai/health：200，UP，0.1.0 | Jar 通过，当前线上版本与本地源码关系未证实 |
| huanbao-dataquery | target/huanbao-dataquery-0.1.0.jar，mvn BUILD SUCCESS | 8089/actuator/health：200，UP | 健康通过，业务查询失败 |
| Nginx API 代理 | 不适用 | 8090/api/ai/health 返回前端 HTML，不是 Gateway JSON | 阻断 |
| HikariCP | application.yml 配置 maximum-pool-size=8、minimum-idle=2 | active、idle、max、pending 指标均不可读，返回 500 | 阻断 |
| DomainSemanticProvider SPI | Java SPI descriptor 已随 Jar 构建，Ops provider 源码存在 | 未通过有效实库请求验证 | 代码就绪，生产未验收 |

安全发现：8090 返回的旧版静态 JS 仍包含历史直连 Dify 的凭据形态字面量，而当前本地代码约束是浏览器只持有 Gateway 地址、Dify Key 仅在服务端。该旧包不得继续作为生产版本；如其中的密钥仍有效，应立即在 Dify 侧撤销并轮换。

## 三、实库 10 大核心业务场景全覆盖验收总结

### 3.1 对照基准

本节同时对照：

- docs/DATABASE_RECONCILIATION_REPORT.md 的 46 项公式 Delta Matrix；
- huanbao-dataquery/artifacts/task-4.1/acceptance-matrix.md 的 10 场景验收目标；
- CoreScenarioRegressionTest 对 63 家项目公司、数据截止日 2026-08-31、指标 1001 和 1201 的硬断言。

TASK-4.1 历史矩阵文件记录过 10 tests、0 Failures、0 Errors、0 Skipped，以及 63 家排名场景 3187 ms。但本次重新执行 mvn clean test -q 时，CoreScenarioRegressionTest 检测不到 DATAQUERY_DB_PASSWORD，按设计直接 BLOCKED，禁止退回 H2、fixture 或虚拟数据。因此历史矩阵只能作为上一轮证据，不能替代本次生产签收证据。

### 3.2 当前 10 场景验收状态

| 场景 | 核心断言 | 历史矩阵记录 | 本次当前证据 | 当前结论 |
|---|---|---|---|---|
| 1 事实查询 | 秦皇岛 2026-08 发电量、同比、环比、单位 | PASS | 未执行实库连接 | BLOCKED |
| 2 多公司排名 | 项目公司发电量、63 家覆盖、Top10、图表 | PASS | 未执行 63 家实库查询 | BLOCKED |
| 3 趋势变化 | 2026-03 至 2026-08 六个月序列 | PASS | 未执行实库连接 | BLOCKED |
| 4 公司画像 | 发电量和生活垃圾入厂量双指标看板 | PASS | 未执行实库连接 | BLOCKED |
| 5 异常发现 | 基于真实序列生成异常关注洞察 | PASS | 未执行实库连接 | BLOCKED |
| 6 原因线索 | 事实、相关线索、待核实三层证据 | PASS | 未执行实库连接 | BLOCKED |
| 7 综合比较 | 秦皇岛和保定双主体、双指标比较 | PASS | 未执行实库连接 | BLOCKED |
| 8 大区分析 | 雄安大区、11 家映射、加权重算 | PASS | 未执行实库连接 | BLOCKED |
| 9 经营简报 | 近三个月垃圾入厂量和公文式简报 | PASS | 未执行实库连接 | BLOCKED |
| 10 连续多轮追问 | 排名、Top5、主体切换、同比、指标切换 | PASS | 未执行实库连接 | BLOCKED |

### 3.3 46 项指标与 63 家项目公司的闭环判定

当前不能签署 46 项指标、63 家项目公司实库 100% 闭环，原因是三个独立证据缺口同时存在：

1. 重构前实库基准仍显示 24 项有精确公式正文、1 项正文为空、21 项精确名称缺失；当前 DSL 虽然覆盖差额语义，但没有本次实库写入和有效查询证据。
2. 63 家覆盖是代码测试的硬验收目标，当前测试因为缺少只读连接密码而未执行，不能用历史 PASS 替代。
3. 8089 对今年项目公司发电量排名的直接生产探测返回 HTTP 500，说明从执行服务到数据库、vLLM 或协议编排至少有一处未形成可交付闭环。

## 四、性能压测与北极星指标达成评估

### 4.1 压测方法

压测脚本 tools/perf_data_query.mjs 使用 Node.js 原生 fetch、20 个并发请求、每个请求独立 conversationId，并按生产 SIGNED_HEADER 规则生成 HMAC 身份头。脚本强制要求至少 20 个不同的已授权测试用户，避免撞上默认每用户并发上限 1 后误报性能。

脚本还会读取 SSE completed、analysis_result、protocolVersion 2.0、图表和摘要，并探测：

- hikaricp.connections.active
- hikaricp.connections.idle
- hikaricp.connections.max
- hikaricp.connections.pending

### 4.2 本次真实可执行的 20 并发结果

由于没有可授权的生产测试用户、签名密钥和 Kingbase 只读密码，本次只能执行安全负向探测，不能当作有效业务压测成绩。目标为 121.237.178.23:9002/api/ai/data-query/chat，问题为 今年项目公司发电量排名，20 个签名均为故意无效的测试签名。

| 指标 | 本次观测 | 验收要求 | 判定 |
|---|---:|---:|---|
| 并发数 | 20 | 20 | 通过方法检查 |
| 平均延迟 | 154.69 ms | 需有效请求基线 | 仅负向路径数据 |
| P95 延迟 | 177.73 ms | 需有效请求基线 | 仅负向路径数据 |
| P99 延迟 | 177.95 ms | 需有效请求基线 | 仅负向路径数据 |
| 吞吐量 | 111.948 QPS | 需有效请求基线 | 仅负向路径数据 |
| 错误率 | 100%，20/20 HTTP 500 | 0% | 不通过 |
| SSE completed | 0/20 | 20/20 | 不通过 |
| 图表决策结论 | 0/20 | 20/20 | 不通过 |
| 10 秒内完整决策 | 0/20 | 100% | 不通过 |
| Hikari 指标 | 4 项均 HTTP 500，不可读 | active/max/pending 可观测 | 不通过 |

关键异常：设计要求无效或未认证问数返回 401，但公开 Gateway 本次对 20 个无效签名请求返回 500。该结果应优先作为生产异常处理问题修复，而不是拿 111.948 QPS 当作成功吞吐。

### 4.3 北极星指标结论

北极星指标定义为：领导提问后 10 秒内，完整交付图表、摘要和可执行决策结论。

本次达成度为 0/20，不能证明有效用户路径达成 10 秒体验。历史 TASK-4.1 单场景矩阵中的 3187 ms 只是单轮上一阶段实库回归记录，不是本次生产 20 并发、有效身份、当前 Jar 的证据。

## 五、后续跨部门领域包通过 DomainSemanticProvider SPI 快速扩展接入指南

采购、财务接入应遵循领域包插件化，不把部门规则重新塞回 Gateway 或 Dify Prompt。

### 5.1 实现契约

新增领域 provider 实现 DomainSemanticProvider，至少完成：

- getDomainCode：例如 PROCUREMENT、FINANCE；
- getDomainKeywords：领域路由关键词；
- getMetric：指标名称和别名到 MetricDefinition；
- resolveEntity：供应商、合同、费用科目、组织等实体到受控映射；
- getSqlStrategy：返回本领域 SqlBuildStrategy；
- applyDataScope：在已解析的根 WHERE 上追加当前用户权限。

采购包应优先覆盖采购申请、合同金额、供应商、到货和付款节点；财务包应优先覆盖费用、预算、应收应付、利润和期间口径。每个包都要明确单位、时间粒度、可加和性、敏感字段和零分母策略。

### 5.2 注册与发布

当前仓库已提供 Java ServiceLoader 注册文件：

META-INF/services/com.huanbao.dataquery.core.spi.DomainSemanticProvider

接入步骤：

1. 新建 domain.procurement 或 domain.finance 包，实现 provider、实体映射和 SQL 策略。
2. 增加该 provider 的 SPI 注册行，确保 domainCode 唯一。
3. 为指标字典、别名、组织范围、字段白名单和 SQL AST 权限注入补齐单元测试。
4. 为单领域、跨组织、无权限、空数据、敏感指标和跨轮会话各写回归测试。
5. 先在测试库用只读账号执行，再通过 10 秒体验和 20 并发压测，最后由 Gateway 配置领域路由。
6. 不允许浏览器直接连接数据库或 Dify，不允许把任意 SQL、表名、字段名和组织编码直接交给模型。

### 5.3 领域包验收门槛

领域包只有同时满足以下条件才能标记为可用：

- provider 能被 Registry 发现且无重复 domainCode；
- 指标和实体只解析到白名单资产；
- 查询在 KingbaseQueryExecutor 的只读、超时、最大行数和组织权限边界内执行；
- 结果带数据截止日、证据链、图表和摘要；
- 审计记录包含脱敏请求、用户、范围、指标、时间和执行状态；
- 单领域有效请求错误率为 0%，P95/P99 和 10 秒决策体验通过签收阈值。

## 六、项目签收验收意见

### 6.1 当前意见

本项目当前意见为：有条件交付，暂不最终签收 TASK-4.3 Final Sign-off。

发布动作记录：本地完整工作区已提交为 24e9c92 并成功推送 origin/main。按部署规程尝试连接 192.168.245.138 执行服务器 pull、npm ci、npm run build 和 systemctl reload nginx 时，SSH 连接被拒绝；尝试连接 121.237.178.23 时被公钥/密码认证拒绝。因此本次没有服务器侧拉取、重建或重载的可验证证据。

已完成的交付物：

- 前端生产构建产物已在本地生成；
- AI Gateway 和 huanbao-dataquery 生产 Jar 已构建成功；
- 生产公开 Gateway 健康端点可达；
- 生产独立问数健康端点可达；
- 20 并发压测脚本已归档，具备真实身份签名、SSE 完成、图表决策、错误率和 Hikari 指标采集能力；
- SPI 领域扩展骨架和 OPS 领域包代码已随工程构建。

未满足的最终签收条件：

1. 生产门户必须部署当前 dist，并确认 Nginx 的 API 代理或正式 Gateway 外部地址；旧静态包不能继续上线。
2. Gateway 必须发布与当前源码一致的安全异常处理，未认证请求应返回 401，而不是 500。
3. 8089 必须完成真实问数成功回归，不得只以 actuator health 的 UP 作为业务可用证明。
4. 提供最小权限的 20 个签名测试身份、DATAQUERY_DB_PASSWORD 和授权表映射，执行有效 20 并发压测。
5. 暴露并采集 Hikari active、idle、max、pending，提供压测前后连接池稳定性证据。
6. 重新执行 10 大核心实库场景，确认 63 家项目公司 formal_code 唯一、46 项指标语义和实库 SQL 结果闭环。
7. 对 8090 旧包中历史直连 Dify 凭据做撤销/轮换和发布包安全复核。

完成以上阻断项后，重新运行本报告脚本和 CoreScenarioRegressionTest，补录有效请求的平均延迟、P95、P99、错误率、连接池占用和 10 秒决策达成度，才具备最终签收依据。

本报告归档路径：docs/FINAL_DELIVERY_REPORT.md
