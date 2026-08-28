# Dify / AI Gateway 查询人大金仓接入说明

更新时间：2026-08-28

> 重要：截至 2026-08-28，线上 `智慧办公—智能问数` 工作流还没有使用 Dify HTTP 节点，也没有调用仓库内的 AI Gateway。当前工作流通过 `rookie_text2data / rookie_excute_sql` 插件直接连接 KingbaseES。本文件下面的 HTTP 节点方案是目标接入方案，不代表当前线上链路。

## 当前实际链路

```text
前端
    ↓ POST /v1/chat-messages，streaming
Dify 智能问数应用
    ↓ rookie_text2data / rookie_excute_sql
KingbaseES
```

当前应用和工作流详见[《Dify 智能问数工作流现状》](./Dify智能问数工作流现状.md)。

## 计划中的 Gateway 链路

```text
Dify HTTP 请求节点
        ↓ POST /api/ai/data/query
Spring Boot AI Gateway
        ↓ NamedParameterJdbcTemplate
人大金仓 KingbaseES
        ↓
统一 JSON 返回给 Dify
```

## 1. KingbaseES JDBC 驱动

项目的 `kingbase` Maven profile 使用 Maven Central 中的官方驱动：

```xml
<groupId>cn.com.kingbase</groupId>
<artifactId>kingbase8</artifactId>
<version>8.6.0</version>
```

应按实际 KingbaseES 服务端版本选择兼容的驱动；如果不是 `8.6.0`，构建时
通过 `-Dkingbase.jdbc.version=实际版本` 覆盖，或使用企业 Maven 仓库批准的版本。

## 2. 配置并启动 Spring Boot

不要把数据库密码和接口密钥提交到仓库。Linux 示例：

```bash
export AI_GATEWAY_DB_URL='jdbc:kingbase8://192.168.1.10:54321/huanbao'
export AI_GATEWAY_DB_USERNAME='ai_query_user'
export AI_GATEWAY_DB_PASSWORD='replace-me'
export AI_GATEWAY_DB_DRIVER='com.kingbase8.Driver'
export DIFY_HTTP_API_KEY='replace-with-a-long-random-secret'

cd ai-gateway
mvn -Pkingbase clean package
java -jar target/ai-gateway-0.1.0.jar
```

数据库账号建议只授予所需表或视图的 `SELECT` 权限。当前示例查询读取
`ai_action_audit`，因此应确保该表已创建，并为查询账号授权。智能问数白名单表
`ai_data_query_user` 的建表 SQL 位于 `docs/sql/ai_data_query_user.sql`。

## 3. Dify HTTP 请求节点（目标方案，当前未启用）

以下配置用于后续把数据库访问迁移到 AI Gateway；当前线上工作流不会读取这些 HTTP 节点示例中的 `userName` 或 `queryCode`。

- 方法：`POST`
- URL：`http://AI_GATEWAY地址:8088/api/ai/data/query`
- Header：`Content-Type: application/json`
- Header：`X-API-Key: 在 DIFY_HTTP_API_KEY 中配置的值`
- 超时：建议 30 秒

查询最近审计记录的 Body：

```json
{
  "userName": "{{ current_user_name }}",
  "queryCode": "action_audit_recent",
  "params": {
    "userId": "{{#start.user_id#}}",
    "action": "open_form",
    "limit": 20
  }
}
```

统计最近若干天记录的 Body：

```json
{
  "userName": "{{ current_user_name }}",
  "queryCode": "action_audit_summary",
  "params": {
    "days": 7
  }
}
```

成功返回：

```json
{
  "success": true,
  "code": "0",
  "message": "ok",
  "data": {
    "queryCode": "action_audit_recent",
    "rowCount": 1,
    "records": [
      {
        "action_id": "demo-id",
        "user_id": "demo-user",
        "action": "open_form",
        "result": "sent",
        "created_at": "2026-07-22T10:00:00+08:00"
      }
    ]
  }
}
```

Dify 后续节点可读取 `body.data.records`。不同 Dify 版本的 HTTP 节点输出
变量名可能略有差异，以节点调试面板显示的结构为准。

`userName` 是前端传入的 `current_user_name`，仅用于本次试点白名单匹配。Gateway
在执行任何固定查询前会再次校验该姓名：缺少时返回 `CURRENT_USER_MISSING`，不在
启用名单时返回 `DATA_QUERY_NOT_COVERED`。前端状态不能绕过这一层校验。

## 4. 接真实业务查询

不要开放“前端传 SQL”接口。新增业务查询时：

1. 在 `DifyDataQueryService` 增加固定 `queryCode`。
2. 对每个输入参数做类型、长度、范围校验。
3. 在 `DifyDataQueryRepository` 使用命名参数执行固定 SQL。
4. 对敏感字段做脱敏，并限制最大返回行数。
5. 给 KingbaseES 查询账号只授予对应视图的 `SELECT` 权限。

生产环境还应通过 Nginx 或防火墙仅允许 Dify 服务器访问该接口，并使用
HTTPS；`X-API-Key` 是应用层补充校验，不能替代网络访问控制。
