# Phase 7: 个人 Wiki Compiler MVP - ArticleCard 与 ConceptCard

本文档用于指导 NoteWeave 第七阶段编码实现。

范围：

```text
Phase 7: Source Compile / ArticleCard / Candidate Concept / ConceptCard / ArticleConceptRelation / Citation Backtrace
```

第七阶段目标是把个人 ResearchProject 中的 Source 编译成结构化卡片，形成个人 Wiki Compiler 的 MVP。

本阶段只做 ArticleCard 和 ConceptCard，不做 MethodologyCard 自动生成，不做个人问答，不做 Artifact，也不做 Artifact 沉淀回个人 Wiki。

---

## 1. 参考文档

```text
docs/features/phase_6_personal_research_source.md
docs/features/database_api_blueprint.md
docs/implementation_breakdown.md
```

---

## 2. 阶段目标

完成后系统应具备：

- 用户可以触发 Source 编译。
- Source 编译生成 ArticleCard。
- ArticleCard 保存摘要、要点、标签和证据片段。
- 从 ArticleCard / Source 文本中抽取 Candidate Concept。
- 对概念做简单归一化。
- 创建或合并 ConceptCard。
- 建立 ArticleConceptRelation。
- Card 能回溯 Raw Source 证据。
- 编译任务通过 `Task(type = SOURCE_COMPILE)` 管理状态。

---

## 3. 本阶段不做的事

- 不做 MethodologyCard 自动生成。
- 不做复杂知识图谱可视化。
- 不做个人 Chat。
- 不做 Artifact。
- 不做外部论文搜索。
- 不做复杂概念消歧模型。

---

## 4. 包结构

```text
com.noteweave.personal.compiler
  ├── dto
  ├── service
  └── prompt

com.noteweave.personal.card
  ├── controller
  ├── dto
  ├── model
  ├── repository
  └── service
```

建议类：

```text
WikiCompilerService
ArticleCardService
ConceptExtractionService
ConceptMergeService
EvidenceBacktraceService
ArticleCard
ConceptCard
SynthesisCard
ConceptAlias
ConceptRelation
ArticleConceptRelation
```

---

## 5. 数据模型

### ArticleCard

表：`article_card`

字段：

```text
id
spaceId
researchProjectId
sourceId
title
summary
keyPointsJson
tagsJson
evidenceQuotesJson
cardStatus
createdAt
updatedAt
```

`evidenceQuotesJson` 只作为展示缓存；正式证据必须写入 `article_card_citation`。

### ConceptCard

表：`concept_card`

字段：

```text
id
spaceId
researchProjectId
name
normalizedName
definition
explanation
useCasesJson
commonMisunderstandingsJson
evidenceQuotesJson
confidence
cardStatus
createdAt
updatedAt
```

唯一约束：

```text
research_project_id + normalized_name
```

证据要求：

```text
贡献该 Concept 的 Source ID 通过 ArticleConceptRelation.sourceId 追溯
evidenceQuotesJson 中每条 quote 必须绑定 sourceId / articleCardId
正式证据关系必须写入 concept_card_citation
```

ConceptCard 可以融合多篇资料，但不能丢失定义、用法或误区的来源。合并已有 ConceptCard 时追加证据，不覆盖旧 evidence。`evidenceQuotesJson` 只作为展示缓存，不作为唯一证据来源。

### ConceptAlias

```text
conceptCardId
alias
normalizedAlias
```

### SynthesisCard

表：`synthesis_card`

说明：SynthesisCard 属于个人 Wiki Card，但不是本阶段从 Source 编译产生；它由后续个人 Artifact 经用户确认后沉淀得到。本阶段只预留模型边界。

字段：

```text
id
spaceId
researchProjectId
sourceArtifactId
sourceArtifactVersionId
title
summary
insightsJson
evidenceQuotesJson
cardStatus
createdBy
createdAt
updatedAt
```

MVP 规则：

- Source 编译不创建 SynthesisCard。
- Artifact 沉淀到个人 Wiki 时优先创建 SynthesisCard。
- 后续增强再支持从 SynthesisCard 生成 Concept merge proposal。

### SynthesisConceptRelation

```text
synthesisCardId
conceptCardId
relationType
evidence
```

### Card Citation Relation

```text
article_card_citation
concept_card_citation
synthesis_card_citation
```

Card Citation 关联表是正式证据来源；`evidenceQuotesJson` 只能缓存少量展示片段。

### ArticleConceptRelation

```text
articleCardId
conceptCardId
sourceId
evidence
relevanceScore
```

---

## 6. 编译流程

```text
用户触发 Source compile
  ↓
创建 Task(SOURCE_COMPILE)
  ↓
加载 Source Raw Text
  ↓
LLM 生成 ArticleCard JSON
  ↓
保存 ArticleCard
  ↓
LLM 抽取 Candidate Concepts
  ↓
概念归一化 normalizedName
  ↓
查找已有 ConceptCard
  ↓
高置信度存在则合并补充
  ↓
低置信度或不存在则创建 ConceptCard
  ↓
保存 ConceptAlias
  ↓
保存 ArticleConceptRelation
  ↓
更新 Source.compileStatus = READY
  ↓
更新 ResearchProject.compileStatus
  ↓
Task = SUCCESS
```

失败：

```text
Source.compileStatus = FAILED
Task = FAILED
记录 errorMessage
```

---

## 7. LLM 输出约束

ArticleCard JSON：

```json
{
  "title": "文章标题",
  "summary": "摘要",
  "keyPoints": ["要点1", "要点2"],
  "tags": ["RAG", "检索"],
  "evidenceQuotes": [
    {
      "quote": "原文片段",
      "sourceId": 1001,
      "reason": "为什么重要"
    }
  ]
}
```

Concept JSON：

```json
{
  "concepts": [
    {
      "name": "RAG",
      "aliases": ["Retrieval Augmented Generation"],
      "definition": "定义",
      "explanation": "解释",
      "useCases": ["场景1"],
      "evidence": {
        "sourceId": 1001,
        "quote": "原文证据"
      },
      "confidence": 0.85
    }
  ]
}
```

要求：

- 必须解析 JSON。
- JSON 解析失败要重试一次或标记失败。
- 不允许直接保存未校验的自由文本。

---

## 8. Service 设计

### WikiCompilerService

```java
CompileSourceResponse compileSource(Long userId, Long sourceId);
void executeCompileTask(Long taskId);
```

### ArticleCardService

```java
ArticleCard createOrUpdateFromSource(Source source, ArticleCardDraft draft);
List<ArticleCardResponse> list(Long userId, Long projectId);
ArticleCardResponse get(Long userId, Long cardId);
```

### ConceptExtractionService

```java
List<CandidateConcept> extract(Source source, ArticleCard articleCard);
```

### ConceptMergeService

```java
ConceptCard createOrMerge(Long projectId, CandidateConcept candidate);
String normalizeName(String name);
MergeDecision decideMerge(Long projectId, CandidateConcept candidate);
```

归一化规则 MVP：

```text
trim
lowercase
全角半角归一
去除多余空格
常见别名匹配
```

合并规则：

```text
normalizedName 完全相同：直接合并
embedding 相似度 >= 0.88 且 LLM 判断 SAME：合并
embedding 相似度 0.78 ~ 0.88：标记 REVIEW 或创建新卡片
embedding 相似度 < 0.78：创建新卡片
```

合并校验：

- 每次合并后必须写入或更新 `ArticleConceptRelation`。
- 合并不得删除原有 ConceptAlias。
- 合并后的 ConceptCard 必须保留所有 `sourceId` 与 evidence quote。

### EvidenceBacktraceService

```java
boolean quoteExistsInSource(Source source, String quote);
void validateArticleEvidence(ArticleCard articleCard);
void validateConceptEvidence(ConceptCard conceptCard);
```

MVP：

- 简单字符串包含校验。
- 不存在时仍可保存，但必须标记低 confidence 或写 warning。
- ArticleCard / ConceptCard 返回给生成链路前必须能回溯到 Source 原文。

---

## 9. API 设计

```http
POST /api/v1/personal/sources/{sourceId}/compile
GET  /api/v1/personal/research-projects/{projectId}/article-cards
GET  /api/v1/personal/article-cards/{cardId}
GET  /api/v1/personal/research-projects/{projectId}/concept-cards
GET  /api/v1/personal/concept-cards/{cardId}
PUT  /api/v1/personal/concept-cards/{cardId}
POST /api/v1/personal/concept-cards/merge
```

Concept merge request：

```json
{
  "targetConceptId": 1,
  "sourceConceptIds": [2, 3]
}
```

---

## 10. 权限要求

- 只能编译自己的 Source。
- 只能查看自己的 ArticleCard / ConceptCard。
- Concept merge 只能在同一个 ResearchProject 内执行。
- 所有接口必须 owner-only。

---

## 11. 错误码补充

```text
SOURCE_NOT_READY
SOURCE_COMPILE_FAILED
ARTICLE_CARD_NOT_FOUND
CONCEPT_CARD_NOT_FOUND
CONCEPT_MERGE_INVALID
LLM_JSON_PARSE_FAILED
EVIDENCE_BACKTRACE_FAILED
```

---

## 12. 测试建议

```text
WikiCompilerServiceTest
ArticleCardServiceTest
ConceptExtractionServiceTest
ConceptMergeServiceTest
EvidenceBacktraceServiceTest
```

重点覆盖：

- Source 未 READY 时不能编译。
- 编译成功生成 ArticleCard。
- 同一个 Source 不重复生成多个 ArticleCard。
- 概念归一化能合并大小写差异。
- 同项目内同名 Concept 不重复创建。
- ArticleConceptRelation 正确建立。
- 其他用户不能访问卡片。

---

## 13. 验收清单

- Source 可以触发编译。
- Task 状态可查询。
- 编译后生成 ArticleCard。
- 编译后生成 ConceptCard。
- 编译后生成 ArticleConceptRelation。
- ArticleCard 可以查询。
- ConceptCard 可以查询。
- ConceptCard 可以手动更新。
- ConceptCard 可以手动合并。
- Source.compileStatus 正确更新。
- ResearchProject.compileStatus 正确更新。

---

## 14. 给 AI 执行第七阶段的边界提醒

- 不要实现 MethodologyCard 自动生成。
- 不要实现个人 Chat。
- 不要实现 Artifact。
- 不要实现外部论文搜索。
- LLM 输出必须按 JSON 解析。
- 所有 API 必须使用 `/api/v1`。
- 所有个人资源必须 owner-only。



