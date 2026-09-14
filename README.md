# 环宝 AI 智能助手 / huanbao-ai-assistant

环宝 AI 智能助手是一个基于 Vue 3 + Vite 的企业门户右侧智能助手前端项目，用于嵌入公司办公门户，为员工提供查制度、写材料、办流程、问生产数据的一站式入口。

截至 2026-09-02，统一 Master Gateway 改动已提交并与 origin/main 同步；线上健康接口已验证，但生产服务器是否已拉取并重载该提交、Dify Key 实际绑定哪个 App、Kingbase 和门户身份是否联调完成，不能只从本 README 推出。详细证据见 [系统抓取与文档同步报告](./docs/系统抓取与文档同步报告-2026-09-02.md)。

当前版本是可演示、可使用的前端 V1：制度问答、办公智能和智能问数统一经 AI Gateway 接入 Dify，流程助手为前端动作卡片演示版，后续通过 postMessage、门户父页面、iGIX 菜单/表单能力完成真实办理。

项目整体最新状态见[项目现状总览](./docs/项目现状总览.md)；Dify 全部应用、已发布工作流、草稿和其他节点见[Dify 全部应用与工作流现状](./docs/Dify全部应用与工作流现状.md)，服务器和模型配置见[Dify 服务器部署与模型配置](./docs/Dify服务器部署与模型配置.md)。

## 当前能力

- 企业门户右侧嵌入式助手面板。
- 环宝 AI 视觉风格、欢迎区、消息气泡、输入区。
- 四项专业智能能力：
- 制度问答：查询制度依据，通过 AI Gateway 访问 Dify blocking/统一 Master。
  - 办公智能：办公材料处理，通过 AI Gateway 访问 Dify Agent streaming/统一 Master。
  - 流程助手：流程办理辅助，当前为前端动作卡片，不接真实业务系统。
  - 智能问数：查询生产指标数据，前端只持有用户专属会话句柄，由 AI Gateway 负责身份、DataScope、会话归属和 Dify SSE 代理；详见 [智能问数一体化升级交付报告](./docs/智能问数一体化升级交付报告.md)。
- 标题栏智能能力选择器，数量和菜单来自 `assistantModes` 配置。
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
  - AI Gateway / Dify
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
    assistantModes.js             四种智能能力配置
    workflowActions.js            流程助手动作卡片配置
  services/
    chatApi.js                    Gateway 路由与 Mock 调用
    dataQueryApi.js               智能问数 Gateway SSE 客户端
    gatewayChatApi.js             制度/办公 Gateway 客户端
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

前端环境变量只放非敏感配置。Dify API Key 在 AI Gateway 版本中只能放后端环境变量，不能放进 `VITE_` 变量，因为 `VITE_` 会被打进浏览器产物。

```env
VITE_AI_GATEWAY_BASE_URL=http://localhost:8088
VITE_DATA_QUERY_DEFAULT_PERIOD=今年
```

说明：
- `VITE_AI_GATEWAY_BASE_URL` 是前端 Gateway 入口；Gateway 的 Dify Key 只放后端环境变量。
- 旧版前端直连 Dify 变量已从运行链路移除，不要再写入 `.env.local`。
- Vite 只能读取 `VITE_` 开头的变量。

## 服务器部署

本地路径：

```text
~/company-projects/huanbao-ai-assistant
```

服务器静态发布目录：

```text
/opt/huanbao-ai-assistant
```

前端由服务器上的 `docker-nginx-1` 容器挂载该目录的 `dist/` 提供服务；服务器不是该项目的 Git 工作区。

访问地址：

```text
http://121.237.178.23:9002
```

Dify 地址：

```text
http://121.237.178.23:9002
```

Nginx 配置：

```text
/etc/nginx/conf.d/huanbao-ai-assistant.conf
```

服务器登录：

- SSH 地址：`121.237.178.23:22`
- SSH 用户：`root`
- 登录密码不写入仓库，使用服务器凭据或密码管理工具保管。

部署流程：

```bash
# 本地
cd ~/company-projects/huanbao-ai-assistant
npm run build
git status
git add .
git commit -m "说明"
git push

# 服务器：将 <release-id> 替换为本次发布编号
scp -r dist root@121.237.178.23:/opt/huanbao-ai-assistant/.release-stage-<release-id>
ssh root@121.237.178.23
cd /opt/huanbao-ai-assistant
tar -czf backups/dist-<release-id>.tar.gz -C . dist
# Nginx bind-mounts dist，保留目录本身，只替换目录内容
find dist -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +
cp -a .release-stage-<release-id>/dist/. dist/
docker exec docker-nginx-1 nginx -t
docker exec docker-nginx-1 nginx -s reload
```

服务器只托管构建后的 `dist/`，不执行 `git pull`、`npm ci` 或重新构建。发布前必须先推送本地提交，并保留服务器上的压缩备份。

日常发布已配置为 GitLab CI：提交到默认分支后自动执行前端、Gateway、`huanbao-dataquery` 的测试和打包，再使用 GitLab CI/CD Variables 中的 SSH 部署密钥发布到生产。发布脚本会备份前端和两个后端 Jar，重启服务并检查 `8089`、`8088`、`9002`；健康检查失败会自动回滚。首次启用必须先配置 `PROD_SSH_PRIVATE_KEY`、`PROD_SSH_KNOWN_HOSTS`、`PROD_DATAQUERY_SERVICE`、`PROD_DATAQUERY_JAR_PATH`、`PROD_GATEWAY_CONTAINER`、`PROD_GATEWAY_JAR_PATH`，详见 `docs/部署说明.md`。

## 常见问题

- 办公智能报 `Agent Chat App does not support blocking mode`：说明错误使用 blocking，请确认 Gateway/前端走 streaming 和 `response_mode: streaming`。
- 401：通常是 Dify API Key 错误或环境变量未生效。
- 页面仍是旧版本：确认本地已 push、服务器已同步 `dist/`，并检查容器内 Nginx 配置后强刷浏览器缓存。
- 历史记录不互通：历史使用浏览器 localStorage，本地、服务器、不同浏览器之间不会同步。

## 后续开发方向

- AI Gateway：作为前端与 Dify、审计日志之间的后端安全中间层。
- 流程助手 pending 入口逐项实测，通过后再开放。
- 服务端权限校验与操作审计。
- postMessage origin 白名单收口。

## AI Gateway 后端服务

仓库内新增独立 Spring Boot 服务：

```text
ai-gateway/
  pom.xml
  src/main/java/com/huanbao/aigateway
  src/main/resources/application.yml
```

定位：

- Dify 制度问答代理。
- 智能问数开放范围和查询代理预留。
- 流程助手操作审计落库。
- 动作参数校验预留。
- 统一异常处理和参数校验。
- 健康检查。

技术栈：

- Spring Boot 3.x
- Java 17
- PostgreSQL
- Spring Web
- Validation
- JdbcTemplate

本地启动：

```bash
cd ~/company-projects/huanbao-ai-assistant/ai-gateway

export AI_GATEWAY_DB_URL=jdbc:postgresql://localhost:5432/huanbao_ai
export AI_GATEWAY_DB_USERNAME=postgres
export AI_GATEWAY_DB_PASSWORD=your-password
export DIFY_POLICY_API_BASE=http://your-dify-host/v1
export DIFY_POLICY_API_KEY=replace-with-backend-env-secret

mvn spring-boot:run
```

注意：

- 真实 Dify API Key 只能放后端环境变量或后端配置，不能提交到代码和文档。
- 制度问答和办公智能当前通过 AI Gateway 代理，分别调用 `/api/ai/policy/chat` 和 `/api/ai/office/chat`；Dify Key 只允许存在 Gateway 后端环境。
- 操作审计表 SQL 位于 `docs/sql/ai_action_audit.sql`。
