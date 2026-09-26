# 场景化 RAG 演进、技术选择与 Trade-off 专项

> 默认主回答见[场景化 RAG 一体化面试手册](31-场景化RAG一体化面试手册.md)。本文只负责 BM25、Vector、RRF、Rerank、Reading Window、受限图读取和索引版本化的选择过程，适合回答“为什么不用另一种方案”，不承担默认功能介绍。

> 本文补充两篇 `02-场景化RAG` 主文档，重点回答“为什么一路演进成现在这样”。代码和 Git 能直接证明的内容标为 `[当前实现]`；为了面试复盘组织出的阶段标为 `[设计演进线]`；外部资料只标为 `[行业参考]`，不能拿来证明项目效果。

## 1. 一句话定位

NoteWeave 的 RAG 不是“向量库加 Prompt”，而是以 Workspace、Source Snapshot 和 Evidence 为边界，把同一资料底座上的问答、长文精读和 Wiki 关系浏览拆成三种相关性定义，并用版本化策略、双重归属校验和可回放评测保证结果可解释。

## 2. 两条时间线必须分开

### 2.1 Git 可以验证的实现时间线

| 日期 | Commit | 可验证变化 | 面试中可以说什么 |
|---|---|---|---|
| 2026-05-16 | `c22f1e8e` | 团队问答、引用和 Retrieval Trace 的早期闭环 | 项目先建立“资料可问、答案可引用”的学校服务原型 |
| 2026-07-03 | `d869a603` | Workspace、Upload 和 RAG 主流程重建 | 资料归属与检索从功能代码进入 Workspace 边界 |
| 2026-07-03 | `a65c05c9` | QA Evidence Chain 加固 | QA 不再只返回 TopK 文本，而是形成 Evidence 身份和选择链 |
| 2026-07-03 | `4814cd67` | Wiki 重建与 Note 关系召回 | Note、Wiki 从通用问答中拆出自己的候选对象 |
| 2026-07-20 | `ddc5fb26` | WeKnora QA 与 Marginalia Note 检索闭环 | Hybrid、Source Recall、Reading Window 和场景化评测进一步落地 |
| 2026-07-20 | `9bcd3110` | Projection Lifecycle、回填与发布门禁加固 | 索引成为可重建投影，而不是不可追溯的第二真源 |
| 2026-08-05 | `fd60682a` | 运行时加固 | Provider、降级、身份和配置组合的失败语义更完整 |

这些是实现节点，不等于系统严格按下面 V0 到 V8 上过九次生产版本。面试时要明确说“Git 事实线有这些提交；V0 到 V8 是我根据 Bad Case 复盘出的设计演进线”。

### 2.2 面试使用的设计演进线

```text
V0 关键词 TopK
  -> V1 BM25 + Dense Hybrid
  -> V2 RRF 候选融合 + 有限 Rerank
  -> V3 QA、Note、Wiki 三种候选对象
  -> V4 Evidence Selection 与拒答
  -> V5 多轮 Query Rewrite 与低召回扩展
  -> V6 查询前 Scope + 命中后 Ownership
  -> V7 Snapshot、Projection、Alias 与 Release Gate
  -> V8 分层 Gold、Shadow Replay 与成本门禁
```

演进主线不是“用了越来越多模型”，而是相关性、权限、版本和失败语义逐步从隐式约定变成显式契约。

## 3. 为什么学校项目会需要场景化 RAG

最早的学校需求很朴素：学生上传课程大纲、实验手册和课件，然后追问考核规则、实验步骤或某个概念。很快出现三类完全不同的任务：

1. “补交实验扣多少分”要求精确规则和直接引用，这是 QA。
2. “围绕事务隔离级别继续整理这份 80 页讲义”要求先找对资料，再保留章节上下文，这是 Note。
3. “从事务页跳到 MVCC、Undo Log 和一致性读”要求页面版本、关系和来源回链，这是 Wiki。

如果三者都使用全库 Chunk TopK，只改变 Prompt，QA 会被长上下文稀释，Note 会得到互不连续的碎片，Wiki 会丢失页面身份和版本。因此项目共享资料真源、Provider、Evidence 和权限基础设施，但拆分候选单位、排序目标、预算与失败语义。

## 4. V0：为什么从 BM25 起步

`[设计演进线]` 学校资料中的课程号、实验编号、百分比、Java 类名和错误码需要精确词项匹配。BM25 成本低、无需训练数据、结果可解释，适合作为第一条可工作的基线。

BM25 的典型形式为：

```text
score(D,Q) = sum IDF(q_i) * f(q_i,D) * (k1 + 1)
             / (f(q_i,D) + k1 * (1 - b + b * |D| / avgdl))
```

`k1` 控制词频饱和，`b` 控制文档长度归一化。它比朴素 TF-IDF 更能抑制一个词重复很多次带来的虚高分。但“退课申请”“撤销选课”可能语义接近、字面不同，跨中英文提问也容易漏召回，这推动了 Dense Recall。

## 5. V1：为什么 Hybrid，而不是用向量替换关键词

### 5.1 两路检索解决不同错误

| Query 类型 | BM25 | Dense | 结论 |
|---|---|---|---|
| 课程号、版本号、错误码 | 强 | 可能把相似标识符混在一起 | 保留词法锚点 |
| 同义改写、自然语言描述 | 可能漏召回 | 强 | 需要语义召回 |
| 生僻专名、短 Query | 通常稳定 | 取决于训练语料 | 不能只信向量 |
| 跨语言提问 | 依赖分析器和资料语言 | 多语模型可能更好 | 需要实测模型，不做先验承诺 |

`[当前实现]` `QaHybridRetriever` 使用 `ElasticsearchQaHybridSearchAdapter` 的关键词与向量通道。当前 `QaRetrievalStrategyProfile` 固化每路 Recall 60、Vector Weight 0.7、Keyword Weight 0.3；这些是可复现默认参数，不是已经证明的最优参数。

### 5.2 为什么不直接上独立向量数据库

Elasticsearch 已同时承担 BM25、向量、Workspace/Source 过滤和版本化投影，当前规模下减少了一套双写与权限一致性链路。只有出现以下证据时才值得迁移：

- ANN 规模或延迟明确超出 ES 能力；
- 向量过滤、更新或量化成为主要瓶颈；
- 独立向量库的质量或成本收益覆盖双系统运维成本。

迁移时必须重新解决 Candidate ID、删除传播、ACL 同步、回填、Alias、观测和回滚，不能只比较纯向量 QPS。

## 6. V2：为什么 RRF 和 Rerank 都需要

BM25 分数与余弦相似度不在同一量纲，Min-Max 归一化会受当前候选分布和极值影响。Learning to Rank 能学习复杂特征，但当前没有足够稳定的人工标签。RRF 只依赖排序，对异构通道和小数据阶段更稳。

`[当前实现]` 加权 RRF 公式是：

```text
RRF(d) = sum_c weight_c / (k + rank_c(d))
```

当前 `k=60`，Vector Weight 为 `0.7`，Keyword Weight 为 `0.3`，Fusion 最多保留 100 个候选。`k` 越大，前几名之间的差异越平滑；权重代表通道先验，不代表概率。候选以稳定 `chunkId` 合并，并用 `chunkId` 打破同分，确保回放顺序确定。

RRF 的弱点是忽略 Query 与文本的细粒度交互，也不知道第一名比第二名究竟强多少。因此项目只在有限候选上调用通用 `RerankClient`，当前 Rerank 上限 40。Rerank 不能修复召回缺失，只能重新排序已进入候选的内容。

Rerank Provider 关闭、异常、空响应、重复或越界下标、非有限分数时，`QaRerankService` 回退 RRF 顺序并记录 `QA_RERANK_UNAVAILABLE`。降级结果可以继续运行，但不能伪装为完整发布质量。

## 7. V3：为什么 QA、Note、Wiki 不能只换 Prompt

| 场景 | 候选单位 | 优化目标 | 当前关键实现 | 最大代价 |
|---|---|---|---|---|
| QA | Passage/Chunk | 少量直接支持答案的证据 | `QaHybridRetriever`、RRF、Rerank、Evidence Selection | 两路召回和 Provider 时延 |
| Note | Source -> Anchor -> Reading Window | 找对资料并保留连续原文 | `NoteRetrievalService`、`NoteReadingPlanner`、`NoteReadingRetriever` | 两阶段检索和更多策略参数 |
| Wiki | Current Page Version + 受限邻域 | 页面身份、关系和来源回链 | `WikiEvidenceRetriever`、`WikiGraphBudgeter` | 关系能力受限，不是完整 GraphRAG |

`[当前实现]` Note 对每个 Source 规划最多 4 个窗口，再把候选限制到 24，Rerank 最多返回 8。Primary Window 是最强锚点，Continuation Window 优先选择相邻 Chunk 或相邻 Window，剩余位置才放 Secondary Evidence。这是启发式 Cascade Retrieval，不是学习型阅读规划器。

`[当前实现]` Wiki 使用当前知识页版本、出链、反链和 Citation，受 Graph Hop、Node、Edge 和 Character Budget 约束。它没有实体抽取、社区发现和社区摘要，不能包装成 Microsoft GraphRAG。

## 8. V4：为什么排序后还要 Evidence Selection

TopK 是单候选排序，最终 Prompt 需要的是集合。六个相邻 Chunk 即使都相关，也可能重复占满上下文，遗漏第二个独立来源。

`[当前实现]` `QaEvidenceSelectionPolicy` 先做一轮每个 Source 至少取一个候选，再按原顺序填满剩余位置；最多 6 条 Evidence、总字符不超过 8,000，并按 Evidence ID 去重。这个贪心策略的优点是确定、可回放、成本低；缺点是 Source Diversity 只是代理指标，不能保证结论覆盖最优。

理想的集合目标可以写成：

```text
Utility(S) = relevance(S)
           + lambda1 * source_coverage(S)
           + lambda2 * claim_coverage(S)
           - lambda3 * redundancy(S)

subject to |S| <= 6 and characters(S) <= 8000
```

当前代码没有求解这个优化问题，面试中只能把它作为解释 Evidence Selection 的目标函数，不得声称已经实现 MMR 或整数规划。

## 9. V5：为什么 Query Rewrite 必须冻结版本

多轮问题“第二个呢”缺少独立语义，需要结合会话主题改写。但 Rewrite 可能删掉否定词、课程号、时间范围或 Source Scope，因此它不是普通文本预处理，而是一次受约束的计划变换。

`[当前实现]` QA Strategy Tuple 固化 `qa-conversation-rewrite-v1` 和 `qa-low-recall-expansion-v1`。原始 Query、改写版本、检索 Query、Must Terms、Preferred Terms、语言与问题类型进入 Plan/Trace。低置信歧义应澄清，不能用全库扩召回掩盖错误指代。

为什么不默认 HyDE：它能把短问题扩成假想答案，可能改善语义召回，但也会加入资料中不存在的实体和结论，扩大错误召回。只有在冻结模型、Prompt、Scope 并通过单独消融后才值得启用。

## 10. V6：为什么权限检查做两次

第一道边界在 ES Query 前下推 Workspace、允许 Source 与 Current Snapshot Filter，避免越权内容进入候选和排序。第二道边界由 `RetrievalHydrator`/Ownership Adapter 回到 MySQL 真源校验 Source、Snapshot 和 Workspace 归属，防索引脏数据、延迟删除或伪造命中。

这不是重复授权：检索系统负责高效缩小候选，MySQL 真源负责最终业务事实。任何相似度分数都不是权限结论。`Scope Violation=0` 是必须满足的安全不变量，但只有在足够规模、包含对抗样本的评测中才有宣传价值。

## 11. V7：为什么索引只是投影

Embedding 模型、维度、Mapping 或 Chunk 策略变化后，原索引不能静默复用。MySQL 保存 Source、Snapshot、Chunk 与 Projection 状态，MinIO 保存原文件，ES 保存可重建投影。`RetrievalBackfillService` 创建新物理索引，覆盖与质量门禁通过后由 Alias 切换。

代价是最终一致性和双份存储。用户上传成功不等于立即可检索，必须暴露 Parse、Projection 和 Ready 状态；旧投影晚到不能覆盖新 Snapshot；删除先撤回可见性，物理清理异步收敛。

为什么不原地覆盖：原地更新空间省，但模型或 Mapping 出错时没有完整回滚面，也无法解释历史回答使用了哪套索引。版本化投影用存储成本换可回滚和可复现实验。

## 12. V8：为什么评测也要成为系统能力

最终答案变好不能证明检索变好。项目需要沿漏斗记录：

```text
Query/Scope
  -> Keyword/Vector Candidate
  -> RRF Rank
  -> Rerank Rank
  -> Relevance Admission
  -> Ownership Hydration
  -> Evidence Selection
  -> Citation/Answer
```

`[当前实现]` 仓库有 Gold Contract、Deterministic Replay、Shadow Comparator、Quality Gate 和 Release Gate。当前 QA/Note 消融只有各 3 条固定 Fixture，33.3% 到 100% 证明报告结构和回归机制，不是在线 Hybrid 的实测准确率。Rerank 在现有 3 条 QA Fixture 上没有产生额外 Recall Lift，也不能据此断言它无用。

## 13. 关键参数如何解释，而不是死背

| 参数 | 当前值 | 解决什么 | 调大风险 | 调小风险 |
|---|---:|---|---|---|
| Candidate Limit | 12 | Retrieval Step 的候选预算 | 下游成本增加 | 相关项进不了后续阶段 |
| 每路 Recall | 60 | 给 Hybrid 足够召回面 | ES、网络和融合成本增加 | 长尾召回下降 |
| Fusion Limit | 100 | 合并两路去重后的上限 | 内存与 Rerank 输入增加 | 截断单路独有候选 |
| Rerank Limit | 40 | 昂贵精排的候选上限 | P95 和 Provider 成本增加 | 精排看不到潜在相关项 |
| RRF K | 60 | 平滑名次贡献 | 前列差异变弱 | 过度奖励每路第一名 |
| Vector/Keyword Weight | 0.7/0.3 | 表达当前通道先验 | 语义近似误召回增加 | 同义改写召回可能下降 |
| Final Evidence | 6 | 控制集合大小 | Prompt 冗余 | 结论覆盖不足 |
| Evidence Characters | 8,000 | 控制上下文成本 | Token、延迟增加 | 长规则被截断 |

参数必须通过 Query 分桶消融决定。当前值是版本化基线，不是“业内最佳参数”。

## 14. 比较过但没有采用的方案

| 方案 | 优点 | 当前没有选择的原因 | 触发条件 |
|---|---|---|---|
| 只用 Dense | 实现简单、语义泛化好 | 精确标识符和数字不稳 | 业务 Query 几乎没有词法锚点且实测胜出 |
| 归一化分数相加 | 保留原始分差 | 不同 Query/模型分布需要持续校准 | 有稳定校准集和漂移监控 |
| Learning to Rank | 能学习复杂特征 | 标签规模不足，存在反馈偏差 | 有足够人工或行为标签及线上特征服务 |
| 全库 Cross-Encoder | 排序表达力强 | 计算量和延迟不可接受 | 候选库极小或离线任务 |
| 完整 GraphRAG | 擅长全局与多跳关系 | 索引成本、错误边、权限传播复杂 | 多跳错误占比高且受限图扩展已到上限 |
| End-to-end 长上下文 | 少维护检索组件 | 成本高、定位差、权限与引用仍要做 | 单份短资料、低频离线任务 |
| 生成式 Query Expansion/HyDE | 可补同义表达 | 可能扩大错误实体和范围 | 受控消融证明净收益 |

## 15. 外部方案给了什么启发

| 外部工作 | 可借鉴的点 | NoteWeave 的不同点 |
|---|---|---|
| BEIR | 强 BM25 基线，报告 nDCG、Recall 和延迟 | 内部资料还必须评估 Scope、Snapshot 和 Citation |
| KILT | 答案和 Provenance 要一起评价 | Source Snapshot 不是固定 Wikipedia Snapshot |
| RAGAS/ARES | 把 Context、Faithfulness、Answer 分层 | LLM Judge 只能是代理，需要人工校准 |
| Elasticsearch Hybrid Search | BM25 与向量可用 RRF 融合 | 还增加了业务真源 Ownership 二次校验 |
| Weaviate | Search、Filter、Rerank、多租户是不同问题 | 当前没有为了向量能力引入独立数据库 |
| Microsoft GraphRAG | Local、Global 和图索引解决不同问题 | 当前 Wiki 仅受限图读取，不宣称完整 GraphRAG |

详细来源见[场景化 RAG 演进取舍外部依据](../research/RAG演进取舍外部依据.md)。外部论文只能解释选择合理，不能把论文分数写进项目简历。

## 16. 五分钟面试主回答

这个项目最初给学校做课程资料服务。第一版用关键词 TopK，因为课程号、实验编号和评分比例这类精确字段 BM25 很可靠；但学生换一种问法或跨语言提问会漏召回，所以我加入 Dense Recall。两路分数不同量纲，当时又没有足够 LTR 标签，因此选择只依赖名次的加权 RRF；再把 Cross-Encoder 能力放在有限候选的通用 Rerank Provider 后面，Provider 异常就保留 RRF 顺序并显式标记降级。

真正的转折不是加了向量，而是发现 QA、Note、Wiki 对“相关”的定义不同。QA 要直接支持答案的 Passage，Note 要先选 Source 再找 Anchor 和连续 Reading Window，Wiki 要当前 Page Version、有限关系和 Source Backlink。所以它们共享资料真源、权限、Evidence 和引用契约，但拆分候选单位和预算。排序后还有 Evidence Selection，避免同一来源的相邻 Chunk 占满上下文；最终最多 6 条、8,000 字符，这些参数都冻结在 Strategy Tuple 中。

工程上我把 ES 当可重建投影，不当业务真源。召回前下推 Workspace 和 Source Scope，命中后再回 MySQL 校验 Ownership；索引升级走新物理索引、回填、质量门禁和 Alias 切换。评测也分召回、排序、Evidence、引用、拒答、安全、延迟与成本。仓库当前 3 条 QA 和 3 条 Note 消融只是固定 Fixture，证明回放机制，不会在面试里说成线上 100%。完成扩大后的人工 Gold 和真实 Provider 消融后，才会把 Hybrid 相对 BM25 的绝对提升写进简历。

## 17. Ownership 答法

项目只有我一个人类开发者，AI 是实现辅助工具。我负责把学校场景拆成 QA、Note、Wiki 三种任务，决定数据与权限边界、比较 RRF/LTR/分数融合、设计 Snapshot 与 Projection 状态、定义失败语义和指标，并审查代码、补测试、跑回放和验收。AI 可以加速生成实现与文档，但它不承担线上结论，也不能决定哪些 Fixture 可以宣传。

## 18. 代码与证据速查

| 主题 | 代码或测试入口 |
|---|---|
| 冻结策略参数 | `QaRetrievalStrategyProfile`、`QaRetrievalStrategyProfileTest` |
| Hybrid 主链 | `QaHybridRetriever`、`ElasticsearchQaHybridSearchAdapter` |
| 加权 RRF | `QaRrfFusionService`、`QaRrfFusionServiceTest` |
| Rerank 失败语义 | `QaRerankService`、`QaRerankServiceTest` |
| 相关性准入 | `QaEvidenceRelevancePolicy`、对应 Policy Test |
| 集合选择 | `QaEvidenceSelectionPolicy`、对应 Policy Test |
| Note 两阶段阅读 | `NoteRetrievalService`、`NoteReadingPlanner`、`NoteReadingRetriever` |
| Wiki 受限图读取 | `WikiEvidenceRetriever`、`WikiGraphBudgeter` |
| Ownership | `RetrievalHydrator`、`JdbcEvidenceOwnershipAdapterTest` |
| 投影与切换 | `SourceRetrievalProjectionService`、`RetrievalBackfillService`、`RetrievalIndexManager` |
| 评测 | `FinalRetrievalGoldContractTest`、`RetrievalBenchmarkReplayTest`、`RetrievalShadowComparatorTest`、`RetrievalQualityGateTest` |

## 19. 面试官二次审查结论

| 审查问题 | 当前是否闭环 | 还需验证什么 |
|---|---|---|
| 为什么不是普通向量 RAG | 是，三种候选对象和失败语义明确 | 用真实案例展示差异 |
| 为什么选 RRF | 是，数据规模和分数校准取舍明确 | 在人工 Gold 上与归一化融合配对比较 |
| 参数为什么这样定 | 部分，代码参数可复现 | 需要分桶敏感性实验 |
| Rerank 是否真有收益 | 尚未证明 | 构造 Hard Negative 并报告 nDCG Lift/P95 |
| 安全如何证明 | 机制完整 | 增加跨 Workspace、旧 Snapshot、缓存污染对抗集 |
| 业务价值如何证明 | 尚未证明 | 与人工查资料建立时间、采纳和大改率基线 |
| 外部水平如何比较 | 口径已对齐 | 运行公开子集前不能声称达到某 Benchmark 水平 |

下一篇[场景化 RAG 真实案例、消融实验与 Ownership 答辩](19-场景化RAG真实案例消融实验与Ownership答辩.md)专门补齐真实案例、理想数据、统计可信度和宣传边界。
