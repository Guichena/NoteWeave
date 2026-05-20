# NoteWeave 个人 Wiki 链路技术改造方案

更新时间：2026-05-19

## 1. 文档定位

这份文档只讲技术改造方案，不讲面试表达口径。

目标是基于当前个人链路：

- `Source`
- `ArticleCard / ConceptCard / ConceptRelation`
- `Artifact`
- `SynthesisCard`

把它从“编译优先的个人 Wiki 生成链路”，升级成“具备正式 answering 能力的 Wiki-first personal research system”。

这里的核心前提是：

- 不把当前路线推翻成 `Notebook-first`
- 不把系统直接改造成 open-ended research agent
- 保留 `Wiki-first` 的长期知识边界
- 在此基础上补 `answering / retrieval / trace / research planning`

---

## 2. 当前技术起点

当前代码已经具备这些基础：

- `SafeUrlContentFetcher`：URL 安全抓取与边界控制
- `WikiCompilerService`：把 Source 编译成 `ArticleCard / ConceptCard / ConceptRelation`
- `PersonalEvidenceService`：汇总个人侧证据
- `PersonalGenerationService`：组装个人生成上下文
- `ArtifactPlanExecutor`：按 plan 执行受控生成
- `PersonalArtifactDistillationService`：显式 proposal / confirm / distill 到 `SynthesisCard`
- `MemoryWritebackService`：长期记忆写回边界

这意味着当前个人链路已经不是裸聊天，而是一条分层知识加工链：

1. 原始资料进入 `Source`
2. 编译成 Wiki 层结构化知识
3. 基于 Wiki 生成 `Artifact`
4. 用户确认后沉淀成 `SynthesisCard`

所以后续改造的重点不是“从 0 做个人知识系统”，而是：

- 在已有 Wiki 骨架上补正式 answering layer
- 在已有 Artifact 骨架上补 retrieval / trace / revision

---

## 3. 当前技术短板

### 3.1 缺少正式的 answering layer

当前个人链路更像：

- 按 project 全量加载 `ArticleCard / ConceptCard / SynthesisCard`
- 聚合这些 card 上挂着的 citation
- 再生成回答或 artifact

问题在于：

- 当前问题没有正式 narrowing 过程
- 不同问题复杂度没有差异化策略
- 回答更像 compile-first 的生成，而不是 query-aware answering

### 3.2 缺少 `wiki -> source` 的回溯纠偏机制

`Wiki-first` 的优势是稳定，但它天然存在一个问题：

- 编译后的 wiki 不一定覆盖当前问题的全部细节

如果系统不能：

1. 先查 wiki
2. wiki 不够时回源到 source
3. 仍不够时明确返回材料不足

那就很容易走向“硬生成”。

### 3.3 有 evidence storage，但缺 answer trace

当前个人侧有 evidence backtrace 和 citation 存储，这很重要。  
但还缺：

- 这次回答用了哪些 wiki 节点
- 最终用了哪些 citation
- 哪些结论证据不足
- 哪些 source 被排除

也就是说：

- 有 evidence
- 但还没有正式的 answering trace

### 3.4 缺少研究过程中的交互中间层

当前有：

- `Source`
- `Card`
- `Artifact`
- `Synthesis`

但没有：

- `ResearchQuestion`
- `SavedAnswer`
- `WorkingNote`
- `HighlightedExcerpt`

这意味着用户在研究过程中的主动判断无法正式积累，也无法进入后续 retrieval。

---

## 4. 改造目标

目标不是把个人链路改造成一个 notebook 产品，而是形成下面这条更完整的技术主线：

1. `Source` 进入系统
2. `WikiCompilerService` 编译成 Wiki 层知识
3. `PersonalAnswering` 层按当前 query 从 Wiki 层做 narrowing
4. 证据不够时回源到 `Source chunk`
5. 生成 grounded answer，并记录 answer trace
6. 用户可把高价值回答沉淀为中间研究层
7. 稳定结果再沉淀为 `SynthesisCard`

可以概括成一句话：

`Wiki-first answering, source-backed correction, traceable outputs, explicit writeback`

---

## 5. 升级点与改造落点

## 升级点 1：补 `Wiki-aware Answering Layer`

### 目标

把个人回答从“全项目上下文直接生成”升级成“先基于 wiki 做 narrowing，再回答”。

### 设计原则

回答默认优先从更稳定的知识层开始：

1. `SynthesisCard`
2. `ConceptCard`
3. `ArticleCard`
4. `Source chunk`

这符合当前 `Wiki-first` 路线。

### 服务改造

建议新增：

- `PersonalAnswerQueryService`
- `PersonalAnswerRetrievalService`
- `PersonalAnswerService`

### API 改造

建议新增：

- `POST /api/v1/personal/projects/{projectId}/ask`

请求体建议包含：

- `query`
- `mode`
  - `AUTO`
  - `WIKI_ONLY`
  - `WIKI_THEN_SOURCE`
- `selectedSourceIds`
- `selectedCardIds`

### 对现有代码的影响

- `ResearchContextService` 从“按 project 全量 load”变成“按 query 分层装配 wiki context”
- `PersonalEvidenceService` 从“汇总所有卡片引用”变成“围绕命中的 wiki 节点构造 evidence pack”

---

## 升级点 2：补 `Question-aware Narrowing / Routing`

### 目标

不同类型的问题走不同的检索策略，而不是统一上下文装配。

### 建议问题分类

- `FACT`
- `SUMMARY`
- `COMPARISON`
- `OPEN_RESEARCH`

### 建议策略

- `FACT`
  - 优先 `ConceptCard + Source chunk`
- `SUMMARY`
  - 优先 `ArticleCard + SynthesisCard`
- `COMPARISON`
  - 优先多 `ConceptCard + 多 Source chunk`
- `OPEN_RESEARCH`
  - 先 `Synthesis / Concept`，再逐步回源扩展

### 服务改造

建议新增：

- `QuestionTypeClassifier`
- `PersonalAnswerRouter`

---

## 升级点 3：补 `Answer Trace + Evidence Coverage`

### 目标

回答不只是给结果，还能解释：

- 为什么这么答
- 用了哪些 wiki 节点
- 用了哪些 evidence
- 哪些结论缺支持

### 数据模型建议

新增：

- `personal_answer_trace`
- `personal_answer_trace_item`
- `personal_answer_citation_relation`

### 记录字段建议

`personal_answer_trace`

- `id`
- `project_id`
- `user_id`
- `query`
- `answer_text`
- `answer_mode`
- `coverage_score`
- `confidence_score`
- `status`
- `created_at`

`personal_answer_trace_item`

- `trace_id`
- `source_type`
  - `SYNTHESIS_CARD`
  - `CONCEPT_CARD`
  - `ARTICLE_CARD`
  - `SOURCE_CHUNK`
- `source_id`
- `selected_reason`
- `used_in_final_answer`
- `score`

### 服务改造

建议新增：

- `PersonalAnswerTraceService`
- `EvidenceCoverageService`

---

## 升级点 4：补 `Corrective Backtracking`

### 目标

当 wiki 层证据不足时，允许系统：

1. 先评估 retrieval 质量
2. 必要时回源或纠偏
3. 再不够时返回材料不足

### 具体策略

低质量 retrieval 时可依次尝试：

1. `query rewrite`
2. `concept expansion`
3. `source backtracking`
4. `material-insufficient response`

### 服务改造

建议新增：

- `PersonalRetrievalEvaluator`
- `PersonalQueryRewriteService`
- `PersonalSourceBacktrackingService`

### 和现有代码的关系

- 复用 `WikiCompilerService` 已有的 evidence backtrace 结果
- 复用 `Source` readable text contract

---

## 升级点 5：补轻量 `Notebook Layer`，但不替代 Wiki

### 目标

把用户研究过程中的主动判断沉淀下来，但最终稳定知识仍然回到 Wiki。

### 数据模型建议

新增：

- `research_question`
- `saved_answer`
- `working_note`
- `excerpt_bookmark`

### 关系建议

新增：

- `working_note_card_relation`
- `working_note_source_relation`
- `saved_answer_citation_relation`

### 设计边界

这里的 Notebook layer 只是交互层：

- Notebook 是过程层
- Wiki 是沉淀层

不要让 notebook 中间层直接替代 `SynthesisCard`。

---

## 升级点 6：补 `Hierarchical + Graph Retrieval`

### 目标

让长文档、多 source、概念关系网络真正进入检索层。

### Hierarchical 部分

建议补：

- `section summary`
- `source summary`
- `project synthesis summary`

### Graph 部分

你当前已经有：

- `ConceptCard`
- `ConceptRelation`
- `SynthesisConceptRelation`

下一步应该把它们从展示层升级成 retrieval 输入层。

### 服务改造

建议新增：

- `SourceSummaryBuilder`
- `ProjectSummaryBuilder`
- `ConceptNeighborhoodRetrievalService`

---

## 升级点 7：最后补 `Bounded Research Agent`

### 目标

在 answering 和 notebook layer 稳定后，再加受边界约束的 research 能力。

### 最适合放 agent 的位置

- `source discovery`
- `retrieval planning`
- `artifact revision`

### 服务改造

建议新增：

- `ResearchPlannerService`
- `SourceDiscoveryProposalService`
- `ArtifactRevisionReviewService`

### 设计边界

必须保留：

- proposal
- confirmation
- bounded scope

不建议一开始就做 open-ended autonomous agent。

---

## 6. 分阶段实施路线

## 第一阶段：先把“根据资料回答”做出来

优先级最高：

1. `Wiki-aware Answering Layer`
2. `Question-aware Narrowing / Routing`
3. `Answer Trace + Evidence Coverage`

这三步完成后，个人链路才真正拥有正式回答能力。

## 第二阶段：把回答变得更稳

接着做：

4. `Corrective Backtracking`
5. 轻量 `Notebook Layer`

这两步完成后，系统会更像 research workspace，而不是一次性生成器。

## 第三阶段：做长期差异化

最后做：

6. `Hierarchical + Graph Retrieval`
7. `Bounded Research Agent`

这部分是形成技术壁垒的关键。

---

## 7. 如果当前只能先做 3 个点

最值得先做的是：

1. `Wiki-aware Answering Layer`
2. `Question-aware Narrowing / Routing`
3. `Answer Trace + Evidence Coverage`

原因很简单：

- 这三步直接决定个人链路能不能真正“根据资料回答”
- 而且它们都建立在你当前已有的 Wiki 骨架之上，不需要推翻现有路线

---

## 8. 代码锚点

和这份技术方案最相关的当前代码：

- `src/main/java/com/noteweave/personal/source/fetch/SafeUrlContentFetcher.java`
- `src/main/java/com/noteweave/personal/compiler/service/WikiCompilerService.java`
- `src/main/java/com/noteweave/personal/generation/service/ResearchContextService.java`
- `src/main/java/com/noteweave/personal/generation/service/PersonalEvidenceService.java`
- `src/main/java/com/noteweave/personal/generation/service/PersonalGenerationService.java`
- `src/main/java/com/noteweave/artifact/service/ArtifactPlanExecutor.java`
- `src/main/java/com/noteweave/personal/distillation/service/PersonalArtifactDistillationService.java`
- `src/main/java/com/noteweave/memory/service/MemoryWritebackService.java`

---

## 9. 参考资料

这部分只保留和技术方案最相关的资料：

### 产品 / 官方资料

- NotebookLM 聊天与引用机制  
  https://support.google.com/notebooklm/answer/16179559?hl=en-GB

- NotebookLM Source Discovery  
  https://support.google.com/notebooklm/answer/16215270?co=GENIE.Platform%3DDesktop&hl=en-GB

- Google Blog: How Google developed and tested NotebookLM  
  https://blog.google/innovation-and-ai/products/developing-notebooklm/

- OpenAI: Introducing deep research  
  https://openai.com/index/introducing-deep-research/

- Claude Research  
  https://claude.com/blog/research

### 关键论文

- In-Context Learning with Long-Context Models  
  https://arxiv.org/abs/2405.00200

- Adaptive-RAG  
  https://arxiv.org/abs/2403.14403

- Corrective Retrieval Augmented Generation  
  https://arxiv.org/abs/2401.15884

- Self-RAG  
  https://arxiv.org/abs/2310.11511

- RAPTOR  
  https://arxiv.org/abs/2401.18059

- GraphRAG  
  https://microsoft.github.io/graphrag/
