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

## 当前验证

```text
MemoryRuntimeContractTest + Phase6ResearchArtifactContractTest
Tests run: 46, Failures: 0, Errors: 0, Skipped: 9

新增定向契约：
- Artifact Source deletion redaction: PASS
- Research progress conversation projection: PASS
- Runtime inspectors and frozen replay: PASS

Artifact worker model contracts: 13 passed
```

## 剩余收尾

1. 将 production prompt compilation 从 `memory_object/memory_version` 切到 canonical `memory_item/memory_runtime_revision`。
2. 增加 architecture gate，禁止 Answer/Research/Artifact 新增 legacy Memory read/write。
3. 汇总并执行跨 workspace、投毒、删除传播、输入回放与性能门禁。
4. 完成旧 Memory writer / Research legacy result route 的删除或明确退役。
