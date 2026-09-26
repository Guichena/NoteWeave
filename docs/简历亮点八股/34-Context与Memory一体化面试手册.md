# Context Engineering 与 Memory 一体化面试手册

> Context 解决一次运行如何得到稳定输入，Memory 解决跨会话哪些信息可以进入未来运行。面试推荐分成 Session Context、Explicit Memory 和 Derived Memory：显式低风险偏好可按窄 Scope 立即生效并支持撤销，只有模型推断内容才默认走 Candidate/Review。

## 1. 主回答

长会话的两个问题经常被混在一起：当前 Run 的上下文会超窗，长期记忆又可能污染事实。项目把 Session Context、Explicit Memory 和 Derived Memory 分开。每次 AnswerRun 或 ResearchRun 都冻结历史、摘要、资料范围、Memory Control Pack 和策略版本；Context Compiler 为 RAG Evidence 保留独立事实预算，历史 Summary 和 Memory 不能把当前任务所需证据挤掉。用户明确要求记住的低风险偏好在可信用户动作、窄 Scope 和敏感内容检查后直接创建 Active Revision，模型推断的偏好才进入 Candidate、Review 和 Promotion。审核通过的 Derived Revision 可以按类型进入后续 Control Pack，但不能因此充当 Source Evidence。

这套设计让“用户删除了消息”“摘要生成失败”“旧事实被撤销”“新运行不该看到某条记忆”等问题都有单独的状态和恢复路径。

## 2. 演进主线

| 阶段 | 旧方案 | 暴露的问题 | 当前选择 |
| --- | --- | --- | --- |
| V0 | 每轮拼接全部消息 | Token 和延迟线性增长 | History Head + Input Snapshot |
| V1 | 固定滑窗 | 早期约束和关键结论丢失 | Segment + Summary Revision |
| V2 | 摘要直接覆盖历史 | 摘要失败会破坏后续上下文 | BUILDING/READY/STALE Promotion |
| V3 | 所有摘要都当 Memory | 临时上下文污染长期事实 | Session、Explicit、Derived 三层信任路径 |
| V4 | 记忆按相似度直接召回 | 事实、偏好和任务状态混杂 | 风险分层和运行类型编译 |
| V5 | 删除只改当前 UI | Replay、Summary、Memory 仍可复现旧内容 | Replay Redaction、撤销和派生失效 |

## 3. Context 链路

```text
Conversation
  -> History Head
  -> RunInputSnapshot
       -> messages
       -> segment summaries
       -> current Evidence Snapshot
       -> Memory Control Pack
       -> strategy / prompt version
  -> AnswerRun or ResearchRun
```

History Head 表示本次运行看到的会话边界。Input Snapshot 保存规范化输入和引用，不要求复制全部原文。Run 期间用户可以继续发消息，但不会悄悄改变已经开始的执行。

### 3.1 Segment Summary

长对话按 Segment 切分，摘要先构建为 Revision，再通过完整性检查后 Promotion 为 READY。新摘要失败时，旧 READY 版本继续提供上下文。摘要压缩比例只表示节省了多少 Token，不能直接代表信息质量。

### 3.2 删除和 Replay Redaction

删除后的新 Run 不应继续编译被删内容，历史 Run 的不可变 Snapshot 又不能直接改写，所以读取和 Replay 层应用 Redaction，相关 Summary 标记 STALE 或 DELETED，并触发重建。物理删除、审计保留和派生失效是不同的生命周期，需要按隐私政策继续细化。

## 4. Memory 链路

```text
Trusted explicit user action
  -> narrow scope and safety validation
  -> Active Immutable Revision + undo

Model/behavior inference
  -> Candidate + provenance/confidence/risk
  -> Review or policy gate
  -> Active Immutable Revision

Active Revision -> Revoke/supersede -> Control Pack compiler
```

Memory 不直接参与 Source 检索、Citation 或 Evidence Rerank。显式低风险偏好可以走快路径，高风险事实、跨 Workspace 规则和敏感内容仍需审核。模型推断内容通过审核后产生不可变 Revision。运行时按类型编译：用户偏好进入表达或结构控制，长期事实需要来源和时间，任务状态有过期时间。

### 4.1 为什么 Memory 不等于聊天摘要

聊天摘要服务当前会话压缩，Memory 影响未来多个 Run。摘要可以在下一次会话后失效，Memory 需要来源、版本、撤销和反馈。把两者混在一起会让删除、纠错和权限边界变得不可解释。

### 4.2 撤销和污染

事实被用户纠正后，新的 Revision 需要覆盖或标记旧 Revision Stale；撤销不应只从 Redis 删除一条缓存，还要使 Control Pack、摘要和派生读取失效。当前完整 Revoke Watermark 和依赖传播仍是目标设计，面试中应明确这一点。

不可变 Snapshot 只保证“当时用了什么”可解释，不代表 Run 可以无视后续安全事实。普通偏好更新和新 Summary Revision 不改变在途 Run；隐私删除、Workspace 成员撤权、Source 安全封禁和高风险 Memory 撤销属于 Hard Revocation。`[目标设计]` RunInputSnapshot 同时记录 `input_validity_epoch`，高风险工具调用和最终提交前比较当前 Scope/Revocation Epoch。Epoch 已推进时，Run 进入 `INPUT_REVOKED` 或受控取消，禁止发布、写回和继续调用外部工具；已经发送给外部 Provider 的内容无法追回，只能停止后续动作、记录影响范围并执行供应商侧删除流程。

## 5. 运行时编译

Control Pack 是运行输入的一部分，不是隐式全局 Prompt。编译器根据 Run 类型、Workspace、用户权限、Memory Revision、撤销水位、Source Scope 和 Token Budget 生成稳定输入。

编译顺序应保证：

1. 先应用 Workspace 和资源权限。
2. 再过滤已撤销或过期 Revision。
3. 再按风险和任务类型选择 Memory。
4. 最后与会话消息、摘要和 Evidence Snapshot 合并。
5. 写入 Input Snapshot，供重试和回放复用。

## 6. 学校场景演练

教师明确选择“记住这个偏好：课程讲义优先使用中文术语”。系统验证它来自可信用户动作，只作用于该教师或当前课程 Workspace，不含敏感内容，于是直接创建可撤销的 Active Revision；不要求为了低风险格式偏好再排一次人工审核。模型从普通对话推断出的“教师可能喜欢中文术语”仍只能成为 Candidate。“课程规则将在下学期改变”的临时消息也不能自动晋升长期事实。

用户删除包含学生姓名的消息后，新 QA 和 Research 都不再看到它，相关 Segment Summary 标记过期。历史 Run 仍保留审计标识，但 Replay 输出执行 Redaction。用户提交纠正事实后，新的 Memory Revision 生效，旧版本保留来源并可追溯。

## 7. 指标

| 指标 | 计算 | 意义 |
| --- | --- | --- |
| Snapshot Compile P95 | 编译完成 - Run 准备开始 | 长会话准备成本 |
| Summary Retention | 摘要保留的 Gold 约束 / 原 Segment 约束 | 摘要信息质量 |
| Summary Compression | Summary Token / 原 Segment Token | 上下文压缩程度 |
| Memory Update Accuracy | 正确更新的事实 / 更新 Case | 记忆写入质量 |
| Temporal Accuracy | 时间条件正确的回答 / 时间 Gold | 旧事实和新事实区分 |
| Correct Abstention | 正确拒答 / 应拒答 Case | 记忆不足时是否克制 |
| Stale Read Rate | 已撤销或过期 Memory 被编译的 Run / Run | 撤销传播硬门槛 |
| Memory Pollution Rate | 不应进入当前 Run 的 Memory / 编译 Memory | 长期记忆污染 |
| Delete Redaction Coverage | 已删除内容从新读取和 Replay 中消失的 Case / 删除 Case | 隐私和回放一致性 |
| Hard-revocation Commit Violation | Hard Revocation 后仍发布、写回或调用新外部工具的 Run / 被撤销的在途 Run | 目标为 0；Snapshot 稳定性不能覆盖权限与删除 |

## 8. 面试官连续追问

### 为什么不每次都把全部历史发给模型

成本、延迟和窗口会随会话增长，且旧消息中可能包含已删除或失效内容。Input Snapshot、Segment Summary 和 Memory Control Pack 让输入可控、可解释、可重放。

### 摘要模型错了怎么办

摘要先处于 BUILDING，只有通过结构完整性和关键约束检查后才 Promotion 为 READY。失败时沿用上一版，不能直接覆盖当前可用摘要。

### Memory 与 RAG 有什么边界

RAG 从当前允许的资料 Snapshot 召回证据，Memory 是跨运行的受控上下文。Memory 不应替代 Source Citation，也不直接进入资料 Evidence Rerank。

### 用户删除后，历史 Run 是否必须物理消失

按隐私和审计策略分层。产品读取和 Replay 需要立即 Redaction，派生摘要和索引失效，审计数据按保留政策处理，最终再物理清理。当前 Redaction 和派生失效覆盖到什么程度，要按实际实现回答。

### 为什么 Memory 要不可变 Revision

原地更新会破坏历史解释和撤销链。不可变 Revision 支持来源、时间、反馈、纠正和旧 Run 回放，代价是编译器要处理版本选择和撤销水位。

### 如何证明 AI 写代码时你有 Ownership

我把“当前会话摘要”“未来记忆”“资料证据”拆成不同对象，并为删除、撤销、过期和摘要失败定义了不同状态。AI 可以帮忙生成序列化和测试样例，但不会替我决定这些边界和不变量。

## 9. 简历表达

> 设计 Context Engineering 与三层 Memory 信任路径，通过 History Head、不可变 RunInputSnapshot 和 Segment Summary Revision 控制会话上下文；显式低风险偏好按窄 Scope 创建可撤销 Revision，模型推断内容进入 Candidate/Review，再由 Control Pack 按运行类型编译，避免长期记忆污染和删除后旧内容继续进入新运行。

## 10. 4 分钟标准主回答

长会话有两个不同问题：当前 Run 的上下文越来越长，跨会话 Memory 又可能把临时内容当成长期事实。项目用 History Head 冻结本次运行看到的会话边界，再把消息、Segment Summary、Evidence Snapshot、Memory Control Pack 和策略版本编译成不可变 RunInputSnapshot。编译时安全策略和任务契约不可裁剪，当前问题与必要 Raw Tail 使用交互预算，RAG Evidence 使用独立事实预算，Memory 只进入控制预算，历史 Summary 使用剩余预算。用户在生成期间继续发消息，不会改变已经开始的 Run。

旧消息按 Segment 切分，摘要先生成 Revision，再通过完整性检查 Promotion 为 READY。新摘要失败时保留上一版，避免一次摘要错误破坏后续上下文。用户删除消息后，新 Run 不再编译它，相关摘要标记 STALE 或 DELETED，历史 Run 的 Replay 通过 Redaction 展示。

Memory 按来源走两条路径。用户通过可信交互明确要求记住的低风险偏好，经过窄 Scope、长度和敏感内容检查后直接创建可撤销 Revision；模型或行为推断先进入 Candidate，再按事实、任务状态和风险经过 Review。保存、导出、重试和编辑只是弱反馈，只能影响排序先验或进入离线复核，不能自动证明某条 Memory 正确。Compiler 根据 Workspace、用户、Run 类型、Revision、撤销水位和 Token Budget 生成 Control Pack，Memory 不参与 Source Citation 和 Evidence Rerank。

关键指标包括 Snapshot Compile P95、Summary Retention、Memory Update Accuracy、Temporal Accuracy、Stale Read 和 Pollution Rate。当前完整 Revoke Watermark 和派生依赖传播仍是目标设计，这个边界需要主动说明。

## 11. 八股映射

重点对应不可变快照、版本控制、缓存 Key、摘要压缩、时间事实、数据血缘、软删除与物理删除、事件回放、隐私 Redaction、乐观锁和 Token Budget。完整二阶回答见[二阶追问回答库](36-重点知识点二阶追问回答库.md)。

## 12. 深挖附件

Input Snapshot、Summary Promotion、Memory Revision、撤销传播和 Replay Redaction 的独立 3 分钟回答见[重点知识点三分钟深挖库](35-重点知识点三分钟深挖库.md)，Conversation 幂等、SSE Replay 和 Memory 撤销的二阶回答见[重点知识点二阶追问回答库](36-重点知识点二阶追问回答库.md)。

| 侧重点 | 附件 |
| --- | --- |
| Context、Memory 架构和数据模型 | [Context 与 Memory 详细设计](05-Context与Memory-详细架构与具体设计.md) |
| 设计演进、案例、消融和 Ownership | [Context 与 Memory 演进专项](22-Context与Memory演进案例消融与Ownership答辩.md) |
| Conversation、SSE 和 Input Snapshot | [Conversation、AnswerRun、SSE](27-Conversation-AnswerRun-SSE与上下文恢复.md) |
| 前端多 Run、快照校正和过期请求 | [前端工作台与流式状态](39-前端工作台-流式状态与恢复一体化面试手册.md) |
| Memory 行业和论文依据 | [Memory 业界演化研究](../research/Memory业界演化研究.md) |
| 简短问答速查 | [Context 与 Memory 面试题](05-Context与Memory-面试题与参考回答.md) |

## 13. 面试前自检

1. 能区分 History Head、Input Snapshot、Summary Revision 和 Memory Revision。
2. 能解释摘要失败为什么不覆盖上一版。
3. 能说清 Memory 为什么不参与 Citation 和 Evidence Rerank。
4. 能解释删除、Redaction、派生失效和物理清理的边界。
5. 能报出 Memory Update、Temporal、Abstention 和 Pollution 四类指标。
6. 能明确当前完整撤销传播仍属于哪一层目标设计。

## 14. Memory 的数字为什么最容易骗人

Memory Hit Rate 很高不一定是好事。系统把更多旧内容塞进 Prompt，会同时提高命中、Token 和污染概率。长期记忆需要同时测写入、选择、时间、撤销、拒答和业务收益。

| 问题 | 指标 | 公式 | 代表的意义 |
| --- | --- | --- | --- |
| 该记的是否写对 | Memory Update Accuracy | `正确新增、修改或撤销的 Revision / Memory Update Case` | Candidate、Review 和版本策略是否正确 |
| 取出的是否相关 | Memory Precision@K | `TopK 中相关且当前有效的 Memory / TopK Memory` | 防止语义相似但过期的内容进入 Prompt |
| 时间是否正确 | Temporal Accuracy | `使用当时有效 Revision 正确回答的 Case / 时间 Gold Case` | 区分旧事实、新事实和指定时间点 |
| 不知道时是否克制 | Correct Abstention | `正确拒绝使用 Memory 的 Case / 应拒绝 Case` | 防止缺失信息被模型补成事实 |
| 撤销是否传播 | Stale Read Rate | `撤销后仍编译旧 Revision 的 Run / 撤销后的相关 Run` | 安全硬指标，目标应接近 0 |
| 是否带入无关内容 | Pollution Rate | `不应进入当前 Run 的 Memory / 编译进入的 Memory` | 长期质量和 Prompt Injection 风险 |
| 压缩是否保真 | Summary Retention | `摘要保留的 Gold 约束 / 原 Segment Gold 约束` | 不能只看 Token 压缩比例 |
| 是否真的省资源 | Useful Token Saving | `Baseline Token - Treatment Token`，同时要求任务质量不降 | 把压缩收益与质量护栏绑定 |

Summary Compression 很高可能意味着摘要太短，Memory Precision 很高可能来自只返回一个保守结果，Stale Read 为 0 也可能是 Memory 根本没启用。因此每个指标都要和 Task Success、样本覆盖及 Token 配对。对撤销和跨 Workspace 污染这类高风险问题，报告总体平均值没有意义，应单列失败数和失败 Case。

`[行业参考]` LongMemEval 用 500 个问题覆盖信息提取、多会话、时间推理、知识更新和拒答。Mem0 常宣传相对 Benchmark、搜索和更新能力，Zep 强调时间事实与失效，Letta 强调可见 Memory Block 和上下文层级，LangGraph 区分 Thread 内短期状态与跨 Thread 长期记忆。NoteWeave 借鉴任务分桶与可见性设计，但内部学校 Gold 不按同一协议运行时，不能把分数与产品或论文直接横比。完整依据见[Memory 业界演化研究](../research/Memory业界演化研究.md)。

### 面试官问“为什么不直接接 Mem0 或 Zep”

外部 Memory Provider 可以负责候选抽取和语义召回，但项目仍要掌握 Workspace Scope、用户身份、Review、不可变 Revision、撤销水位、删除和 RunInputSnapshot。若 Provider 直接成为真源，权限变更与历史回放会形成第二套状态机。当前选择是让 Provider 最多返回 Candidate，Canonical Memory 和编译资格由 Host 决定。未来只有在外部方案能提供租户隔离、版本、撤销和可导出审计证据时，才考虑扩大职责。

### 面试官问“Memory 到底创造了什么业务价值”

学校场景中可以看重复说明减少、跨会话任务恢复和个性化偏好命中。业务指标可定义为 `Repeated Clarification Reduction = 1 - Treatment 重复澄清轮次 / Baseline 重复澄清轮次`，并配对 Task Success、用户纠正率和 Stale Read。用户少说一遍要求，但错误偏好让最终答案返工，不能算收益。

`[演练假设]` 可以用 `N=100` 个跨会话课程任务解释数据卡：Temporal Accuracy 从 `0.72` 提升到 `0.88`，重复澄清轮次下降 `31%`，输入 Token 中位数下降 `24%`，Pollution Rate `2.1%`，撤销后的 Stale Read 为 `0/40`。这只是理想实验示例；`0/40` 只能说固定 Case 未观察到失败，不能写“撤销可靠性 100%”。

### 二阶追问：用户说“以后不要再记这个”，系统要改哪些地方

先写撤销事件并推进 Revoke Watermark，新 Run 的 Compiler 不再选择旧 Revision；相关 Summary、Cache 和 Control Pack 标记失效；正在运行的 Run 按冻结 Snapshot 保持可解释，是否取消由隐私等级决定；历史 Replay 做 Redaction；物理清理进入异步任务和审计。当前完整派生依赖传播仍是 `[目标设计]`，面试中不能说已经覆盖所有缓存、备份和导出文件。
