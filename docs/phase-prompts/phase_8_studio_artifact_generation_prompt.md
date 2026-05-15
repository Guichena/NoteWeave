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

## Subagent 分工模板

本节描述 AI 编码代理执行本 Phase 时允许使用的 subagent 协作方式。`subagent` 只表示编码执行分工，不是 NoteWeave 产品运行态 Agent/Skill 设计。

通用规则：

- 主代理是本 Phase 的 owner，负责最终集成、测试、文档更新和交付结论。
- subagent 必须先按本文档的“必读文档”顺序读取上下文，再开始自己的子任务。
- 每个 subagent 必须有明确 ownership，限定可修改的模块、文件或测试范围。
- 不允许多个 subagent 同时修改同一文件或同一模块；需要交叉修改时由主代理统一合并。
- 不允许 subagent 扩大当前 Phase 范围，遇到范围外问题只记录为遗留风险。
- 所有实现仍必须遵守 TDD：先写失败测试，再写最小实现，再重构和回归。
- subagent 产出必须由主代理 review 后合入，主代理不能直接信任未验证结果。

推荐分工：

```text
lead-agent:
- 读取全部必读文档，拆分任务，维护 Phase 边界。
- 负责最终集成、运行回归测试、更新 PROJECT_STATUS。

test-agent:
- 先写 Studio Task、ArtifactVersion、SkillExecutionLog、导出和权限失败测试。
- 覆盖编辑/重新生成不覆盖历史、Artifact 默认不进 Wiki。

artifact-domain-agent:
- 负责 Artifact、ArtifactVersion、ArtifactSource、ArtifactCitation、SessionArtifact。
- 不实现团队发布 Wiki 或个人沉淀 Wiki。

studio-task-agent:
- 负责 StudioTaskService、ARTIFACT_GENERATE Task 创建/取消/重试/查询。
- 不新增 REPORT_GENERATION 等并行 Task 类型。

plan-skill-agent:
- 负责 ArtifactPlanExecutor、固定 Plan、Skill Registry、Skill 接口。
- 不实现复杂 Agent 自主规划。

export-agent:
- 负责 Markdown 导出、Artifact 更新、归档和重新生成入口。
- 不实现 PPT / DOCX / PDF 高级导出。

logging-agent:
- 负责 SkillExecutionLog 脱敏、taskId/artifactVersionId 绑定。
- 不暴露完整敏感 Prompt 或私有原文。

review-agent:
- 只做 review，不直接改代码。
- 重点检查 Artifact/Wiki 边界、版本历史、Skill 日志脱敏和权限。
```
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

