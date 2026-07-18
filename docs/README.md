# NoteWeave v2 文档中心

## 主项目重构施工入口（2026-07-12）

主项目（不含 Research/Artifact Worker 内部实现）的最新审查与施工文档统一位于：

- [主项目重构文档索引](../改造计划/主项目重构/README.md)
- [主项目系统差距审查与总体改造方案](../改造计划/主项目重构/主项目系统差距审查与总体改造方案.md)
- [阶段0：正确性、安全与工程基线](../改造计划/主项目重构/阶段0-正确性安全与工程基线.md)
- [阶段0A：Docker 全容器运行与交付基线](../改造计划/主项目重构/阶段0A-Docker全容器运行与交付基线.md)
- [阶段1：Workspace 权限与模块边界](../改造计划/主项目重构/阶段1-Workspace权限与模块边界.md)
- [阶段2：Execution、Outbox 与并发基础设施](../改造计划/主项目重构/阶段2-Execution-Outbox与并发基础设施.md)
- [阶段3：资料上传、解析与索引投影](../改造计划/主项目重构/阶段3-资料上传解析与索引投影.md)
- [阶段4：AnswerRun 与真实流式回答](../改造计划/主项目重构/阶段4-AnswerRun与真实流式回答.md)
- [阶段5：QA、Note、Wiki 统一检索策略](../改造计划/主项目重构/阶段5-QA-Note-Wiki统一检索策略.md)
- [阶段6：Knowledge、Memory 与前端生产化](../改造计划/主项目重构/阶段6-Knowledge-Memory与前端生产化.md)

以上文档是主项目当前的施工入口；目标架构仍以 `docs/NoteWeave-v2目标系统架构与功能中间件设计.md` 为准，历史审查文档仅用于追溯。

> 本文件是项目文档的唯一正式入口。
>
> 文档发生冲突时，按照本文定义的权威等级处理，不再使用“以另一份文档为准”的链式引用。

## 1. 权威等级

| 等级 | 类型 | 作用 |
|---|---|---|
| L0 | 当前代码、Flyway、自动化测试 | 当前已经实现的事实 |
| L1 | 目标架构 | 决定系统最终应该如何设计 |
| L2 | 现行改造方案 | 决定从当前状态如何迁移到目标架构 |
| L3 | 子系统设计 | 描述 QA/Note/Wiki/Research/Artifact/Memory/Source 细节 |
| L4 | API、事件和数据契约 | 约束跨模块与跨进程边界 |
| L5 | 验证与能力证据 | 证明某项能力是否真正完成 |
| L9 | 历史归档 | 只用于追溯，不得直接作为施工依据 |

当 L1/L2 与 L0 不一致时：

- L0 表示当前事实；
- L1/L2 表示目标和改造要求；
- 必须在 Backlog 中记录差距，不能用文档覆盖代码事实。

## 2. 当前必读文档

### 2.1 系统是什么

1. [系统总体设计与功能总表](D:/java-projects/NoteWeave-v2/docs/系统总体设计与功能总表.md)
2. [目标系统架构、功能与中间件设计](D:/java-projects/NoteWeave-v2/docs/NoteWeave-v2目标系统架构与功能中间件设计.md)

### 2.2 当前如何改造

1. [现有系统适配与改造实施方案](D:/java-projects/NoteWeave-v2/改造计划/NoteWeave-v2现有系统适配与改造实施方案.md)
2. [主项目系统设计与中间件全面审计](D:/java-projects/NoteWeave-v2/docs/主项目系统设计与中间件全面审计及全新优化方案.md)

### 2.3 当前技术契约

1. [API 与事件契约 v2](D:/java-projects/NoteWeave-v2/docs/API与事件契约-v2.md)
2. [数据库与迁移规范](D:/java-projects/NoteWeave-v2/docs/数据库与迁移规范.md)

### 2.4 子系统设计

- [问答 RAG 链路设计](D:/java-projects/NoteWeave-v2/docs/问答RAG链路设计.md)
- [Note 链路设计](D:/java-projects/NoteWeave-v2/docs/Note链路设计.md)
- [Wiki 链路设计](D:/java-projects/NoteWeave-v2/docs/Wiki模式设计.md)
- [Memory 机制详细设计](D:/java-projects/NoteWeave-v2/docs/Memory机制详细设计.md)
- [资料基础设施详细设计](D:/java-projects/NoteWeave-v2/docs/资料基础设施详细设计.md)
- [Research Agent 架构文档](D:/java-projects/NoteWeave-v2/docs/DeepResearch-ResearchAgent架构文档.md)
- [Research Agent 多 Agent 架构深度设计与验证实施手册](D:/java-projects/NoteWeave-v2/docs/ResearchAgent-多Agent架构深度设计与验证实施手册.md)
- [产物生成 Skill 优先重构设计](D:/java-projects/NoteWeave-v2/docs/产物生成Skill优先重构设计.md)

这些专项文档目前仍需继续适配统一 Answer Pipeline、Execution、选择性幂等、Redis 和线程池设计。发生冲突时以 L1/L2 文档为准。

## 3. 后端设计模式决策

当前统一决策：

| 能力 | 决策 |
|---|---|
| Outbox | 保留，统一实现；只用于数据库提交后必须可靠投递的跨系统副作用 |
| Inbox | 不建立全局万能表；按场景使用 Claim/Fencing、版本化 upsert、唯一键或 Receipt |
| Execution | 用于 Research、Artifact、Source、重建和异步 Answer；普通 QA 只用 AnswerRun |
| Redis Streams | 用于多实例 SSE 低延迟事件桥，重要状态仍在 MySQL |
| Redis Cache | 用于权限、Catalog 和热点 Projection；Cache-Aside + TTL + 写后失效 |
| Redis Rate Limit | 用于用户、Workspace 和外部 Provider 配额 |
| Redis Lock | 只用于短期协调，不替代 MySQL lease/CAS |
| 线程池 | 按 Answer I/O、Source I/O、Projection、SSE、Maintenance 隔离 |
| Kafka | 用于 Worker 命令和可靠领域事件，不用于普通 QA 和同步查询 |
| Saga 框架 | 当前不引入，使用状态机、Outbox、幂等和补偿 |
| 全量事件溯源 | 当前不采用，保留业务真源表和审计事件 |

## 4. 文档分类

后续统一迁移为以下目录：

```text
docs/
  01-项目介绍/
  02-架构与设计/
  03-施工与改造/
  04-教学与学习/
  90-验证与证据/
  99-历史归档/
```

当前先建立分类和权威关系，不立即批量移动文件，避免破坏脚本和交叉引用。

### 4.1 项目介绍

保留并修改：

- `系统总体设计与功能总表.md`
- `项目亮点与简历草案.md`
- 根目录、Backend、Frontend、Workers README

### 4.2 架构与设计

当前权威：

- `NoteWeave-v2目标系统架构与功能中间件设计.md`
- QA/Note/Wiki/Memory/Source/Research/Artifact 专项设计

已建立替代文档、等待归档：

- `系统架构设计.md`
- `技术架构详细设计.md`
- `技术栈与数据库设计.md`
- `数据库迁移与建表顺序.md` -> `数据库与迁移规范.md`
- `最小接口契约.md` -> `API与事件契约-v2.md`

其中 API 和数据库内容需要先形成新的版本化契约，再归档旧文件。

### 4.3 施工与改造

当前权威：

- `改造计划/NoteWeave-v2现有系统适配与改造实施方案.md`

保留为施工规范：

- `阶段计划/TDD与迁移总则.md`
- `阶段计划/AI开发提示模板.md`

其余阶段文档在完成内容提取后归档。

### 4.4 教学与学习

- `DeepResearch-ResearchAgent面试回答手册.md`
- `DeepResearch-P5C-项目讲解稿.md`
- `深度研究智能体执行手册.md`
- `项目亮点与简历草案.md`

教学材料不能作为实现完成度证据，必须引用 Capability Coverage。

### 4.5 验证与证据

- `ResearchAgent-Capability-Coverage.md`
- `ResearchWorker-Repair-Plan.md`
- `DeepResearch-Gate1样例说明.md`
- `DeepResearch-P5A验证说明.md`
- `DeepResearch-P5B-demo-suite.md/json`
- `DeepResearch-M4-gold-set.json`
- `DeepResearch-P5C-亮点证据映射.json`
- `产物生成Agent二次全面审查与简历验收.md`
- `主项目系统设计与中间件全面审计及全新优化方案.md`

移动这些文件前必须同步修改 `scripts` 中的硬编码路径。

### 4.6 历史归档

计划归档：

- `代码落地路线与开工清单.md`
- 已完成阶段1～阶段5B文档
- `Research与Artifact任务骨架施工设计.md`
- `深度研究智能体编排升级设计.md`
- `深度研究智能体工程落地设计.md`
- `深度研究智能体全量交付执行文档.md`
- `受控式异步产物生成Agent编排升级设计.md`
- `产物生成Agent独立模块施工文档.md`
- `产物生成Agent需求审计与收尾改造.md`
- `改造计划` 中三份早期架构源文档
- `改造计划/主项目检修与改造方案.md`

历史文档顶部必须包含 `ARCHIVED`，且不得被 README 列为开工必读。

## 5. 文档整改顺序

### D0：入口治理

- 建立本文；
- 更新根 README；
- 停止使用旧 `具体设计与落地索引`；
- 给旧入口增加 DEPRECATED/ARCHIVED 标记。

### D1：核心契约

- 已新建 API 与事件契约 v2；
- 已新建数据库与迁移规范；
- 更新 Source/QA/Note/Wiki 专项设计；
- 更新 Redis、线程池、Outbox/幂等决策。

### D2：子系统收敛

- Research 文档收敛为“架构 + 执行手册 + Capability Evidence”；
- Artifact 文档收敛为“架构 + 运行契约 + Capability Evidence”；
- Memory 文档收敛为“模型 + 门控 + Compiler + Outcome”。

### D3：物理迁移

- 创建分类目录；
- 使用 `git mv` 移动文件；
- 批量更新 Markdown 链接；
- 修改脚本硬编码路径；
- 运行文档链接检查和交付脚本。

### D4：删除重复文件

只在归档提交完成后删除完全重复内容。优先删除早期重复 DOCX，不直接删除仍有历史信息的 Markdown。

## 6. 文档状态标记

后续所有正式文档顶部必须包含：

```text
文档状态：CURRENT / DRAFT / EVIDENCE / DEPRECATED / ARCHIVED
权威等级：L1-L9
最后核对日期：YYYY-MM-DD
代码基线：commit/branch（可选）
替代文档：path（DEPRECATED/ARCHIVED 时必填）
```

## 7. 当前下一步

文档治理的下一施工批次：

1. 更新根 README 和 `具体设计与落地索引`；
2. 新建 API 与事件契约 v2；
3. 新建数据库与迁移规范；
4. 更新 Source、QA、Note、Wiki 设计；
5. 收敛 Research 和 Artifact 文档；
6. 最后执行目录移动和链接修复。
