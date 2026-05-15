# Phase 11 Prompt: Personal Wiki-based Generation

你是 NoteWeave 项目的编码代理。请执行 Phase 11：个人 Wiki-based Generation。

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
docs/features/phase_7_personal_wiki_compiler_cards.md
docs/features/phase_8_studio_artifact_generation.md
docs/features/phase_13_methodology_card.md
docs/features/phase_11_personal_generation.md
```

## 目标

基于个人 Wiki Card 生成 Artifact：

```text
PersonalGenerationService
Research Report
Study Guide
Reading Notes
Comparison
Work Prep
MethodologyCard 注入
ArticleCard / ConceptCard / SynthesisCard（如已存在）检索
ArtifactSource
ArtifactCitation
```

## 严格边界

不要实现：

```text
生成后自动写入个人 Wiki
Artifact -> SynthesisCard 沉淀
Concept merge proposal
Methodology proposal
Quiz / 答题 / 评分
```

## 必须遵守

- 个人生成结果默认只是 Artifact。
- 生成时可读取 SynthesisCard，但不能自动创建 SynthesisCard。
- Artifact 来源和 Citation 必须可回溯。
- 个人资源必须校验 owner。

## 交付要求

实现代码和测试。并确保以下内容已落实：

```text
支持哪些生成类型
上下文如何装配
ArtifactSource/Citation 如何保存
测试命令和结果已记录到 docs/PROJECT_STATUS.md
```
