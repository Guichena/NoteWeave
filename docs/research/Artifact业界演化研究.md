# Artifact 业界演化研究与 NoteWeave 推导

> 本文聚焦可靠执行与状态模型。公司产品宣传、Presentation Benchmark、视觉和可编辑性指标见 [Artifact 产物生成产品宣传与评测口径](./Artifact产物生成产品宣传与评测口径.md)。

> `[行业参考]` 本文用于解释外部方案的问题、保证边界与迁移代价。文中的 NoteWeave 推导属于 `[目标设计]`，不能证明当前实现、生产效果或真实业务收益。

> 本文不是 NoteWeave 的 Git 历史，也不是把某个工作流框架直接复制进项目。行业事实来自官方文档或规范；NoteWeave 部分是根据当前 Skill、Job、Run、Version、Task、Worker 和 Writeback 约束推导出的合理路线。

## 1. 先给结论：Artifact 是“受控副作用的可恢复产物流水线”

产物生成不应被建模成一次模型调用，而应拆成以下边界：

```text
用户目标与资料范围
  -> Skill catalog / 输入校验
  -> Job / Run / input snapshot
  -> acquire / execute
  -> verify / repair / wait
  -> 不可变 Artifact Version
  -> compare / export / rollback / writeback
```

这里有三种事实：

1. Skill 是系统允许执行的能力与权限边界。
2. Run 是可恢复的执行状态，记录输入、节点、租约、预算和错误。
3. Version 是可比较、可回滚、可审计的产物，不等于某个临时文件路径。

## 2. 行业事实与可迁移抽象

### 2.1 Temporal：把失败当作正常控制流

[Temporal 官方文档](https://docs.temporal.io/)将 Workflow 描述为可在进程崩溃、网络故障或基础设施中断后继续的持久执行；Activity 用于不可靠的外部调用，并提供自动重试、Task Queue、Signal 和 Timer。它的关键不是“永不失败”，而是把工作流状态和恢复位置保存为一等对象。

**对 NoteWeave 的推论：** Artifact 的 waiting、retry、cancel 和 resume 必须是持久化状态，不能依赖 Python 进程中的协程。当前已有 Task/Outbox/Checkpoint/Callback 契约，可先保留控制面；只有跨天等待、复杂 Signal 或大量编排需求明显超过现有状态机时，才评估引入 Temporal。

### 2.2 LangGraph：副作用、幂等和人机暂停

[LangGraph Durable Execution](https://docs.langchain.com/oss/python/langgraph/durable-execution)要求把不可逆副作用放到可重试边界之外，或为副作用设计幂等键；运行状态由 checkpointer 持久化，`interrupt` 可以暂停等待外部输入后恢复。[Persistence](https://docs.langchain.com/oss/python/langgraph/persistence)还将 checkpoint 用于人机协作、时间旅行和故障恢复。

**对 NoteWeave 的推论：** 文件写入、外部 API、PDF 导出和 Source writeback 不能和模型推理混在同一个不可区分的函数里。每个节点应有稳定 task key、输入 digest、输出 manifest 和 retry policy；人工批准恢复的是同一 Run 的新 revision，而不是重新创建一个无关联的任务。

### 2.3 Prefect：Task/Flow/State 是不同层次

[Prefect Tasks](https://docs.prefect.io/v3/concepts/tasks)把 task 定义成可缓存、可重试、可并发、带状态记录的逻辑步骤，并建议每个 task 表示一个有意义的工作或副作用。[Prefect Flows](https://docs.prefect.io/v3/concepts/flows)组合任务并拥有独立的 flow run。[Prefect States](https://docs.prefect.io/v3/concepts/states)区分 Scheduled、Running、Retrying、Completed、Failed、Crashed、Cancelled 等状态，状态本身驱动编排逻辑。

**对 NoteWeave 的推论：** `artifact_job`、`artifact_job_run`、`artifact_file` 不应混成一张万能表；waiting、verify_failed、repairing 和 dead-letter 必须是可查询状态。缓存只复用相同 Skill、输入快照和 provider 版本的安全结果，不能用语义相似替代版本条件。

### 2.4 Dagster：物化结果与触发器分开

[Dagster Sensors](https://docs.dagster.io/concepts/partitions-schedules-sensors/sensors)通过外部状态产生 `RunRequest`，也可以监听运行失败等状态。传感器触发一次运行并不代表资产已经物化成功，资产结果仍需独立记录。

**对 NoteWeave 的推论：** 文件到达、人工批准、上游 Source version 完成可以触发 Artifact Job，但触发事件不能直接写成产物成功。`artifact_file` 应保存 digest、大小、MIME、storage key 和验证结果，便于重建、比较和对账。

### 2.5 MCP：工具协议不是权限与产品语义

[MCP Tools 规范](https://modelcontextprotocol.io/specification/2025-06-18/server/tools)规定工具输入校验、访问控制、限流、输出清洗和错误区分，并建议敏感调用保留用户拒绝能力。[MCP Authorization](https://modelcontextprotocol.io/specification/2025-06-18/basic/authorization)规定受保护服务器校验 OAuth 令牌的受众、范围和有效期；[MCP Specification 的安全原则](https://modelcontextprotocol.io/specification/2025-11-25)要求显式同意、最小权限、清楚展示工具行为和审计。

**对 NoteWeave 的推论：** MCP 只能作为 Skill 的适配器，不能直接成为用户请求的任意 Action。Skill catalog 需要维护 allowlist、输入 schema、scope、预算、审批级别、超时和审计字段；写文件、发消息、修改外部系统等高副作用工具应进入 waiting 或人工批准。

### 2.6 OpenHands：执行环境与控制面分离

[OpenHands SDK 架构](https://docs.openhands.dev/sdk/arch/overview)将 agent、tool、workspace、event 和 security policy 分成可组合组件，并强调无状态组件、类型化模型和多用户隔离；Agent Server 通过 API/WebSocket 提供隔离的执行会话。

**对 NoteWeave 的推论：** Python Worker 只拿到一个受限执行上下文，不持有主库写权限。Java 控制面负责 workspace ACL、Run 状态、租约、版本检查和写回；事件流用于展示，不能成为唯一状态源。

## 3. 关键技术名词

| 术语 | 含义 | NoteWeave 落点 |
| --- | --- | --- |
| Skill catalog | 可执行能力的名称、版本、输入 schema 和权限清单 | `ArtifactSkillController` 与系统 Skill registry |
| Job | 用户的一次产物意图 | `artifact_job` |
| Artifact Run | 一次不可变的生成、再生成或追加式回滚请求 | `artifact_job_run`、Trigger Type、Input Snapshot、预留 Version ID |
| Execution Attempt | 基础设施执行、接管与恢复代际 | Task、Checkpoint、Lease、Epoch、Fencing |
| Input snapshot | 本次运行固定的用户要求、Source version、配置和 provider | `artifact_run_input_snapshot` |
| Activity/Task | 一个可观察、可重试或有副作用的步骤 | acquire、execute、verify、repair、export |
| Waiting | 等待用户、外部回调或资源后可继续的状态 | waiting state + lifecycle endpoint |
| Idempotency key | 重复投递不产生重复副作用的稳定身份 | task key、callback receipt、version key |
| Fencing | 旧 worker 即使晚到也不能覆盖新 owner 的凭证 | lease + fencing token |
| Artifact Version | 一次不可变、可比较和可回滚的产物 | `artifact_version` |
| Writeback | 将产物保存为 Source/Note/Wiki 的受控提交 | owner、ACL、版本条件和审计 |

## 4. NoteWeave 的 A-G 推导演化路线

### 阶段 A：能力目录

先只允许系统注册的 Skill，定义输入 schema、输出类型、provider 开关、副作用级别和验证器。此阶段解决“能做什么”和“谁批准了能力”，不追求任意工具扩展。

### 阶段 B：Job、Run 和不可变 Version

Job 表示用户意图，Artifact Run 表示一次明确的生成、再生成或追加式回滚请求，Execution Attempt 表示基础设施执行代际，Version 表示产物快照。用户改变要求、主动再生成或回滚时创建新 Run 和 Version ID；Worker 接管与同输入恢复只增加 Attempt。错误、取消和超时保留在 Attempt 历史，不修改旧 Version。

### 阶段 C：输入快照与异步执行

把用户指令、引用 source/version、模板、参数和 Skill 版本固化为 input snapshot。通过 Task/Outbox 投递给 Worker，HTTP 只负责受理和查询。

### 阶段 D：Skill Graph 与节点状态

将 acquire、execute、verify、repair、wait 组织为有依赖的图。每个节点声明输入、输出、预算、超时、重试条件和副作用；节点完成后写 checkpoint，恢复时从最近安全边界继续。

### 阶段 E：验证与修复

验证文件是否存在、格式是否正确、结构是否完整、引用是否可追溯、内容是否满足 Skill 规则。失败进入有限次数的 repair；同一错误反复出现时进入 dead-letter，留下 error code、阶段、证据和 redrive 入口。

### 阶段 F：文件、版本和回滚

文件元数据与对象存储分离，保存 digest、大小、MIME、storage key、来源节点和生成时间。compare 比较两个 Version 的 manifest；rollback 生成目标版本的新写回意图，不删除历史。

### 阶段 G：受控扩展

当 ACL、审批、版本、审计和验证稳定后，才允许系统 Skill 通过 MCP/外部 provider 扩展。扩展必须经过 schema、allowlist、scope、预算、超时和人工确认；模型可以提出计划或 repair，不得自行发布新的可执行 Skill。

## 5. 取舍总结

- 不直接采用 Temporal：当前已有控制面状态机和消息契约，先减少基础设施迁移；未来复杂 Signal、长时间等待和跨服务回放需求增加时再评估。
- 不直接采用纯 DAG：DAG 只表达依赖，不能代替 ACL、版本、审批和写回条件。
- 不直接让对象存储成为真源：对象 key 无法独立表达 Job/Run/Version、权限和比较关系。
- 不直接开放任意 MCP：协议解决发现和调用，不解决产品级权限、预算、版本和副作用治理。
- 不直接做无限自动修复：修复必须有上限、验证和 dead-letter，否则失败会变成不可解释的循环副作用。

## 6. 评估与事实边界

Artifact 应分别评估：Skill 输入校验、节点恢复、文件正确性、验证召回、版本比较、回滚安全、writeback ACL 和重复回调。没有真实 provider、容量压测或恢复演练时，只能说明契约和机制，不能宣称生成质量、吞吐或成功率。
