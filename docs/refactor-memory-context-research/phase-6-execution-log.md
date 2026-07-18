# Phase 6 执行日志：事件、评测、删除传播与旧路径清理

## 2026-07-18：Artifact 删除传播

- Red：删除 Artifact 已选 Source 后，冻结的 `sample_text/summary` 仍可从 worker input 读取，且没有 replay availability。
- Green：`RunReplayRedactionService` 在 Source 删除事务中重写命中的 `artifact_run_input_snapshot`，保留 Source/Snapshot identity，清空正文并设置 `METADATA_ONLY`。
- Backend worker input 与 Python `ArtifactTaskInput` 均显式携带 `replay_availability`。

## 2026-07-18：Research progress 投影

- Red：conversation-bound Research task progress 只写 task/research 状态，Conversation event replay 为空。
- Green：`WorkerTaskCallbackService` 在 canonical `task_event` 写入后发布 `research.progress` 到 `ConversationEventMux`，run identity 使用 `research_run.id`，run sequence 来自 task event 序列。
- 契约同时验证 progress 不新增 `conversation_message`，因此没有第二条 Research 本地消息路径。

## 2026-07-18：Inspector 与 Run Replay

新增公开只读入口：

```text
GET /api/v2/workspaces/{workspaceId}/context-inspector/runs/{executionKind}/{runId}
GET /api/v2/workspaces/{workspaceId}/memory/inspector
GET /api/v2/workspaces/{workspaceId}/run-replay/{executionKind}/{runId}
```

- Context Inspector 从冻结 RunInputSnapshot 展示 summary/message/memory revision identities。
- Memory Inspector 组合 canonical `MemoryRuntime.recall` 与 review queue。
- Run Replay 只返回 frozen snapshot 和 replay availability，不重新编译当前上下文。
- 所有入口复用 workspace permission / snapshot ownership 校验。

## 2026-07-19：Canonical Memory production cutover

- `V082__cut_over_memory_compiler_to_canonical_runtime.sql` 为 usage log 增加 canonical Item/Revision identity，回填 legacy payload，并把 outcome lifecycle 字段迁到 `memory_item`。
- `MemoryCompilerService` 的 prompt compilation 只查询 `memory_item` 与 `memory_runtime_revision`；usage log 同时记录 canonical identity。
- `MemoryOutcomeService` 和 review 路径以 canonical Item/Revision 为决策真相，legacy object 仅保留兼容双写。
- `ArchitectureBoundaryTest.productionMemoryCompilerMustNotReadLegacyMemoryTables` 禁止 compiler 重新引入 `from/join memory_object` 或 `memory_version`。

## 2026-07-19：Research Report typed-ref 删除传播

- Red：删除 Research Report 后，下游 Artifact 的 typed upstream ref 仍保持 `FULL` replay。
- Green：`RunReplayRedactionService` 同时解析 `source_scope_snapshot_json`、`upstream_refs_json`、`SOURCE_SNAPSHOT`、`RESEARCH_REPORT` 以及 generated report Source identity，命中的 Artifact snapshot 清空正文并降为 `METADATA_ONLY`。
- 契约：`deletingResearchReportMustDowngradeArtifactReplayThroughTypedUpstreamRef`。

## 2026-07-19：Research message ownership 与旧 result route 退役

- `ConversationResearchProjectionService` 成为 Research report card/message projection 的 Conversation-owned writer。
- `ArchitectureBoundaryTest.researchModuleMustNotWriteConversationMessagesDirectly` 禁止 Research 模块直接 `insert/update conversation_message`。
- 三条 split-result legacy route 对 atomic、legacy non-snapshot、unknown 和 invalid task 全部拒绝：

  ```text
  POST /internal/research-agent/workspace-evidence-batches
  POST /internal/research-agent/candidate-batches
  POST /internal/research-agent-tasks/{taskId}/submit
  ```

  返回 `RESEARCH_AGENT_ATOMIC_COMPLETION_REQUIRED`，canonical `/complete` 是唯一结果写入口；claim、heartbeat 与 completion 不受 guard 影响。`ResearchAgentLegacyResultRouteGuardTest`：11/11。

## 2026-07-19：Context 回归修复

- Red：完成两轮 QA 后追问“第二点为什么重要”，检索没有使用 frozen recent raw tail，错误返回无足够依据。
- 根因：`ChatService.buildConversationTurns` 逆序遍历 oldest-first history，把 USER 错配到前一条 ASSISTANT。
- Green：按 message sequence 正向配对，QA/Note/Wiki 的指代追问恢复主题锚点；没有 READY SegmentSummary 时继续使用 bounded raw tail，不临时重建旧摘要。

## Phase 6 九个切片审计

| Slice | 完成证据 |
|---|---|
| 1. Artifact Source 删除 | `deletingArtifactSourceMustRedactFrozenBodyAndDowngradeReplay` |
| 2. Research Report 删除 | `deletingResearchReportMustDowngradeArtifactReplayThroughTypedUpstreamRef` |
| 3. Research progress SSE | `researchProgressMustProjectToConversationEventStreamWithoutCreatingMessages` |
| 4. Cross-workspace 隔离 | `foreignWorkspaceMustNotReadRunSnapshotsInspectorsReplayOrConversationEvents`，Artifact foreign-source contract |
| 5. Memory 投毒 | `webSourceAndReportPromptInjectionMustNotWriteMemory` |
| 6. Inspector / Replay | `runtimeInspectorsShouldExposeCanonicalMemoryAndFrozenRunInput` |
| 7. Architecture gates | canonical compiler gate + Research message ownership gate，29/29 |
| 8. Legacy route cleanup | split-result routes 全 schema 拒绝，11/11；legacy Memory tables 不再被 production prompt compiler 读取 |
| 9. Focused gates | deletion/replay/auth/poisoning 包含于 backend contracts；worker regressions 与 Docker performance gate 见下 |

## 最终验证记录

```text
Backend Maven（最终工作树）
Tests: 637, Failures: 0, Errors: 0, Skipped: 11

Research worker
377 passed

Artifact worker
208 passed

Frontend
97 passed；production build PASS；UI contract PASS

Flyway
空 MySQL 8.4 临时库从 V001 迁移到 V083，installed rank 66；V082 canonical Memory cutover 与 V083 Research task priority 均成功。验证库、临时 jar 和验证进程已清理。

Performance gate（Docker，SkipColdStart）
第一次：稳定态 p95 通过；闲置容器首请求预热超阈值
  API p95 68.58 ms；ES p95 263.28 ms
第二次预热后：全部阈值通过
  first API 55.87 ms；API p95 42.50 ms
  first ES 32.65 ms；ES p95 82.44 ms
```

性能原始证据：

- `target/phase6-performance-gate/phase0-performance-20260719-004004.json`
- `target/phase6-performance-gate/phase0-performance-20260719-004021.json`
