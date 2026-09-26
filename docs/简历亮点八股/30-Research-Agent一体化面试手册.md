# Research Agent 一体化面试手册

> 这篇把 Research Agent 的架构、演进、案例、契约、指标和 Ownership 合并成一条准备路径。面试默认采用“稳定 ResearchRun + ExecutionAttempt + Typed WorkItem”的推荐设计，Matrix 只服务比较型任务；当前实现差异作为迁移背景。跨功能裁决见[面试推荐架构与规模化演进裁决](43-面试推荐架构与规模化演进裁决.md)。

## 1. 30 秒主回答

学校资料服务原型最初只能做一次问答。问题变成开放式调研后，单次长 Prompt 无法回答三个问题：研究范围是否覆盖完整、结论是否有可靠证据、长任务中断后是否能继续。于是我把 Research Agent 改造成验证驱动的研究运行。

用户提交研究问题、目标、交付格式、时间范围、深度和资料范围。Java Host 创建稳定 ResearchRun，Planner 在 Run 下维护版本化 ResearchPlan。初始 Attempt 执行当前 Plan Revision；基础设施恢复创建新 ExecutionAttempt，并 Hydrate 前一有效 Checkpoint，不重新编译计划。同一冻结目标和资料范围内出现明确缺口、来源失效、依赖发现或 Validator Repair 时，才创建新 Plan Revision；用户改变目标、资料范围或交付标准时创建新 ResearchRun，不能用重规划偷换原 Run 的语义。比较任务生成 Matrix Cell，开放调查生成 Claim/Question，时间型任务生成 Timeline Event。Python Worker 只执行搜索、阅读和推理，结果先作为 Candidate 返回，再由 Host 做来源资格、版本、预算、并发和证据校验。Checkpoint 记录产生它的 Attempt，并进入 Run 级单调 Checkpoint 链；恢复只推进新的执行代际，不改变用户看到的 Run。最终报告带 Evidence Manifest，可以继续写回 Source。

## 2. 功能演进主线

| 阶段 | 旧方案 | 暴露的问题 | 当前选择 |
| --- | --- | --- | --- |
| V0 | 单次长 Prompt | 范围不可控，无法解释进度 | 保留为需求验证手段 |
| V1 | ReAct 自由循环 | 状态藏在对话，重试和恢复困难 | 允许模型决策，但不让轨迹成为真源 |
| V2 | Plan-and-Execute | 目标更稳定，但计划过早固定 | Intent 固定，Plan 可按缺口修订 |
| V3 | 所有问题都使用 Matrix/Cell | 比较任务清楚，开放调查和时间线被强行表格化 | Typed WorkItem，Matrix 作为比较型 Planner |
| V4 | 单次引用检查 | 有链接不等于支持结论 | L0 规则、L1 语义判断、L2 独立复核按风险启用 |
| V5 | 从头重跑 | 中断浪费调用，旧结果可能晚到 | Checkpoint Hydration、Lease、Fencing |
| V6 | Worker 直接写业务 | 跨语言写入破坏权限和事务边界 | Worker 只提交 Envelope，Host 仲裁 |

面试中不要把这些阶段讲成十二次上线。它们是从学校原型到面试向工程化重构时，围绕失败窗口形成的设计演进线；Git 事实时间线另见附件。

## 3. 一次研究到底保存什么

```text
ResearchRun
  -> ResearchIntent / Brief / FeatureFlagSnapshot
  -> ResearchPlan(planRevision)
       -> WorkItem(type, required, expectedVersion, acceptanceContract)
            -> Task(snapshot, leaseEpoch, fencingToken)
                 -> Evidence(source identity, locator, qualification)
                 -> Candidate(baseWorkItemVersion, evidenceRefs)
                      -> Merge(decision, resultingWorkItemVersion)
  -> BudgetReservation / Ledger
  -> Checkpoint(high-water marks, canonical digest)
  -> Report / Collection / Source write-back
```

| 对象 | 面试解释 | 关键不变量 |
| --- | --- | --- |
| Run | 一次研究的聚合根 | 输入、Workspace 和执行模式冻结 |
| Intent | 用户真正想完成的目标 | 不被 Worker 的临时计划替换 |
| ExecutionAttempt | 一次执行、恢复或 Worker 接管代际 | 基础设施重试不改变用户 Run 身份 |
| ResearchPlan | 带版本的工作计划 | 计划修订保存 Diff，不偷换 Intent |
| WorkItem | Matrix Cell、Claim、Question、Timeline 或 Source Audit | 按类型定义完成条件和证据责任 |
| Task | 一次受租约保护的执行 | 旧 Owner 无权提交 |
| Evidence | 支撑或反驳 Claim 的来源绑定 | 来源、Snapshot、定位和验证状态齐全 |
| Candidate | Worker 提交的候选更新 | 不等于事实，不直接覆盖 WorkItem |
| Checkpoint | 可恢复的结构化账本 | 哈希、高水位和摘要一致 |

## 4. 请求与执行契约

`CreateResearchRunRequest` 的核心字段是 `question`、`profile`、`researchGoal`、`deliverableFormat`、`constraints`、`timeRange`、`depth`、`researchType`、`sourceScopeSourceIds`、`retrievalMode` 和 `seedSourceIds`。

Request 只描述用户意图，Host 还要编译 Research Brief、冻结来源范围和 Feature Flag Snapshot。Worker 领取 Task Snapshot，得到角色、目标 Cell、Expected Version、Provider、来源策略、预算和 Schema Version。这样重试输入可复现，执行中配置变化也不会改变旧任务。

### 4.1 Java Host 与 Python Worker

| Host | Worker |
| --- | --- |
| 身份、Workspace、Source Scope | 搜索、阅读、抽取、模型推理 |
| Run、Cell、Evidence、预算真源 | 消费冻结 Task Snapshot |
| Claim、Heartbeat、Fencing、提交仲裁 | 返回 Completion Envelope |
| Checkpoint、恢复、报告和写回 | 不直接写业务表 |

采用“薄 Worker、厚 Host”的理由是把模型和外部 Provider 的非确定性限制在执行面，把业务不变量留在事务和审计能力更强的 Host。代价是跨语言协议和回执设计更复杂。

## 5. 候选合并与证据验证

两个 Worker 可能基于同一个 WorkItem Version 得到不同结果。Candidate 带 `baseWorkItemVersion` 和 Evidence 引用，Host 用 Expected Version 条件更新。版本冲突时进入拒绝、重算或冲突审查，不做 Last Write Wins。比较型任务仍可把 `baseCellVersion` 作为 WorkItem Version 的具体实现。

证据验证分三档：

1. L0 确定性规则检查来源范围、Snapshot、定位、Schema、数字、单位和重复证据。
2. L1 只对有语义争议的 WorkItem 判断 Claim 与 Citation 的支持、反驳或不充分。
3. L2 只用于高风险结论，使用异构模型、独立来源盲审或人工抽检，并检查跨 WorkItem 冲突、报告结构和停止条件。

Local 和 Global 表示验证责任，不强制等于两次模型调用。Verifier 降低的是错误不易发现的风险，不是把幻觉概率变成零；不确定时允许 `ABSTAIN/INSUFFICIENT`。Judge 需要人工校准集，生成模型不能无约束地给自己最终背书。

## 6. Checkpoint、恢复和重复调用

Checkpoint 保存 Attempt Epoch、Plan Revision、WorkItem 状态、预算视图以及 Task、Candidate、Merge 的高水位。只有真实 Fan-out 才保存 Wave/Barrier。Canonical JSON、内容大小和 SHA-256 用于识别截断和内容替换。

恢复时在同一个 ResearchRun 下创建新的 ExecutionAttempt，推进 Lease Epoch 和 Fencing Token，恢复已确认 WorkItem、Evidence、规划状态和预算锚点，再只调度高水位之后的工作。用户改变目标、资料范围或主动 Fork 时才创建新 Run。旧实现的 Descendant Run 作为迁移兼容，不再是推荐模型。

Lease 解决“任务可以被重新领取”，Fencing 解决“旧 Worker 晚到也不能提交”，幂等键和 Completion Receipt 解决“重复回调业务结果收敛”。这三个机制保护的失败窗口不同。

当前实现已覆盖 Completion 级回执和 Checkpoint Hydration。所有外部 Search、Read、Model 调用的 Operation Receipt 仍属于下一步生产化边界，因此不能承诺端到端 Exactly Once 或调用成本绝对不重复。

## 7. 学校场景演练

问题：比较三种课程资料助手路线，覆盖检索质量、教师维护成本、引用追溯和考试周容量风险。

1. Host 冻结课程资料范围，将目标编译为 3 个方案实体和 4 个评价维度。
2. Planner 判断它是比较型任务，生成 `3 × 4` Matrix，共 12 个 `MATRIX_CELL` WorkItem。
3. Worker 负责发现、阅读和抽取，Host 校验来源身份、快照版本和定位。
4. 两个 Worker 同时更新同一 Cell 时，旧版本候选进入冲突，不覆盖已合并内容。
5. 阶段结束保存 Checkpoint，模拟 Worker 退出后在同一 Run 下创建新 Attempt 并恢复。
6. Verifier 检查 Cell 覆盖、关键 Claim 支持、来源独立性和冲突处理。
7. Finalizer 生成比较表和推荐意见，用户可以查看 Evidence Manifest 或保存为新的 Source。

这是固定演练故事，真实用户数、任务量和节省时间必须以后由事件和评测 Manifest 替换。

## 8. 指标与简历口径

| 指标 | 计算 | 说明 |
| --- | --- | --- |
| Required WorkItem Completion | 通过类型化验收的必填 WorkItem / 必填 WorkItem | 全 Research 的计划完成度 |
| Matrix Cell Coverage | 通过验收的必填 Cell / 必填 Cell | 只衡量比较型任务的范围完整性 |
| Citation Support | 被引用 Evidence 直接支持、且已经带引用的可核验 Claim / 带引用的可核验 Claim | 已给出的引用是否真正支撑结论 |
| Citation Completeness | 带有效引用的可核验 Claim / 全部应引用的可核验 Claim | 应引用结论是否漏引 |
| Conflict Discovery | 识别的冲突 Gold Case / 冲突 Gold Case | 是否隐藏矛盾资料 |
| Resume Reuse | 恢复后复用的有效步骤 / 中断前有效步骤 | 恢复节省的重算 |
| Duplicate Convergence | 重复 Completion 后业务行增量 | 回调幂等 |
| Verified Claim per 1K Tokens | 通过验证 Claim / Token × 1000 | 单位有效产出 |

简历可以写：

> 设计验证驱动的 Deep Research Agent，将研究计划拆成带类型的 WorkItem、Evidence、Candidate 和 Checkpoint，比较型任务使用 Matrix Cell；通过 Lease、Fencing、Expected Version 和 Completion Receipt 约束跨语言 Worker，并在固定研究集上验证断点恢复、重复回调收敛和引用可追溯。

如果有完整 Manifest，再补充样本数、Baseline 和绝对提升。只有 4 Case 或 30 次故障注入时，应称为固定回放和受控演练。

## 9. 面试官连续追问

### 为什么叫 Agent，不是普通 Workflow

下一步动作会根据缺口、冲突和预算变化，决策空间不是固定 DAG；执行又必须落到带类型 WorkItem、受约束 Task 和状态迁移。项目选择“计划可修订，执行可验收”。

### 为什么不用现成 Deep Research 框架

现成框架适合验证交互，但 Workspace 权限、Source Snapshot、预算、SSE 和写回都属于 Java 业务域。让框架持有真源会产生双状态机，所以借鉴图执行和工具合同，保留 Host 协议。

### 能保证 Exactly Once 吗

不能承诺端到端 Exactly Once。队列和网络仍可能重复，系统通过幂等键、唯一约束、条件更新、Fencing 和回执实现业务效果收敛。

### Checkpoint 为什么不直接用日志

日志是观测数据，不一定覆盖多表事务一致位置。Checkpoint 是业务协议的一部分，保存已确认状态、高水位和摘要；日志用于定位，不作为恢复真源。

### AI 写了很多代码，你的 Ownership 是什么

我负责对象边界、失败窗口、方案取舍和验收指标。最能体现判断的是 Candidate 与 Cell 分离、提交权留在 Host、Checkpoint 不从日志推导，以及每个选择对应的反例测试。

## 10. 4 分钟标准主回答

这个功能的业务起点是学校资料服务。低风险短问答继续走 Lite 路径，只有多资料、跨分钟、需要证据或写回的请求才进入 Structured Research。原方案暴露三个缺陷：模型说自己完成了，不代表研究范围真的覆盖；回答里有链接，不代表链接支持对应结论；任务中断后从头重跑会重复调用并丢失已确认状态。

我先比较了三条路。纯 ReAct 灵活，但状态隐藏在对话轨迹里；固定 DAG 容易验收，遇到资料缺口和冲突时又不够灵活；把状态留在 Python Agent 内部开发快，却会分散权限、预算和事务。推荐方案使用稳定 Intent 和可修订 ResearchPlan，Plan 生成 Typed WorkItem：比较任务使用 Matrix Cell，事实调查使用 Claim，开放问题使用 Question，时间型任务使用 Timeline Event。`OPEN_QUESTION` 创建时同时绑定时间、成本、分支数和最小新增证据收益，达到边界后只能完成、弃权或明确证据不足，不能继续无界 Replan。Worker 只返回 Candidate，Java Host 负责最终提交。

一次 Run 冻结 Workspace、资料范围、用户目标、总预算和当前 Plan Revision 链。Attempt 记录起始 Checkpoint、Owner 代际、执行时使用的策略与 Provider 配置，以及本代新增消费；它不重新拥有一份总预算，也不会因为恢复自动生成新计划。Task 以 Snapshot 交给 Worker，其中只携带本任务的预算授权和 Run 剩余预算视图。Worker 搜索和阅读得到 Evidence，再基于当前 WorkItem Version 生成 Candidate。Host 检查来源身份、Snapshot、定位、引用和预算，版本匹配时才合并。两个 Worker 同时更新同一个 WorkItem 时，晚到旧版本进入冲突，不覆盖新结果。

长任务可靠性靠四个机制组合：Lease 让失联任务可重新领取，Fencing 让旧 Worker 晚到时没有提交资格，幂等键让重复动作只产生一个业务结果，Completion Receipt 让 Worker 超时重试时拿到同一语义回执。阶段边界和昂贵外部调用回执后生成 Checkpoint。恢复时在同一 Run 下创建新 Attempt，复用已确认 WorkItem 和 Evidence，只调度高水位之后的工作。

验证按风险分级。L0 规则检查来源范围、版本、数字和单位；L1 只判断有争议 Claim 与证据的关系；高风险结论才进入异构 Judge、独立来源或人工抽检。Local 和 Global 是责任边界，不要求每个字段都多调用两次模型。评测同时看 Required WorkItem Completion、Citation Support、Conflict Discovery、恢复复用率和 Verified Claim per 1K Tokens。

这套设计只用于持续时间长、来源需要审计或结果需要写回的任务。简单问题走 Lite，一次生成加确定性检查即可；普通结构化研究使用顺序 WorkItem 和有界 Fetch 并发；真正出现独立 Fan-out 后才启用 Wave/Barrier 和多 Worker。当前仓库的 Matrix、Descendant Run 和默认并发 1 可以解释为演进起点，面试推荐方案以 Typed WorkItem 和稳定 Run/Attempt 为准。

## 11. 项目八股映射

| 面试八股 | 在 Research 中的落点 | 继续追问 |
| --- | --- | --- |
| Java 事务 | Host 在事务内完成 Candidate、Cell Merge、预算结算和 Receipt | 为什么不能把跨 Provider 调用放事务里 |
| MySQL 乐观锁 | Cell Expected Version 条件更新 | 版本冲突后为什么不重试覆盖 |
| MySQL 索引 | Run、Task 状态和 Lease 过期索引 | Claim 查询如何避免全表扫描 |
| Kafka 至少一次 | Task/Command 异步投递 | 重复消息怎样转成幂等业务效果 |
| Redis Lease | 只做短期协调或配额 | 为什么持久 Task Lease 不能只放 Redis |
| Java 并发 | Claim、Heartbeat、Commit 的竞争 | 旧 Owner 晚到如何被 Fencing 拒绝 |
| HTTP 超时 | Worker Callback 和 Provider 调用 | 超时后为什么是 UNKNOWN 而不是 FAILED |
| Python Worker | 执行搜索、阅读和模型调用 | 为什么不让 Worker 直接写 MySQL |
| RAG | Evidence 来源、定位和 Citation Support | Research 和普通 QA 的证据粒度差异 |
| 分布式系统 | At Least Once、最终一致和恢复 | 为什么不承诺 Exactly Once |

## 12. 三层追问训练

面试官问“为什么要 Checkpoint”，第一层回答是为了断点恢复；第二层要说明它保存高水位、Canonical Digest 和预算锚点，日志不能作为恢复真源；第三层要回答“Checkpoint 写成功、Worker 结果未提交、进程又退出”时如何处理，应该由新的 Run 根据已确认高水位重新调度，而不是假设最后一次外部调用一定成功。每个机制都按“作用、实现、反例”讲三层，回答才不会停留在名词解释。

## 13. 深挖附件

重点机制的 3 分钟延伸回答统一见[重点知识点三分钟深挖库](35-重点知识点三分钟深挖库.md)，二阶追问和替代方案见[重点知识点二阶追问回答库](36-重点知识点二阶追问回答库.md)。

| 侧重点 | 附件 |
| --- | --- |
| 完整架构、算法、状态和源码导航 | [详细架构与具体设计](01-Deep-Research-Agent-详细架构与具体设计.md) |
| 演进、参考框架和 Trade-off | [演进与技术取舍](16-Research-Agent演进与技术取舍专项.md) |
| 学校案例、消融、Ownership 和指标 | [案例与 Ownership](17-Research-Agent真实案例消融实验与Ownership答辩.md) |
| 请求、Task、Checkpoint、回执契约 | [契约级数据模型](25-Research-Agent契约级数据模型与面试官下钻.md) |
| Task 接纳、配额、等待与公平调度 | [Task 调度与运行时治理](38-Task调度-Quota与运行时治理一体化面试手册.md) |
| 简短问答速查 | [面试题与参考回答](01-Deep-Research-Agent-面试题与参考回答.md) |

## 14. 面试前自检

1. 能从 Request 讲到 ResearchRun、ExecutionAttempt、Typed WorkItem、Task 和 Evidence，并说明 Matrix 只服务比较型任务。
2. 能区分 Lease、Fencing、Expected Version 和幂等键。
3. 能解释为什么恢复保持同一 Run，只创建新的 ExecutionAttempt。
4. 能说清 Verifier 能证明什么，不能证明什么。
5. 能拿出一个旧方案失败案例和一个故障注入点。
6. 能区分源码事实、固定回放、理想数据卡和生产结果。

## 15. 指标计算、行业参照与数字答辩

Research Agent 的数字要回答三个不同问题：是否找全、是否说对、是否值得。只报“报告生成成功率”会把空报告、无依据报告和高成本报告算成同一种成功。

| 问题 | 主指标 | 公式 | 面试意义 |
| --- | --- | --- | --- |
| 研究计划是否完成 | Required WorkItem Completion | `通过类型化验收的必填 WorkItem / 必填 WorkItem` | 适用于开放调查、时间线、来源审计和比较任务 |
| 比较范围是否覆盖 | Matrix Cell Coverage | `通过 Cell 验收的必填 Cell / 必填 Cell` | 只用于比较型任务，防止报告很长但漏掉关键维度 |
| 关键事实是否找到 | Key Claim Recall | `命中的 Gold Key Claim / Gold Key Claim` | 衡量搜索和阅读是否漏掉承重事实 |
| 引用是否真的支持结论 | Citation Support | `被引用证据直接支持的可核验 Claim / 有引用的可核验 Claim` | 防止“有链接但链接不支持结论” |
| 应有引用的地方是否都有 | Citation Completeness | `至少带一个有效引用的应引用 Claim / 全部应引用 Claim` | 与 Citation Support 分开，避免只给少数安全 Claim 加引用 |
| 冲突是否被发现 | Conflict Discovery | `正确识别的冲突 Case / Gold 冲突 Case` | 衡量 Agent 是否会把互相矛盾的来源强行合并 |
| 恢复是否节省重算 | Resume Reuse | `恢复后复用的已确认步骤 / 中断前已确认步骤` | 证明 Checkpoint 有业务收益，不只是“能恢复” |
| 单位成本产出多少 | Verified Claim per 1K Tokens | `通过验证的 Claim / 输入输出 Token × 1000` | 将质量与成本放在同一口径 |
| 用户是否愿意采用 | Accepted Report Rate | `观察窗口内发生用户主动采纳动作且未在撤销窗口回滚的报告 / 符合采纳机会的完成报告` | 保存为 Source、显式导出、继续编辑或确认写回才算，排除自动保存、系统重试、强制流程和演示账号 |

这些指标必须按研究类型、资料范围、难度和预算切片。不同 WorkItem 类型的验收条件不能混成一个平均分；Matrix Cell Coverage 也不能把三维课程调研和十二维竞品调研直接平均。Citation Support 的分母只包含有引用的可核验 Claim，Citation Completeness 的分母包含全部应引用 Claim，两者一起才能防止系统只给少数安全 Claim 加引用。Accepted Report Rate 需要固定采纳观察窗口和撤销窗口，例如完成后 24 小时内主动保存或导出，且随后 24 小时没有删除、撤销或立即重跑；分母只包含界面和权限上确实存在采纳动作的完成报告。

`[行业参考]` OpenAI Deep Research 等产品常用“多步骤研究、来源和带引用报告”描述用户价值；GPT Researcher 一类开源产品还会宣传来源数、报告长度、耗时和单次成本。DeepResearchBench、ALCE 和 LiveDRBench 则把报告质量、Citation 和 Key Claim 拆开。NoteWeave 可以复用这种指标结构，但内部学校 Gold 与公开 Web Benchmark 的语料、工具权限、模型和预算不同，不能直接比较绝对分数。完整依据见[Research Agent 外部调研](../research/Research-Agent演进取舍外部依据.md)和[公开 Benchmark 与宣传口径](../research/公开Benchmark与简历指标宣传口径调研.md)。

### 面试官问“你的效果比直接 Prompt 好多少”

先说明实验是同一批 Query 的配对比较，固定资料 Snapshot、模型、Token Budget、Prompt 版本和 Judge。Baseline 用单次 Prompt，Treatment 使用 Typed WorkItem、Evidence Verifier 和 Checkpoint；比较型子集再启用 Matrix。主结果报告 Required WorkItem Completion、Citation Support 和 Key Claim Recall 的绝对差值，比较型子集补充 Matrix Cell Coverage，再报告 P95、Token 和成本护栏。若只有固定 Fixture，就说“在 N 个冻结 Case 上，覆盖与引用门禁全部通过”，不能写“准确率提升 X%”。

一个可用于演练的理想数据卡可以写成 `[演练假设]`：`N=60` 个课程研究任务，Required WorkItem Completion 从 `0.68` 提升到 `0.87`，Citation Support 从 `0.74` 提升到 `0.90`，每份报告 Token 增加 `22%`，中断恢复后复用 `81%` 的已确认步骤。比较型子集另报 Matrix Cell Coverage。面试时必须紧接着说明这些数字是实验设计示例，只有真正生成 Manifest、原始结果和失败样本后才能进入简历。

### 数字背后的取舍

Completion 提升可能来自把 WorkItem 拆得更细，也可能同时放大成本，因此 Required 集合与类型必须冻结；Citation Support 提升可能来自少写可核验 Claim，所以要同时看 Completeness；Resume Reuse 很高也可能说明 Checkpoint 太频繁，写入开销过大。可信结论至少由一个质量主指标、一个安全或完整性护栏、一个延迟或成本指标共同组成。

## 16. 重点知识点一：Table-as-State 与候选仲裁

### 3 分钟回答

自由 ReAct 把研究状态藏在自然语言轨迹里，模型说“已经覆盖”很难由程序验证。比较型任务先把研究目标拆成 Matrix，实体是 Row，待回答维度是 Cell。Cell 保存状态、版本、Canonical Value、Evidence 和冲突，不直接保存“模型最后一次说了什么”。这样覆盖率、缺口、优先级和停止条件都能计算，恢复时也能定位到字段。开放调查、时间线和来源审计使用各自 Typed WorkItem，不强行表格化。

搜索和阅读只产生 Candidate。Candidate 带来源身份、Snapshot、Locator、值、置信与生成它的 Plan Revision，不能直接覆盖 Cell。Host 合并时检查 Cell Expected Version、Plan Revision、Lease Epoch、Fencing Token 和 Evidence Scope。版本不匹配意味着 Candidate 基于旧状态，进入冲突或丢弃；相同值来自多个独立来源时可以增加支持，但同域转载要按 Lineage 去重，不能用数量伪造独立性。

候选仲裁比较过 Last Write Wins、Majority Vote、Authority-first 和 Independent Quorum。Last Write Wins 最容易被晚到旧结果覆盖；多数票会把转载当成共识；权威优先适合法规或官方参数，却不适合开放争议。当前选择是在字段级结合来源独立性、直接支持、版本和冲突处理，无法自动裁决时保留原 Canonical Value，并生成新的 Recovery Target 或在报告中显式限定，不虚构已完成。

Table-as-State 的代价是规划必须先定义实体和字段，对完全开放、很难结构化的问题会损失自由度；Cell 越细，状态和验证成本越高。适用边界是多实体、多维度、需要比较和引用的研究。简单的一问一答或创意写作不需要先建 Matrix。验证时看 Matrix Cell Coverage、冲突 Case、旧版本提交拒绝和同来源去重，不用最终文风代替状态正确性。

### 二阶追问：Cell 拆得太细会不会让报告割裂

会，所以 Cell 是验证和恢复单位，不是最终段落单位。Global Verifier 和 Reporter 仍按主题、因果与受众组织成文，并检查跨 Cell 一致性。拆分策略要通过 Coverage、重复 Claim、报告连贯性和 Token 成本共同校准，不能只追求字段越多越好。

### 二阶追问：为什么不让模型直接解决冲突

模型可以给出裁决建议，但不能无条件修改 Canonical State。冲突可能来自时间、范围、版本或来源权威差异，需要保留两边 Evidence 和限定条件。Host 先做确定性身份与版本检查，再让 Verifier 分析语义；低置信或无法归一化时保持冲突可见，避免一次生成把历史证据覆盖。

## 17. 重点知识点二：Checkpoint、恢复与结果未知

### 3 分钟回答

长任务恢复不能等同于“把最后一段 Prompt 再发一次”。一次 Research Run 可能已经确认部分 Cell、消耗预算、写入 Evidence、发起外部搜索，也可能在 Completion 已提交后丢失 HTTP 响应。若只保存日志，系统无法知道哪些动作已经成为业务事实；若从头重跑，会重复外部调用、扩大成本并产生不同结果。

Checkpoint 保存恢复闭包：Plan Revision、Canonical Ledger 摘要、已确认 Cell/Evidence、执行高水位、预算预留与结算、待恢复目标、Schema Version 和内容 Digest。数据库保存身份、序号与对象引用，大体积状态放对象存储。新 Checkpoint 必须单调，读取时校验 Run、前驱、大小、Hash 和版本，损坏时回退上一有效点或明确失败，不能根据半截 JSON 猜状态。

恢复会创建新的执行代际，而不是让旧 Worker 继续拥有提交权。Lease 让失联 Owner 的时间窗口过期，新的 Claim 推进 Epoch/Fencing Token；旧 Worker 即使恢复网络，Heartbeat、Cell Merge 和 Completion 都会因 Token 过期被拒绝。幂等键与 Completion Receipt 处理“数据库已提交但响应丢失”，完全相同的重放返回原结果，键相同但 Payload Digest 不同返回冲突。

这些机制仍不能保证外部模型、搜索或写工具 Exactly-once。调用超时可能已经产生副作用，Fencing 只能阻止旧结果进入 Host 真源。生产还需要 Operation ID、Provider Receipt、缓存或对账。验证要逐个注入发送前退出、发送后响应丢失、Checkpoint 损坏、Lease 过期接管、旧 Worker 晚到和重复 Completion，断言最终状态可查询且不会出现第二份业务结果。

### 二阶追问：为什么恢复保持同一 Run，但创建新 Attempt

ResearchRun 表示用户的稳定研究意图，基础设施恢复没有改变目标、资料范围和交付要求，因此不应创建新 Run。新的 ExecutionAttempt 记录恢复原因、输入 Checkpoint、Owner 代际、Provider 配置和新增成本；旧 Attempt 保留历史审计。Attempt 身份变化后推进 Epoch 与 Fencing Token，旧 Worker 不能继续提交。用户改变目标、资料范围或主动 Fork 时才创建新 Run。

### 二阶追问：Checkpoint 越频繁越好吗

不是。频繁 Checkpoint 降低最坏重算，却增加序列化、对象存储、数据库元数据和一致性开销。间隔应结合单 Wave 成本、外部调用副作用、恢复 RPO 和状态大小决定。指标同时看 Checkpoint 写入 P95、平均大小、恢复复用率和重复调用成本。

## 18. 重点知识点三：Verifier 与引用可信度

### 3 分钟回答

有 URL 不等于有证据。一个来源可能只与主题相关，却不直接支持 Claim；引用也可能定位错误、版本过期或来自不允许的 Workspace。NoteWeave 把验证拆成确定性门禁、Local Verifier 和 Global Verifier。确定性门禁检查 Source/Snapshot 身份、Workspace Scope、Locator、Schema、版本和引用是否存在；Local Verifier 判断单个 Cell 的 Claim 是否由原文直接支持、是否存在否定证据；Global Verifier 检查必填 Cell、跨 Cell 冲突、结论范围和最终 Citation Completeness。

Verifier 本身也会误判，所以不能让同一个模型在没有约束时既生成又给自己打满分。确定性规则先执行，语义 Judge 冻结模型和 Rubric，并用人工校准集测一致性。低置信否决可以触发有限反证或补查，高风险冲突不能靠重复自我反思无限循环。达到预算或轮次上限仍缺证时，任务应降级、拒绝或明确列出缺口，不把不确定性藏进流畅文本。

Citation Support 和 Citation Completeness 要同时看。Support 的分母是已有引用的可核验 Claim，衡量引用是否真的支撑；Completeness 的分母是全部应引用 Claim，防止系统只给少数安全结论加引用。再配 Key Claim Recall、Conflict Discovery、独立来源覆盖和人工抽样，才能区分“搜到了”“引用对了”“结论完整”三个层次。

行业 Benchmark 可以借鉴 ALCE、DeepResearchBench 和 LiveDRBench 的 Claim/Citation 分层，但内部学校 Gold 还要增加 Workspace、Source Version、必填 Cell、冲突事实和可拒答条件。只有 Dataset、Snapshot、模型、工具、预算和 Judge 一致时才能横比。当前固定回放能证明门禁和失败分类，不能声称消除幻觉或达到某产品水平。

### 二阶追问：Local 都通过，Global 为什么还会失败

每个 Cell 单独有证据，不代表组合后没有口径冲突、时间混用、范围遗漏或重复计数。Global Verifier 检查研究 Intent 的集合约束，比如所有方案是否使用同一时间区间、总成本是否与分项一致、必填维度是否全部覆盖。

### 二阶追问：LLM Judge 与人工不一致时信谁

先冻结分歧样本，由两名人工按 Rubric 独立标注并仲裁，判断是 Gold、Rubric 还是 Judge 问题。Judge 只承担可校准的代理评分，不是事实真源。高风险发布门禁可以要求人工抽样或确定性规则，不能因为模型分数稳定就跳过校准。
