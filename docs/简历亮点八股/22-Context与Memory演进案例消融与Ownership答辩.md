# Context 与 Memory：演进、案例、消融与 Ownership 答辩

> 默认主回答见[Context 与 Memory 一体化面试手册](34-Context与Memory一体化面试手册.md)。本文只负责从窗口、摘要到受控 Memory 的演进，长期案例、行业对照、消融和 Ownership；当前撤销传播边界仍以默认入口和详细设计的校准说明为准。

> 本文补充两篇 `05-Context与Memory` 主文档。`[当前实现]` 表示源码或测试可证；`[演练案例]`、`[理想消融数据]` 与 `[生产待验证]` 必须分开。

## 1. 一句话定位

我把上下文工程拆成三条时间语义：Conversation Ledger 保存发生过什么，Summary/Segment 压缩较旧历史，RunInputSnapshot 冻结某次运行看到了什么。长期 Memory 再按信任来源分流：显式低风险用户偏好可以直接创建窄 Scope、可撤销的 Revision；模型或行为推断走 Signal、Candidate、Review 和 Promotion，不让模型输出自动变成跨会话事实。

## 2. Git 事实线与设计演进线

### 2.1 Git 可验证节点

| 日期 | Commit | 可验证变化 |
|---|---|---|
| 2026-07-05 | `307138bc` | Memory 与 Agent Foundations |
| 2026-07-19 | `3b743598` | v2 重建阶段，Conversation/Run 边界收敛 |
| 2026-08-05 | `fd60682a` | Memory Compiler、运行快照、事件回放与安全边界加固 |

Flyway `V074` 创建 `run_input_snapshot`，`V077-V078` 创建 Conversation Segment 与 Summary Revision，`V080` 创建 Canonical `memory_item`、`memory_runtime_revision` 和 `memory_event`。这些迁移能证明数据结构，不证明真实用户 Memory 效果。

### 2.2 设计演进复盘

```text
V0 最近 N 条消息
  -> V1 较旧历史做 Summary
  -> V2 Segment + Immutable Summary Revision
  -> V3 Context Compiler + Token Ledger
  -> V4 RunInputSnapshot 冻结一次运行输入
  -> V5 对话信号只创建 Memory Candidate
  -> V6 Review + Immutable Revision + Conflict/Revoke
  -> V7 Scope/Neighborhood/Utility 预算编译 Control Pack
  -> V8 Outcome Feedback、Stale 与 Shadow Recall
```

这是一条设计因果线，不是八次生产发布。

## 3. Context、Summary、Snapshot、Memory 和 Evidence

| 对象 | 回答的问题 | 生命周期 | 是否可作为事实证据 |
|---|---|---|---|
| Conversation Ledger | 用户和系统实际说过什么 | 会话内追加 | 用户消息是输入事实，模型消息不是外部事实 |
| Segment/Summary | 较旧历史如何压缩 | 有覆盖区间和 Revision | 只作上下文摘要，可能丢信息 |
| Raw Tail | 最近连续消息是什么 | 随 Head 推进 | 保留精确局部语境 |
| RunInputSnapshot | 某次 Run 当时看到了什么 | Run 级不可变 | 用于复现输入，不证明输出正确 |
| Memory | 跨运行可复用的偏好、决策、约束 | 候选、审核、Revision、撤销 | 只控制行为，不替代资料证据 |
| Evidence | 当前问题的事实支持来自哪里 | 与 Run/Snapshot 绑定 | 是外部事实引用来源 |

把 Memory 放进 Evidence Rerank 会形成自证：一次模型推断被记住，下一次又因相似度高成为“证据”。当前 Compiler 明确写入“Memory 不作为事实来源”，Research 和 Artifact 也要求结论回到资料或验证证据。

## 4. 为什么最近 N 条是合理起点又不够

最近 N 条实现简单、不会产生摘要模型错误，短会话应优先使用。但长会话中可能把早期“只使用 2026 版课程规则”的约束挤出窗口，也可能在用户切换话题后保留大量无关消息。

Topic-aware Context 需要同时保留：已完成 Segment 的受控 Summary、当前主题必要历史、连续 Raw Tail 和当前 Query。Raw Tail 必须连续，因为选择性抽取最近消息可能漏掉否定、纠正和指代链；“第二个不要，改第一个”不能只保留“改第一个”。

## 5. Summary 为什么要版本化

自由覆盖一行 Summary 会丢失它覆盖的消息范围、生成时的 Conversation Head 和旧版本。并发下，Summary Worker 读取 Seq 1-40，用户已写到 Seq 45；若 Worker 晚到后直接标为当前 Summary，可能让 41-45 被错误认为已压缩。

正确 Promotion 条件至少包括：

```text
expected_segment_range matches
and source_head matches expected_head
and previous_revision matches current_revision
and status = BUILDING/READY 的合法迁移
```

不满足则标记 STALE，不覆盖当前可见 Revision。Stale Build 是正确拒绝晚到结果，不应全部算系统失败。

## 6. 为什么 Run 创建时冻结 Input Snapshot

用户提交问题后，新的消息、Summary Promotion、Memory Review、Source 更新都可能发生。Retry 如果重新编译“当前上下文”，就不再是同一次 Run：模型、Evidence 或答案变化时无法区分随机性和输入漂移。

`run_input_snapshot` 绑定 Conversation Head、Summary Revision、Raw Tail、Memory Revision/Pack、Source Scope 和 Run 身份。重试读取冻结输入；新信息需要新 Run。删除或合规撤销后可以进入 Replay Degraded，保留身份与 Manifest，但不应为复现而恢复已删除敏感全文。

## 7. 一个 Memory 完整案例

`[演练案例]` 学校项目中，教师多次纠正：“所有数据库课程笔记都用三级标题；不要把 Wiki 页面当一手来源；术语统一写 MVCC，不翻译成多版本并发控制。”

### 7.1 Signal 和 Candidate

显式用户反馈产生 `USER_FEEDBACK` Signal，不直接改 Prompt。`MemoryCandidatePolicy` 当前给 User Feedback 的 Confidence 0.98、基础 Utility 0.90、基础 Risk 0.10；Model Inference 只有 Confidence 0.35、基础 Risk 0.65。阈值是规则基线，不是学习得到的概率。

Candidate 解析出三个 Slot：结构偏好、来源政策、术语政策，并记录 Workspace/User Scope、Task Neighborhood、Provenance 和内容 Hash。把它们塞成一大段 Profile 会让部分更新和冲突难处理。

### 7.2 去重与冲突

“统一使用 MVCC”和“术语写 MVCC”可按 Statement Matcher/相似度归为等价候选；当前等价相似度阈值基线是 0.85。若已有“统一写多版本并发控制”，则标为 `CONFLICTING_ACTIVE_MEMORY`，Risk 增加，不自动 Last-write-wins。

冲突候选进入 Review。用户确认后创建新的 `memory_runtime_revision`，`supersedes_revision_id` 指向旧版本，Memory Item 的 Current Revision 原子切换；旧 Revision 保留供审计，不继续进入新 Pack。

### 7.3 Scope 与编译

只属于该教师的表达偏好使用 USER Scope；学院统一模板规则才使用 WORKSPACE Scope。Compiler 的排序是 Scope Priority、Task Neighborhood、Utility、更新时间和稳定 ID，Chat Pack 最多 320 估算 Token，Artifact/Research 最多 480。

目标课件运行时，Compiler 选择 `COMMON + ARTIFACT + ARTIFACT_PRESENTATION` 的 Memory，把三级标题和术语政策编译成 Structure/Terminology Constraint；“Wiki 不是一手来源”进入 Evidence Policy。它们不作为课程知识正文。

### 7.4 Outcome Feedback

用户接受产物可以记录 POSITIVE，大改术语或重试记录 EDITED/RETRIED，明确否定记录 NEGATIVE。但保存、导出和重试存在大量混杂因素，只能作为弱排序信号；明确纠正和撤销才拥有修改状态的高权重。当前 Utility 启发式可以用于 Shadow Ranking：

```text
utility_new = 0.75 * utility_old + 0.25 * outcome_score
```

推荐方案不因 Utility 低自动 Revoke 或覆盖显式规则。累计不良结果只创建 Review Request 或降低 Derived Memory 的选择优先级；明确用户纠正、撤销、到期或来源失效才推进状态。上述公式是启发式 Shadow 策略，不是因果学习。

## 8. Candidate Promotion 的取舍

| 方案 | 优点 | 风险 | 当前选择 |
|---|---|---|---|
| 所有模型推断自动写入 | 召回丰富 | 污染、隐私、循环自证 | 不采用 |
| 所有内容人工审核 | 最保守 | 成本高，价值出现太慢 | 高风险/冲突项使用 |
| 风险分层门控 | 兼顾效率和风险 | 策略和解释成本 | 当前方向 |
| 只存原聊天向量 | 无抽取成本 | 更新、冲突和撤销困难 | 只可作为检索辅助手段 |

显式“记住这个”不应一律排队审核。来自可信用户动作、只影响当前 User/Workspace、内容低风险且通过敏感检查的偏好，可以直接创建 Active Revision，同时记录审计事件并提供撤销。高风险事实、权限规则、跨 Workspace 传播仍进入 Candidate/Review。普通对话和行为推断在 Background 提取，只能创建 Candidate。

## 9. Memory Ranking 不是相似度排序

向量相似只回答文本接近，不回答当前用户能否使用、事实是否有效、是否冲突、对该任务是否有用。完整选择顺序是：

```text
Scope/ACL/Status/Validity Hard Filter
  -> Task Neighborhood
  -> Utility/Recency/可选语义召回
  -> Stable Sort
  -> Token Budget Packing
```

可以把目标效用写成：

```text
U(m,q) = alpha * task_relevance
       + beta * outcome_utility
       + gamma * recency
       + delta * explicitness
       - eta * risk
       - mu * token_cost
```

当前代码实现的是确定性优先级和 Utility，不得声称已经训练上述 Learning-to-Rank 模型。

## 10. Cache 为什么必须带 State Fingerprint

只用 `workspace_id + pack_type` 缓存会在 Review、Revoke、Revision、User 或 Policy 变化后返回旧 Memory，甚至串用户。`MemoryCompiledPackCache` 的 Key 包括 Workspace、User Fingerprint、Pack Type、Request Fingerprint、Compiler Policy Version 和 Eligible State Fingerprint。

Redis 不可用时不能返回空 Pack 冒充“没有 Memory”。可以回源 DB 并标记降级；生产若无法完成权限检查则 Fail Closed。Cache Hit Rate 高不等于正确，必须看 Version Mismatch 和 Stale Read。

## 11. 指标与公式

### 11.1 Context 与 Summary

```text
Token Saving = (Raw Context Tokens - Compiled Tokens) / Raw Context Tokens

Constraint Retention = 保留的 Gold 约束数 / Gold 约束总数

Summary Stale Rate = 被正确拒绝的晚到 Build / 完成 Summary Build

Summary Freshness Lag = Promotion Time - Source Head Time

Snapshot Replay Fidelity = 关键输入 Manifest 一致的 Replay / Replay Attempt
```

Token Saving 越高不一定越好，必须与 Constraint Retention 和任务结果一起看。

### 11.2 Memory 写入与召回

```text
Promotion Precision = 最终被用户确认有价值的 Promotion / Promotion 总数

Promotion Recall = 应进入长期 Memory 且成功 Promotion 的 Signal / 应 Promotion Signal

Memory Precision@K = TopK 中真正有用 Memory / K

Update Accuracy = 新 Revision 正确替代旧事实的 Case / 更新 Case

Temporal Accuracy = 在指定时间点召回当时有效 Revision 的 Case / 时间 Case

Scope Violation = 越权 Memory / 返回 Memory

Poison Acceptance = 恶意或模型臆测进入 Active Revision / 攻击 Candidate

Revocation Convergence = 最后一个 Pack 不再包含 Memory 的时间 - Revoke Commit 时间
```

Memory Precision 通常比 Recall 更重要：漏掉一个偏好会使体验稍差，错误偏好进入每次 Prompt 会系统性污染结果。

### 11.3 Outcome 与业务价值

```text
Negative Outcome Rate = NEGATIVE + RETRIED + 高幅 EDITED / Applied Memory

Memory Lift = Metric(with memory) - Metric(without memory)

Cost per Useful Memory = 提取+审核+存储+编译成本 / 被确认有用 Memory 数

Net Token Saving = 少用的历史 Token - Summary/Memory 提取与 Pack Token
```

## 12. 理想消融实验

以下均为 `[理想消融数据]`。假设锁定 100 个长会话任务、固定模型、资料和 Prompt：

### 12.1 Context 策略

| Variant | Constraint Retention | Task Success | 输入 Token 中位数 | Snapshot Replay | P95 |
|---|---:|---:|---:|---:|---:|
| 最近 20 条 | 72.0% | 68.0% | 9,800 | 100% | 3.8 s |
| 全历史 | 94.0% | 79.0% | 31,400 | 100% | 7.9 s |
| Summary + Raw Tail | 91.0% | 82.0% | 12,200 | 100% | 4.6 s |
| Topic-aware + Summary + Snapshot | 96.0% | 88.0% | 13,100 | 100% | 4.9 s |

正确解释：全历史保留约束高但 Token/P95 显著增加；Summary + Tail 以较低 Token 接近全历史；Topic-aware 进一步改善当前任务，但收益需要排除模型和 Prompt 变化。

### 12.2 Memory 策略

`[理想消融数据]` 另用 80 个跨会话任务，其中含更新、时间、拒答和污染攻击：

| Variant | Task Success | Update Accuracy | Temporal Accuracy | Correct Abstention | Poison Acceptance | Memory P@5 |
|---|---:|---:|---:|---:|---:|---:|
| 无长期 Memory | 61.3% | 20.0% | 35.0% | 92.5% | 0/20 | 不适用 |
| 原聊天向量召回 | 72.5% | 52.5% | 55.0% | 80.0% | 6/20 | 0.68 |
| 自动抽取并写入 | 78.8% | 67.5% | 62.5% | 75.0% | 8/20 | 0.73 |
| Candidate + Review + Revision | 83.8% | 90.0% | 87.5% | 90.0% | 0/20 | 0.91 |
| + Outcome Feedback | 86.3% | 92.5% | 90.0% | 92.5% | 0/20 | 0.93 |

这里不能只报 Task Success。自动写入虽然理想平均分提高，却让 Poison Acceptance 和拒答恶化；治理方案的价值是更新、时间、污染和撤销一起变好。

### 12.3 架构与模型收益

固定旧/新模型分别测试无治理与有治理 Memory，计算 `Architecture Gain`、`Model Gain` 和 Interaction。若强模型自身能从全历史提取偏好，也仍需检查 Scope、Revoke 和 Replay；模型能力不能替代治理正确性。

## 13. Gold Set 与统计可信度

参考 LongMemEval 的信息提取、多会话、时间推理、知识更新和拒答五类任务，再增加 Workspace 隔离、Prompt Injection、撤销和 Evidence Isolation。开发集与锁定集按用户/主题切分，防止同一偏好改写泄漏。

每条 Case 至少标注：输入时间线、可见 Revision、应召回/不得召回 Memory、Expected Scope、关键约束、是否应拒答和结果 Oracle。写入评测和召回评测分开；最终 Answer 好不能反推 Promotion 正确。

二元指标报告 Wilson 区间，新旧策略做配对 Bootstrap。LLM Judge 只能评价软质量，Scope、Version、Revoke、Poison 和 Manifest 由确定性 Oracle 判断。

## 14. 外部产品与论文怎样宣传

LongMemEval 使用 500 个长期记忆问题，覆盖信息提取、多会话、时间、更新和拒答；Mem0 常宣传搜索、实体 Scope、过期、反馈和相对 Benchmark；Zep 强调时间知识图谱与事实失效；Letta 强调可见 Memory Block、只读/共享和 Context Hierarchy；LangGraph 区分 Thread Memory 与长期 Semantic/Episodic/Procedural Memory。

详细依据见[Memory 业界演化研究](../research/Memory业界演化研究.md)。NoteWeave 应复用能力分桶和指标，不移植外部产品分数。内部 Gold 只有按相同 LongMemEval 协议运行时才能公开横比。

## 15. 简历表达

### 15.1 当前可写

> 设计可回放 Context Pipeline，将 Conversation Ledger、Segment/Summary Revision、连续 Raw Tail 与 RunInputSnapshot 分离；重试读取冻结输入，晚到 Summary 通过版本条件标记 Stale，避免当前状态漂移破坏复现。

> 设计受控长期 Memory：用户/模型 Signal 先进入 Candidate，按来源置信度、冲突和风险 Review，Promotion 生成不可变 Revision；Compiler 按 Scope、Task Neighborhood、Utility 和 Token Budget 生成 Chat/Research/Artifact Control Pack，并将 Memory 与 Evidence 信任域隔离。

### 15.2 实测后才可写

> 在 `{N}` 个锁定跨会话任务上，治理型 Memory 相比无 Memory 将 Update Accuracy 从 `{A}` 提升至 `{B}`、Memory P@5 达 `{P}`，Poison Acceptance 为 `0/{M}`，输入 Token 中位数降低 `{T}`；模型、时间线和 Scope Oracle 保持一致。

### 15.3 禁止写

- “实现无限上下文”。
- “Memory 准确率 95%”，不说明写入、召回、更新还是任务结果。
- “向量库就是长期记忆”。
- “模型自动学习用户偏好”，当前是受控 Candidate/Revision。
- “删除后历史可完全复现”，合规删除可能导致 Replay Degraded。

## 16. Ownership 压力追问

### 16.1 AI 参与很多，你做了什么

我定义 Ledger、Summary、Snapshot、Memory 和 Evidence 的信任边界，决定模型输出只能创建 Candidate，设计 Scope/Revision/Conflict/Revoke/Outcome 状态和 Token Compiler，审查缓存 Fingerprint、并发 Promotion 与删除语义，并用跨用户、晚到 Summary、冲突 Revision 和 Replay 测试验收。AI 帮助生成实现，但不能自行决定把什么长期记住。

### 16.2 为什么不直接用 Mem0/Zep

外部产品能加速抽取、混合召回或时间图谱，但项目的 Workspace ACL、审核、Source/Evidence 隔离、RunInputSnapshot 和 Artifact/Research Control Pack 是领域契约。可将 Provider 作为可替换投影或召回层，不能让它成为授权和事实真源。

### 16.3 最大风险

最大风险是低频错误 Memory 被重复注入后形成系统性偏差，而平均满意度不容易发现；其次是用户撤销后缓存和历史 Pack 未及时失效。优先保证 Precision、Scope、Revoke 和审计，再追求 Recall 和自动化写入率。

## 17. 面试官二次审查

| 审查面 | 已闭环 | 仍缺真实证据 |
|---|---|---|
| 概念边界 | Context、Summary、Snapshot、Memory、Evidence | 真实故障/用户故事 |
| 演进取舍 | Sliding Window 到治理型 Memory | 各阶段真实实验 |
| 算法 | Promotion 阈值、Revision、Utility、Budget、Cache Key | 阈值敏感性和大规模召回 |
| 时间语义 | Validity、Stale、Update、Revoke、Replay | 长周期真实用户数据 |
| 安全 | Scope、Poison、Evidence Isolation | 对抗集与删除传播演练 |
| 指标 | 写入、召回、任务、成本分层 | 锁定 Gold 与置信区间 |
| 外部比较 | LongMemEval/Mem0/Zep/Letta 口径 | 按公开协议的可比运行 |
| Ownership | 个人决策与 AI 边界 | 现场推演并发 Promotion |

从大厂面试官角度，这个亮点已经不缺 Memory 名词，最缺的是长期用户时间线上的真实更新、撤销和污染数据。实测前应把卖点落在可信写入、版本化时间语义、可回放输入和 Evidence 隔离，而不是声称记忆让回答提升了多少。
