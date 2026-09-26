# DeepResearch / ResearchAgent 业界演化研究与 NoteWeave 推导

> `[行业参考]` 产品与框架对照（OpenAI / AutoGen / LangGraph / CrewAI）。论文、演进叙事与面试取舍以 [Research Agent 演进取舍外部依据](./Research-Agent演进取舍外部依据.md) 为准。NoteWeave 推导属于 `[目标设计]`，不得当作已上线能力或生产质量证据。

> 研究目的：为 NoteWeave 的 Research 链路补上一条“从可解释基础能力逐步演化到长任务智能研究”的合理路线。
>
> 资料边界：行业事实只引用官方产品文档、官方代码仓库或论文；NoteWeave 部分是根据现有架构文档、领域术语和这些公开抽象推导出的设计建议，不把任何建议叙述成项目历史。本文重点讨论规划、搜索、阅读、提取、验证、写作、来源引用、覆盖率、并行合并、checkpoint、预算、取消、质量门禁和人机协作。

## 1. 先给结论：Research 应该是“可恢复的证据生产线”

DeepResearch 最容易被误解为“让一个更强的模型搜索网页，然后写一篇长文”。从公开系统抽象看，更稳妥的定义是：

```text
研究目标
  -> 可审阅的计划
  -> 受约束的来源获取
  -> 阅读与结构化提取
  -> claim / evidence 对齐
  -> 覆盖率与冲突验证
  -> 可合并的报告草稿
  -> 人工或策略门禁
  -> 可追溯的 Report Artifact
```

这里有三个不同层次：

1. **研究策略**：决定要回答哪些子问题、需要什么来源、何时停止。
2. **运行时状态**：记录每个搜索、读取、提取、验证和写作步骤是否完成，失败后从哪里继续。
3. **知识产物**：记录来源快照、证据片段、结论、引用关系和最终报告。

把三层混在一个 prompt 或一段聊天记录里，会导致无法解释覆盖率、无法安全重试，也无法知道报告中哪句话由哪份来源支持。NoteWeave 的 Research 应优先将这三层分开，再逐步增加模型自治程度。

## 2. 一手项目抽象：行业已经解决了哪些问题

本节只记录外部项目公开说明了什么，不把它们的产品能力直接等同于 NoteWeave 的当前实现。

### 2.1 OpenAI Deep Research：先计划、可干预、带引用的异步研究

OpenAI 对 Deep Research 的公开说明有几个对 ResearchAgent 很关键的事实：

- 研究开始前会生成可审阅的研究计划，用户可以修改计划后再开始；运行中可以查看进度、改变关注点、调整可使用的来源并中断任务。
- 任务可以使用公开网络、用户上传文件和已连接的数据应用；连接应用的研究操作是读取型，最终报告包含引用或来源链接，并提供来源区和活动历史，方便复核和复用。
- 官方产品说明将能力描述为多步骤的搜索、评估来源、改写查询和综合，而不是一次搜索返回一段摘要；发布说明还提到会根据新发现回溯、调整搜索，读取文件并分析数据。
- 官方安全资料明确列出提示注入、隐私、代码执行、幻觉和来源权威性判断等风险，说明“能浏览”本身不能代替安全和质量门禁。

这些能力对应的技术名词是 **plan review**、**source policy**、**progress streaming**、**interruptible run**、**citation provenance** 和 **risk gate**。它们共同指向一个结论：研究运行必须是用户可观察、可暂停、可调整的状态机，而不是不可见的长 prompt。

参考：[Introducing deep research](https://openai.com/index/introducing-deep-research/)、[Deep research in ChatGPT](https://help.openai.com/en/articles/10500283-deep-research)、[Deep research System Card](https://openai.com/index/deep-research-system-card/)。

### 2.2 Microsoft AutoGen：把 Agent 协作建模为消息、团队和终止条件

AutoGen 官方文档公开了两层抽象：

- **Agent Runtime** 负责消息投递和 Agent 生命周期，Agent 逻辑与消息传输解耦；分布式 runtime 可以把 Agent 放在不同进程或机器上，并通过消息类型通信，而不是直接访问另一个 Agent 的实例。
- **Team** 提供 `RoundRobinGroupChat`、`SelectorGroupChat`、`MagenticOneGroupChat` 和 `Swarm` 等协作模式。文档明确建议：简单任务先优化单 Agent，只有在单 Agent 不足时才升级到团队；团队需要更强的脚手架和终止策略。
- 团队可以使用文本条件、最大消息数等自动终止条件，也可以通过 `ExternalTermination` 在外部停止；运行状态能够保存和加载，恢复时由各参与者负责继续自己的状态。
- 分布式 runtime 仍被标为实验能力，这提醒我们：跨进程 Agent 协作可以成为扩展点，但不应默认成为业务事实源。

这套设计的知识点包括 **message-driven runtime**、**team topology**、**termination condition**、**state serialization**、**handoff** 和 **distributed lifecycle**。对 Research 的启发是：并行搜索分支应该返回结构化消息和状态，而不是共享可变的全局上下文。

参考：[AutoGen Teams](https://microsoft.github.io/autogen/stable/user-guide/agentchat-user-guide/tutorial/teams.html)、[AutoGen Quickstart](https://microsoft.github.io/autogen/stable/user-guide/core-user-guide/quickstart.html)、[Distributed Agent Runtime](https://microsoft.github.io/autogen/stable/user-guide/core-user-guide/framework/distributed-agent-runtime.html)、[State API](https://microsoft.github.io/autogen/stable/reference/python/autogen_agentchat.state.html)。

### 2.3 LangGraph / LangChain：用图状态、checkpoint 和明确的工作流模式承载长任务

LangGraph 官方文档把 Agent 工作流分成两类：**workflow** 有预定代码路径，**agent** 动态决定过程和工具。文档给出的编排模式包括：

- **Parallelization**：把相互独立的子任务同时执行，或重复同一任务后比较多个结果，以提升速度或置信度。
- **Orchestrator-worker**：编排器先分解任务，再派发 worker，最后综合 worker 输出；适用于子任务数量和内容无法提前固定的研究任务。
- **Evaluator-optimizer**：一个节点生成，另一个节点评估并反馈，直到满足质量条件；适合报告草稿和引用检查。
- **Persistence**：每个 super-step 保存 checkpoint，支持 human-in-the-loop、会话记忆、时间旅行和故障恢复；失败 super-step 中已完成的 pending writes 可以在恢复时复用，避免重复成功工作。
- **Interrupt**：在任意节点暂停，持久化图状态并等待外部输入；恢复时使用同一个 thread id，状态更新形成新的 checkpoint 而不是覆盖旧 checkpoint。
- **Send / fan-out-fan-in**：可以把一批动态子任务分发给多个节点，再以 reducer 合并结果。

这套抽象把 **checkpoint** 从“保存一段聊天记录”提升为“保存下一步要执行什么、当前状态是什么、哪些写入已完成”。对 NoteWeave 来说，Research 的 `checkpoint` 应至少包含 plan version、当前 cell/row、pending tool call、evidence ids、budget 和 retry metadata。

参考：[Workflows and agents](https://docs.langchain.com/oss/python/langgraph/workflows-agents)、[Persistence](https://docs.langchain.com/oss/python/langgraph/persistence)、[Interrupts](https://docs.langchain.com/oss/python/langgraph/interrupts)、[Graph API](https://docs.langchain.com/oss/python/langgraph/use-graph-api)、[Human-in-the-loop](https://docs.langchain.com/oss/python/langchain/human-in-the-loop)。

### 2.4 CrewAI：用 Flow 控制生产流程，用 Crew 承载自治协作

CrewAI 官方文档将 `Crews` 和 `Flows` 刻意分开：

- Crew 是角色化 Agent 团队，允许动态协作和委派；适用于问题开放、决策路径不确定的部分。
- Flow 是事件驱动的生产工作流，提供 start/listen/router、状态管理、条件分支和持久化执行；文档强调它适合需要精确执行路径和安全状态管理的自动化。
- Flow 状态可以使用结构化模型，也可以使用字典；`@persist` 支持恢复同一状态或从快照派生新分支。状态持久化使流程能够暂停、恢复和在失败后继续。
- Task/Process 提供顺序、层级或混合流程，并支持 guardrail、callback 和 human-in-the-loop 触发器。

它揭示了一个很实用的边界：**确定性编排放在 Flow，开放式推理放在 Crew**。Research 应先用显式状态机约束“搜索、读取、验证、写作”主干，只有某个子任务需要多角色讨论时，才把它包成可回收的协作单元。

参考：[CrewAI 官方文档](https://docs.crewai.com/)、[Crews and Flows](https://github.com/crewAIInc/crewAI)、[Flows（持久化与恢复）](https://docs.crewai.com/en/concepts/flows)。

### 2.5 OpenHands：隔离工具执行，并用事件流保持可观察性

OpenHands 官方 Runtime 采用 client-server 沙箱，文件、命令和代码在隔离 workspace 中执行；Remote Agent Server 管理容器生命周期、多用户隔离和 WebSocket 事件流，SDK 用 typed event 表示 action、observation 和状态更新。对 Research 的推论是：抓取、解析和代码分析应隔离；事件流只负责呈现，不能替代持久化状态，网页正文必须作为不可信输入进入验证。

参考：[Runtime Architecture](https://docs.openhands.dev/openhands/usage/architecture/runtime)、[Remote Agent Server](https://docs.openhands.dev/sdk/guides/agent-server/overview)、[Agent Server Architecture](https://docs.openhands.dev/sdk/arch/agent-server)。

### 2.6 ByteDance DeerFlow：把研究封装为可按需加载的 Skill

DeerFlow 基于 LangGraph/LangChain，组合文件系统、记忆、沙箱、工具和 sub-agent；Skill 以 Markdown 描述流程并按需渐进加载，复杂任务由 lead agent 分解、并行执行后再合成。它证明研究流程可以复用，但 NoteWeave 仍须把 Evidence、ACL、预算和报告版本作为自己的领域事实源。

参考：[DeerFlow](https://github.com/bytedance/deer-flow)、[Deep Research Skill](https://github.com/bytedance/deer-flow/blob/main/skills/public/deep-research/SKILL.md)。

## 3. 关键知识点：一条研究链路需要哪些可验证对象

| 知识点 | 含义 | 在 Research 中应该留下的可追踪对象 |
| --- | --- | --- |
| 对象 | 作用 | 最小可追踪字段 |
| --- | --- | --- |
| Plan / Cell | 子问题、依赖、停止条件 | plan version、cell、budget |
| Candidate / Snapshot | 发现与固定来源内容 | query、URL、时间、digest |
| Claim / Evidence | 报告陈述与支撑片段 | claim id、snapshot、locator、provenance |
| Citation Support / WorkItem Completion | 分开判断证据是否支持 Claim、必做研究单元是否完成 | claim-evidence edge、required/completed work items；比较子集另看 matrix cells |
| Conflict / Verifier | 记录冲突并给出门禁判定 | conflict set、decision、uncertainty |
| Checkpoint / Interrupt | 可恢复和人机暂停边界 | next step、pending writes、payload |
| Budget / Artifact | 资源结算和可复用产物 | reservation、usage、version、manifest |

其中最重要的设计思想是 **evidence-first**：先让结论拥有稳定的证据标识，再让 LLM 负责组织语言；不能反过来先写一篇漂亮报告，再事后猜测每句话的出处。

## 4. NoteWeave 的 A-G 合理演化路线（设计推论）

下面的 A-G 是基于上面公开抽象和 NoteWeave 现有领域模型的推导，不代表已经发生过的实现过程。每一阶段都能独立验证，下一阶段不能绕过上一阶段的事实边界。

### A. 研究范围与来源策略显式化

**目标**：在执行任何搜索前固定 ResearchRun 的问题、范围、语言、时间窗口、允许来源和预算。

**落点**：`Run`、`Control Pack`、`Source Scope`、`Budget Reservation`。来源模式可以分为 `WEB_ONLY`、`WEB_PLUS_SEEDS` 和 `SOURCES_ONLY`；Workspace 负责归属和 ACL，是否检索某个来源由本次 run 的显式 scope 决定。

**为什么先做**：OpenAI 的计划审阅和来源控制说明，用户应能在运行前决定“研究什么、使用哪些来源”；没有显式 scope，后续 coverage 和引用完整性都无法解释。

**不采用的近似方案**：不让模型根据 workspace 中“可能有用的资料”隐式搜索全库，也不把一个默认 Provider 当成事实源。这样做虽然短，但会把权限、成本和来源选择隐藏在 prompt 行为里。

### B. 计划、子问题和可停止的研究表

**目标**：将研究目标编译成可检查的子问题、实体、行和单元格，而不是仅保存在自然语言计划中。

**落点**：`ResearchPlan`、`ResearchEntity`、`ResearchRow`、`ResearchCell`。每个 cell 需要 `required_evidence_type`、状态、候选来源、失败原因和下一步建议；planner 可以动态追加 cell，但不能静默删除已承诺的覆盖项。

**技术名词**：table-as-state、orchestrator-worker、fan-out/fan-in、coverage ratio。LangGraph 的 orchestrator-worker 与 DeerFlow 的 scoped sub-agent 都说明，复杂任务要把“分解”和“合成”从模型自由文本中提取成可观测结构。

**不采用的近似方案**：不把完整计划作为一段 prompt 交给单个 Agent，再用最终文本猜测是否覆盖；这会让缺失项、重复项和预算消耗不可审计。

### C. 搜索、抓取、阅读与证据快照分层

**目标**：把候选发现、页面抓取、正文阅读、局部提取和证据登记拆成不同动作。

**落点**：`SearchCandidate` -> `ExternalSnapshot` -> `ReadingWindow` -> `Evidence`。候选只代表“可能相关”，不能直接进入报告；只有成功抓取并保存 URL、时间、内容摘要和 locator 后，才能成为可引用 Evidence。

**技术名词**：provider adapter、source provenance、content digest、locator、snapshot isolation。网页内容和文件内容都应被视为不可信输入，工具返回的指令不能改变研究控制流。

**为什么不只使用向量搜索**：向量召回适合发现语义近的片段，但不能单独表达来源时间、权威级别、访问权限、冲突关系和引用位置；它应是 provider/Projection，而不是 Source 真相。

### D. Claim-Evidence 对齐与覆盖率门禁

**目标**：写作前先建立结论和证据的二部图，并对每个 cell 计算覆盖、可信度和冲突状态。

**落点**：`Claim`、`Evidence Manifest`、`Citation Edge`、`Verifier Decision`。最小门禁包括：每个高重要性 claim 至少一条有效证据；证据 locator 可回到 snapshot；来源不是同一页面的重复镜像；冲突没有被平均抹平；缺证据的句子必须降级为“未知/待核实”。

**技术名词**：citation completeness、source diversity、authority weighting、contradiction set、confidence calibration。OpenAI 官方资料明确承认来源权威性判断和置信度校准仍是风险，因此必须显式记录不确定性。

**不采用的近似方案**：不把“引用数量”当作质量分数，也不让生成模型在报告末尾自动补一组无对应关系的链接；数量不能证明 claim 被支持。

### E. 有界并行与 canonical merge

**目标**：在独立 cell、来源或验证维度之间并行，但所有结果必须经过确定性的合并门禁。

**落点**：`Worker Task`、`Lease`、`Fencing Token`、`Candidate Set`、`Canonical Merge`。并行分支只写自己的命名空间，返回结构化 candidate/evidence；merge 负责去重、排序、冲突聚合和覆盖更新，不让分支直接改写 canonical report。

**参考推理**：LangGraph 的 parallelization 和 Send API 说明 fan-out/fan-in 的必要性；AutoGen 的消息 runtime 说明跨 Agent 应传消息而不是共享实例；CrewAI 的 Flow/Crew 分界说明确定性主干应与自治协作分开。

**不采用的近似方案**：不一开始启动大量全自主 Agent。并行度过高会增加重复来源、token 和写入竞争；单 Agent 仍能完成的子任务不应被拆成团队。

### F. Checkpoint、取消、预算和人机协作

**目标**：让运行在网络故障、Provider 超时、用户修改方向或人工审核时安全停下并继续。

**落点**：`Checkpoint` 保存 plan version、当前 cell、pending action、evidence ids、retry policy、budget ledger 和下一状态；`Cancel` 是状态机事件，不是直接杀进程；`Interrupt` 产生可解释 payload，恢复时使用同一 run/checkpoint 关系。预算采用 reserve -> consume -> settle/release，取消时保留已产生的 Evidence 和费用记录。

**参考推理**：LangGraph 将 checkpoint 与 HITL、故障恢复和时间旅行绑定；AutoGen 提供外部终止和状态保存；CrewAI 支持恢复或从快照分支；OpenAI Deep Research 允许运行中中断并修改方向。

**不采用的近似方案**：不把 HTTP 请求生命周期当作研究生命周期，也不把取消实现成粗暴的进程终止。前者无法处理小时级任务，后者会丢失证据、费用和可恢复位置。

### G. 报告 Artifact、写回与持续质量反馈

**目标**：将报告视为带版本、来源清单和质量摘要的 Artifact；只有通过门禁或明确人工批准才可写回 Note/Wiki/Source。

**落点**：`ReportArtifact`、`ReportVersion`、`Evidence Manifest`、`Quality Summary`、`Save As Source`。报告至少应包含：问题和范围、方法、结论、每条结论的引用、冲突与限制、来源列表、覆盖率和 verifier 结果。

**技术名词**：immutable version、writeback gate、quality regression、human approval、outcome feedback。写回是跨模块副作用，必须重新做 ACL、版本冲突和幂等检查。

**不采用的近似方案**：不直接把最后一段 Markdown 覆盖为 Source，也不让 Research Agent 绕过 Java 控制面直接写主库。这样会失去报告版本、撤销和跨模块审计。

## 5. 质量门禁：把“研究得好不好”拆成可测量问题

建议把质量拆成四层，而不是给最终文章一个模糊分数：

1. **来源层**：URL 可访问、快照完整、来源类型和时间符合 scope、抓取内容未被错误重定向；重复镜像应去重。
2. **证据层**：每个 Evidence 有 locator 和 digest；引用片段能在 snapshot 中定位；证据与 claim 的关系有支持、补充或冲突语义。
3. **覆盖层**：必答 cell 全部完成，未完成项有原因；source diversity 达到要求；同一结论不只依赖一个低权威来源。
4. **写作层**：报告中的句子能映射到 claim；不确定性、时间范围和反例被保留；引用不集中堆在段末而无法知道对应关系。

当任意一层不通过时，运行应产生结构化 `VerifierDecision`：`PASS`、`PASS_WITH_LIMITATIONS`、`NEEDS_RESEARCH`、`BLOCKED`。这比让 Agent 自己说“我已经验证过”更可靠。

## 6. 为什么当前不直接采用“全自治多 Agent”

这些方案不是永远不能用，而是缺少前置约束时会放大风险：

| 近似方案 | 看起来的好处 | 当前不作为默认主干的原因 |
| --- | --- | --- |
| 单次大 Prompt | 实现快、上下文连续 | 无法恢复、无法解释覆盖率和预算，失败时只能从头重跑 |
| 全自治多 Agent | 覆盖面大、可并行 | 需要共享状态协议、终止条件、幂等和 canonical merge；否则重复和冲突不可控 |
| 只用搜索摘要 | 延迟低、成本低 | 摘要不是稳定证据，缺少正文快照、定位和上下文 |
| 只用向量/图谱召回 | 便于相似度检索和关系扩展 | 不能独立承担 ACL、来源版本、时间过滤和引用证明 |
| 直接使用任意 MCP | 工具丰富、扩展快 | 工具权限、凭据、外部副作用和提示注入边界难以统一，必须先走 Skill/Provider admission |
| 直接 Event Sourcing 全部状态 | 事件回放强 | Research 的查询和审核仍需要直接可读的业务投影；全量事件源会增加一致性和迁移复杂度 |
| 同步 HTTP 长连接 | 调用模型简单 | 不适合分钟到小时任务、断线恢复和并行 worker；应采用 Task + event/replay |

因此，合理策略是“确定性控制面 + 可替换执行面 + 证据门禁 + 有界自治”。当 Evidence、checkpoint、预算和取消已经稳定时，才逐步增加并行 Agent 或更动态的规划器。

## 7. 与 NoteWeave 现有模型的对照

现有架构文档已经包含 `ResearchRunController`、`research_agent_outbox`、Worker lease/fencing、checkpoint、evidence manifest、external snapshot、budget reservation 和 report artifact 等概念；它们可以按下列方式解释：

```text
ResearchRun / Control Pack
  -> Planner / rows / cells
  -> research_agent_outbox
  -> Worker task + lease/fencing
  -> search / fetch / read / extract
  -> external snapshot + evidence manifest
  -> local verifier + global verifier
  -> checkpoint / cancel / resume
  -> report artifact / save-as-source
```

这条解释把已有对象放回统一的 Research 语义中：

- `Workspace` 是归属、ACL 和配额边界，不自动等价于搜索范围。
- `Run` 是一次有输入、预算、状态、事件和输出的可恢复执行。
- `Worker` 是执行面，不拥有主业务库写权限；Java 控制面负责状态、幂等和最终写回。
- `Evidence` 是可追溯依据，必须指向 Source/ExternalSnapshot 的具体版本和片段。
- `Checkpoint` 是恢复边界，不是简单的日志；基础设施恢复应创建新的 ExecutionAttempt 关联旧 Checkpoint，保留稳定 ResearchRun 和旧 Attempt 历史。只有用户目标或资料范围变化时才创建新 Run。
- `ReportArtifact` 是带来源和质量摘要的产品版本，写回 Note/Wiki 前必须经过版本和权限检查。

详细接口和运行边界以[DeepResearch / ResearchAgent 当前架构](../DeepResearch-ResearchAgent架构文档.md)为准；本文只负责补上业界对照和推导出的演化解释。

## 8. 建议的验证清单

每个 Research 能力完成后，至少应有以下验证问题：

- 计划是否能在开始前被查看和修改？修改后是否产生新的 plan version？
- 每个搜索结果是否能区分 candidate、snapshot 和 evidence？
- 报告中的 claim 是否能逐条回到 evidence locator？
- 必答 cell 缺失时，系统是否拒绝声称“已完成”？
- 并行 worker 重试或重复回调时，是否只产生一个 canonical 结果？
- 取消、超时、Provider 失败和进程重启后，是否能从 checkpoint 继续？
- 预算耗尽时是否停止扩张搜索，并结算已消费资源？
- 高风险写回、执行代码或启用外部工具时，是否会触发明确的人机审批？
- 报告是否同时保留结论、证据、冲突、限制和质量摘要？

这些检查将“一个模块一个功能”的演化变成可验收的接口契约，而不是继续堆积过程说明。

## 9. 一手资料索引

- [OpenAI Introducing deep research](https://openai.com/index/introducing-deep-research/)
- [OpenAI Deep research in ChatGPT](https://help.openai.com/en/articles/10500283-deep-research)
- [OpenAI Deep research System Card](https://openai.com/index/deep-research-system-card/)
- [Microsoft AutoGen Teams](https://microsoft.github.io/autogen/stable/user-guide/agentchat-user-guide/tutorial/teams.html)
- [Microsoft AutoGen Distributed Agent Runtime](https://microsoft.github.io/autogen/stable/user-guide/core-user-guide/framework/distributed-agent-runtime.html)
- [LangGraph Workflows and agents](https://docs.langchain.com/oss/python/langgraph/workflows-agents)
- [LangGraph Persistence](https://docs.langchain.com/oss/python/langgraph/persistence)
- [LangGraph Interrupts](https://docs.langchain.com/oss/python/langgraph/interrupts)
- [CrewAI Documentation](https://docs.crewai.com/)
- [CrewAI source repository](https://github.com/crewAIInc/crewAI)
- [OpenHands Runtime Architecture](https://docs.openhands.dev/openhands/usage/architecture/runtime)
- [OpenHands Agent Server](https://docs.openhands.dev/sdk/arch/agent-server)
- [ByteDance DeerFlow](https://github.com/bytedance/deer-flow)
- [DeerFlow Deep Research Skill](https://github.com/bytedance/deer-flow/blob/main/skills/public/deep-research/SKILL.md)

## 10. 公开 Benchmark 与论文指标

这一节专门回答“一个 Research Agent 的公开宣传指标到底测了什么”。Benchmark 的分数不能脱离数据集、工具、模型、评测时间和判分方式解释；尤其不能把浏览器任务成功率、短答案准确率直接当成长报告质量。

### 10.1 DeepResearch Bench：最接近长报告、引用和领域研究质量

[DeepResearch Bench 论文](https://arxiv.org/abs/2506.11763)和[官方项目页](https://deepresearch-bench.github.io/)提供了较完整的报告级评测：100 个 PhD-level 任务，覆盖 22 个领域，中英文各 50 个。它把评测拆成两个框架：

- **RACE**（Reference-based Adaptive Criteria-driven Evaluation）使用参考报告和动态权重，衡量完整性、深度、指令遵循和可读性等报告质量维度；它还用 Pairwise Agreement Rate、Pearson/Spearman 等指标检查自动评审与专家偏好的相符程度。
- **FACT**（Factual Abundance and Citation Trustworthiness）衡量证据收集与引用可靠性。核心指标是 **Citation Accuracy**（被来源支持的 statement-URL 对占可引用 statement 的比例）和 **Effective Citations per Task**（每个任务平均有多少条被有效支持的 statement）。

该 Benchmark 的最大价值不是某个厂商的总分，而是把“写得完整”和“引用真的支持这句话”分开。对 NoteWeave 最适合借鉴的字段是 `claim_id`、`evidence_id`、`locator`、`citation_accuracy`、`effective_citations` 和 `coverage`；若要做简历指标，应先固定任务集、Provider、模型、judge 版本和报告格式，再报告自己的复现实验，不能直接引用官方 leaderboard 的他人分数。

### 10.2 BrowseComp：测“能否找到难找的信息”，不是测报告质量

[OpenAI BrowseComp](https://openai.com/index/browsecomp/)发布了 1,266 个难以通过普通搜索直接找到答案的问题，强调信息分散、实体关系纠缠和需要浏览多个网站。论文/官方说明主要使用 answer correctness 的 **pass rate / accuracy**，也讨论单次、best-of-N 和多次采样聚合；官方分析显示，任务难度分布很宽，不能仅看平均分。

它适合评估 NoteWeave 的 `search -> fetch -> read -> answer` 基础能力，特别是 query expansion、回溯和多来源查找；不适合直接代表 `ReportArtifact` 质量，因为最终答案短、没有强制 claim-citation 图，也不考察报告结构、冲突说明和写回门禁。简历可以写“在固定 BrowseComp 子集上验证了搜索召回/答案正确率”，不能写成“深度研究报告质量达到某百分比”。

### 10.3 GAIA：通用助理的短答案、工具和多模态综合能力

[GAIA 原始论文](https://arxiv.org/abs/2311.12983)包含 466 个由人类设计和标注的问题，覆盖日常助理、科学和一般知识，部分题目附带图片、表格、音频或视频。题目按所需步骤和工具数量粗分 Level 1、2、3；评分通常是归一化后的准 exact match，因为答案设计成字符串、数字或短列表，便于自动判分。

GAIA 测的是“能否通过推理、浏览、文件处理和工具使用得到唯一答案”，不是长文引用完整性。OpenAI 的发布页曾报告 Deep Research 在 GAIA Level 1/2/3 的结果和平均值，但该数字属于当时特定模型、工具和评测设置，不能迁移为 NoteWeave 的系统指标。对 NoteWeave，GAIA 适合做端到端工具回归的外部参照；应另外记录 source snapshot、证据链和恢复状态。

### 10.4 AssistantBench：真实、耗时网页任务的 answer rate / precision / exact match

[AssistantBench 官方页面](https://assistantbench.github.io/)收录 214 个真实且耗时的网页任务，涉及 525 个页面和 258 个网站。官方表格同时报告 **Accuracy、Answer Rate、Precision、Exact Match**；其任务要求跨页面导航、规划执行并在步骤间传递信息。该项目公开结果显示当前系统整体仍很难，最佳公开配置的 accuracy 约为四分之一量级。

它对 NoteWeave 的启发是把网页研究拆成“找到信息”和“最终回答正确”两个指标，并保留失败轨迹；但它更偏浏览器执行，不等价于 Research 的 Evidence/Report 质量。适合放入浏览器 Provider 的 smoke test，不适合作为简历中 Research Agent 的唯一质量证据。

### 10.5 Mind2Web：网页动作级与整任务级成功率

[Mind2Web 原始论文](https://arxiv.org/abs/2306.06070)提供超过 2,000 个开放任务，来自 137 个网站和 31 个领域，目标是评估跨网站泛化的网页 Agent。其评测将动作分解为：

- **Element Accuracy**：是否选中了可接受的页面元素；
- **Operation F1**：点击、输入、选择等操作参数是否正确；
- **Step Success Rate**：单步的元素和操作都正确；
- **Task Success Rate**：整条动作序列每一步都成功。

Mind2Web 的强项是把“整任务失败”定位到具体操作步骤；这与 NoteWeave 的 `row/cell` 和 `worker task` 很相似，可借鉴 step-level telemetry。但它评估的是网页动作，不评估引用和长报告。当前若使用其在线变体，还要记录网页漂移、登录状态和任务版本，否则不同时间的分数不可比。

### 10.6 WebArena：可复现网页环境与功能正确性

[WebArena ICLR 论文](https://proceedings.iclr.cc/paper_files/paper/2024/hash/4410c0711e9154a7a2d26f9b3816d1ef-Abstract-Conference.html)提供可自托管的真实网站环境，覆盖电商、论坛、协作开发和内容管理等场景，任务按最终状态的**功能正确性**评分。论文中的 baseline 报告端到端 task success rate，并将代理结果与人工表现比较；它的目标是测长时域导航、状态修改和工具交互，而不是文字报告。

WebArena 适合验证 NoteWeave 的浏览器/Artifact 执行隔离、取消和失败恢复，尤其适合作为“外部副作用前必须审批”的安全测试环境。它不应被当成 Research 报告准确率，也不能替代固定来源快照下的证据回归。

### 10.7 Microsoft LiveDRBench：将 Deep Research 定义为“高 fan-out 的概念探索”

Microsoft Research 的[Characterizing Deep Research](https://www.microsoft.com/en-us/research/publication/characterizing-deep-research-a-benchmark-and-formal-definition/)提出 LiveDRBench：100 个科学、公共事件和先验检索任务，并用中间的 key-claim representation 把搜索推理与最终文章表面分开。论文强调 Deep Research 的核心不是“报告很长”，而是搜索中对概念进行广泛、高推理量的 fan-out；其指标使用 claim-level **F1**，并分析引用来源数、分支数和 backtracking 事件。

这与 NoteWeave 的 `ResearchEntity / Row / Cell / Claim / Evidence` 最贴近。可借鉴的内部指标包括：关键 claim precision/recall/F1、每个 claim 的有效证据数、搜索分支数量、回溯比例、重复来源率和单位预算覆盖率。若未来做公开简历指标，LiveDRBench 比 GAIA 更能说明“研究过程质量”，但仍需声明自己的检索环境和判分器。

### 10.8 DeepResearchGym：为研究回归固定检索环境

[DeepResearchGym 项目页](https://www.deepresearchgym.ai/)和[原始论文](https://arxiv.org/abs/2505.19253)针对商业搜索 API 动态、昂贵和不可复现的问题，提供基于 ClueWeb22/FineWeb 的可复现搜索 API，并以 DiskANN 提供稳定排名。它扩展 Researchy Questions，通过 LLM-as-a-judge 评估用户信息需求对齐、检索 faithfulness 和报告质量，并用人工评估检查自动指标是否一致。

它最适合作为 NoteWeave 的本地回归基础：固定 corpus、固定搜索版本、固定任务和固定 judge，便于比较 parser、provider、planner 或 verifier 的改动。它的指标不能自动代表生产环境，因为固定语料会牺牲新鲜度；生产还要另测动态网页、来源过期和权限隔离。

### 10.9 STORM：论文/开源项目可报告的方法效果，但不要伪装成通用榜单

[STORM 论文](https://arxiv.org/abs/2402.14207)和[Stanford OVAL 官方仓库](https://github.com/stanford-oval/storm)把长文研究拆成 pre-writing 和 writing：先发现多视角、模拟作者与专家对话、检索并建立层级大纲，再按大纲写作。论文用 FreshWiki 做大纲和文章评估，并报告相对 outline-driven RAG baseline 的组织性绝对提升约 25 个百分点、覆盖约 10 个百分点；同时通过 Wikipedia 编辑反馈暴露来源偏差传播和无关事实过度关联等问题。

STORM 的数字是特定 FreshWiki 数据和人工/自动评测设置下的相对结果，适合在简历中作为“采用多视角问题生成和 outline-first 设计的论文依据”，不适合写成 NoteWeave 自身的分数。其结构可以直接启发 NoteWeave 的 Planner、ResearchCell 和 Report outline。

### 10.10 GPT Researcher 与 DeerFlow：架构宣传指标必须与论文评测分开

[GPT Researcher 官方仓库](https://github.com/assafelovic/gpt-researcher)公开了 planner、execution/crawler、source tracking 和 publisher 的分层架构，并支持并行、网页/本地混合检索、报告导出和多 Agent；仓库本身没有一套可与上述论文直接对齐的标准分数。其[产品页](https://gptr.dev/)引用 DeepResearchGym 并展示 citation precision/recall、report quality 和 key-point recall 等宣传数字，但这些数字应被视为项目方在特定配置下的报告，除非同时核对 DeepResearchGym 的任务、评测脚本和模型版本，不能直接写“行业 SOTA”。

[DeerFlow 官方仓库](https://github.com/bytedance/deer-flow)重点宣传长时域 SuperAgent harness、skills、sub-agents、memory、sandbox 和 message gateway；其 README 更像架构和能力说明，而不是一套固定 benchmark 结果。对 NoteWeave 的借鉴是“如何把研究做成可加载 Skill、如何有界并行和管理长任务”，而不是复制未经同协议复现的分数。

## 11. 哪些指标适合写入 NoteWeave 简历

| 评测对象 | 主要测量 | 与 NoteWeave 的匹配度 | 可写入简历的条件 |
| --- | --- | --- | --- |
| DeepResearch Bench | 报告完整性、深度、引用准确率、有效引用数 | 高 | 固定任务、模型、Provider、judge 和报告版本，给出可复现实验 |
| LiveDRBench | Claim 发现、fan-out、回溯与 grounded research | 高 | 保存中间 claim/evidence 表，并公布任务和检索环境 |
| DeepResearchGym | 可复现检索、需求对齐、faithfulness、报告质量 | 高 | 固定 corpus、搜索 API、评测脚本和成本 |
| BrowseComp | 难找信息的搜索/答案正确率 | 中 | 只声称浏览问答能力，不夸大为报告质量 |
| GAIA | 通用助理、文件/多模态/工具端到端答案 | 中 | 明确 Level、附件、工具和 exact-match 规则 |
| AssistantBench | 真实网页任务的 accuracy/precision/exact match | 中低 | 只用于浏览器 Provider 或工具执行能力 |
| Mind2Web / WebArena | 页面动作和功能状态成功率 | 中低 | 只用于浏览器/Artifact 执行链路，说明环境版本 |
| STORM | 多视角研究、大纲、组织与覆盖的论文结果 | 方法参考 | 写“借鉴/实现方法”，不要把论文相对提升写成自己的结果 |
| GPT Researcher / DeerFlow | 开源架构和能力宣传 | 方法参考 | 只引用可核对的源码/文档，不直接搬产品页的 SOTA 口号 |

更稳妥的 NoteWeave 简历表达是：“设计并实现 evidence-first Research Run：以 claim/evidence ledger、source snapshot、coverage/verifier、checkpoint/resume 和 bounded fan-out 合并支撑可追溯报告；在固定任务集上分别报告 citation accuracy、claim coverage、task success、恢复成功率、单位预算成本和延迟。” 只有真正跑过并保存评测包后，才填写具体百分比。

## 12. Benchmark 不能替代的生产指标

公开数据集大多不覆盖 NoteWeave 的多租户和长任务约束，因此还需要本地指标：

- **Evidence**：claim support precision、citation completeness、source diversity、snapshot 可重放率。
- **Research process**：必答 cell coverage、重复来源率、backtracking ratio、每 cell 平均搜索次数、预算消耗和取消响应时间。
- **Reliability**：checkpoint 恢复成功率、重复 callback 幂等率、lease/fencing 冲突率、worker redrive 成功率。
- **Safety and governance**：越权来源拒绝率、提示注入隔离率、人工审批命中率、写回冲突拒绝率。
- **Product**：报告首屏时间、完整报告时间、用户修改计划后的完成率、save-as-source 成功率。

这些指标比“跑过某个排行榜”更能证明 NoteWeave 的系统设计，因为它们直接对应 Workspace、Source、Evidence、Run、Worker、Checkpoint 和 Artifact 领域对象，也能被固定 fixture 和 verifier 自动复核。
