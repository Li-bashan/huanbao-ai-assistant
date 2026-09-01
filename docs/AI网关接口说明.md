# AI 网关接口说明

更新时间：2026-08-31

当前状态：智能问数已切换为环宝前端调用 `POST /api/ai/data-query/chat`，Gateway 服务端完成身份校验、开放范围校验、Dify 代理、v2 协议适配、限流和审计。制度问答与办公智能仍按各自现有链路运行；Dify 控制台发布、生产密钥配置和 Kingbase 全链路联调需在部署环境完成。

AI Gateway 是 `huanbao-ai-assistant` 前端与 Dify、审计日志之间的后端安全中间层。

第一版只做：

- Dify 制度问答代理。
- 智能问数开放范围、查询前二次校验和人员管理 CRUD。
- 流程助手操作审计落库。
- 动作参数校验预留。
- 统一异常处理。
- 参数校验。
- 健康检查。

第一版不做：

- 不接真实 iGIX 权限接口。
- 不实现流程发起。
- 不改变前端 `postMessage` 协议。
- 不在前端暴露 Dify API Key。

智能问数人员白名单接口和试点边界见[智能问数人员开放范围说明](./智能问数人员开放范围说明.md)。

## 智能问数 v2 代理

```http
POST /api/ai/data-query/chat
Accept: text/event-stream
Content-Type: application/json
```

请求体中的 `userContext` 或 `untrustedClientContext` 都只是兼容字段；生产权限不信任浏览器字段，而由 Gateway 的 `SIGNED_HEADER` 模式校验 `X-Portal-Identity`、时间戳和 HMAC 签名，再按 `tenantId + userId` 查询服务端开放范围。`BODY_TRIAL` 只有显式配置 `DATA_QUERY_ALLOW_BODY_IDENTITY_TRIAL=true` 才能使用，并且响应身份会标记为未验证。

```json
{
  "query": "查询今年项目公司发电量排名",
  "conversationId": "",
  "requestId": "客户端请求 ID",
  "userContext": {
    "userId": "门户用户 ID",
    "userCode": "用户编码",
    "userName": "用户姓名",
    "orgCode": "组织编码",
    "orgName": "组织名称",
    "tenantId": "租户 ID"
  },
  "clientContext": {
    "assistantMode": "data-query",
    "timezone": "Asia/Shanghai"
  }
}
```

`conversationId` 是环宝持有的当前数据问数会话句柄。首轮可以为空，Gateway 会生成用户专属的 opaque handle；后续按 `tenantId + userId + assistantMode + conversationId` 校验归属，再把服务端保存的 Dify `conversation_id` 转给 Dify。Dify 内部会话 ID 不返回浏览器，避免会话串用和越权。Gateway 不重新计算指标、排名或同比。

稳定 SSE 事件为：`analysis_started`、`text_delta`、`analysis_result`、`clarification`、`completed`、`error`。Dify 内部的 `message`、`agent_message`、`workflow_started`、`node_started` 等事件只在 Gateway 内部适配，不直接成为页面契约。

## 智能问数 v2 响应

`analysis_result.response` 使用 `Data Query Response Protocol v2`：

```json
{
  "protocolVersion": "2.0",
  "requestId": "",
  "conversationId": "",
  "status": "SUCCESS_WITH_DATA",
  "messageType": "analysis",
  "analysisType": "RANKING",
  "content": {
    "title": "",
    "summary": "",
    "metrics": [],
    "table": {"columns": [], "rows": [], "total": 0, "defaultVisibleRows": 10},
    "chart": {"type": "bar", "categories": [], "series": []},
    "insights": [],
    "evidence": [],
    "dataInfo": {},
    "followUps": []
  },
  "clarification": null,
  "meta": {}
}
```

前端只按 `messageType`、`analysisType` 和结构化字段渲染；协议校验失败时降级为可信摘要文本，并记录 `PROTOCOL_VALIDATION_FAILED`。组织或指标澄清通过 `messageType=clarification` 返回候选卡片，点击后仍复用原 `conversationId`。

## 统一返回结构

```json
{
  "success": true,
  "code": "0",
  "message": "ok",
  "data": {}
}
```

失败时：

```json
{
  "success": false,
  "code": "VALIDATION_ERROR",
  "message": "query must not be blank",
  "data": null
}
```

## 健康检查

```http
GET /api/ai/health
```

返回示例：

```json
{
  "success": true,
  "code": "0",
  "message": "ok",
  "data": {
    "status": "UP",
    "time": "2026-06-28T10:00:00+08:00",
    "version": "0.1.0"
  }
}
```

## 流程动作审计写入

```http
POST /api/ai/action-audits
Content-Type: application/json
```

请求示例：

```json
{
  "actionId": "9d0fd475-1f54-4db6-a9e7-dc37e08eeb3f",
  "userId": "c9dc8506-eec2-a73f-ecf5-ede837808cee",
  "userName": "刘昊澎",
  "query": "打开采购请示单",
  "action": "open_form",
  "formCode": "CGQSD",
  "funcId": "9744034a-7fcc-4510-97fa-f563aecd26e6",
  "status": "verified",
  "hasFields": false,
  "sentAt": "2026-06-28T10:00:00+08:00",
  "result": "sent",
  "errorMessage": ""
}
```

校验规则：

- `actionId` 必填，最长 80。
- `action` 必填，最长 40。
- `result` 必填，最长 40。
- `userName` 最长 80。
- `query` 最长 1000。
- 不保存完整 `fields`。
- 服务端自动保存 `clientIp` 和 `userAgent`。

## 流程动作审计查询

```http
GET /api/ai/action-audits?page=1&size=20
```

支持查询参数：

- `userId`
- `action`
- `formCode`
- `result`
- `startTime`
- `endTime`
- `page`
- `size`

时间格式使用 ISO-8601，例如：

```text
2026-06-28T00:00:00+08:00
```

## Dify 制度问答代理

```http
POST /api/ai/policy/chat
Content-Type: application/json
```

请求示例：

```json
{
  "query": "差旅费包括哪些费用？",
  "conversationId": "",
  "userId": "c9dc8506-eec2-a73f-ecf5-ede837808cee",
  "userName": "刘昊澎"
}
```

网关调用：

```http
POST {dify.policy.apiBase}/chat-messages
Authorization: Bearer ${DIFY_POLICY_API_KEY}
```

返回字段：

```json
{
  "answer": "制度回答内容",
  "conversationId": "dify-conversation-id",
  "retrieverResources": []
}
```

要求：

- Dify API Key 只能配置在后端环境变量或 `application.yml`。
- API Key 不能返回给前端。
- Dify 401 转为 `DIFY_UNAUTHORIZED`。
- Dify 超时或网络异常转为 `DIFY_TIMEOUT_OR_NETWORK_ERROR`。
- 基础问答日志当前只打印，不落库。

## 动作校验预留

```http
POST /api/ai/workflow/actions/validate
Content-Type: application/json
```

请求示例：

```json
{
  "action": "open_form",
  "formCode": "CGQSD",
  "funcId": "9744034a-7fcc-4510-97fa-f563aecd26e6",
  "status": "verified"
}
```

返回示例：

```json
{
  "success": true,
  "code": "0",
  "message": "ok",
  "data": {
    "allow": true,
    "reason": "ok"
  }
}
```

第一版规则：

- `status = verified` 才允许通过。
- `action` 只能是 `open_form` 或 `open_menu`。
- `formCode` 和 `funcId` 必填。
- 不做真实权限判断。
