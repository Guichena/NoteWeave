# NoteWeave v2 现有系统适配与改造实施方案

> 文档状态：现行改造实施基线
>
> 目标架构：《docs/NoteWeave-v2目标系统架构与功能中间件设计.md》
>
> 审计证据：《docs/主项目系统设计与中间件全面审计及全新优化方案.md》
>
> API/事件契约：《docs/API与事件契约-v2.md》
>
> 数据库规范：《docs/数据库与迁移规范.md》
>
> 本文回答：如何在不中断现有功能的前提下，从当前代码迁移到目标架构

## 1. 改造目标

本轮改造不是简单修复若干 Bug，也不是推倒重写，而是完成以下结构性收敛：

1. QA、Note、Wiki 从 ChatService 内的三个分支迁移为统一 Answer Pipeline；
2. Source、Wiki、Research、Artifact 的长任务迁移为统一 Execution 控制面，普通 QA 保留轻量 AnswerRun；
3. MySQL、Kafka、MinIO、Elasticsearch、Redis 的实际职责与目标设计一致；
4. Java 成为唯一业务真源，Python Worker 退化为可替换计算节点；
5. 巨型 Service 和前端 App 按模块与 Use Case 渐进拆分；
6. 在改造过程中保留现有 API 和数据的兼容读取能力；
7. 每个阶段都能单独上线、验证和回滚。
8. 通过隔离线程池、Redis Streams、限流与缓存体现可验证的后端并发和多实例能力，而不是无边界增加中间件。

## 2. 改造原则

### 2.1 不做一次性重写

采用 Strangler Pattern：

```text
旧入口
  -> Compatibility Facade
  -> 新 Use Case（已迁移功能）
  -> 旧 Service（未迁移功能）
```

每次只迁移一条可验证纵向链路，旧实现保留到流量、数据和测试全部切换完成。

### 2.2 先正确性，再抽象

改造顺序固定为：

```text
修复 P0 正确性
  -> 建统一控制面
  -> 迁移领域链路
  -> 拆巨型类
  -> 做性能与算法升级
```

不能在消息会丢失、状态会错误推进时先做 Hybrid Retrieval 或 UI 重构。

### 2.3 新旧并行必须有单一写入者

允许双读和影子计算，不允许两个实现同时写同一聚合。

- 新旧 Answer 可以影子比较，但只有一个版本提交 Message；
- 新旧 Dispatcher 不能同时发送同一 Outbox；
- ES 新旧 index 可以双写，但只有一个 alias 对外查询；
- Worker 本地 repository 只能作为缓存，不与 Java 双向同步业务版本。

### 2.4 所有兼容都有删除期限

兼容层必须记录：

- owner；
- 开始版本；
- 删除条件；
- 最晚删除版本；
- 监控指标。

禁止永久保留 `taskId/task_id`、新旧状态、重复表和双调用路径。

### 2.5 设计模式按场景采用

- Outbox 是数据库到外部系统可靠投递的标准能力，保留并统一；
- Inbox 不建设成所有消费者共享的万能表；
- Worker 命令优先使用 Java Execution Claim + Attempt Fencing；
- Callback 使用持久化 Receipt；
- ES/Wiki 投影使用 aggregate version 幂等；
- Redis `SET NX` 只抑制短时间重复，不承担最终幂等；
- 线程池按工作负载隔离，不通过一个全局 `@Async` 池制造表面异步。

## 3. 当前系统基线

### 3.1 当前可保留能力

- Workspace 作为统一边界；
- Source/Snapshot、Citation、KnowledgeVersion、ArtifactVersion；
- Java + Research Worker + Artifact Worker 分工；
- ObjectStorage 抽象；
- TaskEvent、Outbox、Callback Receipt、Checkpoint 基础；
- Python Worker 已有较完整测试；
- Artifact Skill/Capability/Verifier/Repair 概念。

### 3.2 当前必须先修复的问题

| 编号 | 问题 | 当前影响 | 目标处理 |
|---|---|---|---|
| S0-1 | Backend 测试无法编译 | 无 Java 回归基线 | 修复配置 Test Fixture |
| S0-2 | conversation_message 缺 updated_at | LLM 完成写回运行期失败 | 新增迁移，不改 V005 |
| S0-3 | ES disabled Bean 不完整 | 关闭 ES 可能无法启动 | SearchIndex Port + Noop Adapter |
| S0-4 | source.index 两个同组消费者 | ES 事件被竞争消费 | 删除空消费者/独立 group |
| S0-5 | Kafka 异常被吞并 ACK | 消息丢失 | 统一 error handler/DLT |
| S0-6 | Source 提前标 INDEXED | 状态与索引事实不一致 | Index Projector ACK |
| S0-7 | Source/Wiki 伪 Outbox | DB/Kafka 双写窗口 | 业务事务只写 Outbox |
| S0-8 | Research/Artifact Outbox 不对称 | 可靠性不可预测 | 通用 Dispatcher |
| S0-9 | 用户授权缺失 | Workspace IDOR | ActorContext + Authorizer |
| S0-10 | Worker 默认认证不足 | 可触发内部任务 | prod 强制认证 |
| S0-11 | 历史 Flyway 被修改 | checksum/环境漂移 | 恢复并追加迁移 |
| S0-12 | Source 事务自调用失效 | 部分 chunk 提交 | 独立 Committer/TransactionTemplate |

### 3.3 当前构建状态

- Backend：主代码编译通过，测试编译失败；
- Frontend：测试和构建通过；
- Research Worker：测试通过；
- Artifact Worker：测试通过。

在 Phase 0 完成前，不允许进行大规模结构迁移。

## 4. 现有代码到目标模块的映射

| 当前包/类 | 目标模块 | 改造方式 |
|---|---|---|
| `workspace/*` | workspace | 增加 ActorContext、MemberRepository、Authorizer |
| `conversation/*` | conversation | 拆 Message Command/Query，增加 MessageRevision |
| `chat/ChatService` | answer + conversation | 逐步退化为兼容 Facade |
| `chat/RetrievalService` | retrieval | 拆 Retriever、Ranker、EvidenceBuilder |
| `source/UploadService` | source | 拆 Upload、Blob、Source Command、Parse Command |
| `source/SourceParseService` | source | 拆 Parser、Parse Handler、Result Committer |
| `knowledge/KnowledgeService` | knowledge | 拆 Note、Wiki、Graph、Governance Query/Command |
| `knowledge/WikiIngestService` | knowledge/source projection | 改为事件驱动 Projector |
| `task/TaskService` | execution compatibility | 新 Execution 上线后保留旧 API 投影 |
| `worker/*Outbox*` | execution adapters | 合并为通用 Outbox Dispatcher |
| `research/ResearchRunService` | research | 拆 Command、Query、Result Committer、Checkpoint |
| `artifact/ArtifactJobService` | artifact | 拆 Job、Run、Version、Writeback、Worker Input |
| `memory/*` | memory | 拆 Eligibility、Neighborhood、Staleness、Compiler |
| `infra/Kafka*` | platform.messaging | 仅保留 Adapter 和配置 |
| `infra/Elasticsearch*` | platform.search | 实现 SearchIndex/Projection Port |
| `infra/*ObjectStorage` | platform.objectstorage | 保留并强化 locator/checksum |
| `frontend/src/App.tsx` | feature modules | 渐进抽出 API、Chat、Research、Artifact、Wiki |

## 5. 数据库适配策略

### 5.1 Flyway 规则

- 已存在 V001～V024 一律视为已发布；
- 恢复被修改的历史文件；
- 所有改造从新版本 migration 开始；
- migration 只前进，不通过修改 checksum 解决问题；
- CI 校验已发布 migration 的 hash；
- 集成测试优先使用 MySQL Testcontainers。

### 5.2 第一批修复迁移

建议实际编号以仓库最新版本为准，逻辑内容如下：

```text
add conversation_message.updated_at
add source_chunk(snapshot_id, chunk_no) unique constraint
add task/execution transition version fields
add missing indexes for outbox claim and event sequence
add workspace/member authorization indexes
```

### 5.3 Execution 新表

新增：

```text
execution
execution_attempt
execution_event
outbox_message
command_receipt（仅不可自然幂等的外部命令需要）
```

迁移期保留旧表：

```text
task
task_event
task_outbox
```

### 5.4 新旧 Task 数据关系

迁移期采用映射字段：

```text
task.execution_id nullable
execution.legacy_task_id nullable
```

规则：

- 新建迁移后任务时，同时创建 execution 和 legacy task 投影；
- 旧 API 从 task 读取；
- 新 API 从 execution 读取；
- execution 是唯一状态写入者；
- task 状态由投影器同步；
- 全部客户端迁移后删除兼容写和映射。

不允许 TaskService 和 ExecutionCoordinator 各自推进状态。

### 5.5 AnswerRun 数据

新增：

```text
answer_run
answer_evidence_snapshot
message_revision
```

现有 `conversation_message` 保留为消息逻辑身份和当前版本指针。

迁移步骤：

1. 新回答写 AnswerRun + MessageRevision，普通 QA 不强制创建 Execution；
2. 同步回填 `conversation_message.content` 供旧前端读取；
3. 新前端改读 revision；
4. 停止覆盖 content；
5. content 降级为 latest projection 或移除。

只有异步 Answer 才关联 Execution/Attempt/Outbox。这样避免普通聊天为了统一模型增加不必要的数据库写放大。

### 5.6 JSON 大字段治理

逐步分类：

- 高频检索字段拆列；
- 审计快照保留 JSON；
- 大报告/checkpoint 写 MinIO；
- MySQL 保存 object locator/hash；
- 每个 JSON payload 增加 schema version。

## 6. Phase 0：正确性止血与基线恢复

### 6.1 目标

在不改变产品行为的前提下，使现有系统达到可重构状态。

### 6.2 改造项

#### Backend 构建

- 新增 `NoteWeavePropertiesTestFactory`；
- 修复两个 HTTP Worker Client 测试；
- 全仓脚本执行 Maven、Frontend、两个 Worker 测试；
- CI 以该脚本为合并门禁。

#### Chat SQL

- 新增 migration 补 `conversation_message.updated_at`；
- 添加 persist streamed content 集成测试；
- 在真 SSE 改造前保持现有返回格式。

#### Elasticsearch Bean

引入：

```java
interface SearchIndex
class ElasticsearchSearchIndex
class NoopSearchIndex
```

`RetrievalService` 改依赖 SearchIndex，ES enabled/disabled 都必须启动测试通过。

#### Kafka

- 删除 `KafkaTaskConsumer.onSourceIndex` 空观察者；
- ES Projector 使用独立 consumer group；
- handler 失败必须抛出；
- 配置受控重试和 DLT；
- DLT 内容保留原 messageId、topic、partition、offset、errorCode。

#### Source 状态

- 解析完成只写 PARSED/INDEX_PENDING；
- ES Projector 完成后回写 INDEXED；
- 单 snapshot 全部 passage 成功才 READY；
- 索引失败进入 INDEX_FAILED。

#### 安全

- Research Worker 增加与 Artifact Worker 一致的 auth middleware；
- prod profile token 为空拒绝启动；
- debug route 仅在 debug app/router 中注册；
- Java internal route 统一认证错误格式。

### 6.3 验收

- 四个子项目测试通过；
- ES on/off 均成功启动；
- 故意让 ES 写失败，Source 不显示 READY；
- Kafka handler 抛错后消息不会静默提交；
- 未携带内部凭据无法执行 Worker task；
- Flyway 在已有数据库上 validate 通过。

### 6.4 回滚

Phase 0 只包含兼容性修复和追加 migration，应用代码可回滚；新增列不删除。

## 7. Phase 1：统一 Outbox Dispatcher

### 7.1 先抽基础设施，不先迁业务表

从现有 `ArtifactOutboxDispatcherService` 提取：

```java
OutboxRepository
OutboxClaimPolicy
RetryPolicy
DeliveryAdapter
DeadLetterService
OutboxDispatcher
```

保留 Artifact 行为不变，先用现有测试证明抽取等价。

当前统一选择 MySQL Polling Outbox，不在本轮引入 Debezium：

- 现有系统已经有 task_outbox、Scheduler、lease 和 Micrometer 基础；
- 当前吞吐没有证明需要 Kafka Connect/CDC；
- Polling 方案更容易做 crash window 和 redrive 测试；
- Kafka Transaction 无法解决 MySQL/Kafka 原子性；
- Redis Stream 不作为业务 Outbox。

当 outbox backlog、数据库轮询成本或投递吞吐达到明确阈值后，再通过 Delivery Port 迁移 Debezium CDC。

### 7.2 Delivery Adapter

建立：

- KafkaDeliveryAdapter；
- HttpWorkerDeliveryAdapter（仅确有 HTTP 投递需求时）；
- Noop/TestDeliveryAdapter。

DeliveryAdapter 不负责业务状态更新。

### 7.3 消费端幂等策略

不统一要求 `inbox_receipt`：

| 消费类型 | 改造方案 |
|---|---|
| Research/Artifact Worker command | Worker 收到 taskId 后向 Java claim active attempt |
| Worker progress/complete/fail | 强制 idempotency key + callback receipt |
| ES source projection | `document_id + aggregate_version` 幂等 upsert |
| Wiki projection | workspace/source projection checkpoint |
| 外部不可重复业务命令 | command receipt/inbox 唯一键 |
| Redis 短期去重 | 只做性能优化，失败后仍由数据库保证正确性 |

### 7.4 迁移顺序

1. Artifact Outbox；
2. Research Outbox；
3. Source Parse/Index；
4. Wiki ingest/retract；
5. Generated Source ingest。

### 7.5 Source/Wiki 调整

删除业务事务中的：

```text
kafkaPublisher.publishSourceParse
kafkaPublisher.publishWikiIngest
kafkaPublisher.publishSourceIndex
```

业务事务只写 outbox。Dispatcher 成为唯一发送者。

### 7.6 Research 调整

删除专用的简单 READY -> SENT 算法，改用通用 claim/lease/retry/DLT。

### 7.7 验收

- 同一 outbox 只有一个 dispatcher claim；
- publish 前崩溃可以重试；
- publish 后、SENT 前崩溃会重复投递但业务幂等；
- 连续失败进入 DLT；
- DLT 可人工 redrive；
- Source、Research、Artifact 使用相同指标。

## 8. Phase 2：Execution 控制面

### 8.1 建立核心模型

实现：

```java
Execution
ExecutionAttempt
ExecutionEvent
ExecutionStateMachine
ExecutionCoordinator
ExecutionQueryService
```

### 8.2 状态兼容

建立映射：

| 新状态 | 旧 Task 状态 |
|---|---|
| PENDING/DISPATCHING | PENDING |
| RUNNING | RUNNING |
| WAITING | WAITING |
| SUCCEEDED | COMPLETED |
| FAILED | FAILED |
| CANCELLED | CANCELLED |
| TIMED_OUT | FAILED + errorCode |

### 8.3 Worker Callback

新 callback 合同增加：

- executionId；
- attemptId；
- eventId；
- idempotencyKey；
- schemaVersion。

兼容期 Gateway 接收旧路径，根据 taskId 查 execution/attempt 后转成新 Command。

### 8.4 Attempt fencing

所有结果提交 SQL 必须包含：

```text
where execution_id = ?
  and active_attempt_id = ?
  and status in (...)
  and version = ?
```

受影响的迟到回调返回 `STALE_ATTEMPT_IGNORED`。

### 8.5 迁移领域

顺序：

1. Artifact；
2. Research；
3. Source；
4. Wiki rebuild；
5. 仅迁移达到异步阈值的 Chat Answer。

普通 QA 只创建 AnswerRun，不创建完整 ExecutionAttempt。AnswerRun 在升级异步时再绑定 executionId。

### 8.6 验收

- 所有领域共享状态机；
- TaskService 不再无条件覆盖 terminal 状态；
- 重复 complete 只生效一次；
- cancel/timeout 可到达 Worker；
- 旧 Task API 返回值与迁移前兼容。

### 8.7 业务隔离线程池

在 Execution 基线稳定后，引入线程池配置模块：

```text
answerIoExecutor
sourceIoExecutor
projectionExecutor
sseDispatchExecutor
maintenanceExecutor
```

改造规则：

- LLM/HTTP Retriever 只进入 `answerIoExecutor`；
- MinIO 流式 I/O 和轻量解析协调进入 `sourceIoExecutor`；
- ES/Wiki 投影进入 `projectionExecutor`；
- SSE 写出进入 `sseDispatchExecutor`，不在其中执行 LLM；
- 补偿扫描、GC 和一致性校验进入低优先级 `maintenanceExecutor`；
- 每个池配置 core/max/queue/rejection/timeout；
- 增加 MDC/OpenTelemetry TaskDecorator；
- 暴露 active、queue、rejected 和 wait time 指标；
- 被拒绝的持久任务回到 Execution/Outbox 重试，不能丢弃。

第一阶段不建议直接改大量方法为 `@Async`，而是先迁移明确的边界任务。

## 9. Phase 3：统一 Answer Pipeline

### 9.1 新建 answer 模块

核心组件：

```text
AnswerCommandHandler
AnswerPipeline
AnswerStrategyRegistry
AnswerRunRepository
AnswerEventPublisher
CitationGroundingService
```

### 9.2 迁移 QA

先把现有 QA 逻辑包装成：

```text
QaAnswerStrategy
LegacyQaRetrieverAdapter
```

本阶段不立即改变召回算法，只改变结构和运行记录。

验证新旧输出：

- evidence 数量；
- source 覆盖；
- citation；
- 最终文本；
- 延迟。

### 9.3 迁移 Note

把现有 RetrievalService 中的 Note 逻辑拆为：

- SourceMetadataRetriever；
- NoteJournalRetriever；
- SourceRelationRetriever；
- ReadingWindowRetriever；
- NoteEvidencePolicy；
- NoteAnswerStrategy。

先保持原评分权重，再用 gold set 调整。

### 9.4 迁移 Wiki

从 KnowledgeService 拆：

- WikiPageRetriever；
- WikiGraphRetriever；
- WikiEvidencePolicy；
- WikiAnswerStrategy；
- WikiChangeProposalService。

回答流程不直接修改 Wiki Page。

### 9.5 ChatService 退化

迁移后 ChatService 只负责兼容：

```java
sendMessage(...) -> answerCommandHandler.handle(...)
stream(...)      -> executionEventStream.open(...)
```

删除：

- mode switch；
- 三套 StringBuilder Answer；
- 手工 SSE 字符串拼接；
- Citation 持久化细节；
- Topic NLP 启发式的业务编排。

### 9.6 真 SSE

新 Endpoint：

```text
GET /api/v2/executions/{executionId}/events
```

兼容旧 `/chat/requests/{id}/stream`，内部解析 requestId -> executionId 后转发。

实现要求：

- `SseEmitter` 或局部 Reactive Stream；
- event sequence；
- Last-Event-ID；
- client disconnect cancel policy；
- partial revision 定时落库；
- terminal event 后关闭。

单实例阶段先完成真正流式；多实例阶段接入 Redis Streams：

```text
Answer/Execution Event
  -> Redis XADD（短保留）
  -> 任意 Java 节点 XREAD BLOCK
  -> SSE Client
```

重要阶段事件和最终状态仍写 MySQL。Redis 断线时先从数据库快照恢复，再继续订阅。

### 9.7 验收

- 新增 AnswerMode 不修改 Pipeline；
- 三模式都产生 RetrievalPlan/EvidenceBundle；
- 首 token 在生成过程中到达前端；
- 断线后可恢复；
- 重新生成产生新 Revision；
- Memory 不进入 Citation。

## 10. Phase 4：Source 与检索重构

### 10.1 Parser Registry

```java
interface DocumentParser {
    boolean supports(SourceMedia media);
    ParsedDocument parse(SourceBlob blob);
}
```

实现顺序：

1. PlainText/Markdown；
2. PDF；
3. DOCX/PPTX；
4. HTML/Web；
5. OCR。

### 10.2 流式文件处理

- Upload chunk 不再全部读入 byte[] 合并；
- 使用流式 SHA-256；
- 使用 MinIO multipart/compose；
- Parser 使用 InputStream/临时受控文件；
- 限制单任务内存预算。

### 10.3 Passage 模型

统一：

- Section；
- Passage；
- ReadingWindow；
- Locator；
- contentHash；
- parserVersion。

Source Snapshot 重解析时创建新 passage 集，不覆盖旧 snapshot。

### 10.4 ES 新索引

创建共享版本化 index：

```text
noteweave_passage_v3
```

迁移：

1. 从 MySQL 全量 backfill；
2. 新旧索引双投影；
3. 影子查询比较；
4. alias 切换；
5. 观察；
6. 删除旧 Workspace index。

### 10.5 Hybrid Retrieval

按顺序加入：

1. BM25 基线；
2. embedding/vector recall；
3. RRF fusion；
4. reranker；
5. Evidence diversity/coverage。

每一步必须通过 gold set 指标后再启用，配置 Feature Flag。

### 10.6 验收

- 多文件类型解析正确；
- 索引可全量重建；
- Source 删除/更新不会返回陈旧 passage；
- READY 与 ES 可查询一致；
- Recall@K/MRR 不低于旧实现；
- 性能满足预算。

## 11. Phase 5：Research 与 Artifact 领域拆分

### 11.1 ResearchRunService

拆分为：

```text
CreateResearchRunHandler
ResumeResearchRunHandler
ResearchRunQueryService
ResearchWorkerInputAssembler
ResearchProgressHandler
ResearchResultCommitter
ResearchCheckpointService
ResearchReportService
ResearchTraceProjection
```

优先修复：

- list/detail N+1；
- 大 result payload 入 longtext；
- checkpoint 全删全插；
- report bucket 不一致；
- trace type/状态裸字符串；
- callback attempt fencing。

### 11.2 ArtifactJobService

拆分为：

```text
CreateArtifactJobHandler
RegenerateArtifactHandler
RollbackArtifactHandler
ArtifactJobQueryService
ArtifactWorkerInputAssembler
ArtifactResultCommitter
ArtifactVersionService
ArtifactWritebackService
ArtifactExportService
```

规则：

- regenerate 创建新 ArtifactRun，不覆盖原 taskId；
- rollback 创建新版本；
- Version 不可变；
- Java 保存唯一正式 Version；
- Worker repository 只保存执行缓存/调试镜像；
- writeback 使用 Proposal/Receipt。

### 11.3 Worker 本地状态

逐步迁移：

| 当前状态 | 目标 |
|---|---|
| memory repository | 测试专用 |
| JSON file repository | 本地开发/恢复缓存 |
| waiting queue JSON | Java Execution WAITING 真源 |
| acquisition dict/file | Java operation/receipt 真源或显式 checkpoint |
| writeback dict/file | Java proposal/receipt 真源 |

Worker 重启后通过 executionId/attemptId 从 Java 和 MinIO 恢复。

### 11.4 验收

- Research/Artifact Service 职责清晰；
- Worker 多实例不会出现版本分叉；
- rollback/regenerate 历史完整；
- checkpoint 可恢复；
- list API 使用轻量 Projection 和 cursor；
- 大 payload 不进入普通明细响应。

## 12. Phase 6：Memory、权限与前端

### 12.1 Memory

拆分：

- MemorySignalService；
- EvidenceEligibilityPolicy；
- SemanticNeighborhoodClassifier；
- MemoryConflictService；
- StalenessPolicy；
- PromotionPolicy；
- ControlPackCompiler；
- MemoryOutcomeService。

迁移兼容：

- 现有 MemoryObject 继续读取；
- 新 Candidate 增加 policyVersion；
- 老对象首次编译时计算默认 priority；
- `COMMON` 不直接删除，改为最低优先级；
- 冲突对象不同时进入 ControlPack。

### 12.2 Workspace 授权

引入：

```java
ActorContext
WorkspaceAuthorizer
Permission
```

迁移顺序：

1. 只记录 actor，不拦截；
2. 开启 read 授权；
3. 开启 write 授权；
4. 文件和 Worker scope；
5. 删除 local-user fallback。

Feature Flag 必须按环境控制，避免一次切换锁死已有本地数据。

### 12.3 前端拆分

顺序：

1. `shared/api`；
2. `entities/execution`；
3. `features/chat`；
4. `features/research`；
5. `features/artifact`；
6. `features/wiki`；
7. `features/sources`；
8. App 只保留路由和布局。

前端兼容层统一处理旧 Task/new Execution 响应，不允许每个 feature 单独判断。

### 12.4 Redis

Redis 分三步接入：

#### R1：SSE Event Bridge

- Redis Streams；
- execution/answer 维度 stream；
- `MAXLEN ~` 和 TTL 清理；
- Last-Event-ID；
- 数据库 snapshot fallback。

#### R2：限流与并发配额

- 用户 API 限流；
- Workspace Research/Artifact 并发配额；
- LLM/provider token bucket；
- 上传/导出频率控制；
- 返回 retry-after。

#### R3：Cache-Aside

- Workspace permission；
- Skill Catalog；
- Source metadata；
- Wiki Index summary；
- 短期 Execution Projection。

必须具备 TTL 抖动、写后失效、缓存穿透保护、命中率指标和 Redis 故障降级。

持久任务 claim 继续使用 MySQL lease/CAS；Redis Lock 只用于短期 rebuild/index switch 协调。

## 13. API 兼容方案

### 13.1 兼容期

保留旧 API：

```text
/chat/requests/{assistantRequestId}/stream
/tasks/{taskId}
/tasks/{taskId}/events
```

新增：

```text
/answer-runs/{answerRunId}
/executions/{executionId}
/executions/{executionId}/events
```

旧响应增加可选字段：

```json
{
  "task_id": "...",
  "execution_id": "..."
}
```

### 13.2 弃用流程

1. 响应 Header 增加 Deprecation/Sunset；
2. 前端全部迁移；
3. 观察旧 endpoint 调用量；
4. 外部调用量归零；
5. 删除 Compatibility Controller；
6. 删除旧 Task Projection。

## 14. 消息兼容方案

### 14.1 Payload 版本

旧消息继续支持 `payload_version=v1`；新命令使用 `schema_version=2`。

Consumer 流程：

```text
Deserialize Envelope
  -> Version Router
  -> V1 Adapter / V2 Command
  -> Canonical Command
```

### 14.2 Topic 切换

不直接复用旧 topic 改 schema：

1. 发布 v2 topic；
2. Worker 同时监听 v1/v2；
3. Java 影子发布 v2；
4. 验证后只发送 v2；
5. 清空 v1 lag；
6. 停止 v1 consumer。

### 14.3 幂等

V1 消息没有 messageId 时，用稳定字段计算 legacy idempotency key：

```text
hash(topic + taskId + targetId + operation)
```

## 15. 中间件改造清单

### MySQL

- 恢复历史 migration；
- 新增 Execution/AnswerRun；
- 状态 version/CAS；
- cursor index；
- JSON 大字段治理；
- Testcontainers。

### Kafka

- 修 consumer group；
- 统一 Outbox；
- 按场景采用 claim/fencing、versioned upsert 或 receipt，不建设全局 Inbox；
- DLT/redrive；
- schema version；
- 固定镜像版本。

### Elasticsearch

- 条件 Bean/Noop；
- Source 状态 ACK；
- 共享 index；
- alias/reindex；
- vector/hybrid；
- projection lag 指标。

### MinIO

- bucket 语义统一；
- report/checkpoint 放 derived；
- 流式上传；
- locator/hash；
- GC 和授权下载。

### Redis

- Redis Streams 承接多实例 SSE 事件桥；
- Redis Lua/令牌桶承接限流和并发配额；
- Cache-Aside 承接权限、Catalog 和热点 Projection；
- 不保存业务真源；
- TTL/namespace；
- 故障降级。

### 线程池

- 按 answer/source/projection/SSE/maintenance 隔离；
- 有界队列和拒绝策略；
- 上下文传播与 Micrometer 指标；
- 外部依赖独立 semaphore；
- 后台持久任务拒绝后回队列，不丢失。

## 16. 测试与验证计划

### 16.1 每阶段必须执行

```text
Backend compile/test
Frontend test/build
Research Worker pytest
Artifact Worker pytest
Flyway validate/migrate
关键 Contract Test
```

### 16.2 集成测试矩阵

| 场景 | 必测故障 |
|---|---|
| Outbox | publish 前后崩溃、重复 claim、DLT、redrive |
| Worker | 重复命令、迟到 callback、重启恢复、超时 |
| Thread Pool | 队列满、拒绝、单依赖阻塞、上下文传播 |
| Redis | Stream 断线恢复、缓存击穿、限流、Redis 不可用降级 |
| Source | parser 失败、ES 失败、重建、删除 |
| Answer | 断流恢复、模型失败、无证据、重复生成 |
| Research | checkpoint、resume、waiting、cancel |
| Artifact | regenerate、rollback、writeback、export |
| Security | 跨 Workspace、无 token、重放 callback |

### 16.3 影子验证

允许：

- 新旧 Retriever 同时计算，只有旧结果返回；
- 新旧 ES index 同时查询；
- 新 Answer Pipeline 生成 trace 但不写 Message；
- 新 Execution 从旧 Task 事件构建影子投影。

所有影子任务必须有预算和采样率，不能翻倍消耗全部模型成本。

## 17. 灰度与回滚

### 17.1 Feature Flags

建议：

```text
execution.v2.enabled
answer.pipeline.v2.enabled
answer.qa.v2.percentage
answer.note.v2.percentage
answer.wiki.v2.percentage
search.index.v3.enabled
worker.contract.v2.enabled
workspace.auth.enforced
redis.event-stream.enabled
redis.rate-limit.enabled
redis.cache.enabled
```

### 17.2 回滚规则

- 数据库采用 expand-and-contract；
- 先新增列/表，后切写，最后删旧结构；
- 回滚版本必须能忽略新字段；
- alias 切换可快速回旧索引；
- v2 topic 失败时可短期恢复 v1 producer；
- 不回滚已经生成的不可变 Version，只切换后续写入者。

## 18. 优先级与里程碑

| 里程碑 | 内容 | 预期结果 |
|---|---|---|
| M0 | 正确性止血 | 全仓测试绿、消息不静默丢失 |
| M1 | 通用 Outbox | 四类任务相同投递可靠性 |
| M2 | Execution | 状态、Attempt、事件统一 |
| M3 | Answer Pipeline | 三模式统一骨架和证据合同 |
| M3.5 | 并发与 Redis | 隔离线程池、真 SSE、Redis Stream/限流 |
| M4 | Source/Search | 正确解析、索引 ACK、Hybrid Retrieval |
| M5 | Research/Artifact | 真源收敛、巨型 Service 拆分 |
| M6 | Memory/Auth/Frontend | 多用户安全和可维护 UI |

## 19. Definition of Done

一个改造项只有同时满足以下条件才算完成：

- 目标代码已启用，不只是新增未使用接口；
- 旧路径已标记弃用或删除；
- 数据迁移和回滚路径明确；
- 单元、集成和契约测试通过；
- 指标和日志可观察；
- 文档和 schema 已更新；
- 不新增未说明的中间件职责；
- 不产生第二套业务真源；
- 性能和质量指标不低于迁移前基线。

## 20. 第一批建议施工任务

按照依赖顺序，第一批可以直接拆为：

1. 修复 Backend 测试构造器；
2. 新增 conversation_message.updated_at migration；
3. 修复 Elasticsearch enabled/disabled Adapter；
4. 修复 source.index consumer group；
5. Kafka handler 失败重投与 DLT；
6. Source 状态改为 PARSED -> INDEX_PENDING -> READY；
7. 从 Artifact Dispatcher 抽通用 Outbox 内核；
8. Research 接入通用 Dispatcher；
9. Source/Wiki 移除事务内直接 publish；
10. 建 Execution 表、状态机和兼容 Task Projection；
11. Artifact 先接入 Execution；
12. Research 接入 Execution；
13. 建 AnswerRun 和 QA Strategy，普通 QA 不强制创建 Execution；
14. 建隔离线程池和指标；
15. 实现真 SSE，并接入 Redis Streams 多实例事件桥；
16. 接入 Redis 限流和并发配额；
17. 依次迁移 Note/Wiki Strategy；
18. 最后按热点数据接入 Cache-Aside。

前九项完成前，不建议开始向量检索、Redis 缓存或大规模前端拆分；线程池和 Redis Streams 应在真 SSE/Execution 边界明确后接入。

## 21. 旧文档处理

`改造计划/主项目检修与改造方案.md` 保留为 2026-07-11 历史代码审计，不再作为施工入口。

其中仍有效的具体缺陷应迁移到 Issue/Backlog，并使用以下状态：

```text
OPEN
FIXED
PARTIALLY_FIXED
SUPERSEDED_BY_ARCHITECTURE
INVALID
NEEDS_RECHECK
```

当前正式文档关系：

```text
目标系统设计
  docs/NoteWeave-v2目标系统架构与功能中间件设计.md

实施与迁移
  改造计划/NoteWeave-v2现有系统适配与改造实施方案.md

审计证据
  docs/主项目系统设计与中间件全面审计及全新优化方案.md

历史快照
  改造计划/主项目检修与改造方案.md
```
