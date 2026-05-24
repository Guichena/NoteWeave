# 文件：07_Artifact_Skill_Agent边界.md

## 1. 本主题覆盖的通用问题

- Agent 项目里你具体做了什么？
- 这是固定工作流还是 Agent？
- 有没有 ReAct、LangChain、LangGraph、Spring AI？
- Skill 执行是什么？
- MethodologyCard 起什么作用？
- MCP、Bibtex、GraphRAG 在项目里有没有？
- Artifact 为什么不直接进入 Wiki？

## 2. 架构视角怎么切入

NoteWeave 当前不是完整开放 Agent 平台，而是受控生成工作流。这个回答要主动讲清楚，反而更可信。

当前主线是：

```text
Artifact Task
-> ArtifactPlanExecutor
-> fixed plan by ArtifactType
-> SkillExecutionLog
-> LLM generation
-> ArtifactVersion
-> Citation / Source relation
-> optional user-confirmed distillation
```

这个设计强调可控、可观测、可取消、可追溯，而不是让模型自由决定任意工具调用。

## 3. 完整链路深讲

### 3.1 Artifact 生成

```text
create studio task
-> create Artifact
-> create ARTIFACT_GENERATE Task
-> Worker executes ArtifactPlanExecutor
-> LoadGenerationContextSkill
-> SelectEvidence / SelectCards
-> GenerateXxxSkill
-> SaveArtifactSkill
-> ArtifactVersion
-> ArtifactSource / ArtifactCitation
```

### 3.2 Methodology 注入

个人生成时，系统会加载 ResearchProject 上下文，并通过 MethodologyMatcher 匹配方法论：

```text
project cards
-> personal space cards
-> preset cards
-> GENERAL fallback
```

MethodologyCard 提供 workflow、outputStructure、qualityChecklist。它不是硬编码 prompt，而是可管理的生成结构。

### 3.3 Skill 日志

每个 skill 记录：

- taskId。
- artifactId。
- artifactVersionId。
- skillName。
- status。
- latency。
- model。
- token。
- 脱敏 input/output summary。

这样可以定位生成失败卡在哪一步。

### 3.4 Distillation

Artifact 默认只是成果，不自动进入长期知识。

个人侧：

```text
ArtifactVersion
-> distill proposal
-> user preview
-> confirm
-> owner recheck
-> stale artifactVersion check
-> SynthesisCard
-> synthesis_card_citation
-> artifact_card_relation
```

## 4. 关键实现锚点

- `ArtifactPlanExecutor`
- `SkillExecutionLogService`
- `PersonalGenerationService`
- `ResearchContextService`
- `PersonalEvidenceService`
- `MethodologyMatcher`
- `MethodologyPromptSectionBuilder`
- `PersonalArtifactDistillationService`
- `ArtifactDistillationProposal`
- `SynthesisCard`

## 5. 为什么这么设计

### 5.1 为什么不是开放 Agent

开放 Agent 需要：

- 工具 schema。
- 工具权限。
- 沙箱。
- 预算控制。
- 循环终止。
- tool call log。
- 失败回滚。
- eval 指标。

当前项目更需要可信生成和可追踪成果，所以使用固定 pipeline 更合适。

### 5.2 为什么 Artifact 不直接变 Wiki/Card

LLM 输出可能有错误或不稳定。Artifact 是生成成果，Wiki/Card 是长期知识。中间必须有人工确认、版本绑定和 citation 复制。

### 5.3 MCP / Bibtex / GraphRAG 怎么回答

- MCP：未来可接到 Skill/tool 层或 Source import 层，不是当前主链路。
- Bibtex：未来可作为 Source importer，解析论文元数据和 PDF 链接，不是当前端到端实现。
- GraphRAG：当前主链路是 Hybrid RAG，有 Wiki/Concept relation 和图谱展示，但不是完整 GraphRAG 主链路。

## 6. 可直接复述的深答

我不会把 NoteWeave 当前实现说成完整开放 Agent 平台。更准确地说，它是受控的 AI 生成工作流。比如生成 Artifact 时，系统会根据 ArtifactType 选择固定 plan，先 LoadGenerationContext，再 SelectEvidence 或 SelectCards，然后 GenerateReport、GenerateWorkPrep 这类生成 skill，最后 SaveArtifact。每一步都有 SkillExecutionLog、进度、耗时、token、失败信息和取消点。个人生成还会匹配 MethodologyCard，把 workflow、输出结构和质量检查注入 prompt。生成结果保存为 ArtifactVersion，不会自动进入长期知识；团队要发布 Wiki，个人要 proposal/confirm 后才生成 SynthesisCard。MCP、Bibtex、GraphRAG 都可以作为后续扩展，但当前主链路不应该夸大成已经实现完整 Agent 或 GraphRAG。

## 7. 追问兜底

### 如果问“固定工作流是不是不智能”

固定工作流牺牲部分灵活性，但换来可控、可审计、可取消和更容易评测。知识系统里这是合理取舍。

### 如果问“未来如何做 Agent”

补工具 schema、权限、沙箱、预算、循环终止、tool log、eval、失败回滚，再逐步开放。

### 如果问“为什么不用 LangChain/Spring AI”

当前核心是业务闭环和工程控制，自己收口 prompt、LLM gateway、trace、citation 更可控。框架可以辅助，但不是核心竞争点。

