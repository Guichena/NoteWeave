# Phase 10.5 Prompt: Methodology Preset / Matcher

你是 NoteWeave 项目的编码代理。请执行 Phase 10.5：MethodologyCard 预置模板与匹配器前置切片。

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
docs/features/phase_10_5_methodology_preset_matcher.md
docs/features/phase_13_methodology_card.md
docs/features/phase_11_personal_generation.md
```

## 目标

在 Phase 11 个人生成前提供最小方法论能力：

```text
MethodologyCard 预置模板
MethodologyMatcher
任务类型到方法论匹配
Prompt 注入结构
质量检查清单
```

## 严格边界

不要实现：

```text
高级编辑
版本管理
用户自定义复杂方法论
Artifact -> Methodology proposal
Quiz
```

## 必须遵守

- 只做 Phase 11 所需的最小可用能力。
- 完整 MethodologyCard 管理仍归 Phase 13。
- 预置模板必须可测试、可扩展。

## 交付要求

实现代码、基础数据或初始化逻辑和测试。并确保以下内容已落实：

```text
有哪些预置方法论
Matcher 如何选择
如何注入个人生成 Prompt
测试命令和结果已记录到 docs/PROJECT_STATUS.md
```
