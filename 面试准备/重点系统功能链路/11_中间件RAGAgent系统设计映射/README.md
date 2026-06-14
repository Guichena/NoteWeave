# 中间件 RAG Agent 系统设计映射

> 本文件为 2026-06-01 重构版，依据当前代码、测试和 Flyway 迁移整理。不要再按旧阶段计划或旧题库口径背。

## 0. 本篇定位
这篇把常见八股题映射回 NoteWeave 的真实链路。目标不是背“Redis 有哪些数据结构”，而是能解释每个中间件为什么放在这个位置、为什么不承担别人的职责、坏了以后系统怎么降级或恢复。

## 1. 面试先说版
NoteWeave 的中间件职责划分比较清晰：MySQL 是业务事实源，保存用户、空间、任务、文档、卡片、Artifact、Citation、Memory、Eval 和 Admin 状态；Redis 是短期运行态，主要承接 WebSocket ticket、流式 partial content、stop/resume、上传 runtime；MinIO 保存上传对象、解析文本、citation snapshot 和 Artifact 相关对象；Elasticsearch 做 BM25、向量和 Wiki 检索索引，但不做权限事实源；Kafka 承担后台任务事件，和 TaskOutbox 一起实现最终一致；LLM、Skill、MCP 工具只在受控编排里使用，不能绕过权限、任务和证据链路。

## 2. 当前真实口径
每个中间件都有边界：MySQL 管事实，Redis 管短期状态，MinIO 管对象，ES 管可重建索引，Kafka 管异步投递，LLM/MCP 管生成上下文。面试时越能讲清“不让它做什么”，越像真实做过系统。

### 已实现
- `docker-compose.yml` 提供 MySQL、Redis、MinIO、Elasticsearch、Kafka。
- `Task + TaskAttempt + TaskEvent + TaskOutbox + Kafka + Worker` 是主异步骨架。
- Redis 参与 chat runtime、WebSocket ticket、stop/resume 和 upload runtime state。
- ES 承担 document chunk、wiki page、hybrid retrieval 等检索能力。
- Studio MCP 已有 Bilibili 工具扩展：本地和远程两种实现，聊天可通过 `/mcp bilibili <url>` 显式触发。

### 不能说满
- Redis 不是主任务队列。
- ES 不是业务事实源，也不能代替 MySQL 做权限判断。
- Kafka 不保证业务天然幂等，幂等要靠 Task 状态和 idempotencyKey。
- MCP 不是完整开放式平台，当前是受控 Bilibili 工具扩展。
- 当前 Skill 不是完全自主多 Agent 编排。

## 3. 代码和测试锚点
- `docker-compose.yml`
- `src/main/resources/application.yml`
- `src/main/java/com/noteweave/task/service/TaskOutboxService.java`
- `src/main/java/com/noteweave/task/service/TaskKafkaPublisher.java`
- `src/main/java/com/noteweave/chat/runtime/service/ChatRuntimeService.java`
- `src/main/java/com/noteweave/team/rag/retriever/HybridRetriever.java`
- `src/main/java/com/noteweave/team/rag/retriever/WeightedReciprocalRankFusion.java`
- `src/main/java/com/noteweave/team/wiki/service/WikiRetriever.java`
- `src/main/java/com/noteweave/studio/service/StudioMcpToolRegistry.java`
- `src/main/java/com/noteweave/studio/service/RemoteBilibiliMcpToolService.java`
- `src/test/java/com/noteweave/chat/Phase11_6ChatMcpIntegrationTest.java`
- `src/test/java/com/noteweave/studio/service/RemoteBilibiliMcpToolServiceTest.java`

## 4. 必会问题与深答

### Q1: 每个中间件在项目里分别承担什么职责？
我会按“事实、短期状态、对象、索引、消息”来讲。MySQL 保存不可丢的业务事实，比如 Space 权限、Document 元数据、Task 状态、ArtifactVersion、Citation、Memory 和 Eval 结果。Redis 保存可以重建或过期的运行态，比如 WebSocket ticket、partialContent、stop 标记和上传进度。MinIO 保存大对象，比如原始文件、解析文本、citation snapshot。ES 保存可重建索引，用于 BM25、向量和 Wiki recall。Kafka 保存异步任务投递事件，Worker 最终仍然回查 MySQL 的 Task 决定怎么执行。

追问接法：
- “为什么这样分”：不可丢事实进 MySQL，可过期状态进 Redis，大文件进 MinIO，可重建检索结构进 ES，异步执行通知进 Kafka。
- “哪个是系统事实源”：MySQL。ES、Redis、Kafka 都不能替代它做权限和最终状态判断。

### Q2: 为什么 Redis 不做主任务队列？
Redis 在 NoteWeave 里更适合放短期运行态，而不是承接主任务事实。原因是任务需要状态流转、attempt、event、失败原因、取消、重试、Admin 介入和审计，这些都要和业务实体建立稳定关系。当前项目把 Task、TaskAttempt、TaskEvent 和 TaskOutbox 放在 MySQL，Redis 只负责 WebSocket runtime、stop/resume、ticket、上传临时状态这类“丢了可以从正式数据恢复体验”的内容。面试时可以说：Redis 不是不能做队列，而是这个项目更需要可审计、可恢复、可管理的任务模型。

追问接法：
- “Redis 挂了怎么办”：运行中流式状态可能丢失，但正式 ChatMessage、Task、ArtifactVersion 在 MySQL，用户至少能看到已完成结果或失败状态。
- “为什么不用 Redis Stream”：可以作为后续队列实现候选，但仍然要保留 DB Task 事实源和幂等控制。

### Q3: 为什么 ES 不能代替 MySQL？
ES 的价值是检索，不是事务事实。RAG 查询时 ES 能高效召回 chunk、wiki page 和语义相关内容，但权限、文档删除状态、activeIndexVersion、citation 关系和用户空间边界不能只信 ES。当前链路需要在检索前限定 Space 和 KnowledgeBase 范围，检索后还要通过 DB 状态或资源访问服务做二次校验。这样即使 ES 有旧索引、延迟刷新或部分索引失败，也不会让用户越权看到不该看的内容。

追问接法：
- “ES 索引和 DB 不一致怎么办”：以 DB 为准，索引可以重建；删除和重建索引要通过状态字段、version 和任务失败兜底。
- “为什么不用 MySQL LIKE”：关键词和语义召回能力不够，且无法承担向量相似度和多路融合。

### Q4: Kafka 和 Outbox 各自解决什么问题？
Kafka 解决异步投递和削峰，Outbox 解决业务事务和消息发送之间的一致性。如果业务接口直接改 DB 后发 Kafka，发消息失败会导致任务没人执行；如果先发 Kafka 再提交 DB，Worker 可能读不到业务状态。NoteWeave 的做法是在同一个事务里写 Task 和 TaskOutbox，事务提交后由 outbox 调度器投递 Kafka。Consumer 收到消息后只信 taskId，再回查 DB 判断状态、幂等和取消。这样可以接受最终一致，但不会把“消息发出”和“业务事实已提交”混在一起。

追问接法：
- “Kafka 重复投递怎么办”：Worker 必须幂等，靠 Task 状态、attempt 和业务目标状态决定是否执行副作用。
- “Kafka 挂了接口怎么办”：业务可以先返回任务已创建，Outbox 保留待投递记录，恢复后继续发送。

### Q5: RAG 为什么要和中间件边界一起讲？
因为 NoteWeave 的 RAG 不是单纯调用模型，而是一条跨 MySQL、ES、MinIO、LLM、Citation 的证据链。MySQL 决定用户能查哪些 Space/KnowledgeBase；ES 做 BM25、向量和 Wiki recall；MinIO 可能保存 citation snapshot 或解析文本；LLM 只在 evidence-first prompt 下生成；Citation 和 RetrievalTrace 再把回答和证据落回 MySQL。面试时把 RAG 和中间件边界一起讲，可以说明你理解“召回、权限、证据、追溯、运营”是一体的。

追问接法：
- “向量召回失败怎么办”：可以降级到 BM25/Wiki recall，并在 trace 里记录召回源和失败原因。
- “模型胡说怎么办”：先看证据是否召回，后看 prompt 是否约束，最后看 citation 和用户反馈进入 eval。

### Q6: Skill 和 Agent 的边界怎么讲？
Skill 是受控流水线步骤，Agent 是更开放的自主规划和工具调用。当前项目已经有 `ArtifactPlanExecutor`、SkillExecutionLog、MethodologyCard、MCP tool context，这些可以被讲成 Agent 化能力的地基；但实际执行仍然是按 artifact type 选择固定步骤，工具也通过 registry 显式注册，不能任意访问系统资源。面试时最稳的说法是：我做的是可观察、可审计、可回放的生成工作流，后续可以演进为更强的 Agent planner，但当前不夸成完全自主多 Agent。

追问接法：
- “为什么不直接让模型决定工具”：工具调用涉及权限、成本、外部网络和数据写入，必须先受控注册、参数校验和结果审计。
- “怎么演进”：增加 planner、tool policy、sandbox、预算控制、人工确认和 eval 回归，而不是直接放开执行。

### Q7: Bilibili MCP 为什么要做成本地/远程两种工具？
Bilibili 解析依赖外部网络、视频页面、字幕接口和容错逻辑，放成远程服务可以隔离主系统失败面，也便于独立部署和升级。当前配置里 `remote-enabled=true` 时主系统通过 `RemoteBilibiliMcpToolService` 调用 `/api/v1/mcp/bilibili/invoke`；关闭远程时可走 `LocalBilibiliMcpToolService`。无论本地还是远程，返回的都是结构化 promptContext，后续仍进入 Artifact 的受控生成链路，而不是让外部工具直接写数据库。

追问接法：
- “远程服务超时怎么办”：工具调用失败应让本次 Artifact task 失败或给出明确错误，不应该静默编造视频内容。
- “这是不是 MCP 平台”：不是。它证明了受控工具扩展路径，但当前只有 Bilibili 这类明确注册的工具。

### Q8: 如果某个中间件挂了，怎么讲降级？
按职责降级。MySQL 挂了，业务事实不可用，核心链路应该失败并报警；Redis 挂了，WebSocket 恢复体验和运行中 partial 状态受影响，但已落库消息和任务仍可查；MinIO 挂了，上传、解析文本和 snapshot 读取失败，需要任务重试；ES 挂了，RAG 检索降级或失败，不能让模型无证据自由发挥；Kafka 挂了，Outbox 先积压，恢复后补投递；LLM 或 MCP 挂了，生成任务失败，保留 TaskEvent 和错误原因供 Admin 排查。

## 5. 大厂深挖追问路径
1. 先让你列中间件职责。
2. 再问为什么不用 Redis/ES/MySQL/Kafka 互相替代。
3. 再问一致性、幂等、失败恢复和权限越权。
4. 然后把问题压到 RAG：证据从哪来，为什么可信，错了怎么查。
5. 最后抓 Agent/MCP，看你是否能讲清受控工具扩展和未完成边界。

## 6. 一句话记忆
MySQL 管事实，Redis 管短期运行态，MinIO 管对象，ES 管可重建索引，Kafka 管异步投递，LLM/MCP 管受控生成上下文；任何一个组件都不能越界替代权限、证据和任务事实源。
