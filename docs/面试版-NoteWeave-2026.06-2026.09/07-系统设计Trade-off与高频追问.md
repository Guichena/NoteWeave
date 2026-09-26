# 系统设计 Trade-off 与高频追问

## 1. 系统设计题的统一答法

遇到“你会怎么设计”时，先把问题收敛成五个约束：

1. 数据是否需要追溯到某个不可变版本。
2. 任务是秒级请求、异步流水线，还是可恢复长任务。
3. 模型输出是否允许直接产生业务副作用。
4. 失败后能否重试，还是必须先对账未知结果。
5. 质量、安全和成本中，哪个是当前第一优先级。

然后按“最小可用方案 → 当前方案 → 规模扩大后的升级条件”回答，不把所有高级组件一次性塞进架构。

## 2. 横向取舍表

| 问题 | 最小方案 | 当前方案 | 升级条件 |
| --- | --- | --- | --- |
| 文件处理 | 同步解析 | MinIO + Kafka 分阶段任务 | 处理吞吐和租户公平成为瓶颈时扩展 Worker 池 |
| 检索 | 关键词或单路向量 | BM25 + Vector + RRF + Rerank | 向量规模或 P95 成主瓶颈时拆专用向量库 |
| Agent | Prompt 或固定 Workflow | Schema + Skill Graph + Verifier | 跨天 Timer、人工审批成为主路径时上工作流引擎 |
| 状态 | 单表状态字段 | Run / Task / Attempt + Checkpoint | 审计重放成为主要查询时评估事件溯源 |
| 一致性 | 数据库后发消息 | Transactional Outbox + 幂等消费 | 表和下游显著增多时评估 CDC |
| 缓存 | Redis 直接返回 | Redis 加速，读时回 MySQL | 热点访问成为主要瓶颈时做分层缓存和失效广播 |
| Memory | 最近 N 条 | Topic Window + Summary + Candidate Gate | 规模扩大后再做更细的用户级记忆检索 |

## 3. 安全与权限

权限不能只在 Controller 校验一次。至少有四层：入口校验 Workspace，检索前做 Scope Filter，命中后回库校验 Source / Snapshot，Worker 写回时校验 Lease、Version 和 Capability。缓存命中、ES 命中和模型生成都不能跳过最后的归属检查。

高风险能力默认 Fail Closed：配额服务不可用时不启动高成本 Research / Artifact；ACL 和版本缓存不可用时回源 MySQL；SSE 或缓存空结果不能解释成“没有权限”或“没有事件”。

## 4. 可观测性与评测

不要只看 HTTP 200 和模型满意度。按用户旅程拆指标：

- 资料：上传成功率、解析耗时、Snapshot READY 延迟、Outbox Oldest Ready、Kafka Lag、死信数量。
- QA：召回覆盖、Evidence 支撑、拒答正确性、越界引用、P95 延迟。
- Research：有效 Cell 完成率、必填字段覆盖、引用支撑率、冲突发现率、恢复成功率、预算消耗。
- Artifact：Schema 通过率、Repair 次数、文件 Manifest 完整率、交付成功率。
- Context / Memory：输入 Token、摘要覆盖、关键约束保留、误晋升和撤销传播。

评测必须保留基线、变更策略、数据集版本和失败样本。只说“准确率提升”无法定位是召回、排序、生成还是拒答策略发生了变化。

## 5. 容量推导面试卡

### 5.1 资料处理

```text
所需吞吐 = 单文件平均大小 × 每小时文件数 ÷ 处理窗口
Worker 数 ≈ 目标吞吐 ÷ 单 Worker 稳态吞吐
```

解析、Embedding 和索引写入的吞吐不同，按最慢阶段配置队列和并发。`128 MB` 是单文件边界，不直接推出 QPS；需要根据实际 PDF 页数、切片数、Embedding 批大小和 Provider 延迟压测。

### 5.2 Research / Artifact

长任务容量受三个上限同时约束：Workspace 并发、Provider 速率、单任务预算。只提高 Worker 数可能把瓶颈推给 Provider 或 MySQL。用 Admission Reservation 控制接纳，用 Active Lease 控制执行，用 Budget Ledger 控制单任务消耗，三种生命周期不要混成一个计数器。

## 6. 高频场景题

### 场景 A：Worker 处理一半宕机

先通过任务状态和 Lease 判断是否是未知结果。Lease 过期后只重试未完成的阶段，成功 Receipt 的外部调用不重复执行。旧 Worker 恢复后携带旧 Fencing Token 写回会被拒绝。

### 场景 B：资料更新后搜索仍返回旧内容

先查 MySQL 当前 Snapshot 和删除意图，再查 ES generation、Indexer Receipt 和缓存。新版本先成为真源，旧投影不允许继续作为有效证据；必要时通过 Outbox 重放和索引重建恢复投影。

### 场景 C：Research 结果很完整，但引用支撑率下降

冻结同一评测集，按召回、Rerank、Evidence Selection、Verifier 和生成分别做消融。先检查是否使用了错误 Snapshot 或 Memory 混入 Evidence，再决定调 TopK、窗口或拒答阈值，不能只换更大的模型。

### 场景 D：一个大任务占满所有资源

将速率、并发、预算和公平调度分开治理。大任务进入排队，Active Permit 按 Workspace 和 Workload 隔离，QA 等短请求保留独立资源。必要时按任务风险和预计成本做加权公平，而不是简单 FIFO。

## 7. 一句话收尾

NoteWeave 的核心不是“用了哪些 AI 组件”，而是把不确定的模型执行装进确定的系统边界：资料有版本，任务可恢复，检索可引用，产物有 Schema，Memory 不污染事实，失败能够被解释和继续处理。

