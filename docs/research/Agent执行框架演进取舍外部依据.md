# Agent 执行框架演进取舍外部依据

> `[行业事实]` 本文只使用论文、标准、官方文档、官方仓库和公司一手材料。外部项目的功能描述不自动证明 NoteWeave 已实现同等能力。

> `[当前实现]` NoteWeave 事实以当前源码为准；`[目标设计]` 和 `[生产待验证]` 只表示合理演进方向或测量方案，不能写成已经上线的结果。

> 调研日期：2026-08-17。GitHub Star、版本状态和产品页面会变化，引用时必须保留日期。

## 0. 结论先行

1. **Workflow 与 Agent 不是二选一。** Anthropic 将 Workflow 定义为由预设代码路径编排模型和工具的系统，将 Agent 定义为由模型动态决定过程与工具使用的系统。NoteWeave 当前更准确的名称是“受控 Agentic Workflow”：Skill 和输入可以影响编译结果，但执行前形成冻结契约，运行时不能任意扩大能力。
2. **Skill、Plugin、Tool 和 Schema 分属不同层。** Skill 是可复用的任务知识与过程包；Plugin 是安装、分发和扩展容器；Tool 是运行时可调用接口；JSON Schema 只校验数据形状。四者都不能单独代替授权、隔离、审批、预算和审计。
3. **MCP 是互操作协议，不是安全边界。** MCP 统一发现和调用工具，但规范仍把输入校验、访问控制、限流、结果清洗、用户确认和审计责任留给实现方。
4. **Durable Execution 不等于 Exactly Once。** Checkpoint 或事件历史能恢复控制流，但外部副作用仍可能出现“Provider 已成功，客户端因超时不知道结果”的未知结果窗口。正确方案是稳定幂等键、结果查询、回执去重、fencing 和业务结果与投递结果分离。
5. **限流不等于公平。** 令牌桶限制到达速率，Lease 限制在途量；多租户公平还需要租户分区、权重、排队、拒绝策略和饥饿监控。
6. **公开指标通常测的是不同东西。** Benchmark 宣传任务成功率，厂商案例宣传业务时长或成本，框架仓库宣传 Star 或客户 Logo，安全项目宣传攻击成功率。若任务集、模型、工具、预算、终态判定、运行次数和失败分母不同，数字不能横比。
7. **NoteWeave 目前能宣传的是工程机制和已执行测试，不是生产收益。** 尚无真实用户、线上容量、单位产物成本或统一 Gold Set 结果时，只能讲 Job/Version、Skill Contract、Quota/Lease、Callback 幂等、Waiting/Resume、Verifier/Repair 和 Host Writeback 的设计与测试证据。

## 1. Workflow、Agent 与混合执行

### 1.1 一手定义

[Anthropic《Building Effective AI Agents》](https://www.anthropic.com/research/building-effective-agents)明确区分：

- Workflow：LLM 和工具通过预定义代码路径编排；
- Agent：LLM 动态决定自己的过程和工具使用方式；
- 实践上应从最简单可行方案开始，只在任务确实需要灵活决策时增加 Agent 自主性。

这一区分关注“谁决定下一步”，不是关注系统里有没有 LLM、DAG 或 Tool Calling。

| 形态 | 下一步由谁决定 | 优点 | 主要代价 | 适用条件 |
| --- | --- | --- | --- | --- |
| 单次 Prompt | 固定调用方 | 实现最短 | 不可恢复，难定位失败 | 短文本、无副作用 |
| 固定 Workflow | 代码 | 可预测、易测试 | 输入差异大时分支膨胀 | 稳定 SOP、强合规 |
| 自由 Agent | 模型 | 探索能力强 | 成本、权限和终态难控制 | 开放探索、低副作用 |
| 受控 Agentic Workflow | 编译器与模型在边界内共同决定 | 兼顾变化与治理 | 需要中间表示、版本和策略 | 长任务、多工具、可审计产物 |

### 1.2 NoteWeave 的准确位置

`[当前实现]` Artifact Skill 先经过 Java 侧 Catalog 和输入约束，再由 Python Worker 形成节点序列；[`skill_graph.py`](../../workers/artifact-worker/app/skill_graph.py)中的 `execute_skill_graph()` 按 `plan.node_sequence` 线性运行。当前不是具备 Ready Queue、条件边、节点并行和动态循环的通用 DAG 引擎。

因此面试口径应是：

> 我没有把系统做成完全自由的 Agent。用户目标先解析成版本化 Skill 和输入快照，执行前编译为冻结计划；模型可以在允许范围内生成内容或选择受限策略，但工具、预算、写回和终态仍由确定性控制面约束。当前是编译期 DAG 语义、运行期拓扑序线性执行。

不能说：

- “模型会自主规划任意步骤并动态创建工具”；
- “已经实现完整并行 DAG 调度”；
- “使用 Agent 就天然比固定 Workflow 成功率高”。

## 2. Skill、Plugin、Tool 与 Contract

### 2.1 Skill 是渐进披露的任务知识包

[Agent Skills Specification](https://agentskills.io/specification)把 Skill 定义为至少包含 `SKILL.md` 的目录，Frontmatter 提供名称和描述，还可以带脚本、参考资料和资产。客户端先读取轻量元数据，触发后再加载完整说明，这是一种渐进披露机制。

它说明 Skill 适合承载：

- 何时使用某项能力；
- 任务步骤和领域规则；
- 可复用脚本、模板和参考资料；
- 输入预期和产出要求。

但 Skill 文本本身不是强制安全策略。恶意或错误 Skill 可以诱导模型调用危险工具，附带脚本也可能执行代码。因此安装来源、版本、签名或摘要、所需能力和审查记录仍应由 Host 管理。

### 2.2 Plugin 是分发边界，不是授权边界

不同产品对 Plugin 的目录结构没有统一标准。以 [Claude Code Plugins 官方文档](https://code.claude.com/docs/en/plugins)为例，Plugin 可以打包 Commands、Agents、Skills、Hooks 和 MCP Servers。这个例子证明 Plugin 更接近“扩展的安装与分发容器”，而不是一种统一运行协议。

面试时应主动区分：

```text
Plugin package
  -> 安装来源、版本、依赖、更新策略
  -> 声明 Skills / Hooks / MCP Servers

Skill contract
  -> 任务意图、步骤、输入输出、所需能力

Tool contract
  -> 运行时方法名、参数 Schema、返回 Schema、错误

Policy decision
  -> 当前用户和当前 Run 到底能不能调用
```

Plugin 已安装只代表“代码或配置存在”，不能推出当前用户被授权使用全部能力。

### 2.3 Tool Contract 与 MCP

[MCP Tools Specification](https://modelcontextprotocol.io/specification/2025-11-25/server/tools)要求 Tool 至少描述名称和 `inputSchema`，也可以提供 `outputSchema` 和结构化结果。规范同时提醒实现方：

- Server 应校验 Tool 输入；
- Client 应展示敏感 Tool 调用并允许用户拒绝；
- 应实施访问控制、限流、输出清洗、超时和审计；
- Tool Annotation 只是提示，来自不可信 Server 时不能当作安全事实。

`[当前实现]` NoteWeave 的系统 MCP 通过 [`system_mcp_registry.py`](../../workers/artifact-worker/app/system_mcp_registry.py)注册；自定义 MCP 执行器会检查传输、命令、工作目录、返回错误和结构化内容大小，见 [`custom_mcp_executor.py`](../../workers/artifact-worker/app/custom_mcp_executor.py)。这些校验仍不等于操作系统级 Sandbox 或完整第三方供应链审查。

### 2.4 JSON Schema 能证明什么

[JSON Schema Draft 2020-12 Core](https://json-schema.org/draft/2020-12/json-schema-core)定义了描述 JSON 结构和约束的词汇体系。它可以验证类型、必填字段、枚举、数组和对象结构，但不能证明：

- 引用的资源属于当前 Workspace；
- URL 不指向内网或云元数据；
- `score: 100` 符合业务含义；
- Tool 返回文本没有 Prompt Injection；
- 写操作已得到当前用户批准。

`[当前实现]` [`ArtifactSkillCatalogService.java`](../../backend/src/main/java/com/noteweave/artifact/ArtifactSkillCatalogService.java)会拒绝未知输入、补默认值并校验声明类型和枚举。准确口径是“实现了当前 Catalog 所需的结构化输入校验”，不是“完整实现了 JSON Schema 2020-12 的所有关键字和语义验证”。

## 3. Capability、最小权限、Sandbox 与人工审批

### 3.1 能力必须取交集

[MCP Authorization Specification](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization)要求受保护资源校验令牌有效性和受众，不能把发给其他资源的 Token 当作自己的授权。其安全意义是：认证了调用方，不代表调用方对所有 Tool、所有资源和所有副作用都有权限。

NoteWeave 的有效能力可表达为：

```text
effective_capabilities =
    system_registered
  ∩ skill_allowed
  ∩ workspace_authorized
  ∩ runtime_available
  ∩ approval_granted
  ∩ security_policy_allowed
```

任何一层只能收紧，Prompt、Tool 返回文本或 Plugin Manifest 都不能扩大权限。

### 3.2 Policy Gate 不等于 Sandbox

Policy Gate 决定“是否允许调用”，Sandbox 限制“即使调用了，进程实际能碰到什么”。完整隔离至少要分别考虑：

- 文件系统根目录、只读挂载和 Symlink Escape；
- 网络 Egress、私网、DNS Rebinding 和 Redirect 逐跳校验；
- 进程、系统调用、容器权限和宿主 Socket；
- CPU、内存、磁盘、进程数和执行时间；
- 环境变量、云凭据、MCP 子进程继承的 Secret；
- 产物上传和最终业务写回权限。

[OpenHands 官方仓库](https://github.com/OpenHands/OpenHands)在无 Sandbox 启动方式中明确警告 Agent 会获得本机文件系统的完整访问，并把 Docker 方式单列为隔离选项。这说明“Agent 有权限提示框”与“进程被隔离”是两件事。

[Craft Agents 官方源码](https://github.com/craft-ai-agents/craft-agents-oss/tree/4289b16097322e9911d3078d8a64bd8c830717c3/packages/session-tools-core/src/runtime)提供路径与环境清洗以及多平台 Sandbox Backend，但 Backend 可能报告 unavailable；[`permission-manager.ts`](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/shared/src/agent/core/permission-manager.ts)仍是应用层预检查。二者不能互相冒充。

### 3.3 人工审批的正确语义

MCP Tool 规范建议敏感操作保留用户确认能力。一个可审计 Approval 至少应绑定：

```text
actor + tenant/workspace + tool/action
+ normalized_arguments_digest
+ target_resource + side_effect_level
+ policy_version + expiration
+ run/attempt identity
```

否则用户批准的是“发一封邮件”，执行时却可能变成另一收件人或另一正文，形成审批后的参数替换。参数、目标、能力或策略发生实质变化时，应重新审批。

适合自动放行的通常是低副作用、只读、范围固定且可审计的操作；外发消息、删除、覆盖、支付、授权变更和跨租户读取应进入 Waiting/Approval。人工确认也不是免检：Host 仍要做 ACL、版本冲突和路径校验。

## 4. Durable Execution、Checkpoint 与未知结果

### 4.1 Temporal 解决的是持久控制流

[Temporal Workflow Execution](https://docs.temporal.io/workflow-execution)把 Workflow Execution 建模为可持久恢复的执行；[Activities](https://docs.temporal.io/activities)承载可能失败或产生外部副作用的操作。Workflow 代码需要满足确定性重放约束，外部世界交互通常放入 Activity。

适合引入 Temporal 的信号包括：

- 跨小时或跨天等待外部 Signal；
- 补偿分支显著增多；
- 需要大规模可靠 Timer；
- 自研状态机的恢复、版本和可观测成本已成为主要负担。

它不提供 Skill 语义、模型质量、Workspace ACL、MCP 信任或业务写回规则。把现有 Job 表迁到 Temporal 也不会自动消除重复副作用。

### 4.2 LangGraph 解决的是状态图和 Agent 恢复

[LangGraph Durable Execution](https://docs.langchain.com/oss/python/langgraph/durable-execution)通过 Checkpointer 保存执行状态，并要求工作流保持确定性、把副作用和非确定操作放入可重试 Task，同时保证幂等；[Interrupts](https://docs.langchain.com/oss/python/langgraph/interrupts)支持暂停并在外部输入后恢复。

LangGraph 适合：

- Python 内部有条件边、循环和 Agent 状态；
- 需要节点级 Checkpoint、Interrupt 和时间旅行；
- 状态图本身是主要复杂度。

NoteWeave 若将来采用它，应把它放在 Worker Runtime 内部，继续让 Java Host 持有 Workspace 权限、Job/Version 真源、Quota、Outbox、内部认证和最终 Writeback。否则框架 Checkpoint 会错误地取代业务真源。

### 4.3 Idempotency 与 Unknown Outcome

[AWS Builders' Library《Making retries safe with idempotent APIs》](https://aws.amazon.com/builders-library/making-retries-safe-with-idempotent-APIs/)建议由调用方提供稳定请求标识，使服务端能识别同一意图的重试；[EC2 API Idempotency](https://docs.aws.amazon.com/ec2/latest/devguide/ec2-api-idempotency.html)进一步规定，同一 Client Token 若携带不同参数应返回不匹配错误。

典型未知结果窗口：

```text
Worker -> Provider: execute(request_id=R)
Provider: 副作用已经提交
Provider -> Worker: 200 OK
网络: 响应丢失
Worker: timeout，不知道 Provider 是否成功
```

此时盲目重试可能重复发消息、重复扣费或重复生成对象。正确的恢复优先级是：

1. 使用相同 Idempotency Key 查询或重试；
2. Provider 支持结果查询时先查状态；
3. 本地保存请求摘要，拒绝同 Key 不同内容；
4. 终态回调使用 Receipt 去重；
5. Lease/Fencing 阻止旧 Worker 覆盖新 Owner；
6. 将“业务已成功”和“成功回执尚未送达”分成两个状态。

`[当前实现]` [`WorkerTaskCallbackService.java`](../../backend/src/main/java/com/noteweave/worker/WorkerTaskCallbackService.java)要求 Complete/Fail 终态回调携带 Idempotency Key，保存 Callback Type 与 Payload Digest；同 Key 不同内容发生冲突，重复内容返回既有结果。它证明 Host 回调幂等边界，不证明所有外部 Provider 都支持幂等执行。

### 4.4 Exactly Once 应如何回答

面试官问“Kafka 重投时能否 Exactly Once”，建议回答：

> 我不宣称跨 Kafka、Worker、Provider、对象存储和 MySQL 的端到端 Exactly Once。消息可能 At-least-once，系统通过幂等键、状态条件更新、Callback Receipt、不可变 Version 和 Delivery Fencing 实现 Effectively Once 的业务效果。对不支持幂等查询的外部副作用，未知结果仍要进入人工对账或专门补偿，不能靠重试掩盖。

## 5. Quota、并发与公平

### 5.1 三类控制解决不同问题

[RFC 2697](https://datatracker.ietf.org/doc/html/rfc2697)给出了 Single Rate Three Color Marker 的令牌桶计量模型。工程上可迁移为：令牌按速率补充，桶容量允许有限突发，调用消耗令牌。

| 控制 | 主要限制 | 不能替代 |
| --- | --- | --- |
| Rate Limit / Token Bucket | 单位时间到达量 | 长任务同时在途量 |
| Concurrency Lease | 昂贵任务同时运行数 | 短时间突发与长期公平 |
| Queue/Fair Scheduler | 谁先获得稀缺资源 | Provider 真实硬上限 |

`[当前实现]` [`WorkloadQuotaService.java`](../../backend/src/main/java/com/noteweave/quota/WorkloadQuotaService.java)用 Redis Lua 维护令牌桶和带 TTL 的 ZSET Lease；Rate Key 包含 Workspace、Actor Fingerprint 和 Workload，Concurrency Key 包含 Workspace 和 Workload。TTL 能回收崩溃持有者，但默认阈值只是保护配置，不是实测容量或 SLA。

### 5.2 为什么仍可能不公平

即使每个 Workspace 有并发上限，也可能出现：

- 大租户持续占满自己的槽位，小租户排队时间仍很长；
- 长任务 Head-of-line Blocking；
- 一个 Actor 用多个身份绕过速率分区；
- 高成本 Tool 与低成本 Tool 共用一个令牌；
- Retry 流量挤占新请求；
- Waiting 任务是否持有 Lease 的语义不清。

公平需要明确调度目标，例如按 Workspace 加权轮转、按 Workload 分池、限制单任务 Cost Weight、为重试设置独立预算，并监控：

```text
tenant_share_i = served_cost_i / sum(served_cost)
normalized_share_i = tenant_share_i / configured_weight_i
starvation_rate = tasks_waiting_over_SLO / admitted_tasks
```

这些是评估公式，不是 NoteWeave 当前生产结果。没有真实流量分布和排队数据时，不能说“实现了严格公平调度”。

## 6. 框架与产品的适用边界

| 方案 | 一手定位与强项 | 适合 NoteWeave 的条件 | 不能替代的边界 |
| --- | --- | --- | --- |
| [LangGraph](https://github.com/langchain-ai/langgraph) | 低层状态化 Agent 编排；Checkpoint、Durable Execution、Interrupt | Worker 内条件图、循环、人工暂停成为主要复杂度 | Java ACL、业务 Job/Version、Quota、Writeback |
| [Temporal](https://docs.temporal.io/) | Durable Workflow、Activity、Timer、Signal、Retry | 跨天等待、补偿、Timer 和恢复规模显著增长 | Agent 规划、Skill、模型质量、Tool 信任 |
| [AutoGen](https://github.com/microsoft/autogen) | AgentChat、事件驱动 Core、多 Agent 对话和工具扩展 | 验证多 Agent 协作、消息协议或实验型 Team | 生产鉴权和业务事务；官方仓库现已标记 Maintenance Mode，并建议新项目使用 Microsoft Agent Framework |
| [CrewAI Crews](https://docs.crewai.com/en/concepts/crews) | 角色化 Agent 协作与任务委派 | 问题天然需要多个角色协商 | 确定性恢复、隔离和权限不是角色 Prompt 自动提供的 |
| [CrewAI Flows](https://docs.crewai.com/en/concepts/flows) | 事件驱动、状态化、可控流程 | 既要 Crew 自主性又要外层确定流程 | 仍需自有 ACL、幂等、预算和业务终态 |
| [DeerFlow 2.0](https://github.com/bytedance/deer-flow) | 官方定位为含 Skills、Sub-agents、Memory 和 Sandbox 的 Super Agent Harness | 通用深任务、技能扩展和子 Agent 运行时参考 | 2.0 是与 1.x 不共享代码的重写；不能用 1.x Research 结论证明 2.0，也不能把 Harness 当业务事务引擎 |
| [Craft Agents OSS](https://github.com/craft-ai-agents/craft-agents-oss) | 桌面/远程 Agent 工作区、Session、Source、Skill、权限模式、自动化 | 学习 Pre-tool Policy、Session 持久化和多 Backend 边界 | 通用工作界面不是 Evidence/Artifact Verifier，也不是强 Sandbox |

选择原则不是“Star 多就引入”，而是观察当前最昂贵的复杂度落在哪一层：

- 图状态和 Interrupt 复杂，优先评估 LangGraph；
- 跨服务长事务和 Timer 复杂，优先评估 Temporal；
- 多 Agent 消息协作是产品核心，才评估 AutoGen/CrewAI；
- 通用技能与受控桌面执行是目标，参考 DeerFlow/Craft；
- 当前只有线性节点和少量 Waiting 时，自有状态机更少依赖、更易验证。

## 7. 外部项目如何宣传数据

### 7.1 Task Success：Benchmark 分数

[SWE-bench](https://arxiv.org/abs/2310.06770)用真实 GitHub Issue 和仓库测试判断 Patch 是否解决问题；原论文包含 2,294 个 Python 软件任务。当时最佳模型只解决 1.96%，但这个历史数字不能与后续 Verified 子集、不同 Harness、不同模型和不同时间的榜单直接比较。

[$\tau$-bench](https://arxiv.org/abs/2406.12045)不只检查一次 Tool Call，而是比较对话结束后的数据库状态和标注目标状态，并用 `pass^k` 衡量同一策略多次运行都成功的可靠性。论文报告当时先进 Function Calling Agent 的任务成功率仍低于 50%，Retail 场景 `pass^8` 低于 25%。这两个数字属于论文给定模型、领域、工具和版本，不能迁移成 NoteWeave 基线。

关键面试知识点：

- `pass@k` 常表示 k 次中至少一次成功；
- `$\tau$-bench` 的 `pass^k` 强调 k 次都成功；
- Agent 产品面向重复业务时，后者更接近可靠性要求；
- Task Success 必须由外部终态 Oracle 判定，不能让同一个生成模型自评。

### 7.2 Tool Success：不要把 HTTP 200 当任务成功

工具层至少拆成：

```text
schema_accept_rate = schema_valid_calls / proposed_tool_calls
tool_execution_success = expected_domain_outcomes / attempted_tool_calls
task_success = tasks_meeting_terminal_oracle / eligible_tasks
duplicate_effect_rate = duplicated_side_effects / side_effecting_requests
```

`Tool execution success` 仍可能高于 `Task success`：每次 API 都返回 200，但 Agent 选错 Tool、顺序错误、违反政策或漏掉最后一步，任务仍失败。`$\tau$-bench` 使用最终数据库状态而不是只看调用响应，正是为了避免这一误判。

### 7.3 Latency 与业务结果

[Klarna 官方新闻稿](https://www.klarna.com/international/press/klarna-ai-assistant-handles-two-thirds-of-customer-service-chats-in-its-first-month/)使用“首月处理 230 万次对话、承担约三分之二客服聊天、平均解决时长从 11 分钟降到 2 分钟、重复咨询下降 25%”宣传其 AI Assistant，并给出相当于 700 名全职员工工作量和 2024 年利润改善预测。

这些是公司自报的产品业务指标，不是可复现实验：

- “解决时长”不是模型单次推理延迟；
- “承担对话”不是成功解决；
- “相当于员工数”依赖排班、任务定义和反事实假设；
- 利润改善预测不是已审计的单位任务成本。

NoteWeave 若宣传延迟，应分别报告 Queue、Waiting、Active Execution 和 End-to-end 的 P50/P95；若宣传业务节省，应给人工基线、相同任务范围、统计周期和异常处理成本。

### 7.4 Cost

框架通常不公开统一的“每成功任务成本”，因为它取决于模型、Token、Tool、重试、缓存、失败任务和基础设施。更可比的公式是：

```text
cost_per_attempt = total_model_tool_infra_cost / attempted_tasks
cost_per_success = total_model_tool_infra_cost / successful_tasks
wasted_cost_rate = cost_of_failed_cancelled_duplicate_runs / total_cost
```

必须同时报告 Attempt 和 Success 口径。只报告成功任务消耗会隐藏失败成本；只按 Token 价格估算会漏掉搜索、浏览器、对象存储、队列和人工审批成本。

### 7.5 Safety

[AgentDojo](https://arxiv.org/abs/2406.13352)针对 Agent 从不可信 Tool 数据中遭遇 Prompt Injection 的场景，提供 97 个真实任务和 629 个安全测试用例。它把正常任务能力与攻击下的安全性分开评估，说明“正常任务能完成”与“遭攻击不越权”是两个维度。

建议安全指标：

```text
benign_task_utility = benign_tasks_completed / benign_tasks
attack_success_rate = attacks_achieving_forbidden_goal / attack_attempts
unauthorized_action_block_recall = blocked_unauthorized_actions / all_unauthorized_actions
false_block_rate = blocked_authorized_actions / all_authorized_actions
secret_exposure_rate = runs_exposing_seeded_secret / attacked_runs
```

只宣传“有 Sandbox”“有 Approval”或“支持 MCP OAuth”属于机制声明，不是安全效果。没有固定威胁模型、攻击集和误杀率时，不能声称“防住 Prompt Injection”。

### 7.6 Adoption

截至 2026-08-17，GitHub 官方 API 返回的仓库 Star 快照如下。数值在读取后会继续变化，只能与日期一起引用。

| 仓库 | Star 快照 | 一手数据 |
| --- | ---: | --- |
| LangGraph | 39,862 | [GitHub API](https://api.github.com/repos/langchain-ai/langgraph) |
| AutoGen | 60,470 | [GitHub API](https://api.github.com/repos/microsoft/autogen) |
| CrewAI | 57,203 | [GitHub API](https://api.github.com/repos/crewAIInc/crewAI) |
| DeerFlow | 80,154 | [GitHub API](https://api.github.com/repos/bytedance/deer-flow) |
| Craft Agents OSS | 7,069 | [GitHub API](https://api.github.com/repos/craft-ai-agents/craft-agents-oss) |
| OpenHands | 84,288 | [GitHub API](https://api.github.com/repos/OpenHands/OpenHands) |

字段语义见 [GitHub REST API, Get a Repository](https://docs.github.com/en/rest/repos/repos#get-a-repository)。

Star 只能证明开发者关注度，不能证明：

- 生产部署数量或付费客户数量；
- 任务成功率、SLA 或安全成熟度；
- 当前主分支是否适合新项目；
- 与 NoteWeave 业务的契合度。

AutoGen 高 Star 与其当前 Maintenance Mode 同时存在，就是“累计采用信号不等于当前技术路线建议”的直接例子。客户 Logo、下载量、Discord 人数和 Fork 也应按同样原则解释。

## 8. NoteWeave 可比指标与实测条件

以下全部是 `[生产待验证]` 的测量定义，不是当前成绩。

### 8.1 总体任务指标

| 指标 | 计算方式 | 代表意义 | 必须保留的失败 |
| --- | --- | --- | --- |
| Task Success Rate | 满足外部终态 Oracle 的任务数 / 全部有效任务 | 端到端完成能力 | Timeout、预算耗尽、Verifier Fail |
| Reliable Success `pass^k` | 每个任务连续 k 次均成功的比例 | 重复运行稳定性 | 任一次失败都计失败 |
| End-to-end P95 | 提交到终态的时长 P95 | 用户实际等待 | Queue 与 Waiting |
| Cost per Success | 所有 Attempt 成本 / 成功任务数 | 获得一个有效产物的真实代价 | 失败、重试、Repair 成本 |
| Recovery Rate | 故障注入后满足终态且无重复副作用的任务 / 可恢复故障任务 | 恢复机制效果 | Duplicate、Stale Callback |
| Human Escalation Rate | 需要人工处理的任务 / 全部任务 | 自动化边界 | 对账与安全审批 |

### 8.2 功能级指标

| 功能 | 指标 | 计算方式 |
| --- | --- | --- |
| Skill Compiler | Compile Acceptance / Rejection Precision | 合法计划通过率；非法能力、环、缺失输入的拒绝准确率 |
| Capability | Unauthorized Block Recall / False Block Rate | 越权阻止召回率；合法调用误杀率 |
| MCP | Schema Accept、Tool Execution、Unknown Outcome | 结构合法率、领域成功率、结果未知率分开统计 |
| Waiting/Resume | Resume Success、Resume Latency、Spec Drift | 同 Run 恢复成功率、唤醒到继续时长、是否错误重编译 |
| Callback | Duplicate Effect、Stale Acceptance | 重复回调造成重复副作用比例；旧 Delivery 被接受比例 |
| Verifier/Repair | Defect Recall、Repair Success、Regression Rate | 缺陷检出率、修复闭环率、修好一处破坏其他区域比例 |
| Writeback | Unauthorized Commit、Version Conflict Detection | 越权写回数；版本冲突检出率 |
| Quota | Rejection、Queue P95、Starvation | 保护效果与租户体验，不只看 Redis 是否扣令牌 |

### 8.3 可以横比的前提

只有同时冻结以下条件，NoteWeave 才能与自己的基线或其他方案做相对比较：

1. 相同任务清单、数据快照和终态 Oracle；
2. 相同模型、温度、Seed、Prompt/Skill/Policy 版本；
3. 相同 Tool、Provider、Sandbox、网络和资源限制；
4. 相同最大轮数、Token、时间、并发和 Repair 预算；
5. 每个任务相同运行次数，并报告随机波动或置信区间；
6. Timeout、拒绝、取消、未知结果和人工介入进入明确分母；
7. 延迟区分冷启动、缓存命中、排队、Waiting 和执行；
8. 成本记录货币、价格日期、缓存、失败运行和人工时间；
9. 安全比较使用同一威胁模型、攻击集、Secret Seed 和误杀集；
10. 保留 Trace、终态证据和失败分类，避免只报一个平均数。

### 8.4 建议的消融实验

不先填数字，先固定实验：

| 方案 | 保留能力 | 要回答的问题 |
| --- | --- | --- |
| A：单次 Prompt | 同模型、无 Skill Graph | 最小成本下的结构和稳定性基线 |
| B：固定 Workflow | 固定节点、无动态路由 | 动态编译是否真的带来收益 |
| C：受控 Skill Graph | 当前方案 | 质量、成本、恢复和安全的综合效果 |
| D：C 去掉 Verifier/Repair | 其余相同 | Repair 的净收益与回归风险 |
| E：C 去掉 Capability Gate | 仅安全测试环境 | Gate 对越权阻止和合法误杀的影响 |
| F：C 全量重试替代局部恢复 | 其余相同 | 局部恢复是否降低成本和漂移 |

学校场景的 Gold Set 应覆盖课程资料整理、长文/PDF 产物、不同来源输入、Provider 暂时不可用、重复投递、审批等待和写回冲突。任务答案和 Verifier 必须由独立规则或人工标注确定，不能用生成模型自己的打分直接当真值。

## 9. 面试官高频追问与回答边界

### 9.1 “这不就是普通 Workflow 吗？”

回答重点：普通 Workflow 的节点和路径完全硬编码；NoteWeave 将 Skill、输入 Schema、能力策略、Provider/审批状态编译为冻结执行契约，运行时仍受控。它保留受限动态性，但当前不冒充自由 Agent 或通用 DAG 引擎。

### 9.2 “有 MCP 和 JSON Schema，为什么还需要 Capability Policy？”

MCP 解决发现和调用，Schema 解决参数形状；二者不知道当前用户能否访问 Workspace、是否允许联网、目标是否跨租户、写操作是否批准。授权必须由 Host 结合身份、资源、Skill 和审批做交集决策。

### 9.3 “有 Docker 就安全吗？”

Docker 只是隔离手段之一。仍要检查挂载目录、Docker Socket、Root、Network Egress、Secret、资源限制和最终写回。应用层 Permission Prompt 也不能替代 OS Sandbox。

### 9.4 “Checkpoint 之后为什么还会重复执行？”

Checkpoint 保存控制流位置，不一定与外部副作用原子提交。进程可能在 Provider 成功后、Checkpoint 落盘前崩溃。恢复时必须依赖外部幂等键、状态查询或对账，不能只看本地节点状态。

### 9.5 “为什么不直接上 Temporal 或 LangGraph？”

当前运行期主要是线性节点，Java 已持有业务状态机、Outbox、Quota 和 Callback；直接引入会产生双状态源和迁移成本。条件图/Interrupt 成为主要复杂度时评估 LangGraph，跨天等待/Timer/补偿成为主要复杂度时评估 Temporal。

### 9.6 “限流已经按 Workspace 了，为什么还不算公平？”

租户隔离只限制上限，不决定共享资源的服务顺序。任务时长、Cost Weight、Retry 和队首阻塞仍会造成饥饿，需要排队策略和真实等待时长数据证明公平。

### 9.7 “你们成功率多少？”

没有冻结 Gold Set、模型/工具版本、运行次数和外部 Oracle 时，不报百分比。可以展示当前通过的契约与故障测试，以及完整评测设计；只有实测结果可复现后，才把 Task Success、P95、Cost per Success 或 Recovery Rate 写进简历。

## 10. 对现有 04 文档的补充判断

两篇 04 文档的工程主线已经成立，但面试时应补齐以下边界：

1. 将“Capability Union”统一解释为多层能力交集，避免 `Union` 一词被追问成权限并集；如果保留历史命名，要明确实现语义是 Intersection。
2. 当前 Java Catalog 是项目需要的 Schema 子集校验，不应声称完整 JSON Schema Engine。
3. 当前 System/Custom MCP 有注册、进程和结果校验，但不能声称任意第三方 MCP 已具备强 Sandbox 和供应链安全。
4. 当前 Callback 幂等不等于所有 Provider 副作用幂等；Unknown Outcome 必须单独回答。
5. Token Bucket + Lease 是容量保护，不等于公平队列、生产吞吐或 SLA。
6. Waiting/Resume 要区分是否释放并发 Lease、是否复用原 Spec、谁触发唤醒和 Claim 后崩溃如何恢复。
7. Framework 对比必须加入当前生命周期：AutoGen 已进入 Maintenance Mode；DeerFlow 2.0 与 1.x 不共享代码；Star 不能替代适用性判断。
8. 测试指标要把 Task、Tool、Recovery、Safety、Latency、Cost 分开，不能用“测试通过数”替代业务成功率。

## 11. 一手来源索引

### 定义、协议与可靠性

- Workflow 与 Agent：[Anthropic, Building Effective AI Agents](https://www.anthropic.com/research/building-effective-agents)
- Skill 目录规范：[Agent Skills Specification](https://agentskills.io/specification)
- JSON Schema：[JSON Schema Draft 2020-12 Core](https://json-schema.org/draft/2020-12/json-schema-core)
- MCP Tool：[MCP Tools Specification 2025-11-25](https://modelcontextprotocol.io/specification/2025-11-25/server/tools)
- MCP 授权：[MCP Authorization Specification 2025-11-25](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization)
- MCP 安全实践：[MCP Security Best Practices](https://modelcontextprotocol.io/specification/2025-11-25/basic/security_best_practices)
- Token Bucket 标准来源：[RFC 2697](https://datatracker.ietf.org/doc/html/rfc2697)
- Idempotent API：[AWS Builders' Library](https://aws.amazon.com/builders-library/making-retries-safe-with-idempotent-APIs/)
- Client Token 语义：[Amazon EC2 API Idempotency](https://docs.aws.amazon.com/ec2/latest/devguide/ec2-api-idempotency.html)

### 执行框架与产品

- LangGraph：[Official Repository](https://github.com/langchain-ai/langgraph)，[Durable Execution](https://docs.langchain.com/oss/python/langgraph/durable-execution)，[Interrupts](https://docs.langchain.com/oss/python/langgraph/interrupts)
- Temporal：[Official Documentation](https://docs.temporal.io/)，[Workflow Execution](https://docs.temporal.io/workflow-execution)，[Activities](https://docs.temporal.io/activities)
- AutoGen：[Official Repository and Maintenance Notice](https://github.com/microsoft/autogen)，[Teams](https://microsoft.github.io/autogen/stable/user-guide/agentchat-user-guide/tutorial/teams.html)，[Paper](https://arxiv.org/abs/2308.08155)
- CrewAI：[Official Repository](https://github.com/crewAIInc/crewAI)，[Crews](https://docs.crewai.com/en/concepts/crews)，[Flows](https://docs.crewai.com/en/concepts/flows)
- DeerFlow：[Official Repository](https://github.com/bytedance/deer-flow)
- Craft Agents OSS：[Official Repository](https://github.com/craft-ai-agents/craft-agents-oss/tree/4289b16097322e9911d3078d8a64bd8c830717c3)
- OpenHands：[Official Repository](https://github.com/OpenHands/OpenHands)，[Platform Paper](https://arxiv.org/abs/2407.16741)

### 评测与宣传口径

- 软件任务终态评测：[SWE-bench Paper](https://arxiv.org/abs/2310.06770)，[Official Harness](https://github.com/SWE-bench/SWE-bench)
- Tool-Agent-User 交互与 `pass^k`：[$\tau$-bench](https://arxiv.org/abs/2406.12045)
- Prompt Injection 安全评测：[AgentDojo](https://arxiv.org/abs/2406.13352)，[Official Repository](https://github.com/ethz-spylab/agentdojo)
- 厂商业务指标案例：[Klarna AI Assistant Official Press Release](https://www.klarna.com/international/press/klarna-ai-assistant-handles-two-thirds-of-customer-service-chats-in-its-first-month/)
- GitHub 采用度字段：[GitHub REST API, Get a Repository](https://docs.github.com/en/rest/repos/repos#get-a-repository)

## 12. 最终面试口径

> Agent 执行框架的价值不在于给模型无限工具，而在于把不确定决策放进确定的契约里。NoteWeave 用 Skill 和输入 Schema 描述任务，用冻结执行计划约束本次运行，用 Capability Intersection、Quota 和 Approval 控制能做什么，用 Checkpoint、Idempotency Receipt 和 Fencing 处理恢复，用 Verifier/Repair 和 Host Writeback 控制结果与副作用。当前实现仍是编译期 DAG、运行期线性节点，不宣称通用动态调度；当前测试能证明协议不变量，不等于生产成功率。外部 Benchmark、公司业务自报和 GitHub 采用度只有在口径一致时才有参考价值，真正能写进简历的数字必须来自冻结任务集、外部终态 Oracle、多次运行和可复现 Trace。
