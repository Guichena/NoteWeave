# NoteWeave 功能说明与架构文档

## 1. 项目定位

NoteWeave 是一个面向 **个人研究** 与 **团队知识协作** 的 AI 知识工作台。

系统既不是单纯的个人学习工具，也不是强企业内部化的知识管理系统，而是同时支持个人与团队两种使用形态：

- **个人研究工作台**：面向个人学习、研究、资料整理、技术调研、内容创作和知识管理。用户可以围绕一个主题收集资料，将资料编译为结构化知识卡片，并生成研究报告、学习指南、测验、阅读笔记、对比分析等产物。
- **团队知识空间**：面向小组、项目团队、课程小组、实验室、开源社区或企业团队等多人协作场景。团队可以沉淀项目文档、技术方案、会议纪要、FAQ、复盘记录和 Wiki，通过权限可控的大规模 RAG 问答与轻量 Wiki 沉淀，提高团队资料检索和知识复用效率。

项目核心目标是：

```text
让个人和团队都能围绕可信资料进行问答、研究、整理、生成和知识沉淀。
```

系统采用双模式设计：

- **团队知识空间**：面向多人共享的大规模资料，采用 RAG 检索增强生成，结合权限控制、多路召回、RRF 融合排序和轻量 Wiki 沉淀，解决团队资料“存得下、搜得到、问得准、可溯源、可沉淀”的问题。
- **个人研究工作台**：面向个人深度研究和长期知识管理，采用全量 LLM-Wiki 编译，将个人资料转化为 Article Card、Concept Card 和 Methodology Card，形成个人语义知识索引，支持长期知识融合、结构化写作、学习复盘和研究产物生成。

NoteWeave 的设计重点不是简单做一个 RAG 问答系统，而是将不同使用场景拆成两类链路：

```text
团队知识空间：大规模 RAG + 轻量 Wiki
个人研究工作台：全量 Wiki 编译 + Studio 产物生成
```

一句话定位：

> NoteWeave 是一个支持个人研究与团队协作的 AI 知识工作台，团队侧解决共享资料检索与知识沉淀，个人侧解决深度研究、知识组织与产物生成。

---

## 2. 核心设计原则

### 2.1 团队与个人分治

团队文档通常具有以下特点：

- 文档规模大，可能达到千级、万级。
- 权限复杂，需要空间级、知识库级、文档级权限过滤。
- 文档质量参差不齐，存在过期文档、重复文档和低价值会议纪要。
- 查询诉求以“快速搜索、准确问答、可溯源”为主。

因此团队侧不做全量 LLM-Wiki 编译，而采用大规模 RAG 作为主链路。

个人资料通常具有以下特点：

- 规模相对可控。
- 资料由用户主动收集，价值密度更高。
- 使用场景偏向深度理解、写作、复盘、学习和知识沉淀。
- 权限复杂度低。

因此个人侧采用全量 Wiki 编译，将所有个人资料转化为结构化知识索引。

### 2.2 Raw Source 作为事实源

无论是团队空间还是员工个人研究工作台，原始资料都作为不可随意修改的事实层存在。

```text
Raw Source = 原始事实来源
```

系统生成的摘要、卡片、Wiki、回答都需要能够回溯到 Raw Source 或其引用片段，从而降低幻觉风险。

### 2.3 Wiki 是高级语义索引

在 NoteWeave 中，Wiki 不是普通的富文本页面，而是一种高层语义索引。

团队侧 Wiki 是轻量 Wiki，主要用于沉淀高价值问答、FAQ、技术方案和复盘结论。

个人侧 Wiki 是完整 LLM-Wiki，由 Article Card、Concept Card、Methodology Card 和它们之间的关系组成。

```text
Chunk Index = 低级检索索引
Wiki Index = 高级语义索引
```

---

## 3. 系统整体功能概览

系统主要分为 8 个核心模块：

1. 用户与空间模块
2. 团队知识库模块
3. 团队 RAG 检索模块
4. 团队轻量 Wiki 模块
5. 员工个人研究工作台模块
6. 个人 Wiki Compiler 模块
7. 个人 Wiki 生成模块
8. Memory 与异步任务模块

整体结构如下：

```text
用户
 ├── 团队空间
 │    ├── 团队知识库
 │    ├── 团队文档
 │    ├── Chunk Index
 │    ├── Hybrid Retrieval + RRF
 │    ├── RAG 问答
 │    └── 轻量 Wiki 沉淀
 │
 └── 员工个人研究工作台
      ├── 个人资料
      ├── Article Card
      ├── Concept Card
      ├── Methodology Card
      ├── Wiki Graph
      └── Wiki-based Generation
```

---

## 4. 用户与空间模块

### 4.1 Space 统一建模

系统使用 Space 抽象个人空间和团队空间。

```text
Space
 ├── PERSONAL
 └── TEAM
```

每个用户注册后自动创建个人空间。团队空间由用户主动创建。

### 4.2 核心实体

```text
User
  ↓
Space
  ↓
SpaceMember
```

### 4.3 团队角色

团队空间采用轻量 RBAC 设计，MVP 阶段支持三种角色：

| 角色 | 权限说明 |
|---|---|
| OWNER | 管理空间、成员、知识库、文档和 Wiki |
| EDITOR | 上传文档、编辑 Wiki、发起 AI 问答 |
| VIEWER | 浏览资料、检索问答、查看 Wiki |

### 4.4 权限服务

封装统一权限服务：

```java
SpacePermissionService
```

核心方法：

```java
boolean canViewSpace(Long userId, Long spaceId);
boolean canManageSpace(Long userId, Long spaceId);
boolean canUploadDocument(Long userId, Long spaceId);
boolean canEditWiki(Long userId, Long spaceId);
boolean canAskQuestion(Long userId, Long spaceId);
```

所有团队侧检索和生成链路都必须经过权限过滤，避免越权召回。

---

## 5. 团队知识库模块

### 5.1 功能目标

团队知识库用于承载大规模团队文档，目标是：

```text
文档存得下
权限控得住
问题搜得到
答案能溯源
结论可沉淀
```

### 5.2 支持资料类型

MVP 支持：

- PDF
- Markdown
- Word 文档
- 纯文本
- 网页链接
- 团队 Wiki 页面

后续可扩展：

- 会议纪要
- 接口文档
- 代码文档
- 图片 OCR
- 音视频转写

### 5.3 团队文档处理流程

```text
团队文档上传
  ↓
文件存储
  ↓
文本解析
  ↓
Chunk 切片
  ↓
生成 Embedding
  ↓
写入 ES / 向量索引
  ↓
支持 RAG 问答
```

### 5.4 文档状态机

```text
UPLOADED
PARSING
CHUNKING
EMBEDDING
INDEXING
INDEXED
FAILED
```

MVP 可简化为：

```text
PENDING
PROCESSING
INDEXED
FAILED
```

### 5.5 主要数据对象

```text
KnowledgeBase
Document
DocumentChunk
IndexTask
```

Chunk 是团队 RAG 的最小检索单位。

---

## 6. 团队 RAG 检索模块

### 6.1 设计目标

团队侧采用大规模 RAG，重点解决：

- 大规模文档检索
- 权限过滤
- 多路召回
- 融合排序
- 证据上下文构造
- 引用溯源

### 6.2 团队 RAG 总流程

```text
团队问题
  ↓
SpacePermissionService 权限校验
  ↓
QueryRewriteService 查询改写
  ↓
HybridRetriever 多路召回
      ├── BM25Retriever
      ├── VectorRetriever
      └── WikiRetriever
  ↓
RRFFusionService 融合排序
  ↓
EvidencePostProcessor
      ├── 去重
      ├── 相邻 chunk 合并
      ├── 同文档限流
      └── 引用信息整理
  ↓
AnswerGenerator
  ↓
答案 + 引用 + 相关文档 + 一键沉淀 Wiki
```

---

## 7. 团队 Hybrid Retrieval + RRF 设计

### 7.1 为什么使用多路召回

单纯 BM25 的问题：

- 依赖关键词匹配。
- 容易漏掉同义表达。
- 对语义问题理解不足。

单纯向量检索的问题：

- 可能召回语义相近但业务不相关的片段。
- 对专有名词、接口名、错误码、配置项不一定稳定。

因此团队侧采用 Hybrid Retrieval。

### 7.2 多路召回器

#### BM25Retriever

适合：

- 专有名词
- 接口名
- 错误码
- 配置项
- 系统模块名
- 明确关键词

#### VectorRetriever

适合：

- 语义相似问题
- 同义表达
- 概念解释
- 流程总结

#### WikiRetriever

团队 Wiki 页面是人工沉淀过的高质量内容，因此单独作为一路召回源。

适合：

- FAQ
- 项目手册
- 技术方案摘要
- 事故复盘结论
- 已发布 Wiki 页面

### 7.3 RRF 融合排序

RRF，全称 Reciprocal Rank Fusion，用于融合多个召回器的排序结果。

RRF 不依赖不同检索器的原始分数，只基于各自排序名次进行融合，适合解决 BM25 分数和向量相似度不可直接比较的问题。

基础公式：

```text
score(d) = Σ 1 / (k + rank_i(d))
```

通常 k 取 60。

### 7.4 Weighted RRF

团队场景下，可以对不同召回源设置权重。

```text
score(d) = Σ weight_i / (k + rank_i(d))
```

推荐权重：

| 召回源 | 权重 |
|---|---:|
| BM25 | 1.0 |
| Vector | 1.0 |
| Wiki | 1.3 |

Wiki 作为人工沉淀内容，可信度更高，因此权重略高。

### 7.5 融合后的后处理

RRF 排序之后，需要进行 evidence 后处理：

1. Chunk 去重
2. 同文档相邻 Chunk 合并
3. 同一文档召回数量限流
4. 引用信息整理
5. 上下文长度控制

推荐规则：

```text
每篇文档最多保留 3 个 evidence block
相邻 chunk 自动合并
最终保留 Top 8 ~ 12 个 evidence block
```

这样可以避免上下文被单篇文档刷屏，提高答案覆盖面。

---

## 8. 团队轻量 Wiki 模块

### 8.1 团队 Wiki 定位

团队 Wiki 不做全量 LLM-Wiki 编译，不维护复杂 Concept Card 图谱。

它是团队高质量知识沉淀层：

```text
团队 Wiki = 人工可编辑的高质量文档源
```

### 8.2 团队 Wiki 来源

团队 Wiki 可以来自：

- RAG 问答结果一键沉淀
- Editor 手动创建
- Owner 发布规范文档
- 技术方案总结
- 事故复盘结论
- 项目 FAQ

### 8.3 团队 Wiki 生成流程

```text
团队 RAG 问答
  ↓
用户认为答案有价值
  ↓
一键生成 Wiki 草稿
  ↓
Editor / Owner 修改
  ↓
发布 Wiki 页面
  ↓
Wiki 页面进入团队 RAG 索引
```

### 8.4 Wiki 页面进入 RAG

团队 Wiki 页面本身作为高质量 source 进入 RAG 索引。

```text
source_type = WIKI
is_wiki = true
quality_score = 1.2
```

后续检索时，WikiRetriever 会优先召回这些内容。

---

## 9. 员工个人研究工作台模块

### 9.1 功能目标

员工个人研究工作台面向个人学习、写作、个人成长与工作学习、技术研究和知识管理。

目标是：

```text
资料读得深
知识能融合
文章写得出
面试能复盘
长期可沉淀
```

### 9.2 与团队空间的区别

| 维度 | 团队空间 | 员工个人研究工作台 |
|---|---|---|
| 资料规模 | 大，千级/万级 | 小到中等，可控 |
| 默认处理 | Chunk + RAG | 全量 Wiki 编译 |
| 核心索引 | Document Chunk | Article / Concept / Methodology |
| Wiki 形态 | 轻量页面沉淀 | 高级语义索引 |
| 生成方式 | RAG 证据生成 | Wiki 组织生成 |
| 权限复杂度 | 高 | 低 |
| 重点 | 搜索、问答、权限、沉淀 | 融合、写作、复盘、记忆 |

### 9.3 个人资料处理流程

```text
上传 / 导入资料
  ↓
解析原文
  ↓
生成 Article Card
  ↓
抽取 Candidate Concepts
  ↓
概念归一化
  ↓
生成或更新 Concept Card
  ↓
建立 Article ↔ Concept 双向链接
  ↓
更新个人 Wiki Index
```

个人资料默认进行全量 Wiki 编译。

---

## 10. 个人 Wiki Compiler 模块

### 10.1 模块定位

Wiki Compiler 是员工个人研究工作台的核心模块，负责将原始资料编译成结构化知识索引。

```text
Raw Source
  ↓
Article Card
  ↓
Concept Card
  ↓
Methodology Card
  ↓
Wiki Graph
```

### 10.2 Article Card

一篇资料对应一张 Article Card。

字段设计：

```text
id
user_id
source_id
title
summary
key_points
tags
concept_ids
evidence_quotes
created_at
updated_at
```

Article Card 的作用：

- 将原始资料转化为结构化摘要。
- 作为原文和概念之间的桥梁。
- 为后续写作、问答、复盘提供文档级入口。

### 10.3 Concept Card

Concept Card 用于融合多篇资料中的相同或相近概念。

字段设计：

```text
id
user_id
name
aliases
definition
explanation
use_cases
common_misunderstandings
related_concepts
source_article_ids
evidence_links
confidence
created_at
updated_at
```

Concept Card 的作用：

- 概念去重。
- 多文档知识融合。
- 维护长期知识节点。
- 支持跨资料问答和写作。

### 10.4 Candidate Concept 与 Canonical Concept

个人资料默认全量编译，但概念仍然需要区分候选概念和正式概念。

```text
Candidate Concept：从文章中抽取出的候选概念
Canonical Concept：经过归一化、融合后的正式概念卡片
```

处理流程：

```text
抽取 Candidate Concepts
  ↓
名称归一化
  ↓
与已有 Concept Card 做相似度匹配
  ↓
高置信度：合并到已有 Concept Card
低置信度：创建新的 Concept Card
```

MVP 阶段可以采用：

```text
名称归一化 + embedding 相似度 + LLM 判断
```

### 10.5 Methodology Card

Methodology Card 用于描述某类任务的知识组织方式。

字段设计：

```text
id
user_id
scene
problem_type
workflow
required_concepts
output_structure
quality_checklist
example_outputs
created_at
updated_at
```

示例方法论：

- 个人成长与工作学习项目总结与方案表达方法论
- 技术方案设计方法论
- 论文阅读方法论
- 知识问答训练方法论
- 故障复盘方法论
- AI 写作框架方法论

Methodology Card 的作用：

- 将 Concept Card 组合成可执行的写作或分析框架。
- 指导 AI 生成结构化输出。
- 支持面试回答、文章生成、复盘总结等任务。

---

## 11. 个人 Wiki-based Generation 模块

### 11.1 个人生成总流程

```text
个人问题 / 写作任务
  ↓
匹配 Methodology Card
  ↓
检索相关 Concept Card
  ↓
定位 Article Card
  ↓
必要时回溯 Raw Source 证据
  ↓
生成结构化输出
```

### 11.2 生成逻辑

个人侧不是直接从原始文档 RAG 出答案，而是：

```text
Wiki 组织思路
Raw Source 提供证据
LLM 生成答案
```

具体过程：

1. 分析用户任务类型。
2. 匹配是否存在合适的 Methodology Card。
3. 根据方法论选取相关 Concept Card。
4. 通过 Concept Card 找到对应 Article Card。
5. 回溯原文证据片段。
6. 构造 Wiki-aware Prompt。
7. 生成结构化回答、文章、复盘或方案表达。

### 11.3 适用场景

个人 Wiki-based Generation 适合：

- 生成个人成长与工作学习项目亮点
- 整理面试回答
- 生成学习路线
- 论文阅读总结
- 技术博客写作
- 复盘某个知识主题
- 比较多个概念异同
- 从个人资料中提炼方法论

---

## 12. Memory 与异步任务模块

### 12.1 Memory 定位

Memory 用于记录用户长期偏好和学习状态。

个人 Memory 示例：

```text
用户正在准备 Java 后端个人成长与工作学习
用户偏好面试官视角回答
用户重点关注 RAG、Agent、Memory、Redis、Kafka
用户希望输出结构化、可放入简历的项目表达
```

Memory 不替代知识库，而是参与生成时的个性化上下文。

### 12.2 Memory 使用位置

个人侧：

- Wiki 生成时调整表达风格。
- 方法论匹配时结合用户目标。
- 知识复盘时结合用户当前学习阶段。

团队侧：

MVP 阶段不做复杂团队 Memory，避免权限和一致性问题。

### 12.3 异步任务

以下任务均适合异步执行：

团队侧：

- 文档解析
- Chunk 切片
- Embedding 生成
- 索引写入
- Wiki 页面索引更新

个人侧：

- Article Card 生成
- Candidate Concept 抽取
- Concept Card 融合
- Methodology Card 生成
- Wiki Index 更新

推荐技术方案：

```text
Kafka / RabbitMQ / Redis Stream + Worker
```

MVP 可先使用：

```text
Redis Stream + 异步线程池
```

---

## 13. 数据库核心表设计概览

### 13.1 用户与空间

```text
user
space
space_member
```

### 13.2 团队知识库

```text
knowledge_base
document
document_chunk
index_task
```

### 13.3 团队轻量 Wiki

```text
wiki_page
wiki_page_version
```

### 13.4 员工个人研究工作台

```text
personal_source
article_card
concept_card
concept_alias
concept_relation
article_concept_relation
methodology_card
```

### 13.5 问答与生成

```text
chat_session
chat_message
generation_task
citation
```

---

## 14. 技术架构建议

### 14.1 后端技术栈

结合当前 Java 后端方向，推荐：

```text
Spring Boot
Spring Security / JWT
MyBatis-Plus / JPA
MySQL
Redis
Elasticsearch
Kafka / RabbitMQ / Redis Stream
对象存储 MinIO / OSS
LLM API
Embedding Model
```

### 14.2 检索存储

团队侧推荐：

```text
MySQL：元数据、权限、状态
Elasticsearch：BM25、文本索引、过滤字段
向量字段 / 向量库：语义召回
Redis：缓存、任务状态、热点数据
```

MVP 可以先使用：

```text
Elasticsearch BM25 + dense_vector
```

避免引入过多基础设施。

### 14.3 文件存储

```text
MinIO / OSS
```

存储：

- 原始文件
- 解析后的纯文本
- 中间处理结果
- 卡片生成缓存

---

## 15. 关键服务设计

### 15.1 团队侧服务

```text
TeamKnowledgeBaseService
DocumentIngestionService
DocumentParserService
ChunkService
EmbeddingService
HybridRetriever
Bm25Retriever
VectorRetriever
WikiRetriever
RRFFusionService
EvidencePostProcessor
TeamAnswerGenerator
TeamWikiService
```

### 15.2 个人侧服务

```text
PersonalResearchService
PersonalSourceService
WikiCompilerService
ArticleCardService
ConceptExtractionService
ConceptMergeService
MethodologyCardService
WikiGraphService
PersonalGenerationService
EvidenceBacktraceService
```

### 15.3 通用服务

```text
UserService
SpaceService
SpacePermissionService
FileStorageService
LLMClient
EmbeddingClient
MemoryService
TaskQueueService
CitationService
```

---

## 16. 核心接口设计概览

### 16.1 用户与空间

```http
POST /api/auth/register
POST /api/auth/login
GET  /api/users/me

POST /api/spaces
GET  /api/spaces
GET  /api/spaces/{spaceId}
POST /api/spaces/{spaceId}/members
PUT  /api/spaces/{spaceId}/members/{memberId}/role
DELETE /api/spaces/{spaceId}/members/{memberId}
```

### 16.2 团队知识库

```http
POST /api/team/spaces/{spaceId}/knowledge-bases
GET  /api/team/spaces/{spaceId}/knowledge-bases

POST /api/team/knowledge-bases/{kbId}/documents
GET  /api/team/knowledge-bases/{kbId}/documents
GET  /api/team/documents/{documentId}
DELETE /api/team/documents/{documentId}
```

### 16.3 团队 RAG 问答

```http
POST /api/team/spaces/{spaceId}/chat
GET  /api/team/chat-sessions/{sessionId}
```

### 16.4 团队 Wiki

```http
POST /api/team/spaces/{spaceId}/wiki-pages
GET  /api/team/spaces/{spaceId}/wiki-pages
GET  /api/team/wiki-pages/{pageId}
PUT  /api/team/wiki-pages/{pageId}
POST /api/team/wiki-pages/{pageId}/publish
POST /api/team/chat-messages/{messageId}/create-wiki-draft
```

### 16.5 员工个人研究工作台

```http
POST /api/personal/sources
GET  /api/personal/sources
POST /api/personal/sources/{sourceId}/compile

GET  /api/personal/article-cards
GET  /api/personal/concept-cards
GET  /api/personal/methodology-cards

POST /api/personal/chat
POST /api/personal/write
POST /api/personal/review
```

---

## 17. 核心流程详解

### 17.1 团队文档上传与索引流程

```text
用户上传文档
  ↓
权限校验：canUploadDocument
  ↓
保存原始文件到对象存储
  ↓
创建 document 记录，状态 PENDING
  ↓
投递 index_task
  ↓
Worker 解析文本
  ↓
Chunk 切片
  ↓
生成 embedding
  ↓
写入 ES / 向量索引
  ↓
更新 document 状态为 INDEXED
```

### 17.2 团队问答流程

```text
用户在团队空间提问
  ↓
权限校验：canAskQuestion
  ↓
构造检索 filter
  ↓
Query Rewrite
  ↓
BM25 / Vector / Wiki 多路召回
  ↓
Weighted RRF 融合排序
  ↓
EvidencePostProcessor 后处理
  ↓
构造 Prompt
  ↓
LLM 生成回答
  ↓
返回答案、引用、相关文档
  ↓
用户可一键沉淀为 Wiki
```

### 17.3 团队 Wiki 沉淀流程

```text
用户点击“一键沉淀为 Wiki”
  ↓
系统基于问答内容生成 Wiki 草稿
  ↓
Editor / Owner 修改
  ↓
发布 Wiki 页面
  ↓
Wiki 页面进入团队索引
  ↓
后续 RAG 中作为高质量召回源
```

### 17.4 个人资料 Wiki 编译流程

```text
用户上传个人资料
  ↓
解析原文
  ↓
生成 Article Card
  ↓
抽取 Candidate Concepts
  ↓
概念归一化
  ↓
与已有 Concept Card 匹配
  ↓
合并或创建 Concept Card
  ↓
建立 Article ↔ Concept 双向链接
  ↓
更新 Wiki Graph
```

### 17.5 个人生成流程

```text
用户发起写作 / 问答 / 复盘任务
  ↓
识别任务类型
  ↓
匹配 Methodology Card
  ↓
选择相关 Concept Card
  ↓
定位 Article Card
  ↓
回溯 Raw Source 证据
  ↓
构造 Wiki-aware Prompt
  ↓
生成结构化输出
```

---

## 18. 项目亮点总结

### 18.1 差异化架构

系统没有对团队和个人采用同一套知识处理方式，而是根据使用场景分治：

```text
团队：大规模 RAG + 轻量 Wiki
个人：全量 Wiki 编译 + Wiki-based Generation
```

### 18.2 团队侧工程亮点

- 权限过滤与 RAG 检索融合。
- BM25、向量检索、Wiki 高质量页面多路召回。
- Weighted RRF 融合排序。
- Chunk 去重、相邻片段合并、同文档限流。
- 答案带引用来源。
- 高价值答案沉淀为团队轻量 Wiki。

### 18.3 个人侧 AI 应用亮点

- 借鉴 LLM-Wiki 思路，将资料全量编译为结构化知识索引。
- Article Card 实现文章级知识抽取。
- Concept Card 实现跨文章概念融合。
- Methodology Card 支撑写作、复盘和方案表达。
- Wiki 组织思路，Raw Source 提供证据。
- 支持长期个人知识沉淀。

### 18.4 可扩展性亮点

- 异步任务处理文档解析、索引和 Wiki 编译。
- Space 抽象统一个人与团队。
- Retriever 接口支持扩展更多召回源。
- Wiki 页面可反向进入团队 RAG 索引。
- 个人 Wiki 可扩展知识图谱可视化、卡片审核、版本管理。

---

## 19. NotebookLM 风格生成与 Research 功能补充

为了让 NoteWeave 更接近 NotebookLM 的产品体验，可以在现有“团队 RAG + 个人 Wiki”架构上增加两类能力：

1. **基于资料的多形态生成能力**：报告、测验、学习指南、FAQ、时间线、简报等。
2. **Research 资料收集能力**：支持用户围绕一个主题主动添加文献、网页、论文和外部资料，并纳入员工个人研究工作台或团队知识库。

这两类能力不改变系统主架构，而是作为员工个人研究工作台和团队空间之上的应用层功能。

---

### 19.1 NotebookLM 风格生成能力定位

NotebookLM 的核心体验不是只做问答，而是围绕一组资料生成不同形态的学习与研究产物。

NoteWeave 可以将这些能力抽象为：

```text
Source / Wiki Index / RAG Evidence
        ↓
Generation Template
        ↓
结构化内容产物
```

也就是说，报告、测验、学习指南等不是单独模块，而是基于统一生成引擎的不同 `task_type`。

推荐支持的生成类型：

| 生成类型 | 适用模式 | 说明 |
|---|---|---|
| Research Report 研究报告 | 个人为主，团队可用 | 基于资料生成结构化长报告 |
| Study Guide 学习指南 | 个人 | 提炼重点、概念、学习路径 |
| Quiz 测验题 | 个人 | 根据资料生成选择题、简答题、答案解析 |
| FAQ 常见问题 | 团队/个人 | 从资料中提取高频问题与回答 |
| Briefing 简报 | 团队/个人 | 生成短版摘要、会议前速览 |
| Timeline 时间线 | 团队/个人 | 适合事件、项目、历史过程类资料 |
| Comparison 对比分析 | 个人/团队 | 比较多个方案、论文、技术路线 |
| Mind Map Outline 思维导图大纲 | 个人 | 输出层级化知识结构 |
| Work Prep 知识复盘 | 个人 | 将资料转成自测问题、回答与追问 |

---

### 19.2 员工个人研究工作台中的生成能力

员工个人研究工作台已经采用全量 Wiki 编译，因此 NotebookLM 风格生成可以优先基于个人 Wiki Index。

个人生成链路：

```text
用户选择生成类型
  ↓
读取 Methodology Card / Generation Template
  ↓
检索相关 Concept Card
  ↓
定位 Article Card
  ↓
回溯 Raw Source 证据
  ↓
生成结构化内容
  ↓
保存为个人输出 / Wiki 页面 / 学习记录
```

#### 研究报告 Research Report

适合用户基于多篇资料生成一份完整报告。

输出结构示例：

```text
1. 研究主题
2. 背景与问题定义
3. 核心观点总结
4. 关键概念解释
5. 多资料观点对比
6. 证据与引用
7. 结论与建议
8. 后续可研究问题
```

个人侧报告优先使用：

```text
Methodology Card → Concept Card → Article Card → Raw Evidence
```

#### 测验 Quiz

适合学习和面试复习。

题型可以包括：

```text
单选题
多选题
判断题
简答题
场景题
面试追问题
```

每道题应包含：

```text
题目
选项
正确答案
答案解析
对应知识点
来源引用
难度等级
```

测验生成流程：

```text
选择资料 / 概念范围
  ↓
抽取核心 Concept Card
  ↓
根据 Bloom Taxonomy 或难度规则生成题目
  ↓
回溯 Article Card / Raw Source 生成解析
  ↓
保存 quiz_session
```

#### 学习指南 Study Guide

适合将资料转成学习材料。

输出结构：

```text
核心概念
重点摘要
知识点之间的关系
推荐学习顺序
易错点
自测问题
延伸阅读
```

#### 方案表达训练 Work Prep

这是个人成长与工作学习项目里很适合突出的能力。

输出结构：

```text
高频自测问题
标准回答
追问方向
项目结合点
常见误区
可量化表达
```

---

### 19.3 团队空间中的生成能力

团队侧不走全量 Wiki 编译，因此生成能力应基于 RAG Evidence 和轻量 Wiki。

团队生成链路：

```text
用户选择生成类型
  ↓
权限过滤
  ↓
Hybrid Retrieval + RRF 召回证据
  ↓
读取团队 Wiki 高质量内容
  ↓
生成结构化产物
  ↓
可保存为 Wiki 草稿
```

团队侧适合支持：

| 生成类型 | 场景 |
|---|---|
| FAQ | 从团队文档中整理常见问题 |
| Briefing | 项目资料快速摘要 |
| Meeting Prep | 会前资料速览 |
| Technical Summary | 技术方案摘要 |
| Incident Review Draft | 事故复盘草稿 |
| Onboarding Guide | 新人入门指南 |

团队侧生成结果默认不直接发布，而是进入 Wiki 草稿，由 Editor / Owner 审核后发布。

```text
生成结果
  ↓
Wiki Draft
  ↓
人工编辑
  ↓
发布
  ↓
进入团队 RAG 索引
```

---

### 19.4 Generation Template 设计

为了避免每种生成能力都硬编码，可以设计统一的生成模板表。

```text
generation_template
```

核心字段：

```text
id
name
task_type
mode_scope        // PERSONAL / TEAM / BOTH
description
input_requirements
output_schema
prompt_template
quality_checklist
created_at
updated_at
```

示例 task_type：

```text
RESEARCH_REPORT
QUIZ
STUDY_GUIDE
FAQ
BRIEFING
TIMELINE
COMPARISON
INTERVIEW_PREP
MINDMAP_OUTLINE
```

这样系统可以统一处理不同生成任务：

```text
用户选择 task_type
  ↓
加载 generation_template
  ↓
根据空间类型选择证据来源
  ↓
生成结构化输出
```

---

### 19.5 生成结果存储

生成结果不应该只存在聊天记录中，而应该成为可复用资产。

建议增加：

```text
generated_artifact
```

字段：

```text
id
user_id
space_id
source_scope_type     // TEAM_SPACE / PERSONAL_RESEARCH
artifact_type         // REPORT / QUIZ / GUIDE / FAQ / BRIEFING
title
content
source_ids
citation_ids
status                // DRAFT / PUBLISHED / ARCHIVED
created_at
updated_at
```

Quiz 可以单独拆表：

```text
quiz
quiz_question
quiz_answer_record
```

用于支持用户答题、评分和复习。

---

### 19.6 Research 添加文献功能

NotebookLM 的 Research 能力可以理解为：用户围绕一个主题不断收集资料，并把资料加入当前研究上下文。

NoteWeave 可以设计为：

```text
Research Project / Research Topic
  ↓
添加资料
  ↓
资料解析
  ↓
进入个人 Wiki 编译或团队 RAG 索引
  ↓
生成报告、测验、指南等产物
```

#### 个人 Research

个人侧适合做成一个独立的 Research Project。

```text
Research Project
 ├── 主题
 ├── 研究目标
 ├── Sources
 ├── Article Cards
 ├── Concept Cards
 ├── Generated Artifacts
 └── Research Notes
```

添加资料方式：

```text
上传 PDF
粘贴网页链接
粘贴文本
导入 Markdown
添加论文 DOI / arXiv 链接
手动添加书籍 / 博客引用
```

个人 Research 添加后的流程：

```text
添加文献 / 网页 / 资料
  ↓
抓取或解析原文
  ↓
生成 Source 记录
  ↓
自动进入 Wiki Compiler
  ↓
更新 Article Card / Concept Card
  ↓
更新 Research Project 的知识地图
```

#### 团队 Research

团队侧可以支持“添加到团队知识库”，但不默认深度编译。

```text
添加外部资料
  ↓
权限校验
  ↓
保存为团队 Document
  ↓
解析切片
  ↓
进入团队 RAG 索引
  ↓
可选生成 Wiki 草稿
```

---

### 19.7 Research Source Discovery 资料发现

如果项目想进一步增强，可以加入资料发现功能。

MVP 阶段可以先支持用户手动添加链接。

增强版可以支持：

```text
基于关键词搜索论文
基于主题搜索网页
基于已有资料推荐相关资料
基于 Concept Card 推荐缺失资料
```

推荐流程：

```text
用户输入研究主题
  ↓
系统生成检索关键词
  ↓
调用外部搜索 / 论文 API
  ↓
返回候选资料
  ↓
用户选择加入 Research Project
  ↓
进入解析和编译流程
```

可接入的数据源：

```text
网页搜索
arXiv
Semantic Scholar
CrossRef
GitHub README / Issue / PR
用户手动上传文件
```

个人成长与工作学习项目中，MVP 不建议一开始就做复杂外部搜索，可以先实现：

```text
手动添加 URL + PDF 上传 + 主题归档
```

后续把资料发现作为扩展亮点。

---

### 19.8 Research Project 数据模型

建议新增：

```text
research_project
research_source
research_artifact
```

#### research_project

```text
id
user_id
space_id
project_type       // PERSONAL / TEAM
title
description
research_goal
status
created_at
updated_at
```

#### research_source

```text
id
project_id
source_id
source_type        // PDF / URL / TEXT / PAPER / WIKI / TEAM_DOCUMENT
import_status
compile_status
created_at
updated_at
```

#### research_artifact

```text
id
project_id
artifact_id
artifact_type      // REPORT / QUIZ / GUIDE / FAQ / BRIEFING
created_at
```

个人 Research Project 与个人 Wiki 深度绑定；团队 Research Project 与团队 RAG 和 Wiki Draft 绑定。

---

### 19.9 加入后的整体功能结构

加入 NotebookLM 风格功能后，系统结构变为：

```text
团队空间
  ├── 团队知识库
  ├── 大规模 RAG
  ├── Hybrid Retrieval + RRF
  ├── 轻量 Wiki
  ├── FAQ / Briefing / Onboarding Guide 生成
  └── 团队 Research 资料归档

员工个人研究工作台
  ├── Research Project
  ├── Source 添加
  ├── 全量 Wiki Compiler
  ├── Article / Concept / Methodology Card
  ├── Report / Quiz / Study Guide / Work Prep 生成
  └── Generated Artifact 管理
```

---

### 19.10 新增能力的 MVP 建议

第一阶段建议只做：

个人侧：

```text
Research Project
PDF / URL / 文本添加资料
Article Card / Concept Card 编译
研究报告生成
测验生成
学习指南生成
```

团队侧：

```text
RAG 问答结果生成 Wiki 草稿
FAQ 生成
Briefing 生成
```

第二阶段再做：

```text
论文搜索
自动推荐相关文献
Quiz 答题记录
时间线生成
对比分析
Onboarding Guide
```

这样可以保持项目边界清晰，不会因为 NotebookLM 功能过多而失控。

---

### 19.11 简历表述补充

可以补充为：

> 在员工个人研究工作台中引入 Research Project 能力，支持用户围绕主题添加 PDF、网页和文本资料，并自动进入 LLM-Wiki 编译流程；基于 Article Card、Concept Card 和 Methodology Card 设计多任务生成引擎，支持研究报告、测验题、学习指南和知识复盘等 NotebookLM 风格内容生成。

团队侧可以补充为：

> 团队空间支持将 RAG 问答结果、FAQ 和项目简报生成 Wiki 草稿，经人工审核后发布，并重新进入团队检索索引，形成“问答—沉淀—再检索”的知识闭环。

---

## 20. 纯按钮触发 + 右侧 Studio 产物生成架构

NoteWeave 的交互最终调整为 **纯按钮触发、弹窗配置、后台异步执行、右侧 Studio 展示产物**。

这意味着：

> 用户不需要通过自然语言发起复杂生成任务，而是通过明确的按钮、菜单和弹窗来触发报告、测验、学习指南、FAQ、Briefing、Wiki 草稿、文献导入等能力。Chat 可以保留为资料问答入口，但复杂生成类能力主要由按钮触发。

整体产品形态更接近：

```text
左侧：Sources / Wiki / 文档范围
中间：资料问答 Chat
右侧：Studio / Artifacts / 生成产物
```

其中右侧 Studio 是生成能力的核心入口。

---

### 20.1 交互原则

系统交互采用：

```text
按钮触发
  ↓
弹窗配置
  ↓
创建异步任务
  ↓
后台 Agent + Skill 执行
  ↓
右侧 Studio 展示 Artifact
```

用户不需要在对话中描述复杂参数，而是通过弹窗完成配置。

例如：

```text
点击“生成测验”
  ↓
弹窗选择资料范围、题型、难度、题目数量
  ↓
点击确认
  ↓
系统创建异步任务
  ↓
后台调用 QuizGenerateSkill
  ↓
右侧 Studio 展示 Quiz Artifact
```

---

### 20.2 Chat 与 Studio 的职责划分

Chat 主要负责资料问答：

```text
基于团队 RAG 的文档问答
基于个人 Wiki 的知识问答
多轮追问
引用溯源
解释某个来源片段
```

Studio 主要负责结构化产物生成：

```text
研究报告
测验题
学习指南
FAQ
Briefing
Timeline
Comparison
Work Prep
Mind Map Outline
Audio Script
Wiki Draft
Onboarding Guide
```

也就是说：

```text
Chat = 问答与追问
Studio = 生成与产物管理
```

---

### 20.3 右侧 Studio 产物类型

右侧 Studio 不只生成 Wiki，也会生成大量不融入 Wiki 的独立 Artifact。

Artifact 分为两类：

#### 可沉淀类 Artifact

这类产物可以进一步发布或沉淀到 Wiki。

```text
Wiki Draft
FAQ
Onboarding Guide
Technical Summary
Incident Review Draft
```

#### 独立产物类 Artifact

这类产物默认不进入 Wiki，只作为用户的学习、研究或输出结果保存。

```text
Research Report
Quiz
Study Guide
Briefing
Timeline
Comparison
Work Prep
Mind Map Outline
Audio Overview Script
Presentation Outline
Reading Notes
```

这点很重要：

> 不是所有生成结果都应该融入 Wiki。Wiki 只沉淀长期稳定、可复用、经过确认的知识；报告、测验、简报、学习指南等更多是阶段性产物，默认作为 Artifact 管理。

---

### 20.4 Artifact 生命周期

Artifact 的生命周期独立于 Wiki。

```text
生成任务创建
  ↓
后台异步执行
  ↓
生成 Artifact
  ↓
右侧 Studio 展示
  ↓
用户查看 / 编辑 / 重新生成 / 导出 / 复制
  ↓
可选发布为 Wiki 或关联到 Research Project
```

Artifact 状态：

```text
GENERATING
READY
FAILED
ARCHIVED
PUBLISHED_TO_WIKI
```

Artifact 操作：

```text
查看
编辑
重新生成
基于它生成新产物
导出 Markdown / PDF
复制到剪贴板
发布为 Wiki
删除 / 归档
```

---

### 20.5 产物与 Wiki 的关系

Wiki 是长期知识沉淀层，Artifact 是任务产物层。

二者关系如下：

```text
Raw Source / RAG / Wiki Index
        ↓
Skill 生成
        ↓
Artifact
        ↓
可选发布为 Wiki
```

默认规则：

| 产物类型 | 默认是否进入 Wiki | 原因 |
|---|---|---|
| Wiki Draft | 是，审核后发布 | 本身就是 Wiki 草稿 |
| FAQ | 可选 | 团队常见问题适合沉淀 |
| Onboarding Guide | 可选 | 适合团队长期复用 |
| Technical Summary | 可选 | 需要人工确认后沉淀 |
| Research Report | 否 | 偏阶段性研究输出 |
| Quiz | 否 | 偏学习训练产物 |
| Study Guide | 否 | 偏个人学习材料 |
| Briefing | 否 | 偏短期阅读材料 |
| Timeline | 否 | 视场景可选 |
| Work Prep | 否 | 偏个人工作学习训练 |

---

### 20.6 Studio 按钮设计

#### 员工个人研究工作台 Studio

推荐按钮：

```text
生成研究报告
生成测验
生成学习指南
生成方案表达训练
生成思维导图大纲
生成对比分析
生成时间线
生成阅读笔记
添加文献
编译个人 Wiki
```

#### 团队空间 Studio

推荐按钮：

```text
生成 FAQ
生成 Briefing
生成 Wiki 草稿
生成新人入门指南
生成技术方案摘要
生成事故复盘草稿
生成会议准备材料
```

---

### 20.7 弹窗配置设计

每个 Studio 按钮都对应一个配置弹窗。

#### 生成研究报告弹窗

字段：

```text
报告主题
资料范围
报告长度
报告风格
是否包含引用
是否包含对比分析
是否保存到 Research Project
```

#### 生成测验弹窗

字段：

```text
资料范围
题目数量
题型
难度
是否生成答案解析
是否按概念分类
```

#### 生成学习指南弹窗

字段：

```text
资料范围
学习目标
学习时长
输出风格
是否包含自测问题
是否包含延伸阅读
```

#### 生成 Briefing 弹窗

字段：

```text
资料范围
目标读者
长度
是否包含行动项
是否包含风险点
```

#### 添加文献弹窗

字段：

```text
URL / DOI / arXiv 链接 / PDF
所属 Research Project
是否立即编译个人 Wiki
标签
备注
```

#### 生成 Wiki 草稿弹窗

字段：

```text
来源范围
目标 Wiki 空间
页面标题
生成风格
是否包含引用
是否需要人工审核
```

---

### 20.8 Skill 与 Agent 的定位

在纯按钮触发模式下，Skill 和 Agent 仍然是后端核心亮点。

```text
Studio Button
  ↓
TaskService
  ↓
Agent Plan
  ↓
Skill Registry
  ↓
RAG / Wiki / Citation / Memory
  ↓
Artifact Store
```

Skill 是可复用能力：

```text
ReportGenerateSkill
QuizGenerateSkill
StudyGuideSkill
InterviewPrepSkill
BriefingSkill
FAQSkill
WikiDraftSkill
SourceImportSkill
WikiCompileSkill
CitationBacktraceSkill
```

Agent 负责组合 Skill：

```text
PersonalResearchAgent：报告、测验、学习指南、方案表达训练
TeamKnowledgeAgent：FAQ、Briefing、Wiki 草稿、新人指南
ImportAgent：文献导入、网页抓取、文件解析
WikiCompileAgent：个人 Wiki 编译、概念融合
```

此时 Agent 不依赖用户自由对话触发，而是由按钮任务明确触发，稳定性更高。

---

### 20.9 异步任务中心

报告、测验、Wiki 编译、文献导入都可能耗时较长，因此必须设计任务中心。

核心任务类型：

```text
DOCUMENT_INDEX_TASK
SOURCE_IMPORT_TASK
WIKI_COMPILE_TASK
REPORT_GENERATION_TASK
QUIZ_GENERATION_TASK
STUDY_GUIDE_TASK
INTERVIEW_PREP_TASK
FAQ_GENERATION_TASK
BRIEFING_GENERATION_TASK
WIKI_DRAFT_TASK
```

任务状态：

```text
PENDING
RUNNING
SUCCESS
FAILED
CANCELLED
```

统一异步执行流程：

```text
用户点击 Studio 按钮
  ↓
弹窗提交参数
  ↓
后端创建 task 记录
  ↓
写入消息队列
  ↓
Worker 消费任务
  ↓
加载 Agent Plan
  ↓
调用 Skill
  ↓
保存 Artifact
  ↓
更新 task 状态
  ↓
右侧 Studio 刷新结果
```

MVP 推荐实现：

```text
MySQL task 表 + Redis Stream + Worker + 前端轮询
```

---

### 20.10 多会话与 Artifact 归属设计

工作台支持多个会话窗口。每个团队空间或员工个人研究工作台下可以创建多个 Chat Session，用于隔离不同任务上下文。

```text
Workspace / Space
  ├── Sources
  ├── Wiki / Cards
  ├── Chat Sessions
  │     ├── 会话 A：资料总览
  │     ├── 会话 B：方案讨论
  │     ├── 会话 C：知识复盘
  │     └── 会话 D：报告修改
  ├── Studio
  └── Artifacts
```

多会话的核心价值是：

```text
同一个工作台下支持多个研究任务
不同会话之间上下文隔离
避免长对话互相污染
每个会话可绑定不同资料范围
产物可以跨会话复用
```

#### 会话范围 Scope

每个会话可以绑定一个知识范围：

```text
SPACE
KNOWLEDGE_BASE
RESEARCH_PROJECT
SELECTED_SOURCES
ARTIFACT
```

例如：

```text
会话 A：基于整个团队空间问答
会话 B：只基于某个知识库问答
会话 C：围绕某个 Research Project 讨论
会话 D：围绕某份 Report Artifact 修改
```

---

### 20.11 Artifact 归属原则

Artifact 不应该只属于某个会话，而应该作为工作台级资产统一管理。

最终采用：

```text
Artifact 主归属：Workspace / Space / Research Project
Artifact 可选关联：Chat Session / Chat Message
```

也就是说：

```text
会话是过程
产物是资产
```

原因是报告、测验、学习指南、FAQ、Briefing、Wiki 草稿等产物生成后通常需要：

```text
跨会话查看
基于它继续生成别的产物
编辑
导出
发布为 Wiki
长期保存到 Research Project
```

如果 Artifact 只跟随某个会话，会导致产物难以复用和管理。

---

### 20.12 Artifact 与会话的关系

推荐模型：

```text
Workspace / Research Project
  ├── Sources
  ├── Chat Sessions
  └── Artifacts
```

Artifact 归属于工作台，但记录来源会话。

```text
Artifact
  ├── workspace_id / space_id
  ├── research_project_id
  ├── created_from_session_id，可为空
  ├── created_from_message_id，可为空
  └── source_scope
```

如果一个 Artifact 在多个会话中被引用、修改或作为新产物的输入，可以通过关联表记录。

```text
session_artifact
```

关系类型：

```text
CREATED_FROM
REFERENCED
UPDATED_IN
GENERATED_NEXT
```

示例：

```text
Report A 在会话 1 中生成
Quiz B 基于 Report A 在会话 2 中生成
Study Guide C 在会话 3 中引用了 Report A
```

这种设计可以同时保留产物的生成上下文和跨会话复用能力。

---

### 20.13 Artifact 数据模型

建议将 Artifact 作为独立核心模型。

```text
generated_artifact
```

字段：

```text
id
user_id
space_id
workspace_id
research_project_id
created_from_session_id
created_from_message_id
artifact_type        // REPORT / QUIZ / GUIDE / FAQ / BRIEFING / WIKI_DRAFT / INTERVIEW_PREP
title
content
source_scope_type    // TEAM_SPACE / PERSONAL_RESEARCH / SELECTED_SOURCES / ARTIFACT
source_ids
citation_ids
status               // GENERATING / READY / FAILED / ARCHIVED / PUBLISHED_TO_WIKI
task_id
created_at
updated_at
```

Quiz 可以拆成：

```text
quiz
quiz_question
quiz_answer_record
```

用于支持答题、评分和复习。

---

### 20.14 Chat Session 数据模型

```text
chat_session
```

字段：

```text
id
user_id
space_id
session_type        // TEAM_CHAT / PERSONAL_RESEARCH_CHAT
title
scope_type          // SPACE / KNOWLEDGE_BASE / RESEARCH_PROJECT / SELECTED_SOURCES / ARTIFACT
scope_ids
summary
status              // ACTIVE / ARCHIVED
created_at
updated_at
```

```text
chat_message
```

字段：

```text
id
session_id
role                // USER / ASSISTANT / SYSTEM
content
message_type        // TEXT / ARTIFACT / TOOL_RESULT
citation_ids
created_at
```

```text
session_artifact
```

字段：

```text
id
session_id
artifact_id
relation_type       // CREATED_FROM / REFERENCED / UPDATED_IN / GENERATED_NEXT
created_at
```

---

### 20.15 前端展示方式

右侧 Studio / Artifacts 区域展示当前工作台的全部产物。

支持筛选：

```text
按类型筛选
按来源会话筛选
按资料范围筛选
按创建时间筛选
按是否发布 Wiki 筛选
```

当前会话内可以展示“本会话相关产物”：

```text
本会话生成的 Artifact
本会话引用过的 Artifact
本会话修改过的 Artifact
```

因此 UI 上形成两层：

```text
工作台 Artifacts：全量资产库
当前会话 Artifacts：当前上下文相关产物
```

---

### 20.16 最终产品结构

```text
团队空间
  ├── Sources / Documents
  ├── Team Chat
  ├── Team Wiki
  ├── Studio
  │     ├── FAQ
  │     ├── Briefing
  │     ├── Wiki Draft
  │     ├── Onboarding Guide
  │     └── Incident Review Draft
  ├── Artifacts
  └── Task Center

员工个人研究工作台
  ├── Sources
  ├── Personal Wiki Cards
  │     ├── Article Cards
  │     ├── Concept Cards
  │     └── Methodology Cards
  ├── Personal Chat
  ├── Studio
  │     ├── Research Report
  │     ├── Quiz
  │     ├── Study Guide
  │     ├── Work Prep
  │     ├── Mind Map Outline
  │     └── Comparison
  ├── Artifacts
  └── Task Center
```

---

### 20.12 产品侧功能优先级

主功能优先级调整为：

```text
1. 资料上传 / 添加 Source
2. 团队 RAG 问答与个人 Wiki 问答
3. 右侧 Studio 产物生成
4. 弹窗参数配置
5. 异步任务中心
6. Artifact 管理
7. 可选发布为 Wiki
8. 对话追问与引用解释
```

---

### 20.13 简历表述补充

可以这样描述：

> 参考 NotebookLM 的 Workspace + Studio 体验，设计右侧 Studio 产物生成系统，将报告、测验、学习指南、FAQ、Briefing、Wiki 草稿等能力抽象为按钮触发的异步 AI Task；后端通过 TaskService 创建任务，Agent Plan 编排多个 Skill 执行，并将结果保存为独立 Artifact，支持查看、编辑、重新生成、导出以及可选发布为 Wiki。

或者：

> 区分 Wiki 知识沉淀层与 Artifact 任务产物层：Wiki 只保存长期稳定、可复用的知识，报告、测验、学习指南、Briefing 等默认作为独立 Artifact 管理，避免将临时生成内容污染长期知识库。

---

## 21. 开发落地导向的工程约束

当前文档用于指导后续编码实现，因此需要将产品概念收敛为稳定的工程模型，避免在实现阶段出现概念混乱、模块边界不清、任务流不可落地等问题。

本项目在实现时遵循以下工程约束：

```text
先稳定领域模型
再设计表结构
再拆模块和接口
最后实现异步任务与 AI 能力
```

---

### 21.1 核心层级关系定稿

系统最高层容器统一为 Space。

```text
Space
  ├── PERSONAL
  │     └── ResearchProject
  │           ├── Source
  │           ├── ArticleCard
  │           ├── ConceptCard
  │           ├── ChatSession
  │           └── Artifact
  │
  └── TEAM
        ├── KnowledgeBase
        │     ├── Document
        │     └── DocumentChunk
        ├── WikiPage
        ├── ChatSession
        └── Artifact
```

工程约定：

- `Space` 是系统最高层业务容器。
- `Space.type = PERSONAL` 时，主要承载个人研究工作台。
- `Space.type = TEAM` 时，主要承载团队知识空间。
- 个人侧以 `ResearchProject` 为核心组织单元。
- 团队侧以 `KnowledgeBase` 为核心组织单元。
- `ChatSession` 属于 `Space`，并通过 `scope_type / scope_ids` 绑定具体资料范围。
- `Artifact` 属于 `Space`，可选关联 `ResearchProject`、`ChatSession` 或 `GenerationTask`。
- `WikiPage` 主要用于团队轻量 Wiki。
- 个人侧 Wiki 不使用 `WikiPage` 作为核心，而是由 `ArticleCard / ConceptCard / MethodologyCard` 构成。

---

### 21.2 核心实体边界

#### 基础实体

```text
User
Space
SpaceMember
```

基础实体负责用户、空间和成员权限。

#### 个人侧实体

```text
ResearchProject
Source
ArticleCard
ConceptCard
ConceptAlias
ConceptRelation
ArticleConceptRelation
MethodologyCard
```

个人侧核心是 ResearchProject。个人资料进入 ResearchProject 后，默认进入 Wiki Compiler 流程，生成 Article Card 和 Concept Card。

#### 团队侧实体

```text
KnowledgeBase
Document
DocumentChunk
WikiPage
WikiPageVersion
```

团队侧核心是 KnowledgeBase。团队资料进入 KnowledgeBase 后，默认进入 RAG 索引流程。

#### 通用实体

```text
ChatSession
ChatMessage
Artifact
GenerationTask
Citation
SkillExecutionLog
```

通用实体负责对话、生成产物、异步任务、引用和 Skill 执行记录。

---

### 21.3 模块边界定稿

后端建议按以下模块拆分：

```text
com.noteweave.auth
com.noteweave.user
com.noteweave.space
com.noteweave.permission

com.noteweave.team.kb
com.noteweave.team.document
com.noteweave.team.rag
com.noteweave.team.wiki

com.noteweave.personal.project
com.noteweave.personal.source
com.noteweave.personal.compiler
com.noteweave.personal.card

com.noteweave.chat
com.noteweave.studio
com.noteweave.artifact
com.noteweave.task
com.noteweave.skill

com.noteweave.llm
com.noteweave.embedding
com.noteweave.storage
com.noteweave.search
com.noteweave.citation
```

模块职责：

| 模块 | 职责 |
|---|---|
| auth / user | 注册登录、用户信息 |
| space | 个人空间、团队空间、空间管理 |
| permission | 空间成员角色与权限判断 |
| team.kb | 团队知识库管理 |
| team.document | 团队文档上传、解析、切片 |
| team.rag | 团队检索问答、RRF、多路召回 |
| team.wiki | 团队轻量 Wiki、Wiki 草稿、发布 |
| personal.project | 个人 Research Project 管理 |
| personal.source | 个人资料上传、URL 导入 |
| personal.compiler | 个人 Wiki 编译流程 |
| personal.card | Article / Concept / Methodology Card 管理 |
| chat | 多会话、消息、引用展示 |
| studio | 右侧 Studio 按钮入口与任务创建 |
| artifact | 产物存储、查看、编辑、导出 |
| task | 异步任务、状态机、重试、取消 |
| skill | Skill 接口、Skill Registry、固定 Plan 执行 |
| llm / embedding | 模型调用封装 |
| storage | 文件与原文存储 |
| search | ES / 向量检索封装 |
| citation | 引用片段、来源映射 |

---

### 21.4 MVP 必做模块

第一版 MVP 必须实现的模块：

```text
1. 用户注册登录
2. Space 创建与 SpaceMember 权限
3. 团队 KnowledgeBase 创建
4. 团队 Document 上传、解析、切片
5. 团队基础 RAG 问答
6. 个人 ResearchProject 创建
7. 个人 Source 上传
8. 个人 ArticleCard 生成
9. 个人 ConceptCard 抽取与简单合并
10. ChatSession 多会话
11. Studio 异步任务入口
12. Artifact 保存与查看
13. GenerationTask 状态管理
```

MVP 可以暂缓的模块：

```text
完整 MethodologyCard 自动生成
复杂 Agent 自主规划
完整 RRF 评估平台
复杂 Wiki 审核流
多模型切换
外部论文搜索
团队共享 Memory
复杂知识图谱可视化
```

---

### 21.5 个人侧全量 Wiki 编译边界

个人侧的“全量 Wiki 编译”不是指用户所有个人资料无条件全局编译，而是指：

```text
以 ResearchProject 为单位，对该项目内的精选 Source 进行全量 Wiki 编译。
```

工程边界：

- 一个 ResearchProject 是个人 Wiki 编译的基本单位。
- Source 加入 ResearchProject 后，可以触发 WikiCompileTask。
- MVP 中 ArticleCard 必做。
- ConceptCard 必做，但只做简单归一化和合并。
- MethodologyCard 可以先预置模板，暂不做复杂自动生成。
- Raw Source 始终保留，Card 需要能回溯原文证据。

推荐处理流程：

```text
Source 上传 / 导入
  ↓
解析原文
  ↓
生成 ArticleCard
  ↓
抽取 Candidate Concepts
  ↓
概念归一化
  ↓
合并或创建 ConceptCard
  ↓
建立 ArticleConceptRelation
  ↓
更新 ResearchProject 编译状态
```

---

### 21.6 团队侧 RAG 边界

团队侧的“大规模 RAG”在工程上分阶段实现。

MVP 阶段：

```text
支持百到千级文档的基础 RAG
先实现 BM25 / ES 检索
保留向量检索和 RRF 扩展接口
```

增强阶段：

```text
BM25Retriever
VectorRetriever
WikiRetriever
Weighted RRF
EvidencePostProcessor
```

团队侧默认不做全量 Wiki 编译，只做：

```text
Document → Chunk → Index → RAG Chat
```

团队 Wiki 作为轻量知识沉淀：

```text
RAG 答案 / FAQ / Briefing / 技术总结
  ↓
Wiki Draft
  ↓
人工确认
  ↓
发布 WikiPage
  ↓
WikiPage 进入团队 RAG 索引
```

---

### 21.7 Skill + Agent 工程边界

项目保留 Skill + Agent 设计，但 MVP 不实现完全自主规划 Agent。

工程上采用：

```text
Plan-based Agent Orchestration
```

也就是：

```text
Studio Button
  ↓
弹窗配置参数
  ↓
创建 GenerationTask
  ↓
根据 task_type 选择固定 Plan
  ↓
Plan 顺序调用多个 Skill
  ↓
保存 Artifact
```

示例：生成测验

```text
QuizGenerationTask
  ↓
LoadResearchContextSkill
  ↓
SelectConceptSkill
  ↓
GenerateQuizSkill
  ↓
SaveArtifactSkill
```

示例：生成研究报告

```text
ReportGenerationTask
  ↓
LoadResearchContextSkill
  ↓
SelectArticleCardSkill
  ↓
SelectConceptCardSkill
  ↓
GenerateReportSkill
  ↓
CitationBacktraceSkill
  ↓
SaveArtifactSkill
```

这样可以保留 Skill 可复用性，同时保证执行流程可控、可测试、可调试。

---

### 21.8 异步任务工程约束

所有耗时 AI 任务必须异步执行。

统一任务状态：

```text
PENDING
RUNNING
SUCCESS
FAILED
CANCELLED
```

必须考虑：

```text
任务幂等
失败重试
任务取消
任务超时
重复提交防抖
中间结果保存
错误日志记录
任务状态回滚
```

推荐 MVP 实现：

```text
MySQL generation_task 表
Redis Stream 作为任务队列
Worker 消费任务
前端轮询任务状态
```

任务执行基本流程：

```text
创建任务记录 PENDING
  ↓
写入 Redis Stream
  ↓
Worker 获取任务
  ↓
更新 RUNNING
  ↓
执行固定 Plan
  ↓
保存 Artifact / Card / Index
  ↓
更新 SUCCESS
```

失败流程：

```text
执行异常
  ↓
记录 error_message
  ↓
retry_count + 1
  ↓
未超过最大重试次数则重新入队
  ↓
超过则标记 FAILED
```

取消流程：

```text
用户取消任务
  ↓
任务状态改为 CANCELLED
  ↓
Worker 在步骤边界检查状态
  ↓
停止后续执行
```

---

### 21.9 Wiki 与 Artifact 工程边界

必须区分 Wiki 和 Artifact。

```text
Wiki = 长期知识沉淀层
Artifact = 阶段性任务产物层
```

默认规则：

- Report、Quiz、StudyGuide、Briefing、Comparison 默认是 Artifact，不进入 Wiki。
- WikiDraft、FAQ、OnboardingGuide、TechnicalSummary 可以选择发布为 Wiki。
- Artifact 发布为 Wiki 需要人工确认。
- Wiki 发布后需要生成 WikiPageVersion。
- 发布后的 WikiPage 进入团队 RAG 索引。
- Artifact 与 WikiPage 保留关联关系，便于追踪来源。

---

### 21.10 开发顺序建议

建议按以下顺序开发：

```text
第一阶段：基础框架
1. User / Auth
2. Space / SpaceMember
3. FileStorage
4. GenerationTask 基础状态机

第二阶段：团队基础 RAG
5. KnowledgeBase
6. Document 上传与解析
7. Chunk 切片
8. ES BM25 检索
9. Team Chat 问答

第三阶段：个人 Wiki 编译
10. ResearchProject
11. Source 上传
12. ArticleCard 生成
13. ConceptCard 抽取与合并
14. Personal Chat 问答

第四阶段：Studio 与 Artifact
15. Studio 按钮任务
16. Report Artifact
17. Quiz Artifact
18. StudyGuide Artifact
19. Artifact 查看与导出

第五阶段：增强能力
20. VectorRetriever
21. WikiRetriever
22. Weighted RRF
23. 团队 Wiki Draft 发布
24. MethodologyCard
```

---

## 22. MVP 范围建议

### 19.1 第一阶段 MVP

团队侧：

- 用户注册登录
- 团队空间创建
- 成员角色权限
- 文档上传
- 文档解析与切片
- Elasticsearch BM25 检索
- 基础 RAG 问答
- 回答引用来源

个人侧：

- 个人资料上传
- Article Card 生成
- Concept Card 抽取与简单合并
- 基于 Concept / Article 的问答与写作

### 19.2 第二阶段增强

团队侧：

- 向量召回
- WikiRetriever
- Weighted RRF
- Evidence 后处理
- 一键生成 Wiki 草稿

个人侧：

- Methodology Card
- 概念关系图
- Raw Source 证据回溯
- 面试回答生成
- 学习路线生成

### 19.3 第三阶段优化

- Rerank 模型
- 更复杂权限模型
- Wiki 版本管理
- 概念 Lint 去重
- 卡片质量评分
- 热点文档推荐
- 个人 Memory 深度融合

---

## 20. 简历表述参考

可以在简历中这样描述项目：

> 设计并实现 NoteWeave AI 知识工作台，支持团队知识检索与个人深度研究双模式。团队侧采用大规模 RAG 架构，融合权限过滤、BM25/向量/Wiki 多路召回与 Weighted RRF 排序，支持带引用的文档问答和高价值答案 Wiki 沉淀；个人侧借鉴 LLM-Wiki 思路，将个人资料全量编译为 Article Card、Concept Card 和 Methodology Card，构建可长期演化的个人语义知识索引，支撑结构化写作、知识复盘和知识融合。

也可以拆成多条：

- 抽象 Space 统一建模个人空间与团队空间，基于 SpaceMember 实现 OWNER / EDITOR / VIEWER 三级角色权限控制。
- 设计团队 RAG 检索链路，支持 BM25、向量检索和 Wiki 页面多路召回，并通过 Weighted RRF 解决多检索器分数不可比较问题。
- 实现 EvidencePostProcessor，对召回 Chunk 进行去重、相邻片段合并和同文档限流，提高生成上下文质量。
- 设计个人 Wiki Compiler，将原始资料异步编译为 Article Card、Concept Card 和 Methodology Card，实现跨文档概念融合与知识沉淀。
- 构建 Wiki-based Generation 流程，基于方法论卡片组织输出结构，结合概念卡片和原文证据生成可溯源回答。

---

## 21. 最终架构总结

NoteWeave 的最终架构可以概括为：

```text
团队空间
  = 大规模文档池
  + 权限过滤
  + Hybrid Retrieval
  + Weighted RRF
  + Evidence Context
  + 轻量 Wiki 沉淀

员工个人研究工作台
  = 个人资料库
  + 全量 Wiki Compiler
  + Article Card
  + Concept Card
  + Methodology Card
  + Wiki-based Generation
```

一句话总结：

> 团队侧用 RAG 解决规模和权限问题，个人侧用 LLM-Wiki 解决深度理解和长期知识组织问题。

