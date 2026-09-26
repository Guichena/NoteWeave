# Agent 执行框架：演进、案例、消融与 Ownership 答辩

> 默认主回答见[Agent 执行与 Artifact 一体化面试手册](32-Agent执行与Artifact产物一体化面试手册.md)。本文只负责通用执行原语的演进、替代方案、消融和 Ownership；Artifact 的内容、文件、版本和采纳指标继续放在 `23` 与 `24`，两层不要混讲。

> 本文补充两篇 `04-Agent执行框架` 主文档，聚焦通用执行原语。Artifact 业务能力、格式边界和产物质量指标见 [Artifact Agent 专项](23-Artifact-Agent产物生成演进案例指标与Ownership答辩.md)。`[当前实现]` 表示代码或测试可证；`[演练案例]` 与 `[理想消融数据]` 只用于设计和面试演练。

## 1. 一句话定位

我没有把 Agent 做成能任意循环调用工具的聊天机器人，而是把生成任务编译为版本化 ExecutionSpec 和受 Capability 约束的 Skill Graph，由 Java Host 持有身份、配额、状态和写回权限，Python Worker 只执行冻结输入；长任务通过 Kafka、Lease/Fencing、Waiting/Resume、Verifier 和局部 Repair 恢复。

准确边界是“编译期 DAG、运行期线性受控 Agentic Workflow”。当前并不是任意条件图或通用动态调度器；历史类名中的 `Capability Union` 表示收集各节点需求，最终有效权限仍是 Skill、Workspace、审批、运行环境和风险策略的交集，不是权限并集。

## 2. Git 事实线与设计演进线

### 2.1 Git 可验证节点

| 日期 | Commit | 可验证变化 |
|---|---|---|
| 2026-07-03 | `d869a603` | Workspace、Task 与资料基础链路 |
| 2026-07-05 | `307138bc` | Memory 与 Agent Foundations |
| 2026-07-05 | `1699e302` | Outbox 到 Worker 的执行派发 |
| 2026-07-05 | `a2254cca` | Research Dispatch 切换 Kafka，可靠原语开始共用 |
| 2026-07-18 | `a89869b6` | Durable Multi-agent Execution，Lease/恢复能力加深 |
| 2026-08-05 | `fd60682a` | Artifact Kafka、Callback、权限和运行时边界加固 |

### 2.2 设计演进线

```text
V0 一次 Prompt 直接生成文件
  -> V1 固定 Workflow，保留受限动态选择
  -> V2 Prompt Template 升级为版本化 Skill Contract
  -> V3 Skill 编译为 ExecutionSpec/DAG
  -> V4 Capability 交集与 MCP Host Control
  -> V5 Rate Limit + Concurrency Lease + Workspace Fairness
  -> V6 Kafka Delivery + Callback Idempotency + Fencing
  -> V7 Waiting/Resume，复用冻结编译结果
  -> V8 Verifier + 局部 Repair + Host Writeback
```

这条演进线表达设计因果，不等于八次生产版本。

## 3. 为什么一次 Prompt 不够

学校最早的产物需求是“把课程资料整理成一份复习提纲或演示文稿”。一次 Prompt 很快，但输入范围、输出格式、工具权限和失败位置都不透明：模型可能读错 Source、生成 Markdown 却保存为 PPTX、调用外部下载器超时后整篇重做，或者直接覆盖 Workspace 文件。

框架化不是为了让模型更聪明，而是把以下责任从 Prompt 中拿出来：

- 输入和版本由谁冻结；
- 哪个 Skill 能产生哪类 Artifact；
- 节点依赖、参数和输出 Schema；
- 工具 Capability 与审批；
- 长任务的配额、租约、恢复和取消；
- 产物 Contract、局部修复和最终写回。

## 4. Workflow、Agent 与 Skill Graph 的取舍

| 方案 | 优点 | 问题 | 当前用途 |
|---|---|---|---|
| 固定 Workflow | 可预测、可测试 | 无法处理输入差异 | 关键状态与最终 Contract |
| 自由 ReAct Agent | 灵活探索 | 循环、成本、越权和复现风险 | 不作为业务真源 |
| 通用 DAG 引擎 | 调度成熟 | 不理解模型、Evidence、Capability 和写回语义 | 规模增长后可承载底层调度 |
| Skill Graph | 业务语义显式，可编译和版本化 | 维护 Compiler 与 Schema | 当前选择 |

Skill 不是 Prompt 文件。它至少包含输入 Schema、输出类型、所需 Capability、图节点、校验规则、Repair 边界和版本。当前 Java Catalog 校验项目实际使用的 Schema 子集，不声称实现完整 JSON Schema Draft。ExecutionSpec 是一次 Run 的冻结中间表示，防止 Resume 时读取到已变化的 Skill 或 Workspace 当前状态。

## 5. 为什么没有直接采用 LangGraph 或自由 Agent 框架

LangGraph、Temporal、Step Functions 等都能解决一部分编排问题，但项目真正困难的是业务身份与副作用边界。即使采用框架，仍要实现 Workspace ACL、Source Snapshot、Capability Policy、Operation Identity、Callback Receipt、Artifact Version 和 Host Writeback。

当前单人项目先保留轻量自有状态机，便于把不变量写进 SQL 和测试。触发迁移的条件应是：DAG 数量和分支增长、跨日 Timer/Signal 增多、运维 UI 和历史回放成为主要成本，而不是“自研听起来高级”。迁移时 Skill/ExecutionSpec 可以作为领域层，底层调度替换为成熟引擎。

## 6. 一个完整演练案例

`[演练假设]` 教师选择数据库课程讲义和实验报告模板，要求生成一份 20 页可编辑复习课件，包含 6 个主题、引用和一张事务隔离对比表；若缺少课程封面图，允许调用受控网页获取能力，但必须人工审批后写回 Workspace。当前仓库没有 PPTX Renderer、逐页视觉验证或可编辑性检查，这个目标设计案例只用于解释执行框架，不能作为格式能力宣传。

### 6.1 Compile

Host 校验 Workspace、Source Scope、目标格式和用户权限，从 Skill Catalog 选择 Presentation Skill。Compiler 校验 JSON Schema，将“资料读取、结构规划、逐节生成、表格校验、导出、最终 Contract”编译为 DAG，并固化 Skill Version、Source Snapshot、Capability Set、预算和 Output Contract。

缺少必填主题数时直接返回参数错误；一次请求同时要 PPTX 和讲义 PDF 时，要么编译两个有依赖的 Artifact Job，要么要求用户明确主产物，不能用关键词随机选一个 Skill。

### 6.2 Admission 与 Dispatch

`[当前实现]` `WorkloadQuotaService` 使用 Redis Lua 原子执行 Token Bucket 和 Sorted Set Concurrency Lease。默认基线是 Workspace+Actor+Workload 速率 20/20 每分钟、Workspace+Workload 并发 4、Lease 300 秒；它们是保护参数，不是吞吐结论。

当前实现中，Job、Task 与 Outbox 在 Host 侧建立，Artifact Kafka 命令保持 70 分钟 Callback Lease，Consumer 单条拉取并让 Poll Interval 覆盖最长工具执行。它能解释现有 Delivery Token 和 Fencing，却会让小时级任务占住分区进度。面试推荐设计改为 Consumer 幂等登记 Durable Execution 后立即提交 Offset，Scheduler 再通过 Active Permit、Execution Lease 和 Fencing 管理长任务；旧 Delivery Token 只用于迁移期回调兼容。

### 6.3 Capability 与 MCP

模型提出“获取封面图”只是一项意图。实际允许能力是：

```text
Effective Capability
  = Skill Declared
  intersect Workspace Policy
  intersect User Approval
  intersect Runtime Available
  intersect Risk/Environment Policy
```

MCP 只统一工具发现与调用协议，不自动提供授权、沙箱或可信输出。Host/Worker 仍限制服务器白名单、输入 Schema、工作目录、进程超时、网络与副作用级别。当前 `mcp_sandbox_root=/app/runtime` 只是路径约束，不等于完整 OS Sandbox。

### 6.4 Waiting 与 Resume

缺少审批或外部素材时，任务进入持久 Waiting，保存等待原因、所需 Capability、Deadline 和 Resume Token。用户批准后复用原 ExecutionSpec 和已完成节点，从阻塞节点继续；不重新读取“当前最新资料”并重做规划，否则同一 Job 前后输入不一致。

### 6.5 Verifier、Repair 与写回

局部 Verifier 检查页数、主题覆盖、引用、表格结构和文件可打开性。若第 7 页表格缺列，只 Repair 对应节点；修复后先复验局部，再运行 Final Contract，防止改好一处破坏全局页数或引用。

Worker 输出 Candidate Artifact 与 Trace，不能直接覆盖 Workspace。Host 重新校验 Job、Version、Capability、用户权限、Snapshot 与 Contract，生成 Artifact Version 或 Writeback Preview；用户确认后再写回。

## 7. 外部副作用与 Unknown Outcome

最危险窗口不是“模型报错”，而是外部工具已经生成或上传文件，Worker 在 Callback 前崩溃。Kafka 会重放命令；Callback Idempotency 能阻止 Host 接受两个终态，Delivery Fencing 能拒绝旧 Worker，但如果第一次外部调用结果未知，仍可能重复副作用。

完整方案需要：

```text
operation_id = hash(job_id, node_id, attempt_generation, normalized_input)
```

Provider 支持 Idempotency Key 时传入同一 Operation ID；不支持时先查询 Operation Receipt/结果，再决定是否重试。无法确认时进入 `UNKNOWN_OUTCOME` 或人工对账，不能把超时直接当“没有执行”。

`[当前实现]` 已有 Delivery Token、Callback Receipt、Acquisition Operation Trace 和重复终态保护；所有外部工具都具备端到端副作用去重仍是边界，不能声称全局 Exactly-once。

## 8. 为什么既限速又限并发

Token Bucket 控制一段时间的到达量并允许有限突发；Concurrency Lease 控制长任务同时占用资源数。每分钟 20 次、每次一小时的任务即使速率合规，也会堆积大量在途执行，所以两者不能互相替代。

```text
tokens = min(capacity, tokens_old + refill_rate * elapsed)
admit_rate = tokens >= cost

active_leases = count(score > now)
admit_concurrency = active_leases < limit
```

Redis Lua 保证同一 Key 的读、清理过期 Lease、判断与扣减原子完成。Redis Cluster 下相关 Key 必须同 Hash Slot。生产环境配额依赖不可用时应 Fail Closed；本地 Fallback 不能用于多实例生产。

按 Workspace 限制上限也不等于公平调度。不同任务时长、成本、重试和队首阻塞仍可能让小任务饥饿；要声称公平，还需定义队列顺序、权重、最大等待时间并测 Starvation Rate 或加权 Jain Index。

## 9. 指标和公式

```text
Compile Success = 合法请求编译成功数 / 合法编译请求数

First Pass Yield = 首轮通过内容 Contract 的 Candidate 数 / 已接受首轮 Contract 判定的 Candidate 数

Repair Yield = Repair 后通过最终 Contract 的 Candidate 数 / 进入 Repair 的 Candidate 数

Collateral Change Rate = Repair 时非目标正确区域被改变数 / Repair 数

Resume Success = 从 Waiting/Crash 恢复到正确 Canonical State 且无重复副作用的 Run 数 / 注入可恢复故障的 Run 数

Capability Denial Precision = 真正越权且被拒绝数 / 所有 Capability 拒绝数

Writeback Conflict Rate = 写回时 Snapshot/Version 冲突数 / 写回尝试数

Cost per Verified Artifact = 模型+工具+存储+重试成本 / 通过 Contract 的 Artifact 数

Token Amplification = 包含 Repair 的总 Token / 首次生成 Token
```

高 Repair Trigger Rate 不等于框架强，可能是首轮质量差或 Verifier 误报；必须一起看 First Pass Yield、Verifier Precision/Recall、Repair Yield、Collateral Change 和成本。若同一 Candidate 允许多次 Repair，再另报 Attempt 级成功率，不能和 Candidate 级 Yield 共用分母。

## 10. 理想消融实验

以下为 `[理想消融数据]`。假设锁定 60 个学校产物任务，固定模型、资料、Skill 和硬件：

| Variant | Contract Pass | First Pass Yield | Crash Resume | 越权副作用 | P95 | 单位 Verified Artifact 成本 |
|---|---:|---:|---:|---:|---:|---:|
| 单次 Prompt | 61.7% | 61.7% | 0.0% | 3/60 | 96 s | 0.82 元 |
| 固定 Workflow + Schema | 76.7% | 76.7% | 0.0% | 1/60 | 118 s | 0.91 元 |
| Skill Graph + Capability | 83.3% | 83.3% | 0.0% | 0/60 | 132 s | 1.02 元 |
| + Waiting/Resume | 86.7% | 83.3% | 18/20 | 0/60 | 148 s | 1.05 元 |
| + Local Repair + Final Gate | 93.3% | 83.3% | 18/20 | 0/60 | 176 s | 1.16 元 |

正确解释：Schema/Graph 主要改善可验证通过率与权限；Waiting/Resume 主要改善故障任务恢复；Repair 将最终通过率提高 6.6 个百分点，但 P95 和成本上升。不能把所有收益归因给模型，也不能只报 93.3% 忽略更高成本。

### 10.1 Repair 对照

`[理想消融数据]` 对 30 个局部缺陷比较全量重生成和局部 Repair：

| 策略 | 修复成功 | Collateral Change | Token 中位数 | P95 |
|---|---:|---:|---:|---:|
| 全量重生成 | 26/30 | 8/30 | 18,400 | 142 s |
| 局部 Repair + Final Gate | 27/30 | 2/30 | 6,200 | 71 s |

局部 Repair 的理想价值是 Token 降低 66.3%、正确区域被改坏减少，而不是“多一轮模型必然更准”。

## 11. Verifier 怎样校准

格式、页数、文件可打开性等确定性 Contract 直接由程序验证；事实支持、版面美观等主观项需要人工或 Judge。锁定集至少标注错误类型与严重度，计算 Precision、Recall 和 F1：

```text
Verifier Precision = TP / (TP + FP)
Verifier Recall = TP / (TP + FN)
```

高风险事实错误优先 Recall，自动阻断发布还要控制 Precision。Judge 与 Generator 使用同一模型可能自偏好，需人工盲审与不同模型复核。Verifier 失败不能无限 Reflexion，必须有 Repair 次数、Token、时间和 Deadline 上限。

## 12. 业务价值

`[演练案例]` 对同一批课件/报告任务记录人工制作时长、首次验收通过、重大返工、引用错误和导出失败：

```text
Authoring Time Saved = (人工中位时长 - Agent 辅助中位时长) / 人工中位时长

Major Rework Rate = 需要重做结构或核心内容的 Artifact / Artifact 总数

Verified Output Throughput = 通过 Contract 的页数或文档数 / 人工小时
```

实际用户研究前不能写“效率提升 X%”。可以写已经建立 Artifact Version、Verifier、Repair 和 Writeback Receipt，让效果可测量。

## 13. 外部依据与差异

LangGraph 强调有状态图和持久执行；Temporal 强调 Durable Execution；MCP 定义模型与工具/资源的开放协议；OpenAI Agents、AutoGen、CrewAI 等强调 Agent、Tool、Handoff 或多 Agent 协作。它们解释了 Workflow、Agent 和 Tool Protocol 的行业方向，但不替 NoteWeave 实现 Workspace ACL、Capability Intersection、Artifact Version 和受控写回。详见[Agent 执行框架演进取舍外部依据](../research/Agent执行框架演进取舍外部依据.md)。

同类产品通常宣传任务成功率、Benchmark、工具生态、执行时长或成本。NoteWeave 应报告 Contract Pass、Resume、权限拒绝、Repair/Collateral Change 和单位 Verified Artifact 成本，不用“支持 N 个工具”代替安全与质量。

## 14. 简历表达

### 14.1 当前可写

> 设计受控 Agent 执行框架，将自然语言任务编译为版本化 Skill/ExecutionSpec/DAG；Java Host 持有 Workspace 身份、Capability 交集、Redis Lua 配额、Kafka Delivery/Callback/Fencing 和最终写回，Python Worker 只执行冻结输入，并通过 Waiting/Resume、Verifier 与局部 Repair 处理长任务故障。

> 将 MCP 作为工具协议而非授权边界，叠加 Skill 声明、Workspace Policy、用户审批、运行时白名单和路径/超时限制；产物以 Candidate/Version/Receipt 受控提交，Worker 不直接修改 Workspace。

### 14.2 实验完成后才可写

> 在 `{N}` 个锁定 Artifact Task 上，Skill Graph + Local Repair 将 Final Contract Pass 从 `{A}` 提升到 `{B}`，Crash Resume 为 `{R/M}`，越权副作用为 `0/{S}`，单位 Verified Artifact 成本 `{C}`，模型与任务集保持一致。

### 14.3 禁止写

- “实现完全自主 Agent”。
- “MCP 保证安全”。
- “分布式任务 Exactly-once”。
- “Redis Lua 让系统高可用”，Redis 本身仍可能不可用。
- “Repair 提升准确率”，不说明数据、错误类型和副作用。

## 15. Ownership 压力追问

### 15.1 AI 写了大部分代码，你的难点在哪

我负责把 Prompt 生成问题改写成可执行契约，定义 Skill/Spec/Capability/Version 的边界，选择 Host 与 Worker 权责，枚举外部副作用 Unknown Outcome，规定 Fencing 与 Callback 幂等，设计 Verifier/Repair 停止条件并验收。AI 能生成节点代码，但不能替我对越权写回或重复副作用负责。

### 15.2 这是不是过度设计

对单次生成一个 Markdown，确实过度；对跨分钟工具调用、可编辑 Artifact、外部素材、审批、重试和 Workspace 写回，必须有状态与权限。框架只应服务长任务和副作用任务，简单同步生成保留直达路径。

### 15.3 最大未解决风险

不同外部工具的幂等能力不一致，当前 Delivery/Callback 保护 Host 终态，不能天然去重所有外部副作用；另外 Skill Catalog 变多后，Compiler 路由、版本兼容和测试矩阵会膨胀。下一阶段应建立 Operation Receipt/Reconciliation，并只保留高复用 Skill。

## 16. 面试官二次审查

| 审查面 | 已闭环 | 仍缺真实证据 |
|---|---|---|
| 演进与取舍 | Prompt、Workflow、Skill、Capability、恢复、写回 | 各阶段真实用户 Bad Case |
| 完整案例 | 课件 Compile 到 Writeback | 真实 Artifact 运行 Receipt |
| 算法 | DAG、Token Bucket、Lease、Fencing、Repair | 大规模调度与 Redis Cluster 实测 |
| 安全 | Capability 交集和 Host Control | OS/容器级 Sandbox 与网络 Egress 审计 |
| 指标 | Contract、Resume、Repair、成本 | 锁定 Gold 与人工标注 |
| 外部比较 | 框架职责差异明确 | 相同任务对 LangGraph/直接 Prompt 的实测 |
| Ownership | 决策、审查、验收边界明确 | 现场解释 Unknown Outcome 和补偿 |

最能打动面试官的不是“我也做了一个 Agent 框架”，而是能说清哪些自由度交给模型、哪些状态必须由确定性 Host 持有，以及外部副作用结果未知时系统如何停止猜测并进入可对账状态。
