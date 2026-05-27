# 02 空间权限与资源边界

## 0. 本篇定位

这条链路回答：NoteWeave 如何保证团队知识、个人研究、Artifact、Citation、Memory 不互相越权。

核心口径：

```text
Space 是最高业务容器。
TEAM 通过 SpaceMember.role 管理协作权限。
PERSONAL 默认 owner-only。
Admin system_role 和团队 OWNER 不能混用。
关键资源读取必须回到统一资源访问校验或模块内权限校验。
```

## 面试先说版

这条链路我会从“AI 知识系统怎么避免越权”讲。RAG 系统的权限风险不只发生在接口入口，还会发生在检索、Citation、Artifact、Memory、预览下载这些后续链路里。所以 NoteWeave 把 Space 作为最高业务边界，团队空间和个人空间先隔离，再在团队空间内用成员角色控制协作权限。

这里的关键取舍是：权限模型会比简单 userId 判断复杂，但所有资源都能回到同一条边界上。检索时先用 ES filter 做前置过滤，召回后再用 MySQL 做状态和权限复核；Citation 查询、Artifact 生成、Memory 写回也不能绕过空间边界。

## Q1：为什么要把 `TEAM` 和 `PERSONAL` 做成一级空间？

**答：**

因为团队知识协作和个人研究沉淀的权限、生命周期和知识沉淀方式不一样。

团队侧的重点是共享知识库、团队 Chat、团队 Wiki、团队 Artifact 和成员协作。这里需要 `SpaceMember.role` 区分 `OWNER / EDITOR / VIEWER`，比如 Viewer 可以问答但不能上传文档或发布 Wiki。

个人侧的重点是 ResearchProject、Source、ArticleCard、ConceptCard、SynthesisCard、个人 Artifact 和 UserMemory。这些内容默认只有 owner 能访问，因为个人研究里可能包含草稿、偏好、未公开资料和临时结论。

所以 `Space` 不是简单文件夹，而是后续知识、任务、引用、会话、记忆和成果的最高隔离边界。

## Q2：`users.system_role` 和 `SpaceMember.role` 为什么要分开？

**答：**

这两个角色解决的问题不同。

`users.system_role` 是系统后台权限，比如 ADMIN 能访问 `/api/v1/admin/**`，查看系统健康、任务、用户、审计日志、RAG Eval 和清理任务。

`SpaceMember.role` 是某个团队空间内的业务角色，比如 OWNER 可以管理成员和发布内容，EDITOR 可以上传和编辑，VIEWER 只能查看和问答。

如果把两者混在一起，会出现权限语义混乱。比如一个团队 OWNER 不应该天然拥有全系统 Admin 权限，一个系统 Admin 也不应该被当成某个团队空间的知识 owner。

## Q3：资源访问为什么不能只在接口入口判断一次？

**答：**

因为 NoteWeave 的资源是链式关联的。一次问答可能从 ChatSession 关联到 Space，再到 KnowledgeBase、DocumentChunk、Citation、Snapshot；一次 Artifact 可能关联 Source、Card、Citation、MethodologyCard；一次 Admin 查询也可能跨任务、用户、空间。

如果只在接口入口按 id 判断一次，很容易出现后续链路绕过权限的问题。更稳的方式是关键资源读取时都回到统一资源访问校验或模块内权限服务做二次确认。

典型例子是 Citation 查询。即使用户能看到某条 message，也不能直接把 citationId 返回出去，而要根据 Citation 的 `spaceId / sourceType / sourceId` 回查资源所属空间，确认当前用户仍然有权限。

## 常见追问

**追问：检索时权限怎么保证？**

ES 查询带 `spaceId / knowledgeBaseId / status` 等 filter；检索后再通过 MySQL 校验文档状态、删除状态和 activeIndexVersion。也就是前置过滤加后置校验。

**追问：Admin 能不能看所有用户个人研究？**

不要夸大。Admin 主要用于系统管理、健康、任务、日志、评测和清理。涉及敏感日志和 Prompt 时，要强调脱敏和访问边界。

## 实现兜底锚点

- `Space`
- `SpaceMember`
- `SpacePermissionService`
- `ResourceAccessService`
- `SpaceController`
- `SpaceControllerTest`
- `SpacePermissionServiceTest`

## 3 到 5 分钟深答模板

> 我把 `TEAM` 和 `PERSONAL` 做成一级空间，不是为了界面上多一个分类，而是因为团队知识协作和个人研究沉淀在权限模型、生命周期和长期知识边界上天然不同。团队空间强调成员协作、共享知识库、Citation 和 Wiki；个人空间强调 owner-only、Source、Card、Artifact、Synthesis 和个人 Memory。如果不在最外层把空间边界拆开，后面 Citation、Memory、Artifact、Wiki 都很容易串权限。权限上我又分成两层：`system_role` 负责系统后台能力，`SpaceMember.role` 负责团队业务角色。关键资源读取不会只在接口入口判断一次，而是回到统一资源访问校验或模块权限服务做二次确认，检索侧还有 ES 前置过滤和 MySQL 后置校验，所以回答、Citation、预览、下载这些链路都能守住同一套空间边界。

## 边界和不能说满的地方

- 可以坚定讲：Space 是最高业务隔离边界，团队和个人资源不会混讲。
- 不要讲成：Admin 天然能随便看所有个人研究；接口入口鉴权一次就够；检索 filter 做了就不需要二次校验。
