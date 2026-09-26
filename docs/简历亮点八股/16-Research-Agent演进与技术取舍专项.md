# Research Agent 演进、技术选择与 Trade-off 专项

> 默认主回答见[Research Agent 一体化面试手册](30-Research-Agent一体化面试手册.md)。本文只负责解释每次演进的触发问题、候选方案、参考依据、最终选择和代价，适合面试官追问“为什么这样设计”时使用，不再作为另一份默认 4 分钟稿。

> 证据边界：本文把三类内容分开。`[当前实现]` 来自当前源码、迁移、配置、测试或 Git 历史；`[设计演进]` 是用 Bad Case 解释架构选择的面试叙事，不等于每个阶段都曾独立上线；`[行业参考]` 只说明选择有外部依据，不能当作 NoteWeave 的成绩。真实生产指标仍按 `[生产待验证]` 处理。

权威实现见 [Research Agent 架构](../DeepResearch-ResearchAgent架构文档.md) 和 [详细架构与具体设计](01-Deep-Research-Agent-详细架构与具体设计.md)，外部依据见 [Research Agent 演进取舍外部依据](../research/Research-Agent演进取舍外部依据.md)；产品/框架对照见 [Deep Research 业界演化研究](../research/DeepResearch业界演化研究.md)。具体学校案例、消融设计、Verifier 校准、失败窗口和 AI 协作 Ownership 见[真实案例、消融实验与 Ownership 答辩](17-Research-Agent真实案例消融实验与Ownership答辩.md)。本文只负责把“问题、候选、选择、原因、代价、验证和下一步”串成面试主线。

## 1. 一句话定位

NoteWeave 的 Research Agent 不是把搜索工具接给模型后循环调用，而是一条可恢复的证据生产线。用户给出研究目标、资料范围和预算，系统把开放问题编译成可检查的 Matrix/Cell，Worker 只提交 Candidate、Evidence 和审计结果，Java Host 负责 Canonical Ledger、权限、任务、预算和最终提交。报告只能从通过验证的 Canonical State 生成。

学校场景给这套设计提供了业务起点。普通课程问答只需要在已有资料中检索并回答；课程调研、实验综述和多方案比较还要发现实体、补齐多个字段、处理来源冲突、跨较长时间运行，并解释哪些结论有证据、哪些仍不确定。Research Agent 解决的是这个增量问题，不替代 QA RAG。

## 2. 两条时间线必须分开

### 2.1 Git 可以验证的实现时间线

| 时间 | 仓库证据 | 能说明什么 | 不能说明什么 |
|---|---|---|---|
| 2026-06-30 | `4ee947e8`，新增 Deep Research Harness 设计 | Research 的评测和架构设计开始形成独立专题 | 不能说明当时已经具备完整运行时 |
| 2026-07-05 | `cd1e5788` 到 `a463b500`，补充 Loop、Search/Read Adapter、Extraction、Callback、Kafka Dispatch 与 Report Save | 单 Worker 研究闭环、跨语言调度和报告写回进入代码 | 不能说明已经完成分布式恢复与多角色治理 |
| 2026-07-18 | `a89869b6`、`a80c5529`，加入 Durable Multi-agent Execution，并把 Incremental Path 设为权威路径 | Cell Task、Lease/Fencing、增量提交和受控角色开始成为主线 | “Multi-agent”不等于默认大规模并行，当前默认 Agent 并发仍为 1 |
| 2026-07-18 | `244219fc`、`db56de94`，要求外部 Evidence 在完成前归档并受 Task Scope 约束 | 外部网页从临时 Tool Output 变成带身份、范围和归档边界的 Evidence | 不能推导所有网页质量都可靠 |
| 2026-07-19 | `7f094853`，解耦 Research Evidence Scope 与 Workspace Source | Workspace 资料和外部研究来源不再被错误合并成同一范围 | 仍需要 Permit、Source Policy 与 Evidence Qualification |
| 2026-07-19 | `0bcbd6c6`，关闭 Web-first Research 的剩余缺口 | 搜索、抓取、归档和引用链进一步加固 | 不代表公开 Benchmark 已达到领先水平 |
| 2026-08 | 当前工作树中的 Completion、Hydration、Role、Rollout 和 Evidence Validation 代码 | 当前源码已经形成更完整的协调、验证与灰度边界 | 未提交内容和当前测试结果要单独核对，不能只引用日期 |

### 2.2 面试使用的设计演进线

设计演进线回答“如果从最小方案开始，为什么会自然走到当前结构”。它不是发布日志。

```text
课程资料 QA
  -> 单次长 Prompt
  -> ReAct Search/Read Loop
  -> Intent + Plan-and-Execute
  -> Matrix / Cell Table-as-State
  -> Candidate + Evidence + Canonical Merge
  -> Deterministic Rules + Local/Global Verifier
  -> Lazy Replan + Bounded Repair
  -> Checkpoint Hydration + Receipt
  -> Java Host + Python Worker + Outbox/Kafka
  -> Bounded Role Tasks + Wave Barrier
  -> Synthesis Candidate + Backend Atomic Completion
  -> Gold/Shadow/Rollout/Cost Governance
```

## 3. 为什么从课程 QA 继续做 Research Agent

QA 的输入范围和输出契约相对稳定：在当前 Workspace 的 Source Snapshot 中检索证据，对一个问题生成带引用回答。它可以用一次 Retrieval Plan 和一个 AnswerRun 表达。

Research 的完成条件不同。一个“比较三种技术路线并给出学校部署建议”的任务，至少包含候选发现、比较维度、每个候选的事实字段、来源独立性、冲突、缺口、预算和最终报告。一次检索命中不等于任务完成，生成一篇长文也不能证明关键字段已覆盖。

因此当前系统没有让 Research 复用普通聊天状态。`ResearchRun`、Matrix、Row、Cell、Evidence、Candidate、Checkpoint、Budget、Role Result 和 Artifact 分别保存目标、过程、事实和交付结果。代价是领域对象增多，迁移、查询和测试更复杂。若需求只是对一份课件做一次问答，直接走 QA 或 Note，Research Agent 属于过度设计。

## 4. V0：为什么单次长 Prompt 只适合验证需求

### 4.1 最小方案

把研究问题、课程资料和“请给出详细报告”放进一个 Prompt，等待模型一次生成。它的优点是实现最少、首版交付快，适合验证用户是否需要报告形式的产物。

### 4.2 暴露的问题

- 模型可能在没有直接证据时补齐看似合理的段落。
- 引用通常在写作阶段补，无法证明 Citation 真正支持 Claim。
- 多个实体和维度的缺口藏在自然语言中，程序无法判断完成度。
- 调用超时或进程退出后，只能重新开始，可能重复搜索和计费。
- 输入资料发生变化时，无法解释报告使用了哪个版本。

### 4.3 为什么没有停在这里

Research 的核心产物不只是文本，而是“可核验结论及其来源”。单次 Prompt 没有稳定中间状态，也没有可以编程检查的停止条件。当前系统只在最终 Synthesis 阶段生成连续文本，前面的研究过程必须外部化。

**Trade-off**，拆分步骤会增加延迟、Provider 调用和工程成本。对于低风险、短问题和已有完整资料的任务，单次生成仍可能是更合理的产品路径。

## 5. V1：为什么先采用 ReAct，又不让 ReAct 成为业务真源

### 5.1 参考了什么

`[行业参考]` ReAct 将推理轨迹与工具动作交错，让模型根据观察结果继续搜索或调整方向。它很适合验证 Search、Fetch、Read 等工具能否支持未知路径探索，也比固定脚本更能处理开放问题。

### 5.2 为什么适合原型

Research 的搜索路径无法完全预先写死。模型需要根据已读内容决定下一条 Query、是否扩大来源、是否回查术语。ReAct 提供了最小自治循环，早期可以快速观察模型实际需要哪些 Tool 和 Trace。

### 5.3 为什么当前不保存 ReAct 对话作为状态

长任务中，Thought/Action/Observation 轨迹会持续增长。对话能说明模型做过什么，却不能稳定回答以下问题：

- 哪些 Required Field 已验证。
- 哪些字段只有 Candidate，没有 Evidence。
- 两个来源冲突影响哪个结论。
- 恢复时哪些外部动作可以复用。
- 旧 Worker 的最后一条 Observation 是否仍属于当前 Plan。

当前选择是保留 ReAct 风格的局部工具决策，但 Canonical State 由结构化 Ledger 承担。模型可以决定下一步怎么找，不能自行决定业务事实已经成立。

**Trade-off**，模型自由度下降，任何新 Tool 和状态都要进入 Schema、权限和审计。问题路径短、失败可接受且不需要恢复时，自由 ReAct 更简单。

## 6. V2：为什么 Intent 固定，Plan 可以改变

### 6.1 比较过的方向

| 方向 | 优点 | 失败模式 |
|---|---|---|
| 每轮重新生成计划 | 灵活 | 目标、来源和完成标准会随上下文漂移 |
| 完全固定 Workflow | 确定、易测 | 遇到新实体、来源失败和冲突时无法调整 |
| Intent + Versioned Plan | 目标稳定，路径可变 | 需要 Plan Revision、影响范围和 Replan 审计 |

### 6.2 当前选择

`ResearchIntent` 固定目标、必填结果、来源边界、时间范围、输出规格和预算。Plan 负责完成路径，包括 Matrix、查询族、阶段、依赖、Checkpoint 和 Stop Contract。只有出现可观测偏差时才 Lazy Replan，例如关键来源失败、冻结实体策略内发现新别名或漏项、字段长期缺证或冲突无法消解。用户改变实体范围、研究目标或交付标准时创建新 ResearchRun，不在原 Run 内改写 Intent。

Replan 不能降低 Intent 的必填字段来制造“完成”。新 Plan Revision 要记录原因、受影响 Cell、预算变化和 Digest；未受失效假设影响且已经验证的 Evidence 保留，避免全量重跑。

### 6.3 为什么不每轮 Replan

频繁 Replan 会产生计划抖动，旧 Task 与新 Plan 竞争，预算不断消耗在重新规划上。当前优先做局部 Search、Read Window 扩大或 Counterfactual Repair，只有局部动作无法解决的偏差才升级为 Replan。

**Trade-off**，Lazy Replan 可能反应较慢，也要求系统正确判断偏差范围。若未来任务依赖图高度动态，需要更细的局部子图失效和 Plan Diff，而不是简单增加 Replan 次数。

## 7. V3：为什么从通用 Matrix 演进为 Typed WorkItem

### 7.1 候选方案

**保存完整对话**，开发最简单，但缺口、版本和冲突不可计算。**文档级状态**适合“一篇来源是否处理完”，却无法表达一个来源只支持部分字段。**自由 JSON**灵活，但 Schema 不稳定会让恢复、合并和评测困难。**Row/Cell Matrix**要求先定义实体和维度，但每个缺口有稳定地址。

### 7.2 Matrix 适合什么

实体作为 Row，研究维度作为 Column，交叉位置成为 Cell。Cell 保存 Candidate Value、Evidence Ref、Conflict Ref、Status 和 Version。对“多个方案 × 多个维度”的比较任务，它可以直接计算 Matrix Cell Coverage、定位 GAP/STALE/CONFLICT、分配 Task，并通过版本 CAS 合并。

`[行业参考]` STORM 的 outline-first 和多视角研究说明，长文研究需要先建立结构再写作；LiveDRBench 使用 Key Claim Representation 将搜索过程与文章表面表达分开。NoteWeave 没有复制它们的数据结构，而是把学校调研和多实体比较落为 Matrix/Cell。

### 7.3 推荐选择和边界

把 Table-as-State 作为通用模型，会把开放问题压进预定义结构。叙事性、探索性、因果调查和时间线任务不应通过不断增加行列勉强表达。推荐的 ResearchPlan 使用 Typed WorkItem：比较任务生成 `MATRIX_CELL`，事实调查生成 `CLAIM_INVESTIGATION`，开放探索生成 `OPEN_QUESTION`，时间任务生成 `TIMELINE_EVENT`，关键来源核验生成 `SOURCE_AUDIT`。

统一的是 WorkItem 身份、版本、Candidate、Evidence 和调度合同，不统一每种任务的验收语义。Matrix 仍是学校方案比较的默认 Planner，但不再代表整个 Research Agent。这样开放问题不需要等待将来“重新评估 Cell”，Planner 在创建计划时就能选择合适的工作单元。

## 8. V4：为什么 Worker 只提交 Candidate，不能直接写 Cell

### 8.1 直接写的风险

模型或 Worker 搜到一个结果后直接更新 Current Value，最简单的合并策略就是 Last Write Wins。它把“更新得更晚”等同于“结论更正确”，旧 Worker 晚到、重复消息或来源冲突都会覆盖已经验证的值。

### 8.2 比较过的合并策略

| 策略 | 优点 | 为什么没有作为通用规则 |
|---|---|---|
| Last Write Wins | 实现最小 | 时间顺序不代表事实质量 |
| Majority Vote | 能吸收部分随机噪声 | 同站转载或同一上游会形成伪多数 |
| Authority First | 法规、官方参数等字段有效 | 开放问题不总有唯一权威来源，权威也可能过期 |
| LLM Judge 直接选 | 能处理语义差异 | 判断不可稳定复现，也可能忽略租户和版本边界 |
| Independent Candidate Quorum + Merge Gate | 可以检查来源独立性、版本和证据 | Provenance、归一化和调度成本更高 |

### 8.3 当前选择

Worker 产生 Candidate 和 Evidence，Java Host 的 Canonical Ledger 才拥有 Current Value。Merge Gate 校验 Task、Plan Revision、Cell Expected Version、Lease Epoch、Fencing Token、Evidence Scope 和 Payload Digest。高风险 Cell 可以要求两个独立 Candidate，第二个使用受限 `COUNTERFACTUAL` 角色；盲选结果仍要通过事实和证据验证。

这里的 Quorum 不等于两次模型调用。来源必须按 Identity、Lineage、Domain、Snapshot 和上游关系判断独立性。两个页面复制同一篇公告，只算一个来源族。

**Trade-off**，高风险任务成本和延迟增加，Candidate 归一化可能把本质相同的表达误判成冲突。低风险 Cell 默认 Quorum 为 1，不能为了架构完整让所有字段双倍执行。

## 9. V5：为什么从“有引用”演进到 Evidence Qualification

URL 存在只能证明页面被访问过。可靠 Evidence 还要回答：它属于哪个 Run/Cell，是否在允许的 Source Policy 中，引用 Span 是否直接支持 Claim，Snapshot 是否可回放，内容是否被 Prompt Injection 污染，多个来源是否真正独立。

当前 Evidence Qualification 分三层：

1. 身份与范围，校验 Workspace、Run、Task、Cell、Snapshot 和 Digest。
2. 语义支持，校验 Citation Span、Claim 关系、数字、单位、年份、比较方向和否定。
3. 来源质量与独立性，校验 Authority、Lineage、时效、直接性和冲突状态。

外部网页必须先通过 Permit 和安全抓取，再归档 External Snapshot，才能成为 Evidence。搜索结果摘要和未归档 Tool Output 不能直接完成 Cell。

`[行业参考]` OpenAI Deep Research 的公开材料强调引用和来源选择，也明确承认来源权威性、错误事实和置信度仍有风险；DeepResearchBench 将 Citation Correctness、Completeness 和整体报告质量分开。这些依据支持“引用需要单独验证”，不证明 NoteWeave 已达到其产品效果。

**Trade-off**，归档网页会增加存储、隐私和删除成本，严格 Span 校验也可能拒绝合理的跨段推断。系统允许 Guarded/Partial 结果，但不能把不足证据包装成已验证事实。

## 10. V6：为什么同时需要确定性规则、Local Verifier 和 Global Verifier

### 10.1 单层自检的问题

让生成报告的同一个模型再说“答案是否正确”，错误相关性很高。只做规则校验能发现 ID、范围、数字和版本错误，却无法判断复杂语义是否得到支持。只做一个全局 LLM Judge，又难以定位具体哪个 Cell 失败。

### 10.2 当前分层

- 确定性规则先检查 Scope、Digest、版本、数字、单位、年份、比较方向和引用身份。
- Local Verifier 判断单个 Cell 的 SUPPORTS、PARTIALLY_SUPPORTS、CONTRADICTS 或 INSUFFICIENT。
- Global Verifier 从 Canonical Facts 重新计算必填覆盖、关键缺口、跨 Cell 一致性、冲突和报告资格，不直接复制 Local 结论。
- Local/Global 不一致时保留 `VERIFIER_DISAGREEMENT`，进入有界补查或人工判断，不默认放行。

### 10.3 为什么不做无限 Reflexion

`[行业参考]` Reflexion 展示了通过反馈和记忆改进后续尝试的思路，Self-RAG 展示了检索、生成和自我批判的结合。NoteWeave 借鉴“失败后根据明确反馈修复”，但不让模型无界自我反思。Repair 必须绑定 Gap、Conflict、Verifier Reason、预算和次数上限，超过上限的 Cell 进入 FROZEN、BLOCKED 或 Guarded Write。

**Trade-off**，Verifier 增加 Token、延迟和误杀。其 Precision 低会反复拒绝正确证据，Recall 低会放过错误结论，所以需要人工校准、分风险阈值和可回放 Judge 版本。安全与 Scope 规则不能交给可选 LLM Judge。

## 11. V7：为什么从 Context Restart 演进到 Checkpoint Hydration

### 11.1 比较过的恢复方式

| 方式 | 优点 | 问题 |
|---|---|---|
| 从头重跑 | 实现最小 | 重复搜索、重复计费，结果可能漂移 |
| 保存对话后续写 | 上下文连续 | 不能确认哪些外部动作已提交，状态兼容困难 |
| 完整 Event Sourcing | 理论上可重建任意状态 | 事件版本、回放、存储和隐私治理成本高 |
| 结构化 Checkpoint Hydration | 只恢复语义闭包 | 快照 Schema、完整性和边界选择更复杂 |

### 11.2 当前选择

Checkpoint 保存 ResearchPlan/Digest、WorkItem 状态与版本、Accepted Evidence Digest/Lineage、Open Verification/Repair、Budget Summary 和任务高水位。只有真实 Fan-out 才保存 Stage/Barrier。恢复时保持 ResearchRun 身份不变，创建新的 ExecutionAttempt 并推进 Epoch/Fencing Token；复制不可变语义状态，不复制 Active Lease、Task、Outbox、Reservation 或 Receipt，只为 GAP、STALE 和 Open Repair 创建新的 Fenced Task。旧 Descendant Run 方案作为实现迁移背景，不再是推荐设计。

大状态可以放 MinIO，数据库保存索引、摘要、大小和 SHA-256。恢复前先验证版本和摘要。旧格式只能走明确的 `CONTEXT_RESTART` 或失败，不能静默假装 Hydration 成功。

`[行业参考]` LangGraph 把 Checkpointer 与 Thread State 绑定，用于恢复、Interrupt 和 Human-in-the-loop；Temporal 通过事件历史重放实现 Durable Execution。NoteWeave 采用持久状态和恢复思想，但没有把领域真源交给这些框架，当前 Canonical Ledger 仍在 MySQL。

### 11.3 Checkpoint 放在哪里

太频繁会造成数据库写放大、对象膨胀和兼容成本；太稀疏会让恢复重复昂贵调用。当前合理边界是外部调用回执后、Wave/Stage 结束、预算结算和人工暂停点。是否调整要看 Recovery Cost、Duplicate External Action Rate、Checkpoint Size 和 P95 恢复时间。

## 12. V8：为什么采用 Java Host 加 Python Worker

### 12.1 全部放 Python 的优缺点

模型、检索和 Agent 生态更集中，开发迭代快；但当前项目的 Workspace ACL、Source、Task、Outbox、预算、审计和事务真源都在 Java/MySQL。让 Python 直接写业务表会产生双写入口和权限绕过。

### 12.2 全部放 Java 的优缺点

事务和类型边界统一，但模型 Provider、网页抓取、数据处理和评测工具的迭代成本更高，也会把高变化执行逻辑压进核心业务服务。

### 12.3 当前边界

Java Host 拥有身份、权限、Canonical Ledger、Task、预算、Outbox、Feature Flag、Rollout 和最终 Completion。Python Worker 负责 Planner、Search/Fetch/Read、Extraction、Verifier、Repair 和 Synthesis Candidate。Worker 只能通过版本化 Task Snapshot 读取授权输入，通过内部回调提交候选，不能直接写 Java 业务表。

跨语言代价包括 Schema 演进、Canonical JSON、Unicode NFC、重复键、浮点和 Digest 一致性。项目专门定义 Canonicalization 与 Deep Freeze，因为“都算 SHA-256”不能保证两端先得到相同字节。

当执行逻辑稳定、团队只维护 Java 或跨语言运维成为主要成本时，可以考虑合并运行时；当前模型与工具迭代速度使分层仍有价值。

## 13. V9：为什么 Research 使用 Outbox/Kafka、Lease 和 Fencing

Research Run 可能持续数分钟，HTTP 请求不应一直占用连接。Java 事务内创建 Run、Task 和 Outbox，Dispatcher 发布 Kafka Command；Worker Claim 后执行，Heartbeat 续租，完成时使用稳定 Idempotency Key 和 Payload Digest 回调。

Kafka 解决传输、保留和重放，不保证 MySQL、MinIO、外部 Provider 和业务状态 Exactly-once。系统接受 At-least-once，通过以下机制保证业务效果：

- Outbox 保证业务状态和发送意图同事务。
- Task Key 与 Command ID 吸收重复消息。
- Lease Epoch 和 Fencing Token 拒绝旧 Worker。
- Cell Expected Version 使用 CAS 防止旧 Candidate 覆盖。
- Completion Receipt 处理 Host 已提交但响应丢失。
- Unknown Outcome 和对账处理外部副作用无法确认的窗口。

为什么不用直接 HTTP 调 Worker：短任务可以，但长任务需要独立积压、重放、恢复和发布 Drain。为什么不用 Kafka Transaction 解决全部一致性：它不能把普通 MySQL 事务和外部 Provider 自动加入同一原子提交。

**Trade-off**，Kafka、Outbox、Lease 和回调形成多个重试域，错误分类和运维复杂度上升。学校早期规模可以先用 Polling Outbox 直接驱动 Worker；当多 Consumer、保留重放和独立积压成为真实需求时再启用 Kafka。

## 14. V10：为什么不默认运行多 Agent Swarm

### 14.1 多 Agent 带来的真实问题

并行 Agent 能增加覆盖和吞吐，也会增加重复搜索、Provider 限流、预算放大、取消传播、状态合并和错误相关性。多个角色同时写同一事实时，角色数量不会自动带来来源独立性。

### 14.2 当前选择

角色是受控 Task Profile，不是自由聊天人格。调查、反证、来源审计和合成只描述工具、风险和输出 Contract，不决定并发数量。每个角色的 Tool Set、Snapshot Schema、写权限和 Completion Handler 由 Host Registry 决定。

当前配置中 `research_agent_max_concurrency=1`，`fetch_max_concurrency=4`。这说明当前有效优化是 I/O 并行，不是多 Agent。推荐运行时在该阶段使用顺序 WorkItem、依赖计数和阶段游标，不把 Wave/Barrier 放进默认主链。只有独立 WorkItem 比例、Provider Permit、预算和 Merge Contract 都满足阈值时才启用 Fan-out/Fan-in。高风险 Candidate Quorum 最大为 2，也必须先证明来源或判断过程真正独立。

`[行业参考]` AutoGen 的 Team/Message/Termination、CrewAI 的 Flow/Crew 分层、LangGraph 的 Orchestrator-Worker 和 DeerFlow 的 Sub-agent/Skill 都提供了多角色组织方式。NoteWeave 借鉴任务拆分、消息边界和终止条件，保留自己的 Cell、Evidence、Lease 和 Canonical Merge。

### 14.3 何时值得提高并行度

需要同时满足：可并行 WorkItem 比例足够高，Provider TPM/RPM 有余量，数据库和队列没有先饱和，Merge Reject 与重复来源率可控，单位成功成本没有恶化。达到条件后才把相邻 WorkItem 编成 Wave，并为真实 Fan-in 建 Barrier。只看到 P95 较慢就加 Agent，可能让 429、重试和成本更差。

## 15. V11：为什么 Worker 不能直接发布最终报告

Worker 的 Reporter 或 Synthesis Executor 只能读取已经验证的 Canonical Input，输出 Synthesis Candidate、Claim-to-Cell、Citation-to-Evidence、Limitations 和 Input Digest。它不能新增搜索、修改 Cell 或自行把 Run 改为 COMPLETED。

Java Backend 再检查 Stage Settled、无 Active Required Task、无 Open Repair、Evidence Audit、Snapshot Digest、Cell Version、预算结算和 Completion Idempotency，然后在一个本地事务中提交 Artifact、Manifest、业务终态、Receipt 与后续 Outbox。

这个边界避免“报告写出来了”覆盖“研究合同没有完成”。它也处理完成响应丢失：相同 Payload 重放返回原 Receipt，相同 Key 不同 Payload 返回冲突。

**Trade-off**，Backend Validation 与跨语言 Canonicalization 增加完成延迟和协议复杂度。确定性 Renderer 表达有限，但可以作为失败降级和 Citation Completeness 的最低 Oracle；Worker Synthesis 更自然，却必须受 Backend Guard。

## 16. V12：为什么评测不能只看最终报告好不好

最终报告分数会把搜索、证据、验证、恢复和写作混在一起。当前指标按层拆分：

| 层次 | 核心指标 | 对应问题 |
|---|---|---|
| Intent/Plan | Required Goal Coverage、Dependency Validity、Replan Reason | 是否研究了正确的问题 |
| Process | Cell Completion、Repeated Action、Backtracking、Budget | 是否有效推进 |
| Evidence | Citation Support、Source Diversity、Conflict Discovery | 结论是否有可审计依据 |
| Verification | Verifier Precision/Recall、Disagreement、Repair Success | 门禁是否可靠 |
| Reliability | Checkpoint Recovery、Stale Reject、Duplicate Completion | 中断和重放是否破坏结果 |
| Product | Report Adoption、Edit/Restart、Time to Valid Result | 用户是否真的得到价值 |
| Cost | Cost per Successful Run、Marginal Evidence Gain | 质量提升是否值得成本 |

`[行业参考]` BrowseComp 适合测难找信息的搜索与短答案，GAIA 适合测工具和多步骤通用助理，DeepResearchBench 更接近长报告与引用，LiveDRBench 更关注 Key Claim、Fan-out 和 Backtracking，DeepResearchGym 强调可复现检索环境。它们不能互相替代。

面试时还要主动区分两个名称接近的基准：`DeepResearchBench` 是 100 个 PhD 级任务、22 个领域、中英文各 50 题的 RACE/FACT 报告与引用评价；`Deep Research Bench`（标题中有空格，arXiv:2506.06287）是 89 个跨 8 类任务，并提供 RetroSearch 冻结网页环境。前者更适合解释报告质量与 Citation，后者更适合解释研究轨迹和可复现 Web 环境。数字不能混用。

NoteWeave 当前 4 Case/少量 Fixture 只能证明机制和门禁。要宣传外部可比结果，需要固定任务、Source Snapshot、Provider、模型、Judge、脚本和成本，不能把某篇论文或公司的成绩搬成自己的成绩。

## 17. 为什么没有直接采用相近框架

| 方案 | 可借鉴部分 | 当前没有直接替换的原因 | 何时重新评估 |
|---|---|---|---|
| LangGraph | Graph State、Checkpoint、Interrupt、Orchestrator-Worker | 不能替代 Workspace ACL、Canonical WorkItem/Evidence、预算和 Java 业务事务；迁移会形成两套状态机 | 动态条件边、人工中断和节点生态成为主要复杂度时 |
| Temporal | Durable Execution、Retry、Timer、History Replay | 引入独立基础设施和 Workflow Versioning；领域 Ledger、Evidence 与权限仍需自己维护 | 跨天任务、补偿和人工步骤远超当前调度能力时 |
| AutoGen | Team、Message、Termination、分布式 Runtime | 偏 Agent 消息协作，当前核心难点是字段状态、证据和权威合并 | 大量独立专家角色和对话协商产生可测收益时 |
| CrewAI | Flow 与 Crew 分层、持久化流程 | 仍需接入 NoteWeave 的任务、ACL、证据、预算和回调协议 | 产品流程比领域状态更稳定且框架集成成本下降时 |
| DeerFlow | Skill、Sub-agent、Sandbox、长任务 Harness | 能力面广，但 NoteWeave 需要学校 Workspace、Source Snapshot 和 Evidence Governance | 需要更通用的 Skill 市场和隔离执行环境时 |
| 完整 Event Sourcing | 任意时间点重建和审计 | 事件兼容、存储、删除和回放成本高于当前收益 | 法规审计或复杂时间旅行成为硬需求时 |

选择“不直接采用”不代表这些框架不好。当前判断来自已有 Java 业务真源、团队规模、部署复杂度和 Research 的领域特殊性。面试官改变题设后，答案也应改变。

## 18. 最关键的十个决策及其代价

| 决策 | 得到什么 | 付出什么 | 最小反例 |
|---|---|---|---|
| Research 独立状态模型 | 完整度、冲突和恢复可表达 | 对象与迁移增加 | 单资料短问答不需要 |
| Intent 稳定、Plan 可变 | 目标不漂移、路径可调整 | Revision 与失效分析 | 固定步骤任务用 Workflow 更简单 |
| Typed WorkItem，Matrix 为比较型适配器 | 共享调度合同又保留不同验收语义 | Planner 和 Schema 类型更多 | 低风险短任务可直接使用单个 Claim/Question |
| Worker 只写 Candidate | 权威合并与旧结果防护 | 回调和 Canonicalization 复杂 | 单进程无并发原型可直接写 |
| Evidence 先归档再采信 | 可回放、可删除、可审计 | 存储与隐私成本 | 临时低风险搜索可不长期归档 |
| 风险分级 Verifier | L0 规则、L1 语义、L2 独立复核 | 需要风险分类和 Judge 校准 | 简单确定性字段只需 L0 |
| Lazy Replan | 避免计划抖动和全量重做 | 纠偏可能不够及时 | 全局依赖假设失效时重编译整份 Plan；用户输入目标变化时创建新 Run |
| Checkpoint Hydration | 减少重复外部调用 | Schema 与完整性治理 | 秒级任务从头重跑更便宜 |
| Java Host + Python Worker | 稳定业务真源与快速模型迭代 | 跨语言契约成本 | 单栈小项目无需分层 |
| 有界角色和并发 | 控制成本、合并和安全 | 探索覆盖可能较慢 | 高并行、无共享状态任务可放宽 |

## 19. 面试官连续追问答辩卡

### 19.1 这不就是复杂版 RAG 吗

RAG 一般在一次请求中召回 Evidence 并生成答案。Research 需要维护跨轮次的实体、字段、冲突、预算、Checkpoint 和报告完成契约。它复用 RAG 的搜索和 Evidence 基础，但增加了可持续推进的状态与恢复协议。

### 19.2 为什么不直接用 ChatGPT/OpenAI Deep Research

产品层可以直接使用成熟服务。NoteWeave 研究的是学校 Workspace、私有 Source、权限、版本、可恢复任务和受控写回怎样与 Research 结合。外部产品提供方法和体验参照，但当前项目需要自己的数据边界、审计和成本控制。若目标只是让用户得到一份报告，采购成熟产品可能比自研合理。

### 19.3 Table-as-State 会不会限制模型

会。它换来缺口可计算、恢复可定位和并发可合并。Wide Discovery 和 Plan Revision 用来扩展结构，但仍有 Cell 上限。完全开放的探索可以先用自由 Agent 发现结构，再编译成 Matrix，不要求一开始知道全部实体。

### 19.4 Verifier 也是模型，凭什么相信

不直接相信。Scope、版本、Digest、数字和引用身份先由确定性规则校验；LLM 只判断难以规则化的语义支持。Local/Global 独立计算并记录分歧，评测还要校准 Precision、Recall 和误杀成本。

### 19.5 两个 Candidate 为什么比一个更可靠

只有执行和来源真正独立时才更可靠。同一个模型对同一个转载源重复两次没有意义。高风险 Quorum 还要比较 Source Lineage、Evidence Digest、候选归一化和盲选结果，并保留无法消解的冲突。

### 19.6 Worker 崩溃后为什么不会重复花钱

不能承诺绝对不重复。Checkpoint Hydration、Operation Key 和 Receipt 能复用已确认动作；对 IN_FLIGHT 且 Provider 可查询的动作先查状态；不可查询、非幂等的外部副作用仍可能进入 Unknown Outcome，需要预算保护和对账。

### 19.7 为什么默认并发只有 1

当前优先证明状态和合并正确性。Fetch 可以并发 4，但 Agent 任务默认有界顺序推进。提高 Agent 并发前要证明独立 Cell 足够多、Provider 配额允许、Merge Reject 和重复来源不恶化，并且单位成功成本值得。

### 19.8 为什么 Completion 必须由 Backend 确认

Worker 只知道自己生成了候选，不拥有 Workspace 权限、Canonical Ledger 和业务终态。Backend 必须确认 Task/Lease/Fencing、Cell Version、Evidence、预算和 Payload Digest，再原子提交 Artifact、Receipt 和状态。

### 19.9 最大的设计风险是什么

系统可能为了可验证性过度结构化，Verifier 也可能误杀，复杂协议还会增加开发和运维成本。因此必须保留简单 QA/单次生成路径，用质量、恢复和成本数据决定哪些任务真正需要 Research Agent。

### 19.10 如果重做一次，先做什么

先固定 20 到 50 个真实学校调研任务和人工 Gold，建立单次 Prompt、ReAct、Intent/Plan、Cell/Verifier 的配对基线，再按 Bad Case 增加恢复和并发。当前文档的完整架构适合说明最终设计，但真实产品迭代应让评测先于复杂度。

## 20. 五分钟面试主回答

NoteWeave 的 Research Agent 起点是学校资料服务。普通 QA 可以在课程 Workspace 中检索资料并回答，但课程调研、实验综述和多方案比较需要跨多个来源补齐实体和字段，处理冲突，并在几分钟甚至更长时间里持续执行。最初最简单的方案是单次长 Prompt，后来加入 ReAct 风格的 Search、Read、Think Loop。ReAct 适合探索，却把完成度、冲突和恢复点藏在对话里，模型搜到一点资料就可能提前写报告，进程中断后也不知道哪些事实已经验证。

我把稳定目标和可变路径分开。Research Intent 固定必填结果、来源范围、输出规格和预算，Plan 可以在可观测偏差下做版本化 Replan。研究状态不再保存成一段对话，而是 Typed WorkItem：方案比较使用 `MATRIX_CELL`，事实调查使用 `CLAIM_INVESTIGATION`，开放探索使用 `OPEN_QUESTION`，时间任务使用 `TIMELINE_EVENT`，关键来源核验使用 `SOURCE_AUDIT`。比较任务中的实体是 Row、研究维度是 Column，每个 Cell 保存 Candidate、Evidence、Conflict、Status 和 Version；其他 WorkItem 使用自己的验收合同。这样缺口能计算，又不会把所有研究勉强压成二维表。

Worker 不能直接覆盖 Canonical WorkItem。它只提交 Candidate 和 Evidence，Java Host 的 Canonical Ledger 通过 Plan Revision、WorkItem Version、Lease Epoch、Fencing Token、Evidence Scope 和 Digest 做合并。Last Write Wins 会把晚到当正确，简单多数票又会把转载当独立来源，所以高风险 Claim 或 Matrix Cell 可以要求两个来源独立的 Candidate，并保留无法消解的冲突。

验证分三层。确定性规则先检查权限、版本、数字、单位和引用身份；Local Verifier 按 WorkItem 类型判断单个工作单元是否满足合同；Global Verifier 重新计算整个 Intent 的必填覆盖、跨 WorkItem 一致性和报告资格。失败后只能围绕明确 Gap 或 Conflict 做有限 Repair、反证或 Replan，不能无限自我反思。`OPEN_QUESTION` 还必须绑定时间、成本、分支数和最小新增证据收益，达到边界后收敛为 `COMPLETED`、`ABSTAINED` 或 `INSUFFICIENT`。Reporter 只读取经过门禁的 Canonical State，Worker 输出 Synthesis Candidate，最终由 Backend 原子提交 Artifact、Evidence Manifest、预算和 Completion Receipt。

长任务可靠性上，Java 事务内创建 Run、Task 和 Outbox，Kafka 负责命令传输，Python Worker 使用 Lease 和 Heartbeat。每轮或关键外部回执后保存结构化 Checkpoint，恢复时 Hydrate 已验证 WorkItem 和 Evidence，只重做 GAP、STALE 或 Open Repair。旧 Worker 晚到由 Fencing 和版本条件拒绝，完成已提交但响应丢失时返回原 Receipt。

这套设计参考了 ReAct 的工具探索、STORM 的结构化预写作、OpenAI Deep Research 的计划和引用体验、LangGraph/Temporal 的持久执行思想，以及 AutoGen、CrewAI、DeerFlow 的有界角色协作。但没有直接把框架当业务真源，因为 NoteWeave 还需要 Workspace ACL、Source Snapshot、Canonical WorkItem/Evidence 和 Java 事务。它牺牲了自由度、实现复杂度和一部分延迟，换来可验证、可恢复和可审计。当前最重要的边界是默认 Agent 并发仍为 1，多角色能力部分受 Feature Flag 控制，内部 Fixture 也不能当作公开 Benchmark 成绩。

## 21. 继续追问时的展开顺序

1. 面试官问算法和状态，展开 Intent/Plan、Typed WorkItem、比较型 Matrix、Gap Priority 和 Stop Contract。
2. 问正确性，展开 Candidate、Source Independence、Merge Gate、Local/Global Verifier。
3. 问分布式，先区分 Run、Attempt 和外部 Operation，再展开 Outbox、Lease、Fencing、CAS、Receipt 和 Unknown Outcome。
4. 问恢复，展开同一 Run 下的新 Attempt、Checkpoint Hydration、Operation Identity 和旧格式兼容。
5. 问并行，先说明默认顺序 WorkItem 与 Fetch 并发，再讲何时启用 Wave Barrier、Candidate Quorum 和 Provider 配额。
6. 问安全，展开 Workspace Scope、Tool Permit、SSRF、External Snapshot 和 Prompt Injection。
7. 问指标，展开 Field Coverage、Citation Support、Conflict Discovery、Recovery、P95 和单位成功成本。
8. 问框架，比较 LangGraph、Temporal、AutoGen、CrewAI 和当前领域状态机。

回答任何一层都回到同一个选择逻辑：旧方案在哪个可复现 Bad Case 失败，当前机制保护什么不变量，为此承担什么成本，什么条件下会切换到另一种方案。

## 22. 代码证据速查

| 面试主张 | 当前代码或配置锚点 | 能证明什么 | 不能证明什么 |
|---|---|---|---|
| Java Host 持有业务真源 | `ResearchAgentCellMergeService`、`ResearchAgentCompletionCommitter`、`ResearchBudgetAndCheckpointService` | Candidate 合并、预算/Checkpoint 和 Completion 由 Backend 控制 | Java 天然比 Python 更可靠 |
| Worker 只执行授权任务 | `ResearchAgentTaskSnapshotCanonicalizer`、`ResearchAgentPermitService`、`agent_task_client.py` | Worker 读取版本化 Snapshot，并通过受控接口 Claim、Heartbeat、回调 | 任意第三方 Tool 都已经安全接入 |
| 角色不是自由人格 | `ResearchAgentRoleCapabilityRegistry`、`ResearchAgentRoleWorkflowService`、`role_executor.py` | Role 的调度资格、工具集、结果类型和写权限可由代码约束 | 多角色一定提高质量 |
| 实验角色默认关闭 | `application.yml` 中 `evidence-audit-v1`、`worker-synthesis-v1`、`wide-discovery-v1` 默认值均为 `false` | 三类角色不会仅因模型输出角色名就获得执行权 | 开启开关后已经具备生产收益 |
| Agent 并发与 Fetch 并发分离 | `config.py` 中 `research_agent_max_concurrency=1`、`fetch_max_concurrency=4`，`CompositeFetchAdapter` 使用有界线程池 | 默认只并行 I/O，不默认并行多个 Agent Task | 当前机器的最大吞吐或最优并发就是 1/4 |
| 旧 Worker 不能覆盖新 Owner | Task Claim/Heartbeat、Lease Epoch、Fencing Token、Cell Expected Version 与 Completion 校验 | 重复、晚到和换主场景有显式拒绝条件 | 整条跨系统链路 exactly-once |
| 外部来源先归档再采信 | External Snapshot Archive、Evidence Scope、Citation/Evidence Validation | 临时搜索结果不能绕过 Snapshot 和 Evidence 身份直接完成 Cell | 被归档网页的内容必然正确 |
| 评测目前属于机制证据 | Research Worker Harness、Role/Completion/Fault Injection 测试和少量 Fixture | 状态机、门禁和故障窗口可以确定性回归 | 已达到外部 Benchmark 或生产 SLO |
