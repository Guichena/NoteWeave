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
- 事件必须可恢复、可 ack，避免刷新丢消息。

## 交付要求

实现代码和测试。并确保以下内容已落实：

```text
WS 事件协议
DRAFT 生命周期
中断/恢复如何处理
测试命令和结果已记录到 docs/PROJECT_STATUS.md
```

