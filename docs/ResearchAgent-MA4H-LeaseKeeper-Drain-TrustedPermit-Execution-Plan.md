# Research Agent MA4H：LeaseKeeper、Drain 与 Trusted Permit 执行方案

> 状态：`IMPLEMENTED / VERIFIED`（2026-07-17）；后续进入 MA4I。  
> 前置：MA4G 已在 deterministic fake-provider、真实 MySQL 8.4/Kafka 隔离 fixture 范围内 `IMPLEMENTED / VERIFIED`；其原子 completion 不证明长调用期间的 lease 安全。  
> 本阶段目标：让已 claim 的 Worker execution 在其 lease/fence 仍有效时才继续外部工作与 completion；让 Backend 从 authoritative task snapshot 派生 permit scope；让 SIGTERM 进入可证明的 drain。  

## 1. 范围与非目标

MA4H 只闭合执行期间的控制面，不新增 Run advancement、wave/checkpoint/repair、SYNTHESIS、真实 provider 或生产灰度。

完成后必须成立：

- 每个 active execution 自己拥有一个 `LeaseKeeper`；首个 heartbeat 在 claim 后立即调度，之后以小于 `lease_seconds / 3` 的有界 interval 续租。
- heartbeat 返回 stale lease、cancelled/terminal task、fence/epoch 不匹配，或超过明确的 Backend-unavailable 预算时，execution 进入不可逆 `STOPPED`；不得再发起新的 Search/Fetch/Read/Extract/LLM 调用，不得调用 `/complete`。
- Worker 只能以 `task_id + worker_instance_id + lease_epoch + fencing_token + tool_identity` 请求 permit；Backend 锁定 task 并从 immutable snapshot 派生 workspace/run/role/provider/budget key，忽略 Worker 自报的 scope。
- SIGTERM/drain 停止 poll 与新 claim，等待已开始的 execution 在 grace period 内完成或停止；超时不伪造 completion，交由 DB-clock/reaper 恢复。
- MA4G atomic completion 仍是唯一结果写入口；MA4H 不恢复旧的 evidence/candidate/submit 分段 client。

不在本阶段证明：真实 provider cancel 的物理中断、provider 账单 exactly-once、Run 自动推进、灰度或 SLO。这些分别留给 MA5/MA6、MA4I/MA4J。

## 2. 目标状态机与不变量

```text
CLAIMED
  -> EXECUTING (register control + start LeaseKeeper)
  -> DRAINING  (SIGTERM: no new poll/claim)
  -> COMPLETING (only if control remains ACTIVE)
  -> DONE

EXECUTING/DRAINING
  -> STOPPED (stale/cancelled/terminal heartbeat, lease-loss, bounded heartbeat outage)
  -> ABANDONED (drain grace elapsed; no /complete)
```

`ExecutionControl` 是 Worker 内的每 execution shared object，至少含不可逆 `stop_reason`、cancellation event、最后成功 heartbeat 的 monotonic timestamp 与 active external-call count。所有 tool phase 在开始前、每个可重试边界、写 completion 前检查 control；`STOPPED/ABANDONED` 不得重新转回 `ACTIVE`。

Backend heartbeat 的线性化条件必须与 completion 相同：task 当前 owner、epoch、fence、DB-clock lease、Run 非终态均匹配。Heartbeat 只能延长有效 lease，不能复活 expired/cancelled task，不能改变 immutable snapshot、budget、cell binding 或 task owner。

## 3. 接口与数据契约

### 3.1 Heartbeat

保留内部 heartbeat route，但请求和响应均必须带 `task_id`、`worker_instance_id`、`lease_epoch`、`fencing_token`、`lease_seconds`。Backend 使用 `current_timestamp`，拒绝 caller clock；响应返回同一 epoch/fence 与新的 server lease expiry。任何 4xx stale/cancel/terminal 是 stop signal，不是可重试成功。

Worker 新增显式配置（有严格上下界）：

- `NOTEWEAVE_RESEARCH_AGENT_HEARTBEAT_INTERVAL_SECONDS`：默认由 lease 推导，必须 `< lease / 3`；
- `NOTEWEAVE_RESEARCH_AGENT_HEARTBEAT_FAILURE_BUDGET_SECONDS`：小于有效 lease 的安全余量；
- `NOTEWEAVE_RESEARCH_AGENT_DRAIN_GRACE_SECONDS`：仅控制本地等待，不改变 Backend lease；
- `NOTEWEAVE_RESEARCH_AGENT_CONSUMER_ENABLED` 关闭或接到 drain 后禁止 poll/claim。

### 3.2 Trusted permit

permit request 不得再接受 `workspace_id/research_run_id/role/provider_key` 作为权威输入。Backend 接收 lease identity 与受限 `tool_identity`，锁 task→Run 并校验 snapshot digest/allowlist；随后自行构造 Redis bucket/provider identity。未知 tool、非 allowlisted tool、stale lease、cancelled task 全部 fail-closed。

若兼容 DTO 暂存旧字段，它们必须被 Bean Validation 后的 service 忽略并记录低基数 rejection metric；不得成为 fallback。待 inventory 为零后物理删除。

### 3.3 Drain

consumer runner 接到 SIGTERM：设置 process-wide draining flag、停止 Kafka poll、拒绝 register 新 execution；对已有 control 发出 `DRAIN_REQUESTED`。在 grace 内仅允许仍 ACTIVE 的 completion 走 MA4G 原子路径；到期后取消本地 future/HTTP retry，不 ACK 未完成 record，进程以可区分 exit code 退出。下一 consumer/reaper 负责恢复。

## 4. TDD 切片与红灯顺序

### H1：ExecutionControl 与 LeaseKeeper（先红后绿）

1. 红灯：interval 边界、立即首 heartbeat、单 execution 只一个 keeper、stop reason 不可逆。
2. 红灯：heartbeat stale/cancel/terminal、连续 unavailable 超预算、late success 均使后续 tool phase 与 `/complete` 不可达。
3. 实现：Worker `ExecutionControl`、LeaseKeeper lifecycle、toolchain cooperative cancellation adapter；不得在 daemon thread 中吞掉异常。
4. 定向验证：短 lease + slow fake toolchain；记录 heartbeat timeline、stop reason、无 completion callback。

### H2：Backend authoritative heartbeat 与 trusted permit

1. 红灯：heartbeat 使用 DB clock；stale/cancelled/expired/owner mismatch 不可延 lease；合法 heartbeat 不改变 snapshot/budget/cell state。
2. 红灯：伪造 workspace/run/role/provider 的 permit request 不能扩大 scope；同一 lease 合法 tool 得到 server-derived key。
3. 实现：共享 task/run lock helper、permit request DTO 收窄、snapshot-derived permit service、低基数拒绝指标。
4. 定向验证：H2 contract + 真实 MySQL DB-clock/lock tests。

### H3：Consumer drain

1. 红灯：drain 后无新 claim/poll；已有 execution 可在 grace 内原子完成。
2. 红灯：grace 到期不 ACK、不提交半包；重启/reaper 可使用新 epoch 恢复。
3. 实现：process-wide drain coordinator、active registry、SIGTERM handler 与 bounded shutdown。
4. 定向验证：可控 consumer、slow fake phase、SIGTERM/subprocess harness。

### H4：隔离真实退出门

每轮使用全新 `noteweave-ma4h-*` project、internal network、零宿主端口、默认 cleanup。至少包括：

| Round | 注入 | 必须断言 |
| --- | --- | --- |
| H1 | lease 小于 slow fake phase；heartbeat 正常 | 多次 DB-clock heartbeat，最终仅一个 MA4G completion，预算/receipt/state digest 正确。 |
| H2 | heartbeat 4xx stale/cancel | stop 后无后续外部 phase、无 `/complete`、无 child/CAS/outbox 新写。 |
| H3 | heartbeat 网络不可达超过 budget | Worker 自停且不 ACK；lease expiry 后新 epoch recovery，无重复结算。 |
| H4 | SIGTERM during active phase / during completion | drain 不 claim 新 task；grace 内完成或超时无 ACK；重启/reaper 后状态可解释。 |
| H5 | permit scope spoof | Backend 拒绝伪造 workspace/run/role/provider，合法 snapshot permit 可通过；审计指标无高基数标签。 |

所有 round 除 manifest `VERIFIED` 外还要保存 image digest、MySQL version/isolation、Kafka lag/DLQ、全状态 digest、控制 marker 与 project-scoped cleanup 证据。

## 5. 代码边界与审查清单

- Worker：`kafka_consumer.py`、`runner.py`、`deep_cell_executor.py`、`agent_task_client.py`、`config.py`；新增控制对象与测试，不能让线程/async 混用隐式绕过 cancellation。
- Backend：heartbeat/controller/service/task lock primitive、permit controller/service、properties/metrics 与 DTO；禁止把 permit 逻辑复制到 Worker。
- Migration：除非需要持久化新的 server-side audit identity，否则优先不引入表；若新增，必须准备 fresh + legacy MySQL 迁移证明。
- Tests：每个生产分支至少一条红灯；任何 “sleep” 必须用 marker/barrier 证明已进入目标窗口，不能靠 wall-clock 猜测。
- Compatibility：旧 permit payload 或 lifecycle route 的保留必须有明确 scope guard、deprecated 标记与删除门槛。

## 6. MA4H 退出门与声明边界

仅当 H1–H5、Worker/Backend 定向回归、静态检查和主服务不变性证明全部满足时，MA4H 才能标记 `IMPLEMENTED / VERIFIED`。完成后可声明“受控 fake-provider 执行面在短 lease/网络失败/SIGTERM 夹具下可持续保活或安全停止”；仍不得声明真实 provider cancellation、成本 exactly-once、Run 自动推进或生产就绪。

## 7. 已验证证据账本（截至 2026-07-17）

本节只记录已落盘且可复查的结论；H1–H5 均已完成并满足本阶段退出门。真实 Provider 长时 cancellation 与生产 soak 仍属于外部证明，不回写为 MA4H 未完成。

| 切片 | 已验证结论 | 证据/边界 |
| --- | --- | --- |
| H1：LeaseKeeper / ExecutionControl | claim 后同步首个 heartbeat；后台续租、stale/网络故障预算耗尽后 fail-closed；外部 phase、completion retry 和 `/complete` 均受不可逆 control 约束。 | Worker 全量单测（含 H1–H3）`348 passed`；`compileall` 已通过。 |
| H2：authoritative heartbeat / trusted permit | permit 请求严格只接受 `task_id`、`worker_instance_id`、`lease_epoch`、`fencing_token`、`tool_identity`；scope 由锁定的 task snapshot/server row 派生，旧 scope 字段会拒绝。畸形 JSON 统一返回 400 `MALFORMED_REQUEST`，不再错误映射为 500。 | Backend Research 定向 Maven：`ResearchAgentTrustedPermitServiceTest` 7、`ResearchAgentTaskServiceTest` 11、`ResearchAgentRateLimitServiceTest` 1，共 19 全绿。全仓 `testCompile` 仍受无关的 `SourceServiceCacheTest` 构造器变更阻塞，不能归因为本切片。最终真实 MySQL 8.4/READ-COMMITTED fixture：`scripts/ma4h/evidence/noteweave-ma4h-h2-20260717t0830g-20260717T073015Z`，manifest 为 `VERIFIED`，三份报告均为 0 failures/errors/skips，cleanup 为空。rate-limit transport 在该 fixture 中为 mocked。 |
| H3：SIGTERM drain / recovery | SIGTERM 后停止 poll/新 claim；grace 到期使 active control 停止，未完成消息不 `/complete`、不 ACK；DB-clock 到期后新 consumer 以新 epoch 恢复，最终只产生一次 execution/completion。 | 真实 MySQL/Kafka 隔离 fixture：`scripts/ma4h/evidence/noteweave-ma4h-h3-20260717t0642a-20260717T061740Z`，manifest 为 `VERIFIED`。状态序列为 `RUNNING epoch=1 -> EXPIRED -> SUBMITTED epoch=2`；grace 后 execution/completion/evidence 计数均为 0，恢复后为 1/1；Kafka lag=0、DLQ end offset=0，project-scoped container/volume/network cleanup 均为空。 |
| H4a：heartbeat stale lease | 已进入标记的 `search` phase 后注入第二次 heartbeat stale；Worker 在 cooperative boundary 停止，未进入 fetch/read/extract，未写 execution/completion；reaper 后由正常 Worker epoch 2 恢复。 | 真实 MySQL/Kafka 隔离 fixture：`scripts/ma4h/evidence/noteweave-ma4h-h4-stale-20260717t0700c-20260717T063800Z`，manifest 为 `VERIFIED`。`phase-log.txt` 仅为 `search`；fault 后 execution/completion/evidence 均为 0，恢复后 execution/completion 均为 1；recovery Kafka group lag=0、DLQ end offset=0，cleanup 为空。 |
| H4b：heartbeat unavailable budget | 已进入标记的 `search` phase 后持续注入 Backend unavailable；在明确 failure budget 耗尽后 Worker fail-closed，未进入后续 phase、未写 completion；reaper 后由正常 Worker epoch 2 恢复。 | 真实 MySQL/Kafka 隔离 fixture：`scripts/ma4h/evidence/noteweave-ma4h-h4-unavailable-20260717t0705d-20260717T064315Z`，manifest 为 `VERIFIED`。`phase-log.txt` 仅为 `search`；fault 后 execution/completion/evidence 均为 0，恢复后 execution/completion 均为 1；recovery Kafka group lag=0、DLQ end offset=0，cleanup 为空。 |
| H5：permit scope spoof | real Worker claim/heartbeat 持有有效 lease 时，四类 caller-supplied scope（`workspace_id`、`research_run_id`、`role`、`provider_key`）均被严格 field contract 拒绝；不允许的 tool 被 role allowlist 拒绝；五字段合法 request 被 server-derived scope 授权。 | 真实 MySQL/Redis/Kafka Backend fixture：`scripts/ma4h/evidence/noteweave-ma4h-h5-20260717t0800f-20260717T071940Z`，manifest 为 `VERIFIED`。四份 spoof response 均为 `400 RESEARCH_AGENT_PERMIT_INVALID`，forbidden tool 为 `400 RESEARCH_AGENT_PERMIT_TOOL_FORBIDDEN`，valid search 为 `200 GRANTED`，cleanup 为空。 |

H3 使用 deterministic fake provider 与 phase marker，在外部 phase 已开始后才注入 SIGTERM；它证明 Worker 的协作式安全停机及恢复语义，不证明真实 provider 的物理取消、成本 exactly-once 或生产环境 SLO。
