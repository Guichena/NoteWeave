# NoteWeave 简历定稿与面试材料地图

本文只服务面试准备。上半部分锁定当前简历正文；下半部分按 `D:\java-projects\notes` 的编简历方法，把仓库里已有文档映射到每一条贡献上。不改源码，也不改 `docs/简历亮点八股` 的文件结构。

证据标签仍以 [文档中心](./README.md) 为准。简历数字在 Gold 跑完并写入 Manifest 之前，一律不填百分比。

---

## 1. 从 notes 蒸馏出的写法

来源：`整理后的专题文档/01-编简历思路.md`、`06-第四周-AI项目.md`、`07-答疑-Agent优势与AI面试难点.md`、`18-AI-Agent-Plan与Replan.md`、`19-AI项目面试表达.md`。

| 原则 | 用在 NoteWeave 上 |
| --- | --- |
| 先定人设，再堆经历 | 人设是「Java 后端 + AI 工程落地」：权限、状态、提交权在控制面；模型只出候选。不是「会调大模型」。 |
| 正确姿势：岗位要什么 => 怎么写 | 大厂后端/AI 工程看：一致性、可靠投递、限流隔离、可恢复长任务、检索质量、失败可解释。五条贡献分别对应这些。 |
| 针对问题写方法，不要功能说明书 | 每条先写「针对什么会坏」，再写方法名。面试官能顺着问 Outbox、RRF、Fencing、Verifier。 |
| 结构学社招，深度匹配自己 | 用「针对 / 设计 / 构建」；不写公司级架构、线上 QPS、用户规模。校园交付 + 个人改造要能分开讲。 |
| 技术关键词必须可追问 | `Table-as-State`、BM25、RRF、Outbox、Skill Graph、MCP、Topic-aware Window 都要能落到源码或专题文档。 |
| AI 项目不是接 API | 要能讲：检索为何不准、上下文为何不能全塞、Plan 为何要 Checkpoint/Replan、工具失败怎么办、效果怎么评。 |
| 数字要有分母 | 仓库 4/6 条 Fixture 只证明回归契约。未跑校园 Gold 前，简历不写 100%、128MB 吞吐、采纳率。 |
| 排查四段 | 现象 -> 定位 -> 原因 -> 解决。适合讲：接口成功但搜不到、引用不支撑、Worker 晚到、记忆污染。 |

口述节奏（notes + 现有八股一致）：

```text
30 秒：针对什么问题，最核心的方法叫什么
2 分钟：一条链路 + 两个机制
4 到 5 分钟：原理、实现、失败窗口、评测边界
继续追问：替代方案、Trade-off、测试证据
```

---

## 2. 简历定稿

**NoteWeave｜AI 资料研究工作台**

**2026.01 – 2026.05**

**核心技术：** `SpringBoot` `MySQL` `Redis` `Kafka` `Elasticsearch` `Skill Graph` `MCP`

**项目描述：** 面向校园资料研究与知识沉淀构建来源驱动工作台，覆盖多类型资料接入、QA/Note/Wiki 分场景知识链路、Deep Research、Skill 产物生成与长期记忆；Java 控制面持有权限与提交权，Python Agent Runtime 按冻结输入执行检索、研究与生成长任务。

**核心职责与贡献：**

1. **设计验证驱动的 Deep Research Agent：** 针对开放研究易漂移、中断不可恢复、引用不支撑结论的问题，采用 `Verification-Centric Research`，以 `Table-as-State` 将问题收成有界矩阵，统一组织实体、字段、Evidence 与结论。主服务保存权威状态，Worker 只提交候选；通过 Plan/Replan、Local/Global Verifier 和受限反证形成纠偏闭环，并配合逐轮 Checkpoint、Lease/Fencing 与引用审计。研究完成以证据门禁为准，证据不足明确失败。

2. **构建任务感知的 QA / Note / Wiki 知识链路：** 针对问答、深读、知识沉淀对「相关」定义不同、一套向量 TopK 效果不稳的问题，三条链路共享 Source 版本、Workspace ACL 与 Evidence 契约，按场景拆检索单元和写回语义。QA 融合 BM25、Vector 与 Weighted RRF，再经 Rerank 和 Evidence Selection 给出可拒答带引用回答；Note 先做资料级召回，再按 Reading Window 连续精读并审阅写回；Wiki 以不可变 Page Version 维护当前页，链接、反链和来源回链作为可重建投影。检索命中读时回库校验权限与当前版本；Scope Violation 作为硬门槛。

3. **搭建高可靠的事件驱动资料底座：** 针对大文件同步解析占住请求、业务库与搜索索引无法原子更新的问题，基于 MySQL、MinIO、Kafka 与 Elasticsearch 将原文件、业务状态和检索投影分离。解析、切片、Embedding 与索引写入走异步任务；同一数据库事务写入业务变更与 Transactional Outbox，消费端凭幂等键、版本栅栏、退避重试和死信重驱保证最终一致。删除先提交否定事实再扇出，投影可从真源重建。

4. **构建工程化 Agent 执行框架：** 针对一次 Prompt 生成结构漂移、工具越权、失败后全量重做的问题，将报告、测验、学习指南抽象为 `Skill Catalog`，以 Schema 驱动的 `Skill Graph` 编译获取、生成、校验与修复路径。Java Host 冻结输入并独占版本提交，Python Worker 只返回 Candidate，经 Verifier 后由 Host 落版本。通过内置 MCP 接入白名单资料与工具；结合 Redis 令牌桶、并发租约与任务状态持久化，实现 Workspace 级限流、资源隔离和故障恢复。

5. **设计面向长任务的 Context Engineering 与 Memory 体系：** 针对长对话全量回放导致 Token 膨胀、自动写记忆污染后续事实的问题，采用 Topic-aware Window 与增量摘要，只重算受新增消息影响的片段，并在任务启动时冻结可见资料与记忆版本。通过候选提取、门控晋升和版本化管理沉淀长期 Memory，将短期会话、用户记忆与资料证据分层隔离，避免偏好进入引用和事实链。

时间、校园交付范围、个人改造边界以 [项目事实卡](./简历亮点八股/08-项目事实卡与Ownership面试口径.md) 本人填写为准。v2 仓库连续建设从 2026-05 起，口头要把校园阶段和改造阶段分开。

---

## 3. 五条贡献怎么背、读哪篇

每条只服务简历上的那句话。先一体化手册，再专题设计，八股旧专项当追问附件。

### 3.1 Deep Research

| 项 | 内容 |
| --- | --- |
| 30 秒 | 开放研究不能靠写长文。矩阵收问题，主服务管状态，Worker 只交候选；完成看证据门禁。 |
| 关键词 | Table-as-State、Plan/Replan、Local/Global Verifier、Checkpoint、Lease/Fencing、NO_SUPPORTED_CANDIDATE |
| 主入口 | [30-Research 一体化手册](./简历亮点八股/30-Research-Agent一体化面试手册.md) |
| 设计专题 | [Deep Research 架构](./DeepResearch-ResearchAgent架构文档.md) |
| 取舍依据 | [Research-Agent 外部依据](./research/Research-Agent演进取舍外部依据.md) |
| 追问附件 | `01` 面试题、`16` 演进取舍、`17` 案例、`25` 契约 |
| notes 对照 | Plan 不是固定 workflow；执行中要 Checkpoint / Recovery / Replan（notes `18`） |
| 先不写进简历 | 未跑的 Cell 完成率、Citation Support、恢复次数 |

### 3.2 QA / Note / Wiki（不要说成「一套 RAG」）

| 项 | 内容 |
| --- | --- |
| 30 秒 | 「相关」是任务语义。三条链路共享资料和权限，检索单元和写回方式不同。 |
| 关键词 | BM25、Vector、Weighted RRF、Rerank、Evidence Selection、Reading Window、Page Version、读时回库、Scope Violation |
| 主入口 | [31-场景化知识链路一体化手册](./简历亮点八股/31-场景化RAG一体化面试手册.md)（手册文件名仍带 RAG，口述用知识链路） |
| 设计专题 | [QA](./问答RAG链路设计.md)、[Note](./Note链路设计.md)、[Wiki](./Wiki模式设计.md) |
| 取舍依据 | [RAG 外部依据](./research/RAG演进取舍外部依据.md) |
| 追问附件 | `02` 面试题、`18`/`19` 演进与案例、`26` 契约 |
| notes 对照 | 不是一个 index；ES 关键词 + 向量双路，RRF 融合，再用 rerank（notes `19`） |
| 先不写进简历 | 6 条 Fixture 的 33.3%/100%；跑完 48 条校园 Gold 再填 Recall@5 与 Δ |

### 3.3 资料底座

| 项 | 内容 |
| --- | --- |
| 30 秒 | 索引不是事实。库、对象、投影分开；同一事务写 Outbox，删除先记否定事实。 |
| 关键词 | MinIO、Transactional Outbox、幂等、版本栅栏、死信重驱、Tombstone、可重建投影 |
| 主入口 | [33-资料底座一体化手册](./简历亮点八股/33-资料底座与异步投递一体化面试手册.md) |
| 设计专题 | [资料基础设施](./资料基础设施详细设计.md)、[API 与事件契约](./API与事件契约-v2.md) |
| 取舍依据 | [资料底座外部依据](./research/资料底座演进取舍外部依据.md) |
| 追问附件 | `03` 面试题、`20` 案例 |
| notes 对照 | MySQL 与 ES 最终一致：先写库再异步索引，失败重试/死信/补偿（notes `01` 数据一致性） |
| 先不写进简历 | 128MB 当吞吐；Ready P95 等 30 个文件跑完再填 |

### 3.4 Agent / Artifact / Skill / MCP

| 项 | 内容 |
| --- | --- |
| 30 秒 | 不确定的模型决策收进确定契约。Host 交版本，Worker 只出候选；MCP 只走白名单。 |
| 关键词 | Skill Catalog、Skill Graph、Schema、Verifier、Java Host、Python Worker、MCP、令牌桶、并发租约 |
| 主入口 | [32-Agent 与 Artifact 一体化手册](./简历亮点八股/32-Agent执行与Artifact产物一体化面试手册.md) |
| 设计专题 | [Artifact Skill 执行架构](./Artifact-Skill执行架构.md) |
| 取舍依据 | [Agent 执行框架外部依据](./research/Agent执行框架演进取舍外部依据.md) |
| 追问附件 | `04` 面试题、`21`/`23`/`24` 案例与契约 |
| notes 对照 | Agent 要规划、工具、失败处理、评估；不是 workflow 配一配（notes `06`/`07`） |
| 面试必能拆 | 生成成功 ≠ 回调成功 ≠ 版本提交成功 |

### 3.5 Context / Memory

| 项 | 内容 |
| --- | --- |
| 30 秒 | 上下文不是越多越好。当次冻结输入；记忆要审核才能进后续任务，且不能当引用。 |
| 关键词 | Topic-aware Window、增量摘要、输入冻结、候选、门控晋升、版本、信任域 |
| 主入口 | [34-Context 与 Memory 一体化手册](./简历亮点八股/34-Context与Memory一体化面试手册.md) |
| 设计专题 | [Memory 机制](./Memory机制详细设计.md) |
| 取舍依据 | [Memory 业界演化](./research/Memory业界演化研究.md) |
| 追问附件 | `05` 面试题、`22` 案例、`27` SSE/恢复 |
| notes 对照 | 压缩、过滤、RAG 结果与历史对话分开，画像/权限进系统上下文（notes `01` 上下文工程） |

---

## 4. 现有文档怎么为这份简历服务

不新造一套目录。权威设计仍在 `docs/` 专题；背诵仍从八股 `00` 进。整理原则：

```text
简历五条
  -> 八股 30–34 一体化手册（主背）
  -> 对应设计专题（方法与边界）
  -> 08 事实卡 + 09 故障 + 14/15 基础与参数（横切追问）
  -> research 外部依据（「为什么这样选」，不是项目成绩）
```

| 现有材料 | 对这份简历的作用 | 以后整理时注意 |
| --- | --- | --- |
| `00-总索引`、`00-演进主线` | 总开场 4–5 分钟 | 开场对齐「来源驱动 + 五条贡献」，不要从中间件清单起讲 |
| `30`–`34` | 五条主背 | 口述标题跟简历走：第 2 条说知识链路，不说「一套 RAG」 |
| `01`–`05`、`16`–`26` | 深挖附件 | 只在追问时打开，避免和一体化手册重复背两遍架构 |
| 架构 / 演化 / API 契约 | 状态、路径、Outbox/Lease 真源 | 参数表只认一处；面试数字认 `15` 和评测文档 |
| QA/Note/Wiki/Research/Artifact/Memory 专题 | 方法细节 | 简历不出现的内部词（如「账本」）口述可换成「权威状态 / 主服务状态」 |
| `08` 事实卡 | 时间、Ownership、校园 vs 改造 | 简历时间与 Git 不一致时，口头先划边界 |
| `07`/`29` 指标、评测报告 | 分母和标签 | 演练表里的 91.7%、63% 节时未跑之前当题库，不当简历 |
| `docs/research` | 论文/产品依据 | 只支撑「为什么选 RRF / Outbox / 不选 LangGraph」 |
| 前端视觉、Provider 清单、fixtures | 不进面试主线 | 保持现状即可 |

建议的准备顺序：

1. 把第 2 节简历能不看稿讲一遍。
2. 每条贡献练 30 秒 + 两个机制。
3. 用 `08` 把校园交付和个人改造讲圆。
4. 抽 `09`、`14`、`15` 做随机跳转：Kafka 至少一次、Redis 令牌桶、Hikari 不是 QPS。
5. Gold 跑完后，只把第 1–3 条末尾补样本 N 和绝对 Δ，并同时改评测 Manifest，不改这篇的方法表述。

---

## 5. 口述禁用与可替换

| 简历/八股里可能出现 | 面试怎么说 |
| --- | --- |
| Canonical Ledger / 账本 | 主服务保存权威状态，Worker 不能直接改结论 |
| 场景化 RAG（第 2 条标题） | QA / Note / Wiki 三条知识链路 |
| 128 MB | 上传接纳上限，不是吞吐 |
| 6 条 Gold 100% | 回归 Fixture，不是线上准确率 |
| 水平扩展（尚未压测） | Workspace 级限流和隔离；扩容条件另说 |
| MCP | 白名单内的工具协议，不是开放任意插件 |

校园背景一句话（可按事实卡改）：

> 先参加校园 Agent 平台的资料问答交付，再把 Workspace 隔离、资料接入、分场景检索和长任务能力抽出来做工程化改造。
