# AI 网关接口说明

更新时间：2026-07-22

当前状态：`ai-gateway/` 中的接口代码已实现，但助手前端尚未切换到 AI Gateway，当前制度问答和办公智能仍从浏览器直连 Dify，流程动作也尚未写入网关审计。

AI Gateway 是 `huanbao-ai-assistant` 前端与 Dify、审计日志之间的后端安全中间层。

第一版只做：

- Dify 制度问答代理。
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
