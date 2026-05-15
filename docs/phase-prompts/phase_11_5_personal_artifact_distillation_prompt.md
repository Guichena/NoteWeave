# Phase 11.5 Prompt: Personal Artifact Distillation

你是 NoteWeave 项目的编码代理。请执行 Phase 11.5：个人 Artifact 沉淀回个人 Wiki。

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
docs/features/phase_11_5_personal_artifact_distillation.md
docs/features/phase_8_studio_artifact_generation.md
docs/features/phase_11_personal_generation.md
docs/features/phase_7_personal_wiki_compiler_cards.md
```

## 目标

实现个人侧用户确认后的沉淀闭环：

```text
Artifact -> SynthesisCard
artifact_card_relation
synthesis_card_citation
synthesis_concept_relation
distill-to-personal-wiki API
ArtifactViewer 沉淀入口所需后端能力
```

## 严格边界

不要实现：

```text
生成完成后自动写入个人 Wiki
自动修改已有 ConceptCard
自动修改 MethodologyCard
复杂 merge proposal
Quiz
```

## 必须遵守

- 只有用户显式确认后才能沉淀。
- MVP 只开放 `Artifact -> SynthesisCard`。
- Concept / Methodology 只能作为后续 proposal 来源。
- 必须保留 Artifact 与 Card 的来源关系。

## 交付要求

实现代码、迁移脚本和测试。并确保以下内容已落实：

```text
确认流程
SynthesisCard 如何生成
ArtifactCardRelation 如何写入
测试命令和结果已记录到 docs/PROJECT_STATUS.md
```
