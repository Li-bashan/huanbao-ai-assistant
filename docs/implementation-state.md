# 环宝 AI 智能助手实施状态

## Checkpoint 0：生产基线冻结

- 生产签收基准时间：2026-09-03
- 生产签收状态：APPROVED
- 当前基线分支：`main`
- 当前基线 HEAD Commit Hash：`d76a4a746be61a00f1b6b3d12f2c5216f3673417`
- 远程基线：`origin/main` 当前仍为 `246593f5f0939d9398e2ac574149f288ed6b5455`；本地 Checkpoint 0 提交尚未推送
- Checkpoint 0 标记：`BASELINE_FROZEN`
- 现场保全分支：`rescue/dirty-work-20260903_181228`
- 现场保全提交：`34fd2c85e662a2b30487302447a4973ad0b4d4d8`

## 基线边界

本检查点仅记录生产稳态基线，不包含任何前端增强或业务逻辑变更。后续施工必须保持以下内容只读锁定：

- `huanbao-dataquery/` 微服务内核代码
- `docs/sql/` 下全部 DDL/DML SQL
- `docs/METRIC_SEMANTIC_DICTIONARY.json` 指标语义字典
- `ai-gateway/` 网关鉴权、Java 代码、POM 和配置

不得提交真实 API Key、数据库密码、签名密钥或其他生产敏感凭据。

## Checkpoint 0.5：生产契约对账审计与施工 Gate

- 审计状态：`AUDITED_READ_ONLY`
- 审计基线：`d76a4a746be61a00f1b6b3d12f2c5216f3673417`
- 审计报告：[生产契约对账审计报告](./01-产品与前端/生产契约对账审计报告.md)
- 变更边界：只新增审计报告并更新本文件；未修改前端业务代码、配置、Java 或 SQL。
- 路径说明：任务指定的 `docs/02-网关与安全/AI网关接口说明.md` 不存在，实际对账来源为 `docs/AI网关接口说明.md`。
- 协议结论：`protocolVersion=2.0`；`title/summary/metrics/table/chart/insights/evidence/followUps` 均属于 `content`；`meta` 与 `content` 同级；`dataCutoffDate`、`indicatorName` 属于 `content.dataInfo`；未发现 `periodLabel`。
- 10 类状态：`FACT/TREND/RANKING=SUPPORTED`；`DETAIL/COMPARISON/ANOMALY/OVERVIEW=PARTIAL`；`DRILLDOWN/RANKING_COMPARISON/DISTRIBUTION=NOT_SUPPORTED`。
- 高阶状态：`composite_data_to_doc=PARTIAL`（无公开 Gateway/Master 专用接口）；`workflow_audit_api=PARTIAL`（无 ATTEMPT 预记账和同 actionId 幂等）；`portal_ack=NOT_VERIFIED / HOST_PENDING`；File Hub=`NOT_VERIFIED`；`DIAGNOSIS=NOT_VERIFIED`。
- 安全状态：`ACCESS_AND_SECURITY=PARTIAL`。签名身份和组织 DataScope 有代码链路，但指标范围/集团权限未传入执行服务，Gateway 未执行 Origin 白名单校验。
- 强制 Gate：`FAIL / BLOCKED_FOR_VIEW_CODING`。在 COMPARISON、ANOMALY、OVERVIEW、DRILLDOWN 和 ACCESS_AND_SECURITY 的阻断项关闭前，不开始依赖这些假设的新界面视图编码。
- 前端防线：保留 `protocolValid` 双条件结构化入口、v2 失败文本降级、legacy Markdown 兼容、字段裁剪和不猜测字段策略；另记录后端 `NO_DATA` 与前端 `NO_DATA_IN_PERIOD` 的状态命名偏差，待后续契约统一。

## Checkpoint 1：欢迎界面与智能自适应入口治理

- 实施状态：`COMPLETED`
- 变更边界：仅修改 `src/App.vue`、新增 `src/components/welcome/HuanbaoWelcome.vue`、修改 `src/style.css`，并追加本节；未修改问数分析视图、Gateway、Java、SQL 或生产契约。
- 欢迎台：智能自适应状态下使用中立的环宝 AI 智能助手欢迎语，四项能力以 2×2 平级卡片展示：规章制度查询、生产智能问数、办公公文起草、业务流程指引。
- 入口交互：点击能力卡片填充首条示例问题；点击具体预设问题直接复用现有意图路由或流程动作卡片；输入框在自适应状态保持可用。
- 权限感知：复用现有问数 `/access` 检查；问数未授权时全员仍可见入口，并显示 `🔒 需授权`，点击后提示联系管理员开通；锁定问数模式仍使用原有阻断页。
- 历史面板：保留标题栏原生面板打开机制，补充搜索框无障碍标签和模式 Tag 的 `aria-pressed` 状态，未改为抽屉。
- 响应式安全：补充 400px 级别的标题栏和欢迎卡片布局约束；根容器未使用 `position: fixed`，浏览器实测 `innerWidth=400` 时 `documentScrollWidth=400`、`bodyScrollWidth=400`。
- 验证结果：`npm run build` 通过；本地浏览器验证四项入口、流程卡片、历史搜索与模式筛选；模拟 `DATA_QUERY_NOT_COVERED` 验证问数锁标识和管理员引导。

## Checkpoint 2：问数根渲染调度器与 Generative UI 组件解耦

- 实施状态：`COMPLETED`
- 变更边界：仅重构 `src/components/DataQueryResult.vue`，新增 `src/components/data-query/views/` 与 `src/components/data-query/shared/` 展示组件，并更新本文件；未修改 App、Gateway、Java、SQL 或协议工具层。
- 根入口：`DataQueryResult.vue` 是智能问数唯一根调度器，按未授权阻断、加载骨架、有效 v2 结构化协议、降级文本四级优先级分发；Task 3/4 精细视图暂不提前接入，v2 统一进入 `GenericAnalysisView`。
- 组件落地：`AccessBlockedView`、`LoadingSkeleton`、`ProtocolFallbackView`、`GenericAnalysisView` 已落地；`MetricGrid`、`AnalysisTable`、`InsightList`、`FollowUpActions` 已拆为独立 shared 展示组件。`AnalysisTable` 保留紧凑明细、复制、CSV 导出、展开和全屏能力。
- 协议对齐：业务字段始终位于 `content` 下，根调度器向视图传递 `normalizedPayload.content`、同级 `meta` 和 `content.dataInfo`；指标名称与数据截止日期仅从 `content.dataInfo.indicatorName` / `content.dataInfo.dataCutoffDate` 展示，不读取或推断 `meta.periodLabel`。
- 状态兼容：`NO_DATA`、`NO_DATA_IN_PERIOD`、`SUCCESS_EMPTY` 与 `EMPTY` 统一进入中性空态文案；访问阻断状态单独处理，不与无数据状态混淆。当前 App 仍传 `protocol`，根组件同时保留该兼容入口，规范调用可直接传 `data`。
- 验证结果：`npm run build` 通过，Vite 8.0.16 转换 2489 个模块并成功生成生产产物；仅保留问数异步 chunk 体积警告，无 Vue/import 语法错误。
- 提交：`refactor(data-query): checkpoint-2 root renderer and buildable shell`，最终 hash 以本节提交后的 Git HEAD 为准。

## Checkpoint 3：FACT、TREND、RANKING、COMPARISON 精细视图

- 实施状态：`COMPLETED`
- 变更边界：仅更新 `src/components/DataQueryResult.vue`，新增 `FactView`、`TrendView`、`RankingView`、`ComparisonView`，并追加本节；`DataQueryChart.vue` 无需改动，未修改 App、Gateway、Java、SQL 或协议工具层。
- 根路由：`FACT` / `DETAIL` 映射 `FactView`，`TREND` 映射 `TrendView`，`RANKING` 映射 `RankingView`，`COMPARISON` 映射 `ComparisonView`；未知分析类型继续安全回退 `GenericAnalysisView`，未建立平行根组件。
- FACT：以 `content.metrics[0]` 作为事实主卡，其他 KPI 仍通过动态 `MetricGrid` 展示；明细、洞察、证据、图表和后续追问沿用 shared 展示组件。
- TREND：`content.metrics` 全量动态循环，图表直接使用 `content.chart` 的 categories/series；副标题优先拼接 `content.dataInfo.timeRange.expression` 与 `content.dataInfo.indicatorName`，无表达式时降级为“指标数据要点”，不硬编码“近半年”或“运行特征摘要”；截止日期只读取 `content.dataInfo.dataCutoffDate`。
- RANKING：将后端 chart categories/series 适配为水平柱状图，排名梯队与 `previousRank`、`rank`、`rankChange` 均原样读取后端字段，不在前端推导名次变化。
- COMPARISON：仅展示后端已返回的 `value`、`yearOverYearPercent`、`monthOverMonthPercent` 事实字段，缺失字段直接隐藏；不根据 rows 做加减，也不使用 `AVG()` 派生单耗或比率。
- 协议对齐：所有业务数据继续从 `content.metrics`、`content.table`、`content.chart`、`content.insights`、`content.evidence`、`content.followUps` 读取；指标名称和数据截止日期从根传入的 `content.dataInfo` 读取，未将 content 平铺到顶层。
- 状态兼容：精细视图延续 `NO_DATA`、`NO_DATA_IN_PERIOD`、`SUCCESS_EMPTY` 与 `EMPTY` 的中性空态兼容，未授权、加载和协议降级仍由根调度器优先处理。
- 验证结果：`npm run build` 通过，Vite 8.0.16 转换 2497 个模块并成功生成生产产物；6 个月和 12 个月趋势样例均保留后端折线 categories/series；禁用硬编码与 `AVG()` 检查无命中。仅保留既有大 chunk 体积警告。
- 提交：`feat(data-query): checkpoint-3 core views fact trend ranking comparison and router switch`，最终 hash 以本节提交后的 Git HEAD 为准。

## Checkpoint 4：ANOMALY、DRILLDOWN、OVERVIEW 高阶视图与 Gate 降级

- 实施状态：`COMPLETED_WITH_GATE_FALLBACK`
- 变更边界：仅更新 `src/components/DataQueryResult.vue`、新增 `AnomalyView`、`DrilldownView`、`OverviewView`，并追加本节；未修改 Gateway、Java、SQL、协议工具层或生产数据契约。
- 根路由：`ANOMALY` 映射 `AnomalyView`，`DRILLDOWN` / `DIAGNOSIS` 映射 `DrilldownView`，`OVERVIEW` 映射 `OverviewView`，其他未定义类型继续回退 `GenericAnalysisView`。
- ANOMALY 降级：优先展示 `content.insights` 中 `attention` 类型的关注文本；当前没有独立异常 DTO，因此不渲染异常点、阈值或异常序列，固定提供通用核查提示，并继续展示现有 metrics、chart、table、evidence。
- DRILLDOWN 降级：不提供下钻树、父子维度或因果结论。若存在可选 `content.relatedMetrics`，仅将其作为原样线索文本展示；当前真实契约缺失该结构时，回退到 `content.insights` / `content.evidence` 文本，不凭空生成指标卡，并固定展示同期数据变动线索边界、现场排查方向和免责声明。
- OVERVIEW 降级：`content.sections` 存在时通过防御性递归按层级渲染；当前真实协议没有该数组时，回退展示 `summary` / `documentMarkdown`，以及现有 `metrics`、`chart`、`table`、`insights`、`evidence`，不构造领导视角分段。
- 数据边界：视图业务数据只从 `content` 读取；指标名称和统计期间/截止日期只从 `dataInfo` 读取。可选高阶字段缺失时使用空集合或通用文案，不使用 Mock 数据假装后端支持。
- 验证结果：`npm run build` 通过，Vite 8.0.16 转换 2503 个模块并成功生成生产产物；浏览器走查确认缺少 `sections` 的 OVERVIEW 能展示 summary/documentMarkdown、指标和明细，ANOMALY 能展示 attention 关注文本，DRILLDOWN 能展示 insights/evidence 线索和固定免责声明；页面无运行时错误。仅保留既有大 chunk 体积警告。
- 提交：`feat(data-query): checkpoint-4 anomaly drilldown overview views and gate-constrained fallback`，最终 hash 以本节提交后的 Git HEAD 为准。

## Checkpoint 5：Data Query -> Office AI 跨能力协同契约

- 实施状态：`COMPLETED_WITH_BACKEND_REQUIRED`
- Gate 判定：`composite_data_to_doc=PARTIAL`，其中公开 Gateway/Master 服务端编排接口为 `NOT_SUPPORTED`；当前不能把内部 DataQuery 文档编排入口当作公开可调用能力。
- 施工边界：仅新增《问数与公文跨能力协同接口契约规范》，未修改 `src/`、Gateway、DataQuery、Java、SQL 或任何前端业务代码；严禁浏览器端先取数、拼接 Prompt 再调用 Office AI。
- 契约产物：定义 Truth/Presentation 分层、服务端两阶段编排、单一聚合 SSE 流、`POST /api/ai/composite/data-to-doc` 建议路由、请求体边界、`data_ready -> doc_streaming -> completed` 事件标准、失败语义、幂等审计和快照哈希防篡改约束。
- 后端前置条件：必须先由 Gateway 或 DataQuery 服务端提供正式路由、OpenAPI/JSON Schema、真实 SSE 正负样例、DataScope 继承、数值一致性校验和数据结果保留降级，前端才具备接入条件。
- 前端状态：当前独立问数和办公能力保持不变；跨能力协同前端准备原则已明确，但实现受服务端公开契约阻断，状态标记为 `BACKEND_REQUIRED`。

## Task 6：流程助手安全通信、两阶段握手与父页面 ACK 协议

- 实施状态：`COMPLETED_WITH_HOST_PENDING`
- Gate 结论：严格保留 `workflow_audit_api=PARTIAL` 与 `portal_ack=NOT_VERIFIED / HOST_PENDING`；本次只完成前端可交付骨架，不能宣称全链路端到端已闭环。
- `src/utils/actionBridge.js`：生成 UUID `actionId`，递归清洗 payload，使用解析后的 `portalOrigin` 发送顶层及 payload 内一致的 `actionId`；不使用通配符目标 Origin。
- ACK 安全校验：只接受同时满足 `event.origin === portalOrigin`、`event.source === window.parent`、消息类型和 `actionId` 匹配的回执；支持 `SUCCESS`、`FAILED`、`TIMEOUT`，5 秒无合法 ACK 自动进入 `TIMEOUT`，并清理 listener 与定时器。
- 审计容错：发送前尝试 `ATTEMPT` 预审计，收到 ACK 或超时后尝试最终审计；404、500、网络错误和同 `actionId` 唯一键冲突只记录 `console.warn`，不阻断动作或使页面崩溃。现有 Gateway 尚不支持 ATTEMPT 到最终结果的同 actionId 幂等更新，因此审计仍为 `PARTIAL`。
- `WorkflowActionCard.vue`：执行动作显示 `待发送 -> 已发出 -> 等待门户响应 -> 已确认 / 门户处理失败 / 等待超时`；ACK 前不显示办理成功结论，超时明确提示当前不能确认已办理。
- 父页面规范：新增《iGIX 门户父页面 ACK 对接规范》，定义 iframe 来源校验、`IGIX_AI_ACTION` payload、`IGIX_AI_ACTION_ACK` 回执、父页面权限校验和 `actionId` 幂等消费要求。
- 验证结果：`npm run build` 通过，Vite 8.0.16 转换 2503 个模块；仅保留既有大异步 chunk 体积警告。隔离通信回归验证合法 ACK、错误来源忽略、约 5007ms 无 ACK `TIMEOUT`、listener 清理和审计 500 静默容错均通过。

## Checkpoint 7：File Hub 平台级附件支撑中心延期归档

- 实施状态：`OPTIONAL_SKIPPED`
- 归档结论：Task 7 按项目决策延期至二期专项建设，不阻塞一期主线发布，也不在本轮新增文件上传、引用或导出入口。
- 二期规划文档：[二期 File Hub 平台级附件支撑中心架构设计与待办规划](./04-规划与研究/二期FileHub平台级附件支撑中心架构设计与待办规划.md)
- 业务范围：公文多模态上下文起草、线下私有表格沙箱即席计算（DuckDB/SQLite）、高保真 Word/PPT 导出。
- 前置依赖：MinIO/临时对象存储接入、Apache POI/Tika 解析微服务、AI 网关上传流式鉴权、脱敏与租户级 DataScope 隔离。
- 开发纪律：二期另立专属分支 `feat/file-hub-platform`；严禁侵入一期只读指标库及其既有查询、权限和 SQL 资产。
- 本轮变更边界：仅新增二期规划文档并更新本状态文件；未修改一期前端、Gateway、DataQuery、Java、SQL 或生产配置。

## Checkpoint 8A：发布前代码级终验与性能复测

- 终验时间：2026-09-03（Asia/Shanghai）
- Checkpoint 8A 状态：`RELEASE_CANDIDATE_APPROVED_WITH_GAPS`
- 项目最终验收：`PROJECT_FINAL_ACCEPTANCE = BLOCKED`
- 总纲裁决：构建通过，核心前端代码级走查通过，Gateway 安全单测通过，既有生产 10/10 与 20 并发基线证据通过；但受 Checkpoint 0.5 审计事实约束，`ACCESS_AND_SECURITY=PARTIAL`，且 OVERVIEW/DRILLDOWN 的后端独立结构仍未闭环，因此不构成全量生产业务与安全签收。
- 发布范围：准许进入 Task 8B，但仅限发布前端体验治理成果；不得将本状态解释为后端权限、安全或真实业务全链路缺口已关闭。

### 8A.1 生产打包验证

- 执行：`npm run build`
- 结果：`PASS`，退出码 0；Vite `8.0.16` 转换 2503 个模块并成功生成 `dist/`。
- 产物：`dist/index.html`、静态图片/SVG、入口 JS/CSS，以及 `DataQueryResult` 异步 JS/CSS 均完整输出。
- 备注：仅有问数异步 chunk 大于 500 kB 的既有体积警告；没有 syntax、type 或 broken import 错误。

### 8A.2 既有生产基线 10/10 核心场景代码级回归走查

判定口径：下表是当前代码、自动化测试断言和既有生产基线报告的交叉核验，不把本次因凭据缺失而未执行的真实库套件伪装成实时 E2E 通过。`CoreScenarioRegressionTest` 本次运行被设计性阻断：缺少 `DATAQUERY_DB_PASSWORD`，10 项均未执行，禁止退回 H2 或虚拟数据。

| # | 核心场景 | 当前代码/既有基线核验 | 本次判定 |
|---:|---|---|---|
| 1 | 秦皇岛基准全厂发电量查询 | `FactView`、秦皇岛正式编码解析与 `scenario01` 真实库断言存在；既有生产报告记录通过 | `PASS`（本次真实库复测 BLOCKED） |
| 2 | 项目公司全厂发电量排名 | `RankingView`、排名图/表与 `scenario02` 63 家组织断言存在；既有生产报告记录通过 | `PASS`（本次真实库复测 BLOCKED） |
| 3 | 月度发电量趋势连续性 | `TrendView` 直接消费后端 categories/series；`scenario03` 断言连续月份与折线结构 | `PASS`（本次真实库复测 BLOCKED） |
| 4 | 用户组织 DataScope 越权拦截 | Gateway 将 `allowedOrgCodes` 传入执行边界，查询根 WHERE 注入组织范围；组织维度拦截有代码/测试证据 | `PASS`（指标/集团权限仍受安全缺口约束） |
| 5 | 单月数据异常识别与关注文本 | `ProtocolAssembler` 生成 `attention` 关注线索，`AnomalyView` 只展示关注文本与已有事实，不编造异常 DTO | `PASS`（降级边界） |
| 6 | 指标字典口径解释 | `OpsDomainSemanticProviderTest` 校验 46 项指标加载、编码和安全聚合口径；`scenario06` 保留三层证据 | `PASS` |
| 7 | 跨组织对标对比 | `ComparisonView` 仅渲染后端已返回的 value/同比/环比事实，不派生双轴或虚构计算；`scenario07` 有比较断言 | `PASS`（现有返回事实降级） |
| 8 | 连续追问槽位平滑继承 | `ConversationStateManagerTest` 与 `scenario10` 校验只更新请求槽位、保留指标/组织/期间/比较状态 | `PASS` |
| 9 | ECharts 结构化图表渲染 | `DataQueryChart` 使用 ECharts；趋势为 `line`，排名由 `RankingView` 适配水平 `bar`；场景 2/3 有协议断言 | `PASS` |
| 10 | 空身份/非法请求网关拦截 | `DataQueryChatControllerTest`、`DataQueryIdentityServiceTest` 校验缺签名/非法身份 401；既有线上探测记录无身份问数被拦截 | `PASS`（本次生产请求未复测） |

综合结果：代码级场景矩阵 `10/10 PASS`；当前真实 Kingbase E2E 复测 `10/10 BLOCKED`，不是失败，也不能当作本轮新鲜 E2E 证据。

### 8A.3 MUST_HAVE 核心矩阵

| MUST_HAVE | 终验判定 | 事实边界 |
|---|---|---|
| `FACT` | `PASS` | 单值强化胶囊和动态指标展示存在 |
| `TREND` | `PASS` | 动态 metrics 栅格，无“近半年”死字，副标题按 timeRange/indicatorName 动态拼接 |
| `RANKING` | `PASS` | 水平柱状图与明细均存在 |
| `COMPARISON` | `PASS`（降级） | 就地渲染现有返回事实，不虚构双轴对比 |
| `ANOMALY` | `PASS`（降级） | 基于 `attention` 关注线索呈现，不编造指标卡、阈值或异常点 |
| `OVERVIEW` | `PASS`（降级） | 有 `sections` 循环；缺失时回退 summary/documentMarkdown、指标和明细，不白屏 |
| `DRILLDOWN` | `PASS`（降级） | 呈现客观线索，固定免责声明边界，严禁强加因果 |
| `ACCESS_AND_SECURITY` | `PARTIAL / BLOCKING` | Checkpoint 0.5 已确认指标级 DataScope、集团/全组织权限未穿透执行服务，Gateway 未做 Origin 白名单校验；存在未闭环安全缺口，触发一票否决，不能判为 A |

### 8A.4 20 并发性能复测与基线比对

- 压测工具：`tools/perf_data_query.mjs`；`node --check tools/perf_data_query.mjs` 通过。
- 本次正式复测：执行 `node tools/perf_data_query.mjs --concurrency=20` 时，脚本因缺少 `PERF_IDENTITY_SECRET` 主动退出，未发出未授权或伪造身份请求；本地环境没有可用的签名密钥和授权用户 JSON，因此本次运行状态为 `BLOCKED_BY_TEST_CREDENTIALS`。
- 既有生产基线：`docs/FINAL_DELIVERY_REPORT.md` 记录 20/20 有效请求、错误率 `0%`、P95 `4,384.20 ms`。
- 门槛比对：`4,384.20 ms × 1.10 = 4,822.62 ms`，基线 P95 `4.3842 s <= 4.82 s`，既有性能基线判定 `PASS`；本次没有证据表明性能恶化，但也不把历史值冒充本次复测值。
- 既有连接池证据：报告记录压测后 `active=0`、`idle=14`、`max=20`、`pending=0`；本地未配置 `PERF_DATAQUERY_BASE_URL`，未重复采集生产 Hikari 指标。

### 8A.5 安全与三态裁决

- `ai-gateway`：`mvn -q test` 通过，鉴权、访问范围和 SSE 控制器相关 12 项测试全部通过；这证明代码防线存在，不等于生产签名密钥、Origin 白名单和指标/集团权限执行链已完成现场验收。
- `huanbao-dataquery`：`mvn -q test` 进程通过，常规单测通过；真实库核心场景套件因缺少只读数据库密码而 10 项阻断。
- 未触发状态 C：没有构建失败、性能恶化证据或核心功能崩溃；本次被阻断的外部凭据前置不应被改写为失败。
- 未满足状态 A：Checkpoint 0.5 的 `ACCESS_AND_SECURITY=PARTIAL` 属于一票否决；同时 OVERVIEW/DRILLDOWN 仍依赖前端防御性降级，后端独立结构未闭环。
- 唯一结论：`RELEASE_CANDIDATE_APPROVED_WITH_GAPS`；`PROJECT_FINAL_ACCEPTANCE = BLOCKED`。Task 8B 仅可发布前端体验治理成果，核心业务与后端安全缺口关闭前禁止宣称全量生产验收。

## Task 8B：前端体验治理受控发布与现场冒烟报告

- 执行时间：2026-09-03 22:13（Asia/Shanghai）
- 8A 准入：`RELEASE_CANDIDATE_APPROVED_WITH_GAPS`，满足受控发布条件；本次仅核验并发布前端体验治理候选成果，不宣称后端安全或全链路验收完成。
- 发布候选 commit：`bda833f8b9530accfb95d0dc39340e60395ef65d`。
- 双轨状态：`FRONTEND_RELEASE = APPROVED`；`PROJECT_FINAL_ACCEPTANCE = BLOCKED`。
- 现场执行状态：`PRODUCTION_ROLLOUT = BLOCKED_BY_ENVIRONMENT_ACCESS`。前端候选和 UI 冒烟达到发布批准条件，但本机没有现场 Nginx/SSH 控制面，未把远端旧页面冒充为本次已覆盖版本。

### 8B.1 强制前置与构建产物

- `git status --short --branch`：工作区 clean，输出仅有 `## main...origin/main [ahead 9]`，无文件变更；HEAD 与 8A 发布候选 commit 一致。
- `npm run build`：`PASS`，退出码 0；Vite `8.0.16` 转换 2503 个模块并生成 `dist/`。仅有既有大异步 chunk 体积警告。
- `dist/index.html` 入口引用：`/assets/index-BtasfnsH.js`、`/assets/index-CdoNN_K4.css`，两项文件均存在；对应 SHA-256 分别为 `907516F8995F855E486D5BE11CA35EB74898726F9068A1437CE6EBB2CE45E2FC`、`4CD6B53341E5DEB1436BBB8B2F1798CCBE4FE50AD522E6F7B7D7D1F118EC6DB6`。

### 8B.2 Nginx 动态探测、备份与发布边界

- 本机执行 `nginx -T`：失败，原因是 Windows 本机未安装或未暴露 `nginx` 命令；`127.0.0.1:9002` 与 `127.0.0.1:8090` 均 connection refused，仅 `127.0.0.1:5173` 为本地 Vite 开发服务。
- 现场只读 HTTP 探测：`http://192.168.245.138:8090/` 返回 200，`http://121.237.178.23:9002/` 也返回前端 HTML；但 `192.168.245.138:22` connection refused，无法进入远端执行 `nginx -T`、读取真实 `root/alias` 或复制文件。
- 动态路径：`WEB_ROOT = UNRESOLVED`；未从真实 Nginx `server` block 读取到路径。
- 现场备份：`BACKUP_DIR = NOT_CREATED`。由于没有真实 `WEB_ROOT`，没有执行 `cp -r`、静态资源覆盖、`nginx -t` 或 `nginx -s reload`，避免误拷贝到未知目录。
- Nginx 步骤仅生成以下标准 Linux 发布/回滚脚本，未在本机执行：

```bash
#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="${PROJECT_DIR:-$(pwd)}"
DIST_DIR="${PROJECT_DIR}/dist"
NGINX_DUMP="$(mktemp)"
trap 'rm -f "$NGINX_DUMP"' EXIT

nginx -T >"$NGINX_DUMP" 2>&1
WEB_ROOT="$(awk '
/^[[:space:]]*server[[:space:]]*\{/ { depth=1; hit=0; next }
depth > 0 {
  line=$0
  open_count=gsub(/\{/, "", line)
  close_count=gsub(/\}/, "", line)
  if ($0 ~ /^[[:space:]]*listen[[:space:]]+([^;]*:)?9002([[:space:];]|$)/) hit=1
  if (hit && $0 ~ /^[[:space:]]*(root|alias)[[:space:]]+/) {
    path=$2; sub(/;$/, "", path); print path; exit
  }
  depth += open_count - close_count
}' "$NGINX_DUMP")"

if [[ -z "$WEB_ROOT" || "$WEB_ROOT" != /* || "$WEB_ROOT" == "/" || ! -d "$WEB_ROOT" ]]; then
  echo "Unable to resolve a safe absolute WEB_ROOT from nginx -T" >&2
  exit 1
fi
if [[ ! -f "$DIST_DIR/index.html" ]]; then
  echo "Missing release artifact: $DIST_DIR/index.html" >&2
  exit 1
fi

export WEB_ROOT
BACKUP_DIR="${WEB_ROOT}_backup_$(date +%Y%m%d_%H%M%S)"
export BACKUP_DIR
cp -r "$WEB_ROOT" "$BACKUP_DIR"
cp -r "$DIST_DIR"/. "$WEB_ROOT"/
nginx -t && nginx -s reload

LOCAL_REFS="$(grep -oE '(src|href)="[^"]+\.(js|css)"' "$DIST_DIR/index.html" | sed -E 's/.*="([^"]+)"/\1/' | sort)"
ONLINE_REFS="$(curl -fsS http://127.0.0.1:9002/ | grep -oE '(src|href)="[^"]+\.(js|css)"' | sed -E 's/.*="([^"]+)"/\1/' | sort)"
test "$LOCAL_REFS" = "$ONLINE_REFS"
echo "Released with WEB_ROOT=$WEB_ROOT BACKUP_DIR=$BACKUP_DIR"
printf 'Rollback: cp -r "%s"/. "%s"/ && nginx -s reload\n' "$BACKUP_DIR" "$WEB_ROOT"
```

### 8B.3 防缓存击穿 Hash 对账

- 本地候选 `dist/index.html` 与其引用文件严格一致，引用文件均存在。
- `127.0.0.1:9002/`：connection refused，无法完成用户指定的本机线上 HTML 对账。
- `192.168.245.138:8090/` 返回 `/assets/index-CSOSpz8D.js`、`/assets/index-DP1OF6Md.css`；`121.237.178.23:9002/` 返回 `/assets/index-CRzxlC43.js`、`/assets/index-BUA8lWxQ.css`。两套线上引用均与候选 `index-BtasfnsH.js`、`index-CdoNN_K4.css` 严格不一致；线上现状判定为 `HASH_MISMATCH / CANDIDATE_NOT_DEPLOYED`，不宣称发布已覆盖。

### 8B.4 真实环境与隔离 UI 冒烟矩阵

| 项目 | 判定 | 证据与边界 |
|---|---|---|
| 欢迎台 | `PASS`（本地候选） | 隔离浏览器加载候选前端；四项能力 2×2 平级展示。400px 视口下 `documentScrollWidth=400`、`bodyScrollWidth=400`，网格为 `175.5px 175.5px`，无横向溢出。 |
| 问数未授权卡片 | `PASS`（隔离 UI 模拟） | 用非敏感测试身份和模拟 `DATA_QUERY_NOT_COVERED` 响应验证，卡片展示 `🔒 需授权`，点击后提示联系管理员；不作为真实权限链验收证据。 |
| FACT/TREND/RANKING/COMPARISON Generative UI | `BLOCKED_FOR_REAL_BACKEND` | 真实门户身份不可用，独立本地访问问数返回“暂未获取到当前登录信息”；本次未伪造授权用户或业务数据。沿用 8A 的代码级通过结论，不改写为本次真实数据 E2E。 |
| OVERVIEW/ANOMALY/DRILLDOWN | `BLOCKED_FOR_REAL_BACKEND` | 真实数据协议未进入本次浏览器会话；8A 已验证缺少 `sections`、attention 线索和固定免责声明的代码级降级边界，本次不把它扩展成后端结构已闭环。 |
| 流程助手握手 | `PASS`（前端/网关审计容错） | “打开采购请示单”动作卡片发出 `IGIX_AI_ACTION`，界面先显示“等待门户响应”，无 ACK 约 5 秒后显示“等待超时”和“当前不能确认已办理”；预审计 HTTP 200，最终审计 HTTP 500 被前端以 handled warning 容错，未显示虚假成功。 |
| Console / Network | `PASS`（候选本地会话） | `errors` 无 unhandled error；仅有预期的开发信息和审计 500 handled warning。Network 未发现 Dify URL、Dify Key 或直传 SQL；流程冒烟只访问本地模块和 AI Gateway 审计接口。 |
| 现场 8090 页面 | `BLOCKED / OLD_ARTIFACT` | 浏览器可打开但仍是旧版制度欢迎页，静态 hash 与候选不一致；不能作为 8B 新版冒烟通过证据。 |

### 8B.5 现场安全补充与最终裁决

- `http://121.237.178.23:9002/api/ai/health` 返回 200/UP。
- 无身份调用 `POST /api/ai/data-query/access` 返回宽松 `covered=true`、`identityVerified=false`、`identitySource=PERMISSIVE` 结果，说明真实环境仍存在 8A 已标注的身份/权限闭环缺口；没有继续发起真实问数，避免越过授权边界。
- `FRONTEND_RELEASE = APPROVED` 仅表示前端体验治理候选通过构建和前端冒烟并获准受控发布；实际远端静态资源覆盖因现场控制面不可达保持 `BLOCKED`。
- `PROJECT_FINAL_ACCEPTANCE = BLOCKED`。Checkpoint 0.5/8A 的后端安全、DataScope、门户 ACK 全链路和高阶视图后端契约缺口继续有效。
