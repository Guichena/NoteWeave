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
- 先写 SOURCE_COMPILE、ArticleCard、ConceptCard、Evidence 回溯相关失败测试。
- 覆盖 Source 无文本不可编译、JSON 解析失败、概念去重。

article-card-agent:
- 负责 ArticleCard 模型、服务、查询和 Source 关联。
- 不修改 Concept merge 规则。

concept-agent:
- 负责 ConceptCard、ConceptAlias、ConceptRelation、ArticleConceptRelation。
- 保证合并只在同一 ResearchProject 内执行。

compiler-agent:
- 负责 WikiCompilerService、LLM JSON 输出解析、SOURCE_COMPILE Worker。
- 失败必须回写 Task 和可诊断错误。

evidence-agent:
- 负责 EvidenceBacktraceService、card citation 关系表、证据校验。
- evidenceQuotesJson 只能作为展示缓存。

review-agent:
- 只做 review，不直接改代码。
- 重点检查证据不丢失、概念合并、LLM JSON 失败和 owner-only 权限。
```
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

