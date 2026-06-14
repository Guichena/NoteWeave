# 个人研究 Source、Card、Artifact 与 Synthesis 链路

> 本文件为 2026-06-01 重构版，依据当前代码、测试和 Flyway 迁移整理。不要再按旧阶段计划或旧题库口径背。

## 0. 本篇定位
个人研究不是团队 RAG 的缩小版，它更强调资料导入、卡片化、概念归并、证据回溯和产物沉淀。

## 1. 面试先说版
个人研究主线我会从 Source 讲起。用户在 ResearchProject 下导入文件、URL 或文本，SourceImportService 会保证 READY 状态必须对应可读文本，URL 抓取还要通过 SafeUrlContentFetcher 限制内网地址等风险。接下来 SOURCE_COMPILE 任务把 Source 编译成 ArticleCard 和 ConceptCard，不只是让 LLM 输出摘要，还会做概念抽取、alias、relation、ArticleConceptRelation，并通过 EvidenceBacktraceService 把证据回到原文位置。这样后面生成 Report、StudyGuide、WorkPrep 时，PersonalGenerationService 可以读取结构化卡片和证据，而不是把所有 Source 原文粗暴塞进 prompt。

## 2. 当前真实口径
个人侧主线是 ResearchProject、Source、ArticleCard、ConceptCard、Citation 和后续 Artifact/Synthesis。

### 已实现
- ResearchProjectController 支持个人项目 CRUD。
- SourceController 支持 upload/url/text、list/detail/import/compile/delete。
- SafeUrlContentFetcher 限制不安全 URL 获取。
- WikiCompilerService、EvidenceBacktraceService、ConceptMergeService、PersonalCardController 支持卡片化和证据查看。

### 设计目标
- Source 支持 FILE/URL/TEXT，导入后必须有可读文本；WikiCompilerService 抽 ArticleCard 和 ConceptCard，ConceptMergeService 控制概念归并，EvidenceBacktraceService 把卡片证据回溯到 Source 原文。
- 它让个人知识不是一次性摘要，而是可搜索、可引用、可组合生成成果的结构化资产。

### 后续可扩展
- 如果 LLM 抽出的 quote 在原文里找不到怎么办？
- 同一个概念在多个 Source 里出现，如何合并？
- 如何避免个人卡片膨胀成低质量知识库？

## 3. 代码和测试锚点
- src/main/java/com/noteweave/personal/source/service/SourceImportService.java
- src/main/java/com/noteweave/personal/source/fetch/SafeUrlContentFetcher.java
- src/main/java/com/noteweave/personal/compiler/service/WikiCompilerService.java
- src/main/java/com/noteweave/personal/compiler/service/EvidenceBacktraceService.java
- src/test/java/com/noteweave/personal/Phase6PersonalResearchSourceIntegrationTest.java
- src/test/java/com/noteweave/personal/Phase7PersonalWikiCompilerIntegrationTest.java

## 4. 必会问题与答题骨架

### Q1: 个人研究链路和团队知识库链路有什么不同？

回答时按四步走：
1. 先说场景：个人研究不是团队 RAG 的缩小版，它更强调资料导入、卡片化、概念归并、证据回溯和产物沉淀。
2. 再说方案：Source 支持 FILE/URL/TEXT，导入后必须有可读文本；WikiCompilerService 抽 ArticleCard 和 ConceptCard，ConceptMergeService 控制概念归并，EvidenceBacktraceService 把卡片证据回溯到 Source 原文。
3. 再说收益：它让个人知识不是一次性摘要，而是可搜索、可引用、可组合生成成果的结构化资产。
4. 最后落到真实代码锚点，不要停在概念。

可直接复述：

> 个人研究主线我会从 Source 讲起。用户在 ResearchProject 下导入文件、URL 或文本，SourceImportService 会保证 READY 状态必须对应可读文本，URL 抓取还要通过 SafeUrlContentFetcher 限制内网地址等风险。接下来 SOURCE_COMPILE 任务把 Source 编译成 ArticleCard 和 ConceptCard，不只是让 LLM 输出摘要，还会做概念抽取、alias、relation、ArticleConceptRelation，并通过 EvidenceBacktraceService 把证据回到原文位置。这样后面生成 Report、StudyGuide、WorkPrep 时，PersonalGenerationService 可以读取结构化卡片和证据，而不是把所有 Source 原文粗暴塞进 prompt。

常见追问：
- 如果 LLM 抽出的 quote 在原文里找不到怎么办？
- 同一个概念在多个 Source 里出现，如何合并？
- 如何避免个人卡片膨胀成低质量知识库？

### Q2: Source READY 为什么必须有可读文本？

回答时按四步走：
1. 先说场景：个人研究不是团队 RAG 的缩小版，它更强调资料导入、卡片化、概念归并、证据回溯和产物沉淀。
2. 再说方案：Source 支持 FILE/URL/TEXT，导入后必须有可读文本；WikiCompilerService 抽 ArticleCard 和 ConceptCard，ConceptMergeService 控制概念归并，EvidenceBacktraceService 把卡片证据回溯到 Source 原文。
3. 再说收益：它让个人知识不是一次性摘要，而是可搜索、可引用、可组合生成成果的结构化资产。
4. 最后落到真实代码锚点，不要停在概念。

可直接复述：

> 个人研究主线我会从 Source 讲起。用户在 ResearchProject 下导入文件、URL 或文本，SourceImportService 会保证 READY 状态必须对应可读文本，URL 抓取还要通过 SafeUrlContentFetcher 限制内网地址等风险。接下来 SOURCE_COMPILE 任务把 Source 编译成 ArticleCard 和 ConceptCard，不只是让 LLM 输出摘要，还会做概念抽取、alias、relation、ArticleConceptRelation，并通过 EvidenceBacktraceService 把证据回到原文位置。这样后面生成 Report、StudyGuide、WorkPrep 时，PersonalGenerationService 可以读取结构化卡片和证据，而不是把所有 Source 原文粗暴塞进 prompt。

常见追问：
- 如果 LLM 抽出的 quote 在原文里找不到怎么办？
- 同一个概念在多个 Source 里出现，如何合并？
- 如何避免个人卡片膨胀成低质量知识库？

### Q3: URL 导入要防哪些安全问题？

回答时按四步走：
1. 先说场景：个人研究不是团队 RAG 的缩小版，它更强调资料导入、卡片化、概念归并、证据回溯和产物沉淀。
2. 再说方案：Source 支持 FILE/URL/TEXT，导入后必须有可读文本；WikiCompilerService 抽 ArticleCard 和 ConceptCard，ConceptMergeService 控制概念归并，EvidenceBacktraceService 把卡片证据回溯到 Source 原文。
3. 再说收益：它让个人知识不是一次性摘要，而是可搜索、可引用、可组合生成成果的结构化资产。
4. 最后落到真实代码锚点，不要停在概念。

可直接复述：

> 个人研究主线我会从 Source 讲起。用户在 ResearchProject 下导入文件、URL 或文本，SourceImportService 会保证 READY 状态必须对应可读文本，URL 抓取还要通过 SafeUrlContentFetcher 限制内网地址等风险。接下来 SOURCE_COMPILE 任务把 Source 编译成 ArticleCard 和 ConceptCard，不只是让 LLM 输出摘要，还会做概念抽取、alias、relation、ArticleConceptRelation，并通过 EvidenceBacktraceService 把证据回到原文位置。这样后面生成 Report、StudyGuide、WorkPrep 时，PersonalGenerationService 可以读取结构化卡片和证据，而不是把所有 Source 原文粗暴塞进 prompt。

常见追问：
- 如果 LLM 抽出的 quote 在原文里找不到怎么办？
- 同一个概念在多个 Source 里出现，如何合并？
- 如何避免个人卡片膨胀成低质量知识库？

### Q4: ConceptCard 如何去重和合并？

回答时按四步走：
1. 先说场景：个人研究不是团队 RAG 的缩小版，它更强调资料导入、卡片化、概念归并、证据回溯和产物沉淀。
2. 再说方案：Source 支持 FILE/URL/TEXT，导入后必须有可读文本；WikiCompilerService 抽 ArticleCard 和 ConceptCard，ConceptMergeService 控制概念归并，EvidenceBacktraceService 把卡片证据回溯到 Source 原文。
3. 再说收益：它让个人知识不是一次性摘要，而是可搜索、可引用、可组合生成成果的结构化资产。
4. 最后落到真实代码锚点，不要停在概念。

可直接复述：

> 个人研究主线我会从 Source 讲起。用户在 ResearchProject 下导入文件、URL 或文本，SourceImportService 会保证 READY 状态必须对应可读文本，URL 抓取还要通过 SafeUrlContentFetcher 限制内网地址等风险。接下来 SOURCE_COMPILE 任务把 Source 编译成 ArticleCard 和 ConceptCard，不只是让 LLM 输出摘要，还会做概念抽取、alias、relation、ArticleConceptRelation，并通过 EvidenceBacktraceService 把证据回到原文位置。这样后面生成 Report、StudyGuide、WorkPrep 时，PersonalGenerationService 可以读取结构化卡片和证据，而不是把所有 Source 原文粗暴塞进 prompt。

常见追问：
- 如果 LLM 抽出的 quote 在原文里找不到怎么办？
- 同一个概念在多个 Source 里出现，如何合并？
- 如何避免个人卡片膨胀成低质量知识库？

### Q5: 为什么卡片必须能证据回溯？

回答时按四步走：
1. 先说场景：个人研究不是团队 RAG 的缩小版，它更强调资料导入、卡片化、概念归并、证据回溯和产物沉淀。
2. 再说方案：Source 支持 FILE/URL/TEXT，导入后必须有可读文本；WikiCompilerService 抽 ArticleCard 和 ConceptCard，ConceptMergeService 控制概念归并，EvidenceBacktraceService 把卡片证据回溯到 Source 原文。
3. 再说收益：它让个人知识不是一次性摘要，而是可搜索、可引用、可组合生成成果的结构化资产。
4. 最后落到真实代码锚点，不要停在概念。

可直接复述：

> 个人研究主线我会从 Source 讲起。用户在 ResearchProject 下导入文件、URL 或文本，SourceImportService 会保证 READY 状态必须对应可读文本，URL 抓取还要通过 SafeUrlContentFetcher 限制内网地址等风险。接下来 SOURCE_COMPILE 任务把 Source 编译成 ArticleCard 和 ConceptCard，不只是让 LLM 输出摘要，还会做概念抽取、alias、relation、ArticleConceptRelation，并通过 EvidenceBacktraceService 把证据回到原文位置。这样后面生成 Report、StudyGuide、WorkPrep 时，PersonalGenerationService 可以读取结构化卡片和证据，而不是把所有 Source 原文粗暴塞进 prompt。

常见追问：
- 如果 LLM 抽出的 quote 在原文里找不到怎么办？
- 同一个概念在多个 Source 里出现，如何合并？
- 如何避免个人卡片膨胀成低质量知识库？

## 5. 大厂深挖追问路径
1. 先问你做了什么。
2. 再问为什么这样设计，不用更简单方案。
3. 再问失败、重试、越权、删除、断线、重建索引时会发生什么。
4. 最后问如何量化效果和下一步演进。

把答案往下压一层：
- 业务层：个人研究不是团队 RAG 的缩小版，它更强调资料导入、卡片化、概念归并、证据回溯和产物沉淀。
- 架构层：Source 支持 FILE/URL/TEXT，导入后必须有可读文本；WikiCompilerService 抽 ArticleCard 和 ConceptCard，ConceptMergeService 控制概念归并，EvidenceBacktraceService 把卡片证据回溯到 Source 原文。
- 数据层：引用 MySQL、Redis、MinIO、ES、Kafka 或 Citation/Trace 的真实职责。
- 测试层：能说出对应 IntegrationTest 或 ServiceTest。
- 边界层：明确哪些是后续扩展，不冒充已落地。

## 6. 不能说满的地方
- 不要说已经实现外部资料自动发现。
- 不要说 Bibtex 主链路已经完成。
- 不要把个人卡片直接等同于团队 Wiki。

## 7. 零基础记忆法
记住一句话：先讲“为什么需要这个模块”，再讲“请求从哪里来、状态落在哪里、失败怎么恢复、证据怎么追踪、权限怎么兜底”。按这个顺序答，大多数追问都能接住。
