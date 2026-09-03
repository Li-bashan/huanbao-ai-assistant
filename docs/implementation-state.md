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
