# 场景化 RAG 真实案例、消融实验与 Ownership 答辩

> 默认主回答见[场景化 RAG 一体化面试手册](31-场景化RAG一体化面试手册.md)。本文只负责 QA、Note、Wiki 的学校案例、Gold、消融、行业指标对照和 Ownership。理想数据卡用于演练计算方式，只有生成 Manifest 和原始结果后才能进入简历。

> 这篇文档把架构落到学校资料场景和面试数字。它允许构造理想案例，但绝不把理想值说成已经发生的生产结果。

## 1. 证据标签

| 标签 | 含义 | 可以怎样使用 |
|---|---|---|
| `[当前实现]` | 源码、配置、迁移或自动化测试可证明 | 可以直接讲机制和固定参数 |
| `[已测-模拟]` | 固定 Fixture 或 Mock Provider 可复现 | 必须同时说样本、环境和限制 |
| `[演练案例]` | 根据学校业务设计的完整故事 | 用于回答流程和取舍，不冒充真实用户事件 |
| `[理想消融数据]` | 预先设计的合理目标结果 | 只能讲实验设计，实测前不写进简历 |
| `[生产待验证]` | 需要真实 Provider、用户或持续流量 | 作为上线门禁或补数清单 |
| `[行业参考]` | 论文、官方文档或同类产品口径 | 只能比较评测方法，不能移植分数 |

## 2. Ownership：一个人类开发者加 AI 辅助

### 2.1 “这些是不是 AI 写的”

建议直接回答：

> 项目只有我一个人类开发者，AI 参与代码与文档生成。我负责需求抽象、架构边界、方案比较、Prompt 和数据契约、代码审查、测试设计、故障注入、指标口径和最终验收。比如选择 RRF 不是让 AI 随机挑技术，而是因为 BM25 与向量分数难直接校准、当前又没有足够 LTR 标签；AI 给出的代码只有通过稳定 ID、Provider 畸形响应、Scope、Snapshot 和回放测试后才被接受。

### 2.2 面试官如何验证你真的掌握

我应当能现场完成四件事：

1. 手算一个两路加权 RRF 排名。
2. 从一个 Bad Case 判断错误在 Recall、Rerank、Selection 还是 Generation。
3. 解释为什么 ES 命中后还要回 MySQL 做 Ownership Hydration。
4. 设计一个只改变单一变量的消融，并说清分母、置信区间和停止条件。

如果只能背“Hybrid 提升准确率”，不能回答这些问题，就不能证明 Ownership。

## 3. 三个学校场景的完整演练

### 3.1 QA：课程补交规则

`[演练案例]` 数据范围包含《软件工程课程大纲 2026 版》《实验提交规范》和一份已废止的 2025 版规则。学生问：“实验三晚交两天扣多少分，校医院证明能豁免吗？”

正确链路：

1. Conversation Context 保留“实验三”和当前课程 Workspace，不扩大到全校资料。
2. Query Rewrite 将指代补全，但保留“两天”“校医院证明”等 Must Terms。
3. BM25 召回“每迟交 24 小时扣 10%”和“校医院证明”原句，Dense 补到“不可抗力材料可申请豁免”的同义表述。
4. RRF 合并通道，Rerank 将已废止版本和只谈普通请假的段落降权。
5. Ownership Hydration 拒绝旧 Snapshot 或越权课程副本。
6. Evidence Selection 保留大纲与提交规范两个独立来源，而不是六个相邻 Chunk。
7. 生成层回答“两天按规则扣 20%；医疗证明是否豁免需要按申请条款审核”，每个结论回链到当前 Snapshot。

V0 Bad Case：只用向量时，语义相似的 2025 版“每 24 小时扣 5%”可能排在前面；只用 BM25 时，“不可抗力材料”与“医疗证明”的同义关系可能漏掉。这里 Hybrid 的价值是错误互补，不是向量必然优于关键词。

### 3.2 Note：围绕长 PDF 精读

`[演练案例]` 教师上传 86 页《数据库事务与恢复》讲义，用户要求：“基于这份讲义继续整理 MVCC 如何避免读写阻塞，保留和 Undo Log、Read View 相邻的解释。”

正确链路：

1. Source Recall 先锁定指定讲义，而不是让全库相似 Chunk 竞争。
2. Anchor Recall 找到 Read View 定义所在窗口。
3. `NoteReadingPlanner` 把锚点标为 Primary Window，优先补相邻 Chunk/Window 作为 Continuation。
4. Rerank 只处理最多 24 个规划候选，返回最多 8 个窗口；Provider 失败时保留启发式顺序并标记 `NOTE_WINDOW_RERANK_UNAVAILABLE`。
5. 最终笔记区分讲义原文、用户已有笔记和模型补充，引用回到页码/章节位置。

V0 Bad Case：全库 Chunk TopK 能找到 MVCC、Undo Log、Read View 三个词，却可能来自三份不同资料，顺序互不连续，无法解释讲义作者的论证链。Note 的主要指标因此不是普通 Recall@K，而是 Source Hit、Anchor Accuracy 和 Window Continuity。

### 3.3 Wiki：课程知识页关系浏览

`[演练案例]` 系统已有“MVCC”“Undo Log”“Read View”“隔离级别”知识页。学生从 MVCC 页追问“它与可重复读和幻读是什么关系”。

正确链路：

1. 只读取当前 Page Version。
2. 文本相关性找到 MVCC 与隔离级别页面作为种子。
3. 在 Hop、Node、Edge、Character Budget 内读取出链、反链和 Citation。
4. 关系只扩候选，最终事实仍回到讲义 Source Snapshot。
5. 页面没有可靠 Source Backlink 时降低引用等级，不让 Wiki 生成内容自证。

V0 Bad Case：普通 Chunk RAG 可能答对概念，却无法解释用了哪个页面版本，也无法区分页面间链接与事实证据。当前实现是受限图读取，不是实体图、社区摘要和 Global Search 意义上的完整 GraphRAG。

## 4. 当前真实数据能证明什么

`[已测-模拟]` `qa-note-ablation-report-v1.json` 中 QA 和 Note 各有 3 条固定 Case：单路为 33.3% 或 66.7%，组合变体为 100%，Scope Violation 为 0；延迟是微秒级确定性 Fixture Replay。它只能证明：

- 报告 Schema、Variant 名称和指标计算不会静默漂移；
- 固定候选下 Scope 和 Citation Contract 可回归；
- 回放与门禁机制能执行。

它不能证明：

- 在线 ES、Embedding、Rerank 或 LLM 的准确率；
- Hybrid 在真实问题上必然提升 33.3 或 66.7 个百分点；
- 用户满意度、节省时间或生产 P95；
- Wiki 的真实关系检索效果。

因此简历中禁止写“RAG 准确率 100%”。

## 5. 正式 Gold Set 怎样构造

### 5.1 数据集结构

`[目标设计]` 第一版人工数据集建议 150 条，其中 30 条用于开发和阈值校准，120 条锁定测试。锁定测试分布：

| 场景 | 数量 | 必含样本 |
|---|---:|---|
| QA | 60 | 精确规则、同义改写、中英混合、版本冲突、跨资料、无答案、越权诱导 |
| Note | 36 | 长 PDF、指定 Source、多主题同名资料、相邻窗口、跨章节、无有效 Anchor |
| Wiki | 24 | 当前版本、旧版本、出链、反链、无回链、关系噪声、预算截断 |

开发集可以反复查看；锁定测试集在策略冻结后只运行，不能用测试结果继续调 RRF Weight、阈值或 Prompt。资料按 Source 或主题分组切分，近重复段落不得跨开发与测试集。

### 5.2 标注单元

每条 Case 至少保存：

```text
case_id, mode, workspace_id, query_type, query,
allowed_source_ids, snapshot_ids, relevant_evidence_ids,
direct_support_claims, expected_citation_ids, should_refuse,
source_anchor, acceptable_windows, page_version,
annotation_status, annotator_ids, disagreement_reason
```

不能只标一个参考答案。Retriever 需要多相关 Evidence 标签，Note 需要可接受的 Anchor/Window 范围，Wiki 需要版本和回链，拒答样本需要明确“资料中确实没有答案”。

### 5.3 标注一致性

抽取至少 30 条由两名标注者独立判断 Evidence 是否直接支持，使用 Cohen's Kappa：

```text
kappa = (p_o - p_e) / (1 - p_e)
```

`p_o` 是实际一致率，`p_e` 是按双方标签边际分布计算的偶然一致率。Kappa 低不应靠多数投票掩盖，而要修订“相关”“直接支持”“可接受窗口”的标注手册。一个人开发时可以请教师或同学做盲审；实在只有一名标注者，就必须如实写单人标注并抽样延迟复标，不能虚构双标。

### 5.4 防止数据泄漏

用 MinHash、SimHash 或 Embedding 近邻检查开发集与测试集近重复。定义：

```text
NearDuplicateLeakRate = 测试集中与开发集超过相似阈值的样本数 / 测试样本数
```

Query Rewrite、Prompt、Weight、Chunk 和 Selection Policy 都在测试前冻结。若测试集被用于调参，必须重新标记为开发集并创建新锁定集。

## 6. 三类指标与公式

### 6.1 QA

```text
Recall@K = |Relevant(q) intersect TopK(q)| / |Relevant(q)|

MRR = (1/N) * sum 1 / rank_first_relevant(q)

DCG@K = sum(i=1..K) (2^rel_i - 1) / log2(i + 1)
nDCG@K = DCG@K / IDCG@K

Citation Support = 被直接证据支持、且已经带引用的可核验 Claim 数 / 带引用的可核验 Claim 总数

Citation Completeness = 至少带一个有效引用的应引用 Claim 数 / 全部应引用 Claim 数

Evidence Precision = 直接支持至少一个 Claim 的 Evidence 数 / 最终 Evidence 数
```

Recall 回答“有没有找到”，nDCG/MRR 回答“是否排在前面”，Citation Support 回答“生成结论是否真的有证据”。三者不可互相替代。

### 6.2 Note

```text
Source Hit@K = 正确 Source 出现在 TopK 的 Case 数 / Note Case 数

Anchor Accuracy = Primary Window 落在可接受 Anchor 集的 Case 数 / Note Case 数

Window Continuity = 被选相邻窗口对数 / 需要上下文的被选窗口对数

Key Span Coverage = 被 Reading Windows 覆盖的必要原文 Span 数 / 必要 Span 总数
```

Window Continuity 不能单独越高越好。全取相邻窗口会得到 100% 连续性但可能与 Query 无关，所以必须同时看 Key Span Coverage、Evidence Precision 和字符预算。

### 6.3 Wiki

```text
Page Version Correctness = 命中当前正确版本的 Case 数 / Wiki Case 数

Source Backlink Coverage = 可回到 Source Snapshot 的最终 Wiki Evidence 数 / 最终 Wiki Evidence 数

Relation Expansion Precision = 扩展后与问题相关的页面数 / 扩展页面总数

Path Recall = 命中的标注必要关系边数 / 标注必要关系边总数
```

如果任务不需要关系扩展，Path Recall 不适用。不要为了让图指标好看强行走图。

### 6.4 安全、性能和成本

```text
Scope Violation Rate = 越权 Evidence 数 / 返回 Evidence 总数

Refusal Precision = 正确拒答数 / 所有拒答数
Refusal Recall = 正确拒答数 / 应拒答案例数
Refusal F1 = 2PR / (P + R)

P95 = 排序后第 ceil(0.95N) 个延迟样本

Cost per Supported Answer = 总 Provider 与模型成本 / Citation Support 达标答案数

Evidence Efficiency = 被支持 Claim 数 / 送入模型的 1000 字符数
```

Scope Violation 的目标必须为 0，但报告仍要给分母和攻击样本类型。“跑了 10 条为 0”和“跑了 10 万条为 0”证据强度不同。

## 7. 完整理想消融

以下全部是 `[理想消融数据]`，用于说明实验应长什么样，不是当前结果。假设锁定 QA 测试集 60 条、真实 Elasticsearch、固定 Embedding/Rerank/LLM、同一 Snapshot 和 Evidence Budget。

### 7.1 QA 架构消融

| Variant | Recall@5 | nDCG@10 | Citation Support | Refusal F1 | Retrieval P95 | 单次检索成本 |
|---|---:|---:|---:|---:|---:|---:|
| BM25 | 71.7% | 0.684 | 76.1% | 0.80 | 78 ms | 0.12 分 |
| Dense | 76.7% | 0.721 | 78.0% | 0.76 | 104 ms | 0.25 分 |
| Hybrid + RRF | 86.7% | 0.812 | 84.9% | 0.84 | 139 ms | 0.36 分 |
| Hybrid + RRF + Rerank | 86.7% | 0.873 | 89.2% | 0.86 | 231 ms | 0.71 分 |
| 上述 + Evidence Selection | 86.7% | 0.873 | 93.4% | 0.90 | 238 ms | 0.72 分 |

正确解释：Hybrid 相比 BM25 的理想 Recall@5 绝对提升 15.0 个百分点；Rerank 没提高 Recall，因为候选集合没变，但 nDCG 提升 0.061；Selection 主要改善 Citation Support 和拒答，不应归因成召回收益。

不能说“准确率提升 21%”。如果必须给相对提升，计算为 `(86.7%-71.7%)/71.7%=20.9%`，但简历优先写绝对百分点，避免歧义。

### 7.2 Note 消融

以下仍是 `[理想消融数据]`，锁定 Note 测试集 36 条：

| Variant | Source Hit@3 | Anchor Accuracy | Key Span Coverage | Window Continuity | Citation Support | P95 |
|---|---:|---:|---:|---:|---:|---:|
| 全库 Chunk TopK | 75.0% | 61.1% | 63.9% | 41.7% | 69.3% | 118 ms |
| Source Recall + Anchor | 91.7% | 77.8% | 77.8% | 47.2% | 80.1% | 146 ms |
| + Continuation Window | 91.7% | 83.3% | 88.9% | 86.1% | 88.4% | 162 ms |
| + 有限 Rerank | 91.7% | 88.9% | 91.7% | 86.1% | 91.0% | 246 ms |

这里最值得宣传的不是“Note 准确率”，而是“两阶段阅读相对全库 TopK 将 Anchor Accuracy 从 61.1% 提升到 83.3%，同时 Window Continuity 提升 44.4 个百分点”。实测前只能作为目标模板。

### 7.3 Wiki 消融

以下仍是 `[理想消融数据]`，锁定 Wiki 测试集 24 条：

| Variant | Page Version Correctness | Source Backlink Coverage | Relation Precision | Path Recall | Citation Support |
|---|---:|---:|---:|---:|---:|
| 普通 Chunk RAG | 79.2% | 66.7% | 不适用 | 不适用 | 72.1% |
| Current Page Only | 95.8% | 79.2% | 不适用 | 54.2% | 82.4% |
| + 受限关系扩展 | 95.8% | 87.5% | 78.6% | 83.3% | 90.2% |

只有需要多跳关系的 Query 才计算 Path Recall，分母必须是关系子集。Current Page Correctness 的收益来自版本过滤，不能说成图检索收益。

### 7.4 参数敏感性

`[目标设计]` 至少单独扫描：每路 Recall `{20, 40, 60, 100}`、RRF K `{10, 30, 60, 100}`、Vector Weight `{0.3, 0.5, 0.7}`、Rerank TopN `{10, 20, 40}`、Evidence Limit `{4, 6, 8}`。一次只改变一个参数，记录质量、P95 和成本曲线，最终选 Pareto 前沿而不是最高质量点。

## 8. 模型收益和架构收益怎样拆开

面试官会问：“是不是换一个更强模型就行？”使用二维实验：

| | 旧模型 | 新模型 |
|---|---:|---:|
| 旧架构 | Q00 | Q01 |
| 新架构 | Q10 | Q11 |

```text
Architecture Gain under old model = Q10 - Q00
Architecture Gain under new model = Q11 - Q01
Model Gain under old architecture = Q01 - Q00
Model Gain under new architecture = Q11 - Q10
Interaction = Q11 - Q10 - Q01 + Q00
```

Embedding、Rerank 和 Generation Model 要分别冻结，不要把三者同时换掉。若新架构只在新模型上有效，Interaction 很大，需要说明两者耦合；不能把全部收益归给自己设计。

## 9. 统计可信度

60 条 QA 仍是小样本。对二元指标使用 Wilson 区间，对 Recall/nDCG Delta 使用 Query 级 Bootstrap，对新旧系统使用配对检验而不是把两个总体平均值当独立样本。

`[目标设计]` 报告至少包含：

- 样本量、数据集版本和 Query 分桶；
- 均值与 95% 置信区间；
- 新旧策略的配对绝对 Delta；
- 失败 Case 列表，不只展示成功例；
- 模型、索引、Prompt、Judge 和策略版本；
- 重复运行方差与 Provider 异常数。

LLM Judge 要先与人工标签校准，并报告一致率和 Kappa。Judge 与被测 Answer 使用同一模型家族可能有自偏好，必须做人工盲审或更换 Judge 复核。

## 10. 学校业务价值怎样量化

`[演练案例]` 选 24 个真实课程资料任务，12 个使用传统关键词搜索/人工翻页，12 个使用 NoteWeave，交叉交换后再做一轮，记录：

```text
Time Saved Rate = (人工基线中位时长 - 系统辅助中位时长) / 人工基线中位时长

First-pass Acceptance = 首稿无需结构性重写的任务数 / 总任务数

Major Rewrite Rate = 需要重新查资料或改核心结论的任务数 / 总任务数

Verified Claim Throughput = 人工确认正确且有引用的 Claim 数 / 总分钟数
```

`[理想消融数据]` 假设人工中位 18 分钟、系统辅助 7 分钟，则节时率为 `(18-7)/18=61.1%`；24 个任务有 19 个首稿无需结构性重写，则采纳率为 `79.2%`。这些数字实测前不可写入简历。

节时不等于质量提升。必须同时报告 Major Rewrite、Citation Support 和任务难度；否则系统可能只是更快地产生错误答案。

## 11. 成本与容量手算

### 11.1 单位成功答案成本

假设 `[演练案例]` 1,000 次请求总成本 42 元，其中 860 次 Citation Support 达标：

```text
Cost per Supported Answer = 42 / 860 = 0.0488 元
```

如果省掉 Rerank 后成本降到 25 元，但达标答案只剩 700 次，则为 `0.0357 元`。是否保留 Rerank 要看质量门槛和边际收益，不是只看总账单。

### 11.2 Little 定律估并发

若峰值到达率为 8 Query/s，在线 Retrieval 平均停留 0.25 s：

```text
L = lambda * W = 8 * 0.25 = 2 个平均在途 Retrieval
```

容量不能只按平均值配置。还要考虑 P95、突发系数、Embedding/Rerank 连接池和重试放大。Provider 超时重试 3 次可能把压力近似放大到 3 倍，因此正式容量测试要区分正常流量与故障流量。

## 12. 与论文和公司宣传口径怎样比较

| 外部对象 | 对方常宣传什么 | 本项目可对齐什么 | 不能怎样说 |
|---|---|---|---|
| BEIR | nDCG@10、Recall、跨数据集泛化 | BM25/Hybrid/Rerank 的配对 Delta | 内部 Gold 分数达到 BEIR 水平 |
| KILT | Answer 与 Provenance | Citation Support、Snapshot 回链 | 有引用就等于答案正确 |
| RAGAS/ARES/LangSmith | Context、Groundedness、Answer 分层 | Retrieval、Evidence、Generation、Delivery 四层 | Judge 分数等于人工真相 |
| Elasticsearch/OpenSearch | Hybrid、RRF、过滤和可扩展搜索 | ES 上的词法、向量、过滤、Alias | 官方能力就是项目实测性能 |
| Weaviate/Pinecone | Recall、Latency、QPS、过滤、成本 | 在同一硬件与数据上的质量/延迟曲线 | 引用厂商 Benchmark 作为自己的成绩 |
| Cohere 等 Rerank Provider | 精排质量与易集成 | nDCG Lift、P95、每次成本、降级率 | 模型榜单分数等于端到端 RAG 效果 |
| Microsoft GraphRAG | Local/Global/DRIFT 和关系型检索 | Wiki Path Recall、关系精度、回链 | 外部方法参考；当前受限图读取不是完整 GraphRAG |

内部 Gold 优先证明业务适配，公开数据集证明外部可比性。两种结果要分栏，不能混成一个 Overall Score。

## 13. 简历表达

### 13.1 当前可以写

> 设计并实现 QA、Note、Wiki 三条场景化 RAG 链路：QA 采用 BM25/向量双路召回、加权 RRF、可降级 Rerank 与集合级 Evidence Selection；Note 采用 Source-Anchor-Reading Window 两阶段精读；Wiki 固化页面版本、受限关系预算与来源回链，并以 Workspace 查询过滤加 MySQL Ownership 校验阻断越权候选。

> 建立版本化 Retrieval Strategy、Snapshot/Projection 回填、Shadow Replay 与质量门禁；固定 Fixture 覆盖单路/融合、拒答、Scope、旧版本和 Provider 降级，明确将小样本回归结果与线上效果隔离。

### 13.2 完成正式实验后才可以写

> 在 `{Dataset Version}` 的 `{N}` 条锁定学校资料 Query 上，Hybrid + RRF 相比 BM25 将 Recall@5 从 `{A}` 提升至 `{B}`，Rerank 将 nDCG@10 提升 `{C}`，Citation Support 达 `{D}`，Scope Violation 为 `0/{M}`，Retrieval P95 为 `{P}` ms；模型、索引、Evidence Budget 和测试协议保持一致。

> 对 `{N}` 个长文精读任务，两阶段 Source/Anchor/Window 检索相比全库 Chunk TopK 将 Anchor Accuracy 提升 `{X}` 个百分点、Key Span Coverage 提升 `{Y}` 个百分点，并将单位有效 Evidence 成本控制在 `{Z}`。

### 13.3 禁止使用

- “RAG 准确率 100%”。
- “使用 GraphRAG 提升多跳推理”，当前没有完整 GraphRAG。
- “Rerank 提升 Recall”，除非候选定义确实在 Rerank 后变化且实验能证明。
- “支持百万文档毫秒检索”，没有对应规模、硬件、并发和统计窗口。
- “用户效率提升 60%”，理想演练不能冒充真实用户研究。

## 14. 高频压力追问

### 14.1 为什么不把三个 Retriever 合并

共享的是 Source、Snapshot、Scope、Provider、Evidence 和 Trace；差异是 Candidate、目标函数和失败语义。把它们合并会制造大量 Mode 分支，接口看似统一，策略却更难测试。若以后差异收敛，再下沉共同机制，不为了代码行数提前统一业务语义。

### 14.2 RRF 权重 0.7/0.3 是拍脑袋吗

当前是版本化默认基线，不声称最优。正式实验会在开发集扫描权重，在锁定集只验证一次，同时报告按 Query 类型的 Delta。面试官如果要求生产结论，我会明确说尚未完成真实 Provider 的参数敏感性实验。

### 14.3 为什么 Rerank 默认可关闭

它是外部 Provider，增加费用、延迟和可用性依赖。关闭时保留确定性 RRF 顺序并记录降级；Release Gate 可以要求正式环境必须有完整 Rerank 质量凭证。可运行与可发布是两个级别。

### 14.4 规则型 Relevance Policy 会不会误杀

会。当前 30% Query Term Coverage、最少匹配词和标识符规则是确定性安全网，不是语义真理。它对短中文、词形变化和跨语言可能误杀，因此需要按 Query 分桶测 False Positive/False Negative，并把 Provider Rerank 与规则准入的作用拆开。

### 14.5 为什么不是 MMR

当前 Selection 先每 Source 一条、再回填并受字符预算约束，属于更简单的确定性贪心。MMR 会显式在相关性与新颖性间打分，但需要选择相似度模型和 lambda。现有数据不足以证明 MMR 的额外复杂度有净收益，所以文档只把集合效用写成目标，不谎称代码已经实现 MMR。

### 14.6 Gold 是你自己标的，可信么

当前 Fixture 只是契约回归。正式数据需要锁定测试、主题级去重、外部盲审或抽样双标、Kappa、失败样本和置信区间。只有我单人标注时会公开这一限制，不把自标 Gold 说成客观行业 Benchmark。

### 14.7 强模型加长上下文会不会淘汰 RAG

单份短资料和低频离线任务可能直接长上下文更简单；多 Workspace、版本更新、精确权限、可引用回放和高频查询仍需要检索与证据治理。选择应由成本、质量和数据边界决定，而不是默认所有问题都 RAG。

### 14.8 最大设计风险是什么

第一是文档量和真实 Query 不足，系统复杂度可能超过学校场景需要；第二是自建评测容易偏向自己方案；第三是 Provider 和模型变化导致旧结论失效。应对方式是保留 BM25 基线、冻结版本、做配对消融，并允许简单场景退回单路检索。

## 15. 面试官二次审查与补缺

### 15.1 可信度缺口

| 追问 | 文档当前答案 | 仍不能声称什么 |
|---|---|---|
| 样本从哪里来 | 学校资料场景分桶，开发/锁定集分离 | 已有 150 条真实用户 Gold |
| 是否数据泄漏 | 主题切分、近重复检测、策略冻结 | 当前 Fixture 已完成泄漏审计 |
| 谁标注 | 目标方案要求盲审、Kappa | 已经有双人标注结果 |
| 模型还是架构收益 | 二维实验拆分 | 当前已测得 Architecture Gain |
| 结果是否稳定 | Bootstrap、配对检验、重复运行 | 现有 3 Case 有统计显著性 |

### 15.2 工程深度缺口

现有文档已覆盖召回、排序、证据、权限、版本、投影、降级和回放。下一轮代码或数据工作仍应补：

1. 真实 ES 加真实 Embedding/Rerank 的一键 Benchmark Receipt。
2. Query 分桶和 Hard Negative，尤其是旧版本、相似课程号和跨 Workspace 副本。
3. Cache Key 中 Workspace、Scope、Index、Strategy 和 Provider Version 的契约测试。
4. Projection Lag 与首次可检索时间的端到端测量。
5. Citation Claim 级人工标注，而不只检查 Citation ID 存在。
6. 真实文件类型对 Chunk/Window 的敏感性，包括 PDF 表格、扫描件和标题层级。

### 15.3 业务深度缺口

架构指标不能替代学校价值。要补人工检索时间、首稿采纳、大改率、教师复核错误和无法回答率；同时记录任务难度与资料长度，避免只挑容易问题。若没有真实用户数据，简历应把重点放在工程闭环和可复现实验，不虚构 DAU、满意度或节时率。

### 15.4 最终审计结论

从大厂面试官角度，这个亮点已经能回答“为什么拆链路、为什么 RRF、怎么降级、怎么防越权、怎么版本化、怎么评测、AI 辅助下你做了什么”。仍然最缺的是扩大后的人工 Gold、真实 Provider 配对消融和学校用户基线。这三项完成前，最强可用卖点是架构判断与验证体系，不是一个漂亮准确率。

## 16. 面试前自检

- 能否不看文档手算 RRF。
- 能否把 Recall、nDCG、Citation Support 和 Refusal F1 的分母说清。
- 能否明确当前 3+3 Fixture 的证据等级。
- 能否讲完 QA、Note、Wiki 各一个学校案例。
- 能否解释 Rerank 为什么改变排序而不必改变 Recall。
- 能否解释为何 ES 不是业务真源。
- 能否承认当前没有生产 SLO、成本和真实用户效果。
- 能否说明 AI 做了什么、自己为何对结果负责。
