# NoteWeave v2 Workers

阶段1先提供 Python Worker 的最小工程壳：

- `research-worker`：后续承接 Deep Research
- `artifact-worker`：后续承接右侧产物生成

当前 worker 不直写业务真源。Research Worker 已支持按 `task_id` 拉取 Java 主系统输入、执行内部研究 loop，并把进度和结果回调给 Java；Artifact Worker 当前仍以本地执行骨架为主。正式任务输入和结果回传按 `docs/最小接口契约.md` 接入 Java 主系统。

当前 Java 主系统已经落下第一批内部承接接口：

- `GET /internal/worker/research-tasks/{taskId}/input`
- `GET /internal/worker/artifact-tasks/{taskId}/input`
- `POST /internal/worker/research-outbox/dispatch`
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
  - `app/verifier.py`
  - `app/runner.py`

Research Worker 当前已经具备 `Search / Read / Extract / Verify / Branch / Write` 的轻量闭环，并提供：

- `POST /debug/run-task`：直接提交完整 worker input，用于本地调试。
- `POST /tasks/{task_id}/run`：按 `task_id` 从 Java 拉取 input，执行后依次回调 `progress` 和 `complete`；失败时回调 `fail`。
- `python -m app.kafka_consumer`：消费 `noteweave.research.run` Kafka 消息，按 `task_id` 执行 Research Worker。

Artifact Worker 当前仍只做“可验证的占位 loop”，暂时不直接消费 Kafka。

当前 Research 异步链路为：Java 创建 `research_run` 和 `task`，把待发送消息写入 `task_outbox`；Java 侧 publisher 将 `noteweave.research.run` 消息发布到 Kafka 并标记 `SENT`；Python Research Worker 作为 Kafka consumer 拉取 `task_id`，再通过 Java worker input 接口读取完整输入并回调进度、完成或失败。

Research Kafka consumer 采用手动 offset commit：只有任务执行成功并完成 Java 回调后才提交 offset；失败时不提交，让消息保留重试空间。

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

## 本地启动

```powershell
conda run -n noteweave-workers uvicorn app.main:app --reload --port 18091 --app-dir workers/research-worker

conda run -n noteweave-workers python -m app.kafka_consumer

conda run -n noteweave-workers uvicorn app.main:app --reload --port 18092 --app-dir workers/artifact-worker
```
