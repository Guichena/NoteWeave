# 文件：05_RAG_模型_Prompt_幻觉治理.md

## 1. 本主题覆盖的通用问题

- 搜索接入是关键词、向量、混合检索还是别的方案？
- 为什么这么选？
- 文档切分怎么做？
- 实际使用什么模型，为什么选它？
- system prompt 怎么设计？
- 大模型幻觉怎么处理？
- 模型效果怎么判断？
- 召回率、准确率怎么定义？

## 2. 架构视角怎么切入

NoteWeave 的 RAG 不是“向量库 + LLM”，而是 evidence-first RAG：

```text
Permission
-> Retrieval Scope
-> BM25 / Vector / Wiki Recall
-> Weighted RRF
-> EvidencePostProcessor
-> Grounded Prompt
-> LLM
-> Citation
-> RetrievalTrace / LLMCallLog / Eval
```

这个回答要突出“证据先行”：模型只是最后生成层，前面有权限、检索、融合、证据处理和引用约束。

## 3. 完整链路深讲

### 3.1 检索范围

用户提问时，先校验 TEAM_CHAT、FORMAL session 和 ask permission。然后根据 session scope 获取 ACTIVE KnowledgeBase。前端传来的 scope 不能直接信任，要由服务端复核。

### 3.2 多路召回

- BM25：适合关键词、术语、错误码、标题、人名、编号。
- Vector：适合语义相似、同义改写、自然语言问题。
- Wiki recall：适合团队已沉淀的稳定知识。

`HybridRetriever` 会先跑 BM25，再尝试 vector 和 wiki。向量失败时 fallbackUsed=true，并降级 BM25。

### 3.3 RRF 融合

不同召回源分数尺度不同，不能直接相加。Weighted RRF 基于排名融合：

```text
score(d) = sum(weight_i / (k + rank_i(d)))
```

它更关注“多个召回器都认为靠前”的结果，而不是某一路分数特别大。

### 3.4 证据后处理

`EvidencePostProcessor` 做：

- chunk 去重。
- minScore 过滤。
- 按 score、documentId、chunkIndex 排序。
- 同文档证据限流。
- 相邻 chunk 合并。
- maxContextChars 截断。
- citationIndex 编号。

这一层解决“召回结果不能直接塞 prompt”的问题。

### 3.5 Prompt 与生成

`TeamRagPromptBuilder` 把 evidence 编号、引用规则、无证据兜底、prompt injection 防护规则组织进 prompt。LLM 调用通过 `ObservedLlmGateway`，记录 promptVersion、scene、token、latency、error。

### 3.6 Citation 与观测

回答后保存 Citation 和 message_citation。RetrievalTrace 记录召回过程，Citation 记录最终答案使用的证据。二者共同支持坏回答排查。

## 4. 关键实现锚点

- `HybridRetriever`
- `Bm25Retriever`
- `VectorRetriever`
- `WikiRetriever`
- `WeightedReciprocalRankFusion`
- `EvidencePostProcessor`
- `TeamRagPromptBuilder`
- `ObservedLlmGateway`
- `CitationService`
- `RetrievalTraceService`
- `PromptVersionService`

## 5. 为什么这么设计

### 5.1 为什么不只用向量

向量对语义相似好，但对专有名词、编号、错误码、精确短语不一定稳定。BM25 对这些更可靠。Wiki recall 则引入已确认知识。混合检索比单一路径更稳。

### 5.2 为什么不只靠 prompt 防幻觉

prompt 只能约束模型，但不能保证事实正确。必须从证据源头控制：无证据兜底、Citation 持久化、Trace 排查、Eval 回归。

### 5.3 为什么 Citation 不放 JSON

Citation 是证据关系，不是展示字段。关系化后才能做权限二次校验、版本追踪、Artifact/Card/Wiki 复用和排障。

### 5.4 模型如何选择

当前仓库支持 OpenAI-compatible client 和 StubLlmClient，具体 provider 可配置。面试时不要编造某个生产模型版本。可以说选择模型会看上下文窗口、中文能力、成本、延迟、稳定性、工具生态和私有化约束。

## 6. 可直接复述的深答

NoteWeave 的 RAG 是 evidence-first 设计。用户提问后，系统先校验权限和 session scope，再做 BM25、向量和 Wiki 多路召回。BM25 解决关键词和术语精确匹配，向量解决语义改写，Wiki recall 引入已沉淀的稳定知识。多路结果通过 weighted RRF 融合，因为不同召回器的分数尺度不可比。融合后还不能直接塞进 prompt，需要 EvidencePostProcessor 做去重、低分过滤、相邻 chunk 合并、同文档限流和上下文裁剪。如果没有证据，就明确返回无证据兜底；如果有证据，TeamRagPromptBuilder 会把证据编号和引用规则注入 prompt，再通过 ObservedLlmGateway 调模型。最后 Citation、RetrievalTrace 和 LLMCallLog 都会持久化，方便排查坏回答。这样治理幻觉不是靠一句 prompt，而是靠检索、证据、引用和观测闭环。

## 7. 追问兜底

### 如果问“准确率多少”

当前没有生产准确率数字，不编造。可以说明会用 Eval case、recall@k、MRR、citation coverage、人工 bad case 复盘来验证。

### 如果问“citation coverage 高是否代表答案正确”

不代表。它只能说明答案有引用覆盖，还要结合引用是否正确、claim 是否被证据支持、生成是否忠实。

### 如果问“模型版本和上下文窗口”

如果仓库没有固定生产配置，不要编造。说系统支持 provider 配置，本地可用 stub，真实上线会按成本、延迟、上下文和效果评测选型。

