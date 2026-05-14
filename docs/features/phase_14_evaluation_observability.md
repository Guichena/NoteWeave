# Phase 14: 评测与可观测性

本文档用于指导 NoteWeave 第十四阶段编码实现。

范围：

```text
Phase 14: LLM Call Log / Prompt Version / RAG Evaluation / Retrieval Metrics / Citation Quality / User Feedback
```

第十四阶段目标是为已经落地的检索、问答、生成和引用链路补齐可观测能力：能看到一次回答用了什么 Prompt、检索到了哪些证据、消耗了多少 Token、延迟和错误在哪里，并能用小规模黄金样例持续验证 RAG 效果。

---

## 1. 参考文档

```text
docs/features/phase_4_team_rag_chat_citation.md
docs/features/phase_8_studio_artifact_generation.md
docs/features/phase_9_retrieval_enhancement_rrf.md
docs/features/phase_11_personal_generation.md
docs/features/phase_12_long_term_memory.md
docs/features/database_api_blueprint.md
```

---

## 2. 阶段目标

- 记录 LLM 调用日志，包含模型、Prompt 版本、Token、耗时、错误和业务来源。
- 为 Chat、RAG、Studio、个人生成等链路统一埋点。
- 记录每次检索的 query、召回来源、召回分数、RRF 合并结果和最终证据。
- 支持用户对回答进行点赞、点踩、问题反馈。
- 支持管理员维护小规模 RAG Eval Case。
- 支持手动触发 Eval Run，并产出检索与回答质量指标。
- 能从日志反查某次回答的 Prompt、引用证据和生成结果。

---

## 3. 本阶段不做的事

- 不做完整在线 A/B 实验平台。
- 不做自动化模型微调。
- 不做复杂 BI 分析大屏。
- 不做全链路分布式追踪系统替代品。
- 不做用户行为增长分析。
- 不做题库测评、测验打分、答题记录等 Quiz 相关功能。

---

## 4. 数据模型

### PromptVersion

表：`prompt_version`

核心字段：

```text
id
name
scene
version
content
variablesJson
status
createdBy
createdAt
updatedAt
```

scene 建议值：

```text
TEAM_RAG_CHAT
WORKSPACE_CHAT
PERSONAL_GENERATION
STUDIO_GENERATION
MEMORY_SUMMARY
METHODOLOGY_MATCH
```

status：

```text
DRAFT
ACTIVE
ARCHIVED
```

### LLMCallLog

表：`llm_call_log`

核心字段：

```text
id
userId
spaceId
sessionId
messageId
taskId
artifactId
scene
provider
model
promptVersionId
promptHash
inputTokens
outputTokens
totalTokens
latencyMs
success
errorCode
errorMessage
createdAt
```

说明：

- 默认不在表中保存完整 Prompt 原文，避免敏感信息扩散。
- 如需排障，可保存脱敏后的 `promptSnapshot` 或对象存储引用。
- `scene` 用于区分 Chat、RAG、Studio、Memory 等调用来源。

### RetrievalTrace

表：`retrieval_trace`

核心字段：

```text
id
userId
spaceId
sessionId
messageId
taskId
scene
queryText
retrieverType
topK
latencyMs
createdAt
```

### RetrievalTraceItem

表：`retrieval_trace_item`

核心字段：

```text
id
traceId
sourceType
sourceId
documentId
chunkId
wikiPageId
score
rank
selectedAsEvidence
metadataJson
```

sourceType：

```text
DOCUMENT_CHUNK
WIKI_PAGE
ARTICLE_CARD
CONCEPT_CARD
MEMORY
```

### AnswerFeedback

表：`answer_feedback`

核心字段：

```text
id
userId
spaceId
sessionId
messageId
rating
reason
comment
createdAt
```

rating：

```text
UP
DOWN
NEUTRAL
```

reason 建议值：

```text
HELPFUL
NOT_GROUNDED
WRONG_CITATION
MISSING_CONTEXT
TOO_VERBOSE
TOO_SHORT
OTHER
```

### RagEvalCase

表：`rag_eval_case`

核心字段：

```text
id
spaceId
name
queryText
expectedAnswer
expectedSourceJson
tagsJson
enabled
createdBy
createdAt
updatedAt
```

### RagEvalRun

表：`rag_eval_run`

核心字段：

```text
id
spaceId
name
status
caseCount
startedBy
startedAt
finishedAt
summaryJson
```

### RagEvalResult

表：`rag_eval_result`

核心字段：

```text
id
runId
caseId
answerMessageId
retrievalTraceId
recallAtK
mrr
citationCoverage
groundednessScore
answerQualityScore
latencyMs
errorMessage
createdAt
```

---

## 5. Service 设计

### PromptVersionService

```java
PromptVersionResponse create(Long userId, CreatePromptVersionRequest request);
PromptVersionResponse activate(Long userId, Long promptVersionId);
PromptVersionResponse getActive(String scene);
List<PromptVersionResponse> list(String scene);
```

规则：

- 同一 scene 同一时间只能有一个 ACTIVE。
- 业务调用只依赖 ACTIVE 版本。
- 历史日志保留调用时的 promptVersionId。

### LLMCallLogService

```java
Long begin(LLMCallContext context);
void markSuccess(Long logId, TokenUsage usage, long latencyMs);
void markFailure(Long logId, String errorCode, String errorMessage, long latencyMs);
Page<LLMCallLogResponse> search(LLMCallLogQuery query);
```

接入点：

```text
TeamRagChatService
ChatRuntimeService
PersonalGenerationService
StudioGenerationService
MemoryWritebackService
```

### RetrievalTraceService

```java
Long createTrace(RetrievalTraceCreateRequest request);
void addItems(Long traceId, List<RetrievalTraceItemCreateRequest> items);
void markSelectedEvidence(Long traceId, List<Long> selectedItemIds);
RetrievalTraceDetailResponse get(Long userId, Long traceId);
```

### AnswerFeedbackService

```java
AnswerFeedbackResponse submit(Long userId, Long messageId, SubmitAnswerFeedbackRequest request);
Page<AnswerFeedbackResponse> search(Long userId, AnswerFeedbackQuery query);
```

### RagEvaluationService

```java
RagEvalRunResponse startRun(Long userId, Long spaceId, StartRagEvalRunRequest request);
void executeRun(Long runId);
RagEvalRunResponse getRun(Long userId, Long runId);
Page<RagEvalResultResponse> listResults(Long userId, Long runId);
```

执行流程：

```text
读取 enabled RagEvalCase
  ↓
按当前检索与生成链路执行问答
  ↓
记录 RetrievalTrace 与 LLMCallLog
  ↓
计算 recall@k / MRR / citationCoverage
  ↓
可选调用 LLM Judge 评估 groundedness / answerQuality
  ↓
写入 RagEvalResult
```

---

## 6. 指标定义

### 检索指标

```text
recall@k:
  expectedSourceJson 中的目标来源是否出现在 topK 中

MRR:
  第一个命中目标来源的倒数排名

selectedEvidenceRate:
  topK 结果中最终被 Prompt 使用的比例
```

### 引用指标

```text
citationCoverage:
  回答中的关键结论是否至少有一个 Citation 支撑

citationPrecision:
  Citation 是否真的支持对应句子
```

### 生成指标

```text
groundednessScore:
  回答是否基于检索证据，不编造外部信息

answerQualityScore:
  回答是否完整、清楚、符合任务要求
```

### 运行指标

```text
latencyMs
inputTokens
outputTokens
totalTokens
successRate
errorRate
```

---

## 7. API 设计

### 用户反馈

```http
POST /api/v1/chat/messages/{messageId}/feedback
GET  /api/v1/chat/messages/{messageId}/feedback
```

### 管理端 Prompt

```http
GET  /api/v1/admin/prompt-versions
POST /api/v1/admin/prompt-versions
POST /api/v1/admin/prompt-versions/{promptVersionId}/activate
```

### 管理端日志

```http
GET /api/v1/admin/llm-call-logs
GET /api/v1/admin/retrieval-traces/{traceId}
```

### 管理端 RAG Eval

```http
GET  /api/v1/admin/spaces/{spaceId}/rag-eval-cases
POST /api/v1/admin/spaces/{spaceId}/rag-eval-cases
PUT  /api/v1/admin/rag-eval-cases/{caseId}
POST /api/v1/admin/spaces/{spaceId}/rag-eval-runs
GET  /api/v1/admin/rag-eval-runs/{runId}
GET  /api/v1/admin/rag-eval-runs/{runId}/results
```

---

## 8. 权限要求

- 普通用户只能提交自己可访问消息的反馈。
- LLM 调用日志、检索 Trace、Eval Case、Eval Run 只允许管理员或 Space owner 访问。
- PromptVersion 只允许管理员维护。
- 日志中不得向无权限用户泄露 Prompt、私有资料、用户记忆或检索证据。

---

## 9. 集成要求

- 所有 LLM 调用必须经过统一 Gateway 或统一包装器。
- 所有 RAG 检索必须生成 RetrievalTrace。
- Citation 写入时要关联 messageId 和可选 retrievalTraceId。
- Eval Run 不应污染正式会话历史。
- Eval Run 产生的任务可以复用 `task` 表，但 taskType 应明确为 `RAG_EVAL_RUN`。

---

## 10. 验收清单

- Chat/RAG/Studio/个人生成调用后能看到 LLMCallLog。
- RAG 问答后能查看 RetrievalTrace 和最终证据。
- 用户能对回答提交反馈。
- 管理员能维护 RagEvalCase。
- 管理员能手动触发 RagEvalRun。
- EvalRun 能产出 recall@k、MRR、citationCoverage、latency、token 使用量。
- 失败的 LLM 调用能记录 errorCode 和 errorMessage。

---

## 11. 给 AI 执行第十四阶段的边界提醒

- 不要做完整 A/B 实验平台。
- 不要做用户增长分析大屏。
- 不要把完整敏感 Prompt 直接明文暴露给前端。
- 不要让 Eval Run 写入正式用户会话。
- 不要引入 Quiz 题库、答题、评分等暂缓功能。
- 所有 API 必须使用 `/api/v1`。
