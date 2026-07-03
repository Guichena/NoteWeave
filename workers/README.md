# NoteWeave v2 Workers

阶段1先提供 Python Worker 的最小工程壳：

- `research-worker`：后续承接 Deep Research
- `artifact-worker`：后续承接右侧产物生成

当前 worker 不直写业务真源，只暴露健康检查与配置装载测试。正式任务输入和结果回传按 `docs/最小接口契约.md` 接入 Java 主系统。
