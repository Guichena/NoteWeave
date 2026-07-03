# NoteWeave v2 问答 RAG 链路设计

## 1. 定位

`问答 RAG 链路` 是 NoteWeave 在统一聊天界面中的默认回答链路。

它负责在当前研究工作台资料范围内，为用户提供低延迟、可引用、可继续追问的资料问答能力。

一句话定义：

`问答 RAG 链路是面向快速理解和连续追问的资料问答链路：系统基于当前研究工作台内已经解析和索引的资料进行检索，读取必要证据片段，生成带来源引用的回答，并允许用户将高价值回答保存为结构化笔记或继续派生产物。`

## 2. 与 Note / Wiki 的区别

### 2.1 问答 RAG 链路

问答 RAG 链路是 `答案优先`。

目标是：

- 快速回答当前问题
- 保持当前会话上下文连续性
- 给出必要引用
- 尽量降低交互延迟
- 支持一键保存或继续生成产物

典型输出：

```text
直接回答
关键依据
引用来源
后续建议
```

### 2.2 Note 链路

Note 链路是 `阅读整理优先`。

它参考 `Marginalia-style Structured Reading Funnel`，重点是：

- 先定位候选资料
- 再读取原文窗口
- 抽取摘录卡片
- 生成结构化笔记

因此 Note 链路更像“研究助理帮用户读资料、摘证据、整理结论”。

### 2.3 Wiki 链路

Wiki 链路是 `稳定知识优先`。

它优先使用已有 Wiki 页面、页面链接、索引和来源回链回答问题，目标是让用户围绕长期知识页面持续维护，而不是只完成一次问答。

## 3. 核心原则

### 3.1 工作台是默认检索边界

问答 RAG 链路默认只在当前研究工作台内检索，不做全局开放召回。

基础检索对象包括：

- 用户上传资料
- URL / 文本导入资料
- 音视频转写文本
- 被用户明确保存为资料的系统生成内容

问答 RAG 的基础闭环只建立在“工作台资料池 + 证据引用”之上。其他链路产生的内容如果要进入问答检索，必须先被用户明确保存为资料，再按普通资料统一解析、索引和引用。

### 3.2 先快后准

问答 RAG 链路不追求每次都进行多轮研究，而是采用“先快后准”的默认策略：

1. 先利用会话上下文和轻量检索快速理解问题
2. 再召回少量高置信候选片段
3. 对关键片段进行 rerank 或证据校验
4. 生成引用清楚的回答
5. 如果证据不足，再提示用户升级为 Note、Wiki 或 Deep Research

### 3.3 引用必须可回跳

回答中出现事实性结论时，应尽量绑定 `Citation`。

Citation 至少应能回到：

- 资料
- 资料快照
- chunk
- 原文窗口
- 页码 / 段落 / 时间戳 / offset

### 3.4 不把聊天历史当事实来源

聊天历史只用于理解上下文，不直接作为事实证据。

如果回答需要事实依据，必须回到当前工作台内可检索、可回跳的资料证据。

## 4. 技术路线

问答 RAG 链路定义为：

`Workspace-bounded Conversational RAG + Hybrid Retrieval + Evidence Rerank + Citation-grounded Answer`

可以拆成六段：

1. `Query Understanding`
   基于当前用户问题、最近会话消息、当前回答链路、工作台信息，生成检索意图和查询改写。

2. `Workspace-bounded Retrieval`
   只在当前 `workspace_id / topic_scope_id` 内检索，避免不同工作台资料串扰。

3. `Hybrid Retrieval`
   同时使用关键词检索、向量检索和结构化过滤召回候选内容。

4. `Evidence Rerank`
   对候选资料片段做二次排序，优先选择能直接回答问题、来源可靠、引用位置明确的证据。

5. `Context Packing`
   将少量证据片段、引用定位、必要会话上下文组装成模型输入，避免把大量无关资料塞给模型。

6. `Citation-grounded Answer`
   生成简洁回答，并把事实结论绑定到 Citation。

## 5. 检索对象

问答 RAG 链路的检索对象以工作台资料池为主，不直接依赖其他链路内部对象：

| 对象 | 用途 | 是否默认参与 |
|---|---|---|
| `Source Chunk` | 用户资料解析后的文本片段 | 是 |
| `Conversation Message` | 当前会话上下文理解 | 只作上下文，不作证据 |
| `Generated Source` | 被用户明确保存为资料的系统生成内容 | 按资料接入 |

说明：

- 问答 RAG 不直接硬编码 Note / Wiki / Artifact / Memory 的内部对象。
- 其他链路产生的内容只有被用户明确保存为资料后，才进入统一资料解析、索引和引用流程。
- Research Trace 属于 Deep Research 过程资产，默认不进入主检索。

## 6. 主流程

```text
用户在聊天框提问
  -> 读取当前会话上下文
  -> 读取工作台 / 主题范围
  -> 判断问题类型
  -> 生成检索查询与过滤条件
  -> 执行关键词检索 + 向量检索 + 结构化过滤
  -> 合并候选结果
  -> rerank 候选证据
  -> 生成 Citation 候选
  -> 组装回答上下文
  -> LLM 生成带引用回答
  -> 保存 Conversation Message 与 Message Citation
  -> 用户可继续追问 / 保存为结构化笔记 / 触发右侧产物
```

## 7. 查询理解

### 7.1 输入

查询理解阶段输入：

- 用户当前问题
- 当前会话最近若干轮消息
- 当前工作台信息
- 当前主题范围
- 用户当前选择的回答链路
- 可选的当前资料阅读上下文

### 7.2 输出

查询理解阶段输出：

```json
{
  "question_type": "definition | summary | comparison | source_lookup | reasoning | generation",
  "search_queries": ["..."],
  "must_filter": {
    "workspace_id": 1,
    "topic_scope_id": 2
  },
  "preferred_material_types": ["uploaded_file", "imported_text", "generated_source"],
  "answer_style": "short | normal | detailed",
  "needs_citation": true
}
```

### 7.3 规则

- 简单追问优先继承上一轮上下文
- 明确问“来源在哪”时提高引用密度
- 明确要求总结、对比、解释时提高资料覆盖度
- 证据不足时不强答，应提示可升级为 Note 或 Deep Research

## 8. 召回策略

### 8.1 关键词检索

用于处理：

- 精确术语
- 文件名
- 人名 / 公司名 / 产品名
- 标题与小标题
- 中文短词

推荐由 `Elasticsearch` 承接：

- `source_chunk_index`

问答 RAG 不直接查询 Note、Wiki、Artifact 或 Memory 的内部索引。只有当系统生成内容被用户明确保存进工作台资料池后，才按普通资料进入统一资料索引。

### 8.2 向量检索

用于处理：

- 语义相似问题
- 改写后的自然语言查询
- 跨文件概念匹配
- 用户描述不完全匹配原文关键词的情况

当前不单独引入向量数据库，优先使用 Elasticsearch 向量能力或后续可替换实现。

### 8.3 结构化过滤

所有召回都必须带上：

- `workspace_id`
- `topic_scope_id`
- `status`
- `visibility`
- `deleted_at`

可选过滤：

- source_type
- material_type
- created_by
- updated_at
- tag

### 8.4 合并策略

候选结果合并采用轻量 `RRF-style merge`：

- 关键词结果提供精确命中
- 向量结果提供语义补召
- 结构化对象提高可解释性
- 系统生成内容只有在被用户明确保存为资料后，才作为普通资料参与召回

## 9. Rerank 与证据选择

### 9.1 Rerank 输入

Rerank 阶段输入：

- 用户问题
- 候选片段标题
- 候选片段摘要
- 候选片段正文短摘录
- 来源类型
- 来源更新时间
- 是否已有 Citation 定位

### 9.2 排序偏好

优先级从高到低：

1. 能直接回答问题的资料片段
2. 有明确引用位置的资料片段
3. 来自当前用户选中资料或当前会话强相关资料的片段
4. 被用户明确保存为资料的系统生成内容
5. 仅语义相似但证据位置不清晰的片段

### 9.3 证据选择

默认建议：

- 快速问答：选 3-5 条证据
- 普通问答：选 5-8 条证据
- 对比类问题：保证每个被比较对象至少有 1-2 条证据
- 来源查找类问题：宁可少答，也要保证引用准确

## 10. Context Packing

模型输入不应塞入全部召回内容，而应压缩为结构化上下文：

```text
用户问题
会话必要上下文
证据片段列表
  - evidence_id
  - source_type
  - title
  - excerpt
  - citation_locator
  - relevance_reason
回答要求
```

Context Packing 规则：

- 保留用户问题原文
- 保留必要的上一轮指代信息
- 每条证据都带 `evidence_id`
- 每条证据都带可生成 Citation 的定位信息
- 删除重复、低分、无定位证据
- 会话上下文只能帮助理解指代，不能替代资料证据

## 11. 回答生成

回答结构建议：

```text
直接回答

关键依据
- ...
- ...

引用来源
- ...

可继续操作
- 保存为结构化笔记
- 生成报告 / 测验 / 学习指南
- 升级为深度研究
```

生成约束：

- 不编造未检索到的来源
- 不把会话历史当作事实引用
- 引用必须来自当前轮证据候选
- 如果证据不足，明确说明不足
- 如果存在冲突证据，标出冲突

## 12. 数据对象

### 12.1 复用对象

问答 RAG 链路复用以下对象：

- `conversation`
- `conversation_message`
- `source`
- `source_snapshot`
- `source_chunk`
- `source_window`
- `citation`
- `message_citation`
- `retrieval_trace`
- `llm_call_log`

### 12.2 建议补充字段

`conversation_message` 建议补充或保留：

- `retrieval_trace_id`
- `answer_mode`
- `model_name`
- `latency_ms`
- `citation_count`

`retrieval_trace` 建议至少包含：

- `id`
- `workspace_id`
- `topic_scope_id`
- `conversation_id`
- `message_id`
- `query_text`
- `query_rewrite_json`
- `retrieval_strategy`
- `candidate_count`
- `selected_evidence_count`
- `latency_ms`
- `trace_payload_json`
- `created_at`

## 13. 接口设计

### 13.1 发送消息

```text
POST /api/conversations/{conversationId}/messages
```

请求体：

```json
{
  "content": "这几篇资料对 RAG 的定义是什么？",
  "answer_mode": "QA",
  "context_snapshot_id": 123
}
```

返回：

```json
{
  "message_id": 1001,
  "assistant_message_id": 1002,
  "status": "completed",
  "answer": "...",
  "citations": []
}
```

### 13.2 流式回答

```text
GET /api/conversations/{conversationId}/messages/{messageId}/stream
```

事件：

```text
retrieval_started
retrieval_done
rerank_done
answer_delta
citation_ready
completed
failed
```

### 13.3 保存为结构化笔记

```text
POST /api/v2/messages/{messageId}/save-as-note
```

作用：

- 将当前回答、引用和用户可编辑内容保存为工作台内的结构化笔记
- 保留 `message_citation -> knowledge_version_citation` 的证据链

## 14. 后端模块

推荐模块：

- `ConversationService`
- `AskChainService`
- `QueryRewriteService`
- `RetrievalService`
- `RerankService`
- `EvidencePackingService`
- `CitationResolver`
- `AnswerGenerationService`
- `KnowledgeWritebackService`
- `RetrievalTraceService`

模块调用关系：

```text
ConversationController
  -> AskChainService
      -> QueryRewriteService
      -> RetrievalService
      -> RerankService
      -> EvidencePackingService
      -> AnswerGenerationService
      -> CitationResolver
      -> RetrievalTraceService
  -> ConversationService
```

## 15. Elasticsearch 索引建议

### 15.1 source_chunk_index

字段：

- `workspace_id`
- `topic_scope_id`
- `source_id`
- `source_snapshot_id`
- `chunk_id`
- `title`
- `heading`
- `content`
- `summary`
- `page_no`
- `locator_json`
- `embedding`
- `status`

### 15.2 资料化生成内容索引

问答 RAG 不直接读取其他链路内部对象。系统生成内容如果被用户保存为资料，可以进入统一资料索引；具体索引是否拆分由实现阶段决定。

#### generated_source_index

字段：

- `workspace_id`
- `topic_scope_id`
- `source_id`
- `source_snapshot_id`
- `generated_from_type`
- `generated_from_id`
- `title`
- `content`
- `summary`
- `embedding`
- `status`

## 16. 评估与验收

问答 RAG 链路的验收不只看“回答像不像”，至少要看四类指标：

### 16.1 检索指标

- `hit@k`
- `MRR`
- `nDCG`
- 候选为空率

### 16.2 引用指标

- Citation 覆盖率
- Citation 可回跳成功率
- 引用片段与回答事实的一致率

### 16.3 回答指标

- 直接回答率
- 证据不足时的拒答 / 降级提示正确率
- 冲突证据提示率

### 16.4 体验指标

- 首 token 延迟
- 总回答延迟
- 用户继续追问率
- 保存为结构化笔记率
- 右侧产物触发率

## 17. 落地范围

第一阶段建议实现：

```text
工作台级过滤
  -> source chunk 关键词检索
  -> source chunk 向量检索
  -> 简单 RRF 合并
  -> rerank 可选
  -> Citation 生成
  -> 带引用回答
  -> 保存为结构化笔记
```

暂不做：

- 独立向量数据库
- 全局跨工作台检索
- 复杂 Claim Graph
- 自动多轮研究
- 自动改写 Wiki

## 18. 最终口径

`问答 RAG 链路是 NoteWeave 的默认资料问答链路。它以当前研究工作台为检索边界，通过关键词检索、向量检索、结构化过滤和证据 rerank 召回已解析资料中的高价值片段，再通过 Citation-grounded Answer 生成可回跳来源的回答。它和 Note 链路的区别是答案优先、延迟更低、输出更轻；和 Wiki 链路的区别是不会优先进入长期页面组织，而是围绕当前问题快速给出可信回答。`
