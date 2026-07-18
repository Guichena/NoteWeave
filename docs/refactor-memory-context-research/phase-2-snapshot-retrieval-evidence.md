# Phase 2 Spec：RunInputSnapshot、RetrievalConfig 与 Evidence 固化

## 目标

让每个 AnswerRun 与 ResearchRun 在开始执行前固定可回放的初始输入。Snapshot 记录当轮模式、有效检索配置、活动历史头、消息 cutoff、Grounding、Memory/Segment 版本引用、编译器与 prompt 版本以及 token budget；它不保存最终 Answer Evidence 或 Research Evidence Manifest。

## 公共 seam

```text
POST /api/v2/conversations/{conversationId}/messages
GET  /api/v2/workspaces/{workspaceId}/answer-runs/{answerRunId}
GET  /api/v2/workspaces/{workspaceId}/research-runs/{researchRunId}
GET  /api/v2/workspaces/{workspaceId}/runs/{executionKind}/{runId}/input-snapshot
GET  /api/v2/workspaces/{workspaceId}/answer-runs/{answerRunId}/evidence
GET  /api/v2/workspaces/{workspaceId}/research-runs/{researchRunId}/evidence
```

第四个 seam 是只读回放元数据接口。它只返回已授权且未被删除的 Snapshot；正文被擦除后返回明确 replay availability，而不能用当前 Composer、Source 或 Memory 状态替代。

## 数据与不变量

- `run_input_snapshot` 追加保存，`(execution_kind, run_id)` 唯一；AnswerRun 与 ResearchRun 两种 FK 至多一个非空。
- 事务 B 原子写入 Snapshot、Run 关联与执行投递；PREPARING Run 不得先进入可执行状态。
- `effective_retrieval_config` 由服务端从 TurnMode、strategy、channels、scope、grounding 规范化得到；它成为 request hash 与 Snapshot 的一部分。
- Snapshot 中所有 Version/Content Ref 必须同时满足 owner、workspace、对象类型和有效期校验。
- Answer EvidenceBundle 在回答执行后固化；Research EvidenceManifest 在研究完成后固化。Trace 不能替代 Evidence。
- 所有 worker retry 只读取同一 Snapshot；新输入、重新生成或用户修改研究计划创建新 Run/Revision，绝不原地改 Snapshot。

## TDD 场景

### SR-01 Answer Snapshot 只创建一次

同一 `clientRequestId` 的重复 Answer 提交只产生一个 AnswerRunInputSnapshot，第二个 receipt 指向同一 run。Snapshot 的 mode、history head、query message、retrieval config 和 compiler version 与第一次提交一致。

### SR-02 Composer 变更不影响旧 Run

Answer 在事务 A 后、事务 B 前恢复时，即使后续请求使用不同 mode/source scope，恢复后的 Snapshot 仍等于冻结输入。GET Snapshot 不得读取当前会话默认值或当前 Source 选择。

### SR-03 非法 RetrievalConfig 先失败

`NONE` 携带 channel/scope/grounding、`AUTO` 没有 channel、`EXPLICIT` 没有有效引用时，在创建 Message/Run 前返回稳定 400，且不产生 Submission 孤儿行。

### SR-04 Evidence 是最终实际输入

AnswerRun 完成后，Evidence 记录实际入模片段的顺序、截断、内容 hash、来源/版本引用与抓取时间；仅有候选 ID、分数或 Trace 的记录应被拒绝为不可回放。

### SR-05 删除传播

删除被 Snapshot 引用的正文后，Snapshot 仍可查看元数据，但 replay availability 降为 `METADATA_ONLY` 或 `UNAVAILABLE`；不得把删除后的 ref 替换成新版内容。

### SR-06 Research Snapshot 与最终 Manifest 分离

Deep Research 创建时固定初始 RetrievalConfig/grounding Snapshot；worker 最终 EvidenceManifest 另行关联 ResearchReport。多次 worker attempt 共享初始 Snapshot，不能覆盖它。

## 首个纵向切片

先交付 SR-01：Answer 提交在事务 B 写入一个不可变 Snapshot，并提供只读 GET。该切片不改变现有检索策略；以现有 `retrieval_plan_json` 与 `evidence_bundle_json` 为兼容输入，新增独立 Snapshot 真相源，之后再扩展完整 RetrievalConfig 和 Evidence schema。

## 执行记录

| 日期 | 切片 | RED | GREEN | 结果 |
|---|---|---|---|---|
| 2026-07-18 | SR-01 Answer Snapshot | GET 输入快照返回 500，`run_input_snapshot` 不存在 | `answerSubmissionPersistsOneImmutableInputSnapshot` | `V074` 建立不可变 Snapshot；事务 B 以 AnswerRun 唯一键写入，GET 可读取 mode、历史 head、规范化 retrieval config、编译/prompt 版本和 token budget |
| 2026-07-18 | SR-02 恢复沿用冻结输入（Answer 首切片） | 不适用：复用 TR-05 进程退出场景 | `recoveryAfterProcessExitBetweenPrepareAndReadyKeepsTheOriginalLedger` | 恢复写入同一 AnswerRun 的唯一 Snapshot，仍指向原 query message，不从当前 Composer 补齐 |
| 2026-07-18 | SR-03 RetrievalConfig 提前校验 | `NONE + WORKSPACE` 被忽略并创建了 AnswerRun | `invalidRetrievalConfigFailsBeforeItReservesTheIdempotencyKey` | 无效配置在 Submission 前返回 `RETRIEVAL_CONFIG_INVALID`，同一 clientRequestId 可立即用于合法提交 |
| 2026-07-18 | SR-03 NONE 执行语义 | 不适用：在同一切片实现后补充公开快照断言 | `noneRetrievalConfigPersistsAnEmptyRetrievalPlan` | NONE 写入零步骤 RetrievalPlan 和空 EvidenceBundle，不调用 RetrievalOrchestrator |
| 2026-07-18 | SR-04 Answer Evidence Manifest | 不适用：在 `V075` 和最小持久化路径完成后补充端到端公开契约 | `answerEvidenceManifestPreservesSelectedExcerptAndContentHash` | `V075` 以 AnswerRun + rank 唯一持久化实际选中的证据；公开 evidence 接口返回原始 excerpt、来源引用和 SHA-256 content hash。上传资料→提问→查询 evidence 的完整链路通过。 |
| 2026-07-18 | SR-05 Source 删除传播（Answer） | `deletingSelectedSourceDowngradesReplayAndRedactsEvidenceBody`：删除后 Snapshot 仍为 `FULL` | 同名公共 API 契约 | `RunReplayRedactionService` 在 Source 删除事务内将受影响 Answer Snapshot 降为 `METADATA_ONLY`，并清空 evidence excerpt/原内容 hash/字符成本；保留来源 ID 作为审计元数据。 |
| 2026-07-18 | SR-06 Research 初始 Snapshot | Research GET Snapshot 返回 404 | `deepResearchSubmissionPersistsItsInitialInputSnapshot` | 统一提交事务将 Research preparation、RunInputSnapshot 与 task outbox 一起提交；Snapshot 不包含最终 Report Evidence |

当前兼容 RetrievalConfig 将无 scope 的 Answer 映射为 `AUTO + WORKSPACE`，有显式 source scope 的 QA 映射为 `EXPLICIT + WORKSPACE`。完整请求字段校验和 Research Snapshot 是下一条 TDD 切片。

## 完成定义

- SR-01 至 SR-06 自动化通过；
- 空 H2 与 MySQL 的追加 Flyway 迁移通过；
- Answer/Research 的现有 SSE、worker callback 与导出链路可读取兼容字段；
- 旧 `retrieval_plan_json` / `evidence_bundle_json` 的删除有双读回填和回滚门槛，不能直接移除。
