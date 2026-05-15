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
- 先写 Wiki 草稿、发布、版本、WIKI_INDEX Task、权限失败测试。
- 覆盖发布失败可重试、未发布 Wiki 不进入 RAG。

wiki-domain-agent:
- 负责 WikiPage、WikiPageVersion、草稿创建/编辑/版本查询。
- 不实现复杂审批流。

publish-agent:
- 负责 OWNER 发布、ArtifactVersion/ChatMessage 生成草稿入口。
- 保证来源可追踪。

wiki-index-agent:
- 负责 WIKI_INDEX Task、WikiIndexService、WikiRetriever 基础接入。
- 发布失败不能破坏草稿。

source-trace-agent:
- 负责 Artifact / Message / Citation 到 Wiki 的来源关系保存。
- 不绕过 Citation 权限边界。

review-agent:
- 只做 review，不直接改代码。
- 重点检查发布权限、索引幂等、版本历史和 Artifact/Wiki 边界。
```
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

