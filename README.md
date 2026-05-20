# NoteWeave

NoteWeave 是一个把团队知识、个人研究和成果沉淀放进同一工作台的 AI 知识工作台项目。

它的目标很明确：

- 团队侧可以做知识库、检索问答、团队 Wiki 和成果协作
- 个人侧可以做研究项目、资料导入、卡片沉淀和成稿生成
- 两侧共用同一套空间模型、权限体系、异步任务和检索底座

---

## 当前状态

当前仓库已经具备可运行、可验收的本地开发环境，并包含一套中文演示数据与测试账号。

当前本地访问地址：

- 登录页：[http://127.0.0.1:18082/login](http://127.0.0.1:18082/login)
- Swagger：[http://127.0.0.1:18082/swagger-ui.html](http://127.0.0.1:18082/swagger-ui.html)
- 健康检查：[http://127.0.0.1:18082/actuator/health](http://127.0.0.1:18082/actuator/health)

测试账号：

- `admin / NoteWeave123!`
- `alice / NoteWeave123!`
- `bob / NoteWeave123!`

说明：

- `admin` 是管理员
- `alice` 拥有个人空间，同时是团队空间编辑者
- `bob` 是团队空间只读成员

---

## 产品结构

### 团队空间

适合团队共享与协作，主要包含：

- 团队知识库
- 文档上传与索引
- 团队聊天与检索问答
- 团队 Wiki
- 团队成果库
- 管理后台任务、健康、评测、日志

### 个人空间

适合个人研究与沉淀，主要包含：

- 研究项目
- Source 导入
- Article / Concept / Synthesis 卡片
- Artifact 生成与编辑
- Memory 和记忆沉淀

### 通用底座

- `Space.type = PERSONAL | TEAM`
- 基于角色的访问控制
- Kafka + Task Outbox 异步任务链路
- MinIO 对象存储
- Elasticsearch 检索与索引

---

## 当前已实现能力

当前代码已覆盖以下核心能力：

- Spring Boot 3.x + Java 17 + Maven 工程基础
- Spring Security + JWT 登录认证
- 用户、空间、成员管理
- 团队空间与个人空间隔离
- OWNER / EDITOR / VIEWER 权限矩阵
- 统一错误处理与 API 响应结构
- Task / Worker / Outbox 异步任务基础设施
- 团队知识库创建、查询、归档
- 分片上传、断点续传、文档元数据管理
- 文档解析、切片、索引与搜索调试
- 个人研究项目、资料、卡片、成果相关链路
- 开发环境中文种子数据与验收样例

当前仓库中的实现细分与阶段说明，请以这些文档为准：

- [docs/PROJECT_STATUS.md](/D:/java-projects/NoteWeave/docs/PROJECT_STATUS.md)
- [docs/CONTRACT.md](/D:/java-projects/NoteWeave/docs/CONTRACT.md)
- [docs/implementation_breakdown.md](/D:/java-projects/NoteWeave/docs/implementation_breakdown.md)
- [docs/features/database_api_blueprint.md](/D:/java-projects/NoteWeave/docs/features/database_api_blueprint.md)

---

## 本地启动

### 1. 环境准备

需要本地具备：

- JDK 17+
- Maven 3.9+
- Docker Desktop 或可用的 Docker Compose 环境

### 2. 启动依赖服务

在项目根目录执行：

```powershell
docker compose up -d
```

当前统一使用的宿主机端口：

- MySQL：`13307`
- Redis：`6380`
- MinIO API：`19100`
- MinIO Console：`19101`
- Elasticsearch：`19200`
- Kafka：`19092`

### 3. 启动应用

方式一：Maven

```powershell
$env:SPRING_PROFILES_ACTIVE="dev"
$env:SERVER_PORT="18082"
mvn spring-boot:run
```

方式二：Jar

```powershell
mvn -q -DskipTests package
java -Dspring.profiles.active=dev -Dserver.port=18082 -jar target/noteweave-0.0.1-SNAPSHOT.jar
```

默认配置已经对齐当前端口；如需覆盖，可使用环境变量：

- `DB_URL`
- `DB_USERNAME`
- `DB_PASSWORD`
- `REDIS_HOST`
- `REDIS_PORT`
- `MINIO_ENDPOINT`
- `MINIO_BUCKET`
- `MINIO_TEST_BUCKET`
- `ES_URIS`
- `KAFKA_BOOTSTRAP_SERVERS`
- `SERVER_PORT`

更详细的启动说明见：

- [NOTEWEAVE_启动指南.md](/D:/java-projects/NoteWeave/NOTEWEAVE_启动指南.md)

---

## 验收与演示数据

当前开发环境已内置一批中文演示数据，便于直接查看页面效果：

- 团队空间：`产品策略协作台`
- 团队知识库：`AI 产品研究资料库`、`发布准备台`
- 个人研究项目：`三季度新手引导阻力研究`、`竞品表述跟踪`
- 成果：`三季度新手引导建议简报`
- 团队 Wiki：`研究协作原则`

验收账号、角色说明、团队/个人空间差异、推荐验收路径见：

- [NOTEWEAVE_验收说明.md](/D:/java-projects/NoteWeave/NOTEWEAVE_验收说明.md)

---

## 测试

运行单元测试与集成测试：

```powershell
mvn test
```

当前仓库已包含的代表性测试包括：

- `AuthServiceTest`
- `SpacePermissionServiceTest`
- `AuthControllerTest`
- `SpaceControllerTest`
- `TaskControllerTest`
- `TaskServiceIntegrationTest`
- `Phase2UploadFlowIntegrationTest`
- `DocumentParserServiceTest`
- `ChunkServiceTest`
- `Phase3DocumentProcessingIntegrationTest`
- `StoragePropertiesValidatorTest`

浏览器 UI 冒烟回归可使用：

```powershell
cd tools/playwright-smoke
npm install
npm run install:browsers
npm run smoke
```

---

## 中间件契约

本项目要求开发和测试都使用容器化中间件，不依赖本机散装安装服务。

当前中间件包括：

- MySQL
- Redis
- MinIO
- Elasticsearch
- Kafka

详细约束见：

- [docs/DOCKER_MIDDLEWARE.md](/D:/java-projects/NoteWeave/docs/DOCKER_MIDDLEWARE.md)
- [.env.example](/D:/java-projects/NoteWeave/.env.example)

---

## 文档索引

- [docs/PROJECT_STATUS.md](/D:/java-projects/NoteWeave/docs/PROJECT_STATUS.md)
- [docs/CONTRACT.md](/D:/java-projects/NoteWeave/docs/CONTRACT.md)
- [docs/implementation_breakdown.md](/D:/java-projects/NoteWeave/docs/implementation_breakdown.md)
- [docs/features/README.md](/D:/java-projects/NoteWeave/docs/features/README.md)
- [docs/features/database_api_blueprint.md](/D:/java-projects/NoteWeave/docs/features/database_api_blueprint.md)
- [docs/note_weave_功能说明与架构文档.md](/D:/java-projects/NoteWeave/docs/note_weave_功能说明与架构文档.md)
- [NOTEWEAVE_启动指南.md](/D:/java-projects/NoteWeave/NOTEWEAVE_启动指南.md)
- [NOTEWEAVE_验收说明.md](/D:/java-projects/NoteWeave/NOTEWEAVE_验收说明.md)
