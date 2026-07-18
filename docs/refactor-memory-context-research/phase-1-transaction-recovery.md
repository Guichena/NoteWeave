# Phase 1 Spec：Turn Submission 三段事务与恢复

## 目标

把当前 `ConversationTurnModule -> ChatService` 的兼容长事务拆成事务 A、事务外编译、事务 B 和事务 C。远程检索、Memory 编译和模型调用不得持有数据库事务；失败和进程退出后，Submission、Message、Run 与活动历史头仍可确定性恢复。

## 已确认测试 Seam

```text
POST /api/v2/conversations/{conversationId}/messages
GET  /api/v2/workspaces/{workspaceId}/turn-submissions/{submissionId}
POST /internal/recovery/turn-submissions/{submissionId}/prepare
```

HTTP seam 验证客户端可见 receipt、稳定错误与最终消息。恢复 seam 是后台恢复器的公开内部边界。测试不调用私有事务方法，不按 SQL 调用次数断言。

## 事务边界

### 事务 A：Prepare

- 创建或读取 `turn_submission`，比较规范化请求 hash；
- 校验 `expected_history_head_message_id`；
- 新一轮创建 UserMessage 与 AssistantPlaceholder；
- 创建 PREPARING AnswerRun 或 ResearchRun；
- 写入不可变 `preparation_json`：请求配置、历史 head、conversation cutoff、selection time、Source 版本引用；
- CAS 推进 Conversation active head；
- 提交事务。

### 事务外：Compile

- 只读取 `preparation_json` 冻结的输入；
- 调用 `ConversationContextCompiler`；
- 不读取 Composer 当前状态补齐旧请求；
- 返回 `SnapshotDraft` 或结构化失败。

### 事务 B：Ready

- 写 `RunInputSnapshot`；
- Run 关联 Snapshot；
- PREPARING → READY/QUEUED；
- 写启动 Outbox；
- Submission 写 READY receipt。

### 事务 C：Complete

- 固化 Evidence/Report；
- 完成 AssistantPlaceholder；
- 使用 attempt/fencing/CAS 迁移 Run 终态；
- 写 completion、Memory observation、summary compaction outbox。

## TDD 场景

### TR-01 编译失败可观察

事务 A 成功后 Context Compiler 失败：Submission 与 Run 为 FAILED，助手消息为 FAILED；GET Submission 返回稳定错误。不得留下不可查询的孤儿行。

### TR-02 相同请求恢复

相同 `clientRequestId + request hash` 重试 FAILED/PREPARING Submission：复用原 Message、Run、history head 和 `preparation_json`，不重新读取当前 Composer 或创建第二组消息。

### TR-03 并发 winner

两个并发相同请求只有一个唯一键 winner；loser 读取 winner receipt。不同 hash 的 loser 返回 `TURN_SUBMISSION_CONFLICT`。

### TR-04 超时恢复器

超过阈值的 PREPARING Submission 可被 lease claim；同一时刻只有一个恢复 owner。恢复成功进入 READY，恢复失败增加 attempt 并记录最后错误。

### TR-05 进程退出点

分别模拟事务 A 后、编译后事务 B 前、事务 B 后 worker 前退出；每个退出点都能通过 Submission 状态和 Outbox 唯一键恢复，且不重复 Message/Run/Snapshot。

## 完成定义

- TR-01～TR-05 自动化通过；
- `ConversationTurnModule` 不再标注覆盖整个提交流程的 `@Transactional`；
- `ChatService.sendMessage()` 不再同时负责消息落账、上下文编译、检索和 Run 创建；
- 远程依赖调用期间数据库连接池 active transaction 不增长；
- Answer 与 Research 的现有 SSE/Task 回归全部通过。

## 执行记录

| 场景 | RED 证据 | GREEN 证据 | 实现结果 |
|---|---|---|---|
| TR-01 编译失败可观察 | 事务 A 后的编译错误只会向调用方返回，账本无法稳定查询 | `failedAnswerPreparationRemainsQueryableWithItsOriginalLedgerIds` | Submission、AnswerRun 与占位消息一起标记 FAILED，并保留错误码与原始 ID |
| TR-02 冻结输入恢复 | 失败重试会重新创建消息或依赖当前请求 | `recoveryReusesFrozenPreparationAndOriginalLedgerIds` | 只读取 `preparation_json`，复用原 Message/Run/history head，递增 attempt |
| TR-03 并发 winner | 并发首次插入的两个请求都可能继续执行 | `concurrentIdenticalSubmissionsReturnTheSingleWinnerReceipt` | 唯一键竞争的 loser 等待并复用 winner receipt |
| TR-04 超时 lease | 新鲜 PREPARING 可被任意恢复器抢占；过期条目可双重恢复 | `recoveryRejectsPreparingSubmissionBeforeItsLeaseIsStale`、`concurrentRecoveryClaimsOneStalePreparationLease` | `V073` 增加数据库时钟驱动的 60 秒 lease；终态写入绑定 lease owner |
| TR-05 事务 A 后退出 | PREPARING 的孤立账本没有可验证恢复路径 | `recoveryAfterProcessExitBetweenPrepareAndReadyKeepsTheOriginalLedger` | 模拟编译前进程退出后，恢复仍完成同一组 Message/Run，且没有重复账本记录 |

验收命令：

```powershell
.\mvnw.cmd -f backend\pom.xml -DforkCount=0 "-Dspring.kafka.listener.auto-startup=false" "-Dtest=ConversationTurnModuleContractTest" test
```

最新结果：14 tests，0 failures，0 errors。Answer 在事务 B 后、实际流式 worker 前的恢复沿用已有 Answer SSE 入口：无活动 stream 的 Run 会被重新 claim；Research 则继续使用已有任务 outbox/worker callback。后续 Phase 2 将把输入快照与执行投递进一步显式化。
