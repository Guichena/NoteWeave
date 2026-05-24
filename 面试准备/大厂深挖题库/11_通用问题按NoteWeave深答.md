# 文件：11_通用问题按NoteWeave深答.md

## 1. 来源与使用方式

本文档从 `D:/java-projects/面试整理/待按实际补充的问题清单.md` 中提取通用问题，并结合 NoteWeave 当前仓库进行回答。原清单里包含一些明显属于其他项目或不符合 NoteWeave 当前事实的题，比如订单 ID、优惠券、分库分表生产规模、完整 Multi-Agent、GraphRAG 主链路、MCP 主链路、Bibtex 端到端等。本文只保留可迁移到 NoteWeave 的通用问法，并给出诚实回答边界。

回答原则：

- 先从架构和业务闭环切入，再深入到具体模块、表、状态和链路。
- 已实现能力可以坚定讲；当前没有证据的能力要说成扩展方向。
- 不编造 QPS、P99、token/day、线上用户量、准确率等生产指标。
- 面试中如果被问到其他项目专有词，要主动转成 NoteWeave 的对应能力或明确“不属于当前项目主链路”。

## 2. 通用问题提取结果

### 项目主述类

- 请介绍一下这个项目。
- 这个项目解决了什么问题，业务价值是什么？
- 你负责哪一部分，如何体现 ownership？
- 项目的亮点、难点分别是什么？
- 这个项目是否打通闭环？没正式上线时价值怎么体现？
- 如果让你详细展开讲项目，你会怎么讲？

### 架构设计类

- 项目整体架构是怎样设计的？
- 为什么要这么设计，为什么不用别的方案？
- 如果从单体扩展到分布式架构，你会怎么改？
- 为什么这里要异步处理，而不是同步写数据库？
- 如何把复杂技术方案讲给业务方、产品或上下游？

### 中间件与工程类

- 项目里为什么用 Redis，职责和边界是什么？
- 项目里使用了什么 MQ？有没有事务消息？消费者怎么设计？
- 幂等怎么做？重复消费、重复请求怎么处理？
- 有没有做分库分表、二级缓存、本地缓存？如果没有怎么回答？
- 挑一个复杂接口详细讲一下。

### AI / RAG / Agent 类

- 实际用的是什么模型？为什么选它？
- 大模型幻觉怎么处理？
- system prompt 怎么设计？
- 文档切分用什么方案，为什么这么做？
- 搜索接入是关键词、向量、混合检索还是别的方案？
- 记忆系统如何设计，短期记忆、长期记忆、summary 分别怎么存？
- 这是固定工作流还是 Agent？有没有 ReAct、LangChain、Spring AI、GraphRAG？

### 指标与效果类

- 项目优化后效果提升了多少？
- 有没有压测？QPS、RT、P95、P99、错误率是多少？
- 模型效果怎么判断？准确率、召回率、citation coverage 怎么定义？
- 每日 token 消耗是多少？
- 怎么证明某个指标真实可靠？

### 个人与沟通类

- 这个项目是独立完成还是协作完成？
- 做了多久，开发周期如何分阶段推进？
- 遇到分歧怎么对齐？
- 怎么用 AI Coding？AI 生成代码不符合预期怎么办？
- 遇到压力题和开放题怎么回答？

## 3. 项目介绍：如何从整体架构讲清 NoteWeave

### 对应原问题

- 请介绍一下这个项目。
- 这个项目解决了什么问题？业务价值是什么？
- 如果让你详细展开讲项目，你会怎么讲？
- 这个项目是不是已经打通闭环？

### 深入回答

NoteWeave 可以从“AI 知识工作台”这个定位讲起。它解决的不是单次问答问题，而是知识从资料进入系统、被解析检索、被引用生成、再沉淀为长期知识的完整闭环。

整体架构上，我会把它分成四层：

第一层是基础域：Auth、User、Space、Permission。`Space` 是最高业务容器，分成 `TEAM` 和 `PERSONAL` 两种类型。团队空间解决多人协作、知识库、团队 RAG、Wiki 发布；个人空间解决 ResearchProject、Source、Card、Artifact、Memory。这个边界让权限、检索、沉淀和长期记忆都有清晰归属。

第二层是异步与存储底座：MySQL 保存业务状态和关系，MinIO 保存文件、解析文本和 citation snapshot，Elasticsearch 做 BM25、向量和 Wiki 检索，Kafka 承载长任务分发，Redis 承载 WebSocket runtime、ticket、短期状态。长任务统一走 `Task + TaskAttempt + TaskEvent + TaskOutbox + Kafka + Worker`。

第三层是团队知识闭环：团队文档经过分片上传、FileObject 复用、Document 创建、异步解析、Chunk 切分、ES 索引，最终进入 Hybrid RAG。用户提问时，系统通过 BM25、Vector、Wiki recall 多路召回，用 Weighted RRF 融合，再经过 EvidencePostProcessor 处理证据，最后生成带 Citation 的回答。

第四层是个人研究闭环：个人 Source 导入后必须产出可读文本，再由 WikiCompiler 编译为 ArticleCard 和 ConceptCard，并通过 evidence backtrace 回到 Source 原文。Artifact 生成时加载卡片、证据和 MethodologyCard；生成结果默认只是 Artifact，用户确认后才沉淀为 SynthesisCard。

这个项目的业务价值在于：它不是让用户“问一次答一次”，而是把知识工作流变成可追踪、可复用、可沉淀的系统。团队能把资料沉淀成可信 Wiki，个人能把研究资料沉淀成结构化卡片和成果，管理员还能通过 Trace、LLMLog、Eval、Health 和 Audit 做排障。

### 可直接复述的面试回答

NoteWeave 是一个 AI 知识工作台，核心解决的是知识从资料进入系统，到被检索引用，再到生成成果，最后沉淀为长期知识的闭环问题。它分成团队和个人两条主线：团队侧是 KnowledgeBase、Document、Chunk、Hybrid RAG、Citation、Wiki；个人侧是 ResearchProject、Source、ArticleCard、ConceptCard、Artifact、SynthesisCard。底层复用 Space/Permission 权限模型、Task/Outbox/Kafka 异步任务、MinIO 对象存储、Elasticsearch 检索、Redis WebSocket runtime、MySQL 业务状态，以及 LLM 日志、RetrievalTrace、Eval 和 Admin/Ops。它和普通 RAG Demo 的区别是，不只回答问题，还强调权限、证据、版本、沉淀和可运维。

### 常见追问

- 你这个项目最核心的技术难点是什么？
- 为什么要同时有团队侧和个人侧？
- 如果没有正式上线，怎么证明阶段价值？

### 追问回答要点

项目最难的不是调 LLM，而是把 AI 输出放到一个受权限、证据、版本和长期知识治理约束的工程系统里。没有正式生产上线时，可以用本地可验收闭环、集成测试、演示数据、Trace/Eval 能力证明工程完整度，但不能编造线上规模。

## 4. Ownership 与项目亮点：如何讲“我具体做了什么”

### 对应原问题

- 你负责哪一部分？
- 如何体现 ownership？
- 项目里最有亮点的地方是什么？
- 最难的点是什么？你怎么解决？

### 深入回答

NoteWeave 里最适合作为 ownership 主线的不是“我做了所有功能”，而是挑三条可深挖链路讲清楚。

第一条是统一异步任务骨架。项目里上传解析、Source 编译、Artifact 生成、Wiki 入索引、Eval、Cleanup 都是长任务。如果每个模块各写一套后台逻辑，会出现状态、重试、取消、审计和前端进度不统一的问题。所以我把它们收敛成 `Task + Attempt + Event + Outbox + Kafka + Worker`。这体现的是架构 ownership：不是只完成某个接口，而是搭了后续模块复用的执行底座。

第二条是 evidence-first RAG。团队问答不是直接把用户问题扔给 LLM，而是先校验权限，再按 session scope 检索，经过 BM25、Vector、Wiki recall 和 RRF 融合，再做证据后处理，最后保存 Citation 和 RetrievalTrace。这体现的是 AI 工程 ownership：关注答案能否被证据支撑、能否被排查，而不是只看模型输出是否自然。

第三条是生成结果沉淀边界。Artifact 不等于 Wiki，也不等于 Card。生成结果默认只是版本化 Artifact，团队要人工发布成 Wiki，个人要 proposal/confirm 后才沉淀成 SynthesisCard。这体现的是产品和数据治理 ownership：避免 AI 输出自动污染长期知识库。

### 可直接复述的面试回答

我会把自己的 ownership 讲成三条主线。第一是统一异步任务底座，把文档解析、Source 编译、Artifact 生成、Wiki 索引、Eval 和清理都收敛到 Task/Outbox/Kafka/Worker，而不是每个模块各写后台逻辑。第二是团队 RAG 的 evidence-first 链路，从权限过滤、混合检索、RRF 融合、证据后处理，到 Citation 和 RetrievalTrace 持久化，保证回答可追踪。第三是 Artifact 到长期知识的沉淀边界，生成结果不会自动污染 Wiki 或 Card，而是通过发布或确认进入长期知识。最难的是这些链路不是孤立功能，而是要同时考虑权限、一致性、失败恢复和可观测。

### 常见追问

- 你怎么证明这些不是简单功能堆叠？
- 如果只能讲一个亮点，讲哪个？
- 这些模块之间如何复用？

### 追问回答要点

如果只能讲一个亮点，优先讲 Task/Outbox/Kafka 或 RAG/Citation。前者体现后端系统设计，后者体现 AI 工程深度。

## 5. 架构设计：为什么这么设计，而不是用更简单方案

### 对应原问题

- 项目整体架构是怎样设计的？
- 为什么要这么设计？为什么不用别的方案？
- 如果从单体扩展成分布式架构，你会怎么改？

### 深入回答

NoteWeave 当前是 Spring Boot 单体应用，但不是无边界单体。它在包结构和业务模型上已经按 Auth/Space/Permission、Task、Team Document、RAG/Search、Personal Research、Artifact、Memory、Admin/Ops 做了模块划分。

为什么当前不一开始拆微服务？因为项目仍处于产品原型和工程工作台阶段，很多能力需要快速迭代，且权限、Task、Citation、Artifact、Memory 之间有较强事务和领域关系。过早拆服务会带来分布式事务、远程调用、接口版本和权限一致性的复杂度，反而降低迭代效率。

如果未来扩展成分布式，我会按资源特征和解耦程度拆：

- 第一优先级拆 Worker 执行侧。文档解析、embedding backfill、Source compile、Artifact generate、RAG eval、cleanup 本来就通过 Task/Kafka 解耦，适合独立部署和水平扩容。
- 第二优先级拆检索/索引服务。ES 查询、索引写入、RRF、SearchDebug 对资源消耗和扩展策略有独立特点。
- 第三优先级拆 LLM Gateway 和 Eval/Observability。它们涉及模型调用、prompt version、token、latency 和评测闭环。
- 权限和 Space 不宜过早拆，因为它们是所有资源访问的基础域，拆出去会让每次资源访问都依赖远程鉴权，必须配缓存和一致性策略。

### 可直接复述的面试回答

当前我选择模块化单体，而不是一开始微服务，是因为项目还在快速迭代阶段，权限、Task、Citation、Artifact 和 Memory 之间有很多强关系，单体能降低事务和调用复杂度。但我在设计上保留了清晰边界：任务执行通过 Task/Kafka 解耦，检索通过 ES 边界解耦，LLM 调用通过 ObservedLlmGateway 收口。如果未来拆分，我会先拆 Worker，因为它最适合独立扩容；再拆检索索引服务；最后拆 LLM gateway 和 eval。权限和 Space 会最谨慎，因为它们是全局访问控制基础。

### 常见追问

- 拆 Worker 后如何避免重复执行？
- 权限服务拆出去后如何保证性能？
- 微服务后 outbox 还怎么做？

### 追问回答要点

拆服务不是按包名机械拆，而是看资源特征、事务边界和扩展需求。Task/Kafka 是天然边界，权限域不是第一优先级。

## 6. 异步与 MQ：为什么不用同步写入，Kafka 怎么用

### 对应原问题

- 为什么这里不直接写数据库，而是异步写入？
- 项目用的是什么 MQ？有没有事务消息？
- 消费者怎么设计？
- 幂等怎么做？
- 如果换成下单场景还能这么做吗？

### 深入回答

NoteWeave 中不是所有写入都异步。用户注册、创建 Space、创建 session、保存 message 这类短事务是同步写 MySQL。异步处理的是耗时长、失败概率高、依赖外部系统的任务，例如文档解析、ES 索引、embedding backfill、Source 编译、Artifact 生成、Wiki 入索引、RAG Eval、资源清理。

为什么这些要异步？以文档上传为例，merge 后如果同步解析 PDF、切 chunk、写 ES、生成 embedding，HTTP 请求会变得很慢，并且任何一步失败都会让用户上传体验不稳定。更合理的做法是：同步阶段只保证文件对象和 Document 元数据可靠落库；解析和索引交给后台 Worker，前端通过 Task 状态看进度。

Kafka 在项目里承担长任务分发职责。业务服务创建 Task 时，同一事务写 TaskEvent 和 TaskOutbox；`TaskDispatcher` 后续扫描 outbox 并投递 Kafka。这里没有依赖 Kafka 事务消息，而是使用 outbox pattern 解决业务 DB 和 Kafka 之间的最终一致性。

消费者设计上，Worker 收到消息后不信任消息体，只用 taskId 回查 DB。只有 Task 是 PENDING 才能 claim 成 RUNNING，并创建 TaskAttempt。重复消费时，如果 Task 已经 SUCCESS、FAILED 或 RUNNING，就不会重复执行副作用。任务输入里的 idempotencyKey 用于防止重复创建任务；Worker 的状态机用于防止重复执行任务。

如果换成下单场景，原则类似但边界不同。订单主状态一般要同步创建并返回明确结果；库存扣减、支付回调、物流通知、积分发放等可以异步。关键是区分“用户必须立刻知道结果的核心事务”和“可最终一致的后置动作”。

### 可直接复述的面试回答

项目里不是所有写入都异步，核心业务状态仍然同步写 MySQL。异步的是文档解析、索引、Source 编译、Artifact 生成、Eval 和 cleanup 这类长任务。设计上先创建 Task 和 TaskOutbox，再由 dispatcher 投递 Kafka，Worker 消费后回查 DB 状态并执行。这里用的是 outbox pattern，而不是直接在业务事务里发 Kafka，因为 DB 和 Kafka 不能保证本地原子性。Worker 只信 taskId 和 DB 状态，只有 PENDING 任务能转 RUNNING，重复消费会被状态机挡住。这个思路换到下单场景也成立，但订单创建这种核心状态要同步，发券、积分、通知这类后置动作才适合异步。

### 常见追问

- DB 成功 Kafka 失败怎么办？
- Kafka 发成功但标记 outbox SENT 失败怎么办？
- RUNNING 任务取消怎么办？

### 追问回答要点

DB 成功 Kafka 失败由 outbox 补偿；Kafka 重复投递由 Worker 幂等处理；RUNNING 任务不强杀，设置 cancelRequested，由 Worker 在安全点停止。

## 7. Redis：职责、数据结构和边界怎么讲

### 对应原问题

- 为什么项目里要用 Redis？
- Redis 在项目中的职责是什么，业务边界在哪里？
- 用了哪些数据结构？
- Redis 实际 QPS、P99 怎么回答？
- 本地缓存和 Redis 怎么配合？

### 深入回答

NoteWeave 中 Redis 的定位要讲清楚：它不是主任务队列，也不是长期记忆数据库，而是短期运行态和临时状态存储。

Redis 主要用于三类场景：

第一类是上传进度。分片上传时用 Redis bitmap 或等价 key 记录 uploadId 下已上传 chunk，帮助快速返回上传进度。但 Redis 不是唯一事实源，最终 merge 仍要检查 UploadChunk 表和 MinIO object 是否存在。

第二类是 WebSocket runtime。WebSocket ticket、RuntimeState、StreamState、ShortTermContext、事件 buffer、partialContent 都适合放 Redis，因为它们读写频繁、生命周期短、需要 TTL，丢失后不会破坏已持久化的正式消息和 Citation。

第三类是临时控制状态。例如 stop/resume、ack 之后的事件重放，依赖 Redis 存储最近事件和 partialContent。

Redis 的边界也要讲清楚：长期 Memory 存 MySQL；Task 主链路走 Kafka，不走 Redis Stream；业务权限和 Citation 关系存 MySQL；Redis 丢失会影响 runtime 恢复体验，但不应该让正式数据丢失。

如果被问 Redis QPS/P99，不要编造。可以说当前仓库没有生产压测数字，但可以通过 WebSocket runtime 压测、上传并发压测、Redis command latency、key count、memory usage、slowlog 来评估。

当前项目没有实现本地缓存 + Redis 二级缓存主链路。如果被问本地缓存，应诚实说没有把它作为核心方案。后续如果要加，可以考虑缓存配置类数据、PromptVersion、Methodology preset，但必须处理更新通知、TTL、版本号和手动失效。

### 可直接复述的面试回答

Redis 在 NoteWeave 里主要承载短期状态，不承载长期业务事实。上传链路里它记录分片进度，但 merge 时仍以 DB 和 MinIO 校验为准；WebSocket 链路里它保存 ticket、runtime state、stream state、事件缓冲和 partialContent，用来支持 stop、resume 和断线恢复；长期 Memory、Task 状态、Citation 关系仍然在 MySQL，主异步任务也走 Kafka 而不是 Redis Stream。这样 Redis 丢失最多影响临时运行态恢复，不会破坏正式消息和证据链。至于 QPS/P99，当前没有生产数据，我会用压测和 Redis latency/slowlog 来验证，而不会编造数字。

### 常见追问

- Redis 挂了系统还能用吗？
- 为什么不把 Task 队列也放 Redis？
- Redis 中的 runtime state 和 MySQL message 有什么区别？

### 追问回答要点

Redis 挂了会影响 WebSocket ticket/resume 和上传进度体验，但不应影响已落库消息、Document、Citation、Task。Task 用 Kafka 是因为需要更可靠的任务分发和消费组语义。

## 8. RAG、模型与幻觉治理：从检索到回答怎么讲

### 对应原问题

- 实际用的是什么模型？为什么选它？
- 大模型会出现幻觉吗？怎么处理？
- system prompt 怎么设计？
- 搜索接入是关键词、向量还是混合检索？
- 文档切分用什么工具？为什么这么做？
- 模型效果怎么判断？

### 深入回答

NoteWeave 的模型层通过 LLM client 抽象和 `ObservedLlmGateway` 收口。仓库支持 OpenAI-compatible API 和 StubLlmClient，本地开发和测试可以使用 stub。面试时不要绑定一个没有仓库证据的具体商业模型版本，也不要编造上下文窗口。可以说项目设计上支持外部模型 provider 配置，模型调用会记录 scene、promptVersion、token、latency、错误等观测信息。

幻觉治理不能只靠 prompt。NoteWeave 的策略是 evidence-first：

- 检索前做权限过滤，避免越权资料进入上下文。
- 用 BM25、Vector、Wiki recall 做混合召回。
- 用 Weighted RRF 融合，避免不同检索分数不可比。
- 用 EvidencePostProcessor 做去重、相邻 chunk 合并、同文档限流、上下文裁剪。
- 无证据时返回明确兜底，不让模型自由发挥。
- Prompt 中明确要求基于证据回答，并使用引用编号。
- 最终保存 Citation，支持反查 source、chunk、version、snapshot。
- 通过 RetrievalTrace 和 LLMCallLog 排查坏回答。

system prompt 的设计核心是约束模型角色、证据使用规则、引用格式、无法回答时的兜底策略和 prompt injection 防护。PromptVersion 的价值是让后续排查知道当时到底用了哪个版本的提示词。

文档切分方面，项目使用 DocumentParserService 解析 PDF/Markdown/TXT，再由 ChunkService 切分。切分要保留 chunkIndex、contentHash、pageNo、offset、indexVersion 等信息，原因是 Citation 需要回溯，索引重建需要版本化。

模型效果不能只说“感觉不错”。应从 recall@k、MRR、citation coverage、no-evidence rate、answer feedback、LLM latency、token usage 等维度评估。当前仓库已有 Eval/Trace 能力，但没有生产准确率数字。

### 可直接复述的面试回答

NoteWeave 治理幻觉不是只靠一句 prompt，而是把模型放在 evidence-first 链路里。用户提问后，系统先按权限和 session scope 检索，再用 BM25、向量和 Wiki recall 做混合召回，用 weighted RRF 融合，经过 EvidencePostProcessor 做去重、相邻合并、同文档限流和上下文裁剪。如果没有证据，就返回明确无证据兜底；如果有证据，prompt 会要求模型基于证据和引用编号回答。回答后 Citation、RetrievalTrace、LLMCallLog 都会持久化，方便排查。模型层通过 OpenAI-compatible client 和 stub 抽象，具体 provider 可以配置，当前我不会编造某个生产模型版本和 token 规模。

### 常见追问

- 如果向量召回失败怎么办？
- citation coverage 高是否代表答案一定正确？
- Prompt injection 怎么防？

### 追问回答要点

向量失败 fallback 到 BM25；citation coverage 只代表答案有证据覆盖，不等于事实完全正确；prompt injection 要通过证据格式隔离、系统规则、只信检索证据和输出约束共同处理。

## 9. Memory 与会话恢复：短期、长期、summary 怎么设计

### 对应原问题

- 记忆系统怎么设计？
- 短期记忆、长期记忆和 summary 分别存在哪？
- 长期记忆什么时候写入？哪些不写？
- 会话中断后的状态恢复怎么做？

### 深入回答

NoteWeave 的记忆分为运行态短期状态和长期记忆两类。

短期状态主要服务 WebSocket runtime，存在 Redis。包括 RuntimeState、StreamState、ShortTermContext、event buffer、partialContent。它解决的是“当前这轮生成到哪里了、能不能 stop、刷新后能不能 resume”。

长期记忆存在 MySQL，包括 session_summary、memory_item、space_memory、user_memory。它解决的是“正式会话之后，哪些稳定信息能帮助后续回答”。

写入策略由 `MemoryWritebackStrategy` 控制：

- DRAFT 会话不写。
- 空轮次不写。
- 短问候不写。
- 包含 password、token、secret、api-key、email 等敏感信号的内容不写。
- 稳定偏好写 user memory。
- 有意义的正式轮次写 session summary 和 space memory。

读取策略由 `ContextReadRouter` 控制。DRAFT 只读 recent history 和 retrieval evidence，不读长期 memory；FORMAL TEAM_CHAT 可以读 recent history、session summary、space memory、user memory 和 retrieval evidence。这样避免把所有历史都塞进 prompt。

会话恢复方面，WebSocket 使用 Redis 事件缓冲和 ack。客户端刷新后发送 `chat.resume`，服务端重放 ack 之后的事件，并返回 partialContent 和 runtimeStatus。FORMAL 完成后才落 MySQL assistant message 和 Citation。

### 可直接复述的面试回答

NoteWeave 的记忆不是把所有聊天历史塞进 prompt，而是分层设计。短期运行态放 Redis，服务 WebSocket 的 stop、resume、partialContent 和事件重放；长期记忆放 MySQL，包括 session summary、space memory、user memory。写入上 DRAFT 不写，敏感信息不写，短问候不写，稳定偏好才写 user memory；读取上由 ContextReadRouter 决定不同会话读取哪些层。这样既能保持连续性，又能控制 token、隐私和噪声。断线恢复时，客户端带 ack resume，服务端从 Redis 重放未确认事件并返回 partialContent；正式完成后才把 assistant message 和 Citation 落库。

### 常见追问

- Redis 丢了会不会丢正式回答？
- Memory 越写越多怎么办？
- 用户关闭记忆怎么办？

### 追问回答要点

Redis 丢失影响运行态恢复，不影响已持久化正式消息。Memory 治理靠 TTL、pin、置信度、用户可管理、写入过滤和读取计划。用户关闭 memory 后停止新写入。

## 10. Agent、Skill、Spring AI、LangChain、GraphRAG：如何守住事实边界

### 对应原问题

- 这是固定工作流还是 Agent？
- 你们用 Spring AI 还是自己用 Prompt/工作流控制输出？
- 有没有 ReAct、LangChain、LangGraph、GraphRAG、MCP？
- Modular Agent、多工具调度、多 Agent 怎么设计？

### 深入回答

NoteWeave 当前主链路不是完整开放 Agent 平台，也不是多 Agent 系统。最诚实、最强的说法是：它实现的是受控的 AI 生成工作流和 Skill pipeline。

具体来说，`ArtifactPlanExecutor` 会根据 ArtifactType 选择固定 plan，例如 REPORT 包含 LoadGenerationContextSkill、SelectEvidenceSkill、GenerateReportSkill、SaveArtifactSkill；WORK_PREP 包含 LoadGenerationContextSkill、SelectConceptCardSkill、GenerateWorkPrepSkill、SaveArtifactSkill。每一步都有明确输入输出、进度、取消点和 SkillExecutionLog。

这和开放 Agent 的区别是：

- 当前没有让模型自由决定任意工具调用。
- 当前没有多 Agent 之间的通信协议。
- 当前没有 ReAct 循环主链路。
- 当前没有 MCP 作为主工具协议。
- 当前没有 GraphRAG 作为团队 RAG 主链路。

为什么这么设计？因为在知识工作台里，可信、可审计、可取消比“模型自由发挥”更重要。固定 pipeline 牺牲了一些灵活性，但换来了更强的可控性、可观测和失败恢复。

如果面试官问后续如何演进成 Agent，可以说要补工具 schema、权限声明、沙箱、预算控制、循环终止条件、tool call log、回滚策略、eval 指标等。

### 可直接复述的面试回答

我不会把 NoteWeave 当前实现夸成完整 Agent 平台。当前更准确的定位是受控生成工作流。比如 Artifact 生成会按固定 plan 执行 LoadGenerationContext、SelectEvidence、GenerateReport、SaveArtifact，每一步都有日志、进度、取消点和脱敏记录。这样做是因为知识系统里可控、可审计、可追踪比开放式工具调用更重要。Spring AI、LangChain、LangGraph、MCP、GraphRAG 这些都可以作为未来扩展方向，但当前仓库主链路没有强依赖它们。如果未来要做开放 Agent，需要补工具权限、沙箱、预算、循环终止和评测体系。

### 常见追问

- 固定工作流是不是不够智能？
- 为什么不用 ReAct？
- 未来接 MCP 会放在哪层？

### 追问回答要点

固定工作流适合高可信场景；ReAct 适合开放探索但需要更强安全和评测；MCP 可以接在 Source import 或 Skill/tool 层，但当前不是主链路。

## 11. 指标与效果：没有生产数据时怎么回答

### 对应原问题

- 优化后效果提升多少？
- 有没有压测？QPS、RT、P95、P99 是多少？
- 错误率、超时率、成功率有变化吗？
- 每日 token 消耗多少？
- 怎么证明准确率或召回率真实可靠？

### 深入回答

这个问题最重要的是不要编造。NoteWeave 当前仓库是工程原型和本地可验收项目，没有真实线上流量、生产 QPS、P99、每日 token 消耗。面试中应该回答已有可观测面和未来如何验证。

可以讲的指标面：

- 任务链路：Task pending/running/success/failed 数量、attempt latency、retry count、cancel count、Kafka lag。
- 上传链路：chunk upload success rate、merge latency、MinIO object error rate。
- 检索链路：retrieval latency、BM25/vector/wiki hit count、fusion count、fallbackUsed、no-evidence rate。
- 生成链路：LLM latency、input/output tokens、timeout rate、error code。
- 证据质量：citation coverage、source-backed evidence ratio、citation permission failure。
- Eval：recall@k、MRR、citation coverage、latency、bad case 分类。
- 运维：SystemHealthSnapshot、cleanup job success/failure、AuditLog。

怎么证明指标可靠？要有 case 集、固定评测口径、可重复运行、版本记录、Trace 保留和人工抽样。比如 recall@k 需要定义标准答案或标准证据；citation coverage 需要定义答案中关键 claim 是否有 citation 支撑；准确率不能模糊说“92%”，必须说明样本来源、标注标准、统计方法和置信范围。

### 可直接复述的面试回答

当前我不会给 NoteWeave 编造生产 QPS、P99 或每日 token，因为这个仓库没有真实线上流量。更准确的说法是：项目已经有观测和评测入口，可以支撑后续验证。任务侧可以看 Task 状态、attempt latency、retry 和 Kafka lag；检索侧可以看 retrieval latency、BM25/vector/wiki 命中量、fusion count、fallbackUsed 和 no-evidence rate；生成侧可以看 LLM latency、token、timeout；质量侧可以通过 Eval 统计 recall@k、MRR、citation coverage，并结合 AnswerFeedback 和人工 bad case 复盘。这样回答比给一个没有依据的数字更可信。

### 常见追问

- 如果面试官一定要数字怎么办？
- citation coverage 怎么定义？
- 没上线怎么证明项目价值？

### 追问回答要点

可以说“目前没有生产数字，我不会虚构；如果要上线前验证，我会设计压测和 eval”。项目价值体现为工程闭环、集成测试、本地验收、可观测和可扩展设计。

## 12. 分库分表、本地缓存、网关、注册中心：不符合当前项目时怎么转答

### 对应原问题

- 项目有没有分库分表？
- 为什么这么拆，分片键是什么？
- Redis + 本地缓存怎么更新？
- 有没有网关、注册中心、配置中心？
- HTTP SDK 改 RPC 怎么改？

### 深入回答

这些问题在原清单里很常见，但不应该硬套到 NoteWeave。

当前 NoteWeave 没有实现分库分表。正确回答是：当前项目处于原型和工程工作台阶段，数据规模没有到必须分库分表的证据，优先通过空间隔离字段、索引、分页、归档、ES 检索和 Task 异步化解决。如果未来文档量和 citation/trace 量上升，可以优先考虑日志归档、冷热分层、按 spaceId 或时间分区，而不是一开始就分库分表。

当前项目没有本地缓存 + Redis 二级缓存主链路。正确回答是：Redis 主要用于短期 runtime 和上传进度；配置类数据如 PromptVersion、Methodology preset 未来可以做本地缓存，但要配合版本号、TTL、主动失效或消息通知。

当前项目没有微服务网关、注册中心、配置中心主链路。正确回答是：因为当前是模块化单体，不需要服务发现。未来拆 Worker、Search、LLM Gateway 后，才需要服务注册、配置管理、网关鉴权和 RPC 设计。

HTTP 改 RPC 的问题可以从边界回答：如果拆成服务，内部高频调用可以用 RPC，但跨服务权限、超时、重试、幂等、traceId、错误码映射都要设计。外部 API 仍然保留 HTTP/REST 更合适。

### 可直接复述的面试回答

NoteWeave 当前没有分库分表和本地缓存主链路，我不会硬说做了。当前阶段主要通过 Space 隔离字段、合理索引、分页、归档、ES 检索和异步 Task 来承载数据增长。如果未来 citation、trace、llm log 变成大表，我会先做保留期、归档和按时间或 space 维度分区，再考虑分库分表。当前也没有网关和注册中心，因为还是模块化单体；如果未来拆 Worker、Search 或 LLM Gateway，才需要服务发现、配置中心和 RPC 治理。

### 常见追问

- 不做分库分表会不会撑不住？
- 为什么不加本地缓存提升性能？
- 什么时候从 HTTP 改 RPC？

### 追问回答要点

架构不要为不存在的规模买单。先监控和定位瓶颈，再引入复杂方案。

## 13. 复杂接口：挑一个接口从整体到细节讲

### 对应原问题

- 最近代码写得多吗？挑一个复杂接口详细讲一下。
- 挑一个你负责的复杂链路讲讲。

### 推荐讲法一：文档上传 merge 接口

这个接口适合展示存储、幂等、权限和异步。

链路：

```text
POST /document-uploads/{uploadId}/merge
-> load upload for update
-> require upload permission
-> check upload status and idempotent return
-> validate UploadChunk count + Redis bitmap + MinIO object exists
-> compute server-side SHA-256
-> merge chunks into final object
-> find/create FileObject by spaceId + contentHash
-> create Document
-> increment FileObject.refCount
-> create DOCUMENT_PROCESS Task
-> write TaskOutbox
-> cleanup temp chunks/bitmap
-> return documentId/taskId/status
```

为什么复杂：

- 不能相信前端 md5，必须服务端算 hash。
- 秒传只能复用对象，不能复用权限。
- merge 可能重复调用，必须返回已有 document/task。
- MinIO、MySQL、Redis 之间没有跨资源事务，必须设计补偿和清理边界。
- 解析索引不能同步做，必须异步 Task。

### 推荐讲法二：团队 RAG 问答接口

这个接口适合展示 AI 工程深度。

链路：

```text
POST /chat/sessions/{sessionId}/messages
-> validate TEAM_CHAT + FORMAL
-> requireAskQuestion
-> persist USER message
-> resolve session scope
-> HybridRetriever(BM25 + Vector + Wiki)
-> Weighted RRF
-> EvidencePostProcessor
-> persist RetrievalTrace
-> no evidence fallback or build grounded prompt
-> ObservedLlmGateway
-> persist ASSISTANT message
-> save Citation + message_citation
-> MemoryWriteback
-> return answer + citations
```

为什么复杂：

- 检索必须有权限 filter。
- 召回后要 MySQL 复核状态和 activeIndexVersion。
- Citation 不是 JSON，而是正式关系。
- 无证据要兜底，不能让模型编造。
- Trace 和 LLMLog 要能排查坏回答。

### 可直接复述的面试回答

我可以讲团队 RAG 问答接口。它不是一个简单调用 LLM 的接口。请求进来先校验 session 是 TEAM_CHAT FORMAL，再校验用户对 space 有提问权限。然后保存用户消息，解析 session scope 得到可见知识库。检索层会用 BM25、向量和 Wiki recall，多路结果通过 weighted RRF 融合，再经过 EvidencePostProcessor 做去重、相邻 chunk 合并、同文档限流和上下文裁剪。系统会先保存 RetrievalTrace。如果没有证据，直接返回无证据兜底；如果有证据，就构造 grounded prompt 调 LLM。最后保存 assistant message、Citation 和 message_citation，并触发 formal 会话的 memory writeback。这个接口复杂在它同时处理权限、检索质量、证据可追踪、模型调用和长期记忆边界。

## 14. 沟通与方案推进：如何把复杂技术讲给非技术方

### 对应原问题

- 组内业务交流时，怎么把复杂方案讲给业务方、产品或上下游？
- 推进方案时遇到分歧怎么办？
- 跨团队沟通做得好的一点是什么？

### 深入回答

讲复杂方案时，不要从技术名词开始，而要从风险和收益开始。

以 NoteWeave 的 Citation 设计为例，如果给产品讲，不要先讲 relation table、sourceVersion、snapshotObjectKey，而是说：用户看到 AI 回答后，需要知道这句话来自哪里；管理员遇到坏回答时，需要能反查是资料问题、检索问题还是模型问题；权限变化后，不能让用户继续看到无权资料。然后再解释为什么 Citation 要独立存关系表，而不是塞在 message JSON。

以 Task/Outbox 为例，如果给业务方讲，不要先讲 outbox pattern，而是说：上传成功不代表解析立刻完成；解析可能失败、可以重试、可以取消、可以看进度；我们把这些长任务统一成任务中心，让前端和运营能看到状态。

分歧处理上，可以把不同方案拆成成本、收益、风险、可回滚性和当前阶段必要性。例如“要不要一开始微服务”：收益是独立扩容，成本是调用复杂度、部署复杂度和分布式一致性。当前阶段更适合模块化单体，等 Worker 或 Search 确实成为瓶颈再拆。

### 可直接复述的面试回答

我和非技术方沟通时，会先翻译成业务风险和用户价值，而不是直接讲技术名词。比如 Citation 关系表，我不会一上来讲表结构，而是说用户需要知道 AI 回答依据，管理员需要能排查坏回答，权限变化后不能泄露证据，所以引用必须能独立校验和追踪。再比如 Task/Outbox，我会解释成长任务中心：上传后解析、索引、生成这些任务都有状态、可重试、可取消、可排查。遇到分歧时，我一般会把方案按收益、复杂度、风险和当前阶段必要性拆开，让大家看到为什么现在先做模块化单体，未来再按 Worker/Search 边界拆分。

### 常见追问

- 产品坚持要自动沉淀所有生成结果怎么办？
- 业务方觉得 Citation 设计太重怎么办？
- 上下游要求同步返回解析结果怎么办？

### 追问回答要点

自动沉淀会污染知识库；Citation 设计是为了可信和审计；同步解析会牺牲稳定性和用户体验，可以返回 taskId 和进度。

## 15. AI Coding 与个人表达：如何回答但不偏离项目

### 对应原问题

- 平时怎么用 AI Coding？
- AI 生成代码不符合预期怎么办？
- 遇到开放题或压力题怎么回答？
- 最近关注什么 AI / Agent 产品？

### 深入回答

这类问题可以回答个人方法论，但要回到工程能力。

AI Coding 的合理口径：

- 用它做方案对比、代码阅读、测试补全、边界 case 枚举、文档整理。
- 关键业务代码仍自己 review，尤其是权限、一致性、异常处理、事务边界和安全。
- AI 生成不符合预期时，先缩小上下文，给出明确接口契约、错误日志和期望行为，再让它改小步 patch。
- 不直接接受大段改动，先跑测试和 diff review。

结合 NoteWeave 可以说：这个项目本身也体现了对 AI 输出的谨慎态度，Artifact 不自动进入 Wiki，Memory 有写入策略，Citation 要 evidence backtrace。写代码时也类似，AI 是辅助，不是权威。

开放题或压力题的回答方法：

- 先承认边界。
- 再说当前项目事实。
- 再说如果扩展会怎么做。
- 最后说明验证指标。

### 可直接复述的面试回答

我平时会用 AI Coding 做代码阅读、方案草稿、测试 case 枚举和文档整理，但涉及权限、一致性、事务、异常处理和安全的代码一定会自己 review。AI 生成不符合预期时，我不会让它无限大改，而是缩小上下文，给明确输入输出、错误日志和期望行为，让它做小步 patch，然后看 diff、跑测试。这个思路和 NoteWeave 里对 AI 输出的处理是一致的：模型可以辅助生成，但最终要通过证据、权限、版本和测试来约束。

### 常见追问

- AI 写的代码占比多少？
- 怎么避免 AI 引入隐藏 bug？
- 你怎么看 Agent 产品？

### 追问回答要点

不要报虚假占比。强调 review、测试、边界 case、最小 diff、可回滚。评价 Agent 产品时讲适用边界，不要泛泛吹。

## 16. 风险题统一转答模板

### 被问到生产指标

回答：

当前项目没有真实生产 QPS/P99/token/day，我不会编造。但系统已经有 TaskEvent、RetrievalTrace、LLMCallLog、Eval、HealthSnapshot 这些观测面。如果要上线前验证，我会设计上传并发压测、检索压测、WebSocket runtime 压测和 RAG Eval，用 task latency、Kafka lag、retrieval latency、citation coverage、LLM timeout 等指标来衡量。

### 被问到分库分表

回答：

当前没有做分库分表，因为还没有数据规模证据。后续如果 trace、citation、llm log 成为大表，我会先做索引、分页、归档和冷热数据，再考虑按 spaceId 或时间维度分区/分表。

### 被问到完整 Agent / Multi-Agent

回答：

当前不是完整开放 Agent 或 Multi-Agent 系统，而是受控 Skill pipeline。它强调可控、可审计、可取消。未来要做 Agent，需要补工具权限、沙箱、预算、循环终止、tool log 和 eval。

### 被问到 MCP / Bibtex

回答：

当前 MCP 和 Bibtex 不是主链路。MCP 可以作为未来工具协议接到 Skill/Source import 层；Bibtex 可以作为 Source importer，把论文元数据和 PDF 链接接入现有 Source -> Card 链路。

### 被问到 GraphRAG

回答：

当前主链路是 Hybrid RAG，包括 BM25、Vector、Wiki recall 和 Weighted RRF。项目有图谱/关系展示和 Wiki/Concept relation，但不应把它说成完整 GraphRAG 主链路。

## 17. 最推荐背诵的万能深答

如果面试官连续追问项目、架构、难点、指标，可以用这一段兜住：

NoteWeave 我会把它定位成一个权限约束下的 AI 知识工作台，而不是普通 RAG Demo。它的主链路是：资料先进入系统，团队侧通过 Document 上传、解析、切片和索引进入 RAG；个人侧通过 Source 导入和 WikiCompiler 编译成 ArticleCard/ConceptCard。生成阶段不直接让 LLM 面对原始资料，而是通过权限过滤、混合检索、证据后处理、Methodology 和 prompt version 组织上下文。生成结果也不会自动变成长期知识，团队侧要发布为 Wiki，个人侧要用户确认后沉淀为 SynthesisCard。底层我用 Task/Outbox/Kafka 统一长任务，用 Redis 管 WebSocket 短期 runtime，用 MySQL 管业务状态和证据关系，用 ES 做检索，用 MinIO 存对象和快照。这个项目的难点在于把 AI 输出放进一个可追踪、可回滚、可排障、可运维的工程闭环里。当前没有生产 QPS/P99，我会通过 Trace、Eval、TaskEvent、LLMLog 和 HealthSnapshot 去验证效果，而不是编造数字。
