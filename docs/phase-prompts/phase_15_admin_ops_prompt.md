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

## Subagent 分工模板

本节描述 AI 编码代理执行本 Phase 时允许使用的 subagent 协作方式。`subagent` 只表示编码执行分工，不是 NoteWeave 产品运行态 Agent/Skill 设计。

通用规则：

- 主代理是本 Phase 的 owner，负责最终集成、测试、文档更新和交付结论。
- subagent 必须先按本文档的“必读文档”顺序读取上下文，再开始自己的子任务。
- 每个 subagent 必须有明确 ownership，限定可修改的模块、文件或测试范围。
- 不允许多个 subagent 同时修改同一文件或同一模块；需要交叉修改时由主代理统一合并。
- 不允许 subagent 扩大当前 Phase 范围，遇到范围外问题只记录为遗留风险。
- 所有实现仍必须遵守 TDD：先写失败测试，再写最小实现，再重构和回归。
- subagent 产出必须由主代理 review 后合入，主代理不能直接信任未验证结果。

推荐分工：

```text
lead-agent:
- 读取全部必读文档，拆分任务，维护 Phase 边界。
- 负责最终集成、运行回归测试、更新 PROJECT_STATUS。

test-agent:
- 先写 Admin Guard、任务管理、资源清理、健康检查、AuditLog 测试。
- 覆盖普通用户不可访问 admin、清理默认先 scan。

admin-auth-agent:
- 负责 Admin Guard、systemRole=ADMIN 权限和敏感信息保护。
- 不复用 Space OWNER 作为系统管理员。

task-admin-agent:
- 负责任务查询、重试、取消、mark failed 和状态边界。
- 重试/取消必须写 AuditLog。

cleanup-agent:
- 负责 ResourceCleanupService、OpsCleanupJob、OpsCleanupItem。
- 默认 scan，不直接物理删除。

health-agent:
- 负责 MySQL / Redis / MinIO / Kafka / ES / LLM Provider 健康检查。
- 无权限用户不能看到内部配置。

audit-agent:
- 负责 AuditLog 模型、写入点和管理端查询。
- 不记录敏感明文。

review-agent:
- 只做 review，不直接改代码。
- 重点检查 admin 权限、清理安全性、审计完整性和运维信息泄露。
```
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
- 只允许 `FAILED / TIMEOUT` 任务重试；`CANCELLED` 任务不能直接 retry，需要业务重新发起。
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
