# 环宝 AI 智能助手 / huanbao-ai-assistant

环宝 AI 智能助手是一个基于 Vue 3 + Vite 的企业门户右侧智能助手前端项目，用于嵌入公司办公门户，为员工提供查制度、写材料、办流程的一站式入口。

当前版本是可演示、可使用的前端 V1：制度问答和办公智能已接入真实 Dify 应用，流程助手为前端动作卡片演示版，后续通过 postMessage、门户父页面、iGIX 菜单/表单能力完成真实办理。

## 当前能力

- 企业门户右侧嵌入式助手面板。
- 环宝 AI 视觉风格、欢迎区、消息气泡、输入区。
- 三种助手模式：
  - 制度问答：查询制度依据，使用 Dify blocking。
  - 办公智能：办公材料处理，使用 Dify Agent streaming。
  - 流程助手：流程办理辅助，当前为前端动作卡片，不接真实业务系统。
- 悬浮式模式切换。
- 自动意图识别与模式分发。
- Markdown 渲染与 DOMPurify 安全过滤。
- 制度问答引用来源展示、原文片段展开。
- 办公智能 streaming 输出 `<think>` 过滤。
- 流程助手动作卡片。
- AI 回复复制、导出 Markdown、导出 Word/HTML。
- localStorage 本地历史：搜索、分类筛选、分组、删除单条、清空历史。

## 技术栈

- Vue 3
- Vite
- JavaScript
- CSS
- Dify API
- markdown-it
- DOMPurify
- localStorage
- Nginx 静态部署

## 目录结构

```text
src/
  App.vue                         主助手页面和交互逻辑
  style.css                       全局样式
  config/
    assistantModes.js             三种助手模式配置
    workflowActions.js            流程助手动作卡片配置
  services/
    chatApi.js                    Dify blocking / streaming / Mock 调用
  utils/
    conversationStorage.js        localStorage 会话历史
    intentRouter.js               自动意图识别
    markdown.js                   Markdown 渲染与消毒
    messageExport.js              复制与导出
public/
  huanbao-avatar.png              Header 和气泡头像
  huanbao-welcome-bg.png          欢迎区背景图
docs/                             项目交接文档
```

## 本地启动

```bash
cd ~/company-projects/huanbao-ai-assistant
npm install
npm run dev
```

修改 `.env.local` 后必须重启 `npm run dev`，Vite 不会自动重新读取已启动进程里的环境变量。

## 构建

```bash
npm run build
```

构建产物在 `dist/`。

## 环境变量

真实密钥只放 `.env.local` 或部署平台环境变量，不能提交到仓库。

```env
VITE_DIFY_USER=your-user-id
VITE_USE_DIFY=true

VITE_POLICY_DIFY_API_BASE=http://your-dify-host/v1
VITE_POLICY_DIFY_API_KEY=app-your-policy-key

VITE_OFFICE_DIFY_API_BASE=http://your-dify-host/v1
VITE_OFFICE_DIFY_API_KEY=app-your-office-key
```

说明：
- `VITE_POLICY_DIFY_*` 用于制度问答。
- `VITE_OFFICE_DIFY_*` 用于办公智能 Agent。
- 旧变量 `VITE_DIFY_API_BASE` / `VITE_DIFY_API_KEY` 仅作为制度问答兼容回退。
- Vite 只能读取 `VITE_` 开头的变量。

## 服务器部署

本地路径：

```text
~/company-projects/huanbao-ai-assistant
```

服务器路径：

```text
/LBSops/huanbao-ai-assistant
```

访问地址：

```text
http://192.168.245.138:8090
```

Nginx 配置：

```text
/etc/nginx/conf.d/huanbao-ai-assistant.conf
```

部署流程：

```bash
# 本地
cd ~/company-projects/huanbao-ai-assistant
npm run build
git status
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

服务器 `git pull` 拉的是远程仓库，不会读取 Mac 本地工作区。如果本地显示领先 `origin/main`，必须先 `git push`。

## 常见问题

- 办公智能报 `Agent Chat App does not support blocking mode`：说明错误使用 blocking，请确认办公智能走 `streamChatMessage` 和 `response_mode: streaming`。
- 401：通常是 Dify API Key 错误或环境变量未生效。
- 页面仍是旧版本：确认本地已 push、服务器已 pull，浏览器强刷缓存。
- 历史记录不互通：历史使用浏览器 localStorage，本地、服务器、不同浏览器之间不会同步。

## 后续开发方向

- 流程助手 postMessage 动作协议。
- 门户父页面监听助手动作。
- 接入真实 iGIX 菜单 / 表单。
- ticket 免登与用户身份注入。
- 表单字段预填。
- 服务端历史记录与审计。
