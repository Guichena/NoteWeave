# 工程化 Agent 执行框架：面试题与参考回答

> 当前默认入口是[Agent 执行与 Artifact 一体化面试手册](32-Agent执行与Artifact产物一体化面试手册.md)。A 档先讲其中 4 分钟主回答；B 档选择 Skill Compiler、Capability Intersection、Quota、Waiting/Resume、Repair、MCP 安全或 Artifact Version/File 独立展开；普通 Schema、DTO 和工具定义属于 C 档 30 到 90 秒速查。重点机制需要讲反例和副作用边界，简单名词不重复整条演进故事。

> 阅读说明：前面的短问答用于面试官打断时快速回应。本章末尾的“八维度母题长回答”才是默认准备材料，每题应讲 3 到 5 分钟，并主动覆盖问题来源、实现机制、失败窗口、方案取舍、验证证据和当前边界。

> 证据边界：Artifact、Skill Catalog、Skill Graph、审批、验证、版本与写回以 [Artifact Skill 执行架构](../Artifact-Skill执行架构.md)为准，公共任务可靠性以 [API 与事件契约](../API与事件契约-v2.md)为准。代码存在的 Worker 测试不等于本轮已执行，生产成功率与成本属于 `[生产待验证]`。

> 深挖入口：[演进、完整案例、消融与 Ownership 答辩](21-Agent执行框架演进案例消融与Ownership答辩.md)；[Artifact Agent 产物生成、格式边界与质量指标](23-Artifact-Agent产物生成演进案例指标与Ownership答辩.md)。

## 0. 脑图主干

```text
Artifact Generation
├─ Action / Skill：报告、测验、学习指南等稳定能力
├─ Schema：输入输出契约
├─ Skill Graph：受控执行计划
├─ MCP：系统内置外部能力
├─ Java Host：Job、Version、权限、事务
├─ Python Worker：生成、验证、修复
└─ Redis：令牌桶 + 并发租约 + Workspace 隔离
```

## 0.1 从 0 讲起的演进版主回答

最开始的产物生成就是一次 Prompt 调用，这对短文本足够。产物变成 PDF、课程笔记和带图片的长文以后，资料获取、规划、工具调用、排版和校验混在一次生成里，任一步失败都要全部重做，模型还可能调用不该使用的能力。

我们比较过固定 Workflow 和自由 Agent。固定流程容易测试和恢复，但不同 Skill 的路径与可选能力不同；自由 Agent 能动态规划，却很难保证 Schema、权限和完成条件。最终选择受控中间路线：Skill 定义目标、输入输出和 Capability Policy，Compiler 生成冻结 ExecutionSpec，并做环检测和拓扑排序。运行期当前按拓扑序线性执行，不宣称有完整 Ready Queue、条件边和节点并发。

工具多起来以后，专用 HTTP Adapter 会重复建设发现、Schema 和 Trace，所以系统引入 MCP 统一协议，但生产只允许受信任系统 MCP。资源治理同时使用 Redis Lua 令牌桶和并发租约，前者管到达速率，后者管长任务同时运行数；单纯 Semaphore 在 Worker 崩溃后可能不归还，租约可以续期和回收。

Artifact Runtime 对已经实现等待契约的能力，可以把外部依赖阻塞持久化为 Waiting 并沿用原 Spec；这不等于所有 Provider 429 都会自动进入等待态。输出不合格时由确定性与语义 Verifier 产生结构化问题，Repair 只重做相关节点，最后再过全局契约。代价是框架和状态机更复杂，也减少了 Agent 自由度；收益是权限、资源、恢复和输出质量可以由程序控制。MCP 始终受系统注册、能力白名单和 Host 写回约束。

## 1. 30 秒回答

我把报告、测验、学习指南等生成能力抽象成带 Schema 的 Skill，由 Python Runtime 编译成受控 Skill Graph，而不是让自由 Agent 任意选择步骤。Java Host 负责 Workspace 权限、Artifact Job/Version、可靠投递和业务状态，Python Worker 负责资料获取、生成、验证和修复；MCP 只作为系统内置能力边界。长任务进入异步队列，并通过 Redis Lua 实现 Workspace + Actor + Workload 速率令牌桶和 Workspace + Workload 并发租约，配合任务持久化做到限流、资源隔离、水平扩展和故障恢复。

## 2. 3 分钟回答

这个框架解决两类问题。Agent 侧，完全自由的工具调用难以保证输入完整、输出结构和执行边界；工程侧，报告生成和研究任务耗时长、资源贵，一个 Workspace 的突发任务可能占满 Worker。

我先把稳定产品能力定义成 Action/Skill。每个 Skill 有输入 Schema、输出 Schema、所需资料、允许能力和验证规则。Runtime 将 Skill 编译成 ExecutionSpec 和 Skill Graph，节点可以是加载上下文、获取资料、生成、Verifier、Repair 和文件归档。图结构可以按 Skill 变化，但节点类型和状态转换受 Harness 控制。

Java Host 是业务控制面，维护 Artifact Job、Version、权限、幂等键和文件元数据；Python Worker 是智能执行面，负责 Prompt、模型调用和工具编排。MCP 用来把 B 站等外部能力接入 Worker，但当前是系统内置 MCP，不允许用户上传任意插件。

任务提交前先通过 Redis Lua 按 Workspace + Actor + Workload 原子扣减令牌，再按 Workspace + Workload 获取并发租约。Worker 执行期间续租，完成或失败时释放；Redis 不可用时默认 Fail Closed，避免配额失控。Job 和 Outbox 持久化在 MySQL，即使 Worker 或 Host 重启也能恢复。

## 3. 为什么不用自由 Agent

### 面试官问：受控 Skill Graph 会不会失去 Agent 灵活性？

稳定产品能力的输入输出和安全边界本来就应该固定。灵活性保留在节点内部的内容生成、资料选择和局部 Repair，不开放在“能调用什么能力、能写到哪里、何时算完成”这些控制面。这样既能使用模型推理，也能做 Schema 校验、幂等和恢复。

### 追问：这和普通 Workflow 有什么区别？

普通 Workflow 的步骤和参数通常预先写死。Skill Graph 的节点类型受控，但 Graph 可以根据 Skill、输入和能力集合编译；生成节点可以根据证据决定是否触发 Repair，资料节点也可以选择不同内置能力。它属于受控 Agentic Workflow。

## 4. Skill、Schema 和 Graph

### Skill 是什么？

Skill 是面向产品能力的稳定契约，不只是 Prompt 模板。它定义输入、输出、默认风格、资料要求、允许能力、执行图和验证规则。用户选择的是报告或测验，不需要理解内部 Node。

### 为什么 Schema 很重要？

Schema 在三个阶段发挥作用：提交时拒绝缺失或非法输入；编译时确定哪些节点和能力可用；完成时校验输出结构。它把模型自由文本限制在业务可处理的形状内。

### Skill Graph 如何防止越权？

Compiler 只从系统注册的节点与能力集合生成 ExecutionSpec。Worker 不能在运行时凭模型文本新增任意 Tool；节点执行前再校验 Capability Policy、Workspace Scope 和目标路径。

## 5. MCP 设计

### 面试官问：项目里 MCP 用在什么地方？

MCP 作为外部能力适配协议。例如系统内置 B 站 MCP，可以把视频信息和可用内容作为资料输入给生成 Skill。简历只需要说明 MCP 扩展外部资料和工具，不展开具体媒体处理链路。

### 追问：为什么不直接写一个 HTTP Client？

单个能力用 HTTP Client 也能做。MCP 的价值是统一工具发现、参数 Schema 和调用结果，使不同 Worker 可以复用同一能力契约。代价是增加协议和进程边界，所以只在能力需要被多个 Agent/Skill 复用时使用。

### 追问：用户能装自己的 MCP 吗？

当前 MCP 由系统注册并受内部令牌保护，能力集合在 Host 侧编译后下发，生产只启用受信任的系统 MCP。工具协议的复用不改变 Workspace 权限、审批和受控写回边界。

## 6. Java Host 与 Python Worker

### 为什么要分语言？

Java 适合稳定业务领域、事务、权限和中间件治理，Python 适合模型 SDK、Prompt 和 Agent 策略快速迭代。分层的关键不是语言本身，而是业务真源不能跟随 Agent Runtime 频繁变化。

### 跨服务如何保证一致性？

当前实现由 Host 创建 Job、Task 和 Outbox，Worker 使用 70 分钟 Delivery、单条拉取和手动 Offset 提交完成长任务。推荐方案把这条链路拆开：Consumer 收到命令后按 Command ID 在 MySQL 幂等登记 Durable Execution，事务提交后即可提交 Offset；Scheduler 再分配 Active Permit，并领取带 Epoch/Fencing Token 的 Domain Execution Lease。Worker 使用预留的 Host Version ID 返回 Candidate；内容 Contract 通过后，Host 创建内部 `DELIVERY_PENDING` Version 并冻结全部必需文件 Manifest，必需文件全部 `READY` 后才提升为用户可见终态，写回只接受 `READY` Version。Kafka 重放只能重复登记同一个 Execution，不能重复创建业务版本。

### 为什么控制面 Read Timeout 是 30 秒，Worker 却可以执行一小时？

当前配置中的 30 秒、4200 秒和 70 分钟分别保护 HTTP 控制面、Kafka 消费会话和 Callback 所有权，不能混成一个任务超时。不过更优设计会删除小时级 Kafka 会话：Consumer 完成 Durable Execution 登记后提交 Offset，3600 秒工具超时只属于 Worker Execution Deadline，所有权只由 Execution Lease 和 Fencing 判断。这样控制面超时、传输确认和业务执行期限仍然分开，同时不让最慢任务决定分区进度。

### Kafka 重投时，如何避免旧 Worker 越权执行？

`[当前实现]` Kafka 采用至少一次语义，重复消息是协议正常输入。命令携带本次 Outbox Claim 的 Delivery Token；Worker 在任何 LLM、MCP 或导出动作之前，用该 Token 请求冻结输入，Java 会校验并续租当前 Delivery。若消息属于已经失效的旧投递，Java 返回 409，Worker 直接提交该旧 Offset，不执行昂贵步骤。执行中的 Progress、Complete 和 Fail 也继续携带同一 Token，因此 Lease 过期后旧 Worker 即使恢复也不能提交结果。

这解决了旧 Kafka 重放进入昂贵执行和旧 Owner 越权写回的问题，但不把“至少一次消费”宣传成“昂贵副作用严格只执行一次”。如果 Worker 在外部副作用已经发生、Callback 尚未确认时崩溃，消息仍会重放；外部工具仍需稳定的 Execution/Operation Identity、幂等键、结果查询或 Unknown Outcome 对账。验收应注入 Worker Kill 和 Callback 断网，断言旧 Token 被拒绝、Host 不产生双终态，并统计外部副作用是否重复。

## 7. Redis 令牌桶与并发租约

### 为什么既要限速又要限并发？

令牌桶控制一段时间内能提交多少任务，防止突发；并发租约控制同时运行多少长任务，保护 Worker、模型 Provider 和下游连接池。只有限速时，少量超长任务仍可能占满系统；只有并发时，短任务突发会造成排队和数据库压力。

### 为什么用 Redis Lua？

扣减令牌需要同时读取余额、按时间补充、判断和写回；获取租约需要清理过期项、统计当前并发并写入租约。Lua 在 Redis 内原子执行，避免多个 Host 的读改写竞争。

### 租约为什么要续期？

任务可能超过初始 TTL。Worker 活跃时周期续租；宕机后无法续租，租约自然过期，其他任务可以获得名额。释放操作按 Token 删除，防止旧任务误删新租约。

### Redis 挂了怎么办？

配额是保护性控制，默认 Fail Closed 更安全。若业务选择本地 Fallback，只能作为明确降级，并接受多实例下无法保证全局额度的边界。当前默认不开启本地 Fallback。

## 8. 高并发和高可用

### 如何水平扩展？

Host 无状态扩容；Kafka Partition 和 Consumer Group 扩展 Worker；Redis 保存跨实例配额；MySQL Job/Outbox 保存恢复状态。扩容上限仍受数据库、Provider Rate Limit 和对象存储吞吐限制。

### 如何避免一个 Workspace 拖垮其他人？

速率按 Workspace + Actor + Workload 维度控制，并发租约按 Workspace + Workload 控制；Research 与 Artifact 可以有不同配置。执行队列、任务状态和指标也带 Workspace 维度，便于隔离热点。

### 追问：任务应该排队还是直接拒绝？

交互式 QA 只有很短的等待预算，超过截止时间后应快速拒绝或显式降级，不能让请求线程无限等待。Research 与 Artifact 可以进入持久队列，但必须限制队列长度、最老年龄、Workspace 份额和 Deadline；任务被接受入队也不代表已经获得 Provider、线程或数据库连接，Claim 后仍要二次准入。当前项目已有令牌桶、并发 Lease 与有界执行器，统一 Admission Controller、队列公平和 Count-only 灰度仍是目标设计。

### Worker 崩溃怎么办？

任务 Lease 和 Redis 并发租约会过期，Outbox/调度器重新投递；完成回调幂等，旧 Worker 晚到时由状态版本和 Lease 拒绝。

## 9. Verifier 与 Repair

生成结果先做 Schema、必填字段、引用和文件检查，失败时生成结构化 Repair Target，只重做有问题部分。Repair 次数有上限，超过后保留失败原因，不允许无限自我反思。

## 10. 数据和边界

当前默认 Workspace 配额为令牌桶容量 20、每分钟补充 20、并发上限 4、租约 300 秒。这些是可配置保护参数，不是压测得到的系统极限，也不应写成吞吐指标。

## 11. 常见质疑

- “Schema 驱动就是函数调用”：函数调用只约束一次输入输出，Skill Graph 还包含多节点状态、能力边界、验证和恢复。
- “Redis 分布式锁”：这里不是通用锁，而是令牌桶和有 TTL 的并发租约。
- “用了 MCP 就能绕过系统权限”：能力仍由注册、Workspace Scope、审批和 Host 写回共同约束。
- “水平扩展就是高可用”：扩展还要配合持久化状态、Lease、幂等和故障接管。

## 12. 一句话收尾

这套框架把模型的自由度放在内容生成和局部决策里，把权限、状态、资源和终态收回到 Schema、Skill Graph 与 Java Host 控制面。

## 13. 功能背后的框架与服务治理八股

### 13.1 JSON Schema 与普通参数校验有什么区别

普通 `if` 校验适合少量固定字段，JSON Schema 可以声明类型、必填项、枚举、数组元素、嵌套对象和附加字段策略，并能被 Host、Worker 和前端共用。项目用 Schema 约束 Skill 输入输出，解决跨 Java/Python 的契约漂移。

Schema 只保证结构合法，不保证业务语义。例如 `question` 是非空字符串，不代表问题适合当前 Skill。因此还需要业务 Policy 和 Verifier。面试时要把结构校验、业务校验和模型内容校验分开。

### 13.2 Skill Graph 与 DAG 知识

如果 Skill Graph 是 DAG，编译阶段可以用拓扑排序检查依赖并生成执行顺序。Kahn 算法维护入度为 0 的队列，每取一个节点就减少后继入度；最后处理节点数小于总节点数，说明存在环。

Agent 场景有时需要 Repair Loop，因此不能简单禁止所有环。更安全的做法是把循环表达为受控节点，必须声明最大次数、退出条件和可恢复状态，而不是让任意 Graph Edge 形成无界环。

### 13.3 Tool Calling、Function Calling 和 MCP

Function Calling 是模型输出符合某个函数参数 Schema；Tool Calling 是更宽泛的模型选择工具机制；MCP 是 Host/Client 与 Tool Server 之间的标准协议，解决工具发现、Schema 和调用传输。模型会调用工具，不代表底层一定使用 MCP。

项目中 Skill Graph 决定允许哪些能力，模型只在允许集合内生成调用；MCP 负责部分内置外部能力的协议接入。Capability Policy 仍在 Host/Worker 控制面，不交给 MCP Server 自己决定。

### 13.4 为什么需要 Verifier/Repair，而不是让模型自我反思

自由 Self-Reflection 容易重复改写整份内容，成本不可控，也无法证明修复了哪项错误。项目的 Verifier 输出结构化 Failure Code 和 Target Node，Repair 只处理缺失章节、非法结构或引用问题，并限制次数。这对应软件工程里的 Validation Error、局部重试和错误边界。

### 13.5 令牌桶算法

令牌桶以速率 `r` 补充，容量上限 `C`。距离上次更新经过 `Δt` 后：

```text
tokens = min(C, old_tokens + Δt × r)
allow = tokens >= cost
tokens = tokens - cost, if allow
```

它允许容量范围内的短时突发，同时限制长期平均速率。漏桶更强调恒定流出，适合平滑请求；固定窗口实现简单但边界处可能出现双倍突发；滑动窗口更准确但存储和计算更高。长耗时 Agent 既需要入口令牌桶，又需要运行中并发租约。

### 13.6 Redis Lua 为什么能保证原子性

Redis 在执行 Lua Script 时不会穿插执行其他命令，因此读余额、补充、判断和扣减形成一个原子操作。否则两个 Host 都读到 1 个 Token，可能同时放行。

Lua 原子不等于分布式事务。它只能保证 Redis 内部 Key 的操作，后续 MySQL 创建任务失败仍需释放租约或等待过期。脚本也不能执行太久，否则会阻塞 Redis 主线程。

### 13.7 Redis Cluster 下 Lua 有什么限制

一个脚本涉及的多个 Key 必须在同一 Hash Slot，通常用 Hash Tag，例如 `{workspaceId}:rate` 和 `{workspaceId}:lease`。当前如果令牌桶和租约分开脚本执行，就分别保证各自原子性；若想做跨 Key 全原子 Acquire，需要确保同 Slot 并设计失败回滚。

### 13.8 并发租约和分布式锁的区别

分布式锁通常只允许一个 Owner 进入临界区；并发租约允许最多 N 个任务同时运行，更接近分布式 Semaphore。项目用 ZSET 记录多个 Lease Token 和到期时间，`ZCARD < limit` 时才能加入。

租约提供自动过期，但要处理时钟、续租、Owner Token 和旧任务晚释放。Redis 使用服务端时间或由脚本统一时间口径更稳，Release 必须匹配唯一 Token。

### 13.9 限流、熔断、降级、隔离的区别

- 限流：入口主动拒绝超过容量的请求。
- 熔断：下游持续失败时暂时停止调用。
- 降级：用较弱能力维持核心功能，例如不做 Rerank。
- 隔离：把 Workload、线程池、连接池或集群资源分开。

本项目已明确实现 Redis 配额和 Workload 隔离；不要为了“高可用”声称所有 Provider 都已实现完整熔断器，除非代码存在对应状态机和指标。

### 13.10 限流阈值怎么定

不是拍脑袋写 4。阈值应来自压测拐点、Provider Rate Limit、数据库连接池和单任务平均资源。可以从 Little's Law 推导并发：`L=λW`。若平均任务 30 秒、稳定到达 0.1 条/秒，则平均在途约 3 个；再结合安全余量设置 4。当前项目的 4 是默认保护参数，不是经过生产压测的极限。

### 13.11 Fail Open 与 Fail Closed

Redis 故障时 Fail Open 保可用但可能让昂贵任务淹没系统，Fail Closed 保护资源但拒绝合法请求。Research/Artifact 成本高、执行时间长，当前默认 Fail Closed。普通低成本读接口可能选择本地降级，两类场景不能套同一答案。

## 14. 源码级执行框架追问

### 面试官问：Provider 不健康时直接换一个不就行了吗？

不能由模型临时换。项目的 Capability Resolver 检查 Skill Graph 约束、Provider Discovery、审批、健康状态和 Mapping Source。Preferred Provider 不健康时，只能切到满足同一能力约束的 Approved Candidate；没有合法候选则进入 Waiting。

这能防止功能降级演变成权限升级。例如原 Provider 只允许读取字幕，Fallback Provider 却带文件写权限，两者不能因为“都能处理视频”就互换。

### 面试官问：Waiting Task 恢复时为什么不重新规划？

重新规划可能选择不同 Skill、Provider 或写回权限，破坏提交时的审批语义。项目保存 Compiled Plan Checkpoint、Blocked Request 和输入快照，Provider 恢复或审批通过后只恢复匹配操作，并复用原计划。文件 Store 使用 Claim-once、Lease 和 Atomic Replace，损坏状态隔离而不是清空。

Provider 实际成功但 ACK 丢失时，只重投 ACK，不把任务改写为 Provider 失败。这里区分业务结果与控制消息投递结果。

### 面试官问：Skill 自动路由是不是关键词匹配？

不是单一关键词。Resolver 综合 Brief、Style、Source Platform、Structure、Workspace Context 和 Route Hint，多信号候选优先。相同优先级的冲突在注册时拒绝，避免运行时随机选择。Skill Graph 还检查引用、重复节点、环和 Runtime Skill 注册状态。

混合输入先转成 Canonical Content Object，再编译 Execution Plan。这个中间层类似编译器 IR，使输入适配、能力选择和生成节点解耦。

这里必须补当前边界：Java `ArtifactSkillCatalogService` 当前以显式 `skill_key` 为主，能证明别名归一化、Catalog 查找、允许字段、枚举和必填输入校验。自然语言 Intent Decision、多意图拆分和澄清是目标演进，不能因为 Worker 内存在路由逻辑就声称全产品已经有统一意图识别。

### 追问：缺少必填参数或一次要两个产物怎么办？

缺少 URL、语言或写回目标时，只问能够解除阻塞的最少问题，不允许模型猜值。一次请求要报告和 PDF 时，若两个 Skill 共享同一 Input Snapshot 且依赖明确，可以建立父 Job 和可独立恢复的子 Run；否则先让用户确认优先产物。最终生成成功不能反推 Skill 路由正确，仍要用 Gold Decision、Missing-slot Rate、澄清 Precision/Recall 和高风险错选率评测。

### 面试官问：局部 Verifier 会不会修好一处又破坏整体？

所以项目是双门禁。Node-level Verifier 按 JSON Path 或 Section 做 Local Repair，修复后重新校验节点；全部节点完成后再执行 Final Contract，检查跨节点结构、关键章节和 Evidence Coverage。Output Contract Trace 汇总失败码和修复记录。

只做全量重生成成本高且容易漂移，只做局部校验又可能得到“每段合法、整体缺页”的结果。

### 面试官问：Worker 为什么不能直接写 Workspace 文件？

Worker 属于生成执行面，不应该拥有 Workspace 最终写权限。编译阶段先判断 Writeback Mode 和目标类型，Worker 只生成 Preview 与 Writeback Request，Java Host 做权限、路径和版本校验后写入。失败请求保留 Receipt 和 Attempt History，可以重投，不需要重跑模型生成。

未知模式、高风险 MCP 能力并集和不允许的目标会在计划阶段拒绝。这里可以引出 Command/Query Separation、Least Privilege 和 Human-in-the-loop Gate。

### 面试官问：跨服务回调失败为什么不能统一标记任务失败？

输入拉取、模型生成、Provider 执行和回调投递属于不同 Failure Domain。生成已经成功，只有回调网络失败时，应该重投稳定幂等回调，而不是重跑生成。错误持久化前脱敏，并标记是否可重试；正式路由还要求内部认证，未配置 Secret 时 Fail Closed。

终态回调绑定 Task 和 Worker Type，普通 Artifact Callback 不能完成 Research Task，防止跨协议误提交。

### 面试官问：你们的 MCP 安全除了参数校验还有什么？

生产系统 MCP 使用受信任配置，下载出口拒绝 Path Traversal，HTTP 响应和文本有大小限制，携带凭据的请求不跨重定向，子进程也有超时。Tool 返回文本仍按外部内容处理，不进入 System Instruction 区；工具数量和副作用扩大时，继续沿输入 Schema、能力白名单和审批状态收紧边界。

## 15. 4 到 5 分钟标准主回答

这个模块负责把报告、测验、FAQ、Wiki、课程笔记等生成能力做成统一执行框架。真正困难的不是让模型生成 Markdown，而是如何保证不同产物有稳定结构，外部工具不会越权，长任务可以恢复，多租户并发不会把模型 Provider 和 Worker 压垮，生成结果还能经过校验和受控写回。

我没有使用完全自由的 Agent，而是把一次生成抽象为 SkillDefinition。Skill 描述输入 Schema、输出 Schema、Style、Graph Template、Capability Policy 和 Verification Policy。提交请求后，Compiler 先解析 Skill、输入来源和生成目标，生成冻结的 ExecutionSpec。ExecutionSpec 类似编译器中间表示，它已经确定节点、依赖、能力、Provider 候选、输出契约和写回方式，Worker 不再根据模型文本临时增加工具。

Skill Graph 使用 DAG 语义描述依赖。节点可以是内容加载、规范化、生成、验证、修复和导出，Compiler 检查重复节点、缺失 Skill、未注册 Runtime Node 和环依赖，再用 Kahn 算法生成拓扑序。当前运行时按固化的 `node_sequence` 线性执行。相比硬编码 Workflow，Skill 可以按配置扩展；相比自由 Agent，Graph 限制了合法动作空间。当独立 I/O 节点占主要耗时或并发明显增加时，再增加就绪状态机、Attempt Fencing 和并行写合并。

外部能力通过 Capability Resolution 和 MCP 接入。MCP 只解决 Client、Server 和 Tool Schema 的协议标准化，不负责业务授权。有效能力是系统能力、Skill 允许能力、Workspace 权限和运行环境能力的交集。Provider 选择还要看 Discovery、审批、健康状态和优先级。首选 Provider 不健康时，只能切到满足同一约束的 Approved Candidate；没有合法候选就进入 Waiting。

Waiting Task 保存 Blocked Operation、Compiled Plan Checkpoint、输入快照和恢复目标。Provider 恢复、审批通过或异步 MCP 结果到达后，只恢复匹配任务，并复用原计划。这样恢复前后不会因为重新规划而更换 Skill 或扩大写权限。Provider 已经成功但 ACK 传输失败时，只重投回执，不把业务成功改写成 Provider 失败。

生成完成后执行 Node-level Verifier 和 Final Contract。局部 Verifier 根据 JSON Path、章节或结构错误做定向 Repair，避免整篇重新生成；最终门禁再检查跨节点结构、必需章节和 Evidence Coverage。生成内容可读但没有满足证据契约时仍然失败。Output Contract Trace 保留每次修复的原因和状态。

并发治理分成速率和在途数量两个维度。令牌桶按 Workspace + Actor + Workload 限制昂贵操作到达速率，并发租约按 Workspace + Workload 限制同时运行的长任务。独立 Redis Lua 脚本分别完成令牌补充与扣减、过期 Lease 清理与租约新增，避免多实例先查后改的竞争。Lease 使用唯一 Token 和 TTL，Worker 需要续租，释放时必须匹配自己的 Token。生产策略在 Redis 不可用时 Fail Closed，避免昂贵任务失去全局约束。

Java Host 负责权限、任务状态、Outbox、配额和最终写入，Python Worker 负责模型、内容处理和 MCP 调用。跨服务请求与回调都有内部认证、稳定幂等键和 Worker Type 绑定。Worker 不能直接写 Workspace，只能生成 Writeback Preview 和 Request，由 Java Host 再做权限、目标和版本校验。

这套框架的 Trade-off 是编译、状态机和能力治理增加了开发成本，也限制了一部分模型自由度。单一固定产物使用普通异步 Job 更简单；完全开放的探索任务可以使用更自由的 Agent。当前项目需要多种可配置产物、外部能力、受控写回和多租户资源隔离，因此选择 Skill Graph 加 Compiler 的中间路线。

## 16. 常见方案比较的面试口径

### 追问：Skill Graph 和普通工作流引擎有什么不同？

工作流引擎通常执行开发时写好的节点和条件。Skill Graph 的节点和约束由 Skill Catalog、输入类型、能力映射和 Style 共同编译，同一个模板可以得到不同 ExecutionSpec。但编译后仍是受控 DAG，不允许模型任意修改运行图。动态发生在编译阶段，稳定发生在执行阶段。

### 追问：为什么不直接使用 LangGraph？

LangGraph 可以简化节点状态和图执行，但项目仍需要 Java 侧权限、MySQL 任务真源、Outbox、Redis 配额、跨语言回调和 Workspace 写回。直接采用框架不能替代这些领域约束。当前实现保留自有状态协议，代价是代码更多，好处是业务状态和恢复语义完全可控。若未来图结构更复杂，可以在 Python 内部使用图框架，但 Host 协议仍需保留。

### 追问：令牌桶为什么不能替代并发租约？

令牌桶限制开始速率。任务一旦启动，无论执行 1 秒还是 10 分钟，都只消耗一次令牌。若任务持续时间变长，系统仍可能积累大量在途请求。并发租约直接限制当前运行数量。反过来，只有限制并发会允许空闲时瞬间启动大量任务，冲击 Provider。两个维度需要同时控制。

### 追问：为什么 Repair 不直接重新生成整篇？

全量重生成实现简单，但会修改原本正确的部分，Token 成本也更高。局部 Repair 只处理失败节点，稳定性更好；代价是需要结构化输出、错误定位和跨节点最终校验。短文本或输出没有结构时，全量重生成可能更经济。

## 17. 面试官八维度母题长回答

### 17.1 业务抽象：为什么用 Skill、ExecutionSpec 和 Run，而不是一个通用 Prompt？

这个模块面对的业务不是“调用一次大模型”，而是稳定生产报告、测验、FAQ、Wiki 和课程笔记等不同产物。它们的输入来源、输出结构、允许工具、质量门禁和写回目标都不同。如果只把差异放进 Prompt，系统无法在调用前判断请求是否合法，也无法解释恢复时应该继续哪一步。因此我把可复用能力抽象为 SkillDefinition，把一次具体请求编译成不可变 ExecutionSpec，再由 Run、Node Attempt 和 Writeback Request 记录执行与副作用。

SkillDefinition 是产品能力契约，描述 Input/Output Schema、Style、Graph Template、Capability Policy 和 Verification Policy；ExecutionSpec 是编译后的中间表示，冻结本次节点顺序、输入快照、Provider 候选、能力集合、输出契约和写回方式；Run 是这份 Spec 的一次生命周期。这样 Skill 可以升级而不改变已经启动的任务，失败恢复复用原 Spec，不会因为 Catalog 更新或模型重新规划而扩大权限。用户看到的是某类产物和状态，不需要理解底层用了哪个 Provider。

自由 Agent 对探索问题灵活，但动作空间、成本和输出结构难预测，尤其不适合自动写回 Workspace；每种产物硬编码 Workflow 初期简单，但会复制权限、状态和恢复逻辑。当前方案选择“编译前可配置、编译后受约束”的中间路线。代价是维护 Schema、Compiler 和 Runtime Node Registry，也牺牲部分模型自由度。若只有一个固定短任务，普通异步 Job 更合适；当能力种类和组合持续增长时，这个抽象才真正产生收益。

### 17.2 数据与一致性：Job、Version、Task、Outbox 和写回如何保证不乱？

MySQL 是任务身份、状态和输出版本的真源。提交请求时使用 Workspace、Actor、Skill、目标和客户端幂等键建立 Job 与冻结的 ExecutionSpec，并在同一事务写入派发 Outbox。相同幂等键与相同 Payload 返回原 Receipt，不同 Payload 复用同一键则冲突，避免网络重试创建两份任务。Dispatcher 可能重复发布，但 Worker 回调携带 Job ID、Spec Version、Attempt 和稳定幂等键，Host 通过唯一约束和条件状态迁移吸收重复。

执行结果不能简单“最后一次写覆盖”。Worker 开始节点时获得对应 Attempt，完成回调只允许从预期状态推进，并校验 Worker Type、ExecutionSpec 和输出契约。Lease 过期后新 Worker 接管，旧 Worker 晚到的回调不能确认新 Attempt，这本质上是 Fencing。Provider 已经完成但回执传输失败时，重试的是同一结果提交，不应把 Provider 成功改写成失败，也不应再次调用昂贵模型。Waiting 任务保存 Blocked Operation 和 Checkpoint，恢复后继续原计划，而不是重新编译。

Worker 只生成 Preview 或 Writeback Request，Java Host 最终提交前重新检查 Workspace 权限、目标版本和写入策略。目标在长任务期间已变化时，使用期望版本做冲突检测，进入 Review 或显式失败，不能静默覆盖。这里没有跨 MySQL、Provider 和工具系统的分布式事务，而是本地事务、幂等回调、版本检查和补偿。Outbox 保证派发意图不丢，不保证模型只调用一次；回调幂等保护 Host 副作用，也不能撤销外部工具已经完成的不可逆动作，因此高风险工具必须预览、审批或具备自身幂等键。

### 17.3 并发与容量：Redis 令牌桶、并发租约、Worker 和 Provider 配额如何协同？

容量需要同时控制到达速率和在途数量。令牌桶按 Workspace、Actor 和 Workload 限制一段时间内能启动多少昂贵任务，吸收突发并维护公平性；并发租约按 Workspace 与 Workload 限制当前同时运行的长任务。只有令牌桶时，任务时长从 10 秒增长到 10 分钟仍会积累大量在途任务；只有并发限制时，空闲瞬间仍可能启动过多请求冲击 Provider。独立 Redis Lua 脚本原子完成令牌补充扣减、过期 Lease 清理和新 Lease 创建，多实例不会出现先查后改竞争。

总容量取决于最慢阶段。按 Little 定律，在途量约等于到达率乘平均服务时间。应分别测编译、内容加载、模型首 Token、完整生成、Verifier、Repair、MCP 和写回耗时，再决定 Worker 数量与各节点超时。Java Host 数据库连接池、Python Worker 最大并发、Provider RPM/TPM、MCP Server 并发和 SSE 连接都是约束，不能把某个线程池调大就称为扩容。不同 Workload 应分池或分配权重，避免长报告阻塞短 FAQ。

背压要尽早发生。配额不足返回明确重试信息，Worker 队列有上限，Provider 429 当前进入有界退避或失败分类，持续不可用不做无限重试。持久 Waiting、到期唤醒和公平恢复是目标治理，不能当成所有 Provider 路径都已实现。生产策略在 Redis 故障时对昂贵任务 Fail Closed，保护全局成本，代价是可用性下降；低风险只读能力可按策略降级。容量结论必须来自包含输入 Token 分布、输出长度、Repair 比例和 Provider 限额的压测。当前配置值只是保护参数，不是已经验证的 QPS 承诺。

项目调用顺序是创建 Research 或 Artifact 前先执行 `WorkloadQuotaService.requireRate()`，再用 `acquireLease()` 占用对应 Workspace + Workload 的在途名额，任务继续运行时调用 `renewLease()`，进入终态后 `releaseLease()`。默认 Rate Capacity 20、每分钟补 20、每个 Workspace + Workload 并发 4、Lease 300 秒，生产环境禁止本地配额回退。`WorkloadQuotaRedisIntegrationTest` 用两个 Service 实例验证跨实例的租户级限额和 Lease 过期接管，说明这些参数如何被代码消费。

### 17.4 安全：MCP 已有协议和 Schema，为什么仍不能直接让模型调用？

MCP 标准化的是工具发现、参数 Schema 和调用协议，不自动提供业务授权或租户隔离。一次有效能力必须是系统注册能力、Skill 允许能力、Workspace 权限、运行环境能力和审批状态的交集。模型只能在 ExecutionSpec 已编译的 Capability Set 中选择参数，不能通过生成文本新增工具、切换到未审批 Server 或扩大写入 Scope。Provider Discovery 发现了工具也不等于可以使用。

Java Host 持有用户权限、任务真源和最终写权限；Python Worker 负责模型与工具执行，但使用 Audience 区分的内部凭证，回调校验 Worker Type、Job 和幂等身份。Worker 不直接更新 Workspace 对象，只提交 Writeback Preview，由 Host 在提交时复查 Membership、资源归属、目标版本和内容类型。对外工具参数做结构、大小和超时限制，敏感凭证由 Server 侧管理，不进入 Prompt、日志或模型可见输出。工具返回内容属于不可信数据，不能让其中的 Prompt Injection 改写系统策略。

高风险能力还要按副作用分级。只读查询可以自动执行，外发、删除或广泛写入需要确认、审批、幂等键和审计。当前系统实现了能力白名单、内部认证和受控写回；工具并发、调用范围或外部副作用扩大时，再补充更细的进程、凭证和出站网络约束。

具体到 Artifact 链路，`ArtifactSkillCatalogService.requireCapability()` 验证注册能力，`validateAndNormalizeInputs()` 在创建 Job 前做输入 Contract 校验；Worker 完成后，`WorkerTaskCallbackAuthenticator` 先验证回调身份，真正写回由 `ArtifactJobService.saveVersionAsSource()` 或 `writeVersionToKnowledge()` 在 Host 内执行。`WorkerTaskCallbackAuthenticatorTest` 与 `Phase6ResearchArtifactContractTest` 分别验证内部身份和跨模块 Contract。

### 17.5 可观测性：任务“卡住了”，如何判断在排队、Waiting 还是失败？

观测模型要围绕 Job、ExecutionSpec、Node、Attempt 和 Provider Call 建立，而不是只记录最终异常。每个 Run 保留 Skill Version、Spec Hash、Workspace、Workload、当前节点、Attempt、Lease Owner、Provider、Capability 和 Correlation ID。状态区分 QUEUED、RUNNING、WAITING、REPAIRING、SUCCEEDED、FAILED 和 CANCELLED；Waiting 还记录 Blocked Reason、Blocked Operation、下次检查时间和恢复来源。因此“没有进展”可以被区分为配额等待、Provider 不健康、审批未完成、异步工具未回调、Worker 丢失或永久失败。

指标按执行漏斗观察：提交与拒绝、排队年龄、编译失败、节点耗时、模型首 Token 与总耗时、Provider 429/5xx、工具错误、Waiting 数量与年龄、Lease 失效、重复回调、Verifier 失败、Repair 次数、最终契约通过率和写回冲突。输入输出 Token、工具调用和修复增量也与 Job 关联。日志不能记录完整 Prompt、凭证或敏感资料，只记录 Hash、版本、大小、错误分类和受控引用。

排障时先查 Job 真源状态及最近合法迁移，再查 Outbox、派发和配额，再定位当前 Node Attempt 与 Lease，最后看 Provider 或 MCP 调用及回调。任务恢复后输出不一致时，比较 Spec Hash、Input Snapshot 和 Provider 参数。可以定义提交到终态的 P95、Waiting 超预算比例、平均 Repair 次数等 SLI，当前 MDC 和 Micrometer 已支持按 Job、Task、Node 和回调阶段定位问题，规模增长后再根据观测盲区补充更细的关联链路。

### 17.6 成本：怎样避免模型、工具和 Repair 成本失控？

Agent 成本是输入 Context、输出 Token、节点数、Provider 单价、MCP 调用、等待占用、失败重试、Verifier 和 Repair 的总和。自由 Agent 的风险是循环次数与工具选择不可预测。编译后的 ExecutionSpec 给每个节点配置 Token、时间、尝试次数和能力预算，使成本在执行前有上界。Workspace 和 Workload 配额限制总量，令牌桶控制启动速率，并发租约避免大量长任务同时占用昂贵 Provider。

执行中先减少无效工作。Context Compiler 只提供必要输入，结构化输出让 Verifier 定位问题；局部 Repair 只修失败节点并保留已通过结果，避免整篇重生成。Provider 路由不能只看单价，还要看成功率、延迟、上下文限制和 Repair 概率，便宜模型若导致三次修复，总成本可能更高。缓存只用于输入、Spec 和确定性中间结果，不能跨权限或版本混用。当前路径对永久错误停止重试，对 429 和短暂 5xx 有界退避；长期不可用转持久 Waiting 仍需按能力逐条证明。

Verifier、快照和审计本身也有成本。短文本、低风险且人工容易检查时，一次生成可能更经济；高价值产物、有严格结构或自动写回时，校验成本换来较少返工与风险。应按 Skill 统计单位成功产物的输入输出 Token、Provider 调用数、Repair 率、工具费、失败浪费和人工 Review 时间。只有这些数据才能决定换模型、调整 Graph、增加缓存，还是取消收益不足的自动化环节。

当前代码能证明的成本约束是入口配额、Artifact Dispatch `1/1/0`、Kafka Consumer `max.poll.records=1`、Callback 幂等和有限状态迁移。`ArtifactOutboxDispatcherService.dispatchReadyArtifactJobs()` 只按受控 Batch 派发，Broker 发布失败进入重试或 Dead Letter；Worker 不会在一小时 MCP 调用期间预取一批任务，`ArtifactJobService.markWaiting()` 让依赖不可用变成可观察状态。仓库还没有统一的 Skill 级 Token 与工具费用账单，因此成本收益只能讲计算方法和保护点，不能报虚构节省比例。

### 17.7 测试证据：如何证明 Compiler、权限和恢复协议不是纸面设计？

Compiler 测试覆盖确定性与拒绝路径：相同 Skill Version 和输入产生相同 Spec Hash；重复节点、缺失依赖、环、未注册 Runtime Node、Schema 不匹配和未授权 Capability 必须在执行前失败。DAG 用拓扑排序校验，但当前运行时按固化的 `node_sequence` 线性执行，测试不能假装已支持 Ready Queue、条件边或节点并行。Capability 测试验证交集语义，任何一层撤销都不能被 Provider Discovery 或模型参数绕过。

故障测试覆盖派发后 Worker 崩溃、Lease 过期接管、旧 Worker 晚回调、Provider 成功但 ACK 丢失、重复回调、Waiting 恢复、审批变化和取消并发。断言不只是最终成功，还包括副作用没有重复、旧 Attempt 不能推进新状态、恢复复用原 Spec 和 Input Snapshot。Redis 集成测试验证 Lua 原子性、令牌补充、Lease Token、TTL 与错误释放；写回测试验证权限在任务期间撤销、目标版本冲突和 Preview 与 Commit 分离。

质量证据也分层：Schema/Contract 测试证明结构合法，Verifier Fixture 证明已知错误能被发现，局部 Repair 测试证明正确区域保持不变，端到端 Golden Case 证明特定 Skill 满足规则。它们不等于真实用户觉得内容优秀，也不等于 Provider 永远稳定。面试时要给出具体测试名称、注入位置、预期不变量和观察结果。未执行的测试不能表述为本轮已验证，缺少线上质量、容量与恢复数据时应明确列为上线门禁。

可以点名的现有测试是 `ArtifactWorkerInputPayloadTest` 验证 Worker 输入 Contract，`WorkerTaskCallbackServiceTest` 覆盖 Heartbeat、Progress、Complete、Fail 与重复回调，`WorkloadQuotaRedisIntegrationTest` 验证多实例配额，`ArtifactOutboxDispatchSchedulerTest` 验证单并发派发入口。它们没有证明文档中设想的通用 DAG Compiler 全部已经产品化，因此回答 Compiler 题时要区分当前 Skill Contract 与未来图执行器。

### 17.8 演进边界：什么时候需要更复杂的执行编排？

当前 Compiler 支持 DAG 语义并在编译期做拓扑校验，但运行时使用固化节点序列线性执行。这个边界让恢复点、成本和副作用顺序简单，也意味着暂不支持节点级并行、条件边、动态循环和 Ready Queue。只有真实 Skill 出现稳定并行分支，并且串行耗时成为主要瓶颈时，才引入节点就绪状态、依赖计数、并行 Attempt、取消传播和结果合并。不能只把执行器改成线程池，否则失败恢复与重复写入会失控。

额外的图状态或工作流实现可以承担 Python 内部的状态图、Checkpoint 和条件路由，但不能替代 Java Host 的 Workspace 权限、MySQL 任务真源、Outbox、配额、内部认证和受控写回。接入时应保持 ExecutionSpec 和 Host 回调协议稳定，把编排实现放在 Runtime 内部，而不是让框架状态成为唯一业务真源。只有当任务数量、平均时长、审批等待和补偿分支明显增长，现有状态机的维护成本成为瓶颈时，才评估更强的工作流实现。

插件化也必须伴随治理。新增 Skill 先走 Catalog 与版本管理；第三方 Skill 或 MCP Server 还需要来源、能力声明、审批、兼容策略和隔离。旧 Job 永远绑定旧 Spec，Skill 升级只影响新提交；Schema 采用兼容版本与灰度，Provider 替换只能选择满足相同 Capability 和 Contract 的候选。长期不变量是编译前验证、执行时受限、状态可恢复、副作用由 Host 最终授权。只要保持这四点，底层框架就能演进而不破坏业务与安全边界。

当前可替换边界落在 `ArtifactWorkerInputPayload` 和 Worker Callback Contract：只要新的执行实现仍接受同一不可变输入，并通过 `WorkerTaskCallbackService` 返回受控状态，Host 的权限、Outbox、Quota 和写回不需要迁移。只有并行分支、等待、人工确认或补偿路径占主要复杂度时，才补节点 Attempt、Ready 状态与取消传播；否则现有 Job/Version 加 Worker 状态机更容易测试和回滚。

## 18. 八维母题的项目源码答辩卡

| 评分面 | 项目功能与生产类 | 核心方法、状态或真实设置 | 验证证据与当前边界 |
|---|---|---|---|
| 业务抽象 | `ArtifactSkillCatalogService.resolveSkill()/validateAndNormalizeInputs()` 解析 Skill，`ArtifactJobService.createJob()` 创建 Job、输入快照和任务，`getWorkerInput()` 下发冻结执行输入 | Skill 描述能力；当前 Host Version 在成功回调后追加，推荐方案才预留唯一 Version ID | `ArtifactWorkerInputPayloadTest`、`Phase6ResearchArtifactContractTest` |
| 数据与一致性 | Job、Input Snapshot、Task、Outbox 在本地事务中提交，`ArtifactOutboxDispatcherService.dispatchReadyArtifactJobs()` 派发，`WorkerTaskCallbackService.completeFromDelivery()` 追加 Version 并确认终态 | Callback 带任务身份、Delivery Token 与幂等条件；当前对象存储与数据库最终一致，推荐用 Delivery Manifest 和对账 | `ArtifactOutboxDispatchSchedulerTest`、`WorkerTaskCallbackServiceTest`；跨服务不使用分布式事务 |
| 并发与容量 | `WorkloadQuotaService.requireRate()/acquireLease()` 在创建昂贵任务前执行，Worker 和 Provider 还有各自并发限制 | Token Bucket 20、每分钟补 20、并发 4、Lease 300 秒；Artifact Dispatch Executor 为 `1/1/0` | Quota Redis Integration、Task Quota Lifecycle、Scheduler Test；阈值不是生产容量结果 |
| 安全 | `ArtifactSkillCatalogService.requireCapability()`、Approval Decision、`WorkerTaskCallbackAuthenticator` 与 `ArtifactJobService.saveVersionAsSource()` 分层授权 | MCP Schema 只验证参数形状，最终能力取 Skill、用户权限、Provider 健康和审批策略的交集 | `WorkerTaskCallbackAuthenticatorTest`、`Phase6ResearchArtifactContractTest` |
| 可观测性 | Artifact Runtime Trace 记录 Node、Lifecycle、Contract、Verification、Repair 和 Writeback，Outbox 暴露状态与年龄 | Waiting、Running、Failed、Completed 必须区分；RunId、JobId、VersionId、TaskId 串联 | Artifact Trace、Dispatcher Metrics Test；任务规模增长时再增加关联维度 |
| 成本 | 配额限制任务入口，Execution Spec 固化模型与工具预算，Verifier 和 Repair 有次数边界 | 模型调用、工具调用、重试和 Repair 分开计数；Provider 失败不能触发无界重新规划 | Quota、Repair、Callback Test；尚无线上单位 Artifact 成本账单 |
| 测试证据 | `ArtifactWorkerInputPayloadTest`、`WorkerTaskCallbackServiceTest`、`WorkloadQuotaRedisIntegrationTest` 与 `Phase6ResearchArtifactContractTest` 分层验证 | 覆盖输入 Contract、重复回调、旧 Delivery Token、配额耗尽和写回边界 | 测试能证明协议不变量，不能替代真实 Provider 与恶意 Tool 的长期运行验证 |
| 演进边界 | Worker Runtime 可按任务规模和时长演进，Host 继续保存权限与业务真源 | 旧 Job 绑定旧 Spec；Skill 升级只影响新提交；Provider 必须满足同一 Capability Contract | Execution Mode 与兼容性测试；扩展由任务时长、等待分支、并发和恢复成本触发 |

## 当前 Artifact 契约

对外接口是 `/api/v2/skills` 和 `/api/v2/workspaces/{workspaceId}/artifact-jobs`。`[当前实现]` Java 创建 Job、输入快照、Task 和 Outbox，Artifact Worker 通过内部接口读取输入、报告进度、完成或失败，Host 在成功回调后追加业务 Version；Worker 调试仓储还有另一套 Version ID。`[目标设计]` 才是在创建 ArtifactRun 时由 Host 持久化唯一 `reserved_version_id`；`RESERVED` 阶段只有预留身份，内容 Contract 通过后才用同一 ID 创建 `DELIVERY_PENDING` Version，全部必需文件 `READY` 后再提升。Skill Graph、Verifier、Repair、waiting 和 system MCP 是 Python 运行时内部实现。用户自定义 MCP、Action-first 产品对象和真实 LLM 只有在配置与 Provider 存在时才可验证。

## 19. 面试版上下游链路与技术取舍长回答

Artifact 的入口不是让模型直接生成一段 Markdown，而是先把用户需求映射到受控 Skill。面试推荐方案中，Java 主服务校验 Workspace、用户权限、Skill Version 和输入 Schema，在本地事务里创建 Job、Run、Input Snapshot、预留 Version ID 和待接纳命令；Consumer 再幂等登记 Durable Execution。输入快照冻结用户要求、资料范围、Control Pack、Skill Version 和上游引用，Provider、Prompt、temperature 与随机参数只有在实际持久化后才能宣称可重放，当前实现还没有全部覆盖。Python Worker 只能读取该 Run 的快照，不能在执行过程中偷偷切换到当前会话或最新资料。Worker 解析 Skill，生成 Execution Spec 和 Skill Graph，按拓扑顺序执行 acquire、execute、verify、repair 或 wait，再通过内部 Callback 回写进度和 Candidate；Host 负责 Version、文件交付状态和终态。

这条链路把用户可见对象和运行时细节分开。用户只提交 Skill、Job 和输入，内部的 action key、graph、MCP binding、Verifier 和 Repair 不暴露成任意可组合的 API。Capability Resolution 取 Skill 要求、用户权限、Workspace Policy、Provider 健康和当前审批的交集，MCP Schema 只解决参数形状，不能代替副作用授权。验证失败进入有限 Repair；只有已实现持久等待记录、恢复条件和唤醒入口的能力，外部依赖不可用时才进入 Waiting。取消、Lease 过期或旧 Delivery Token 到达时由 Java 状态机收口。生成结果不会覆盖旧 Version，写回 Source、Note 或 Wiki 前还要再次执行权限和版本冲突校验。

当前采用的是受控 Skill、Schema、ExecutionSpec 和 Host Writeback，而不是让一次用户请求任意扩大网络、凭证和副作用范围。图执行、等待和重试的通用实现可以作为 Runtime 参考，但仍应让 Java 持有 Workspace ACL、Job 状态和写回权限。只有当任务数量、平均时长、等待分支和补偿逻辑显著增长，当前 Skill Graph 的维护成本超过收益时，才评估新的编排实现，而不是先把产品对象迁移成框架对象。

配额和并发是执行面的前置门禁。创建昂贵 Job 前先用 Redis Lua 检查速率令牌和 Workspace/Workload 并发租约，Worker 侧仍受 Provider 并发、HTTP 超时和回调容量约束。Redis 故障时高风险任务选择 Fail-closed，避免每个实例各算一份全局配额；具备等待契约的能力可以进入 Waiting，其他路径按错误分类做有界重试或失败。Artifact Dispatch Executor 当前有意保持很小，因为调度本身需要可观察的 Claim 节奏，不应通过扩大线程池掩盖下游饱和。

面试收尾可以强调：Agent 工程化的重点不是让模型拥有更多工具，而是把工具能力放进版本、权限、预算、等待、验证和写回边界里。自由度越高，失败越难解释；当前方案牺牲部分任意组合能力，换来一个可以重试、比较、回滚和审计的产物生命周期。默认关闭 LLM、MCP 或 Provider 时，系统仍能验证 Job、Task、Callback 和 Version 协议，但不能把空产物或 NoOp 结果说成真实生成质量。
