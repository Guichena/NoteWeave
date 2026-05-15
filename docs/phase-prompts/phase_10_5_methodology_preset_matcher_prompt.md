# Phase 10.5 Prompt: Methodology Preset / Matcher

你是 NoteWeave 项目的编码代理。请执行 Phase 10.5：MethodologyCard 预置模板与匹配器前置切片。

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
- 先写 Methodology seed、matcher、prompt 集成相关失败测试。
- 覆盖预置模板可读、匹配结果稳定、无模板时可降级。

seed-agent:
- 负责预置 MethodologyCard seed 数据和幂等初始化。
- 不实现模板市场或复杂管理。

matcher-agent:
- 负责 MethodologyMatcher 初版和匹配评分规则。
- 不做复杂自动抽取。

prompt-agent:
- 负责把 workflow、outputStructure、qualityChecklist 注入生成上下文。
- 不修改 Phase 11 的完整生成流程。

review-agent:
- 只做 review，不直接改代码。
- 重点检查模板幂等、匹配可解释性和 Phase 13 边界。
```
## 必读文档

按顺序读取：

```text
docs/PROJECT_STATUS.md
docs/CONTRACT.md
docs/DOCKER_MIDDLEWARE.md
docs/implementation_breakdown.md
docs/features/database_api_blueprint.md
docs/features/phase_10_5_methodology_preset_matcher.md
docs/features/phase_13_methodology_card.md
docs/features/phase_11_personal_generation.md
```

## 目标

在 Phase 11 个人生成前提供最小方法论能力：

```text
MethodologyCard 预置模板
MethodologyMatcher
任务类型到方法论匹配
Prompt 注入结构
质量检查清单
```

## 严格边界

不要实现：

```text
高级编辑
版本管理
用户自定义复杂方法论
Artifact -> Methodology proposal
Quiz
```

## 必须遵守

- 只做 Phase 11 所需的最小可用能力。
- 完整 MethodologyCard 管理仍归 Phase 13。
- 预置模板必须可测试、可扩展。

## 交付要求

实现代码、基础数据或初始化逻辑和测试。并确保以下内容已落实：

```text
有哪些预置方法论
Matcher 如何选择
如何注入个人生成 Prompt
测试命令和结果已记录到 docs/PROJECT_STATUS.md
```
