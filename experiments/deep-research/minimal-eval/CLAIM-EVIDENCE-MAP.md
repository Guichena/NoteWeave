# 简历主张—证据映射

| 简历主张 | 代码/数据落点 | 自动化证据 | 真实验收证据 |
| --- | --- | --- | --- |
| Table-as-State 显式维护 Cell、Evidence 与结论 | `research_matrix_cell`、Candidate/Evidence canonical ledger；Coordinator tick | Research 包全量测试；正常与缺失题 | 正常题 Run 的 Matrix、正式 Claim 引用链 |
| 局部 Replan 控制研究漂移 | `ResearchAgentLocalReplanRecorder`、`research_agent_replan_audit`、`LOCAL_REPAIR` | 两 Cell 测试证明 VERIFIED Cell 不重开 | 至少 1 条真实 audit；前后 Task 与调用计数 |
| 证据校验阻止无支持结论 | Java canonical qualification；`ResearchAgentHonestReportService` | quote 篡改、三类终态测试 | quote 篡改题中 Candidate 未提升、报告不含该 Claim |
| 受限反证处理来源冲突 | Cell recovery policy、冲突 reason code、repair budget | 冲突策略测试 | 冲突题的 verifier 决策、有限 repair 次数和诚实终态 |
| Checkpoint / Task 恢复 | Task reclaim、archive inventory、usage ledger | Worker 7/7 + Backend 8/8 恢复测试 | Worker kill、接管 Execution、恢复前后调用计数 |
| Execution Fencing 拒绝旧写入 | execution grant / fencing token | 旧 completion 不改变 canonical state 的测试 | 延迟 callback 的拒绝码与前后 DB 快照 |
| 引用审计支持逐项回溯 | report Citation audit；exact quote / snapshot / URL | verified / limitations / insufficient 三类报告测试 | 每条正式 Claim 的引用点击与 DB 抽查 |

状态口径：代码存在用 `[当前实现]`；模拟或夹具通过用 `[已测-模拟]`；只有保存真实 Run ID 与证据文件后才能改成真实验证事实。
