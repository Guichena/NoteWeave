# Phase 6 Prompt: Personal ResearchProject / Source

你是 NoteWeave 项目的编码代理。请执行 Phase 6：个人 ResearchProject 与 Source 导入。

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
docs/features/phase_1_5_task_outbox_worker.md
docs/features/phase_3_document_processing_indexing.md
docs/features/phase_6_personal_research_source.md
```

## 目标

实现个人研究资料入口：

```text
ResearchProject
Source
FILE / URL / TEXT 导入
raw_text_object_key
parsed_text_object_key
SOURCE_IMPORT Task
Source 列表、详情、删除、重新导入
```

## 严格边界

不要实现：

```text
ArticleCard
ConceptCard
个人生成
Artifact
外部资料自动发现
Quiz
```

## 必须遵守

- ResearchProject 只能属于 PERSONAL Space。
- Source READY 时必须有 raw text 或 parsed text。
- URL 抓取失败不能标记 READY。
- 个人资源必须校验 owner，不能只按 id 查询。
- FILE 可复用 DocumentParser，但不能复用团队权限模型。

## 交付要求

实现代码、迁移脚本和测试。并确保以下内容已落实：

```text
Source 状态流转
READY 文本约束
权限校验点
测试命令和结果已记录到 docs/PROJECT_STATUS.md
```

