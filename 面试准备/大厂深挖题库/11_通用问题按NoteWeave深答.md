# 通用问题按 NoteWeave 深答

> 本文件为 2026-06-01 题组版，依据当前代码、测试和 Flyway 迁移整理。这里不按模块单独加深，而是把大厂常见通用题合并成相似问题题组，再映射回 NoteWeave 的真实链路。

## 0. 使用方式

通用八股题不要脱离项目背。每次回答都按这个顺序：

1. 先判断面试官问的是哪类问题：边界、一致性、表结构、中间件、证据、沉淀、工具、运营、验证、扩展。
2. 再把相似问题放在一组回答，避免“问上传就只讲上传，问 RAG 就只讲 RAG”。
3. 最后落到 NoteWeave 的代码事实：Task/Outbox/Kafka、Hybrid RAG、Citation/Trace、Artifact/Synthesis、MCP/Skill、Admin/Ops。

## 1. 架构边界类：为什么这样分层，怎么避免做成 Demo？

这一组常见问法：
- 你这个项目的业务边界是什么？
- 为什么不是一个普通 RAG 问答 Demo？
- 为什么要同时做 TEAM 和 PERSONAL？
- 系统角色、空间角色、资源权限为什么要拆开？

2 分钟回答：

> 我会把 NoteWeave 讲成一个 AI 知识工作台，而不是单纯聊天页面。它的核心闭环是：资料进入系统，经过权限约束和异步处理变成可检索知识；用户问答时先检索可见证据，再生成带 Citation 的回答；生成出的 Artifact、Wiki、SynthesisCard、Memory 又要区分临时内容和长期知识。这个闭环里最先要定的是边界，所以项目把 Space 做成一级容器，并区分 TEAM 和 PERSONAL。TEAM 侧更偏团队知识库、RAG Chat、Wiki、Graph、Admin/Ops；PERSONAL 侧更偏 ResearchProject、Source、ArticleCard、ConceptCard、SynthesisCard、Artifact、Methodology。系统角色 users.system_role 负责 USER/ADMIN 这类全局管理能力，SpaceMember.role 负责 OWNER/EDITOR/VIEWER 这类空间协作能力。这样 Citation、Trace、Artifact、Memory、Admin 查询都能回到同一套边界，避免 AI 链路二次读取时串数据。

追问怎么接：
- 如果问“Controller 校验后 Service 还要不要校验”，答 Service 层仍要做关键资源边界，因为后台任务、Admin、WebSocket、异步 Worker 不一定都从同一个 Controller 入口进来。
- 如果问“Admin 能不能看所有私有数据”，答 Admin 是运维管理角色，不应该天然绕过个人私有 Memory 和团队空间边界，敏感读取要有审计和最小权限。
- 如果问“为什么 PERSONAL 和 TEAM 不共用一套模型”，答它们生命周期不同：个人研究强调 Source、卡片和 Synthesis，团队知识强调 KnowledgeBase、Document、Wiki、协作权限和公开可追溯。

不能说：
- 不要把团队 OWNER 说成系统 ADMIN。
- 不要说权限只靠前端隐藏按钮。
- 不要说 Admin 可以无边界绕过所有数据。

## 2. 一致性类：长任务、MQ、Outbox、重试怎么讲？

这一组常见问法：
- 为什么不在事务里直接发 Kafka？
- Outbox 解决什么一致性问题？
- Worker 重复消费如何保证幂等？
- 任务取消、重试、失败恢复怎么设计？
- 上传解析、Wiki 入索引、RAG Eval、cleanup 为什么都能统一讲？

2 分钟回答：

> NoteWeave 里很多能力都是长任务：文件上传后的解析和索引、个人 Source 编译、Artifact 生成、Wiki 入索引、RAG Eval、资源清理。如果每条链路都自己同步处理或自己发消息，前端会超时，失败恢复也会分散。所以项目把后台执行抽成 Task、TaskAttempt、TaskEvent、TaskOutbox、Kafka、Worker。业务事务里先写业务事实、Task 和 Outbox；之后由 Outbox 调度器把消息投到 Kafka；Consumer 收到后不信消息里的复杂状态，只拿 taskId 回查 DB；Worker 通过 TaskExecutionCoordinator 记录 attempt、event、状态流转和 cancel_requested。这个设计不是强一致承诺，而是让数据库事实和消息投递最终一致，同时让重试、取消、审计和 Admin 介入都走统一入口。

追问怎么接：
- 如果问“消息投递成功但 Worker 失败”，答 Task 保持失败或可重试状态，TaskEvent 记录失败原因，AdminTaskService 可以按规则重试或标记失败。
- 如果问“重复消费怎么办”，答 Consumer 以 taskId 回查当前状态，Worker 只在可执行状态推进，具体业务写入要用状态、唯一约束、版本或覆盖式索引保证幂等。
- 如果问“取消为什么不是强杀”，答 cancel_requested 是协作式取消，Worker 在安全点检查后停止，不能承诺打断已经发出的外部 IO 或 LLM 请求。

不能说：
- 不要说 Outbox 提供分布式强一致。
- 不要说 Redis 是后台任务主队列。
- 不要说所有失败都能自动恢复。

## 3. 数据模型和中间件类：表怎么拆，组件怎么落位？

这一组常见问法：
- 你项目里核心表结构怎么设计？
- 为什么不用一张大 JSON 表保存所有 AI 结果？
- MySQL、Redis、MinIO、ES、Kafka 各自承担什么？
- 如果 ES、Kafka、Redis、MinIO 任一组件出问题，系统怎么回到一致状态？

2 分钟回答：

> 我会按数据生命周期讲表结构。身份和权限用 users、space、space_member；长任务用 task、task_attempt、task_event、task_outbox；团队资料用 knowledge_base、document_upload、file_object、document、document_chunk；可信问答用 chat_message、citation、message_citation、retrieval_trace、retrieval_trace_item、llm_call_log、answer_feedback；生成沉淀用 artifact、artifact_version、wiki_page、wiki_page_version、synthesis_card、memory_item；评测和运维用 prompt_version、rag_eval_case/run/result、audit_log、ops_cleanup_job、system_health_snapshot。中间件也按形态分工：MySQL 是事实源，Redis 是短期运行态和 bitmap，MinIO 是大对象，ES 是检索读模型，Kafka 是异步任务通道。跨组件失败时，最终都要回 Task、Outbox、Trace、Citation、cleanup 和 Admin/Ops 对齐。

追问怎么接：
- 如果问“为什么很少显式 foreign key”，答当前由服务层边界、唯一索引、状态机和集成测试约束关系，后续可对核心强关系逐步补 FK，但 FK 不能替代权限判断。
- 如果问“哪些索引最关键”，答空间过滤、任务积压、文档版本、Trace 排障、Eval/Ops 时间序列这些访问路径。
- 如果问“ES 里有旧 chunk 怎么办”，答 ES 是读模型，MySQL 的 status、activeIndexVersion、权限和 Citation 读取校验才是事实。

不能说：
- 不要说已经做了分库分表或读写分离。
- 不要说 ES/Redis/Kafka 能替代 MySQL 事实源。
- 不要说 object_key、ES doc、Kafka message 自身就是权限凭证。

## 4. 外部输入类：文件、URL、Bilibili MCP、Prompt 怎么安全进入系统？

这一组常见问法：
- 上传到可检索经历哪些阶段？
- URL Source 要防哪些风险？
- `/mcp bilibili <url>` 是怎么进入 Chat 或 Artifact 的？
- 用户 prompt 和外部工具结果会不会污染长期知识？

2 分钟回答：

> 我会把外部输入统一讲成“受控进入系统”。文件上传不是上传完就能 RAG，而是 init、分片、MinIO 存储、merge、Document/FileObject 落库、DOCUMENT_PROCESS Task、解析、chunk、写 ES、更新 activeIndexVersion。URL Source 进入个人研究链路时，要先经过安全抓取和可读文本校验，READY 不等于随便信任网页。Bilibili MCP 是新增的受控工具入口，不是开放式工具市场：StudioMcpToolRegistry 负责解析和注册工具，LocalBilibiliMcpToolService 或 RemoteBilibiliMcpToolService 调 BilibiliMcpCoreService 得到结构化上下文。Chat 里可以通过 `/mcp bilibili <url>` 触发，ArtifactPlanExecutor 在识别到工具参数后加入 LoadMcpToolContextSkill，并通过 SkillExecutionLog 和 RetrievalTrace 留下执行过程。无论是文件、URL、MCP 还是 prompt，最后都要经过权限、任务、证据和人工确认边界，不能直接写进 Wiki 或长期 Memory。

追问怎么接：
- 如果问“秒传能不能跨 Space 复用”，答 FileObject 可以从对象层考虑复用，但权限和 Document 归属必须按 Space 隔离，不能因为 hash 一样就泄露存在性或内容。
- 如果问“URL Source 会不会引入 SSRF”，答 SafeUrlContentFetcher 不只是下载网页，它限制 http/https、禁止 userInfo，解析 DNS 后拦截 localhost、.local、内网、链路本地、多播等地址，并限制重定向次数和响应大小。
- 如果问“远程 Bilibili MCP 失败怎么办”，答工具调用失败会反映到计划执行失败或降级提示，不能让失败工具结果伪装成可靠证据。
- 如果问“Prompt injection 怎么处理”，答文档内容和工具结果只作为 evidence/context，PromptBuilder 要明确约束模型基于证据回答，权限和业务判断不能交给模型。

不能说：
- 不要说上传成功就代表已可检索。
- 不要说 MCP 是当前所有工具调用的主协议。
- 不要说外部工具结果会自动进入 Wiki/Memory。

## 5. 证据可信类：RAG、Citation、Trace、防幻觉怎么答？

这一组常见问法：
- Hybrid RAG 具体做了什么？
- BM25、向量、Wiki recall 各解决什么问题？
- Weighted RRF 为什么比简单拼接稳？
- 没有证据时为什么要兜底？
- 用户说回答错了，怎么排查？

2 分钟回答：

> NoteWeave 的 RAG 重点不是“把问题发给模型”，而是 evidence-first。团队问答先基于用户可见范围做召回，HybridRetriever 组合 BM25、向量和 Wiki recall；WeightedReciprocalRankFusion 把不同召回源按排名融合，避免某一路分数尺度支配全部结果；EvidencePostProcessor 做去重、相邻 chunk 合并、数量限制和截断；TeamRagPromptBuilder 再把证据和约束组织进 prompt。生成后 CitationService 把引用持久化，RetrievalTrace 记录召回、融合、后处理和 prompt 相关信息。这样坏答案不是只能看日志猜，而是能沿着 query、retrieval hits、evidence items、prompt version、LLM call、citation 一层层排查。

追问怎么接：
- 如果问“向量召回失败怎么办”，答可以保留 BM25/Wiki recall 的有限检索结果，并在回答中明确证据不足；不能编造向量结果。
- 如果问“Citation 不准确先看哪里”，答先看 Citation 指向的 resource/chunk，再看 RetrievalTrace 中该 evidence 是否进入 prompt，最后看模型是否引用错位。
- 如果问“RAG Eval 怎么验证”，答通过 eval case/run/result 记录 recallAtK、MRR、citationCoverage 等离线评测指标，支撑迭代，但不等于线上生产准确率。
- 如果问“这些算法怎么测”，答 RRF、EvidencePostProcessor、PromptBuilder 适合单测，团队 RAG 主链路再用集成测试验证权限、召回、trace、citation 是否一起落地。

不能说：
- 不要把 Hybrid RAG 说成完整 GraphRAG。
- 不要说 Citation 只靠模型输出编号。
- 不要编造 recall@k、准确率或线上 A/B 结果。

## 6. 临时到长期类：Chat、Artifact、Wiki、Synthesis、Memory 怎么分层？

这一组常见问法：
- Artifact 为什么不是 ChatMessage 的一个字段？
- Wiki 和 Artifact 为什么分开？
- SynthesisCard 为什么要用户确认？
- DRAFT/FORMAL 和长期记忆有什么关系？
- AutoWikiMaintenance 会不会替代人工确认？

2 分钟回答：

> NoteWeave 把临时探索和长期知识分开，这是面试里很重要的设计点。ChatMessage 是对话事实，DRAFT 更偏临时探索，FORMAL 才可能触发受控记忆写回。Artifact 是版本化产物，适合保存报告、学习指南、FAQ、技术总结、Wiki 草稿等阶段性输出；ArtifactVersion、ArtifactSource、ArtifactCitation 让它可迭代、可追溯。团队 Wiki 是人工确认后的长期团队知识，有 WikiPage、WikiPageVersion、WikiIndex、WikiGraph；个人 SynthesisCard 是用户确认后的个人长期沉淀，PersonalArtifactDistillationService 先生成 proposal，再 confirm 绑定 artifactVersionId 写入。MemoryWritebackStrategy 会过滤 DRAFT、短问候和敏感内容。AutoWikiMaintenance 和 WikiGraph 可以辅助发现链接、孤立页、维护建议，但不能替代人工确认。

追问怎么接：
- 如果问“为什么 Artifact 不自动进入 Wiki”，答生成结果可能只是草稿或阶段性推理，直接进入 Wiki 会污染团队长期知识。
- 如果问“proposal 为什么绑定 artifactVersionId”，答要保证用户确认的是某个确定版本，避免生成内容变更后沉淀对象不一致。
- 如果问“Memory 丢了怎么办”，答 Redis runtime state 丢失影响运行态恢复，正式 ChatMessage 和长期 Memory 以 MySQL 事实为准；同时不能把 Redis 说成业务事实源。

不能说：
- 不要说 Artifact 自动进入 Wiki、ConceptCard 或 Memory。
- 不要说 DRAFT 会写长期记忆。
- 不要说 AutoWikiMaintenance 已经是复杂审批流。

## 7. 工具和 Agent 边界类：MCP、Skill、ArtifactPlanExecutor 怎么讲？

这一组常见问法：
- Skill 在项目里是什么，不是什么？
- MCP 是怎么接进来的？
- ArtifactPlanExecutor 和 Agent 有什么区别？
- SkillExecutionLog 的价值是什么？

2 分钟回答：

> 当前项目里 Skill 更像受控生成步骤，而不是开放式自主 Agent。ArtifactPlanExecutor 会根据输入构造有限步骤，比如加载个人研究上下文、加载 MCP 工具上下文、生成 Artifact、写 Citation 和 Trace；如果输入里配置了 Bilibili MCP，就通过 StudioMcpToolRegistry 解析工具参数，调用本地或远程 Bilibili MCP 服务，把结构化上下文作为生成素材。每一步可以通过 SkillExecutionLog 记录 skillName、输入、输出、状态、耗时和 promptVersion，方便以后排查为什么生成结果不对。这个设计的好处是可控、可追踪、能落库；边界是它还不是完整 Agent marketplace，也不是模型自主选择任意工具的多 Agent 编排。

追问怎么接：
- 如果问“未来要变成 Agent 怎么演进”，答先抽象工具权限、工具 schema、执行沙箱、审计、重试和成本控制，再开放更复杂的 planning。
- 如果问“MCP 失败会不会影响主流程”，答工具上下文失败应在对应 Task/SkillExecutionLog 中体现，生成可以失败或给明确降级提示，不能吞掉错误后编造内容。
- 如果问“Skill 和 MethodologyCard 区别”，答 Skill 是执行步骤，MethodologyCard 是输出方法论和 prompt 结构约束。

不能说：
- 不要说当前已经有完整多 Agent 编排。
- 不要说 MCP 是通用开放平台。
- 不要把 SkillExecutionLog 说成模型训练数据闭环。

## 8. 可观测和运营类：坏答案、失败任务、资源残留怎么查？

这一组常见问法：
- 一次坏答案怎么定位？
- PromptVersion 有什么意义？
- RAG Eval 怎么和正式 ChatSession 隔离？
- cleanup scan 和 execute 为什么拆开？
- health 为什么按组件拆？
- 你怎么证明这些链路真的经过测试，而不是只靠手工点页面？

2 分钟回答：

> AI 应用要能面试到工程深度，就不能只讲“能回答”，还要讲坏答案怎么查、链路怎么验证。NoteWeave 里 PromptVersion 管不同场景 prompt 的版本，LLMCallLog 记录模型调用，RetrievalTrace 记录检索和证据，AnswerFeedback 记录用户反馈。RAG Eval 通过 case/run/result 做离线评测，走 RAG_EVAL_RUN Task，不污染正式 ChatSession。Admin/Ops 侧有 task 管理、cleanup、health、dashboard、audit。cleanup 先 scan 再 execute，是为了先给出候选残留和风险，再人工确认执行，降低误删；health 按 MySQL、Redis、MinIO、Kafka、ES、LLM 拆，是为了排障时快速定位是事实源、运行态、对象存储、消息、检索还是模型层出了问题。测试上，纯算法用单测，跨组件用 MockMvc + ContainerizedIntegrationTest，异步链路用 Task/TaskAttempt/TaskEvent/TaskOutbox 断言状态，RAG 用 Trace/Citation/Eval 做回归。

追问怎么接：
- 如果问“用户说引用不准先查什么”，答先查 RetrievalTrace 和 Citation，再查对应 resource/chunk 是否仍可见。
- 如果问“任务重试一直失败怎么办”，答看 TaskEvent 和业务错误，必要时 Admin 标记失败或重新触发，不要无限重试。
- 如果问“没有生产指标怎么说”，答诚实说当前有日志、trace、eval、task event 可支撑度量方案，但没有真实线上 QPS/P99/准确率。
- 如果问“集成测试覆盖什么”，答它覆盖 MySQL/Redis/Kafka/MinIO/ES 协作和接口状态，不等于线上压测。

不能说：
- 不要说 Eval 等同线上 A/B。
- 不要让普通用户访问全局运维日志。
- 不要编造生产效果数据。
- 不要把本地集成测试包装成真实生产稳定性数据。

## 9. 扩展和压测类：性能瓶颈、降级、未来演进怎么答？

这一组常见问法：
- 并发上来先优化哪里？
- Kafka 堆积、ES 挂、LLM 慢分别怎么办？
- 单体项目怎么演进？
- 哪些数据结构或索引会影响性能？

2 分钟回答：

> 我会先把瓶颈按链路拆开，而不是直接说分库分表。上传处理可能卡在 MinIO、解析 CPU、chunk 写库和 ES 写入；RAG 可能卡在向量召回、融合后处理、LLM latency；Artifact 可能卡在外部工具、LLM 和持久化；WebSocket 可能卡在连接数和 runtime state；Admin/Eval 可能卡在批量任务。当前项目是单体，但边界已经比较清楚：文档处理 Worker、RAG 检索生成、Artifact 生成、RAG Eval/Ops 都可以按 Task 和服务边界拆出去。降级时要讲具体组件：ES 不可用时问答只能提示检索不可用或使用有限上下文，Kafka 堆积看 Outbox 和 Task 积压，LLM 慢要做超时、重试、排队和成本控制，MinIO 异常会影响原文和文件下载。

追问怎么接：
- 如果问“为什么不一开始微服务”，答当前阶段先用清晰模块边界和统一任务骨架降低复杂度，等瓶颈明确后再按链路拆服务。
- 如果问“怎么压测”，答按上传处理、RAG 查询、WebSocket 流式、Artifact 生成、Admin Eval 分场景测，而不是只测一个 HTTP QPS。
- 如果问“Redis 能不能缓存全部知识”，答 Redis 适合短期运行态和票据，不适合做业务事实源或全文/向量检索主存储。

不能说：
- 不要说项目已经完成微服务拆分。
- 不要说已有真实 P99、QPS、token/day。
- 不要用分布式术语覆盖当前代码没有的能力。

## 10. 一句话总收束

遇到通用题时，把它收束成这句话：

> NoteWeave 的设计重点是把 AI 生成放进一个可控知识系统：先做权限和数据归属，再用 Task/Outbox/Kafka 承接长任务，用 Hybrid RAG/Citation/Trace 保证证据可查，用 Artifact/Wiki/Synthesis/Memory 区分临时结果和长期知识，再用 PromptVersion、LLMCallLog、RAG Eval、Admin/Ops 让效果和故障能被运营。

这句话能接住大多数追问，后面再按题组展开即可。
