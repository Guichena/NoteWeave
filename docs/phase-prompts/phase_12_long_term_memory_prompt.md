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

