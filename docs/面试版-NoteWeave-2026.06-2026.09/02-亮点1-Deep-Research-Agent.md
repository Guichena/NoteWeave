# 亮点 1：验证驱动、可恢复的 Deep Research Agent

## 1. 简历亮点对应的面试回答

我没有把 Deep Research 做成一次长 Prompt，而是把开放问题拆成一张可验证的研究表。行是研究对象，列是必须回答的字段，每个单元格保存候选结论、Evidence、验证状态和下一步动作。Java Host 保存这张表和任务状态，Python Worker 只在冻结输入上搜索、阅读和提取候选。Plan 负责决定要验证哪些格子，Replan 只允许补缺口或处理冲突，Local / Global Verifier 负责质量门禁，Checkpoint 负责断点恢复。这样任务中断时可以从未完成的格子继续，结论也能逐项回到来源，而不是重新生成一篇看似完整的文章。

## 2. 为什么一次 Prompt 或自由 ReAct 不够

开放研究同时面对三个问题：

- 研究范围容易漂移，模型会把“比较课程”扩展成无界写作。
- 搜索结果很多，但不一定支撑最后一句结论，完成率和证据质量脱钩。
- 任务可能跨越多个搜索、解析和生成步骤，进程中断后无法判断哪些外部调用已经发生。

一次 Prompt 的优势是实现快、路径短，适合演示；自由 ReAct 的优势是探索空间大，适合未知问题。但两者都不适合作为长期任务的业务真源。我们的折中是“受控 Agent”：允许模型参与计划和候选生成，但状态、预算、权限和提交由程序控制。

## 3. Table-as-State 具体数据模型

```text
ResearchRun
├─ run_id / workspace_id / plan_version
├─ input_snapshot_id / visible_source_versions
├─ budget: max_cells, max_searches, max_tokens, deadline
├─ status: PLANNED / RUNNING / VERIFYING / READY / FAILED
└─ ResearchCell[]
   ├─ cell_id / entity_key / field_key
   ├─ expected_type / required / risk_level
   ├─ candidates[]
   ├─ evidence_refs[]
   ├─ state: TODO / SEARCHING / CANDIDATE / VERIFIED / GAP / CONFLICT
   └─ cell_version / lease_epoch / last_checkpoint_id
```

`ResearchCell` 是最小可恢复单元。它比“一个 Agent 任务”更细，能做到只重跑失败格子；比“一个搜索结果”更粗，能把多个来源合并成一个待验证命题。`entity_key + field_key + expected_type` 用来防止把 A 课程的学时证据写到 B 课程的考核字段上。

## 4. 端到端执行流程

### 4.1 Plan

Planner 接收用户问题和可见资料，输出结构化 `ResearchPlan`：实体类型、必填字段、字段类型、预算、验证等级和停止条件。Plan 先通过 Schema 校验，再检查矩阵规模。超过上限时返回 `PLAN_BOUNDED`，要求用户缩小范围，不通过偷偷减少必填字段。

### 4.2 Dispatch 与执行

Host 为每个待处理 Cell 创建 Task，并写入 Outbox。Worker 领取任务时获得带过期时间的 Lease 和递增的 Fencing Token。它只能读取 `RunInputSnapshot` 指定的 Source 版本，只能调用白名单搜索和阅读工具，并返回 `Candidate`：

```json
{
  "cell_id": "course-A.exam",
  "claim": "闭卷考试，占总评 60%",
  "evidence": [{"source_id": "s1", "snapshot_id": "v3", "quote": "...", "locator": "p.4"}],
  "confidence": 0.82,
  "observations": ["课程大纲与教师说明一致"]
}
```

Worker 不直接把 `claim` 写进最终结论。Host 校验 Cell 版本、Lease Epoch、Fencing Token、Snapshot 摘要和 Workspace 权限后，才把候选追加到 Cell。

### 4.3 Local Verifier

Local Verifier 只看单个 Cell，检查四件事：证据是否属于同一实体和字段、引用是否能覆盖候选文本、字段类型和单位是否正确、来源版本是否仍可见。数字字段做类型化比较，年份、学分、百分比不能只靠字符串相似度。

### 4.4 Global Verifier

Global Verifier 根据当前所有 Cell 重新计算覆盖率、冲突和必填字段缺口，不直接相信 Local Verifier 的“通过”状态。它还检查同一实体内部是否出现互相矛盾的时间、单位和结论。Global Verifier 失败时只生成 GAP 或 CONFLICT，不把冲突用 Last Write Wins 覆盖掉。

### 4.5 Replan 与受限反证

Replan 的输入是验证结果，不是原始聊天。它可以为 GAP 增加搜索任务，为 CONFLICT 生成有限反证目标，例如查找否定证据、替代来源或不同年份版本，但不能修改用户要求的必填字段来提高完成率。每轮反证有查询族、次数和 Token 预算上限，预算耗尽后转为“证据不足”或“冲突待人工确认”。

### 4.6 综合与完成

综合器只负责把已验证 Cell 组织成报告草稿，引用必须绑定到 Cell 的 Evidence 集合。Host 最后执行完成门禁：必填字段覆盖、引用支撑、越界检查、格式 Schema 和任务状态都通过，才将 Run 提升为 `READY`。否则输出 `FAILED`、`PARTIAL` 或 `NEEDS_REVIEW`，不把“生成了长文本”当成功。

## 5. Checkpoint、Lease 与恢复

Checkpoint 保存计划版本、Cell 状态、Evidence 引用、预算消耗、外部调用 Receipt 和事件高水位。进程重启时先读取最近 Checkpoint，再扫描 `TODO / GAP / SEARCHING 超时` 的 Cell，只创建缺口任务。已经有成功 Receipt 的搜索不重复付费调用。

Lease 解决“谁现在负责执行”，Fencing Token 解决“旧 Worker 晚到还能不能写回”。提交时使用条件更新：`run_id + cell_id + cell_version + lease_epoch + fencing_token` 全部匹配才允许落库。Lease 过期后，旧 Worker 的回调即使网络恢复也会被拒绝。

超时不直接等于失败。Provider 请求超时可能已经产生外部副作用，系统先按 `operation_id` 对账；能确认成功就复用 Receipt，确认失败才重试，无法确认则进入 `UNKNOWN_OUTCOME`，由补偿任务处理。

## 6. 关键 Trade-off

| 决策 | 选择 | 放弃的东西 | 为什么适合当前项目 |
| --- | --- | --- | --- |
| 状态粒度 | Cell 级 Table-as-State | 自由 Agent 的任意探索 | 能逐项追踪证据、恢复和预算 |
| 验证方式 | Local + Global | 多 Agent 投票 | 投票不能证明证据绑定和全局覆盖 |
| 纠偏方式 | 有界 Replan + 受限反证 | 无限 Reflection | 预算可控，失败原因明确 |
| 恢复方式 | Checkpoint + Receipt | 全量重放对话 | 节省成本，减少输入漂移 |
| 编排位置 | Host 持有权威状态，Worker 执行 | Worker 直接写库 | 方便权限、幂等、旧 Owner 防护 |
| 工作流框架 | 轻量 Run / Task 状态机 | 一开始引入重型工作流平台 | 当前问题是有界研究，不是跨天人工审批 |

## 7. 面试官高频追问

**问：为什么不让多个 Agent 投票？**  
投票只能说明多个模型输出相似，不能证明每个结论都绑定到了正确实体和字段。我们把验证对象收敛到 Cell 和 Evidence，使用确定性规则先挡住类型、范围和引用问题，再用模型处理语义判断。

**问：Research 和普通聊天 Agent 的边界是什么？**  
普通聊天可以容忍短期上下文和一次性回答；Research 需要可恢复、可审计和逐项完成，因此必须有 Run、Cell、Checkpoint、预算和完成门禁。

**问：Research 如何保证不越权读取资料？**  
Run 启动时冻结 Workspace、Source Snapshot 和权限摘要；检索阶段做 Scope Filter，写回前再回库校验当前权限和版本。权限校验失败宁可少证据，也不把缓存命中当作授权。

**问：完成率和引用支撑率冲突怎么办？**  
正确失败优先。必填字段没有可靠证据时进入 `NO_SUPPORTED_CANDIDATE` 或 `NEEDS_REVIEW`，不能通过放宽引用门槛来换完成率。

