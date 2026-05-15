# Phase 10.5: MethodologyCard 预置模板与匹配器

本文档用于指导 NoteWeave Phase 10.5 编码实现。

范围：

```text
Phase 10.5: MethodologyCard Preset / MethodologyMatcher / Prompt Injection
```

本阶段是 Phase 11 个人生成前的最小前置切片，只提供稳定输出框架，不做完整 MethodologyCard 管理后台。

---

## 1. 参考文档

```text
docs/CONTRACT.md
docs/DOCKER_MIDDLEWARE.md
docs/implementation_breakdown.md
docs/features/database_api_blueprint.md
docs/features/phase_13_methodology_card.md
docs/features/phase_11_personal_generation.md
```

---

## 2. 阶段目标

完成后系统应具备：

- 系统预置一组 MethodologyCard。
- MethodologyMatcher 能根据生成类型、场景、项目上下文选择合适模板。
- Phase 11 个人生成可以读取匹配到的 MethodologyCard。
- Prompt 构建时能注入 workflow、outputStructure、qualityChecklist。
- 预置模板可测试、可扩展、可重复初始化。

---

## 3. 本阶段不做的事

- 不做用户自定义 MethodologyCard。
- 不做复杂版本管理。
- 不做模板市场。
- 不做从 Artifact 自动抽取 MethodologyCard。
- 不做 Artifact -> Methodology proposal。
- 不做 Quiz、测验、答题、评分或题库。

---

## 4. 数据模型

复用 `database_api_blueprint.md` 中的 `methodology_card`。

本阶段只要求支持：

```text
card_source = PRESET
status = ACTIVE
version = 1
```

预置模板必须属于系统可读范围。实现上可以使用系统 Space、空 `research_project_id` 或等价机制，但不能把预置模板误判为某个普通用户私有卡片。

---

## 5. 预置模板建议

MVP 至少包含：

```text
RESEARCH_REPORT
STUDY_GUIDE
COMPARISON
WORK_PREP
```

每个模板至少包含：

```text
name
scene
problemType
workflowJson
outputStructureJson
qualityChecklistJson
```

---

## 6. Service 设计

### MethodologySeedService

```java
void seedPresetCards();
```

要求：

- 可重复执行。
- 使用稳定唯一键避免重复创建。
- 不覆盖用户自定义模板。

### MethodologyMatcher

```java
Optional<MethodologyCard> match(Long userId, Long researchProjectId, String artifactType, Map<String, Object> params);
```

匹配优先级：

```text
artifactType 精确匹配
  ↓
scene / problemType 匹配
  ↓
默认通用模板
```

---

## 7. API 设计

本阶段不要求开放完整管理 API。可以提供只读接口供 Phase 11 或调试使用：

```http
GET /api/v1/personal/research-projects/{projectId}/methodology-cards
```

完整创建、编辑、归档和高级匹配 API 放到 Phase 13。

---

## 8. 验收清单

- 预置 MethodologyCard 可以初始化。
- 初始化重复执行不会创建重复模板。
- MethodologyMatcher 能为 REPORT / STUDY_GUIDE / COMPARISON / WORK_PREP 返回合适模板。
- Phase 11 生成上下文可以读取匹配到的 MethodologyCard。
- 不开放用户自定义和复杂管理能力。
- 所有测试依赖中间件时使用 Testcontainers。

