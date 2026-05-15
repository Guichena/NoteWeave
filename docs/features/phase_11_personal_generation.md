# Phase 11: 个人 Wiki-based Generation

本文档用于指导 NoteWeave 第十一阶段编码实现。

范围：

```text
Phase 11: Personal Generation / Research Report / Study Guide / Comparison / Work Prep / Artifact
```

第十一阶段目标是基于个人 ResearchProject 中的 Source、ArticleCard、ConceptCard、SynthesisCard 和 MethodologyCard，生成可复用 Artifact，包括研究报告、学习指南、对比分析和方案表达训练。

本阶段复用 Phase 8 的 `Task / Skill / Artifact` 机制，不做 Quiz 答题评分，不做外部资料发现，不做 MethodologyCard 自动抽取。

---

## 1. 参考文档

```text
docs/features/phase_6_personal_research_source.md
docs/features/phase_7_personal_wiki_compiler_cards.md
docs/features/phase_8_studio_artifact_generation.md
docs/features/database_api_blueprint.md
```

---

## 2. 阶段目标

完成后系统应具备：

- 用户可以基于个人 ResearchProject 生成 Artifact。
- 支持 Research Report。
- 支持 Study Guide。
- 支持 Comparison。
- 支持 Work Prep。
- 生成时优先使用 ArticleCard / ConceptCard；如果项目中已存在 SynthesisCard，也可作为稳定二次知识上下文，并结合 MethodologyCard 组织输出。
- 必要时回溯 Source Raw Text。
- 生成结果保存为 Artifact。
- Artifact 与 ArticleCard / ConceptCard / Source 建立来源关系。
- Artifact 与 Citation 建立关系。
- Artifact 默认不写入个人 Wiki；用户确认后的沉淀流程放到 Phase 11.5。
- 所有个人生成资源 owner-only。

---

## 3. 本阶段不做的事

- 不做 Quiz 答题、评分、错题复习。
- 不做外部论文搜索。
- 不做网页搜索。
- 不做 MethodologyCard 自动抽取。
- 不做团队 Wiki 发布。
- 不做生成完成后自动写入个人 Wiki。
- 不做 Artifact -> SynthesisCard 沉淀。
- MVP 不直接把 Artifact 合并进已有 ConceptCard。
- 不做复杂 Agent 自主规划。

---

## 4. Artifact 类型

本阶段支持：

```text
RESEARCH_REPORT
STUDY_GUIDE
COMPARISON
WORK_PREP
```

可以兼容 Phase 8 中已有：

```text
REPORT
STUDY_GUIDE
COMPARISON
```

推荐工程枚举统一使用：

```text
REPORT
STUDY_GUIDE
COMPARISON
WORK_PREP
```

---

## 5. 生成上下文来源

优先级：

```text
ResearchProject
  ↓
ArticleCard
  ↓
ConceptCard
  ↓
SynthesisCard
  ↓
ArticleConceptRelation
  ↓
Source Raw Text
```

原则：

- ArticleCard 提供文章级摘要和要点。
- ConceptCard 提供跨资料概念融合。
- SynthesisCard 如已存在，可提供用户确认后的二次知识总结。
- Source Raw Text 只在需要证据引用时回溯。
- 不全量塞入所有 Source 原文。
- 生成 Artifact 时必须写入 `ArtifactSource`，记录参与生成的 ArticleCard、ConceptCard 和 Source。
- 生成 Artifact 后不自动创建 ArticleCard / ConceptCard / MethodologyCard / SynthesisCard。

---

## 6. Plan 设计

### Report Plan

```text
LoadResearchProjectSkill
SelectArticleCardSkill
SelectConceptCardSkill
BuildPersonalEvidenceSkill
GenerateResearchReportSkill
CitationBacktraceSkill
SaveArtifactSkill
```

### Study Guide Plan

```text
LoadResearchProjectSkill
SelectConceptCardSkill
BuildLearningPathSkill
GenerateStudyGuideSkill
SaveArtifactSkill
```

### Comparison Plan

```text
LoadResearchProjectSkill
SelectComparedConceptSkill
BuildComparisonMatrixSkill
GenerateComparisonSkill
SaveArtifactSkill
```

### Work Prep Plan

```text
LoadResearchProjectSkill
SelectConceptCardSkill
GenerateWorkPrepSkill
SaveArtifactSkill
```

---

## 7. Service 设计

### PersonalGenerationService

```java
CreateStudioTaskResponse createPersonalGenerationTask(Long userId, CreatePersonalGenerationRequest request);
```

### ResearchContextService

```java
ResearchGenerationContext load(Long userId, Long researchProjectId, GenerationScope scope);
```

职责：

- 加载 ResearchProject。
- 加载 ArticleCard。
- 加载 ConceptCard。
- 加载 SynthesisCard。
- 加载匹配到的 MethodologyCard。
- 加载必要 Source 元数据。
- 校验 owner-only。

### PersonalEvidenceService

```java
List<PersonalEvidenceItem> buildEvidence(ResearchGenerationContext context, EvidenceNeed need);
```

证据来源：

```text
ArticleCard.evidenceQuotesJson
ArticleConceptRelation.evidence
Source Raw Text quote
```

### PersonalArtifactGenerator

```java
ArtifactDraft generate(ResearchGenerationContext context, GenerationParams params);
void saveArtifactSources(Long artifactId, ResearchGenerationContext context, List<PersonalEvidenceItem> evidence);
```

不同 artifactType 可有不同实现。

ArtifactSource 写入规则：

```text
ARTICLE_CARD: 被选入上下文的 ArticleCard
CONCEPT_CARD: 被选入上下文的 ConceptCard
SYNTHESIS_CARD: 被选入上下文的 SynthesisCard
SOURCE: 被证据回溯引用的 Source
ARTIFACT: 当本次生成基于已有 Artifact 时记录
```

要求：

- `ArtifactSource` 与 `ArtifactCitation` 都要在同一生成事务或同一任务完成步骤内写入。
- 证据不足导致无法写入任何 Source 时，Artifact 状态不得标记 READY，应返回生成失败或证据不足错误。

### Phase 11.5 兼容边界

本阶段只生成 Artifact，不创建 SynthesisCard，不写 `artifact_card_relation`，也不提供 `distill-to-personal-wiki` API。

要求：

- 如果项目中已经存在 SynthesisCard，可作为生成上下文读取。
- 新生成 Artifact 必须保存 ArtifactVersion、ArtifactSource 和 ArtifactCitation，供 Phase 11.5 的沉淀流程使用。
- 任何 Concept / Methodology 更新都必须留到用户确认后的 proposal 阶段。

---

## 8. API 设计

复用 Studio Task：

```http
POST /api/v1/studio/tasks
GET  /api/v1/studio/tasks/{taskId}
```

个人生成请求：

```json
{
  "spaceId": 1,
  "researchProjectId": 100,
  "taskType": "ARTIFACT_GENERATE",
  "sourceScopeType": "RESEARCH_PROJECT",
  "sourceIds": [100],
  "params": {
    "artifactType": "REPORT",
    "topic": "RAG 技术调研报告",
    "length": "MEDIUM",
    "style": "STRUCTURED",
    "includeCitations": true
  }
}
```

Work Prep 请求：

```json
{
  "spaceId": 1,
  "researchProjectId": 100,
  "taskType": "ARTIFACT_GENERATE",
  "sourceScopeType": "RESEARCH_PROJECT",
  "sourceIds": [100],
  "params": {
    "artifactType": "WORK_PREP",
    "scenario": "技术面试",
    "targetRole": "Java 后端开发",
    "focus": ["RAG", "Kafka", "Redis"]
  }
}
```

---

## 9. Prompt 输出要求

研究报告：

```text
标题
摘要
背景
核心概念
关键发现
方案/观点对比
结论
引用来源
```

学习指南：

```text
学习目标
前置知识
核心概念路径
分阶段学习计划
自测问题
引用来源
```

对比分析：

```text
对比对象
评价维度
对比表格
适用场景
权衡结论
引用来源
```

Work Prep：

```text
表达框架
核心回答
追问准备
项目亮点表达
风险与改进
```

---

## 10. 权限要求

- ResearchProject owner-only。
- ArticleCard / ConceptCard owner-only。
- Source owner-only。
- Artifact owner-only，除非后续显式发布到团队。

---

## 11. 错误码补充

```text
PERSONAL_GENERATION_SCOPE_INVALID
RESEARCH_CONTEXT_EMPTY
RESEARCH_ARTICLE_CARD_MISSING
RESEARCH_CONCEPT_CARD_MISSING
PERSONAL_GENERATION_FAILED
```

---

## 12. 验收清单

- 可基于 ResearchProject 创建报告任务。
- 可生成 REPORT Artifact。
- 可生成 STUDY_GUIDE Artifact。
- 可生成 COMPARISON Artifact。
- 可生成 WORK_PREP Artifact。
- Artifact 与 ResearchProject 关联。
- Artifact 与来源 Card / Source 关联。
- Artifact 不会自动进入个人 Wiki。
- 本阶段不会创建 SynthesisCard，也不会写 ArtifactCardRelation。
- 其他用户不能访问生成结果。

---

## 13. 给 AI 执行第十一阶段的边界提醒

- 不要实现 Quiz 答题评分。
- 不要实现外部资料搜索。
- 不要实现 MethodologyCard 自动抽取。
- 不要实现团队 Wiki 发布。
- 不要实现个人 Artifact 沉淀到个人 Wiki。
- 必须复用 Task / Artifact。
- 所有 API 必须使用 `/api/v1`。
- 所有个人资源必须 owner-only。



