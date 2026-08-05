# 场景化 RAG：面试题与参考回答

> 阅读口径：第 3 至 13 节是打断式追问速查，不应单独作为完整答案。第 14 节提供项目总回答，第 16 节按八个评分面提供 3 到 5 分钟母题长回答，覆盖算法、工程与演进追问。

## 0. 脑图主干

```text
统一入口
├─ QA：快速事实问答
│  └─ BM25 + Vector + RRF + Rerank + Evidence Selection
├─ Note：围绕少量资料深读
│  └─ Source Recall + Original-text Window + Rerank
├─ Wiki：长期知识组织
│  └─ Page Relation + Source Backlink + Version
└─ 共享：Workspace Scope + Evidence + Citation + Trace
```

## 0.1 从 0 讲起的演进版主回答

最开始我们用 BM25 取 TopK Chunk，它对术语、数字和代码符号很稳定，成本低，也最容易解释。用户开始用同义表达和自然语言提问后，单路关键词召回会漏掉语义相关内容，所以增加向量召回。向量也不能完全替代 BM25，因为精确实体和短字符串仍是词法检索的优势。

两路召回以后，我们比较过归一化分数相加、Learning to Rank 和 RRF。分数相加需要持续校准不同查询和模型的分布，LTR 需要更大标注集，RRF 只看名次，更符合当前数据规模。系统先用 RRF 合并候选，再让 Cross-Encoder 只精排有限集合，Provider 失败时退回融合顺序。排序后还有 Evidence Selection，因为单条相关不等于整个证据集合好，还要做同源去重、来源覆盖和 Token 预算。

随后发现 QA、Note 和 Wiki 不能只换 Prompt。QA 的候选是直接回答问题的 Chunk；Note 要先选资料，再围绕锚点展开连续原文；Wiki 关心页面版本、关系和来源回链。最终三条链路共享 Scope、Evidence、Citation、Provider 和 Trace，只拆分候选、排序和预算策略。

代价是策略与评测成本增加，收益是每个场景可以定义自己的“相关”和“证据充分”。当前 Wiki 只是受限关系读取，不包装成完整 GraphRAG；内部 6 条样本只用于确定性回归，不能当成线上准确率或公开 Benchmark。

## 1. 30 秒回答

我没有把所有知识任务都塞进一套通用向量检索，而是按任务目标拆成 QA、Note、Wiki 三条链路。QA 关注低延迟和证据覆盖，采用 BM25、Vector、RRF 与 Rerank；Note 先定位相关资料，再展开原文窗口做深读；Wiki 更关注页面关系、版本和来源回链。三条链路共享 Workspace 权限、Evidence 和 Citation 契约，但候选单位、排序目标和上下文组织不同。仓库有 6 条内部样本和固定消融 fixture，用于校验报告口径与回归边界，不把其中的 100% 外推为在线链路准确率。

## 2. 3 分钟回答

三条链路解决的是“任务目标不同，相关性的定义也不同”。QA 通常需要从多份资料里快速找到能直接回答问题的片段，候选单位是 Chunk，重点是混合召回、排序和证据覆盖。Note 是带阅读上下文的资料深读，不能只命中一个孤立片段，所以先在 Source 层找到最相关资料，再围绕锚点展开原文窗口。Wiki 面向长期知识组织，除了文本相关性，还要利用页面链接、版本和来源关系，保证知识页能够回到原始资料。

QA 先做 Workspace 和来源范围过滤，然后同时执行 BM25 与向量召回。两路分数不可直接相加，因此用 RRF 按名次融合，再用 Rerank 做语义精排，最后由 Evidence Selection 控制来源覆盖和上下文预算。Note 则采用粗到细的分层检索，资料级候选避免在全库 Chunk 中直接竞争，原文窗口保留章节和相邻语境。Wiki 以页面为中心，读取关联页面和 Citation Backlink，再按问题选择必要内容。

三条链路最终都输出统一 Evidence Bundle 和 Citation，回答过程会固化 Retrieval Plan、候选、排序和降级信息。这样聊天层可以共用回答框架，检索层又不会被压成一种策略。

## 3. 为什么要拆三条链路

### 面试官问：为什么不做一套 Hybrid RAG 加不同 Prompt？

Prompt 只能改变生成方式，不能改变候选单位和相关性目标。QA 的最优候选可能是一段精确 Chunk；Note 需要一份资料和它的原文邻域；Wiki 还需要页面关系与版本。强行共用同一 TopK，会让 QA 上下文过重、Note 丢失阅读语境、Wiki 退化成普通文本搜索。

### 追问：三套链路会不会重复建设？

共享横切能力，包括权限过滤、索引访问、Embedding/Rerank Provider、Evidence、Citation、Trace 和降级协议。差异只保留在 Retrieval Plan、Candidate Type、Rank Feature 和 Context Assembly，避免复制基础设施。

## 4. QA 链路

### 面试官问：为什么同时用 BM25 和向量检索？

BM25 对专有名词、错误码、类名和精确短语更稳定；向量召回擅长语义相近但词面不同的表达。两者的错误类型不同，混合召回可以提高覆盖。不能说向量检索天然比关键词先进，实际效果要用 Gold Set 验证。

### 面试官问：RRF 是什么，为什么不用分数加权？

BM25 分数和向量相似度不在同一尺度，直接相加需要持续校准。RRF 只依赖每路排名，典型公式是：

```text
score(d) = Σ 1 / (k + rank_i(d))
```

它对分数分布不敏感，适合作为稳定融合层。缺点是忽略原始分数差距，因此后面仍需要 Rerank 和业务特征。

### 面试官问：Rerank 解决什么？

召回阶段追求不漏，候选数量相对多；Rerank 用 Query 和候选文本做更细的相关性判断，把真正能回答问题的证据排到前面。它增加延迟和成本，所以只处理融合后的有限候选，并设置超时和降级。Provider 失败时保留 RRF 排名，不能让整个问答失败。

### 追问：TopK 越大越好吗？

不是。TopK 太小会漏证据，太大会增加模型上下文噪声和引用错误。最终不是机械截取前 K 条，而是结合来源覆盖、去重、证据相关性和 Token 预算选择 Evidence Bundle。

## 5. Note 链路

### 面试官问：为什么先找资料，再找原文？

深读问题通常围绕少量资料展开。如果直接让全库 Chunk 竞争，多个相邻片段可能互相挤压，也容易命中标题相近但不是用户正在读的资料。Source Recall 先缩小资料范围，Reading Retriever 再围绕锚点窗口读取原文，相关性和连续语境更稳定。

### 追问：窗口多大怎么定？

窗口不是固定越大越好。根据锚点位置、章节边界、候选分数和问题类型扩展，并受上下文预算限制。定义类问题可能只需相邻窗口，论证和对比问题需要更宽范围。需要记录锚点与扩展窗口的角色，避免把邻近文本误当直接证据。

## 6. Wiki 链路

### 面试官问：Wiki 为什么不是普通 RAG？

Wiki 的核心对象是有版本和关系的知识页。检索不仅看文本相似度，还要看页面链接、索引位置、来源回链和当前版本。回答引用 Wiki 时，需要继续追溯到页面对应的资料来源，不能把模型生成的 Wiki 文本当成天然事实。

### 追问：这是不是 GraphRAG？

当前更准确的说法是结构关系增强的 Wiki 检索，使用页面关系和来源回链，但不声称实现了完整 GraphRAG 的实体抽取、社区发现和图摘要流水线。

## 7. Query Rewrite 与多轮对话

### 面试官问：用户问“第二个呢”怎么检索？

先由上下文编译器恢复当前主题、指代对象和来源范围，再生成独立可检索 Query。改写必须保留用户约束，不能把模型猜测写进查询。置信度不足时澄清，不能用全库检索掩盖歧义。

### 追问：Query Rewrite 会不会把问题改错？

会，所以保留原 Query 和 Rewrite Query，Trace 中记录改写原因；专有名词和显式过滤条件必须保留。可用离线集比较改写前后的 Recall@K，并对低置信度改写走原 Query 双路召回或回退。

## 8. 评估与数据

### 面试官问：你怎么评估 RAG？

分层评估：召回看 Recall@K；排序看 MRR 和 NDCG；引用看 Citation Precision/Coverage；拒答看 Refusal Accuracy；安全边界看 Scope Violation；工程侧看 P95 延迟、Provider 失败率和降级率。最终回答质量可以人工评审或 LLM Judge，但不能代替检索层指标。

### 追问：你简历上的 100% 怎么解释？

这是 6 条内部样本上的**手工固化回归 fixture**，不是当前在线融合链路自动重放得到的准确率，也不是公开 Benchmark。其中 QA 3 条、Note 3 条，fixture 记录了单路 33.3% 至 66.7%、融合变体 100%、Scope Violation 为 0；现有可执行 Replay 使用的是确定性 BM25 Baseline，不能把 fixture 中的 100% 归因成 Vector/RRF/Rerank 融合链路的实测结果。这个数字只能证明评测报告契约和回归口径存在，不能证明真实问题达到 100%。

## 9. 降级与故障场景

### 向量服务不可用怎么办？

降级到 BM25，保留 Workspace 和来源过滤，在 Trace 中记录降级。结果可能下降，但不能跨 Scope 或伪造向量结果。

### Rerank 超时怎么办？

使用 RRF 融合顺序继续，标记 Rerank Degraded。Rerank 是精排增强，不应成为单点故障。

### Elasticsearch 索引尚未完成怎么办？

根据 Source Projection 状态决定等待、回退到可用快照或提示资料仍在处理中。不能读取未完成投影后假装结果完整。

### 检索结果很多但证据不足怎么办？

相关不等于支持。Evidence Selection 需要判断片段是否回答问题；达不到门槛时拒答或说明资料不足，不让生成模型靠常识补全。

## 10. 常见错误回答

- “我们用了向量数据库，所以召回很准”：没有指标和对照。
- “RRF 就是把两个分数相加”：概念错误。
- “TopK 设大一点避免漏召回”：忽略噪声和上下文成本。
- “Wiki 是 GraphRAG”：超过当前实现边界。
- “准确率 100%”：遗漏 6 条内部样本的口径。

## 11. 一句话收尾

场景化 RAG 的核心不是做三份相似代码，而是让候选单位、排序目标和上下文组织服从任务本身，再用统一 Evidence 与 Citation 契约把三条链路接回同一个知识工作台。

## 12. 功能背后的检索算法八股

### 12.1 BM25 为什么比 TF-IDF 更适合文本召回

TF-IDF 会让词频持续线性增长，长文档容易因为同一个词出现很多次获得过高分。BM25 对词频做饱和，并通过文档长度归一化降低长文档优势。简化公式是：

```text
score(q, d) = Σ IDF(t) × tf(t,d) × (k1 + 1)
                         / [tf(t,d) + k1 × (1 - b + b × |d|/avgdl)]
```

`k1` 控制词频饱和速度，`b` 控制长度归一化。项目中的 QA 文档包含标题、Heading 和正文，可以在 ES Multi Match 中给标题和 Heading 更高 Boost；专有名词、错误码和类名通常由 BM25 找得更稳。

### 12.2 向量召回在项目里解决什么

Embedding 把 Query 和 Chunk 映射到同一向量空间，常用余弦相似度：

```text
cos(q, d) = (q · d) / (||q|| × ||d||)
```

它能召回“事务消息”和“本地消息表保证最终一致”这类词面不同但语义相关的内容。缺点是专有词可能被稀释，而且模型、维度和归一化方式改变后旧索引不可直接复用。因此项目把 Embedding Model、Dimension 和 Index Version 绑定到投影版本。

### 12.3 ANN 索引为什么不是精确 KNN

向量库通常用 HNSW 等近似最近邻索引，用少量召回损失换查询速度。HNSW 可以理解为多层小世界图：高层快速跳转到相近区域，底层精细搜索。`efSearch` 越大，召回通常越高，但延迟和 CPU 也上升。项目当前简历不写具体 HNSW 参数，因为核心实现由 Elasticsearch 承载，参数应通过真实 Gold Set 和延迟压测确定。

### 12.4 RRF 的 k=60 有什么含义

项目当前 `RRF_K=60`，并对 Vector、Keyword 使用 0.7/0.3 的通道权重。加权 RRF 可以写成：

```text
score(d) = Σ weight_i / (60 + rank_i(d))
```

`k` 越大，前几名之间的差距越平滑；越小，Top1/Top2 的优势越强。RRF 解决的是异构分数不可比，不代表 0.7/0.3 永远最优。参数应该由消融和真实 Query 分布校准。

### 12.5 Cross-Encoder Rerank 为什么更准也更慢

Bi-Encoder 在索引阶段分别编码文档，Query 到来后只做向量相似度，适合大规模召回；Cross-Encoder 把 Query 和候选拼在一起输入模型，让注意力直接建模词间关系，排序更准，但每个候选都要前向计算。项目先召回有限候选，再批量 Rerank，正是典型的“高召回、低精排”架构。

### 12.6 Chunk 切分的知识点

Chunk 太小会丢失论证上下文，太大会把多个主题混在一起，Embedding 向量被平均，还增加 Prompt Token。项目默认 Chunk Size 900、Overlap 120，这是工程初始值，不是普适最优。更合理的优化方式是按标题和段落做结构切分，再用 Gold Set 比较 Recall@K、引用定位和延迟。

### 12.7 为什么 Metadata Filter 要在召回前做

如果先全局向量召回 TopK，再在应用层过滤 Workspace，合法候选可能根本没进入 TopK，还存在越权文本进入中间 Trace 的风险。因此 Workspace、Source、Snapshot Status 等过滤必须下推 ES Query。这个问题同时考察 RAG 和多租户数据隔离。

### 12.8 Note 的粗排和精读对应 Cascade Retrieval

Cascade Retrieval 用便宜模型先缩小候选，再用昂贵模型精排。Note 先从资料级候选选出最多 4 份资料，再在资料内部定位阅读锚点和原文窗口。这样既降低全库 Window Rerank 成本，也保留“这段话属于哪份资料、前后在讨论什么”的语境。

### 12.9 Evidence Selection 与排序有什么区别

排序优化单个候选相关性，Selection 优化候选集合。即使 Top3 都相关，如果来自同一来源的相邻 Chunk，覆盖面可能很差。项目 Selection 会做同源去重、来源覆盖和预算控制。这里可以类比搜索结果 Diversification，但不要声称实现了 MMR，除非代码确实使用相关性与候选间相似度的 MMR 公式。

### 12.10 拒答为什么属于 RAG 能力

检索系统不能只优化“有答案时找出来”，还要识别“资料里没有”。拒答门槛可以结合 Top Score、有效 Evidence 数、直接支持标记和来源覆盖。阈值过低会幻觉，过高会拒绝可回答问题，需要用包含 Negative Case 的 Gold Set 评估 Refusal Accuracy。

## 13. 三条链路的源码级追问

### 面试官问：为什么一次 Run 要冻结 Strategy Tuple？

因为 RAG 结果不仅由代码决定，还由 Policy、Prompt、索引和 Provider 版本决定。项目把这些组成稳定 Strategy Tuple 写入 Plan 和 Trace。未知版本、Label 与 Tuple 漂移直接拒绝，不静默回退。这样线上回答、离线评测和 Shadow Replay 才能复现同一条链路。

这可以类比模型服务的配置快照和数据库 Schema Version。没有版本门禁，所谓 A/B 对比可能比较的是两组同时变化的系统。

### 面试官问：跨语言问题为什么不能只依赖向量召回？

产品名、版本号、错误码和缩写属于高区分度 Lexical Anchor。项目保留 Product + Version、Distinctive Identifier、汉字二元组和特定词覆盖，中文语义和英文标识分别进入检索。多轮追问只做一跳 Topic Continuation，避免旧主题递归污染当前问题。

如果没有 Anchor 或词法覆盖过低，系统宁可拒答，也不返回“范围内最新但无关”的 Chunk。这里体现的是语义召回与精确词法约束互补，而不是简单 BM25 加向量。

### 面试官问：TopK 排完以后为什么还要 Evidence Selection？

排序优化单个候选，Selection 优化整个证据集合。项目先保证 Source Diversity，再补高分候选，同时做去重和字符限制。Rerank Provider 漏回部分候选时，不会把它们直接丢弃，而是放在已返回候选之后，并记录 Degradation。

引用编号按最终 Prompt Evidence Order 生成。模型引用 Bundle 外证据或重复 Prompt Ref 时拒绝。这个设计把 TopK、Context Packing 和 Citation Identity 串成一个确定性过程。

### 面试官问：Scope Filter 已经下推 ES，为什么还要二次 Ownership 校验？

前置过滤防止越权候选进入 TopK，后置校验防止索引脏数据、旧 Snapshot 或错误投影突破边界。项目批量校验 Passage、Source、Snapshot 和 Knowledge Version 的归属，100 个 Hit 仍保持固定次数查询，避免 N+1。归属不匹配 Fail Closed。

安全边界不能完全委托给可重建投影。Elasticsearch 是检索加速层，MySQL 中的所有权关系才是权威判断。

### 面试官问：Note 链路内部最值得讲的算法是什么？

是资料级候选和关系扩展结合的两阶段阅读。Source Rank 综合 Journal、Metadata、Relation 和 Verify 特征，语义 Hit 还能作为关系图 Seed；加权关系从 Anchor 向外扩展，再拆成 Primary、Adjacent、Secondary Window。最终只把仍在 Evidence Bundle 中的 Window 渲染给模型。

它可以联系 Cascade Retrieval、Graph Expansion 和 Reading Plan，但不要直接说实现了完整 GraphRAG。图在这里负责扩大阅读上下文，不负责替代所有向量和词法召回。

### 面试官问：Wiki 为什么无页面时不回退普通 RAG？

Wiki 模式承诺的是结构化知识页及其版本、引用和关系。无页面时若偷偷回退普通资料，接口语义会从“知识库中存在什么”变成“资料里可能提过什么”。项目因此保持明确 No Page，并对不可变版本、Latest Pointer、断链和受限 BFS 单独治理。

Ego Graph 分别限制节点、边和渲染字符，缓存命中后仍读取 Live State 与 Links。这样既避免图爆炸，也避免旧缓存复活已删除页面。

### 面试官问：如何证明一次 RAG 优化是真的？

Gold Annotation 先生成 Draft，再脱敏和人工 Reviewed，并检查候选与原文漂移。Shadow Replay 导出线上 Trace、Bundle 和内容无关 Receipt，在固定 Strategy Tuple 下重放相同候选数、字符约束和降级状态。Quality Gate 同时比较绝对阈值与相对 Delta。

索引发布还要通过 Provider Ready、Dual Coverage、Completed Build 和 No Degraded Runs。Catalog 变化、Backfill 未完成或 Count 未验证时不切 Alias，避免把评测提升建立在不完整索引上。

## 14. 4 到 5 分钟标准主回答

这个模块解决的是同一个 Workspace 里三种不同的信息需求：QA 要快速找到直接证据，Note 要围绕一份资料深读原文，Wiki 要按知识页和关系组织稳定内容。如果只做一套 Hybrid Search，然后换三个 Prompt，看起来复用最多，但召回单位、排序目标和失败语义都不一样。

QA 的召回单位是 Chunk。查询先冻结 Workspace、Source Scope 和 Strategy Tuple，再同时执行 BM25 与向量召回。BM25 擅长产品名、版本号、错误码和专有词，向量召回擅长同义改写和自然语言表达。两路分数分布不同，不能直接相加，所以使用加权 RRF 按排名融合。融合后的候选再经过 Cross-Encoder Rerank，它把 Query 和文段一起编码，语义判断比双塔向量更细，但计算更贵，因此只处理较小候选集。

排序以后还有 Evidence Selection。TopK 只代表单条候选相关，不能保证整个上下文好用。如果前几名都是同一资料的相邻 Chunk，会浪费上下文，也缺少来源覆盖。Selection 先保证每个 Source 的代表证据，再按分数补齐，执行去重和字符限制。最后的 Citation ID 按进入 Prompt 的证据顺序生成，模型引用 Bundle 外内容时直接拒绝。检索结果不足时明确拒答，不用最近资料凑答案。

Note 链路的目标不是从全库找几段答案，而是确定应该读哪份资料、从哪里开始读以及前后语境是什么。它先做 Source-level Rank，综合语义、元数据、Journal 和关系特征，再在候选资料内找 Anchor。Anchor 命中后按章节边界扩展 Primary、Adjacent 和 Secondary Window。关系图可以从语义 Hit 向相邻资料扩展，但最终只把通过 Evidence Selection 的 Window 送入模型。这是一种 Cascade Retrieval：便宜的资料级粗排缩小范围，昂贵的窗口精读只发生在少量资料中。

Wiki 的召回单位是不可变 Knowledge Version。它先定位 Page Identity 和 Latest Pointer，再按 Citation Order 读取一跳关系。Ego Graph 使用受限 BFS，分别限制节点数、边数和渲染字符，防止高连接页面把上下文撑爆。Wiki 没有目标页面时不会偷偷回退到普通 RAG，因为“知识库没有这页”和“普通资料可能提过”是不同结果。

三条链路共用 RetrievalPlan、Candidate、Evidence Bundle、Trace、Scope 校验和 Provider 治理。Plan 里冻结 Mode、Policy、Prompt、索引和降级策略，避免运行过程中配置漂移。Scope Filter 在 Elasticsearch 召回前下推，召回后再批量校验 Passage、Snapshot 和 Knowledge Version 的 Ownership。前置过滤保证 TopK 不被越权数据占用，后置校验防止脏索引突破数据库权威边界。

索引使用 MySQL 保存真源和投影状态，MinIO 保存原文件与快照，Elasticsearch 保存可重建检索文档。新索引先 Backfill 和 Dual Recall，数量、Catalog Version 和 Provider Health 都通过后，再原子切换 Alias。评测使用人工 Reviewed 的 Gold Set，并把线上 Trace 与 Evidence Bundle 导出做 Shadow Replay，比较 Recall、MRR、nDCG、Citation Support、拒答准确率、延迟和降级状态。

Trade-off 是三条链路增加了策略和测试成本，但换来了清楚的召回目标和失败语义。数据规模很小、只有一种问答方式时，一条 Hybrid Search 足够；当前项目既有快速问答、资料精读和知识页读取，拆链路比让一个 Prompt 同时承担三种语义更容易优化和解释。

## 15. 面试官继续深挖时怎么回答

### 追问：为什么选 RRF，不选归一化分数相加？

BM25 分数受词频、文档长度和查询长度影响，向量相似度通常落在较窄区间。Min-Max 或 Z-score 归一化依赖当前候选分布，候选变化时同一个原始分数会得到不同结果。RRF 只看排名，对分数量纲不敏感，也便于加入第三路召回。代价是丢失绝对分数信息，因此项目在融合后再用 Rerank 判断精细相关性。

### 追问：为什么不直接让 Rerank 处理全库？

Cross-Encoder 每个 Query-Document Pair 都要完整前向计算，不能像双塔向量一样预计算文档向量。全库 Rerank 的成本随文档数线性增长。项目先用 BM25 和 ANN 做高 Recall 候选生成，再对几十条候选精排，把昂贵模型放在第二阶段。

### 追问：三条链路会不会维护成本太高？

真正共享的是检索基础设施，不共享的是业务策略。Plan、Trace、Evidence、归属校验、索引版本和评测框架共用；QA 的 Chunk Fusion、Note 的 Reading Window、Wiki 的 Page Graph 独立。这样避免复制中间件代码，也不会把三个排序目标硬塞进一个大量条件分支的函数。

### 追问：什么时候 MySQL Fallback 合理？

Elasticsearch 暂时不可用、数据量较小，而且业务明确接受较弱检索时，可以启用受控 Fallback。它仍要有关键词相关性和 Source Diversity，不能返回最近几条资料。高质量 QA 默认 Fail Closed，是因为隐藏回退会把基础设施故障伪装成低质量答案。

## 16. 面试官八维度母题长回答

### 16.1 业务抽象：为什么 QA、Note、Wiki 不能只换 Prompt

RAG 的业务抽象不是把文本搜出来，而是针对当前任务构造一组能够支持生成的 Evidence。QA、Note 和 Wiki 对相关性的定义不同。QA 从多份资料里找能直接回答问题的片段，候选单位是 Chunk；Note 围绕少量资料深读，先判断读哪份 Source，再围绕 Anchor 展开连续原文；Wiki 面向稳定知识组织，候选带页面身份、版本、关系和 Source Backlink。

如果只建一条 Hybrid Search 再换 Prompt，复用度最高，但候选对象和失败语义没有改变。QA 的 TopK 可能被一份资料的相邻 Chunk 占满；Note 命中单个片段却失去章节语境；Wiki 把生成页面当普通文本后，无法解释页面版本和原资料关系。Prompt 能调整生成语气，不能把 Chunk 变成 Reading Window，也不能补出 Page Version。

当前选择共享 RetrievalPlan、Workspace Scope、Provider、Evidence Bundle、Citation 和 Trace，只拆分 Recall、Rank、Selection 与 Budget。这样公共基础设施不会复制，场景又能独立演进。代价是三套策略需要不同 Gold Case、参数和降级语义，维护成本高于一条通用链路。

业务指标也要按场景拆。QA 看直接证据 Recall、Citation Support、拒答正确性与延迟；Note 看 Source 命中、Anchor 与 Window 连续性、原文覆盖；Wiki 看正确页面版本、关系扩展是否有帮助、Source Backlink 和无页面时的失败语义。统一的答案满意度无法定位问题出在召回、排序、证据组织还是生成。

选择边界是：数据少、任务单一时一条 BM25 或 Hybrid 足够；只有候选单位和相关性目标已经稳定分化，才拆策略。当前 Wiki 是受限关系读取，不宣称完整 GraphRAG 的实体抽取、社区发现和图摘要。

### 16.2 数据与一致性：资料更新后如何保证检索版本可解释

MySQL 保存 Source、Snapshot、Chunk 元数据和 Projection 状态，MinIO 保存原文件与不可变快照，Elasticsearch 保存可重建查询投影。Source 是稳定身份，Snapshot 是某次内容版本；检索文档必须带 Workspace、Source、Snapshot、Chunk、位置、Index Version 和 Embedding Version。回答只能选择 Projection Ready 的有效 Snapshot。

上传、解析和 ES 写入无法放进一个事务。数据库先记录 Snapshot 与投影任务，Worker 解析固定 Snapshot，ES 写入成功后再由 Finalizer 更新 Ready。用户在窗口期看到 Pending 或 Indexing，不能把暂时搜不到解释为资料中没有。旧消息晚到时，Projection Version 和当前 Snapshot 状态阻止它把已撤回版本重新设为可见。

索引升级不能原地覆盖。Embedding 模型、维度、Chunk 策略或 Mapping 改变时创建新 Index Version，Backfill 后做数量、Catalog Version、Provider Health 和质量门禁，再切 Alias。旧索引保留一个回滚窗口。Dual Recall 可以比较新旧候选，但切换条件必须固定，不能用同一批在线流量临时改标准。

一次 Answer Run 还要冻结 Strategy Tuple，包括模式、Plan Version、索引、Embedding、RRF、Rerank、TopK、Evidence Budget 和降级状态。否则代码没变但配置变化，同一问题重放得到不同证据，无法判断优化是否有效。Evidence Bundle 保存入选身份与位置，不依赖后来更新的页面内容。

一致性代价是更多版本、状态和存储。替代方案是始终读取最新索引，简单但历史回答不可解释；同步双写让上传后立即可搜，却把 ES 故障耦合到上传事务。当前选择业务真源加版本化投影，接受可观测的一致性窗口。

### 16.3 并发与容量：Hybrid、Rerank 和 Evidence Budget 如何控制延迟

QA 延迟可以拆成 Query Rewrite、BM25、Vector、RRF、Hydration、Rerank、Evidence Selection 和 LLM。BM25 与 Vector 可以并行，RRF 是本地轻计算，Rerank 通常是新增主要延迟，最终生成又受 Evidence 字符数影响。只看总 P95 无法决定应减少候选、调整批量还是换 Provider。

TopK 不是越大越好。召回 K 太小会漏证据，太大会增加 ES 返回、Hydration、Rerank 和 Prompt 成本，还让相邻重复 Chunk 增多。可以先画漏斗：两路各召回多少、去重后多少、Rerank 多少、最终 Evidence 多少。每层记录候选数、字符、耗时和丢弃原因，再用 Recall@K 曲线找边际收益变小的位置。

Rerank 不处理全库，因为 Cross-Encoder 对 Query 与每个候选联合编码，计算量近似随候选数线性增长。Hybrid Recall 负责高 Recall 缩小集合，Rerank 负责有限集合高 Precision。Provider 支持批处理、超时和有限重试，失败时回退 RRF，而不是整条回答失败。向量服务不可用时 QA 可显式退到 BM25，但要记录 Degraded Reason。

并发上限还受 Elasticsearch Search Thread、Embedding/Rerank Rate Limit、Java I/O 线程池和 Hikari 连接池约束。两路召回并行不代表无限并发；Workspace 热点需要入口配额，同一 Query 的重复请求可以在权限和版本完整进入 Key 后缓存。Evidence Budget 防止高 TopK 把后续 LLM 上下文和首字延迟放大。

容量结论需要真实查询分布。压测应按短精确词、自然语言、跨语言、长资料和无证据问题分桶，报告各阶段 P50/P95/P99、Recall、降级率和 Provider 429。当前配置和 6 条样本不能证明生产吞吐。

### 16.4 安全：Scope Filter 为什么要做两次，RAG 如何防数据泄漏

多租户 RAG 最危险的错误是先全局召回 TopK，再在应用层过滤。越权文本已经进入 ES 结果、Trace 或 Rerank，合法候选也可能被越权候选挤出 TopK。所有查询先下推 Workspace、Source Scope、Snapshot Status 与 Index Version，让不属于当前范围的文档不参加打分。

查询下推仍不够。ES 是异步投影，可能有脏文档、旧版本或错误 Workspace 字段；批量 Hydration 因此回 MySQL 校验 Source 与 Snapshot 归属、状态和用户可访问性。校验 Fail Closed，数量或身份不匹配时丢弃并记录 Scope Violation，不能为了可用性静默保留。这个双层设计增加一次批量查询，却把索引正确性和授权正确性分开。

Query Rewrite 也在安全边界内。用户问“第二个呢”时可以解析指代，但改写必须保留 Workspace、Source Scope 和显式约束，模型猜测不能扩大检索范围。置信度不足时澄清，不用全库检索掩盖歧义。Embedding 与 Rerank Provider 只接收必要文本，Trace 不记录密钥和不必要全文。

资料内容按不可信数据处理。Chunk 中的 Prompt Injection 不能变成系统指令，Citation 只证明来源身份和支持关系，不给外部文本工具权限。Memory 不进入 Retrieval 和 Evidence Rerank，避免用户偏好或模型历史输出改变事实来源排序。Wiki 页面无法回到 Source 时降低引用等级，不让生成知识自证。

当前还需说明 Provider 数据政策、日志留存、向量反推和敏感字段脱敏属于生产治理范围。仓库有 Scope、归属校验、错误脱敏和 Provider Endpoint Security 测试，但没有完整 DLP 与租户级加密密钥。

### 16.5 可观测性：回答质量下降时如何判断是召回、排序还是生成

每次 Retrieval Execution Trace 记录 Plan 与 Strategy Tuple、各阶段候选 ID、原始排名、融合、Rerank、去重、最终 Evidence、候选数、字符数、耗时和降级原因。没有这条 Trace，用户说答案变差时只能看最终文本，无法判断相关证据从未被召回、被错误排序、被 Selection 丢弃，还是生成阶段忽略了证据。

定位按漏斗进行。Gold Evidence 没进 BM25 或 Vector，问题在 Query、Chunk、Mapping、Embedding 或 Scope；进入召回但没进 RRF 前列，检查稳定 ID、权重和重复合并；RRF 靠前但 Rerank 降低，检查 Provider、输入截断和跨语言；Rerank 靠前但没入 Evidence，检查同源去重、预算和直接支持；Evidence 正确但回答错，才进入 Prompt、LLM 和 Citation Audit。

运行指标至少包括各路 Recall 数、去重率、Rerank 请求与失败、Evidence 选择数和字符、拒答与降级、Projection Lag、Provider Health、各阶段 P50/P95、Scope Violation 和 Citation Support。缓存要记录 Hit、Miss、Fallback 和 Version Mismatch，不能只看命中率。

告警要区分质量与基础设施。ES 不可用、Embedding 失败、Rerank 超时属于运行故障；索引 Ready 但 Recall 下降、Citation Support 下降属于质量回归；Scope Violation 属于安全事件。Projection 未完成不是 ES 故障，MySQL Fallback 也不能静默伪装正常 Hybrid 结果。

仓库已有版本化 Trace、Shadow Export、Quality Gate 和 Health Indicator，没有完整线上 A/B 与 OpenTelemetry。生产 SLO 需要分别定义 QA 首字延迟、完成延迟、可用率、降级率、Citation Support 和拒答正确性，不能用接口 200 代表回答成功。

### 16.6 成本：为什么不只用最强 Embedding 和 LLM Judge

RAG 成本包括离线解析与 Embedding、在线 BM25 与 Vector 查询、Rerank、Hydration、Prompt Token、索引存储和重建。模型升级还会触发全量 Backfill，索引双写期间存储与计算暂时增加。成本控制要同时看每次 Query 和每个 Source 生命周期。

在线链路使用漏斗控制昂贵步骤。BM25 与 ANN 先高效召回，RRF 不需要模型调用，Cross-Encoder 只处理有限候选，Evidence Selection 限制最终字符。让 Rerank 处理全库或让 LLM Judge 对每个 Chunk 打分，质量上限可能更高，但延迟、价格、输出稳定性和批处理能力更差。当前数据规模也不足以训练 LTR，因此 RRF 是低校准成本选择。

离线成本由 Chunk 数、重叠率、Embedding 维度和版本数决定。Chunk 越小召回定位更精细，但文档数、向量数和上下文碎片增加；重叠越大语境损失少，存储与重复候选增加。版本化索引避免错误混用，却需要旧索引回滚窗口。Wiki 与 Note 能复用 Source Snapshot 和 Chunk，不能各自重复生成相同向量。

缓存必须把 Workspace、Source Scope、Query、Strategy、Index 与 Provider Version 放进 Key。只按 Query 缓存会跨租户泄漏或在索引升级后返回旧结果。权限校验和 Evidence 结果不适合无边界长 TTL。

当前没有真实账单和单位 Query 成本报表。面试中可以提出记录 Embedding 字符、Rerank 候选、LLM 输入 Token、缓存命中、降级与质量指标，并比较每增加一层带来的 Recall 或 Citation Delta，不能声称固定节省比例。

### 16.7 测试证据：如何证明一次 RAG 优化真的有效

检索评测要分层。Recall@K 证明相关证据进入候选，MRR 与 NDCG 证明排序位置，Citation Precision 与 Coverage 证明最终引用，Refusal Accuracy 证明无证据时没有强答，Scope Violation 证明安全边界，P95 与降级率证明工程可用。最终答案人工评分或 LLM Judge 不能替代这些中间指标，否则质量下降时无法定位。

Gold Case 需要固定 Mode、Workspace、Query、允许 Source、相关 Evidence、期望 Citation 与是否拒答。Query 类型要分精确术语、语义改写、跨语言、指代、多资料冲突和无答案。Annotation 要保留 Draft、人工 Reviewed 状态，并检测 Candidate Drift 与 Raw Content Drift，资料变了以后旧标签不能继续冒充真值。

优化实验先冻结 Strategy Tuple，再做 Ablation。比较 BM25、Vector、Hybrid、Rerank 与 Selection 时只能改变目标变量，其他 TopK、索引和数据保持一致。Shadow Replay 导出内容受控的 Trace、Bundle 与 Receipt，在新策略上重放，Quality Gate 同时看绝对阈值和相对 Delta。新策略 Recall 提高但 Scope Violation、延迟或拒答变差，不能发布。

当前 3 条 QA 与 3 条 Note 的 33.3% 到 100% 是手工固化 fixture，用于报告契约，不是当前在线融合链路自动实验。现有 Replay 使用确定性 BM25 Baseline。它证明评测机制能回归，不证明 Hybrid 在线准确率。更强证据需要扩大人工样本、真实 ES 与 Provider、分桶报告和置信区间。

工程测试还要覆盖 RRF 稳定 ID、Rerank 超时回退、ES Disabled 与 Unavailable 区分、Alias 切换、旧投影晚到、Ownership 校验和缓存版本失配。每项测试对应一个明确不变量。

### 16.8 演进边界：什么时候需要 LTR、独立向量库或完整 GraphRAG

当前选择 Elasticsearch 同时承载 BM25、过滤和向量检索，部署和权限过滤较统一。只有向量规模、召回延迟、索引更新或 ANN 能力明确超出 ES，才评估独立向量库；迁移后要解决双系统过滤一致性、Candidate ID、重建与观测，不能只比较向量 QPS。

RRF 适合异构分数难校准、标注数据少的阶段。积累足够 Query、Candidate、点击或人工相关性标签后，可以训练 LTR，学习来源、字段、时效和交互特征；它的代价是训练数据偏差、特征漂移、线上推理和解释成本。上 LTR 的触发条件应是 RRF 在稳定错误类型上达到上限，而不是模型更先进。

完整 GraphRAG 适合实体关系决定答案、跨文档多跳查询占比高、文本召回持续漏掉结构路径的场景。当前 Wiki 已有页面关系和 Source Backlink，但没有实体抽取、图社区和社区摘要。引入完整图链路前要证明关系扩展提高了多跳 Recall，并处理图版本、错误边、权限传播和重建成本。

Query Rewrite 可以从规则与单次模型输出演进为多候选 Query 或 HyDE，但会增加费用和错误扩大风险；Rerank 可以换更强模型，但要重新评测跨语言、长文本与延迟；Chunk 可以从固定窗口演进为文档类型感知或语义切分，但必须重建索引和 Gold Evidence。

小规模、单场景资料库继续使用 BM25 或简单 Hybrid 更合理。演进依据是可重复的错误分析、质量 Delta、延迟和成本，不是把更多检索名词叠进链路。

判断顺序也很重要：先确认问题来自召回能力，而不是资料未索引、Scope 误过滤、切片不合理或评测样本偏差；再通过离线 Replay 和 Shadow 流量证明候选方案在相同 Evidence Budget 下改善质量，且 P95 延迟与单位请求成本仍在预算内。只有收益持续跨过预设门槛，才承担双写、回填、删除同步和回滚链路。这样面试官追问“为什么现在不用”时，回答的是证据和迁移成本，而不是团队不会某项技术。

## 17. 八维母题的项目源码答辩卡

| 评分面 | 项目功能与生产类 | 核心方法、状态或真实设置 | 验证证据与当前边界 |
|---|---|---|---|
| 业务抽象 | QA 进入 `QaHybridRetriever.retrieve()`，Note 进入 `NoteRetrievalService`，Wiki 进入 `WikiEvidenceRetriever` | QA 召回 Passage，Note 先选 Source 再规划阅读，Wiki 从 Page 出发做受限图扩展 | QA、Note、Wiki Retriever Test；三条链路共享资料真源，但不共享同一种召回单位 |
| 数据与一致性 | `SourceRetrievalProjectionService.projectSnapshot()` 生成 QA Chunk 与 Note Source 双投影，`SourceRetrievalProjectionFinalizer.finalizeReady()` 收口 READY | Projection 带 Snapshot、Embedding、Schema Version；QA 与 Note 都完整后才把当前 Snapshot 标记可检索 | `SourceRetrievalProjectionCoordinatorTest`、Compensation Test；MySQL、ES 之间是最终一致，不是强事务 |
| 并发与容量 | `OpenAiCompatibleEmbeddingClient` 批量嵌入，`ElasticsearchQaHybridSearchAdapter` 执行 kNN 与 BM25，`QaRerankService` 调 Rerank | Embedding Batch 32、Rerank Batch 80、QA Recall 60、Fusion 30、Rerank 12、Final Evidence 8 | Provider Client、RRF、Rerank Test；参数是默认基线，尚无生产 P95 容量结论 |
| 安全 | ES 查询先按 Workspace、Source Scope 和 Current Snapshot 过滤，`RetrievalHydrator.hydratePassageOwnership()` 再从 MySQL 校验归属 | Scope Filter 前置减少泄漏面，Ownership Hydration 拒绝陈旧或伪造命中；缓存不能替代授权 | ES Query Test、`JdbcEvidenceOwnershipAdapterTest`；没有把向量相似度当权限判断 |
| 可观测性 | `QaHybridRetriever` 输出 Vector、Keyword、RRF、Rerank、Ownership Rejected、Selected 数量，Projection Binder 输出状态指标 | 诊断按召回漏斗拆分，不用最终答案差直接推断 Embedding 失效 | Retrieval Shadow、Quality Gate、Operational Metrics Test；当前 Trace 主要靠 RunId 与 SnapshotVersion 串联 |
| 成本 | Query Embedding、Rerank、LLM Judge、ES 存储和重建分别核算，`QaRerankService` 在 Provider 不可用时回退 RRF | Rerank 默认关闭，缺失时结果标记 Degraded；正式 Release Gate 要求 Rerank 与质量凭证 | `QaRerankServiceTest`、`RetrievalReleaseGateServiceTest`；降级能运行不等于可正式发布 |
| 测试证据 | `RetrievalBenchmarkReplay`、`RetrievalShadowComparator`、`RetrievalQualityGate` 形成离线与 Shadow 证据链 | Gold、Shadow、Ablation 使用同一 Snapshot 和 Evidence Budget 才可比较 | Final Gold Contract、Replay、Shadow Comparator Test；6 条 Fixture 不能表述成线上准确率 |
| 演进边界 | `RetrievalBackfillService.rebuildWorkspace()` 创建新物理索引，`RetrievalIndexManager.switchAliases()` 原子切换 | 先双写或回填，再做覆盖检查和 Alias 切换；失败保留旧 Alias | Live ES Integration、Backfill Catalog Gate Test；当前 ES 为单节点 Compose，不是生产向量集群 |
