# Phase 10: 团队 Wiki Draft、发布与入索引

本文档用于指导 NoteWeave 第十阶段编码实现。

范围：

```text
Phase 10: Wiki Draft / WikiPage / WikiPageVersion / Publish / Wiki Index / WikiRetriever
```

第十阶段目标是让团队高价值答案、FAQ、Briefing、技术总结等 Artifact 可以经过人工确认发布为 WikiPage，并重新进入团队 RAG 检索链路。

---

## 1. 参考文档

```text
docs/features/phase_8_studio_artifact_generation.md
docs/features/phase_9_retrieval_enhancement_rrf.md
docs/features/database_api_blueprint.md
```

---

## 2. 阶段目标

- 支持创建 Wiki 草稿。
- 支持从 Artifact 发布为 Wiki 草稿。
- 支持编辑 Wiki 草稿。
- 支持发布 WikiPage。
- 发布时生成 WikiPageVersion。
- 发布后的 WikiPage 写入 ES。
- 实现 WikiRetriever。
- TeamChatService hybrid retrieval 可召回 WikiPage。
- Artifact 与 WikiPage 保留来源关系。

---

## 3. 本阶段不做的事

- 不做复杂多人审核流。
- 不做富文本协作编辑。
- 不做 Wiki 图谱可视化。
- 不做复杂版本 diff。

---

## 4. 数据模型

### WikiPage

表：`wiki_page`

```text
id
spaceId
title
content
status
sourceArtifactId
publishedVersionId
createdBy
updatedBy
createdAt
updatedAt
```

状态：

```text
DRAFT
PUBLISHED
ARCHIVED
```

### WikiPageVersion

表：`wiki_page_version`

```text
id
wikiPageId
versionNo
title
content
changeNote
createdBy
createdAt
updatedAt
```

### ES Wiki Index

索引名：

```text
noteweave_wiki_page
```

字段：

```text
spaceId
wikiPageId
title
content
status
sourceArtifactId
updatedAt
```

---

## 5. Service 设计

### TeamWikiService

```java
WikiPageResponse createDraft(Long userId, Long spaceId, CreateWikiDraftRequest request);
WikiPageResponse createDraftFromArtifact(Long userId, Long artifactId, PublishArtifactToWikiRequest request);
List<WikiPageResponse> list(Long userId, Long spaceId, WikiPageQuery query);
WikiPageResponse get(Long userId, Long pageId);
WikiPageResponse updateDraft(Long userId, Long pageId, UpdateWikiPageRequest request);
WikiPageResponse publish(Long userId, Long pageId, PublishWikiPageRequest request);
void archive(Long userId, Long pageId);
```

### WikiIndexService

```java
void index(WikiPage page);
void remove(Long wikiPageId);
void executeIndexTask(Long taskId);
void retryFailedIndexTask(Long taskId);
```

要求：

- 发布 Wiki 后只创建 `WIKI_INDEX` Task，不在发布事务内同步写 ES。
- Task payload 必须包含 `wikiPageId`、`publishedVersionId`、`spaceId` 和幂等键。
- ES 文档 ID 使用 `wiki:{wikiPageId}:{publishedVersionId}`，重复消费同一 Task 必须覆盖同一文档而不是新增重复文档。
- 索引失败时标记 `WIKI_INDEX_FAILED`，保留 WikiPage 已发布状态，但在页面上展示“未入索引/可重试”。

### WikiRetriever

```java
List<RetrievalHit> retrieve(RetrievalQuery query);
```

要求：

- 只召回 `status = PUBLISHED`。
- 必须 filter `spaceId`。

---

## 6. 发布流程

```text
用户创建/编辑 Wiki Draft
  ↓
点击发布
  ↓
校验 canEditWiki
  ↓
生成 wiki_page_version
  ↓
wiki_page.status = PUBLISHED
  ↓
wiki_page.published_version_id = version.id
  ↓
创建 WIKI_INDEX Task 异步写入 ES wiki index
  ↓
Worker 写入成功后标记 Task SUCCESS
  ↓
如果来源是 Artifact，artifact.status 可更新 PUBLISHED_TO_WIKI
```

失败处理：

```text
WIKI_INDEX Task 执行失败
  ↓
记录 errorMessage / retryCount
  ↓
未超过 maxRetry 则重新入队
  ↓
超过后标记 FAILED，并保留 WikiPage.indexStatus = FAILED
```

重试要求：

- 只能重试 FAILED / TIMEOUT 的 `WIKI_INDEX` Task。
- 重试前确认 WikiPage 仍为 PUBLISHED，且 publishedVersionId 未变化。
- 如果 publishedVersionId 已变化，应创建新 Task，不复用旧 Task。

---

## 7. API 设计

```http
POST /api/v1/team/spaces/{spaceId}/wiki-pages
GET  /api/v1/team/spaces/{spaceId}/wiki-pages
GET  /api/v1/team/wiki-pages/{pageId}
PUT  /api/v1/team/wiki-pages/{pageId}
POST /api/v1/team/wiki-pages/{pageId}/publish
DELETE /api/v1/team/wiki-pages/{pageId}
GET  /api/v1/team/spaces/{spaceId}/wiki-pages/search
GET  /api/v1/team/wiki-pages/{pageId}/versions
POST /api/v1/team/chat-messages/{messageId}/wiki-drafts
POST /api/v1/artifacts/{artifactId}/publish-to-wiki
```

CreateWikiDraftRequest：

```json
{
  "title": "部署流程 FAQ",
  "content": "...",
  "sourceArtifactId": 3001
}
```

PublishWikiPageRequest：

```json
{
  "changeNote": "首次发布"
}
```

PublishArtifactToWikiRequest：

```json
{
  "spaceId": 10,
  "title": "新人入门指南",
  "artifactVersionId": 3
}
```

---

## 8. 权限要求

| 操作 | OWNER | EDITOR | VIEWER |
|---|---:|---:|---:|
| 查看 Wiki | Y | Y | Y |
| 创建草稿 | Y | Y | N |
| 编辑草稿 | Y | Y | N |
| 发布 Wiki | Y | N | N |
| 归档 Wiki | Y | N | N |

MVP 建议：

```text
发布 Wiki 只允许 OWNER。
```

---

## 9. 错误码补充

```text
WIKI_PAGE_NOT_FOUND
WIKI_ACCESS_DENIED
WIKI_DRAFT_REQUIRED
WIKI_PUBLISH_FAILED
WIKI_INDEX_FAILED
ARTIFACT_CANNOT_PUBLISH_TO_WIKI
```

---

## 10. 测试建议

```text
TeamWikiServiceTest
WikiIndexServiceTest
WikiRetrieverTest
```

重点覆盖：

- EDITOR 可以创建草稿。
- VIEWER 不能创建草稿。
- OWNER 可以发布。
- 发布生成版本。
- 发布后写入 ES。
- WikiRetriever 只召回 PUBLISHED。
- Artifact 发布 Wiki 后保留关联。

---

## 11. 验收清单

- 可以创建 Wiki 草稿。
- 可以编辑 Wiki 草稿。
- 可以发布 Wiki。
- 发布生成 WikiPageVersion。
- 发布后 WikiPage 进入 ES。
- Hybrid retrieval 可以召回 Wiki。
- Artifact 可以发布为 Wiki 草稿。
- Wiki 与 Artifact 关联可追踪。

---

## 12. 给 AI 执行第十阶段的边界提醒

- 不要做复杂审核流。
- 不要做多人协作编辑。
- 不要把所有 Artifact 自动发布为 Wiki。
- 发布必须人工触发。
- WikiRetriever 只召回 PUBLISHED。
- 所有 API 必须使用 `/api/v1`。



