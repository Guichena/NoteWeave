# Phase 7 Prompt: Personal Wiki Compiler Cards

你是 NoteWeave 项目的编码代理。请执行 Phase 7：个人 Wiki Compiler MVP。

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
docs/features/phase_6_personal_research_source.md
docs/features/phase_7_personal_wiki_compiler_cards.md
docs/features/phase_4_team_rag_chat_citation.md
```

## 目标

实现 Source 到个人 Wiki Card 的编译：

```text
SOURCE_COMPILE Task
ArticleCard
ConceptCard
ConceptAlias
ConceptRelation
ArticleConceptRelation
article_card_citation
concept_card_citation
Card 搜索与详情
Evidence 回溯
```

## 严格边界

不要实现：

```text
SynthesisCard 创建
Artifact 沉淀
个人生成
Methodology 自动抽取
Quiz
```

## 必须遵守

- Source 编译不创建 SynthesisCard。
- `evidenceQuotesJson` 只能作为展示缓存，正式证据写 Card Citation 关联表。
- Concept 合并必须保留旧 evidence，不覆盖。
- 低置信度创建新 ConceptCard，高置信度合并已有卡片。
- 所有个人 Card 必须校验 owner。

## 交付要求

实现代码、迁移脚本和测试。并确保以下内容已落实：

```text
编译流程
Concept 合并规则
Citation/Evidence 如何回溯
测试命令和结果已记录到 docs/PROJECT_STATUS.md
```

