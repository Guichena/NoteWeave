# Phase 0/1 Prompt: Bootstrap / Auth / User / Space / Permission

你是 NoteWeave 项目的编码代理。请执行 Phase 0/1：工程骨架、认证、用户、空间与权限。

## 测试驱动执行规则

本阶段必须采用测试驱动开发：

```text
1. 先根据本阶段目标和验收标准写测试
2. 运行测试，确认关键测试失败且失败原因符合预期
3. 再写最小实现让测试通过
4. 重构和补齐边界处理
5. 最后运行当前阶段相关测试和必要回归测试
```

## Docker 中间件执行规则

本阶段涉及的所有中间件必须通过 Docker Compose 或 Testcontainers 提供：

```text
MySQL
Redis
MinIO
Elasticsearch
Kafka
```

要求：

- 不允许依赖本机散装安装的中间件。
- 本地开发中间件统一维护在根目录 `docker-compose.yml`。
- 集成测试中间件统一使用 Testcontainers。
- 当前 Phase 新增中间件、bucket、topic、index 或测试路径时，必须同步更新 `docs/DOCKER_MIDDLEWARE.md`。
- 测试临时路径统一使用 `target/noteweave-test/{phase}/`，不能写用户机器绝对路径。

## 必读文档

按顺序读取：

```text
docs/PROJECT_STATUS.md
docs/CONTRACT.md
docs/DOCKER_MIDDLEWARE.md
docs/implementation_breakdown.md
docs/features/database_api_blueprint.md
docs/features/phase_0_1_bootstrap_auth_space.md
```

如果文档冲突，以 `CONTRACT.md`、`implementation_breakdown.md`、`database_api_blueprint.md`、当前 phase 文档的顺序为准。

## 目标

完成项目基础工程能力：

```text
统一响应
统一错误码
分页响应
JWT auth
refresh token / logout / logout-all
users.system_role
user_session
Space
SpaceMember
Personal Space 自动创建
SpacePermissionService
ResourceAccessService 基础约束
Flyway 初始迁移
```

## 严格边界

不要实现：

```text
文件上传
Task Worker
RAG
Chat
个人 ResearchProject
Artifact
Wiki
Quiz
```

## 必须遵守

- 所有 API 使用 `/api/v1`。
- `users` 表必须包含 `system_role`。
- refresh token 只保存 hash，放入 `user_session`。
- 注册成功后必须创建 PERSONAL Space，并写入 OWNER SpaceMember。
- `ADMIN` 是系统后台角色，不能和 Space `OWNER` 混用。
- 非 dev 环境 JWT secret 不能使用弱默认值。

## 交付要求

实现代码、迁移脚本和测试。并确保以下内容已落实：

```text
改了哪些文件
新增了哪些表/API
测试命令和结果已记录到 docs/PROJECT_STATUS.md
还剩哪些非阻塞风险
```

