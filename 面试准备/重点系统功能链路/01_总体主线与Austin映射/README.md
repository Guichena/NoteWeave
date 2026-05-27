# 01 总体主线与 Austin 映射

## 0. 本篇定位

这部分解决“怎么从系统功能角度介绍 NoteWeave”。它参考 Austin 通知平台的连续深挖方式，但主线必须替换为 NoteWeave 的可信知识生产闭环。

Austin 的核心是可靠触达：

```text
业务事件 -> 消息任务 -> MQ -> 消费发送 -> 状态收敛 -> 追踪补偿
```

NoteWeave 的核心是可信知识生产：

```text
资料进入系统 -> 解析索引 -> 检索引用 -> 生成成果 -> 确认沉淀 -> 评测排障
```

## 面试先说版

如果面试官让我从系统功能角度介绍 NoteWeave，我不会先说“我写了哪些类”，而是会把它讲成一个可信知识生产闭环：资料怎么进入系统，怎么被异步解析和索引，怎么通过 Hybrid RAG 召回证据，怎么生成 Artifact 或 Wiki，最后怎么通过 Citation、Trace、Eval 和 Admin/Ops 去排障。

它和 Austin 的类比不是业务一样，而是方法论一样：Austin 治理的是可靠触达，NoteWeave 治理的是可信知识生产。两者都要回答状态怎么流转、消息怎么异步、失败怎么补偿、结果怎么追踪、线上坏 case 怎么定位。

## Q1：从重点系统功能角度，怎么介绍 NoteWeave？

**答：**

NoteWeave 是一个面向团队知识协作和个人研究沉淀的 AI 知识工作台。它不是只做一次性的 RAG 问答，而是把资料进入系统、被解析索引、被检索引用、生成结构化成果，再沉淀成 Wiki 或个人知识卡片这一整条链路做成闭环。

从系统功能上可以分成三层讲。

第一层是基础治理能力，包括认证、`TEAM / PERSONAL` 双空间、成员角色、资源权限、统一 API 响应和后台任务模型。这个层负责保证后面所有知识、会话、引用和成果都有清晰归属。

第二层是知识处理主链路。团队侧是 `KnowledgeBase -> DocumentUpload -> Document -> DocumentChunk -> Hybrid Retrieval -> Citation -> Chat / Wiki`；个人侧是 `ResearchProject -> Source -> ArticleCard / ConceptCard -> Artifact -> SynthesisCard`。

第三层是可运营能力，包括 `TaskEvent`、`RetrievalTrace`、`LLMCallLog`、`AnswerFeedback`、`RagEvalRun`、`SystemHealth`、`Cleanup` 和 `AuditLog`。这些能力让它不是黑盒 AI demo，而是可以追踪一次回答为什么这么答、检索了什么、用了哪个 Prompt、任务失败在哪里。

## Q2：怎么把 Austin 的主线替换成 NoteWeave 的主线？

**答：**

Austin 通知平台可以按“可靠触达”讲，NoteWeave 要按“可信知识生产闭环”讲。

| Austin 通知平台主线 | NoteWeave 对应主线 |
|---|---|
| 消息从入口到发送成功 | 知识从资料进入系统，到检索引用、生成成果、沉淀为长期知识 |
| 可靠通知 | 可靠知识摄取、可追溯回答、可恢复生成任务 |
| 消息状态机 | `Task + Attempt + Event + Outbox + Kafka + Worker` |
| RabbitMQ 异步发送 | Kafka 承载统一后台任务执行 |
| traceId / 锚点日志 | `RetrievalTrace`、`LLMCallLog`、`TaskEvent`、`Citation`、`AuditLog` |
| 渠道成功不等于用户收到 | LLM 回答不等于事实正确，必须有证据、引用和评测 |

面试时可以这样收口：

> Austin 的核心是把通知发送治理成可追踪、可补偿的消息链路。NoteWeave 的核心是把知识生产治理成可权限隔离、可检索、可引用、可评测、可沉淀的闭环。两者都不是简单调中间件，而是在业务链路上设计状态、幂等、失败恢复和可观测。

## Q3：为什么说它不是普通 RAG demo？

**答：**

普通 RAG demo 通常只有上传、切片、向量检索、拼 Prompt、问模型这几个步骤。NoteWeave 的区别在于它围绕知识工作台做了系统边界。

第一，它有空间和权限边界。`TEAM` 和 `PERSONAL` 是一级业务容器，团队资源按成员角色访问，个人资源默认 owner-only。

第二，它有统一异步底座。文档解析、Source 导入、Source 编译、Artifact 生成、Wiki 入索引、Embedding backfill、RAG Eval、Cleanup 都复用 `Task + Outbox + Kafka + Worker`。

第三，它有证据链和可观测。回答会保存 `Citation`、`message_citation`、`RetrievalTrace`、`LLMCallLog` 和 `AnswerFeedback`。

第四，它有知识沉淀。团队侧可以把稳定内容发布为 Wiki，个人侧可以把 Artifact 确认沉淀为 SynthesisCard。

## 实现兜底锚点

- `com.noteweave.space`
- `com.noteweave.task`
- `com.noteweave.team.document`
- `com.noteweave.team.rag`
- `com.noteweave.personal`
- `com.noteweave.artifact`
- `com.noteweave.memory`
- `com.noteweave.admin`
- `Phase4TeamRagIntegrationTest`
- `Phase11PersonalGenerationIntegrationTest`
- `Phase14ObservabilityEvaluationIntegrationTest`
- `Phase15AdminOpsIntegrationTest`

## 3 到 5 分钟深答模板

如果面试官说“你从系统设计角度完整讲一下 NoteWeave”，可以这样回答：

> 我会把 NoteWeave 定义成一个权限感知、证据优先、可观测的 AI 知识工作台，而不是普通 RAG demo。它的主线不是“上传文档然后问模型”，而是把知识从资料进入系统、被解析索引、被检索引用、生成成果，再沉淀成长期知识做成闭环。第一层是基础治理，包括 `TEAM / PERSONAL` 双空间、资源权限和统一异步任务底座；第二层是知识处理主链路，团队侧是上传解析索引、Hybrid RAG、Citation 和 Wiki，个人侧是 Source、Card、Artifact、Synthesis；第三层是可观测和运营能力，包括 RetrievalTrace、LLMCallLog、RagEvalRun、SystemHealth、Cleanup 和 AuditLog。它和 Austin 的相似点在于都强调状态、幂等、失败恢复和可观测，只是 Austin 处理的是可靠触达，NoteWeave 处理的是可信知识生产闭环。

## 常见追问继续怎么接

- 如果继续追问“你最想先展开哪一层”，优先展开统一任务底座或 Hybrid RAG，这两条最能体现系统设计能力。
- 如果继续追问“和 Austin 最大的映射关系是什么”，可以答：本质都在治理异步状态和可恢复链路，只是一个面向消息触达，一个面向知识生产。
- 如果继续追问“最怕被问什么”，可以主动收边界：生产指标、完整开放式 MCP 平台主链路、GraphRAG 和开放 Agent 目前都不该说成已落地事实。更准确的说法是 MCP 已有远程 B 站 tool service 样例落地。

## 边界和不能说满的地方

- 可以坚定主讲：双空间、统一任务底座、Hybrid RAG、Citation、WebSocket Runtime、个人研究链路、Memory、Eval、Admin/Ops。
- 不要讲成当前已落地：真实生产 QPS/P99、完整开放式 MCP 平台主链路、Bibtex 端到端、完整开放 Agent、GraphRAG 主链路、微服务拆分、分库分表。
