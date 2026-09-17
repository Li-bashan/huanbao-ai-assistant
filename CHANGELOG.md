# Changelog

本文件只记录经过整理的、对使用者或维护者有意义的变更，不复制提交日志，也不保存阶段性验收报告。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，日期使用 ISO 8601 格式。

## [Unreleased]

### Added

- 智能问数支持全员开放模式：Gateway 环境变量 `DATA_QUERY_ACCESS_OPEN_TO_ALL=true` 时跳过 `ai_data_query_user` 授权表校验，全部登录门户用户可用，查询范围为全组织（问数服务将 `X-Internal-Allowed-Orgs: *` 展开为 dim_org_mapping 全部组织编码，SQL 组织过滤边界不变）。默认 `false` 维持原授权表控制。

### 计划

- 按[去 Dify 化智能助手整体建设方案](./docs/去Dify化智能助手整体建设方案.md)建设独立模型、知识、办公和问数能力。
- 清理历史 Dify 问数资产，保留制度、办公和 Master 迁移期间的最小兼容边界。

## [2026-09-15]

### Changed

- 智能问数生产链路固定为前端 -> AI Gateway -> `huanbao-dataquery:8089` -> KingbaseES。
- 将 2025 年生活垃圾入厂量的生产口径、数据源和未关闭的业务参考差异写入当前基线文档。
- 建立文档索引、目录规范和当前事实优先级，移除历史交付报告与生成物。

