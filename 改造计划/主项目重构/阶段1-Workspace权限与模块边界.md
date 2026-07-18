# 阶段 1：Workspace 权限与模块边界

> 目标：让每个用例拥有明确的身份、租户、应用服务和领域端口，消除 `local-user` 与跨模块 Service 直连。  
> 前置：阶段 0 通过。

## 实施状态（2026-07-13）

已完成：

- Bearer Session 身份解析、开发 `local-user` 显式回退与生产禁用门禁；
- `CurrentUserProvider`、`WorkspaceAccessGuard` 和统一 MVC 授权拦截器；
- Workspace 路径及 Knowledge/Message/Task/Conversation/Upload/Chat Request 直接资源 ID 授权；
- Source、Conversation、Upload、Chat、Memory 等关键应用服务的授权入口；
- Memory 审计 actor 改为当前用户，主项目业务写入不再硬编码 `local-user`；
- 401、403、Owner 正常访问、跨 Workspace 直接 ID 和 403 Counter 合同测试。
- `WorkspacePermission` 与 OWNER/EDITOR/VIEWER 显式权限矩阵；读、资料写、回答、知识写、Memory 审核、执行操作和管理权限不再混为“成员”；
- V027 增加 `workspace_member.status/updated_at`、`workspace.acl_version` 与授权查询索引；停用/移除成员立即拒绝；
- Redis ACL 缓存采用 `acl:{workspaceId}:{userId}:{aclVersion}`，10 分钟 TTL 加抖动，Redis 异常回源 MySQL，连接与命令超时有界；
- ACL hit/miss/load/error Micrometer 指标；
- Owner 专用成员管理 API：列举、添加/修改角色与状态、移除成员；每次变更事务内递增 `acl_version`，禁止通过普通成员接口修改 Owner；
- OWNER/EDITOR/VIEWER、停用成员、版本递增与 Owner 不变量合同测试；Docker 中验证 Viewer 读 200/写 403、升级 Editor 后写 200、移除后读 403；
- 完整后端回归 108/108 通过，V027 在 H2 和 Docker MySQL 均迁移成功，阶段 0A Docker Smoke 在新权限链路上再次通过。
- V028 为 Workspace、Member、Task、Upload、Source、Conversation、Knowledge Item 增加 `created_by/updated_by`，HTTP fallback 也显式写入请求身份；
- `AuditActorProvider` 区分真实请求用户和 `SYSTEM:<component>` 后台 actor，避免后台线程因开发 fallback 被错误记为 `local-user`；
- Workspace Wiki 设置使用 `UpdateWorkspaceWikiSettingsUseCase + WorkspaceWikiCommandPort`，Source 删除使用 `SourceWikiCommandPort`，Controller/SourceService 不再直接注入 `WikiIngestService`；
- 引入 ArchUnit 1.4.2，并建立 4 条可执行门禁：Controller 禁止直连 JDBC/中间件、Workspace 禁止依赖 Knowledge/Source/Infra、SourceController 保持模块边界、Security 禁止反向依赖业务模块；
- V029 补齐 Conversation 原模型缺少的 `updated_at`，消息序号分配和实时会话活跃更新均写入真实 actor；
- 对象存储接口移出 `infra` 成为中立 `storage.ObjectStorage` 端口；Upload、SourceParse、GeneratedSource、WikiIngest 改为依赖 `SourceMessagingMode`、`SourceParsePort`、`SourceWikiCommandPort`、`TaskCommandPort`；
- Knowledge/Wiki 只依赖 `WorkspaceQueryPort`，不再注入 `WorkspaceService` 实现；Source/Upload 包已不存在对 `com.noteweave.infra` 的编译依赖；
- Task 全状态机、Knowledge 创建/版本/重命名/删除、Conversation、上传、Source 解析和 ES 投影的 `updated_by` 全部补齐；
- 检索读取抽成中立 `ChunkSearchPort`/`ChunkSearchHit`，`RetrievalService` 不再依赖 `ElasticsearchIndexer` 或 Elasticsearch 配置；搜索引擎不可用时仍按既定策略降级到 MySQL；
- Chat 的会话更新、SSE 内容落库、引用读取/绑定、上下文窗口查询，以及 Upload 的分片、完成态、Source 结果、文件引用计数均增加显式 Workspace 条件；
- ArchUnit 门禁由 4 条增强为 8 条，新增 Chat 禁止依赖 `infra`；最新完整后端回归 `118/118`，Docker Smoke 在 V029 再次全通过。

仍未完成：

- `ChunkSearchPort` 已解除 Chat 对 Elasticsearch 的实现耦合，但 QA、Note、Wiki 三种检索链路仍需统一为策略接口，并形成统一的 Answer Task/Answer Package；
- Chat/Upload 已完成关键写入与读取的 Workspace 纵深条件；Knowledge 及少量“先按直接资源 ID 解析 Workspace”的入口查询仍需继续收敛；
- 当前 ArchUnit 已守住关键新边界，但尚未启用全业务包循环依赖门禁；需先拆解现有遗留循环，不能简单豁免。

## 1. 目标模块

```text
identity       CurrentPrincipal, authentication adapter
workspace      Workspace, Membership, WorkspacePolicy
source         Source aggregate and source application ports
answer         Conversation boundary (P4 adds AnswerRun)
knowledge      Knowledge boundary
memory         Memory boundary
execution      Long-running work boundary (P2 implements)
integration    Worker-facing contracts only
infrastructure JDBC/Kafka/Redis/ES/MinIO adapters
```

采用按业务能力组织包，不按 Controller/Service/Repository 横向分层。每个模块内部允许 `api/application/domain/infrastructure` 四层，但小模块不必为了形式创建空层。

## 2. 身份与授权模型

### 2.1 核心接口

```java
public interface CurrentPrincipal {
    UserId requireUserId();
    Set<String> roles();
}

public interface WorkspaceAuthorizer {
    WorkspaceAccess require(WorkspaceId workspaceId, WorkspacePermission permission);
}
```

权限建议：`WORKSPACE_READ`、`SOURCE_WRITE`、`ANSWER_RUN`、`KNOWLEDGE_WRITE`、`MEMORY_REVIEW`、`EXECUTION_OPERATE`、`WORKSPACE_ADMIN`。角色只是权限集合：OWNER、EDITOR、VIEWER，可后续扩展，不把角色判断散落在 Controller。

### 2.2 请求规则

- Controller 从认证过滤器取得 Principal，不接受客户端传 `userId` 作为身份；
- Application Service 首行执行 Workspace 权限校验；
- Repository 方法强制携带 WorkspaceId，例如 `findById(workspaceId, sourceId)`；
- 跨 Workspace 管理接口放入独立 admin surface，不复用用户 API；
- 404 与 403 策略统一，日志保留真实拒绝原因但不泄漏资源存在性。

开发环境可以通过显式 `dev.local-principal-enabled=true` 映射 local-user；生产 profile 禁止启用。删除业务代码中的 `LOCAL_USER_ID` 常量，created_by/updated_by 来自 Principal 或 `SystemActor`。

## 3. 应用服务边界

### 3.1 Workspace 用例

将 `WorkspaceController` 中对 Wiki 的直接调用移到用例编排：

```text
UpdateWorkspaceWikiSettingUseCase
  -> authorize WORKSPACE_ADMIN
  -> WorkspaceRepository.updateWikiEnabled
  -> DomainCommandPort.enqueue(WikiBackfillRequested / WikiRetractRequested)
```

Workspace 模块不依赖 `WikiIngestService` 实现，只发布一个契约稳定的 command/event。

### 3.2 Source 用例

拆分当前 Upload/Source 巨型编排为：

- `InitiateUploadUseCase`
- `UploadPartUseCase`
- `CompleteUploadUseCase`
- `DeleteSourceUseCase`
- `RegisterGeneratedSourceUseCase`

它们依赖 `ObjectStoragePort`、`SourceRepository`、`CommandOutboxPort`，不依赖 KafkaTemplate、具体 MinIO Client 或 Wiki Service。

### 3.3 Answer/Knowledge/Memory 用例

- Conversation CRUD 与回答执行分开；
- Knowledge command 与 query 分开，Controller 不直接拼图或统计；
- Memory signal/candidate/review/compiler 之间通过接口协作；
- Memory 不直接依赖 Artifact 模块。由 `CapabilityCatalogPort` 提供通用只读能力，Artifact adapter 实现它。

## 4. 模块依赖规则

建议用 Spring Modulith 或 ArchUnit 做静态门禁：

- `domain` 不依赖 Spring Web、Kafka、Redis、ES、MinIO；
- Controller 只能依赖同模块 Application API；
- 模块 A 不直接注入模块 B 的 Repository/内部 Service；
- `memory` 不依赖 `artifact`，`workspace` 不依赖 `knowledge` 实现；
- `infrastructure` 可以实现领域端口，但业务模块不能依赖具体 adapter；
- Worker DTO 放在 `integration-contract`，不把 Worker 内部模型带进主域。

不建议一次性移动所有类。先给新用例建立正确边界，再用 facade 包住旧 Service，逐个迁移方法，并登记旧 facade 删除条件。

## 5. 数据与 API

### 5.1 数据迁移

核对并补齐：

- `workspace_member(workspace_id, user_id, role, status, created_at, updated_at)` 唯一约束；
- 用户状态和成员状态均为 ACTIVE 才授权；
- 对 `created_by/updated_by` 缺失的重要表用新迁移补列；
- 权限缓存版本可在 Workspace 增加 `acl_version`，成员变更时原子递增。

### 5.2 API 契约

- 用户 API 不出现 `ownerId/userId` 任意指定字段；
- 所有资源路径明确 Workspace，或服务端从资源安全解析 Workspace；
- 错误结构统一：`code/message/request_id/details`；
- 写接口支持 `Idempotency-Key` 的场景仅限客户端可重试的创建操作，保存结果与 request hash，不能用它代替领域唯一约束。

## 6. Redis 权限缓存

本阶段已启用授权判定缓存：

```text
key: acl:{workspaceId}:{userId}:{aclVersion}
value: permission bitset
ttl: 5-15 min + jitter
```

成员变更在同一 MySQL 事务内递增 `acl_version`，旧 key 无需扫描删除即自然失效。授权时先读取 Workspace 状态与 ACL 版本，再查版本化缓存；Redis 不可用或超时则回源 MySQL，禁止“缓存查不到即允许”。当前 TTL 为 10 分钟加 0~5 分钟抖动，连接超时 500ms、命令超时 750ms，并记录 hit/miss/load/error。

## 7. 施工顺序

1. 引入 `CurrentPrincipal` 与生产/开发 adapter；
2. 建 `WorkspaceAuthorizer` 和权限枚举；
3. 为 Source、Conversation、Knowledge、Memory、Task API 加授权契约测试；
4. Repository 查询补 Workspace 条件；
5. 替换所有 `local-user` 写入；
6. 从 Workspace/Source Controller 移除跨域编排；
7. 建应用端口并用 facade 迁移旧 Service；
8. 加 ArchUnit/Modulith 依赖测试；
9. 清理不再使用的跨模块 public 方法。

## 8. 测试与验收

- OWNER/EDITOR/VIEWER 权限矩阵全覆盖；
- 枚举所有 Controller，验证匿名、非成员、停用成员和跨 Workspace id；
- 同一资源 id 在错误 Workspace 下不可被查询或修改；
- 成员移除后权限缓存即时通过 version 失效；Redis 故障时仍正确拒绝/允许；
- ArchUnit 阻止新增 Controller 跨模块注入和 domain 依赖基础设施；
- 审计记录包含真实 actor，系统任务使用明确的 `SYSTEM:<component>`。

完成标志：主项目业务代码不再硬编码 local-user；所有用户入口都有 Workspace 权限；关键模块依赖规则进入 CI。

## 9. 回滚

鉴权不应整体关闭。灰度期间可对老用户自动补 owner membership，但必须记录并设置截止版本。应用层 facade 可切回旧实现，新的 Workspace 条件和审计字段继续保留。
