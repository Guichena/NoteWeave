# Phase 0/1 Spec：Conversation Turn 与 Research 接入

## 目标

在不重写现有 Answer/Research Executor 的前提下，建立统一 `ConversationTurnModule`，使普通回答和 Deep Research 都从 Conversation 的消息账本发起，并由 `turn_submission` 统一拥有 `clientRequestId` 幂等语义。

## 已确认测试 Seam

只通过以下公开边界验证行为：

```text
POST /api/v2/conversations/{conversationId}/messages
ConversationTurnModule.submitNewTurn(SubmitTurnCommand) -> TurnReceipt
```

HTTP seam 验证调用者可见的 receipt、冲突码和消息重载结果。Module seam 用于覆盖 HTTP 不适合稳定触发的事务恢复场景。测试不 mock 自有策略、Repository 或 Executor；外部模型、Kafka 和时钟可在系统边界替换。

## 命令与回执

第一阶段先支持：

```text
SubmitTurnCommand
  conversationId
  content
  requestedTurnMode: QA | NOTE | WIKI | DEEP_RESEARCH
  sourceScope[]
  clientRequestId
  expectedHistoryHeadMessageId?
  researchOptions?

TurnReceipt
  submissionId
  executionKind: ANSWER | RESEARCH
  queryMessageId
  answerMessageId
  answerRunId? / researchRunId?
  status
  reused
```

`DEEP_RESEARCH` 映射为 `RESEARCH`；其他模式映射为 `ANSWER`。本阶段沿用已有回答模式策略和 Research Task 轮询，不统一两种 Run 状态机，也不重写 SSE。

## Golden Scenarios

### GS-01 Answer 提交幂等

同一用户、Conversation、`clientRequestId` 和相同规范化请求提交两次：返回同一个 Submission、UserMessage、AssistantMessage 和 AnswerRun；第二次 `reused=true`，消息序号不增长。

### GS-02 幂等键冲突

同一用户、Conversation、`clientRequestId` 携带不同 content、mode、scope 或 research options：返回 HTTP 409 和 `TURN_SUBMISSION_CONFLICT`，不创建新消息或 Run。

### GS-03 每轮切换模式

同一 Conversation 可依次提交 `WIKI -> QA -> DEEP_RESEARCH -> NOTE`。每条 Submission 固化自己的 TurnMode 和 ExecutionKind，修改下一轮默认值不改变历史 Run。

### GS-04 Research 消息归属

提交 `DEEP_RESEARCH` 会创建 UserMessage、PENDING AssistantMessage 和 ResearchRun；三者属于同一 Workspace/Conversation，Run 通过 `query_message_id`、`answer_message_id` 关联消息。

### GS-05 Research 完成投影

Worker 终态回调只更新自己 Run 对应的助手占位消息。旧 attempt、重复 callback 或取消后的 callback 不得覆盖当前消息和终态。

### GS-06 活动历史头 CAS

提交时提供的 `expectedHistoryHeadMessageId` 与当前活动头不一致则返回 `CONVERSATION_HEAD_CONFLICT`；失败提交不产生孤儿消息或 Run。

### GS-07 会话恢复

Workspace 下可列出 Conversation，重新加载消息后能够看到 Answer 和 Research 消息；Research 专用页面读取同一账本，不维护第二套聊天数据。

## 数据约束

- `turn_submission` 唯一键：`(actor_user_id, conversation_id, client_request_id)`。
- 相同 key、相同 `request_payload_hash` 返回原 receipt；相同 key、不同 hash 报 409。
- Submission 的 `execution_kind` 与 Run FK 一致，最多一个 Run FK 非空。
- Message 正文提交后不可原地编辑；占位消息只允许由关联 Run 的受控完成路径填充。
- `conversation.active_head_message_id` 通过 `lock_version`/CAS 更新。
- Phase 1 的迁移只追加列和表，不删除旧入口需要的数据。

## TDD 纵向切片

1. RED/GS-01：重复 Answer HTTP 请求当前会创建两组消息和 Run。
2. GREEN/GS-01：增加 `turn_submission` 与 Answer receipt 复用。
3. RED/GS-02：相同 key 的不同请求当前没有冲突语义。
4. GREEN/GS-02：规范化请求 hash + 409 冲突。
5. RED/GREEN/GS-04：Deep Research 从统一入口创建消息和关联 Run。
6. RED/GREEN/GS-05：Research callback 以 attempt/fencing 更新占位消息。
7. RED/GREEN/GS-06：活动历史头 CAS。
8. RED/GREEN/GS-07：Conversation 列表、消息重载和前端恢复。

## Phase 1 完成定义

- GS-01 至 GS-07 全部自动化通过。
- Flyway 在空 H2/MySQL schema 与当前 schema 上均可迁移。
- 现有 Answer SSE、Research Task 轮询和 Worker callback 回归通过。
- 新提交不再绕过 `turn_submission`；旧 Research 创建入口有明确兼容期限或内部转发。
- 验收记录包含每个切片的 RED 失败原因、GREEN 命令和剩余风险。

## 执行记录

| 日期 | 切片 | RED | GREEN | 备注 |
|---|---|---|---|---|
| 2026-07-17 | Spec 与 seam 固定 | 不适用 | 完成 | 后续测试限于本 spec 的公开 seam |
| 2026-07-17 | GS-01 Answer 幂等 | 重复请求缺少 Submission 回执并创建不同消息/Run | `ConversationTurnModuleContractTest` 通过 | `V060` 增加统一提交账本；旧 `ChatService` 暂作 Answer 兼容执行器 |
| 2026-07-17 | GS-02 幂等冲突 | 相同 key、不同 payload 返回 200 | 返回 409 / `TURN_SUBMISSION_CONFLICT` | 请求哈希覆盖 content、mode 与 source scope |
| 2026-07-17 | GS-04 Research 消息归属（创建侧） | `DEEP_RESEARCH` 被旧 AnswerMode 拒绝并返回 400 | 统一入口返回 Research receipt，并创建 UserMessage、AssistantPlaceholder、ResearchRun 关联 | Worker 完成后投影占位消息仍属于后续切片 |
| 2026-07-18 | GS-06 活动历史头 CAS | 旧 head 仍可追加并返回 200 | stale head 返回 409 / `CONVERSATION_HEAD_CONFLICT` | `V070` 增加 active head、lock version、reply 链、context status 与 content hash |
| 2026-07-18 | GS-07 会话恢复 | Conversation GET 返回 500，消息重载入口不存在 | Workspace Conversation 列表与按序消息重载通过 | Answer 与 Research 共享同一消息账本 |
| 2026-07-18 | GS-05 Research 完成投影 | Run 已完成但助手消息仍为 PENDING | 完成事务写报告卡并切换消息为 CURRENT | 报告正文仍以 ResearchRun 为真相源 |
| 2026-07-18 | GS-05 Callback fencing | 旧 `(attempt=0, token=0)` callback 返回 200 | 返回 409 / `RESEARCH_CALLBACK_FENCED` | `V071` 增加 attempt/token，callback event 进入唯一回执账本 |
| 2026-07-18 | GS-03 每轮模式切换 | 特征合同直接为绿 | WIKI → QA → DEEP_RESEARCH → NOTE 顺序可重载 | Run 类型按轮固化 |
| 2026-07-18 | TR-01 ～ TR-05 三段事务恢复 | 编译失败不可稳定查询；并发恢复会双重领取 | `ConversationTurnModuleContractTest` 14/14 | 事务 A/编译/事务 B 已拆开；`V072` 冻结输入、`V073` 恢复 lease |

基线说明：首次组合基线命令超过 60 秒且未生成新报告，未判定为通过或失败。使用 `-DforkCount=0` 后，空 H2 schema 成功执行 51 个 Flyway migration，并完成以下回归：

- `ConversationTurnModuleContractTest`：8/8；
- `ChatControllerDispatchTest`：1/1；
- `ArchitectureBoundaryTest`：27/27；
- `Phase1And2ContractTest`：12/12；
- `Phase6ResearchArtifactContractTest`：32/32。

最新集中回归共 80 个测试，全部通过。Maven 在报告生成后会因现有后台 producer 关闭延迟而滞留，验收以最新 Surefire 报告为准并终止明确的遗留测试进程。

## 当前剩余工作

- 前端 Composer 接入 `DEEP_RESEARCH`、`expected_history_head_message_id`、Conversation 列表与消息重载；Research 页面改为同账本专用视图。
- Phase 2：固化 RunInputSnapshot、完整 RetrievalConfig 与 Evidence 版本引用。
- 把 Research profile/options 加入 `SubmitTurnCommand`；当前统一入口使用 `balanced` 兼容默认值。
