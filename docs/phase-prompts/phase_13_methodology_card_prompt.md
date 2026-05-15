# Phase 13 Prompt: MethodologyCard

你是 NoteWeave 项目的编码代理。请执行 Phase 13：MethodologyCard 方法论卡片。

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
- 先写 MethodologyCard CRUD、模板版本、scope、自动匹配和权限测试。
- 覆盖预置模板只读、用户模板 owner-only。

methodology-domain-agent:
- 负责 MethodologyCard 模型、版本、状态、scope 和归档。
- 不实现模板市场。

api-agent:
- 负责用户创建/编辑/列表/归档 API。
- 保证系统预置、个人 Space、项目内模板权限分离。

matcher-agent:
- 负责项目内、个人 Space、系统预置三级匹配。
- 匹配结果需要可解释。

prompt-agent:
- 负责生成任务选择或自动匹配 MethodologyCard 的 Prompt 集成。
- 不改变 Artifact 默认不进入 Wiki 的契约。

review-agent:
- 只做 review，不直接改代码。
- 重点检查模板权限、版本历史、匹配优先级和 Prompt 可观测性。
```
## 必读文档

按顺序读取：

```text
docs/PROJECT_STATUS.md
docs/CONTRACT.md
docs/DOCKER_MIDDLEWARE.md
docs/implementation_breakdown.md
docs/features/database_api_blueprint.md
docs/features/phase_11_personal_generation.md
docs/features/phase_13_methodology_card.md
```

## 目标

完善方法论卡片：

```text
MethodologyCard 管理
预置模板
任务匹配
编辑
归档
质量检查清单
生成 Prompt 结构化注入
```

## 严格边界

不要实现：

```text
复杂多人审批
自动从 Artifact 修改 MethodologyCard
未确认 proposal 自动合并
Quiz
```

## 必须遵守

- 预置方法论和用户方法论要能区分。
- 用户编辑必须校验 owner。
- 后续 Artifact / SynthesisCard -> MethodologyCard proposal 只能用户确认后写入。

## 交付要求

实现代码和测试。并确保以下内容已落实：

```text
MethodologyCard 字段
Matcher 如何工作
编辑和归档规则
测试命令和结果已记录到 docs/PROJECT_STATUS.md
```

