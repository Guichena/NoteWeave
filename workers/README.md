# NoteWeave v2 Workers

Python Worker 分别承接 Research 与 Artifact 的独立执行运行时：

- `research-worker`：承接 Deep Research
- `artifact-worker`：承接右侧 Skill-first 产物生成

worker 不直写 Java 业务真源。两类 Worker 均按 `task_id` 拉取 Java 输入并回调进度/完成/失败；Artifact Worker 另具备 run/resume/provider-ack、Skill Graph、Verifier/Repair、可选 LLM、系统 MCP、等待恢复和 PDF 导出链路。正式契约见 `docs/最小接口契约.md`。

当前 Java 主系统已经落下第一批内部承接接口：

- `GET /internal/worker/research-tasks/{taskId}/input`
- `GET /internal/worker/artifact-tasks/{taskId}/input`
- `POST /internal/worker/research-outbox/dispatch`
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
  - `app/callback.py`

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

Research Worker 当前已经具备 `Search / Read / Extract / Verify / Branch / Write` 的轻量闭环，并提供：

- `POST /debug/run-task`：直接提交完整 worker input，用于本地调试。
- `POST /tasks/{task_id}/run`：按 `task_id` 从 Java 拉取 input，执行后依次回调 `progress` 和 `complete`；失败时回调 `fail`。
- `python -m app.kafka_consumer`：消费 `noteweave.research.run` Kafka 消息，按 `task_id` 执行 Research Worker。

Artifact Job 使用 Java 自动调度的 HTTP outbox publisher 调用 `POST /tasks/{task_id}/run`。Worker 在外部 provider 阻塞时进入 waiting，并通过系统 MCP 执行、Java host ack 和 `POST /tasks/{task_id}/resume` 自动恢复。运行态落盘，`/debug/*` 默认关闭。配置 `NOTEWEAVE_INTERNAL_AUTH_TOKEN` 后，Java 与 Worker 双向请求都必须携带 `X-NoteWeave-Internal-Token`；Artifact Worker 的 run/resume/ack/export/internal/debug 控制面均受保护，`/health` 除外。

当前 Research 异步链路为：Java 创建 `research_run` 和 `task`，把待发送消息写入 `task_outbox`；Java 侧 publisher 将 `noteweave.research.run` 消息发布到 Kafka 并标记 `SENT`；Python Research Worker 作为 Kafka consumer 拉取 `task_id`，再通过 Java worker input 接口读取完整输入并回调进度、完成或失败。

Research Kafka consumer 采用手动 offset commit：任务成功后提交 offset；poison message 会按 `NOTEWEAVE_KAFKA_CONSUME_MAX_ATTEMPTS` 有限重试，耗尽后提交 offset 并跳过，避免单条坏消息卡死整个 consumer。调试时可设置 `NOTEWEAVE_KAFKA_CONSUME_RAISE_ON_FAILURE=true` 在提交后抛出错误。

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
conda run -n noteweave-workers uvicorn app.main:app --reload --port 18091 --app-dir workers/research-worker

conda run -n noteweave-workers python -m app.kafka_consumer

conda run -n noteweave-workers uvicorn app.main:app --reload --port 18092 --app-dir workers/artifact-worker
```

## Docker 启动

默认 `docker compose up -d` 只启动 MySQL / Redis / Kafka / MinIO / Elasticsearch。

全容器模式启动 Java Backend、Research Worker API、Research Kafka Consumer 和 Artifact Worker API：

```powershell
docker compose --profile app up --build
```

Research Worker 容器默认配置：

- `NOTEWEAVE_JAVA_BASE_URL=http://backend:8081`
- `NOTEWEAVE_KAFKA_BOOTSTRAP_SERVERS=kafka:9092`
- `NOTEWEAVE_KAFKA_RESEARCH_TOPIC=noteweave.research.run`
- `NOTEWEAVE_KAFKA_CONSUME_MAX_ATTEMPTS=3`
- `NOTEWEAVE_KAFKA_CONSUME_RAISE_ON_FAILURE=false`
- `NOTEWEAVE_INTERNAL_AUTH_TOKEN=<与 Backend/Artifact Worker 相同的共享 secret>`

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
