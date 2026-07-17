# Research Agent MA4J：Synthesis、Report 与原子终态收口执行方案

> 状态：F1–F4b `IMPLEMENTED / VERIFIED`；MA4J 在简历项目约束内 `COMPLETE`（2026-07-17）。OS process-kill / Kafka isolation 保留为可选外部证明，不作为本阶段退出门。  

## 1. 公共 seam 与不变量

唯一内部入口为 `finalizeIncrementalRun(runId)`，不接收 Worker 提供的报告、citation、状态或 workspace scope。它在 `research_run FOR UPDATE` 后从 canonical ledger 派生标题、Markdown 与 citation gate；同一 Run 的重复调用必须重放同一 receipt。

- gate 只接受 `VERIFIED` cell、其 immutable evidence/citation lineage 和无 active/failed agent task 的 terminal wave；不通过不写 report、不把 Run/父 task 标为完成。
- report persistence、`research_run.status=COMPLETED`、父 `task.task_status=COMPLETED`、final trace/outbox 在一个数据库事务内；任何失败整体回滚。
- 不复用 `completeFromWorker` 作为 incremental 收口入口，避免 Worker 绕过 canonical ledger/citation gate。
- 终态后不可重写 report；response-loss/重复 scheduler 只能返回既有 receipt。

## 2. TDD 垂直切片

1. **F1 gate**：verified ledger 可生成 deterministic report；存在 active/failed/unverified cell 时拒绝且零终态写入。
2. **F2 atomic finalization**：通过 gate 后一次写 report、Run、父任务和 trace；注入 report write 后故障证明全回滚。
3. **F3 replay**：commit 后重复 finalize 返回同一 title/digest，不新增 trace/artifact、不覆盖 markdown。
4. **F4a durable artifact**：以 V050 将 immutable report artifact、稳定 generation identity/digest 与 Run/父任务放入同一事务。
5. **F4b automatic handoff**：Coordinator 在 terminal verified ledger 自动调用 finalizer，并以 Run 锁保持与并发 scheduler 的线性化。
6. **可选外部证明**：若后续需要提升到生产声明，再扩展 coordinator SIGTERM/Kafka 夹具；当前以 deterministic pre-commit fail-stop、run-lock 并发和 durable scheduler replay 作为简历项目证明。

## 3. 边界

本阶段不做真实 LLM synthesis、外网 evidence archive、质量 A/B、成本账单或生产灰度；这些分别属于 MA5/MA6。MA4J 的 report 是从已持久化 canonical ledger 的 deterministic rendering，不能据此声称真实 provider 质量。

## 4. 已验证证据

| 切片 | 覆盖 | 证据 |
| --- | --- | --- |
| F1 | `INCREMENTAL_V1` 的 verified + evidence-bound ledger 才能 finalization；一次性将 report、Run、父 task、trace 收口；重复调用重放而不新增 trace。未验证 ledger 零终态写入。 | MySQL 8.4 / Flyway v048：`ResearchAgentIncrementalFinalizationServiceTest` 2/2 green，`scripts/ma4j/evidence/noteweave-ma4j-f1-20260717t1810a-20260717T101647Z`，manifest `VERIFIED`，项目资源已清理。 |
| F2 | report/Run 更新后、父 task 完成前的 deterministic fail-stop 会回滚 report、Run 和父 task；后续 retry 可正常收口。 | MySQL 8.4 / Flyway v048：同一测试类 3/3 green，`scripts/ma4j/evidence/noteweave-ma4j-f2-20260717t1830a-20260717T102354Z`，manifest `VERIFIED`，项目资源已清理。 |
| F3 | 两个并发 finalizer 在 `research_run FOR UPDATE` 上线性化；只生成一份报告和一条终态 trace，另一调用重放相同 Markdown。 | 强制无缓存重建后，MySQL 8.4 / Flyway v048：同一测试类 4/4 green，`scripts/ma4j/evidence/noteweave-ma4j-f3b-20260717t1850a-20260717T103231Z`，manifest `VERIFIED`，项目资源已清理。 |
| F4a | V050 新增数据库原子 `research_agent_report_artifact`；稳定 artifact ID、generation key 与 SHA-256 digest 和 Run/父任务同事务提交；重放/并发返回同一 artifact。 | 首轮内容等价迁移 4/4 green；因并行工作区新增 turn-submission migration，artifact migration 已重编号为 V050，当前完整 Flyway 链的聚合复验列入 F4b 最终退出门。 |

补充确定性门禁：finalizer 不依赖数据库默认 collation，而按 unsigned UTF-8 排序 verified cell；Markdown 表格对反斜杠、竖线和 CR/LF 做确定性转义。`ResearchAgentIncrementalFinalizationServiceTest` 当前 5/5，避免同一 ledger 在不同排序规则下产生不同 report digest，也避免证据或候选文本破坏表格列结构。
| F4b | terminal wave 无 active/current-failed task 且全部 cell 为 evidence-bound `VERIFIED` 时，Coordinator tick 自动调用 finalizer；Run、父 task 与唯一 artifact 收口。 | MySQL 8.4、当前 Flyway 链（本轮 artifact 为 V050，用户 turn-submission 为 V060）：`ResearchAgentTaskCoordinatorServiceTest#shouldAutomaticallyFinalizeATerminalCitationGatedLedger` 1/1 green；`scripts/ma4j/evidence/noteweave-ma4j-f4b-green-20260717T111511Z`，manifest `VERIFIED`，项目资源已清理。 |
| MA4J 聚合退出门 | finalization、artifact/digest、fail-stop rollback、并发 replay、自动 terminal handoff、Coordinator wave/repair 与 scheduler enable/disable contract 同轮回归。 | MySQL 8.4、当前 Flyway 链：`ResearchAgentIncrementalFinalizationServiceTest` 4/4、`ResearchAgentTaskCoordinatorServiceTest` 21/21、`ResearchAgentCoordinatorSchedulerTest` 4/4，合计 29/29 green；`scripts/ma4j/evidence/noteweave-ma4j-f4-final2-20260717t1930a-20260717T113119Z`，manifest `VERIFIED`，项目资源已清理。 |
