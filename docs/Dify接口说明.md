# Dify 接口说明

更新时间：2026-08-31

> 智能问数已由环宝前端通过 AI Gateway 代理，浏览器不再读取 `VITE_DATA_QUERY_DIFY_API_KEY`。本文件中关于智能问数前端直连的内容属于历史接口记录；当前 Dify 控制台发布状态仍以部署环境实测为准。

## Dify 在项目中的作用

Dify 提供三类智能能力，产品层共展示四项能力（流程助手由前端规则和 iGIX 动作卡片实现）：

- 制度问答知识库。
- 办公智能 Agent。
- 智能问数独立 Dify 问数应用，仅查询生产指标数据。

流程助手当前不调用 Dify。

## 制度问答接口

- 模式：`policy`
- 方法：blocking
- 历史前端直连环境变量：
  - `VITE_POLICY_DIFY_API_BASE`
  - `VITE_POLICY_DIFY_API_KEY`
- 兼容回退：
  - `VITE_DIFY_API_BASE`
  - `VITE_DIFY_API_KEY`

## 办公智能接口

- 模式：`office-ai`
- 方法：streaming
- 环境变量：
  - `VITE_OFFICE_DIFY_API_BASE`
  - `VITE_OFFICE_DIFY_API_KEY`

## 智能问数接口

- 模式：`data-query`
- 环宝入口：`POST /api/ai/data-query/chat`，`response_mode` 由 Gateway 固定为 `streaming`。
- Dify 上游：仅 Gateway 服务端调用 `POST /chat-messages`。
- 环宝只配置 `VITE_AI_GATEWAY_BASE_URL`；Dify Key 使用 Gateway 的 `DIFY_DATA_QUERY_API_KEY`，不能配置在 `VITE_` 变量中。
- 当前应用：`智慧办公—智能问数`，Dify 地址为 `http://121.237.178.23:9002`，应用 ID 为 `200bb456-20bf-48ab-af38-c7b9e59ff070`，模式为 `advanced-chat`。
- 请求输入：Gateway 注入已校验身份、组织范围、指标范围和分析上下文；前端不再发送 Dify 内部节点字段。
- 当前产品协议：Dify 结果组装为 `Data Query Response Protocol v2`，由环宝原生渲染摘要、KPI、表格、图表、洞察、数据说明和追问入口。
- 旧版纯文本仍由 Gateway 和前端转换成 `LEGACY_RESPONSE`，以摘要文本降级，不阻断整个聊天。

## blocking 请求格式

```json
{
  "inputs": {},
  "query": "问题",
  "response_mode": "blocking",
  "conversation_id": "",
  "user": "huanbao-web-user"
}
```

## streaming 请求格式

```json
{
  "inputs": {},
  "query": "问题",
  "response_mode": "streaming",
  "conversation_id": "",
  "user": "huanbao-web-user"
}
```

## SSE 返回事件

可能事件：

- `message`
- `agent_message`
- `message_end`
- `error`
- `ping`
- `workflow_started`
- `node_started`

当前只处理 answer、结束和错误，其余忽略。

## conversationId

连续对话依赖 `conversation_id`。

- blocking 从响应 `conversation_id` 读取。
- streaming 通常从 `message_end` 读取。
- 切换模式或新对话时清空。
- 恢复历史时恢复对应 conversationId。

## 引用来源

制度问答返回 `metadata.retriever_resources`。前端会：

- 按文档去重。
- 展示文档名。
- 支持展开原文片段。

## `<think>` 过滤

办公智能 Agent 可能返回模型思考过程：

```text
<think>...</think>
```

前端在 `chatApi.js` 中过滤，不展示给用户，也不保存到历史。

## 常见错误

- 401：API Key 错误或未生效。
- `invalid_param Agent Chat App does not support blocking mode`：Agent 应用不能用 blocking，必须 streaming。
- CORS：需要 Dify 服务允许前端访问，或后续改后端代理。

## 安全注意事项

- 不要写真实 API Key。
- `.env.local` 不提交。
- 生产建议走后端代理隐藏 Key。
- 智能问数当前由 Gateway 统一校验身份和开放范围；人员配置与迁移说明见[智能问数一体化升级交付报告](./智能问数一体化升级交付报告.md)。
