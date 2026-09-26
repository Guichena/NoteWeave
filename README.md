# NoteWeave v2

NoteWeave v2 是一个来源驱动的研究工作台。系统以 Workspace 隔离资料、会话、知识、研究任务、产物和 Memory，提供三条聊天回答链路以及两个独立长任务能力：

- QA：在 Workspace 资料范围内检索证据并生成带引用回答。
- Note：按资料召回、原文窗口阅读和关系扩展组织证据，可把确认后的中性草稿保存为资料。
- Wiki：检索并维护带版本、链接、反链、来源回链和治理信息的知识页面。
- Deep Research：通过 Java 协调面与 Python Research Worker 执行可恢复的研究任务。
- Artifact：通过 Skill Catalog、Skill Graph 和 Python Artifact Worker 生成、校验、版本化、导出并写回产物。
- Memory：把候选、人工审核、运行时 revision 和 outcome 反馈组织为受控长期沉淀。

长期文档从 [文档中心](./docs/README.md) 开始阅读。文档统一使用 `[当前实现]`、`[目标设计]`、`[已测-模拟]`、`[行业参考]` 和 `[生产待验证]` 标记事实边界。

## 技术结构

| 组件 | 当前实现 |
| --- | --- |
| Backend | Java 17、Spring Boot 3.3.5、Spring JDBC、Flyway |
| Frontend | React 19、TypeScript 5、Vite 5 |
| Research Worker | Python 3.12、Pydantic、Kafka consumer |
| Artifact Worker | Python 3.12、FastAPI、Pydantic、可选 LLM 与系统 MCP |
| 业务真源 | MySQL 8.4，Flyway 仓库迁移当前到 `V104` |
| 运行设施 | Redis 7.2、Kafka、MinIO、Elasticsearch 8.15.3 |

Backend 持有业务真源和协调状态。Research Worker 只通过 Kafka 命令和受保护的内部 HTTP 契约与 Backend 通信；Artifact Worker 由 Backend 的可靠 Outbox 通过 HTTP 调度。两个 Worker 都不直接写 Java 业务表。

## Docker 启动

在仓库根目录创建开发环境变量文件：

```powershell
Copy-Item .env.example .env
```

请至少替换 `.env` 中的数据库、Redis、Kafka、Elasticsearch、MinIO、内部服务令牌、Bootstrap 密码，以及 Chat / Embedding / Rerank / Research / Artifact 的 Provider 密钥。LLM、ES、Embedding 和 Rerank 默认开启，密钥留空时应用能启动，完整 QA、Research 和 Artifact 结果要等密钥配好之后才会生成。随后启动完整应用：

```powershell
docker compose --profile app up --build -d
docker compose --profile app ps
```

浏览器入口是 `http://localhost:3000`。Frontend 由 Nginx 托管，同源 `/api` 请求转发到 Backend。Backend 的容器端口为 `8081`，Artifact Worker 的容器内控制面端口为 `18092`。

不带 `--profile app` 的 `docker compose up -d` 只启动 MySQL、Redis、Kafka、MinIO 和 Elasticsearch。

## Provider 边界

`.env.example` 默认开启下列能力，密钥需要启动后自行填写：

- `NOTEWEAVE_LLM_ENABLED=true`
- `NOTEWEAVE_EMBEDDING_ENABLED=true`
- `NOTEWEAVE_RERANK_ENABLED=true`
- `NOTEWEAVE_ES_ENABLED=true`
- Research 与 Artifact 的 LLM 开关走各自变量，地址和模型有示例值，密钥默认为空

复制 `.env.example` 为 `.env` 后，先填基础设施密码和内部令牌即可启动。Chat、Research 和 Artifact 使用彼此独立的 Provider 配置，不能共用通用密钥变量；对应 `*_API_KEY` 仍为空时，相关生成会失败或降级，不会假装已经配好。

完整演示前可检查配置：

```powershell
.\scripts\smoke-compose-provider-readiness.ps1 -EnvFile .env -RequireFullDemo
```

## 本地开发

Backend：

```powershell
.\mvnw.cmd -f backend\pom.xml spring-boot:run
```

Frontend：

```powershell
Set-Location frontend
corepack enable
pnpm install --frozen-lockfile
pnpm dev
```

Python Worker 环境与启动命令见 [workers/README.md](./workers/README.md)。

## 验证

Backend 全量测试：

```powershell
.\mvnw.cmd -f backend\pom.xml test
```

Frontend 测试与构建：

```powershell
Set-Location frontend
pnpm test
pnpm build
```

Worker 测试：

```powershell
.\workers\scripts\test-workers.ps1
```

真实浏览器 E2E 需要先启动完整 Compose 环境：

```powershell
Set-Location frontend
corepack pnpm e2e
```

E2E 不使用 Mock Server，会创建真实 Workspace、上传资料并观察任务 SSE。Provider 未配置时，只能验证对应的受控失败或降级路径。

## 目录

- `backend`：Java 主系统、Flyway 迁移和后端测试。
- `frontend`：React 工作台、组件测试和 Playwright E2E。
- `workers/research-worker`：Research Agent 执行面。
- `workers/artifact-worker`：Artifact Skill 执行面。
- `docs`：长期技术文档、研究材料和受保护面试资料。
- `scripts/fixtures`：验证脚本使用的 manifest、evidence mapping 和能力覆盖输入，不作为长期文档入口。
- `scripts`：运行、评测、Smoke 和迁移辅助脚本。
- `reference`：外部参考源码与研究输入，不属于生产运行路径。
