# 场景化 RAG 演进与技术取舍外部依据

> `[行业参考]` 本文只记录论文、官方文档和公开产品的评测口径。外部结果不能证明 NoteWeave 的质量，NoteWeave 的实现事实和实验结果必须回到源码、固定数据集和可重复命令。

## 1. 研究问题

本文回答六个问题：为什么保留 BM25，为什么做 Hybrid，为什么使用 RRF 和有限 Rerank，为什么分层评估检索与生成，为什么 Note/Wiki 需要不同指标，以及公司通常用什么数字宣传搜索或 RAG 产品。

## 2. 从检索基础到 RAG

### 2.1 BM25 是强基线，不是过时方案

Robertson 与 Zaragoza 的 BM25 综述解释了词频饱和、文档长度归一化和概率相关性框架。对错误码、课程号、实体名和版本号，词法匹配仍提供稳定且可解释的信号。

- [The Probabilistic Relevance Framework: BM25 and Beyond](https://www.staff.city.ac.uk/~sbrp622/papers/foundations_bm25_review.pdf)

BEIR 在多领域零样本检索上统一比较词法、稀疏、Dense 与 Rerank，使用 nDCG@10、Recall@K、Precision@K 和 MRR。其最重要的工程启示不是某个固定分数，而是 BM25 仍是必须保留的强基线，Dense 与 Rerank 的收益随数据集变化。

- [BEIR: A Heterogeneous Benchmark for Zero-shot Evaluation of Information Retrieval Models](https://arxiv.org/abs/2104.08663)

### 2.2 Dense Retrieval 解决语义改写，但不能替代过滤和版本

DPR 使用双塔分别编码 Query 与 Passage，并通过向量相似度检索候选。双塔适合离线预计算文档向量和在线 ANN，但 Query 与 Passage 在最终打分前缺少充分 token 级交互。

- [Dense Passage Retrieval for Open-Domain Question Answering](https://arxiv.org/abs/2004.04906)

HNSW 通过多层小世界图进行近似近邻搜索，以召回质量换查询速度和内存。ANN 指标必须同时报告 Recall 与 Latency/QPS，不能只报速度。

- [Efficient and Robust Approximate Nearest Neighbor Search Using Hierarchical Navigable Small World Graphs](https://arxiv.org/abs/1603.09320)

RAG 原始工作将非参数外部记忆与生成模型结合，说明知识可以通过 Retrieval 动态提供，而不是全部固化在模型参数中。它没有替业务系统解决 Workspace ACL、Source Snapshot、删除传播和 Citation Contract。

- [Retrieval-Augmented Generation for Knowledge-Intensive NLP Tasks](https://arxiv.org/abs/2005.11401)

## 3. 为什么 Hybrid 与 RRF 是合理中间态

### 3.1 RRF 的依据

Cormack、Clarke 与 Buettcher 提出的 Reciprocal Rank Fusion 使用不同排序器中的名次倒数进行融合。它避免先把异构原始分数校准到同一尺度，适合召回器分数分布不稳定或标签较少的阶段。

- [Reciprocal Rank Fusion Outperforms Condorcet and Individual Rank Learning Methods](https://dl.acm.org/doi/10.1145/1571941.1572114)

RRF 的局限也很明确：只看名次，不利用原始分差和 Query/Document 的交互。若后续有稳定标签、线上行为特征和漂移治理，可以比较归一化融合或 Learning to Rank。

### 3.2 官方搜索产品怎样组织 Hybrid

Elasticsearch 官方 Hybrid Search 文档将 lexical 与 vector retrieval 组合，并推荐 RRF 或线性融合。OpenSearch 把 keyword、neural query 和 normalization/rank fusion 放入 Search Pipeline。两者的共同点是先分别产生候选，再融合，而不是把向量分数假装成 BM25 分数。

- [Elasticsearch Hybrid Search](https://www.elastic.co/docs/solutions/search/hybrid-search)
- [Elasticsearch RRF Retriever](https://www.elastic.co/guide/en/elasticsearch/reference/current/rrf.html)
- [OpenSearch Hybrid Search](https://docs.opensearch.org/docs/latest/vector-search/ai-search/hybrid-search/)

Weaviate 官方文档把 keyword、vector、hybrid、filter 和 rerank 分成不同概念，并说明过滤可以在搜索阶段缩小候选。多租户文档进一步将租户隔离建模为数据组织能力，而非相关性分数。

- [Weaviate Search](https://docs.weaviate.io/weaviate/concepts/search)
- [Weaviate Filters](https://docs.weaviate.io/weaviate/search/filters)
- [Weaviate Multi-tenancy](https://docs.weaviate.io/weaviate/manage-collections/multi-tenancy)

对 NoteWeave 的结论是：Hybrid 能解释相关性组合，不能替代 Workspace Filter、Current Snapshot 和命中后的 Ownership 校验。

## 4. 为什么 Rerank 放在有限候选后

Cross-Encoder 让 Query 与 Document 共同进入 Transformer，通常比独立编码的 Bi-Encoder 有更充分的交互，但不能像文档向量一样大规模预计算。Sentence Transformers 官方文档将常见流程描述为 Retrieve and Re-Rank：先用高效检索取较大候选，再用 Cross-Encoder 重排小集合。

- [Sentence Transformers Retrieve & Re-Rank](https://www.sbert.net/examples/applications/retrieve_rerank/README.html)
- [Passage Re-ranking with BERT](https://arxiv.org/abs/1901.04085)

厂商 Rerank 页面常突出质量、支持多语言、上下文长度、吞吐或易集成。对项目真正可宣传的不是“接入某模型”，而是：

```text
nDCG/MRR Lift
  + Retrieval/Answer P95 Delta
  + Cost per Query
  + Provider Error/Fallback Rate
```

候选未被第一阶段召回时，Rerank 无法补回。因此 Recall 与 Ranking 指标必须分开。

## 5. Chunk、长上下文和集合选择

“Lost in the Middle”显示长上下文模型对位于输入中部的信息利用可能下降，说明把更多文本塞入 Prompt 不自动等于更好。Chunk 太小会丢上下文并增加索引项，太大会降低定位精度并增加 Token 成本。

- [Lost in the Middle: How Language Models Use Long Contexts](https://arxiv.org/abs/2307.03172)

LlamaIndex 将 Document 与 Node 分开，Node 可以继承元数据和关系；其 Ingestion Pipeline 使用 transformation 与 hash 处理缓存、去重和更新。这个思路支持 Source Snapshot 与可重建 Chunk/Projection，而不是把向量索引当原文真源。

- [LlamaIndex Documents and Nodes](https://docs.llamaindex.ai/en/stable/module_guides/loading/documents_and_nodes/)
- [LlamaIndex Ingestion Pipeline](https://docs.llamaindex.ai/en/stable/module_guides/loading/ingestion_pipeline/)

Haystack 把 indexing pipeline 与 query pipeline 分开：Document Store 保存文档，Retriever 只负责打分与 top-k。对 NoteWeave 的启示是资料接入与用户问答不能共享一个同步请求，检索器也不应承担全部 Workspace ACL。

- [Haystack Document Store](https://docs.haystack.deepset.ai/docs/document-store)
- [Haystack Retrievers](https://docs.haystack.deepset.ai/docs/retrievers)
- [Haystack Pipelines](https://docs.haystack.deepset.ai/docs/pipelines)

Note 场景没有统一的单一 Benchmark。可参考 Qasper、LongBench、NarrativeQA 等长文档任务，但内部系统更需要 Source Hit、Anchor Accuracy、Key Span Coverage、Window Continuity 和 Citation Support。

- [Qasper](https://allenai.org/data/qasper)
- [LongBench](https://arxiv.org/abs/2308.14508)
- [NarrativeQA](https://arxiv.org/abs/1712.07040)

## 6. Provenance、Citation 与多跳 Wiki

KILT 在固定 Wikipedia Snapshot 上同时评价下游任务和 Provenance。它直接支持“答案正确”和“证据来自哪里”要分别验证的观点。

- [KILT: a Benchmark for Knowledge Intensive Language Tasks](https://arxiv.org/abs/2009.02252)

HotpotQA 与 2WikiMultiHopQA 提供 supporting facts 或跨文档推理标注；HoVer 同时评价事实核验与支持文档检索。这些任务可作为 Wiki 关系与来源路径的外部参照，但不覆盖 NoteWeave 的页面编辑、ACL、版本和发布生命周期。

- [HotpotQA](https://arxiv.org/abs/1809.09600)
- [2WikiMultiHopQA](https://arxiv.org/abs/2011.01060)
- [HoVer](https://hover-nlp.github.io/)

Microsoft GraphRAG 的索引包含实体/关系抽取、Claim、社区检测、社区报告和 Embedding，并区分 Local、Global 和 DRIFT Search。只有页面链接和有限邻域读取的系统不应自称完整 GraphRAG。

- [Microsoft GraphRAG Indexing Architecture](https://microsoft.github.io/graphrag/index/architecture/)
- [Microsoft GraphRAG Query Overview](https://microsoft.github.io/graphrag/query/overview/)

## 7. RAG 评测为什么必须分层

RAGAS 将 RAG 评价拆成 Context、Faithfulness、Answer 等维度；ARES 使用 Context Relevance、Answer Faithfulness 和 Answer Relevance，并引入 Prediction-Powered Inference；LangSmith 官方教程将 Correctness、Relevance、Groundedness 和 Retrieval Relevance 分开。

- [RAGAS](https://aclanthology.org/2024.eacl-demo.16/)
- [ARES](https://github.com/stanford-futuredata/ARES)
- [LangSmith Evaluate a RAG Application](https://docs.langchain.com/langsmith/evaluate-rag-tutorial)

这些方法共同说明 LLM Judge 是代理评审，不是绝对真相。使用 Judge 时要固定模型与 Rubric，用人工样本校准，报告一致性和置信区间，并检查同模型家族偏好。

TREC RAG 把检索证据与长答案置于同一评测轨道，适合端到端分析；FRAMES 强调需要整合多篇文档的事实、多跳和推理问题。内部资料系统可以借鉴任务结构，但不能把内部小样本与公开 Leaderboard 直接横比。

- [TREC RAG Track](https://trec-rag.github.io/)
- [FRAMES Dataset](https://huggingface.co/datasets/google/frames-benchmark)

## 8. 公司和类似产品怎样宣传数字

公开搜索、向量库和 Rerank 产品通常使用以下四组数字：

| 宣传维度 | 常见数字 | 阅读时必须检查 |
|---|---|---|
| 质量 | Recall@K、nDCG@10、MRR、任务准确率 | 数据集、过滤条件、基线、模型和 K |
| 性能 | P50/P95/P99、QPS、索引吞吐 | 硬件、并发、向量维度、数据规模和缓存 |
| 成本 | 每百万向量、每千次 Query、每页/每 Token | 区域、存储、副本、重建和隐藏 Provider 成本 |
| 可靠性 | SLA、可用性、恢复或索引延迟 | 测量窗口、排除项、是否为合同承诺 |

Pinecone、Weaviate、Qdrant 等向量产品的 Benchmark 会比较 Recall/Latency/QPS，但通常在特定数据集、维度、硬件和索引参数下运行；Elastic/OpenSearch 更常宣传 Hybrid、过滤、聚合和运维能力；Cohere 等 Rerank Provider 更常宣传相对基线的排序质量。NoteWeave 应复用指标结构，不复用外部成绩。

推荐的项目宣传格式：

```text
在固定 Dataset/Snapshot/Model/Index/Strategy 上，
Hybrid 相比 BM25 的 Recall@5 绝对提升 X 个百分点，
Rerank 的 nDCG@10 绝对提升 Y，Citation Support 为 Z%，
Scope Violation 为 0/M，Retrieval P95 为 P ms，
单位 Supported Answer 成本为 C。
```

## 9. 外部水平能否直接判断

不能用内部学校数据集分数判断“达到 BEIR、KILT 或某厂商水平”，因为 Query 分布、语料、标注、模型和硬件都不同。可使用三层证据：

1. `[内部可复现]` 在学校 Gold 上证明业务适配和新旧 Delta。
2. `[公开可比]` 按公开数据集协议运行相同指标，报告完整配置。
3. `[生产待验证]` 在真实流量上报告分桶质量、P95、降级率和成本。

只有第二层可以谈公开 Benchmark 排名，只有第三层可以谈真实用户与 SLO。第一层最适合简历讲架构价值，但措辞应是“在内部锁定 Gold 上”，不能省略限定词。

## 10. 对 NoteWeave 的具体结论

1. 保留 BM25 是合理的，因为精确术语、数字和标识符是学校资料高频 Query。
2. Hybrid 的价值要通过相同数据和模型下相对 BM25/Dense 的绝对 Delta 证明。
3. RRF 适合标签少、异构分数难校准的阶段；有足够标签后再比较 LTR。
4. Rerank 主要应评价 nDCG/MRR Lift、P95 与成本，不应默认宣传 Recall Lift。
5. QA、Note、Wiki 需要不同中间指标，最终都要回到 Citation Support、Scope 和版本正确性。
6. 当前受限 Wiki 关系读取不能宣传为完整 GraphRAG。
7. 小 Fixture 只保护契约，正式宣传至少需要锁定测试、失败样本、置信区间和真实 Provider。
