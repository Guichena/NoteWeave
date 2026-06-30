﻿# NoteWeave v2

NoteWeave v2 是一次面向统一知识工作台的全新重构。

这个项目不再延续旧版以 `team` 与 `personal` 为核心的产品拆分方式，而是把“用户工作区”作为第一边界，并围绕以下结构重新设计系统：

- 3 个用户可见工作模式：`问答`、`Wiki`、`资料调查`
- 1 个轻量知识沉淀层：`Note`
- 1 个全局重型任务入口：`深度研究`
- 1 个受控异步执行平面：`Artifact Runtime`
- 1 套门控式长期记忆机制：`Graduated Memory`
- 1 套贯穿全链路的 `Evidence / Citation / Version` 可追溯能力

当前统一后的项目亮点也以这套架构为准：

1. 基于 `Evidence-Gated Branchable Research Harness` 的 Deep Research 智能体
2. 面向右侧产物栏、采用 `Schema-Gated Skill Graph Runtime` 的受控式异步产物生成 Agent
3. `RAG / Wiki / Note` 一体化知识工作流
4. 基于 `Graduated Memory` 的门控式长期知识晋升机制
5. 基于文件上传、Kafka、MinIO 的资料处理底座

## 当前阶段

项目目前处于“架构优先”阶段。

在正式编写业务代码之前，v2 会先明确以下基础设计：

1. 系统架构
2. 统一领域模型
3. 能力边界与建设路线
4. 项目核心亮点
5. 可用于简历表达的项目定位
6. 技术栈与数据库设计

## 文档阅读顺序

建议按以下顺序阅读：

1. `docs/系统架构设计.md`
2. `docs/名词口径与汇报词表.md`
3. `docs/统一领域模型.md`
4. `docs/能力边界与建设路线图.md`
5. `docs/产品详细设计.md`
6. `docs/技术架构详细设计.md`
7. `docs/后端模块与中间件落地设计.md`
8. `docs/深度研究智能体编排升级设计.md`
9. `docs/受控式异步产物生成Agent编排升级设计.md`
10. `docs/技术栈与数据库设计.md`
11. `docs/项目亮点与简历草案.md`

## 设计源文档优先级

以下三份设计资料被视为 v2 的最高优先级输入：

1. `改造计划/三模式知识工作台重构设计.md`
2. `改造计划/深度研究智能体功能与架构设计.md`
3. `改造计划/受控式异步产物生成智能体架构设计.md`

参考项目仅用于借鉴设计模式，不作为直接照搬的实现来源：

- `reference/WeKnora`
- `reference/Marco-DeepResearch`
- `reference/A-mem`
- `reference/PlugMem`
- `reference/sample-amazon-bedrock-agentcore-memory-mcp-server`

## 项目结构

- `docs`：统一后的系统架构、领域模型、能力边界、技术设计和项目亮点文档
- `改造计划`：上游源设计文档与设计推演记录
- `reference`：参考源码仓库与借鉴索引
- `backend`：后端实现目录
- `frontend`：前端实现目录
