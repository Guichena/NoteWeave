# 阶段 5：统一 Evidence Ownership 防线验收

- 日期：2026-07-14
- 范围：QA/Note Passage、Wiki Knowledge Version、Orchestrator 最终准入
- 错误码：`EVIDENCE_SCOPE_VIOLATION`

## 原缺口

原 `EvidenceScopeGuard` 只验证 Evidence identity 是否完整、`accessScope` 字符串是否与 ID 一致，以及 QA 显式 Source scope：

- QA Passage 在专用 hydrate 中已有数据库 ownership 复核；
- Note Passage 与 Wiki Knowledge Version 主要依赖各自查询正确；
- 任意 retriever 若返回格式正确但属于其他 Workspace 的 item/version 或 source/snapshot/passage，统一 guard 无法独立识别；
- 阶段验收项“QA/Note/Wiki scope 和权限过滤均有越权测试”缺少最后一道跨模式证明。

## 已完成实现

### Port 与 Guard

- 新增 `EvidenceOwnershipPort`，以稳定 `EvidenceIdentity` 表达 Passage 或 Knowledge Version 身份。
- 新增 `EvidenceOwnershipGuard`，由 Orchestrator 在结构 scope guard 后执行。
- 任一 identity 未被数据库证明为当前 Workspace 的 current evidence 时，整步 fail closed。
- 错误继续复用 `EVIDENCE_SCOPE_VIOLATION` 和 `noteweave.retrieval.scope_violation{mode,channel}`，不增加高基数标签。

### JDBC 批量复核

`JdbcEvidenceOwnershipAdapter`：

- Passage：校验 workspace、Source READY/INDEXED、Snapshot 为最新 indexed version、Snapshot INDEXED、Passage PROJECTED，并核对 source/snapshot/passage 三元组。
- Knowledge：校验 workspace、item ACTIVE、version 等于 `knowledge_item.latest_version_id`，并核对 item/version 二元组。
- 无论输入数量，Passage 与 Knowledge 各一次批量 SQL，单 step 最多两次查询；空类型不查询。

### 兼容边界

- QA 原 ownership hydrate 保留，作为 ES hit 早期 fail-closed；统一 guard 是所有模式共享的最终防线。
- 无 Evidence 的拒答路径不执行数据库查询。
- 测试用 Orchestrator 兼容构造器只信任已经由 fixture 验证的 evidence；生产 Spring 构造器强制注入真实 ownership guard。

## 自动化证据

- `JdbcEvidenceOwnershipAdapterTest`：100 Passage + 100 Knowledge identities 固定两次 SQL，并断言 current Snapshot/latest Version 条件。
- `Phase1And2ContractTest.retrievalHydrationShouldExcludeSupersededSourceSnapshots`：
  - 当前 Workspace 的 current Passage + current Knowledge Version 通过；
  - 相同证据以另一个 Workspace 上下文校验时返回 `EVIDENCE_SCOPE_VIOLATION`；
  - 同 Workspace 的历史 Snapshot Passage 同样被拒绝。
- `Phase3NoteWikiContractTest`：真实 Note/Wiki 策略产生的 Evidence 均通过统一 ownership guard，正文、citation、latest version 行为不变。
- 针对性 Backend：46/46 通过。
- 完整 Backend：`214/214` 通过，0 failure、0 error、0 skipped；Flyway 32 个迁移；`ArchitectureBoundaryTest` 13/13。
- `git diff --check` 与本轮 Java trailing whitespace 检查通过。

## 结论

三种模式不再只相信 retriever 的 scope 字符串或查询实现；EvidenceBundle 准入具备统一、批量、current-version aware 的数据库 ownership 证明。
