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
