# NoteWeave

NoteWeave 是一个面向团队与个人的 AI 知识工作台后端项目。  
核心目标是把“团队知识沉淀 + 个人研究生产力”统一到同一套空间模型和权限体系中，并逐步演进到可扩展的 RAG、Studio 产物生成与记忆系统。

---

## 项目定位

基于《`docs/note_weave_功能说明与架构文档.md`》的设计，NoteWeave 采用以下产品方向：

- 团队侧：文档沉淀、可追溯问答、Wiki 沉淀。
- 个人侧：Research 工作台、资料编译、结构化知识卡片、生成产物。
- 通用底座：统一 Space 权限、异步任务、检索与会话运行态。

---

## 核心设计原则

- 团队与个人分治：`Space.type = PERSONAL | TEAM`。
- Raw Source 作为事实源：生成结果可回溯原始证据。
- Wiki 与 Artifact 边界清晰：长期知识与临时产物分离。
- 先打底座再扩能力：先身份、归属、权限，再文件、检索、RAG、生成。

---

## 当前实现状态（Phase 0/1）

当前仓库已完成：

- Spring Boot 3.x + Java 17 + Maven 工程初始化。
- 统一响应与错误体系：`ApiResponse` / `ErrorCode` / `BusinessException` / `GlobalExceptionHandler`。
- 核心实体与表映射：
  - `users`
  - `space`
  - `space_member`
- Spring Security + JWT 认证。
- `CurrentUserProvider`（业务层不直接解析 JWT）。
- 用户认证接口：
  - `POST /api/v1/auth/register`
  - `POST /api/v1/auth/login`
  - `GET /api/v1/users/me`
- 空间与成员接口：
  - `POST /api/v1/spaces`
  - `GET /api/v1/spaces`
  - `GET /api/v1/spaces/{spaceId}`
  - `POST /api/v1/spaces/{spaceId}/members`
  - `GET /api/v1/spaces/{spaceId}/members`
  - `PUT /api/v1/spaces/{spaceId}/members/{memberId}/role`
  - `DELETE /api/v1/spaces/{spaceId}/members/{memberId}`
- 注册事务保障：
  - 创建用户
  - 自动创建 PERSONAL Space
  - 自动创建 OWNER SpaceMember
- `SpacePermissionService` 权限矩阵：
  - OWNER / EDITOR / VIEWER
  - 非成员不可访问 TEAM Space
  - PERSONAL Space 仅 owner 可访问
  - `canUploadDocument` 对 VIEWER 返回 `false`（为后续阶段预留）

---

## 暂未实现（按规划延后）

以下能力会在后续阶段接入，当前不在 Phase 0/1 范围内：

- 文件上传与分片续传
- Kafka / MinIO
- Elasticsearch
- 团队 RAG 问答
- WebSocket 会话执行底座
- 个人 ResearchProject
- Artifact / Studio

---

## 技术栈

- Java 17
- Spring Boot 3.x
- Spring Web
- Spring Security
- Spring Data JPA
- Spring Data Redis
- MySQL
- JWT（jjwt）
- Lombok
- Maven
- Test: JUnit 5, Spring Test, H2

---

## 快速启动

### 1) 环境准备

- JDK 17+
- Maven 3.9+
- MySQL 8+
- Redis 6+

### 2) 配置项（可用环境变量覆盖）

默认配置在 `src/main/resources/application.yml`：

- `DB_URL`
- `DB_USERNAME`
- `DB_PASSWORD`
- `REDIS_HOST`
- `REDIS_PORT`
- `REDIS_PASSWORD`
- `JWT_SECRET_KEY`
- `JWT_EXPIRATION_SECONDS`
- `SERVER_PORT`

### 3) 启动

```bash
mvn spring-boot:run
```

健康检查：

```text
GET /actuator/health
```

---

## 测试

```bash
mvn test
```

当前已包含：

- `AuthServiceTest`
- `SpacePermissionServiceTest`
- `AuthControllerTest`
- `SpaceControllerTest`

---

## 文档索引

- 总体实现拆解：[`docs/implementation_breakdown.md`](docs/implementation_breakdown.md)
- 全量架构说明：[`docs/note_weave_功能说明与架构文档.md`](docs/note_weave_功能说明与架构文档.md)
- 功能分阶段文档入口：[`docs/features/README.md`](docs/features/README.md)
- 第一阶段详细说明：[`docs/features/phase_0_1_bootstrap_auth_space.md`](docs/features/phase_0_1_bootstrap_auth_space.md)
- 数据库与 API 蓝图：[`docs/features/database_api_blueprint.md`](docs/features/database_api_blueprint.md)

---

## 路线图（简版）

- Phase 2-3：KnowledgeBase、上传链路、异步解析与索引
- Phase 4-5：团队 RAG、WebSocket 会话运行态与记忆
- Phase 6-8：个人研究工作台、Wiki Compiler、Studio Artifact
- Phase 9+：混合检索增强、Wiki 发布、可观测性与运维能力

具体顺序以 `docs/implementation_breakdown.md` 为准。
