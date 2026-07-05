# NoteWeave v2 Workers

阶段1先提供 Python Worker 的最小工程壳：

- `research-worker`：后续承接 Deep Research
- `artifact-worker`：后续承接右侧产物生成

当前 worker 不直写业务真源，只暴露健康检查与配置装载测试。正式任务输入和结果回传按 `docs/最小接口契约.md` 接入 Java 主系统。

当前 Java 主系统已经落下第一批内部承接接口：

- `GET /internal/worker/research-tasks/{taskId}/input`
- `GET /internal/worker/artifact-tasks/{taskId}/input`
- `POST /internal/worker/tasks/{taskId}/heartbeat`
- `POST /internal/worker/tasks/{taskId}/progress`
- `POST /internal/worker/tasks/{taskId}/complete`
- `POST /internal/worker/tasks/{taskId}/fail`

当前 Python worker 也已经有最小执行骨架：

- `research-worker`
  - `app/models.py`
  - `app/planner.py`
  - `app/reporter.py`
  - `app/runner.py`

- `artifact-worker`
  - `app/models.py`
  - `app/compiler.py`
  - `app/verifier.py`
  - `app/runner.py`

这两条链路当前都只做“可验证的占位 loop”，不直接访问业务真源，也不直接消费 Kafka；它们的职责是先把输入 schema、阶段推进和最终结果 schema 固定下来。

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

conda run -n noteweave-workers uvicorn app.main:app --reload --port 18092 --app-dir workers/artifact-worker
```
