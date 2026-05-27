# 文件：11_通用问题按NoteWeave深答.md

## 0. 本篇定位

这篇现在只承担一个角色：`通用问题统一转答总册`。

它解决的不是“某个主题怎么完整深答”，而是：

- 面试官的问题本身不完全属于 NoteWeave，怎么先转成当前项目能诚实承接的问法。
- 面试官抛的是通用八股、开放题、风险题，怎么快速落到 NoteWeave 的真实链路。
- 回答里怎么同时保住项目真实性、技术深度和边界。

去重后的分工是：

- `01-项目主述题.md`：项目开场主文档。
- `02-模块深问题.md`：模块级技术主文档。
- `09-重点系统功能QA深入版.md`：系统设计总串讲主文档。
- `通用问题分主题深答/*`：每个主题的标准深答主文档。
- `通用问题分主题深答_加深版/*`：在标准深答基础上补原理、极端场景、压测、trade-off。
- `重点系统功能链路/11_中间件RAGAgent系统设计映射/README.md`：中间件、RAG、Agent 和高频八股的主映射文档。

所以这篇不再重复保存每个主题的长答案，只保留最有价值的“转答动作”和“统一口径”。

## 1. 使用方式

如果面试官的问题有下面这些特点，就先用这篇：

- 问法很通用，不像专门针对 NoteWeave。
- 问的是“为什么这样设计”“如果换成别的场景怎么办”“你怎么证明不是 demo”。
- 问的是 Redis、MQ、RAG、Agent、Memory、指标、分库分表、AI Coding 这种容易跑偏的话题。
- 你一时不知道该落到哪条链路上。

统一动作是：

```text
先把问题转成 NoteWeave 的真实业务问题
-> 再落到一个主链路或主模块
-> 再补设计取舍、失败处理和边界
```

## 2. 通用问题最常见的 10 种接法

| 面试官常见问法 | 第一反应怎么接 | 优先落到哪里 | 不能说满的点 |
|---|---|---|---|
| 介绍一下你的项目 | 先讲 AI 知识工作台闭环，不要先报技术栈 | `01-项目主述题.md` / `09-重点系统功能QA深入版.md` | 不编生产指标 |
| 你具体做了什么 | 讲三条 ownership 主线，不说“全是我做的” | 统一异步、evidence-first RAG、知识沉淀边界 | 不夸大个人贡献 |
| 为什么这么设计 | 先讲业务风险，再讲架构方案 | `02-模块深问题.md` / 分主题 `02` | 不空喊“为了性能/为了安全” |
| 为什么要异步 / 用 MQ | 先讲长任务，再讲 Outbox 和最终一致 | 分主题 `03` | 不说强一致、不说绝不重复 |
| Redis 在项目里干嘛 | 先讲短期运行态，再讲边界 | 分主题 `04` / `06` | 不说主任务队列、主事实源 |
| RAG / 幻觉怎么治理 | 先讲 evidence-first，不先讲模型名字 | 分主题 `05` | 不说 Citation=正确率 |
| Agent / Skill / MCP 怎么讲 | 先讲当前是受控 workflow | 分主题 `07` | 不说完整开放 Agent 已落地 |
| 没有生产指标怎么办 | 先承认没有，再讲验证方案 | 分主题 `08` | 不编 QPS/P99/token/day |
| 挑一个复杂接口讲 | 优先选文档上传 merge 或团队 RAG 问答 | 分主题 `09` | 不只讲接口字段 |
| 你怎么和产品/业务沟通 | 先讲业务目标，再讲技术约束 | 分主题 `09` | 不只讲“我解释了原理” |

## 3. 万能转答模板

### 3.1 项目整体类

`适用问题：`

- 请介绍一下你的项目。
- 这个项目解决什么问题？
- 为什么不是普通 demo？

`最稳转答：`

我会先把 NoteWeave 定义成一个 AI 知识工作台，而不是一次性问答 demo。它解决的是知识从资料进入系统、被解析检索、被引用生成、再沉淀为长期知识的闭环问题。团队侧是 KnowledgeBase、Document、Hybrid RAG、Citation、Wiki；个人侧是 Source、Card、Artifact、Synthesis。底层再用双空间权限、统一异步任务、WebSocket Runtime、Memory、Eval 和 Admin/Ops 把这条链路做成可治理系统。

`继续追问时要补：`

- 双空间为什么是一级边界。
- 为什么要统一任务底座。
- 为什么回答之后还要有 Citation、Trace 和沉淀层。

### 3.2 Ownership / 亮点类

`适用问题：`

- 你具体做了什么？
- 项目亮点和难点是什么？

`最稳转答：`

我不会说“所有功能都是我做的”，而会收成三条 ownership 主线。第一条是统一异步任务底座，把上传解析、Source 编译、Artifact 生成、Eval 和 cleanup 收口到 `Task + Outbox + Kafka + Worker`。第二条是团队 RAG 的 evidence-first 链路，从权限过滤、混合检索、RRF 融合到 Citation 和 RetrievalTrace 持久化。第三条是 Artifact 到长期知识的沉淀边界，保证生成内容不会自动污染 Wiki 或个人知识库。

`继续追问时要补：`

- 这三条为什么比“做了多少页面”更有技术含量。
- 如果只能讲一个亮点，优先讲哪一个。

### 3.3 架构设计 / 为什么不用简单方案

`适用问题：`

- 为什么这么设计？
- 为什么不直接做简单一点？
- 为什么现在不拆微服务？

`最稳转答：`

我一般先把问题还原成业务风险。NoteWeave 里最难的不是把模型接起来，而是同时处理权限、一致性、证据追踪、运行态和长期知识边界。所以我会优先做模块化单体，把权限、任务、Citation、Artifact、Memory 这些强关系放在一个演进成本更低的边界里；同时通过 Task/Kafka、检索边界和 LLM gateway 预留后续拆分空间。

`继续追问时要补：`

- 如果以后拆分，优先拆 Worker、检索和 LLM gateway。
- 为什么权限域不是第一批拆分对象。

### 3.4 MQ / Outbox / 幂等一致性

`适用问题：`

- 为什么要用 MQ？
- 为什么要 Outbox？
- 幂等怎么做？

`最稳转答：`

NoteWeave 里文档解析、Source 编译、Artifact 生成、RAG Eval、Cleanup 都是长任务，不适合同步 HTTP 里做完。我的做法是先在 MySQL 里落 Task 和 Outbox，再由 Kafka 推动 Worker 执行。这里不用大事务强行包住 MySQL、Kafka、MinIO、ES 和 LLM，而是接受中间态，通过 Outbox 补偿、Task 状态机和消费侧幂等把结果收敛起来。

`继续追问时要补：`

- Outbox 会重复，所以 Worker 必须回查 Task 状态。
- `cancel_requested` 是安全点取消，不是强杀线程。
- Kafka 堆积时先看任务类型和下游瓶颈，不盲目加消费者。

### 3.5 Redis / 缓存 / 运行态

`适用问题：`

- Redis 在项目里做什么？
- 为什么 Redis 不做主任务队列？
- 你们有没有本地缓存？

`最稳转答：`

Redis 在 NoteWeave 里主要承接短期状态，而不是业务事实源。典型场景是上传分片 bitmap、WebSocket ticket、runtime state、partial content、event buffer 和 stop/resume 控制。正式消息、Citation、Task、Memory 还是落 MySQL，后台长任务走 Kafka。这样 Redis 丢了会影响运行态体验，但不该破坏正式业务事实。

`继续追问时要补：`

- Redis 为什么适合 TTL、高频读写和恢复窗口。
- 当前没有把本地缓存做成主链路，不要硬编二级缓存故事。

### 3.6 RAG / 模型 / 幻觉治理

`适用问题：`

- 用的什么模型？
- 幻觉怎么处理？
- 为什么 Hybrid RAG？

`最稳转答：`

我不会先从模型名字答起，而是先讲 evidence-first 链路。用户提问后先做权限和 session scope 限制，再走 BM25、向量和 Wiki recall，多路结果用 weighted RRF 融合，之后做 evidence post-process，最后把治理后的 evidence 交给 Prompt。回答完成后还会保存 Citation、RetrievalTrace 和 LLMCallLog，所以回答是否可信、问题出在哪里都可以反查。

`继续追问时要补：`

- BM25、向量、Wiki recall 各自解决什么。
- 无证据兜底比“什么都答”更重要。
- citation coverage 高不等于答案一定正确。

### 3.7 Memory / Agent / Skill 边界

`适用问题：`

- 长期记忆怎么做？
- 这是 Agent 吗？
- MCP、GraphRAG、Bibtex 怎么回答？

`最稳转答：`

Memory 和 Agent 都要先讲边界。Memory 不是聊天记录全量复制，而是最近消息、session summary、space memory、user memory 的分层读取和策略写回，DRAFT、敏感信息和低价值内容不会直接进长期记忆。Agent 这块当前更准确的说法是受控 Skill Pipeline 或 Workflow，不是完整开放式 Agent 平台。MCP 也不能再笼统讲成“完全没做”，因为当前已经把 B 站解析能力拆成远程 MCP tool service，并接到产物入口和对话显式触发；但完整开放平台仍然不是当前主链路。Bibtex、GraphRAG 继续按扩展方向回答。

`继续追问时要补：`

- DRAFT 为什么不写长期 Memory。
- MethodologyCard 为什么比把结构全写死在 Prompt 里更稳。
- 开放 Agent 的难点是权限、预算、日志、回滚和评测。

### 3.8 指标 / 压测 / 效果

`适用问题：`

- 效果提升了多少？
- QPS、P95、P99 是多少？
- token/day 怎么看？

`最稳转答：`

当前没有真实线上 QPS、P99、token/day 或准确率，我不会编造。更稳的回答是讲指标口径和验证方案：任务链路看成功率、失败率、重试率和耗时；RAG 看 recall@k、MRR、citationCoverage、no-evidence rate；模型调用看 latency、token、错误类型；运行态看 stop/resume 和断线恢复；再通过 RagEvalRun、Trace、Log 和压测逐层验证。

`继续追问时要补：`

- 为什么“没有线上数据”不等于“没有工程验证”。
- citation coverage 不等于 correctness。

### 3.9 复杂接口 / 排障 / 沟通

`适用问题：`

- 挑一个复杂接口讲。
- 一条坏回答怎么排查？
- 复杂技术怎么讲给业务方？

`最稳转答：`

复杂接口优先讲文档上传 merge 或团队 RAG 问答。前者能讲上传分片、对象存储、异步解析、索引版本和权限边界；后者能讲权限过滤、混合检索、证据治理、Citation 和 Trace。排障时不要只说“去看日志”，而要按 `ChatMessage -> RetrievalTrace -> Citation -> LLMCallLog -> Eval` 或 `Task -> Attempt -> Event -> 下游组件健康` 这样顺着链路查。给业务沟通时，先讲业务风险和收益，再解释为什么需要这些技术约束。

`继续追问时要补：`

- 为什么最复杂的不是接口参数，而是状态、一致性和边界一起成立。
- Citation、TaskOutbox、Memory 这些词如何翻译成业务能理解的话。

### 3.10 AI Coding / 个人表达

`适用问题：`

- 你怎么用 AI Coding？
- AI 生成代码不符合预期怎么办？

`最稳转答：`

我会把 AI Coding 用在代码阅读、方案对比、测试补全和文档整理上，但涉及权限、一致性、异常语义和安全边界的代码一定自己 review。AI 输出不符合预期时，我不会让它无限大改，而是缩小上下文、给明确接口契约和错误日志、要求它做小步 patch，再看 diff、跑测试、核对链路。

`继续追问时要补：`

- 为什么 AI 提高的是研发效率，不是替代验证。
- 这套使用方式和 NoteWeave 自己对 AI 输出的治理逻辑是一致的。

## 4. 风险题统一收口

### 被问到生产指标

当前没有真实线上 QPS、P99、token/day、线上准确率，我不会编造。能讲的是已有 Trace、LLMLog、RagEvalRun、SystemHealth、AuditLog 和压测入口，后续会按任务层、检索层、生成层和端到端层做验证。

### 被问到分库分表 / 微服务

当前没有分库分表，也不是微服务主链路。更合理的回答是：现在是模块化单体，后续如果任务执行、检索或日志表规模先成为瓶颈，会优先做归档、分区、冷热分层，再判断是否拆服务或分库。

### 被问到完整 Agent / Multi-Agent

当前不能讲成完整开放 Agent 平台。更准确的说法是：已经有受控 Skill Pipeline、Artifact 生成、MethodologyCard 和证据治理；多 Agent 可以作为后续演进方向，但前提是工具权限、预算、日志、失败状态和评测都能被治理。

### 被问到 MCP / Bibtex

Bibtex 当前不是主链路，可以说成未来接入点；MCP 则要更准确一些：当前没有做完整开放平台，但已经把 B 站解析拆成远程 MCP tool service，并接在 Tool/Skill 编排前的产物入口和对话显式触发链路上。不要把它包装成广义多工具自治生态即可。

### 被问到 GraphRAG

当前不能讲成主检索链路。现在能坚定讲的是 Wiki 页面关系、图谱展示和 Wiki recall；完整多跳 GraphRAG 推理属于后续扩展。

## 5. 最推荐背诵的万能深答

如果面试官抛出一个很通用、甚至有点跨项目的问题，我最稳的接法是：先把它转成 NoteWeave 的真实系统问题。比如问 Redis，我不只讲 Redis 快，而会讲它在 NoteWeave 里负责上传进度和 WebSocket 运行态，不是主任务队列；问 MQ，我不只讲削峰解耦，而会讲文档解析、Artifact 生成和 Eval 这些长任务为什么要走 `Task + Outbox + Kafka + Worker`；问 RAG，我不只讲模型和向量，而会讲权限过滤、Hybrid recall、证据治理、Citation 和 Trace。这样回答的核心不是把八股背出来，而是让通用问题真正落回项目的业务闭环、系统边界和失败处理。

## 6. 边界和不能说满的地方

- 这篇只负责通用问题转答，不再重复保存每个主题的完整深答。
- 当前可以坚定讲：双空间、统一异步任务、上传解析索引、Hybrid RAG、Citation、Runtime、个人研究链路、Artifact/Synthesis、Memory、Eval、Admin/Ops。
- 当前不要讲成已落地：真实生产指标、完整开放式 MCP 平台主链路、Bibtex 端到端、完整开放 Agent、GraphRAG 主检索链路、微服务拆分、分库分表。
