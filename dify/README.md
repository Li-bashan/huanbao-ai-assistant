# Dify 统一意图路由

## 导入

1. 在 Dify 工作室点击“导入 DSL 文件”。
2. 选择 `环宝统一意图路由.dify.yml`。
3. 导入后确认“统一意图识别”节点使用 `qwen3.6-plus`；若当前工作区模型名称不同，请手工选择已可用的通义模型。
4. 分别调试以下输入，确认输出后再发布工作流并创建 API Key：
   - `差旅费包括哪些费用？` -> `route=policy`
   - `帮我写一份系统维护通知` -> `route=office`
   - `打开采购请示单` -> `route=workflow`，`workflow_id=purchase_request`
   - `帮我写一份采购审批流程说明` -> `route=office`

## 输入

- `query`：用户当前消息，必填。
- `recent_context`：最近对话上下文，可选。

## 输出

- `route`：`policy`、`office`、`workflow` 或 `unknown`。
- `workflow_id`：白名单中的标准流程事项 ID。
- `confidence`：模型置信度。
- `reason`：简短路由理由。
- `reply`：流程待联调或无法识别时的前端提示。
- `candidates_json`：JSON 字符串。前端解析后展示流程候选卡片。

模型节点只识别意图和标准事项 ID。`formCode`、`funcId`、`action` 由代码节点白名单提供，避免模型编造业务动作。当前仅采购请示单标记为 `verified` 并返回可执行 `payload`；其余事项保持 `pending` 或 `unsupported`，待逐项联调后再开放。

前端收到流程候选后仍必须先展示确认卡片。只有用户点击确认，才允许通过现有 Action Orchestrator 发送 `IGIX_AI_ACTION`，不得自动提交表单。
