# Memory 业界演化研究与 NoteWeave 推导路线

> `[行业参考]` 本文用于比较外部 Memory 产品和开源设计。NoteWeave 推导属于 `[目标设计]`，真实采纳、长期污染和 Token 收益均为 `[生产待验证]`。

> 研究范围：Memory 的分层、写入与提取、更新与冲突、遗忘与 TTL、同意与访问控制、检索、评估，以及这些能力如何按风险逐步演化。
>
> 本文不使用 NoteWeave 的 Git 历史作为证据。文中的“行业事实”来自项目官方文档或官方仓库；“NoteWeave 推论”是结合当前代码、迁移、接口契约和领域语言做出的设计推导，不声称是过去真实发生过的实现过程。

## 1. 当前问题与判断标准

### 1.1 当前 Memory 的事实边界

`docs/Memory机制详细设计.md` 已经把 Memory 定义为受控沉淀链路，而不是聊天记录开关：`signal/observation` 先形成候选，经策略、审核和版本化，再由 compiler 编译成运行时 `control-pack`。当前设计已经明确了以下约束：

- 以 `Workspace` 为隔离边界，不允许跨工作区召回。
- 同时保留候选、审核对象和 canonical runtime，运行时不能只解释旧对象模型。
- `chat`、`artifact`、`research` 使用不同 scope、预算和证据要求。
- 撤销、过期、负反馈会产生新的 runtime revision，失效版本不能继续进入后续运行。
- `shadow-recall` 只能观察或比较，不能绕过审核直接影响回答。

这些约束决定了 NoteWeave 不应把外部 Memory 产品的默认策略直接照搬。Memory 的核心接口应是“候选事实和受控上下文的编译器”，而不是“把向量库结果拼到 prompt”。

### 1.2 评价一个演化阶段是否合理

每一步至少要回答六个问题：

1. 保存的对象是什么，是工作态、事件、事实还是规则？
2. 谁能写入，写入是否要用户明确同意？
3. 新信息与旧信息冲突时，是覆盖、并存、失效还是人工裁决？
4. 召回为什么可以使用，是否带 scope、版本和证据？
5. 何时降权、过期、撤销或删除？
6. 如何用离线样例和线上反馈发现“记错、召回错、越权和过时”？

## 2. 业界共同词汇：四种记忆不是四张表

LangGraph 的 Memory 文档把长期记忆拆成 semantic、episodic、procedural，并把短期 thread memory 单独处理；它还明确区分 memory type 与 semantic search：前者是“存什么”，后者是“怎么找”。参见 [LangGraph Memory 概念](https://docs.langchain.com/oss/python/concepts/memory)。据此，NoteWeave 可以使用以下分层：

| 层 | 保存内容 | 生命周期 | 典型读取方式 | NoteWeave 对应 |
| --- | --- | --- | --- | --- |
| Working | 当前回合、当前任务草稿、短期 scratchpad | 运行或线程内 | 直接放入运行状态 | `Snapshot`、Run state、临时 context |
| Episodic | 某次对话、检索、工具调用及结果 | 长期可追溯，默认不直接全量注入 | 按时间、任务、相似经历检索 | `signal`/`observation`、`memory_outcome`、运行事件 |
| Semantic | 关于用户、工作区、资料或项目的稳定事实 | 版本化，可能被新事实失效 | 过滤 + 关键词/向量/实体/时间 | `memory_candidate`、`memory_item` |
| Procedural | 应当怎样做的规则、偏好、流程提示 | 需更高审查级别，影响行为 | 编译到受控 control-pack | approved revision、prompt/rule projection |

分层的意义不是把同一段文本复制四遍，而是让写入、权限、更新和召回各自拥有合适的契约。例如“上周部署失败”首先是 episodic；从多次结果归纳出的“该项目发布前必须检查迁移”才可能成为 procedural candidate；它们不能因为相似就共享同一信任等级。

## 3. 主流实现的行业事实

### 3.1 Mem0：托管式抽取、实体范围和混合召回

Mem0 官方 API 的新增接口从对话消息提取记忆，要求至少提供 `user_id`、`agent_id`、`app_id` 或 `run_id` 之一来限定实体范围；处理是异步的，返回事件 ID；可设置 `expiration_date`，到期后默认隐藏但仍保留在存储中，需显式 `show_expired` 才能查看（见 [Add Memories](https://docs.mem0.ai/api-reference/memory/add-memories)）。当前 v3 additive pipeline 的事实是单次 ADD-only 抽取，不在这条接口中 UPDATE/DELETE，记忆会累积；删除、更新和反馈使用独立 API（见 [Mem0 更新](https://docs.mem0.ai/api-reference/memory/update-memory)、[删除](https://docs.mem0.ai/api-reference/memory/delete-memories)、[反馈机制](https://docs.mem0.ai/platform/features/feedback-mechanism)）。

Mem0 的搜索文档声明使用 semantic、BM25 和 entity matching 的多信号融合，并支持 `AND/OR/NOT`、范围和元数据过滤；rerank 是可选的额外延迟（见 [Search Memories](https://docs.mem0.ai/api-reference/memory/search-memories)）。Memory Decay 是搜索时的软偏置：按最近访问加强、长期未访问减弱，范围是约 `0.3×` 到 `1.5×`，不把旧候选硬删除（见 [Memory Decay](https://docs.mem0.ai/platform/features/memory-decay)）。

Mem0 的平台文档还把组织、项目、成员角色和团队隔离作为独立能力；READER 可以查看和搜索，OWNER 才能改项目设置和管理成员（见 [Organizations & Projects](https://docs.mem0.ai/api-reference/organizations-projects)）。这说明“记忆内容的召回权限”和“管理记忆系统的权限”应分开。

**对 NoteWeave 的启示：** 候选抽取适合异步任务；实体/工作区 scope 必须是查询契约的一部分；混合召回应发生在 projection 层；衰减应先作为排序偏置而非物理删除；清空操作要强制要求明确过滤范围。

### 3.2 Zep：时间知识图谱、事实失效和上下文编译

Zep 的官方概念文档把 Context Graph 定义为代理记忆单元：节点是实体，边是事实/关系；新数据到达时图会动态更新，旧事实被标记为失效但历史被保留；Context Block 会返回与当前线程相关的事实、摘要和有效/失效日期（见 [Zep Key Concepts](https://help.getzep.com/concepts)）。

Zep 的 Memory API 要求按 session 添加消息，并从用户的多个 session 建立 user-level knowledge graph；`memory.get` 会依据当前 session 的最近消息决定相关性，同时返回最近消息和 raw facts；如要更精确控制查询，可改用 Graph API（见 [Zep Memory API](https://help.getzep.com/v2/memory)）。Zep 还区分 user graph 和 group graph，允许将产品或组织知识作为共享图显式查询，而不是复制到每个用户图（见 [Zep Groups](https://help.getzep.com/v2/groups)）。

Zep 的 MCP 文档把“谁是用户”和“能访问什么”拆为两问：用户图目标绑定到认证身份，写工具默认关闭，管理员可以启用全局写入开关；独立图访问则有项目范围限制（见 [Zep Memory MCP Server](https://help.getzep.com/v3/memory-mcp-server)）。

**对 NoteWeave 的启示：** 事实更新不能简单覆盖文本，应该记录 `valid_from/valid_to` 或等价 revision；共享工作区知识应通过显式 scope 组合；上下文编译器应返回事实、来源和时间，而不是返回无解释的字符串；写入权限需独立于读取权限。

### 3.3 LangGraph：短期 checkpoint、长期 store 和三种长期记忆

LangGraph 官方文档把 checkpointer 与 store 分成两个接口：checkpointer 保存单 thread 的图状态快照，用于对话连续性、人机协作、时间旅行和故障恢复；store 保存跨 thread 的用户偏好、事实和共享知识（见 [LangGraph Persistence](https://docs.langchain.com/oss/python/langgraph/persistence)）。生产环境应使用数据库后端，内存 checkpointer 重启会丢失数据；长对话需要 trim、delete、summarize 或 retention policy。

长期 Memory 文档明确说明 semantic memory 可用 profile 或 collection 两种管理方式。Profile 更新较集中，但随着字段变多，整体重写容易出错；collection 更容易添加窄事实、召回率往往更高，但会把更新、删除、去重和上下文完整性问题转移到存储与检索层（见 [Semantic memory: profile vs collection](https://docs.langchain.com/oss/python/concepts/memory)）。

LangGraph 还把写入时机分成 hot path 和 background：hot path 让记忆立即可用、便于通知用户，但会增加延迟和主链路复杂度；background 不影响主链路、可以批量去重和调度，但新记忆不会立即对所有线程可见（见同一页的 [Writing memories](https://docs.langchain.com/oss/python/concepts/memory)）。其 store 使用 namespace/key 组织数据，可组合用户或组织 ID，并支持语义搜索和过滤（见 [Add long-term memory](https://docs.langchain.com/oss/python/langgraph/add-memory)）。

LangGraph 将 procedural memory 描述为模型权重、代码和 prompt 的组合，实际系统更常通过 reflection/meta-prompting 更新 instructions，而不是运行中改权重或代码（见 [Procedural memory](https://docs.langchain.com/oss/python/concepts/memory)）。

**对 NoteWeave 的启示：** Working 与长期 Memory 必须分开；`Snapshot`/checkpoint 负责复现，`memory_item` 负责跨运行知识；稳定事实采用小粒度 collection + revision，工作区级规则采用受审查 profile；procedural 更新必须进入 compiler 和 review，而不能让一次回答直接改系统规则。

### 3.4 Letta：可见的 memory block、归档记忆和读写控制

Letta 的 Memory Block 是持久化、始终可见的上下文区块，由 label、description、value 和字符上限组成；agent 可以通过 memory tools 读写。官方文档强调 description 会影响 agent 如何使用该块，说明“记忆类型/用途”本身也要成为元数据（见 [Letta Memory Blocks](https://docs.letta.com/v1-sdk/memory/memory-blocks)）。

Letta 支持 read-only block；共享组织策略可以让多个 agent 读取但不允许 agent 修改。多个 agent 共享同一 block 时，更新立即可见，但官方也警告并发修改采用 last-write-wins，可能覆盖其他变更；可用 read-only 或受控外部脚本降低风险（见 [Letta Memory Blocks 的并发与删除说明](https://docs.letta.com/v1-sdk/memory/memory-blocks)）。

Letta 的 Context hierarchy 根据数据重要性和规模选择抽象：少量、高重要性信息放 memory blocks；中等规模的只读资料放 files；低重要性的历史放 archival memory；大规模资料使用外部 RAG（见 [Context hierarchy](https://docs.letta.com/v1-sdk/memory/context-hierarchy)）。

**对 NoteWeave 的启示：** control-pack 应像可解释的 block，而不是无限上下文；规则和敏感共享信息需要只读/可撤销能力；并发更新不能依赖 last-write-wins，应由候选、revision 和 fencing 收口；规模与重要性应共同决定是直接注入还是按需检索。

### 3.5 Redis：适合缓存和检索 projection，不适合作为事实真源

Redis 官方文档提供 KNN、向量范围查询和文本、数值、标签等元数据过滤，并支持 FLAT、HNSW 等索引（见 [Redis vector search concepts](https://redis.io/docs/latest/develop/ai/search-and-query/vectors/)）。Redis 的 TTL/EXPIRE 是键级别的时间到期机制，`TTL` 返回剩余秒数，键可在到期后被删除（见 [TTL command](https://redis.io/docs/latest/commands/ttl/)、[EXPIRE command](https://redis.io/docs/latest/commands/expire/)）。

**对 NoteWeave 的启示：** Redis 可以作为短期 working context、召回缓存或可重建 projection；但键级 TTL 不表达“事实在某日失效但历史仍需审计”，也不保存审核、来源和多版本语义。因此 canonical memory 仍应以可审计的关系/对象模型为真源，Redis 只放派生数据。

## 4. 从行业事实推导 NoteWeave 的分阶段演化

以下是“如果按风险和知识成熟度逐步建设，最合理的路线”，不是 Git 历史，也不是已经完成的功能清单。

### 阶段 A：工作记忆与可复现运行

**目标：** 先让一次运行能暂停、重试和回放，不急于跨会话学习。

**设计：** Working memory 只存在于 Run/Conversation 的 `Snapshot` 和 checkpoint；记录输入、状态、事件和输出。长对话采用裁剪/摘要，但原始事件保留在审计链路。任何短期摘要都不能直接冒充稳定事实。

**为什么先做这一层：** 没有可复现的运行边界，后续无法判断一条 Memory 是用户输入、模型推断还是工具结果；也无法区分“记忆错误”与“当时输入就错”。这与 LangGraph 将 checkpointer 作为 thread-scoped memory 的分离一致。

**不采用：** 不把整段聊天直接写入长期 vector store。这样会混淆 Working/Episodic，召回时也无法知道哪句话是事实、哪句话是模型假设。

### 阶段 B：Episodic 事件沉淀

**目标：** 为后续抽取保留可追溯原料。

**设计：** 将用户明确表达、工具调用、资料变更、回答反馈和任务结果记录成 observation，保存 workspace、actor、时间、source/version、run_id、敏感级别和去重键。默认只产生候选，不进入 control-pack。`memory_outcome` 记录某次使用后的成功、失败、纠正或撤销信号。

**知识点：** Episodic memory 是“发生过什么”，不是“永远正确的事实”；event sourcing 的价值是追加、可重放和因果顺序，而不是把事件直接当作 prompt。

**不采用：** 不用单个 profile 覆盖所有事件。LangGraph 文档已指出整体 profile 随字段变大更易重写出错；NoteWeave 还需要审计和证据，所以保留事件原料更稳妥。

### 阶段 C：Semantic candidate 抽取与去重

**目标：** 从事件中提取窄粒度、可检索的事实候选。

**设计：** 后台 worker 从 observation 生成 `memory_candidate`，每个候选附带原始 evidence、抽取模型/策略、置信度、类别、敏感标记、有效时间、冲突键和幂等键。先做规则/哈希去重，再做关键词、实体和向量近邻合并；低置信度候选停留在候选池。

**写入策略：** “记住这个”这类用户明确请求可以走 hot path，但只创建候选并给出待审核提示；默认对话走 background，避免拖慢回答。这个折中同时吸收了 LangGraph 的 hot/background trade-off 和 Mem0 的异步事件接口。

**不采用：** 不把 LLM 输出直接 `upsert` 成 canonical item；模型可能把推断写成事实，且重复请求会导致重复记忆。候选和 canonical runtime 之间必须有 seam。

### 阶段 D：审核、冲突和不可变版本

**目标：** 让高影响记忆在进入运行时前可解释、可撤销。

**设计：** review decision 至少支持 approve、reject、revise、expire、revoke。相同冲突键的新事实不删除旧版本，而是将旧版本标记为 `valid_to` 或 revoked，生成新 revision；同一时间窗内无法裁决的事实并存并标记冲突。每次 compiler 只选择当前有效且权限允许的 revision。

**为什么不是 last-write-wins：** Letta 的官方文档明确指出共享 block 并发修改可能发生 last-write-wins 覆盖；这对临时配置尚可，对用户偏好、合规信息和研究结论不可接受。NoteWeave 应以 revision + fencing/optimistic concurrency 保证旧 worker 不能写回。

**冲突优先级建议：** 用户显式纠正 > 同一用户近期明确陈述 > 可信资料版本 > 模型推断；来源相同再比较时间和置信度。优先级只是候选排序，不能代替人工审核。

### 阶段 E：按运行类型编译 control-pack

**目标：** 让 Memory 对 QA、Note、Wiki、Research、Artifact 产生不同的受控上下文。

**设计：** compiler 读取当前 revision，按 `workspace + actor + run_type + consent + sensitivity + budget` 过滤，再按混合召回和时间/衰减排序，输出带 memory_id、version、evidence、有效时间和理由的 control-pack。`shadow-recall` 只生成比较结果，不改变真实 pack。

**召回策略：** 第一层是硬过滤（workspace、scope、权限、撤销、过期）；第二层是 lexical/BM25、向量、实体和时间多路召回；第三层做 rerank、去重和 token budget；最后才编译成模型上下文。Mem0 的 hybrid search、Zep 的 Context Block 和 Letta 的 context hierarchy 都支持这种“先选对象，再编译上下文”的方向。

**不采用：** 不把“全量长期记忆”拼进 prompt，也不让下游模块自己决定哪些 memory 可用。这样会破坏 Locality，导致权限和预算规则散落在 QA、Research、Artifact 等调用方。

### 阶段 F：遗忘、过期和删除治理

**目标：** 让旧记忆减少干扰，同时保留需要的审计能力。

**设计：** 区分三种语义：

1. `expiration`：事实在业务上只有效到某日期，过期后不进入默认召回，但历史版本保留。
2. `decay`：按访问频率/最近使用做软排序偏置，不等于事实失效。
3. `revoke/delete`：用户或管理员要求撤回；runtime 立即产生 tombstone，projection 异步清理，审计保留最小化标识。

Mem0 的 expiration 和 decay 文档分别体现了前两种语义；Redis TTL 只能直接表达第三类存储行为，不能代替业务语义。因此 NoteWeave 不把 Redis EXPIRE 当成 canonical memory 的生命周期。

**安全操作：** 单条删除必须有 memory/version；批量删除必须强制要求 workspace、actor 或其他过滤条件，防止空过滤造成全库擦除。删除后应验证搜索、control-pack、缓存和异步队列均不再使用被撤回版本。

### 阶段 G：Procedural memory 与反馈闭环

**目标：** 从“记住事实”进化到“总结可复用的做法”，但不让错误反馈直接改变系统规则。

**设计：** 将重复成功的 episodic patterns 生成 procedural candidate，例如“该工作区的 Research 结果必须给出来源表”。candidate 需要人工/规则审核，按 run_type 编译成低权限 instruction；每次使用记录 outcome，负反馈触发降权、revoke 或新 revision，而不是直接改原规则。

**评估闭环：** 建立四类样例：写入是否该写、冲突是否正确处理、召回是否相关且不越权、control-pack 是否真正改善任务。离线集合比较不同 compiler/retriever 版本；线上只采样必要 trace 和用户反馈。LangSmith 官方把 correctness、relevance、groundedness、retrieval relevance 分开评估，Mem0 也提供对记忆的正负反馈 API（见 [LangSmith RAG evaluation](https://docs.langchain.com/langsmith/evaluate-rag-tutorial)、[LangSmith evaluation concepts](https://docs.langchain.com/langsmith/evaluation-concepts)、[Mem0 Feedback](https://docs.mem0.ai/platform/features/feedback-mechanism)）。

**不作无证据承诺：** 没有实际样例、人工标注和基线比较时，只能说明评估机制，不能声称召回率、token 节省或准确率已经提升。

## 5. 推荐的 NoteWeave 领域模型

### 5.1 写入面

`observation -> candidate -> review decision -> memory version -> runtime revision` 是主写入链路。每一跳都保留 source/evidence 和 actor；后台 worker 可重试，但必须使用幂等键、lease 和 fencing。直接写入 canonical runtime 只允许受信任的导入或管理员操作，并同样生成审计事件。

### 5.2 读取面

`request context -> hard scope filter -> candidate retrieval -> rerank/decay -> evidence check -> control-pack` 是主读取链路。读取 API 应返回“为什么选中”所需的最小元数据：memory id、version、source、validity、scope 和 confidence。默认返回摘要/标识，避免把完整敏感输入写入日志。

### 5.3 同意与 ACL

建议将以下状态独立建模，而不是塞进一个布尔字段：

- `consent`: 未请求、已同意、已拒绝、可撤回。
- `visibility`: private、workspace、shared-group、run-only。
- `read_policy`: 哪类 run 可以读取。
- `write_policy`: 用户、worker、管理员或规则是否可写。
- `sensitivity`: 普通、敏感、认证材料等。

Mem0 用 project membership 隔离团队，Zep 用认证身份绑定 user graph，Letta 用 read-only block 限制修改；NoteWeave 应组合这些思想，但以自身 Workspace 和 run_type 为权威，而不把外部 provider 的 user_id 当作授权依据。

### 5.4 评估与观测字段

每次 recall 记录 query/run_type/scope、候选集合摘要、最终选中的 memory version、过滤原因和使用结果；不保存不必要的原始 prompt。离线测试至少覆盖：

- extraction precision：不该写入的陈述是否被拦截；
- dedup/conflict：同义、纠正、时间变化是否得到预期 revision；
- retrieval precision/recall：相关记忆是否出现且未越权；
- temporal validity：过期和新旧事实是否排序正确；
- control-pack usefulness：任务结果是否有证据支持，是否受到失效记忆影响；
- deletion/ACL：撤回后任何 projection、缓存和异步重试都不能继续提供内容。

## 6. 为什么 NoteWeave 不直接选择某一个相近方案

| 相近方案 | 它擅长什么 | 不直接作为 NoteWeave 核心的原因 | 保留的借鉴点 |
| --- | --- | --- | --- |
| Mem0 | 开箱即用的抽取、混合搜索、过期和反馈 | 托管平台的实体/项目模型不能替代 Workspace、review 和 control-pack；ADD-only 默认也不覆盖本项目的冲突治理 | 异步事件、实体过滤、BM25+向量+实体、软衰减 |
| Zep | 时间图谱、事实失效、上下文摘要 | 图谱构建和维护成本高；NoteWeave 的首要问题是可审计事实与运行授权，不是全量关系推理 | valid time、Context Block、user/group graph 分离 |
| LangGraph store/checkpointer | 线程 checkpoint、跨线程 store、hot/background 模式 | 是编排框架的持久化抽象，不包含 NoteWeave 的审核、证据、撤销和 workspace 治理 | working/long-term 分离、namespace、存储与检索分离 |
| Letta memory blocks | 可见、可编辑、共享/只读上下文 | always-visible block 会消耗上下文；last-write-wins 不适合高风险事实；agent 自主写规则需受控 | block 描述、read-only、按重要性选择抽象 |
| Redis | 低延迟缓存、向量/标签过滤、TTL | TTL 和索引不表达版本、证据、审核和失效历史；缓存重建与真源语义不同 | working cache、projection、短期 TTL、向量过滤 |
| 单一 JSON profile | 结构简单，读取方便 | 大 profile 重写易丢字段，难表达历史和冲突 | 对小范围稳定偏好可作为受审查 profile projection |

最终选择是“领域真源 + 可重建 projection + 受控 compiler + 可替换 provider”，而不是把 Memory 供应商当作领域模型。这样可在低规模阶段使用关系表或 JSON，在检索需求增长后增加向量、BM25、图谱或 Redis，而不改变审核和 Evidence 契约。

## 7. 术语补充

- **Working memory**：当前 Run 可直接使用的短期状态，不保证跨运行保留。
- **Episodic memory**：带时间和因果顺序的经历记录，通常是 Semantic/Procedural candidate 的原料。
- **Semantic memory**：被抽取为事实、实体或关系的知识，不等于 semantic search。
- **Procedural memory**：影响做事方式的规则、提示或流程；在 NoteWeave 中必须经过更高等级审核。
- **Candidate**：尚未获得运行时信任的记忆候选。
- **Revision**：不可变的运行时版本；新事实通过新 revision 表达，不覆盖旧证据。
- **Validity**：事实在业务世界中的有效时间；与 created_at、updated_at 不同。
- **Decay**：按使用或时间改变排序的软偏置，不等于删除或事实撤销。
- **Control-pack**：面向具体 run_type 编译的受控输入，包含 scope、预算、证据和版本。
- **Shadow recall**：只做比较和观测的召回，不进入真实 control-pack。

## 8. 结论

合理的演化不是“先上向量库，再把所有聊天存进去”，而是：

```text
Working checkpoint
  -> Episodic observation
  -> Semantic candidate
  -> Review / consent / conflict revision
  -> Scope-aware hybrid recall
  -> Evidence-backed control-pack
  -> Procedural candidate and feedback loop
  -> Decay / expiration / revoke / deletion governance
```

这条路线让复杂度按收益出现：先保证复现和隔离，再增加跨会话事实；先把候选和真源分开，再增加混合召回；先让事实可撤销，再允许规则影响行为；最后才考虑时间图谱、自动反思和更激进的衰减。它既吸收 Mem0、Zep、LangGraph、Letta、Redis 的成熟经验，也保留 NoteWeave 自己的 Workspace、Evidence、Version、Control Pack 和审核边界。
