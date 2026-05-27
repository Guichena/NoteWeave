# 08 Wiki 长期知识与图谱链路

## 0. 本篇定位

这条链路回答：团队侧如何把稳定知识从 Chat 或 Artifact 沉淀为 Wiki，并通过版本、索引和关系图支撑长期知识管理。

核心链路：

```text
ChatMessage / Artifact / manual draft
-> Wiki draft
-> publish
-> WikiPageVersion
-> WIKI_INDEX Task
-> Wiki search / WikiRetriever
-> Wiki relations / graph
```

## 面试先说版

这条链路我会从“团队长期知识怎么沉淀”讲。RAG 能从原始文档里找证据，但团队协作还需要把稳定结论整理成 Wiki。NoteWeave 支持从聊天、Artifact 或手工草稿生成 Wiki draft，用户发布后形成版本，再异步入索引，成为后续 RAG 的长期知识召回来源。

这里的取舍是：Wiki 不是模型生成后自动写入，而是人工确认后的长期团队知识。Wiki Graph 当前更偏页面关系、链接解析、可视化探索和 Wiki recall 辅助，不要夸成完整 GraphRAG 主链路。

## Q1：团队 Wiki 在系统里承担什么职责？

**答：**

团队 Wiki 承担的是人工确认后的长期团队知识。

团队文档和 RAG 适合从原始资料里找证据，但团队长期协作还需要把稳定结论整理成 WikiPage。NoteWeave 支持手动创建 Wiki 草稿，也可以从 ChatMessage 或 Artifact 生成 Wiki Draft。发布后生成 WikiPageVersion，并通过 WIKI_INDEX Task 入索引，成为后续 RAG 的一个召回来源。

这样团队知识从“原始文档片段”升级到“人工整理后的长期页面”，同时保留版本、引用和关系。

## Q2：为什么 Wiki 发布也要异步入索引？

**答：**

因为入索引依赖 ES，可能失败，也可能需要处理引用、链接和图关系。异步任务能记录状态、重试和错误，不影响草稿和发布记录本身。

这也和文档索引保持一致：长期知识进入检索链路时，不直接在用户请求里同步完成所有索引工作，而是通过 Task/Worker 保证状态可追踪。

## Q3：Artifact 发布为 Wiki 和个人 Artifact 沉淀为 Synthesis 有什么区别？

**答：**

团队 Wiki 面向团队共享知识，发布后进入团队可检索知识体系；个人 SynthesisCard 面向个人研究沉淀，默认 owner-only。

两者相同点是都需要用户确认，不让 LLM 生成内容自动污染长期知识。不同点是团队 Wiki 有页面版本、发布状态、Wiki 索引和团队图关系；个人 Synthesis 更偏研究产物总结和个人知识卡片。

## Q4：Wiki Graph 是当前主检索链路吗？

**答：**

不要把它说成 GraphRAG 主链路。

当前可以讲的是 Team Wiki 支持页面关系、链接解析、graph 展示和 WikiRetriever 作为 hybrid recall 的一部分。它增强团队长期知识的组织和召回，但不要夸大成完整 GraphRAG 推理系统。

稳妥表达是：当前图谱更偏 Wiki 页面关系和可视化探索，以及作为 Wiki recall 的辅助来源；完整 GraphRAG、多跳推理、图算法排序可以作为后续扩展。

## 实现兜底锚点

- `TeamWikiService`
- `WikiIndexTaskWorker`
- `WikiIndexService`
- `WikiSearchService`
- `WikiRetriever`
- `WikiGraphService`
- `WikiRelationService`
- `WikiLinkParser`
- `Phase10TeamWikiIntegrationTest`

## 3 到 5 分钟深答模板

> 团队 Wiki 解决的是“把稳定知识从原始资料和聊天回答里沉淀成长期团队知识”的问题。团队文档和 RAG 更适合找证据，但协作场景里还需要把稳定结论整理成 Wiki 页面。NoteWeave 支持从聊天、Artifact 或手工草稿生成 Wiki draft，发布后生成页面版本，再通过后台索引任务异步入索引，成为后续 Wiki recall 的来源。这样团队侧的长期知识既保留版本，又能进入搜索和 RAG。页面关系和图谱能力更偏长期知识组织、可视化探索和 Wiki recall 的辅助来源，而不是当前主检索链路上的完整 GraphRAG。

## 常见追问继续怎么接

- 如果继续追问“为什么 Wiki 不直接替代文档库”，可以答：Wiki 负责稳定结论，原始文档负责证据细节，两者不是替代关系。
- 如果继续追问“发布为什么还要异步”，可以答：因为索引更新和关系刷新不该阻塞主事务，发布成功和可检索成功要靠任务链路最终收敛。
- 如果继续追问“图谱现在有什么价值”，可以答：当前价值在知识组织、关系浏览和辅助召回，不要夸成完整 GraphRAG 推理引擎。

## 边界和不能说满的地方

- 可以坚定讲：Wiki draft / publish / version / index / recall / graph 展示。
- 不要讲成：发布后自动全量替代文档证据；Wiki Graph 已经是完整 GraphRAG 主链路；生成内容会自动无审核沉淀进长期知识。
