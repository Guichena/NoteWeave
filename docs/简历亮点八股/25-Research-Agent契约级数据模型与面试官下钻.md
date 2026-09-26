# Research Agent 契约级数据模型与面试官下钻

> 默认主回答见[Research Agent 一体化面试手册](30-Research-Agent一体化面试手册.md)。本文只在面试官追问 Matrix/Cell/Candidate、Task Snapshot、Lease/Fencing、Checkpoint、Completion Receipt 和预算账本时使用，不另起一份功能主叙事。

> 证据标签：`[当前实现]` 表示源码、迁移或测试可以证明对象与约束存在；`[已测-模拟]` 表示固定 Fixture 或故障窗口已覆盖；`[演练假设]` 只用于解释数据计算；`[目标设计]` 和 `[生产待验证]` 不能写成现有能力或线上成绩。本文未显式加标签的对象说明属于契约解释，不能自动推导生产效果。

> 定位：这篇不是再讲一遍 Research Agent 的概念，而是回答面试官最容易追穿的四个问题：一次研究到底保存了什么，Agent 为什么能恢复，结果为什么可信，以及 Java 与 Python 之间怎样避免重复写入。演进故事先读[Research Agent 演进与技术取舍](16-Research-Agent演进与技术取舍专项.md)，案例和消融先读[Research Agent 案例与 Ownership](17-Research-Agent真实案例消融实验与Ownership答辩.md)。

## 1. 一句话定位

我没有把 Research Agent 做成一个不断追加聊天消息的循环，而是把开放式研究建模成一组可持久化、可验证、可恢复的研究状态。Java Host 掌握业务真源、预算和提交权，Python Worker 只执行受约束的搜索、阅读与推理，所有结果经过版本、租约、证据和幂等门禁后才能合并。

这句话有三个面试钩子：

1. **状态不是文本历史**：研究目标被拆成 Matrix 和 Cell，进度能按字段判断，而不是靠模型说“完成了”。
2. **执行权不等于提交权**：Worker 可以计算候选结果，但不能绕过 Host 直接改变研究真源。
3. **恢复不是重新提问**：Checkpoint 保存的是结构化账本和高水位，恢复后能识别哪些任务、证据和合并已经生效。

## 2. 项目背景与推荐包装

### 2.1 面试主回答

学校原型最初只是让模型基于课程资料生成一篇回答。问题变复杂后，一次调用会同时暴露三个缺陷：覆盖范围不可控、引用和结论无法逐项核对、任务中断只能全部重跑。我后来把它改造成验证驱动的 Research Agent。

用户先提交研究目标、交付格式、时间范围、深度和资料范围。系统把目标编译成研究意图，再规划成二维 Matrix。每个 Cell 表示一个可验收问题，Worker 围绕 Cell 产生 Evidence 和 Candidate，Host 对来源、版本、预算、冲突和提交资格做校验。任务每推进一轮都会形成 Checkpoint，高成本调用前先预留预算，完成后按实际消耗结算。这样做的价值不只是“能写长报告”，而是让一次几十步的研究过程具备可解释进度、失败续跑和结果审计能力。

### 2.2 可用于简历的强口径

> 设计并实现验证驱动的 Deep Research Agent，将开放式研究拆为 Matrix、Cell、Evidence、Candidate 和 Checkpoint 等持久化对象；通过 Lease、Fencing、Expected Version、预算账本和 Completion Receipt 约束跨语言 Worker，在固定研究集上验证断点恢复、重复回调收敛和证据可追溯能力。

不要把“固定研究集”省略成“线上用户”。当前仓库最强的证据是完整机制、迁移和受控回放，不是长期生产流量。

## 3. 用户请求如何进入研究状态

`POST /api/v2/workspaces/{workspaceId}/research-runs` 接收的核心字段如下：

| 字段 | 业务含义 | 为什么不能只塞进 Prompt |
| --- | --- | --- |
| `question` | 原始研究问题 | 保留用户原话，便于重放和评测 |
| `profile` | 研究配置档 | 决定默认预算、深度和执行策略 |
| `researchGoal` | 成功条件 | 将“要回答什么”与“怎样算完成”分开 |
| `deliverableFormat` | 报告形式 | 约束最终产物，不污染证据发现阶段 |
| `constraints` | 禁止项和范围限制 | 可由 Host 做确定性校验 |
| `timeRange` | 时间边界 | 用于资料过滤和过期判断 |
| `depth` | 研究深度 | 映射轮次、Cell 和预算上限 |
| `researchType` | 调研类型 | 区分比较、综述、决策支持等策略 |
| `sourceScopeSourceIds` | Workspace 资料范围 | 防止跨资料、跨租户召回 |
| `retrievalMode` | 获取模式 | 区分仅资料、仅外部、混合获取 |
| `seedSourceIds` | 种子来源 | 为需要种子的策略提供受控起点 |

关键设计是 Request 只表达用户意图，不直接携带 Worker 可执行指令。Host 还要编译 Research Brief、冻结来源范围和 Feature Flag Snapshot，避免同一个 Run 在执行中因配置变化而漂移。

## 4. 八类核心对象

```text
ResearchRun
  -> ResearchIntent / Brief / FeatureFlagSnapshot
  -> ResearchPlan(planRevision)
       -> WorkItem(type, expectedVersion, acceptanceContract)
            -> MATRIX_CELL(row, column, coverageState)
            -> CLAIM_INVESTIGATION / OPEN_QUESTION
            -> TIMELINE_EVENT / SOURCE_AUDIT
            -> Task(snapshot, leaseEpoch, fencingToken)
                 -> Evidence(source identity, locator, qualification)
                 -> Candidate(baseWorkItemVersion, evidenceRefs)
                      -> Merge(decision, resultingWorkItemVersion)
  -> BudgetReservation / Ledger
  -> Checkpoint(high-water marks, canonical digest)
  -> Report / Collection / Source write-back
```

| 对象 | 职责 | 关键不变量 |
| --- | --- | --- |
| `ResearchRun` | 一次稳定的用户研究意图 | Workspace、目标和资料范围不可被 Worker 偷换 |
| `ExecutionAttempt` | 一次执行、恢复或接管代际 | 基础设施恢复不改变 Run 身份，Epoch 单调推进 |
| `ResearchPlan` | 版本化工作计划 | Planner 修订保存 WorkItem Diff |
| `WorkItem` | Matrix Cell、Claim、Question、Timeline 或 Source Audit | 只有基于当前版本的候选才能合并 |
| `Task` | 一次可领取执行 | 同一时刻只有当前租约持有者可提交 |
| `Evidence` | 支撑或反驳结论的证据 | 必须保留来源身份、定位信息和验证状态 |
| `Candidate` | Worker 提交的候选更新 | 候选不等于事实，需经过 Host 仲裁 |
| `Checkpoint` | 可恢复的研究账本切面 | 快照摘要、高水位和内容哈希必须匹配 |
| `Report/Artifact` | 面向用户的成文结果 | 引用只能指向已接纳的 Evidence |

### 4.1 为什么统一 WorkItem，而不是统一成 Matrix

Todo List 只能表达做过哪些动作，不能说明每个工作单元如何验收。Matrix 能把比较型任务的覆盖率变成结构化状态，但开放调查、因果问题和时间线不应被强行表格化。推荐合同统一 `work_item_id/type/version/status/evidence_refs`，再按类型定义验收：`MATRIX_CELL` 看字段覆盖，`CLAIM_INVESTIGATION` 看支持或反驳，`TIMELINE_EVENT` 看时间与顺序。简单任务可以只有一个 Claim 或 Question。

`OPEN_QUESTION` 不能只保存“后续还可以继续查”。创建时必须绑定最大时间、成本或 Token、最大分支数、最小新增证据收益和允许终态。达到边界后只能收敛为 `COMPLETED`、`ABSTAINED` 或 `INSUFFICIENT`，并保存停止理由；否则 Typed WorkItem 只是把无界 ReAct 换了一个对象名。

### 4.2 为什么 Candidate 不能直接覆盖 WorkItem

并发 Worker 可能基于同一个 WorkItem 版本得到不同结论。如果后完成者直接覆盖，结果取决于网络时序。Candidate 保存 `baseWorkItemVersion` 和证据引用，Host 用 Expected Version 做条件合并。比较任务可继续使用 `baseCellVersion` 作为具体字段。版本不匹配时进入拒绝、重算或冲突审查，而不是 Last Write Wins。

## 5. Java Host 与 Python Worker 的责任边界

| Java Host | Python Worker |
| --- | --- |
| 身份、Workspace 和资料范围鉴权 | 执行搜索、阅读、抽取和模型推理 |
| Run、Attempt、WorkItem、Evidence 和预算真源 | 消费不可变 Task Snapshot |
| Lease、Heartbeat、Fencing 和状态迁移 | 在租约窗口内返回 Completion Envelope |
| 来源资格、版本、幂等和提交仲裁 | 不直接写业务表，不决定最终合并 |
| Checkpoint、恢复、最终报告和写回 | 对局部失败返回结构化错误 |

这里采用“薄 Worker、厚 Host”不是因为 Python 不可靠，而是为了缩小可信计算边界。模型和外部 Provider 天然存在超时、重复、非确定输出和供应商差异，业务不变量留在事务能力更强、审计入口更集中的 Host 更容易证明。

反过来的代价是协议更多、联调成本更高。若只是单机 Demo，全部放在 Python 中更快；当任务跨分钟、支持重试和多 Worker 后，状态真源与执行进程分离才开始回本。

## 6. Task Snapshot、Lease、Fencing 和 Completion Receipt

### 6.1 Task Snapshot

Worker 领取的不是“去数据库再查一下最新内容”，而是一份冻结执行上下文，至少包含 Run、Attempt、任务身份、WorkItem 类型、Expected Version、Provider、来源策略、本 Task 的预算授权、Run 剩余预算视图和 Schema Version。Run 仍持有总预算账本，Snapshot 不能给 Attempt 复制一份可独立消费的总额度。冻结快照解决两个问题：重试输入可复现，运行中配置变化不会悄悄改变旧任务。

### 6.2 Lease 与 Heartbeat

Claim 将任务从可执行态转为持有态，并记录 `workerInstanceId`、`leaseEpoch`、`fencingToken` 和过期时间。Heartbeat 只能由同一持有者、同一 Epoch 和 Token 续租。进程失联后任务可重新领取，避免永久卡死。

### 6.3 Fencing Token

仅有 Lease 仍不够。旧 Worker 可能在租约过期后恢复网络并晚到提交。单调递增的 Fencing Token 让 Host 能识别它已经不是当前 Owner，即使旧进程仍认为自己成功，也不能覆盖新 Owner 的结果。

### 6.4 Completion Receipt

Completion 不是只返回 `200 OK`。Host 返回结构化 Receipt，记录哪些 Evidence 被接纳、Candidate 是否幂等重放、Merge 为什么成功或拒绝、预算预留怎样结算。Worker 超时后再次提交同一 Completion，Host 可以返回同一语义结果，而不是再写一遍。

推荐回答：

> 幂等键解决“这个业务动作是不是已经处理过”，Fencing 解决“现在这个提交者还有没有资格处理”。两者保护的是不同维度，不能互相替代。

## 7. Checkpoint 与恢复

### 7.1 Checkpoint 保存什么

Checkpoint 不是报告文本备份，而是一个有完整性校验的 Attempt 账本快照，包括 Attempt Epoch、Plan Revision、WorkItem Version、预算视图以及 Task、Candidate、Merge 的高水位。只有真实 Fan-out 才保存 Wave/Barrier。Canonical JSON、内容大小和 SHA-256 用于识别截断、乱序序列化或内容被替换。

### 7.2 Hydrated Resume

恢复时保持 ResearchRun 不变，创建新的 ExecutionAttempt，并从指定 Checkpoint 恢复已确认 WorkItem、Evidence、规划状态和账本锚点，再只调度高水位之后未完成的工作。Attempt 保留 `resumedFromAttemptId` 和 `resumedFromCheckpointNo`。用户改变目标、资料范围或主动 Fork 时才创建新 Run。旧版 `resumedFromResearchRunId` 作为迁移兼容字段，不再代表推荐语义。

### 7.3 为什么不从最后一条日志继续

日志是观测数据，不一定具备事务一致性，也无法证明多张表已经提交到同一语义位置。Checkpoint 是业务协议的一部分，保存经事务确认的状态和摘要。日志可以辅助定位，但不能作为恢复真源。

### 7.4 当前边界

当前已实现 Checkpoint Hydration 和 Completion 级回执。更强的目标态是让每一次外部 Search、Read、Model 调用都有 Operation Receipt，从而在“外部调用成功、进程在落库前退出”的窗口内避免重复付费。目前面试时应说“完成提交可去重，外部副作用级 Exactly Once 仍需要 Provider 幂等键或调用回执补全”。

## 8. 一条学校场景的端到端演练

问题：为教务老师比较三种课程知识库建设方案，要求覆盖检索质量、教师维护成本、学生引用可追溯性和考试周容量风险，并给出推荐意见。

1. Host 冻结课程资料范围，将目标编译为四个评价维度和三个方案实体。
2. Planner 判断它是比较型任务，生成 `3 × 4` Matrix，共 12 个 `MATRIX_CELL` WorkItem。高风险结论要求至少两个独立来源或一条校内事实加一条外部依据。
3. 第一轮任务负责发现和抽取。Worker 只拿到当前 Cell、种子资料和预算，不读取其他 Workspace。
4. Host 校验来源身份、快照版本、定位范围和重复证据，再把结果保存为 Evidence。
5. Candidate 基于 Cell Version 生成。若两个 Worker 同时补同一 Cell，Host 对晚到旧版本返回冲突，不覆盖已合并内容。
6. 阶段结束后保存 Checkpoint。模拟 Worker 退出后，在同一 Run 下创建新 Attempt 并恢复，已完成 Evidence 不重复生成。
7. Verifier 检查 12 个 Cell 的覆盖、关键结论的证据支持、来源独立性和引用闭环。
8. Finalizer 生成比较表、风险段和推荐结论。用户可以查看 Evidence Manifest，也可以将报告保存为新的 Source，进入后续 QA。

这段是可复现演练故事，不应讲成“学校上线后真实处理了多少请求”。真正采集到用户数据后，再将步骤 2 的 Cell 数、恢复节省调用数和人工复核时间替换成带 Manifest 的结果。

## 9. 指标怎样与数据结构对应

| 指标 | 计算方式 | 说明 |
| --- | --- | --- |
| Required WorkItem Completion | `通过类型化验收的必填 WorkItem / 必填 WorkItem` | 全 Research 的计划完成主指标，按任务类型宏平均 |
| Matrix Cell Coverage | `通过 Cell 验收的必填 Cell / 必填 Cell` | 只用于比较型任务，不代表开放调查或时间线质量 |
| Evidence Support Rate | `有合格 Evidence 支撑的关键 Claim / 关键 Claim` | 是否存在无依据陈述 |
| Citation Correctness | 抽样判断引用是否真正蕴含 Claim | 不能只检查链接存在 |
| Source Independence | `独立来源组 / 证据来源数` 或关键 Claim 的独立来源覆盖 | 防止十条转载被误认为十个来源 |
| Conflict Resolution Rate | `已裁决冲突 Cell / 发现冲突 Cell` | 是否隐藏了相互矛盾的材料 |
| Resume Reuse Rate | `恢复后复用的有效步骤 / Checkpoint 前有效步骤` | 断点恢复实际省了多少重算 |
| Duplicate Completion Convergence | 同一幂等键重复提交后业务行增量是否为 0 | 验证回调幂等 |
| Budget Error | `abs(预留 - 实耗) / max(实耗, 1)` | 预算模型是否需要校准 |
| Verified Claim per 1K Tokens | `验证通过 Claim / Token × 1000` | 比单看 Token 成本更接近有效产出 |

指标的统计方法、置信区间和对外口径统一见[指标生产手册](29-业务指标埋点与简历数字生成手册.md)。

## 10. 面试官连续追问

### Q1：这不就是工作流，为什么叫 Agent

Agent 体现在 Planner 会根据缺口、冲突和预算动态决定下一步研究动作，不是固定 DAG；工作流体现在任何动作都必须落到受约束 Task 和状态迁移。我的选择是“决策自适应，执行受治理”，而不是让模型拥有无限工具自由度。

### Q2：为什么不用 LangGraph 或现成 Deep Research 框架

原型阶段可以用现成框架快速验证交互，但项目的难点是 Workspace 权限、Source Snapshot、业务事务、预算、SSE 和写回都在 Java 域中。让框架成为真源会产生双状态机。因此我借鉴图执行、Checkpoint 和 Tool Contract 的思想，保留自有 Host 协议。代价是需要自己维护协议与恢复测试。

### Q3：能保证 Exactly Once 吗

我不承诺端到端 Exactly Once。队列和网络仍然是 At Least Once，系统通过幂等键、唯一约束、条件更新、Fencing 和 Completion Receipt 达到业务效果上的收敛。对没有幂等能力的外部模型调用，只能通过 Operation Receipt、供应商幂等键或结果缓存进一步缩小重复窗口。

### Q4：模型把错误内容写进 Candidate 怎么办

Candidate 本身没有真源资格。Host 会做来源范围、引用定位、Schema、Cell Version 和确定性资格检查；语义正确性则由独立 Verifier、冲突检查和 Gold Set 评测兜底。这里也不能夸大为“消除幻觉”，更准确是让错误更容易被发现、隔离和回放。

### Q5：Checkpoint 会不会非常大

不会无界保存完整上下文。Checkpoint 使用结构化状态、高水位、摘要和内容寻址引用，较大的 Source 内容仍由原始存储和 Snapshot 管理。若规模继续增长，可采用增量 Checkpoint 加周期全量快照，但会增加恢复链验证成本。

### Q6：为什么预算要先 Reserve 再 Settle

如果只在调用后扣费，并发任务会同时看到“还有预算”而超卖。Reserve 在发起高成本动作前占用额度，Settle 按实耗结算并释放差额。它类似额度账户，不是财务强一致账本，但需要幂等键和事务锁保证同一任务不重复预留。

### Q7：怎样证明是你设计的，不是 AI 自动生成的

我会讲三类个人判断：为什么把提交权留在 Java Host，为什么 Candidate 与 Cell 分离，以及为什么不用“日志继续执行”代替 Checkpoint。AI 可以帮助生成样板代码、测试输入和备选方案，但对象边界、失败窗口、取舍标准和验收口径由我决定。我还能从请求字段一路解释到表、条件更新和反例测试，这比声称手写每一行更能说明 Ownership。

## 11. 强口径、保守口径和禁区

| 场景 | 推荐表达 |
| --- | --- |
| 简历 | “实现可恢复、可验证的 Research Agent，使用结构化研究状态和跨语言提交协议治理长任务” |
| 一面 | “学校资料服务原型后的个人工程化重构，完整链路可在固定案例和故障注入下演示” |
| 深挖 | “Completion 级幂等和 Checkpoint Hydration 已落地，外部 Operation Receipt 是继续生产化的边界” |
| 禁止 | “已经服务数万学校用户”“所有模型调用 Exactly Once”“外部 Benchmark 行业领先” |

包装可以放大设计价值、演练结果和职责深度，但不要编造能被追问到合同、监控、用户数和线上事故的一手生产事实。

## 12. 源码与证据导航

| 问题 | 入口 |
| --- | --- |
| 创建请求和用户 API | `CreateResearchRunRequest`、`ResearchRunController`、`ResearchRunCommandService` |
| Task Claim、续租和完成 | `ResearchAgentTaskInternalController`、`ResearchAgentTaskService` |
| Completion 原子提交 | `ResearchAgentCompletionCommitter`、`ResearchAgentCompletionCanonicalizer` |
| Checkpoint 与预算 | `ResearchBudgetAndCheckpointService`、`ResearchAgentCheckpointSnapshotCompiler` |
| Hydrated Resume | `ResearchAgentCheckpointHydrator` |
| Candidate 与 Cell 合并 | `ResearchAgentCellMergeService` |
| 来源资格验证 | `ResearchEvidenceQualificationService` |
| Python 执行 | `workers/research-worker/app/deep_cell_executor.py` |
| 数据库演进 | `V097` 到 `V104` Research 相关迁移 |

## 13. 面试前自检

至少能不看文档回答以下问题：

1. Run、Task、Cell、Candidate、Evidence 分别是谁的真源，为什么不能合并成一张表。
2. Lease、Fencing、幂等键和 Expected Version 各保护哪个失败窗口。
3. 从外部调用成功到 Completion 落库之间宕机，会不会重复付费。
4. Checkpoint 如何证明完整，Hydrated Resume 为什么保持同一 Run 并创建新 Attempt。
5. Verifier 能降低什么风险，又不能证明什么。
6. Matrix 如何支持量化覆盖率，为什么简单问题不一定需要 Matrix。
7. 为什么选 Java Host 加 Python Worker，而不是纯 Python Agent。
8. 当前实现、固定演练、理想指标和生产事实的边界在哪里。
