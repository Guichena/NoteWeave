# 10 可靠性一致性排障追问

## 0. 本篇定位

这部分不是单独业务链路，而是面试官最容易横向追问的系统能力：一致性、幂等、降级、依赖异常、排障和边界。

## 面试先说版

如果面试官连续追问可靠性，我会先给一个总原则：NoteWeave 不追求把 MySQL、Kafka、MinIO、Elasticsearch、LLM Provider 放进一个强事务，而是把每个组件的职责拆清楚，用最终一致、幂等、状态机、索引版本和可观测来收敛失败。

MySQL 是业务事实源，Kafka 是后台任务推进，Redis 是短期运行态，MinIO 是对象存储，ES 是召回索引，LLM Provider 是生成能力，不是事实源。每个组件失败时，系统要么保留可重试状态，要么返回无证据或失败兜底，不能让模型编造，也不能把短期状态当成正式事实。

这段回答能自然带出：分布式事务取舍、Outbox、消费幂等、ES/MySQL 最终一致、Redis 降级、对象存储清理、RAG 排障和 Admin/Ops 可观测。

## Q1：异步链路里如何保证一致性？

**答：**

NoteWeave 不追求跨 MySQL、Kafka、MinIO、Elasticsearch、LLM 的强事务，而是通过本地事实表、Outbox、幂等和补偿实现最终一致。

业务状态先落 MySQL，比如 Document、Source、Artifact、Task。需要异步执行时，同事务写 TaskOutbox。Kafka 投递只是推进执行，Worker 拿到消息后必须回查 DB 状态。执行结果再写回 TaskAttempt、TaskEvent 和业务状态。

对于 ES 这类外部索引，用 activeIndexVersion 和后置 MySQL 校验降低不一致风险。对于 MinIO 对象，用 FileObject、refCount、soft delete 和 cleanup scan/execute 管理生命周期。

## Q2：幂等怎么做？

**答：**

幂等要按链路分层。

入口幂等主要靠 task `idempotency_key`、上传 uploadId、FileObject 的 `spaceId + contentHash` 唯一约束、Card 的项目内唯一约束等，避免重复创建同一业务任务或资源。

消费幂等主要靠 Worker 回查 Task 状态、TaskAttempt 记录和业务表唯一约束。比如重复消费 DOCUMENT_PROCESS 不应该重复创建 active chunk；重复确认 Artifact distillation 不能重复生成多个 SynthesisCard。

补偿幂等主要靠状态条件和 Admin 操作记录。retry、cancel、mark failed、cleanup execute 都要基于当前状态推进，并写 AuditLog。

## Q3：中间件异常怎么降级？

**答：**

要按依赖分开讲。

MySQL 异常时，业务事实无法可靠读写，核心 API 应该失败并暴露健康状态。Redis 异常会影响 WebSocket ticket、runtime state、resume 和短期状态，但不应该丢失已落库的正式消息和任务。MinIO 异常会影响上传、解析和 citation snapshot，任务应失败并可重试。Elasticsearch 异常会影响检索和索引，RAG 可以返回无证据兜底或失败提示，不能编造。Kafka 异常会影响后台任务推进，outbox 可以保留待投递。LLM Provider 异常会影响生成，任务或回答应记录失败，不能吞掉错误。

## Q4：如何定位“用户看不到某条资料”的问题？

**答：**

按资源链路排查。

第一看用户是否属于对应 Space，角色是否允许查看。第二看 KnowledgeBase 是否 ACTIVE，Document 是否未删除、是否 INDEXED。第三看 DocumentChunk 是否存在，activeIndexVersion 是否匹配。第四看 ES 里是否有对应索引文档，spaceId/knowledgeBaseId/status filter 是否正确。第五看 RAG session scope 是否包含该 KB。第六看 RetrievalTrace 是否召回过该 chunk，如果没有召回，再看 query、BM25/向量/wiki recall、RRF 和 EvidencePostProcessor 的过滤。

## Q5：如何回答“你这个项目是不是过度设计”？

**答：**

我会说它不是为了堆技术，而是因为 AI 知识工作台的风险点天然比较多。

如果只是个人玩具，可以上传文件、向量检索、问模型就结束。但 NoteWeave 的目标是团队和个人两类空间共存，要处理权限、异步任务、证据引用、运行态恢复、生成成果版本、长期记忆污染、RAG 评测和运维排障。这些问题如果一开始完全不设计，后面会很难补。

同时项目也不是所有地方都上重方案。比如 Redis 只承载 runtime state，不当主任务队列；DRAFT 不写长期 Memory；个人 Artifact 不自动改 ConceptCard；清理先 scan 再 execute；Bibtex 仍然只是扩展方向，而 MCP 这块已经先落了一个远程 B 站 tool service，但没有扩成完整开放平台。

## Q6：哪些高级技术点不能硬说成已经做了？

**答：**

不能说：

- 真实生产 QPS、P99、token/day 或线上准确率。
- 完整开放式 MCP 平台已经做完，或者 MCP 已成为所有主链路强依赖。
- Bibtex 已端到端落地。
- 完整开放 Agent 平台。
- GraphRAG 是当前主检索链路。
- 微服务拆分、注册中心、配置中心、分库分表。
- Redis 是主后台任务队列。

可以说：

- 当前已经有权限隔离、异步任务、Hybrid RAG、Citation、Trace、Eval、Admin/Ops 的工程化基础。
- Bibtex、完整 Agent、GraphRAG、多模型治理可以作为后续扩展方向；MCP 则要更准确地说成“已有远程 B 站 tool service 落地，但不是完整开放平台”。

## 3 到 5 分钟压力答模板

> 如果面试官从可靠性、一致性和排障角度连续追问，我会先把总原则说清楚：NoteWeave 不追求跨 MySQL、Kafka、MinIO、Elasticsearch、LLM 的强事务，而是通过本地业务事实、Outbox、消费侧幂等、索引版本和可观测链路做最终一致。任务执行靠 Task 状态机、TaskAttempt、TaskEvent 和 Admin 操作收敛；检索链路靠 activeIndexVersion、Citation、RetrievalTrace 和 MySQL 状态复核降低脏数据风险；运行态靠 Redis 承载短期状态，而正式事实仍回到 MySQL。也就是说，这个项目的可靠性不是某一个中间件单点兜住的，而是通过边界清晰的职责划分、统一状态模型和排障入口把失败收敛起来。当前我会坚定讲这些工程化基础，也会明确说 MCP 已经先落了一个远程 B 站 tool service，但不会把它夸大成完整开放平台、真实生产 SLA 或完整多 Agent 已落地。
