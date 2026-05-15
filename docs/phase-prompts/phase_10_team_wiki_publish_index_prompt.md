# Phase 10 Prompt: Team Wiki Publish / Index

你是 NoteWeave 项目的编码代理。请执行 Phase 10：团队 Wiki Draft、发布与入索引。

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
docs/features/phase_8_studio_artifact_generation.md
docs/features/phase_9_retrieval_enhancement_rrf.md
docs/features/phase_10_team_wiki_publish_index.md
```

## 目标

实现团队 Wiki 闭环：

```text
WikiPage
WikiPageVersion
Wiki 草稿
Artifact -> WikiPage
ChatMessage/Citation -> Wiki 草稿
发布
WIKI_INDEX Task
WikiRetriever
Wiki 搜索
wiki_page_citation
```

## 严格边界

不要实现：

```text
复杂审批流
多人实时协同编辑
个人 SynthesisCard
个人 Artifact 沉淀
Quiz
```

## 必须遵守

- 团队 Artifact 需要人工确认后才发布为 WikiPage。
- 发布后生成 WikiPageVersion。
- 只有 PUBLISHED 且已索引的 Wiki 进入团队 RAG。
- Wiki 入索引失败可重试，不影响草稿。
- Wiki 引用必须写 `wiki_page_citation`。

## 交付要求

实现代码、迁移脚本和测试。并确保以下内容已落实：

```text
发布流程
WikiPageVersion 如何生成
WIKI_INDEX 如何重试
测试命令和结果已记录到 docs/PROJECT_STATUS.md
```

