# Phase 4 Prompt: Team RAG Chat / Citation

你是 NoteWeave 项目的编码代理。请执行 Phase 4：团队基础 RAG 问答与 Citation 回溯。

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
docs/features/phase_3_document_processing_indexing.md
docs/features/phase_4_team_rag_chat_citation.md
```

## 目标

完成团队侧最小 RAG 问答闭环：

```text
ChatSession
ChatSessionScope
ChatMessage
BM25 Retriever
Evidence Context
LLM Answer
Citation
MessageCitation
Retrieval Trace 基础记录
```

## 严格边界

不要实现：

```text
WebSocket 流式
DRAFT 会话恢复
向量召回
WikiRetriever
个人 Wiki
Artifact 生成
Quiz
```

## 必须遵守

- Citation 必须保存 `pageNo/startOffset/endOffset/quoteHash/snapshotObjectKey/sourceVersion`。
- 不得把 citation ids 放进 message JSON。
- Citation 返回前必须二次权限校验。
- 检索前必须做 Space / KnowledgeBase 权限过滤。
- 证据不足时要明确返回可解释结果，不要伪造引用。

## 交付要求

实现代码、迁移脚本和测试。并确保以下内容已落实：

```text
RAG 请求流程
权限过滤点
Citation 如何保存和查询
测试命令和结果已记录到 docs/PROJECT_STATUS.md
```

