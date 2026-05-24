# 文件：08_Artifact_Methodology_Skill与Synthesis沉淀.md

## 1. 本主题面试官想考什么

这个主题考察你对 AI 生成成果生命周期的设计：Artifact 为什么独立于 ChatMessage，为什么要版本化，Methodology 如何影响 prompt，Skill 在项目里到底是什么，为什么个人 Artifact 要用户确认后才沉淀为 SynthesisCard。

## 2. 高频问题清单

### 基础问题

- Artifact 是什么？和 Message/Wiki/Card 有什么区别？
- Artifact 为什么要版本化？
- MethodologyCard 在项目里起什么作用？
- Skill 执行在项目里到底是什么？

### 进阶问题

- 为什么生成结果不能自动进入 Wiki？
- Methodology 为什么不直接写死在 prompt 里？
- ArtifactPlanExecutor 的 plan 如何工作？
- SkillExecutionLog 为什么要脱敏？
- 个人 Artifact 为什么沉淀为 SynthesisCard，而不是直接改 ConceptCard？

### 深挖追问

- `PROJECT / SPACE / PRESET` 方法论匹配顺序如何设计？
- Artifact distillation 为什么要绑定 artifactVersionId？
- stale proposal 为什么要拒绝？
- 如果 Skill 执行失败，Artifact 状态如何处理？

### 压力追问

- 你这里的 Skill 是不是 Agent？
- 如何避免 Artifact 生成污染个人 Wiki？
- 如果用户编辑 Artifact 后再沉淀，如何保证引用版本正确？
- 如果未来支持开放插件式 Skill，需要补哪些安全边界？

## 3. 问答与讲解

### Q1：为什么 Artifact 要独立出来，而不是直接存 ChatMessage？

#### 面试官为什么问

面试官想看你是否理解“对话内容”和“可交付成果”的生命周期不同。

#### 回答思路

说明 ChatMessage 是交互记录，Artifact 是结构化成果，有版本、来源、引用、导出、编辑、再生成和沉淀生命周期。

#### 结合我的项目怎么答

项目有 Artifact、ArtifactVersion、ArtifactSource、ArtifactCitation、SessionArtifact。Artifact 可以来自团队 ChatMessage，也可以来自个人 ResearchProject。它支持查看、编辑、归档、导出和重新生成；生成过程通过 `ArtifactPlanExecutor` 执行固定 plan，并记录 SkillExecutionLog。

#### 技术原理 / 链路设计讲解

如果把成果直接放在 ChatMessage 里，会出现几个问题：无法版本化、无法独立编辑、无法导出、无法绑定多源引用、无法沉淀到 Wiki/Card、无法复用生成状态。Artifact 独立后，Chat 可以继续是交互记录，Artifact 则成为可管理的内容资产。

#### 技术栈特点与选型理由

MySQL 保存 Artifact 元数据、版本和引用关系；MinIO 可存导出或快照；Task Worker 负责生成；LLM 日志和 Skill 日志支持排障。

#### 可直接复述的面试回答

我把 Artifact 从 ChatMessage 里独立出来，是因为二者生命周期不一样。ChatMessage 是对话记录，Artifact 是可交付成果，比如报告、学习指南、对比分析、Wiki 草稿。Artifact 需要版本、来源、引用、编辑、导出、重新生成和后续沉淀能力。如果只存在 message content 里，后面很难做版本追踪和证据审计。当前项目用 Artifact、ArtifactVersion、ArtifactSource、ArtifactCitation 来管理成果，让它既能从聊天生成，也能从个人研究项目生成。

#### 常见追问

- Artifact 编辑后 citation 怎么处理？
- Artifact 和 Wiki 的边界是什么？
- ArtifactVersion 为什么不能覆盖？

#### 常见坑

不要说 Artifact 只是“生成结果页面”。要讲生命周期和证据关系。

---

### Q2：MethodologyCard 为什么不直接写死在 prompt 里？

#### 面试官为什么问

这是可扩展性和 Prompt Governance 题。面试官想看你是否能把提示词从代码里抽象成可管理的知识。

#### 回答思路

说明写死 prompt 的问题：难复用、难版本、难按项目/空间/预设匹配、难治理。MethodologyCard 提供 workflow、outputStructure、qualityChecklist。

#### 结合我的项目怎么答

项目里 MethodologyMatcher 按 project -> personal space -> preset 的顺序匹配，优先 exact artifactType/problemType，再用 scene signal 破 ties，最后 GENERAL fallback。生成时 `MethodologyPromptSectionBuilder` 把选中的 methodology 注入 prompt，让不同类型 Artifact 有不同输出框架。

#### 技术原理 / 链路设计讲解

Methodology 本质上是“生成方法”和“质量标准”的结构化配置，而不是单纯 prompt 文本。把它抽出来后，项目可以支持预设模板、用户自定义、项目级覆盖、空间级复用和后续版本管理。

#### 技术栈特点与选型理由

MethodologyCard 存在 MySQL，便于 CRUD、scope、status、createdBy、版本和权限控制；Prompt rendering 通过服务层注入，避免散落在各个生成方法里。

#### 可直接复述的面试回答

MethodologyCard 我没有直接写死在 prompt 里，因为它更像生成方法论和质量标准，而不是一段固定提示词。比如研究报告、学习指南、工作准备材料，需要不同 workflow、输出结构和质量检查项。如果写死在代码里，后续项目级定制、个人空间复用、预设模板和版本管理都会很困难。当前设计是通过 MethodologyMatcher 按 project、space、preset 的顺序匹配，再把 workflow、outputStructure、qualityChecklist 注入生成 prompt。这样既保留稳定结构，又不把所有生成逻辑硬编码。

#### 常见追问

- GENERAL fallback 有什么意义？
- 用户自定义方法论如何避免越权？
- Methodology 会不会导致 prompt 过长？

#### 常见坑

不要把 Methodology 说成“几个模板”。它的亮点在作用域、匹配、注入和治理。

---

### Q3：你这里的 Skill 是不是完整 Agent 平台？

#### 面试官为什么问

这是简历风险题。如果你写了 Skill，面试官可能会追问是否有工具调用、规划、权限沙箱、多 Agent 协作。

#### 回答思路

诚实降维：当前 Skill 更像可控的生成流水线步骤和执行日志，不是完全开放的 Agent 插件生态。然后讲它的价值：可观测、可取消、可记录、可扩展。

#### 结合我的项目怎么答

`ArtifactPlanExecutor` 对不同 ArtifactType 定义固定 plan，如 LoadGenerationContextSkill、SelectEvidenceSkill、GenerateReportSkill、SaveArtifactSkill。每一步会记录 SkillExecutionLog，包括状态、耗时、模型、token 和脱敏输入输出。它不是开放式 Agent 自主规划，而是受控编排。

#### 技术原理 / 链路设计讲解

受控 Skill pipeline 的好处是可预测：每个步骤职责清楚，可以在 taskContext 中检查取消、记录进度、失败时定位。开放 Agent 虽然灵活，但需要工具权限、沙箱、预算、循环控制、审计和安全策略，当前项目没有把它作为主链路实现。

#### 技术栈特点与选型理由

固定 plan 用 Java 服务实现，适合当前工程阶段；SkillExecutionLog 支持排障；TaskExecutionContext 支持取消和 progress。后续如果扩展开放 Skill，需要引入权限声明、输入输出 schema、资源预算和沙箱。

#### 可直接复述的面试回答

我会很明确地说，当前项目里的 Skill 不是完整开放式 Agent 平台。它更像受控的生成流水线步骤。比如生成报告会按 LoadGenerationContext、SelectEvidence、GenerateReport、SaveArtifact 这样的固定 plan 执行，每一步都有日志、耗时、状态和脱敏输入输出，也能在安全点响应取消。这样做的收益是可控、可观测、容易排障。完整 Agent 平台需要开放工具调用、权限沙箱、预算控制、循环终止和多工具审计，这些不是当前主链路，所以我不会把它夸大成已经实现的 Agent 生态。

#### 常见追问

- 如果未来做开放 Skill，需要哪些安全机制？
- SkillExecutionLog 为什么要脱敏？
- 固定 plan 会不会不够灵活？

#### 常见坑

不要为了显得高级说“这是 Agent”。诚实说“受控生成流水线”反而更可信。

---

### Q4：个人 Artifact 为什么要用户确认后才沉淀为 SynthesisCard？

#### 面试官为什么问

这是知识污染控制题。面试官想看你有没有把 AI 输出和长期知识分层。

#### 回答思路

说明 Artifact 是生成成果，不一定稳定可信；SynthesisCard 是个人 Wiki 的长期知识，需要显式确认、绑定版本、复制 citation、避免改写 ConceptCard。

#### 结合我的项目怎么答

项目的 distillation 是两步：先生成 proposal，不写个人 Wiki；用户 confirm 后，服务端复核 owner-only 权限，确认 proposal 绑定的 artifactVersionId 仍是当前版本，再创建 SynthesisCard、artifact_card_relation、synthesis_card_citation、synthesis_concept_relation，并把 Artifact 标记为 DISTILLED_TO_PERSONAL_WIKI。

#### 技术原理 / 链路设计讲解

绑定 artifactVersionId 是为了避免“用户预览 A 版本，确认时 Artifact 已变成 B 版本”的错配。SynthesisCard 不直接改 ConceptCard，是因为生成成果可能是综合结论，不应该自动覆盖已有概念事实。

#### 技术栈特点与选型理由

MySQL 关系表保存 Artifact -> SynthesisCard 的 lineage；Citation 复制为正式关系；stale proposal 拒绝保证版本一致性。

#### 可直接复述的面试回答

个人 Artifact 默认不会自动进入个人 Wiki，因为 Artifact 是生成成果，可能还需要用户判断和编辑。真正进入长期知识的 SynthesisCard 必须经过用户确认。项目里 distillation 是两步：先生成 proposal，只做预览，不写 Card；确认时重新校验 owner 权限，并检查 proposal 绑定的 artifactVersionId 还是当前版本，避免用户预览的是旧版本却确认到新内容。确认后才创建 SynthesisCard、复制 citation，并建立 Artifact 到 Card 的关系。这样能避免 AI 输出自动污染个人知识库。

#### 常见追问

- stale proposal 怎么判断？
- 为什么不直接合并 ConceptCard？
- no-citation Artifact 能不能沉淀？

#### 常见坑

不要说“生成完自动保存到 Wiki”。这个项目的优势正是显式确认和版本绑定。

---

## 4. 本主题总结

Artifact 主题要讲清：Artifact 是可版本化成果，不等于 Message/Wiki/Card；Methodology 是结构化生成方法；Skill 是受控 pipeline，不是开放 Agent；Synthesis 沉淀必须用户确认并绑定具体版本。

## 5. 面试前自查清单

- 我是否能解释 Artifact、Wiki、Card、Message 的边界？
- 我是否能讲出 ArtifactPlanExecutor 的固定 plan 价值？
- 我是否能诚实说明 Skill 不是完整 Agent？
- 我是否能解释 MethodologyCard 的匹配和注入？
- 我是否能说明 distillation 为什么要 proposal/confirm 两步？

## 6. 整条链路深讲：Artifact 如何从生成任务变成可沉淀成果

第一步是创建 Studio/Artifact 任务。用户可以从团队 ChatMessage 或个人 ResearchProject 创建生成任务。系统先创建 Artifact 元数据，再创建 `ARTIFACT_GENERATE` Task，进入统一 Task/Outbox/Kafka Worker。

第二步是加载生成上下文。`ArtifactPlanExecutor` 的第一步通常是 `LoadGenerationContextSkill`。如果来源是团队 ChatMessage，就加载 session、message、message citations；如果来源是个人 ResearchProject，就调用 `PersonalGenerationService.prepare`，加载 ResearchProject、ArticleCard、ConceptCard、SynthesisCard、MethodologyCard 和 SOURCE-backed evidence。

第三步是方法论匹配。个人生成会通过 MethodologyMatcher 按 project -> personal space -> preset 查找 MethodologyCard。匹配结果会注入 workflow、outputStructure 和 qualityChecklist，让不同 ArtifactType 有不同生成结构。

第四步是受控 Skill pipeline。不同 ArtifactType 有固定 plan，比如 REPORT 是 LoadGenerationContext、SelectEvidence、GenerateReport、SaveArtifact；WORK_PREP 是 LoadGenerationContext、SelectConceptCard、GenerateWorkPrep、SaveArtifact。每一步都会记录 SkillExecutionLog，输入输出要脱敏。

第五步是生成与保存版本。Generate skill 调 LLM 得到内容，SaveArtifactSkill 创建 ArtifactVersion，并保存 ArtifactSource、ArtifactCitation 等关系。Artifact 不覆盖旧内容，而是保留版本。

第六步是失败和取消。ArtifactPlanExecutor 每个 skill 前会检查 taskContext 是否取消；失败时记录 failed SkillExecutionLog，并通过 ArtifactPersistenceService 把 Artifact 状态收敛，避免卡在 GENERATING。

第七步是沉淀。Artifact 默认只是生成成果，不自动进入 Wiki/Card。团队侧需要人工发布成 Wiki；个人侧调用 distill-to-personal-wiki，先生成 proposal，不写卡片；用户确认后才创建 SynthesisCard。

第八步是版本绑定。distillation proposal 绑定当前 latest artifactVersionId。确认时再次校验 owner 权限，并检查 proposal 是否还是当前版本；如果 Artifact 已更新，就拒绝 stale proposal，避免确认错版本。

## 7. 原理与设计原因速查

- 为什么 Artifact 独立：它有版本、来源、引用、编辑、导出、再生成和沉淀生命周期，不能混在 ChatMessage。
- 为什么 Methodology 不写死：方法论需要按项目、空间、预设复用和治理，写死 prompt 难维护。
- 为什么 Skill 是受控 pipeline：当前项目需要可预测、可审计、可取消的生成步骤，不是开放式 Agent 自主规划。
- 为什么 Skill 日志要脱敏：生成上下文可能包含私有资料、memory、prompt 和证据原文，不能直接暴露。
- 为什么 Artifact 不自动变知识：LLM 输出需要用户判断，长期知识必须更高可信度。
- 为什么 Synthesis 不改 Concept：Synthesis 是综合结论，Concept 是概念事实，自动覆盖会污染原有知识结构。
- 为什么绑定 artifactVersionId：防止用户预览旧版本、确认时写入新版本的错配。

## 8. 3 到 5 分钟深答模板

Artifact 链路我会从“生成成果的生命周期”讲。用户创建生成任务时，系统先创建 Artifact，再创建 `ARTIFACT_GENERATE` Task，走统一异步 Worker。Worker 进入 ArtifactPlanExecutor 后，会按 ArtifactType 执行固定 plan。第一步加载上下文：团队来源会加载 ChatMessage 和 citations，个人来源会加载 ResearchProject 下的 ArticleCard、ConceptCard、SynthesisCard、MethodologyCard 和可回溯到 Source 的 evidence。然后 MethodologyMatcher 会按 project、space、preset 匹配方法论，把 workflow、输出结构和质量检查注入 prompt。后续每个 Skill 都是受控步骤，比如 SelectEvidence、GenerateReport、SaveArtifact，并记录脱敏 SkillExecutionLog。生成结果保存为 ArtifactVersion，不覆盖历史。Artifact 默认不是长期知识，团队要发布成 Wiki，个人要先生成 distillation proposal，用户确认后才创建 SynthesisCard。确认时会绑定 artifactVersionId 并做 stale proposal 校验，保证用户沉淀的就是预览过的那个版本。这条链路体现的是：生成可自动化，但知识沉淀必须可确认、可追溯、可版本化。
