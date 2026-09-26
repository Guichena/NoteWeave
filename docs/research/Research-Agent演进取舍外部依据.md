# Research Agent 演进取舍：论文、产品与工程依据

> 调研日期：2026-08-17
> 用途：Research 面试取舍的权威外部依据。产品/框架对照见 [DeepResearch 业界演化研究](./DeepResearch业界演化研究.md)，项目对照见 [Poirot 对比](./Poirot-项目深度分析与NoteWeave-Research-Agent-对比.md)。
> 证据边界：本文引用论文原文、官方文档、标准和官方仓库。论文结论只能证明某种机制在其设定下有效，不能反向证明 NoteWeave 已实现该论文、达到论文分数或具备生产规模。

## 1. 先给结论：不是从一个 Agent 框架换到另一个框架

NoteWeave 更合理的演进叙事不是“依次复现 ReAct、Reflexion、Self-RAG”，而是逐步把一个不可控的模型循环改造成可验证、可恢复的证据生产线：

```text
单次长文生成
  -> ReAct 式搜索/观察循环
  -> 先规划再执行的有界 Matrix
  -> Evidence、Claim、Citation 独立建模
  -> Local/Global Verifier 与定向 Repair
  -> Checkpoint、Receipt、Lease/Fencing 和 Canonical Ledger
  -> 受控 Synthesis 与 Artifact Promotion
```

外部工作分别回答了不同问题：

| 问题 | 可参考的一手来源 | NoteWeave 的选择 |
| --- | --- | --- |
| 模型怎样边想边调用工具 | ReAct | 保留受限的 Search/Read/Observe 循环，不把对话历史当业务状态 |
| 怎样减少漏步骤 | Plan-and-Solve；Anthropic workflow patterns | 把目标编译成有上限、可版本化的 Intent Matrix |
| 怎样根据失败反馈再尝试 | Reflexion；Evaluator-Optimizer | 只在可观测 GAP/CONFLICT 下创建定向 Repair，不保存自由文本“自我感悟”为事实 |
| 怎样避免无差别检索 | Self-RAG | 用 Evidence Gate、Gap、边际收益和预算决定补查，不声称复现其训练方法 |
| 怎样减少单一路径遗漏并组织长文 | STORM | Planner 做有界 perspective discovery，先完成 Matrix/Outline，再让 Synthesis 消费已接受证据 |
| 怎样组织多角色和可复用研究能力 | AutoGen、CrewAI、DeerFlow | 借鉴消息、Flow、Skill 和隔离子任务，Java/MySQL 仍是唯一业务事实源 |
| 长任务怎样恢复 | LangGraph persistence；Temporal Durable Execution | Java/MySQL 保存 Canonical Ledger，Checkpoint Hydration 恢复语义状态，Receipt 避免重复外部动作 |
| 引用怎样验证 | ALCE；W3C PROV | Claim-Citation-Evidence 显式映射，保存 Snapshot、Digest、Domain 和 Lineage |
| 冲突证据怎样处理 | ECon 等冲突研究 | 保留冲突、补查定义/时间/范围，不做简单多数票或让模型暗中选边 |
| 怎样评测 Research Agent | GAIA、BrowseComp、LiveDRBench、DeepResearchGym、Deep Research Bench、ALCE | 分开测工具检索、Claim 发现、研究过程、长文质量、引用支撑和工程恢复，不能用一个总分代替 |

这条路线与 Anthropic 的工程建议一致：先使用能解决问题的最简单结构，只在评测证明收益时增加 Agent 复杂度。它也解释了为什么 NoteWeave 没有把“角色数更多”当作先进性。

## 2. V0 到 V1：为什么单次生成后需要 ReAct 式循环

### 2.1 外部依据

[ReAct](https://arxiv.org/abs/2210.03629) 把 reasoning trace 与 task-specific action 交错生成。论文的核心价值不是“让模型写更多思维过程”，而是让推理能够根据外部环境的新观察更新后续动作；动作又能从知识库或环境取得模型参数之外的信息。论文在问答、事实核验和交互决策基准上验证了这个模式。

### 2.2 可借鉴机制

- 将 `search -> read -> observation -> next action` 变成显式循环，而不是一次 Prompt 要求模型凭记忆写完整报告。
- 工具结果属于环境反馈，下一步必须由新观察驱动。
- 对开放式研究保留动态查询能力，因为事前不可能列全所有搜索词。

### 2.3 NoteWeave 映射

- `DEEP_CELL` Worker 可以在 Task Snapshot、Tool Permit 和预算内执行有限的 Search/Read/Extract。
- Search/Read 结果先形成 Candidate/Evidence，再交给 Java 控制面校验；Worker 不直接写 Cell Current Value。
- ReAct 只存在于“单个受限任务怎样探索”这一层，Run 级进度由 Matrix、Stage、Task、Evidence 和 Ledger 表达。

### 2.4 为什么没有停在 ReAct

ReAct 论文没有提供 NoteWeave 所需的多租户权限、持久化研究账本、外部调用幂等、引用快照、预算结算、崩溃恢复和业务终态。它也不能证明任意长循环都会收敛。若把全部状态留在消息历史中，会出现：

- 重启后不知道哪些外部调用已经产生费用或副作用；
- “模型觉得完成了”无法等价于 Required Field 已覆盖；
- 无法可靠区分新证据、转载证据和同一来源的重复表述；
- 循环次数、上下文长度和成本持续增长，但边际证据收益不可见。

因此，ReAct 是局部探索策略，不是 NoteWeave 的持久执行模型。

### 2.5 不能从来源推导的项目事实

- 不能说 NoteWeave “实现了 ReAct 论文”或复现了论文指标；当前只是采用 reasoning/action/observation 的局部模式。
- 不能用 ReAct 在 HotpotQA、FEVER、ALFWorld 或 WebShop 的论文成绩替代 NoteWeave 的学校资料研究评测。
- 不能因为存在工具循环，就声称系统能自主处理任意开放问题。

## 3. V1 到 V2：为什么从自由循环转向 Plan-and-Execute 与 Intent Matrix

### 3.1 外部依据

[Plan-and-Solve Prompting](https://arxiv.org/abs/2305.04091) 把任务处理分成“先制定子任务计划，再按计划求解”，针对 Zero-shot CoT 的漏步骤问题。它的实验主要是推理数据集，证明的是任务分解可能减少 missing-step error，并不包含分布式执行或持久化语义。

[Anthropic: Building effective agents](https://www.anthropic.com/engineering/building-effective-agents) 区分了两类 agentic system：预定义代码路径组织的 workflow，以及由模型动态决定过程和工具的 agent。文章建议先采用最简单可行方案，并指出复杂 Agent 常以延迟和成本换取任务表现。文章还给出两种与 Research 直接相关的模式：

- Orchestrator-workers：中央模型动态拆分任务，再由 Worker 并行处理并汇总；
- Evaluator-optimizer：生成者与评价者循环，但适用前提是评价标准清晰且迭代改进具有可测价值。

### 3.2 可借鉴机制

- 规划与事实回答分离：Planner 定义“需要验证什么”，Executor 才获取证据。
- 独立子问题可以并行，但依赖关系和最终屏障由确定性代码控制。
- 计划应当有完成合同，而不是只有自然语言步骤列表。
- 复杂度必须通过覆盖率、成本、尾延迟和失败率证明，而不是凭 Agent 数量证明。

### 3.3 比较型任务为什么选择 Matrix，而不是自由文本 Plan

学校资料研究常见的是“多个对象 × 多个维度”，例如比较课程、实验要求、考试范围、时间变化和来源差异。自由文本计划容易出现同义步骤、漏项和无法判断完成。Intent Matrix 把目标编译成稳定的 Row/Column/Cell：

- Cell 是最小可验证单元，可绑定 Candidate、Evidence、状态和版本；
- Matrix Cell Coverage 可以直接作为比较型任务的停止条件；
- Cell 之间的依赖可以支持 Runnable Refill，不必等待整个 Run 的慢任务；
- Plan Revision 与 Digest 能拒绝旧计划任务的晚到结果；
- 1 到 20 行、2 到 8 列和最多 80 Cell 的上限防止规划爆炸。

这是“Plan-and-Execute 思想 + 业务领域模型”的组合，而不是把论文中的 Plan 文本原样落库。Matrix 只适合多对象、多维度比较；推荐领域模型在它之上统一 Typed WorkItem，开放调查、时间线和来源审计使用各自验收语义，全 Research 的主覆盖指标是 Required WorkItem Completion。

### 3.4 Trade-off

| 选择 | 收益 | 代价 | NoteWeave 的控制 |
| --- | --- | --- | --- |
| 自由 ReAct | 灵活，启动快 | 漂移、难恢复、难判断完成 | 只保留在 Cell 内的受限探索 |
| 固定 Workflow | 可预测、易测试 | 对开放问题表达不足 | 用 Matrix 和有限 Replan 保留弹性 |
| 自由文本 Plan | 人类可读 | 难以做依赖、版本、CAS 和覆盖率 | 编译成 Row/Column/Cell 和 Stop Contract |
| 完全动态多 Agent | 搜索空间更大 | 成本、权限和合并复杂度高 | 只有评测证明净收益的 Role 才可调度 |

### 3.5 不能从来源推导的项目事实

- Plan-and-Solve 的推理基准成绩不能证明 NoteWeave Matrix 优于 ReAct。
- Anthropic 的文章是工程经验和模式总结，不是 NoteWeave 的生产认证。
- `80 Cell` 是项目保护参数，不是论文最优值，也不能包装成行业标准。

## 4. V2 到 V3：为什么增加 Verifier 和定向 Repair，而不是无限反思

### 4.1 Reflexion 提供了什么

[Reflexion](https://arxiv.org/abs/2303.11366) 不更新模型权重，而是把任务反馈转成语言形式的 reflection，并保存到 episodic memory，影响后续 trial。它说明“利用失败反馈做下一次尝试”可以优于每次从零开始。

可借鉴的是反馈闭环：失败必须产生可操作反馈，下一轮需要看到前一轮为何失败。不能照搬的是把模型自由生成的反思当作可信事实；反馈源本身可能错误，自我评价也可能与真实证据不一致。

### 4.2 Anthropic 的 Evaluator-Optimizer 限制

Anthropic 明确把这个模式的适用条件限定为：评价标准清晰，且人类给出的反馈确实能改进输出，模型也能提供类似有效反馈。对 Research Agent 来说，这意味着“再想一遍”不能自动成为 Repair 理由，Repair 必须绑定具体缺口：缺引用、数字矛盾、来源冲突、覆盖不足或工具失败。

### 4.3 NoteWeave 映射

- Local Verifier 对单元候选做局部判断，Global Verifier 从 Canonical Facts 独立重算。
- `ResearchTypedClaimValidator` 检查数字、百分比、单位、日期、比较方向和否定；确定性矛盾不能被 LLM 自评覆盖。
- `ResearchEvidenceQualificationService` 校验 Scope、Snapshot、Citation Span、Relation、Domain 和 Lineage。
- Verifier 不一致保留 `VERIFIER_DISAGREEMENT`，而不是用后执行者覆盖前结果。
- `ResearchAgentRepairStopPolicy` 只允许有限、定向的 Counterfactual/Repair；没有可观测缺口时不允许模型因为“还想再试一次”而循环。

因此，NoteWeave 可以说“吸收了反馈驱动的迭代思想”，不能说“用 Reflexion 保证事实正确”。

### 4.4 Trade-off

- 单 Verifier 成本低，但有同源偏差；Local/Global 分离能暴露分歧，却会增加模型调用与终态阻塞。
- 确定性 Validator 可重复、可审计，但只能覆盖结构化矛盾，不能判断所有语义真实性。
- Repair 能提高缺口覆盖，但如果没有次数、预算和边际收益约束，会退化成昂贵的自我辩论。
- 保存完整 Chain-of-Thought 会增加隐私、存储和兼容问题；项目只应保存决策摘要、证据引用、原因码和版本。

### 4.5 不能从来源推导的项目事实

- Reflexion 的 HumanEval 等结果不能作为 NoteWeave Research 质量指标。
- 两个 Verifier 不等于两份独立事实来源；模型实例、Prompt、检索上下文或上游材料可能高度相关。
- Verifier 一致只能作为质量信号，不能替代 Citation-Evidence 的确定性校验。

## 5. V3 到 V4：Self-RAG 对按需检索和反思有什么启发

### 5.1 外部依据

[Self-RAG](https://arxiv.org/abs/2310.11511) 训练单一语言模型生成 reflection tokens，使模型能够按需决定是否检索，并评价检索片段的相关性、生成内容是否获得支持以及回答是否有用。论文针对的是经过专门训练的模型和推理算法，不是给任意 API 模型增加一段“请反思”的 Prompt。

### 5.2 可借鉴机制

- 检索不是固定 Top-K 的无条件步骤；先判断当前单元是否需要外部证据。
- “检索到文本”不等于“文本支持 Claim”，相关性、支持性和回答效用要分开判断。
- 失败后优先补查特定缺口，不重新搜索所有内容。

### 5.3 NoteWeave 映射

NoteWeave 没有训练 Self-RAG 模型，也没有实现它的特殊 token。项目层对应的是显式状态机：

- `GAP/STALE/CONFLICT/REPAIR` 决定是否创建新 Task；
- Evidence Qualification 区分来源身份、语义支持和来源独立性；
- Required WorkItem Completion、Marginal Evidence Gain、Search/Read Budget 和 Cost 决定是否停止；比较型子集使用 Matrix Cell Coverage；
- Synthesis 只能消费已接受 Evidence，不能在写报告时偷偷扩展检索范围。

这比“固定检索 N 条再交给模型”多了一层可审计决策，但它是系统级策略，不应称为 Self-RAG 复现。

### 5.4 Trade-off

- 自适应检索可降低无效调用，但误判“不需要检索”会放大模型参数记忆错误。
- 相关性 Judge 可提高上下文密度，但 Judge 本身需要校准，且不能覆盖数字/单位等确定性校验。
- 按 Cell 补查减少重做，却增加状态、版本和调度复杂度。

### 5.5 不能从来源推导的项目事实

- Self-RAG 的开放域问答、事实核验和引用表现不能迁移成 NoteWeave 指标。
- 没有训练 reflection token 的系统不能声称采用了 Self-RAG 模型。
- “按需检索”不能证明成本一定下降；必须用成功 Run 的总成本、重复调用率和缺口率共同验证。

## 6. 产品侧参考：OpenAI Deep Research 宣传了什么，NoteWeave 能借什么

### 6.1 官方可核对事实

[OpenAI Deep Research API 官方文档](https://developers.openai.com/api/docs/guides/deep-research) 将其定位为复杂分析与研究模型，可搜索、分析并综合大量来源；支持 Web Search、File Search、远程 MCP 和 Code Interpreter。官方文档还明确：

- Deep Research 是多步、长耗时任务，建议使用 background mode 和 webhook；
- 输出会列出 Web Search、Code Interpreter、MCP、File Search 等调用，最终消息带 inline citations；
- `max_tool_calls` 是约束成本和延迟的主要参数；
- API 模式不会自动完成 ChatGPT 产品中的澄清和 Prompt 重写，开发者需要自行提供；
- 公网搜索与私有 MCP/文件同时使用会带来 Prompt Injection 和数据外泄风险，官方建议可信数据源、记录工具调用、参数校验以及分阶段隔离公网与私有数据。

### 6.2 对 NoteWeave 的直接启发

- Research 必须是后台长任务，不应把 HTTP 长连接当唯一完成机制。
- 研究过程要暴露 Plan、工具调用、Evidence 和 Citation，不只返回一篇报告。
- 预算不能只有 Token 上限，还要限制工具调用数、检索/读取次数、总成本和尾延迟。
- 学校 Workspace 私有资料与公网来源需要分层权限和来源策略；网页内容始终是不可信输入。
- Clarification/Brief Compiler 属于独立步骤，不能假设底层 Research Model 会自动补齐目标、时间范围和输出格式。

### 6.3 为什么不能据此猜 OpenAI 内部架构

官方 API 文档公开的是能力、接口和安全建议，没有公开完整的 Planner、内部状态机、Checkpoint、Evidence Ledger 或 Verifier 实现。因此只能说 NoteWeave 的产品目标与“后台、多步、可引用研究”相似，不能说内部架构对标或复刻 OpenAI Deep Research。

官方所说的“数百来源”也不是 NoteWeave 的默认目标。学校场景更关心课程资料范围、来源权威和引用可追溯；来源数量过多可能只是转载和噪声，必须按 Domain/Lineage 去重并由收益/预算停止。

## 7. 持久执行：为什么参考 LangGraph 和 Temporal，但不直接替换现有控制面

### 7.1 LangGraph

[LangGraph Persistence](https://docs.langchain.com/oss/python/langgraph/persistence) 把两种持久化分开：Checkpointer 保存单个 thread 的图状态快照，Store 保存跨 thread 的应用数据。官方文档将 Checkpointer 用于对话连续性、人机协作、time travel 和 fault tolerance，并提醒内存实现不能跨进程重启，生产环境要使用持久后端。

[LangGraph Fault Tolerance](https://docs.langchain.com/oss/python/langgraph/fault-tolerance) 提供节点级 retry、timeout、error handler 和 resume-safe failure provenance。这些机制证明图执行器可以承担恢复与节点失败处理，但它并不自动提供 NoteWeave 的 Workspace ACL、Evidence Snapshot、Claim-Citation 关系、预算结算、Kafka 回调和 Artifact Promotion 业务语义。

NoteWeave 当前保留 Java/MySQL Canonical Ledger，是为了让现有业务对象继续只有一个权威状态源。若同时让 LangGraph Checkpointer 和 MySQL 都决定 Run/Cell 终态，会形成双状态源。只有当动态图、子图复用和 HITL 的收益明显超过迁移与一致性成本时，才值得评估全量迁移。

### 7.2 Temporal

[Temporal 官方概览](https://docs.temporal.io/temporal) 和 [Event History](https://docs.temporal.io/workflow-execution/event) 说明，Temporal 通过持久化 Event History 记录 Workflow 进度，并在崩溃后恢复。它适合跨天 Timer、等待外部信号、复杂补偿和大量长生命周期 Workflow。

但 Durable Workflow 不等于外部动作 exactly-once。[Temporal Activity Execution](https://docs.temporal.io/activity-execution) 明确描述了一种结果未知窗口：Activity Function 已调用后 Worker 崩溃，任务会在超时后按策略重试。因此 Search、LLM、邮件、写回等外部动作仍需要业务幂等键、Receipt、查询 Provider 结果或 Unknown Outcome 对账。

这正是 NoteWeave 当前保留 Operation Key、Completion Receipt、Lease Epoch 和 Fencing Token 的原因。Temporal 能减少通用编排代码，但不能消除外部 Provider 的幂等与事实账本问题。

### 7.3 迁移判断

| 继续自有状态机更合理 | 评估 LangGraph/Temporal 更合理 |
| --- | --- |
| Run/Cell/Evidence 已经深度绑定现有 MySQL 领域模型 | 动态图和子图组合变化频繁，手写调度成本持续上升 |
| 主要等待以分钟级 Provider 调用和 Kafka 回调为主 | 跨天 Timer、HITL、Signal、补偿工作流数量显著增加 |
| 团队能维护 Outbox、Lease、Checkpoint 与恢复测试 | 专门编排平台能被稳定运维，且历史迁移方案明确 |
| 双状态源风险高于框架收益 | 可以明确指定唯一权威状态并消除双写 |

不能把“没有采用 Temporal/LangGraph”说成技术落后，也不能说自研状态机天然更强。正确回答是：当前业务账本已经存在，迁移必须用故障恢复成本、开发效率、尾延迟和运维复杂度证明净收益。

## 8. 引用、来源独立性与冲突证据

### 8.1 Citation 验证：ALCE 给出的可复用分解

[ALCE 论文](https://arxiv.org/abs/2305.14627) 及其[官方仓库](https://github.com/princeton-nlp/ALCE) 把带引用长文本的评价拆成 fluency、correctness 和 citation quality，而不是只看“是否出现链接”。这支持 NoteWeave 将引用指标至少拆成：

- Citation Correctness/Entailment：该 Citation Span 是否真的支持 Claim；
- ALCE 语境下的 Citation Completeness/Recall：所有可核验 Claim 中有多少得到直接 Evidence 支撑；映射到 NoteWeave 时拆为 Citation Completeness（是否附有效引用）与 Claim-Evidence Coverage（是否得到完整支持），避免同名异义；
- Citation Quality：来源本身是否适合作为该 Claim 的证据；
- Answer Correctness：即使格式和链接正确，答案本身是否覆盖正确事实。

ALCE 的指标和数据集不能直接作为学校资料研究的完整评测。它不负责 NoteWeave 的 Workspace ACL、网页版本、来源 lineage、冲突状态或工程恢复。

### 8.2 Provenance 与来源独立性：W3C PROV 能证明什么

[W3C PROV-DM](https://www.w3.org/TR/prov-dm/) 将 provenance 定义为与数据或事物产生过程有关的 entity、activity 和 agent 信息，可用于判断质量、可靠性或可信度；它还提供 derivation、quotation、revision、primary source 等关系。

这为 NoteWeave 的 Source Snapshot、Content Digest、Provider/Adapter、Fetch Time、`generated_by` 和 `lineage_digest` 提供了标准化建模方向。但 W3C PROV **没有**定义“两个 URL 是否独立来源”的二值算法，也没有证明“不同注册域 = 独立”。因此：

- 同域或同 lineage 可以安全地视为“不应重复计数”的保守信号；
- 不同域只能成为“可能独立”的信号，还要检查共同上游、转载、同一新闻稿、作者和数据集；
- 机构权威、时效、直接性和方法透明度需要作为分开的属性，不能压成不透明总分；
- `lineage_digest` 是 NoteWeave 的工程启发式，不应包装成 W3C 标准字段。

建议将“独立来源数”计算为独立 lineage cluster 数，而不是 URL 数：

```text
IndependentSourceCount(claim)
  = count(distinct accepted evidence lineage cluster for claim)
```

若 lineage 未知，状态应是 `INDEPENDENCE_UNKNOWN`，而不是默认每个域贡献一票。

### 8.3 冲突证据：为什么不能多数票

[ECon](https://arxiv.org/abs/2410.04068) 对 evidence conflict 的实验发现，LLM 在解决冲突时可能无理由地偏向其中一条证据，或者退回自身参数知识。这说明把冲突材料一次性塞给模型，再接受其自然语言裁决，并不构成可靠治理。

[Who's Who](https://arxiv.org/abs/2410.15737) 进一步主张，面对知识冲突时系统应透明呈现冲突，而不是根据模型偏差自行决定展示什么。它研究的是同名实体等冲突场景，不能直接证明 NoteWeave 的策略最优，但支持“冲突可见、歧义先消解”的产品原则。

NoteWeave 对冲突更合适的决策顺序是：

1. 先判断是否是实体歧义、时间变化、口径/单位不同或真正事实冲突；
2. 再比较来源是否为一手、发布时间、适用范围、方法与 lineage；
3. 无法消解时保留双方 Claim/Evidence 和限制说明；
4. 只有确定性冲突解决或达到明确人工规则时才 Promote；
5. 模型“多数同意”不能覆盖 Typed Validator 或权威来源规则。

### 8.4 不能从来源推导的项目事实

- 多个 URL、多个 Agent 或多个模型调用都不自动等于独立证据。
- Citation 数量不能替代 Citation Support、Completeness 和来源质量。
- Conflict Discovery 高不一定表示系统差；也可能表示系统更善于暴露真实分歧。必须与正确消解率和 Unsupported Conclusion Rate 一起看。
- 当前代码中的 Domain/Lineage 校验只能证明结构存在；没有标注集时不能声称来源独立性准确率。

## 9. STORM：为什么采用多视角预写作，但不把大纲当事实账本

### 9.1 来源事实

[STORM 论文](https://arxiv.org/abs/2402.14207)和[Stanford OVAL 官方仓库](https://github.com/stanford-oval/storm)将长文研究拆成 pre-writing 与 writing：先发现不同视角，模拟由检索结果支撑的专家访谈，形成层级大纲，再按大纲生成文章。论文构建 FreshWiki（100 篇当时较新的 Wikipedia 文章），并在其设定下报告：相对 outline-driven RAG baseline，文章组织性提高约 25 个百分点、主题覆盖提高约 10 个百分点。

这些数字是 STORM 在特定数据、基线和评价协议下的论文结果，不是所有 Research Agent 的预期提升。官方 README 还明确提醒输出不是 publication-ready，需要人工显著编辑；论文中的编辑反馈也指出来源偏差会向文章传播，并可能把相关性弱的事实错误关联起来。仓库自报 research preview 用户超过 70,000，只能视为项目采用度信号，不能证明事实准确率、企业可靠性或 NoteWeave 的效果。

### 9.2 对 NoteWeave 的映射与取舍

- Perspective discovery 对应 Planner 在 Matrix 中补充不同利益相关方、时间范围和比较口径，避免只沿用户原始措辞搜索。
- Outline-first 对应先完成 Required Cell 和报告结构，再进入 Synthesis；写作阶段不能再偷偷补事实。
- STORM 的 conversation/history 适合作为探索过程，NoteWeave 仍将 Claim、Evidence、Citation、Snapshot 和冲突写入独立 Ledger，因为大纲不能承担权限、版本、恢复和证据治理。
- 对学校资料，视角不是越多越好。课程官方通知、教师说明、学生经验的权威级别不同；系统应保存来源角色和适用范围，而不是把多个观点平均投票。

因此可以在面试中说“参考 STORM 的 perspective discovery 和 outline-first synthesis”，不能说“复现 STORM”或把论文的 `+25pp/+10pp` 写成 NoteWeave 自身提升。要验证该机制，必须在同一学校 Gold Set 上做 `Matrix` 与 `Matrix + perspective discovery` 的消融，并比较覆盖率、无关事实率、成本和延迟。

## 10. 框架比较：AutoGen、CrewAI、DeerFlow 提供编排抽象，不提供项目证据治理

| 来源事实 | 适合解决的问题 | NoteWeave 的选择 | 不能从框架能力推导 |
| --- | --- | --- | --- |
| [AutoGen Teams](https://microsoft.github.io/autogen/stable/user-guide/agentchat-user-guide/tutorial/teams.html) 提供预设与自定义 Team、终止条件以及团队状态保存/加载；[Core Runtime](https://microsoft.github.io/autogen/stable/user-guide/core-user-guide/framework/agent-and-agent-runtime.html) 负责 Agent 生命周期、消息投递和路由 | 角色协作、handoff、消息驱动执行和可停止对话 | 独立研究分支以结构化 Task/Result 交互，但 Run/Cell/Evidence 终态仍由 Java Canonical Ledger 决定 | Team 或 Runtime 不自动提供 Workspace ACL、引用支撑、来源 lineage、预算结算和外部调用幂等 |
| [CrewAI Agents](https://docs.crewai.com/en/concepts/agents) 将 Agent 表达为带角色、目标、工具和委派能力的执行单元；[Crews](https://docs.crewai.com/en/concepts/crews) 组织 Agent、Task 和协作过程；[Flows](https://docs.crewai.com/en/concepts/flows) 提供事件驱动、状态、路由和持久化工作流 | 将开放式协作与确定性生产流程分层 | 主干更接近 Flow：阶段、门禁和恢复由确定性代码控制；只有局部开放问题才使用 bounded role/worker | 多角色协作不等于多份独立证据，也不能证明质量随 Agent 数量增长 |
| [DeerFlow 官方仓库](https://github.com/bytedance/deer-flow) 将 2.0 定位为基于 LangGraph/LangChain 的 super agent harness，组合可渐进加载的 Skills、sub-agents、memory、filesystem 和 sandbox | 可复用研究流程、隔离工具、按需加载能力和复杂任务分解 | 借鉴 Skill 的渐进披露、隔离子任务和 lead-agent 汇总；学校 Workspace 权限、Evidence Ledger 与 Artifact Promotion 仍属于 NoteWeave 控制面 | README 的功能覆盖不等于固定 benchmark 成绩，也不能证明其安全边界、证据治理或生产 SLO 与 NoteWeave 等价 |

关键 trade-off 是“动态自治放在哪里”。AutoGen/CrewAI 更容易快速表达角色协作，DeerFlow 更接近完整 Agent 产品运行时；但 NoteWeave 已经有 Java/MySQL 业务对象和学校 Workspace 权限。如果让框架 checkpoint 与业务数据库同时决定 Run 终态，会出现双状态源。当前选择是：模型/框架可以提出计划、执行受限工具和返回 Candidate，只有控制面能够 Promote canonical fact、结算预算和推进业务状态。

这不是否定框架，而是明确替换条件：当动态图复用、跨进程 Agent 路由、HITL 或跨天任务显著增加，并且迁移后能保留唯一权威状态、故障恢复测试和 Evidence 语义时，再用开发效率、恢复时间、尾延迟和运维成本评估是否替换自有编排。

## 11. 产品样本：GPT Researcher 用什么数字宣传，NoteWeave 应怎样对齐

[GPT Researcher 官方仓库](https://github.com/assafelovic/gpt-researcher)公开的核心结构是 planner 生成研究问题、execution/crawler 收集信息、逐来源总结和 source tracking，最后由 publisher 聚合报告。README 的产品宣传口径包括：报告超过 2,000 词、聚合超过 20 个来源；其 deep research 配置还自报使用 `o3-mini` high reasoning 时单次约 5 分钟、约 0.4 美元。以上都是项目方 README 在特定时间和配置下的自报告，不是独立审计结果，也不应跨模型、搜索 Provider、地区和日期直接比较。

这些数字说明同类产品常从四个维度宣传：**输出规模**（报告长度）、**检索规模**（来源数）、**用户等待**（端到端耗时）和**单位任务价格**（单次成本）。NoteWeave 可以使用相同维度，但必须补上更能体现学校场景价值的质量分母：

| 宣传或测量内容 | 建议计算口径 | 单独不能证明什么 |
| --- | --- | --- |
| 报告长度 | 最终 Artifact 的词/字数，按报告类型分桶 | 长度不能证明覆盖、正确或有用 |
| 来源数 | `distinct accepted lineage cluster`，同时报告原始 URL 数 | URL 多不能证明来源独立或权威 |
| 端到端耗时 | `terminal_at - accepted_at`，至少报告 P50/P95，并区分成功/失败 | 平均值不能反映排队和尾延迟 |
| 单次成本 | 成功 Run 的 LLM、Search、Read、Sandbox 结算成本之和 | 低成本不能证明完成；失败任务不能从分母中消失 |
| 可引用结论产出 | `accepted supported claims / successful run` | Claim 多不能证明用户问题已覆盖 |
| 单位有效产出成本 | `total settled cost / accepted supported claims` | 不同 Claim 难度和任务集不同时不可横比 |

GitHub Star、下载量、preview 用户数和报告页数可以说明采用度或输出形态，不能作为 Research 质量指标。对外比较 GPT Researcher、DeerFlow、STORM 或商业 Deep Research 时，至少要固定任务集、模型、Prompt、搜索权限、工具预算、时间点、Judge 和报告格式；否则只能写“能力/架构参考”，不能写“优于”或“达到行业水平”。

## 12. Benchmark：各自测什么，为什么不能混成一个“行业分数”

### 12.1 GAIA

[GAIA](https://arxiv.org/abs/2311.12983) 包含需要推理、多模态、浏览和工具使用的真实问题，共 466 题，并保留多数答案用于排行榜。它适合测通用 Assistant 的工具组合和终局答案，但不是长篇研究报告、Citation Completeness 或学校 Workspace 权限基准。

### 12.2 BrowseComp

[BrowseComp](https://arxiv.org/abs/2504.12516) 包含 1,266 个需要持续浏览、寻找难以定位信息的问题，答案短且容易与参考答案核验。论文自己也强调它类似编程竞赛：不完整但有用；它回避了真实用户分布、长答案和歧义处理。因此它适合测搜索坚持度和难事实定位，不适合单独证明 Research Report 质量。

官方实现位于 [OpenAI simple-evals](https://github.com/openai/simple-evals)。如果接入，应固定提交版本、浏览环境、模型和搜索 Provider，否则成绩不可复现。

### 12.3 两个名称接近但口径不同的 Deep Research Benchmark

[DeepResearchBench](https://deepresearch-bench.github.io/static/papers/deepresearch-bench.pdf) 提供 100 个 PhD 级研究任务，覆盖 22 个领域，中英文各 50 题。它用 RACE 评价报告质量，用 FACT 评价 Citation Accuracy 和有效引用，更适合回答“报告写得怎样、引用是否支持正文”。

[Deep Research Bench](https://arxiv.org/abs/2506.06287) 的标题中有空格，提供 89 个跨 8 类的多步 Web Research 任务，并设计 RetroSearch 冻结网页集合以降低开放 Web 持续变化带来的评测漂移；它还关注 hallucination、tool use 和 forgetting 等轨迹问题，更适合研究过程和轨迹诊断。

两者都比短答案 QA 更接近 Research Agent，但任务分布不等于学校课程资料研究。简历或实验报告必须同时写清基准全名、论文链接、题目切片、检索环境、Judge 和预算。RetroSearch 与 Live Web 的成绩也要分开报告，不能只挑更高者宣传。

### 12.4 ALCE

ALCE 更适合测带引用长文本中的正确性和引用质量，可补足 GAIA/BrowseComp 的短答案局限；但它不是 Agent 长任务恢复、工具调用和来源冲突基准。

### 12.5 LiveDRBench：把“研究过程”还原为 Claim 发现

[Microsoft LiveDRBench 论文](https://arxiv.org/abs/2508.04183)与[官方仓库](https://github.com/microsoft/LiveDRBench)把 deep research 的核心操作定义为高 fan-out 的概念探索，并用发现的 claims 作为中间结果，将搜索/推理能力与最终文风分开。基准包含 100 个任务、8 个类别，使用 claim/reference 的 Precision、Recall 和 F1；论文报告其设置下整体最佳系统 F1 为 `0.55`，不同类别 F1 范围为 `0.02-0.72`。

这个结果恰好说明“一个总分”会掩盖任务差异。官方仓库还明确声明：该基准不代表长报告写作质量、不是训练集、不建议作为唯一指标，也不应未经进一步验证直接用于商业或现实决策。NoteWeave 若运行它，必须固定 benchmark 版本、系统与搜索访问、Judge、Prompt/Tool Bundle 和预算；F1 只能说明 Claim 发现表现，不能替代 Citation Support、学校权限和恢复正确性。

### 12.6 DeepResearchGym：可复现检索环境与真实 Web 之间的边界

[DeepResearchGym 论文](https://arxiv.org/abs/2505.19253)与[官方项目页](https://www.deepresearchgym.ai/)提供基于 ClueWeb22/FineWeb 的可复现沙箱，通过 dense retrieval 与 DiskANN 固定检索环境，并从 information-need alignment、retrieval faithfulness 和 report quality 等维度使用 LLM Judge 评估。作者报告沙箱中的延迟/性能排序可与商业 API 比较，且在沙箱训练的策略能泛化到商业搜索；这些都是作者实验结果，不能证明 live Web 新鲜度、生产可靠性或 NoteWeave 质量。

它适合做 parser、retriever、planner 和 verifier 的可重复回归，但对比时必须固定 corpus/index 版本、retriever、Judge、Prompt、Model、Tool Budget 和报告格式。项目页还提示 API 使用数据可能被收集、匿名查询日志可能发布；学校或私有 Workspace 查询在提交前必须经过数据策略审查。

### 12.7 建议的 NoteWeave 评测组合

| 层级 | 数据 | 主要回答的问题 | 不应宣传成什么 |
| --- | --- | --- | --- |
| L0 确定性回放 | 仓库现有故障脚本、Mock Provider、固定 Snapshot | 状态机、幂等、Fence、恢复是否正确 | 真实研究质量 |
| L1 学校 Gold Set | 课程通知、培养方案、实验要求、时间变化、冲突版本 | 目标场景覆盖、引用支撑、冲突发现是否有效 | 通用 Web Agent 排名 |
| L2 工具/Claim 基准 | GAIA、BrowseComp、LiveDRBench 合规子集 | 浏览、工具使用、难事实定位、Claim 发现 | 长文报告质量或生产可靠性 |
| L3 研究报告基准 | DeepResearchBench、Deep Research Bench、DeepResearchGym、ALCE 适配集 | 报告质量、研究过程、事实综合、Citation 质量 | 学校场景用户价值或生产 SLO |
| L4 工程故障注入 | Kill Worker、Callback 丢失、重复乱序、Provider Unknown Outcome | Recovery、Duplicate Call、RTO、账本一致性 | 模型智能水平 |

必须固定并随结果保存：Benchmark Version、题目切片、Model/Prompt/Tool Bundle、Source Snapshot、Provider、最大工具调用数、预算、随机种子、Judge 版本和 Manifest。没有这些信息的单个百分比不可复现。

## 13. 可以直接用于面试的“参考了什么，为什么最后这么选”

### 13.1 两分钟回答

> 最早版本就是单次模型生成，问题是引用和补查不可控。后来参考 ReAct，把搜索、读取和观察做成循环，但我没有把整个系统停在 ReAct，因为状态都在上下文里时，重启、预算和停止条件无法审计。Plan-and-Solve 和 Anthropic 的 workflow/agent 模式让我把 Planner 与 Executor 分开，不过学校资料研究通常是多个对象乘多个维度，所以最终不是存一段自由文本 Plan，而是编译成有上限、可版本化的 Intent Matrix，每个 Cell 都有独立 Evidence 和状态。
>
> 长文组织参考了 STORM 的 perspective discovery 和 outline-first，但没有把大纲或 Agent 对话当事实源：Synthesis 只能消费已经接受的 Evidence。AutoGen、CrewAI 和 DeerFlow 让我确认了消息驱动团队、确定性 Flow、Skill 与隔离子任务的价值；不过框架不负责学校 Workspace ACL、Canonical Promotion 和来源治理，因此当前只借鉴抽象，不引入第二套权威状态。
>
> 质量闭环参考了 Reflexion、Self-RAG 和 evaluator-optimizer 的思想：失败后根据反馈补查，检索也不应固定 Top-K。但我没有把模型自我反思当真源，而是把缺引用、数字单位矛盾、来源冲突和覆盖不足做成显式状态。Local/Global Verifier 发生分歧会阻止无条件成文，Typed Validator 的确定性结论不能被 LLM 覆盖。
>
> 长任务恢复方面参考了 LangGraph Checkpointer 和 Temporal Event History，但项目已有 Workspace、Evidence、Artifact 和 MySQL 账本，如果再引入第二套状态源，一致性成本很高，所以当前保留 Java Canonical Ledger，加 Checkpoint Hydration、Receipt、Lease 和 Fencing。Temporal 官方也说明 Activity 在调用后 Worker 崩溃会重试，所以用了编排框架仍然要解决外部调用幂等。
>
> 最终选择的原则不是 Agent 越多越好，而是可验证、可恢复、可控成本。论文和产品文档只提供机制依据，项目是否更好仍要用学校 Gold Set、引用支撑、冲突发现、恢复成功率和单位成功成本来证明。

### 13.2 面试官继续追问时的短答

**为什么不是纯 ReAct？**
ReAct 解决“下一步怎样根据观察行动”，不解决业务状态、权限、幂等和恢复。NoteWeave 把它限制在 Cell 内，Run 级由 Ledger 决定。

**为什么需要 Matrix？**
因为学校研究常是对象 × 维度问题。Matrix 能定义 Required Cell、覆盖率、依赖、版本和停止条件，自由文本 Plan 做不到稳定 CAS 与恢复。

**两个 Verifier 是不是多数票？**
不是。Global Verifier 从 Canonical Facts 独立重算；两者不一致就记录 disagreement 并阻止无条件发布。模型一致也不能覆盖确定性 Validator。

**为什么不用 LangGraph？**
不是不能用，而是当前 MySQL 已是 Workspace、Evidence 和 Artifact 的权威状态。全量接入会先产生双状态源；等动态图复用和 HITL 收益可量化，再评估统一迁移。

**为什么不用 Temporal？**
当前已有 Outbox、Lease、Checkpoint 和 Callback 状态机。Temporal 在跨天 Timer、Signal 和复杂补偿变多时更有价值，但它也不会自动让外部 Provider exactly-once。

**怎样证明来源独立？**
不能仅靠不同 URL。当前用 Domain + Lineage 做保守聚类，同一上游转载只算一组；lineage 未知就标未知。这个是项目启发式，不是 W3C 标准算法。

**怎样证明引用是真的？**
报告中的可核验 Claim 必须映射到已接受 Evidence 和 Citation Span；分别计算 citation correctness 与 completeness，数字、单位、日期和比较方向再走 Typed Validator。

**为什么不直接用 AutoGen/CrewAI/DeerFlow？**
它们解决 Agent 协作、消息路由、Flow、Skill 和沙箱等通用编排问题，但不会自动获得 NoteWeave 的学校 Workspace ACL、Evidence Ledger、Canonical Promotion 和外部调用幂等。当前已有 Java/MySQL 权威状态，直接叠加框架会先产生双状态源；只有迁移能量化降低编排成本且保持单一事实源时才值得替换。

**STORM 的 `+25pp/+10pp` 能不能写进项目简历？**
只能写成设计依据，不能写成项目结果。那是 STORM 在 FreshWiki 和特定 baseline 下的组织性/覆盖提升。NoteWeave 必须在自己的固定学校 Gold Set 上做消融后，才能填写自己的提升数字。

**竞品说 20+ 来源、5 分钟、0.4 美元，我们怎样比较？**
先统一任务、模型、搜索权限、预算和时间点；来源按独立 lineage 而不是 URL 计数，耗时报 P50/P95，成本只按成功 Run 结算并同时报告 supported claims。没有同协议复现，只能引用为对方自报宣传口径。

## 14. 来源清单与使用边界

| 来源 | 类型 | 本文采用的事实 | 不能推导 |
| --- | --- | --- | --- |
| [ReAct](https://arxiv.org/abs/2210.03629) | ICLR 论文、项目原始论文 | reasoning/action 交错、环境观察驱动后续动作 | 持久化、权限、引用账本、NoteWeave 成绩 |
| [Plan-and-Solve](https://arxiv.org/abs/2305.04091) | ACL 论文、官方代码见论文 | 先分解再执行以降低漏步骤 | 分布式 durable execution、Matrix 最优性 |
| [Reflexion](https://arxiv.org/abs/2303.11366) | NeurIPS 论文 | 语言反馈与 episodic memory 驱动重试 | 自我反思等于事实、Verifier 保证正确 |
| [Self-RAG](https://arxiv.org/abs/2310.11511) | ICLR 论文 | 按需检索及相关性/支持性/效用反思 | 普通 Prompt 即复现、NoteWeave 采用其训练方法 |
| [Anthropic: Building effective agents](https://www.anthropic.com/engineering/building-effective-agents) | 公司官方工程文章 | workflow/agent 区别、orchestrator-workers、evaluator-optimizer、先简后繁 | NoteWeave 的生产证明或指标 |
| [OpenAI Deep Research API](https://developers.openai.com/api/docs/guides/deep-research) | 官方开发者文档 | 后台多步研究、工具轨迹、inline citation、工具预算与安全边界 | OpenAI 内部完整架构 |
| [LangGraph Persistence](https://docs.langchain.com/oss/python/langgraph/persistence) | 官方文档 | Checkpointer 与 Store 分工、恢复和持久化边界 | 自动获得 NoteWeave 领域一致性 |
| [LangGraph Fault Tolerance](https://docs.langchain.com/oss/python/langgraph/fault-tolerance) | 官方文档 | retry、timeout、error handler、resume-safe failure | 外部动作 exactly-once |
| [Temporal 概览](https://docs.temporal.io/temporal) / [Event History](https://docs.temporal.io/workflow-execution/event) / [Activity](https://docs.temporal.io/activity-execution) | 官方文档 | 事件历史恢复、Activity 结果未知和重试窗口 | Provider 调用天然幂等、迁移一定更优 |
| [ALCE](https://arxiv.org/abs/2305.14627) / [官方仓库](https://github.com/princeton-nlp/ALCE) | EMNLP 论文与官方代码 | fluency、correctness、citation quality 分维评价 | 来源 lineage、工程恢复、学校场景成绩 |
| [W3C PROV-DM](https://www.w3.org/TR/prov-dm/) | W3C Recommendation | Entity/Activity/Agent、Derivation、Primary Source 等 provenance 关系 | 不同域必然独立、NoteWeave lineage 算法是标准 |
| [ECon](https://arxiv.org/abs/2410.04068) | EMNLP 论文 | 模型处理冲突时可能无理由选边或依赖内部知识 | NoteWeave 冲突策略已达某准确率 |
| [Who's Who](https://arxiv.org/abs/2410.15737) | EMNLP Findings 论文 | 冲突透明呈现与实体歧义风险 | 适用于全部事实冲突的唯一方案 |
| [GAIA](https://arxiv.org/abs/2311.12983) | 基准原始论文 | 推理、多模态、浏览、工具使用 | 长文 Citation 与工程恢复能力 |
| [BrowseComp](https://arxiv.org/abs/2504.12516) / [官方实现](https://github.com/openai/simple-evals) | OpenAI 基准论文与代码 | 持续浏览、难事实定位、短答案验证 | 真实用户分布和完整 Research Report 质量 |
| [DeepResearchBench](https://deepresearch-bench.github.io/static/papers/deepresearch-bench.pdf) | 基准原始论文 | 100 题、22 领域、RACE 报告质量与 FACT 引用评价 | 学校场景代表性、生产 SLO |
| [Deep Research Bench](https://arxiv.org/abs/2506.06287) | 基准原始论文 | 89 题、8 类、冻结 Web 与轨迹诊断 | 学校场景代表性、生产 SLO |
| [STORM](https://arxiv.org/abs/2402.14207) / [官方仓库](https://github.com/stanford-oval/storm) | NAACL 论文与 Stanford OVAL 仓库 | 多视角预写作、outline-first；FreshWiki 特定设置下组织性和覆盖提升 | NoteWeave 已复现、论文提升可迁移、输出可直接发布 |
| [AutoGen Teams](https://microsoft.github.io/autogen/stable/user-guide/agentchat-user-guide/tutorial/teams.html) / [Core Runtime](https://microsoft.github.io/autogen/stable/user-guide/core-user-guide/framework/agent-and-agent-runtime.html) | Microsoft 官方文档 | Team、终止/状态、Agent 生命周期与消息路由 | Evidence/ACL/预算/幂等治理 |
| [CrewAI Agents](https://docs.crewai.com/en/concepts/agents) / [Crews](https://docs.crewai.com/en/concepts/crews) / [Flows](https://docs.crewai.com/en/concepts/flows) | 官方文档 | 角色 Agent、协作 Crew、事件驱动且有状态的 Flow | 多 Agent 自动提高质量、框架状态等于业务账本 |
| [DeerFlow](https://github.com/bytedance/deer-flow) | ByteDance 官方仓库 | Skills、sub-agents、memory、filesystem、sandbox 的 super agent harness | 固定 benchmark 成绩、NoteWeave 领域治理和生产 SLO |
| [GPT Researcher](https://github.com/assafelovic/gpt-researcher) | 官方仓库 | planner/execution/publisher；README 自报长度、来源、耗时与成本口径 | 独立审计质量、跨配置成本与行业领先性 |
| [LiveDRBench](https://arxiv.org/abs/2508.04183) / [官方仓库](https://github.com/microsoft/LiveDRBench) | Microsoft 论文与仓库 | 100 任务、8 类、Claim P/R/F1、高 fan-out 研究 | 长报告质量、唯一综合指标、生产适用性 |
| [DeepResearchGym](https://arxiv.org/abs/2505.19253) / [官方项目页](https://www.deepresearchgym.ai/) | 论文与官方项目 | 固定 Web 语料沙箱、检索一致性、需求/faithfulness/报告评价 | live Web 新鲜度、学校隐私与生产可靠性 |

## 15. 最终口径

可以说：

> 我参考了 ReAct 的观察驱动工具循环、Plan-and-Solve 的规划执行分离、Reflexion/Self-RAG 的反馈与按需检索、Anthropic 的 workflow/evaluator 模式，以及 LangGraph/Temporal 的持久执行思想。最终没有照搬单一框架，而是针对学校资料研究的对象 × 维度问题，做成 Intent Matrix + Evidence Ledger + Verifier/Repair + Checkpoint/Receipt。核心取舍是牺牲一部分自由度，换取覆盖率、引用、恢复、权限和成本可验证。

不能说：

> 我们复现了 ReAct/Self-RAG，效果达到论文水平；我们和 OpenAI Deep Research 架构相同；两个域就是两个独立来源；两个 Verifier 一致就保证事实正确；用了 Checkpoint 就实现 exactly-once。

当前最需要补的不是更多外部名词，而是一套固定学校 Gold Set 和可复现 Manifest。只有在同一模型、来源快照和预算下，对比 Single-shot、ReAct-only、Matrix、Matrix + Verifier、Matrix + Verifier + Hydration，才能把这些取舍从合理设计升级为可宣传结果。
