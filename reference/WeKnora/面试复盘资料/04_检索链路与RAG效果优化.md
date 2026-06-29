# 文件：04_检索链路与RAG效果优化.md

## 1. 本主题面试官想考什么

这个主题考察你是否理解 RAG 在线链路：Query 理解、向量检索、关键词检索、图谱检索、Web 检索、Rerank、MMR、多轮历史、上下文构造、流式生成，以及如何定位回答不准的问题。

基于仓库可确认的信息：`session_knowledge_qa.go` 中动态组装问答 pipeline；RAG 链路包括 `LOAD_HISTORY`、`QUERY_UNDERSTAND`、`CHUNK_SEARCH_PARALLEL`、`CHUNK_RERANK`、`WEB_FETCH`、`CHUNK_MERGE`、`FILTER_TOP_K`、`DATA_ANALYSIS`、`INTO_CHAT_MESSAGE`、`CHAT_COMPLETION_STREAM`。检索引擎通过 `CompositeRetrieveEngine` 支持多后端和多 retriever type。  
需要你补充的信息：线上使用的 TopK、阈值、rerank 模型、评测集和优化指标。

## 2. 高频问题清单

### 基础问题

- RAG 问答链路是什么？
- 向量检索和关键词检索有什么区别？
- 为什么需要 rerank？
- 为什么要流式输出？

### 进阶问题

- query rewrite/query understand 在这里解决什么问题？
- 多路检索结果怎么融合？
- Rerank 阈值怎么设置？过高过低有什么问题？
- MMR 是什么？为什么要用？

### 深挖追问

- 如果回答不准，你怎么定位问题？
- 如果检索召回了很多无关片段，怎么优化？
- 如果用户问题很短，比如“怎么配置？”，系统怎么理解上下文？
- GraphRAG 和普通向量检索如何配合？

### 压力追问

- 你怎么证明 rerank 有收益？
- 为什么不用纯关键词搜索？
- 为什么不用把所有文档直接塞给长上下文模型？
- RAG 最容易产生幻觉的地方在哪里？

## 3. 问答与讲解

### Q1：WeKnora 的在线问答链路是怎样的？

#### 面试官为什么问

这是 RAG 项目的核心在线链路。面试官想看你是否能说清楚从用户问题到最终答案的每个阶段。

#### 先给结论

在线问答时，WeKnora 先根据会话和请求确定知识库、知识条目、模型、租户、web search 和多模态配置，然后构造一个 ChatManage。

#### 回答思路

1. 解析请求和会话，确定知识库、模型、租户、web search、附件、图片等上下文。
2. 加载历史和用户记忆。
3. 做 query understand/rewrite/expansion。
4. 并行做 chunk search 和 entity graph search。
5. rerank、merge、filter topK。
6. 拼接 prompt/context。
7. 调用 chat model 流式生成，并发送 references 事件。

#### 结合我的项目怎么答

`KnowledgeQA` 会先 resolve knowledge bases 和 chat model，再构建 `ChatManage`。如果没有知识库也没开 web search，会走纯聊天 pipeline；否则会走 RAG pipeline。RAG pipeline 中 `CHUNK_SEARCH_PARALLEL` 会并行执行 chunk search 和 entity search，`CHUNK_RERANK` 会调用 rerank 模型，最后通过 `CHAT_COMPLETION_STREAM` 流式输出。

#### 技术原理 / 链路设计讲解

在线链路最重要的是“先召回依据，再生成答案”。如果没有检索或检索结果质量差，大模型就容易靠参数知识猜答案。WeKnora 通过 query understand 判断是否需要检索，通过并行检索提高召回，通过 rerank 提高排序质量，通过 references 事件把引用结果返回给前端。

#### 技术栈特点与选型理由

`chat_pipeline` 使用插件式 EventManager，每个阶段对应一个 EventType，方便新增或调整 pipeline。这样比把所有逻辑写在一个方法里更可维护，也便于对不同模式动态组装链路。

#### 可直接复述的面试回答

在线问答时，WeKnora 先根据会话和请求确定知识库、知识条目、模型、租户、web search 和多模态配置，然后构造一个 ChatManage。RAG 场景下 pipeline 会加载历史，做 query understand，再并行执行 chunk 检索和实体图谱检索，之后对候选结果 rerank、merge、过滤 TopK，把最终上下文拼成 prompt，最后调用大模型流式生成。生成前还会把引用结果通过 event bus 发给前端，所以用户能看到答案依据。

#### 常见追问

- 如果 query understand 判断不需要检索但实际需要怎么办？
- 为什么 search 和 entity search 要并行？
- 引用结果和最终答案如何对应？

#### 常见坑

不要只说“先查向量库再问模型”。项目实际链路比这复杂，包括 query understand、混合检索、图谱、rerank、merge、filter 和流式输出。

---

### Q2：为什么需要混合检索？向量检索不够吗？

#### 面试官为什么问

面试官想判断你是否理解语义检索和关键词检索的互补性。

#### 先给结论

向量检索不是万能的。它对语义相似问题很好，但对错误码、版本号、接口字段、专有名词这类精确匹配可能不如关键词检索。

#### 回答思路

1. 向量检索擅长语义相似，但对精确词、编号、错误码、专有名词可能不稳定。
2. 关键词/BM25 擅长精确匹配，但不理解同义表达。
3. 混合检索能提高召回覆盖。
4. 结果需要融合和 rerank，否则会引入噪声。

#### 结合我的项目怎么答

项目中 `RetrieverType` 包括 `keywords`、`vector`、`websearch`。`RetrievalConfig` 里有 `EmbeddingTopK`、`VectorThreshold`、`KeywordThreshold`、`RRFK`、`RRFVectorWeight`、`RRFKeywordWeight`。`CompositeRetrieveEngine` 会根据 retriever type 找对应引擎执行检索。

#### 技术原理 / 链路设计讲解

向量检索把文本映射到高维空间，用相似度查找语义接近内容。它适合“怎么接入 SSO”和“OIDC 登录配置”这类语义近似问题。但对于“错误码 E1043”、“migration 000043”、“API 字段 vector_store_id”这种精确词，BM25 往往更可靠。

RRF（Reciprocal Rank Fusion）常用于融合不同检索器排名：不直接比较不同检索器的原始分数，而是按排名倒数加权，降低分数不可比问题。

#### 技术栈特点与选型理由

项目支持不同向量库和关键词后端，说明它不是绑定一个检索实现。RRF 权重放到租户配置里，也说明不同数据集可以调参。

#### 可直接复述的面试回答

向量检索不是万能的。它对语义相似问题很好，但对错误码、版本号、接口字段、专有名词这类精确匹配可能不如关键词检索。WeKnora 支持 vector 和 keywords 两类 retriever，并提供 RRF 参数来融合结果。我的理解是，混合检索先尽量提高召回覆盖，再通过 rerank 和 TopK 过滤控制精度，这比单纯依赖向量检索更稳。

#### 常见追问

- RRF 为什么不用原始 score 相加？
- 关键词和向量权重怎么调？
- 不同向量库相似度分数是否可比？

#### 常见坑

不要把向量检索说成语义搜索的唯一答案。企业知识库里大量问题包含编号、字段名、产品名和报错信息，关键词检索很重要。

---

### Q3：Rerank 在系统里起什么作用？阈值如何取舍？

#### 面试官为什么问

Rerank 是 RAG 效果优化的常见深挖点。面试官想看你是否理解召回和排序的分工。

#### 先给结论

Rerank 的作用是把第一阶段召回的候选片段重新排序。

#### 回答思路

1. 第一阶段召回重在覆盖，宁可多召回。
2. Rerank 用更强模型重新判断 query-passage 相关性。
3. 阈值高会提升精度但可能无结果；阈值低会提升召回但可能污染上下文。
4. 需要结合评测集和线上反馈调参。

#### 结合我的项目怎么答

`PluginRerank` 会排除 DirectLoad 结果，对候选 passage 调用 rerank 模型；如果没有结果且原阈值高于 0.3，会把阈值降为原来的 0.7 倍但不低于 0.3 再重试。Rerank 后还会计算 composite score，并应用 MMR。

#### 技术原理 / 链路设计讲解

向量检索一般是 bi-encoder，查询和文档分别编码，速度快但交互弱。Rerank 通常是 cross-encoder 或专门排序模型，直接输入 query 和 passage，相关性判断更精细，但成本更高。因此一般只对第一阶段 TopK 候选 rerank，而不是全库 rerank。

MMR 用于在高相关结果中保持多样性，避免 TopK 都来自同一段或重复内容。

#### 技术栈特点与选型理由

项目把 rerank 作为可选模型配置，并且支持阈值降级，体现了实际工程里“宁可给用户一个有依据的降级结果，也不要因为阈值过高直接无答案”的思路。

#### 可直接复述的面试回答

Rerank 的作用是把第一阶段召回的候选片段重新排序。第一阶段检索更关注速度和召回，可能会带来一些语义相近但不真正相关的片段；Rerank 会用更强的相关性模型判断 query 和 passage 是否匹配。WeKnora 里如果 rerank 阈值过高导致无结果，还会做一次阈值降级重试，避免直接返回空。最后还会用 MMR 控制结果多样性，减少重复上下文。

#### 常见追问

- Rerank 会不会显著增加延迟？
- 如果 rerank 模型不可用，如何降级？
- composite score 怎么设计更合理？

#### 常见坑

不要说 rerank 一定提高效果。它依赖模型质量和候选集质量；如果第一阶段没召回正确答案，rerank 也救不了。

---

### Q4：如果用户说答案不准，你怎么排查？

#### 面试官为什么问

这是大厂最喜欢的真实排障题。面试官想看你能否把 RAG 错误分层定位。

#### 先给结论

我会按 RAG 链路分层排查。第一看原文有没有被 docreader 正确解析，第二看分块是否把答案切散或缺上下文，第三看 Embedding 和索引是否成功，第四看检索阶段有没有召回正确 chunk，第五看 rerank 是否把正确片段排在前面，第六看最终 prompt 是否把依据传给模型，最后再看模型是否没有遵循上下文。

#### 回答思路

按链路分段：

1. 原文是否解析正确。
2. chunk 是否合理。
3. Embedding 是否成功，维度是否匹配。
4. 检索是否召回正确 chunk。
5. Rerank 是否把正确 chunk 排前。
6. Prompt 是否正确引用上下文。
7. LLM 是否忽略上下文或幻觉。
8. 权限/租户/知识库范围是否限制了召回。

#### 结合我的项目怎么答

可以借助 chunk 预览、knowledge status、search result references、Langfuse trace、worker logs、`task_dead_letters`、retrieval config 和 model config 逐层排查。`chat_pipeline` 各插件也有 pipelineInfo 日志，可看每阶段候选数量和错误。

#### 技术原理 / 链路设计讲解

RAG 的错误不是单点错误，而是流水线误差叠加。比如用户问“如何配置 OIDC”，如果解析时把标题丢了，chunk 里没有上下文；如果 chunk 太大，向量语义被稀释；如果关键词阈值太高，精确字段没召回；如果 rerank 阈值太高，正确片段被过滤；如果 prompt 里没有要求“只基于资料回答”，模型可能幻觉。

#### 技术栈特点与选型理由

Langfuse 的价值在这里很明显：它能把 HTTP 请求、检索、rerank、模型调用、异步任务串到一个 trace 里，避免只靠日志猜。

#### 可直接复述的面试回答

我会按 RAG 链路分层排查。第一看原文有没有被 docreader 正确解析，第二看分块是否把答案切散或缺上下文，第三看 Embedding 和索引是否成功，第四看检索阶段有没有召回正确 chunk，第五看 rerank 是否把正确片段排在前面，第六看最终 prompt 是否把依据传给模型，最后再看模型是否没有遵循上下文。WeKnora 有 references、pipeline 日志、Langfuse trace、任务 dead-letter 和知识状态，可以把问题定位到解析、分块、召回、排序或生成某一层。

#### 常见追问

- 如果正确 chunk 没召回，怎么调？
- 如果召回正确但模型答错，怎么调？
- 是否有自动评测集？

#### 常见坑

不要一上来就说“换模型”。换模型可能掩盖问题，但不一定解决解析、分块和召回错误。

---

## 4. 本主题总结

RAG 效果优化要围绕“召回覆盖、排序精度、上下文质量、生成约束”四件事讲。面试时最加分的是能分层定位错误，并且能讲出每层的指标和工具。

## 5. 面试前自查清单

- 我是否能说出在线 pipeline 的每个阶段？
- 我是否能解释 vector、keywords、Graph、web search 的区别？
- 我是否能解释 RRF、rerank、MMR 的作用？
- 我是否能拿一个错误答案举例说明排查路径？
- 我是否有真实评测数据或调参经验可以补充？
