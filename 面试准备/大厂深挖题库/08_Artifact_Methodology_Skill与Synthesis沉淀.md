# Artifact Methodology Skill 与 Synthesis 沉淀

> 本文件为 2026-06-01 重构版，依据当前代码、测试和 Flyway 迁移整理。不要再按旧阶段计划或旧题库口径背。

## 0. 本篇定位
这篇专门应对三个高风险追问：生成结果怎么落库和追溯，Methodology 为什么不是硬编码 prompt，Skill/MCP 到底是工程化编排还是被夸大的 Agent 平台。

## 1. 面试先说版
Artifact 不是 ChatMessage 的附属字段，而是一个有独立生命周期的生成产物。生成入口可以来自 Studio，也可以来自个人研究链路，统一创建 `ARTIFACT_GENERATE` 任务；执行时 `ArtifactPlanExecutor` 按计划加载上下文、可选加载 MCP 工具上下文、选择 evidence 或卡片、调用 LLM 生成，再通过 `SaveArtifactSkill` 保存 Artifact、ArtifactVersion、ArtifactSource、ArtifactCitation 和 SkillExecutionLog。MethodologyCard 负责把 workflow、outputStructure、qualityChecklist 这类方法论配置注入 prompt，避免所有输出模板写死在代码里。生成结果默认不自动进入 Wiki 或 ConceptCard，因为它可能只是阶段性草稿；真正沉淀到个人长期知识时，要由 `PersonalArtifactDistillationService` 先生成 proposal，再由用户确认后绑定具体 `artifactVersionId` 写入 SynthesisCard。

## 2. 当前真实口径
Artifact 是版本化产物，MethodologyCard 是可管理的输出方法论，Skill 是受控生成步骤和执行日志，Bilibili MCP 是 Studio 工具扩展路径之一，不是完整开放式 MCP 平台。

### 已实现
- Artifact 类型覆盖 REPORT、STUDY_GUIDE、READING_NOTES、BRIEFING、FAQ、COMPARISON、WIKI_DRAFT、ONBOARDING_GUIDE、TECHNICAL_SUMMARY、INCIDENT_REVIEW_DRAFT、PRESENTATION_OUTLINE、TIMELINE、WORK_PREP、MIND_MAP_OUTLINE。
- `StudioTaskService` 创建 `ARTIFACT_GENERATE` 任务，并通过 Task/Outbox/Kafka/Worker 进入后台执行。
- `ArtifactPlanExecutor` 根据 artifact type 选择 Skill 序列，记录 `SkillExecutionLog`，并支持 `LoadMcpToolContextSkill`。
- `MethodologyCardController/Service` 支持方法论卡片 CRUD、归档、版本和 PROJECT/SPACE/PRESET 作用域。
- `PersonalArtifactDistillationService` 支持 proposal/confirm，把 ArtifactVersion 沉淀为 SynthesisCard，并保留卡片关系和 citation。
- `StudioMcpToolRegistry`、`RemoteBilibiliMcpToolService`、`LocalBilibiliMcpToolService`、`StudioMcpChatTriggerService` 支持受控的 Bilibili MCP 工具上下文。

### 不能说满
- 不要说当前是完全自治 Agent 平台。
- 不要说 Artifact 自动进入团队 Wiki 或个人知识库。
- 不要把 MethodologyCard 说成模型训练、RL 或自动学习策略。
- 不要把 Bilibili MCP 说成完整 MCP marketplace 或所有工具调用主协议。

## 3. 代码和测试锚点
- `src/main/java/com/noteweave/studio/service/StudioTaskService.java`
- `src/main/java/com/noteweave/artifact/service/ArtifactPlanExecutor.java`
- `src/main/java/com/noteweave/artifact/service/ArtifactPersistenceService.java`
- `src/main/java/com/noteweave/personal/methodology/service/MethodologyCardService.java`
- `src/main/java/com/noteweave/personal/distillation/service/PersonalArtifactDistillationService.java`
- `src/main/java/com/noteweave/studio/service/StudioMcpToolRegistry.java`
- `src/main/java/com/noteweave/studio/service/RemoteBilibiliMcpToolService.java`
- `src/test/java/com/noteweave/artifact/Phase8StudioArtifactIntegrationTest.java`
- `src/test/java/com/noteweave/personal/Phase11PersonalGenerationIntegrationTest.java`
- `src/test/java/com/noteweave/personal/Phase11_5PersonalArtifactDistillationIntegrationTest.java`
- `src/test/java/com/noteweave/chat/Phase11_6ChatMcpIntegrationTest.java`
- `src/test/java/com/noteweave/studio/service/RemoteBilibiliMcpToolServiceTest.java`

## 4. 必会问题与深答

### Q1: Artifact 为什么要独立于 ChatMessage？
Artifact 面向的是可编辑、可导出、可版本化的阶段性成果，ChatMessage 面向的是一次会话过程。如果把报告、学习指南、FAQ 这类结果只塞进 message content，后面就很难回答三个问题：这个结果用了哪些来源，哪一版被用户确认，重新生成后旧版本还能不能追溯。当前项目把生成产物抽成 Artifact，并用 ArtifactVersion 保存历史，用 ArtifactSource 记录来自 ChatMessage、Document、Card 或 MCP 工具上下文的来源，用 ArtifactCitation 关联证据。这样面试时可以强调：聊天是交互入口，Artifact 是知识工作台里的正式产物，两者生命周期不同。

追问接法：
- 如果面试官问“是不是过度设计”，回答：只有普通闲聊不需要 Artifact；但 NoteWeave 的目标包含报告、学习指南、Wiki 草稿和工作准备，这些结果需要复用、导出、重生成和沉淀，所以要独立建模。
- 如果问“如何避免越权”，回答：Artifact 不能只靠创建时权限，读取来源、citation、distillation 时都要回到 space/user 边界检查。

### Q2: Artifact 为什么需要版本？
版本解决的是“生成结果会被迭代，但历史责任不能丢”。同一个 Artifact 可能因为换了 Methodology、补充了 Source、重新选择 evidence 或重新调用模型而生成新内容。如果直接覆盖，用户看到的 SynthesisCard、Wiki 草稿或导出文件就无法解释来自哪一次生成。当前项目通过 ArtifactVersion 按 versionNo 递增保存内容，SynthesisCard 的沉淀还会绑定 `sourceArtifactVersionId`，这使得后续追溯能落到具体版本，而不是模糊地指向一个会变的 Artifact。

追问接法：
- “版本是不是会无限膨胀”：可以按归档、保留策略、低价值草稿清理和对象存储生命周期继续优化。
- “重生成失败怎么办”：旧版本仍然存在，Task 失败只影响本次 attempt，不应该破坏上一个可用版本。

### Q3: MethodologyCard 解决什么问题？
MethodologyCard 解决的是“输出方法论可管理”，不是简单 prompt 模板。比如报告、学习指南、工作准备、事故复盘的结构不一样，质量检查项也不一样。如果全部硬编码到 `ArtifactPlanExecutor` 里，新增一种输出风格就要改代码；如果完全交给用户自然语言，又很难保证稳定结构。当前项目把 workflow、outputStructure、qualityChecklist 等字段放到 MethodologyCard，并区分 PRESET、SPACE、PROJECT 作用域，再由 MethodologyMatcher 和 MethodologyPromptSectionBuilder 注入生成提示。面试时可以说它让生成链路具备“可配置但受控”的能力。

追问接法：
- “为什么要 PROJECT/SPACE/PRESET 三级”：项目级适合某个研究主题，空间级适合团队或个人长期偏好，预置级提供默认兜底。
- “会不会 prompt 太长”：需要在匹配阶段只选最相关方法论，并把结构化字段压成稳定 section，而不是堆所有卡片。

### Q4: 为什么沉淀成 SynthesisCard 需要用户确认？
因为模型生成结果不等于长期知识。Artifact 可能包含推断、临时表达或尚未核验的总结；如果自动写入 ConceptCard 或 SynthesisCard，就会污染个人知识库。当前项目采用 proposal/confirm 两步：先由 `PersonalArtifactDistillationService` 生成沉淀建议，用户确认后才创建 SynthesisCard，并通过 `artifact_card_relation`、`synthesis_card_citation` 和 `sourceArtifactVersionId` 记录来源。这样既保留 AI 辅助整理效率，又把长期知识的最终责任交给用户。

追问接法：
- “为什么不直接改 ConceptCard”：ConceptCard 更偏概念事实和关系，SynthesisCard 更适合用户确认后的综合结论，直接改概念卡会破坏原有证据结构。
- “没有 citation 能不能沉淀”：可以生成草稿，但正式沉淀应该降低信任等级，至少在 UI 或审计上标记证据不足。

### Q5: Skill 在这个项目里到底是什么，不是什么？
Skill 在当前项目里是受控生成步骤，不是开放式自主 Agent。`ArtifactPlanExecutor` 会根据 artifact type 编排固定步骤，例如 `LoadGenerationContextSkill`、`SelectEvidenceSkill`、`GenerateReportSkill`、`SaveArtifactSkill`；每一步有输入、输出和日志，方便调试生成失败、证据缺失和 prompt 质量问题。它的价值是把“生成一个产物”拆成可观察的流水线，而不是让模型自由决定要调用什么、写哪里、删哪里。

追问接法：
- “为什么不说 Agent”：因为当前没有开放规划器、任意工具调用、长期自主目标和自动执行闭环，说 Agent 容易被追穿。
- “怎么说得更强”：可以说它是 Agent 化能力的地基，已经有 plan、tool context、skill log、task orchestration，但当前按可控工作流落地。

### Q6: Bilibili MCP 工具在当前项目里具体落在哪条链路？
Bilibili MCP 落在 Studio/Artifact 生成链路和 Chat 显式触发链路。用户可以在生成参数里配置 `mcpToolName=bilibili` 和 URL，也可以在聊天里用 `/mcp bilibili <url>` 显式触发。`StudioMcpToolRegistry` 解析参数，远程模式走 `RemoteBilibiliMcpToolService` 调用独立的 `/api/v1/mcp/bilibili/invoke` 服务，本地模式走 `LocalBilibiliMcpToolService` 和 `BilibiliMcpCoreService`。工具返回的视频标题、元信息、字幕上下文会作为 `MCP Tool Context (bilibili)` 注入 Artifact prompt，后续仍然走 `ARTIFACT_GENERATE`、Skill 日志、ArtifactVersion 保存这条主链路。

追问接法：
- “这是不是完整 MCP 平台”：不是。当前是受控注册的 Bilibili 工具扩展，能说明工具解耦和远程服务化，但不能说成通用 marketplace。
- “为什么要远程服务”：B 站解析、字幕抓取和外部网络依赖可以独立部署、隔离失败和演进，主系统只消费结构化上下文。

### Q7: 如果生成结果被质疑不准确，你怎么排查？
先查 Task，再查 Skill，再查证据。第一层看 `TaskAttempt` 和 `TaskEvent`，确认本次 `ARTIFACT_GENERATE` 是否成功、是否重试、是否中途取消。第二层看 `SkillExecutionLog`，确认上下文加载、MCP 工具调用、evidence 选择和生成步骤各自输入输出。第三层查 ArtifactCitation、Citation、RetrievalTrace 或 Source/Card 证据，确认生成内容是否真的有依据。最后看 MethodologyCard 和 prompt section，判断是不是输出结构或质量检查项诱导了错误表达。

追问接法：
- “如果是 MCP 字幕错了”：区分工具上下文错误和模型总结错误，优先保留工具返回原文、视频 URL 和标题，必要时重新拉取或标记该来源低置信。
- “如果是用户资料越权”：立刻查 ArtifactSource 和 citation 的资源归属，确认返回前是否做了二次权限校验。

## 5. 大厂深挖追问路径
1. 先问你为什么需要 Artifact，而不是把结果存在 message。
2. 再问版本、来源、citation 和 distillation 如何保证可追溯。
3. 再问 Methodology 和 Skill 是否只是 prompt 模板换皮。
4. 然后抓 MCP/Agent 这些简历高风险词，确认你有没有夸大。
5. 最后问失败、越权、证据缺失、外部工具超时时怎么兜底。

## 6. 一句话记忆
Artifact 是“可版本化产物”，Methodology 是“可管理输出方法论”，Skill 是“可观察生成步骤”，Synthesis 是“用户确认后的长期沉淀”，Bilibili MCP 是“受控工具上下文扩展”，不是万能 Agent 平台。
