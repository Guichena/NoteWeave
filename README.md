# NoteWeave v2

NoteWeave v2 是一个参考 NotebookLM 产品形态重构的研究工作台。

当前冻结口径：

- 基本单位：`研究工作台`
- 三种聊天回答链路：`问答 RAG / Note / Wiki`
- 两个独立任务能力：`Deep Research / 右侧产物栏`
- 一个共享资料底座：`工作台资料池`
- 一套门控式长期沉淀机制：`Graduated Memory + Task-Neighborhood Memory Compiler`
- 一套实现分工：`Java 主系统 + Python Research Worker + Python Artifact Worker`

## 当前最重要的文档

所有文档从统一入口开始阅读：

1. [文档中心与权威等级](D:/java-projects/NoteWeave-v2/docs/README.md)
2. [目标系统架构、功能与中间件设计](D:/java-projects/NoteWeave-v2/docs/NoteWeave-v2目标系统架构与功能中间件设计.md)
3. [现有系统适配与改造实施方案](D:/java-projects/NoteWeave-v2/改造计划/NoteWeave-v2现有系统适配与改造实施方案.md)
4. [主项目系统设计与中间件全面审计](D:/java-projects/NoteWeave-v2/docs/主项目系统设计与中间件全面审计及全新优化方案.md)
5. [API 与事件契约 v2](D:/java-projects/NoteWeave-v2/docs/API与事件契约-v2.md)
6. [数据库与迁移规范](D:/java-projects/NoteWeave-v2/docs/数据库与迁移规范.md)
7. [主项目重构施工索引](D:/java-projects/NoteWeave-v2/改造计划/主项目重构/README.md)

说明：

- 当前代码、Flyway 和自动化测试是实现事实；目标架构描述最终形态；改造实施方案描述迁移路径。
- 旧阶段计划、早期架构和专项审计不再自动具有施工优先级。
- 文档冲突按照 `docs/README.md` 中的 L0～L9 权威等级处理。

## 当前改造阶段

当前重构按以下主线推进：

1. 修复构建、Kafka、索引状态、安全和 Flyway 等 P0 问题；
2. 统一可靠 Outbox，按场景使用 Claim/Fencing、版本化幂等或 Receipt；
3. 建立长任务 Execution，普通 QA 保持轻量 AnswerRun；
4. 统一 QA/Note/Wiki Answer Pipeline；
5. 接入隔离线程池、真实 SSE、Redis Streams、限流和热点缓存；
6. 重构 Source/Search、Research、Artifact、Memory 和前端模块。

详细顺序以改造实施方案为准。施工默认要求：

- `TDD` 优先
- 能迁移就迁移
- 先打通纵向闭环，不先横向铺满

## 项目结构

- `docs`：正式文档中心、目标设计、子系统设计与验证证据
- `改造计划`：迁移中的施工方案和待归档历史输入，后续将并入 `docs`
- `reference`：参考源码与研究资料
- `backend`：Java 主系统
- `frontend`：React 前端

## Docker 全容器启动

开发、集成测试和演示的标准运行方式：

```powershell
Copy-Item .env.example .env
docker compose --profile app up --build -d
docker compose --profile app ps
```

启动完成后访问 `http://localhost:3000`。Frontend 由 Nginx 托管，并将同源 `/api` 请求代理到 Backend；Backend 在容器网络中使用 `mysql`、`kafka`、`redis`、`minio` 和 `elasticsearch` 等 service name。

不带 `--profile app` 的 `docker compose up -d` 只启动基础设施。当前 Compose 用于本机开发/演示，不等同于生产部署；生产差异见 [Docker 全容器运行与交付基线](D:/java-projects/NoteWeave-v2/改造计划/主项目重构/阶段0A-Docker全容器运行与交付基线.md)。

## 历史设计输入

以下文档只保留为早期设计来源，不再覆盖当前目标架构：

1. `改造计划/三模式知识工作台重构设计.md`
2. `改造计划/深度研究智能体功能与架构设计.md`
3. `改造计划/受控式异步产物生成智能体架构设计.md`
