# 工具目录

这里仅保留可以重复执行、对当前项目仍有价值的工具：

- `deploy-production.sh`：生产发布、备份、健康检查和失败回滚。
- `inspect_views_layout.mjs`：本地页面布局审计。输出的截图和 JSON 报告在 `.gitignore` 中，不提交。
- `data-query-preview.html`：智能问数界面手工预览。
- `perf_data_query.mjs`：问数接口并发和性能检查。
- `test_gateway_isolation.mjs`：Gateway 多用户隔离回归。
- `keep-frontend-running.ps1`：本地开发辅助脚本。

Dify 工作流发布、重建和历史回归脚本已经不属于当前问数生产链路，不再放在仓库中。临时输出、截图、性能日志和测试日志不得提交。
