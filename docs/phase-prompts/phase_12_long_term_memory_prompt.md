# Phase 12 Prompt: Long-term Memory

你是 NoteWeave 项目的编码代理。请执行 Phase 12：长期 Memory 深化。

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
- 先写 SessionSummary、UserMemory、Space/Workspace Memory、删除禁用和 DRAFT 不写入测试。
- 覆盖隐私隔离、过期、置信度、pin。

memory-domain-agent:
- 负责 MemoryItem / UserMemory / SpaceMemory 数据模型和服务。
- 不实现团队共享 Memory 的增强范围。

summary-agent:
- 负责 SessionSummaryService、会话摘要生成和读取。
- DRAFT 会话不得写长期 Memory。

context-router-agent:
- 负责 ContextReadRouter 集成长期 Memory 和会话上下文。
- 保证权限边界明确。

writeback-agent:
- 负责 MemoryWritebackStrategy、敏感信息过滤、置信度和过期策略。
- 低置信度内容不能覆盖稳定偏好。

api-agent:
- 负责 Memory 查看、编辑、删除、禁用写入 API。
- 用户必须知道哪些 Memory 会影响后续回答。

review-agent:
- 只做 review，不直接改代码。
- 重点检查隐私泄露、DRAFT 边界、过期策略和删除禁用语义。
```
## 必读文档

按顺序读取：

```text
docs/PROJECT_STATUS.md
docs/CONTRACT.md
docs/DOCKER_MIDDLEWARE.md
docs/implementation_breakdown.md
docs/features/database_api_blueprint.md
docs/features/phase_5_workspace_chat_runtime.md
docs/features/workspace_chat_runtime_memory.md
docs/features/phase_12_long_term_memory.md
```

## 目标

实现长期记忆能力：

```text
SessionSummary
SpaceMemory
UserMemory
记忆写回
TTL / expires_at
pin
importance_score
confidence_score
上下文按需加载
```

## 严格边界

不要实现：

```text
替代 Chat Runtime 的 Redis 运行态
自动泄露私有原文
复杂个性化推荐
Quiz
```

## 必须遵守

- Phase 5 负责运行态，Phase 12 负责长期记忆。
- Memory 写入必须有来源和置信度。
- 过期记忆不能进入默认上下文。
- pin 的记忆不能被 TTL 清理。
- 个人和团队 Memory 权限边界必须分开。

## 交付要求

实现代码、迁移脚本和测试。并确保以下内容已落实：

```text
Memory 类型
写入和过期策略
上下文加载策略
测试命令和结果已记录到 docs/PROJECT_STATUS.md
```

