# Dify 服务器部署与模型配置

更新时间：2026-09-02

> 复核备注：本文主体是 2026-08-28 的服务器配置记录。本次只做了公开 HTTP 页面和项目接口的只读探测，没有 SSH 登录、读取服务器文件、修改容器或核验管理员配置。因此主机、容器、模型、插件和持久化目录都必须在目标服务器重新确认，不能只按本文视为当前事实。

本文记录当前 Dify 服务器的运行配置、容器、模型、插件、持久化目录和维护要点。所有密码、API Key、内部凭据和加密配置只在服务器上核验，不写入本文、不写入 Git。服务器配置会变化，修改 Dify 后应重新执行本文的核验命令并更新日期。

## 1. 服务器概况

| 项目 | 当前值 |
|---|---|
| 主机名 | `ecm-1bed` |
| 操作系统 | Ubuntu 22.04.5 LTS |
| 内核 | Linux 5.15.0-179-generic x86_64 |
| SSH 地址 | `121.237.178.23:22` |
| SSH 用户 | `root` |
| 环宝助手部署目录 | `/LBSops/huanbao-ai-assistant` |
| Dify 部署目录 | `/root/dify/dify-main/docker` |
| Dify 版本 | `1.16.1` |
| Docker | 29.5.2 |
| Docker Compose | v5.1.4 |
| Dify 控制台和 API 入口 | `http://121.237.178.23:9002` |
| 宿主机磁盘 | 2.0 TB，总使用约 388 GB，可用约 1.6 TB |
| 宿主机内存 | 503 GiB，可用约 477 GiB |
| 宿主机 Swap | 91 GiB |
| 防火墙 | `ufw` 当前 inactive，需由云安全组或其他主机策略承担边界防护 |

当前 Dify、Gateway 和环宝助手前端统一以 `121.237.178.23` 作为生产服务器；前端通过该服务器的 Nginx 对外提供 `http://121.237.178.23:9002`。服务器运行时间较长，最近一次核验时已连续运行约 91 天。登录密码不写入本文或 Git。

## 2. Docker Compose 运行拓扑

```text
公网 121.237.178.23:9002
        |
        v
Nginx 容器 :80
   |              |                |
   v              v                v
Web :3000     API :5001       Plugin Daemon :5002
                  |
       +----------+----------+----------------+
       v                     v                v
PostgreSQL :5432        Redis :6379      Worker / Beat
       |
       +--> Dify 数据库、工作流、应用、消息

API / Worker --> Weaviate :8080 / 50051
API / Worker --> Sandbox
Agent Backend :5050 --> Local Sandbox :5004 / Plugin Daemon
```

### 2.1 当前 Compose profile

`.env` 当前启用的 profile 是：

```text
weaviate,postgresql,collaboration
```

因此当前使用 Weaviate 向量库和 PostgreSQL；MySQL、Qdrant、Milvus、OpenGauss、Oracle 等 compose 文件中的可选服务不是当前运行链路，不能因为它们出现在模板里就认为已经部署。

### 2.2 当前运行容器

| 服务 | 镜像 | 作用 | 当前状态 |
|---|---|---|---|
| `nginx` | `nginx:latest` | 对外 HTTP / HTTPS 入口和反向代理 | 运行中 |
| `api` | `langgenius/dify-api:1.16.1` | Dify API、控制台接口、工作流运行入口 | healthy |
| `api_websocket` | `langgenius/dify-api:1.16.1` | WebSocket / Socket.IO | 运行中 |
| `worker` | `langgenius/dify-api:1.16.1` | 异步任务和工作流后台任务 | 运行中 |
| `worker_beat` | `langgenius/dify-api:1.16.1` | 定时任务调度 | 运行中 |
| `web` | `langgenius/dify-web:1.16.1` | Dify Web 控制台前端 | 运行中 |
| `db_postgres` | `postgres:15-alpine` | Dify 主数据库 | healthy |
| `redis` | `redis:6-alpine` | 队列、缓存和会话相关数据 | healthy |
| `weaviate` | `semitechnologies/weaviate:1.27.0` | 向量检索 | 运行中 |
| `plugin_daemon` | `langgenius/dify-plugin-daemon:0.6.3-local` | Dify 插件安装和执行 | 运行中 |
| `sandbox` | `langgenius/dify-sandbox:0.2.15` | 代码执行沙箱 | healthy |
| `local_sandbox` | `langgenius/dify-agent-local-sandbox:1.16.1` | Agent 本地沙箱 | healthy |
| `agent_backend` | `langgenius/dify-agent-backend:1.16.1` | Agent 运行后端 | 运行中 |
| `ssrf_proxy` / `agent_ssrf_proxy` | `ubuntu/squid:latest` | 出网 SSRF 代理 | 运行中 |
| `init_permissions` | `busybox:latest` | 初始化目录权限 | 已退出，属于一次性初始化容器 |

## 3. 端口和反向代理

| 宿主机端口 | 容器端口 / 服务 | 暴露范围 | 说明 |
|---:|---|---|---|
| `9002` | Nginx `80` | `0.0.0.0` | 当前 Dify 主入口，HTTP |
| `443` | Nginx `443` | `0.0.0.0` | 端口已映射，但 `NGINX_HTTPS_ENABLED=false`，不能据此认为 HTTPS 已完成配置 |
| `5003` | Plugin Daemon `5003` | `0.0.0.0` | 插件远程安装 / 调试端口，生产环境应限制来源 |
| 内部 `5001` | API | Compose 网络 | 不直接映射到宿主机 |
| 内部 `3000` | Web | Compose 网络 | 不直接映射到宿主机 |
| 内部 `5432` | PostgreSQL | Compose 网络 | 不直接映射到宿主机 |
| 内部 `6379` | Redis | Compose 网络 | 不直接映射到宿主机 |
| 内部 `8080` / `50051` | Weaviate HTTP / gRPC | Compose 网络 | 不直接映射到宿主机 |
| 内部 `5050` | Agent Backend | Compose 网络 | 不直接映射到宿主机 |

当前 Nginx `server_name` 是 `_`，容器内监听 80。`/api`、`/console`、`/v1` 等请求转发到 `api:5001`，根路径和 Web 静态页面转发到 `web:3000`，插件相关请求转发到 `plugin_daemon:5002`。前端项目调用的是 `http://121.237.178.23:9002/v1/chat-messages`。

## 4. 关键运行参数

以下是当前 `.env` 中与运行行为直接相关的非敏感配置：

| 配置 | 当前值 |
|---|---|
| `DEPLOY_ENV` | `PRODUCTION` |
| `DEBUG` | `false` |
| `DB_TYPE` / `DB_HOST` / `DB_PORT` | `postgresql` / `db_postgres` / `5432` |
| `DB_DATABASE` | `dify` |
| `DB_PLUGIN_DATABASE` | `dify_plugin` |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_DB` | `redis` / `6379` / `0` |
| `VECTOR_STORE` | `weaviate` |
| `STORAGE_TYPE` / `OPENDAL_SCHEME` | `opendal` / `fs` |
| `OPENDAL_FS_ROOT` | `storage` |
| `EXPOSE_NGINX_PORT` / `NGINX_PORT` | `9002` / `80` |
| `EXPOSE_NGINX_SSL_PORT` / `NGINX_SSL_PORT` | `443` / `443` |
| `NGINX_HTTPS_ENABLED` | `false` |
| `NGINX_SERVER_NAME` | `_` |
| `NGINX_CLIENT_MAX_BODY_SIZE` | `100M` |
| `NGINX_PROXY_READ_TIMEOUT` / `NGINX_PROXY_SEND_TIMEOUT` | `3600s` / `3600s` |
| `GUNICORN_TIMEOUT` | `360` |
| `TEXT_GENERATION_TIMEOUT_MS` | `60000` |
| `WORKFLOW_GENERATION_TIMEOUT_MS` | `180000` |
| `MAX_ITERATIONS_NUM` | `99` |
| `MAX_PARALLEL_LIMIT` | `10` |
| `LOOP_NODE_MAX_COUNT` | `100` |
| `MAX_TOOLS_NUM` | `10` |
| `TOP_K_MAX_VALUE` | `10` |
| `PLUGIN_MAX_PACKAGE_SIZE` | `52428800`，约 50 MiB |
| `PLUGIN_MAX_EXECUTION_TIMEOUT` | `600` 秒 |
| `PLUGIN_PYTHON_ENV_INIT_TIMEOUT` | `600` 秒 |
| `SANDBOX_ENABLE_NETWORK` | `true` |
| `ALLOW_EMBED` | `false` |
| `ALLOW_UNSAFE_DATA_SCHEME` | `false` |
| `ENABLE_COLLABORATION_MODE` | `true` |
| `ENABLE_LEARN_APP` | `true` |
| `MARKETPLACE_ENABLED` | `true` |

当前模型服务没有通过 `.env` 的 `OPENAI_API_BASE` 统一配置暴露出来，该变量当前为空；模型凭据保存在 Dify 数据库的加密凭据表中。不要为了补文档去读取或导出 `encrypted_config`。

## 5. 已部署模型和模型供应商

### 5.1 当前有效模型清单

当前 Dify 数据库 `provider_models` 中核验到以下有效模型，全部使用同一个 OpenAI-compatible 供应商：

供应商标识：`langgenius/openai_api_compatible/openai_api_compatible`

| 模型 | 类型 | 凭据配置名称 | 当前用途 |
|---|---|---|---|
| `Qwen3.8-27B` | `llm` | `Qwen3.8-27B-vLLM` | 智能问数 SQL 生成、失败 SQL 修复、办公 Agent |
| `Qwen3-32B-Int4` | `llm` | `Qwen3-32B-Int4-vLLM` | 智能问数最终回答和图表参数生成 |
| `bge-m3` | `text-embedding` | `API KEY 2` | 当前知识库向量化和语义检索 |
| `bge-reranker-v2-m3` | `rerank` | `API KEY 2` | 可用于知识库重排；智能问数两个当前检索节点的 reranking 关闭 |

模型有效状态以 Dify 数据库记录为准。当前没有把凭据值、模型服务真实内网地址或 API Key 写入文档。

### 5.2 当前项目各能力使用的模型

| 能力 / 节点 | 模型 | 备注 |
|---|---|---|
| 智能问数：生成指标SQL | `Qwen3.8-27B` | 生成一个或多个指标 SQL |
| 智能问数：修复失败SQL | `Qwen3.8-27B` | 根据错误信息生成修复 SQL |
| 智能问数：生成回答与图表参数 | `Qwen3-32B-Int4` | 汇总数据并生成回答和图表参数 |
| 办公智能 Agent 当前配置 | `Qwen3.8-27B` | temperature `0.3`，max tokens `8192` |
| 制度问答已发布工作流 | `qwen-122b` | 旧工作流引用；不在当前 `provider_models` 有效清单中，需在 Dify 控制台实际测试确认 |
| 历史通用问答工作流 | `qwen-122b` / `qwen-32b` | 历史配置引用；不作为当前项目主链路 |

`qwen-122b` 和 `qwen-32b` 出现在部分历史或已发布工作流 JSON 中，但当前有效模型表只列出了 Qwen3 系列和 BGE 模型。这是服务器配置中需要特别关注的兼容性差异：更新供应商或发布制度问答草稿前，应先在 Dify 节点测试中确认模型仍可用，再决定是否迁移模型。

### 5.3 办公智能 Agent 配置

当前应用：`智慧办公-环宝办公智能助手`，应用 ID `3368e09c-20fe-4526-beac-70897eacb18e`。

| 配置项 | 当前值 |
|---|---|
| Agent ID | `01a04281-8d8a-7064-bd10-ec74151c8fee` |
| 当前配置版本 | `2` |
| 当前配置更新时间 | `2026-08-27 09:18:04` |
| 模型 | `Qwen3.8-27B` |
| 模型供应商 | `langgenius/openai_api_compatible/openai_api_compatible` |
| temperature | `0.3` |
| max tokens | `8192` |
| Agent 工具 | 当前 `cli_tools` 和 `dify_tools` 均为空数组 |
| Agent 知识库 | 当前 `knowledge.sets` 为空 |
| 文件上传 | 已启用，单次数量上限 3；配置允许文档、图片、音频、视频类型 |
| 当前配置是否有模型 | 是 |
| 当前配置是否标记已发布 | 否，需在 Dify 控制台确认是否应该发布 |

Agent 系统提示词定位为中文的公司内部办公通用助手，覆盖材料撰写、总结、会议纪要、通知、文件阅读整理和基础办公数据处理。当前 Agent 没有配置知识集和工具，不能把提示词中提到的“知识库、工具、技能”写成已经接通的运行能力。

## 6. 已安装插件

当前插件守护进程的本地工作目录中核验到以下插件包：

| 插件 | 版本 | 主要用途 / 备注 |
|---|---:|---|
| `jaguarliuu/rookie_text2data` | `1.0.3` | 当前智能问数直接执行 KingbaseES SQL 的核心插件 |
| `langgenius/openai_api_compatible` | `0.0.53` | 当前 Qwen、BGE 模型供应商插件 |
| `langgenius/tongyi` | `0.2.0` | 通义模型、Embedding / Rerank 相关插件 |
| `langgenius/echarts` | `0.0.7` | 智能问数图表输出相关插件 |
| `digitforce/data_analysis` | `2.0.5` | 数据分析工具插件 |
| `bowenliang123/md_exporter` | `3.6.9` | Markdown 导出 |
| `sawyer-shi/mind_map` | `0.0.9` | 思维导图能力 |
| `langgenius/openai` | `0.4.2` | OpenAI 供应商插件 |
| `langgenius/ollama` | `0.2.2` | Ollama 供应商插件 |
| `langgenius/dify_extractor` | `0.0.5` | 文档抽取 |
| `langgenius/general_chunker` | `0.0.7` | 文档切分 |
| `langgenius/notion_datasource` | `0.1.12` | Notion 数据源 |
| `langgenius/google_drive` | `0.1.6` | Google Drive 数据源 |
| `langgenius/jina_datasource` / `firecrawl_datasource` | `0.0.5` / `0.2.4` | 网页数据源 |

智能问数实际使用的是 `rookie_text2data`、`openai_api_compatible` 和 `echarts` 这条链路。其他插件出现在已安装环境中，不代表当前项目工作流都在使用。

## 7. 持久化目录和数据规模

### 7.1 当前挂载

| 宿主机目录 | 容器用途 | 最近核验大小 |
|---|---|---:|
| `/root/dify/dify-main/docker/volumes/app/storage` | Dify 文件和应用存储 | 约 380 KiB |
| `/root/dify/dify-main/docker/volumes/db/data` | PostgreSQL 数据目录 | 约 102 MiB |
| `/root/dify/dify-main/docker/volumes/redis/data` | Redis 数据 | 约 48 KiB |
| `/root/dify/dify-main/docker/volumes/weaviate` | Weaviate 向量数据 | 约 6.4 MiB |
| `/root/dify/dify-main/docker/volumes/plugin_daemon` | 插件包、插件虚拟环境、缓存和运行数据 | 约 3.4 GiB |
| `/root/dify/dify-main/docker/volumes/sandbox` | Sandbox 配置和依赖 | 约 8 KiB |

最近核验时 `dify` 数据库约 38 MB，包含 10 个应用、50 条 workflow 记录、7 个数据集和 301 条消息记录。插件目录明显大于业务数据库，备份或迁移时不能只备份 PostgreSQL。

### 7.2 必须备份的内容

生产备份至少覆盖：

1. PostgreSQL `dify` 数据库。
2. PostgreSQL `dify_plugin` 数据库。
3. `volumes/app/storage`。
4. `volumes/weaviate`。
5. `volumes/plugin_daemon`，尤其是已安装插件和插件运行环境。
6. `/root/dify/dify-main/docker/.env`，但必须通过受控的密码库或加密备份保存，不能提交到 Git。

备份示例，仅供服务器运维使用，不要把输出文件提交到项目：

```bash
cd /root/dify/dify-main/docker
mkdir -p /root/dify-backups
docker exec docker-db_postgres-1 pg_dump -U postgres -d dify > /root/dify-backups/dify.sql
docker exec docker-db_postgres-1 pg_dump -U postgres -d dify_plugin > /root/dify-backups/dify_plugin.sql
tar -czf /root/dify-backups/dify-volumes.tar.gz volumes/app/storage volumes/weaviate volumes/plugin_daemon
chmod 600 /root/dify-backups/dify.sql /root/dify-backups/dify_plugin.sql /root/dify-backups/dify-volumes.tar.gz
```

## 8. 服务器上的其他 Docker 项目

同一宿主机还运行着独立的 `buildingai` Compose 项目：

| 容器 | 镜像 | 宿主机端口 | 工作目录 |
|---|---|---:|---|
| `buildingai-nodejs` | `ccr.ccs.tencentyun.com/buildingai/node:22.20.0` | `3000` | `/opt/ai_pro/buildingai` |
| `buildingai-postgres` | `ccr.ccs.tencentyun.com/buildingai/postgres:17.6` | `32769` | `/opt/ai_pro/buildingai` |
| `buildingai-redis` | `ccr.ccs.tencentyun.com/buildingai/redis:8.2.2` | `32768` | `/opt/ai_pro/buildingai` |

这些容器不是 Dify 依赖，不要用 Dify 的 compose 命令停启它们，也不要清理宿主机 Docker volume 时把它们的 volume 一起删除。`buildingai` 项目和 Dify 共用宿主机资源，后续扩容、改端口或重启时要考虑相互影响。

## 9. 日常运维命令

```bash
cd /root/dify/dify-main/docker

# 查看 Dify 服务状态
docker compose ps

# 查看核心服务日志
docker compose logs --tail=200 api worker plugin_daemon nginx

# 启动或补齐 Dify 服务
docker compose up -d

# 只重启修改过配置的服务
docker compose restart api worker plugin_daemon nginx
```

修改 `.env` 后必须重新创建受影响的容器，单纯 `systemctl reload nginx` 不会让 Docker 容器读取新的环境变量：

```bash
docker compose up -d --force-recreate api worker worker_beat plugin_daemon agent_backend nginx
```

执行重建前要确认当前工作流没有长任务，并保存 `docker compose ps` 和日志；不要直接执行全栈 `down -v`，这会有删除持久化数据的高风险。

## 10. 安全和配置风险

当前服务器配置需要持续关注：

- Dify `.env` 当前权限为 `0644`，虽然文件所有者是 root，但建议生产环境收紧为 `0600`，并确认备份文件权限同样收紧。
- compose 文件中存在用于未配置环境变量时的默认内部 Key。生产环境必须确保 `.env` 中的真实 Key 已覆盖默认值，并定期轮换插件、Sandbox、数据库和 Redis 凭据。
- `5003` 插件远程安装 / 调试端口和 `9002` 主入口都映射到 `0.0.0.0`；应通过云安全组、反向代理或主机防火墙限制来源。
- `NGINX_HTTPS_ENABLED=false`，当前主入口是 HTTP。生产环境应配置证书、HTTPS、可信域名和安全 Header。
- `SANDBOX_ENABLE_NETWORK=true`。工作流代码节点和插件可以产生网络访问能力，必须结合 SSRF 代理、出口策略和插件权限审查。
- `ALLOW_EMBED=false`，如后续要把 Dify 控制台嵌入其他页面，不能只改前端 iframe，还要重新评估 CSP、Cookie 和跨域策略。
- 模型凭据存在 Dify 数据库加密字段中；迁移数据库或恢复备份时必须同时保管 Dify `SECRET_KEY`，否则加密凭据可能无法解密。
- 当前项目智能问数仍由 Dify SQL 插件直连 KingbaseES，不经过仓库 AI Gateway；服务器配置文档不能被误读成已经完成服务端用户鉴权。

## 11. 每次服务器变更后的核验

```bash
cd /root/dify/dify-main/docker
docker compose ps
curl -I http://127.0.0.1:9002
docker exec docker-db_postgres-1 psql -U postgres -d dify -At -c "select count(*) from apps;"
```

然后在前端依次验证制度问答、办公 Agent 和智能问数。智能问数要额外确认：已发布 workflow ID 是否变化、最新草稿是否已发布、首轮和补查 SQL 端口是否一致、模型是否仍可用、插件执行日志是否出现错误。Dify 应用和工作流清单见[Dify 全部应用与工作流现状](./Dify全部应用与工作流现状.md)。
