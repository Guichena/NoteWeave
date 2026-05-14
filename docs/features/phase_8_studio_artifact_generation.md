# Phase 8: Studio 与 Artifact 生成系统

本文档用于指导 NoteWeave 第八阶段编码实现。

范围：

```text
Phase 8: Studio Button / Task Plan / Skill Execution / Artifact / Export
```

第八阶段目标是把报告、学习指南、Briefing、FAQ、Comparison、Wiki Draft 等产物从聊天消息中独立出来，作为可管理、可查看、可导出、可再次生成且可追踪版本的 `Artifact`。

本阶段不做 Team Wiki 发布，不做复杂 Agent 自主规划，不做 Quiz、测验、答题、评分或题库。

---

## 1. 参考文档

```text
docs/features/phase_4_team_rag_chat_citation.md
docs/features/phase_7_personal_wiki_compiler_cards.md
docs/features/database_api_blueprint.md
docs/implementation_breakdown.md
```

---

## 2. 阶段目标

完成后系统应具备：

- 用户可以从 Studio 创建生成任务。
- 支持固定 `taskType` 到固定 Plan。
- Task 执行多个 Skill。
- 生成结果保存为 Artifact。
- Artifact 可列表、详情、更新标题/内容。
- Artifact 可关联来源 Source / Document / Card。
- Artifact 可关联 Citation。
- Artifact 可关联创建它的 ChatSession / ChatMessage。
- Artifact 可导出 Markdown。
- Artifact 可重新生成。
- Artifact 默认不进入 Wiki。

---

## 3. 本阶段不做的事

- 不做复杂自主 Agent 规划。
- 不做 Team Wiki 发布。
- 不做 Wiki 审核流。
- 不做 Quiz、测验、答题记录、评分和题库。
- 不做 PPT / DOCX / PDF 高级导出。
- 不做多人协作编辑 Artifact。

---

## 4. 支持的 Artifact 类型

MVP 支持：

```text
REPORT
STUDY_GUIDE
BRIEFING
FAQ
COMPARISON
WIKI_DRAFT
```

后续：

```text
INTERVIEW_PREP
ONBOARDING_GUIDE
TECHNICAL_SUMMARY
```

说明：

- `WIKI_DRAFT` 可以作为 Artifact 类型保留，但发布到 Wiki 放到 Phase 10。

---

## 5. 数据模型

### Artifact

表：`artifact`

字段：

```text
id
userId
spaceId
researchProjectId
createdFromSessionId
createdFromMessageId
taskId
artifactType
title
content
sourceScopeType
status
createdAt
updatedAt
```

状态：

```text
GENERATING
READY
FAILED
ARCHIVED
PUBLISHED_TO_WIKI
```

### ArtifactVersion

表：`artifact_version`

```text
artifactId
versionNo
taskId
title
content
changeNote
createdBy
createdAt
```

说明：

- 编辑、重新生成和发布 Wiki 都必须绑定具体 ArtifactVersion。
- 由任务生成的版本必须写入 `taskId`，手工编辑版本也要保留 `createdBy` 和 `changeNote`。
- 同一 Artifact 在多会话中被引用或继续生成时，只能新增 ArtifactVersion，不覆盖历史版本。

### ArtifactSource

表：`artifact_source`

```text
artifactId
sourceType
sourceId
```

sourceType：

```text
DOCUMENT
DOCUMENT_CHUNK
SOURCE
ARTICLE_CARD
CONCEPT_CARD
CHAT_MESSAGE
ARTIFACT
```

### ArtifactCitation

表：`artifact_citation`

```text
artifactId
citationId
```

### SessionArtifact

表：`session_artifact`

```text
sessionId
artifactId
relationType
```

relationType：

```text
CREATED_FROM
REFERENCED
UPDATED_IN
GENERATED_NEXT
```

### Task

复用通用 `task`。

本阶段任务类型：

```text
REPORT_GENERATION
STUDY_GUIDE_GENERATION
BRIEFING_GENERATION
FAQ_GENERATION
COMPARISON_GENERATION
WIKI_DRAFT_GENERATION
```

### SkillExecutionLog

记录每个 Skill 的输入、输出、模型、耗时和错误。

要求：

- 必须绑定 `taskId`。
- 如果某个 Skill 产出或修改 ArtifactVersion，记录 `artifactId` 和 `artifactVersionId`。
- 失败 Skill 的 errorMessage 不覆盖 ArtifactVersion 历史内容。

---

## 6. Plan 与 Skill 设计

本阶段采用固定 Plan，不做自主 Agent。

### Report Plan

```text
LoadGenerationContextSkill
SelectEvidenceSkill
GenerateReportSkill
CitationBacktraceSkill
SaveArtifactSkill
```

### Study Guide Plan

```text
LoadGenerationContextSkill
SelectArticleCardSkill
SelectConceptCardSkill
GenerateStudyGuideSkill
SaveArtifactSkill
```

### FAQ Plan

```text
LoadGenerationContextSkill
SelectEvidenceSkill
GenerateFaqSkill
SaveArtifactSkill
```

Skill 接口：

```java
SkillResult execute(SkillContext context);
String getName();
```

SkillContext：

```text
taskId
userId
spaceId
researchProjectId
artifactType
sourceScopeType
sourceIds
params
intermediate
```

---

## 7. Service 设计

### StudioTaskService

```java
CreateStudioTaskResponse createTask(Long userId, CreateStudioTaskRequest request);
TaskResponse getTask(Long userId, Long taskId);
void cancel(Long userId, Long taskId);
void retry(Long userId, Long taskId);
```

### ArtifactPlanExecutor

```java
void execute(Long taskId);
```

职责：

- 根据 taskType 选择固定 Plan。
- 顺序执行 Skill。
- 写 SkillExecutionLog。
- 失败时标记 Task / Artifact。

### ArtifactService

```java
List<ArtifactResponse> listBySpace(Long userId, Long spaceId, ArtifactQuery query);
List<ArtifactResponse> listBySession(Long userId, Long sessionId);
ArtifactResponse get(Long userId, Long artifactId);
ArtifactResponse update(Long userId, Long artifactId, UpdateArtifactRequest request);
void archive(Long userId, Long artifactId);
ExportArtifactResponse export(Long userId, Long artifactId, String format);
CreateStudioTaskResponse regenerate(Long userId, Long artifactId, RegenerateArtifactRequest request);
```

---

## 8. API 设计

### Studio Task

```http
POST /api/v1/studio/tasks
GET  /api/v1/studio/tasks/{taskId}
POST /api/v1/studio/tasks/{taskId}/cancel
POST /api/v1/studio/tasks/{taskId}/retry
```

CreateStudioTaskRequest：

```json
{
  "spaceId": 1,
  "researchProjectId": 100,
  "taskType": "REPORT_GENERATION",
  "sourceScopeType": "RESEARCH_PROJECT",
  "sourceIds": [100],
  "createdFromSessionId": null,
  "createdFromMessageId": null,
  "params": {
    "topic": "RAG 技术调研报告",
    "length": "MEDIUM",
    "includeCitations": true
  }
}
```

### Artifact

```http
GET  /api/v1/spaces/{spaceId}/artifacts
GET  /api/v1/chat/sessions/{sessionId}/artifacts
GET  /api/v1/artifacts/{artifactId}
PUT  /api/v1/artifacts/{artifactId}
DELETE /api/v1/artifacts/{artifactId}
POST /api/v1/artifacts/{artifactId}/regenerate
POST /api/v1/artifacts/{artifactId}/generate
GET  /api/v1/artifacts/{artifactId}/export?format=markdown
```

---

## 9. 权限要求

团队 Space：

- 生成 Artifact 需要 `canAskQuestion`。
- 更新 / 删除 Artifact 需要创建者或 OWNER。
- 查看 Artifact 需要 `canViewSpace`。

个人 Space：

- Artifact 默认 owner-only。

SourceScope 权限：

- `DOCUMENT` / `DOCUMENT_CHUNK` 必须属于当前可访问 Space。
- `SOURCE` / `ARTICLE_CARD` / `CONCEPT_CARD` 必须属于当前用户个人项目。

---

## 10. 错误码补充

```text
ARTIFACT_NOT_FOUND
ARTIFACT_ACCESS_DENIED
ARTIFACT_TYPE_UNSUPPORTED
ARTIFACT_EXPORT_UNSUPPORTED
STUDIO_TASK_TYPE_UNSUPPORTED
SKILL_EXECUTION_FAILED
PLAN_EXECUTION_FAILED
```

---

## 11. 测试建议

```text
StudioTaskServiceTest
ArtifactPlanExecutorTest
ArtifactServiceTest
SkillExecutionLogTest
```

重点覆盖：

- 创建 Report 任务。
- Task 选择正确 Plan。
- Skill 执行顺序正确。
- 生成 Artifact 后状态为 READY，并生成 ArtifactVersion。
- Artifact 与来源关联正确。
- Artifact 与 Session 关联正确。
- 用户不能访问无权限 Artifact。
- Markdown 导出成功。

---

## 12. 验收清单

- 可以创建 Studio Task。
- 可以查询 Task 状态。
- Task 可以生成 Artifact。
- Artifact 可以列表和详情查看。
- Artifact 可以更新标题和内容，并产生可追踪版本。
- Artifact 可以 Markdown 导出。
- Artifact 可以重新生成。
- Artifact 默认不进入 Wiki。
- SkillExecutionLog 有记录。

---

## 13. 给 AI 执行第八阶段的边界提醒

- 不要实现复杂 Agent 自主规划。
- 不要发布 Wiki。
- 不要实现 Quiz、测验、答题、评分、题库。
- 不要实现高级导出格式。
- 不要把 Artifact 存在 ChatMessage JSON 字段里。
- 不要使用 `generated_artifact`，必须使用 `artifact`。
- 所有 API 必须使用 `/api/v1`。




