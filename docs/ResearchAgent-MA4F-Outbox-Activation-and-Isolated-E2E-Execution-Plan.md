# Research Agent MA4F：Outbox 激活与隔离双 Worker E2E 执行方案

> 状态：**COMPLETE（受控 fake-provider / 单 workspace 边界）**  
> 完成日期：2026-07-15  
> 前置：MA4E 已闭合 `INCREMENTAL_V1 Run → task → budget reservation → research_agent_outbox`。  
> 本阶段结论：已用同一组最终镜像完成 R1–R4 隔离 Docker 验证，证明 Run-scoped command 激活、双 Worker 分区消费、claim 后写前崩溃接管和消费重放幂等；本结论不等于真实 provider、多 workspace 或完整 Research Run 已可生产运行。
> 后续状态（2026-07-17）：本文的 default-off/双 allowlist 是 MA4F 隔离验证的历史边界；当前自动链路只由 `INCREMENTAL_V1` 授权，旧 manual canary 入口已在 MA6 删除。

## 1. 阶段目标与最终声明

MA4E 的 coordinator 能把命令持久化为 `READY` outbox，但当时没有受控发布入口；仅启动 Worker 不会让 Kafka 获得 command。MA4F 的目标是以默认关闭、workspace/run 双 allowlist 的方式补齐这段激活路径，并用不触碰现有 `noteweave-v2` 服务的隔离环境验证至少一次传输下的关键并发和恢复性质。

MA4F 完成后可以准确声明：

> 在单一合成 workspace、精确 Run allowlist、deterministic fake provider 和隔离 MySQL/Redis/Kafka 下，Backend 可按 Run 发布持久化 command；同一 consumer group 的两个 Worker 能并行处理不同 task，且每个 task 只有一个 canonical execution/merge/settlement；claim 后、任何 permit/evidence/candidate 写入前崩溃可由新 epoch/fence 接管；Kafka command 被真实重放一次时，不会改变已完成链路的持久化状态。

不能把以上声明扩写为“真实搜索/LLM 已验证”“任意崩溃点均可恢复”“完整 Run 自动完成”或“生产级多租户能力已验证”。

## 2. 已交付实现

### 2.1 Run-scoped outbox 激活

- `ResearchAgentCommandDispatcher.dispatchReadyForRun(runId, limit)` 只选择指定 Run 的 `READY` delivery；受控入口为 `POST /internal/research-agent/coordinator/runs/{runId}/publish`。
- publish 复用 coordinator canary 的 enabled、workspace allowlist 和 run allowlist，任一条件不满足即 fail-closed；`limit` 只接受 `1..100`。
- 全局 scheduler 默认关闭，只有显式启用才执行 bounded batch；非法 batch 在启动时 fail-fast。canary endpoint 不调用全局 dispatch。
- dispatcher 仅发布 `INCREMENTAL_V1`、非终态 Run、可 claim task；`RETRY_WAIT` 还必须满足 `next_attempt_at <= now`。
- Kafka topic 统一由 `NOTEWEAVE_KAFKA_RESEARCH_AGENT_TOPIC` 提供，不再由激活路径写死另一份 topic。
- outbox 使用 `delivery_no` compare-and-set 标记 `SENT`，旧 dispatcher 不能把新版 retry delivery 误标成功；publish 异常时 delivery 保持可重试状态。

### 2.2 Lease、写入和 delivery 防线

- claim、同 Worker claim replay、heartbeat、evidence ingestion、candidate ingress 和 submit 都校验 Run 仍为 `INCREMENTAL_V1` 且未终态，防止 Run 切模或终止后继续外写。
- task 成功 submit 时，在同一事务中把尚未发布的 retry delivery 标为 `CANCELLED`，避免 reaper 已生成 retry、原 Worker 又在到期边界完成后产生幽灵 command。
- Worker 的 DLQ failure report 携带 `outbox_id` 和 `delivery_attempt`，让失败记录能够关联具体 delivery，而不是只关联逻辑 task。
- stale epoch/fencing 在 Backend 侧拒绝；Worker 收到不可 claim 的已完成 task 时跳过并提交 Kafka offset，不重新执行工具链。

### 2.3 隔离 fixture

`scripts/ma4f/` 提供基线、双 Worker、崩溃和 replay override，以及 SQL/脚本断言。fixture 的隔离条件如下：

- Compose project 分别使用 `noteweave-ma4f-r1` 至 `noteweave-ma4f-r4`；无固定 `container_name`、无宿主端口，网络为 project-scoped internal network，数据库、Kafka 和存储 volume 均为 project-scoped。
- 固定合成 workspace `00000000-0000-0000-0000-00000000f001` 和 Run `00000000-0000-0000-0000-00000000f002`，Backend 只允许这一个 workspace/run。
- Worker A/B 使用同一 group `noteweave-ma4f-workers`、不同 instance id `ma4f-worker-a` / `ma4f-worker-b`，command topic 为 4 partitions。
- `NOTEWEAVE_RESEARCH_AGENT_FAKE_PROVIDER_ENABLED=true`；Research LLM API key、base URL、model 全为空，fake toolchain 禁止 external search/fetch/read。
- Backend command scheduler 保持关闭，测试只通过 Run-scoped internal endpoint 激活；Redis rate limiter保持 fail-closed，不启用 local fallback。
- 每轮结束前后只操作名称匹配 `noteweave-ma4f-*` 的 project；没有复用或停止当前 `noteweave-v2` 的 Backend、数据库、Kafka 或 Worker。

## 3. 最终镜像与证据口径

R1–R4 的最终退出证据全部以同一组镜像复跑结果为准；早期构建镜像和修复 replay fixture 前的尝试不计入最终通过证据：

| 组件 | 最终镜像 ID |
| --- | --- |
| Backend | `sha256:878ea16952fe6f6fdc4a72b4514deb32a9e762d1f4c63dd13e94f9416f027155` |
| Research Worker | `sha256:e47a9ea804b3d58214fc9c9432ef454e3038a1c473a4a90a40b710eecedd5256` |

每轮断言 task、epoch/fence/attempt、execution、evidence、candidate、accepted merge、cell version、budget、outbox delivery、consumer lag 和 DLQ。数据库行数断言与 Kafka 断言必须同时成立；只看到 Worker 成功日志不算通过。

## 4. R1–R4 最终运行结果

### 4.1 R1：单 Worker 基线

最终同镜像复跑通过：

- 1 task 对应且只对应 1 execution、1 evidence、1 candidate 和 1 accepted merge。
- task 的 `lease_epoch=1`、`fencing_token=1`、`attempt_count=1`，执行者为 `ma4f-worker-a`。
- cell 只从初始版本推进到 `VERIFIED / version=1`；budget 为 `SETTLED`；初始 outbox delivery 为 `SENT`。
- consumer group lag 为 0，DLQ end offset 为 0。

R1 证明单 command 的 happy path 能形成一次有效 execution settlement，但不单独证明竞争、接管或重放。

### 4.2 R2：4 partitions、双 Worker 并行

最终同镜像复跑通过：

- 8 tasks、8 executions，按 task 分组均为 1；8 candidates、8 accepted merges，没有 canonical 双写。8 个 task 提交的是同一个确定性 workspace evidence key，Backend 幂等落为 1 条 canonical `source_evidence`，不是 8 条重复 evidence。
- 两个 consumer member 同时加入同一 group 并实际工作，最终分配为 Worker A 3 项、Worker B 5 项；不是“启动了两个容器但只有一个执行”。
- 8 cells 均只推进到 `version=1`，8 budget reservations 均为 `SETTLED`，8 初始 outbox deliveries 均为 `SENT`。
- command topic 为 4 partitions，最终 group lag 为 0，DLQ end offset 为 0。

R2 证明的是“多个 task 在 consumer group 内并行分配，同时每个 task 保持单一 canonical 结果”。它不证明同一个 cell 可被多个 agent 同时安全改写，也不代表角色级协作编排已经完成。

### 4.3 R3：claim 后、任何外部写入前崩溃

最终同镜像复跑通过：

1. Worker A 真实消费 command 并 claim，获得 epoch 1 / fence 1，随后在任何 permit、evidence、candidate 或 merge 写入前以 exit code 70 退出。
2. lease 到期后 reaper 把 task 转为 `RETRY_WAIT`，释放 cell active binding，并创建 `delivery_no=2 / READY` 的 retry delivery。
3. retry 到期后 Worker B 接管，最终 task 为 epoch 2 / fence 2 / attempt 2；只有 Worker B 产生 execution、evidence、candidate 和 accepted merge。
4. cell 最终仍只有 `VERIFIED / version=1`，budget 只结算一次；尚未发布的 delivery 2 在成功 submit 事务内变为 `CANCELLED`。
5. 使用 Worker A 的旧 epoch/fence 再次 submit，Backend 返回 HTTP 400，错误码为 `RESEARCH_AGENT_TASK_STALE_LEASE`。
6. 最终 consumer group lag 为 0，DLQ end offset 为 0。

R3 的故障注入点是 **claim 后、写前**。它没有覆盖 evidence/candidate CAS 已成功、task submit 尚未发生的崩溃窗口，因此不能写成“任意阶段崩溃均能恢复”。

### 4.4 R4：发布端 guard 与消费端真实重放

R4 把两个容易混淆的性质分开验证：

1. **发布端 guard**：baseline 完成后再次调用同一 Run 的 publish endpoint，返回 `dispatched_count=0`；调用前后 command topic end offset 不变。这证明已发送/已取消 delivery 不会被 server-side dispatcher 再发布。
2. **消费端重放**：停止 Worker，确认 consumer group 已无 active member，把该 group 对 command topic 的 committed offsets reset 到 earliest；脚本确认恰有 `replay_records=1`，再启动同一个 Worker。Worker 实际消费这 1 条历史 command，由于 task 已终态而走 not-claimable skip，并正常提交 offset。
3. **持久状态不变**：重放前后对 Research Agent 可变持久表生成排序、分组件 SHA-256 摘要，overall digest 均为 `d4e34c8791e3e9d00aa66708f11c91bbf001a58795652cf1a8f690f6a8a3d51f`；task/execution/evidence/candidate/merge/cell/budget/outbox 等行数和内容没有变化。
4. **传输收口**：重放后 consumer group lag 为 0，DLQ end offset 为 0。

因此 R4 不是用“第二次 publish 没有消息”冒充 replay；它先证明 publish guard，再通过 Kafka consumer-group offset reset 制造并消费 1 条真实历史记录。

## 5. Replay fixture 首轮暴露的问题与修复

首次 R4 编排没有直接通过。以下两处问题属于测试编排缺陷，已修复后使用同一 baseline 重新执行并通过；失败轮的 durable state digest 也确认未变化。

### 5.1 Bug 1：group 仍为 Stable 时重置 offset

- **现象**：停止 Worker 后立即执行 reset，Kafka group 仍为 `Stable`，命令输出 assignment error 或 0 partition；这不能证明发生了重放。
- **根因**：容器进程停止不等于 Kafka 已完成 session/member eviction；fixture 把进程退出误当成 group 已 inactive。
- **修复**：`reset-replay-offsets.sh` 轮询 `kafka-consumer-groups --describe --state`，只在状态为 `Empty` / `Dead` 且没有 active member 时继续；随后同时校验 dry-run 和 execute 输出包含预期的逐 partition 数字行，并要求 `replay_records > 0`。

### 5.2 Bug 2：失败恢复连带重启一次性依赖

- **现象**：失败路径的 `finally` 使用 `docker compose start worker-a`，Compose 连带启动已完成的 `seed` / `kafka-init`；seed 重插固定主键失败，Worker 也没有真正恢复。
- **根因**：编排脚本把“重启一个已存在的 Worker 容器”实现为 Compose service 依赖图重启。
- **修复**：`run-replay.ps1` 先从指定 project 获取 `worker-a` 的 container ID，后续只对该 ID 执行 `docker stop` / `docker start`，不再启动依赖服务；脚本同时拒绝缺失 Worker A 或意外运行 Worker B 的项目。

这两项修复是证据可信度的一部分：offset reset 必须确认确有可重放记录，失败清理也不能改变 seed 或重新初始化基础设施。

## 6. 可复现入口

四轮都应使用独立、显式 project name。R1 使用基础 Compose；R2/R3 分别叠加 dual/crash override；R4 使用专用 orchestration：

```powershell
docker compose -f scripts/ma4f/docker-compose.yml -p noteweave-ma4f-r1 up --abort-on-container-exit --exit-code-from verify

docker compose -f scripts/ma4f/docker-compose.yml -f scripts/ma4f/docker-compose.dual.yml --profile dual -p noteweave-ma4f-r2 up --abort-on-container-exit --exit-code-from verify

# R3 必须先只启动 crash Worker A；确认 A exit=70、task=RETRY_WAIT、写入计数仍为 0 后，
# 再单独启动 Worker B。不得用 --profile dual 一次性竞态启动 A/B 冒充确定性故障注入。
docker compose -f scripts/ma4f/docker-compose.yml -f scripts/ma4f/docker-compose.crash.yml -p noteweave-ma4f-r3 up -d --no-build
docker compose -f scripts/ma4f/docker-compose.yml -f scripts/ma4f/docker-compose.crash.yml --profile dual -p noteweave-ma4f-r3 up -d --no-deps --no-build worker-b

powershell -File scripts/ma4f/run-replay.ps1 -ProjectName noteweave-ma4f-r4
```

清理前必须再次校验 project name 匹配 `noteweave-ma4f-*`，再对该 project 单独执行 `down -v --remove-orphans`。不得把示例改成无 project name 的全局清理命令。

## 7. 退出门槛判定

| 门槛 | 状态 | 证据 |
| --- | --- | --- |
| Run-scoped publish、双 allowlist、默认关闭 scheduler | PASS | 受控 endpoint、bounded limit、scheduler opt-in |
| publish 成功/失败与 delivery CAS 语义 | PASS | `READY → SENT` 仅在对应 delivery publish 成功后发生；旧 delivery 不能覆盖新版 retry |
| Worker command 消费与 fenced callback | PASS | R1 happy path、R3 epoch 2 接管与旧 fence HTTP 400 |
| 双 Worker 实际并行且无 canonical 双写 | PASS | R2 A=3 / B=5，8 task 各 1 execution/merge/settlement |
| server publish guard | PASS | R4 `dispatched_count=0` 且 topic end offset 不变 |
| consumer replay 幂等 | PASS | R4 reset 后真实 replay 1 record，完整 digest 不变 |
| Kafka 收口 | PASS | R1–R4 最终 lag=0、DLQ end offset=0 |
| 隔离与配置边界 | PASS | project-scoped internal fixture、fake provider、空 LLM 配置、精确 workspace/run allowlist |

据此，MA4F 在其既定的受控范围内完成。该结论只关闭 command activation 与本节故障模型，不替代后续生产能力阶段。

## 8. 明确保留到后续阶段的缺口

### 8.1 Post-CAS / pre-submit 崩溃窗口

当前 `DeepCellExecutor` 的顺序是 evidence/candidate merge（可能已成功推进 cell version）之后才 submit task。若 Worker 在 cell CAS 成功后、submit 前崩溃，旧 task snapshot 仍绑定旧 cell version；reaper 后的新 epoch 可能因 snapshot stale 无法重放。需要 durable execution phase/commit protocol，或让 candidate merge 与 task completion 具备可恢复的同一幂等提交语义。R3 没有覆盖这个窗口。

### 8.2 长调用 heartbeat

Backend 和 client 虽有 heartbeat API，但当前 Kafka `DeepCellExecutor` 路径没有在长 Search/Fetch/Read/LLM 调用期间周期续租。15 秒 fixture lease 只适用于 deterministic fake provider；真实 provider 延迟、退避或流式读取可能让健康 Worker 被 reaper 抢占。需要独立 heartbeat loop、停止条件、连续失败策略和针对长调用的接管测试。

### 8.3 Permit 的中间故障语义

R1/R2 的 fake 工具链会经过 Redis rate-limit permit happy path，R3 则刻意在任何 permit 前崩溃。尚未验证“permit 已授予、工具调用未发生或回调未持久化”时的审计、补偿和重试语义；当前也没有 durable permit receipt 可与 execution/fence 对账。真实 provider 启用前应明确 permit 是否允许消费后不返还，并增加 permit 后崩溃、Redis 超时和限流重试的故障测试。

### 8.4 未用预算自动释放

成功 submit 会把实际 usage 写入 reservation 并标记 `SETTLED`，但 `reserved - consumed` 的未用额度不会在 task 成功或 Run 完成时自动进入 `released_json`。现有 lifecycle release 主要覆盖取消、失败等路径。需要定义成功 task 的 unused release 与 Run 级预算守恒收口，并以 `reserved = consumed + released` 做最终断言；R1–R4 的 `SETTLED` 不能替代这项证明。

### 8.5 Run 自动收口

所有 agent tasks submit 后，目前没有自动触发下一轮 taskization、checkpoint、global verification、final report 或 Run terminal transition。MA4F 完成的是 task pipeline，不是端到端 Research Run lifecycle。后续 coordinator 必须根据 canonical cells 和预算决定继续、冻结或完成，并保证收口重放幂等。

### 8.6 真实 provider 与多 workspace 证据

fixture 只有一个合成 workspace，所有搜索、抓取、读取和抽取结果来自 deterministic fake provider；LLM key/base URL/model 为空。因此尚无真实 URL 归档、robots/SSRF、provider 429/5xx、内容漂移、证据质量、延迟/成本收益或跨 workspace 并发隔离的 E2E 证明。此项必须在单独的凭据隔离 canary 和安全审查后验证，不能由 MA4F 结果推导。

## 9. 下一阶段建议顺序

1. 先解决 post-CAS / pre-submit 恢复协议，这是当前最可能造成“cell 已写但 task 永远 stale”的一致性缺口。
2. 为真实长调用接入后台 heartbeat，并增加 lease 到期边界和 heartbeat 丢失故障注入。
3. 明确 permit 与 budget unused release 的守恒/审计语义，补齐成功、失败、取消和 crash 四类对账。
4. 增加 coordinator 的 round/checkpoint/global verifier/finalize 闭环。
5. 最后在独立 workspace/run allowlist 下执行真实 provider canary，分别报告质量、延迟、成本和安全结果。
