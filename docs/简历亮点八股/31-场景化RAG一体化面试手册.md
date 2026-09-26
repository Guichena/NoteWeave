# 场景化 RAG 一体化面试手册

> 这篇把 QA、Note、Wiki 的架构、演进、案例、消融、契约和面试回答合并成一个入口。三种模式共享运行合同和安全边界，但保留各自的检索目标。

## 1. 60 秒主回答

项目最初只有一条关键词 TopK 链路，能回答简单问题，但在课程编号、同义表达、长文精读和知识关系上表现不稳定。我把 RAG 拆成统一骨架和三种场景策略：QA 面向直接回答，Note 面向 Source 内连续深读，Wiki 面向页面版本和关系组织。

统一骨架是 `RetrievalPlan -> EvidenceBundle -> PromptSpec -> AnswerRun`。计划冻结查询、来源范围、版本和参数；EvidenceBundle 保存经过权限、归属、版本和预算过滤的证据；PromptSpec 固化生成约束；AnswerRun 保存模型、策略、证据和事件。QA 使用 BM25、向量、Weighted RRF、可选 Rerank 和集合级 Evidence Selection，Note 使用 Source Recall、Anchor 和 Reading Window，Wiki 使用 Page Version、显式链接、反链和受限图扩展。

## 2. 从一条 TopK 到三种场景

| 演进 | 旧方案 | 失败 | 当前方案 |
| --- | --- | --- | --- |
| V0 | BM25 单路召回 | 同义表达和语义问题漏召回 | Hybrid Recall |
| V1 | 词法分数和向量分数直接相加 | 分数不在同一量纲 | Weighted RRF |
| V2 | 排序后直接取 TopK | 重复来源、子问题覆盖差 | 集合级 Evidence Selection |
| V3 | QA、Note、Wiki 共用 Retriever | 场景目标不同 | Strategy Contract + 三种策略 |
| V4 | Query 和索引配置隐式变化 | 结果无法复现 | Plan、Prompt、Index Version 冻结 |
| V5 | 只做一次权限过滤 | 投影或关联错误可能跨租户 | Scope Guard + Ownership Guard 二次校验 |

## 3. 统一运行合同

```text
Turn
  -> AnswerModeStrategy
  -> RetrievalPlan
  -> RetrievalOrchestrator
  -> EvidenceBundleSnapshot
  -> PromptSpec
  -> AnswerRun
  -> Citation / Replay / Feedback
```

| 合同 | 作用 |
| --- | --- |
| RetrievalPlan | 记录查询改写、召回通道、TopK、版本和预算 |
| EvidenceBundle | 记录来源、Snapshot、Chunk/Window/Page 定位和排名信号 |
| PromptSpec | 分离系统指令、模式规则、引用约束和输出结构 |
| AnswerRun | 记录一次不可变的语义回答运行、冻结输入、总预算、状态和结果；基础设施恢复属于其下的 ExecutionAttempt |
| EvidenceOwnershipGuard | 校验资源属于当前 Workspace |
| EvidenceScopeGuard | 校验版本、来源范围和允许的检索边界 |

共享合同保证能做 Shadow Replay 和故障定位，策略差异放到 `AnswerModeStrategy`、Retriever、Budgeter 和 Prompt Builder 中，避免复制三套 Controller。

推荐把公共部分收进 `RetrievalOrchestrator` 深模块，而不是让 Controller 拼装一串可选步骤。它只暴露一组小 Interface：

```text
plan(request, scope) -> RetrievalPlan
retrieve(plan) -> EvidenceBundleSnapshot
revalidate(bundleId, currentScope) -> ValidatedEvidenceBundle
explain(bundleId) -> RetrievalTrace
```

这个 Module 内部拥有 Scope/Ownership 二次校验、Snapshot Hydration、预算、降级分类、Evidence 去重和 Trace。QA Passage、Note Reading Window、Wiki Page/Link 是同一个 Seam 上的三个真实 Adapter，它们负责各自的候选发现和类型化定位，不负责绕开公共安全门禁。公共 Evidence Envelope 只保存身份、Snapshot、定位类型、排名信号和摘要；类型细节由 Adapter Contract 解释，不能把三种定位全塞进一个任意 JSON。这样既复用真正稳定的复杂度，也不会为了“统一 RAG”抹平三种场景的目标函数。

## 4. QA：先保证有机会答对

QA 的证据单位是 Passage 或 Chunk。BM25 负责课程编号、术语、错误码等精确词，向量通道负责同义表达。两路结果通过 RRF 融合：

```text
RRF(d) = Σ w_i / (k + rank_i(d))
```

RRF 使用排名而不是原始分数，减少异构检索器校准成本。Rerank 只负责候选相关性，后续还要做集合级选择，约束来源去重、子问题覆盖、独立性和 Token Budget。

QA 的失败不是只有“没搜到”：还包括检索到了但引用不支持、相同来源重复占满上下文、冲突资料被静默合并和证据跨 Workspace。拒答应由证据充分性、覆盖、引用资格和冲突状态共同决定。

## 5. Note：Source 内连续深读

Note 先做 Source Recall，再在目标 Source 内找 Anchor，并按标题层级、相邻位置、字符上限和章节边界扩成 Reading Window。它解决的是“资料找对后，如何保留上下文”，而不是把全库最相关的几个 Chunk 拼起来。

Window 太小会丢定义、前提和例子，太大则增加成本和噪声。当前前端支持可编辑草稿，后端还没有完整 Draft Revision、Reject、Expected Version 和幂等保存合同。面试口径应说“实现了 Source 到 Reading Window 的深读链路，独立草稿版本和多人协作是下一阶段”。

## 6. Wiki：页面版本和受限图扩展

Wiki 的节点是 Knowledge Item 和 Page Version，边来自显式链接、反链和来源回链。文本相似度只能产生候选，不能直接代表概念关系。读取时限制跳数、节点数、边数、关系类型和 Workspace，避免一个总览页把整个知识库拖进上下文。

页面正文、出链和摘要属于同一版本，混合新正文与旧关系会形成不存在的知识状态。当前追加页面版本还没有完整 Expected Head Version，Auto-fix 会直接创建缺页，不是 Proposal 审批流。生产化时应增加 Expected Head Version 和高风险 Proposal。

## 7. 三条学校场景演练

### QA

问题是“课程退选截止规则是什么”。BM25 命中正式通知中的精确规则词，语义通道补充“撤课时限”表达，RRF 融合后保留正式通知和课程手册两类来源，答案逐句引用，日期冲突显式提示。

### Note

用户要求整理数据库课程讲义中的 MVCC。系统先锁定 Source，再以 Read View 段落为 Anchor，扩展到 undo log、可见性判断和示例所在连续 Window，生成可编辑笔记，不主动混入其他课程资料。

### Wiki

用户打开“事务隔离级别”页面，系统读取当前 Page Version，沿显式链接扩展到 MVCC 和间隙锁，受两跳和节点预算限制。缺失页面当前可演示 Auto-fix，目标态应先生成待审批 Proposal。

## 8. 评测和消融

| 维度 | 主指标 | 护栏 |
| --- | --- | --- |
| QA Retrieval | Recall@K、nDCG@K | Citation Support、Scope Violation、P95 |
| Note Reading | Source Hit@K、Window Coverage | Citation、连续性、Window Token |
| Wiki Graph | Link Precision、Supporting Evidence F1 | Version Consistency、Orphan Rate、Scope |

消融要按配对 Case 比较：BM25、Hybrid、Hybrid + RRF、Full Pipeline。当前仓库的小 Fixture 只能证明路径和门禁，不能声称 Rerank 已在行业数据上带来收益。正式实验需要冻结 Gold、Snapshot、Chunk Policy、模型、Prompt、Judge 和成本口径。

## 9. 关键 Trade-off

### 为什么不让大模型直接读全部资料

资料量增大后成本和上下文窗口不可控，权限、版本和引用也难以证明。RAG 增加索引和评测成本，换来可扩展范围和证据诊断。

### 为什么不做万能 Retriever

QA 需要答案相关性，Note 需要相邻连续性，Wiki 需要关系可解释性。统一合同可以复用，统一目标函数会损失场景质量。

### 为什么不用纯 GraphRAG

图适合关系扩展，不适合所有文本问题，构图本身也有误差。项目只在 Wiki 使用受限图增强，QA 和 Note 仍以文本检索为主。

### 为什么保存 Evidence Snapshot

重新检索更省空间，但索引更新后无法复现旧答案。快照增加存储和隐私治理成本，换来引用追溯、回放和版本一致性。

## 10. 面试官连续追问

### RRF 的 `k` 和权重如何定

在冻结 Gold 上网格搜索，观察 Recall、nDCG、来源覆盖、P95 和成本，并按精确术语、语义问题、多跳和拒答分桶。权重不是常数真理，数据分布变化后要重新评估。

### Rerank 失败怎么办

保留融合候选作为降级结果，同时在 AnswerRun 记录 Degradation Mode。高风险场景可以提高拒答阈值，不能静默把降级结果当成正常质量。

### 怎么防跨 Workspace 召回

查询带 Workspace 和 Snapshot Filter，结果进入 Prompt 前再经过 Ownership Guard。权限服务不可用时高风险读取关闭访问，而不是放行。

### Prompt Injection 如何处理

Source 内容按不可信数据处理，不获得系统指令优先级；工具能力和检索分离授权；写回经过独立 Guard 或审批。RAG 本身不能消除 Prompt Injection。

### 效果比业界好吗

当前只能说评测结构采用 Recall、nDCG、Citation Support、Faithfulness 和安全门禁等行业常见口径。小规模内部 Gold 不足以证明行业领先。

## 11. 4 分钟标准主回答

场景化 RAG 的业务问题不是“要不要用向量库”，而是同一套资料在不同任务里需要不同的证据组织方式。QA 要在几秒内给出直接答案，最重要的是相关 Passage、引用和拒答；Note 面向一份资料连续理解，最重要的是 Anchor 周围的上下文和章节连续性；Wiki 面向稳定知识页面，最重要的是 Page Version、显式关系和来源回链。如果三种场景都用同一个 TopK，QA 会被重复内容占满，Note 会丢掉前后文，Wiki 会把文本相似误判成知识关系。

因此我把链路拆成统一合同和三种策略。统一部分固定 RetrievalPlan、EvidenceBundle、PromptSpec 和 AnswerRun。Plan 记录 Query Rewrite、Workspace Scope、Snapshot、召回通道、TopK、Rerank 和 Token Budget；Bundle 保存证据的 Source、Snapshot、Chunk/Window/Page 定位和排名信号；PromptSpec 固化引用规则和输出结构；AnswerRun 记录一次生成的模型、策略、事件和结果。这样索引变化后仍能回放旧答案，也能判断坏结果到底是 Query、Retrieval、Selection 还是 Generation 的问题。

QA 先用 BM25 处理课程编号、术语和错误码，再用向量通道补充同义表达。BM25 分数和向量相似度不在同一量纲，所以采用 Weighted RRF，按排名融合候选。Rerank 只负责重新判断候选相关性，后面还要做集合级 Evidence Selection，去除重复来源，保证子问题覆盖和证据多样性。这样做增加一次模型调用和尾延迟，但能把“最高分的五段重复内容”变成“共同支持答案的一组证据”。

Note 先召回 Source，再找 Anchor，并按标题、段落位置和字符预算扩展 Reading Window。Wiki 则读取当前 Page Version，沿显式链接和反链做受限图扩展，限制跳数、节点数和关系类型。三种模式共享 Workspace 和 Snapshot Guard，避免跨租户或跨版本引用，但不强行共享 Retriever 的目标函数。

评测时不能只看最终答案。QA 看 Recall@K、nDCG、Citation Support、Unsupported Claim 和 Scope Violation；Note 看 Source Hit、Window Coverage 和连续性；Wiki 看 Link Precision、Supporting Evidence 和 Version Consistency。正式实验必须冻结 Gold、Snapshot、Chunk Policy、模型、Prompt 和 Judge，当前小 Fixture 只能证明路径和门禁。系统的代价是每种模式都要维护自己的参数和 Gold Set，收益是出现问题时能定位到具体层，而不是只看到一个总分下降。

## 12. 项目八股映射

| 面试八股 | 在 RAG 中的落点 | 继续追问 |
| --- | --- | --- |
| BM25 | QA 词法召回，适合精确术语 | 为什么 IDF 高的词可能仍然误导排序 |
| 向量检索 | 语义召回和同义表达 | Embedding 模型变化后索引如何重建 |
| RRF | 异构排名融合 | 为什么不能直接相加 BM25 和向量分数 |
| Cross Encoder | QA 可选 Rerank | 延迟和候选数量怎样联动 |
| MySQL 事务 | AnswerRun 与 Evidence Snapshot 固化 | 为什么检索结果不能只放 Redis |
| Elasticsearch | Source Snapshot 的可重建投影 | 投影落后时用户看到什么状态 |
| Redis 缓存 | Provider 健康和短期热点缓存 | 缓存旧权限会不会越权 |
| HTTP 超时 | Embedding、Rerank、Model 依赖 | 单次 Timeout 和总 Deadline 如何组合 |
| 多租户安全 | Scope Filter 加 Ownership Guard | 为什么要做两次归属校验 |
| 评测统计 | Recall、nDCG、Citation、CI | 4/4 命中为什么不能写成真实准确率 100% |

## 13. 追问链示例

如果面试官问“为什么需要 Rerank”，第一层回答是词法和向量融合后的候选还可能重复或排序不准；第二层要说明 Rerank 只处理候选相关性，Evidence Selection 还要解决集合覆盖和 Token Budget；第三层要回答 Provider 超时或返回非法下标时如何降级，系统应保留融合候选、记录 Degradation Mode，并重新评估拒答阈值。面试官问“为什么不直接做 GraphRAG”时，也要从数据结构、图构建误差、跳数预算和场景收益讲，而不是说 GraphRAG 太复杂。

## 14. 简历表达

> 设计 QA、Note、Wiki 三条场景化 RAG，在统一 RetrievalPlan、EvidenceBundle、PromptSpec 和 AnswerRun 合同上实现 Hybrid/RRF/Rerank、Source 内 Reading Window 与受限图扩展，并通过 Evidence Snapshot 支持引用追溯、权限校验和策略回放。

## 15. 深挖附件

RRF、Rerank、Evidence Selection、Reading Window 和多租户 Scope 的独立 3 分钟回答见[重点知识点三分钟深挖库](35-重点知识点三分钟深挖库.md)，二阶回答见[重点知识点二阶追问回答库](36-重点知识点二阶追问回答库.md)。

| 侧重点 | 附件 |
| --- | --- |
| 统一架构、算法、性能和代码定位 | [详细架构与具体设计](02-场景化RAG-详细架构与具体设计.md) |
| 演进、技术选择和外部参考 | [演进与技术取舍](18-场景化RAG演进与技术取舍专项.md) |
| 学校案例、Gold、消融和业务价值 | [案例、消融与 Ownership](19-场景化RAG真实案例消融实验与Ownership答辩.md) |
| DTO、状态、边界和连续追问 | [契约级数据模型](26-场景化RAG契约级数据模型与面试官下钻.md) |
| 索引重建、质量收据和发布门禁 | [检索投影治理与 Release Gate](40-检索投影治理-ReleaseGate与Inspector一体化面试手册.md) |
| 简短问答速查 | [面试题与参考回答](02-场景化RAG-面试题与参考回答.md) |

## 16. 面试前自检

1. 能解释三种模式的证据单位和排序目标。
2. 能从 RRF 讲到集合级 Evidence Selection。
3. 能说清 Note 草稿和 Wiki 版本的当前边界。
4. 能解释 Evidence Snapshot 为什么支持回放。
5. 能拿出一个 BM25、Hybrid、Rerank 各自解决的 Bad Case。
6. 能区分 Recall、Citation Support、Faithfulness 和业务采纳率。

## 17. 三种 RAG 不能共用一张成绩单

QA、Note 和 Wiki 的候选单位、相关性定义和失败后果不同。统一看一个 Answer Accuracy，会掩盖“答案碰巧正确但证据错误”“Source 找对但窗口截断”“Wiki 页面正确但版本过期”等问题。

| 模式 | 主指标 | 公式 | 必须配对的护栏 |
| --- | --- | --- | --- |
| QA | Recall@K | `TopK 命中的 Gold Evidence / Gold Evidence` | Scope Violation、Unsupported Claim、P95 |
| QA | nDCG@K | 按 Gold 相关等级计算折损累计增益，再除以理想排序 | Citation Support、拒答正确率 |
| Note | Source Hit Rate | `命中正确 Source 的 Query / Note Query` | Window Coverage、跨 Source 污染 |
| Note | Window Coverage | `窗口覆盖的 Gold 关键 Span / Gold 关键 Span` | Reading Token、连续性人工评分 |
| Wiki | Path Recall | `命中的 Gold 支持路径 / Gold 支持路径` | Link Precision、Version Consistency |
| 统一交付 | Citation Support | `被 Evidence 直接支持且带引用的可核验 Claim / 带引用的可核验 Claim` | Citation Completeness、Faithfulness、Scope Violation |

RRF 的结果按排名融合，因此不要比较 BM25 原始分数和向量相似度。一个常用写法是 `score(d) = Σ w_i / (k + rank_i(d))`，其中 `w_i` 是通道权重，`k` 控制头部排名差异。`k` 和权重只能在冻结 Gold 上选择，实验要同时报告候选 K、Rerank 开关、Evidence Token Budget 和超时，否则相同 Recall 也可能来自完全不同的成本。

`[行业参考]` BEIR 常用 nDCG@10、Recall@K 和 MRR 描述检索，RAGAS 与 ARES 将 Context、Faithfulness 和 Answer 分层，Microsoft GraphRAG 区分 Local、Global 等检索目标。它们支持“按层和场景评测”的方法，不证明 NoteWeave 当前受限图读取等于完整 GraphRAG。行业依据见[RAG 演进取舍外部依据](../research/RAG演进取舍外部依据.md)。

### 面试官问“Hybrid 和 Rerank 分别贡献多少”

使用同一 Gold 做消融：BM25、Vector、Hybrid、Hybrid 加 Rerank、Hybrid 加 Rerank 加 Evidence Selection。每组固定 Snapshot、Chunk Policy、Embedding、候选 K、生成模型和 Token Budget，报告 Recall@K、nDCG、Citation Support、P95 和单次成本的绝对值及配对差值。Rerank 提高 nDCG 却没有提高 Citation Support，说明候选排序变好但证据组合或生成仍有问题；Recall 上升而 P95 超出预算，则需要缩小候选或只对困难 Query 启用。

`[演练假设]` 可以准备一张理想数据卡说明计算方式，比如 `N=120` 条学校 Query，Hybrid 相比 BM25 的 Recall@10 提高 `9.2` 个百分点，引入 Rerank 后 nDCG@10 再提高 `6.1` 个百分点，Citation Support 提高 `4.8` 个百分点，P95 增加 `180 ms`。这些数值没有实验 Manifest 时不能写进简历，也不能与 BEIR 论文分数横比。

### 二阶追问：Recall 已经很高，为什么答案仍然错

Recall 只证明 Gold Evidence 出现在候选集合中。Rerank 可能把它排到 Token Budget 之外，Evidence Selection 可能被重复内容占满，Prompt 可能没有保留定位，模型也可能生成不受支持的 Claim。定位时依次看 Candidate、Ranked Candidate、Selected Evidence、Prompt Evidence 和最终 Citation，不用一个端到端分数猜根因。
