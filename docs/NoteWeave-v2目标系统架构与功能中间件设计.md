# NoteWeave v2 目标系统架构、功能与中间件设计

> 文档状态：目标架构基线
>
> 适用范围：NoteWeave v2 Java 主系统、React 前端、Research Worker、Artifact Worker 及其基础设施
>
> 本文回答：系统最终应当如何设计、各模块负责什么、各中间件为什么存在以及如何协作
>
> 本文不负责：罗列当前代码缺陷和安排具体迁移步骤，相关内容见《NoteWeave-v2现有系统适配与改造实施方案》
>
> 配套契约：`docs/API与事件契约-v2.md`、`docs/数据库与迁移规范.md`

## 1. 系统定位

NoteWeave v2 是一个以研究工作台为基本单位的证据驱动知识工作系统。

系统围绕用户的资料、问题、笔记、知识页、研究过程和生成产物建立统一工作空间，并提供：

- 资料上传、解析、版本化和检索；
- QA、Note、Wiki 三种回答模式；
- 带来源、定位和引用的证据回答；
- Deep Research 长任务；
- Artifact/Skill 产物生成；
- Note、Wiki、Artifact、Research Report 的受控回写；
- 跨任务偏好和项目规则的 Graduated Memory；
- 完整的任务生命周期、审计、恢复和可观测性。

系统不是三个聊天产品、一个 Wiki 产品和两个 Agent 的简单拼接，而是：

> 一个 Workspace 边界内，共享资料与证据基础设施，由不同策略完成回答、研究、生成和知识沉淀。

## 2. 架构目标

目标架构必须同时满足以下要求：

1. **边界统一**：Workspace 是权限、资料和业务对象的默认隔离边界；
2. **证据优先**：模型输出不能替代来源事实，所有正式结论都可追溯到 Evidence；
3. **轻重分离**：普通 QA 保持低延迟，Research/Artifact 等重任务独立执行；
4. **生命周期统一**：短回答和长任务共享 Execution 状态、事件、重试和审计模型；
5. **业务真源明确**：MySQL 保存业务状态，Worker 不持有第二套业务真源；
6. **投影可重建**：Elasticsearch、缓存和派生视图都可以由真源重建；
7. **模块可演进**：新增回答模式、Retriever、Skill 或 Worker 时不修改核心大类；
8. **失败可恢复**：消息重复、Worker 重启、外部依赖超时不能造成重复结果或永久悬挂；
9. **默认安全**：所有 Workspace 和 Worker 接口都必须认证授权；
10. **设计与实现一致**：文档中的 Hybrid Retrieval、Outbox、SSE 等术语必须达到对应工程语义。

## 3. 总体架构选择

### 3.1 架构形态

采用：

```text
React Feature-oriented SPA
        |
        | REST + SSE
        v
Java Modular Monolith
  ├─ 业务真源与权限
  ├─ Answer/Knowledge/Source
  ├─ Execution Control Plane
  └─ Worker Contract Gateway
        |
        +-------------------------+
        |                         |
        v                         v
Research Worker             Artifact Worker
        |                         |
        +-----------+-------------+
                    |
        MySQL / Kafka / MinIO / Elasticsearch / Redis
```

Java 主系统采用模块化单体，而不是立即拆成多个微服务。原因是核心业务对象仍然高度关联，当前更需要清晰边界和一致事务，而不是更多网络边界。

Research Worker 与 Artifact Worker 保持独立进程，因为它们具有：

- 更长执行时间；
- 不同 Python/AI 工具生态；
- 独立资源和并发需求；
- 可通过命令与结果契约和 Java 解耦的执行模型。

### 3.2 控制面与计算面

系统分为两个平面：

- **控制面**：Java 中的 Execution、权限、状态、事件、预算、Outbox、结果提交；
- **计算面**：Java Inline Handler 或 Python Worker 执行检索、模型生成、验证、修复和文件生成。

Worker 不能直接决定业务对象的最终状态，也不能绕过 Java 写业务表。

### 3.3 技术能力选择原则

本项目需要体现后端工程能力，但技术组件必须解决真实问题。判断标准是：

1. 是否存在明确的并发、跨实例、一致性或恢复需求；
2. 是否有比当前方案更简单且同样可靠的实现；
3. 是否能定义故障行为、监控指标和删除条件；
4. 是否会制造第二套业务真源；
5. 是否能通过测试证明，而不只是出现在架构图中。

因此：

- 线程池用于隔离不同负载和控制背压，不用于给同步方法随意加 `@Async`；
- Redis 用于多实例短期协调、事件桥、限流和缓存，不保存最终任务状态；
- Outbox 用于必须同时完成“数据库状态变更 + 外部投递”的场景；
- Inbox 只是一种幂等手段，不要求所有消费者都落一张通用 Inbox 表；
- Execution 只承接可恢复、可取消、可重试的运行任务，不吞并所有普通业务请求。

## 4. 功能架构

### 4.1 Workspace

Workspace 是以下对象的统一容器：

- Workspace Member 与角色；
- Source 与 Source Snapshot；
- Conversation 与 Message；
- Note 与 Wiki Page；
- Research Run；
- Artifact Job 与 Version；
- Memory Signal/Object；
- Execution 与审计事件。

所有业务查询必须同时满足：

```text
Authenticated Actor
  + Workspace Membership
  + Required Permission
  + Object belongs to Workspace
```

### 4.2 资料功能

资料模块负责：

- 分片或直传上传；
- 文件去重和对象存储；
- Source/Snapshot 版本；
- 文件类型识别；
- PDF、Office、Markdown、文本、网页和 OCR 解析；
- Section/Passage/Locator 生成；
- 摘要、标签和结构化元数据；
- 检索索引投影；
- 删除、重建和投影校验。

Source 的状态只反映真实处理阶段：

```text
UPLOADED
  -> PARSING
  -> PARSED
  -> INDEX_PENDING
  -> INDEXING
  -> READY

任一阶段可以进入 FAILED，并保存 retryable/errorCode。
```

### 4.3 Conversation 与回答

Conversation 保存用户交互上下文，但聊天历史不是事实证据。

一次回答对应一个 AnswerRun：

- 关联 User Message；
- 保存 AnswerMode；
- 保存 Context Snapshot；
- 关联 Execution；
- 保存 RetrievalPlan 和 EvidenceBundle；
- 产生一个或多个 MessageRevision；
- 记录模型、Prompt、token、费用和验证结果。

### 4.4 QA 模式

QA 用于低延迟资料问答：

- Passage BM25 + Vector 召回；
- Workspace/Source/Permission 过滤；
- 多来源覆盖；
- 轻量 rerank；
- 引用对齐；
- 默认 Inline 执行；
- 超预算时允许升级为异步 AnswerRun。

### 4.5 Note 模式

Note 用于资料级阅读和结构化沉淀：

- 先筛 Source，而不是直接取全局 top-k chunk；
- 综合 metadata、已有 Note、历史引用和关系信号；
- 打开带 locator 的原文窗口；
- 生成回答、摘录卡片和 Note Draft；
- 用户确认后才写正式 Note Version。

### 4.6 Wiki 模式

Wiki 用于稳定知识网络：

- 检索 Wiki Page、Version、Citation、Backlink 和 Graph；
- 优先使用已验证稳定页；
- 输出知识网络综合回答；
- 发现冲突、缺页、过期引用和未解析链接；
- 变更以 Wiki Change Proposal 形式提交；
- 用户或策略批准后创建新 Wiki Version。

### 4.7 Deep Research

Research 是独立长任务：

- 明确 Research Intent、约束、资料范围和预算；
- Planner、Search、Read、Extract、Verify、Revise、Report；
- 支持 checkpoint、resume、branch 和 counterfactual；
- 过程证据与正式报告分离；
- 最终报告可以显式保存为 Workspace Source；
- Research Worker 仅执行计算，Java 保存 ResearchRun 真源。

### 4.8 Artifact/Skill

Artifact 是受控生成系统：

- Skill 定义输入 schema、节点图、能力、输出合同和验证策略；
- Resolver 选择 Skill/Style/Recipe；
- Capability Resolver 选择本地能力、MCP 或外部 Provider；
- Runtime 支持等待审批、等待外部回调、恢复、局部修复和导出；
- 每次生成形成 ArtifactRun；
- 成功结果形成不可变 ArtifactVersion；
- 回滚通过创建新版本表达，不修改历史版本。

### 4.9 Memory

Memory 只保存：

- 用户偏好；
- Workspace 项目口径；
- 输出格式和术语策略；
- 明确的负向规则；
- 跨任务可复用的交互约束。

Memory 不作为事实来源，不参与 Citation，也不直接决定检索结果。

生命周期：

```text
Signal
  -> Candidate
  -> Evidence Eligibility
  -> Semantic Neighborhood
  -> Conflict/Staleness Check
  -> Promotion Decision
  -> Memory Object
  -> Runtime Control Pack
  -> Application Outcome
```

## 5. 模块化单体设计

### 5.1 模块划分

```text
com.noteweave
  identity
  workspace
  source
  conversation
  answer
  retrieval
  knowledge
  memory
  research
  artifact
  execution
  platform
```

### 5.2 模块内部结构

```text
module/
  api/              HTTP DTO、Controller
  application/      Use Case、Command/Query Handler
  domain/           Aggregate、Policy、State Machine
  ports/            Repository、Gateway、Retriever 接口
  adapters/         JDBC、Kafka、HTTP、ES、MinIO 实现
```

这是约束依赖方向的结构，不要求为了形式给每个类创建接口。

### 5.3 模块依赖规则

- API 只能调用本模块 Application Use Case；
- Application 可以调用 Domain 和 Port；
- Domain 不依赖 Spring、JdbcTemplate、Kafka 或 HTTP；
- Adapter 实现 Port；
- 一个模块不能直接更新另一个模块的数据表；
- 跨模块写操作通过 Application Port 或 Domain Event；
- Query 可以使用专用 Projection，但不能绕过授权。

### 5.4 聚合所有权

| 聚合 | 所有模块 | 唯一写入口 |
|---|---|---|
| Workspace/Member | workspace | WorkspaceCommandHandler |
| Source/Snapshot | source | SourceCommandHandler |
| Conversation/Message | conversation | ConversationCommandHandler |
| AnswerRun | answer | AnswerRunHandler |
| Note/WikiPage | knowledge | KnowledgeCommandHandler |
| ResearchRun | research | ResearchCommandHandler |
| ArtifactJob/Version | artifact | ArtifactCommandHandler |
| Execution/Attempt | execution | ExecutionCoordinator |
| MemoryObject | memory | MemoryPromotionHandler |

## 6. 核心设计模式

### 6.1 Strategy：回答模式和检索策略

```java
public interface AnswerModeStrategy {
    AnswerMode mode();
    RetrievalPlan plan(AnswerCommand command, MemoryControlPack controls);
    PromptBlueprint compose(AnswerCommand command, EvidenceBundle evidence);
    AnswerResult postProcess(GeneratedAnswer answer, EvidenceBundle evidence);
}
```

QA、Note、Wiki 注册为不同策略，AnswerPipeline 不使用 switch 判断业务模式。

### 6.2 Pipeline/Template：固定回答骨架

```text
Authorize
  -> Create Run
  -> Compile Context
  -> Plan Retrieval
  -> Retrieve
  -> Rank Evidence
  -> Generate
  -> Verify/Citation Grounding
  -> Commit Revision
  -> Complete Execution
```

固定阶段由 Pipeline 管理，差异由 Strategy、Policy 和 Retriever 组合表达。

### 6.3 Command Handler 与 CQRS-lite

系统采用轻量命令查询分离：

- Command 负责授权、状态机和事务；
- Query 返回专用 Projection；
- 不建立独立读库；
- 复杂列表可以使用 SQL Projection；
- Query 不产生业务副作用。

### 6.4 State Machine

Execution、Source、Research、Artifact 都使用显式状态机。

状态推进必须满足：

- 校验 fromStatus；
- 使用 version/CAS 防止并发覆盖；
- terminal 状态默认不可逆；
- 迟到 attempt 不得覆盖新 attempt；
- 重复 callback 返回幂等结果；
- 每次合法变更产生事件。

### 6.5 Repository 与 Port/Adapter

业务模块依赖：

- `SourceRepository`；
- `ExecutionRepository`；
- `SearchIndex`；
- `ObjectStore`；
- `WorkerGateway`；
- `LlmGateway`。

Adapter 可以使用 JdbcTemplate、jOOQ、KafkaTemplate、ES Client 或 MinIO SDK。

### 6.6 Transactional Outbox 与选择性幂等

当一个业务事务既要更新 MySQL，又必须在提交后向 Kafka、Worker 或其他外部系统投递消息时，使用 Transactional Outbox：

```text
Business Transaction
  -> Aggregate Update
  -> Outbox Insert
  -> Commit
  -> Dispatcher Claim
  -> Deliver
```

不使用数据库事务中直接发 Kafka，也不依赖 `afterCommit` 作为可靠消息机制。

Outbox 并非所有事件都需要：

- 同一进程内、允许同步失败回滚的调用不需要 Outbox；
- 纯查询和可随时重建的临时计算不需要 Outbox；
- 普通 QA 的 Inline 执行不经过 Kafka，也不写发送 Outbox；
- 只有跨进程、跨中间件且不能接受丢失的副作用才进入 Outbox。

消费端幂等按场景选择，不建立万能 Inbox：

| 场景 | 推荐手段 |
|---|---|
| Worker 重复收到执行命令 | Java `claimExecution(executionId, attemptId)` + Attempt Fencing |
| Worker callback 重复提交 | `worker_callback_receipt` 唯一键 |
| ES 重复收到投影事件 | 文档 ID + aggregate version 条件更新 |
| Wiki/Knowledge 投影 | projection version/checkpoint |
| 创建领域对象的外部命令 | command receipt/inbox 唯一键 |
| 短时间网络重试抑制 | Redis `SET NX EX`，仅作为优化 |

只有当消费者会产生不可自然幂等的业务副作用，并且无法依靠聚合唯一约束或版本号去重时，才创建持久化 Inbox Receipt。

#### Outbox 实现方案选择

| 方案 | 优点 | 缺点 | 本项目结论 |
|---|---|---|---|
| MySQL Polling + Lease | 实现直观、容易测试、复用现有表和调度能力 | 有轮询延迟，需要 claim/lease | 当前首选 |
| Debezium CDC Outbox | 低侵入、高吞吐、数据库日志驱动 | 增加 Kafka Connect/Debezium 运维复杂度 | 数据量明显增长后再评估 |
| Kafka Transaction | Kafka 内部原子性好 | 无法与 MySQL 业务事务原子提交 | 不能替代 Outbox |
| Redis List/Stream Queue | 延迟低、实现快 | 无法解决 MySQL 与 Redis 双写原子性 | 不作为业务 Outbox |
| `afterCommit` 直接发送 | 代码简单 | 进程在 commit 后发送前崩溃会丢消息 | 禁止作为最终方案 |

当前采用 MySQL Polling Outbox，Dispatcher 使用数据库 lease/CAS，并通过批量 claim、合理轮询间隔和唤醒信号降低空轮询成本。未来若 Outbox 吞吐成为明确瓶颈，再迁移 Debezium CDC，而不是提前增加基础设施。

### 6.7 Policy

以下规则使用 Policy 对象表达，而不是散落 if/switch：

- Evidence selection；
- Retry/backoff；
- Execution escalation；
- Memory promotion；
- Writeback approval；
- Budget/deadline；
- Workspace permission。

### 6.8 Saga/补偿

不引入通用 Saga 框架。涉及 MySQL、MinIO、ES、Worker 的跨系统流程使用：

- Outbox；
- 幂等；
- 状态机；
- 补偿任务；
- 可重建投影。

## 7. 统一回答架构

### 7.1 核心对象

```java
AnswerCommand
ContextSnapshot
RetrievalPlan
CandidateSet
EvidenceBundle
PromptBlueprint
GeneratedAnswer
VerificationResult
AnswerResult
```

### 7.2 AnswerRun 与异步 Execution

每次回答都创建轻量 `AnswerRun`，但只有进入异步、可恢复执行路径时才创建完整 `Execution`：

```text
AnswerRun
  1 --- N MessageRevision
  1 --- 1 EvidenceBundleSnapshot

Async AnswerRun
  1 --- 1 Execution(type=CHAT_ANSWER)
Execution
  1 --- N ExecutionAttempt
  1 --- N ExecutionEvent
```

普通 QA 不需要为了“统一”额外写完整任务表、Outbox 和 Attempt；它使用 AnswerRun 自己的 `CREATED/GENERATING/COMPLETED/FAILED` 轻量状态和实时 SSE。只有满足以下条件时升级为 Execution：

- 检索或生成预计超过同步时限；
- 需要断点恢复；
- 需要排队、取消、重试或预算控制；
- 需要跨进程 Worker；
- 客户端断开后仍应继续运行。

### 7.3 Inline 与 Async

- QA 默认 Inline；
- Note/Wiki 根据资料量和预算决定 Inline/Async；
- Research/Artifact 默认 Remote Async；
- Inline Answer 订阅 Answer Event，异步任务订阅 Execution Event，两者使用相同 SSE Event Envelope；
- 执行方式不是 API 合同的一部分。

### 7.4 真流式输出

SSE 必须满足：

- Controller 在生成完成前就返回连接；
- delta 实时写给客户端；
- 事件拥有递增 sequence/id；
- 支持 `Last-Event-ID` 恢复；
- completed/failed/cancelled 是终态事件；
- 数据库不为每个 token 写一行；
- 按合理间隔保存 partial revision/checkpoint。

## 8. 检索与证据架构

### 8.1 Retriever 类型

```java
public interface Retriever {
    RetrievalKind kind();
    CandidateSet retrieve(RetrievalQuery query);
}
```

内置实现：

- PassageBm25Retriever；
- PassageVectorRetriever；
- SourceMetadataRetriever；
- NoteJournalRetriever；
- WikiPageRetriever；
- KnowledgeGraphRetriever；
- ConversationContextRetriever（仅用于理解，不作为事实证据）。

### 8.2 统一检索流程

```text
Query Understanding
  -> Scope/Permission Filter
  -> Parallel Recall
  -> Fusion
  -> Rerank
  -> Diversity/Freshness/Coverage
  -> EvidenceBundle
```

### 8.3 EvidenceItem

EvidenceItem 至少包含：

- evidenceId；
- workspaceId；
- sourceId/snapshotId；
- knowledgeItemId/versionId；
- content；
- locator；
- source type；
- retrieval score/reason；
- freshness；
- permission snapshot；
- content hash。

### 8.4 Citation Grounding

回答完成后执行：

- 引用是否指向 EvidenceBundle；
- 引用文本是否支持相邻 claim；
- locator 是否有效；
- 是否存在未引用的重要事实；
- 来源是否过期或已删除。

## 9. Execution 长任务控制面

### 9.1 数据模型

```text
execution
execution_attempt
execution_event
outbox_message
command_receipt（按需）
```

Execution 保存：

- type、target、actor、workspace；
- status、priority、version；
- budget、deadline、cancel request；
- latest attempt；
- result reference；
- error code。

### 9.2 状态机

```text
PENDING
  -> DISPATCHING
  -> RUNNING
  -> WAITING
  -> RUNNING
  -> SUCCEEDED

PENDING/DISPATCHING/RUNNING/WAITING
  -> FAILED / CANCELLED / TIMED_OUT
```

### 9.3 Attempt Fencing

Worker callback 必须携带 attemptId。只有当前 active attempt 可以：

- 更新进度；
- 写结果；
- 完成任务。

旧 attempt 的迟到回调只记录审计，不改变当前状态。

### 9.4 Execution 适用边界

必须使用 Execution：

- Research Run；
- Artifact Run；
- Source Parse/Index；
- Workspace Wiki Rebuild；
- 异步 Answer；
- 大批量导出和重建任务。

不应使用 Execution：

- 普通 CRUD；
- 快速权限查询；
- 普通列表查询；
- 能在请求内稳定完成的轻量 QA；
- 单纯的缓存刷新通知。

这样既保留统一长任务能力，又避免把系统设计成“一切皆 Task”的通用工作流平台。

## 10. Worker 架构

### 10.1 Worker 职责

Worker 可以：

- 获取版本化输入；
- 执行模型、搜索、读取和工具调用；
- 写大 checkpoint 到 MinIO；
- 上报进度、等待原因和结果；
- 在相同 commandId 下幂等恢复。

Worker 不可以：

- 直接修改 Java 业务数据库；
- 保存 Artifact/Research 的第二套最终真源；
- 自行改变 Workspace 权限；
- 使用未授权能力绕过审批；
- 用内存状态作为唯一恢复来源。

### 10.2 命令信封

```json
{
  "schema_version": 1,
  "message_id": "uuid",
  "command_id": "uuid",
  "execution_id": "uuid",
  "attempt_id": "uuid",
  "command_type": "RESEARCH_RUN",
  "workspace_id": "uuid",
  "correlation_id": "uuid",
  "causation_id": "uuid",
  "idempotency_key": "string",
  "deadline_at": "timestamp",
  "payload": {}
}
```

### 10.3 回调规则

- 强制内部认证；
- 强制 idempotency key；
- 强制 attemptId；
- progress 可以重复；
- complete/fail 只能成功一次；
- 大 payload 使用 Object Locator；
- callback schema 必须版本化。

## 11. 中间件设计

### 11.1 MySQL

职责：

- 用户、Workspace、权限；
- 业务聚合和版本；
- Execution、Attempt、Event；
- Outbox、按需 Command/Callback Receipt；
- Citation、关系和可查询元数据。

设计规则：

- Flyway 历史迁移不可修改；
- 复杂状态表使用 version 乐观锁；
- 状态使用枚举约束/check constraint；
- 高频过滤字段使用组合索引；
- JSON 只保存低频扩展和审计快照；
- 大报告和 checkpoint 不直接塞入 longtext；
- list query 使用 cursor pagination；
- 避免 mapper 内 N+1 查询。

事务规则：

```text
外部读取/计算：事务外
聚合更新 + Outbox：短事务
外部投递：事务外
结果提交：短事务
```

### 11.2 Kafka

职责：

- Java 到 Worker 的长任务命令；
- Source/Knowledge/Search 的领域事件；
- 可重建投影驱动。

不用于：

- 普通同步 QA；
- 查询 RPC；
- 保存任务当前状态；
- 替代数据库权限判断。

Topic 建议：

```text
noteweave.command.research.v1
noteweave.command.artifact.v1
noteweave.event.source.v1
noteweave.event.knowledge.v1
noteweave.event.execution.v1
noteweave.dlt.v1
```

Consumer Group 按订阅者能力命名，不按整个应用统一命名：

```text
research-worker-v1
artifact-worker-v1
es-source-projector-v1
wiki-source-projector-v1
```

可靠性：

- `acks=all`；
- producer idempotence；
- Outbox 负责发送重试；
- consumer 手动/受控提交；
- 按消费场景使用 claim、版本号、唯一键或 receipt 去重；
- 指数退避；
- DLT 和 redrive；
- message key 保证同聚合有序。

### 11.3 Elasticsearch

职责：

- Passage、Wiki、可检索 Artifact 的全文和向量投影；
- BM25、Vector、过滤和高亮；
- 可重建搜索索引。

推荐共享版本化索引，而不是每 Workspace 一个小索引：

```text
noteweave_passage_v3
```

主要字段：

- workspace_id + routing；
- source_id/snapshot_id；
- knowledge_item_id/version_id；
- title/content；
- dense_vector；
- source_type；
- locator；
- visibility；
- updated_at/content_hash。

使用 alias 完成无停机 reindex。ES 故障时允许受控降级，但不能把未索引数据标记为 READY。

### 11.4 MinIO

职责：

- 原始文件；
- 解析派生文件；
- 大型 checkpoint；
- Research Report；
- Artifact 导出文件。

Bucket：

```text
noteweave-source    原始用户或外部来源
noteweave-derived   解析结果、checkpoint、生成报告
noteweave-export    面向下载的导出物
```

对象规则：

- key 包含 workspace/domain/object/version；
- MySQL 保存 locator、hash、size、media type；
- 写入使用 checksum；
- 删除采用标记 + 异步 GC；
- 下载使用短期签名 URL 或受权代理；
- Worker 不通过共享本地路径假定对象存在。

### 11.5 Redis

Redis 不是业务真源。为了支持多实例、并发展示和后端能力建设，推荐按以下顺序接入。

#### 第一优先级：多实例 SSE 事件桥

Java 节点将 Answer/Execution 事件先持久化必要的阶段事件，再发布到 Redis Stream：

```text
Producer Node
  -> DB stage event/checkpoint
  -> XADD noteweave:events:{executionId}
  -> SSE Node XREAD BLOCK
  -> Browser
```

- Stream 使用短保留期和 `MAXLEN ~`；
- Redis 只负责低延迟分发，数据库保存可恢复的重要阶段事件；
- 浏览器携带 Last-Event-ID；
- Redis 丢失时客户端从数据库快照恢复，再继续订阅。

不推荐只用 Redis Pub/Sub，因为 Pub/Sub 无法处理短暂断线后的事件恢复。

#### 第二优先级：分布式限流

使用 Redis Lua 或成熟令牌桶实现：

- 用户级 API 限流；
- Workspace 级 Research/Artifact 并发限制；
- 外部 LLM/provider 请求速率限制；
- 上传和导出频率限制。

限流策略必须返回 retry-after，并按接口成本区分权重。

#### 第三优先级：Cache-Aside

适合缓存：

- Workspace 权限快照；
- Skill Catalog；
- Source 基础元数据；
- 高频 Wiki Index 摘要；
- 短期 Execution Query Projection。

缓存规则：

- key 带 environment/workspace/version；
- 使用 TTL + 随机抖动防止雪崩；
- 写操作提交后删除缓存，不直接维护复杂双写；
- 热点 miss 使用短锁或 single-flight 防止击穿；
- 缓存命中与回源耗时必须有指标。

#### 谨慎使用：分布式锁与幂等

- 持久任务 claim 优先使用 MySQL lease/CAS，不使用 Redis 锁代替任务真源；
- Wiki rebuild、索引全量切换等短期协调可使用 Redis 锁；
- Redis `SET NX EX` 可以抑制短时重复请求，但最终幂等仍由数据库唯一键、版本号或 receipt 保证；
- 必须设置 token、TTL、续约和安全释放，禁止无过期锁。

Redis 的职责最终限定为：

- SSE 跨节点事件桥；
- 分布式限流；
- 热点 Query Projection 缓存；
- 少量短期协调与重复请求抑制。

规则：

- 所有 key 有 TTL；
- key 带环境和 Workspace 前缀；
- Redis 故障不能造成业务数据丢失；
- 缓存失效优先使用版本号或事件；
- Redis 不可用时，核心写入和任务状态仍然正确；
- Redis Streams、Cache 和 Rate Limit 分别设置 key namespace 和容量告警。

### 11.6 HTTP 与 SSE

REST 用于：

- Command 创建；
- Query；
- Worker 获取输入；
- Worker callback；
- 文件元数据和授权下载。

SSE 用于：

- Answer delta；
- Execution progress；
- waiting/approval 状态；
- terminal event。

SSE 事件统一包含：

```json
{
  "event_id": "...",
  "execution_id": "...",
  "sequence": 12,
  "event_type": "execution.progress",
  "occurred_at": "...",
  "payload": {}
}
```

### 11.7 线程池与并发隔离

线程池用于 Bulkhead、背压和可观测并发，不使用单一全局 `@Async` 池。

推荐至少划分：

| 线程池 | 负载 | 特征 |
|---|---|---|
| `answerIoExecutor` | LLM、HTTP Retriever、外部读取 | I/O 密集，中等并发，严格超时 |
| `sourceIoExecutor` | MinIO 流式读写、轻量解析协调 | 有界队列，按文件大小限流 |
| `projectionExecutor` | ES/Wiki 投影和缓存失效 | 小并发、允许重试、按聚合有序 |
| `sseDispatchExecutor` | SSE emitter 写入和连接维护 | 不执行模型调用，不持有数据库事务 |
| `maintenanceExecutor` | GC、校验、补偿扫描 | 低优先级，避免影响在线请求 |

设计规则：

- 不使用 `Executors.newCachedThreadPool` 和无界队列；
- 核心线程、最大线程、队列容量和拒绝策略全部配置化；
- 不同外部依赖使用独立 semaphore/bulkhead，避免一个 LLM 拖死全部请求；
- 在线请求优先使用快速失败或 CallerRuns，不无限排队；
- 后台任务被拒绝时回到 Execution/Outbox 重试，不静默丢失；
- 跨线程传播 correlationId、actorId、workspaceId 和 tracing context；
- 每个池暴露 active、queued、completed、rejected、wait time 指标；
- `@Transactional` 方法不跨线程继续使用同一事务；
- CPU 密集解析不应长期占用 Java Web 线程池，必要时迁移独立 Worker。

Java 17 阶段采用 `ThreadPoolTaskExecutor` 和受控 `CompletableFuture`；若未来升级 Java 21，可评估 Virtual Threads，但仍保留外部依赖并发上限和背压，不能把虚拟线程当作无限并发。

推荐配置结构：

```yaml
noteweave:
  executors:
    answer-io:
      core-size: 16
      max-size: 32
      queue-capacity: 200
      rejection-policy: caller-runs
    projection:
      core-size: 4
      max-size: 8
      queue-capacity: 500
      rejection-policy: abort-to-outbox-retry
    sse-dispatch:
      core-size: 8
      max-size: 16
      queue-capacity: 1000
      rejection-policy: close-slow-client
```

示例值只是起始配置，最终根据任务到达率、平均等待时间、外部依赖并发上限和压测结果调整，不能用固定“CPU 核数公式”代替容量评估。

## 12. 一致性与数据流

### 12.1 Source 上传

```text
Upload Complete
  -> 保存 Source/Snapshot + Parse Command Outbox
  -> Parser 执行
  -> 提交 ParsedDocument + Index Event Outbox
  -> ES Projector 索引
  -> 回写 Index Projection Status
  -> Source READY
```

### 12.2 Chat Answer

```text
User Message
  -> AnswerRun（必要时升级为 Execution）
  -> Strategy + RetrievalPlan
  -> EvidenceBundle
  -> Streaming Generation
  -> Citation Verification
  -> MessageRevision Commit
  -> AnswerRun COMPLETED / Async Execution SUCCEEDED
```

### 12.3 Research/Artifact

```text
Domain Command
  -> Domain Aggregate + Execution + Outbox
  -> Worker receives command
  -> Java claimExecution + active attempt fencing
  -> Progress/Checkpoint
  -> Result Callback
  -> Java Validate/Commit
  -> Domain Version/Report
  -> Execution SUCCEEDED
```

## 13. API 设计

推荐资源：

```text
/workspaces
/sources
/conversations
/answer-runs
/knowledge-items
/research-runs
/artifact-jobs
/executions
```

规则：

- Mutation 支持 `Idempotency-Key`；
- list 使用 cursor；
- Detail 使用 `include=` 控制大字段；
- 错误统一 `code/message/details/correlation_id`；
- API DTO 与数据库 Row 解耦；
- 时间使用 UTC ISO-8601；
- schema 命名统一，不长期兼容 `taskId`/`task_id` 双格式；
- Worker API 与用户 API 分域和鉴权。

## 14. 安全设计

### 14.1 用户侧

- Spring Security；
- Session/JWT 二选一并有刷新/撤销机制；
- Workspace RBAC；
- 所有对象查询带 workspace constraint；
- 文件下载二次授权；
- 记录 actorId 和审计事件。

### 14.2 内部服务

- 非开发环境 token 为空则拒绝启动；
- 推荐 mTLS 或短期签名 token；
- callback 防重放；
- Worker 命令包含 deadline 和最小权限 scope；
- MCP/外部 Provider 使用 capability allowlist；
- debug route 不进入生产路由表。

### 14.3 内容与工具安全

- 上传文件类型、大小和病毒扫描；
- Prompt Injection 标记与来源隔离；
- 外部 URL SSRF 防护；
- MCP 命令、环境变量和文件路径 allowlist；
- Writeback 默认需要策略批准；
- 敏感数据不进入日志和模型遥测。

## 15. 可观测性

统一标识：

- correlationId；
- causationId；
- executionId；
- attemptId；
- workspaceId；
- actorId；
- messageId/commandId。

指标：

- API latency/error；
- Answer 首 token和总耗时；
- retrieval recall/rerank latency；
- citation coverage；
- Kafka lag；
- Outbox READY/PROCESSING/DLT；
- Redis Stream lag/length、cache hit ratio、rate-limit reject；
- 各业务线程池 active/queued/rejected/task wait time；
- Worker heartbeat/waiting；
- model token/cost；
- ES projection lag；
- MinIO error/throughput。

使用 OpenTelemetry 贯穿 Java、Worker、Kafka 和外部模型调用。

## 16. 测试架构

- Domain/state machine 单元测试；
- Strategy/Policy 契约测试；
- Java/Python JSON Schema 合同测试；
- Testcontainers 集成测试；
- Outbox crash/idempotency 测试；
- ES index rebuild 测试；
- Retrieval gold set；
- Citation precision 测试；
- Playwright 端到端冒烟；
- 架构测试限制模块依赖和跨模块 JDBC。

## 17. 非功能指标

### 性能

- QA 首 token P95 小于 2 秒，不含不可控外部模型排队；
- Execution Event 推送 P95 小于 1 秒；
- list API 默认小于 200 KB；
- Source 大文件处理不同时保留多份完整 byte[]；
- Query 数量不随列表子对象线性增长。

### 可靠性

- Command 至少一次投递、业务结果恰好一次生效；
- Worker/Java 重启不丢任务；
- 投影可全量重建；
- terminal 状态不被迟到回调覆盖；
- DLT 可观测并可 redrive。

### 可维护性

- 新增 AnswerMode 不修改 AnswerPipeline；
- 新增 Retriever 不修改业务策略核心；
- 新增 Worker transport 不修改领域模块；
- 每个状态复杂模块只有一个写入口；
- 目标架构文档、契约和代码同步版本化。

## 18. 明确禁止的反模式

- Controller 返回完整字符串冒充实时 SSE；
- 在数据库事务中直接发 Kafka 或调用远程 Worker；
- 多个模块直接更新同一业务表；
- Worker 使用内存/JSON 文件作为最终业务真源；
- 同一 topic 的不同订阅者误用同一 consumer group；
- 捕获异常后继续确认 Kafka 消息；
- 以 JSON 大字段替代所有领域建模；
- 为每个 Workspace 创建一个小 ES Index；
- 把 Memory 当事实或 Citation；
- 为一对一实现滥建接口；
- 用一个万能 TaskService 吞并所有领域语义；
- 为了展示技术栈，把所有方法都改成 `@Async`；
- 用 Redis 分布式锁替代数据库状态机和任务 lease；
- 为天然幂等的 ES upsert、缓存失效事件统一落通用 Inbox 表；
- 同一线程池同时承载 LLM、文件处理、SSE 和后台补偿；
- 未经评测直接叠加向量、图检索和 reranker。

## 19. 后端模式必要性决策表

| 设计 | 结论 | 适用场景 | 不适用场景/替代方案 |
|---|---|---|---|
| Transactional Outbox | 必需 | MySQL 提交后必须可靠发送 Kafka/HTTP | 同进程同步调用、普通 QA |
| 通用 Inbox 表 | 不全局采用 | 不可自然幂等的外部命令 | ES 用 versioned upsert；Worker 用 claim/fencing；callback 用 receipt |
| Execution | 长任务必需 | Research、Artifact、Source、异步 Answer | CRUD、普通查询、轻量 QA |
| Redis Streams | 多实例 SSE 推荐 | 低延迟事件桥、断线短期恢复 | 业务最终事件仍存 MySQL；跨服务命令仍用 Kafka |
| Redis Cache | 选择性采用 | 权限、Catalog、热点 Projection | 强一致写模型不缓存或写后失效 |
| Redis Lock | 谨慎采用 | 短期 rebuild/switch 协调 | 持久任务 claim 使用 MySQL lease/CAS |
| 业务隔离线程池 | 推荐 | LLM I/O、投影、SSE、维护任务 | 不使用无界全局线程池 |
| Kafka | 长任务/领域事件必需 | Worker 命令、可靠投影事件 | 同步查询、普通 QA、前端事件推送 |
| Saga 框架 | 暂不采用 | 当前流程可由状态机/补偿解决 | 未来跨多个独立业务服务再评估 |
| 全量 CQRS/事件溯源 | 不采用 | 当前无需独立读写存储和事件重放真源 | 使用 CQRS-lite + 审计事件 |

这张表是架构评审门槛：新增中间件或设计模式时，必须补充它解决的问题、失败行为、替代方案和退出条件。

## 20. 架构决策总结

NoteWeave v2 的最终结构应概括为：

```text
Workspace 提供边界和权限
Source 提供可版本化事实
Retrieval 生成 EvidenceBundle
Answer 统一 QA/Note/Wiki 的运行骨架
Research 和 Artifact 提供重型计算能力
Knowledge 负责受控长期沉淀
Memory 提供非事实型控制信号
Execution 提供可靠生命周期
MySQL 保存真源
MinIO 保存大对象
Elasticsearch 保存可重建检索投影
Kafka 负责跨进程传输
Redis 只提供短期协调
```

这一架构应作为后续功能、接口、表结构、中间件和 Worker 设计的最高优先级基线。

## 21. AI 运行协议与质量工程补充

### 21.1 版本化运行事件

AnswerRun 与 Execution 对外事件统一包含 `schemaVersion`、`eventId`、`sequence`、`correlationId`、运行/attempt 标识、时间和 payload。SSE、Redis Stream、数据库关键事件使用同一契约；前端对重复、乱序、断线续传和未知可选字段有确定行为。

浏览器实时协议固定为 SSE。回答 delta、Citation、状态和 Execution 摘要都通过统一 Session Event Stream 传输；控制命令继续使用普通 HTTP。最终架构不引入 Socket.IO/WebSocket，避免第二套连接、鉴权、重连和事件语义。

一个活跃 Conversation 使用一条逻辑 Session Event Stream 聚合 Answer 状态、delta、Citation 和相关 Execution 摘要，前端按 sequence reducer 更新，避免每个面板独立轮询。连接建立采用 snapshot + cursor delta，终态再以数据库快照校正。事件可在 20-50ms 窗口内批量发送，但状态、错误和终态不可丢弃；优化目标必须根据本项目请求数和端到端延迟基线制定。

### 21.2 质量、成本与延迟门禁

当前架构固定使用统一 LLM Gateway，不建设多模型路由和 OpenUI/Structured UI。每次模型、Prompt 或检索变更比较：固定样本质量、citation 确定性结果、输入/输出 token、首 token、总延迟、失败率和单位成功成本。

模型 Judge 只能作为辅助评测，不能覆盖权限、引用存在性、schema 和状态机等确定性失败。PR fixture、容器 smoke、完整 benchmark 和线上观测形成分层质量门禁。

### 21.4 Retry、Redrive、Replay

- Retry 在同一 Execution 下创建新 Attempt；
- Redrive 重新投递 DEAD/DLT 消息；
- Replay 从已验证 checkpoint 创建有来源关系的新 Execution。

Replay 不是把历史事件重新发布一遍。它必须记录原运行、checkpoint、代码/策略版本、操作者和理由；不可逆副作用重新执行前需要幂等保护或人工批准。

## 22. 容器运行架构

开发、集成测试和演示的标准运行面是 Docker Compose 全容器拓扑：Nginx Frontend、Spring Backend、Worker API/Consumer、MySQL、Kafka、Redis、MinIO、Elasticsearch。容器内通过 service DNS 通信，Frontend 以同源 `/api` 代理 Backend，SSE 代理关闭缓冲。

开发 Compose 不是生产拓扑。生产环境必须使用不可变镜像、Secret、内部网络、认证/TLS、非 root、资源限制、日志轮转、备份恢复和多副本部署；不得对公网暴露数据服务，不得使用单节点和开发默认口令宣称高可用。详细施工基线见 `改造计划/主项目重构/阶段0A-Docker全容器运行与交付基线.md`。
