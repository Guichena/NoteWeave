# Artifact Agent 契约级数据模型与面试官下钻

> 默认主回答见[Agent 执行与 Artifact 一体化面试手册](32-Agent执行与Artifact产物一体化面试手册.md)。本文是 Artifact 的字段和失败窗口权威附件。面试推荐设计以 Host 预分配唯一 Version ID、Kafka 快速接纳 Durable Execution、验证后副作用为准；当前双 ID 和长消费会话仅用于解释迁移原因。

> 本文是 `23-Artifact-Agent产物生成演进案例指标与Ownership答辩.md` 的第二轮审计补充。重点不是再讲一遍架构，而是把面试官会继续追问的请求、快照、结果、版本、文件、审批、失败和写回契约钉死。
>
> 标签约定：`[当前实现]` 表示能从代码、迁移或测试直接证明；`[目标设计]` 表示为了生产化还需要补的方案；`[生产待验证]` 表示代码有入口或局部证据，但缺少端到端、真实 Provider 或真实用户数据证明。

## 1. 先给面试官的结论

当前 Artifact Agent 已经不是“一次返回文本”的接口，而是一条有输入快照、异步任务、Worker 执行、Verifier、版本和文件归档的链路。但它还不是完整的生产级 Artifact 平台，主要边界如下：

1. 当前快照能复现资料范围、资料版本摘要、用户要求、Skill 输入、上游引用和 Control Pack，不能单独复现某次模型调用的全部随机性。
2. 当前输出有结构化 `result_payload` 和运行 Trace，但 Java Host 的业务版本表与 Worker 的调试仓储是两条持久化路径，不能把 Worker 仓储记录直接当成线上真源。
3. 当前审批是能力审批，不是“用户审核内容后发布”的完整人审状态机。审批请求保存在 Worker 内存队列，公开接口主要是 debug 路径。
4. `PASS` 和 `WARN` 都会进入完成回调，`FAIL` 才阻断；PDF `UNKNOWN` 或拉取失败可能表现为文件 `FAILED`，而任务仍被标记为完成。这是必须诚实解释的交付语义。
5. 当前写回到知识区是 Host 创建新的 Note/Wiki Item，没有目标 Revision 条件，也没有与对象存储形成跨系统原子事务。

## 2. 请求与执行输入契约

### 2.1 外部创建请求 `[当前实现]`

Java `CreateArtifactJobRequest` 的字段和约束如下：

| 字段 | 类型/限制 | 进入快照后的含义 |
|---|---|---|
| `skillKey` | 非空，最长 64 | 选择内置或注册 Skill；Host 先在 Catalog 校验 |
| `userRequirement` | 非空，最长 4000 | 用户目标和约束，创建时 trim 后保存 |
| `inputs` | JSON 对象 | 按 Skill 的输入 Schema 校验、归一化后保存 |
| `sourceScopeSourceIds` | 最多 50 个 ID，每个最长 36 | Host 解析为当前 `READY/PARSED/INDEXED` 的资料对象并冻结 |
| `upstreamRefs` | 最多 20 条 | 只接受 `SOURCE_SNAPSHOT` 或 `RESEARCH_REPORT`，并校验 workspace 与 revision 归属 |

请求本身没有 `model`、`provider`、`temperature`、`seed`、`promptVersion` 或人工审核策略字段。它们不能在面试中被描述为用户可配置且已被持久化的参数。

### 2.2 Worker 输入 `[当前实现]`

Worker 通过 Host 内部接口收到 `ArtifactTaskInput`：

```text
task_id, workspace_id, target_id
input_snapshot_id, replay_availability
source_scope[]
upstream_refs[]
context_snapshot
control_pack
input_payload {
  skill_key, action_key, style_profile_key, prompt_recipe_id,
  context_snapshot_id, user_requirement, generation_brief,
  inputs, requested_capabilities, writeback_mode
}
```

几个容易被问到的细节：

- `target_id` 是 Artifact Job 目标，不是某个 Source 的当前 Head。
- `source_scope` 不是只传 ID。Host 查询当前 Ready Source 的标题、摘要、sample text、Source Snapshot ID、版本号和 SHA-256，并把这些元数据放进快照对象。
- `context_snapshot` 目前主要是一个 `context_snapshot_id` 引用；它不是把全部 Memory 内容再次复制到 Artifact 快照中。
- `control_pack` 会保存风格、结构、术语、禁用模式、证据和交互策略，但不等于完整的模型 Prompt 日志。

### 2.3 快照到底冻结了什么

| 维度 | 当前行为 | 面试中的准确说法 |
|---|---|---|
| Source 身份 | 保存 `source_id`、`source_snapshot_id`、版本号和 `source_snapshot_sha256` | 能证明输入资料版本，避免读取 Workspace 当前 Head |
| 用户输入 | 保存 `user_requirement` 和归一化后的 `inputs_json` | 同一快照下可复现业务意图和 Skill 参数 |
| 上游结果 | 保存 `upstream_refs` 的类型、ID、revision ID | 能追溯 Research Report 或 Source Snapshot 的上游关系 |
| Control Pack | 保存编译后的 JSON | 能解释上下文约束来自哪个工作区策略 |
| 编译器 | 保存 `compiler_version=artifact-input-v1` | 有输入契约版本，但不是完整代码构建 SHA |
| Skill/Action/Style | 创建时只明确保存 Skill；Action、Style、Graph、Prompt 在 Worker 编译执行计划时解析 | 这些是运行时解析结果，不能说已全部写入输入快照 |
| Provider/Model | generation trace 会返回 provider/model | 能观察本次生成使用的提供方和模型，但不代表输入快照冻结了它们 |
| Prompt 内容 | Prompt Recipe 会进入 execution plan 和生成 payload | 有配方级 Trace，尚未形成独立的 prompt 内容哈希与版本表 |
| temperature/seed | 当前请求、快照和 `generation_trace` 均没有字段 | 目前不能宣称随机性可完全复现 |

### 2.4 真实的再生成语义和一个重要缺口

`regenerateVersion()` 会允许修改 `userRequirement` 和 `inputs`，但 `persistRegeneration()` 复用上一轮的 `inputSnapshotId`。而 `getWorkerInput()` 又是通过 `input_snapshot_id` 读取 `user_requirement` 和 `inputs_json`。

因此当前代码存在一个需要在面试中主动说明的风险：**再生成请求的修改值写入了 `artifact_job_run`，但 Worker 输入读取仍可能拿到旧快照中的要求和参数。** 这不是“再生成一定使用新要求”的可证明事实，应该记录为 `[当前缺口]`。

推荐修复为 `[目标设计]`：每次再生成都创建新的不可变 `ArtifactRunInputSnapshot`，包含新的要求、参数、资料快照、Control Pack、编译器版本和配置指纹；`artifact_job_run.input_snapshot_id` 只指向本轮快照。这样再生成既能改变要求，也能保留完整审计链。

### 2.5 Source Snapshot 不等于传给模型的全文 `[当前实现]`

Host 的 `loadSourceScopeItem()` 查询的是最新 `source_snapshot` 的元数据，并通过 `source_chunk -> source_window` 取第一段窗口作为 `sample_text`。Worker 的普通 Source 适配器随后使用：

```text
plain_text = sample_text 或 summary 或 title
```

所以当前普通 Workspace Source 的 Artifact 输入通常是“标题 + 摘要 + 一个样本文本窗口 + 版本 SHA”，而不是全文内容。视频、音频或带 URL 的 Skill 可能通过 Capability Provider 获取字幕/转写，再把完整结果构造成 CCO；这属于另一条采集路径。

这带来三个必须主动说明的后果：

1. `source_snapshot_sha256` 能证明资料版本身份，但不能证明本次 LLM 已经看到该版本的全部正文。
2. `Claim Support Rate` 如果只基于 sample_text 计算，不能包装成整篇 Source 的事实正确率。
3. 长文学习指南、Research 结果或跨章节产物若要依赖全文，必须增加按窗口分页读取、检索证据集或完整快照归档，并在 Trace 中记录实际送入模型的窗口 ID 和内容摘要。

推荐的 `[目标设计]` 是把 `source_snapshot_id` 解析为受控的窗口集合：快照保存窗口列表、每个窗口的 SHA-256、读取顺序和截断策略；生成请求记录实际使用窗口的 ID 集合。这样才能区分“资料版本可追溯”和“模型上下文覆盖充分”。

## 3. 结果、版本和文件契约

### 3.1 Worker 结果 `[当前实现]`

`ArtifactTaskResult` 的顶层字段是：

```text
result_type       默认 MARKDOWN
result_title
result_payload    JSON 对象
trace_summary
citations[]
job_snapshot
version_snapshot
```

`result_payload` 当前主要包含：

| 键 | 作用 |
|---|---|
| `markdown` | Host 最终落库的正文来源 |
| `execution_plan` / `execution_spec` / `runtime_plan` | Action、Graph、Prompt、Style 和节点计划 |
| `sections` / `node_traces` | 结构化章节和节点级校验/修复记录 |
| `generation_trace` | 生成模式、Provider、Model、章节数和来源数 |
| `export_trace` | `NOT_REQUIRED`、`COMPILED` 或 `UNKNOWN` 等导出状态 |
| `verification` / `output_contract_trace` | PASS、WARN、FAIL 和具体检查 |
| `evidence_coverage` | 章节证据覆盖情况 |
| `approval_trace` / `capability_union_trace` | 能力审批和权限合取结果 |
| `writeback_preview` / `writeback_request` | Host 写回预览、摘要、派发和回调信息 |
| `lifecycle_trace` | 阶段、进度和运行指标 |
| `artifact_commit` | Worker 仓储提交回执 |

Host 回调时会把 `result_payload_json` 原样保存到 `artifact_version`，再由 `ArtifactPayloadReadModelAssembler` 兼容解析为 Java `runtimeTrace`。因此 `result_payload` 是扩展载荷，`runtime_trace` 是面向读取端的稳定投影，两者不应混为一个数据库字段契约。

`[目标设计]` 不继续扩大这个 JSON，而是让 Worker 返回强类型 `ArtifactCandidateEnvelope`：固定包含 `candidate_id`、`artifact_run_id`、`execution_attempt_id`、`reserved_version_id`、`schema_version`、`content_ref/content_digest`、`content_contract_receipt_ref`、`citation_manifest_ref`、`trace_ref` 和 `completion_idempotency_key`。正文、Citation Manifest、Validator Receipt 和受控 Trace 分别按自己的 Schema 保存，Host 只把通过验证的业务字段提升为 Version；`job_snapshot`、`version_snapshot` 等 Worker 回显只能用于对账，不能覆盖 Host 真源。确需扩展的 Metadata 使用命名空间、大小上限和白名单 Schema，未知字段进入隔离而不是被 Controller、SQL 和前端逐层猜测。这样把“业务交付合同”和“调试材料”分开，也避免把 Prompt、工具参数或敏感来源片段无边界复制到用户可读版本。

这里还有两个容易被忽略的事实：

- `citations` 顶层列表当前只取 `task_input.source_scope[:3]`；更细的章节引用在 `sections[].source_refs` 和 CCO 的 `source_trace` 中。只看版本详情的顶层 citations 不能推出“所有 Claim 都有引用”。
- `generation_trace` 没有 token usage、请求耗时、重试次数、temperature、seed、Prompt 哈希或成本字段。现有进度指标主要是章节数、Source 数、节点数等计数，不是端到端时延和成本观测。

### 3.2 Artifact Version 的两个视角

| 视角 | 当前实现 | 不能过度宣称的地方 |
|---|---|---|
| Java Host 业务真源 | `artifact_version` 保存版本号、标题、Markdown、结果 JSON、Trace 摘要、引用、来源任务 | 表结构没有独立 `status`、`input_snapshot_id`、配置指纹列，需从 payload/任务关系反查 |
| Python Worker 仓储 | 内存或文件后端保存 `version_record`、`retrieval_record`、`runtime_trace`，支持 debug 查询和追加式 rollback | 它不是 Java 业务库的事务真源，不能用 Worker 仓储计数替代线上版本统计 |

Worker 的 `ArtifactVersionSnapshot.status` 初始为 `DRAFT`，而 Host 完成回调后业务 Job 状态为 `COMPLETED`。这两个状态描述的是不同边界：前者是 Worker 产物快照状态，后者是 Host 任务状态。面试时必须先说清楚状态所属对象。

还要注意版本 ID 并未统一：Worker 通过自己的 Artifact Repository 为 `target_id` 预留 `version_snapshot.version_id`，这个 ID 会进入 `result_payload.artifact_version`、`writeback_request` 和 `artifact_commit`；Java Host 的 `appendCompletedVersion()` 会再生成一个新的 UUID 作为业务库 `artifact_version.id`，没有复用 Worker ID。

因此当前 Trace 中的 Worker Version ID 与 API 返回的 Host Version ID 不保证相同，`artifact_commit.persisted_version_count` 也只是 Worker 仓储计数。推荐设计不保留两套业务 ID：Host 创建 ArtifactRun 时预分配唯一 `artifact_version_id`，放入冻结输入；Worker Repository 只按 `attempt_id` 保存临时调试记录，Candidate、文件、审批、写回和 Receipt 全部绑定 Host Version ID。映射表只用于兼容旧数据，不作为长期架构。

预分配 ID 初始保存在 ArtifactRun 的唯一 `reserved_version_id` 和 Reservation 状态中，只是内部 `RESERVED` 身份，此时还没有 `artifact_version` 业务行。内容 Contract 通过后，Host 在同一事务用该 ID 建立 Version 与全部必需文件 Manifest，并进入 `DELIVERY_PENDING`，此时不能下载、采纳或写回；所有必需文件都达到 `READY` 后，Version 才提升为用户可见的 `READY`。首轮内容产生前取消时，只终结 Run 并把 Reservation 标为 `ABANDONED`，不创建空 Version；Version 已进入交付链后取消或超过保留期，才把 Version 置为 `ABANDONED`。文件重试耗尽时进入 `DELIVERY_FAILED`，显式重试可带同一 Version ID 回到 `DELIVERY_PENDING`；每次物化使用新的 `delivery_attempt_id` 和 Staging Key，追加保存失败原因、Checksum、对象 Receipt 和时间线，不覆盖前一次 Attempt。重试只能重物化同一不可变内容；用户修改内容时必须创建新 ArtifactRun 和 Version ID。ID 不回收、不复用，只有 `READY` Version 对用户可见。

指标资格按到达阶段判断，不能简单排除 `ABANDONED`。所有已接纳 Run 都进入固定 Cohort 的严格接纳完成率，取消、失败和超期未完成分别单列；完成首轮生成并接受内容 Contract 判定的 Candidate 进入 First-pass Yield；内容 Contract 通过后冻结 Delivery Contract，其中声明的全部必需文件进入 File Ready Rate，即使物化尚未开始，或之后失败、取消、废弃，也保留在分母；只有 `READY` Version 进入 Accepted Artifact Rate 的合格分母。尚未生成 Candidate 就废弃的空预留 ID 不进入内容或文件质量分母，但不能从严格接纳完成率中消失。

### 3.3 推荐的传输与执行身份

Kafka Command 只携带 `command_id`、`artifact_run_id` 和 Schema Version。Consumer 在短事务中按 Command ID 幂等创建 `durable_execution`，提交事务后即可提交 Offset。Scheduler 再分配 Active Permit，并为 Domain Execution Lease 分配 Owner、Lease Epoch 和 Fencing Token；Permit 不共享业务 Fencing 代次。用户改变生成要求、参数或资料范围时创建新 ArtifactRun；Worker 重启、租约接管、Checkpoint 恢复或相同输入重试只新增 ExecutionAttempt，继续绑定原 Run 和预留 Version ID。这样 Kafka Offset 表示命令已持久接纳，Execution Attempt 表示谁在本代执行，Artifact Version 表示什么结果已交付，这些身份不再互相代替。

### 3.4 Artifact File `[当前实现]`

`artifact_file` 是按版本和格式唯一的文件 Manifest：

```text
file_id, artifact_version_id, file_format, file_name, media_type
storage_backend, bucket_name, object_key
size_bytes, checksum_sha256, status, error_message, created_at
```

当前 Host 一定会物化 Markdown；只有 `export_trace.status=COMPILED` 且文件名通过安全校验时，才会从 Worker 拉取 PDF。`READY` 只表示对象写入成功并记录了大小和 SHA-256，不表示跨阅读器可打开，也不表示视觉版式通过。

Host 的 `downloadPdf()` 还只检查对象非空；PDF Header、LaTeX 编译和样例文件的验证主要由 Worker 测试覆盖。因此“下载接口返回 200”与“目标 PDF 阅读器可以打开”仍不是同一个指标。

### 3.5 版本比较的真实能力 `[当前实现]`

Host 的 `compareVersions()` 将 Markdown 按行统计，再计算新增、删除和保留行数，并额外判断标题是否变化。这是可解释的轻量 Diff，不是基于章节 ID、Claim ID 或 AST 的语义 Diff。相同文本行移动位置也可能被计为未变化，段落重排和引用关系变化不会得到专门的语义标注。

因此简历可以写“提供版本比较和追加式回滚”，不能写“自动识别事实变更、证据变更和结构影响范围”。后者需要结构化 Artifact IR 和字段级 Diff。

推荐回滚语义是：用户从当前 v3 选择恢复 v1，Host 创建触发类型为 `ROLLBACK` 的新 ArtifactRun 和预留 Version ID；内容 Contract 通过后追加 v4，并记录 `restored_from_version_id=v1`，再用 Expected Head v3 做 CAS。不可变且 Checksum、权限、保留状态都有效的文件对象可以内容寻址复用，但 v4 仍要有自己的完整 Manifest；对象已删除、密钥域变化或 Validator 版本要求重验时重新物化。直接把 Current Pointer 从 v3 指回 v1 会让版本时间倒退，也会让 v2/v3 期间的写回、引用和审计难以解释，因此不作为推荐方案。

## 4. 状态机和交付语义

### 4.1 任务状态 `[当前实现]`

```text
QUEUED -> RUNNING -> FINALIZING -> COMPLETED
   |         |
   +-> WAITING_FOR_APPROVAL
   +-> WAITING_FOR_CAPABILITY
   +-> WAITING_FOR_PROVIDER
   |
   +-> FAILED
```

Host 完成回调先用条件更新把任务置为 `FINALIZING`，再追加版本，最后才置为 `COMPLETED`。失败回调只把 Artifact Job 置为 `FAILED`，具体 `phase/errorCode/errorMessage/retryable` 主要落在通用 `task` 和 `task_event` 中，而不是 Artifact Job 自己的错误列。

### 4.2 PASS、WARN、FAIL、UNKNOWN 不是一回事

| 状态 | 当前行为 | 面试官会问什么 |
|---|---|---|
| `PASS` | Contract 通过，正常完成 | 通过的是结构和规则，不是事实绝对正确 |
| `WARN` | 没有硬失败但有警告，仍可进入完成回调和版本化 | 为什么警告不阻断？哪些 Skill 允许 WARN 交付？ |
| `FAIL` | Runner 抛出 `ARTIFACT_OUTPUT_CONTRACT_FAILED`，不生成正常完成版本 | Verifier 误判怎么办？是否有重试和人工兜底？ |
| `UNKNOWN` | Provider 或 PDF 编译结果未知；当前 PDF 分支不会产生 READY PDF，但任务可能仍完成 Markdown | 任务级 SLO 的分母是否包含 UNKNOWN？下载按钮如何避免误导？ |
| `FAILED` 文件 | 对象存储写入、Worker 拉取或空载荷会将 `artifact_file.status` 置为 FAILED | 为什么 Job 可能已 COMPLETED？是否有补偿和重新物化？ |

当前实现没有一个统一的“内容可交付状态”和“所有必需文件可交付状态”联合门禁。`Job COMPLETED` 不等于 Markdown 和 PDF 全部 READY，这是一个必须写进评测口径的事实。

### 4.3 完成回调与对象存储的一致性

Java 回调流程在数据库事务中完成版本落库，并随后调用 `materializeExports()`。数据库事务不能回滚已经写入对象存储的字节；对象存储失败时，Export Service 会保存 `FAILED` 文件元数据并记录清洗后的错误消息，任务仍可能被通用 Task Service 标记为 `COMPLETED`。

Worker 内部还有一个更早的顺序窗口：`export_artifact_if_required()` 在最终 `verify_artifact_output()` 之前执行。B 站 PDF 可能已经调用 LaTeX 并生成文件，随后内容 Contract 才失败。此时 Host 不会提交成功版本，但 Worker 沙箱可能留下孤儿文件和已发生的渲染成本。

因此当前系统的真实一致性是：

```text
版本元数据：数据库事务保护
文件对象：对象存储独立写入，靠 Manifest 状态和后续补偿发现问题
两者之间：不是跨系统原子提交
```

推荐设计增加必需文件集合、Staging Key、文件级补偿队列、导出重试上限、`DELIVERY_PENDING`、`DELIVERY_FAILED` 和 `ABANDONED` 状态，以及下载接口对 Manifest 的联合检查。

推荐提交顺序是：Host 预留不可见 Version ID -> 内容生成 -> 内容 Contract -> Host 创建不可见的 `DELIVERY_PENDING` Version -> 一次性创建全部必需格式的 `PENDING` Manifest -> DeliveryAttempt 在 Staging 导出必需格式 -> 文件 Contract -> 晋升对象并将对应 Manifest 标记为 `READY` -> 所有必需文件 READY -> Version 提升为 `READY` -> 创建审批或写回 Proposal。`DELIVERY_PENDING` 不能出现在用户可下载或可写回的版本列表中。文件重试耗尽后进入 `DELIVERY_FAILED`，显式重试可以复用同一业务身份；失败 Staging 对象由 TTL 或清理任务回收，不进入用户下载路径。取消或超过保留期后，Version 进入 `ABANDONED`。

### 4.4 取消、重试、配额和预算

`[当前实现]` 通用 `TaskService.createTask()` 会把 `ARTIFACT_JOB` 映射为 `artifact` workload，执行速率检查并占用并发 Lease；Heartbeat、完成或失败会续租或释放配额。Outbox 和 Task 层也有 redrive 原语。

但 Artifact 的公开 Controller 只有创建、查询、版本、再生成、比较、回滚、写回和导出，没有用户取消接口。`regenerate` 又要求先存在一个来源版本，所以“首次运行失败且没有版本”的用户重试不能简单等同于版本再生成。Worker 的模型调用也没有持久化 token usage 或按任务预算结算。

`[目标设计]` 取消以 ArtifactRun 为对象，并带 Expected Run Version 与幂等键。Run 尚未产生内容时终结 Reservation；Version 已处于 `DELIVERY_PENDING` 或 `DELIVERY_FAILED` 时，将未完成 DeliveryAttempt 停止并把 Version 推进到 `ABANDONED`。Version 已经 `READY` 后不接受“取消生成”，用户应执行归档、删除或追加式回滚；已经完成的外部写回也不能随 Task 取消自动撤销，只能根据 Writeback Receipt 创建显式补偿 Proposal。所有终态迁移保持单调。

因此当前可以说“有通用工作负载限流和并发配额”，不能说“用户可随时取消、失败可自助恢复、费用有硬预算”。生产化至少需要：

- `CANCEL_REQUESTED -> CANCELLED` 状态、Worker 协作取消和不可取消副作用的 Receipt。
- 区分同 Attempt redrive、从 Checkpoint resume 和创建新 Run retry。
- 最大 token、最大外部调用、最大墙钟时间和最大费用的预算预留与结算。
- 取消、超时和预算耗尽时仍要处理 Provider Unknown Outcome，不能只停止轮询。

### 4.5 前端展示状态也要区分 Worker Trace 和 Host Manifest

`ArtifactVersionActions.tsx` 会把 `version.files` 的格式、状态、存储后端和大小展示给用户，但“下载 PDF”按钮当前只判断：

```text
runtime_trace.export_trace.status == COMPILED
```

它没有同时要求 `artifact_file` 中存在 `PDF/READY`。因此 Worker 已编译 PDF、但 Host 拉取或对象存储归档失败时，页面仍可能展示下载按钮，点击后才由后端再次物化或返回未就绪错误。

这说明 `export_trace` 是 Worker 执行事实，`artifact_file` 才是 Host 交付事实。生产 UI 应以 Manifest 为下载门禁，Trace 只用于解释“为什么没有文件”或“文件正在补偿”。同理，写回按钮还应结合 Verification、审批状态、目标权限和幂等状态，而不是只因为 Version 存在就允许操作。

## 5. 人工审批、内容审核和写回

### 5.1 当前审批是能力审批，不是内容发布审批

当高风险 Capability 的 Provider 不是 `APPROVED` 时，Worker 创建 `approval-{task}-{capability}-{provider}` 请求，任务进入 `WAITING_FOR_APPROVAL`。批准后更新 Provider 状态并唤醒等待任务。

但当前审批记录存放在 Python 进程内存，接口路径是 `/debug/capability-approval-requests`、`/debug/capability-approval-request-detail` 和 `/debug/approve-capability-request`。没有持久化审批人、审批时间、拒绝、过期、撤销、理由或 workspace 级 UI 状态机。

面试中应该说：这是“外部能力调用前的安全闸门原型”，不是已经完成的“教师审核 Artifact 内容后发布”流程。

### 5.2 内容安全、隐私和版权边界

当前已有的保护主要是：Workspace/Source 归属校验、工具 Allowlist、内部 Token、回调 Token、路径和文件名约束、错误消息脱敏，以及把外部内容当作不可信输入处理。

仍缺少可宣传的完整证据：

- PII 检测、脱敏和审计报告。
- 恶意提示词、版权文本过量复现和危险内容分类器。
- 图片、字幕、转写和网页的许可状态字段与阻断策略。
- Provider 返回内容的 provenance 与逐 Claim 证据绑定。

所以当前可以说“有权限和输入边界控制”，不能说“已经解决内容安全、版权和事实风险”。

### 5.3 写回契约 `[当前实现]`

Worker 侧有 `WritebackPreview`、`WritebackRequest` 和回调 Receipt，可模拟向 Host 派发写回；Host 侧的真实接口是：

- `save-as-source`：把版本 Markdown 作为新的 Generated Source 保存。
- `writeback`：按 `NOTE` 或 `WIKI` 创建新的 Knowledge Item。

`ArtifactKnowledgeWritebackRequest` 只有 `itemType` 和 `title`，没有 `expectedRevision`、目标对象 ID、幂等键或冲突策略。因此当前“写回”更准确的表述是“从 Artifact 版本创建新的知识对象”，不是对现有知识页做带版本条件的原子更新。

标准 Java `ArtifactWorkerInputPayload` 只发送 `skillKey`、`styleProfileKey`、`contextSnapshotId`、`userRequirement`、`generationBrief` 和 `inputs`，没有发送 Python 模型中的 `writeback_mode` 与 `requested_capabilities`。Python 会把缺失的 `writeback_mode` 默认为 `NONE`。因此正常 Host 创建链路下，Worker 的 Writeback Preview/Request 通常不是实际写回入口；它主要是执行框架和 debug 回调协议能力。用户真正的写回发生在版本生成后的 Host API。

另外，Runner 当前在最终 `verify_artifact_output()` 之前就调用 `register_writeback_request()`。若后续 Contract 为 `FAIL` 并抛出异常，进程内仍可能留下一个 `READY_FOR_HOST_WRITEBACK` 请求，而 Host 并没有对应的成功业务 Version。这是顺序不变量缺口。

推荐顺序是：最终 Verifier 通过 -> Host 创建不可见的 `DELIVERY_PENDING` Version 与必需文件 Manifest -> DeliveryAttempt 导出并通过文件 Contract -> 必需文件全部 READY -> Version 提升为 `READY` -> 基于该 ID 创建可审批写回 Proposal -> 校验 Expected Revision 和幂等键 -> 条件写入目标对象。任何预览可以提前计算，但可执行请求不能早于 `READY` Version，不能只以业务版本行已经创建作为放行条件。

### 5.4 删除传播与重放降级 `[当前实现]`

Source 删除时，`RunReplayRedactionService` 会查找引用该 Source 或上游 Revision 的 Artifact 输入快照。命中后会清空对应 Source 对象的 `summary` 和 `sample_text`，并把 `replay_availability` 从 `FULL` 降为 `METADATA_ONLY`。这说明系统没有为了可复现而永久保留已删除的输入正文。

但当前没有一条同等完整的 Artifact 输出级联策略，自动处理：

- 已生成 `artifact_version.content_markdown` 中包含的派生内容。
- 已物化到对象存储的 Markdown/PDF。
- 已通过 save-as-source 或 writeback 创建的新 Source、Note、Wiki。
- Worker 文件仓储和调试 Receipt 中可能残留的副本。

因此准确说法是“输入重放快照支持删除后正文脱敏和降级”，不是“Source 删除会自动清除所有派生产物”。生产化需要 lineage 图、保留策略、删除影响预览、异步清理 Receipt 和无法自动删除时的人工审核队列。

## 6. 面试官会要求补的观测指标

在已有质量指标之外，契约审计至少要补下面这些数字：

```text
Snapshot Replay Rate
= 能按 input_snapshot_id 重新构造同一输入摘要的运行次数 / 重放尝试次数

Config Reproducibility Rate
= provider、model、prompt、compiler 和生成参数均有指纹的运行次数 / 完成运行次数

Warn Delivery Rate
= verification=WARN 且被交付的版本数 / verification=WARN 版本数

Partial Delivery Rate
= Job=COMPLETED 但存在必需文件 FAILED/UNKNOWN 的版本数 / 完成版本数

Approval Wait P95
= 从 WAITING_FOR_APPROVAL 到 APPROVED 或拒绝的 P95 时长

Writeback Conflict Rate
= 因目标 Revision 不匹配而拒绝的写回次数 / 写回尝试次数

Failure Attribution Coverage
= 同时有 phase、error_code、retryable 和可关联 task_event 的失败任务数 / 失败任务数
```

当前只能直接从运行 Trace、Task Event、文件 Manifest 或 debug Receipt 计算其中一部分；没有真实运行数据时，不能填入百分比。

## 7. 面试官追问与推荐回答

### Q1：你说输入快照可复现，为什么没有 seed 和 temperature？

推荐回答：当前快照保证的是资料、用户输入、上游引用和控制包可复现，generation trace 还会记录 Provider 和 Model，但请求没有持久化 temperature、seed 和完整 prompt hash，所以它是“输入与策略可追溯”，不是“LLM 字节级确定性重放”。要达到后者，我会为每轮生成保存配置指纹、Prompt 模板版本、请求摘要、响应摘要和 Provider API 版本，并把随机性参数纳入 Snapshot。

### Q2：再生成修改要求是否真的生效？

推荐回答：这是当前实现需要修正的地方。再生成记录了新要求，但复用了旧 `input_snapshot_id`，Worker 输入读取可能仍拿旧值。正确做法是每轮生成新建不可变输入快照，并让 Run 只引用自己的快照。这个问题不能用“前端传了新参数”来掩盖。

### Q3：WARN 为什么可以交付？

推荐回答：Verifier 的 WARN 表示非硬性规则或证据提示，不等于失败；当前统一回调会完成版本。生产上应该由 Skill 的风险等级决定 WARN 是否允许交付，例如学习指南可以带警告交付，考试答案或对外发布稿则应升级为人工审核或阻断。现在这条策略还没有形成统一的 Host 级门禁。

### Q4：PDF 失败时 Job 为什么还是完成？

推荐回答：这是当前状态语义的限制。当前 Job 完成主要表示内容回调已经提交，PDF 从 Worker 拉取并写对象存储失败时会落 `artifact_file=FAILED`，因此不能把该状态解释为产物完整交付。推荐模型里 Job 只是长期用户意图，不设置一次生成的成功分母；ArtifactRun 在内容通过后创建 `DELIVERY_PENDING` Version 与完整 Manifest，所有必需文件 `READY` 后才成功。PDF 失败时 Run 保持交付失败或可重试状态，不能提升为严格完成。

### Q5：审批是不是有 UI？

推荐回答：当前有能力审批队列、等待状态和批准后唤醒逻辑，但请求保存在 Worker 内存，公开入口仍是 debug 接口，没有持久化审批审计和内容发布审核 UI。它证明了状态机原语，不能包装成完整的人审产品。

### Q6：回滚能不能撤销已经写回的 Wiki？

推荐回答：Artifact 内部回滚是追加新版本，不会删除旧版本。当前 Host 写回是创建新的 Note/Wiki Item，且没有目标 Revision 条件，所以内部回滚不会自动撤销外部对象。若要支持撤销，必须保存写回对象 ID、期望版本、反向操作和冲突策略。

## 8. 本轮审计后的补全优先级

| 优先级 | 缺口 | 完成证据 | 面试/宣传解锁 |
|---|---|---|---|
| P0 | 每轮再生成创建新输入快照 | 新快照 ID、要求/参数回放测试 | 可以可信回答“再生成如何生效” |
| P0 | 完成配置指纹 | Provider、Model、Prompt、Compiler、temperature、seed 的 Trace 和哈希 | 可以宣传可重放，而不是只说可追溯 |
| P0 | Host 预分配唯一 Version ID | Worker 只返回 Candidate，旧 ID 建兼容映射 | 文件、写回和审计使用同一身份 |
| P0 | Kafka 快速接纳 Durable Execution | Consumer 登记后提交 Offset，Scheduler 独立领取 | 长任务不再阻塞分区和 Rebalance |
| P0 | 统一 Job 与文件交付状态 | Staging、文件集合门禁、补偿任务、部分成功状态 | 可以给出端到端交付成功率 |
| P1 | 审批持久化与拒绝/过期 | 审批表、操作者、理由、UI/API、审计事件 | 可以回答人审和合规追问 |
| P1 | Verifier WARN 策略按 Skill 配置 | 风险分级、阻断矩阵、人工审核 Gold | 可以解释为什么某类 WARN 可交付 |
| P1 | 写回带 Revision 和幂等 | expected revision、目标对象、冲突测试 | 可以回答并发编辑和重复回调 |
| P2 | PII、版权和 provenance | 分类器评测、阻断样本、授权字段 | 才能谨慎宣传安全和来源治理 |

## 9. 这轮审计后的简历边界

现在可以写：

> 为 Artifact Agent 定义并实现请求、输入快照、结构化结果、版本、文件 Manifest 和运行 Trace 契约，明确区分内容质量、文件交付、能力审批与 Host 写回边界；通过 PASS/WARN/FAIL、等待状态、回调幂等和 SHA-256 归档支持可追溯交付。

现在不能写：

- 每次生成都能在字节级完全重放。
- 生成结果经过人工审核后才发布。
- Job COMPLETED 就代表 Markdown、PDF 等所有文件均可下载。
- 写回已有 Note/Wiki 时具备乐观锁和冲突解决。
- 已经具备完整 PII、版权和内容安全审核。

这些不是措辞保守，而是当前代码和测试能否证明的边界。
