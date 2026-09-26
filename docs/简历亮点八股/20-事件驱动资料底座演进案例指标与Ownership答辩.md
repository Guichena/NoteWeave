# 事件驱动资料底座：演进、案例、指标与 Ownership 答辩

> 默认主回答见[资料底座与异步投递一体化面试手册](33-资料底座与异步投递一体化面试手册.md)。本文只负责同步上传到 Outbox/Kafka/Projection 的演进事件、理想压测、故障窗口和 Ownership，跨服务字段与状态仍以 API 契约和当前源码为准。

> 本文补充两篇 `03-事件驱动资料底座` 主文档。`[当前实现]` 只表示源码、配置、迁移或测试可证明；`[演练案例]` 和 `[理想压测数据]` 不能写成生产结果。

## 1. 一句话定位

我把学校资料上传从同步文件接口演进为以 MySQL 状态为真源、MinIO 保存不可变对象、Kafka 承载异步命令、Elasticsearch 保存可重建投影的资料生命周期；通过 Transactional Outbox、业务幂等、Lease/Fencing、版本门禁、DLQ 和对账处理跨存储的最终一致性。

## 2. 真实 Git 线与设计演进线

### 2.1 Git 可以证明的节点

| 日期 | Commit | 可验证变化 |
|---|---|---|
| 2026-07-03 | `d869a603` | Workspace、Upload 与 RAG 资料主链启动 |
| 2026-07-03 | `9e0f235c` | File Object 复用与身份加固 |
| 2026-07-03 | `e8ad93c6` | 分片上传 MD5 校验 |
| 2026-07-03 | `d09e3b14` | Wiki/Source 生命周期和关系信号 |
| 2026-07-20 | `9bcd3110` | Retrieval Projection、补偿、回填与 Release Gate |
| 2026-08-05 | `fd60682a` | Kafka、对象存储、安全和运行时故障语义加固 |

### 2.2 设计演进复盘

```text
V0 同步上传、解析、切片
  -> V1 对象先落地，解析异步任务化
  -> V2 业务状态与 Outbox 同事务
  -> V3 At-least-once + 业务幂等状态机
  -> V4 Source/Snapshot/Chunk/Projection 分层
  -> V5 Lease、Claim Token、指数退避和 DLQ
  -> V6 版本化 ES 投影、补偿、Alias 和对账
  -> V7 删除可见性与物理清理分离
```

这不是七次生产发布。它是从已实现的故障窗口反推出来的设计因果链。

## 3. 为什么最初同步方案不够

学校原型里，一份 PDF 上传后可以同步解析并返回成功，代码最短、调试最简单。但 100 页 PDF 的解析、切片、Embedding 和 ES 写入会占住 HTTP 线程；任一后置步骤失败，接口要么整体超时，要么已经写入对象却告诉用户失败。用户重试又可能生成重复 Source。

因此“上传成功”被拆成三个不同承诺：

1. 文件对象已可靠接收并完成完整性校验。
2. Source/Snapshot 与解析 Task 已创建。
3. 检索 Projection 已完成并可供回答使用。

异步不是为了显得架构复杂，而是为了把不同耗时、失败责任和重试策略分开。

## 4. 为什么选 Polling Outbox

| 方案 | 优点 | 当前没选的原因 |
|---|---|---|
| 应用先写 DB 再发 Kafka | 简单 | DB 提交后进程崩溃会永久漏消息 |
| XA/2PC | 理论强一致 | Kafka、对象存储、ES 很难形成统一 XA，耦合和阻塞大 |
| Kafka Transaction | Kafka 内事务能力强 | 不能原子提交普通 MySQL 业务事务 |
| CDC/Debezium | 低延迟、少轮询 | 增加 Binlog 权限、Connector、Schema 演进和运维面 |
| Polling Outbox | 业务状态与命令同一 MySQL 事务，可审计、易本地复现 | 有轮询延迟和表扫描成本 |

当前规模和单人维护成本下，Polling Outbox 更合适。若 `Oldest Ready Age` 和扫描数据库负载持续超预算，再以数据触发 CDC，不因为 CDC 更时髦就迁移。

## 5. 一个文件讲完整

`[演练案例]` 教师上传 86 页“数据库事务与恢复”PDF 文件，中途网络抖动，解析后 ES 第一次写入成功但 Finalize 事务失败。

### 5.1 接收与完整性

Upload 层先校验身份、Workspace、大小、扩展名、声明 MIME 与实际内容；分片使用 MD5/摘要校验，最终对象使用稳定 digest。`file_object` 表示物理对象，`source` 表示业务资料，`source_snapshot` 固化一次可引用版本。相同二进制可以复用 File Object，但不同 Workspace 的 Source 和 ACL 仍独立。

客户端看到的 Complete Upload 只表示上传阶段完成，不表示资料已经可检索。

### 5.2 同事务创建事实和命令

Backend 在 MySQL 事务里创建 Source、Snapshot、Task 与 `task_outbox`。若事务回滚，命令也不存在；若事务提交但进程崩溃，Outbox 仍为 `READY`，后续 Dispatcher 能接管。

### 5.3 Claim、发布和重复

`[当前实现]` `DurableOutboxDispatcher` 用条件更新 Claim 候选，将状态置为 `PROCESSING`，写入唯一 Delivery Token、Lease Until 并增加 Attempt。普通 Lease 为 1 分钟，最多 5 次；指数退避 60 秒封顶并加入最多约 20% Jitter。

Kafka 已收到消息但 Outbox 确认前进程崩溃时，Lease 过期后会再次发布。系统接受重复投递，Consumer 根据 Task/Source/Snapshot 状态判断：仍为 `PENDING` 才处理；已经完成、删除或被新版本替代则 Ack/Skip，不重复推进业务终态。

### 5.4 解析与投影

Consumer 从 MinIO 按 Snapshot Object Key 读取原文件。`SourceParseService` 加锁确认 Snapshot 仍是 `PENDING` 且 Source 未删除，再生成 Chunk、Window、摘要和元数据。当前默认 Chunk Size 900、Overlap 120；它们是配置基线，不是解析质量的最优结论。

解析完成后再创建 Retrieval Projection Outbox。ES 写入成功但 MySQL Finalize 失败时，补偿或重试使用相同 Snapshot 和 Projection Identity；不会创建第二个业务 Source。只有当前 Snapshot 的 QA Chunk 与 Note Source 投影满足门禁，才进入 READY。

### 5.5 删除与晚到消息

若教师在解析过程中删除资料，MySQL 先提交不可见/删除事实。晚到 Consumer 读取 `SourceParseAssessment` 后发现 `TARGET_DELETED` 或非 `PENDING`，不得重新解析并恢复可见性。MinIO/ES 的物理删除放在 Post-commit Cleanup，失败可重试；不能在数据库事务提交前删除外部对象，否则 DB 回滚后原文件已不可恢复。

## 6. 三个提交点与三种“成功”

```text
DB Commit Success
  != Kafka Broker Ack
  != Consumer Business Completion
  != Projection Visible
```

Outbox `SENT` 只证明发布边界完成；Kafka Offset 提交只证明当前 Consumer Record 已被框架接受；Source `READY` 才表示业务投影可使用。面试时把它们说成一个 Exactly-once 会被直接追穿。

## 7. 为什么 Claim Token 不能省

只用 `status=PROCESSING` 和 Lease 会发生 ABA：Dispatcher A Lease 过期，B 接管并成功发布；A 恢复后再写“发送成功”，可能覆盖 B 的新状态。每次 Claim 生成不可猜测 Token，完成/失败更新必须带 `where lease_owner = ?`。影响行数为 0 表示旧 Owner 已失去资格，只能停止，不能继续确认。

这与 Fencing Token 的核心思想相同：不是阻止旧进程运行，而是让业务提交点拒绝旧身份。

## 8. 重试、DLQ 和补偿的边界

| 机制 | 解决什么 | 不解决什么 |
|---|---|---|
| Retry | 暂时网络错误、429、短期依赖不可用 | 永久坏文件、Schema 不兼容 |
| DLQ | 隔离 Poison Message，保留人工诊断入口 | 自动修好业务数据 |
| Redrive | 修复原因后，以受控身份重新投递 | 跳过幂等与版本校验 |
| Compensation | 前一步外部副作用已成功、后续事实失败 | 提供跨系统瞬时强一致 |
| Reconciliation | 周期发现“DB 说有、对象/投影没有”等漂移 | 替代正常事务路径 |

当前 Retry Backoff 是有界指数加 Jitter，最大 5 次。不可重试错误应尽早终止，避免一个扫描损坏 PDF 持续阻塞 Partition。

## 9. 指标怎样算

```text
Upload Acceptance Rate = 被接受上传数 / 合法上传请求数

Source Ready Success Rate = 在观察窗口内进入 READY 的 Snapshot 数 / 已接受 Snapshot 数

Time to Searchable = Projection READY 时间 - Upload Accepted 时间

Projection Lag = 当前时间 - 最早未完成 Projection 的业务创建时间

Duplicate Side-effect Rate = 产生重复外部副作用的 Operation 数 / 重放 Operation 数

DLQ Rate = 进入 DLQ 的消息数 / 消费消息数

Redrive Recovery Rate = Redrive 后成功收敛数 / Redrive 数

Deletion Convergence = 最后一个派生副本不可见时间 - 删除事务提交时间

Reconciliation Drift Rate = 对账不一致项 / 被检查实体数
```

Kafka Lag 是消息条数，不等于 Time to Searchable。需要同时看最老事件年龄、各阶段 P95 和 Source 状态漏斗。

## 10. 理想故障注入与容量结果

以下全部为 `[理想压测数据]`，只定义目标输出格式：

| 场景 | 样本/注入 | 目标结果 | 必看指标 |
|---|---:|---|---|
| DB 提交后进程退出 | 1,000 次中注入 100 次 | 100/100 最终发布，无业务命令丢失 | Oldest Ready、重复发布数 |
| Broker Ack 后确认丢失 | 100 次 | 允许重复消费，0 次重复业务终态 | Idempotent Replay、Side-effect Duplicate |
| 旧 Lease Owner 晚到 | 100 次 | 100/100 被 Token/Fencing 拒绝 | Lost Claim Count |
| ES 写后 Finalize 失败 | 100 个 Snapshot | 100/100 经补偿收敛到同一版本 | Projection Lag、Current Version Error |
| 删除与解析竞态 | 100 次 | 0 次资料复活，在线不可见优先完成 | Resurrection Count、Cleanup Lag |
| Poison PDF | 50 个 | 有界重试后 DLQ，不阻塞正常资料 | DLQ Age、Partition Progress |

`[理想压测数据]` 假设稳定到达 20 个文件/秒、Worker 有效处理 25 个/秒，净清空速率为 5 个/秒；积压 10,000 个需约 `10,000/5=2,000 秒`。如果到达率等于处理率，增加 Outbox Batch 只会搬移积压，不会清空。

## 11. 学校业务价值

`[演练案例]` 统计一个学期资料：上传到可搜索中位时间、失败后恢复时间、教师重复上传次数、资料搜不到工单、旧版本引用数和删除后仍可见事件。可以计算：

```text
Manual Recovery Reduction = (旧流程人工恢复数 - 新流程人工恢复数) / 旧流程人工恢复数

Duplicate Upload Reduction = (旧重复上传率 - 新重复上传率) / 旧重复上传率

Fresh Citation Rate = 引用当前 Snapshot 的回答数 / 有引用回答数
```

这些需要真实历史数据。没有数据时，简历只能写“建立可计算指标与恢复闭环”，不能写节省多少人天。

## 12. 成本与容量

资料生命周期成本应按一份成功可检索资料核算：对象存储、解析 CPU、Chunk 数、Embedding 字符、ES 文档、Kafka 重放和双索引回填。失败重试成本也要计入：

```text
Cost per Ready Snapshot = 总资料链路成本 / READY Snapshot 数

Retry Amplification = 实际执行 Attempt 数 / 唯一业务 Operation 数

Storage Amplification = 原文件 + Snapshot + Chunk + Vector + 回滚索引字节 / 原文件字节
```

Chunk Overlap 增大能改善边界语境，也线性增加向量与 ES 文档成本；版本化 Alias 提供回滚，但切换期间需要双份索引。二者都是明确 Trade-off。

## 13. 外部依据和宣传口径

Transactional Outbox 解决数据库事实与消息意图的原子记录；Debezium Outbox Router 展示 CDC 方式；Kafka 的 Exactly-once 语义主要覆盖 Kafka 读写事务，不自动覆盖 MySQL、MinIO 和 ES；Elasticsearch Alias 支持索引版本切换。详细来源见[资料底座演进取舍外部依据](../research/资料底座演进取舍外部依据.md)。

云厂商通常宣传吞吐、端到端延迟、可用性、持久性和恢复；文档解析产品宣传页数/秒、字段或表格准确率、Cost per Page。NoteWeave 应对齐指标形式，但不能引用厂商 SLA、ParseBench 或 Kafka Benchmark 作为本项目成绩。

## 14. 简历表达

### 14.1 当前可写

> 设计事件驱动资料生命周期，以 MySQL Source/Snapshot 为业务真源、MinIO 保存不可变对象、Kafka 承载解析/投影命令、Elasticsearch 保存可重建索引；通过 Transactional Outbox、Lease/Claim Token、业务幂等、指数退避、DLQ/Redrive 和版本门禁覆盖 DB Commit、Broker Ack、Consumer Completion 与 Projection Visible 的故障窗口。

> 将资料删除拆为事务内撤回可见性与 Post-commit 物理清理，旧 Snapshot 晚到消息由状态与版本条件拒绝；建立 Time to Searchable、Projection Lag、重复副作用、删除收敛和对账漂移指标。

### 14.2 实测后才能写

> 在 `{环境/提交}` 的 `{N}` 份、`{大小/页数分布}` 资料故障注入中，`{X}` 次 DB/Kafka/ES 中断全部恢复，重复业务终态为 `{Y/N}`，Time to Searchable P95 为 `{Z}`，积压恢复斜率为 `{R}` 个/秒。

### 14.3 禁止写

- “实现 Exactly-once”。
- “支持 50 QPS”，Outbox Batch 50 不是吞吐。
- “Kafka 保证消息不丢”，没有 Broker 配置、保留、副本和故障测试限定。
- “多存储强一致”，当前是可观测、可补偿的最终一致。

## 15. Ownership 与压力追问

### 15.1 AI 写了代码，你做了什么

我定义 Source/Snapshot/Projection 的事实边界，枚举 DB Commit、Broker Ack、Consumer Commit、ES Finalize 和 Delete 的故障窗口，选择 Polling Outbox 而非 XA/CDC，决定 Claim Token 与版本条件，审查 SQL 影响行数和状态迁移，并用重复投递、旧 Owner 晚到和投影补偿测试验收。AI 是实现加速器，不对一致性结论负责。

### 15.2 最难的不是 Kafka 吗

不是。最难的是给每个提交点定义“结果已知还是未知”、谁是真源、谁可以重放、旧身份如何失效，以及外部副作用完成但本地确认丢失时怎样对账。Kafka 只解决其中一段传输。

### 15.3 为什么不用 Exactly-once

因为业务跨 MySQL、MinIO、Kafka 和 ES。即使 Kafka 内 EOS 成立，外部对象和数据库状态仍需幂等与补偿。项目追求的是 At-least-once Delivery 加业务 Effectively-once，而不是无法兑现的全局 Exactly-once。

### 15.4 最大风险

Polling 扫描和状态表会随积压增长，解析任务成本差异会造成 Head-of-line Blocking，对账若无运营入口也只是在报警。扩规模前要按 Task Type 分区/隔离、建立 Oldest Age 告警、Redrive 审批和对账修复 Receipt。

## 16. 面试官二次审查

| 审查面 | 已补齐 | 仍缺的真实证据 |
|---|---|---|
| 演进因果 | 同步、Outbox、幂等、投影、删除主线 | 每阶段真实事故工单 |
| 完整案例 | 上传、解析、投影失败、删除竞态 | 真实学校文件执行记录 |
| 算法知识 | Claim CAS、Token、Backoff、积压斜率 | MySQL 锁与真实 Kafka 集成压测 |
| 指标 | 可靠性、延迟、成本、业务价值公式 | 生产窗口分子分母 |
| 外部比较 | Outbox、CDC、Kafka、ES 口径 | 相同硬件和负载的公开可比结果 |
| Ownership | 个人决策与 AI 边界 | 面试现场手画时序与 SQL 条件 |

当前材料足以讲清可靠性设计，但没有真实生产流量、跨可用区灾备、长期对象耐久性和 SLO 达成证据。面试中应把强项落在故障建模、状态真源和恢复闭环，不虚构可用性百分比。
