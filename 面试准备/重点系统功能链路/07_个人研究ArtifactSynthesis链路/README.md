# 07 个人研究 Artifact Synthesis 链路

## 0. 本篇定位

这条链路回答：个人研究如何从 Source 进入系统，变成 Card，再生成 Artifact，最后经用户确认沉淀为 SynthesisCard。

核心链路：

```text
ResearchProject
-> Source(FILE / URL / TEXT)
-> SOURCE_IMPORT
-> SOURCE_COMPILE
-> ArticleCard / ConceptCard
-> Citation / EvidenceBacktrace
-> MethodologyCard
-> ARTIFACT_GENERATE
-> ArtifactVersion
-> distill-to-personal-wiki proposal / confirm
-> SynthesisCard
```

## 面试先说版

这条链路我会从“个人研究不是一次问答，而是知识生产流程”讲。用户不是简单上传几篇资料让模型写报告，而是希望系统能先理解资料，再基于证据生成阶段性成果，最后把用户认可的结论沉淀为长期个人知识。

所以 NoteWeave 把个人研究拆成 `Source -> Card -> Artifact -> Synthesis`。Source 是原始资料，Card 是结构化理解，Artifact 是生成出来的报告、学习指南、对比分析这类工作产物，Synthesis 是用户确认后的长期知识。这里的关键取舍是：LLM 生成结果不能直接当长期事实，必须经过证据回溯、版本化和用户确认。

从 Agent 角度讲，我不会把它说成完全开放的 autonomous agent，而是可控的 Skill Pipeline。系统按步骤加载上下文、选择证据、套用 MethodologyCard、生成 Artifact、保存版本。这样自主性弱一点，但可观测、可复现、可评测，也更适合真实系统。

## Q1：为什么设计成 `Source -> Card -> Artifact -> Synthesis`？

**答：**

因为个人研究不是一次问答，而是从资料理解到成果生成再到长期沉淀的过程。

`Source` 是原始资料入口，可以来自文件、URL 或文本。`ArticleCard` 和 `ConceptCard` 是对 Source 的结构化理解，把文章摘要、关键点、概念、别名、关系和证据提取出来。`Artifact` 是基于这些卡片和证据生成的阶段性成果，比如报告、学习指南、对比分析、Work Prep、Reading Notes。`SynthesisCard` 是用户确认后沉淀下来的长期个人知识。

这样分层的好处是：原始资料、结构化理解、生成成果、长期沉淀互相分开，不会让模型生成内容自动污染个人知识库。

## Q2：URL Source 导入为什么要做安全限制？

**答：**

因为 URL 抓取容易引入 SSRF 风险。用户如果提交 localhost、内网 IP、metadata 地址或非法协议，服务端去抓取就可能访问不该访问的内部资源。

所以 SafeUrlContentFetcher 需要限制协议、解析 host、阻止 localhost/private network 等危险地址，并且抓取失败时不能把 Source 标成 READY。Source 只有在 raw text 或 parsed text 可读取时，才应该进入后续 compile。

## Q3：个人 Wiki Compiler 做了什么？

**答：**

它把 Source 编译成结构化知识卡片。

对文章类内容，会生成 ArticleCard，包括标题、摘要、关键点、标签、证据摘录。对概念类内容，会生成 ConceptCard，包括规范化名称、别名、定义、解释、使用场景、误解点、置信度等。

系统还会建立概念别名、概念关系、文章和概念之间的关系，并把卡片里的证据回溯到 Source 原文。

## Q4：Personal Generation 为什么要求 SOURCE-backed citation？

**答：**

因为个人 Artifact 也应该是 evidence-grounded 的，而不是只根据卡片摘要自由生成。

生成阶段会加载研究项目、文章卡、概念卡、已有长期知识和方法论卡片，但证据必须能回溯到 Source。如果卡片没有正式 Citation，或者 Citation 不能回到原始资料，生成任务应该失败，而不是生成一个看起来很完整但没有证据支撑的 Artifact。

## Q5：Artifact 和 Synthesis 为什么要分开？

**答：**

Artifact 是生成成果或工作产物，Synthesis 是长期知识。两者可信度和生命周期不同。

Artifact 可能只是一次生成草稿，可以编辑、再生成、导出，有版本历史。SynthesisCard 则代表用户确认过、愿意沉淀的长期内容。如果生成完成就自动进入个人知识库，会把错误、半成品和临时内容污染长期知识。

所以个人侧通过 distill-to-personal-wiki 两步 proposal/confirm 流程沉淀为 SynthesisCard。

## Q6：MethodologyCard 解决什么问题？

**答：**

MethodologyCard 解决的是生成结构和方法论一致性问题。

同样是生成报告，不同场景可能需要不同 workflow、outputStructure 和 qualityChecklist。MethodologyCard 把方法论抽象成可匹配、可管理的卡片，支持系统预置、个人空间、项目级卡片，并在 Personal Generation 时注入 Prompt。

在面试里可以补充：当前已经有 MethodologyCard 的创建、查询、更新、归档、版本递增、owner-only 权限，以及自定义卡片优先于 preset 的匹配逻辑。

## 实现兜底锚点

- `SourceService`
- `SourceImportService`
- `SafeUrlContentFetcher`
- `WikiCompilerService`
- `EvidenceBacktraceService`
- `ConceptMergeService`
- `PersonalGenerationService`
- `PersonalArtifactDistillationService`
- `MethodologyCardService`
- `Phase6PersonalResearchSourceIntegrationTest`
- `Phase7PersonalWikiCompilerIntegrationTest`
- `Phase11PersonalGenerationIntegrationTest`
- `Phase11_5PersonalArtifactDistillationIntegrationTest`
- `Phase13MethodologyCardIntegrationTest`

## 3 到 5 分钟深答模板

> 个人研究链路我会按 `Source -> Card -> Artifact -> Synthesis` 讲。Source 是文件、URL、文本等原始资料入口；Card 是对资料的结构化理解，包括文章摘要、关键点、概念、别名和关系；生成阶段再结合方法论卡片、项目上下文和可回溯证据产出 Artifact 版本；最后只有用户确认过的内容才会通过 proposal / confirm 沉淀为 SynthesisCard。这套分层的重点不是“多造几个表”，而是把原始资料、结构化理解、生成产物和长期知识拆开，避免 LLM 结果自动污染个人知识库。MethodologyCard 解决的是生成结构和方法论一致性，Source-backed citation 解决的是个人生成也必须 evidence-grounded，而不是只根据卡片摘要自由发挥。

## 常见追问继续怎么接

- 如果继续追问“为什么不直接从 Source 生成最终结论”，可以答：中间加 Card 和 Artifact 是为了把结构化理解、可回溯证据和最终沉淀拆开，减少知识污染。
- 如果继续追问“最有个人特色的是哪层”，可以答：`MethodologyCard` 和 distill-to-synthesis 这两层最能体现个人研究工作台，而不是通用聊天机器人。
- 如果继续追问“怎么避免幻觉写进个人知识库”，可以答：生成阶段要求 source-backed citation，沉淀阶段还需要人工确认，不走自动入库。

## 边界和不能说满的地方

- 可以坚定讲：Source、Card、Artifact、Synthesis 的分层，EvidenceBacktrace，MethodologyCard，distill-to-personal-wiki。
- 不要讲成：Artifact 生成后自动进入长期知识；当前已经完整复刻 NotebookLM；Skill 已是完整开放 Agent 平台。
