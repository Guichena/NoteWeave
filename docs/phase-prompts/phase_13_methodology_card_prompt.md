# Phase 13 Prompt: MethodologyCard

你是 NoteWeave 项目的编码代理。请执行 Phase 13：MethodologyCard 方法论卡片。

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
docs/features/phase_11_personal_generation.md
docs/features/phase_13_methodology_card.md
```

## 目标

完善方法论卡片：

```text
MethodologyCard 管理
预置模板
任务匹配
编辑
归档
质量检查清单
生成 Prompt 结构化注入
```

## 严格边界

不要实现：

```text
复杂多人审批
自动从 Artifact 修改 MethodologyCard
未确认 proposal 自动合并
Quiz
```

## 必须遵守

- 预置方法论和用户方法论要能区分。
- 用户编辑必须校验 owner。
- 后续 Artifact / SynthesisCard -> MethodologyCard proposal 只能用户确认后写入。

## 交付要求

实现代码和测试。并确保以下内容已落实：

```text
MethodologyCard 字段
Matcher 如何工作
编辑和归档规则
测试命令和结果已记录到 docs/PROJECT_STATUS.md
```

