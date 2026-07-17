# Research Agent MA4D：Role Executor、TaskSnapshot 与灰度执行方案

> 状态：IMPLEMENTED / VERIFIED（fake-provider 受控范围；真实 Provider 证据待 MA5，2026-07-17）  
> 前置：MA4A–C transport、lease/cancel、DLQ、Redis permit、预算结算  
> 目标：将受控 agent task 从 transport 演练升级为真实、可灰度的 Search→Fetch→Read→Extract execution

## 1. 不可跳过的边界

当前 Kafka command 仅携带 identity；claim 只返回 target cells/budget，不能安全驱动外部工具。MA4D 必须由 Backend claim 返回版本化 `TaskSnapshot`，包括服务端确认的 workspace、role、plan/entity-set/branch、target cell version、query/source policy、provider profile、预算和 snapshot digest。command、Worker 环境变量和 LLM 输出均不能扩大该范围。

每个 execution 由 `RoleExecutorFactory` 新建隔离的 LLM client、Toolbox、trace、cancel token 和 usage accumulator；禁止复用可变 Agent/ToolManager。初期只启用 `DEEP_CELL`，bundle 内严格顺序 Search→Fetch→Read→Extract，bundle 之间才允许并行。

## 2. 契约与状态

`research-agent-task-snapshot.v1` 必须在 claim 后取得，且 response 带 `lease_epoch/fencing_token/snapshot_digest`。Worker result 扩展为 candidate/evidence submission：每个 candidate 绑定 exact cell、base version、EvidenceCard/source lineage、usage 和 sanitized trace digest。Worker 永远不直接写 canonical cell；结果先 append-only，再 verifier/CAS merge。

cancel/lease lost/rate limit/budget exhausted 都必须中断工具调用并提交可解释终态或让 lease 过期；不得把部分工具失败伪装为支持性证据。

## 3. TDD 红灯清单

1. executor factory 两次创建得到不同 toolbox/usage/trace 对象；一个 execution 的取消不影响另一个。
2. snapshot 缺 role/source policy/provider profile/digest 或 lease 不匹配时，Worker 在任何外网调用前拒绝。
3. 工具 allowlist 外调用、URL SSRF/injection guard、budget/permit 拒绝均不产生 candidate。
4. 同一 bundle 的工具调用顺序可重放；不同 bundle 只共享 immutable snapshot。
5. candidate/evidence result 的 task/cell/base-version/fencing 不一致被 Backend 拒绝；重复 result 幂等。
6. canary 仅允许 `INCREMENTAL_V1 + workspace/run allowlist + 1 worker`；2 worker 前须完成真实 provider 顺序/并行 A/B。

## 4. 灰度放行

先以 fake provider 做 Compose 两 worker crash/lease/retry 测试；再用显式 provider credential、小预算、单 workspace/run canary。至少四次同条件顺序/并行 rollout，报告 exact-table/citation、p95、成本、429、stale merge。质量退化、错误 VERIFIED、预算不守恒或安全拒绝异常时自动停新 distributed task 并回退。

## 5. 实施记录（进行中，2026-07-15）

* Worker 已有严格 `research-agent-task-snapshot.v1` Pydantic contract 与 role executor factory；factory 为每次 execution 隔离 toolbox、usage 和 cancel token，工具 allowlist 由代码强制。
* V044 为 Backend task 增加 `target_bindings_json`、`execution_context_json`、schema version 与 digest。旧 transport task 不会自动获得 MA4D 权限。
* 已完成 MA4D-1：版本化 create/claim DTO、Backend canonical digest 生成、Worker client snapshot hydration，以及在 executor 前的 task/lease/fencing/digest fail-closed 校验；旧 transport task 没有 snapshot 会被拒绝，不能被误当成 MA4D task 执行。
* 已完成 MA4D-2A：`DEEP_CELL` 使用现有 Search/Fetch/Read/Extract adapters 进行严格顺序调用；每阶段均先经过 Backend permit boundary，未授权 external 时强制 workspace-only adapter；默认 executor 开关关闭。没有 evidence ingestion path 时，抽取结果只以 `NO_EVIDENCE_INGESTION_PATH` 终止并提交 usage/trace，绝不伪造 candidate。
* 已完成 MA4D-2B：版本化 task claim 将所有 target cell 绑定到同一 lease/fencing；submit、lease reaper、cancel 会释放 active cell binding。workspace-only evidence append 对当前 lease、task persisted source scope、source id、逐字 quote 和 idempotency 做 Backend 验证。DEEP_CELL 只回传 `WORKSPACE` read window card，并仅为 snapshot target 上、`SUPPORTS`、base-version 匹配的 card 形成 candidate。Worker 不提交 merge verdict；Backend 根据持久化 evidence 的 quote/relation/value 计算最小结构化 verdict 后调用既有 CAS merge。`ResearchAgentCandidateIngressServiceTest` 的 evidence grounding 拒绝与 evidence→candidate→CAS 接受两个场景均通过。
* MA4D-3 已由 MA4F 的隔离 R1–R4 双 Worker fixture 完成；真实 provider 仍不得在无凭证和 A/B 归档时宣称完成。

## 6. 2026-07-15：TaskSnapshot 后端子阶段（MA4D-1）

本子阶段只打通可信快照下发与拒绝式校验，**不**接入真实 Search/Fetch/Read provider，也不改变默认执行模式。

1. `CreateTask` 保留既有 `target_cells: List<String>` 以兼容 MA3/MA4A transport task；只有显式提供同一组 versioned `target_bindings`（`cell_id`、`expected_version`）以及 `execution_context`（`provider_key`、非空 `source_policy`、非空 `query_policy`）的任务，才写入 V044 scope，标记为 snapshot-ready。
2. Backend 仅在 claim 成功后构造 snapshot：从 task/run 持久化字段取得 workspace、role、entity/branch、plan/entity-set 版本、预算与 policies，并填入本次 lease epoch/fencing token；重放同一 lease 返回字节相同的 JSON/digest，重试后新 lease 生成新 digest。
3. Digest 对不含 `snapshot_digest` 的 canonical JSON（递归 key 排序、UTF-8 SHA-256）计算；claim response 同时携带 `task_snapshot_json` 与 `snapshot_digest`。旧任务两个字段均为空，绝不由 Worker 猜测 version、provider 或 policy。
4. Worker 只在 snapshot 完整、digest 本地校验通过且 task/lease/fencing 与 claim 精确一致时才允许 executor 创建；任何缺失、篡改或身份不一致均在外部工具调用前失败。

TDD 验收：新版本任务可获得包含 server scope 的快照；同 worker claim 重放不变；旧任务仍无快照；Worker 能拒绝 digest/lease/task 不一致的 snapshot。真实 executor loop、permit-before-tool 与 candidate/evidence submission 留在 MA4D-2。

## 7. MA4D-2：DEEP_CELL 工具执行与受控结果回传（执行方案）

### 7.1 分段与边界

MA4D-2A 仅落地真实工具调用的安全边界：`DEEP_CELL` 从已验证的 snapshot 构建最小 `ResearchTaskInput/ResearchPlan`，同一 bundle 固定执行 `search → fetch → read → extract`。每一个实际工具阶段前必须向 Backend 的 Redis permit boundary 申请额度；拒绝、取消、snapshot 失配或任一阶段异常时立即中止，不得把未完成执行伪装成已验证候选。默认 role executor 仍显式注入，且 consumer 配置默认关闭。

MA4D-2B 才会增加 evidence ingestion、candidate append 与 Backend verifier/CAS merge。因为当前 `ResearchAgentCellMergeService` 会校验 `source_evidence` 存在性，在没有服务端可验证 evidence lineage 之前，Worker 不得生成可 merge candidate；2A 的合法终态只能是可审计的 `NO_SUPPORTED_CANDIDATE` / `NO_EVIDENCE_INGESTION_PATH`，绝不是“研究完成”。

### 7.2 受信输入

`query_policy.query` 为唯一检索问题；`source_policy.source_scope` 是唯一 workspace source 清单。`allow_external_search` 和 `allow_external_fetch` 必须由 snapshot 明示，缺失时均为 `false`，Worker 环境变量、LLM 输出及网页内容不能扩大该范围。snapshot target cell 的 column 形成最小 schema；其他 column、entity 或 URL 不得被写入候选。

### 7.3 调用与限流协议

Worker 每个阶段先检查 cancellation token、role toolbox allowlist，再以 snapshot 的 `provider_key/workspace_id/research_run_id/role` 调用 `POST /internal/research-agent/permits`。Backend 使用既有 `ResearchAgentRateLimitService`，Redis 不可用或额度不足即 fail-closed。工具适配器可以是真实 existing Search/Fetch/Read/Extract adapter；测试 double 仅用于断言调用顺序，不能作为生产 provider 宣称。

### 7.4 TDD 红灯清单

1. 未获 permit 时 search 不得开始；每个实际阶段都有独立 permit，顺序严格为 Search、Fetch、Read、Extract。
2. `DEEP_CELL` 以外 role、缺 query/source scope、未显式授权 external 时，在任意网络调用前拒绝；workspace-only path 不构造 external adapter。
3. 每次 execution 创建新的 RoleExecutionContext/LLM/toolchain；取消在阶段之间可见，usage 与 trace 不跨任务泄漏。
4. 工具返回空结果只能产生明确终态与 sanitized trace digest；没有 evidence ingestion path 时不得调用 candidate/merge 写接口。
5. Backend permit 请求缺失/篡改 provider、workspace、run 或 role 时拒绝；Redis unavailable 保持 fail-closed。

MA4D-2A 验收不包含真实 provider、candidate 写入或双 worker canary；这些分别由 MA4D-2B 和 MA4D-3 完成，不能提前宣称。

## 8. MA4D-2B：Evidence / Candidate / CAS 协议（执行方案）

### 8.1 服务端先决条件

版本化 task 首次 claim 后，Backend 必须在同一事务内将 snapshot target cells 绑定为 `active_task_id + lease_epoch + fencing_token`，且 SQL 同时检查 cell version、plan revision 与 entity-set version。任何一个 target 绑定失败都回滚 claim，不允许 Worker 持有一个必然 stale 的 lease。相同 worker 的 claim replay 不改变绑定；新 lease 只能在全部 target 仍满足 snapshot version 时绑定。

### 8.2 Append-only 写入协议

Worker 只能调用内部 `evidence-batches` / `candidate-batches` append API，必须携带 task、worker、lease、fencing、execution 与 idempotency identity。Backend 在写入前验证当前 lease ownership 和 snapshot target binding；Worker 没有 direct cell update 或 merge verdict 的权限。

MA4D-2B 初版只接受 `WORKSPACE` evidence：source id 必须存在于 ResearchRun 的 server-side source scope，quote 必须是该受信 source sample 的逐字子串，且 source/quote/content digest 不能由 LLM 指令扩大。external fetched evidence 必须等到 archive/snapshot object 与 provenance ingestion 落地后才能参与 candidate；不是回退为无校验 external 文本。

### 8.3 Verifier 与合并

Backend 根据已持久化 evidence relation、quote binding 和 target cell binding 计算最小 verifier verdict，再调用既有 `ResearchAgentCellMergeService` 的 CAS merge。即使 verdict 为 SUPPORTS，CAS 失败仍只记录 rejected merge；candidate/evidence/merge 均保留 append-only 审计。Worker 无法提交 `SUPPORTS` 字符串来直接写 canonical cell。

### 8.4 TDD 红灯清单

1. 版本化 task claim 成功时所有 target cell 绑定同一 lease/fencing；任一 stale cell 使 claim 整体失败。
2. 没有当前 lease、目标 cell 不在 snapshot、base version 不匹配、或 idempotency 内容冲突的 evidence/candidate 请求均拒绝且无 canonical 写入。
3. workspace quote 不属于 server source sample、未知 source、external/non-archived evidence 均拒绝；重放同 batch 幂等。
4. 只有后端 verifier 调用 CAS；Worker result 中不携带 merge verdict，过期 candidate 仍由 CAS append rejected merge。

实施顺序：先完成 8.1 cell binding，再完成 8.2 evidence append，最后才让 Worker 从已抽取 card 产生 candidate batch。2B 完成前，2A 维持 `NO_EVIDENCE_INGESTION_PATH`。

## 9. MA4D-3：Fake-provider 双 Worker Canary（执行方案）

本阶段只使用本地 deterministic fake Search/Fetch/Read/Extract provider，不加载真实 LLM/Search/URL credential。通过两个独立 worker instance、同一 Kafka topic/group、短 lease 与可控 crash point 验证：同一 command 最多一个有效 claim；同 worker replay 不推进 epoch；worker A 在 evidence/candidate 之后、submit 之前中断时，worker B 只能在 reaper retry 后取得新 fencing，并让旧 submission/candidate merge 被拒绝或审计为 stale；canonical cell 只允许一次 CAS 成功。

canary 配置必须同时满足 `INCREMENTAL_V1`、workspace/run allowlist、`research_agent_consumer_enabled`、`research_agent_deep_cell_executor_enabled`、fake provider 开关和小预算。Compose/测试必须断言不存在外网 provider URL/API key；每轮记录 task epoch、outbox delivery、evidence/candidate/merge 数、usage、终态和 DLQ。至少四次顺序/双 worker 对照后，才允许撰写真实 provider canary 的独立方案；任何 stale merge、预算不守恒或 DLQ/offset 顺序错误立即停用 distributed consumer。

最终记录：`DeterministicFakeToolchain` 必须显式开启并强制禁用 LLM/external adapter；MA4F 隔离 R1–R4 已覆盖真实 Kafka/MySQL 双 Worker 分工、crash/lease/retry、replay、offset/DLQ 顺序和 project-scoped cleanup。该证据只完成 fake-provider 受控执行面，不等同于真实 Provider 四轮质量/成本/p95 A/B。

隔离 fixture 已迁移到 `scripts/ma4f`/后续 MA4G-H round，不再保留主 `docker-compose.yml` 的旧 `agent-canary` profile、workspace/run 双 allowlist 或 canary 死配置。唯一功能授权条件为 `agent_execution_mode=INCREMENTAL_V1`，fixture 只提供 deterministic fake provider，不注入真实 Provider credential。

运行前检查：先确认运行中的 Backend 镜像已包含 MA4D-2B internal evidence/candidate/permit endpoints；否则 canary worker 虽可启动，但无法验证本阶段协议。建议在隔离环境以新镜像执行 `docker compose --profile app --profile agent-canary up -d --build backend research-agent-canary-worker-a research-agent-canary-worker-b`，再由 coordinator 创建 allowlisted `INCREMENTAL_V1` snapshot-ready task。不得对未知用途的现有 Backend 容器直接重建或重启。

最终验证同时包含 Backend candidate/atomic completion 定向测试、Worker snapshot/fake toolchain/consumer/DLQ 回归与 MA4F R1–R4 Compose 证据。当前总门禁为 Backend Research 聚合 `194 tests, 0 failures, 0 errors, 1 skipped`、Worker `359/359`、LockMatrix `13/13`；唯一 skip 是需显式 Redis integration 环境的测试。
