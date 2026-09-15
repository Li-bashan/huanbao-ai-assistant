# 问数语义与展示收口 Spec

## Proposal

Intent: 防止单位、年度累计和前端二次求和导致业务数字失真，并让失败状态可定位。

Scope: 本次覆盖独立问数服务的结果语义、年度事实查询、v2 前端渲染和回归验证；不改数据库原始数据，不处理图二参考报表的未知数据源对账。

Approach: 后端计算并输出唯一可信结果，结果携带明确的数值语义；合法 v2 结果由前端原样渲染，旧协议仅保留兼容降级。

## Requirements

### Requirement: 后端必须声明结果数值语义

The data-query service MUST include the requested measure, time semantics, display unit and aggregation rule in every successful structured result.

#### Scenario: 年度事实查询

- GIVEN 用户查询某指标某一整年，且指标是按月发生量
- WHEN 后端完成查询
- THEN 月度明细保持月度发生量，年度核心指标等于月度发生量之和
- AND 结果明确标记 `requestedMeasure=ANNUAL_SUM`、`timeSemantics=PERIOD_INCREMENT`

### Requirement: 前端不得重算合法结构化结果

The frontend MUST render the values, unit, analysis type and chart supplied by a valid v2 response without recomputing business metrics.

#### Scenario: 合法 v2 年度结果

- GIVEN 后端返回 `protocolVersion=2.0` 且协议校验通过
- WHEN 前端渲染结果卡片
- THEN 前端使用后端提供的核心指标和图表
- AND 前端不得把 FACT 改成 TREND、不得再次求和、换单位或推导排名

### Requirement: 旧协议必须与正式协议隔离

The frontend MAY retain legacy parsing only for responses that fail v2 validation, and MUST NOT let legacy business calculations override a valid v2 response.

#### Scenario: 旧格式兼容

- GIVEN 返回内容不是通过校验的 v2 结构化结果
- WHEN 前端处理响应
- THEN 前端可以使用旧格式降级逻辑
- AND 降级结果不得被标记为已校验的正式结构化结果

### Requirement: 结果必须可回归验证

The service MUST have automated coverage for unit conversion, annual aggregation, YTD ranking semantics and protocol metadata.

#### Scenario: 生活垃圾入厂量

- GIVEN 原始值为 `21818500` 吨，指标展示单位为万吨
- WHEN 计算指标
- THEN 结果为 `2181.85` 万吨
- AND 该换算只发生一次

## Design

### Technical Approach

在 `huanbao-dataquery` 内新增不可变的结果语义对象，由流水线根据已经解析并校验的计划决定 `ANNUAL_SUM`、`YTD` 或 `PERIOD_VALUE`，协议层输出到 `dataInfo.valueSemantics`。年度事实查询继续返回逐月发生量，协议层只在后端生成年度核心指标，不把累计序列交给前端二次求和。

`DataQueryResult.vue` 只对未通过 v2 校验的旧响应运行兼容转换。合法 v2 的 FACT 结果由 FactView 展示后端 KPI、表格和图表。

### Architecture Decisions

- Decision: 业务计算统一归属 `huanbao-dataquery`。
  - Reason: 前端、Dify 和后端不能各自解释单位与时间口径。
- Decision: 不为图二参考数字增加硬编码。
  - Reason: `2181.85` 与当前生产数据源的差异必须通过源表、组织范围和快照完成对账。

### File Changes

- `huanbao-dataquery/src/main/java/com/huanbao/dataquery/pipeline/DataValueSemantics.java` (new)
- `huanbao-dataquery/src/main/java/com/huanbao/dataquery/pipeline/CompositeExecutionResult.java` (modified)
- `huanbao-dataquery/src/main/java/com/huanbao/dataquery/pipeline/DataToDocPipelineService.java` (modified)
- `huanbao-dataquery/src/main/java/com/huanbao/dataquery/protocol/DataInfoDto.java` (modified)
- `huanbao-dataquery/src/main/java/com/huanbao/dataquery/protocol/ProtocolAssembler.java` (modified)
- `src/components/DataQueryResult.vue` (modified)
- `src/components/data-query/views/FactView.vue` (modified)
- `src/utils/dataQueryProtocol.js` (modified)
- `huanbao-dataquery/src/test/java/com/huanbao/dataquery/protocol/ProtocolAssemblerTest.java` (modified)
- `huanbao-dataquery/src/test/java/com/huanbao/dataquery/pipeline/DataToDocPipelineServiceTest.java` (modified)

## Tasks

### Wave 1

- [x] 增加后端结果语义对象和协议字段。
- [x] 年度事实 KPI 在后端计算，保留月度明细。

### Wave 2

- [x] 合法 v2 前端只渲染后端结果。
- [x] 增加单元、协议和构建回归。

## 验收标准

- `2025年生活垃圾入厂量是多少` 不出现原始吨值，也不出现累计值重复求和。
- 年度核心指标由后端产生，月度表格和图表与后端同源。
- 合法 v2 结果在前端保持 FACT 语义，不再被兼容逻辑改写。
- 后端测试和 `npm run build` 全部通过。

## Verification

- `mvn -q -f huanbao-dataquery/pom.xml clean test package` passed.
- `npm run build` passed.
- The production database regression matrix remains explicitly blocked when `DATAQUERY_DB_PASSWORD` is absent; this does not change the local H2 and protocol test result.
