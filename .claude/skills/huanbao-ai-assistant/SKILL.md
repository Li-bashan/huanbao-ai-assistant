# Skill: huanbao-ai-assistant

## 什么时候使用

当用户要求修改环宝 AI、智能助手、制度问答、办公智能、流程助手、历史记录、Dify 接入、门户嵌入、复制导出、部署时，应使用本 Skill。

修改前先阅读：

1. `README.md`
2. `CLAUDE.md`
3. `docs/项目交接说明.md`

## 项目能力范围

- 企业门户右侧 AI 助手前端。
- 制度问答 Dify blocking。
- 办公智能 Dify Agent streaming。
- 流程助手前端动作卡片。
- 自动意图识别。
- Markdown、引用来源、历史、复制导出。

## 技术栈

Vue 3、Vite、JavaScript、CSS、Dify API、localStorage、Nginx。

## 主要文件

- `src/App.vue`
- `src/services/chatApi.js`
- `src/config/assistantModes.js`
- `src/config/workflowActions.js`
- `src/utils/intentRouter.js`
- `src/utils/conversationStorage.js`
- `src/utils/messageExport.js`
- `src/utils/markdown.js`
- `src/style.css`

## 开发流程

1. 先读项目上下文文档。
2. 保持改动范围最小。
3. 不凭空新增复杂依赖。
4. 修改后执行 `npm run build`。
5. 汇报修改文件、验证结果和风险。

## UI 修改规则

- 保持浅蓝、圆角、轻量风格。
- 保持右侧嵌入能力。
- 不改成全屏后台。
- 不删除现有欢迎区、模式切换、历史弹层、工具栏。

## Dify 接入规则

- 不暴露密钥。
- 制度问答必须 blocking。
- 办公智能必须 streaming。
- Agent Chat App 不支持 blocking。
- `<think>` 内容必须过滤。

## 流程助手开发规则

- 不直接调用 Dify。
- 不打开真实表单。
- 使用 `workflowActions` 配置。
- 命中事项后展示动作卡片。
- 后续真实动作通过 postMessage / iGIX 对接。

## 历史记录开发规则

- 使用 localStorage。
- 不保存 loading / streaming 半截消息。
- 保留搜索、筛选、删除、清空。

## 部署检查规则

- 本地 `npm run build`。
- 本地 `git push`。
- 服务器 `git pull`。
- 服务器 `npm ci && npm run build`。
- `systemctl reload nginx`。

## 验证方式

- 制度问答：差旅费包括哪些费用？
- 办公智能：帮我写一份关于智慧办公系统临时维护的通知。
- 流程助手：打开采购请示单。
- 意图识别：给我整理会议纪要：流程、表单、审批节点……
- 历史：搜索、筛选、删除、清空。
- 工具栏：复制、导出 Markdown、导出 Word。

## 禁止事项

- 不要把真实 API Key 写进代码或文档。
- 不要绕过现有架构。
- 不要破坏制度问答 blocking。
- 不要破坏办公智能 streaming。
- 不要让流程助手直接调用 Dify。
- 不要删除引用来源展示。
- 不要删除历史搜索筛选能力。
- 不要删除复制导出能力。
