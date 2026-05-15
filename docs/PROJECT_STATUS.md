# NoteWeave Project Status

本文档用于给后续 AI 编码代理快速判断当前做到哪里。每次开始新阶段前先读本文档；每完成一个阶段后必须更新本文档。

更新时间：2026-05-15

---

## 1. 当前结论

当前状态：

```text
文档契约、Docker 中间件契约和 Phase 阶段边界已复查对齐；Phase 0/1 和 Phase 1.5 已通过回归测试，可以进入 Phase 2。
```

本轮已完成的是架构文档、数据库/API 蓝图、阶段提示词、执行契约、Docker 中间件契约和阶段边界复查整理。当前代码已包含 Auth/User/Space/Permission 与 Task/Outbox/Worker 基础设施，并已通过现有测试验证。

下一步：

```text
进入 Phase 2: 文件上传与异步摄取。
```

---

## 2. AI 开工读取顺序

每个阶段开始前按这个顺序读取：

```text
docs/PROJECT_STATUS.md
docs/CONTRACT.md
docs/DOCKER_MIDDLEWARE.md
docs/implementation_breakdown.md
docs/features/database_api_blueprint.md
docs/phase-prompts/{current_phase}_prompt.md
docs/features/{current_phase}.md
```

如果文档冲突，优先级固定为：

```text
PROJECT_STATUS
CONTRACT
DOCKER_MIDDLEWARE
implementation_breakdown
database_api_blueprint
phase prompt
phase document
```

所有编程阶段默认使用测试驱动开发：

```text
先写测试
运行并确认测试失败
再写实现
重构
最后运行当前阶段测试和必要回归测试
```

以下文档只作为背景或历史审查记录，不作为实现权威：

```text
docs/note_weave_功能说明与架构文档.md
docs/features/noteweave_full_arch_review.md
docs/architecture_review_issues_and_recommendations.md
```

---

## 3. 已完成的文档准备

已完成：

```text
docs/CONTRACT.md
docs/DOCKER_MIDDLEWARE.md
docs/implementation_breakdown.md
docs/features/database_api_blueprint.md
docs/features/phase_1_5_task_outbox_worker.md
docs/features/phase_10_5_methodology_preset_matcher.md
docs/features/phase_11_5_personal_artifact_distillation.md
docs/phase-prompts/*
docker-compose.yml
.env.example
```

关键契约已定：

```text
所有 API 使用 /api/v1
所有中间件通过 Docker Compose 或 Testcontainers 提供
测试临时路径统一使用 target/noteweave-test/{phase}/
Space 是最高业务容器
users.system_role = USER / ADMIN
refresh token 使用 user_session
异步任务统一使用 task / task_attempt / task_event / task_outbox
task.status 包含 TIMEOUT
Artifact 默认不进入 Wiki
团队 Artifact 人工发布为 WikiPage 后进入团队 RAG Index
个人 Artifact 用户确认后 MVP 只沉淀为 SynthesisCard
Citation / Evidence 必须可回溯，Card 证据不能只放 JSON
Quiz / 答题 / 评分 / 题库暂缓
```

---

## 4. 阶段状态

| 阶段 | 状态 | 说明 |
|---|---|---|
| 文档契约整理 | DONE | 契约、蓝图、Docker 中间件契约、Phase prompt 已整理 |
| Phase 0/1 | DONE | 工程骨架、认证、用户、空间、权限代码已完成，并通过回归测试 |
| Phase 1.5 | DONE | Task / Outbox / Worker 代码已完成，并通过回归测试 |
| Phase 2 | PENDING | 文件上传与异步摄取 |
| Phase 3 | PENDING | 文档解析、Chunk、索引 |
| Phase 4 | PENDING | 团队 RAG Chat 与 Citation |
| Phase 5 | PENDING | WebSocket Chat Runtime |
| Phase 6 | PENDING | 个人 ResearchProject / Source |
| Phase 7 | PENDING | 个人 Wiki Compiler |
| Phase 8 | PENDING | Studio / Artifact |
| Phase 9 | PENDING | 检索增强 / RRF |
| Phase 10 | PENDING | 团队 Wiki 发布入索引 |
| Phase 10.5 | PENDING | Methodology 预置模板与 Matcher |
| Phase 11 | PENDING | 个人 Wiki-based Generation |
| Phase 11.5 | PENDING | 个人 Artifact 沉淀为 SynthesisCard |
| Phase 12 | PENDING | Long-term Memory |
| Phase 13 | PENDING | MethodologyCard 完整管理 |
| Phase 14 | PENDING | Evaluation / Observability |
| Phase 15 | PENDING | Admin / Ops |
| Phase 16 | PENDING | Frontend Workspace |

---

## 5. 暂缓范围

当前不要实现：

```text
Quiz
测验答题
评分
题库
错题复习
外部研究资料自动发现
商业化计费
复杂企业审批流
复杂多人实时协同编辑
```

---

## 6. 每阶段完成后更新格式

完成一个阶段后，把本文档中的阶段状态更新为 `DONE`，并补充：

```text
完成阶段：
完成日期：
主要改动：
新增表：
新增 API：
测试命令：
测试结果：
遗留风险：
下一阶段：
```

如果某阶段只完成一部分，状态使用：

```text
IN_PROGRESS
```

不要把未完成阶段标为 `DONE`。

---

## 7. 测试驱动要求

每个编程阶段完成时，必须在本文档的阶段记录中更新：

```text
实现后运行了哪些测试命令
测试结果是什么
哪些场景暂时只能手动验证
```

阶段状态不能只因为代码写完就标为 `DONE`；必须完成验证后才能标为 `DONE`。

---

## 8. 最近验证记录

完成阶段：
```text
文档契约整理
Docker 中间件契约整理
Phase 阶段边界冲突复查
Phase 0/1 验证
Phase 1.5 验证
```

完成日期：
```text
2026-05-15
```

主要改动：
```text
补齐 Docker Compose 中间件：MySQL / Redis / MinIO / Elasticsearch / Kafka
补齐 .env.example
补齐 docs/DOCKER_MIDDLEWARE.md
同步 application.yml 与 test application.yml 的容器化中间件配置
阶段提示词与执行契约增加 TDD 和 Docker/Testcontainers 要求
补齐 Phase 10.5 和 Phase 11.5 feature 文档
收口 Phase 8 / Phase 11 / Phase 11.5 的 Artifact -> Wiki 边界
统一 Studio 生成任务为 ARTIFACT_GENERATE + params.artifactType
统一 Phase 2 上传对象 key 与 Docker dev/test 前缀契约
```

测试命令：
```text
docker compose config --quiet
git diff --check
Markdown code fence balance check
mvn test
```

测试结果：
```text
docker compose config 校验通过
git diff --check 通过，仅有 CRLF 换行提示
Markdown code fence balance check 通过
mvn test 通过：Tests run: 30, Failures: 0, Errors: 0, Skipped: 0
```

下一阶段：
```text
Phase 2: 文件上传与异步摄取
```
