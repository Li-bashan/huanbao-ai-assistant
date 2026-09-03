# Dify 接口说明

更新时间：2026-09-02

## 1. 项目中的调用边界

Dify 是服务端智能能力提供方。浏览器不应读取 Dify API Key，也不应把 Dify /v1 作为业务入口。当前工作区前端通过 AI Gateway 访问制度、办公和智能问数；流程助手不调用 Dify。

| 能力 | 前端当前入口 | Gateway 到 Dify | 备注 |
|---|---|---|---|
| 制度问答 | /api/ai/master/chat | blocking/统一 Master；兼容 policy 路由仍在代码 | 代码已提交；生产服务器部署待确认 |
| 办公智能 | /api/ai/master/chat | streaming/统一 Master；兼容 office 路由仍在代码 | 过滤内部分析事件 |
| 智能问数 | /api/ai/data-query/chat | streaming，带身份、授权、会话、审计和 v2 适配 | 当前前端已接 |
| 流程助手 | 前端动作卡片 | 不调用 Dify | 通过门户 postMessage |

历史文档中出现的 VITE_POLICY_DIFY_API_BASE、VITE_OFFICE_DIFY_API_BASE、VITE_DATA_QUERY_DIFY_API_KEY 和浏览器直连示例，不代表当前生产调用方式。Dify Key 应只存在 Gateway 服务器环境变量。

## 2. Gateway 代理合同

Gateway 服务端向 Dify 的典型请求是 POST /v1/chat-messages，使用服务端 Bearer Key。问数请求还会传递经过 Gateway 认证的用户、授权范围、分析状态、澄清选择和服务端会话映射。

浏览器可见的问数 SSE 事件是 analysis_started、text_delta、analysis_result、clarification、completed、error；Dify 内部 message、agent_message、workflow_started 和 node_started 会在 Gateway 内部适配。

问数结构化结果目标为 protocolVersion=2.0，包含 analysisType、metrics、table、chart、insights、evidence、dataInfo 和 followUps。前端只渲染通过协议校验的结构化字段。

## 3. Dify 资产核对

Dify 控制台和历史报告中同时存在智慧办公—环宝统一中枢、智慧办公—智能问数、智慧办公—环宝制度智能助手、智慧办公—环宝办公智能助手以及历史 01-数据库查询助手。详细节点、草稿和发布信息见 Dify全部应用与工作流现状.md 和 PROJECT_AUDIT_REPORT.md。

Gateway 配置不保存 App ID，只保存 Base URL 和 Key。因此控制台能看到某个 App、公开 Web App 能返回 200，都不能证明线上 Key 已绑定该 App。发布版本、Key 绑定、协议版本和 auth_context 消费必须在服务器/控制台完成只读核对。

## 4. 安全边界

- 不在代码、文档、截图或构建产物保存真实 Key。
- 不把 Dify 的 SQL 执行能力当成 Gateway 权限校验。
- 不把 auth_context_json 字段出现当成 SQL 已执行组织过滤。
- Kingbase 表、字段、数据质量和查询权限需要独立实库验收。
- Dify 页面可达不等于业务查询已通过。
