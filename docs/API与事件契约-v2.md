# NoteWeave v2 API 与事件契约

> 文档状态：DRAFT / 目标契约
>
> 权威等级：L4
>
> 最后核对日期：2026-07-12
>
> 迁移方案：`改造计划/NoteWeave-v2现有系统适配与改造实施方案.md`

## 1. 契约原则

- 用户 API、内部 Worker API、Kafka Event 分域；
- 普通 QA 返回 AnswerRun，不强制创建 Execution；
- Research、Artifact、Source 等长任务返回 Execution；
- 所有 Mutation 支持客户端 requestId 或 Idempotency-Key；
- Worker callback 强制 attemptId 和 idempotencyKey；
- JSON 字段统一 snake_case；
- 事件使用统一 Envelope；
- 大 payload 通过 Object Locator 传递；
- Redis Streams 是 SSE 传输实现，不进入对外业务契约。

## 2. 通用响应

成功：

```json
{
  "success": true,
  "data": {},
  "correlation_id": "uuid"
}
```

失败：

```json
{
  "success": false,
  "error": {
    "code": "SOURCE_NOT_FOUND",
    "message": "资料不存在",
    "details": {}
  },
  "correlation_id": "uuid"
}
```

## 3. Answer API

### 3.1 创建回答

```http
POST /api/v2/conversations/{conversation_id}/answer-runs
Idempotency-Key: client-generated-key
```

```json
{
  "mode": "QA",
  "content": "问题正文",
  "options": {
    "allow_async_escalation": true,
    "source_scope_ids": []
  }
}
```

响应：

```json
{
  "answer_run_id": "uuid",
  "user_message_id": "uuid",
  "assistant_message_id": "uuid",
  "execution_id": null,
  "execution_mode": "INLINE",
  "status": "CREATED",
  "event_url": "/api/v2/answer-runs/{id}/events"
}
```

若升级为异步：

```json
{
  "answer_run_id": "uuid",
  "execution_id": "uuid",
  "execution_mode": "ASYNC",
  "status": "PENDING",
  "event_url": "/api/v2/executions/{id}/events"
}
```

### 3.2 查询 AnswerRun

```http
GET /api/v2/answer-runs/{answer_run_id}
```

至少返回：

- mode；
- status；
- retrievalPlanVersion；
- maximumOutputTokens；
- retrievalDegraded；
- retrievalDegradationReasons；
- latest revision；
- citation summary；
- executionId（可空）；
- createdAt/completedAt；
- errorCode。

### 3.3 重新生成

```http
POST /api/v2/answer-runs/{answer_run_id}/regenerations
Idempotency-Key: ...
```

重新生成创建新 AnswerRun 或新明确 revision attempt，不能覆盖历史正文。

## 4. Execution API

### 4.1 查询长任务

```http
GET /api/v2/executions/{execution_id}
```

```json
{
  "execution_id": "uuid",
  "execution_type": "RESEARCH_RUN",
  "target_type": "RESEARCH_RUN",
  "target_id": "uuid",
  "status": "RUNNING",
  "active_attempt_id": "uuid",
  "progress": {
    "phase": "VERIFYING",
    "percent": 65,
    "message": "正在验证证据"
  },
  "wait_context": null,
  "result_ref": null,
  "error": null,
  "version": 7
}
```

### 4.2 取消

```http
POST /api/v2/executions/{execution_id}/cancel
Idempotency-Key: ...
```

### 4.3 重试

```http
POST /api/v2/executions/{execution_id}/retries
Idempotency-Key: ...
```

重试创建新 Attempt，不复用旧 attemptId。

## 5. SSE Event

### 5.1 Answer Event

```http
GET /api/v2/answer-runs/{answer_run_id}/events
Last-Event-ID: 15
```

### 5.2 Execution Event

```http
GET /api/v2/executions/{execution_id}/events
Last-Event-ID: 15
```

统一事件结构：

```text
id: 16
event: execution.progress
data: {"event_id":"...","sequence":16,"occurred_at":"...","payload":{}}
```

事件类型：

```text
answer.delta
answer.citation
retrieval.summary
answer.completed
answer.failed
execution.status
execution.progress
execution.waiting
execution.completed
execution.failed
execution.cancelled
```

Redis Streams 只负责跨节点实时转发。重要阶段事件和最终状态必须能够从 MySQL 恢复。

`retrieval.summary` 使用 `retrieval-execution-trace-v1`，由 AnswerRun 在进入生成阶段时持久化到 MySQL `answer_event`：

```json
{
  "trace_schema_version": "retrieval-execution-trace-v1",
  "plan_version": "qa-passage-v1",
  "retrieval_latency_micros": 1200,
  "candidate_count": 12,
  "admitted_candidate_count": 12,
  "selected_evidence_count": 6,
  "selected_evidence_characters": 4200,
  "degraded": false,
  "degradation_reasons": [],
  "maximum_output_tokens": 1200,
  "execution_trace": {
    "schema_version": "retrieval-execution-trace-v1",
    "plan_version": "qa-passage-v1",
    "total_latency_micros": 1200,
    "raw_candidate_count": 12,
    "admitted_candidate_count": 12,
    "selected_evidence_count": 6,
    "selected_evidence_characters": 4200,
    "steps": [
      {
        "step_index": 0,
        "channel": "QA_PASSAGE",
        "candidate_limit": 12,
        "raw_candidate_count": 12,
        "admitted_candidate_count": 12,
        "latency_micros": 1100,
        "degraded": false,
        "degradation_reasons": [],
        "measurements": {}
      }
    ],
    "selected_evidence": [
      {
        "rank": 1,
        "evidence_id": "passage:uuid",
        "kind": "PASSAGE",
        "raw_score": 8.0,
        "fused_score": 8.0,
        "rerank_score": 8.0,
        "character_cost": 700
      }
    ]
  }
}
```

该事件只记录标识符、计数、耗时、rank 与 score，不允许写入 query、title、excerpt、content 或其他资料正文。`selected_evidence_count` 表示最终 EvidenceBundle 大小，不包含 Wiki 复用的既有 citation 数量。

生产环境的 QA 主检索通道不可用时必须 fail closed，并以 `QA_RETRIEVAL_PROVIDER_UNAVAILABLE` / HTTP 503 结束请求；不得切换到 MySQL lexical 检索后继续生成正式答案。主检索正常但没有可接纳命中时返回空 EvidenceBundle；ownership 校验拒绝命中时 `degraded=true`，稳定原因码写入 `degradation_reasons`。`NOTEWEAVE_QA_MYSQL_FALLBACK_ENABLED` 只允许非生产诊断和无 Elasticsearch 的合同测试显式启用，生产启动门禁会拒绝该配置。原因码不得包含异常消息、查询或资源标识符。

所有 channel 进入 EvidenceBundle 前还必须通过数据库 ownership/current-version 复核：Passage 必须属于当前 Workspace、当前可用 Source Snapshot 且 projection 为 PROJECTED；Knowledge Version 必须属于当前 Workspace 的 ACTIVE item，并等于 `latest_version_id`。跨 Workspace、历史版本或已删除对象统一以 `EVIDENCE_SCOPE_VIOLATION` fail closed，不允许仅凭 retriever 返回的 `access_scope` 字符串放行。

AnswerRun 同时在 MySQL 内部持久化两份不进入 SSE 的审计快照：

- `answer_run.retrieval_plan_json`：完整 RetrievalPlan，包括 mode、steps、filters 与 evidence/字符/graph hop/node/edge/character budget；
- `answer_run.evidence_bundle_json`：`evidence-bundle-snapshot-v1`，保留 bundle id、plan version、degradation、证据身份、Source Snapshot/Passage 或 Knowledge Item/Version、rank/score、scope、freshness、selection reason 与 character cost。

EvidenceBundle 审计快照不得复制 title、excerpt、content 或 retriever opaque metadata。在线评测导出使用 `RetrievalExecutionShadowExporter` 将该快照与 `retrieval-execution-trace-v1` 映射为 `retrieval-shadow-v1`；case/evidence/source/citation 标识符必须先通过带盐 HMAC 假名化后才允许落盘。

Wiki step 的 `measurements` 还会记录 `graph_hops_used`、`graph_nodes_used`、`graph_edges_used`、`graph_characters_used`，以及 `graph_hop_limit`、`graph_node_limit`、`graph_edge_limit`、`graph_character_limit`。`graph_characters_used` 是对真正入选关系渲染成本的确定性、token-safe proxy，用于在调用模型前限制图关系体积；它不是 tokenizer 的精确 token 计数，也不包含 Wiki 页面正文、summary 或 citation 文本。其他 channel 没有对应安全数值时返回空对象。

### 5.3 当前兼容 Chat Stream

当前已落地的兼容端点：

```http
POST /api/v2/conversations/{conversation_id}/messages
```

创建消息后除 `assistant_message_id/assistant_request_id/answer_run_id` 与两个 stream URL 外，还直接返回降级状态。以下含 `qa_mysql_fallback` 的示例只适用于显式开启诊断 fallback 的非生产环境：

```json
{
  "retrieval_degraded": true,
  "retrieval_degradation_reasons": [
    "qa_primary_no_scoped_hits",
    "qa_mysql_fallback"
  ]
}
```

该字段与随后 AnswerRun snapshot、`retrieval.summary` 和 EvidenceBundle snapshot 使用同一份 bundle 状态；API 层不得重新推断或维护第二套降级状态。

```http
GET /api/v2/chat/requests/{assistant_request_id}/stream
Accept: text/event-stream
```

事件为 `chat.delta`、`chat.citation`、`chat.completed`、`chat.failed`，每个事件带 run 内单调 SSE `id`。响应是实际增量流，不是完整字符串。前端 parser 必须接受 `event:value` 与 `event: value`，并按 SSE 规范合并多行 `data:`。

兼容端点后续由会话级 Session Event Stream 汇聚，并映射到正式 `answer.*` 事件；正式事件和恢复语义仍以 5.1 的 AnswerRun 契约为准。

## 6. Worker 获取与 Claim

Worker 收到 Kafka command 后，先 claim，而不是直接执行：

```http
POST /internal/v2/executions/{execution_id}/attempts/{attempt_id}/claim
X-NoteWeave-Internal-Token: ...
Idempotency-Key: command_id
```

成功：

```json
{
  "claimed": true,
  "execution_id": "uuid",
  "attempt_id": "uuid",
  "input_url": "/internal/v2/executions/{id}/attempts/{attempt_id}/input",
  "deadline_at": "timestamp"
}
```

重复或旧 Attempt：

```json
{
  "claimed": false,
  "reason": "STALE_ATTEMPT"
}
```

Claim 是 Worker command 的主要幂等屏障，因此不要求 Python Worker 自己维护通用 Inbox 数据库。

## 7. Worker Callback

### 7.1 Progress

```http
POST /internal/v2/executions/{execution_id}/attempts/{attempt_id}/progress
Idempotency-Key: event_id
```

### 7.2 Complete

```http
POST /internal/v2/executions/{execution_id}/attempts/{attempt_id}/complete
Idempotency-Key: event_id
```

### 7.3 Fail

```http
POST /internal/v2/executions/{execution_id}/attempts/{attempt_id}/fail
Idempotency-Key: event_id
```

规则：

- Header 必填；
- callback receipt 使用 `(attempt_id, idempotency_key)` 唯一键；
- active attempt 才能推进状态；
- duplicate 返回原 Ack；
- stale attempt 返回 `STALE_ATTEMPT_IGNORED`；
- complete/fail 只能首次改变终态；
- result payload 超限时使用 object locator。

## 8. Kafka Envelope

```json
{
  "schema_version": 2,
  "message_id": "uuid",
  "command_id": "uuid",
  "execution_id": "uuid",
  "attempt_id": "uuid",
  "message_type": "RESEARCH_EXECUTE",
  "workspace_id": "uuid",
  "correlation_id": "uuid",
  "causation_id": "uuid",
  "occurred_at": "timestamp",
  "deadline_at": "timestamp",
  "payload": {}
}
```

## 9. 幂等矩阵

| 接口/事件 | 最终幂等手段 | Redis 是否参与 |
|---|---|---|
| 创建 AnswerRun | `(actor_id, idempotency_key)` 唯一键 | 可短期加速 |
| 创建 Research/Artifact | command receipt 或领域唯一键 | 可短期加速 |
| Worker command | Java claim + attempt fencing | 不作为最终保证 |
| Worker callback | callback receipt | 可短期拦截重复 |
| ES projection | document ID + aggregate version | 否 |
| Cache invalidation | 重复删除天然幂等 | 否 |
| SSE delivery | sequence 去重 | Redis Stream 承载 |

## 10. 兼容路径

旧接口暂时保留：

```text
/conversations/{id}/messages
/chat/requests/{id}/stream
/tasks/{id}
/internal/worker/tasks/{id}/*
```

任务实时观测的规范入口为持续订阅 `/tasks/{id}/events`，支持 `Last-Event-ID` 断线续传并在任务终态后关闭。`/tasks/{id}/event-history?afterEventId=...` 保留为首次快照校正、SSE 不可用时的显式恢复路径，不再与健康 SSE 并行轮询。

兼容 Controller 把旧请求转换为新 Command，并在响应中增加 `answer_run_id/execution_id`。

当旧接口调用量归零后删除，不长期维护两套业务实现。
