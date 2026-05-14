# Phase 13: MethodologyCard 方法论卡片

本文档用于指导 NoteWeave 第十三阶段编码实现。

范围：

```text
Phase 13: Preset MethodologyCard / Methodology Matching / Generation Structure / Quality Checklist
```

第十三阶段目标是为个人生成能力增加方法论层，让报告、学习指南、对比分析、方案表达训练等输出可以按稳定框架组织，而不是只依赖临时 Prompt。

---

## 1. 参考文档

```text
docs/features/phase_7_personal_wiki_compiler_cards.md
docs/features/phase_11_personal_generation.md
docs/features/database_api_blueprint.md
```

---

## 2. 阶段目标

- 支持 MethodologyCard 表。
- 内置一批预置 MethodologyCard。
- 生成任务可按 taskType 匹配 MethodologyCard。
- Prompt 构造时引用 MethodologyCard 的 workflow / outputStructure / qualityChecklist。
- 用户可以查看 MethodologyCard。
- 用户可以手动创建或编辑个人 MethodologyCard。
- 后续可基于 Source 自动抽取 MethodologyCard。

---

## 3. 本阶段不做的事

- 不做复杂自动抽取。
- 不做方法论图谱。
- 不做多用户共享模板市场。
- 不做复杂审核流。

---

## 4. 数据模型

表：`methodology_card`

字段：

```text
id
spaceId
researchProjectId
name
scene
problemType
workflowJson
requiredConceptsJson
outputStructureJson
qualityChecklistJson
cardSource
createdAt
updatedAt
```

cardSource：

```text
PRESET
USER_CREATED
AI_EXTRACTED
```

---

## 5. 预置模板

建议内置：

```text
Research Report Methodology
Study Guide Methodology
Comparison Analysis Methodology
Work Prep STAR Methodology
Technical Proposal Methodology
Postmortem Methodology
FAQ Methodology
```

示例：

```json
{
  "name": "Comparison Analysis Methodology",
  "scene": "技术方案对比",
  "problemType": "COMPARISON",
  "workflow": [
    "定义对比对象",
    "确定评价维度",
    "逐项比较",
    "总结适用场景",
    "给出权衡结论"
  ],
  "outputStructure": [
    "背景",
    "对比维度",
    "对比表格",
    "适用场景",
    "结论"
  ],
  "qualityChecklist": [
    "是否明确对比对象",
    "是否使用统一维度",
    "是否说明取舍条件"
  ]
}
```

---

## 6. Service 设计

### MethodologyCardService

```java
List<MethodologyCardResponse> list(Long userId, Long researchProjectId);
MethodologyCardResponse get(Long userId, Long cardId);
MethodologyCardResponse create(Long userId, CreateMethodologyCardRequest request);
MethodologyCardResponse update(Long userId, Long cardId, UpdateMethodologyCardRequest request);
void archive(Long userId, Long cardId);
```

### MethodologyMatcher

```java
Optional<MethodologyCard> match(Long userId, Long researchProjectId, String taskType, Map<String, Object> params);
```

匹配顺序：

```text
用户项目内 MethodologyCard
  ↓
用户个人 Space MethodologyCard
  ↓
系统 PRESET MethodologyCard
```

### Prompt 集成

Phase 11 的 PersonalGenerationService：

```text
匹配 MethodologyCard
  ↓
将 workflow / outputStructure / qualityChecklist 注入 Prompt
  ↓
生成 Artifact
```

---

## 7. API 设计

```http
GET  /api/v1/personal/research-projects/{projectId}/methodology-cards
GET  /api/v1/personal/methodology-cards/{cardId}
POST /api/v1/personal/research-projects/{projectId}/methodology-cards
PUT  /api/v1/personal/methodology-cards/{cardId}
DELETE /api/v1/personal/methodology-cards/{cardId}
```

---

## 8. 权限要求

- PRESET 可被所有用户读取。
- USER_CREATED 只能 owner 访问。
- researchProjectId 非空时必须属于当前用户。

---

## 9. 验收清单

- 系统能初始化预置 MethodologyCard。
- 用户能查看预置 MethodologyCard。
- 用户能创建个人 MethodologyCard。
- 生成报告时能匹配 Research Report Methodology。
- 生成对比分析时能匹配 Comparison Methodology。
- Prompt 中包含 outputStructure 和 qualityChecklist。

---

## 10. 给 AI 执行第十三阶段的边界提醒

- 不要实现复杂自动抽取。
- 不要实现模板市场。
- 不要改变 Phase 11 Artifact 机制。
- 所有 API 必须使用 `/api/v1`。
- 个人 MethodologyCard 必须 owner-only。

