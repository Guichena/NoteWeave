# Research Agent MA4：分布式执行面与可靠交付执行方案

> 文档状态：CURRENT / MA4 整体 `COMPLETE`（简历项目、受控 fake-provider 证据范围）
>
> 最后核查：2026-07-17
>
> 阶段：MA4A–J 已按各自限定范围实现并验证；真实 provider / external evidence A-B 属于 MA5，生产灰度属于 MA6
>
> 前置：MA3 已提供增量 task/lease/CAS/budget/checkpoint 真相与 `INCREMENTAL_V1` 隔离。
>
> 目标：把受控 `DEEP_CELL` bundle execution 从单进程扩展为可恢复、可审计的多进程执行面；canonical merge 始终只走 Backend verifier/CAS。

## 1. 当前结论

MA4A–F 已经闭合以下受控链路：

```text
INCREMENTAL_V1 Run
  -> server-side coordinator taskization
  -> task + budget reservation + agent outbox（同事务）
  -> INCREMENTAL_V1-scoped automatic dispatcher
  -> Kafka research.agent.command
  -> isolated DEEP_CELL Worker（deterministic fake provider）
  -> claim + snapshot/fencing validation
  -> permit -> Search -> Fetch -> Read -> Extract
  -> append-only evidence + candidate
  -> Backend verifier + CAS merge
  -> execution submit + budget settlement
  -> Kafka offset commit
```

R1–R4 已在隔离 Compose 环境中验证单 Worker、双 Worker、多 partition、claim 后写前崩溃接管和消费重放幂等。因此可以说“受控 fake-provider 分布式 task pipeline 已有可复核 E2E 证据”。

MA4G–J 已继续闭合 atomic completion、持续 LeaseKeeper/trusted permit、SIGTERM drain、自动 wave/checkpoint/repair、数据库原子 report artifact，以及 Run/父 task 终态收口。MA4 因此在**简历项目、受控 fake-provider 证据范围**内完成。真实 provider、external archived evidence、质量/延迟 A/B 属于 MA5，生产 SLO/灰度回退属于 MA6；不得用 MA4 fixture 代替这些外部证明。

## 2. 正确性边界

Kafka 只提供至少一次唤醒；业务正确性来自 Backend task 状态、lease epoch、fencing token、版本化 TaskSnapshot、append-only evidence/candidate、CAS 和预算 reservation。Worker 不能直写 MySQL canonical cell，也不能信任 command 或自身环境变量扩大服务端 snapshot 的 workspace、target、source policy、provider policy 或预算。

当前已验证的崩溃窗口是：Worker A 成功 claim 后，在任何 permit/evidence/candidate 写入前退出；lease 到期后 Worker B 以新 epoch/fencing 接管。当前**没有**证明 evidence/CAS 已成功而 submit 未发生时可以无损恢复；该窗口由 MA4G 负责。

## 3. MA4A–F 实施状态

| 阶段 | 当前状态 | 已闭合能力 | 仍不得推出 |
| --- | --- | --- | --- |
| MA4A Transport | 完成（限定范围） | versioned command/result、独立 topic/group/DLQ、Backend outbox/dispatcher、Worker claim→execute→submit→commit consumer；默认关闭 | 不等于真实研究质量或生产启用 |
| MA4B Lifecycle | 完成（限定范围） | lease reaper、retry/backoff、cancel、delivery failure/redrive、delivery_no、旧 fencing 拒绝、DLQ failure 归属 | 不等于执行期间已有持续 heartbeat、SIGTERM drain 或任意 crash point 恢复 |
| MA4C Resource/Budget | 完成（限定范围） | Redis 三层原子 bucket、默认 fail-closed、task reservation、submit 幂等 settlement、cancel/retry-exhausted release | 当前 permit request 尚需 MA4H 绑定 authoritative task/lease；没有真实 provider 429/成本证据 |
| MA4D Executor/Ingress | 完成（受控 DEEP_CELL 范围） | versioned TaskSnapshot/digest、execution isolation、顺序 Search→Fetch→Read→Extract、workspace evidence ingestion、candidate/Backend verifier/CAS | external fetched evidence 未归档验证；未验证真实 provider；当前执行顺序仍存在 post-CAS crash 窗口 |
| MA4E Coordinator | 完成 | Run→task→reservation→outbox 同事务；稳定 fingerprint；不 taskize VERIFIED/FROZEN cell；旧 canary/manual allowlist 已在 MA6 清理 | 自动 wave/finalize 由 MA4I/J 闭合 |
| MA4F Activation/E2E | 完成（fake-provider 证据范围） | `INCREMENTAL_V1` scoped publish、隔离 fixture、R1–R4；多 partition 双 Worker、写前崩溃接管、重放幂等 | 不等于真实 provider 或生产可用 |

这里的“完成”只表示相应阶段重新收窄后的 DoD 已满足，不表示原设计中所有生产能力已经完成。未闭合能力已显式进入 MA4G–J、MA5 和 MA6，不能通过改变措辞隐藏。

## 4. MA4F 隔离运行证据

最终复核使用同一轮新构建镜像：Backend `sha256:878ea16952fe...`，Worker `sha256:e47a9ea804b3...`。fixture 使用独立 Compose project/network/volume，无宿主端口，LLM key/base URL/model 为空，external Search/Fetch 关闭，且只允许固定 workspace/run；因此证据不会污染当前项目服务，也不包含真实 provider 行为。

| 轮次 | 场景 | 已验证事实 | 证据边界 |
| --- | --- | --- | --- |
| R1 | 单 Worker 基线 | 1 task/1 execution/1 evidence/1 candidate/1 accepted merge；cell 仅到 `VERIFIED v1`；budget `SETTLED`；outbox `SENT`；lag=0、DLQ=0 | 只证明同镜像 fake-provider 基线 |
| R2 | 双 Worker、8 tasks、4 partitions | 8 task 均只有一个 execution/accepted merge/cell v1；Worker A=3、B=5，两个实例都实际执行；8 budgets settled；lag=0、DLQ=0 | 证明多 partition 有效分工和无 canonical 双写，不证明真实 provider 加速 |
| R3 | claim 后、任何业务写前崩溃 | Worker A 在 permit/evidence/candidate 前以 code 70 退出；reaper 后 Worker B 以 epoch/fence/attempt=2 完成；A 的旧 fencing submit 返回 HTTP 400 / `RESEARCH_AGENT_TASK_STALE_LEASE`；retry outbox 被 `CANCELLED`；lag=0、DLQ=0 | 只覆盖 **write-before crash**；不覆盖 CAS 成功后、submit 前崩溃 |
| R4 | publish/consume replay | 二次 publish 的 `dispatched_count=0` 且 topic offsets 不变；将同 group reset 到 earliest 后 `replay_records=1`；replay 前后 15 个 canonical components、10 行 overall state 的 SHA-256 均为 `d4e34c8791e3e9d00aa66708f11c91bbf001a58795652cf1a8f690f6a8a3d51f`；lag=0、DLQ=0 | 证明该 fixture 的 replay 幂等，不是 Kafka exactly-once 或所有副作用的形式化证明 |

本轮最终测试证据：Backend MA4 聚合定向套件 `37/37` 通过，Worker 全量套件 `273/273` 通过。两者证明当前代码回归和局部契约，不替代 R1–R4 的进程/中间件证据，也不替代 MA5 的真实 provider 质量与性能验证。

## 5. MA4G–J 实施记录

各阶段均按独立执行方案、失败模型与 TDD 切片推进：

### MA4G：原子 completion 与 post-CAS crash recovery（已实现并验证）

已通过 immutable completion envelope 与单个 Backend transaction 消除该窗口：真实 MySQL LockMatrix、migration、G1、G2、G3、G4 退出门均为 `VERIFIED`。其中 G2 使用 second-cell CAS named lock 精确定位 connection 后 `KILL CONNECTION`，G3 证明 commit 后响应丢失的 byte-identical replay，G4 覆盖两个 Worker、16 task/32 cell 与 offset reset。证据路径及 fake-provider/非生产边界见 `ResearchAgent-MA4G-Atomic-Execution-Finalization-Execution-Plan.md` §16.1。

### MA4H：LeaseKeeper、cancel/drain 与 trusted permit（已实现并验证）

已实现持续 heartbeat、fail-closed ExecutionControl、trusted server-derived permit 与 SIGTERM drain；H1–H5 的 Worker、Backend、MySQL/Kafka 隔离证据见 `ResearchAgent-MA4H-LeaseKeeper-Drain-TrustedPermit-Execution-Plan.md`。

### MA4I：Run advancement、wave/checkpoint/repair 与模式接管（已实现并验证）

已实现 run-lock snapshot、单调 checkpoint、normal/repair taskization、mode handoff guard、自动 recovery scheduler 与数据库时钟 lease reaper；证据见 `ResearchAgent-MA4I-Run-Advancement-Wave-Checkpoint-Repair-Execution-Plan.md`。

### MA4J：SYNTHESIS、final report 与原子 Run/父 task 收口（已实现并验证）

只从 evidence-bound `VERIFIED` canonical ledger 生成 deterministic report；V050 artifact、Run terminal、父 task completion 与 trace 同事务提交。fail-stop 回滚、并发 replay、自动 terminal handoff 与聚合 29/29 MySQL 退出门见 `ResearchAgent-MA4J-Synthesis-Report-Atomic-Finalization-Plan.md`。

MA4G–J 已通过各自限定退出门，MA4 在本文声明边界内完成。

## 6. MA5/MA6 边界

- **MA5：真实 provider + archived external evidence canary/A-B。** 在独立凭证、显式预算、provider URL allowlist、外部内容归档/provenance 和脱敏 trace 就绪后，至少四次同条件顺序/并行对照，评估 exact-table、citation、wall-clock、成本、429/5xx。fake-provider R1–R4 不能替代。
- **MA6：production rollout 与自动回退。** 建立 SLO、canary 扩量、告警、操作手册和自动回退；质量、预算、429、stale merge 或恢复指标越界时停止新 distributed task，并回退到经过验证的本地/顺序模式，保留审计数据。

在 MA5/MA6 前不得宣称“真实 provider 分布式多 Agent 已上线”“质量优于 TAS/MiroFlow/Marco/DeepWideSearch”或“p95 已改善”。

## 7. 当前可用声明

准确表述为：

> Research Agent 已完成 MA4A–J 限定范围：具备受控多 Worker、immutable completion/CAS、budget settlement、LeaseKeeper/trusted permit/drain、自动 wave/repair/recovery，以及 citation-gated report artifact 与 Run/父任务原子收口。证据来自 deterministic fake provider 与真实 MySQL/Kafka 隔离夹具；真实 provider 质量/延迟/成本 A-B 和生产灰度仍分别属于 MA5/MA6。
