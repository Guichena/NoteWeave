# 文件：07_Artifact_Skill_Agent边界加深版.md

## 1. 这个主题要回答什么

这个主题覆盖：

- Artifact 是什么？
- Skill 是什么？
- MethodologyCard 是什么？
- 这是 Agent 吗？
- 有没有 ReAct、MCP、GraphRAG、LangChain？
- 为什么生成结果不直接进 Wiki？

## 2. 面向初学者：Artifact 是什么

Artifact 可以理解为“AI 生成的正式成果”。

比如：

- 报告。
- 学习指南。
- 对比分析。
- 工作准备材料。
- Wiki 草稿。

它和 ChatMessage 不同：

- ChatMessage 是对话记录。
- Artifact 是成果，有版本、来源、引用、导出、编辑和沉淀。

## 3. Artifact 完整链路

```text
用户创建生成任务
-> 创建 Artifact
-> 创建 ARTIFACT_GENERATE Task
-> Worker 执行 ArtifactPlanExecutor
-> LoadGenerationContextSkill
-> SelectEvidence / SelectCards
-> GenerateXxxSkill
-> SaveArtifactSkill
-> ArtifactVersion
-> ArtifactSource
-> ArtifactCitation
```

Artifact 的关键点：

- 不覆盖旧版本。
- 保留来源。
- 保留 citation。
- 可以重新生成。
- 可以沉淀为 Wiki/Card，但必须确认。

## 4. Skill 是什么

NoteWeave 里的 Skill 不是开放插件，也不是完整 Agent 工具调用。

它更像“受控工作流步骤”：

```text
LoadGenerationContextSkill
SelectEvidenceSkill
SelectArticleCardSkill
SelectConceptCardSkill
GenerateReportSkill
SaveArtifactSkill
```

每个 Skill 有明确职责：

- 输入是什么。
- 输出是什么。
- 失败怎么记录。
- 是否可取消。
- 耗时和 token 怎么记录。

## 5. MethodologyCard 是什么

MethodologyCard 是生成方法论。它告诉模型：

- 生成步骤 workflow。
- 输出结构 outputStructure。
- 质量检查 qualityChecklist。

例如 WORK_PREP 类型可以用 STAR 结构；REPORT 可以用背景、发现、结论、建议结构。

为什么不写死 prompt？

- 不同项目需要不同方法论。
- 用户可能自定义。
- 预设模板要复用。
- 后续要版本管理和治理。

匹配顺序：

```text
project-level
-> personal-space-level
-> preset-level
-> GENERAL fallback
```

## 6. 八股知识点：Workflow、Agent、ReAct 的区别

### 6.1 Workflow

Workflow 是固定流程。步骤由系统设计者提前定义。

优点：

- 可控。
- 易测试。
- 易排障。
- 成本可预测。

缺点：

- 灵活性较弱。

NoteWeave 当前主要是 workflow。

### 6.2 Agent

Agent 通常指模型能根据目标自主规划步骤、选择工具、观察结果、继续行动。

典型能力：

- planning。
- tool calling。
- memory。
- reflection。
- multi-step execution。

风险：

- 不稳定。
- 成本不可控。
- 工具误调用。
- 循环不停止。
- 安全边界复杂。

### 6.3 ReAct

ReAct 是 Reasoning + Acting。模型交替进行思考和行动：

```text
Thought -> Action -> Observation -> Thought -> Action ...
```

适合开放工具调用，但需要严格控制。

NoteWeave 当前没有把 ReAct 作为主链路。

## 7. 为什么当前不做完整 Agent

因为 NoteWeave 是知识工作台，核心诉求是可信和可审计。

完整 Agent 需要补：

- tool schema。
- tool permission。
- sandbox。
- budget。
- max steps。
- loop detection。
- tool call log。
- fallback。
- eval。

当前阶段用固定 Skill pipeline 更稳。

## 8. MCP / Bibtex / GraphRAG 怎么回答

### 8.1 MCP

当前不是主链路。未来可以接在：

- Source import。
- Skill tool layer。
- Admin tool integration。

需要补权限、审计、超时、沙箱。

### 8.2 Bibtex

当前不是端到端能力。未来可以作为 Source importer：

```text
Bibtex metadata
-> Source
-> PDF / URL
-> Source compile
-> ArticleCard / ConceptCard
```

### 8.3 GraphRAG

当前主链路是 Hybrid RAG。项目有 Wiki relation、ConceptRelation、Graph Inspector，但不能说完整 GraphRAG 主链路。

## 9. 为什么 Artifact 不直接进 Wiki

生成结果可能：

- 有幻觉。
- 结构不稳定。
- citation 不完整。
- 用户还没确认。

所以：

```text
Artifact != Wiki
Artifact != Card
```

个人侧必须 proposal/confirm 后才创建 SynthesisCard；团队侧必须人工发布 Wiki。

## 10. 底层原理补充：工作流编排和 Agent 安全

### 10.1 工作流编排本质是什么

工作流编排就是把一个复杂任务拆成多个可控步骤，每一步有明确输入输出。

NoteWeave 的 Artifact 生成就是：

```text
加载上下文 -> 选择证据 -> 生成内容 -> 保存版本
```

好处是每一步都能记录日志、检查取消、定位失败。

### 10.2 Agent 为什么更难控

Agent 让模型自己决定下一步。它的难点是：

- 模型可能选错工具。
- 参数可能填错。
- 工具结果可能被误解。
- 循环可能停不下来。
- 成本不可控。
- 权限风险更高。

所以面试时说 Agent 要同时说安全边界，否则容易被认为只会追概念。

### 10.3 Tool Schema 为什么重要

开放工具调用时，必须定义工具的输入输出 schema，例如：

```json
{
  "tool": "search_source",
  "params": {
    "projectId": "number",
    "query": "string"
  }
}
```

没有 schema，模型输出就很难校验，也难以安全执行。

### 10.4 预算和终止条件

Agent 必须限制：

- max steps。
- max tokens。
- max tool calls。
- timeout。
- cost budget。

否则一次任务可能无限循环或成本失控。

## 11. 可直接复述的深答

NoteWeave 里的 Artifact 是 AI 生成成果，不是普通聊天消息。它有 ArtifactVersion、ArtifactSource 和 ArtifactCitation，可以编辑、导出、重新生成，也可以在用户确认后沉淀。生成过程不是开放式 Agent，而是受控 Skill pipeline。比如报告生成会先 LoadGenerationContext，再 SelectEvidence，再 GenerateReport，最后 SaveArtifact，每一步都有日志、耗时、token、状态和取消点。MethodologyCard 则提供生成方法论，比如 workflow、输出结构和质量检查，不直接写死在 prompt 里，而是按 project、space、preset 匹配。当前项目不应该夸成完整 Agent、ReAct 或 MCP 主链路；它更强调可控、可审计和可追踪。未来如果要做开放 Agent，需要补工具 schema、权限、沙箱、预算、循环终止和 eval。

## 12. 面试官可能追问

### 12.1 固定 workflow 会不会不够智能

在知识系统里，稳定性和可追踪比自由度更重要。当前选择 workflow 是工程取舍。

### 12.2 未来怎么升级 Agent

从受控工具开始，逐步加入 tool schema、权限、预算、max steps、tool log 和 eval。

### 12.3 为什么不直接用 LangChain

框架不是核心。NoteWeave 的核心是业务模型、证据、权限和沉淀边界。框架可以接，但不能替代领域设计。
