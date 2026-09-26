# 04 Artifact / Skill / MCP：功能设计与取舍

对应简历第 4 条。4～5 分钟口述见 [总册第 5 章](./00-简历总册.md)。第 1 节是缩小版系统设计。

**追问先答：** Catalog 收口能做什么；Graph 编译期固化；Host 预分配 Version ID 并提交；超时是未知结果。Job 不当成功率分母。

权威：[Artifact Skill 执行架构](../Artifact-Skill执行架构.md)。更长手册：[32](../简历亮点八股/32-Agent执行与Artifact产物一体化面试手册.md)。

---

## 0. notes 蒸馏（怎么讲）

来自 `06`/`07` Agent 价值、`18` Plan 与工具边界：

- Agent 价值在意图边界、工具可靠、失败处理和评估，不在配 workflow。不要把 Dify / LangGraph / MCP 平台当掌握了 Agent。
- 这些平台降搭建成本，不自动给对象授权、配额和提交权。那些仍要工程设计。
- 格式稳定的产物（讲义、测验）外层可以偏编译好的 Graph；不要运行时让模型随便改边。
- 工具在测试阶段可以观察 Plan 需要什么；线上强制白名单。工具描述里的「忽略审批」是供应链输入，不是策略。
- 超时是未知结果：先查回执再决定重试，不能当失败重放。
- 评估不要用 Job 当成功率分母。Job 是长期意图；一次生成是 Run/Attempt。

人设收口：Skill Catalog + Schema 的 Skill Graph；Java Host 独占版本提交；Worker 只回 Candidate。

---

## 1. 缩小版系统设计

**约束。** 讲义/测验结构不能漂；工具不能越权；失败不能整篇重来；超时不能当失败重放；用户只能看到文件齐了的版本。

### 1.1 架构

```text
显式 skill_key + 输入
  → Java Host
       Catalog/Schema 准入（未知或缺必填 Fail Closed）
       冻结 Input Snapshot
       预分配 artifact_version_id（内部 RESERVED）
       编译 Skill Graph（获取/生成/校验/修复/等待）
       同一事务：Job / Run / Task / Outbox
  → Kafka：只按 Command ID 幂等登记 Durable Execution，立刻交 Offset
  → Scheduler：令牌桶 + 在途租约 + Execution Lease
  → Python Worker：按固化图执行，只回 Candidate（引用预分配 Version ID）
  → Host 回调：身份 / 幂等键 / Lease / Fencing / Digest
  → Verifier → 内容合同通过 → DELIVERY_PENDING（用户不可见）
  → 文件 Contract 齐 → READY（可下载/写回）
```

Kafka 在这条链路只负责**接纳**。小时级 Owner/Heartbeat/Timeout 在 Execution Lease，不靠超长 `max.poll.interval`。

### 1.2 端到端怎么跑

1. **准入。** 稳定 `skill_key` 命中 Catalog：IO Schema、风险、允许能力。模型猜测不能覆盖显式 key。缺 URL/语言/写回目标 → 澄清或拒绝。
2. **冻结与占坑。** 输入快照冻结 Source Scope、Skill 版本、Control Pack。Host 预分配 Version ID，Worker 的候选和文件都引用它，避免回调乱序出两套版本。此时用户看不见。
3. **编译图。** 获取、生成、校验、修复、等待收成有向图：节点、依赖、超时、预算。环和未知节点编译期拒绝。进行中的任务不原地换图。
4. **接纳。** 事务落 Job/Run/Outbox。Consumer 登记 Durable Execution 后交 Offset。DB 提交、Offset 未交而退出：重放同一 Command ID 返回已有 Execution。
5. **调度。** 入口令牌桶 Workspace+Actor+Workload，约 20/分钟；在途租约约并发 4、Lease 300s。Redis Lua 原子更新；不可用生产 Fail Closed。
6. **执行。** Worker 按拓扑跑节点。节点后 Verifier；只修失败节点，有次数上限。缺资料且已有 Wait Receipt 才进 Waiting 并放租约。
7. **回调。** Host 核内部身份、幂等键、Lease、Fencing、Payload Digest。超时是未知结果：先查 Receipt 再决定是否重试。
8. **交付。** 内容合同过 → 不可见 `DELIVERY_PENDING` + 必需文件 PENDING Manifest。Staging 导出、文件合同过，全部 READY 后 Version 才用户可见。写回另走带 Expected Version 的 Proposal。

### 1.3 状态存在哪、谁写谁读

| 数据 | 在哪 | 谁写 | 不变量 |
| --- | --- | --- | --- |
| Job / Run / Attempt / Version | MySQL | 仅 Host | Job ≠ 一次生成；只有 READY 可下载 |
| DurableExecution | MySQL | Consumer 幂等登记 | Offset 已交仍能领到 |
| 文件字节 | MinIO Staging → 正式 Key | Host 在文件合同后晋升 | Markdown 通过 ≠ PDF READY |
| CallbackReceipt | MySQL | Host | 重复回调同一语义 |
| 配额 | Redis | Lua | 不当业务真源 |

### 1.4 三个机制具体怎么落地

**三阶段成功。** 生成成功=有合法 Candidate；回调成功=Host 收到绑定 Digest 的 Receipt；版本提交成功=校验后落 Version/文件。Kafka 有命令、Callback 200，都还不是用户可下载。

**预分配 ID。** RESERVED → DELIVERY_PENDING → READY。首轮内容前取消不创建空 Version。已 READY 不能倒退成取消。ID 不回收。

**未知结果。** 网络超时不能证明 Worker 没做完。先查回执；旧栅栏写回拒绝。未接入等待合同的 Provider 429 只做有限重试，不能包装成自动 Resume。

`[当前实现]` `artifact_job_run` 仍混请求与尝试；PDF/写回可能早于最终 Verifier——面试主动讲。

### 1.5 中间件落到哪种结构（可考到这一层，不到函数名）

| 中间件 | 用它的哪一点 | 落到什么结构 | 为什么是这个 | 不用它当什么 |
| --- | --- | --- | --- | --- |
| Redis **Hash** | 字段级读写、Lua 原子 | 令牌桶：`tokens`、`last_refill`。Key ≈ Workspace+Actor+Workload。容量/补充约 20/分钟 | 要「按时间补令牌再判断再扣减」。多 Host 先 GET 再 SET 会同时放行。Lua 在 Redis 里连续执行，中间不插别的命令 | Lua 不是分布式事务；脚本太长会堵单线程 |
| Redis **Sorted Set** | 按 score 排序、按范围删 | 在途租约：member=任务/Token，score=过期时间。同一 Workload 上限约 4，Lease 300s | 领取时 ZADD；续租改 score；回收用 ZREMRANGEBYSCORE 清过期。比扫 Hash 全部 field 合适 | 不是分布式锁；Release 必须对上自己的 Token，防误放别人的槽 |
| Kafka | 命令保留、消费组 | 产物命令 Topic。Consumer **登记 Durable Execution 后交 Offset** | 小时级任务不能占着分区 Poll。登记事务提交、Offset 未交而退出：重放同一 Command ID 返回已有记录 | Offset 已交 ≠ 文件 READY；ACK ≠ 用户可下载 |
| MySQL | 事务、唯一约束、条件更新 | Job/Run/预分配 Version ID/CallbackReceipt；写回 `WHERE fencing=?` | 版本身份和回执必须可查 | 不当对象存储 |
| MinIO | 大对象、可换 Key | 先写 Staging Key，文件合同过再晋升正式对象 | 半成品不进用户下载路径 | 本地磁盘文件库当版本（多实例对不上） |

三套 Redis 不要混：入口 Hash+ZSet（20/min、并发 4）、Research 工具桶（10/10）、SSE Stream（条 5）。生产 Redis 不可用：昂贵任务 **Fail Closed**。Connect 500ms / Command 750ms，快发现挂掉，避免配额检查把线程拖死。

---

## 2. Skill Catalog

**失败窗口。** 每种产物写一套 Action+Prompt，扩展要改主流程。模型自选任意工具会越权。缺 URL、语言、写回目标时硬跑，副作用不可逆。

**设计。** 报告、测验、学习指南注册为 Skill：稳定 `skill_key`、输入输出 Schema、风险、修复策略、允许的能力。未知 Skill 或缺必填 Fail Closed。显式 `skill_key` 优先于模型猜测。

**notes 方法。** 意图边界先于规划。这里用 Catalog 收口「能做什么」，而不是让模型从开放工具列表里猜。线上工具强制白名单，和 notes「测试观察 / 线上限制」一致。

**和其他做法比。** 万能 Prompt；用户任意注册生产 Action；用自然语言置信度覆盖 Schema 失败。

**失败怎么收。** 缺必填要澄清或拒绝，不带着空参数执行。Skill 数量「11」不当质量指标。

---

## 3. Skill Graph + Schema

**失败窗口。** 获取、生成、校验、修复、等待挤在一次调用里，失败只能全量重做。运行时任意改边，权限和预算无法编译期收口。

**设计。** 编译期把 Skill 收成图：节点、依赖、超时、预算、等待点。按固化顺序执行，节点后验证与修复。Schema 约束输入输出，结构漂移进 Repair 或失败，不直接当版本。图变了要新版本，不原地替换进行中的任务。

**notes 方法。** 外层 Plan（这里是编译好的 Graph）管阶段和检查点；内层节点才允许有限模型决策。不要把「自主多 Agent」当当前宣称。

**和其他做法比。**

| 方案 | 特点 | 何时更贴另一类 |
| --- | --- | --- |
| 一次 Prompt 出文件 | 章节漂移、失败整篇重跑 | 不换 |
| 运行时任意改边的多 Agent | 权限和预算无法收口 | 当前不宣称通用动态调度 |
| 把 MCP Tool 列表当 Graph | 列表没有依赖、超时、校验 | 不换 |
| 固定纯 workflow | 资料差异无法局部决策 | Graph 骨架 + 节点内有限决策 |

**失败怎么收。** 环、未知节点、重复 ID 在编译期拒绝。

---

## 4. Host 提交 vs Worker 候选

**失败窗口。** Worker 自己建版本：回调乱序会出现两套 Version；对象已写、库未提交则用户看到半成品。Worker 文件库当业务版本，多实例对不上。

**设计。** Java Host 冻结 Input Snapshot，事务内 Job/Task/Outbox，创建 Run 时预分配唯一 `artifact_version_id`。Worker 所有 Candidate、文件、Trace 都引用它，只回 Candidate。Host 校验回调身份、幂等键、Lease、栅栏、Payload Digest 后才落版本。用户改要求 → 新 Run；Worker 重启/租约接管 → 新 Attempt，仍绑原 Run 和 Version ID。

预分配 ID 生命周期（追问「会不会两个版本」）：

| 阶段 | 用户能看见吗 | 含义 |
| --- | --- | --- |
| RESERVED | 否 | 只有内部 ID，没有业务 Version |
| DELIVERY_PENDING | 否 | 内容合同已过，文件未齐 |
| READY | 是 | 可下载、采纳、写回 |
| DELIVERY_FAILED / ABANDONED | 否 | 交付链失败或超期；ID 不回收 |
| 已 READY 后取消 | 不允许倒退 | 只能归档、删除或追加式回滚 |

**和其他做法比。** Worker 文件库当业务版本；回调 200 当 Version 已提交；用 Kafka offset 当执行成功。

**失败怎么收。** 首轮内容前取消只终结 Run 和 Reservation，不创建空 Version。`[当前实现]` `artifact_job_run` 仍混请求与尝试；Worker 与 Host Version ID 未完全统一——面试主动讲，不当成已完成目标设计。

---

## 5. Verifier 与局部 Repair

**失败窗口。** 模型一次通过结构、引用、安全校验不现实。整篇重跑贵，且可能改掉已经对的部分。PDF/写回若早于最终 Verifier，失败产物已经外溢。

**设计。** 内容 Contract 查章节、字段、引用、题目答案；文件 Contract 查 Mime、Digest、大小、对象存在。PASS / WARN / FAIL / UNKNOWN 不能混成一个成功布尔。只修失败节点，有次数上限。目标：内容契约通过后先不可见交付中版本，文件齐了再生效。Repair 高可能是生成差，也可能是校验严，要和成本一起看。

**notes 方法。** Checkpoint 之后才进下一阶段。内容通过是检查点，文件 READY 是检查点，写回是另一条带审批的副作用，不要连成一次「成功」。

**和其他做法比。** 无限 Repair 换通过率；跳过校验直接 PDF/写回。

**失败怎么收。** 预算耗尽则失败，保留候选与原因，不标成功。`[当前实现]` PDF/写回可能早于最终 Verifier——已知缺口。

---

## 6. MCP 白名单

**失败窗口。** 任意 MCP Server 等于任意代码和网络。工具描述里的「忽略审批」若当策略，等于把供应链输入写成系统规则。模型填任意 URL 抓取会出网。

**设计。** 只调用注册表内能力、允许域名、风险等级。执行前重新鉴权。冻结的 Capability Set 只定义本 Run 上限，运行期间只能收紧。描述中的指令不当系统策略。内置 MCP 扩展资料和工具，不是开放插件市场。用户自定义生产 MCP 和 Debug 注册在生产必须关掉。

**notes 方法。** 「了解平台机制和限制才加分」。MCP 在这里是调用协议，不是插件市场，也不是授权系统。

**和其他做法比。** 用户自定义生产 MCP；用 MCP 规范代替 Workspace 授权；模型填任意 URL 抓取。

**失败怎么收。** 私网、凭据转发、未注册能力一律拒绝。副作用参数变化必须新的 OperationIntent 和 Approval，不能复用旧审批。`[目标设计]` 高风险工具调用前把编译能力与当前 ACL、Policy、未过期 Approval 再求交集。

---

## 7. 令牌桶与并发租约

**失败窗口。** 到达过快打满 Provider；同一 Workspace 占满在途会挤掉问答。只限 QPS 不限在途，长任务会堆积；只限并发，短时仍能打满 Provider。多实例各用本地计数会超卖。

**设计。** 两套配额，不要合成一个「Agent 并发」：

| 机制 | 键 | 量级 | 保护什么 |
| --- | --- | --- | --- |
| 入口令牌桶 | Workspace + Actor + Workload | 约 20/分钟 | 时间窗口内突发 |
| 在途租约 | Workspace + Workload | 约并发 4、Lease 300s | 同时占用的长任务 |
| Research 工具许可桶 | Provider / Workspace / Role | 独立 | 不能和入口配额混用 |

Redis Lua 做原子更新；Redis 只负责短期协调，Task/Outbox/业务状态仍以 MySQL 为真源。生产 Redis 不可用 Fail Closed。参数是保护值，不是压测 QPS。

**和其他做法比。** 只限 QPS 不限在途；多实例各用本地计数；用线程池大小当配额。

**失败怎么收。** 超限明确拒绝或有界排队。不能把 20/min、并发 4 写成压测吞吐。

---

## 8. 三阶段成功与未知结果

**失败窗口。** 把「模型返回了」「Worker 回调了」「用户能下载」混成一个成功，超时重试会重复副作用，指标会美化。HTTP 超时当失败重放，可能重复写文件。

**设计。** 拆开：生成成功（候选）、回调成功（主服务收到合法 Receipt）、版本提交成功（校验后落 Version/文件）。超时属于未知结果：先查回执再决定重试。内容通过后先 `DELIVERY_PENDING`，必需文件 READY 后才用户可见。首轮内容前取消只终结 Run 和 Reservation，不创建空 Version。已经 READY 的 Version 不允许倒退为取消。

Kafka 在这条链路只负责接纳：Consumer 校验 Command ID 和 Schema，MySQL 幂等登记后交 Offset。小时级 Owner/Heartbeat/Timeout/Fencing 在 Execution Lease，不靠超长 `max.poll.interval`。

**和其他做法比。** HTTP 超时当失败重放；文件还没有就对用户暴露版本；用 Job 当成功率分母。

**失败怎么收。** 回调丢失由 Worker 重发 + 对账；旧栅栏写回拒绝。Waiting 只有能力已实现持久 Wait Receipt 时才能进；未接入等待合同的 Provider 429 仍按有限重试，不能包装成自动 Resume。

---

## 8.5 应考故事（联调/审查发现，不要说成线上事故）

口径同 [09](../简历亮点八股/09-真实缺陷排查与复盘案例.md)。开口「产物联调最难的是超时到底做没做完」。`artifact_job_run` 仍混请求和尝试，主动讲。

**被问「实习最困难」优先讲故事 A**（超时重放重复文件），对方问 Kafka 卡分区再讲故事 B。

### 故事 A：回调超时，再点一次，MinIO 里两份 PDF

**现象。** 用户看到生成失败，重试后下载到内容几乎一样的两个版本；对象存储多了一份。Host 日志里第一次回调晚到被拒或被当成另一次成功。

**定位。** 拆三阶段：Worker 是否已生成文件、回调是否到达、Version 是否提交。典型窗口：Worker 写完对象，回调在路上超时，Host 当失败又派发。早期 Worker 自建 Version ID，两次回调就是两套身份。Kafka 至少一次会把同一命令再送一次。

**根因。** 超时是未知结果。Fencing 只能阻止旧 Worker **改 Host 状态**，不能把已经写到 MinIO 的对象收回来。没有预分配 ID 和文件 Staging，副作用会先于版本出现。

**修复思想。** Host 创建 Run 时预分配 Version ID，Worker 的候选和文件都引用它。内容合同过了先 `DELIVERY_PENDING`（用户不可见），Staging 导出，文件合同过再晋升 READY。超时先查 Receipt：已有 Digest 则幂等返回，不新派。旧 Token 写回条件更新行数为 0。Job 不当成功率分母——这次是同一 Job 下的第二次 Attempt。

**验证。** 回调丢失、重复回调、旧栅栏晚到。用户可见版本不翻倍。`[当前实现]` PDF/写回可能仍早于最终 Verifier——这是已知缺口，目标是校验先于可执行副作用。

**可追问 Redis。** 重试会打入口令牌桶。Hash 桶空了应 429，而不是换一条「不占配额」的路径硬跑。Lua 扣了令牌之后若 MySQL 创建任务失败，要靠租约过期回收槽位，所以 ZSet score 必须是过期时间。

### 故事 B：一个讲义生成把 Kafka 分区卡住，别的课上传也停了

**现象。** 产物任务跑了很久，同一分区上的解析/其他命令 Lag 往上走。看起来像 Kafka 挂了，Broker 其实健康。

**定位。** Consumer 若「占着 Offset 执行到 PDF 完成再提交」，Poll 间隔被最慢任务决定。再调大 `max.poll.interval` 只是把 Rebalance 延后。解析链路（条 3）是有界步骤，本来可以做完再交 Offset；产物是小时级，混用同一消费模型就会互相拖死。

**根因。** 把 Kafka 当执行器。Kafka 的特点是日志、重放、消费组，擅长**接纳和缓冲**，不擅长持有一小时执行权。

**修复思想。** Consumer 校验 Command ID，MySQL 幂等登记 Durable Execution，事务提交后立刻交 Offset。Scheduler 用 Execution Lease（续租、超时、Fencing）管长跑。分区进度不再等于最慢 PDF。无效命令先落 DLQ 再交 Offset，避免毒消息堵分区。

**验证。** 登记后杀 Consumer：Offset 已交，Scheduler 仍能从 MySQL 领到未执行记录。重复命令返回同一 Execution。不要把 Compose 联调成功率当成分区隔离已在生产验证。

**可追问。** Redis 并发 ZSet 限制同一 Workspace 同时 4 个长任务，保护的是 Provider 和线程，不是 Kafka 分区。分区卡死和配额打满是两个现象，不要用加 Consumer 台数解决下游已经慢的问题。

---

## 9. 对照：各方案特点，当前更适合什么

硬约束：结构不漂、工具不越权、失败能局部修、超时先对账。平台降搭建成本，不自动给授权和提交权。

**口述。** Dify 搭流程快，适合内部演示。运行时改边适合探索性 Agent。Temporal 适合跨天等待。开放 MCP 适合本地个人助手。只限 QPS 适合短请求。当前更贴「格式稳定的多租户产物」：Catalog 收口，编译期 Graph，Host 预分配 Version ID，速率和在途两套配额。

### 9.1 执行架构

| 方案 | 特点 | 更适合什么问题 | 当前更贴哪边 |
| --- | --- | --- | --- |
| 一次 Prompt 出文件 | 最快出字 | 个人草稿、无版本 | 讲义/测验要结构、可修、可下载 → Catalog + Graph + Verifier |
| Dify / Coze | 节点编排快 | 内部 demo、流程稳定 | 要 Workspace 授权和 Version → 演示可用，不进提交路径 |
| LangGraph 运行时改边 | 适应资料差异 | 探索性 Agent | 权限和预算要编译期收口 → 外层 Graph 固化，节点内有限决策 |
| Temporal 跑全部产物 | Timer、Wait、可见性 | 跨天等人、人工信号是主路径 | 现在主路径有界 → Durable Execution + Lease；等待变主路径再评估 |
| Celery / XXL-JOB | 定时和重试熟 | 批处理、无内容合同 | 要 Candidate/Verifier/文件合同 → 可参考调度，不当业务模型 |
| Consumer 占 Offset 跑一小时 | 实现直 | 短任务、分区不敏感 | 小时级会卡分区 → Kafka 只登记 Execution 就交 Offset |
| Worker 本地文件当版本 | 少一次上传 | 单实例 | 多实例和乱序回调 → Host 预分配 Version ID |

### 9.2 工具、配额、成功定义

| 方案 | 特点 | 更适合什么问题 | 当前更贴哪边 |
| --- | --- | --- | --- |
| 开放 MCP | 生态大 | 本地个人助手 | 多租户任意代码风险 → 白名单、域名、风险等级 |
| MCP 规范代替鉴权 | 少写授权 | 协议即信任 | 课、人、写回是业务 → MCP 只是调用协议，执行前再鉴权 |
| Function Calling 自选工具 | 灵活 | 工具面极小、可信 | 会越权抓 URL → Catalog 收口，缺必填 Fail Closed |
| 只限 QPS | 入口简单 | 短请求 | 长任务占满问答 → 令牌桶 + 在途租约两套 |
| 多实例本地计数 | 不依赖 Redis | 单实例 | 会超卖 → Redis Lua；不可用 Fail Closed |
| 线程池当配额 | 和部署绑定 | 单 Workload | 扩容即超卖 → Workspace+Actor+Workload 显式配额 |
| 超时当失败重放 | 实现省事 | 调用无副作用 | 会重复写文件 → 未知结果先查回执 |
| Job 当成功率分母 | 数字好看 | Job=一次生成 | Job 是长期意图 → 按 Run / First-pass / READY 分层 |
| 无限 Repair | 完成率容易抬 | 不怕成本 | 分不清生成差还是校验严 → 有限节点 Repair |

收口：Skill Graph 像外层 Plan；MCP 是调用协议；Kafka 在这里只**接纳**，执行权在 Lease。成功拆成候选 / 回执 / 版本。

---

## 10. 校园演练（被要求举例时）

用户选一组数据库课程资料，要求「概念讲义 + 课后题 + 参考答案」。Host 按 Action Key 选 Skill、锁 Source Scope、预分配 Version ID。Consumer 登记 Execution 后交 Offset。Worker 按图执行；缺资料且具备等待合同则放租约进 Waiting。内容 Validator 发现两道题缺来源，只修这两题。内容通过后建不可见 Version；文件齐了才 READY；写回 Wiki 必须带 Expected Revision 的 Proposal。

---

## 11. 评测口径

| 指标 | 分母注意 |
| --- | --- |
| Skill Compile Success | 按 ArtifactRun，不按 Job |
| Admission Rejection Rate | 配额/能力不匹配 ≠ 系统故障 |
| First-pass Yield | 已接受首轮 Contract 判定的 Candidate |
| File Ready Rate | 内容通过后冻结的必需文件，含后来失败的 |
| Accepted Artifact Rate | 只有 READY Version |

Compose 联调不是生产成功率。

---

## 边界

不要把 Skill 个数当成质量指标。Job 是长期意图，不能当成功率分母。文件库按 Host 预分配 Version ID + Staging 晋升来讲。
