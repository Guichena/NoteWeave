# NoteWeave v2 Workers

Python Worker 分别承接 Research 与 Artifact 的独立执行运行时：

- `research-worker`：承接 Deep Research
- `artifact-worker`：承接右侧 Skill-first 产物生成

worker 不直写 Java 业务真源。Research Worker 只消费 canonical agent-command，并通过 claim、heartbeat、completion envelope 与 Java 协调；Artifact Worker 按 `task_id` 拉取输入并回调进度/完成/失败，另具备 run/resume/provider-ack、Skill Graph、Verifier/Repair、可选 LLM、系统 MCP、等待恢复和 PDF 导出链路。正式契约见 `docs/API与事件契约-v2.md` 的 Worker 最小契约章节。

当前 Java 主系统已经落下第一批内部承接接口：

- `GET /internal/worker/artifact-tasks/{taskId}/input`
- `POST /internal/worker/artifact-outbox/dispatch`（保留人工运维入口，正常路径自动调度）
- `POST /internal/worker/tasks/{taskId}/heartbeat`
- `POST /internal/worker/tasks/{taskId}/progress`
- `POST /internal/worker/tasks/{taskId}/complete`
- `POST /internal/worker/tasks/{taskId}/fail`

当前 Python worker 也已经有最小执行骨架：

- `research-worker`
  - `app/models.py`
  - `app/planner.py`
  - `app/search.py`
  - `app/reader.py`
  - `app/extractor.py`
  - `app/branch.py`
  - `app/state.py`
  - `app/verifier.py`
  - `app/reporter.py`
  - `app/runner.py`
  - `app/agent_kafka_consumer.py`
  - `app/agent_task_client.py`

- `artifact-worker`
  - `app/models.py`
  - `app/compiler.py`
  - `app/generation_runtime.py`
  - `app/skill_graph.py`
  - `app/capability_wait_queue.py`
  - `app/acquisition_runtime.py`
  - `app/system_mcp_executor.py`
  - `app/export_runtime.py`
  - `app/verifier.py`
  - `app/runner.py`

Research Worker 当前已经具备 `Search / Read / Extract / Verify / Branch / Write` 的轻量闭环，并提供 canonical agent-command consumer：

- `python -m app.agent_kafka_consumer`：消费 `noteweave.research.agent.command`，按 lease/fencing 契约执行增量 cell task。

Artifact Job 使用 Java 的 MySQL Outbox 保存投递意图，由 `KafkaArtifactOutboxPublisher` 发布最小化的 `artifact-command.v1` 命令到 `noteweave.artifact.job`。Artifact Worker 关闭自动提交且每次只拉取一条命令，先携带 Delivery Token 向 Java 读取冻结输入，再执行模型、MCP 和导出；只有 Complete、Fail 或 Waiting 协议已经由 Java 确认，或旧 Delivery 被 409 Fencing 拒绝后，才手动提交 Kafka Offset。Worker 在外部 provider 阻塞时进入 Waiting，并通过系统 MCP 执行、Java host ack 和 `POST /tasks/{task_id}/resume` 自动恢复。运行态落盘，`/debug/*` 默认关闭。生产环境使用 `NOTEWEAVE_INTERNAL_AUTH_TOKEN`、`NOTEWEAVE_RESEARCH_INTERNAL_AUTH_TOKEN` 和 `NOTEWEAVE_ARTIFACT_INTERNAL_AUTH_TOKEN` 分域认证；Java 与 Worker 双向 HTTP 控制面请求都必须携带 `X-NoteWeave-Internal-Token`，Artifact Worker 的 resume/ack/export/internal/debug 控制面均受保护，`/health` 除外。

当前 Research 异步链路为：Java coordinator 创建 agent task 与 command outbox，dispatcher 发布 `noteweave.research.agent.command`；Python consumer claim task、续约 heartbeat，并用 completion envelope 原子提交结果。consumer 手动提交 offset，poison message 按 `NOTEWEAVE_KAFKA_CONSUME_MAX_ATTEMPTS` 有限重试后进入专用 DLQ。

## Python 环境

Python worker 统一优先使用 `conda`，两个 worker 共用一个环境，避免后续 Research / Artifact 各自维护重复依赖。

```powershell
.\workers\scripts\setup-workers-conda.ps1
```

如果环境已经存在，更新依赖：

```powershell
.\workers\scripts\setup-workers-conda.ps1 -Update
```

## 测试

```powershell
.\workers\scripts\test-workers.ps1
```

`Gate 1` Deep Research smoke harness：

```powershell
.\workers\scripts\run-gate1-smoke.ps1
```

## 本地启动

```powershell
conda run -n noteweave-workers python -m app.agent_kafka_consumer

conda run -n noteweave-workers uvicorn app.main:app --reload --port 18092 --app-dir workers/artifact-worker
```

## Docker 启动

默认 `docker compose up -d` 只启动 MySQL / Redis / Kafka / MinIO / Elasticsearch。

全容器模式启动 Java Backend、Research Agent Consumer 和 Artifact Worker API：

```powershell
docker compose --profile app up --build
```

Research Worker 容器默认配置：

- `NOTEWEAVE_JAVA_BASE_URL=http://backend:8081`
- `NOTEWEAVE_KAFKA_BOOTSTRAP_SERVERS=kafka:9092`
- `NOTEWEAVE_KAFKA_RESEARCH_AGENT_TOPIC=noteweave.research.agent.command`
- `NOTEWEAVE_KAFKA_RESEARCH_AGENT_DLQ_TOPIC=noteweave.research.agent.command.dlq`
- `NOTEWEAVE_KAFKA_CONSUME_MAX_ATTEMPTS=3`
- `NOTEWEAVE_RESEARCH_INTERNAL_AUTH_TOKEN=<Research Worker 专用 secret>`
- `NOTEWEAVE_ARTIFACT_INTERNAL_AUTH_TOKEN=<Artifact Worker 专用 secret>`
- `NOTEWEAVE_INTERNAL_AUTH_TOKEN=<Backend coordinator secret>`

三条 LLM 配置链路彼此隔离：Backend 使用 `NOTEWEAVE_LLM_*`，Research Worker 只使用 `NOTEWEAVE_RESEARCH_LLM_*`，Artifact Worker 只使用 `NOTEWEAVE_ARTIFACT_LLM_*`。Worker 不再读取通用 `NOTEWEAVE_LLM_*`。

如果要接真实 Research LLM，在启动前设置：

```powershell
$env:NOTEWEAVE_RESEARCH_LLM_API_KEY="..."
$env:NOTEWEAVE_RESEARCH_LLM_BASE_URL="..."
$env:NOTEWEAVE_RESEARCH_LLM_MODEL="..."
docker compose --profile app up --build
```

Artifact Worker 使用同名的 `NOTEWEAVE_ARTIFACT_LLM_API_KEY`、`NOTEWEAVE_ARTIFACT_LLM_BASE_URL`、`NOTEWEAVE_ARTIFACT_LLM_MODEL`。System MCP 子进程总超时由 `NOTEWEAVE_MCP_PROCESS_TIMEOUT_SECONDS` 控制，默认 3600 秒。

Research Worker 默认每个任务最多向模型发送 64 次请求；可用 `NOTEWEAVE_RESEARCH_LLM_MAX_TOTAL_CALLS` 调整，设为 `0` 表示不启用该保护上限。
