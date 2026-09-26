# Deep Research 简历能力落地执行计划

> 若当前目标仅为完成“最小但经得住追问”的简历能力，请优先执行 [`DeepResearch-最小简历闭环执行计划.md`](./DeepResearch-最小简历闭环执行计划.md)。本文保留完整生产化路线，不再作为最小闭环的日常任务清单。

> 状态：执行中，按真实 MA4 主路径验收  
> 更新时间：2026-09-19  
> 权威架构：[Research Agent 架构](./DeepResearch-ResearchAgent架构文档.md)  
> 目标：只实现并验证简历描述中的验证驱动、可恢复 Deep Research Agent，不扩展无关能力。

## 1. 目的

本文把下面一句简历描述拆成可执行、可验收、可复现的工程任务：

> 设计验证驱动、可恢复的 Deep Research Agent：针对开放研究易漂移、证据不足和长任务中断的问题，将研究过程抽象为 `Table-as-State`，显式维护研究对象、待验证字段、Evidence 与结论，通过 Plan / Replan、Verifier 和受限反证持续纠偏；结合 Checkpoint 与引用审计支持任务断点恢复和结论逐项回溯。

本文不是第二份架构说明。领域模型、状态定义和接口契约仍以权威架构文档、API 与事件契约及数据库迁移为准。本文只回答四件事：还缺什么、按什么顺序做、怎样验收、拿什么证明。

## 2. 范围边界

### 2.1 本期必须交付

- `Table-as-State` 能显式保存研究对象、待验证字段、证据候选、验证结果和最终结论。
- Plan 能生成可执行 Cell，Replan 只修复失败或缺证据的 Cell，并留下审计记录。
- Verifier 位于真实主链路，而不是旁路或仅有数据结构。
- 受限反证只针对有争议或高风险结论，受预算、次数和来源范围约束。
- Checkpoint 能让同一 Research Run 在新 Execution Attempt 中恢复。
- 已确认的搜索、抓取和模型调用收据在恢复时可复用，不重复计费。
- 报告中的每条提升结论都能回溯到 Cell、Candidate、Evidence、精确引文、归档快照和原始 URL。
- 固定真实 Provider 评测集能生成可复现的指标和实验 Manifest。

### 2.2 明确不做

- WIDE_DISCOVERY。
- 多 Agent 编排、横向 Worker 扩容和高并发压测。
- 跨地域灾备与生产级多活。
- 与本简历描述无关的 Artifact、Memory、Wiki 或 UI 功能扩展。
- 为了数字好看而放宽证据校验、伪造结果或把模拟数据写成生产效果。

验收环境固定为高级特性关闭、单 Worker、并发度 1。先证明一条链路正确，再讨论规模化。

## 3. 简历描述到工程证据的映射

| 简历主张 | 必须存在的实现 | 必须提供的验收证据 |
| --- | --- | --- |
| Table-as-State | Run、Row/Object、Cell/Field、Candidate、Evidence、Decision 的持久化关系 | 数据库快照、状态迁移日志、单 Run 可视化导出 |
| Plan / Replan | 初始 Plan、局部修复策略、旧新 Plan 摘要、原因与预算差异 | 缺证据和冲突用例的 Replan 审计记录 |
| Verifier | Cell 级验证、类型校验、精确引文校验、局部与全局一致性判断 | Verifier 决策记录及拒绝原因分布 |
| 受限反证 | 触发条件、查询约束、调用次数和预算上限 | 冲突用例中的反证轨迹及停止原因 |
| Checkpoint 恢复 | 阶段屏障、外部调用收据、Attempt fencing、恢复入口 | Worker 中断后恢复并完成的故障注入记录 |
| 引用审计 | Claim 到归档引文的完整引用链 | 自动审计报告，所有提升结论逐项可追溯 |
| 效果较好 | 固定数据集、指标脚本、版本和 Provider 清单 | 实验 Manifest、原始结果、聚合指标和 Bad Case |

只有右侧证据齐全，相关主张才可写入简历。

## 4. 当前已验证基线

### 4.1 已经跑通的部分

`[当前实现]` 当前链路已能完成：

```text
用户问题
  -> Java 创建 Research Run
  -> Kafka 投递命令
  -> Python Worker claim
  -> 真实联网搜索
  -> Jina 真实抓取
  -> GLM 真实调用
  -> 部分 Evidence / Candidate 写入 Table-as-State
```

`[生产待验证]` 代表性真实调用中观察到 `search_calls=1`、`fetch_calls=1`、`read_calls=1`、`llm_calls=1`、`evidence_cards=3`、`candidates_submitted=3`、`candidate_merges_accepted=3`。这些数字只证明对应测试运行发生过真实外部调用，不代表稳定成功率。当前 Manifest 中 Serper Key 为 `UNSET`，搜索链可能回落到其他 Adapter，因此最终验收前必须单独取得 Serper 真实调用收据。

### 4.2 当前主要断点

`[生产待验证]` 当前还不能稳定完成：

```text
全部必需 Cell 收敛
  -> Verifier 形成决策
  -> Checkpoint 可恢复
  -> 报告生成
  -> 引用审计通过
```

已观察到某个 `limitations` Cell 在完成一次模型调用后没有形成 Evidence，终止原因为 `NO_SUPPORTED_CANDIDATE`，最终 Run 进入 `RESEARCH_AGENT_TERMINAL_BARRIER_UNSATISFIED`。这说明真实 Provider 可达，但证据不足后的修复、业务终态和后续屏障尚未闭环。

代表性 Run：

- `31d1d44f-85b9-44e3-b1fd-89dcbefa3c14`
- `2f2e25c9-54a0-4993-837f-822e4e633b54`
- `b53ee5dd-c630-42d0-9b61-35ed4eff81a8`

### 4.3 完成度判断

`[生产待验证]` 按简历整句能力而不是代码数量评估，目前约完成 60% 到 70%。M0、M1 已完成；M3 的提升门禁和诚实终态已进入真实主路径；M2 的 CellRecoveryPolicy 已完成模块与 legacy loop 接线，但尚未证明 MA4 生产路径会执行同等局部修复；M4 正在收口任务级恢复，M5、M6 尚未完成。预计还需 12 到 18 个有效工程日。

### 4.4 2026-09-19 主路径审计后的执行状态

状态只按真实 MA4 路径和机器可读证据判定，不再把 legacy loop 单测通过等同于生产能力完成。

| 范围 | 当前状态 | 已有证据 | 尚缺退出条件 |
| --- | --- | --- | --- |
| M0 基线与评测集 | `DONE` | Manifest、三个失败 Run、24 题数据集 | 最终 M6 前重建合规 Manifest |
| M1 抽取诊断 | `DONE` | 结构化拒绝原因、真实 GLM 诊断落库、replay fixture | 无 |
| M2-A 恢复策略模块 | `DONE` | CellRecoveryPolicy、停止保护、legacy loop 测试 | 不作为生产主路径完成证明 |
| M2-B MA4 局部修复与 Replan | `IN_PROGRESS` | Java Discovery Replan 审计接线 | MA4 实际修复路径、真实 Replan 审计行 |
| M3 Verifier 与诚实终态 | `IMPLEMENTED` | Java 独立门禁、终态列、真实 `INSUFFICIENT_EVIDENCE` | 冲突和受限反证真实 E2E |
| M4-A Hydration 正确性 | `VERIFYING` | V108、幂等与 livelock 测试；V108 已进入真实 MySQL | 真实 Hydration 故障演练 |
| M4-B Task 级恢复 | `VERIFYING` | 外部归档复用、V109、Worker 全量 631 例通过；V109 已进入真实 MySQL | 真实重领故障注入与账本证据 |
| M4-C Run/Checkpoint 恢复 | `PENDING` | Schema 设计 | 成功路径 Checkpoint、跨 Attempt 恢复与去重 |
| M5 引用审计与报告 | `PENDING` | 既有局部 Evidence Audit 原语 | 完整引用链和三类诚实报告 |
| M6 固定评测与简历证据 | `PENDING` | 24 题数据集 | 合规环境、真实 Provider 全量运行、指标脚本 |

当前执行顺序固定为：

```text
E1 修复 MA4 当前红测并收口任务级恢复
  -> E2 应用 V108/V109，验证真实 MySQL 与锁顺序
  -> E3 补齐 MA4 主路径的局部修复与真实 Replan 证据
  -> E4 完成 Run/Checkpoint 恢复
  -> E5 完成引用审计与限制性报告
  -> E6 在合规环境跑 24 题并生成简历证据
```

2026-09-19 执行记录：

- `[已测-模拟]` 修复 MA4 HTTP permit 测试桩后，`test_ma4_task_level_recovery.py` 为 7 passed。
- `[已测-模拟]` Research Worker 全量回归为 631 passed。
- `[当前实现]` backend 镜像以当前源码编译成功，容器健康。
- `[当前实现]` 开发 MySQL 已由 V107 顺序迁移至 V109，V108/V109 均成功。
- `[生产待验证]` D-33 的真实 MySQL `tick` 锁矩阵首次尝试证明现有 task-insert 闸门不适用于 `tick()`，测试容器无法形成预期第一段等待图；新增试验用例已撤回，未修改生产锁逻辑迎合测试。下一步需为 `adjudicateRun` 的 run-to-cell 顺序设计专用 cell-update 闸门。
- `[生产待验证]` 真实 Task 重领和跨 Attempt 恢复仍未完成。

## 5. 完成定义

以下条件必须同时满足：

- [ ] 高级开关关闭、单 Worker、并发度 1 时，固定真实 Provider 套件可以完整执行。
- [ ] 每个 Run 都进入可解释的业务终态，不出现无法解释的 terminal barrier。
- [ ] Evidence 不足时输出受保护的限制性报告或证据不足结果，不虚构结论，也不伪装成基础设施故障。
- [ ] 每条进入最终报告的结论都能追到精确归档引文和 URL。
- [ ] 精确引文不匹配、未知 window、错误字段、非法 JSON 等拒绝原因可观测。
- [ ] Worker 在定义的故障点中断后，同一 Run 能由新 Attempt 从 Checkpoint 恢复。
- [ ] 恢复后不重复已确认的 Serper、Jina 和 LLM 外部操作。
- [ ] 过期 Attempt 的回调不会推进状态或覆盖新结果。
- [ ] 指标由脚本从原始运行记录生成，并绑定代码版本、配置和数据集版本。
- [ ] 失败用例和 Bad Case 与成功样本一并保留，任何结果均可复跑。

允许的业务完成结果包括：

- `COMPLETED_VERIFIED`：所需结论全部通过验证。
- `COMPLETED_WITH_LIMITATIONS`：主问题有可靠回答，但明确保留未解决 Cell 或限制。
- `INSUFFICIENT_EVIDENCE`：预算内无法获得足够证据，不提升未经支持的结论。
- 基础设施或协议失败：只用于 Provider、网络、存储、消息或契约确实失败的情况。

枚举名最终以领域模型和迁移为准，关键是不要再把证据不足等同于系统异常。

## 6. 目标执行流

```text
Question + RunInputSnapshot
  -> Plan
  -> Table-as-State 初始化
  -> 对未完成 Cell 执行 Search / Fetch / Extract
  -> Evidence Qualification
       -> 接受：写 Candidate 与 Evidence
       -> 拒绝：写结构化原因
  -> Cell Verifier
       -> 通过：冻结 Cell
       -> 缺证据：局部 Replan
       -> 有冲突：受限反证，再次验证
       -> 预算耗尽：标记 unresolved
  -> Global Verifier
  -> RunCompletionGate
  -> Synthesis
  -> Citation Audit
  -> Report + Experiment Evidence
```

每个外部调用收据和阶段屏障后写 Checkpoint。恢复时创建新的 Execution Attempt，读取同一 Run 的 Checkpoint，只继续未完成 Cell。

## 7. 深模块与接口边界

### 7.1 EvidenceExtraction 模块

职责是把 Provider 输出转成可验证的 Evidence Card，不负责决定 Run 是否完成。

建议接口：

```text
extract(cell, readingWindows, providerPolicy)
  -> ExtractionResult {
       acceptedCards,
       rejectedCards,
       providerReceipt,
       terminationReason
     }
```

拒绝原因至少包括：`INVALID_JSON`、`UNKNOWN_WINDOW`、`WRONG_COLUMN`、`NON_EXACT_QUOTE`、`EMPTY_CLAIM`、`UNSUPPORTED_RELATION`。接口返回结构化结果，让上层可以恢复和决策，避免从日志字符串猜测失败原因。

### 7.2 CellRecoveryPolicy 模块

职责是根据 Cell 当前状态选择有界动作，不直接调用 Provider。

```text
decide(cellState, rejectionSummary, budget, history)
  -> RETRY_EXTRACTION
   | REREAD_WINDOW
   | TARGETED_SEARCH
   | COUNTEREVIDENCE_SEARCH
   | FREEZE_UNRESOLVED
   | STOP_BUDGET_EXHAUSTED
```

该 Module 应隐藏重试阈值、动作优先级和预算扣减细节，只向 Runner 暴露一个稳定决策 Interface。这样可以提高 Depth 和 Locality，避免恢复规则散落在搜索、抽取和 Runner 中。

### 7.3 RunCompletionGate 模块

职责是根据必需 Cell、验证结果、预算和基础设施状态决定业务终态。它是证据管线与报告生成之间的 Seam。

```text
evaluate(tableState, verifierState, budgetState, infraState)
  -> CompletionDecision {
       terminalState,
       promotableClaims,
       unresolvedCells,
       limitations,
       reasonCodes
     }
```

该接口必须允许部分结果诚实完成，禁止将缺证据压扁成不透明异常。

### 7.4 CheckpointRecovery 模块

职责是固化可恢复状态、Hydrate 新 Attempt、复用已确认收据和拒绝过期写入。

```text
save(stageBarrier, tableDigest, receipts, budgetLedger, attemptFence)
hydrate(runId, newAttemptId)
acceptCallback(runId, attemptId, fenceToken, receiptId)
```

Provider Adapter 只负责外部协议和收据标准化。恢复策略不进入 Adapter，保证外部服务替换时核心状态机不变。

### 7.5 CitationAudit 模块

职责是证明每条提升结论的完整引用链：

```text
Report Claim
  -> Cell Decision
  -> Candidate
  -> Evidence
  -> Exact Quote
  -> Archived Snapshot
  -> Source URL + Content Digest
```

审计失败的 Claim 不得进入已验证结论区，只能降级到限制或待验证区。

## 8. 里程碑与任务分解

角色缩写：`BE` 为 Java 后端，`WK` 为 Python Worker，`QA` 为测试与评测。一个人执行时按依赖顺序承担三个角色。

### M0 基线冻结，1 到 2 天

| ID | 任务 | 负责人 | 依赖 | 估时 | 退出条件 | 证据产物 |
| --- | --- | --- | --- | --- | --- | --- |
| DR-000 | 固化真实 Provider 配置、开关快照和版本信息 | BE/WK | 无 | 0.5 天 | Manifest 可重建当前环境 | `experiment-manifest.json` |
| DR-001 | 保存三个代表性失败 Run 的状态、日志和数据库导出 | QA | DR-000 | 0.5 天 | 可离线复盘 terminal barrier | 基线证据包 |
| DR-002 | 建立 20 到 30 题固定评测集及类别标签 | QA | 无 | 1 天 | 样本包含正常、缺失、冲突和故障场景 | 数据集版本与说明 |

### M1 抽取诊断闭环，3 到 4 天

| ID | 任务 | 负责人 | 依赖 | 估时 | 退出条件 | 证据产物 |
| --- | --- | --- | --- | --- | --- | --- |
| DR-101 | 定义 `ExtractionResult` 和拒绝原因枚举 | WK | M0 | 0.5 天 | 所有抽取出口返回结构化结果 | 契约测试 |
| DR-102 | 持久化 accepted/rejected Card、模型和收据摘要 | WK/BE | DR-101 | 1 天 | 每次抽取可解释零卡原因 | DB 与事件样本 |
| DR-103 | 为非法 JSON、未知 window、错误列、非精确引文等补测试 | QA/WK | DR-101 | 1 天 | 每类错误有确定 reason code | 测试报告 |
| DR-104 | 增加脱敏 replay fixture，支持离线重放 Provider 响应 | WK | DR-102 | 1 天 | 同一 fixture 产生稳定判断 | 重放命令与结果 |

### M2 Cell 修复与可审计 Replan，4 到 6 天

| ID | 任务 | 负责人 | 依赖 | 估时 | 退出条件 | 证据产物 |
| --- | --- | --- | --- | --- | --- | --- |
| DR-201 | 实现 `CellRecoveryPolicy` 决策接口 | WK | M1 | 1 天 | 每个缺证据 Cell 获得显式动作 | 单元测试 |
| DR-202 | 实现抽取重试、重读、定向搜索和冻结 unresolved | WK | DR-201 | 2 天 | 不重做已完成 Cell，动作受预算限制 | 集成测试轨迹 |
| DR-203 | 持久化 Replan 原因、影响 Cell、旧新 Plan digest 和预算差异 | BE/WK | DR-201 | 1 天 | 每次 Replan 可完整审计 | DB 记录样本 |
| DR-204 | 实现循环与预算停止保护 | WK | DR-202 | 1 天 | 重复失败不会无限执行 | 故障注入结果 |

### M3 Verifier 与诚实终态，5 到 7 天

| ID | 任务 | 负责人 | 依赖 | 估时 | 退出条件 | 证据产物 |
| --- | --- | --- | --- | --- | --- | --- |
| DR-301 | 把 Cell Verifier 接入实际 Runner 主路径 | WK | M2 | 1.5 天 | Candidate 必经验证才能提升 | 主链路测试 |
| DR-302 | 实现类型、精确引文和来源关系校验 | WK | DR-301 | 1.5 天 | 错误类型与伪引文被拒绝 | 负例报告 |
| DR-303 | 实现局部与全局冲突判断 | WK | DR-301 | 1 天 | 冲突进入明确状态 | 冲突轨迹 |
| DR-304 | 实现受限反证触发、预算和停止条件 | WK | DR-303 | 1 天 | 只对目标 Claim 反证且不会失控 | 调用账本 |
| DR-305 | 实现 `RunCompletionGate` 和业务终态 | BE/WK | DR-301 | 2 天 | 缺证据可诚实完成，不再落入不透明 barrier | 状态契约与 E2E |

### M4 Checkpoint 与真实恢复，5 到 7 天

| ID | 任务 | 负责人 | 依赖 | 估时 | 退出条件 | 证据产物 |
| --- | --- | --- | --- | --- | --- | --- |
| DR-401 | 明确外部收据和阶段屏障的 Checkpoint Schema | BE/WK | M1 | 1 天 | Schema 能表达恢复所需最小状态 | 迁移与契约 |
| DR-402 | 在搜索、抓取、抽取确认后写 Checkpoint | WK | DR-401 | 1.5 天 | 任一阶段中断均有最近安全点 | Checkpoint 样本 |
| DR-403 | 新 Attempt Hydration 并只调度未完成 Cell | BE/WK | DR-402 | 2 天 | 同 Run 可跨 Worker 继续 | 恢复 E2E |
| DR-404 | 实现 receipt 去重、fencing 和 stale callback 拒绝 | BE/WK | DR-403 | 1.5 天 | 已确认外部调用不重复，旧 Attempt 不可写入 | 故障注入日志 |
| DR-405 | 校验预算账本在恢复前后守恒 | QA/WK | DR-403 | 0.5 天 | 重启不重置或重复扣费 | 账本断言 |

### M5 引用审计与报告，3 到 4 天

| ID | 任务 | 负责人 | 依赖 | 估时 | 退出条件 | 证据产物 |
| --- | --- | --- | --- | --- | --- | --- |
| DR-501 | 建立 Claim 到 Snapshot 的可遍历引用链 | BE/WK | M3 | 1.5 天 | 每条提升 Claim 有完整外键或稳定标识链 | 引用链样本 |
| DR-502 | 实现自动 Citation Audit | WK/QA | DR-501 | 1 天 | 缺失或不精确引用阻止 Claim 提升 | 审计报告 |
| DR-503 | 生成 verified、limitations、unresolved 分区报告 | WK | DR-305, DR-502 | 1 天 | 部分结果可读、诚实且逐项可回溯 | 三类报告样本 |

### M6 固定评测与简历证据，4 到 5 天

| ID | 任务 | 负责人 | 依赖 | 估时 | 退出条件 | 证据产物 |
| --- | --- | --- | --- | --- | --- | --- |
| DR-601 | 自动运行固定真实 Provider 套件 | QA | M4, M5 | 1 天 | 所有样本有终态和原始记录 | Run 清单 |
| DR-602 | 执行 JSON、引文、Provider、重启和回调故障注入 | QA | DR-601 | 1 天 | 故障矩阵全部有确定预期 | 故障报告 |
| DR-603 | 编写指标聚合脚本 | QA/BE | DR-601 | 1 天 | 从原始记录一键生成指标 | 脚本与输出 |
| DR-604 | 复盘 Bad Case 并完成一次门禁复测 | 全员 | DR-602, DR-603 | 1 到 2 天 | 目标门禁通过或明确记录未通过项 | 最终证据包 |

## 9. 验收矩阵

| 能力 | 验收场景 | 预期结果 |
| --- | --- | --- |
| Table-as-State | 比较型问题包含多个对象和字段 | 每个 Cell 独立推进，完成 Cell 不因其他 Cell 重试而回退 |
| 抽取可观测 | 模型返回非法 JSON 或不存在的 window | Card 被拒绝，原因结构化持久化，Run 可继续修复 |
| Replan | 一个必需 Cell 无支持证据 | 只重试该 Cell，记录 Plan 差异，预算耗尽后冻结 unresolved |
| Verifier | 引文存在但不支持 Claim | Candidate 不提升，报告中不出现该结论 |
| 受限反证 | 两个可信来源结论冲突 | 触发定向反证，达到次数或预算后停止并保留冲突 |
| 诚实终态 | 主要结论可验证，次要字段缺证据 | `COMPLETED_WITH_LIMITATIONS`，报告明确限制 |
| Checkpoint | 抓取完成后 Worker 退出 | 新 Attempt 复用抓取收据，从后续阶段恢复 |
| Fencing | 旧 Attempt 延迟回调 | 回调被拒绝且不改变 canonical state |
| 引用审计 | 报告 Claim 缺少精确引文 | 审计失败，Claim 降级或报告生成被保护性阻断 |
| 可复现性 | 使用同一 Manifest 重跑 | 配置、版本、数据集和结果差异均可解释 |

## 10. 测试与故障注入矩阵

| 类别 | 注入点 | 断言 |
| --- | --- | --- |
| 正常证据 | 搜索、抓取、抽取均成功 | Cell 验证通过，报告引用链完整 |
| 证据缺失 | 搜索无有效结果 | 有界 Replan 后进入 unresolved 或证据不足终态 |
| 来源冲突 | 两个高质量来源互相矛盾 | 受限反证启动，冲突不会被静默覆盖 |
| 非精确引文 | quote 不是 window 原文子串 | Evidence 拒绝并记录 `NON_EXACT_QUOTE` |
| 非法结构 | LLM 输出非法 JSON | 可诊断重试，不生成脏 Candidate |
| Provider 失败 | Serper、Jina 或 GLM 超时/5xx | 根据收据状态安全重试，基础设施失败分类准确 |
| Worker 中断 | 外部调用确认后、Checkpoint 前后分别终止 | 恢复不丢状态，不重复已确认调用 |
| 重复消息 | Kafka 命令重复投递 | 同一 Run/Attempt 幂等，不重复推进 |
| 乱序回调 | 旧 Attempt 晚于新 Attempt 返回 | fencing 拒绝旧写入 |
| 报告审计 | 删除 Evidence 或 Snapshot 关系 | Citation Audit 阻止对应 Claim 提升 |

## 11. 指标与目标门禁

以下均为 `[目标设计]`，不是当前已达成数字。最终简历只能引用固定评测生成的真实结果。

| 指标 | 目标门禁 | 计算口径 |
| --- | --- | --- |
| 不可解释终态失败数 | 0 | 验收套件中无法映射为业务或基础设施原因的 Run 数 |
| 提升结论引用可追溯率 | 100% | 完整通过 Claim 到 Snapshot 审计的提升结论数 / 全部提升结论数 |
| Stale callback 接受数 | 0 | 被旧 fence 推进 canonical state 的回调数 |
| 恢复后已确认外部操作重复数 | 0 | 恢复后相同幂等键再次产生计费调用的次数 |
| 定义内恢复场景通过率 | 100% | 通过的故障注入恢复场景 / 全部定义场景 |
| 无证据结论提升数 | 0 | 没有合格 Evidence 却进入 verified 区的 Claim 数 |
| Replan 局部性 | 100% | 未重新执行已冻结 Cell 的 Replan 数 / 全部 Replan 数 |

效果指标同时报告样本数、问题类别、Provider、模型、代码提交、配置摘要、均值与分位数。不能只报单个最好结果。

## 12. 排期与依赖

已完成 M0、M1 和 M3 主体后，剩余排期改为：

```text
剩余第 1 周：E1 任务级恢复 + E2 V108/V109 与锁矩阵
剩余第 2 周：E3 MA4 局部修复/Replan + E4 Checkpoint 恢复
剩余第 3 周：E5 引用审计与诚实报告 + E6 首轮评测
缓冲 2 到 3 天：Bad Case 修复、全量回归和最终证据包
```

关键路径改为 `E1 -> E2 -> E3 -> E4 -> E5 -> E6`。任何里程碑未满足退出条件时，不把后续里程碑标为完成。尤其禁止以 legacy loop 的测试结果替代 MA4 主路径证据。

## 13. 必须保留的证据产物

最终证据包至少包含：

- 代码 commit SHA、脏工作区说明和数据库迁移版本。
- 脱敏后的环境配置摘要、Feature Flag Snapshot 和 Provider/模型版本。
- 固定问题集的版本、类别与预期检查点。
- 每个 Run 的状态迁移、Cell 状态、外部收据、预算账本和终态。
- Verifier、Replan、受限反证和 Citation Audit 的结构化记录。
- Worker 中断与恢复前后的 Attempt、Checkpoint 和调用去重记录。
- 指标生成脚本、原始输入、聚合输出和 Bad Case 清单。
- 至少一个 verified、一个 completed-with-limitations、一个 insufficient-evidence 报告样本。

这些材料应由统一实验目录和 Manifest 串联，避免依赖截图或人工口述。

## 14. 风险、控制与回滚

| 风险 | 控制方式 | 回滚策略 |
| --- | --- | --- |
| 为修复终态而降低证据门槛 | Verifier 与 Citation Audit 使用硬门禁 | 恢复旧 CompletionGate，保留新数据不提升 Claim |
| Replan 形成无限循环 | Cell 级次数、总预算和重复 Plan digest 检测 | 关闭 Replan 开关，降级为 unresolved |
| Checkpoint Schema 不完整 | 每个恢复点做 Hydration 等价性测试 | 停止自动恢复，保留人工重跑入口 |
| Provider 非确定性影响复现 | 同时保存真实收据摘要和脱敏 replay fixture | 使用 fixture 定位逻辑，真实套件单独报告 |
| 旧 Attempt 污染新结果 | attemptId、fence token、receipt 幂等键三重校验 | 拒绝可疑回调，Run 转人工复核 |
| 只优化评测集 | 保留隐藏样本和失败类别分层 | 回退针对样本的特殊规则，要求通用 reason code |

## 15. 简历句子可使用的条件

完成 M0 到 M6 不自动等于可以写完整简历句子。还必须满足：

1. 真实 Provider 固定套件达到第 11 节门禁。
2. 至少一次 Worker 中断恢复证明没有重复已确认调用。
3. 至少一个冲突用例触发受限反证并留下停止原因。
4. verified 报告的提升结论全部通过引用审计。
5. limitations 和 insufficient-evidence 路径不会生成未经支持的确定性结论。
6. 所有效果数字都能从保存的 Manifest 和原始 Run 重新计算。

如果仅完成部分能力，简历必须降级表述。例如只有 Table-as-State 与真实搜索链路时，可以写“搭建了基于研究矩阵的 Deep Research 执行骨架”，不能声称已经实现可恢复、持续纠偏和逐项回溯。

## 16. 执行检查表

### 每日

- [ ] 选择一个任务 ID，不并行扩大范围。
- [ ] 修改前保存可复现失败样本。
- [ ] 先补契约、单元或集成断言，再修改状态逻辑。
- [ ] 记录新的 reason code、状态迁移和 Schema 变化。
- [ ] 使用同一输入完成修复前后对照。
- [ ] 检查是否重复外部调用、错误扣费或接受旧回调。
- [ ] 将原始证据加入实验目录，不手工改写指标。

### 每周门禁

- [ ] 本周里程碑的退出条件全部有机器可读证据。
- [ ] 真实 Provider E2E 至少跑一轮。
- [ ] 故障注入和正常路径均回归。
- [ ] 复盘新增 Bad Case，并归入既有 reason code 或明确新增契约。
- [ ] 更新完成度、剩余工期和风险，不新增非目标功能。
- [ ] 对照简历主张检查是否存在无法举证的词语。

## 17. 第一批执行顺序

立即按以下顺序启动，不等待未来功能规划：

1. 完成 DR-000 到 DR-002，冻结当前失败基线和评测集。
2. 完成 DR-101 到 DR-104，让 `NO_SUPPORTED_CANDIDATE` 可解释、可重放。
3. 完成 DR-201 到 DR-204，只修复失败 Cell，先解决当前 `limitations` Cell 的收敛问题。
4. 完成 DR-301 到 DR-305，让 Verifier 和诚实终态进入真实主路径。
5. 再完成恢复、引用审计和全套评测，不提前宣传效果数字。

本计划完成后的交付物不是“代码看起来具备这些类”，而是一条真实 Provider 链路、一个可恢复故障演练、一套逐项引用审计和一份可以复算的效果证据包。
