# NoteWeave v2 Workers

阶段1先提供 Python Worker 的最小工程壳：

- `research-worker`：后续承接 Deep Research
- `artifact-worker`：后续承接右侧产物生成

当前 worker 不直写业务真源，只暴露健康检查与配置装载测试。正式任务输入和结果回传按 `docs/最小接口契约.md` 接入 Java 主系统。

## Python 环境

Python worker 统一优先使用 `conda`，两个 worker 共用一个环境，避免后续 Research / Artifact 各自维护重复依赖。

```bash
conda env create -f workers/environment.yml
conda activate noteweave-workers
```

如果环境已经存在，更新依赖：

```bash
conda env update -f workers/environment.yml --prune
```

## 测试

```bash
cd workers/research-worker
python -m pytest tests

cd ../artifact-worker
python -m pytest tests
```

## 本地启动

```bash
cd workers/research-worker
uvicorn app.main:app --reload --port 18091

cd ../artifact-worker
uvicorn app.main:app --reload --port 18092
```
