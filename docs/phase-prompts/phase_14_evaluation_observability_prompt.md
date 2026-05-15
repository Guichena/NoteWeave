# Phase 14 Prompt: Evaluation / Observability

你是 NoteWeave 项目的编码代理。请执行 Phase 14：评测与可观测性。

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
docs/features/phase_8_studio_artifact_generation.md
docs/features/phase_9_retrieval_enhancement_rrf.md
docs/features/phase_14_evaluation_observability.md
```

## 目标

实现评测和可观测性：

```text
LLMCallLog
RetrievalTrace
RAG Eval
Citation quality
UserFeedback
Task observability
Admin 可查看基础日志
```

## 严格边界

不要实现：

```text
题库测评
Quiz 打分
复杂 BI 大屏
商业化计费
```

## 必须遵守

- LLM 日志必须脱敏。
- 不直接保存完整私有原文和敏感 Prompt。
- RetrievalTrace 要能解释 BM25 / Vector / Wiki / RRF。
- 评测不能绕过权限读取用户无权资源。

## 交付要求

实现代码、迁移脚本和测试。并确保以下内容已落实：

```text
记录哪些日志
如何脱敏
如何关联 Task / Retrieval / Citation
测试命令和结果已记录到 docs/PROJECT_STATUS.md
```

