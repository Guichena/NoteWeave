# Phase 8 Prompt: Studio / Artifact Generation

你是 NoteWeave 项目的编码代理。请执行 Phase 8：Studio 与 Artifact 生成系统。

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
docs/features/phase_1_5_task_outbox_worker.md
docs/features/phase_4_team_rag_chat_citation.md
docs/features/phase_7_personal_wiki_compiler_cards.md
docs/features/phase_8_studio_artifact_generation.md
```

## 目标

实现结构化产物生成：

```text
Studio Button
ARTIFACT_GENERATE Task
固定 Plan
Skill Registry
SkillExecutionLog
Artifact
ArtifactVersion
ArtifactSource
ArtifactCitation
SessionArtifact
Artifact 查看、编辑、归档、导出
```

## 严格边界

不要实现：

```text
团队 Wiki 发布
个人 Artifact 沉淀到 Wiki
复杂 Agent 自主规划
Quiz / 答题 / 评分 / 题库
```

## 必须遵守

- Artifact 默认不进入 Wiki。
- 不得使用 `generated_artifact`。
- Artifact 编辑和重新生成不能覆盖历史，必须写 ArtifactVersion。
- SkillExecutionLog 必须脱敏。
- Artifact Citation 必须走 `artifact_citation`。

## 交付要求

实现代码、迁移脚本和测试。并确保以下内容已落实：

```text
支持哪些 artifact_type
Artifact 版本如何保存
Skill 日志如何脱敏
测试命令和结果已记录到 docs/PROJECT_STATUS.md
```

