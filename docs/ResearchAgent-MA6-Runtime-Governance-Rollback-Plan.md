# Research Agent MA6：运行治理与受控回退执行方案

> 文档状态：IMPLEMENTED / PRODUCTION SLO EVIDENCE PENDING  
> 日期：2026-07-17  
> 项目边界：简历项目所需的可解释自动保护，不建设多地域流量平台

## 1. 决策

MA6 默认启用健康门，不再保留旧 `coordinator-canary` 手工 dispatch/双 allowlist。Run 的唯一执行授权仍是 `agent_execution_mode=INCREMENTAL_V1`。

“回退”必须区分两种状态：

- 尚未创建任何 Agent task/checkpoint/advancement 的 Run：健康门越界时不创建初始 wave，可以安全留在无增量历史状态；
- 已有增量历史的 Run：禁止切到 legacy delete/rebuild 路径。它必须继续 lease recovery、repair、checkpoint 和原子 finalization，或以可解释失败终止。

因此 MA6 的自动保护是 **pause new initial taskization, preserve and recover existing runs**，不是把执行到一半的 canonical ledger 强行交给 `SEQUENTIAL_V1/V2`。

## 2. 健康快照

默认使用最近 15 分钟、至少 10 个 Agent task 的数据库事实：

- task 总数；
- `LEASE_RETRY_EXHAUSTED` 等 terminal failure；
- durable delivery failure；
- rejected cell merge；
- accepted merge。

MA5 的 real-provider benchmark 另外负责 exact-table、citation、cost、429/5xx。运行库尚未持久化完整 provider 状态前，MA6 不伪造这些指标。

## 3. 冻结 seam

`ResearchAgentRolloutPolicy.evaluate(HealthSnapshot)` 输出：

- `CONTINUE`：样本不足或各错误率在阈值内；
- `PAUSE_NEW_INITIAL_WAVES`：任一可靠性指标越界；
- 明确 reason codes 和观测值，便于 trace/面试解释。

`ResearchAgentRolloutGuard.evaluate()` 从数据库构造快照，并由 `ResearchAgentCoordinatorTickService` 仅在 `taskCount=0` 的初始 taskization 前调用。repair/finalization 不经过此门。

## 4. 默认阈值

| 指标 | 默认阈值 |
| --- | --- |
| terminal failure rate | 20% |
| delivery failure rate | 20% |
| rejected merge rate | 30% |
| minimum task sample | 10 |
| observation window | 15 minutes |

阈值可通过 `NOTEWEAVE_RESEARCH_AGENT_ROLLOUT_*` 调整。guard 默认开启；只允许测试或维护环境显式关闭。

## 5. 验收

1. 样本不足 fail open，避免冷启动自锁。
2. 任一指标越界暂停新的初始 wave，并输出稳定 reason code。
3. 已有 active/failed/terminal task 的 Run 仍进入原 recovery/finalization 分支。
4. 删除旧 canary service/controller/config/test 后 Backend 编译和 MA4I/J 回归通过。
5. MA5 没有真实 Provider 证据时，不声称生产 SLO 或自动 429 回退已验证。

## 6. 2026-07-17 实施记录

- 已删除 `ResearchAgentCoordinatorCanaryService`、旧 manual coordinator controller 和三项 canary allowlist 配置；
- 已实现 `ResearchAgentRolloutPolicy` 与数据库 `ResearchAgentRolloutGuard`，默认开启、15 分钟窗口、10 task 冷启动门；
- `ResearchAgentCoordinatorTickService` 只在 `taskCount=0` 前检查 guard，越界返回 `ROLLOUT_PAUSED`；active/repair/finalization 分支保持原自动恢复语义；
- 测试 profile 显式关闭 guard，避免测试历史相互污染；生产配置默认开启；
- policy/guard/Coordinator/scheduler 联合回归 `26/26` 通过。

未证明：真实生产流量、provider 429 自动暂停、p95/SLO。Provider 指标当前由 MA5 immutable benchmark 归档，不伪装成运行库实时指标。
