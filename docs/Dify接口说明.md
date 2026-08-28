# Dify 接口说明

更新时间：2026-07-22

> 当前实现仍由前端直连 Dify。AI Gateway 代理代码已存在但前端尚未切换；生产环境应完成网关切换后移除浏览器可见的 Dify API Key。

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
- 方法：当前代码使用 `POST /chat-messages`。
- `response_mode`：`streaming`。
- 环境变量：`VITE_DATA_QUERY_DIFY_API_BASE`、`VITE_DATA_QUERY_DIFY_API_KEY`。
- 请求输入：`query` 为用户问题，`inputs.current_user_name` 为 iGIX 当前登录人姓名，供 Dify HTTP 节点透传到 Gateway。
- 返回处理：优先读取 `data.outputs.answer/text/result/response/output`；空结果和 workflow failed 统一提示，不在前端生成业务数据。

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
- 智能问数人员开放范围见[智能问数人员开放范围说明](./智能问数人员开放范围说明.md)，Dify HTTP 节点必须把 `current_user_name` 放进 Gateway 请求体 `userName`。
