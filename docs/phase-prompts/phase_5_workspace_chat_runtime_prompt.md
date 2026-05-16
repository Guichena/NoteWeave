# Phase 5 Prompt: Workspace Chat Runtime

你是 NoteWeave 项目的编码代理。请执行 Phase 5：工作台 WebSocket 会话执行底座。

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
- 先写 WebSocket ticket、stream、stop、resume、DRAFT 生命周期相关失败测试。
- 覆盖断线恢复、停止后不继续 delta、DRAFT 不写长期 Memory。

ws-agent:
- 负责 WebSocket ticket、事件 envelope、事件名和连接生命周期。
- 不修改长期 Memory 或 Artifact。

runtime-state-agent:
- 负责 Redis runtime state、short term context、stream state。
- 保证 key 命名和过期策略符合 Docker/Testcontainers 约定。

chat-runtime-agent:
- 负责 ChatRuntimeService、ActiveExecutionRegistry、stop/resume 执行流程。
- 与 Phase 4 RAG 服务通过清晰接口集成。

session-agent:
- 负责 FORMAL / DRAFT 会话状态、转换、丢弃和过期。
- 保证 DRAFT 生命周期不污染正式会话。

review-agent:
- 只做 review，不直接改代码。
- 重点检查事件顺序、恢复幂等、并发停止和状态一致性。
```
## 必读文档

按顺序读取：

```text
docs/PROJECT_STATUS.md
docs/CONTRACT.md
docs/DOCKER_MIDDLEWARE.md
docs/implementation_breakdown.md
docs/features/database_api_blueprint.md
docs/features/phase_4_team_rag_chat_citation.md
docs/features/phase_5_workspace_chat_runtime.md
docs/features/workspace_chat_runtime_memory.md
```

## 目标

实现工作台会话运行态：

```text
WebSocket ticket
WebSocket event envelope
streamId / eventSeq / ack
DRAFT 会话
流式输出
停止/中断
刷新恢复
Redis runtime state
Redis runtime event buffer（Redis Stream 可选）
chat_session.runtime_status
```

## 严格边界

不要实现：

```text
长期 Memory 深化
个人 ResearchProject
Artifact 生成
Wiki 发布
Quiz
```

## 必须遵守

- HTTP Chat 和 WebSocket Chat 的消息、Citation、权限口径一致。
- DRAFT 生命周期必须明确：active、expired、converted、discarded。
- Redis 只保存运行态，不替代 MySQL 长期数据。
- Kafka 仍是唯一后台异步任务消息队列；Redis Stream 如使用，只能作为 Phase 5 WebSocket runtime 的临时事件缓冲和 ack/resume 恢复机制。
- 不得用 Redis Stream 承载上传、解析、索引、生成、评测、清理等后台任务；如果普通 Redis key/list/zset 足够，可不引入 Redis Stream。
- 事件必须可恢复、可 ack，避免刷新丢消息。

## 交付要求

实现代码和测试。并确保以下内容已落实：

```text
WS 事件协议
DRAFT 生命周期
中断/恢复如何处理
测试命令和结果已记录到 docs/PROJECT_STATUS.md
```
