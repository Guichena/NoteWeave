# 11 中间件 RAG Agent 系统设计映射

## 0. 本篇定位

这份不是代码说明，而是面试时把 NoteWeave 自然映射到“中间件八股、RAG 系统设计、Agent / Workflow trade-off”的话术库。

原本独立维护的“高频八股速答稿”已经并回这篇，不再单独保留速查稿。

面试时不要说：

```text
我用了某某类、某某方法。
```

要说：

```text
这个系统问题是什么。
我为什么选这个中间件或架构。
它解决了什么一致性、可靠性、性能、可观测问题。
它的 trade-off 是什么。
如果规模更大，下一步怎么演进。
```

## 1. 中间件职责怎么讲

### MySQL：业务事实源

**面试口径：**

MySQL 在这个项目里不是简单存数据，而是业务事实源。用户、空间权限、任务状态、文档元数据、Citation、Artifact、Memory、Eval 这些最终事实都落 MySQL。

我会重点讲三个点：第一是事务边界，比如创建业务资源和写 outbox 在本地事务里完成；第二是状态机，比如 Task、Document、Artifact 都有明确状态，不靠日志猜；第三是唯一约束和软删除，比如文件对象按 space 隔离，文档删除不立即物理删，避免引用和索引还没收敛时误删。

**能对应的八股：**

- 事务 ACID
- 本地事务边界
- 唯一索引做幂等兜底
- 软删除和数据可恢复
- MySQL 不适合承担全文检索

### Kafka：后台任务流和削峰

**面试口径：**

Kafka 在这里承担的是后台任务流，不是业务事实源。文档解析、索引、生成、评测、清理这些任务都可能耗时，所以用户请求只创建任务并返回，后面由 Kafka 推动 Worker 异步执行。

我没有用本地线程池，是因为本地线程池在进程重启、多实例扩容、失败恢复上都比较弱；也没有把 Redis 当主队列，因为 Redis 更适合短期状态。Kafka 更适合承接可重放、可消费、可水平扩展的任务流。

**能对应的八股：**

- MQ 异步解耦
- 削峰填谷
- at-least-once 与消费幂等
- 消息投递和业务 DB 的最终一致
- 消费堆积时如何排查

### Redis：短期运行态

**面试口径：**

Redis 在这个项目里主要做短期状态，而不是主业务事实源。比如 WebSocket ticket、流式输出的 seq/ack/resume、partialContent、stop 标记，以及上传分片 bitmap。

这个取舍比较重要：Redis 速度快、适合 TTL 和运行态恢复，但不适合承载需要长期审计的业务事实。所以正式的任务、消息、引用、Artifact、Memory 都落 MySQL。

**能对应的八股：**

- Redis 适合缓存/短期状态
- TTL 过期
- bitmap 记录分片状态
- Redis 异常时如何降级
- 为什么 Redis 不是最终事实源

### MinIO：对象存储

**面试口径：**

MinIO 承担的是对象存储。原始文件、解析文本、证据快照这类大对象不适合放 MySQL，放对象存储更合适。

这里的设计点是：文件内容可以复用，但权限不能复用。所以对象复用要按 space 隔离，避免两个空间上传相同 hash 文件后发生权限污染。删除也不直接物理删，而是通过软删除和 cleanup 流程逐步回收。

**能对应的八股：**

- 大对象不进数据库
- 对象存储与元数据分离
- 文件秒传 / hash 复用
- 引用计数和延迟清理
- 权限隔离不能靠 hash

### Elasticsearch：检索引擎

**面试口径：**

Elasticsearch 负责团队知识检索。它既能做 BM25 关键词检索，也能承载向量检索，还能配合 spaceId、knowledgeBaseId、status 等过滤条件。

MySQL 负责事实，ES 负责召回。ES 召回后还要回查 MySQL，确认文档没删除、知识库没归档、indexVersion 是当前版本。这是为了避免索引滞后或脏数据导致越权和错误召回。

**能对应的八股：**

- 倒排索引
- BM25
- 向量检索
- ES 与 MySQL 数据一致性
- 索引版本切换
- 检索召回后权限二次校验

## 2. RAG 系统设计怎么讲

### 为什么 Hybrid RAG？

**面试口径：**

我没有只用向量检索，因为企业知识库里很多查询是专有名词、版本号、术语、错误码，BM25 反而更稳定；但只用 BM25 又处理不好语义相似问题。所以我采用 Hybrid RAG，让 BM25、向量召回、Wiki recall 各自解决不同召回问题。

融合时不能简单拼接，因为不同召回器分数不可比，所以用 RRF 这种基于排名的融合思路。后面再做证据去重、相邻 chunk 合并、同文档限流和上下文截断，控制 Prompt 质量。

**trade-off：**

- 只用向量：实现简单，但精确匹配弱。
- 只用 BM25：关键词强，但语义泛化弱。
- Hybrid：链路复杂，但召回更稳、可解释性更好。

### 如何处理幻觉？

**面试口径：**

我不会把防幻觉只理解成 Prompt 写一句“不要编造”。我会从系统链路上做约束：检索前有权限和范围过滤，检索后有证据后处理，Prompt 里要求基于证据回答，无证据时明确兜底，回答后保存 Citation 和 Trace，最后用 Eval case 做召回和引用覆盖验证。

**能对应的设计点：**

- grounding
- citation
- no-answer / no-evidence fallback
- prompt injection 防护
- RAG Eval

### chunk 怎么设计？

**面试口径：**

chunk 粒度是 RAG 里很关键的 trade-off。太小会丢上下文，太大又浪费 token、召回不精准。NoteWeave 的做法是解析后保存 chunk，同时保留页码、offset、contentHash、indexVersion 等元信息。检索后如果相邻 chunk 都相关，可以在 evidence post-processing 阶段合并，既保留上下文，又不让索引粒度过粗。

**能对应的设计点：**

- chunk size trade-off
- overlap / adjacent merge
- token budget
- citation offset
- indexVersion 防止引用漂移

### RAG 效果怎么评估？

**面试口径：**

我不会直接编一个线上准确率。当前更合理的是用评测集和日志去验证。比如维护 RagEvalCase，跑 EvalRun，观察 recall@k、MRR、citationCoverage、latency。坏 case 再结合 RetrievalTrace 和 LLMCallLog 反查，是召回问题、证据选择问题、Prompt 问题还是模型问题。

**能对应的指标：**

- recall@k
- MRR
- citationCoverage
- latency
- no-evidence rate
- feedback 统计

## 3. Agent / Workflow 怎么讲

### 为什么不说完整 Agent？

**面试口径：**

我不会把这个项目说成完整开放 Agent 平台。当前更准确的说法是：它实现了一个可控的 Skill Pipeline 或 Workflow。

原因是开放式 Agent 的自由度高，但也带来路径不可控、成本不可控、失败难复现、结果难评测的问题。NoteWeave 这里的目标是稳定地产出研究报告、学习指南、对比分析这类 Artifact，所以更适合用固定步骤：加载上下文、选择证据、套用方法论、生成草稿、保存版本、记录日志。

**trade-off：**

- 开放 Agent：灵活，但不可控。
- 固定 Workflow：灵活性弱，但稳定、可观测、可评测。
- 当前选择：先 Workflow，后续再扩展工具调用和更复杂规划。

### MethodologyCard 怎么讲？

**面试口径：**

MethodologyCard 本质上是把生成方法论结构化。比如生成报告、学习指南、竞品对比、面试 STAR 回答，它们需要的结构和质量检查不同。如果都写死在 Prompt 里，后续扩展很难；如果完全交给用户自由 Prompt，又不可控。

所以我把 workflow、outputStructure、qualityChecklist 抽成 MethodologyCard，在生成时按场景匹配。这相当于在 LLM 外面加了一层可维护的方法论约束。

### Artifact 为什么不自动写入知识库？

**面试口径：**

因为 LLM 生成结果不等于长期知识。Artifact 可能是草稿，可能有错误，可能只是一次任务的临时产物。如果自动写入 Wiki 或个人知识库，会污染长期知识。

所以我把 Artifact 作为版本化产物，团队侧需要发布成 Wiki，个人侧需要确认后沉淀成 SynthesisCard。这个设计牺牲一点自动化，但换来长期知识质量。

## 4. System Design 追问怎么接

### 如果数据量变大怎么办？

**回答方向：**

我会先拆瓶颈：上传和解析瓶颈在对象存储、解析 Worker 和 Kafka 堆积；检索瓶颈在 ES 索引、查询过滤和召回 topK；生成瓶颈在 LLM latency 和 token 成本；Admin/Eval 瓶颈在日志和评测任务数量。

可演进方向包括：Worker 水平扩容、Kafka topic 按任务类型拆分、ES index alias 和冷热分层、Embedding backfill 分批限速、RAG Eval 离线化、LLM 调用限流和降级。

### 如果 Kafka 堆积怎么办？

**回答方向：**

先看是哪类任务堆积：文档解析、Embedding、Artifact 生成、Eval、Cleanup 的处理瓶颈不一样。不能盲目扩消费者，如果瓶颈在 ES 或 LLM，扩 Worker 只会放大下游压力。更稳的是按任务类型拆队列、限速、增加 Worker、失败任务隔离，并通过 Admin 看 task status 和 attempt 错误分布。

### 如果 ES 和 MySQL 不一致怎么办？

**回答方向：**

ES 是召回系统，不是事实源。召回后必须回查 MySQL 做权限、状态和 activeIndexVersion 校验。重建索引时也不要先删旧索引，而是写新 indexVersion，成功后再切 active。这样即使 ES 有滞后，也不会直接把脏数据暴露给用户。

### 如果用户反馈回答不准怎么办？

**回答方向：**

我会按 RAG 链路排查：先看 RetrievalTrace，确认有没有召回正确证据；再看 evidence post-processing 是否过滤掉了关键片段；再看 PromptVersion 和 LLMCallLog；最后把这个 case 加到 Eval 里，后续用 recall@k、MRR、citationCoverage 观察优化是否有效。

### 如果要做多 Agent，怎么演进？

**回答方向：**

我不会直接从当前系统跳到完全开放多 Agent。更稳的是从现有 Skill Pipeline 演进：先把工具能力标准化，再引入受控 planner，限定工具权限、预算、最大步数和可观察日志。长期可以做 researcher、writer、reviewer 这种角色分工，但前提是每个 Agent 的输入、输出、证据和失败状态都可追踪。

## 5. 高频八股最短口径

### MQ / Outbox

- `MQ 的作用`：异步、削峰、解耦、重试；在 NoteWeave 里主要承接文档解析、Source 编译、Artifact 生成、Eval 和 Cleanup 这类长任务。
- `Outbox 解决什么`：解决业务 DB 提交和消息发送之间的不一致；Task 和 TaskOutbox 同事务落库，dispatcher 后续补发。
- `为什么还会重复`：Kafka 重复投递、发送成功但标记 SENT 失败、offset 未提交都可能导致重复，所以消费侧必须按至少一次语义做幂等。

### Redis / Runtime

- `Redis 为什么快`：内存读写、事件循环、高效数据结构和 IO 多路复用。
- `在项目里存什么`：上传 bitmap、WebSocket ticket、runtime state、partialContent、event buffer。
- `为什么不当主事实源`：Redis 适合短期运行态，不适合长期审计型业务事实；正式 Task、Message、Citation、Artifact、Memory 都落 MySQL。

### Elasticsearch / RAG

- `倒排索引`：从词到文档列表，适合全文检索。
- `BM25`：适合关键词、术语、编号和精确匹配。
- `向量检索`：适合语义相近召回。
- `为什么 Hybrid RAG`：BM25、向量和 Wiki recall 各补不同误差。
- `为什么 RRF`：不同召回器分数不可比，基于排名融合更稳。

### WebSocket / Memory

- `为什么不用 HTTP 或 SSE`：NoteWeave 需要 stop、resume、ack 和双向控制，WebSocket 更合适。
- `ack 的作用`：客户端告诉服务端已经收到哪条事件，resume 时只重放未确认部分。
- `为什么不把历史全塞 prompt`：token 成本高、噪声大、隐私风险高、旧错误会反复污染后续生成。

### Workflow / Agent

- `Workflow 和 Agent 区别`：Workflow 是系统预先定义步骤，Agent 是模型自主规划和工具调用。
- `当前为什么主讲 Workflow`：当前更重视可控、可审计、可评测和可回滚，不把开放式自主性讲成已落地事实。
- `MCP 怎么答`：当前没有做完整开放平台，但已经把 B 站解析能力拆成远程 MCP tool service，并接到 Studio 产物入口和对话触发；更广义的多工具协议化接入仍然可以继续沿 Tool / Skill 或 Source import 层扩展。

### 指标 / 效果

- `QPS / P95 / P99`：分别是每秒请求数、95% / 99% 请求的响应时间上界。
- `没有生产指标怎么答`：不编造，讲现有观测面、Eval case 和压测方案。
- `citation coverage 高是不是就正确`：不是，只能说明答案有引用覆盖，还要看引用是否真正支撑 claim。

## 6. 最稳的收口

如果最后要收束，可以这样说：

> 我这个项目不是为了把大模型能力包装得很玄，而是把 AI 知识应用拆成几个经典系统问题：权限隔离、异步任务最终一致、对象存储、混合检索、证据追踪、生成工作流、长期记忆和可观测运维。我的设计取舍是尽量让模型能力可控、让生成结果可追溯、让失败能定位，而不是只追求一次回答看起来很智能。
