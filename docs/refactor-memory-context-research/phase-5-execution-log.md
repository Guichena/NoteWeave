# Phase 5 执行日志：Artifact 显式输入

## 2026-07-18：显式 Source Scope

### TDD Red/Green

- Red：Artifact 创建请求未携带 scope 时，旧实现调用 `loadReadySourceScope(workspaceId)`，把 workspace 内 READY Source 自动装入 worker input。
- Green：`CreateArtifactJobRequest` 增加 `source_scope_source_ids`，缺省规范化为空列表；创建流程只持久化请求明确给出的 Source ID，已删除隐式 workspace-wide 查询。
- Red：跨 workspace Source ID 能创建任务并返回 `200`。
- Green：Source scope 在 task/outbox 写入前校验 workspace、READY、PARSED/INDEXED 状态，失败返回 `ARTIFACT_SOURCE_SCOPE_INVALID`。

## 2026-07-18：Artifact RunInputSnapshot 与 upstream refs

- 新增 `V081__create_artifact_run_input_snapshot.sql`，为每个 Artifact run 建立不可变输入快照；旧 run 使用兼容快照回填。
- 创建时将 Source 条目、具体 `source_snapshot_id`、版本号和 SHA-256 写入快照；worker input 读取冻结 JSON，不再回查可变 Source 内容。
- `ArtifactWorkerInputResponse` 暴露 `input_snapshot_id` 和 `upstream_refs`。
- 支持 `SOURCE_SNAPSHOT` 与 `RESEARCH_REPORT` 两类 upstream ref，必须在同一 workspace 命中指定 revision；无效引用在 task 创建前拒绝。
- `RESEARCH_REPORT` 还必须联表命中真实 canonical `research_run`，不能只依赖可伪造的 Source metadata。
- regeneration 复用原 `input_snapshot_id`，不重新发现 Source。
- Artifact worker 的正式 Pydantic 输入模型保留 `input_snapshot_id` 与 typed `upstream_refs`，不再把它们当作额外字段丢弃。

## 验证

```text
Phase6ResearchArtifactContractTest
Backend target contracts: Tests run: 43, Failures: 0, Errors: 0, Skipped: 9
Artifact worker model contracts: 13 passed
```

覆盖缺省 scope 为空、显式 scope、late Source 隔离、跨 workspace 拒绝、Source Snapshot identity、upstream ref 回传、伪造 Research Report provenance 拒绝、regeneration snapshot 复用、worker callback/version lifecycle 和现有 Research/Artifact 契约。Phase 5 已完成；Source/Report 删除后的 snapshot redaction 进入 Phase 6 删除传播切片。
