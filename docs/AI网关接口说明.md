# AI 网关接口说明

更新时间：2026-09-02

AI Gateway 是前端与 Dify、权限、会话和审计之间的服务端中间层。当前工作区前端已具备统一 Master、制度、办公和智能问数四条网关调用链；流程助手仍只走前端动作桥接。工作区存在未提交改动，线上是否已部署 Master 路由需要单独确认。

## 1. 路由总表

| 路由 | 方法 | 用途 | 当前状态 |
|---|---|---|---|
| /api/ai/health | GET | 健康检查 | 线上只读返回 200 / UP |
| /api/ai/master/chat | POST SSE | 制度和办公统一入口 | 当前工作区新增，未证明已部署 |
| /api/ai/policy/chat | POST JSON | 制度 blocking 兼容代理 | 代码存在 |
| /api/ai/office/chat | POST SSE | 办公 streaming 兼容代理 | 代码存在 |
| /api/ai/data-query/access | POST JSON | 问数开放范围 | 线上空身份返回 401 |
| /api/ai/data-query/chat | POST SSE | 问数代理、v2 协议、会话和审计 | 当前前端已调用 |
| /api/ai/data-query/users | GET/POST/PUT/DELETE | 管理授权用户 | 未带管理令牌返回 401 |
| /api/ai/action-audits | GET/POST | 流程动作审计 | 当前查询结果 total=0；代码需补鉴权 |
| /api/ai/workflow/actions/validate | POST | 验证动作卡片参数 | 预留接口，不做真实业务权限 |

## 2. 智能问数请求与身份

请求使用 POST /api/ai/data-query/chat 和 text/event-stream。生产默认 SIGNED_HEADER 模式，Gateway 校验 X-Portal-Identity、时间戳和 HMAC 签名，再按租户、用户和服务端授权表决定开放范围。浏览器提交的 userContext、姓名或组织不能覆盖签名身份；BODY_TRIAL 只有显式配置才允许。

首轮可以不传 conversationId，Gateway 生成用户专属 opaque 会话句柄。Dify 内部 conversation id 只保存在服务端，不返回浏览器。后续请求校验租户、用户、模式和句柄归属。

稳定 SSE 事件是：analysis_started、text_delta、analysis_result、clarification、completed、error。前端对 protocolVersion=2.0 结果渲染指标、表格、图表、证据和 follow-up；协议不合格时降级为可信文本或错误提示。

## 3. 统一 Master

POST /api/ai/master/chat 由 MasterChatController 接收请求，DifyMasterChatService 代理 Dify Agent/Workflow SSE，并过滤内部分析事件。前端会消费 analysis_started、text_delta、analysis_result、completed 和 error。

Master、Policy、Office、Data Query 的 Dify Base URL 和 Key 只配置在 Gateway 服务端。仓库不记录真实 Key，也不把 Dify API 地址作为浏览器直接调用入口。

## 4. 统一返回和错误

JSON 接口使用：

    {
      "success": true,
      "code": "0",
      "message": "ok",
      "data": {}
    }

常见错误包括 UNAUTHORIZED、CURRENT_USER_MISSING、VALIDATION_ERROR、DIFY_TIMEOUT_OR_NETWORK_ERROR 和 PROTOCOL_VALIDATION_FAILED。线上探测中空 query 返回 HTTP 400，未授权用户管理返回 HTTP 401。

## 5. 流程动作和审计

流程助手动作仍由前端通过 IGIX_AI_ACTION 向门户父页面发送，当前已验证采购请示单：

- action：open_form
- formCode：CGQSD
- funcId：9744034a-7fcc-4510-97fa-f563aecd26e6
- 不发送 fields，不自动提交

POST /api/ai/action-audits 已有写入接口和 docs/sql/ai_action_audit.sql DDL，但当前 sendIgixAction 没有自动调用它，动作发送成功不等于审计落库或业务执行成功。该接口当前代码层还需要管理员/签名鉴权。

## 6. 当前不能从接口文档推出的结论

- 不能仅凭 Gateway 在线返回 UP 推出 Dify Key 绑定了哪个 App。
- 不能仅凭 Dify 页面可达推出 Kingbase 查询成功。
- 不能仅凭 Gateway 下发 auth_context 推出 Dify SQL 已执行组织过滤。
- 不能把工作区未提交的 Master 路由写成生产已部署。
