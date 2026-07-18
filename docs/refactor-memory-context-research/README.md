# Memory、Context 与 Research 重构 Spec

本目录把 `docs/Memory-Context-Research重构设计.md` 转换为可执行、可验收的阶段规范。设计文档负责目标架构；这里负责施工顺序、测试 seam、迁移兼容和完成证据。

## 阶段

| 阶段 | 目标 | 状态 | 入口文档 |
|---|---|---|---|
| Phase 0 | 固定术语、不变量、Golden Scenarios 和测试基线 | 进行中 | `phase-0-1-conversation-turn.md` |
| Phase 1 | Research 接入 Conversation，建立统一 Turn 提交账本与三段事务恢复 | 进行中 | `phase-0-1-conversation-turn.md`、`phase-1-transaction-recovery.md` |
| Phase 2 | RunInputSnapshot、RetrievalConfig 和 Evidence 固化 | 进行中 | `phase-2-snapshot-retrieval-evidence.md` |
| Phase 3 | Segment Summary Revision 与长上下文 | 待开始 | 待 Phase 2 完成后建立 |
| Phase 4 | Canonical MemoryRuntime | 进行中 | `phase-4-memory-runtime.md` |
| Phase 5 | Artifact 显式上游输入 | 待开始 | 待 Phase 2 Snapshot 可复用后建立 |
| Phase 6 | 事件、评测与旧路径删除 | 待开始 | 待双写/迁移观测完成后建立 |

## 固定领域语言

- Workspace：个人研究项目和租户隔离边界。
- Conversation：一条连续、可恢复的消息线程，不绑定回答模式。
- Message：不可原地改写的用户/助手原始消息账本。
- Turn：由一条用户消息锚定的逻辑交互，不新增通用业务表。
- Submission：只承载幂等和恢复信息的提交账本。
- Run：本轮执行记录；AnswerRun 与 ResearchRun 保持独立。
- Context：由历史消息、摘要和显式 pin 编译出的会话连续性输入。
- Memory：跨会话仍有价值的偏好、约束、确认决策和回忆入口。
- Evidence：一次 Run 实际使用的事实依据；Trace 不是 Evidence。
- Report：Research 的版本化正式结果；只有显式保存后才成为 Source。

## Spec 规则

1. 每个阶段先固定公开 seam 和可观察行为，再写失败测试。
2. 每次只实现一条纵向切片；测试不绑定内部协作者或 SQL 调用次数。
3. 新旧入口并存期间，必须明确唯一数据归属和删除旧路径的门槛。
4. 阶段完成需同时给出自动化测试、迁移验证和残余风险。
