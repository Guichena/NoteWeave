# NoteWeave 项目讲解与学习知识点

更新时间：2026-06-08

## 1. 这份文档的定位

这份文档不是通用 RAG 教程，也不是单纯的架构摘要，而是面向 **NoteWeave 当前仓库真实设计** 的项目讲解与学习路线图。

目标有三个：

1. 帮你先建立对 NoteWeave 的整体理解。
2. 帮你知道这个项目每一块在解决什么问题、为什么这样设计。
3. 帮你把“读代码”变成“带着问题去读代码”，形成可复用的学习知识点。

如果你只是想快速知道项目干什么，看 `README.md` 即可。

如果你想真的把项目讲明白、学明白、拆明白，优先看这份文档。

---

## 2. 先说结论：NoteWeave 到底是什么

一句话讲，**NoteWeave 是一个双空间的 AI 知识工作台**。

它不是一个单纯的聊天机器人，也不是一个只做 RAG 的知识库，更不是一个只做个人笔记的研究工具，而是把下面几件事放进了同一套底座里：

- 团队知识库上传、解析、检索、问答、引用、Wiki 沉淀
- 个人研究资料导入、卡片编译、Artifact 生成、Synthesis 沉淀
- 会话运行态、长期记忆、方法论注入、评测与运维

更重要的是，这些能力不是各自独立堆起来的，而是复用了同一套基础设计：

- `Space`
- `Permission`
- `Task / Outbox / Kafka / Worker`
- `Evidence / Citation / RetrievalTrace`
- `Artifact / Wiki / Memory` 的边界控制

所以如果你理解了 NoteWeave，不只是理解了一个 AI 应用，而是理解了一套“AI 知识系统怎么做成工程闭环”的方法。

---

## 3. 学这个项目时，先记住三条真相

### 3.1 这个项目最核心的不是“大模型”，而是“系统边界”

NoteWeave 真正难的地方不是调用一次 LLM，而是：

- 不同空间的数据不能串
- 长任务不能乱
- 证据要可回溯
- 临时生成不能自动污染长期知识
- 评测链路不能污染正式业务链路

所以你读代码时，不要只盯着 `llm`、`prompt`、`retrieval`，更要盯着：

- `space`
- `permission`
- `task`
- `citation`
- `memory`
- `admin`

### 3.2 这个项目最有辨识度的不是单个功能，而是“双闭环”

NoteWeave 不是只有团队 RAG，也不是只有个人研究，而是两条闭环并存：

- 团队闭环：`KnowledgeBase -> Document -> Chunk -> RAG -> Citation -> Wiki -> Graph`
- 个人闭环：`ResearchProject -> Source -> Card -> Artifact -> Synthesis -> Memory`

这两条闭环共享基础设施，但边界不同、目标不同、沉淀形式也不同。

### 3.3 当前代码比部分状态文档更“新”

从当前仓库的代码、测试和 Flyway 迁移看，项目已经不只是 `Phase 0/1 ~ Phase 15`。

代码和迁移里还可以看到：

- `V18__phase_16_team_wiki_graph.sql`
- `V19__phase_17_auto_wiki_maintenance.sql`
- `graph` 包
- `AutoWikiMaintenanceService`
- `KnowledgeGraphController`

也就是说，**学习时要优先信代码、测试、迁移，其次再看状态文档**。

这是读这个项目时非常重要的一条原则。

---

## 4. 用什么顺序读这个项目最省力

建议按下面顺序读，而不是一上来就从 Controller 开始乱翻。

### 第一层：先看全景

先读：

```text
README.md
ARCHITECTURE_AND_FUNCTIONS.md
docs/PROJECT_STATUS.md
docs/implementation_breakdown.md
```

你需要先回答三个问题：

1. 项目解决什么问题？
2. 项目分成哪几条主链？
3. 目前做到哪里了？

### 第二层：再看模块地图

看顶层包结构：

```text
auth
space
permission
task
team
chat
citation
personal
artifact
studio
memory
graph
rageval
admin
websocket
```

看到这里，你就要开始建立“模块边界图”，而不是只记功能名。

### 第三层：按业务链路读，而不是按文件夹读

最推荐的顺序是：

1. 认证、空间、权限
2. 统一异步任务骨架
3. 团队文档上传与解析
4. 团队 RAG / Citation / RetrievalTrace
5. WebSocket Runtime
6. 个人 Source / Card / Compiler
7. Artifact / Methodology / Distillation
8. Memory
9. Wiki / Graph / Auto maintenance
10. Eval / Admin / Ops

这个顺序的好处是：你读的是“系统怎么跑起来”，不是“目录里有什么类”。

---

## 5. NoteWeave 最核心的架构观

## 5.1 Space 是最高业务边界

NoteWeave 的第一关键设计，不是表结构，也不是中间件，而是：

```text
Space = 最高业务容器
```

它把系统分成两类空间：

- `PERSONAL`
- `TEAM`

这不是一个简单字段，而是后续很多设计的起点。

### 为什么这样做

因为团队知识和个人研究天然不是同一种对象：

- 团队知识强调共享、权限、引用、长期发布
- 个人研究强调探索、编译、生成、确认后沉淀

如果不先分空间，后面很容易把：

- 团队知识库
- 个人卡片
- Artifact
- Memory
- Wiki

混在一起，最后权限和生命周期都会乱。

### 对应代码

优先看：

```text
src/main/java/com/noteweave/space
src/main/java/com/noteweave/permission
src/test/java/com/noteweave/space
src/test/java/com/noteweave/permission
```

### 这一块要学的知识点

- 领域边界怎么先于功能实现
- `systemRole` 和 `space role` 为什么不能混用
- 资源访问为什么不能只按 `id` 查
- 为什么 AI 系统尤其要“permission-first”

### 学完后你应该能讲出来的话

“NoteWeave 不是把所有 AI 功能堆在一个工作台里，而是先用 Space 把团队与个人主线分开，再让权限服务贯穿检索、引用、Artifact、Memory 和运维链路，先保数据边界，再谈 AI 能力。”

---

## 6. 第二个核心：统一异步任务骨架

如果 Space 是系统边界，那 `Task + Outbox + Kafka + Worker` 就是系统执行骨架。

### 6.1 这个设计解决什么问题

NoteWeave 里很多动作都不是同步小请求，而是长任务：

- 文档解析
- 索引构建
- Source 导入
- Source 编译
- Artifact 生成
- Wiki 入索引
- RAG Eval
- 资源清理

如果每个功能自己造一套异步逻辑，系统很快会失控。

所以项目选择了统一骨架：

```text
Task
TaskAttempt
TaskEvent
TaskOutbox
Kafka
Worker
```

### 6.2 为什么这是个好设计

它的价值不只是“能异步”，而是统一了这些问题的处理方式：

- 状态怎么记录
- 幂等怎么做
- 重试怎么做
- 取消怎么做
- 失败怎么追踪
- 管理后台怎么看

换句话说，它把“AI 长任务”从功能代码里抽出来了。

### 对应代码

优先看：

```text
src/main/java/com/noteweave/task
src/test/java/com/noteweave/task
src/main/resources/db/migration/V2__phase_1_5_task_outbox_worker.sql
```

重点类：

```text
TaskService
TaskOutboxService
TaskDispatcher
TaskKafkaPublisher
TaskKafkaConsumer
TaskExecutionCoordinator
```

### 这一块要学的知识点

- Outbox 模式为什么存在
- 为什么业务事务不能直接依赖 Kafka 成功
- 长任务为什么要拆 `Task / Attempt / Event`
- 幂等键、取消请求、重试边界怎么设计
- 为什么 AI 系统特别需要“统一后台执行模型”

### 学完后你应该能讲出来的话

“NoteWeave 把上传、解析、编译、生成、评测这些长动作都统一成 Task 骨架，不让每个 AI 功能自己造后台逻辑。这样状态、重试、取消、事件和 Admin 可见性都是同一套模型，后续扩功能的时候不会越做越乱。”

---

## 7. 第三个核心：团队知识链路

团队链路是这个项目最接近“企业知识库 + RAG”主线的部分。

## 7.1 团队链路的主流程

可以先记成这条链：

```text
KnowledgeBase
  -> DocumentUpload
  -> FileObject
  -> Document
  -> DocumentChunk
  -> Search / Retrieval
  -> Chat Answer
  -> Citation
  -> Wiki
  -> Graph
```

### 7.1.1 上传不是“把文件丢上去”这么简单

它至少包括：

- 分片上传
- 断点续传
- 合并
- 对象复用
- 异步解析
- 软删除与清理

这说明项目并不是把文件当成简单附件，而是把它们当成“后续知识处理的原材料”。

### 对应代码

```text
src/main/java/com/noteweave/team/document
src/main/java/com/noteweave/team/kb
src/main/resources/db/migration/V3__phase_2_file_upload_async_ingestion.sql
src/main/resources/db/migration/V4__phase_3_document_processing_indexing.sql
```

### 这一块要学的知识点

- 文件上传为什么要和对象管理分开
- `FileObject` 为什么比“直接文档表存路径”更稳
- 文档软删除、对象复用、引用计数这些概念怎么理解
- `Document` 和 `DocumentChunk` 为什么要分层

## 7.2 团队 RAG 不是“搜一下然后问模型”

团队问答链路真正重要的是它的 evidence-first 思路：

```text
权限校验
-> 多路召回
-> 融合排序
-> 证据后处理
-> Prompt 约束
-> LLM 回答
-> Citation 持久化
-> RetrievalTrace 持久化
```

这意味着它不是单纯为了“答出来”，而是为了：

- 可回溯
- 可审计
- 可调优

### 对应代码

```text
src/main/java/com/noteweave/team/rag
src/main/java/com/noteweave/chat
src/main/java/com/noteweave/citation
src/main/java/com/noteweave/search
src/test/java/com/noteweave/chat/Phase4TeamRagIntegrationTest.java
src/test/java/com/noteweave/chat/Phase9HybridRetrievalIntegrationTest.java
src/test/java/com/noteweave/team/rag/retriever
```

重点类：

```text
HybridRetriever
Bm25Retriever
VectorRetriever
WeightedReciprocalRankFusion
EvidencePostProcessor
TeamRagPromptBuilder
TeamChatService
CitationService
RetrievalTraceService
```

### 这一块要学的知识点

- 为什么要做 hybrid retrieval
- BM25、向量、Wiki recall 各自在做什么
- RRF 为什么适合先做融合
- 为什么要有 `EvidencePostProcessor`
- `Citation` 和 `RetrievalTrace` 为什么不能只是日志

### 学完后你应该能讲出来的话

“NoteWeave 的团队 RAG 主线不是把检索当成 prompt 前的一步，而是把证据链做成正式系统对象：先权限过滤，再 BM25 / 向量 / Wiki 多路召回，用 RRF 融合，再做证据后处理，最后把回答和引用、召回过程一起持久化，方便后续审计和评测。”

---

## 8. 第四个核心：WebSocket Runtime 和 DRAFT / FORMAL

这是很多知识库项目没有做深，但 NoteWeave 比较有辨识度的一块。

### 8.1 它解决什么问题

HTTP 适合稳定问答，但不适合：

- 流式输出
- 用户中断
- 运行中恢复
- 多轮草稿式探索

所以项目又做了一套 runtime 层。

### 8.2 为什么要区分 DRAFT 和 FORMAL

这是一个非常好的边界设计。

区别可以这样理解：

- `DRAFT`：临时探索、运行态、试探性上下文
- `FORMAL`：正式会话、可沉淀、可进入长期链路

这个区分带来的好处是：

- 临时探索不会自动污染长期记忆
- 草稿式交互和正式沉淀可以共存
- WebSocket runtime 的状态可以和正式消息存储解耦

### 对应代码

```text
src/main/java/com/noteweave/chat/runtime
src/main/java/com/noteweave/websocket
src/test/java/com/noteweave/chat/Phase5WorkspaceChatRuntimeIntegrationTest.java
```

重点类：

```text
ChatRuntimeService
ChatRuntimeStateStore
ActiveExecutionRegistry
ContextReadRouter
WebSocketTicketService
```

### 这一块要学的知识点

- HTTP 业务态和 WebSocket 运行态怎么分
- 为什么 Redis 更适合放运行态，而不是业务事实
- stop / resume 的设计点是什么
- 为什么 AI 对话系统经常需要“正式态”和“临时态”分离

---

## 9. 第五个核心：个人研究链路

团队链路解决的是“共享知识问答”，个人链路解决的是“研究资料加工和长期沉淀”。

## 9.1 个人链路不是聊天，而是编译式知识加工

可以先记成这条链：

```text
ResearchProject
  -> Source
  -> SourceImport
  -> WikiCompiler
  -> ArticleCard / ConceptCard / Relation
  -> Artifact
  -> Distillation
  -> SynthesisCard
```

### 9.1.1 Source 为什么重要

它是个人研究侧的原材料入口，支持：

- TEXT
- FILE
- URL

而且 URL 不是裸抓取，项目专门做了安全抓取逻辑。

### 对应代码

```text
src/main/java/com/noteweave/personal/project
src/main/java/com/noteweave/personal/source
src/test/java/com/noteweave/personal/Phase6PersonalResearchSourceIntegrationTest.java
src/main/resources/db/migration/V7__phase_6_personal_research_source.sql
```

重点类：

```text
SourceService
SourceImportService
SafeUrlContentFetcher
```

### 这一块要学的知识点

- 个人研究数据为什么不能直接等价成团队 Document
- URL 导入为什么要考虑 SSRF 风险
- 为什么导入 READY 之前必须保证后续可编译文本存在

## 9.2 Wiki Compiler 是个人链路的知识抽象器

它不是简单摘要，而是把资料编译成结构化知识：

- `ArticleCard`
- `ConceptCard`
- `ConceptAlias`
- `ConceptRelation`
- `ArticleConceptRelation`

这个设计说明项目对“长期知识”不是只存原文，而是存一层可复用抽象。

### 对应代码

```text
src/main/java/com/noteweave/personal/compiler
src/main/java/com/noteweave/personal/card
src/test/java/com/noteweave/personal/Phase7PersonalWikiCompilerIntegrationTest.java
src/main/resources/db/migration/V8__phase_7_personal_wiki_compiler_cards.sql
```

重点类：

```text
WikiCompilerService
EvidenceBacktraceService
ConceptMergeService
```

### 这一块要学的知识点

- 为什么“卡片化知识”比“原文堆积”更利于后续生成
- 为什么证据回溯要做成正式关系，而不是只做展示 JSON
- alias / relation / merge 这些知识建模点在真实系统里有什么用

### 学完后你应该能讲出来的话

“个人链路不是做一个笔记本，而是做一条研究资料编译链：先把 Source 转成可分析文本，再编译成文章卡、概念卡、概念关系和证据链，后续 Artifact 和 Synthesis 都建立在这层结构化知识上。”

---

## 10. 第六个核心：Artifact、Methodology、Distillation

这块是 NoteWeave 很有个性的地方。

### 10.1 Artifact 不是普通回答，而是可版本化产物

团队问答和个人研究都可能生成 Artifact，但它和 ChatMessage 不一样。

它更像：

- 报告
- 学习提纲
- 对比分析
- 工作准备材料

也就是说，Artifact 是“可编辑、可版本化、可导出”的产物层。

### 10.2 为什么还要有 Methodology

因为系统不只是“根据资料写一篇东西”，还希望：

- 让生成风格更稳定
- 让不同任务模板可复用
- 让生成方法可配置

这就是 `MethodologyCard` 的意义。

### 10.3 为什么还要有 Distillation / SynthesisCard

因为项目不希望：

- 所有生成结果都自动变成长期知识

所以它做了一层确认式沉淀：

```text
Artifact
  -> 用户确认
  -> DistillationProposal
  -> SynthesisCard
```

这个边界非常重要，它防止“生成内容直接污染个人长期知识”。

### 对应代码

```text
src/main/java/com/noteweave/artifact
src/main/java/com/noteweave/studio
src/main/java/com/noteweave/personal/generation
src/main/java/com/noteweave/personal/distillation
src/main/java/com/noteweave/personal/methodology
src/test/java/com/noteweave/artifact/Phase8StudioArtifactIntegrationTest.java
src/test/java/com/noteweave/personal/Phase11PersonalGenerationIntegrationTest.java
src/test/java/com/noteweave/personal/Phase11_5PersonalArtifactDistillationIntegrationTest.java
```

### 这一块要学的知识点

- 为什么生成系统需要“产物层”
- 为什么 Artifact 不能直接等于 Wiki
- 为什么方法论卡会成为生成稳定性的抓手
- 为什么“proposal / confirm / distill”比自动沉淀更稳

---

## 11. 第七个核心：长期记忆

Memory 是这个项目另一个容易说过头、但其实设计得很克制的模块。

### 11.1 NoteWeave 里的记忆不是万能大脑

它当前更接近一种 **分层长期上下文系统**，而不是无边界的 agent memory。

主要对象包括：

- `SessionSummary`
- `SpaceMemory`
- `UserMemory`
- `MemoryItem`

### 11.2 它想解决什么问题

不是“什么都记住”，而是：

- 会话结束后沉淀摘要
- 保存用户稳定偏好
- 保存空间级长期上下文
- 在当前轮按需读取相关记忆

### 11.3 为什么要强调写回边界

因为临时探索、未确认内容、草稿态信息如果直接写长期记忆，很容易污染系统。

所以项目里有很明确的边界：

- DRAFT 不写长期 Memory
- 正式内容才进入写回判断
- 记忆有重要性、置信度、过期和删除控制

### 对应代码

```text
src/main/java/com/noteweave/memory
src/test/java/com/noteweave/memory
src/main/resources/db/migration/V14__phase_12_long_term_memory.sql
```

重点类：

```text
MemoryContextService
MemoryWritebackService
MemoryWritebackStrategy
SessionSummaryService
UserMemoryService
SpaceMemoryService
```

### 这一块要学的知识点

- 短期运行态和长期记忆为什么不能混
- Memory 读取为什么要有 router
- 写回为什么要有策略而不是每轮都写
- AI 系统里“记忆污染”是什么意思

### 学完后你应该能讲出来的话

“NoteWeave 的长期记忆不是把聊天记录全塞回去，而是把正式会话摘要、用户稳定偏好和空间长期上下文拆开管理，再通过读取路由和写回策略控制什么时候读、什么时候写，重点是保留连续性而不是制造噪音。”

---

## 12. 第八个核心：Wiki、Graph、Auto Wiki Maintenance

这是当前仓库里一个很值得学、也很容易被忽略的点。

### 12.1 Wiki 在这个项目里是什么

Wiki 不是“聊天记录展示页”，也不是“Artifact 别名”。

它是团队长期稳定知识层，具备：

- 草稿
- 发布
- 版本
- 索引
- 关系
- 图视图

### 12.2 为什么要有 Graph

因为团队长期知识除了被搜出来，还希望能被“关系化地看见”。

当前代码里可以看到两层图能力：

- `WikiGraph`
- `KnowledgeGraph`

它们让页面、链接、引用、关系、路径变成可查询对象。

### 12.3 Auto Wiki Maintenance 解决什么问题

它在做的是：

- 文档或 Source 变化后，同步维护长期 Wiki 层

这说明项目已经不只是“人手动维护每一页 Wiki”，而是在往自动维护长期知识结构的方向走。

### 对应代码

```text
src/main/java/com/noteweave/team/wiki
src/main/java/com/noteweave/graph
src/test/java/com/noteweave/team/wiki/Phase10TeamWikiIntegrationTest.java
src/main/resources/db/migration/V18__phase_16_team_wiki_graph.sql
src/main/resources/db/migration/V19__phase_17_auto_wiki_maintenance.sql
```

重点类：

```text
TeamWikiService
WikiGraphService
WikiGraphSyncService
AutoWikiMaintenanceService
KnowledgeGraphService
KnowledgeGraphController
```

### 这一块要学的知识点

- Wiki 为什么是“长期知识层”，而不是普通页面
- 图能力为什么先从 Wiki graph 做起
- WikiGraph 和完整 GraphRAG 的区别
- 自动知识维护为什么要晚于基础 RAG 做

---

## 13. 第九个核心：评测、可观测与运维

这是把项目从“AI demo”变成“可维护系统”的关键部分。

### 13.1 评测不只是给一个分数

在 NoteWeave 里，评测和可观测性至少覆盖：

- PromptVersion
- LLMCallLog
- RetrievalTrace
- AnswerFeedback
- RAGEval
- AdminTask
- Health
- AuditLog

它的价值不是“做个后台页面”，而是能追问题：

- 哪次回答用了什么 Prompt
- 召回到了哪些证据
- 引用覆盖够不够
- 用户反馈怎么样
- Eval Run 结果如何

### 13.2 为什么 Eval 要隔离执行

因为评测链路如果直接写正式会话、正式记忆，就会污染业务事实。

所以这类系统一定要区分：

- 正式业务链路
- 评测链路

### 对应代码

```text
src/main/java/com/noteweave/rageval
src/main/java/com/noteweave/admin
src/main/java/com/noteweave/prompt
src/main/java/com/noteweave/chat/service/RetrievalTraceService.java
src/test/java/com/noteweave/admin/Phase14ObservabilityEvaluationIntegrationTest.java
src/test/java/com/noteweave/admin/Phase15AdminOpsIntegrationTest.java
```

### 这一块要学的知识点

- AI 系统为什么需要 PromptVersion
- 为什么 Trace 和 Citation 都要持久化
- Eval 为什么不能污染正式会话
- 清理、健康检查、Audit 为什么是 AI 系统必备能力

### 学完后你应该能讲出来的话

“NoteWeave 在 Phase 14/15 之后已经不是只有功能，而是把 Prompt、检索、LLM、Citation、Feedback、Eval、AdminTask、Health、Audit 串成了一套可观测和可回归验证的系统，这样问题出现时可以按链路排查，而不是只看应用日志。”

---

## 14. 这个项目最值得学习的 12 个知识点

如果你不是为了背模块，而是为了学能力，那这 12 个点最值得掌握。

### 14.1 领域边界先于 AI 能力

先把团队空间、个人空间、权限和资源归属说清，再谈检索和生成。

### 14.2 AI 长任务必须统一执行模型

上传、编译、生成、评测都走一套 Task 骨架，系统才能稳定演进。

### 14.3 RAG 不只是检索，而是证据系统

Citation、Evidence、RetrievalTrace 是这个项目里比“回答内容”更重要的部分之一。

### 14.4 团队知识和个人研究要分治

它们共享基础设施，但知识形态、沉淀方式、权限边界完全不同。

### 14.5 Artifact 层是很有价值的中间层

它把“聊天结果”和“长期知识”隔开了。

### 14.6 长期记忆一定要克制写回

不是越会记越好，而是越不污染越好。

### 14.7 图能力应该建立在长期知识层上

先有 Wiki，再有 Graph，会比一开始就喊 GraphRAG 更稳。

### 14.8 评测必须隔离

Eval 样本和正式业务不能混。

### 14.9 运行态和业务事实要分开

Redis runtime state 和 MySQL 业务事实不是同一层东西。

### 14.10 安全输入边界不能忽略

URL Source、MCP 工具、文件上传都要当作不可信输入。

### 14.11 设计里要允许“确认后沉淀”

自动生成内容默认不能直接变成长期知识。

### 14.12 真正可讲清楚的项目，一定能落到类、表、测试

如果一个功能讲不清对应：

- 哪个 service
- 哪张表
- 哪个 integration test

通常说明你理解还不够扎实。

---

## 15. 推荐学习路径

如果你是第一次系统学习这个项目，建议按 5 步走。

### 第一步：先看“边界层”

读：

```text
space
permission
auth
user
```

目标：

- 看懂系统怎么区分 TEAM / PERSONAL
- 看懂角色与资源访问怎么控制

### 第二步：再看“执行骨架”

读：

```text
task
相关 migration
task 集成测试
```

目标：

- 看懂异步任务怎么统一建模

### 第三步：看“团队知识闭环”

读：

```text
team.document
team.rag
chat
citation
wiki
graph
```

目标：

- 看懂上传、解析、索引、问答、引用、Wiki、Graph 怎么串起来

### 第四步：看“个人研究闭环”

读：

```text
personal.source
personal.compiler
personal.card
artifact
studio
personal.generation
personal.distillation
```

目标：

- 看懂 Source 怎么变成 Card，再怎么变成 Artifact 和 Synthesis

### 第五步：最后看“系统化能力”

读：

```text
memory
rageval
admin
prompt
websocket
```

目标：

- 看懂这个项目如何从功能集合变成可运维、可评测、可恢复的系统

---

## 16. 针对这个项目，适合重点掌握的技术知识

## 16.1 Java / Spring Boot 侧

- Spring Boot 分层应用怎么组织
- Controller / Service / Repository 分工
- Spring Security + JWT
- 统一异常与错误码
- WebSocket 会话与 ticket 模式
- 事务边界和业务幂等

## 16.2 数据与存储侧

- MySQL 业务事实建模
- Flyway 迁移演进
- MinIO 对象存储建模
- Elasticsearch 检索读模型
- Redis 运行态缓存

## 16.3 异步系统侧

- Outbox 模式
- Kafka 任务解耦
- Worker 执行模型
- 取消、重试、恢复、清理

## 16.4 AI / RAG 侧

- Chunking
- Hybrid retrieval
- RRF
- Evidence post-processing
- Citation / Traceability
- Prompt versioning
- Eval / Feedback

## 16.5 知识系统侧

- Wiki 和 Artifact 的边界
- 个人卡片化知识建模
- 长期记忆写回策略
- 图谱视图与关系维护

---

## 17. 读这个项目时最容易混淆的几组概念

### 17.1 Team KnowledgeBase vs Personal Source

- Team KnowledgeBase：共享知识库
- Personal Source：个人研究原材料

### 17.2 ChatMessage vs Artifact vs Wiki vs SynthesisCard

- ChatMessage：交互过程
- Artifact：阶段性产物
- Wiki：团队长期知识
- SynthesisCard：个人确认后的长期沉淀

### 17.3 RetrievalTrace vs Citation

- RetrievalTrace：召回过程
- Citation：最终答案或产物绑定的证据关系

### 17.4 Runtime State vs Memory

- Runtime State：会话运行时状态
- Memory：长期上下文或稳定偏好

### 17.5 WikiGraph vs GraphRAG

- WikiGraph：当前长期知识关系视图
- GraphRAG：更重的图推理检索主链

---

## 18. 学完这个项目后，你应该能讲清楚什么

如果这份文档真的吸收进去了，你至少应该能独立讲清下面这些问题：

1. NoteWeave 为什么要做 TEAM / PERSONAL 双空间？
2. 为什么 Task / Outbox / Kafka / Worker 是全项目骨架？
3. 团队 RAG 为什么要把 Citation 和 RetrievalTrace 做成正式对象？
4. 为什么 Artifact 不能直接等于 Wiki？
5. 为什么 DRAFT / FORMAL 要分开？
6. 为什么长期 Memory 不能每轮都写？
7. Wiki、Graph、Auto maintenance 为什么是后置但重要的能力？
8. RAG Eval 为什么必须隔离执行？
9. 当前项目哪些是已实现主线，哪些不能说满？
10. 如果让你继续做，你会优先优化哪条链路？

---

## 19. 一句话总结这个项目

如果最后只留一句话，我会这样概括 NoteWeave：

**它不是把 RAG、Wiki、Artifact、Memory 拼在一起，而是把团队知识协作和个人研究沉淀放进同一套权限、任务、证据、生成、评测底座中，做成了一套有边界、有回溯、有沉淀能力的 AI 知识工作台。**

