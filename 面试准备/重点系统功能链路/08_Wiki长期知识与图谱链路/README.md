# 团队 Wiki 长期知识与知识图谱

> 本文件为 2026-06-01 重构版，依据当前代码、测试和 Flyway 迁移整理。不要再按旧阶段计划或旧题库口径背。

## 0. 本篇定位
团队 RAG 的回答可能是临时结论，Wiki 要承载人工确认后的长期稳定知识，并能被搜索、版本化和图谱化。

## 1. 面试先说版
Wiki 这块我会讲成团队长期知识层。团队 RAG 的回答和 Artifact 只是阶段性结果，不一定能直接成为稳定知识。NoteWeave 里可以从 ChatMessage 或 Artifact 创建 Wiki 草稿，经过人工编辑后发布为 WikiPageVersion，然后通过 WIKI_INDEX 任务进入检索。WikiLinkParser、WikiRelationService 和 WikiGraphService 会解析页面关系，KnowledgeGraphController 能暴露空间级图谱和节点详情。这个设计的价值是把“模型生成的临时答案”和“团队确认的长期知识”分开，同时保留版本、来源、Citation 和图谱关系。

## 2. 当前真实口径
NoteWeave 用 WikiPage、WikiPageVersion、WikiIndex、WikiGraph 把团队长期知识从临时回答和 Artifact 中沉淀出来。

### 已实现
- TeamWikiController 提供 WikiPage CRUD、publish、versions、relations、message to wiki draft、artifact publish to wiki、search、wiki graph。
- WikiRetriever 可以作为混合检索的一路来源。
- KnowledgeGraphController 支持空间级 graph、wiki page graph、node detail、neighborhood、path。
- AutoWikiMaintenanceService 和 V19 迁移说明当前还有自动维护方向。

### 设计目标
- TeamWikiService 支持创建草稿、编辑、发布、归档、版本查看；WikiIndexTaskWorker 异步入索引；WikiRelationService/WikiGraphService/KnowledgeGraphService 管理链接、关系和图谱。
- 让团队知识库不只是文件检索，还能沉淀成结构化、可版本、可引用、可导航的长期知识层。

### 后续可扩展
- 如果发布后入索引失败怎么办？
- 如果 Wiki 链接指向不存在页面怎么办？
- 如果团队知识和个人研究要互通，边界怎么设计？

## 3. 代码和测试锚点
- src/main/java/com/noteweave/team/wiki/controller/TeamWikiController.java
- src/main/java/com/noteweave/team/wiki/service/TeamWikiService.java
- src/main/java/com/noteweave/team/wiki/service/WikiIndexTaskWorker.java
- src/main/java/com/noteweave/team/wiki/service/WikiGraphService.java
- src/main/java/com/noteweave/graph/service/KnowledgeGraphService.java
- src/test/java/com/noteweave/team/wiki/Phase10TeamWikiIntegrationTest.java

## 4. 必会问题与答题骨架

### Q1: Artifact 和 Wiki 为什么分开？

回答时按四步走：
1. 先说场景：团队 RAG 的回答可能是临时结论，Wiki 要承载人工确认后的长期稳定知识，并能被搜索、版本化和图谱化。
2. 再说方案：TeamWikiService 支持创建草稿、编辑、发布、归档、版本查看；WikiIndexTaskWorker 异步入索引；WikiRelationService/WikiGraphService/KnowledgeGraphService 管理链接、关系和图谱。
3. 再说收益：让团队知识库不只是文件检索，还能沉淀成结构化、可版本、可引用、可导航的长期知识层。
4. 最后落到真实代码锚点，不要停在概念。

可直接复述：

> Wiki 这块我会讲成团队长期知识层。团队 RAG 的回答和 Artifact 只是阶段性结果，不一定能直接成为稳定知识。NoteWeave 里可以从 ChatMessage 或 Artifact 创建 Wiki 草稿，经过人工编辑后发布为 WikiPageVersion，然后通过 WIKI_INDEX 任务进入检索。WikiLinkParser、WikiRelationService 和 WikiGraphService 会解析页面关系，KnowledgeGraphController 能暴露空间级图谱和节点详情。这个设计的价值是把“模型生成的临时答案”和“团队确认的长期知识”分开，同时保留版本、来源、Citation 和图谱关系。

常见追问：
- 如果发布后入索引失败怎么办？
- 如果 Wiki 链接指向不存在页面怎么办？
- 如果团队知识和个人研究要互通，边界怎么设计？

### Q2: Wiki 发布为什么要走异步入索引？

回答时按四步走：
1. 先说场景：团队 RAG 的回答可能是临时结论，Wiki 要承载人工确认后的长期稳定知识，并能被搜索、版本化和图谱化。
2. 再说方案：TeamWikiService 支持创建草稿、编辑、发布、归档、版本查看；WikiIndexTaskWorker 异步入索引；WikiRelationService/WikiGraphService/KnowledgeGraphService 管理链接、关系和图谱。
3. 再说收益：让团队知识库不只是文件检索，还能沉淀成结构化、可版本、可引用、可导航的长期知识层。
4. 最后落到真实代码锚点，不要停在概念。

可直接复述：

> Wiki 这块我会讲成团队长期知识层。团队 RAG 的回答和 Artifact 只是阶段性结果，不一定能直接成为稳定知识。NoteWeave 里可以从 ChatMessage 或 Artifact 创建 Wiki 草稿，经过人工编辑后发布为 WikiPageVersion，然后通过 WIKI_INDEX 任务进入检索。WikiLinkParser、WikiRelationService 和 WikiGraphService 会解析页面关系，KnowledgeGraphController 能暴露空间级图谱和节点详情。这个设计的价值是把“模型生成的临时答案”和“团队确认的长期知识”分开，同时保留版本、来源、Citation 和图谱关系。

常见追问：
- 如果发布后入索引失败怎么办？
- 如果 Wiki 链接指向不存在页面怎么办？
- 如果团队知识和个人研究要互通，边界怎么设计？

### Q3: WikiRetriever 在 Hybrid RAG 里解决什么问题？

回答时按四步走：
1. 先说场景：团队 RAG 的回答可能是临时结论，Wiki 要承载人工确认后的长期稳定知识，并能被搜索、版本化和图谱化。
2. 再说方案：TeamWikiService 支持创建草稿、编辑、发布、归档、版本查看；WikiIndexTaskWorker 异步入索引；WikiRelationService/WikiGraphService/KnowledgeGraphService 管理链接、关系和图谱。
3. 再说收益：让团队知识库不只是文件检索，还能沉淀成结构化、可版本、可引用、可导航的长期知识层。
4. 最后落到真实代码锚点，不要停在概念。

可直接复述：

> Wiki 这块我会讲成团队长期知识层。团队 RAG 的回答和 Artifact 只是阶段性结果，不一定能直接成为稳定知识。NoteWeave 里可以从 ChatMessage 或 Artifact 创建 Wiki 草稿，经过人工编辑后发布为 WikiPageVersion，然后通过 WIKI_INDEX 任务进入检索。WikiLinkParser、WikiRelationService 和 WikiGraphService 会解析页面关系，KnowledgeGraphController 能暴露空间级图谱和节点详情。这个设计的价值是把“模型生成的临时答案”和“团队确认的长期知识”分开，同时保留版本、来源、Citation 和图谱关系。

常见追问：
- 如果发布后入索引失败怎么办？
- 如果 Wiki 链接指向不存在页面怎么办？
- 如果团队知识和个人研究要互通，边界怎么设计？

### Q4: 知识图谱在当前项目里承担什么作用？

回答时按四步走：
1. 先说场景：团队 RAG 的回答可能是临时结论，Wiki 要承载人工确认后的长期稳定知识，并能被搜索、版本化和图谱化。
2. 再说方案：TeamWikiService 支持创建草稿、编辑、发布、归档、版本查看；WikiIndexTaskWorker 异步入索引；WikiRelationService/WikiGraphService/KnowledgeGraphService 管理链接、关系和图谱。
3. 再说收益：让团队知识库不只是文件检索，还能沉淀成结构化、可版本、可引用、可导航的长期知识层。
4. 最后落到真实代码锚点，不要停在概念。

可直接复述：

> Wiki 这块我会讲成团队长期知识层。团队 RAG 的回答和 Artifact 只是阶段性结果，不一定能直接成为稳定知识。NoteWeave 里可以从 ChatMessage 或 Artifact 创建 Wiki 草稿，经过人工编辑后发布为 WikiPageVersion，然后通过 WIKI_INDEX 任务进入检索。WikiLinkParser、WikiRelationService 和 WikiGraphService 会解析页面关系，KnowledgeGraphController 能暴露空间级图谱和节点详情。这个设计的价值是把“模型生成的临时答案”和“团队确认的长期知识”分开，同时保留版本、来源、Citation 和图谱关系。

常见追问：
- 如果发布后入索引失败怎么办？
- 如果 Wiki 链接指向不存在页面怎么办？
- 如果团队知识和个人研究要互通，边界怎么设计？

### Q5: Wiki 版本和 Citation 如何支撑可追溯？

回答时按四步走：
1. 先说场景：团队 RAG 的回答可能是临时结论，Wiki 要承载人工确认后的长期稳定知识，并能被搜索、版本化和图谱化。
2. 再说方案：TeamWikiService 支持创建草稿、编辑、发布、归档、版本查看；WikiIndexTaskWorker 异步入索引；WikiRelationService/WikiGraphService/KnowledgeGraphService 管理链接、关系和图谱。
3. 再说收益：让团队知识库不只是文件检索，还能沉淀成结构化、可版本、可引用、可导航的长期知识层。
4. 最后落到真实代码锚点，不要停在概念。

可直接复述：

> Wiki 这块我会讲成团队长期知识层。团队 RAG 的回答和 Artifact 只是阶段性结果，不一定能直接成为稳定知识。NoteWeave 里可以从 ChatMessage 或 Artifact 创建 Wiki 草稿，经过人工编辑后发布为 WikiPageVersion，然后通过 WIKI_INDEX 任务进入检索。WikiLinkParser、WikiRelationService 和 WikiGraphService 会解析页面关系，KnowledgeGraphController 能暴露空间级图谱和节点详情。这个设计的价值是把“模型生成的临时答案”和“团队确认的长期知识”分开，同时保留版本、来源、Citation 和图谱关系。

常见追问：
- 如果发布后入索引失败怎么办？
- 如果 Wiki 链接指向不存在页面怎么办？
- 如果团队知识和个人研究要互通，边界怎么设计？

## 5. 大厂深挖追问路径
1. 先问你做了什么。
2. 再问为什么这样设计，不用更简单方案。
3. 再问失败、重试、越权、删除、断线、重建索引时会发生什么。
4. 最后问如何量化效果和下一步演进。

把答案往下压一层：
- 业务层：团队 RAG 的回答可能是临时结论，Wiki 要承载人工确认后的长期稳定知识，并能被搜索、版本化和图谱化。
- 架构层：TeamWikiService 支持创建草稿、编辑、发布、归档、版本查看；WikiIndexTaskWorker 异步入索引；WikiRelationService/WikiGraphService/KnowledgeGraphService 管理链接、关系和图谱。
- 数据层：引用 MySQL、Redis、MinIO、ES、Kafka 或 Citation/Trace 的真实职责。
- 测试层：能说出对应 IntegrationTest 或 ServiceTest。
- 边界层：明确哪些是后续扩展，不冒充已落地。

## 6. 不能说满的地方
- 不要把当前 Wiki Graph 说成完整 GraphRAG 主线。
- 不要说复杂审批流已完成。
- 不要说自动维护可以替代人工确认。

## 7. 零基础记忆法
记住一句话：先讲“为什么需要这个模块”，再讲“请求从哪里来、状态落在哪里、失败怎么恢复、证据怎么追踪、权限怎么兜底”。按这个顺序答，大多数追问都能接住。

## 8. Wiki、Artifact、Document 的区别
| 对象 | 生命周期 | 可信度 | 是否自动进入检索 | 面试口径 |
|---|---|---|---|---|
| Document | 原始资料 | 取决于上传内容 | 解析、chunk、索引后进入检索 | 团队资料来源 |
| Artifact | 生成产物 | 需要用户判断 | 默认不自动变长期知识 | 阶段性工作成果 |
| Wiki | 人工确认后的团队知识 | 更稳定 | 发布后通过 WIKI_INDEX 进入检索 | 长期知识层 |

这三个对象不能混在一起讲。Document 是资料来源，Artifact 是生成结果，Wiki 是治理后的长期知识。把 Artifact 自动写 Wiki 看起来省操作，但会把模型草稿污染长期知识库。

## 9. Wiki 发布后检索不到怎么查
1. 先查 `wiki_page.status` 是否 PUBLISHED。
2. 再查 `publishedVersionId` 是否和索引任务 payload 一致。
3. 再查 `WIKI_INDEX` Task / TaskAttempt / TaskEvent 是否失败。
4. 再查 ES wiki 文档 id 是否是 `wiki:{wikiPageId}:{publishedVersionId}`。
5. 再查 HybridRetriever 是否打开 includeWiki，以及 WikiRetriever 是否按 spaceId 查询。
6. 如果结果能查到但用户看不到，再回到 Space 权限和 Citation 展示校验。

## 10. 图谱不能说满
当前 WikiGraph 和 KnowledgeGraph 更适合讲成“长期知识导航和关系维护”，不要讲成完整 GraphRAG 主链路。面试里可以说后续会把图谱关系用于 query expansion、实体跳转和更强的 evidence planning，但当前 RAG 主召回仍是 BM25、向量和 Wiki recall。
