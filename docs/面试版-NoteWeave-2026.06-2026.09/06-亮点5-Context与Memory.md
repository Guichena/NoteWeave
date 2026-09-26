# 亮点 5：Context Engineering 与长期 Memory

## 1. 简历亮点对应的面试回答

长会话的问题不是“窗口不够大”，而是不同主题、不同可信度和不同生命周期的信息混在了一起。我的设计把当次 Context 和长期 Memory 分开：Raw Message 作为原始真源，按主题形成 Segment，使用 Topic-aware Window、近期原文和增量摘要编译本次输入；跨会话信息先经过 Candidate 提取、门控和版本化晋升。Memory 可以影响格式偏好和已确认约束，但不进入资料召回、Citation 或 Evidence Rerank，避免用户偏好和模型推断反过来证明资料事实。

## 2. 三类对象先分清楚

| 对象 | 生命周期 | 用途 | 信任边界 |
| --- | --- | --- | --- |
| Context | 一次模型调用 | 本次实际输入 | 由 Compiler 选择，必须可回放 |
| Conversation / Summary | 当前会话 | 保持话题连续 | 原始消息是真源，摘要可失效 |
| Memory | 跨会话或跨任务 | 稳定偏好、规范、确认决策 | 有 Scope、版本、来源和撤销 |
| Evidence | 当前资料版本 | 证明外部事实 | 只能来自可见 Source Snapshot |

Memory 不是 RAG 的另一个索引。RAG 回答“资料里有什么”，Memory 回答“用户希望怎么协作”。二者混用会造成事实污染和删除困难。

## 3. Topic-aware Window 与增量摘要

### 3.1 原始消息真源

每条消息写入 `MessageLedger`，带 conversation、topic、sequence 和时间。摘要只保存引用区间、源 Segment 版本、内容 Hash 和状态，不覆盖原文。历史 Run 需要重放时读取当时的 Summary Revision 和 Raw Tail。

### 3.2 主题分段

分段综合显式对象、当前任务、Query 语义和相邻消息。主题识别置信度低时保留近期原文，不强行合并。每个 Segment 维护版本，新增消息只使活动 Segment 或新 Segment 失效。

### 3.3 增量摘要

摘要任务绑定 `segment_id + segment_version`。完成晋升时用 CAS 检查版本，若期间有新消息，旧摘要标记 `STALE`，不能覆盖新状态。Context Compiler 按当前 Mode、活动主题、Token Budget 选择 Summary、Raw Tail、显式 Pin、Memory Pack 和 Evidence。

```text
System / Task Contract
    ↓
Current User Request
    ↓
Relevant Summary + Recent Raw Tail
    ↓
Explicit Constraints
    ↓
Memory Pack（独立低信任区）
    ↓
Evidence Bundle（事实引用区）
```

各区有独立预算。Token 超限时先去重和压缩低价值历史，再减少远期摘要和低优先级 Memory，不能静默删除当前问题、系统约束和必要 Evidence。

## 4. Memory Candidate 到 Revision

1. 从用户明确保存动作、反复纠正、稳定偏好或任务结果中提取 Candidate。
2. 记录来源消息、提取方式、敏感性、建议 Scope 和有效期。
3. 经过类型白名单、重复判断、冲突检测、Workspace / User Scope 检查。
4. 低风险、用户明确确认的偏好可以直接形成可撤销 Revision；模型推断和外部资料只能进入 Review 或受控晋升。
5. 新版本不原地覆盖旧版本，而是建立 `supersedes` 关系；撤销生成显式 Revoke 事件并使相关 Control Pack 失效。

示例：

```text
Candidate: “用户偏好先给结论，再给实现细节”
来源: 用户明确确认
Scope: USER
类型: PRESENTATION_PREFERENCE
状态: ACTIVE

Candidate: “用户熟悉某门课程的全部内容”
来源: 模型推断
状态: REVIEW，不得直接晋升
```

资料里的事实、模型自动总结和短期任务结果默认都不是长期 Memory。外部资料也不能直接生成高权限 Memory，避免 Prompt Injection 或错误信息长期污染。

## 5. RunInputSnapshot 与事实隔离

Research 或 Artifact 启动时创建 `RunInputSnapshot`，冻结可见 Source Snapshot、Conversation Cutoff、Summary Revision、Memory Revision 和 Strategy Tuple。任务执行期间用户新增消息或 Memory 变化，不会悄悄改变本次任务输入；下一次任务读取新版本。

Memory 只作为独立 Section 注入，可影响格式、偏好和已确认约束，但不能：

- 参与 Source Recall；
- 作为 Citation 的来源；
- 参与 Evidence Rerank；
- 覆盖系统安全规则、Workspace ACL 或工具策略。

## 6. 关键 Trade-off

- **Topic-aware Window，而不是最近 N 条：** 能保留跨话题仍有效的约束；代价是需要主题分段和低置信度兜底。
- **增量摘要，而不是每次全量总结：** 成本和延迟更稳定；代价是摘要版本、失效和回读逻辑更复杂。
- **Candidate + Gate，而不是模型直接写 Memory：** 防止幻觉和注入放大；代价是晋升延迟和审核成本。
- **Revision，而不是原地更新：** 支持回放、撤销和审计；代价是读取要选择当前有效版本。
- **Memory 与 Evidence 隔离：** 保护事实可信度；代价是模型不能用个性化记忆直接补齐缺失事实。

## 7. 面试官高频追问

**问：为什么不把所有历史都放进 Prompt？**  
全量回放会增加 Token 和延迟，还会把旧主题、冲突约束和已删除内容带回来。摘要、窗口和原文引用可以把输入压缩，同时保留需要回查的事实。

**问：摘要错了怎么办？**  
原始 MessageLedger 不删除，摘要带覆盖区间和版本。发现不一致时按引用回读原文重算，旧 Summary 标记失效，不能覆盖新版本。

**问：用户说“记住这条”是否立即生效？**  
低风险、明确的用户偏好可以在 Scope 和敏感性检查后形成可撤销 Revision；涉及事实、权限或模型推断的内容先进入 Candidate / Review，不直接影响引用链。

