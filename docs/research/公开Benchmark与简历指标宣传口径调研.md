# 公开 Benchmark 与简历指标宣传口径调研

> `[行业参考]` 公开 Benchmark 只用于建立评测方法与宣传边界，不能作为 NoteWeave 成绩。项目数字必须来自带 Manifest 的 `[已测-模拟]` 或真实生产窗口。

> 目标：根据论文、官方 Benchmark 和开源项目的公开宣传方式，反推 NoteWeave 的 Research Agent、QA RAG、Note/Wiki、资料接入和 Agent Runtime 应该采用哪些指标。本文不把外部项目的成绩移植到 NoteWeave，也不使用 NoteWeave 的 Git 历史，只讨论公开评测口径和简历表达边界。

## 1. 先给结论

公开项目通常不会把所有指标都放进宣传页，而是采用“一个主指标，两个护栏指标”的结构：

```text
主任务质量
  + 证据或安全护栏
  + 延迟、成本或吞吐
```

因此，NoteWeave 的简历不需要测试几十个指标。第一轮只需要建立四组结果：

1. Research Agent：报告质量或 Claim/Citation 质量。
2. QA RAG：Recall@K 或 nDCG@K，加 Citation Support 或 Faithfulness。
3. Note/Wiki：长文档窗口命中，或页面版本和来源回链正确性。
4. 资料接入：Upload 到 Projection Ready 的 P95，或者解析后下游问答 Recall 的变化。

工程可靠性可以作为一项护栏指标加入，例如投影重试后的最终收敛率或 Checkpoint 恢复成功率。不要为了简历额外测 QPS、CPU、连接池等与项目亮点没有直接关系的指标。

## 2. Research Agent 赛道

### 2.1 DeepResearchBench：长报告质量和引用可信度

[DeepResearchBench 论文](https://deepresearch-bench.github.io/static/papers/deepresearch-bench.pdf)把 Deep Research 评估拆为两个框架：

- **RACE**：评估报告生成质量，包含 Overall、Comprehensiveness、Depth、Instruction Following、Readability 等维度。它使用参考报告和动态评价标准，并用 Pairwise Agreement、Pearson Correlation 等指标验证自动评审与专家判断的一致性。
- **FACT**：评估检索和引用可信度。核心指标是 Citation Accuracy，以及每个任务的 Average Effective Citations。Citation Accuracy 按 Statement-URL 对是否真正被来源支持来计算，而不是只检查报告中有没有链接。

这类论文的宣传方式通常是：

```text
报告质量总分
  + 引用准确率
  + 每个任务的有效引用数
  + 与人工专家评分的一致性
```

对 NoteWeave 最有价值的是 FACT 的思想。简历中应该把“字段覆盖率”和“Citation Support”放在一起，因为只完成字段不代表结论有证据，只增加引用数量也不代表引用支持 Claim。

### 2.2 BrowseComp：搜索难度和事实命中

[OpenAI BrowseComp](https://openai.com/index/browsecomp/)包含 1,266 个需要在互联网上寻找隐蔽、纠缠事实的问题，答案刻意设计成短且容易自动核验。它主要报告 Accuracy，并分析单次尝试、Majority Voting、Best-of-N 和多次搜索带来的变化。

BrowseComp 的特点是“难找但易验”。它适合验证 Research Agent 的搜索坚持、查询改写、跨页面拼接和最终事实命中，但不适合证明长报告写得好，也不直接评价引用完整性。

适合 NoteWeave 的映射：

| BrowseComp 能力 | NoteWeave 指标 |
|---|---|
| 找到隐藏事实 | Final Answer Accuracy |
| 多页面拼接 | Evidence Coverage |
| 查询改写和继续搜索 | Search Success under Budget |
| 多次尝试 | Best-of-N 或重试后的成功率 |

### 2.3 GAIA 与 AssistantBench：通用工具代理

[GAIA 论文](https://arxiv.org/abs/2311.12983)包含 466 个由人工设计的问题，覆盖推理、多模态、网页浏览、文件处理和工具使用，答案通常是字符串、数字或列表，因此使用准 Exact Match 自动评分，并按照 Level 1、Level 2、Level 3 体现工具链复杂度。

[AssistantBench](https://aclanthology.org/2024.emnlp-main.505/)包含 214 个现实且耗时的网页任务，强调多网站、长步骤和自动评估。论文同时指出，闭卷模型可能有较高表面准确率，但精度较低，容易产生事实幻觉；网页 Agent 在真实长任务上仍然很难。

这两个 Benchmark 更像“Agent 能不能完成任务”的测试，不是 Deep Research 报告质量测试。它们适合验证 NoteWeave 的 Tool、MCP、任务编排和长任务恢复，但如果简历写的是研究报告质量，优先级低于 DeepResearchBench 和内部 Research Gold Set。

### 2.4 Research Agent 的简历口径

最适合放在简历里的指标顺序是：

1. **Citation Support 或 Citation Accuracy**：结论是否由引用真正支持。
2. **Required WorkItem Completion**：不同研究类型的必填工作单元是否通过各自验收；比较型任务另报 Matrix Cell Coverage。
3. **高影响字段验证率**：关键结论是否经过验证。
4. **Checkpoint 恢复成功率**：中断后是否继续得到同一个 Canonical State。

第一轮只选前两项即可。DeepResearchBench 的 RACE 分数不建议直接照搬，因为它依赖参考报告、Judge LLM、任务语言和评价配置；如果要使用，简历必须注明数据集版本、Judge 模型和任务数量。

## 3. QA RAG 赛道

### 3.1 BEIR：检索器的通用主 Benchmark

[BEIR 论文](https://arxiv.org/abs/2104.08663)使用多个领域和任务评估零样本检索，覆盖词法、稀疏、密集和重排模型。论文报告 nDCG@10、Recall@K、Precision@K、MRR 等排名指标，并特别强调 BM25 是强基线，Reranker 通常能提升效果但会增加计算成本。

BEIR 适合回答“检索器泛化得怎么样”，不适合单独回答“最终回答是否忠实”。因此，简历中使用 BEIR 或其子集时，应报告：

```text
Recall@5 / nDCG@10
  + BM25 基线与 Hybrid/Rerank 的绝对 Delta
  + P95 延迟或单位请求成本
```

### 3.2 MTEB：Embedding 模型能力，不是完整 RAG 结果

[MTEB 官方文档](https://docs.mteb.org/)将 Embedding 评估拆成 Retrieval、Classification、Clustering、Semantic Similarity 和 Pair Classification 等任务，当前覆盖大量语言、领域和任务。

MTEB 适合比较 Embedding 模型，不能直接证明 NoteWeave 的 QA RAG 效果，因为真实结果还受 Chunk、Query Rewrite、Workspace Filter、RRF、Rerank、Evidence Selection 和生成模型影响。简历中不要把 MTEB 的模型分数写成“我们的 RAG 准确率”。

### 3.3 KILT：回答结果和 Provenance 一起评估

[KILT 论文](https://arxiv.org/abs/2009.02252)把多个知识密集型任务放到同一个 Wikipedia Snapshot 上，包括开放域问答、事实核验、实体链接、槽位填充和对话。KILT 同时评估下游答案效果和 Provenance，强调模型不仅要答对，还要指出支持答案的知识片段。

KILT 对 NoteWeave 的启发最直接：

- QA 不能只计算 Answer EM/F1。
- 还要检查 Evidence Recall、Citation Support 和 Source Version。
- Wiki 页面和资料引用都应该能回到稳定的 Source 或 Snapshot。

### 3.4 RAGAS、ARES、LangSmith：工程团队常用的评估层

[RAGAS 论文](https://aclanthology.org/2024.eacl-demo.16.pdf)关注没有完整参考答案时的 RAG 评估，拆分检索相关性、上下文利用和生成质量。[ARES 官方仓库](https://github.com/stanford-futuredata/ARES)将 Context Relevance、Answer Faithfulness 和 Answer Relevance 作为核心维度，并使用合成数据、分类器和 Prediction-Powered Inference 估计置信区间。[LangSmith RAG 评估文档](https://docs.langchain.com/langsmith/evaluate-rag-tutorial)则把评估分成四个关系：

1. Response 与 Reference 的 Correctness。
2. Response 与 Query 的 Relevance。
3. Response 与 Retrieved Context 的 Groundedness。
4. Retrieved Documents 与 Query 的 Retrieval Relevance。

这些工具的共同宣传方式是“分层评估”，不是只报一个最终满意度。NoteWeave 的 Citation Completeness、Citation Support、Evidence Precision 与 Recall@K 正好可以映射到这套结构；旧评测字段 `Citation Coverage` 只兼容映射到 Completeness，不能代替语义支持率。

### 3.5 TREC RAG 与 FRAMES：复杂、多跳、长答案

[TREC RAG Track](https://trec-rag.github.io/)要求系统从文档集合中检索证据，并生成有证据支撑的摘要答案。它将证据检索和长答案评审放在同一个任务中，适合评估从 Retrieval 到 Generation 的完整链路。

[FRAMES 数据集](https://huggingface.co/datasets/google/frames-benchmark)包含需要整合多篇 Wikipedia 文档的多跳问题，关注 Factuality、Retrieval Accuracy 和 Reasoning。FRAMES 比普通单跳 QA 更接近需要跨资料关联的 Knowledge Work 场景。

对于 NoteWeave，QA RAG 第一轮不需要同时跑 BEIR、KILT、FRAMES 和 TREC。建议：

- Retriever 选 BEIR 子集或内部 Gold，报告 Recall@5 和 nDCG@10。
- End-to-end 选内部 Gold 或 FRAMES 小子集，报告 Citation Support 和 Faithfulness。
- 只保留一项延迟指标，例如回答 P95 或 Projection Lag。

## 4. Note 与 Wiki 赛道

### 4.1 Note 没有统一的单一 Benchmark

Note 场景同时包含长文阅读、定位、上下文窗口、引用和继续编辑，公开数据集通常只覆盖其中一部分。因此不能声称存在一个“Note Benchmark”。论文通常按能力选择数据集：

| Note 能力 | 公开数据集 | 常见指标 |
|---|---|---|
| 长文档理解 | [NarrativeQA](https://arxiv.org/abs/1712.07040)、[Qasper](https://allenai.org/data/qasper)、[LongBench](https://arxiv.org/abs/2308.14508) | EM、F1、ROUGE-L、任务分数 |
| 多文档和多跳推理 | [HotpotQA](https://arxiv.org/abs/1809.09600)、[2WikiMultiHopQA](https://arxiv.org/abs/2011.01060) | Answer EM/F1、Supporting Fact EM/F1 |
| 长期记忆和多轮历史 | [LongMemEval](https://arxiv.org/abs/2410.10813) | QA Accuracy、时间推理、更新、拒答 |

NoteWeave 的 Note 链路更适合自建一个小型任务集，记录每个问题的 Source、Anchor、Reading Window 和正确引用。简历指标可以选：

- Source Hit@K：有没有找到正确资料。
- Reading Window Continuity：窗口是否保留关键上下文。
- Citation Support：生成结论是否被原文支持。

Note 不建议直接用 NarrativeQA 的分数宣传“Note 检索准确率”，因为 NarrativeQA 的文体、文档和问题分布与个人研究资料不同。公开数据集更适合作为长文档能力的外部参照。

### 4.2 Wiki 的公开 Benchmark 更接近 KILT、HoVer 和多跳 QA

[HoVer 官方页面](https://hover-nlp.github.io/)要求系统从 Wikipedia 检索支持事实并判断 Claim，报告 Fact Extraction 的 Exact Match、F1、Label Accuracy，以及同时检索所有支持文档并预测正确标签的 HoVer Score。

[HotpotQA](https://arxiv.org/abs/1809.09600)提供句子级 supporting facts，[WikiHop](https://qangaroo.cs.ucl.ac.uk/)和 [2WikiMultiHopQA](https://arxiv.org/abs/2011.01060)强调跨文档关系和可解释推理路径。

这些 Benchmark 适合 Wiki 的“关系扩展和来源回链”能力，但不覆盖页面编辑、版本、权限和发布生命周期。因此 Wiki 的简历指标应分成两类：

1. **知识质量**：页面内容或关系扩展的 Answer F1、Supporting Evidence F1。
2. **工程正确性**：Page Version Correctness、Source Backlink Coverage、旧版本投影拒绝率。

NoteWeave 更应优先报告第二类，因为这是当前系统区别于普通 Wikipedia QA 的工程亮点。

## 5. 文件上传和资料接入赛道

### 5.1 文档解析项目如何宣传

文档解析项目通常不会只写“支持多少格式”，而是展示结构保真和下游可用性：

- [DocLayNet](https://arxiv.org/abs/2206.01062)使用 80,863 页、11 类版面标注，报告 layout detection 的 mAP，并指出模型与人工一致性之间的差距。
- [DocBank](https://aclanthology.org/2020.coling-main.82/)使用 500K 页 token-level 标注，评估细粒度文档布局。
- [DocVQA](https://www.docvqa.org/)从“解析后能否回答用户问题”的角度组织文档视觉问答。
- [ParseBench](https://www.parsebench.ai/)使用 2,000 多个人工验证页面和 5 个维度，拆分 Tables、Charts、Content、Semantic Formatting、Grounding，并单独展示 Cost per Page。
- [Unstructured 的解析对比](https://unstructured.io/benchmarks)使用真实企业页面，对 Content Fidelity、Hallucination Control、Structural Understanding 和 Table Extraction 进行横向比较。

这说明资料接入有两种不同的宣传口径：

```text
解析质量：表格、图表、顺序、结构、Grounding、下游 QA
系统工程：吞吐、P95、失败重试、投影延迟、成本
```

### 5.2 NoteWeave 文件上传最适合的指标

文件上传本身没有一个可以直接代表整个异步资料底座的公开总分。ParseBench、DocLayNet 和 DocVQA 只能评价解析或下游理解，不能替代 Outbox、Kafka、MinIO、Elasticsearch 的可靠性验证。

因此建议只选两项：

1. **Upload Accepted 到 Projection Ready 的 P95**：描述用户从上传到真正可检索的等待时间。
2. **投影重试后的最终收敛率**：描述 Outbox、幂等消费、重试和补偿是否最终得到正确索引。

如果要证明解析质量，再增加一个小型下游指标，例如“解析后 QA Recall@5”，不要同时测 OCR 字符准确率、布局 mAP、Chunk 数、Embedding 成功率、Kafka Lag 和多个吞吐指标。

`128 MB 单文件`可以作为能力边界写入技术描述，但它不是性能指标。线程池大小、数据库连接数、Chunk size 和 Worker 并发也只能作为实现参数，不能直接转换为 QPS。

## 6. 各赛道的公开宣传模板

### 6.1 论文模板

```text
明确任务定义
  → 公开数据集或可复现测试集
  → 强基线和消融实验
  → 主质量指标
  → 安全、事实或证据护栏
  → 延迟、成本或资源开销
```

### 6.2 开源项目和商业项目模板

开源项目通常在 README、Leaderboard 或技术报告中突出一个 Overall Score，再展开维度明细。ParseBench 展示 Overall、Tables、Charts、Content、Semantic Formatting、Grounding 和 Cost per Page；ARES、RAGAS、LangSmith 则强调可以把质量拆到 Retriever、Context、Answer 和 Judge 层。

项目宣传页的数字必须绑定以下条件：

- 数据集版本和样本量。
- Model、Embedding、Reranker 和 Judge 版本。
- Prompt、TopK、Chunk 和检索策略。
- 单次运行还是多次平均。
- 质量提升是否相对同一 Baseline。
- 成本、延迟和错误率是否同时变化。

## 7. 对 NoteWeave 简历的最终建议

### 7.1 第一版简历只保留四个数字

1. Research：字段覆盖率，或 Citation Support。
2. QA RAG：Recall@5 或 nDCG@10。
3. 文件接入：Upload 到 Projection Ready 的 P95。
4. 可靠性：投影最终收敛率，或 Checkpoint 恢复成功率。

Note 和 Wiki 先在简历中写清“Source、Window、Page Version、Source Backlink”的设计亮点，等内部样本达到足够数量后再补数值。这样比把 6 条 Fixture 的 100% 写成主战绩更稳妥。

### 7.2 简历句式

```text
构建验证驱动的 Deep Research Agent，按字段覆盖、Citation Support 和断点恢复定义研究完成边界，在内部 Research Gold Set 上达到 X% 的字段覆盖率和 Y% 的引用支持率。

构建 BM25、Vector、RRF 与 Rerank 组成的场景化 RAG，在固定 Gold Set 上 Recall@5 达到 X，Citation Support 达到 Y%，并保持 Scope Violation 为 0。

搭建从文件上传、解析、切片、向量化到检索投影的异步资料链路，在工作负载 W 下将 Upload 到 Projection Ready 的 P95 控制在 X 秒，投影重试后的最终收敛率达到 Y%。
```

其中 `X`、`Y` 只能填入已经固定数据集、基线、模型和运行命令后得到的结果。公开 Benchmark 的名称可以作为评测参考，但不能把外部项目在 BrowseComp、BEIR、ParseBench 或 LongMemEval 上的成绩写成 NoteWeave 成绩。
