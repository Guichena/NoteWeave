# Agent 执行与 Artifact 产物一体化面试手册

> Agent 执行框架是通用运行底座，Artifact 是最能体现这套底座价值的业务功能。面试默认采用“Kafka 快速接纳、Durable Execution 长任务、Host 预分配唯一 Version ID、验证后写回”的推荐方案；旧的长时间占用 Offset 和 Worker 自建 Version 只作为迁移背景。权威设计见 [Artifact Skill 执行架构](../Artifact-Skill执行架构.md)，统一裁决见[面试推荐架构与规模化演进裁决](43-面试推荐架构与规模化演进裁决.md)。

## 1. 主回答

一次 Prompt 可以生成文本，但不能稳定管理长任务、工具能力、外部文件、局部修复和写回。项目把 Agent 执行拆成 Skill、ExecutionSpec、Capability、ArtifactJob、ArtifactRun、Durable Execution、ArtifactVersion 和 ArtifactFile。Kafka Consumer 只完成命令的幂等持久接纳，Scheduler 和 Worker Lease 管理长任务。模型负责提出候选计划，Host 负责编译、准入、预分配 Version ID、回调校验、Verifier 和写回。

Artifact 不是“调用模型得到一段 Markdown”，而是一个有输入快照、Skill 版本、产物版本、文件元数据、验证状态和写回边界的业务运行。这样同一份课程资料可以生成讲义、题库或研究摘要，并且能重试、比较版本和定位失败。

## 2. 演进主线

| 阶段 | 旧方案 | 失败窗口 | 当前选择 |
| --- | --- | --- | --- |
| V0 | 一次 Prompt 直接返回文本 | 结果不可验证、不能恢复 | 使用受约束 Skill |
| V1 | 固定 Workflow | 可重复但无法适应资料差异 | Workflow 提供骨架，Agent 负责局部决策 |
| V2 | Skill 只是 Prompt 模板 | 版本、输入和输出边界不清 | Skill 变成执行契约 |
| V3 | 每个工具一个专用 Adapter | 能力接入重复、权限分散 | MCP 作为协议，Host 控制 Capability |
| V4 | Consumer 占着 Offset 执行长任务 | 分区阻塞、Rebalance 困难 | 持久登记 Execution 后提交 Offset，由 Scheduler 执行 |
| V5 | 失败全量重试 | 成本和尾延迟放大 | Waiting、Resume 和局部 Repair |
| V6 | Worker 直接写 Workspace | 跨服务副作用不可审计 | Host 持有写回和最终提交 |

Artifact 的专属演进是：文本结果，统一 Markdown IR，版本化 Artifact，独立 Artifact File，以及格式和内容双层 Validator。

## 3. 通用执行合同

```text
Create Job
  -> Input Snapshot / Control Pack
  -> Skill Compile
  -> Admission: capability + quota + policy
  -> Kafka Command / Durable Execution Registration
  -> Scheduler / Active Permit / Execution Lease
  -> Worker Execution
  -> Candidate Callback / Verifier / Repair
  -> Host DELIVERY_PENDING Version / Required File Manifest
  -> DeliveryAttempt / File Staging / File Contract
  -> Required Files READY / Version READY
  -> Approval Proposal / Conditional Write-back
```

| 对象 | 作用 |
| --- | --- |
| SkillDefinition | 描述输入、输出、工具、Verifier 和版本 |
| ExecutionSpec | 编译后的可执行中间表示 |
| Capability | 工具和模型能力的可授权集合 |
| ArtifactJob | 用户的稳定产物意图，可拥有多次生成、再生成和回滚请求 |
| ArtifactRun | 一次不可变的生成、再生成或追加式回滚请求、输入和触发原因 |
| ExecutionAttempt | 同一 Run 下的基础设施执行、恢复或接管代际 |
| DurableExecution | Kafka 已接纳、可由 Scheduler 领取的执行记录 |
| ArtifactVersion | 逻辑产物版本 |
| ArtifactFile | PDF、Markdown 等具体交付文件 |
| CallbackReceipt | Worker 回调幂等和 Payload 绑定 |

Skill Compile 将用户要求、上下文、格式、Capability 和 Control Pack 编译为稳定 ExecutionSpec。Worker 消费 Snapshot，不直接读取不断变化的 Workspace。

冻结的 Capability Set 只定义本 Run 的能力上限，不代表审批和权限永久有效。`[目标设计]` 高风险工具调用前由 Host/Tool Gateway 把编译能力与当前 ACL、Policy、未过期 Approval、输入有效性 Epoch 和 Provider 状态重新求交集；运行期间只能收紧，不能因 Catalog 更新自动扩权。副作用参数变化必须生成新的 OperationIntent 和 Approval，不能复用旧审批。

## 4. 准入、配额和恢复

### 4.1 为什么既限速又限并发

速率限制控制时间窗口内的调用量，防止突发请求；并发租约控制同时占用的任务数，防止单个慢任务长期占满资源。只限速会让长任务堆积，只限并发会让任务在短时间内打满 Provider。

Redis Lua 保证 Workspace、Actor 和 Workload 维度的令牌桶与并发租约原子更新。Redis 只负责短期协调，Task、Outbox 和业务状态仍以 MySQL 为真源。

### 4.2 Waiting、Resume 与局部 Repair

只有能力已经实现持久 Wait Receipt、恢复条件和唤醒入口时，Provider 暂时不可用、需要用户补资料或等待外部文件才进入 Waiting。恢复时复用已经编译的 Skill 和输入快照，不重新解释整个用户请求。未接入等待合同的 Provider 429 仍按当前路径做有限重试和失败分类，不能把它包装成自动 Resume。验证器只修复失败节点或字段，不默认重生成整篇产物。

### 4.3 Kafka 只负责接纳，执行权属于 Task

Consumer 校验 Command ID 和 Schema，在 MySQL 中幂等登记 Durable Execution 后提交 Offset。数据库提交后、Offset 提交前退出会触发消息重放，但唯一 Command ID 返回既有 Execution；Offset 已提交后退出，Scheduler 仍能从 MySQL 找到未执行记录。小时级任务的 Owner、Heartbeat、Timeout 和 Fencing 都在 Execution Lease 中处理，不通过一小时 `max.poll.interval` 保持 Kafka 会话。

### 4.4 回调和未知结果

网络超时不能证明 Worker 没有完成。Callback Receipt 以 Task 和幂等键唯一约束，Payload 绑定 Run、ExecutionAttempt、预留 Version、Schema Version 和 Digest。重复回调返回同一语义结果，未知结果先进入可恢复状态，不直接标记失败后重复写文件。`[目标设计]` Worker 只提交强类型 ArtifactCandidate Envelope 和 Content/Validator/Citation/Trace Receipt 引用；Host 不把混有执行计划、审批、写回和调试信息的任意 JSON 直接提升为业务 Version，也不信任 Worker 回显的 Job/Version Snapshot 覆盖业务真源。

## 5. Artifact 产物契约

### 5.1 输入快照

快照至少冻结用户要求、Source Scope、Control Pack、上下文引用、Skill Version 和触发类型。用户改变要求、参数或资料范围并再次生成时创建新 ArtifactRun，不覆盖旧运行；Worker 重启、租约接管和 Checkpoint 恢复只创建新 ExecutionAttempt，继续绑定原 Run 和 Version ID。Host 创建 Run 时预分配唯一 `artifact_version_id`，Worker 的所有 Candidate、文件和 Trace 都引用它；如果没有完整保存随机种子、temperature 和 Provider 快照，就不能宣称字节级完全复现。

预分配 ID 作为 ArtifactRun 上的唯一 `reserved_version_id` 处于内部 `RESERVED` 阶段，此时还没有业务 Version，也不进入用户版本列表。内容 Contract 通过后，Host 才用同一 ID 创建 Version 与 Manifest，进入不可下载、不可写回的 `DELIVERY_PENDING`；所有必需文件达到 `READY` 后才把 Version 提升为用户可见的 `READY`。首轮内容前取消只终结 Run 和 Reservation，不创建空 Version；交付链开始后的文件重试耗尽进入 `DELIVERY_FAILED`，显式重试可带同一 ID 回到 `DELIVERY_PENDING`。每次物化追加新的 DeliveryAttempt 和 Staging Key，只重试同一不可变内容；交付中取消或超过保留期后 Version 进入 `ABANDONED`。已经 `READY` 的 Version 不允许倒退为取消或废弃，后续只能归档、删除或追加式回滚；已经执行的外部写回需要独立补偿 Proposal。ID 不回收、不复用，只有 `READY` Version 能下载、采纳或写回。

指标分母按到达阶段确定。已经接受内容 Contract 判定的首轮 Candidate 进入 First-pass Yield；内容 Contract 通过后冻结 Delivery Contract，其中声明的全部必需文件进入 File Ready Rate，即使物化尚未开始，或最终失败、取消、废弃，也不能删掉；只有 `READY` Version 进入 Accepted Artifact Rate 的合格分母。尚未生成 Candidate 就废弃的空预留 ID 不进入内容或文件质量分母，但对应 Run 仍进入固定 Cohort 的严格接纳完成率。

### 5.2 逻辑版本和交付文件

`ArtifactVersion` 表示逻辑内容和引用，`ArtifactFile` 表示某种格式的交付对象、Mime、大小、SHA-256、存储位置和状态。Markdown 通过不代表 PDF 文件已经 READY，Job 终态应区分内容验证和文件导出。

### 5.3 质量双门禁

内容 Contract 检查章节、字段、引用、题目答案或结构要求；文件 Contract 检查 Mime、Digest、大小、对象存在、格式可读性和危险内容。PASS、WARN、FAIL、UNKNOWN 不能混成一个成功布尔值。

### 5.4 写回

Artifact Worker 只能返回候选结果。内容 Contract 通过后，Host 先提交不可见的 `DELIVERY_PENDING` Version，并为 Delivery Contract 声明的全部必需格式创建 `PENDING` Manifest。每次 DeliveryAttempt 再把文件写入独立 Staging Key，通过文件 Contract 后晋升对象并更新 Manifest。所有必需文件 `READY` 后，Version 才提升为 `READY`。写回预览可以提前生成，可执行 Proposal 必须绑定 `READY` Host Version、审批状态、幂等键和 Expected Head Version。

## 6. 学校场景演练

用户选择一组数据库课程资料，要求生成“概念讲义 + 课后题 + 参考答案”。

1. Host 根据 Action Key 选择 Skill，并锁定 Source Scope。
2. Host 预分配 Version ID，Skill Compiler 生成章节、例题、答案和引用节点。
3. Kafka Consumer 幂等登记 Durable Execution 后提交 Offset，Scheduler 再分配 Active Permit 和 Worker Lease。
4. Worker 按 Skill Graph 执行；资料缺失且该能力具备持久等待合同时释放 Active Permit 并进入 Waiting。
5. 内容 Validator 发现两道题缺少来源，触发局部 Repair，而不是重生成全部讲义。
6. 内容 Contract 通过后，Host 创建不可见的 `DELIVERY_PENDING` Version 与全部必需文件的 `PENDING` Manifest。
7. DeliveryAttempt 在 Staging 完成导出和检查，所有必需文件 READY 后，Version 提升为 `READY`，用户才能比较版本，并通过带 Expected Revision 的 Proposal 写回 Wiki 或保存为 Source。

## 7. 指标

| 指标 | 计算 | 说明 |
| --- | --- | --- |
| Skill Compile Success | 生成合法 ExecutionSpec 的 ArtifactRun / 接受编译的 ArtifactRun | 输入和能力合同是否稳定；Job 是长期用户意图，不是编译次数 |
| Admission Rejection Rate | 准入拒绝 / 创建请求 | 配额或能力不匹配，不等于系统故障 |
| First-pass Yield | `首轮通过 Validator 的 Candidate / 已接受首轮 Contract 判定的 Candidate` | 首次生成质量 |
| Repair Yield | `Repair 后通过最终 Contract 的 Candidate / 进入 Repair 的 Candidate` | 局部修复价值 |
| File Ready Rate | `READY 且对象可读取的必需 File / 内容通过后冻结的全部必需 File` | 交付链路完整性 |
| Unknown Outcome Rate | UNKNOWN Task / 已发起 Task | 回调和超时治理 |
| Write-back Safety Violation | 越权或旧版本写回数 | 必须为 0 |
| Cost per Accepted Artifact | `总成本 / 观察窗口内有明确用户接受动作、且达到 READY 的产物` | 业务产出成本 |

推荐简历表达：

> 设计受控 Agent 执行框架并落地 Artifact 产物生成，将 Skill 编译、Capability 准入、Redis 速率与并发配额、Waiting/Resume、局部 Repair、Callback Receipt 和 Host 写回串成可恢复链路；产物按 Version/File 双层契约交付，避免 Worker 直接覆盖 Workspace。

## 8. 面试官连续追问

### 为什么不用一个万能 Prompt

不同产物的输入、结构、引用、文件和验证规则不同。Skill 把这些要求版本化，既能复用执行底座，也能按产物类型替换 Contract。

### MCP 会不会让 Agent 权限失控

MCP 只统一能力协议，不自动授予能力。Host 先做 Capability Resolution，再将允许的工具、参数 Schema、Workspace Scope 和副作用等级写入 ExecutionSpec。

### PDF 导出失败，Job 算成功吗

逻辑 Artifact Version 可能成功，但对应 Artifact File 为 FAILED。用户可以使用 Markdown 或重试导出，不能把两者混成一个状态。

### 为什么不全量重生成

全量重生成会放大成本和不稳定性。局部 Repair 只重跑失败节点，同时最终 Contract 再检查跨章节一致性。若上游事实变化或结构性失败，才升级为全量重跑。

### 版本回滚会不会覆盖用户新修改

回滚创建触发类型为 `ROLLBACK` 的新 ArtifactRun，并追加带 `restored_from_version_id` 的新 Version，再用 Expected Head Version 做 CAS；不能把 Current Pointer 倒拨到历史行。文件对象只有在内容寻址、Checksum、权限域和保留状态都仍有效时才能复用，新 Version 仍保存完整 Manifest。写回外部 Note/Wiki 使用独立 Proposal 并再次校验目标 Head。当前部分写回能力已有边界，完整多人并发发布协议属于目标设计。

### AI 写了很多代码，你做了什么

我负责把自由生成拆成 Skill、ExecutionSpec、Capability、Verifier 和写回边界，定义失败状态和指标，并用故障案例判断哪些副作用必须留在 Host。AI 可以生成模板和测试，但不能替代这些边界决策。

## 9. 4 分钟标准主回答

这个功能从学校资料生成讲义和题库开始。低风险摘要走 Lite Skill，一次生成加格式检查即可；多资料、长任务和文件交付进入 Structured Artifact。Skill 定义输入输出、工具和 Validator，Compiler 把用户要求、Source Scope、Control Pack 和 Provider 能力编译成 ExecutionSpec，Host 同时预分配唯一 Version ID。

执行前做 Capability Resolution 和 Admission。Kafka Consumer 只把命令幂等登记为 Durable Execution，随后提交 Offset；Scheduler 再申请 Active Permit 和 Execution Lease，避免一小时任务阻塞 Kafka 分区。任务遇到外部能力不可用时，只有具备持久等待合同时才进入 Waiting，并释放 Active Permit。Validator 只修复失败节点，最终再检查整体 Contract。Worker 回调用 Task、Host Version ID、幂等键和 Payload Digest 绑定。

ArtifactJob 表示稳定用户意图，ArtifactRun 表示一次生成、再生成或追加式回滚请求，ExecutionAttempt 表示基础设施重试和接管，ArtifactVersion 表示 Host 提交的逻辑内容，ArtifactFile 表示 PDF 或 Markdown 交付。Worker 不创建第二套业务版本。Host 预留的 ID 初始不可见；内容 Contract 通过后，Host 先在同一提交边界创建 `DELIVERY_PENDING` Version，并冻结全部必需文件的 `PENDING` Manifest，再启动 DeliveryAttempt，把文件写入 Staging 并逐个校验。必需文件全部 READY 后，Version 才对用户可见并允许写回。对象存储和数据库之间采用 Manifest、Checksum、补偿和对账。核心指标包括 First-pass Yield、Repair Yield、File Ready Rate、Unknown Outcome 和单位成功成本。

这套设计比万能 Prompt 复杂，但能解释输入、能力、版本、失败和写回。简单摘要可以走轻量 Skill，不需要完整动态图；涉及多工具、长任务和文件交付时，受控执行才有价值。

## 10. 八股映射

重点对应编译器中间表示、DAG 拓扑排序、Redis 令牌桶与 Lua 原子性、Semaphore 与 Lease、Kafka/HTTP 至少一次回调、对象存储一致性、Checksum、状态机、MCP 最小权限和 SSRF。完整二阶回答见[二阶追问回答库](36-重点知识点二阶追问回答库.md)。

## 11. 深挖附件

Skill Compiler、Capability Resolution、Quota、Waiting/Resume、Repair 和 Artifact Version/File 的独立 3 分钟回答见[重点知识点三分钟深挖库](35-重点知识点三分钟深挖库.md)，MCP 安全、配额过量和文件一致性的二阶回答见[重点知识点二阶追问回答库](36-重点知识点二阶追问回答库.md)。

| 侧重点 | 附件 |
| --- | --- |
| 通用 Agent 架构、配额、MCP、Lease 和源码导航 | [Agent 执行框架详细设计](04-Agent执行框架-详细架构与具体设计.md) |
| 通用执行演进、案例、消融和 Ownership | [Agent 执行框架演进专项](21-Agent执行框架演进案例消融与Ownership答辩.md) |
| Artifact 业务案例、指标、格式和宣传口径 | [Artifact 产物生成专项](23-Artifact-Agent产物生成演进案例指标与Ownership答辩.md) |
| Artifact 请求、文件、审批和一致性契约 | [Artifact 契约级数据模型](24-Artifact-Agent契约级数据模型与面试官下钻.md) |
| Task、Quota、Waiting 与运行时调度 | [Task 调度与运行时治理](38-Task调度-Quota与运行时治理一体化面试手册.md) |
| 简短问答速查 | [Agent 执行面试题](04-Agent执行框架-面试题与参考回答.md) |

## 12. 面试前自检

1. 能区分 Skill、ExecutionSpec、Capability、Job、Version 和 File。
2. 能解释速率、并发、租约和配额分别保护什么。
3. 能说清 Waiting、Resume、Repair 和 Retry 的差异。
4. 能区分 PASS、WARN、FAIL、UNKNOWN。
5. 能解释为什么写回由 Host 掌握。
6. 能拿出一个 Artifact 文件失败但逻辑版本保留的案例。

## 13. 产物指标、行业口径和宣传边界

Artifact 的成功至少有四层：任务执行完成、内容 Contract 通过、交付文件 READY、用户最终采纳。只报“生成成功率”会把结构不合格、文件损坏和无人采用的结果混在一起。

| 层次 | 指标 | 公式 | 代表什么 |
| --- | --- | --- | --- |
| 执行 | Terminal Execution Success Rate | `执行成功的 ArtifactRun / 已进入终态且排除可证明用户主动取消的 ArtifactRun` | 调度和 Worker 是否完成，不代表内容质量；必须与固定接纳 Cohort 的严格完成率、取消率和超期未终结率并列 |
| 内容 | First-pass Yield | `首轮通过内容 Contract 的 Candidate / 已接受首轮 Contract 判定的 Candidate` | Skill、Prompt 和 Validator 的首次质量 |
| 修复 | Repair Yield | `Repair 后通过最终 Contract 的 Candidate / 进入 Repair 的 Candidate` | Repair 是否比全量重跑有效 |
| 文件 | File Ready Rate | `READY 且对象可读取的必需 File / 内容通过后冻结的全部必需 File` | PDF、Markdown 等能否真正交付 |
| 引用 | Citation Completeness | `至少绑定一个有效 Citation 的应引用 Claim / 全部应引用 Claim` | 讲义和报告是否漏引；完整 Evidence 支持另看 Claim-Evidence Coverage |
| 恢复 | Unknown Outcome Rate | `进入 UNKNOWN 的投递 / 已发起投递` | Callback 与外部副作用是否可判断 |
| 业务 | Accepted Artifact Rate | `观察窗口内有明确用户接受动作的 Artifact / 同窗口内达到 READY 且具备接受资格的 Artifact` | 用户是否愿意采用，自动导出、后台保存和系统重试不算接受动作 |
| 成本 | Cost per Accepted Artifact | `模型、工具、转换和存储成本 / 观察窗口内有明确用户接受动作、且达到 READY 的 Artifact` | 单位有效交付成本 |

First-pass Yield 的分母应是完成首轮生成并接受内容 Contract 判定的 Candidate，不要求它已经提交为业务 Version；Repair Yield 的分母是进入 Repair 的 Candidate，不是最终才创建的 Version；File Ready Rate 的分母是内容通过后冻结的全部必需文件，即使某个文件连第一轮物化都没有启动，也不能删掉。三者不能相互替代。一份 Markdown 内容通过但 PDF 转换失败，应计入内容成功、文件失败；局部 Repair 后通过计入 Repair Yield，不回填首轮成功。Accepted Artifact 需要定义观察窗口，比如完成后七天内发生明确的用户保存、主动导出、写回确认或收藏动作，后台自动保存和系统重试不算接受，避免无限等待造成分母漂移。

`[行业参考]` LangGraph 和 Temporal 强调有状态执行与恢复，MCP 强调模型和工具的标准能力接口，OpenAI Agents、AutoGen 和 CrewAI 常用 Tool、Handoff、Trace 或 Flow 描述执行能力；文档与演示产品更常宣传生成格式、模板数量、耗时和导出能力。NoteWeave 的可宣传点不应是“接了多少工具”，而是内容 Version 与交付 File 分层、Capability 最小权限、局部 Repair、未知结果治理和受控写回。外部依据见[Agent 执行框架调研](../research/Agent执行框架演进取舍外部依据.md)和[Artifact 产品宣传与评测口径](../research/Artifact产物生成产品宣传与评测口径.md)。

### 面试官问“局部 Repair 真能省成本吗”

用同一批失败 Artifact 做配对实验。Baseline 全量重生成，Treatment 只重跑失败节点，固定输入 Snapshot、模型、Validator 和最大尝试次数。主结果看 Repair Yield，护栏看最终 Contract Pass、跨章节一致性和新增错误率，成本看 Token、工具调用、墙钟时间和 Provider 费用。

`Repair Saving = 1 - Treatment Cost / Baseline Full-regeneration Cost`。如果成本下降但最终 Contract Pass 下降，不能宣传节省；如果 Repair Yield 很高但大多数失败只是格式空格，也不能外推到引用错误和事实错误。必须按 Schema、Citation、File、Provider 和 Global Consistency 失败类型分桶。

`[演练假设]` 可以用 `N=80` 个学校讲义和题库任务说明口径：First-pass Yield `71%`，触发局部 Repair 的任务中 `83%` 最终通过，平均 Token 比全量重生成少 `38%`，File Ready Rate `96%`，用户采纳率 `64%`。没有 Manifest、原始产物和人工复核时，这些数字只能作为理想数据卡，不能写成已达成绩。

### 二阶追问：文件 READY 后为什么仍不能直接写回 Workspace

READY 只证明文件通过对象存在、Checksum、Mime 和可读性门禁。写回还要校验目标 Workspace、当前 Head Version、操作者权限、副作用等级和审批策略。旧任务晚到时即使文件合法，也只能生成 Proposal 或新 Version，不能覆盖用户在等待期间完成的新修改。
