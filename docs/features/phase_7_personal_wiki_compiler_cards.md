# Phase 7: 个人 Wiki Compiler MVP - ArticleCard 与 ConceptCard

本文档用于指导 NoteWeave 第七阶段编码实现。

范围：

```text
Phase 7: Source Compile / ArticleCard / Candidate Concept / ConceptCard / ArticleConceptRelation / Citation Backtrace
```

第七阶段目标是把个人 ResearchProject 中的 Source 编译成结构化卡片，形成个人 Wiki Compiler 的 MVP。

本阶段只做 ArticleCard 和 ConceptCard，不做 MethodologyCard 自动生成，不做个人问答，不做 Artifact。

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
confidence
cardStatus
createdAt
updatedAt
```

唯一约束：

```text
research_project_id + normalized_name
```

### ConceptAlias

```text
conceptCardId
alias
normalizedAlias
```

### ArticleConceptRelation

```text
articleCardId
conceptCardId
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
存在则合并补充
  ↓
不存在则创建 ConceptCard
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
      "evidence": "原文证据",
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
```

归一化规则 MVP：

```text
trim
lowercase
全角半角归一
去除多余空格
常见别名匹配
```

### EvidenceBacktraceService

```java
boolean quoteExistsInSource(Source source, String quote);
```

MVP：

- 简单字符串包含校验。
- 不存在时仍可保存，但标记低 confidence 或写 warning。

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

