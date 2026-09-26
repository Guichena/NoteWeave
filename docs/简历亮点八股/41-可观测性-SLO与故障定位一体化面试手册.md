# 可观测性、SLO 与故障定位一体化面试手册

> 定位：这篇不是指标名词表，而是一次故障怎样从用户现象追到业务 Run、异步消息、Provider 和存储层。主回答准备 4 到 5 分钟；指标体系、证据链、SLO、告警和高基数分别准备 3 分钟；二阶追问可以独立回答。事实以仓库当前 Micrometer、结构化日志、持久状态和 Inspector 为准，统一 Trace 与正式生产 SLO 属于目标设计或生产待验证。

## 1. 先给面试官的 60 秒回答

NoteWeave 的难点不是接口报错，而是一次 Research 或 Artifact 会跨浏览器、Java 服务、MySQL Task/Outbox、Kafka、Python Worker、外部 Provider 和对象存储。用户只说“卡住了”，原因可能是入口排队、Outbox 未投递、Kafka 消费落后、Worker 丢 Lease、Provider 429、结果回调未知，或者前端漏了终态事件。

我的做法是先用持久业务状态回答“哪个 Run 卡在哪个阶段”，再用指标判断故障范围和时间窗口，最后用带关联 ID 的日志下钻。当前日志已有 `request_id`、`correlation_id`、`workspace_id`、`task_id`、`source_id`、`answer_run_id` 和 `event_id`；Micrometer 已覆盖 Outbox 数量与最老消息年龄、Source/Projection 状态、Kafka Retry/DLT、Quota、Research Completion、Artifact Outbox 和实时桥接等指标；Context Inspector 和 Run Replay 能查看冻结输入与 Memory。生产化还需要 OpenTelemetry 把 Java、Kafka、Python 和 Provider 调用串成统一 Trace，并按同步 QA、异步 Research/Artifact、投影新鲜度分别定义 SLI 和错误预算。

## 2. 为什么不能只看 HTTP 500

```text
浏览器请求
  -> AnswerRun / Task 已创建
  -> Outbox 等待投递
  -> Kafka 命令
  -> Worker Claim + Lease
  -> Search / LLM / Storage Provider
  -> Completion / Callback
  -> MySQL 终态 + Event
  -> SSE 回放或 Snapshot 校正
```

HTTP 202 只证明系统接纳了请求，不证明任务已经执行；SSE 断开也不证明后端失败；Kafka Lag 为零不代表业务成功，因为消息可能已消费但 Worker 正在等待 Provider；CPU 不高也不代表系统有余量，因为连接池、Provider Permit 或线程池队列可能已经饱和。

因此我把观测问题拆成四层：

| 层次 | 回答的问题 | NoteWeave 的主要证据 |
| --- | --- | --- |
| 业务状态 | 哪个用户产出受影响，卡在哪个阶段 | AnswerRun、Task、Research Run/Task、Artifact Job、Source/Projection 状态 |
| 可靠投递 | 命令和结果有没有跨边界收敛 | Outbox 状态、Oldest Ready Age、Kafka Retry/DLT、Callback Receipt |
| 资源与依赖 | 为什么推进不了 | Queue Age、Worker/Permit、Provider 延迟与 429、Hikari Pending、ES/Redis 健康 |
| 用户呈现 | 后端完成后用户是否看见 | Event ID、Run Sequence、Last-Event-ID、终态 Snapshot、前端 Request Gate |

## 3. Logs、Metrics、Trace 和业务事件的分工

### Metrics 用于发现范围

指标适合回答“从什么时候开始、影响多少、是否仍在扩大”。例如 Outbox Oldest Ready Age 持续增长，说明最老可投递消息越来越旧；Research Completion P95 上升但 Queue Age 正常，更像执行或提交路径变慢；只有单个 Workspace 异常而全局正常，应先查数据、权限、配额和热点，不先扩全部实例。

### Logs 用于解释一次决策

日志应记录状态迁移、拒绝原因、重试域和稳定标识，不记录完整 Prompt、Token、Cookie 或资料正文。它适合回答“为什么这个 Task 进入 WAITING”“哪个 Fencing Token 被拒绝”“这次回调是否命中已有 Receipt”。日志不是状态真源，日志丢失不能改变 Task 的最终状态。

### Trace 用于分解跨服务耗时

Trace 适合拆一条请求在 Java、Kafka、Python Worker、Provider 和存储上的时间。同步 QA 可以有一条父 Trace；异步任务需要把 Trace Context 写入消息 Header，由 Consumer 创建新 Span Link，不能强求一个持续几分钟且永不结束的同步 Span。

`[目标设计]` 统一使用 W3C Trace Context，低延迟同步路径父子传播，异步 Fan-out 使用 Span Link；错误和高延迟样本尾部采样，普通成功请求低比例采样。

### 业务事件用于恢复与审计

Task Event、Answer Event 和 Outbox 是业务协议的一部分，可以回放和对账；Trace/Log 是观测数据。不能因为 Trace 显示完成就补写业务终态，也不能用日志搜索代替幂等 Receipt。

## 4. 关联 ID 怎样设计

| 标识 | 粒度 | 用途 | 是否适合作为指标标签 |
| --- | --- | --- | --- |
| `request_id` | 一次 HTTP 请求 | 查入口和响应 | 否，高基数 |
| `correlation_id` | 一条跨组件业务链 | 串 Java、消息和 Worker | 否，高基数 |
| `workspace_id` | 租户/课程空间 | 权限、配额、故障范围 | 通常不直接进全局时序指标 |
| `answer_run_id` | 一次回答运行 | SSE、Snapshot、结果收敛 | 否 |
| `task_id` | 用户可见长任务 | 阶段和终态 | 否 |
| Domain Task ID | Research WorkItem/Artifact Execution | Claim、Lease、Fencing | 否 |
| Outbox ID | 一次可靠投递 | Dispatch、Retry、DLT | 否 |
| Delivery Token | 一次投递身份 | 幂等和回调 Receipt | 否，且日志要脱敏 |
| `event_id` | 持久事件游标 | SSE 续播和审计 | 否 |
| `source_event_id` | 流投影对应的领域事实事件 | 去重、投影对账和跨视图定位 | 否 |

高基数 ID 放日志和 Trace，指标只放有界标签，例如 `workload=research`、`stage=provider`、`result=timeout`、`provider=openai`、`reason=quota`。Workspace 级诊断使用日志、数据库查询或 Top-N 临时视图，不能把所有 Workspace ID 永久写进 Prometheus 标签，否则时间序列数量会随租户增长。

## 5. 三类 SLI 要分开定义

### 同步 QA

```text
Availability SLI = 有效且在时限内返回的 QA 请求 / 合法 QA 请求
Latency SLI = 在目标延迟内完成首个可用回答的请求 / 成功请求
Grounded SLI = 引用支持通过的回答 / 可回答样本
```

超时后返回模板不应自动算成功。若用户要求资料内回答，但系统降级到无证据生成，HTTP 是 200，业务 SLI 仍应失败或单独记为 Degraded。

### 异步 Research 和 Artifact

异步任务不能用接口响应时间衡量。至少分：

```text
Admission Latency = Task 创建成功时间 - 提交时间
Queue Wait = 首次 RUNNING 时间 - Task 创建时间
Active Service Time = 终态时间 - RUNNING 时间 - 可识别 WAITING 时长
Completion SLI = 在截止时间内产生有效终态产物的任务 / 被接纳任务
Recovery SLI = 故障注入后在窗口内自动收敛的任务 / 被注入任务
```

取消任务不能只靠一个排除规则处理。严格接纳完成率以固定已接纳 Cohort 为分母，用户主动取消、系统超时取消和超期未终结都作为不同的非成功结果保留；只衡量执行器可靠性的 Terminal Execution Success Rate 可以排除带明确用户操作审计的主动取消，但必须同时报告取消率和严格接纳完成率。系统超时、自动取消和无法确认来源的取消不得排除，否则会美化数据。

### Source 与检索投影

```text
Freshness Lag = Projection 可查询时间 - Source Snapshot 提交时间
Projection Coverage = 当前 Catalog Version 已投影 Chunk / 应投影 Chunk
Stale Read Rate = 使用旧 Catalog Version 的检索次数 / 检索次数
```

Source 已 READY 而 Projection 仍 PENDING 时，上传成功 SLI 可以通过，检索新鲜度 SLI 不能通过。两个 SLI 不能揉成一个“系统成功率”。

## 6. SLO、错误预算与告警

`[目标设计]` 示例目标用于演练，不代表当前生产承诺：

| 用户旅程 | SLO 示例 | 主要护栏 |
| --- | --- | --- |
| QA | 99.9% 合法请求可用，P95 首个可用结果小于目标值 | Grounded Rate、Degraded Rate |
| Research | 99% 被接纳 Run 在课程约定时限内得到有效终态 | Queue Age、Verified Claim、单位成功成本 |
| Artifact | 99% Job 在时限内生成可下载且校验通过的 Version | Repair Rate、Callback Unknown |
| Projection | 99% Snapshot 在新鲜度窗口内进入当前 Catalog | Coverage、Failed Chunk、Stale Read |

错误预算计算：

```text
Error Budget = 1 - SLO
Budget Consumed = 失败事件数 / 允许失败事件数
Burn Rate = 当前窗口错误率 / 允许错误率
```

99.9% 月可用性允许约 43.2 分钟不可用，但面试中更推荐按事件量计算，并同时看 5 分钟和 1 小时窗口。短窗口高 Burn Rate 负责快速发现严重事故，长窗口负责过滤瞬时抖动。只按单阈值报警容易在流量低时误报，或在故障持续时反复轰炸。

告警按用户影响分级：

| 级别 | 条件 | 动作 |
| --- | --- | --- |
| P0 | 跨 Workspace 核心旅程不可用、数据越权或不可逆损坏风险 | 立即止损、关闭写入或高风险能力、负责人介入 |
| P1 | 关键 SLO 快速燃烧、长任务大面积不收敛 | 降级 Provider、限制接纳、扩容或回滚 |
| P2 | 单一投影、单一 Provider 或部分租户退化 | 工单处理、限定时间修复 |
| Ticket | 容量趋势、单个坏数据、无用户影响的异常 | 排期治理 |

## 7. 一次完整事件：考试周 Research 大面积变慢

这是 `[演练假设]`，用于展示排障方法，不是声称发生过线上事故。

### 现象

考试周多个课程 Workspace 反馈 Research 长时间停在运行中。API 创建任务正常，前端也持续收到心跳，但有效进度变慢。

### 第一轮判断故障范围

先看 Research Completion SLI、Queue Age、WAITING Ratio 和 Admission Reject Rate。WAITING Ratio 只统计已经接入持久等待合同的能力，不能把所有 Provider 429 直接算成 WAITING。若 Queue Age 上升且 Active Service Time 基本稳定，说明系统接纳速度超过执行速度；若 Queue Age 正常但 Provider P95、429 和已接入 Waiting 的存量上升，瓶颈在外部调用；若 Completion 已完成而前端仍显示运行中，转查 Event/Snapshot 收敛。

### 第二轮定位控制点

假设 Outbox Oldest Ready Age 正常、Kafka Lag 正常、Worker Claim 正常，但 Provider Permit Wait 和 429 同时上升。支持“Provider 容量不足”的证据是多个 Workspace 同时受影响、错误集中在同一 Provider、Java Hikari Pending 和 MySQL 锁等待正常。反证是其他 Provider 同样变慢，或 Worker CPU 饱和，这时不能过早归因第三方。

### 止损

不直接加 Worker，因为更多并发只会增加 429 和重试成本。先降低该 Provider 的 Active Permit，关闭非必要 Wide Discovery，把新增 Research 放入有界队列；只有已经接入持久等待合同的能力才置为 Waiting。QA 与 Artifact 使用独立资源池，防止长任务拖垮交互路径。对已知可恢复失败执行带抖动退避，结果未知的调用先查 Receipt，不立即重放。

### 恢复判定

Provider 429 下降不等于恢复。至少同时满足：新请求成功率恢复、Oldest Queue Age 持续下降、完成吞吐大于到达速率、已接入持久等待的存量收敛、没有 DLT 或 Stale Completion 增长。恢复 Permit 时逐级放量，观察一个完整长任务窗口，而不是一次健康检查成功就全开。

### 复盘行动

补齐 Provider 维度的延迟、429、Permit Wait 和单位成功成本；为 Research 定义独立错误预算；把 Runbook 绑定到告警；做固定 429 故障注入，验证降并发、有限重试、持久 Waiting（仅对已有合同的能力）、恢复和 Fencing；明确哪些功能可降级、哪些必须 Fail Closed。

## 8. 故障证据链：不要把相关性当根因

排障时严格区分五个词：

1. **症状**：用户看到 Research 卡住。
2. **候选原因**：Provider 限流、Kafka Lag、连接池耗尽、Lease 丢失等假设。
3. **支持证据**：Provider 429 与 Permit Wait 同时上升，多个 Workspace 同时受影响。
4. **反证**：Kafka Lag、Hikari Pending 和 Worker CPU 正常，排除另外几条路径。
5. **根因**：通过时间对齐、故障复现或配置变更验证的最小充分解释。

CPU 上升和延迟上升同时发生，只能说明相关。可能是 JSON 序列化放大 CPU，也可能是 Provider 变慢导致对象堆积和 GC。需要线程栈、GC、火焰图、队列和依赖延迟把因果顺序补齐。

## 9. 重点知识点一：可观测性不是“多打日志”

### 3 分钟回答

可观测性的目标是从外部输出推断系统内部状态。日志、指标和 Trace 只是手段，真正困难的是业务语义和关联关系。NoteWeave 中同一个用户请求可能已经返回 202，Task 也创建成功，但 Outbox 尚未投递；或 Worker 已完成，SSE 终态丢失；或 Provider 响应超时，但对方实际已接单。若只统计 HTTP 状态码，三种情况都会被错误归类。

所以我先定义状态真源和不变量。MySQL 的 Task、Run、Outbox、Receipt 和 Event 回答业务是否接纳、是否执行、是否提交、是否可重放；Metrics 对这些状态做聚合，发现影响范围；日志记录某次状态迁移的原因；Trace 拆分跨组件耗时。观测系统不能反向覆盖业务真源，日志存在也不能证明事务提交。

第二是控制标签基数。Run ID、Task ID 和 Workspace ID 是定位问题的关键，但不能作为所有时序指标标签。它们进入结构化日志和 Trace；Metrics 只使用有限枚举。需要租户定位时，从告警时间窗口跳到日志或数据库状态，再以 Workspace 过滤。

第三是让观测直接服务决策。Queue Age 告警对应限流和扩 Worker；Provider 429 对应缩 Permit 和降级；Hikari Pending 对应查慢 SQL、事务和连接持有时间；Alias 结果未知对应 Reconciler，而不是人工猜测。没有 Owner、Runbook 和恢复条件的 Dashboard 只是展示页。

### 二阶追问：为什么业务事件不能全部替代 Trace

业务事件只记录对领域有意义且需要持久化的事实，若把每次 SQL、网络调用和重试都写进事件表，会扩大事务、存储和耦合。Trace 可以采样、过期并记录技术 Span；业务事件必须完整、可重放且受契约约束。二者通过稳定 ID 关联，但生命周期不同。

### 二阶追问：Trace 采样后刚好漏掉事故怎么办

指标和业务状态不采样，用于可靠发现；Trace 对错误、超时、高延迟和稀有状态做 Tail Sampling，提高异常保留率。仍然漏掉时可以依赖持久状态与结构化日志重建。Trace 是加速定位的证据，不应是唯一审计账本。

## 10. 重点知识点二：SLO 为什么按用户旅程定义

### 3 分钟回答

组件健康不等于用户成功。Kafka 99.99% 可用、MySQL 99.99% 可用，但 Research 仍可能因为 Provider 配额不足而无法完成；反过来，ES 短暂不可用时，若 QA 有受控降级且仍返回有依据的结果，用户旅程未必失败。因此 SLI 必须从用户动作到有效产物定义，再用组件指标解释它。

同步 QA 的有效成功需要在时限内返回，并满足证据和权限约束；异步 Research 的有效成功需要在约定时限内进入有效终态，不能把 202 当成功；Artifact 还要保证 Version 可下载且 Verifier 通过；投影则看新鲜度和覆盖率。每条主 SLI 再配护栏，避免通过大量拒答、模板降级或取消任务美化可用性。

SLO 是可靠性和研发速度之间的约束，不是越高越好。若业务只需要考试资料在十分钟内生成，却盲目承诺五个九，会大幅增加双活、冗余和演练成本。先根据用户损失定义目标，再计算错误预算。预算燃烧快时冻结高风险发布、缩小灰度或优先修复可靠性；预算充足时可以接受小范围实验。

### 二阶追问：SLA、SLO、SLI 有什么区别

SLI 是测量值，例如“十分钟内完成的 Research 比例”；SLO 是内部目标，例如 99%；SLA 是与客户约定并可能带赔偿的合同。项目面试中没有真实合同就不要说 SLA，演练值标成目标设计。

### 二阶追问：低流量服务怎样告警

低流量下五分钟一个失败会让错误率剧烈波动。使用更长窗口、最小事件数、合成探测和状态型指标组合；安全越权、数据损坏等事件不依赖比例，单次即告警。

## 11. 重点知识点三：MTTD、MTTR、RTO 和 RPO

### 3 分钟回答

MTTD 是故障发生到被发现的平均时间，MTTR 在团队内必须明确是恢复还是修复。NoteWeave 更关心 Mean Time To Restore：用户旅程恢复到目标范围的时间，永久根治可以晚于恢复。检测时间来自第一条用户影响到告警时间，恢复时间来自告警到 SLI、积压和错误预算重新稳定。

RTO 是灾难后允许多久恢复服务，RPO 是允许丢失多少时间的数据。MySQL 是业务真源，ES 和缓存可重建，因此投影的 RPO 可以接近真源提交点，但重建需要时间；若 MySQL 本身没有备份与恢复演练，就不能宣称 RPO 为零。SSE 连接可以丢，因为事件能从持久游标回放；进程内未持久化状态则无法这样承诺。

改进 MTTR 的关键不是多做 Dashboard，而是缩短证据路径和控制路径：统一关联 ID、按阶段暴露状态、告警链接 Runbook、预先定义降级开关、对结果未知做 Reconciler、对积压有安全的 Redrive。每次事故复盘要区分检测慢、定位慢、决策慢、执行慢和验证慢，分别优化。

### 二阶追问：平均 MTTR 会不会骗人

会。少量超长事故会被平均值掩盖，应同时报中位数、P90、按严重度分组和未恢复事件。只统计已关闭事故还有幸存者偏差。

## 12. 常见二阶追问

### Outbox Oldest Ready Age 上升，但消息数量不高，严重吗

严重程度取决于最老消息是否阻塞关键用户旅程。数量少可能是毒消息不断失败，也可能是低流量系统完全没有消费。Count 反映规模，Age 反映停滞时间，两者必须组合。再查 Dispatch 吞吐、Retry/DLT 和消息所属 Workload。

### Kafka Lag 为零，为什么任务仍卡住

短投影消息可能已经消费，但后续 Finalize 或对账仍未完成。Artifact 推荐链路会先登记 Durable Execution 再 ACK，之后即使 Worker 排队或等待 Provider，Kafka Lag 也可以为零。此时要看 Durable Execution Queue Age、Active Permit、Execution Lease、Provider Wait 和 Host Commit。Completion 已提交但前端仍卡住时，再查带 `source_event_id` 的 Conversation Stream Projection 和 Snapshot。Kafka Lag 只覆盖传输接纳，不覆盖端到端任务状态。

### Hikari Pending 上升怎么判断是连接太少还是 SQL 太慢

先看 Active、Idle、Pending 和连接持有时长，再查慢 SQL、锁等待、事务范围和下游调用是否包在事务内。直接增大连接池可能把压力转移到 MySQL，增加上下文切换和锁竞争。只有数据库仍有容量、SQL 已合理且排队由连接数造成时才扩池。

### 为什么不能把用户问题和 Prompt 全打日志

资料可能包含个人信息、课程内部内容和密钥，完整 Prompt 还会放大日志成本。记录模板版本、输入摘要、Token 数、来源引用 ID 和哈希；授权的 Inspector 按 Workspace 权限读取冻结 Snapshot，敏感字段脱敏并保留审计。

### 告警恢复后为什么不能立即宣告事故结束

瞬时错误率恢复时，旧积压可能仍在增加用户等待，DLT 可能尚未 Redrive，旧 Worker 也可能晚到提交。要同时验证到达率与完成吞吐、最老队列年龄、终态收敛、错误预算和数据一致性。

### Inspector 和 Observability Dashboard 有什么区别

Inspector 面向单个 Run 或 Workspace，回答“当时用了哪个输入快照、哪些 Memory Revision、能否 Replay”；Dashboard 面向聚合运行态，回答“哪类请求整体异常”。当前 `RuntimeInspectorController` 已有 Context、Run Replay 和 Memory 入口，但还没有把 Retrieval Release、Task、Research、Artifact 和 Trace 汇总成统一控制台。

## 13. 当前实现、目标设计与可宣传边界

### `[当前实现]` 可以说

- `application.yml` 的控制台日志格式带请求、关联、Workspace、Task、Source、AnswerRun 和 Event 标识。
- `OperationalMetricsBinder` 从 MySQL 暴露 Outbox 各状态、最老 READY/PROCESSING 年龄、Source 状态和 Projection Chunk 状态 Gauge；查询失败返回 `NaN`，避免伪装成零。
- Kafka Consumer 暴露 Retry、DLT 和 DLT Publish Failure Counter，并使用 Record ACK、有限指数退避加 Jitter。
- Research Completion 有 Latency、Payload Bytes、Cells、Commit、Replay、Conflict、Rollback 等指标；Quota、Artifact Outbox、Realtime Bridge 也有 Micrometer 埋点。
- Context Inspector、Run Replay 和 Memory Inspector 能读取冻结输入与受控 Memory，并执行 Workspace 权限校验。
- Task/Event、Outbox、Receipt、Run Snapshot 等持久对象可以作为故障对账依据。

### `[目标设计]` 可以作为改造方案

- OpenTelemetry 统一 Java、Kafka、Python Worker 和 Provider Trace。
- 每条用户旅程建立 SLI、SLO、Burn Rate 告警、Owner 和 Runbook。
- Provider、Hikari、线程池、Kafka Lag、Durable Execution Queue Age、Admission Reservation、Active Permit、Lease Lost 和 Degraded Run 汇总到同一运行视图。
- 检索发布增加 Generation、Catalog Watermark、Change Log Catch-up Lag 和 Release Gate Receipt；SSE 增加 Domain Event 到 Stream Projection 的 Lag、重复和缺口指标。
- 对高延迟和错误 Trace 做 Tail Sampling，对日志做敏感数据分级和留存治理。

### `[生产待验证]` 不能包装成事实

- 生产环境达到 99.9% 或更高可用性。
- 已经部署 Prometheus/Grafana/OpenTelemetry 全链路。
- 真实考试周 MTTD、MTTR、告警准确率和跨可用区恢复数据。
- 仅凭默认配置推导出的并发、QPS 或容量。

## 14. 八股映射

| 面试方向 | 项目落点 | 回答重点 |
| --- | --- | --- |
| Spring Boot Actuator/Micrometer | `OperationalMetricsBinder` 与各领域 Meter | Counter、Gauge、Timer、Tag 基数、采集失败语义 |
| 分布式链路追踪 | HTTP、Kafka、Python、Provider | Trace Context、Span Link、异步边界、采样 |
| MySQL | 状态表与 Gauge 查询 | 真源、慢 SQL、锁等待、连接池、监控查询开销 |
| Kafka | Retry/DLT、Lag、Outbox | ACK 时机、至少一次、毒消息、端到端延迟 |
| Redis | Quota 与 Realtime Bridge | 延迟、热点 Key、故障降级、不能作为业务真源 |
| JVM | CPU、GC、线程池、Hikari | 指标联动、线程 Dump、堆分析、饱和度 |
| SRE | SLI/SLO、错误预算、Burn Rate | 用户旅程、护栏、告警、恢复判定 |
| 安全 | 日志与 Inspector | 最小披露、脱敏、权限、审计与留存 |

## 15. 源码与文档导航

- 当前指标入口：`backend/src/main/java/com/noteweave/infra/OperationalMetricsBinder.java`
- Kafka Retry/DLT：`backend/src/main/java/com/noteweave/infra/KafkaConfig.java`
- Research Completion：`backend/src/main/java/com/noteweave/research/ResearchAgentCompletionMetrics.java`
- Runtime Inspector：`backend/src/main/java/com/noteweave/inspector/RuntimeInspectorController.java`
- 当前日志和连接池：`backend/src/main/resources/application.yml`
- 指标公式字典：[全项目指标体系](07-全项目指标体系与面试计算手册.md)
- 真实缺陷与排障题：[缺陷排查与复盘](09-真实缺陷排查与复盘案例.md)
- 参数事实边界：[真实配置与容量口径](15-项目真实配置参数与容量口径.md)
- Task 与 Quota：[运行时治理](38-Task调度-Quota与运行时治理一体化面试手册.md)
- 投影与 Inspector：[检索投影治理](40-检索投影治理-ReleaseGate与Inspector一体化面试手册.md)

## 16. 4 到 5 分钟标准主回答

NoteWeave 最初服务学校资料场景时，观测主要是看接口是否报错和任务有没有完成。后来加入异步解析、Research、Artifact、SSE 和多个外部 Provider，一次用户动作会跨浏览器、Java 服务、MySQL Outbox、Kafka、Python Worker、搜索或模型服务，再回到持久事件。这个时候用户说“卡住了”，HTTP 500 已经不能解释问题，因为请求可能早就返回 202，真正的故障发生在排队、投递、执行、回调或展示任一阶段。

我先把业务状态当第一层观测，而不是从日志猜状态。同步回答有 AnswerRun，长任务有通用 Task 和领域 Task，可靠投递有 Outbox，结果未知有 Receipt，前端恢复有持久 Event 与 Snapshot。这样我能先回答哪个 Workspace、哪个 Run、在哪个 Phase 停住，以及它是 WAITING、FAILED 还是只是浏览器漏事件。日志和 Trace 都不能反向修改这些业务事实。

第二层是聚合指标。当前仓库已经使用 Micrometer：`OperationalMetricsBinder` 暴露 Outbox 各状态和最老待投递年龄、Source 状态和 Projection 状态；Kafka 有 Retry、DLT 和 DLT 发布失败；Research Completion 有延迟、Payload、Cell、Commit、Replay、Conflict 和 Rollback；Artifact Outbox、Quota 与实时桥接也有各自指标，这些 Cell 指标是当前 Matrix 实现的事实。推荐方案再按 Typed WorkItem 分桶，并增加 Durable Execution Queue Age、Admission Reservation、Active Permit、Generation Catch-up 和 Stream Projection Lag。结构化日志包含 request、correlation、workspace、task、source、answer run 和 event 标识。高基数 ID 用来查日志和单 Run，Metrics 只保留 Workload、Stage、Result、Provider 等有限标签。

第三层是按用户旅程定义 SLI。QA 看在时限内返回且有依据的比例，不能把无证据模板 200 当成功；Research 和 Artifact 看被接纳后是否在约定时间产生有效终态产物，202 只算 Admission；Source 投影单独看 Freshness Lag、Coverage 和 Stale Read。每个主 SLI 都有护栏，例如 Degraded Rate、Verified Claim、Artifact Verifier 和单位成功成本，避免通过拒答或取消任务美化数据。

如果考试周 Research 变慢，我先看 Queue Age、WAITING Ratio、Completion、Outbox Age 和 Kafka Lag，确定是入口积压、传输积压还是执行变慢。假设 Outbox 和 Kafka 正常，而 Provider P95、429 与 Permit Wait 同时上升，多个 Workspace 受影响，Hikari 和 Worker CPU 正常，这组证据才支持 Provider 容量不足。止损不是盲目加 Worker，而是缩小该 Provider Permit、关闭非必要探索、把新增任务置于有界队列；只有具备持久等待合同的路径才进入 Waiting，并隔离 QA 和 Artifact 资源。恢复时不仅看 429 下降，还要确认完成吞吐大于到达率、最老 Queue Age 下降、已接入 Waiting 和 DLT 收敛，再逐级放量。

生产化方向是用 OpenTelemetry 贯通 Java、Kafka、Python 和 Provider：同步路径传播父子 Span，异步 Fan-out 用 Span Link；错误和高延迟样本做 Tail Sampling。再给每条用户旅程配 SLO、错误预算、Burn Rate 多窗口告警、Owner 和 Runbook。当前我能证明的是 Micrometer、结构化日志、持久状态与 Inspector；统一 Trace、正式生产 SLO、真实 MTTD/MTTR 和跨可用区恢复仍需部署与持续流量验证。
