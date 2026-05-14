# Phase 6: 个人 ResearchProject 与 Source 导入

本文档用于指导 NoteWeave 第六阶段编码实现。

范围：

```text
Phase 6: Personal ResearchProject / Source Upload / URL Source / Source Parse Task
```

第六阶段目标是建立个人研究工作台入口，让用户可以围绕主题创建 ResearchProject，并向项目中添加文件、URL 或文本 Source。

本阶段不生成 ArticleCard / ConceptCard，不做个人问答，不做 Artifact。

---

## 1. 参考文档

```text
docs/features/database_api_blueprint.md
docs/features/file_upload_async_pipeline.md
docs/implementation_breakdown.md
```

---

## 2. 阶段目标

完成后系统应具备：

- 用户可以创建个人 ResearchProject。
- 用户可以查看、更新、删除自己的 ResearchProject。
- 用户可以上传个人 Source 文件。
- 用户可以添加 URL Source。
- 用户可以添加纯文本 Source。
- Source 保留 Raw Source。
- Source 创建后可创建 `SOURCE_IMPORT` Task。
- Source import 状态可查询。
- 所有个人资源只允许 owner 访问。

---

## 3. 本阶段不做的事

- 不做 ArticleCard。
- 不做 ConceptCard。
- 不做 MethodologyCard。
- 不做个人问答。
- 不做个人 Artifact。
- 不做外部论文搜索。
- 不做复杂网页抓取，只做 URL 元数据记录或简单抓取占位。

---

## 4. 包结构

```text
com.noteweave.personal.project
  ├── controller
  ├── dto
  ├── model
  ├── repository
  └── service

com.noteweave.personal.source
  ├── controller
  ├── dto
  ├── model
  ├── repository
  └── service
```

建议类：

```text
ResearchProject
ResearchProjectService
ResearchProjectController
Source
SourceService
SourceController
SourceImportService
```

---

## 5. 数据模型

### ResearchProject

表：`research_project`

字段：

```text
id
spaceId
userId
title
description
researchGoal
compileStatus
status
createdAt
updatedAt
```

状态：

```text
compileStatus = PENDING / COMPILING / READY / FAILED
status = ACTIVE / ARCHIVED
```

### Source

表：`source`

字段：

```text
id
spaceId
researchProjectId
title
sourceType
url
objectKey
rawTextObjectKey
contentHash
importStatus
compileStatus
tokenCount
createdBy
createdAt
updatedAt
```

SourceType：

```text
FILE
URL
TEXT
MARKDOWN
PDF
```

ImportStatus：

```text
PENDING
IMPORTING
READY
FAILED
```

CompileStatus：

```text
PENDING
COMPILING
READY
FAILED
```

---

## 6. Service 设计

### ResearchProjectService

```java
ResearchProjectResponse create(Long userId, CreateResearchProjectRequest request);
List<ResearchProjectResponse> listMine(Long userId);
ResearchProjectResponse get(Long userId, Long projectId);
ResearchProjectResponse update(Long userId, Long projectId, UpdateResearchProjectRequest request);
void archive(Long userId, Long projectId);
```

规则：

- 只能访问自己的 PERSONAL Space 下项目。
- 创建项目时绑定当前用户 PERSONAL Space。

### SourceService

```java
SourceResponse uploadFile(Long userId, Long projectId, MultipartFile file, UploadSourceRequest request);
SourceResponse addUrl(Long userId, Long projectId, AddUrlSourceRequest request);
SourceResponse addText(Long userId, Long projectId, AddTextSourceRequest request);
List<SourceResponse> list(Long userId, Long projectId);
SourceResponse get(Long userId, Long sourceId);
void delete(Long userId, Long sourceId);
SourceResponse triggerImport(Long userId, Long sourceId);
```

文件 Source：

- 写入 MinIO。
- 保存 objectKey。
- 创建 Source。
- 创建 `SOURCE_IMPORT` Task。

URL Source：

- 保存 URL。
- MVP 可不抓取正文，只创建 Source 和 Task。
- 后续由 SourceImportService 抓取。

Text Source：

- 将文本保存为 Raw Source object。
- 创建 Source。
- 可直接标记 importStatus = READY。

### SourceImportService

本阶段可做最小实现：

```text
FILE:
  标记 READY，等待 Phase 7 编译

URL:
  可先只保存 URL，标记 READY

TEXT:
  已有 rawTextObjectKey，标记 READY
```

---

## 7. API 设计

### ResearchProject

```http
POST   /api/v1/personal/research-projects
GET    /api/v1/personal/research-projects
GET    /api/v1/personal/research-projects/{projectId}
PUT    /api/v1/personal/research-projects/{projectId}
DELETE /api/v1/personal/research-projects/{projectId}
```

CreateResearchProjectRequest：

```json
{
  "title": "RAG 技术调研",
  "description": "围绕 RAG 架构整理资料",
  "researchGoal": "形成学习指南和研究报告"
}
```

### Source

```http
POST   /api/v1/personal/research-projects/{projectId}/sources/upload
POST   /api/v1/personal/research-projects/{projectId}/sources/url
POST   /api/v1/personal/research-projects/{projectId}/sources/text
GET    /api/v1/personal/research-projects/{projectId}/sources
GET    /api/v1/personal/sources/{sourceId}
POST   /api/v1/personal/sources/{sourceId}/import
DELETE /api/v1/personal/sources/{sourceId}
```

AddUrlSourceRequest：

```json
{
  "url": "https://example.com/article",
  "title": "文章标题"
}
```

AddTextSourceRequest：

```json
{
  "title": "手动笔记",
  "content": "这里是原始文本..."
}
```

---

## 8. 权限要求

- ResearchProject 只能由 owner 访问。
- Source 只能由 owner 访问。
- 不允许团队 Space 访问个人 Source。
- 所有接口从当前用户解析 PERSONAL Space。

---

## 9. 错误码补充

```text
RESEARCH_PROJECT_NOT_FOUND
RESEARCH_PROJECT_ACCESS_DENIED
SOURCE_NOT_FOUND
SOURCE_ACCESS_DENIED
SOURCE_IMPORT_FAILED
SOURCE_TYPE_UNSUPPORTED
```

---

## 10. 验收清单

- 用户可以创建 ResearchProject。
- 用户只能看到自己的 ResearchProject。
- 用户可以上传文件 Source。
- 用户可以添加 URL Source。
- 用户可以添加 Text Source。
- Source 可以查询。
- Source 可以删除。
- Source 创建后可生成 SOURCE_IMPORT Task。
- 其他用户不能访问该项目或 Source。

---

## 11. 给 AI 执行第六阶段的边界提醒

- 不要生成 ArticleCard。
- 不要生成 ConceptCard。
- 不要实现个人问答。
- 不要实现 Artifact。
- 不要做复杂网页搜索。
- 所有 API 必须使用 `/api/v1`。
- 个人资源必须严格 owner-only。

