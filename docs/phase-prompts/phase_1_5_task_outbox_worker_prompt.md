# Phase 1.5 Prompt: Task / Outbox / Worker

你是 NoteWeave 项目的编码代理。请执行 Phase 1.5：统一异步任务基础设施。

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
docs/features/phase_0_1_bootstrap_auth_space.md
```

## 目标

实现统一异步任务地基：

```text
task
task_attempt
task_event
task_outbox
TaskService
TaskEventService
TaskOutboxService
TaskDispatcher
TaskWorkerRegistry
TaskWorker
NOOP_TEST Worker
通用 Task API
```

## 严格边界

不要实现真实业务任务：

```text
文档解析
Embedding
Source 编译
Artifact 生成
Wiki 入索引
LLM 调用
Quiz
```

## 必须遵守

- 不得新增 `generation_task`、`index_task`。
- Task 状态必须包含 `TIMEOUT`。
- 只有 `PENDING` 任务可以进入执行。
- `FAILED / TIMEOUT` 可按 `max_retry_count` 重试。
- `RUNNING` 取消只设置 `cancel_requested`，Worker 在安全点停止。
- Outbox 投递必须可补偿。
- Consumer/Worker 处理前必须查 DB 状态，保证幂等。

## API

至少实现：

```http
GET  /api/v1/tasks
GET  /api/v1/tasks/{taskId}
GET  /api/v1/tasks/{taskId}/events
POST /api/v1/tasks/{taskId}/cancel
POST /api/v1/tasks/{taskId}/retry
```

## 交付要求

实现代码、迁移脚本和测试。并确保以下内容已落实：

```text
Task 状态机如何实现
Outbox 如何补偿
取消/重试/超时如何处理
测试命令和结果已记录到 docs/PROJECT_STATUS.md
```

