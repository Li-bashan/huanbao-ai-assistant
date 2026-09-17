# Claude / Cursor Agent 入口

本文件只作为 Claude Code、Cursor 等 Agent 的兼容入口，不复制一份独立项目说明。执行任务前必须先阅读根目录 `AGENTS.md`、`README.md` 和 `docs/项目交接说明.md`；若本文件与 `AGENTS.md` 不一致，以 `AGENTS.md` 为准。

## 当前边界

- 项目是 Vue 3 + Vite 的 iGIX 门户右侧嵌入式助手，同时包含 `ai-gateway` 和独立的 `huanbao-dataquery` 服务。
- 智能问数生产链路为 `前端 -> AI Gateway -> huanbao-dataquery:8089 -> KingbaseES`，不再使用 Dify 执行问数。
- 制度问答、办公智能和统一 Master 仍处于 Dify 兼容期；去 Dify 迁移按 `docs/去Dify化智能助手整体建设方案.md` 执行。
- 流程助手是前端动作卡片和 iGIX `postMessage` 桥接，不自动提交业务表单。
- 历史记录使用浏览器 `localStorage`，不是服务端会话存储。

## 修改纪律

- 不提交 API Key、数据库密码、签名密钥或服务器密码。
- 不把浏览器提交的身份、组织、指标、权限或数字当作服务端事实。
- 修改语义字典、SQL、Gateway 或 `huanbao-dataquery` 后，必须运行对应测试和 `npm run build`。
- 保持制度引用、办公 streaming、流程动作卡片、历史搜索筛选和复制导出能力。
- 不把截图、临时日志、发布压缩包和历史交付报告重新加入仓库。

详细架构、接口、部署和验证规则统一见 `docs/README.md`。
