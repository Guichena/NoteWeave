# Deep Research Agent：面试题与参考回答

> 阅读口径：第 3 至 16 节是面试官打断时使用的速查结论，不应单独作为完整回答。第 17 节提供项目总回答，第 19 节按面试官八个评分面提供 3 到 5 分钟母题长回答。准备时先掌握长回答，再用速查结论处理追问。

## 0. 脑图主干

```text
开放式研究
├─ 难点：目标漂移、证据不足、结论冲突、长任务中断
├─ 状态：Table-as-State
├─ 控制：Plan / Replan / Stop Contract
├─ 验证：Local Verifier / Global Verifier
├─ 恢复：反证、补查、Checkpoint / Resume
├─ 审计：Evidence Binding / Citation Audit
└─ 评估：Entity / Cell / Row / Citation / Branch
```

## 0.1 从 0 讲起的演进版主回答

我最开始用的是一个 ReAct 搜索循环，让模型在 Search、Read、Think 和 Write 之间自主选择。这个方向的优点是实现快、探索灵活，适合先验证模型能不能完成研究任务。任务变成长程、多实体、多字段以后，它的问题开始明显：研究目标藏在自然语言轨迹里，模型容易重复搜索或过早写结论；不同来源冲突时，最后一次输出可能覆盖前面的判断；Worker 中断以后，即使保留了对话，也很难判断哪些字段已经验证。

我们比较过固定 Workflow 和继续强化自由 Agent。固定 Workflow 稳定、易测试，但很难预先穷举开放式研究路径；自由 Agent 适应性强，却不适合直接维护权威状态。最后选择 Plan-and-Execute 加 Table-as-State：Research Intent 固定“必须完成什么”，Plan 可以按缺口版本化调整，Row 和 Cell 保存候选、证据、状态与版本。这样模型仍决定怎么搜索，程序负责判断任务是否完整。

接下来暴露的是“搜到资料不等于结论可信”。因此候选不能直接覆盖 Cell，而要经过来源身份、版本和支持度仲裁；Local Verifier 检查单个 Cell，Global Verifier 检查整体 Intent 与引用覆盖。资料不足时只做有界补查和反证，不做无限自我反思。长任务通过 Lease、Cell CAS、Checkpoint 和幂等完成回执恢复，旧 Worker 晚到也不能覆盖新状态。

这个选择的代价是状态对象、验证调用和恢复协议都更复杂，也限制了完全自由的探索。我们接受这个代价，是因为这个场景更关心结论可验证、任务可恢复和过程可审计。对于三五步、失败可接受的探索，我仍会选择简单 ReAct，不会套这套完整架构。

## 1. 30 秒项目回答

我负责的 Research Agent 不是让模型一次性搜索再生成报告，而是采用验证驱动的研究架构。系统先把问题规划成研究对象和字段，再用 `Table-as-State` 记录每个字段的候选值、证据和验证状态。执行过程中通过 Plan/Replan 控制研究方向，由 Local Verifier 检查单个字段的证据支持度，Global Verifier 判断整体研究目标是否满足；资料不足或冲突时进入补查或反证，长任务通过 Checkpoint 恢复。这样报告中的结论可以追溯到具体证据，而不是只保证最终文字看起来合理。

## 2. 3 分钟完整回答

这个亮点解决的是开放式研究任务的三个问题：第一，模型容易在多轮搜索后偏离最初问题；第二，资料数量多不代表证据足够，最终结论可能没有直接支持；第三，任务持续时间长，任何一次 Worker 重启都可能让前面的搜索和阅读浪费。

我的设计核心是 `Verification-Centric Research`。首先把用户问题编译成 Research Intent 和全局 Plan，明确研究目标、对象类型、字段、约束、交付格式和停止条件。随后用 `Table-as-State` 作为研究状态，每个 Cell 都有独立的候选值、Evidence Binding 和验证结果。Worker 内部 Ledger 可以记录冲突信号并驱动补查；当前 Java 权威合并层对与已验证值冲突的候选采取 Fail Closed 拒绝，不自动持久化 `CORRECTED/SCOPED` 状态。搜索和阅读不是为了无限收集资料，而是为了填补缺失 Cell、验证已有结论或解决冲突。

验证分成两层。Local Verifier 面向单个 Cell，判断证据是否直接支持候选值、是否存在冲突，以及当前字段能否进入已验证状态。Global Verifier 不复制 Local 的结果，而是基于当前 Ledger 重新检查研究意图、字段完整度、来源基础和整体一致性，决定继续检索、局部修复、带约束写作还是生成报告。

如果资料不足，系统根据缺口生成补查目标；如果存在冲突，则进入有界的反证分支，不允许无限扩散。每轮闭环结束后持久化 Checkpoint，保存 Plan、Ledger、Evidence、Verifier 结果和下一步决策，恢复时从最近的有效检查点继续。最后通过 Citation Audit 检查报告结论与来源的关联，并用内部 Gold Set 从 Entity、Cell、Row、Citation 和 Branch 五个维度评估。这套设计的重点是研究过程可控、可恢复、可审计，不是追求完全自主的无限循环 Agent。

## 3. 为什么普通 ReAct 不够

### 面试官问：为什么不用 ReAct 一直 Search、Read、Think？

ReAct 适合步骤较短、工具反馈能立即判断对错的任务。Deep Research 的困难是状态跨度长，而且“搜到了资料”不等于“目标字段已经被证据支持”。如果只保存自然语言 Scratchpad，会有三类问题：

1. 研究对象和字段边界会在多轮推理后漂移。
2. 同一结论可能被重复搜索，却没有形成稳定的证据状态。
3. 中断后只能重放对话，不能准确恢复已验证和待补查的部分。

因此我保留 ReAct 的局部工具调用能力，但在外层增加结构化 Plan、Table-as-State、Verifier 和 Stop Contract。模型负责提出候选动作，Harness 负责状态、约束和终止。

### 追问：这还是 Agent 吗，为什么不像 Workflow？

它处在 Workflow 和自由 Agent 之间。固定的是研究控制协议，例如必须经过规划、证据绑定、验证和停止判断；动态的是检索问题、阅读范围、候选结论和 Replan 内容。纯 Workflow 无法预先写死开放式研究路径，自由 Agent 又缺少稳定约束，所以采用 Harness 管控制面、模型做动态决策。

## 4. Table-as-State

### 面试官问：Table-as-State 到底是什么？

它不是简单把最终报告做成表格，而是把“要研究什么、当前知道什么、还缺什么”统一映射到结构化状态。Row 代表研究实体，Column 代表问题要求的字段，Cell 是最小验证单元。每个 Cell 至少需要记录：

- 当前候选值及版本。
- 支持证据和冲突证据。
- 证据对应的来源、快照和原文位置。
- 验证状态，例如 Need Evidence、Verified、Conflicted。
- 最近一次更新轮次和恢复动作。

这样 Planner 可以从空 Cell 和冲突 Cell 生成后续任务，Verifier 可以逐字段判断，Reporter 也只能从通过门禁的 Cell 生成结论。

### 追问：为什么以 Cell 为单位，不以文档或 Claim 为单位？

文档粒度太粗，一份文档可能只支持报告中的一个字段；自由 Claim 粒度又容易在多轮生成中变化。Cell 同时绑定了实体和字段，边界稳定，也更容易计算完整度、冲突率和引用覆盖率。复杂叙述仍可由多个 Cell 或 Claim 组合，但最小验收单位保持稳定。

## 5. Plan 与 Replan

### 面试官问：Plan 里面有什么？

Plan 至少包含 Goal、Context、Constraint、Step 和 Checkpoint：

- Goal：最终要回答什么，交付物是什么。
- Context：用户指定资料、Workspace 范围和已知背景。
- Constraint：来源边界、时间范围、字段要求和禁止推断项。
- Step：研究对象发现、字段补全、冲突验证等阶段任务。
- Checkpoint：每个阶段满足什么条件才能继续。

### 追问：什么时候触发 Replan？

不是每轮都重做计划。典型触发条件包括新实体出现、关键来源获取失败、字段长期缺证、证据互相冲突、原计划无法满足交付结构或剩余步骤不再有边际收益。Replan 优先局部修改当前阶段，只有研究目标或全局假设失效时才重做全局计划。

### 追问：如何避免 Replan 无限循环？

通过三个边界控制：限制闭环轮数和恢复动作次数；记录同类 Query、来源和阅读窗口，避免重复动作；使用 Stop Contract 判断继续研究是否仍可能提升关键字段。如果剩余缺口无法在允许来源和能力范围内补齐，则带不确定性输出，而不是继续循环。

## 6. Local Verifier 与 Global Verifier

### 面试官问：两层验证有什么区别？

Local Verifier 关注一个 Cell 的证据质量，例如引用片段是否直接支持候选值、来源是否属于当前快照、冲突证据是否已经处理。Global Verifier 关注整个研究任务，例如必填字段是否完整、实体覆盖是否充分、多个字段之间是否自洽、是否满足用户的交付要求。

Local 通过不代表可以写报告。一个个 Cell 都可能有证据，但研究对象遗漏一半，或者字段组合无法回答原问题，这时 Global 仍应要求继续研究。

### 追问：Verifier 也是 LLM，怎么保证它不幻觉？

不能把可靠性寄托在一个 Reviewer Prompt 上。系统先用确定性约束校验 ID、快照、Span、Hash、字段状态和支持关系，再按需要调用语义 Judge。语义 Judge 失败时可以降级到词法和极性校验，但不能把降级结果包装成通用 NLI 能力。Verifier 输出结构化状态和原因，最终状态合并仍由 Harness 规则控制。

## 7. 反证与冲突处理

### 面试官问：你说的反证分支怎么做？

当高影响结论只有单一来源、来源存在冲突或候选值过早收敛时，系统生成反向检索目标，例如寻找否定证据、替代解释或不同来源。当前实现是有界顺序反证，不是并行树搜索。分支结果会回写到原 Cell 的支持或冲突证据集合，再由 Verifier 决定接受、修正、保留争议或继续补查。

### 追问：冲突一定要选一个吗？

不一定。需要先判断冲突类型：来源版本不同、统计口径不同、时间范围不同、事实真的矛盾，或者只是措辞差异。能够通过限定条件消解的，修正 Cell 的适用范围；无法消解的，保留多个候选和冲突说明，报告中显式呈现不确定性。

## 8. Checkpoint 与恢复

### 面试官问：Checkpoint 保存什么？

它保存可恢复的研究状态，而不是只保存一段摘要，包括 Plan 版本、Research Ledger、Evidence 引用、Local/Global Verifier 结果、循环轮次、已执行动作、恢复目标和下一步决策。大对象可以落到对象存储，数据库保存索引、摘要和版本关系。

### 追问：恢复时如何避免重复执行？

任务和投递都有稳定 ID，写入采用幂等或 CAS。恢复器只接管过期 Lease，对已经完成的步骤读取持久化结果，对未完成步骤重新投递。即使消息重复，状态机也会根据当前状态和版本拒绝非法跃迁，避免同一个 Cell 被旧结果覆盖。

## 9. Citation 与证据审计

### 面试官问：有引用就说明答案可信吗？

不够。至少要区分 Citation Association 和 Citation Support：前者确认引用是否属于当前结论和来源，后者判断引用片段是否真正支持结论。系统保留来源快照、原文 Span 和内容 Hash，避免页面变化后无法回放；报告生成后再检查字段覆盖、引用归属和支持关系。

### 追问：为什么不在最终写作阶段让模型自己补引用？

自由写作后补引用容易出现“文本合理但引用不支持”的情况。我的做法是先完成 Evidence-to-Cell Binding，Reporter 从已验证状态中生成内容，Citation 是研究状态的投影，而不是写作阶段临时搜索出来的装饰。

## 10. 评估体系

### 面试官问：怎么衡量 Research Agent？

不能只看最终报告像不像。当前内部评估覆盖五个层次：

1. Entity：应发现的研究对象是否完整。
2. Cell：必填字段是否有正确值和直接证据。
3. Row：单个实体的字段完整度和一致性。
4. Citation：引用关联、支持度和覆盖情况。
5. Branch：发生冲突时是否触发正确恢复动作。

同时记录循环轮数、重试、Token 和成本等过程指标。当前 Gold Set 是内部确定性 Harness，只能证明机制可回归，不能声称超过某个外部 Deep Research Benchmark。

## 11. 故障场景题

### Worker 在写入结果前崩溃怎么办？

Lease 到期后由其他 Worker 接管，命令可以重新投递。因为完成结果尚未提交，重跑不会丢失已持久化的上一轮 Checkpoint。

### Worker 已提交结果但响应丢了怎么办？

重试携带相同任务和交付 ID，服务端根据幂等锚点返回已有结果，不重复推进状态。

### Kafka 重复投递怎么办？

Consumer 先检查任务状态和交付编号。相同命令重复到达时，要么返回已完成结果，要么拒绝过期 Lease Epoch，不能再次合并旧候选。

### 外部搜索服务不可用怎么办？

保留 Workspace 内资料研究能力，记录 Provider 失败并缩小研究边界。如果问题必须依赖外部来源，则明确输出证据不足，不用模型常识填补。

## 12. 技术取舍题

### 为什么不用 LangGraph 直接实现？

图框架可以承载节点和状态，但不能替代业务状态模型、证据契约和恢复语义。当前核心难点在 Table-as-State、验证门禁、幂等合并和持久化恢复。是否使用 LangGraph 属于执行框架选择，不影响这些领域模型。

### 为什么 Java Host 加 Python Worker？

Java 负责 Workspace、权限、任务、事务和持久化等稳定业务边界，Python 更适合模型调用、研究策略和评估迭代。两边通过版本化契约和可靠消息协作，避免把业务真源放进快速变化的 Agent Runtime。

### 为什么不用分布式事务？

研究任务跨数据库、Kafka 和对象存储，长事务不现实。系统采用本地事务、Outbox、幂等消费和补偿实现最终一致，并通过状态机暴露处理中和失败状态。

## 13. 面试官可能质疑的点

### “这是不是把简单问题复杂化了？”

短问答不会走完整 Research Harness。只有多来源、长耗时、要求结构化结论和引用审计的任务才进入 Deep Research。复杂度来自任务本身，架构的目的，是让复杂度落在明确状态和契约里。

### “你的数据量和真实用户量是多少？”

这是个人项目，目前没有可代表线上业务的用户量和 TPS，不会虚构。当前证明主要来自机制测试、故障注入、内部 Gold Set 和检索消融。若进入生产，会补充真实 Provider、长时间运行、并发压测和外部 Benchmark。

## 14. 一句话收尾

我把 Deep Research 从“模型不断搜索并写一篇文章”改造成了“以结构化研究状态为核心、由证据和验证门禁驱动、支持中断恢复和引用审计的长任务系统”。

## 15. 功能背后的八股知识点串讲

### 15.1 Agent、Workflow、ReAct、Plan-and-Execute 有什么区别

这不是概念背诵题，要结合 Research 功能回答。

**Workflow** 的节点和流转条件主要由开发者预先确定，优点是稳定、可测试，缺点是无法穷举开放式研究路径。**ReAct** 让模型在 Observation 后继续 Thought 和 Action，适合局部工具调用，但长任务容易把关键状态埋进自然语言历史。**Plan-and-Execute** 先生成相对完整的 Plan，再由 Executor 执行，适合多阶段目标，但执行中遇到新实体或冲突时需要 Replan。**Harness Engineering** 则把状态、工具权限、验证、恢复和停止条件放到模型外部。

本项目的组合是：外层用 Harness 固定研究协议，首次执行采用 Plan-and-Execute，局部搜索和阅读允许 ReAct 式决策，出现可观测偏差时触发 Lazy Replan。这样回答能说明你不是机械选择某个 Agent 范式，而是按不同层次组合。

### 15.2 Table-as-State 对应什么数据结构知识

Table-as-State 可以类比一张带版本的稀疏二维表，但不能只说“行列结构”。Row Key 是研究实体，Column Key 是研究字段，Cell Value 不只是字符串，而是一个状态对象：

```text
Cell = CandidateValue
     + EvidenceSet
     + ConflictSet
     + VerificationStatus
     + Version
```

从算法角度看，Research Loop 每轮都在做三件事：寻找未覆盖 Cell，提升 Partial Cell 的证据状态，解决 Conflicted Cell。Planner 不再面对一大段历史文本，而是面对一个可计算的状态空间。例如缺口集合可以表示为：

```text
Gaps = RequiredCells - VerifiedCells
```

恢复优先级可以综合字段重要性、当前证据量和冲突程度。项目当前并不需要声称用了复杂强化学习，基于规则的优先队列就能保证控制性。

### 15.3 Evidence-to-Cell Binding 对应什么知识点

它本质上是数据血缘与 Provenance。Evidence 必须同时回答四个问题：来自哪个 Source、哪个 Snapshot、原文什么位置、支持哪个 Cell。只保存 URL 会遇到页面变化、同页多观点和引用错配。

项目用 `source_id + snapshot_id + span/hash + cell_id` 形成绑定。Snapshot 解决时间一致性，Span 定位原文，Hash 防止内容变化，Cell ID 防止“引用是真的，但引用不支持这条结论”。这和数据库里的外键、版本控制里的 Commit Hash、数据仓库里的 Lineage 是同一类思想。

### 15.4 Local/Global Verifier 为什么类似分层校验

Local Verifier 类似单元约束，检查一个 Cell 的 Evidence 是否成立；Global Verifier 类似聚合不变量，检查整个研究结果是否满足 Intent。只做 Local 会出现“每个局部都对，但整体漏了实体”；只做 Global 又很难定位具体错误。

可以用数据库约束类比：`NOT NULL` 和 `CHECK` 保证单行合法，跨行汇总和业务规则仍需要更高层校验。项目将两层结果分开持久化，Global 从 Ledger 重算，而不是照抄 Local 的 PASS。

### 15.5 Verifier 的 Precision 和 Recall 怎么理解

Verifier 太松，会让不充分证据进入报告，False Positive 增多；太严，会让大量可用结论停留在 Need Evidence，False Negative 增多，Research 无限补查。对于高风险字段，优先 Precision；对于低风险探索性字段，可以允许 `WRITE_WITH_GUARDRAILS`，在报告中显式标注不确定性。

因此门禁不是一个统一阈值，而是由字段重要性、证据类型和交付场景决定。面试时可以说明：高影响结论要求直接证据和冲突检查，普通背景字段可以接受较弱来源，但不能伪装成同一置信等级。

### 15.6 Stop Contract 与终止算法

Agent 无限循环往往不是模型能力问题，而是没有形式化终止条件。项目的停止判断可以写成一个布尔合约：

```text
can_write = required_coverage >= threshold
         && critical_conflicts == 0
         && citation_contract_passed

must_stop = loop_count >= max_loop
         || no_new_recovery_target
         || repeated_action_detected
```

`can_write` 与 `must_stop` 可能同时为真或假。能写且应停时生成正式报告；不能完全写但必须停时带约束输出；还能继续且存在明确恢复目标时进入下一轮。这个知识点可以自然引出状态机、循环不变量和边界条件。

### 15.7 Checkpoint 对应快照、日志和 Event Sourcing 的取舍

每轮只保存完整快照，恢复快但写放大；只保存事件日志，写入轻但恢复要重放大量事件。项目采用“结构化 Checkpoint 加审计 Trace”的混合方式：Checkpoint 保存最近可恢复闭包，Trace 保存过程事件。它不是完整 Event Sourcing，因为业务当前状态仍有独立表；也不是简单序列化 Prompt，因为恢复依赖稳定 ID、版本和状态。

### 15.8 Lease、CAS 和 Fencing Token

Lease 解决“谁暂时有权执行”，CAS 解决“提交时状态是否仍是我读取的版本”，Fencing Token 解决“过期 Worker 晚到”。三者不能互相替代。

- 只有 Lease：旧 Worker 暂停后恢复，仍可能提交旧结果。
- 只有 CAS：可以阻止覆盖，但多个 Worker 会重复消耗模型和搜索资源。
- Lease + Epoch/CAS：先减少重复执行，再在提交点阻止旧结果。

项目在 Research Task 和 Cell 合并中都保留版本或 Epoch，这就是分布式任务调度、乐观锁和脑裂防护八股在 Agent 功能中的落点。

## 16. 源码级内部设计追问

### 面试官问：为什么两条证据一致还不能直接确认结论？

因为两条证据可能来自同一信息源，例如官网原文、转载和搜索摘要。项目做基于来源独立性的 Candidate Quorum，候选值一致只是必要条件，还要检查来源域和证据谱系。相同来源形成的是 Pseudo Quorum，系统 Fail Closed；候选冲突时生成 Repair Signal，定向补查冲突字段，而不是让最后返回的 Worker 覆盖前一个结果。

这道题可以继续回答相关性错误、信息源独立性和多数投票的前提。多数投票只有在样本近似独立时才有意义，重复转载会把同一错误放大。

### 面试官问：如何避免 Agent 查到一点资料就提前写报告？

系统用 Premature Commitment Guard。Required Frozen Cell 未完成，或仍有 Active Counterfactual Branch 时不允许 Synthesis。每个 Cell 还有 Adaptive Evidence Horizon，确定性高的字段缩小阅读范围，冲突和低覆盖字段扩大范围。达到终止边界后可以输出受约束报告，但必须显式保留缺失、冲突和来源限制。

面试官如果问这是不是动态规划，可以回答不是。它更像不确定性驱动的自适应搜索，状态是 Cell Coverage、Conflict、Retry Pressure 和 Horizon，动作是补搜、扩读、反证或停止。

### 面试官问：Java 和 Python 都算了 Hash，为什么还会不一致？

Hash 只保证相同字节得到相同摘要，不保证两个语言生成相同字节。JSON 字段顺序、Unicode 组合字符、浮点格式和空白都可能不同。项目先定义 Canonical Serialization：NFC、稳定 UTF-8 Key Order、紧凑 JSON 和严格类型，再计算 Digest；重复键、归一化碰撞和非法数字直接拒绝。

输入在校验后 Deep Freeze，防止验证和提交之间被修改。Digest 或 Path-Body 不一致在查库前拒绝。这能引出 Canonicalization、Content Addressing、TOCTOU 和防御式解析。

### 面试官问：外部网页抓下来就能作为证据吗？

不能。外部文本必须由正确 Lease 和 Tool Permit 归档为服务端 External Snapshot，Evidence 再绑定归档身份。Permit 校验 Owner、Epoch、Tool Identity 和 Workspace Scope，网络层防 SSRF、私网访问、DNS Rebinding 和凭据跨重定向。低信任、占位内容或仅 Fallback 来源会降低 Source Foundation 和最终 Confidence。

核心回答是“工具输出属于不可信输入，不属于系统事实”。MCP 解决协议接入，不自动解决授权、来源可信度和 Prompt Injection。

### 面试官问：完成接口成功，但响应丢了怎么办？

完成事务一次提交 Execution、Cells、Task、Bindings、Manifest 和 Outbox，并生成稳定 Receipt。数据库已提交但 HTTP 响应丢失时，调用方用相同 Completion Identity 重试，服务端返回原 Receipt，不重复生成副作用。相同幂等键但 Payload 不同直接冲突，Receipt 损坏或资源守恒不一致 Fail Closed。

这不是依赖网络 Exactly-once，而是 At-least-once 调用加幂等状态机、唯一约束和可重放回执。

### 面试官问：报告完成后为什么还要生成 Evidence Manifest？

正文中的引用是展示层，Manifest 是机器可校验的证据边界。只有通过 Citation Support Audit 的证据进入 Citation-gated Manifest，后续回放、删除降级、研究物化和评测都读取它。Finalizer 幂等执行，即使早期版本完成时漏写 Manifest，也可以补齐而不重跑研究。

## 17. 4 到 5 分钟标准主回答

面试官如果让我完整介绍这个模块，我会先说它解决的不是普通问答，而是开放式研究任务。普通问答通常只有一个问题和一组候选资料，找到几段相关文本就可以生成答案；研究任务要同时处理多个实体、多个字段、来源冲突和中途恢复。例如比较几个产品时，不能只生成一篇读起来顺的文章，还要知道每个产品的价格、版本、能力和限制分别由哪份资料支持，哪些字段还没有查到。

所以我没有把它实现成一个不断执行 Search、Read、Think 的自由 ReAct Agent，而是采用 Plan-and-Execute 加验证反馈的控制方式。任务创建后，Planner 先把研究目标拆成实体和字段，形成结构化 Research Plan。运行状态不是一段越来越长的聊天记录，而是一张字段化状态表。每个 Cell 保存当前状态、候选值、证据绑定、验证结果和版本。这样 Search、Fetch、Read 和 Extract 都是在补齐某些明确的 Cell，系统能够计算还缺哪些字段，也能判断下一轮应该补搜、扩大阅读范围还是进入反证。

字段值不能由模型第一次抽取后直接确认。我在 Cell 前面设计了基于来源独立性的候选仲裁。两个候选即使内容相同，如果都来自同一网站的转载或摘要，只能算同源佐证，不能形成真正的 Quorum。候选冲突时也不是最后写入者覆盖前一个值，而是保留两个 Candidate，生成 Repair Signal，定向查找第三方证据或权威来源。这一层解决的是研究任务中的伪多数和错误放大问题。

验证分成 Local Verifier 和 Global Verifier。Local Verifier 看单个 Cell，例如证据片段是否真的支持这个字段、时间和单位是否一致、引用位置能不能定位。Global Verifier 看整份研究，例如比较口径是否一致、必填字段是否覆盖、来源是不是过度集中、结论与表格之间有没有矛盾。只有通过验证的 Canonical State 才能进入 Synthesis。报告生成后还会做 Citation Support Audit，最终形成 Evidence Manifest，后续回放和删除降级都以这份清单为边界。

执行策略也不是固定检索 TopK。每个 Cell 有自己的 Evidence Horizon。已确认字段缩小后续阅读范围，低覆盖或冲突字段扩大范围；存在活动反证分支或必填 Cell 未完成时，Premature Commitment Guard 会阻止提前写报告。任务达到终止边界但证据仍不足时，可以输出带限制条件的报告，却不能把 No Hit 写成事实不存在。

工程上这是一个跨 Java Host、Python Worker、MySQL、Kafka 和对象存储的长任务。任务通过 Lease、Epoch 和 Cell Version 控制并发，旧 Worker 晚到时不能覆盖新结果；Checkpoint 保存 Plan、Cell 状态、证据引用和下一步动作，进程重启后从闭包状态恢复。Java 与 Python 之间的 Completion Payload 先做统一 Canonicalization，再计算 Digest，避免 JSON 顺序和 Unicode 表示差异造成错误冲突。最终完成在数据库事务中提交任务终态、Cell、Binding、Manifest 和 Outbox，响应丢失时通过稳定 Receipt 幂等重放。

这套方案的代价是状态模型更复杂，表和测试都比自由 Agent 多。它适合研究时间长、字段明确、结果需要审计的任务，不适合一句话就能回答的简单问题。简单问答走普通 RAG 更快；研究任务才值得承担规划、验证和恢复成本。评估时我不会只看报告是否流畅，而会看字段覆盖率、引用支持率、冲突识别率、恢复一致性和拒绝错误结论的能力。

## 18. 长回答后的连续追问模板

### 追问：为什么 Table-as-State 比保存 Agent 对话更适合恢复？

对话记录只能说明模型说过什么，不能直接回答“哪个字段已经完成、由什么证据支持、哪个候选仍在冲突”。Table-as-State 把进度变成可查询状态，调度器可以计算缺口，Verifier 可以针对 Cell 工作，恢复时也只需要读取结构化状态。代价是前期必须定义实体、字段和状态转换，面对完全无法结构化的探索任务时灵活性较差。

### 追问：为什么不是所有 Cell 并行跑？

并行可以降低 I/O 等待，但无界并行会放大搜索费用、Provider 限流和重复阅读。部分 Cell 还存在依赖，例如先确认产品版本，后续价格和功能才有正确口径。项目只对互不依赖的 Fetch 做有界并发，反证和恢复分支按受控顺序推进。这个选择牺牲一部分极限速度，换取资源可控和状态容易解释。

### 追问：Verifier 误杀正确证据怎么办？

Verifier 本质上也是有误差的分类器。阈值过严会降低 Recall，阈值过松会降低 Precision。项目把验证结果分成通过、待补查、冲突和拒绝，而不是只有 true/false；边界样本可以扩大 Evidence Horizon 或进入人工审阅。评测集需要同时包含正确支持、部分支持、单位错误、时间过期和伪引用案例，不能只测正样本。

### 追问：什么时候应该选自由 ReAct？

任务很短、工具调用少、失败成本低，而且目标无法提前拆分时，自由 ReAct 的实现成本更低。例如临时搜索一个概念或做一次探索性问答，不需要建立 Cell 和 Checkpoint。当前方案选择受控 Research Loop，是因为项目强调结构化报告、字段级证据和长任务恢复，目标决定了控制复杂度。

## 19. 面试官八维度母题长回答

### 19.1 业务抽象：Research Agent 为什么不是“多搜几次的 RAG”

普通 RAG 的核心对象是 Query、Candidate 和 Evidence，一轮检索后就能判断证据是否足够。Deep Research 面对的是一个需要持续推进的交付契约，比如比较多个产品的价格、版本、能力和限制。它不仅要找到相关资料，还要知道研究对象有哪些、每个对象必须覆盖哪些字段、字段当前是缺失、候选、已验证还是冲突，以及什么时候可以带着限制条件结束。

因此系统把用户自然语言先编译为 Research Intent。Intent 固定目标、实体类型、必填字段、来源范围、约束和交付格式，Plan 只描述当前准备怎么完成。这个区分很重要：来源失效或实体范围变化时，可以生成新 Plan，不能通过 Replan 偷偷降低 Intent 的完成标准。研究状态使用 Row 与 Cell，而不是把进度藏在 Agent 对话里。Row 对应实体，Cell 对应一个可独立验证的字段，Evidence 绑定到具体 Cell 和候选。

最小方案是 ReAct，让模型反复 Search、Read、Think。它在三五步探索中更灵活，开发成本也低；固定 Workflow 稳定、易测试，却很难预先穷举开放式搜索路径。当前选择 Plan-and-Execute 加 Table-as-State，保留模型对查询与工具的动态选择，让程序控制目标、状态和终止。代价是规划阶段必须把任务结构化，对完全无法定义字段的开放创作不合适。

业务成功不能只看报告是否流畅。至少要看实体与字段覆盖、高影响字段验证率、Citation Support、冲突识别、Guarded Write 比例、任务恢复后是否产生同一 Canonical State，以及用户是否保存或继续使用报告。当前没有线上留存和规模数据，因此这些是指标设计和测试证据，不是已经验证的商业成绩。

面试官继续追问“是不是复杂化”时，可以给选择边界：一句事实问答走普通 QA RAG，少量未知步骤可以用自由 ReAct；字段明确、时间较长、结果要审计的研究任务，才承担 Cell、Verifier 和 Checkpoint 的复杂度。

### 19.2 数据与一致性：候选、Cell 和最终报告如何保持一致

Research 有三类状态需要分开。Candidate 是 Worker 从某段 Evidence 中抽取出的待审值，Canonical Cell 是 Java 权威合并层接受的当前字段状态，Final Report 是只读 Canonical State 生成的派生结果。Candidate 不能直接覆盖 Cell，Report 也不能绕过 Cell 读取所有搜索片段，否则 Last Write Wins 和未验证内容会进入最终输出。

合并请求同时校验 Research Run、Plan Revision、Cell Version、Lease Epoch、Fencing Token 和当前状态。条件更新命中一行才表示提交成功，旧 Worker 即使内容正确，也不能覆盖更高版本。两个候选文本相同还要检查来源身份与归一化结果，同站转载或同一 Snapshot 的不同片段不能简单计作独立 Quorum。候选与已验证值冲突时，当前权威层返回 `VERIFIED_VALUE_CONFLICT` 并保留原值，不虚构自动 `CORRECTED` 或 `SCOPED` 状态。

长模型调用不放在数据库事务中。Worker Claim Task 后在事务外搜索、抓取和验证，只有 Claim、Cell Merge、Checkpoint 指针与 Completion 在短事务中更新。这样不会持有行锁几分钟，也减少连接池占用。它允许任务执行期间状态变化，所以必须用 Lease、Epoch 和版本识别旧结果。

最终完成需要把报告引用、Evidence Manifest、任务终态和可见投影在一个权威提交过程中收口。文件先按不可变 Key 写对象存储，Completion Payload 携带 Hash 和版本。Host 验证归属和 Digest 后提交终态；如果数据库成功但 HTTP 响应丢失，重复 Completion 返回已有 Receipt，不再生成第二份报告。Checkpoint 大对象读取时校验 Size 与 SHA-256，避免损坏快照被当作恢复真源。

这套方案得到的是业务层的幂等和最终一致，不是跨 Kafka、MySQL、对象存储的全局 Exactly-once。验证要制造旧 Lease 晚到、Cell 并发更新、完成响应丢失、Checkpoint 损坏和重复 Kafka 命令，观察条件更新、冲突码和最终状态是否符合不变量。

### 19.3 并发与容量：哪些步骤可以并行，如何避免研究任务拖垮系统

研究任务天然有大量 I/O 等待，但“每个 Cell 都并行”会放大搜索费用、Provider 429、重复阅读和状态冲突。并发需要先看依赖图。确认产品版本后才能判断对应价格和功能，存在这种口径依赖的 Cell 不能盲目并行；同一页面的多个 Fetch 可以共享 Snapshot；反证和修复分支需要基于上一轮验证结果，通常按 Wave 推进。

系统把 Run Advancement 拆成 Task，并使用 Wave Barrier 判断当前批次是否完成。可并行的是互不依赖的 Fetch、Read 或 Cell Task，Canonical Merge 仍通过版本和 CAS 串行收口。Research Worker 有最大并发配置，Fetch 另有有界并发与超时；Lease Heartbeat 间隔必须小于租约三分之一，Heartbeat 请求超时又必须小于间隔，失败预算还要给下一次续租留出空间。这个配置关系防止 Worker 在网络抖动时误以为仍持有所有权。

容量可以从 `L = λW` 估算。假设研究任务平均 5 分钟完成，入口稳定到达每分钟 2 条，平均在途就接近 10 条；如果每条任务同时开 4 个 Fetch，外部并发可能达到 40，还没有算重试和反证。入口需要 Workspace 与 Actor 速率限制，Research Workload 需要并发租约，Task 调度还要有优先级和预算。只加 Worker 会同时增加 MySQL Claim、Kafka 消费、外部搜索和 LLM 压力。

背压指标包括待执行 Task 数、最老 Task 年龄、当前 Wave 时长、Provider 429、Lease Lost、Checkpoint 大小、单 Run Token 与 Cost、循环次数和平均 Evidence Horizon。Provider 限流上升时先降低任务并发或进入 Waiting，不能靠快速重试；数据库锁等待上升时继续扩 Worker 会恶化。

当前仓库证明了调度、Lease、Barrier 和恢复机制，没有真实 Provider 长时间容量测试。面试中可以给模型和测量方法，不能把默认并发 1、Fetch 并发 4 或脚本验证结果当成系统极限。

### 19.4 安全：外部网页、工具和跨服务任务如何建立信任边界

Research 同时接触用户资料、公开网页、搜索 Provider、Python Worker 和 Java Host，攻击面比普通 RAG 更大。第一层是 Workspace 权限。创建 Run 时冻结 Workspace 与 Source Scope，Task、Permit、Snapshot 和 Completion 都带相同身份，Worker 不能用 Prompt 中的文本改变范围。外部文本必须归档成服务端 External Snapshot，再由 Evidence 绑定归档身份，不能让 Worker 回传一个 URL 就直接成为可信引用。

第二层是网络与内容。Fetch 需要阻止 Loopback、私网地址、DNS Rebinding、跨端口重定向和凭据跨重定向，响应大小和解码也要有限制。网页内容、搜索摘要和 Tool Output 都放在不可信区，不进入 System Instruction。Prompt Injection、空仓库、登录页和占位内容会降低 Source Foundation，不能因为模型成功抽取文本就提升信任。

第三层是工具授权。Host 颁发 Trusted Permit，绑定 Owner、Epoch、Research Run、Task、Workspace、Provider 和允许工具。Worker 执行前校验 Permit，旧 Epoch、工具不匹配或范围变化都拒绝。内部接口使用 Research Audience Token，生产要求与其他内部 Token 分离并通过 HTTPS。错误和 Trace 持久化前脱敏，不记录密钥和不必要全文。

第四层是结果污染。Candidate 必须经过 Evidence Binding、来源身份、Local Verifier 和 Canonical Merge；Memory 不参与 Evidence 排名；Final Reporter 只读通过门禁的 Canonical State。这样外部网页中的恶意指令最多影响一个待验证候选，不能直接写系统 Memory 或完成报告。

当前边界也要说清：搜索与 LLM 出口的安全约束不一定和完整 Fetch 防护同等成熟，外部 Provider 还有数据政策与日志留存风险；系统尚无统一零信任网络、Secret Manager 和全面渗透测试。安全回答要包含已经存在的门禁，也要指出仍需加固的出口。

### 19.5 可观测性：Research 卡住时如何定位到具体阶段

Research 的“卡住”至少有六种原因：命令没有投递、Task 没有被 Claim、Worker 丢 Lease、Provider 持续失败、Cell 验证无法通过、当前 Wave 已完成但 Advancement 没推进。只看最终 Run 状态无法区分。每个 Run 需要关联 Command Delivery、Task、Wave、Plan Revision、Cell、Checkpoint、Completion Receipt 和 Report Artifact。

定位可以按控制面顺序进行。先看 Run 当前状态、更新时间和最老未完成 Task；再看 Command Outbox 是否 Ready、Claimed、Retry 或 Dead Letter；Task 已运行时看 Lease Owner、Epoch、Heartbeat 与 Attempt；执行完成但 Run 未推进时看 Wave Barrier、Gap Projection、Advance Decision 与 Coordinator Tick；报告阶段看 Incremental Finalization、Manifest 和 Completion Receipt。跨 Java 与 Python 的 Trace 使用稳定 ID 和 Digest，不依赖本地日志顺序。

指标分四组。调度侧看 Queue Age、Claim Success、Lease Lost、Heartbeat Failure、Wave Duration 和 Recovery Count；研究质量看 Cell Coverage、Verified Ratio、Conflict、Guarded Write、Counterfactual Branch 和 Citation Support；资源侧看搜索次数、Fetch 字节、Token、Checkpoint Size、Provider 延迟与 429；完成侧看 Finalization Retry、Duplicate Completion、Manifest Backfill 和 Run Success。Trace 中只保存必要身份、计数和受控摘要。

告警必须能指向动作。Oldest Task Age 上升且 Worker 利用率低，查 Dispatcher 或 Claim；Lease Lost 上升，查 Heartbeat 延迟和停顿；Cell 长期 Pending 且搜索次数增长，可能是 Stop Policy 或来源不足；Conflict 增长不一定是故障，可能是数据真实冲突；Completion 重试增长但业务成功率稳定，优先查回调网络，不要重跑研究。

当前有业务 Metrics、Checkpoint 与 Trace，但不能宣称完整 OpenTelemetry。生产还需要统一 Trace Context 传播、Dashboard、告警路由和 SLO 基线。建议 SLI 包括 Run 成功率、P95 完成时间、关键 Cell 验证率、Citation Support、恢复成功率和单位 Run 成本。

### 19.6 成本：验证和反证会不会让 Research 变得不可用

Research 成本不能只统计最终写作 Token。一次 Run 包含规划、查询生成、搜索、Fetch、阅读抽取、Local Verifier、Global Verifier、反证、报告和 Citation Audit；失败重试、Checkpoint 恢复和重复候选也会消耗资源。成本账本至少按 Run、Wave、Task、Cell 和 Provider 记录调用次数、输入输出 Token、搜索请求、抓取字节、延迟、重试与缓存命中。

控制成本的第一步是让搜索围绕 Gap，而不是让 Agent 自由浏览。Cell 已验证后缩小 Evidence Horizon，缺失或冲突字段才扩大；同一 Source Snapshot 复用读取结果；候选在 Local Gate 被拒绝后，不进入更昂贵的 Global Synthesis；Stop Contract 同时限制循环、预算和低收益动作。反证只针对高影响或冲突字段，不能每个 Cell 都跑一套对抗搜索。

Verifier 本身有成本，也有误差。确定性检查先处理字段格式、来源身份、时间、单位、空值和引用位置，只有语义支持度与冲突判断交给模型。高影响字段可以用更强模型或多次验证，低风险字段采用较轻模型和抽样 Global Review。局部修复比整份报告重写便宜，Checkpoint 又避免 Worker 崩溃后从头搜索。

替代方案是只做一次强模型端到端研究，调用次数少，却容易在最后才发现证据和结构问题，返工成本不可控；或者对所有步骤使用最强模型，质量上限高，但 Provider 限额和费用难以支撑。当前设计优先用结构化状态减少无效动作，再在关键门禁花费模型预算。

目前仓库有 Budget Checkpoint、Token 与循环限制、Provider 治理和恢复机制，没有真实价格表、账单回填与单位 Run 成本 Dashboard。面试中不能声称节省了某个百分比，应说明怎样记录基线：同一 Gold Set 比较自由 ReAct、无 Verifier、当前方案的完成率、Citation Support、总 Token、搜索次数和 P95 时间。

### 19.7 测试证据：如何证明恢复、冲突和验证机制不是纸面设计

Research 的测试重点不是 Controller 返回 200，而是故障窗口与不变量。Cell Merge 要测试正确 Version、旧 Version、旧 Epoch、重复 Completion、同值不同来源、已验证值冲突和非法状态；Task 要测试 Claim、Renew、Lease 过期、旧 Owner 晚到、取消与终态；Coordinator 要测试 Wave 未完成不推进、完成后只推进一次、Crash 后恢复和重复 Tick 幂等。

跨语言协议要固定 Canonical JSON。测试包含 Key 顺序、Unicode NFC、UTF-8 字节序、BOM、重复 Key、浮点数、代理字符和输入深拷贝，保证 Java 与 Python 计算同一 Digest。Checkpoint 测试要覆盖 Size、SHA-256、损坏对象、缺失对象和旧 Schema。Completion 测试制造数据库提交后响应丢失，确认重复请求返回原 Receipt。

质量测试分字段与报告两层。Local Verifier 数据集需要正确支持、部分支持、否定、单位错、时间过期、同源转载和无定位引用；Global Verifier 要测字段完整但比较口径不一致、来源过度集中、报告结论超出 Cell 和 Citation 缺失。Precision 与 Recall 要分开报告，不能只看总准确率。误杀正确证据会增加补查成本，漏放错误证据会污染报告，两者阈值应按字段风险不同设置。

当前证据包括单元、契约、MySQL 锁矩阵、Redis 集成、调度恢复、故障注入和内部 Gold Harness。它能证明机制可回归，不证明公开 Benchmark 领先和线上稳定性。下一阶段需要真实 Provider、长时间 Soak Test、扩大人工 Reviewed Gold Set、并发压测和故障恢复时间测量。

把测试与指标闭环起来：Lease 晚到测试对应运行时 Fencing Reject，冲突样本对应 Conflict Ratio，Checkpoint 损坏对应 Recovery Failure，Verifier 样本对应 Precision/Recall。面试官追问时能说出“哪个测试证明哪个不变量”，比报测试数量更有说服力。

### 19.8 演进边界：什么时候换 LangGraph、工作流引擎或更自由的 Agent

当前架构把业务控制面放在 Java，把动态研究执行放在 Python。这个边界适合已有 Workspace、权限、MySQL 状态机、Outbox 和 Artifact 体系的项目。全 Python 可以减少跨语言协议，AI 生态也更集中，但业务事务和权限要重建；全 Java 统一部署与类型系统，却会降低研究工具和模型实验迭代速度。

LangGraph 适合表达模型节点、循环和状态图，可以替换 Worker 内部分 Plan/Execute 逻辑，但不会自动解决 Workspace 权限、MySQL 真源、Kafka 重复、外部对象、配额与幂等完成。Temporal 或 Camunda 更适合跨天 Timer、人工审批、补偿和可视化运维，代价是引入新的持久化语义和 Worker 协议。只有当前 Coordinator 的定时扫描、Wave 和恢复逻辑难以维护，且工作流需求跨多个 Agent 复用时，才值得迁移。

多 Agent 也不是默认升级。角色并行可以覆盖不同来源和视角，但会增加候选冲突、费用、协调和虚假多数。当前方案把研究拆为任务与 Cell，并用 Quorum、Verifier 和 Barrier 收口，先解决状态正确性。只有评测证明单 Worker 在某类任务上存在稳定覆盖缺口，而且新增角色能提高 Citation Support 或完成率，才增加角色并行。

规模扩大后，优先看数据库和 Provider。Command Outbox 扫描成为瓶颈时可迁 CDC；Task 表热点时按 Run 或 Workspace 分区；Checkpoint 太大时拆增量快照；外部搜索限额成为主瓶颈时增加 Provider 路由和结果缓存。部署从 Compose 进入 Kubernetes 前，还要补 Worker Drain、Pod Disruption、Secret Manager、Network Policy 和备份恢复。

选择更简单方案的条件同样明确：短问题走 RAG，三五步探索走 ReAct，固定批处理走普通 Workflow。工程能力不是机制越多越好，而是每个机制都有可观察的失败场景和退出条件。

## 20. 八维母题的项目源码答辩卡

第 19 节每个长回答都应带出下面对应行中的项目证据。口述时不需要念完整表格，挑一个生产入口、一个失败窗口和一个测试即可。

| 评分面 | 项目功能与生产类 | 核心方法、状态或真实设置 | 验证证据与当前边界 |
|---|---|---|---|
| 业务抽象 | 创建 Research Run 后，由 `ResearchAgentRunBootstrapService` 建立 Branch、Row 与 Cell，`ResearchAgentTaskCoordinatorService` 把缺口变成 Task | 核心对象是 Run、Checkpoint、Cell、Candidate、Task，不是一个不断追加的 Prompt | `ResearchAgentAutomaticRunBootstrapTest`、`ResearchAgentTaskCoordinatorServiceTest`；短问题仍走普通 RAG |
| 数据与一致性 | `ResearchAgentCellMergeService` 仲裁 Candidate，`ResearchAgentCompletionCommitter` 原子提交完成结果，`ResearchBudgetAndCheckpointService` 保存预算与 Checkpoint | Cell 更新带 Expected Version；完成回调同时校验 Worker、Lease Epoch、Fencing Token 与目标版本 | `ResearchAgentCellMergeServiceTest`、`ResearchAgentMySqlLockMatrixIT`、Completion Fault Injection Test |
| 并发与容量 | `ResearchAgentTaskService.claimTask()/heartbeat()` 管理任务所有权，`ResearchAgentRateLimitService` 控制 Provider | Worker Max Concurrency 当前为 1，Fetch Concurrency 为 4；Task Lease 60 秒、Heartbeat 15 秒、失败预算 30 秒 | `ResearchAgentTaskServiceTest`、`ResearchAgentRateLimitRedisIntegrationTest`；这些是保护参数，不是生产吞吐证明 |
| 安全 | `ResearchAgentPermitService` 校验工具身份，`ResearchExternalSnapshotArchiveService.validate()` 校验归档来源，Worker Fetch Adapter 负责 DNS 与连接固定 | 归档入口拒绝 UserInfo、内网数字地址和元数据域名；网页抓取逐次校验 Redirect | `ResearchAgentTrustedPermitServiceTest`、`ResearchExternalSnapshotArchiveServiceTest`、`test_fetch_adapters.py`；不能推广为所有 LLM Egress 都有同等沙箱 |
| 可观测性 | `ResearchAgentCompletionMetrics` 记录提交、冲突、回滚阶段，`ResearchRunQueryService` 聚合运行态查询 | 关联键使用 RunId、TaskId、Attempt、CheckpointSeq、CellKey；回滚 Stage 使用低基数 Tag | Completion Service、Read Model Mapper 和 Coordinator Scheduler Test；当前不是完整 OpenTelemetry Trace |
| 成本 | `ResearchBudgetAndCheckpointService` 预留和结算 Token、搜索与任务预算，`ResearchAgentRepairStopPolicy` 限制 Repair | Repair 由 Gap、Reason Digest、已排除来源与次数共同决定，不允许无界循环 | `ResearchBudgetAndCheckpointServiceTest`、`ResearchAgentRepairStopPolicyTest`；没有线上单位 Research 成本基线 |
| 测试证据 | Completion、Coordinator、Lease、MySQL Lock Matrix、Redis Rate Limit 分层验证 | 覆盖重复回调、旧 Owner 晚到、锁竞争、故障注入、回放与停止条件 | 测试存在不等于本轮全部运行，也不等于生产流量验证；口述时要说清证据等级 |
| 演进边界 | `ResearchAgentRolloutGuard` 与 `ResearchAgentExecutionModeService` 控制执行模式，Host 仍保存业务真源 | LangGraph 可替换 Python Runtime，但不能接管 Workspace 权限、任务真源和受控写回 | `ResearchAgentCoordinatorRolloutGuardTest`、`ResearchAgentRolloutPolicyTest`；当前 Compose 不是集群级 HA |
