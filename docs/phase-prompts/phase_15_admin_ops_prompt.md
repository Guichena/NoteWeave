# Phase 15 Prompt: Admin / Ops

你是 NoteWeave 项目的编码代理。请执行 Phase 15：管理后台与运维能力。

## 测试驱动执行规则

本阶段必须采用测试驱动开发：

```text
1. 先根据本阶段目标和验收标准写测试
2. 运行测试，确认关键测试失败且失败原因符合预期
3. 再写最小实现让测试通过
4. 重构和补齐边界处理
5. 最后运行当前阶段相关测试和必要回归测试
```

## Docker 中间件执行规则

本阶段涉及的所有中间件必须通过 Docker Compose 或 Testcontainers 提供：

```text
MySQL
Redis
MinIO
Elasticsearch
Kafka
```

要求：

- 不允许依赖本机散装安装的中间件。
- 本地开发中间件统一维护在根目录 `docker-compose.yml`。
- 集成测试中间件统一使用 Testcontainers。
- 当前 Phase 新增中间件、bucket、topic、index 或测试路径时，必须同步更新 `docs/DOCKER_MIDDLEWARE.md`。
- 测试临时路径统一使用 `target/noteweave-test/{phase}/`，不能写用户机器绝对路径。

## 必读文档

按顺序读取：

```text
docs/PROJECT_STATUS.md
docs/CONTRACT.md
docs/DOCKER_MIDDLEWARE.md
docs/implementation_breakdown.md
docs/features/database_api_blueprint.md
docs/features/phase_1_5_task_outbox_worker.md
docs/features/phase_14_evaluation_observability.md
docs/features/phase_15_admin_ops.md
```

## 目标

实现运维管理能力：

```text
Admin Console API
Task 重试
Task 取消
资源清理
孤儿对象扫描
健康检查
AuditLog
```

## 严格边界

不要实现：

```text
商业化计费
复杂企业审批流
Quiz
```

## 必须遵守

- Admin 权限来自 `users.system_role = ADMIN`，不能复用 Space OWNER。
- 只允许安全状态的任务重试。
- 清理任务必须先生成候选项，再逐项清理。
- 不能物理删除仍被引用的对象。
- Admin 日志和敏感字段必须脱敏。

## 交付要求

实现代码和测试。并确保以下内容已落实：

```text
Admin API
清理策略
重试边界
AuditLog 内容
测试命令和结果已记录到 docs/PROJECT_STATUS.md
```

