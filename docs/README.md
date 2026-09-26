# NoteWeave v2 文档中心

NoteWeave v2 是一个来源驱动的研究与知识工作台。系统把 Source 的不可变版本转成可重建检索投影，供 QA、Note、Wiki 和 Deep Research 使用；Research 与 Artifact 的结果经过版本化、验证和写回后，可以产生新的 Source 或 Memory 候选，最终进入后续运行上下文。

```text
Workspace / ACL
  -> Source / immutable Snapshot
  -> Parse / Chunk / Window / Retrieval Projection
  -> QA / Note / Wiki
  -> Deep Research
  -> Artifact
  -> Memory Candidate / Review / Revision
  -> Control Pack / later Run
  -> Feedback / Revocation / Knowledge Governance
```

MySQL 保存业务真源、运行状态、证据关系和审计账本。Elasticsearch、向量索引、Redis 缓存、SSE 流和 Memory 编译结果都属于投影或传输面，丢失后应能从真源重建，不能反向覆盖业务事实。

## 证据标签

所有长期文档使用同一套标签。段落没有标签时，只表示解释或导航，不应据此推断实现状态。

| 标签 | 含义 | 可以据此声称什么 |
| --- | --- | --- |
| `[当前实现]` | 当前源码、配置、迁移或自动化测试可以直接证明 | 仓库中存在该状态、接口或保护机制 |
| `[目标设计]` | 面向生产环境的完整设计 | 设计可实施，不代表已部署或达到指标 |
| `[已测-模拟]` | 固定数据回放、Provider Mock、模拟压测或故障注入结果 | 只对给定版本、样本和环境成立 |
| `[行业参考]` | 官方文档、标准、论文或开源项目提供的设计依据 | 支持取舍合理性，不证明 NoteWeave 效果 |
| `[生产待验证]` | 需要真实用户、真实 Provider、持续流量或灾备演练 | 上线门禁或后续验证项 |

测试为零失败时，只记录“本次 N 轮零失败”以及执行环境，不写“风险为零”。目标值、参考值和模拟值必须与生产值分栏。

## 权威文档与内容归属

相同概念只在一份文档维护完整解释。其他文档只保留模块差异和链接。

| 权威主题 | 文档 | 不在其他文档重复的内容 |
| --- | --- | --- |
| 领域模型、系统拓扑、Workspace 隔离、全链路 | [系统架构设计](./系统架构设计.md) | 真源与投影、统一对象、发布版本包、跨能力主线 |
| 演化、Bad Case、技术取舍 | [系统设计演化与技术取舍](./系统设计演化与技术取舍.md) | 每条能力从最小方案到目标方案的推导、替代方案和迁移条件 |
| 数据、状态、事件、异步可靠性、可观测性、灾备、安全 | [API 与事件契约](./API与事件契约-v2.md) | Outbox、Kafka ACK、Callback、Lease、Fencing、Checkpoint、SLO/RTO/RPO |
| Source 生命周期 | [资料基础设施](./资料基础设施详细设计.md) | 上传、解析、切片、版本、索引、删除传播与重建 |
| QA | [QA RAG 链路](./问答RAG链路设计.md) | 场景检索、Evidence、引用、拒答和质量门禁 |
| Note | [Note 链路](./Note链路设计.md) | Source 定位、Reading Window、草稿审阅和写回 |
| Wiki | [Wiki 链路](./Wiki模式设计.md) | 页面版本、链接、反链、来源回链和治理状态 |
| Deep Research | [Research Agent 架构](./DeepResearch-ResearchAgent架构文档.md)、[简历能力落地执行计划](./DeepResearch-简历能力落地执行计划.md) | 研究矩阵、证据验证、角色、恢复、冲突和成文；执行计划只维护当前简历主张的任务、门禁与证据 |
| Artifact | [Artifact Skill 执行架构](./Artifact-Skill执行架构.md) | Skill Catalog、Skill Graph、Verifier、Version、文件与写回。面试从 [Agent 与 Artifact 一体化手册](./简历亮点八股/32-Agent执行与Artifact产物一体化面试手册.md) 进入，案例与契约下钻见 23、24 |
| Memory | [Memory 机制](./Memory机制详细设计.md) | 候选、审核、不可变版本、撤销、反馈和上下文编译 |
| 评测与发布门禁 | [质量、测试与发布门禁](./测试与评测/NoteWeave-评测指标报告.md) | 指标字典、数据集、统计方法、测试层次、灰度和回滚 |
| 指标宣传与行业对照 | [指标可宣传性审计与补充](./research/指标可宣传性审计与补充.md) | 论文/产品公开口径、当前数字审计、简历表达和补数计划 |
| 外部依据 | [Research Agent](./research/Research-Agent演进取舍外部依据.md)、[场景化 RAG](./research/RAG演进取舍外部依据.md)、[资料底座](./research/资料底座演进取舍外部依据.md)、[Agent 执行框架](./research/Agent执行框架演进取舍外部依据.md)、[Memory](./research/Memory业界演化研究.md) | 论文与官方文档取舍；产品/框架对照和项目分析在 `docs/research`，不进入实现事实或运维主线 |
| 面试串讲 | [面试资料总索引](./简历亮点八股/00-总索引与背诵方法.md)、[全项目演进主线](./简历亮点八股/00-系统演进与面试叙事主线.md) | 从学校原型到当前架构的 4 到 5 分钟总回答、经历卡和连续追问；五个亮点先从一体化手册进入，再按附件下钻 |
| 一体化功能手册 | [Research](./简历亮点八股/30-Research-Agent一体化面试手册.md)、[场景化 RAG](./简历亮点八股/31-场景化RAG一体化面试手册.md)、[Agent 与 Artifact](./简历亮点八股/32-Agent执行与Artifact产物一体化面试手册.md)、[资料底座](./简历亮点八股/33-资料底座与异步投递一体化面试手册.md)、[Context 与 Memory](./简历亮点八股/34-Context与Memory一体化面试手册.md) | 将同一功能的架构、演进、案例、契约、指标和追问合并为一个面试入口，旧专项作为深挖附件 |
| 横切运行手册 | [Task 与 Quota](./简历亮点八股/38-Task调度-Quota与运行时治理一体化面试手册.md)、[前端流式状态](./简历亮点八股/39-前端工作台-流式状态与恢复一体化面试手册.md)、[检索投影发布](./简历亮点八股/40-检索投影治理-ReleaseGate与Inspector一体化面试手册.md)、[可观测性与 SLO](./简历亮点八股/41-可观测性-SLO与故障定位一体化面试手册.md)、[容量规划与成本治理](./简历亮点八股/42-容量规划-性能压测与成本治理一体化面试手册.md) | 补齐任务接纳、浏览器状态、索引发布、故障定位和容量成本五条跨功能主线，并标注源码闭环、演练假设和生产待验证边界 |
| 推荐架构裁决 | [面试推荐架构与规模化演进](./简历亮点八股/43-面试推荐架构与规模化演进裁决.md) | 统一 Research、Artifact、Memory、Quota、检索和 SSE 的推荐终态、简化路径、升级条件与迁移边界；真实能力仍以源码和事实标签为准 |
| 面试契约下钻 | [Research 契约](./简历亮点八股/25-Research-Agent契约级数据模型与面试官下钻.md)、[场景化 RAG 契约](./简历亮点八股/26-场景化RAG契约级数据模型与面试官下钻.md)、[Conversation/SSE](./简历亮点八股/27-Conversation-AnswerRun-SSE与上下文恢复.md)、[认证与 Workspace](./简历亮点八股/28-认证-Workspace权限与多租户隔离.md) | 按接口、表、状态、失败窗口和多租户边界继续追问；安全篇另含完整主回答、撤权事件和独立二阶下钻 |
| 简历数字生产 | [业务指标埋点与简历数字生成手册](./简历亮点八股/29-业务指标埋点与简历数字生成手册.md) | 指标公式、SQL/事件、Gold Set、实验 Manifest、论文与产品宣传口径、演练数据卡、完整答辩和简历边界 |
| 重点知识深挖 | [一级知识点深挖](./简历亮点八股/35-重点知识点三分钟深挖库.md)、[二阶追问回答](./简历亮点八股/36-重点知识点二阶追问回答库.md) | 核心机制逐点准备完整回答，继续覆盖替代方案、故障窗口、参数、扩容和对应八股；简单 CRUD 只做速查 |
| 面试文档审查 | [全项目审查矩阵](./简历亮点八股/37-全项目面试文档审查矩阵.md) | 将全部面试材料分成主背、重点深挖和速查三档，并明确源码事实、契约语义和推荐架构的权威顺序 |

数据库表、HTTP 路径和事件字段在 [API 与事件契约](./API与事件契约-v2.md) 维护；Flyway 文件是最终 Schema 证据。截至 2026-09-16，仓库最高迁移为 `V104__add_research_agent_completion_replay_observation.sql`，环境实际版本仍要读取 `flyway_schema_history`。

## 阅读顺序

1. 先读根目录 [README](../README.md) 和 [领域术语](../CONTEXT.md)，确认运行方式与稳定语言。
2. 读 [系统架构设计](./系统架构设计.md)，沿 Source 到 Memory 的主线建立全景。
3. 读 [系统设计演化与技术取舍](./系统设计演化与技术取舍.md)，理解为什么拆分状态机、投影和执行面。
4. 读 [API 与事件契约](./API与事件契约-v2.md)，掌握状态推进、结果未知、恢复与跨组件一致性。
5. 按实际问题进入 Source、QA、Note、Wiki、Research、Artifact 或 Memory 专题。
6. 准备面试时先读对应的一体化功能手册，再进入旧专项附件下钻源码、算法和案例。
7. 用 [质量、测试与发布门禁](./测试与评测/NoteWeave-评测指标报告.md) 核对指标、证据等级和上线条件。
8. 最后用指标生产手册把简历数字绑定到数据来源和实验 Manifest，不从面试稿反推当前事实。

## 一次请求如何贯穿系统

用户上传资料并在 Workspace 中提问时，Java 主服务先校验身份、成员关系和资源归属，再创建上传记录和对象引用。解析任务生成不可变 Source Snapshot、Chunk 与 Window，检索投影异步收敛。用户提交问题后，Turn Ledger、消息、输入快照和 AnswerRun 在 MySQL 中形成可恢复运行；检索层只查询当前 Workspace 允许的版本，回答层固化 Evidence Manifest。SSE 断线后按数据库游标回放，不能依赖进程内连接补状态。

Research 在同一资料范围上创建独立 Research Run、类型化 WorkItem、Evidence、Checkpoint 和预算账本；比较型任务再把 WorkItem 具体化为 Matrix Cell。Artifact 使用独立 Job、Run、Execution Attempt、Skill Graph、Version 与校验状态。两者共享 Task Interface、Outbox、Execution Lease 抽象、Fencing 校验模式和 Callback Receipt 等可靠性原语，但不共享一张任务表或一套业务状态枚举；只有拥有领域结果提交权的 Execution Lease 使用 Epoch 和 Fencing。Memory 只接收受控候选，审核通过后产生不可变 Revision，再按运行类型编译 Control Pack。

Source 更新、删除、权限变化或事实撤销时，MySQL 先提交新版本或撤销事实，再通过 Outbox 驱动全文索引、向量索引、Redis 缓存、Wiki 关系、Memory 编译结果、评测副本和派生产物失效。用户可见删除与物理清理分开；高风险读取在权限或策略服务不可用时关闭访问。

## 当前事实基线

`[当前实现]` Java 17 / Spring Boot 3.3.5 主服务持有 MySQL 8.4 业务状态，React 19 / TypeScript / Vite 提供工作台，Research Worker 与 Artifact Worker 使用 Python 3.12。Redis 7.2 用于缓存、节流和短期协调，Kafka 承载异步命令，MinIO 保存对象，Elasticsearch 8.15.3 提供可选检索投影。

`[当前实现]` Workspace、Source、QA、Note、Wiki、Research、Artifact、Memory、Task、Outbox、回调回执、运行输入快照与 SSE 回放均有源码或迁移落点。Research 的 `V097-V104` 能力包含证据校验、意图矩阵、运行阶段、Checkpoint Hydration、角色结果、Discovery Proposal、Feature Flag Snapshot 和 Completion Replay Observation；其中多项默认关闭或以 Shadow 方式运行，不能写成默认生产路径。

`[生产待验证]` 仓库没有持续生产流量、真实用户效果、长期 Provider 成本、跨可用区容灾或真实 SLO 达成证据。Compose 是本地联调拓扑，不代表生产部署。

## 文档维护规则

- 事实变更先更新权威文档，再更新面试摘要；禁止在多篇文档维护同一状态表或参数表。
- 版本、参数和指标必须带来源。当前配置写 `[当前实现]`，模拟结果写 `[已测-模拟]`，建议阈值写 `[目标设计]`。
- 外部产品、论文和开源项目只进入 `docs/research` 或专题末尾的依据表，不作为 NoteWeave 运行证据。
- 领域文档必须包含业务问题、非目标、用户产物、演化、Bad Case、状态真源、正常/异常/恢复路径、幂等并发、参数推导、替代方案、指标、测试、灰度和下一阶段条件。
- 受保护目录 `docs/简历亮点八股` 默认不新增、删除、移动或重命名文件；只有经过明确确认的独立面试专项可以新增，并在总索引登记。正文优先链接权威文档，避免复制长段架构说明。
- 新增长期文档前先检查本页的权威归属。阶段方案、验收日志、一次性修复记录和 Debug 路由应合并回专题，不长期并列。
