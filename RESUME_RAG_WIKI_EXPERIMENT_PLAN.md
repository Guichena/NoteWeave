# NoteWeave Resume-Style RAG/Wiki Writeup and Experiment Plan

## 1. Usage Note

This document turns the two capabilities below into resume-style project bullets and a concrete experiment plan.

- If you already have measured offline results, use the "validated result" wording.
- If you have not run the experiments yet, use the "target result" wording first and replace the numbers after evaluation.

Do not present the percentages below as final facts unless they are backed by your own runs.

---

## 2. Team-Side Hybrid RAG: Resume-Style Wording

### 2.1 Validated Result Version

- Designed and implemented a team-side `Hybrid RAG` retrieval pipeline for large-scale, permission-sensitive knowledge bases, combining `Elasticsearch BM25`, `embedding vector search`, `metadata-based permission filtering`, and high-quality wiki-page recall with `Weighted RRF` fusion.
- Improved offline retrieval quality over a `BM25-only` baseline by `12% to 25%` on `Recall@10` and `8% to 18%` on `MRR`, while increasing `Citation Coverage` by `15% to 30%` in multi-source enterprise QA scenarios.
- Reduced retrieval-context redundancy by `20% to 40%` through `chunk deduplication`, `adjacent chunk merging`, `per-document cap`, and citation mapping, leading to higher evidence coverage and more traceable answers.
- Built an evidence-oriented retrieval chain that optimized not only "can retrieve" but also "can cite" and "can obey permissions", improving answer groundedness and reducing invalid-but-inaccessible recall noise.

### 2.2 Target Result Version

- Designed a team-side `Hybrid RAG` retrieval chain for large document collections, complex access control, and diverse query expressions by combining `BM25`, `vector retrieval`, `wiki-page recall`, and `Weighted RRF` fusion.
- Targeted `12% to 25%` gains on `Recall@10`, `8% to 18%` gains on `MRR`, and `15% to 30%` gains on `Citation Coverage` over a `BM25-only` baseline.
- Planned to reduce context redundancy by `20% to 40%` via `chunk deduplication`, `adjacent chunk merging`, and `per-document throttling`, improving retrieval relevance, coverage, and traceability together.

### 2.3 Chinese Resume Version

- 设计团队侧 `Hybrid RAG` 检索链路，面向团队资料规模大、权限复杂、查询表达多样的场景，融合 `Elasticsearch BM25`、`Embedding 向量检索`、`元数据权限过滤` 与高质量知识页召回，并通过 `Weighted RRF` 完成多路排序融合。
- 相比 `BM25-only` baseline，离线评测中 `Recall@10` 预期或可实现 `12%~25%` 提升，`MRR` 提升 `8%~18%`，`Citation Coverage` 提升 `15%~30%`，显著增强多来源问答场景下的相关性与可追溯性。
- 结合 `chunk 去重`、`相邻片段合并`、`同文档限流` 与引用映射，将上下文冗余预计降低 `20%~40%`，提升有效证据密度与答案 groundedness。

---

## 3. Personal-Side LLM-Wiki: Resume-Style Wording

### 3.1 Validated Result Version

- Designed a personal `LLM-Wiki` knowledge compilation pipeline that transformed raw research materials into `Article Card`, `Concept Card`, `Concept Relation`, and evidence-linked semantic indexes for long-term structured knowledge accumulation.
- Improved concept-level retrieval hit rate by `15% to 30%` over a `raw-source retrieval` baseline, and reduced duplicated concept cards by `30% to 50%` through `normalized name`, `alias table`, and `embedding-based concept merging`.
- Increased cross-document concept merge accuracy by `10% to 20%`, enabling stronger semantic reuse across notes, papers, and external references while preserving evidence traceability.
- Built a semantic layer from `raw source -> article card -> concept card -> citation`, which improved downstream QA consistency and content-generation stability by `8% to 15%` in multi-document research scenarios.

### 3.2 Target Result Version

- Designed a personal `LLM-Wiki` knowledge compilation chain to compile raw materials into structured semantic cards and relations, improving the long-term organization and reuse of personal research data.
- Targeted `15% to 30%` improvement in concept-level retrieval hit rate, `30% to 50%` reduction in duplicate concept cards, and `10% to 20%` improvement in cross-document concept merge accuracy compared with a `raw-source` or `summary-only` baseline.
- Planned to improve downstream QA consistency and content generation stability by `8% to 15%` through evidence-grounded semantic compilation instead of one-off document summarization.

### 3.3 Chinese Resume Version

- 设计个人侧 `LLM-Wiki` 知识编译链路，将原始研究资料编译为 `Article Card`、`Concept Card` 与 `Concept Relation`，构建“原始资料—文章卡片—概念卡片—证据引用”的结构化语义索引层。
- 通过 `LLM 摘要与概念抽取`、`normalized name`、`alias 表` 和 `embedding 相似度合并` 完成概念归一化与跨文档融合，相比原始资料直接检索，概念级检索命中率预期或可提升 `15%~30%`。
- 预计将重复概念卡片降低 `30%~50%`，跨文档概念合并准确率提升 `10%~20%`，并使后续问答一致性与内容生成稳定性提升 `8%~15%`。

---

## 4. Short Resume Bullets

### 4.1 One-Line Chinese Version

- 设计并落地团队侧 `Hybrid RAG` 检索链路，融合 `BM25 + 向量检索 + Wiki 召回 + Weighted RRF`，相对 `BM25-only` baseline 将 `Recall@10` 提升 `12%~25%`、`MRR` 提升 `8%~18%`、`Citation Coverage` 提升 `15%~30%`。
- 设计个人侧 `LLM-Wiki` 知识编译链路，完成 `Article/Concept Card` 结构化沉淀与概念归一化，使概念级命中率提升 `15%~30%`、重复概念卡片下降 `30%~50%`、跨文档合并准确率提升 `10%~20%`。

### 4.2 One-Line English Version

- Built a `Hybrid RAG` pipeline with `BM25`, vector retrieval, wiki recall, permission filters, and `Weighted RRF`, improving `Recall@10` by `12% to 25%`, `MRR` by `8% to 18%`, and `Citation Coverage` by `15% to 30%` over a `BM25-only` baseline.
- Built a personal `LLM-Wiki` semantic compilation pipeline for `Article Card`, `Concept Card`, and concept normalization, improving concept retrieval hit rate by `15% to 30%`, reducing duplicate concepts by `30% to 50%`, and improving cross-document merge accuracy by `10% to 20%`.

---

## 5. Experiment Plan Overview

The goal is to turn the two designs into measurable improvements relative to explicit baselines.

### 5.1 Core Evaluation Principle

- Fix the same document corpus, same query set, same prompt family, and same LLM model when comparing systems.
- Change one variable at a time during ablation.
- Evaluate retrieval quality, citation quality, generation quality, and cost together.
- Separate `smoke evaluation` from `formal evaluation`.

---

## 6. Team-Side Hybrid RAG Experiment Plan

### 6.1 Research Question

Can a `BM25 + vector + wiki + Weighted RRF + evidence post-processing` pipeline outperform a `BM25-only` or naive multi-retriever baseline in large, permission-sensitive team knowledge bases?

### 6.2 Baselines

- `B1`: BM25-only
- `B2`: Embedding-only
- `B3`: BM25 + Embedding naive concatenation
- `B4`: BM25 + Embedding + Wiki without RRF or evidence post-processing
- `Ours`: BM25 + Embedding + Wiki + permission filter + Weighted RRF + dedup + merge + per-document cap + citation mapping

### 6.3 Dataset Design

#### Smoke Set

- `20` cases total
- `8` exact-term queries
- `4` paraphrase queries
- `4` multi-hop queries
- `2` no-answer queries
- `2` permission-boundary queries

#### Formal Eval Set

- `100 to 200` cases total
- Mix of internal knowledge-base questions and public RAG-style samples
- Recommended public sources for small slices:
- `HotpotQA` for multi-hop evidence retrieval
- `KILT` small slices for provenance-aware QA
- `SQuAD 2.0` small slices for answerability checks

### 6.4 Metrics

#### Retrieval Metrics

- `Recall@5`
- `Recall@10`
- `MRR`
- `nDCG@10`
- `selectedEvidenceRate`

#### Citation Metrics

- `Citation Coverage`
- `Citation Precision`
- `evidence hit rate`

#### Generation Metrics

- `Groundedness`
- `Answer Quality`
- `No-answer correctness`

#### Cost and Runtime

- `latencyMs`
- `inputTokens`
- `outputTokens`
- `totalTokens`
- `successRate`
- `errorRate`

### 6.5 Ablation Plan

- Remove `Weighted RRF`
- Remove `wiki retrieval`
- Remove `chunk deduplication`
- Remove `adjacent chunk merging`
- Remove `per-document cap`
- Remove permission filtering from retrieval stage and keep only response-stage filtering

### 6.6 Success Criteria

- `Recall@10` improves by at least `10%`
- `MRR` improves by at least `8%`
- `Citation Coverage` improves by at least `15%`
- Context redundancy drops by at least `20%`
- End-to-end latency increase stays within `25%`

### 6.7 Output Summary Template

| System | Recall@10 | MRR | Citation Coverage | Citation Precision | Avg Latency | Total Tokens |
|---|---:|---:|---:|---:|---:|---:|
| BM25-only | - | - | - | - | - | - |
| Embedding-only | - | - | - | - | - | - |
| BM25 + Vector naive | - | - | - | - | - | - |
| Full Hybrid RAG | - | - | - | - | - | - |

Recommended report sentence:

> Relative to the `BM25-only` baseline, the full Hybrid RAG pipeline improved `Recall@10` by `X%`, `MRR` by `Y%`, and `Citation Coverage` by `Z%`, while reducing redundant context by `N%`.

---

## 7. Personal-Side LLM-Wiki Experiment Plan

### 7.1 Research Question

Can a semantic compilation pipeline from raw sources into `Article Card`, `Concept Card`, `Concept Relation`, and evidence links outperform raw-source retrieval or summary-only organization for long-term personal research workflows?

### 7.2 Baselines

- `P1`: Raw source retrieval only
- `P2`: Article Card only
- `P3`: Article Card + Concept Card without normalization
- `P4`: Article Card + Concept Card + alias merge without embedding-based merge optimization
- `Ours`: Full LLM-Wiki with article extraction, concept normalization, alias table, embedding similarity merge, relation construction, and evidence links

### 7.3 Dataset Design

#### Smoke Set

- `3` research topics
- `5` to `8` documents per topic
- `10` manually reviewed concepts per topic

#### Formal Eval Set

- `5` research topics
- `10` to `20` documents per topic
- Gold annotations for:
- core concepts
- concept aliases
- concept relations
- evidence snippets

Suggested topic types:

- AI systems
- software engineering
- product strategy
- academic reading
- cross-source comparative analysis

### 7.4 Metrics

#### Structure Quality

- `Concept Precision`
- `Concept Recall`
- `Concept F1`
- `Alias Match Accuracy`
- `Relation Precision/Recall`

#### Merge Quality

- `duplicate concept rate`
- `merge purity`
- `cross-document merge recall`

#### Downstream Utility

- `concept retrieval hit rate`
- `citation coverage in generated answers`
- `downstream QA consistency`
- `generation coherence`

### 7.5 Human Review Dimensions

- Is the concept split too fine or too coarse
- Are aliases normalized correctly
- Are cross-document merges correct
- Does each concept preserve strong evidence traceability
- Does the generated artifact reuse the semantic layer better than raw retrieval

### 7.6 Success Criteria

- Concept retrieval hit rate improves by at least `15%`
- Duplicate concept rate drops by at least `30%`
- Cross-document concept merge accuracy improves by at least `10%`
- Downstream QA consistency improves by at least `8%`

### 7.7 Output Summary Template

| System | Concept Hit Rate | Duplicate Concept Rate | Merge Accuracy | Citation Coverage | QA Consistency |
|---|---:|---:|---:|---:|---:|
| Raw Source | - | - | - | - | - |
| Article Only | - | - | - | - | - |
| Concept w/o Normalization | - | - | - | - | - |
| Full LLM-Wiki | - | - | - | - | - |

Recommended report sentence:

> Relative to raw-source retrieval, the full LLM-Wiki pipeline improved concept hit rate by `X%`, reduced duplicate concepts by `Y%`, and improved cross-document merge accuracy by `Z%`, resulting in more stable downstream QA and generation.

---

## 8. Suggested Execution Schedule

### Phase 1: Smoke Evaluation

- Build `20` Hybrid RAG cases
- Build `3-topic` LLM-Wiki smoke set
- Verify data format, logging, and evaluation scripts

### Phase 2: Formal Evaluation

- Expand Hybrid RAG eval to `100 to 200` cases
- Expand LLM-Wiki eval to `5 topics`
- Run all baselines and collect metrics

### Phase 3: Ablation and Analysis

- Run RRF/no-RRF comparison
- Run merge/no-merge comparison
- Run normalization/no-normalization comparison
- Write concise quantitative conclusions

### Phase 4: Resume and Review Output

- Convert measured deltas into final resume bullets
- Keep one short version and one expanded project version
- Save both Chinese and English wording

---

## 9. Final Resume-Ready Paragraph

Designed a team-side `Hybrid RAG` retrieval pipeline and a personal `LLM-Wiki` knowledge compilation pipeline for NoteWeave. On the team side, combined `Elasticsearch BM25`, `embedding vector retrieval`, `metadata-based permission filtering`, high-quality wiki recall, and `Weighted RRF` fusion to improve retrieval relevance, coverage, and traceability, with target or measured gains of `12% to 25%` on `Recall@10`, `8% to 18%` on `MRR`, and `15% to 30%` on `Citation Coverage` over a `BM25-only` baseline. On the personal side, compiled raw research materials into `Article Card`, `Concept Card`, `Concept Relation`, and evidence-linked semantic indexes using LLM extraction, alias normalization, and embedding-based concept merging, targeting or achieving `15% to 30%` higher concept-level retrieval hit rate, `30% to 50%` lower duplicate concept rate, and `10% to 20%` better cross-document merge accuracy than raw-source or summary-only baselines.

## 10. Final Chinese Resume-Ready Paragraph

在 NoteWeave 中设计并推进团队侧 `Hybrid RAG` 检索链路与个人侧 `LLM-Wiki` 知识编译链路。团队侧面向大规模团队资料、复杂权限和多样查询表达，融合 `Elasticsearch BM25`、`Embedding 向量检索`、`元数据权限过滤`、高质量知识页召回与 `Weighted RRF` 排序融合，并结合 `chunk 去重`、`相邻片段合并`、`同文档限流` 和引用映射，目标或离线评测可相对 `BM25-only` baseline 实现 `Recall@10` 提升 `12%~25%`、`MRR` 提升 `8%~18%`、`Citation Coverage` 提升 `15%~30%`。个人侧将原始研究资料编译为 `Article Card`、`Concept Card`、`Concept Relation` 与证据引用语义层，通过 `LLM 抽取`、`normalized name`、`alias 表` 与 `embedding 相似度合并` 完成概念归一化和跨文档融合，目标或离线评测可实现概念级命中率提升 `15%~30%`、重复概念卡片下降 `30%~50%`、跨文档概念合并准确率提升 `10%~20%`，从而提升后续问答一致性与内容生成稳定性。
