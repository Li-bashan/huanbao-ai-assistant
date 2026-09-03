# Dify 智能问数工作流重构交付报告

> 历史版本记录：本文记录 2026-08-31 的上一版 13 节点重构。它不是 2026-09-02 的当前代码或生产证据；当前 Dify 控制面资产、发布版本和 Gateway Key 绑定以 PROJECT_AUDIT_REPORT.md 及服务器复核为准。

执行日期：2026-08-31
复核日期：2026-09-02
目标应用：智慧办公—智能问数
目标 App ID：200bb456-20bf-48ab-af38-c7b9e59ff070

## 交付结论

已完成一次性重构并发布。原来的 28 节点、30 条边链路已替换为 13 节点、18 条边的确定性查询链路。事实由代码和数据库决定，模型只负责当前问题的意图抽取和结果说明；最终输出不再信任模型改写后的数据，而是直接由结果审计 JSON 确定性渲染。

发布后的应用名称为“智慧办公—智能问数”，Web App 入口为：

- [Dify 工作流控制台](http://121.237.178.23:9002/app/200bb456-20bf-48ab-af38-c7b9e59ff070/workflow)
- [公开 Web App](http://121.237.178.23:9002/chat/GnIPKrtsz3jygdJU)

最终发布动作已返回 HTTP 200、result success；当前草稿 ID：37b9a2aa-b5d5-4f92-9b4f-a2d2e8ebb170；当前已发布工作流 ID：1e86dcbd-b886-4eb5-8199-7166aeeb6140，13 节点、18 条边。

本轮复核先排除了 Dify 登录和密码本身的问题：截图中的 PostgreSQL 客户端已用同一目标连接成功。进一步测试发现，旧的 Kingbase SQLAlchemy 引擎缓存键没有包含密码，错误配置曾缓存了 `kingbase + MSOKFPT` 引擎；改用新的有效 schema 缓存键后，三个数据库工具节点均成功。当前三个工具仍使用 `kingbase` 类型、环境变量密码引用，并将连接 schema 设为 `public`；业务 SQL 继续显式访问 `MSOKFPT`，以避免插件复用旧密码引擎。

草稿全流程验证成功，公开 Web App 新请求也已验证成功：查询 2026 年 8 月组织 `10004024` 的“全厂发电量”，返回 `1319.006`，原始行数 29，数据更新至 `2026-08-29`。工作流状态为 `succeeded`，没有认证错误或未校验数字。

## 修改前备份

核心重构前已保存当前草稿和已发布版本，备份不包含真实 Secret：

- [草稿 JSON 备份](D:/Desktop/company-projects/huanbao-ai-assistant/backups/dify-intelligent-data-query-saved-2026-08-31.json)
- [已发布版本 JSON 备份](D:/Desktop/company-projects/huanbao-ai-assistant/backups/dify-intelligent-data-query-published-2026-08-31.json)
- [草稿 YAML 备份](D:/Desktop/company-projects/huanbao-ai-assistant/backups/dify-intelligent-data-query-saved-2026-08-31.yml)
- [已发布版本 YAML 备份](D:/Desktop/company-projects/huanbao-ai-assistant/backups/dify-intelligent-data-query-published-2026-08-31.yml)

重构脚本也已保留。脚本只在已登录浏览器上下文中调用 Dify API，不保存账号、密码或数据库 Secret：

- [可复现重构脚本](D:/Desktop/company-projects/huanbao-ai-assistant/tools/rebuild_dify_workflow.js)

## 新工作流结构

    用户问题
      ↓
    用户问题结构化解析
      ↓
    时间范围与元数据查询准备
      ├─→ 查询数据库指标字典
      ├─→ 查询组织候选
      └─→ 统一查询上下文
              ↓
        生成并校验受约束 SQL
              ↓
        SQL 业务校验是否通过
          ├─ true  → 执行受约束 SQL
          └─ false → 跳过执行
              ↓
        结果合理性校验与状态归一
              ↓
        受约束结果回答
              ↓
        解析回答 JSON
              ↓
        输出最终回答

### 节点职责、输入和输出

| 节点 | 职责 | 关键输入 | 关键输出 |
| --- | --- | --- | --- |
| 用户问题 | 接收当前问题 | sys.query | 当前问题 |
| 用户问题结构化解析 | 只抽取意图，不查库、不生成 SQL | 当前问题 | indicator_input、organization_input、date_expression、granularity、request_type |
| 时间范围与元数据查询准备 | 代码确定时间、分表和元数据 SQL | 当前原文、意图文本 | base_json、指标 SQL、组织 SQL |
| 查询数据库指标字典 | 查询正式指标和编码 | 指标 SQL | 指标候选 |
| 查询组织候选 | 查询正式组织和 Code | 组织 SQL | 组织候选 |
| 统一查询上下文 | 合并事实并分类状态 | base_json、两个数据库结果 | context_json、组织、指标、日期、分表、授权编码、状态 |
| 生成并校验受约束 SQL | 代码生成单条安全 SQL | context_json | sql、business_status、can_execute、审计信息 |
| SQL 业务校验是否通过 | 只允许 can_execute 大于 0 的分支进入数据库 | can_execute | true/false 分支 |
| 执行受约束 SQL | 使用已校验 SQL 查询数据库 | rebuild_sql.sql | 数据库结果或错误 |
| 结果合理性校验与状态归一 | 区分空结果、失败、重复键、非法数值 | 查询上下文、执行结果 | status、result_text、audit_json、行数、实时截止日期 |
| 受约束结果回答 | 保留结果说明节点，输入只有结果审计内容 | 用户问题、结果审计 | 模型回答草稿 |
| 解析回答 JSON | 以审计 JSON 为事实源，确定性生成表格和图表字段 | 结果审计、回答草稿 | results、ECharts、图表数据 |
| 输出最终回答 | 输出最终文本 | 解析后的文本 | 用户可见回答 |

旧草稿中的 SQL 猜测链、SQL 数组解析、并行迭代执行、失败 SQL 自动修复、旧结果合并及旧回答解析节点已从活动图中移除。原图完整保留在上述备份中。

## 核心整改

### 指标解析和别名

指标事实改为实时查询 MSOKFPT.CGXTAPPMISNewIndicator，不再以知识库 TopK 作为事实源。

已建立可维护的别名注册表，当前至少覆盖：

    全厂发电量 ← 全场发电量、全厂发了多少电、总发电量
    全厂上网电量 ← 全场上网电量、上网电量
    全厂下网电量 ← 全场下网电量、下网电量
    综合厂用电量 ← 厂用电量
    综合厂用电率 ← 厂用电率

实际回归确认：全场发电量 → 全厂发电量 → 1001。

候选指标不会自动进入 authorized_indicator_codes。只有唯一解析成功并且聚合口径已确认的指标才可执行。

### 计算指标和聚合口径

当前没有从系统资料确认到可安全执行的通用公式注册表。计算型请求、同比、环比、率、效率、占比、平均等表达，在没有明确公式时返回 FORMULA_NOT_FOUND 或 AGGREGATION_NOT_CONFIRMED，不猜公式。

当前只把 1001 全厂发电量纳入聚合注册表，来源是实时样本核验：

- 每日一行；
- ZBZ 可转数值；
- 目标组织和 1001 的同日维度数为 1；
- 2026 年 3 月至 8 月组织 10004024 共 182 行，重复键组为 0；
- 因此该指标的期间汇总使用 SUM。

其他指标暂不默认 SUM。实际运行“上网电量”时，数据库解析到 1002 全厂上网电量，但因没有确认的聚合注册项，系统返回 AGGREGATION_NOT_CONFIRMED 并跳过执行。

### 时间和分表

时间由代码从原始用户问题按白名单规则解析，模型的 date_expression 不再决定事实。边界统一为 [start, end)：

    ZBRQ >= query_start_date
    ZBRQ < query_end_date

已支持今天、昨天、本月、上月、今年、去年、近 N 个月、最近 N 天、明确年月、年月范围、今年 N 月至今。非法月份返回 TIME_PARSE_FAILED。

分表由代码按真实半年分表规则计算，只取有时间交集的表：

    CGXTAPPMISDate_YYYY_06：当年 1～6 月
    CGXTAPPMISDate_YYYY_12：当年 7 月以后

2026-03-01 至 2026-09-01 会选择 2026_06 和 2026_12。2025-09-01 至 2026-09-01 会选择 2025_12、2026_06、2026_12。

没有建立统一 View。当前动态 UNION 更适合现有只读数据库工具和半年表结构，不引入 DDL 权限、跨年度维护和新增年份同步风险；工作流的表名由代码白名单控制。未来如由 DBA 提供稳定只读 View，可在不改变上下文和校验器的前提下替换物理表来源。

### 组织解析

组织查询改为正式名称和 Code 候选查询，执行 SQL 原则上只使用唯一解析后的 orgcode。口语简称无法唯一匹配时返回 ORGANIZATION_AMBIGUOUS，不自行选择。用户可以把返回的 Code 带回完整问题重新查询；已实际用 10004024 验证了闭环。

### SQL 生成和业务校验

当前没有让 SQL LLM 决定业务事实，SQL 由代码依据统一上下文生成。执行前检查：

- context.status 必须为 READY；
- 指标编码必须来自实时字典并在授权集合内；
- 聚合必须来自已确认注册表；
- 组织必须唯一解析，且使用 Code；
- 表名必须属于代码计算出的 allowed_tables；
- 开始和结束日期必须固定绑定；
- 只允许单条 WITH 或 SELECT；
- 禁止 DDL、DML、多语句、动态时间函数和非白名单表；
- 保留 LIMIT 1000；
- ZBZ 使用安全数值转换，非法值不参与可信结果；
- 查询同时计算 MAX(ZBRQ)、原始行数、非法数值行数和重复键组数。

旧的失败 SQL 自动修复节点已删除。当前执行失败直接分类为 SQL_EXECUTION_FAILED，不自动让模型重写业务 SQL，避免修复过程改变指标、组织、时间、表、公式或聚合事实。后续若需要增加语法修复，必须复用同一 query_context 并重新通过完整业务校验。

### 结果状态和渲染

已覆盖 SUCCESS_WITH_DATA、SUCCESS_EMPTY、INDICATOR_NOT_FOUND、INDICATOR_AMBIGUOUS、FORMULA_NOT_FOUND、AGGREGATION_NOT_CONFIRMED、ORGANIZATION_NOT_FOUND、ORGANIZATION_AMBIGUOUS、TIME_PARSE_FAILED、FUTURE_TIME、NO_ALLOWED_TABLE、NO_DATA_IN_PERIOD、SQL_VALIDATION_FAILED、SQL_EXECUTION_FAILED、RESULT_VALIDATION_FAILED 和 SQL_METADATA_LOOKUP_FAILED。

结果校验会检查是否有数据、周期是否落在请求区间、是否缺少数值字段、是否有非法数值行、是否有重复键组以及是否有实时数据截止日期。

最终解析节点不使用回答模型提供的候选名、数值或状态。非成功状态直接输出结果审计中的确定性消息；成功状态直接从审计行生成文本表格和图表数据。因此模型不能把真实候选改写成“公司 A/B”，也不能用示例数字替换数据库结果。

## 数据调查结果

### 指标

实时字典确认：

    newcode = 1001
    newIndicatorname = 全厂发电量
    newObjectcode = 1001
    newObjectname = 电量
    IndicatorUnit = NULL

因此当前结果不会把 1007、1009、1010、1011 自动相加来代表全厂发电量。

### 秦皇岛候选组织

对“秦皇岛公司”做实时候选查询得到四个组织：

    10004024  中节能（秦皇岛）环保能源有限公司
    10004011  中节能（秦皇岛）环保能源有限公司10004011
    10004793  中节能秦皇岛泰盛水务有限公司本部
    10004791  中节能泰盛秦皇岛水务有限公司

所以原始口语问题必须返回歧义，不能直接计算一个被猜出来的数字。

把 Code 10004024 明确带入同一问题后，系统得到：

    指标：1001 全厂发电量
    时间：2026-03-01 至 2026-09-01
    表：CGXTAPPMISDate_2026_06、CGXTAPPMISDate_2026_12
    组织：10004024
    状态：SUCCESS_WITH_DATA
    实时截止：2026-08-29
    总量：8233.82

按月明细为：2026-03 1140.576，2026-04 1479.362，2026-05 1598.842，2026-06 1448.833，2026-07 1247.201，2026-08 1319.006。

旧资料中的 8233.95 与当前数据库实时查询得到的 8233.82 不一致，且旧资料中的“秦皇岛公司”本身不唯一。该数字差异和组织选择都标记为需要业务确认，没有写入任何自动规则。

### 重复键和 ZBZ

调查到的 1001 目标样本没有重复键，且 ZBZ 均可安全转数值。另对 1104 做重复数据分析时发现，同一 orgcode、newIndicator、ZBRQ 下存在两个 ZBBM：1611000004 和 6011000004。

这说明三字段不是完整业务唯一键，至少存在报表或业务维度差异，不能用 DISTINCT 掩盖。当前策略是统计重复键组并将异常结果置为 RESULT_VALIDATION_FAILED，不擅自选一条、不把多条压扁。不同指标的 ZBBM 语义仍需业务确认，因此没有把 1104 等指标放进默认聚合注册表。

## 回归测试

以下均通过 Dify 草稿运行和/或公开 Web App 实际运行验证，运行日期的系统当前日期为 2026-08-31。

| 场景 | 关键事实 | SQL/结果状态 | 最终表现 |
| --- | --- | --- | --- |
| 近 6 个月秦皇岛公司全场发电量 | 2026-03-01 至 2026-09-01；两张 2026 分表；1001；4 个组织候选 | can_execute=0；ORGANIZATION_AMBIGUOUS | 列出 4 个真实组织和 Code，不返回数据 |
| 别名全场发电量加天津正式组织 | 全厂发电量 → 1001；组织 10007969 | SQL_VALIDATED；SUCCESS_WITH_DATA | 2026-08 为 1849.092，截止 2026-08-27 |
| 近 3 个月天津 | 2026-06-01 至 2026-09-01；跨 06、12 分表 | SQL_VALIDATED；3 行 | 2455.992、2189.88、1849.092，折线图字段正确 |
| 跨年度 Code 10004024 | 2025-09-01 至 2026-09-01；3 张表 | SQL_VALIDATED；SUCCESS_WITH_DATA | 总量 15661.252，截止 2026-08-29 |
| 精确 Code 10004024 | 2026-03 至 8 月；两张表 | SQL_VALIDATED；SUCCESS_WITH_DATA | 总量 8233.82，6 个月明细和截止日期可对应 |
| 未来时间 2026 年 9 月 | 开始日 2026-09-01，晚于当前日 | can_execute=0；FUTURE_TIME | 明确提示未来时间，不查库 |
| 非法月份 2026 年 13 月 | 日期白名单拒绝 | can_execute=0；TIME_PARSE_FAILED | 明确要求补充正确日期 |
| 不存在指标 | 指标字典无候选 | can_execute=0；INDICATOR_NOT_FOUND | 不返回假数据 |
| 模糊指标“发电量” | 返回 1001、1006～1011 多个候选 | can_execute=0；INDICATOR_AMBIGUOUS | 列出真实指标候选和 Code |
| 计算型请求 | 没有已确认公式 | can_execute=0；FORMULA_NOT_FOUND | 明确未执行计算 |
| 上网电量 | 解析为 1002，但聚合未确认 | can_execute=0；AGGREGATION_NOT_CONFIRMED | 明确未执行汇总 |
| 不存在组织 | 组织字典无候选 | can_execute=0；ORGANIZATION_NOT_FOUND | 明确要求更准确组织名 |
| Web App 歧义入口 | 公开 URL 使用 GnIPKrtsz3jygdJU | 工作流运行成功 | 页面显示真实秦皇岛候选 |
| Web App 成功入口 | 同一公开 URL | 工作流运行成功 | 页面显示天津 1849.092 和结果表 |

## Web App 错绑处理

已核对并修复应用站点品牌绑定：

    应用名称：智慧办公—智能问数
    站点标题：智慧办公—智能问数
    站点 Code：GnIPKrtsz3jygdJU
    模式：advanced-chat

公开入口不再显示旧的 01-数据库查询助手。公开入口已实际提交成功查询和歧义查询，均进入当前发布工作流。

## 本地项目验证

已执行 node --check tools/rebuild_dify_workflow.js 和 npm run build。结果：构建通过，Vite 完成 2460 个模块转换，无构建错误。

本次没有覆盖或回滚工作区中原有的前端改动；它们仍保持原状。与本次 Dify 重构直接相关的新增文件是备份、重构脚本和本报告。

## 需要业务确认的事项

1. “秦皇岛公司”到底对应哪个组织 Code。当前实时组织表给出四个候选，不能由模型或历史数字自动选择。
2. 旧资料的 8233.95 与当前实时查询 8233.82 的差异来源，是数据更新、口径变化还是旧查询条件差异。
3. 1001 之外各指标的 ZBZ 业务语义和聚合方式。当前只有 1001 经过样本证据确认使用 SUM。
4. ZBBM 两个维度在 1104 等指标中的业务含义，以及每个指标应保留哪些业务维度。
5. 计算指标的正式公式、依赖指标、单位和适用组织范围。没有注册公式前，系统会安全阻断计算型请求。

以上事项没有被猜测成系统规则。当前发布版本宁可返回需要确认，也不会输出语法正确但业务错误的 SQL 或数字。
