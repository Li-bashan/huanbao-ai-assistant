# AGENTS.md

本文件面向 OpenAI Codex / AI Agent，是环宝 AI 智能助手项目的开发指令。

## 项目定位

本项目是企业门户右侧智能助手前端，项目名 `huanbao-ai-assistant`。它不是全屏后台，不是独立聊天产品，而是可嵌入办公门户右侧区域的 AI 助手面板。

Codex 修改代码前应先阅读：

1. `AGENTS.md`
2. `README.md`
3. `docs/项目交接说明.md`

## 技术栈

- Vue 3 + Vite
- JavaScript
- CSS
- Dify API
- localStorage
- Nginx 静态部署

## 当前能力

- 制度问答：通过 AI Gateway 访问 Dify blocking/统一 Master，带引用来源。
- 办公智能：通过 AI Gateway 访问 Dify Agent streaming/统一 Master，过滤 `<think>`。
- 流程助手：前端动作卡片，当前不接真实业务系统。
- 智能问数：通过 AI Gateway 做身份、授权、会话、SSE 和 v2 协议适配。
- 自动意图识别：`src/utils/intentRouter.js`。
- 本地历史：搜索、筛选、删除、清空。
- AI 输出复用：复制、导出 Markdown、导出 Word/HTML。

## 核心文件

- `src/App.vue`：主界面、模式切换、发送、历史、工具栏。
- `src/services/chatApi.js`：Dify blocking / streaming / Mock。
- `src/config/assistantModes.js`：四种助手模式配置。
- `src/config/workflowActions.js`：流程助手动作卡片配置。
- `src/utils/intentRouter.js`：自动意图识别。
- `src/utils/conversationStorage.js`：localStorage 历史。
- `src/utils/messageExport.js`：复制与导出。
- `src/utils/markdown.js`：Markdown 渲染。
- `src/style.css`：全部样式。

## 四个助手模式

1. `policy` 制度问答：查制度、标准、规定，通过 Gateway 使用 Dify blocking 或统一 Master。
2. `office-ai` 办公智能：会议纪要、通知、总结、润色，通过 Gateway 使用 Dify streaming 或统一 Master。
3. `workflow` 流程助手：采购请示单、合同评审、我的待办，必须使用动作卡片和 Mock，不要直接调用 Dify。
4. `data-query` 智能问数：必须使用 Gateway access/chat，不能信任浏览器身份，也不要前端直连 Dify。

## Dify 规则

- 不要把真实 API Key 写进代码、文档、提交记录。
- Dify API Key 只能使用 Gateway 后端环境变量，不能写入任何 `VITE_` 变量。
- 当前工作区 Master 路由使用 `DIFY_MASTER_API_BASE` / `DIFY_MASTER_API_KEY`；制度、办公和问数分别有 Gateway 后端配置。
- 修改 `.env.local` 后必须重启 `npm run dev`。

## streaming 规则

- 办公智能是 Agent Chat App，不支持 blocking。
- 使用 SSE 解析 `message`、`agent_message`、`message_end`、`error`。
- 页面要实时更新 assistant 消息。
- `<think>...</think>` 不能展示给用户。

## 流程助手规则

- 不要让流程助手直接调用 Dify。
- 不要把流程助手退化成普通文本回复。
- 命中流程事项时必须展示动作卡片。
- 按钮当前只追加演示说明，后续通过 postMessage / iGIX 对接。

## 历史记录规则

- 当前历史使用 localStorage。
- 不保存 loading / streaming 半截消息。
- 历史要保留搜索、制度/办公/流程筛选、删除单条、清空能力。

## 复制导出规则

- 不要删除复制、导出 Markdown、导出 Word/HTML。
- 导出 Word 第一版使用 HTML `.doc`，不引入复杂依赖。

## UI 修改规则

- 保持企业门户右侧面板形态。
- 不使用 `position: fixed` 破坏嵌入。
- 不做抽屉式历史。
- 不大改欢迎区背景。
- 样式保持浅蓝、圆角、轻量。

## 禁止事项

- 禁止提交真实 API Key。
- 禁止破坏制度问答 blocking。
- 禁止破坏办公智能 streaming。
- 禁止删除引用来源展示。
- 禁止删除历史搜索、筛选、删除、清空能力。
- 禁止删除复制与导出功能。
- 禁止绕过现有架构新增复杂依赖。

## 常用命令

```bash
npm run dev
npm run build
git status
```

## 验证清单

- 制度问答：`差旅费包括哪些费用？`
- 办公智能：`帮我写一份关于智慧办公系统临时维护的通知`
- 流程助手：`打开采购请示单`
- 意图识别：`给我整理会议纪要：流程、表单、审批节点……`
- 历史：搜索、筛选、删除、清空。
- 工具栏：复制、导出 Markdown、导出 Word。

## 部署流程

必须先本地 push，再服务器 pull：

```bash
# 本地
npm run build
git add .
git commit -m "说明"
git push

# 服务器
cd /LBSops/huanbao-ai-assistant
git pull
npm ci
npm run build
systemctl reload nginx
```
