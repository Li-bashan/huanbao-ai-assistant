# CLAUDE.md

本文件面向 Claude Code / Codex / Cursor 等 AI Agent，是环宝 AI 智能助手项目的核心上下文。

## 项目定位

环宝 AI 智能助手是 Vue 3 + Vite 实现的企业门户右侧 AI 助手，用于嵌入公司办公门户，为员工提供制度问答、办公材料处理、流程办理辅助。

## 产品边界

- 当前是前端项目。
- 制度问答和办公智能已接入 Dify。
- 流程助手当前为动作卡片演示版，不打开真实表单。
- 历史记录使用浏览器 localStorage，不是服务端历史。

## 技术架构

- `src/App.vue` 负责页面、模式切换、发送逻辑、历史、工具栏。
- `src/services/chatApi.js` 负责 Dify API。
- `src/config/assistantModes.js` 配置三个模式。
- `src/config/workflowActions.js` 配置流程动作卡片。
- `src/utils/intentRouter.js` 自动意图识别。
- `src/utils/conversationStorage.js` localStorage 历史。
- `src/utils/messageExport.js` 复制和导出。
- `src/utils/markdown.js` Markdown 渲染与 DOMPurify 消毒。
- `src/style.css` 全部样式。

## 三个助手模式

### 制度问答 policy

- 标题：环宝制度问答助手。
- 使用 Dify blocking。
- 支持 `metadata.retriever_resources` 引用来源。
- 支持原文片段展开。

### 办公智能 office-ai

- 标题：环宝办公智能助手。
- 使用 Dify Agent streaming。
- 用于会议纪要、通知、总结、润色、摘要。
- streaming 输出需过滤 `<think>`。

### 流程助手 workflow

- 标题：环宝流程助手。
- 当前只做前端动作卡片。
- 不调用 Dify。
- 后续通过 postMessage / 门户 / iGIX 接表单和菜单。

## Dify 接入规则

- 不要把真实 API Key 写进代码。
- 制度问答环境变量：
  - `VITE_POLICY_DIFY_API_BASE`
  - `VITE_POLICY_DIFY_API_KEY`
- 办公智能环境变量：
  - `VITE_OFFICE_DIFY_API_BASE`
  - `VITE_OFFICE_DIFY_API_KEY`
- `VITE_DIFY_USER` 作为 Dify user。
- 修改 `.env.local` 后必须重启 `npm run dev`。

## streaming 处理规则

- 办公智能 Agent Chat App 不支持 blocking。
- `streamChatMessage` 使用 fetch + ReadableStream 解析 SSE。
- 处理事件：`message`、`agent_message`、`message_end`、`error`。
- `message_end` 保存 `conversation_id` 和 `message_id`。
- 前端不能展示 `<think>...</think>`。

## intentRouter 规则

- 文件：`src/utils/intentRouter.js`。
- 办公材料处理强意图优先于流程弱关键词。
- “整理会议纪要 + 流程/表单/审批”应进入办公智能。
- 单独出现“流程、表单、审批”不应切到流程助手。

## workflowActions 规则

- 文件：`src/config/workflowActions.js`。
- 当前配置：采购请示单、合同评审流程、我的待办。
- 动作卡片必须展示事项、说明、操作按钮、必填字段。
- 点击按钮仅追加演示提示。

## localStorage 历史规则

- 当前会话 key：`huanbao_current_conversation`。
- 历史 key：`huanbao_conversation_history`。
- 模式 key：`huanbao_current_mode`。
- 不保存 loading / streaming 消息。
- 最多保存 50 条历史。

## 复制和导出规则

- assistant 消息底部显示工具栏。
- 复制为纯文本。
- Markdown 导出为 `.md`。
- Word 导出为 HTML `.doc`。
- 流程动作卡片导出要包含事项、操作和字段。

## UI 修改规则

- 保持门户右侧嵌入面板。
- 不做全屏后台页。
- 不做抽屉式历史。
- 不破坏 Header / ChatBody / Footer 三段式。
- 不使用大图机器人背景替代当前欢迎图。

## 禁止改动项

- 不要写真实 API Key。
- 不要破坏制度问答 blocking。
- 不要破坏办公智能 streaming。
- 不要让流程助手调用 Dify。
- 不要把流程助手做成纯文字回答。
- 不要删除引用来源展示。
- 不要删除历史搜索筛选能力。
- 不要删除复制导出能力。

## 常用命令

```bash
npm run dev
npm run build
git status
```

## 验证清单

见 `docs/验证清单.md`。每次修改后至少执行 `npm run build`。

## 部署注意事项

- 本地提交后必须 `git push`。
- 服务器通过 `git pull` 拉远程仓库。
- 服务器路径：`/LBSops/huanbao-ai-assistant`。
- Nginx 配置：`/etc/nginx/conf.d/huanbao-ai-assistant.conf`。
- 访问地址：`http://192.168.245.138:8090`。

## 后续路线

- 流程助手 postMessage 协议。
- 门户父页面监听助手动作。
- iGIX 菜单 / 表单打开。
- ticket 免登和用户身份注入。
- 表单字段预填。
- 服务端历史记录与审计。
