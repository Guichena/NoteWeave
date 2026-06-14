# Artifact、Skill、Agent 边界

> 本文件为 2026-06-01 重构版，依据当前代码、测试和 Flyway 迁移整理。不要再按旧阶段计划或旧题库口径背。

## 0. 通用问题如何转成项目深答
先把通用八股问题落到 NoteWeave 的真实模块，再回答场景、方案、收益、权衡、故障和指标。下面是本主题的项目化深答。

## 1. 本篇定位
面试官会追问生成结果如何保存、如何追溯、如何避免污染长期知识，以及 Skill 到底是不是 Agent。

## 2. 面试先说版
Artifact 我会强调它不是 chat message 的一个字段，而是独立生命周期。一次生成通过 Studio 或 PersonalGeneration 创建 ARTIFACT_GENERATE 任务，结果保存为 Artifact 和 ArtifactVersion，来源用 ArtifactSource，引用用 ArtifactCitation。MethodologyCard 的价值是把 workflow、outputStructure、qualityChecklist 作为可管理的输出方法论注入 prompt，而不是在代码里写死一堆模板。最关键的边界是 Artifact 默认不进入 Wiki，因为生成内容可能只是阶段性草稿。个人侧要沉淀时，PersonalArtifactDistillationService 先生成 proposal，用户确认后再绑定最新 artifactVersionId，创建 SynthesisCard、artifact_card_relation 和 synthesis_card_citation。Skill 在当前项目里更像可控生成步骤和执行日志，不要夸成完全自主 Agent 平台。

## 3. 当前真实口径
Artifact 是版本化产物，MethodologyCard 控制输出结构，SynthesisCard 是用户确认后的个人长期沉淀。

### 已实现
- ArtifactType 当前包含 REPORT、STUDY_GUIDE、READING_NOTES、BRIEFING、FAQ、COMPARISON、WIKI_DRAFT、ONBOARDING_GUIDE、TECHNICAL_SUMMARY、INCIDENT_REVIEW_DRAFT、PRESENTATION_OUTLINE、TIMELINE、WORK_PREP、MIND_MAP_OUTLINE。
- ArtifactController 支持列表、详情、编辑、归档、regenerate、generate、export、distill-to-personal-wiki、card-relations。
- StudioTaskController、StudioSkillController、StudioTaskService 支持生成任务和 skill 列表。
- MethodologyCardController/Service 支持方法论卡片 CRUD、归档、版本和作用域。
- PersonalArtifactDistillationService 支持 proposal/confirm、版本绑定和 SynthesisCard 写入。

### 设计目标
- StudioTaskService 创建 ARTIFACT_GENERATE 任务，ArtifactVersion 保存历史，ArtifactSource/ArtifactCitation 追踪来源，MethodologyCardService 支持预置和自定义方法论，PersonalArtifactDistillationService 用 proposal/confirm 把 Artifact 沉淀为 SynthesisCard。
- 生成结果既能作为阶段性产物迭代，又不会自动污染 Wiki 或个人概念库。

### 后续可扩展
- 为什么不直接改写 ConceptCard？
- proposal 为什么要绑定 artifactVersionId？
- 如果生成结果没有 citation，能不能沉淀？

## 4. 代码和测试锚点
- src/main/java/com/noteweave/artifact/model/ArtifactType.java
- src/main/java/com/noteweave/studio/service/StudioTaskService.java
- src/main/java/com/noteweave/personal/generation/service/PersonalGenerationService.java
- src/main/java/com/noteweave/personal/methodology/service/MethodologyCardService.java
- src/main/java/com/noteweave/personal/distillation/service/PersonalArtifactDistillationService.java
- src/test/java/com/noteweave/personal/Phase11_5PersonalArtifactDistillationIntegrationTest.java

## 5. 必会问题与答题骨架

### Q1: Artifact 为什么要独立于 ChatMessage？

回答时按四步走：
1. 先说场景：面试官会追问生成结果如何保存、如何追溯、如何避免污染长期知识，以及 Skill 到底是不是 Agent。
2. 再说方案：StudioTaskService 创建 ARTIFACT_GENERATE 任务，ArtifactVersion 保存历史，ArtifactSource/ArtifactCitation 追踪来源，MethodologyCardService 支持预置和自定义方法论，PersonalArtifactDistillationService 用 proposal/confirm 把 Artifact 沉淀为 SynthesisCard。
3. 再说收益：生成结果既能作为阶段性产物迭代，又不会自动污染 Wiki 或个人概念库。
4. 最后落到真实代码锚点，不要停在概念。

可直接复述：

> Artifact 我会强调它不是 chat message 的一个字段，而是独立生命周期。一次生成通过 Studio 或 PersonalGeneration 创建 ARTIFACT_GENERATE 任务，结果保存为 Artifact 和 ArtifactVersion，来源用 ArtifactSource，引用用 ArtifactCitation。MethodologyCard 的价值是把 workflow、outputStructure、qualityChecklist 作为可管理的输出方法论注入 prompt，而不是在代码里写死一堆模板。最关键的边界是 Artifact 默认不进入 Wiki，因为生成内容可能只是阶段性草稿。个人侧要沉淀时，PersonalArtifactDistillationService 先生成 proposal，用户确认后再绑定最新 artifactVersionId，创建 SynthesisCard、artifact_card_relation 和 synthesis_card_citation。Skill 在当前项目里更像可控生成步骤和执行日志，不要夸成完全自主 Agent 平台。

常见追问：
- 为什么不直接改写 ConceptCard？
- proposal 为什么要绑定 artifactVersionId？
- 如果生成结果没有 citation，能不能沉淀？

### Q2: Artifact 为什么需要版本？

回答时按四步走：
1. 先说场景：面试官会追问生成结果如何保存、如何追溯、如何避免污染长期知识，以及 Skill 到底是不是 Agent。
2. 再说方案：StudioTaskService 创建 ARTIFACT_GENERATE 任务，ArtifactVersion 保存历史，ArtifactSource/ArtifactCitation 追踪来源，MethodologyCardService 支持预置和自定义方法论，PersonalArtifactDistillationService 用 proposal/confirm 把 Artifact 沉淀为 SynthesisCard。
3. 再说收益：生成结果既能作为阶段性产物迭代，又不会自动污染 Wiki 或个人概念库。
4. 最后落到真实代码锚点，不要停在概念。

可直接复述：

> Artifact 我会强调它不是 chat message 的一个字段，而是独立生命周期。一次生成通过 Studio 或 PersonalGeneration 创建 ARTIFACT_GENERATE 任务，结果保存为 Artifact 和 ArtifactVersion，来源用 ArtifactSource，引用用 ArtifactCitation。MethodologyCard 的价值是把 workflow、outputStructure、qualityChecklist 作为可管理的输出方法论注入 prompt，而不是在代码里写死一堆模板。最关键的边界是 Artifact 默认不进入 Wiki，因为生成内容可能只是阶段性草稿。个人侧要沉淀时，PersonalArtifactDistillationService 先生成 proposal，用户确认后再绑定最新 artifactVersionId，创建 SynthesisCard、artifact_card_relation 和 synthesis_card_citation。Skill 在当前项目里更像可控生成步骤和执行日志，不要夸成完全自主 Agent 平台。

常见追问：
- 为什么不直接改写 ConceptCard？
- proposal 为什么要绑定 artifactVersionId？
- 如果生成结果没有 citation，能不能沉淀？

### Q3: MethodologyCard 解决什么问题？

回答时按四步走：
1. 先说场景：面试官会追问生成结果如何保存、如何追溯、如何避免污染长期知识，以及 Skill 到底是不是 Agent。
2. 再说方案：StudioTaskService 创建 ARTIFACT_GENERATE 任务，ArtifactVersion 保存历史，ArtifactSource/ArtifactCitation 追踪来源，MethodologyCardService 支持预置和自定义方法论，PersonalArtifactDistillationService 用 proposal/confirm 把 Artifact 沉淀为 SynthesisCard。
3. 再说收益：生成结果既能作为阶段性产物迭代，又不会自动污染 Wiki 或个人概念库。
4. 最后落到真实代码锚点，不要停在概念。

可直接复述：

> Artifact 我会强调它不是 chat message 的一个字段，而是独立生命周期。一次生成通过 Studio 或 PersonalGeneration 创建 ARTIFACT_GENERATE 任务，结果保存为 Artifact 和 ArtifactVersion，来源用 ArtifactSource，引用用 ArtifactCitation。MethodologyCard 的价值是把 workflow、outputStructure、qualityChecklist 作为可管理的输出方法论注入 prompt，而不是在代码里写死一堆模板。最关键的边界是 Artifact 默认不进入 Wiki，因为生成内容可能只是阶段性草稿。个人侧要沉淀时，PersonalArtifactDistillationService 先生成 proposal，用户确认后再绑定最新 artifactVersionId，创建 SynthesisCard、artifact_card_relation 和 synthesis_card_citation。Skill 在当前项目里更像可控生成步骤和执行日志，不要夸成完全自主 Agent 平台。

常见追问：
- 为什么不直接改写 ConceptCard？
- proposal 为什么要绑定 artifactVersionId？
- 如果生成结果没有 citation，能不能沉淀？

### Q4: 为什么沉淀成 SynthesisCard 需要用户确认？

回答时按四步走：
1. 先说场景：面试官会追问生成结果如何保存、如何追溯、如何避免污染长期知识，以及 Skill 到底是不是 Agent。
2. 再说方案：StudioTaskService 创建 ARTIFACT_GENERATE 任务，ArtifactVersion 保存历史，ArtifactSource/ArtifactCitation 追踪来源，MethodologyCardService 支持预置和自定义方法论，PersonalArtifactDistillationService 用 proposal/confirm 把 Artifact 沉淀为 SynthesisCard。
3. 再说收益：生成结果既能作为阶段性产物迭代，又不会自动污染 Wiki 或个人概念库。
4. 最后落到真实代码锚点，不要停在概念。

可直接复述：

> Artifact 我会强调它不是 chat message 的一个字段，而是独立生命周期。一次生成通过 Studio 或 PersonalGeneration 创建 ARTIFACT_GENERATE 任务，结果保存为 Artifact 和 ArtifactVersion，来源用 ArtifactSource，引用用 ArtifactCitation。MethodologyCard 的价值是把 workflow、outputStructure、qualityChecklist 作为可管理的输出方法论注入 prompt，而不是在代码里写死一堆模板。最关键的边界是 Artifact 默认不进入 Wiki，因为生成内容可能只是阶段性草稿。个人侧要沉淀时，PersonalArtifactDistillationService 先生成 proposal，用户确认后再绑定最新 artifactVersionId，创建 SynthesisCard、artifact_card_relation 和 synthesis_card_citation。Skill 在当前项目里更像可控生成步骤和执行日志，不要夸成完全自主 Agent 平台。

常见追问：
- 为什么不直接改写 ConceptCard？
- proposal 为什么要绑定 artifactVersionId？
- 如果生成结果没有 citation，能不能沉淀？

### Q5: Skill 在这个项目里到底是什么，不是什么？

回答时按四步走：
1. 先说场景：面试官会追问生成结果如何保存、如何追溯、如何避免污染长期知识，以及 Skill 到底是不是 Agent。
2. 再说方案：StudioTaskService 创建 ARTIFACT_GENERATE 任务，ArtifactVersion 保存历史，ArtifactSource/ArtifactCitation 追踪来源，MethodologyCardService 支持预置和自定义方法论，PersonalArtifactDistillationService 用 proposal/confirm 把 Artifact 沉淀为 SynthesisCard。
3. 再说收益：生成结果既能作为阶段性产物迭代，又不会自动污染 Wiki 或个人概念库。
4. 最后落到真实代码锚点，不要停在概念。

可直接复述：

> Artifact 我会强调它不是 chat message 的一个字段，而是独立生命周期。一次生成通过 Studio 或 PersonalGeneration 创建 ARTIFACT_GENERATE 任务，结果保存为 Artifact 和 ArtifactVersion，来源用 ArtifactSource，引用用 ArtifactCitation。MethodologyCard 的价值是把 workflow、outputStructure、qualityChecklist 作为可管理的输出方法论注入 prompt，而不是在代码里写死一堆模板。最关键的边界是 Artifact 默认不进入 Wiki，因为生成内容可能只是阶段性草稿。个人侧要沉淀时，PersonalArtifactDistillationService 先生成 proposal，用户确认后再绑定最新 artifactVersionId，创建 SynthesisCard、artifact_card_relation 和 synthesis_card_citation。Skill 在当前项目里更像可控生成步骤和执行日志，不要夸成完全自主 Agent 平台。

常见追问：
- 为什么不直接改写 ConceptCard？
- proposal 为什么要绑定 artifactVersionId？
- 如果生成结果没有 citation，能不能沉淀？

## 7. 不能说满的地方
- 不要说当前已经是开放式 Agent 平台。
- 不要说 Artifact 会自动进入知识库。
- 不要把 MethodologyCard 说成模型训练或 RL。

## 8. 零基础记忆法
记住一句话：先讲“为什么需要这个模块”，再讲“请求从哪里来、状态落在哪里、失败怎么恢复、证据怎么追踪、权限怎么兜底”。按这个顺序答，大多数追问都能接住。
