# Phase 4 执行日志：Canonical MemoryRuntime

## 2026-07-18：Shadow Recall 首个只读垂直切片

### 已交付

- 新增 `MemoryRuntime` 作为 canonical recall 的模块边界；当前实现为 `CanonicalMemoryRuntime`。
- 新增公开只读观察接口：

  ```text
  GET /api/v2/workspaces/{workspaceId}/memory/shadow-recall
  ```

  响应同时返回不变的 legacy `chat/QA` control pack 和 runtime pack，`shadow_mode=true`。
- runtime 仅返回同一 workspace 中对当前用户可见的 `USER`/`WORKSPACE` memory，且必须满足：
  - object 为 `ACTIVE` 且已审核；
  - object 的 latest revision 为 `ACTIVE`；
  - revision 在有效时间窗内，且 `scope_status=VALID`。

  因此 superseded、revoked、stale / invalid 或非 current revision 不会进入 runtime pack。
- 此切片不写入 Memory 状态，也没有把 runtime pack 接到 Answer、Research 或 Artifact 的 prompt 编译路径。

### TDD 契约

`backend/src/test/java/com/noteweave/MemoryRuntimeContractTest.java`

1. 空工作区返回空 runtime pack，同时 legacy pack 保持为空且 compiler policy version 不变。
2. 通过既有公开 signal → promotion → append-version 路径创建两代 revision 后，runtime 只返回当前 ACTIVE revision。

验证命令：

```powershell
.\mvnw.cmd -f backend\pom.xml -DforkCount=0 "-Dspring.kafka.listener.auto-startup=false" "-Dtest=MemoryRuntimeContractTest" test
```

结果：`Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`。

## 2026-07-18：Canonical schema、legacy bridge 与 revision review

### 已交付

- `V080__create_canonical_memory_runtime.sql` 新建 `memory_item`、`memory_runtime_revision` 与 `memory_event`，并从已有 object/version 回填可兼容的 canonical projection。
- Shadow Recall 已切换为读取 `memory_item.current_revision_id`，不再直接读取 legacy `memory_object.latest_version_id`。
- `LegacyMemoryRuntimeBridge` 在既有 promotion、append 和 revoke 的同一事务内同步 canonical projection；因此旧 API 的版本切换不会造成 runtime 召回漂移。
- 新增 runtime review seams：

  ```text
  GET  /api/v2/workspaces/{workspaceId}/memory/review
  POST /api/v2/workspaces/{workspaceId}/memory/revisions/{revisionId}/review
  ```

  `MODEL_INFERENCE` 等需审核 candidate 被投影为 `PROPOSED` revision。`ACCEPT` 通过 row lock 在单个事务内将旧 ACTIVE revision 标记为 `SUPERSEDED`，再激活 proposal 并更新 Item current revision；`REJECT` 则仅拒绝 proposal。

### 验证

`MemoryRuntimeContractTest`：`Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`。

### Snapshot 接入

- `RunInputSnapshotService` 现在在 Answer 与 Research 快照的 `snapshot_json` 中写入 `memory_revision_refs`，值来自同一 canonical `MemoryRuntime.recall(...)` 调用。
- 这使每个新 Run 冻结所选 revision ID；尚需补充覆盖实际 Answer/Research 快照读取的专门 HTTP 回放契约。

### 删除传播

- legacy Memory revoke 现在会将 canonical Item 标记为 `DELETED`，并通过 `RunReplayRedactionService` 把引用该 revision 的快照降级为 `METADATA_ONLY`。

### Observation 模块入口

- `MemoryRuntime` 现包含 `observe(ExecutionObservation)`；canonical 实现以 `observation_id` 唯一键去重，首次写入仅产生 `PROPOSED` revision，不会绕过 review 直接激活。
- 这仍是模块入口，尚未暴露新的 HTTP API；下一步应由 Answer/Research/Artifact adapter 提供可信 provenance，并补并发幂等模块测试。
- `MemoryRuntimeContractTest` 已覆盖重复 observation ID：第二次调用返回同一 revision 且 review queue 只包含一个 proposal。
- 同一 `(workspace, owner, scope, scope_ref_key, slot_key)` 的不同 observation 复用同一个 Item，并创建递增的 `PROPOSED` revision；契约测试覆盖该 slot 不变量。
- revision review 在激活前对 Item 加行锁，确保同一 slot 的并发审核串行化，始终最多保留一个 ACTIVE revision。
- Observation 仅接受 `USER_FEEDBACK` 与 `PROJECT_DECISION` provenance；`WEB`、`SOURCE`、`REPORT` 等不可信输入会以 `MEMORY_OBSERVATION_PROVENANCE_FORBIDDEN` 拒绝，契约测试已覆盖。

### 后续切片

1. 将 execution observation 纳入 canonical 写模型，并以 observation ID 去重；当前 candidate projection 是过渡输入。
2. 以 slot-key + CAS 强化多个 proposal 竞争时的 single ACTIVE invariant。
3. 将 Snapshot 的 selected memory revision refs 切换到 runtime，并补齐删除传播与兼容比对。

## 2026-07-18：并发原子性与 scope 收尾

### TDD：同 slot 并发 observation

- Red：两个线程同时向同一 `(workspace, owner, scope, scope_ref_key, slot_key)` 写入不同 observation，第二个线程触发 `uk_memory_item_scope_slot` 唯一键异常。
- Green：`MemoryRuntime.observe(...)` 现在在单一事务中完成 Item 创建、唯一键竞争收敛、Item 行锁、observation 二次幂等检查和 revision 版本号分配。
- 契约结果：并发写入只生成一个 Item，保留两个可独立审查的 `PROPOSED` revision。

### TDD：拒绝未知 scope

- Red：`PROJECT` 等未知 scope 会被静默扩大为 `WORKSPACE`。
- Green：只接受 `USER` 和 `WORKSPACE`；其他值返回 `MEMORY_OBSERVATION_SCOPE_INVALID`，不产生 Item 或 Revision。

### Phase 4 验证

```text
MemoryRuntimeContractTest
Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
```

验证覆盖空 runtime、current ACTIVE recall、review 激活、observation 幂等、同 slot 复用、同 slot 并发、provenance 拒绝和 scope 拒绝。`RunInputSnapshotService` 的 revision refs、legacy bridge 以及 revoke replay redaction 已接入 canonical revision identity；Phase 4 可以关闭，下一阶段进入 Artifact 显式输入。
