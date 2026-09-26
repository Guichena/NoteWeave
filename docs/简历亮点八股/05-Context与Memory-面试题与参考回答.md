# Context Engineering 与 Memory：面试题与参考回答

> 当前默认入口是[Context 与 Memory 一体化面试手册](34-Context与Memory一体化面试手册.md)。A 档先讲其中 4 分钟主回答；B 档选择 Summary Promotion、RunInputSnapshot、Memory Revision、Evidence Isolation、撤销传播或 Replay Redaction 独立展开；普通概念区分属于 C 档 30 到 90 秒速查。只有时间事实、污染、删除和并发版本问题需要继续进入二阶状态机。

> 阅读说明：前面的短问答用于面试官打断时快速回应。本章末尾的“八维度母题长回答”才是默认准备材料，每题应讲 3 到 5 分钟，并主动覆盖问题来源、实现机制、失败窗口、方案取舍、验证证据和当前边界。

> 证据边界：Memory 的 Signal、Candidate、Review、Revision、Control Pack、Outcome 与 Revoke 以 [Memory 机制详细设计](../Memory机制详细设计.md)为准。面试推荐将 Session、Explicit 和 Derived Memory 分流，统一裁决见[面试推荐架构与规模化演进裁决](43-面试推荐架构与规模化演进裁决.md)。Memory 与 Evidence 属于不同信任域；真实采纳、Token 收益和长期污染均为 `[生产待验证]`。

> 深挖入口：[演进、完整案例、消融与 Ownership 答辩](22-Context与Memory演进案例消融与Ownership答辩.md)。

## 0. 脑图主干

```text
长会话与跨任务连续性
├─ Raw Message Ledger：原始消息真源
├─ Topic-aware Segment：主题分段
├─ Incremental Summary：增量摘要版本
├─ Recent Raw Tail：近期原文
├─ Context Compiler：按任务编译输入
├─ Memory Promotion：候选、审核、晋升
└─ Evidence Isolation：Memory 不进入事实引用链
```

## 0.1 从 0 讲起的演进版主回答

最开始聊天只带最近 N 条消息。它实现简单，短会话也最符合直觉。长会话出现主题切换后，窗口可能留下新的闲聊，却丢掉较早仍有效的约束；单纯扩大窗口又提高成本并带来 Context Rot。

我们先引入摘要。全量摘要一致性直观但成本持续增长，滚动覆盖便宜却会累计误差并破坏历史。因此系统保存 Raw Message Ledger，用 Segment 表示连续主题，用 Summary Revision 做版本化增量压缩，同时保留连续 Raw Tail。异步摘要只有在 Segment Version 未变化时才能 Promotion，旧构建标 Stale，不覆盖新消息。

长期 Memory 也比较过自动写入、全人工确认和纯向量记忆。自动写入个性化快，但会把幻觉和注入长期放大；全人工最安全，审核负担太重；纯向量相似度无法表达 Scope、有效期和确认强度。推荐按来源分流：用户通过可信动作明确保存的低风险偏好，可在窄 Scope 校验后直接创建可撤销 Revision；模型推断、行为信号和外部内容只能形成 Derived Candidate，再由 Java Memory Runtime 做门控和审核。Memory 使用 Revision，不原地覆盖。

Context Compiler 把当前问题、Raw Tail、Summary、Memory 和 Evidence 分区预算，并冻结 RunInputSnapshot。Memory 可以影响偏好和约束，但不进入 Source Recall、Citation 或 Evidence Rerank，避免系统以前生成的内容反过来自证。代价是版本、缓存和隐私删除语义更复杂，收益是长会话可连续、输入可回放、事实链不被个性化污染。

## 1. 30 秒回答

我把上下文和长期记忆分成两个问题处理。长会话通过 Topic-aware Window、Segment Summary 和 Recent Raw Tail 做增量压缩，只重算受新消息影响的尾部片段；跨任务信息则通过 Memory Candidate、人工审核和版本化晋升沉淀为长期 Memory。每次执行由 Context Compiler 按任务选择相关摘要、近期原文和 Memory，并冻结输入快照。Memory 只影响偏好、规范和已确认约束，不进入资料召回、Citation 或 Evidence Rerank，避免用户偏好污染事实证据链。

## 2. 3 分钟回答

长会话不能简单把最近 N 条消息拼进 Prompt。这样既会丢掉早期关键约束，也会让无关旧话题占用 Token。我的设计以 Raw Message Ledger 为真源，把连续消息按主题形成 Segment，每个 Segment 维护版本化摘要，尾部保留 Recent Raw Tail。新消息到来时只更新受影响的尾部 Segment，已经稳定的前序摘要不重复计算。

Context Compiler 不等于拼字符串。它根据当前 Mode、Conversation Cutoff、活动主题和 Token Budget，选择相关 Segment Summary、近期原文、显式固定内容和长期 Memory，生成带 Section 和来源引用的 RunInputSnapshot。这样一次回答、Research 或 Artifact 都能回放当时实际看到的输入。

长期 Memory 与聊天摘要分开。摘要描述“这段对话发生过什么”，Memory 保存跨任务仍有价值的偏好、规范、已确认决策和负向约束。显式低风险偏好可按窄 Scope 立即生效并支持撤销；执行结果和行为信号先产生 Derived Candidate，经过重复、冲突、范围和审核门禁后才晋升。后续修改生成新 Revision，不覆盖历史。

最重要的边界是 Evidence Isolation。Memory 可以告诉模型用户偏好什么格式，但不能作为“某个事实正确”的来源，也不参加资料召回和 Citation 排序。事实问题仍由 Workspace 资料和检索证据回答。

## 3. Context、Session 和 Memory 的区别

### 面试官问：上下文窗口就是 Memory 吗？

不是。Context 是本次模型调用实际输入，生命周期最短；Conversation 保存当前会话的消息和摘要；Memory 是经过治理、能够跨会话或跨任务复用的信息。把三者混在一起会导致旧消息自动变成长期事实，也无法解释模型为什么记住某项内容。

### 追问：RAG 和 Memory 有什么区别？

RAG 查找外部或 Workspace 资料，用于回答事实并提供 Citation；Memory 主要保存用户偏好、规范、已确认决策和 Episode Pointer。RAG 的相关性由问题和资料决定，Memory 还要考虑 Scope、类型、版本、有效期和是否经过确认。

## 4. 为什么需要主题感知窗口

### 最近 N 条有什么问题？

用户可能在 50 轮前确定了格式约束，最近 10 轮讨论的是另一个支线；也可能刚切换话题，最近消息和当前问题无关。固定窗口无法区分主题连续性和关键约束。

### 如何识别主题切换？

结合显式对象、Query 语义、Conversation Segment 和活动任务判断。低置信度时保留近期原文，不急于合并到旧 Segment。主题识别是上下文选择信号，不应悄悄改写用户意图。

## 5. 增量摘要

### 为什么摘要要版本化？

摘要可能随着新消息修正，而且历史 Run 需要读取当时版本。覆盖同一字段会让旧回答无法回放。Summary Revision 保存覆盖的消息区间、源 Segment 版本、内容 Hash、状态和生效时间。

### 摘要丢信息怎么办？

原始消息永远是 Ledger 真源，摘要保留 Message Reference 和覆盖区间。关键约束可以显式固定，近期部分保留 Raw Tail；发现摘要不足时按引用回读原文。摘要只减少常规输入，不删除事实来源。

### 为什么只重算尾部？

稳定 Segment 的输入没有变化，重复总结增加成本，还可能产生语义漂移。新消息通常只影响活动 Segment 或产生新 Segment，因此使用增量 Summary Revision。

## 6. Memory 写入

### 什么信息值得进入长期 Memory？

稳定偏好、长期规范、用户明确确认的决策、反复出现的纠正和可复用 Episode Pointer。临时任务内容、未经确认的模型推断、资料事实和敏感信息不应自动晋升。

### 门控晋升怎么做？

显式与推断走两条路径。可信用户动作明确保存的低风险偏好，检查类型、Scope、敏感性、重复和冲突后可直接形成可撤销 Active Revision；模型推断和行为信号先成为 Derived Candidate，再进入 Review 或受控 Promotion。Artifact 生成只能给出 Memory Promotion Preview，不能自行写入长期真源。

### Memory 冲突怎么办？

不直接覆盖。创建新 Revision，记录 supersedes 关系；判断是范围不同、时间变化还是用户纠正。新版本生效后旧版本保留审计，但不进入当前编译结果。

## 7. Scope 与污染防护

### USER 和 WORKSPACE Scope 怎么选？

跨项目稳定的表达偏好可以属于 USER；只在当前项目成立的术语、约束和决策属于 WORKSPACE。默认选择更窄 Scope，避免一个项目的规则污染另一个项目。

### Memory Poisoning 怎么防？

外部资料和工具输出不能直接生成高权限 Memory；Candidate 保留 Provenance，写入经过类型白名单、Scope 校验和人工审核；召回后作为独立低信任 Section 注入，不能覆盖 System、Tool Policy 或 Evidence。

## 8. Context Compiler

### 编译顺序是什么？

先固定 System 和任务契约，再选择当前问题、近期原文、相关 Segment、显式 Pin、Memory Pack 和检索证据。不同 Section 有独立 Token Budget 和优先级，不能让 Memory 挤掉用户当前问题或直接证据。

### 超出 Token Budget 怎么办？

优先去重和压缩低价值历史，再减少远期 Segment 和低 Rank Memory，最后缩小检索 Evidence。System、当前问题、关键约束和必要证据不能静默删除；无法满足时显式降级。

## 9. 一致性与并发

### 新消息到来时摘要正在生成怎么办？

Summary Build 绑定 Source Segment Version。完成晋升时用 CAS 检查版本；如果 Segment 已变化，则旧摘要标记 Stale，不能覆盖新状态。

### 同一 Memory 并发修改怎么办？

通过 Revision Number、Latest Version 和唯一约束串行化 Canonical 更新。并发候选可以同时存在，但晋升时必须基于当前最新版本，冲突方重新评估。

## 10. 评估指标

上下文可评估早期关键约束召回率、摘要一致率、输入重建一致率、Token 节省率和话题切换准确率。Memory 可评估 Precision@K、确认率、冲突率、陈旧误召回率和用户纠正次数。当前项目没有足够实测数据，因此简历不填写虚构百分比。

## 11. 常见质疑

- “大模型本身支持长上下文”：窗口更长不代表重要信息一定被利用，也不解决成本、回放和跨任务治理。
- “把所有历史向量化就行”：相似度不能判断信息是否稳定、是否过期、属于哪个 Scope。
- “Memory 有来源就能引用”：Memory Provenance 说明它从哪里产生，不代表它是事实证据。
- “摘要越短越好”：压缩率必须服从关键约束保真和可回读。

## 12. 一句话收尾

Context Engineering 解决“这一轮应该看到什么”，Memory Governance 解决“哪些信息值得长期保留”，Evidence Isolation 保证两者不会破坏事实回答的可信度。

## 13. 功能背后的 Context 与 Memory 八股串讲

### 13.1 长上下文为什么仍需要压缩

模型支持更长 Context Window，只说明输入上限增加，不代表每个位置的信息都同等容易被利用。长输入会增加 Prefill 延迟、Token 成本和注意力干扰，还存在 Lost in the Middle，即中间位置的信息利用率可能下降。项目保留近期原文、活动主题和关键约束，把低活跃历史压成 Summary，目标是提高有效信息密度，而不是只为了不超窗口。

### 13.2 Context Rot 是什么

Context Rot 指随着上下文增长，旧约束、无关分支、错误中间结论持续残留，使模型决策质量下降。它不是简单 Token 超限。项目用 Segment 隔离主题、Summary Revision 修正历史、RunInputSnapshot 固化本轮输入，避免每轮都继承全部噪声。

### 13.3 Sliding Window、Summary Memory、Vector Memory 的区别

- Sliding Window：保留最近 N 条，简单但丢早期信息。
- Summary Memory：压缩更早历史，成本低但可能失真。
- Vector Memory：按语义召回历史片段，能跨时间找相关内容，但相似不等于重要或有效。
- Structured Memory：按类型、Scope 和版本存储，治理能力强但建模成本高。

项目不是只选一个，而是 Recent Raw Tail + Segment Summary + Structured Memory。向量召回可以作为候选信号，但不能绕过 Scope、状态和审核。

### 13.4 Topic Segmentation 怎么理解

主题分段可以依赖显式任务 ID、实体变化、语义相似度下降和话语标记。若用相邻消息 Embedding，相似度低于阈值可作为切分候选，但不能直接切，因为“换一种问法”也可能向量变化。项目还结合活动任务、最近指代和结构信号，低置信度时保留在活动窗口并等待更多消息。

### 13.5 摘要的压缩率和保真度怎么权衡

压缩率高会节省 Token，但可能丢失数字、否定、决策和约束。摘要应区分事实、用户决策、待办、纠正和开放问题，并保留 Message Ref。评估不能只用 ROUGE 等词面指标，还要测试关键约束问答、数字一致、否定保留和原文回读。

### 13.6 Working Memory、Episodic Memory、Semantic Memory

Working Memory 是当前任务所需的短期状态；Episodic Memory 记录某次经历或任务指针；Semantic Memory 是跨任务稳定的偏好、规范和事实抽象。项目当前主要保存偏好、规范、已确认决策、负向约束和 Episode Pointer，不把外部资料事实自动提升为用户 Memory。

### 13.7 Memory Rank 怎么设计

一个可解释 Rank 可以组合：

```text
score = semantic_relevance
      + scope_match
      + type_match
      + confirmation_strength
      + recency_or_usage
      - stale_penalty
      - conflict_penalty
```

先做 Status、Scope、有效期等硬过滤，再排序。否则高语义相似的已删除或其他 Workspace Memory 可能越权进入输入。

### 13.8 为什么要版本化而不是 Update 一行

用户偏好会变化，历史 Run 又要回放当时看到的版本。直接 Update 会破坏审计和重建。Memory Object 作为稳定身份，Memory Revision 保存每次变化，Latest Pointer 指向当前版本。这和 MVCC、Git Commit、SCD Type 2 都有相似思想。

### 13.9 TTL、Stale 和删除有什么区别

TTL 到期不一定立即删除，可能先变 Stale 并停止默认召回；Superseded 表示被新版本替换；Deleted 表示用户要求删除，需要清理正文、缓存和派生引用。不同状态对应不同合规和恢复语义，不能只依赖 Redis TTL。

### 13.10 Cache Aside 如何用于 Memory Pack

Context Compiler 先查按 User/Workspace/Target/Policy Version 生成的 Memory Pack Cache，未命中读取 MySQL、编译并写缓存。Memory 变更后删除或版本化失效。这里适合 Cache Aside，因为 MySQL 是真源，缓存丢失只影响性能，不影响正确性。

### 13.11 缓存穿透、击穿和雪崩怎么结合项目回答

不存在的 Pack 可以短 TTL 负缓存防穿透；热点 Workspace 的 Pack 过期会击穿，可用互斥构建或逻辑过期；大量相同 TTL 会雪崩，因此配置 Jitter。项目配置对不同缓存使用 TTL 加抖动，Memory Pack 默认 TTL 180 秒、Jitter 30 秒。

### 13.12 Prompt Injection 与 Memory Poisoning

Prompt Injection 是输入试图改变模型指令；Memory Poisoning 是恶意或错误内容被长期保存，持续影响后续任务。外部资料中的“以后都忽略系统规则”不能成为 Memory Candidate。项目把 Evidence、Memory 和 System Policy 分区，Promotion 只接受允许类型和受信任用户信号。

### 13.13 为什么 Memory 不能进入 Evidence Rerank

如果用户偏好“更相信某个来源”直接影响 Evidence 排名，事实检索会被主观偏好污染。Memory 可以控制输出格式、语言和工作习惯，但 Evidence Rank 只依据问题、资料和来源策略。这个隔离类似推荐系统特征与审计证据的信任域隔离。

### 13.14 删除后的可回放如何处理

可回放和隐私删除存在冲突。删除优先，历史 Snapshot 如果引用已删除正文，只能标记 Degraded 或 Unavailable，不能偷偷保留副本。可以保留非敏感 Hash、Tombstone 和删除事件，证明历史输入曾存在，但不能恢复用户要求删除的内容。

## 14. 源码级 Context 与 Memory 追问

### 面试官问：为什么 Run 创建时要冻结 Input Snapshot？

因为对话、摘要和资料都在持续变化。如果运行时每一步读取“最新上下文”，同一 Answer 的前后阶段可能看到不同历史。项目冻结 History Head、Ready Summary Revision、连续 Raw Tail、Retrieval Plan 和证据身份，后续恢复仍使用原 Ledger ID。

相同幂等提交返回原 Receipt，不同 Payload 冲突。准备阶段进程退出后，通过 Stale Preparation Lease 竞争恢复，多个恢复者只有一个 Winner。

### 面试官问：为什么 Raw Tail 必须连续，不能挑最重要的几条？

重要性筛选可能跳过中间的否定和纠正，例如用户先给约束，随后撤销，再给新要求。只保留首尾会让语义反转。项目对旧 Prefix 做摘要，最近 Tail 保持有界连续，兼顾 Token 和局部对话因果。

### 面试官问：Summary 已经生成，为什么不能立刻使用？

生成完成只代表 Revision Ready，不代表它仍覆盖当前 Segment Head。Promotion 时要比较 Source Revision；旧 Worker 晚到时标记 Stale。Building Summary 不进入新 Snapshot，Ready Summary 替代覆盖消息时也不能与 Raw Message 重复出现。

这可以联系 MVCC 和乐观并发，但要说清楚是业务层版本可见性，不是自己实现数据库事务隔离。

### 面试官问：哪些 Memory 可以立即生效？

通过可信用户动作明确保存、低风险、窄 Scope、可撤销且不与 Active Memory 冲突的偏好可以立即创建 Active Revision，比如“这个 Workspace 的讲义使用三级标题”。跨 Workspace 传播、安全规则、敏感信息和高风险事实必须确认。模型推断、网页内容、工具输出以及保存、导出、重试等行为信号只能形成 Derived Candidate，不能伪装成显式指令。

回答重点是风险分层，不是给所有 Candidate 一个统一置信度阈值。

### 面试官问：Memory 的 Utility 是固定分数吗？

不是。应用结果可以平滑调整 Derived Memory 的排序先验，负向结果触发 Review。Utility 低不能自动把 Canonical Memory 标成 Stale，也不能撤销 Explicit Memory；保存、导出、重试和编辑都只是弱信号。明确纠正、用户撤销、到期、来源失效或人工复核结论才推进状态。Utility 变化会改变 Pack Fingerprint，使缓存自然失效。

### 面试官问：Token 超限时为什么不能截断 Memory 文本？

半条 Memory 可能丢掉否定词、适用范围或例外条件。Compiler 按完整 Memory Object 截断，先做 Scope 隔离，再按 Specificity、Utility、Freshness 和 Neighborhood 排序。Policy Version 和确定性 Token Estimate 写入 Pack，保证相同输入可重放。

### 面试官问：Redis 失败时返回空 Memory 有什么问题？

空 Memory 是合法业务状态，Redis 失败是能力降级。项目在 Schema 或 Fingerprint 不匹配、Redis 故障和未配置时回退 Compiler，并记录 Explicit Degradation。缓存只影响性能，不能改变用户是否存在长期约束的语义。

### 面试官问：资料删除后，历史回答还能完整回放吗？

不能承诺完整回放。Manifest 保留 Evidence Identity、Hash 和删除事实，但正文 Redact，Replay 降级。删除 Ready Summary 还会让依赖它的更早回放降级，新 Snapshot 不再选择这些内容。隐私删除优先于可复现性。

### 面试官问：流式回答断线重连怎么保证顺序？

Mux 先 Prime Replay，再订阅 Live，避免终态先到导致历史 Delta 丢失。Cursor 只重放之后事件，早于有界窗口则明确拒绝并让客户端查询最终结果。Token Delta 短窗口合并，多个实例通过共享 Bridge 传播，不重复启动生成。

Conversation Cursor 可以聚合同一会话的多个 Run，消息序号通过原子分配得到连续 User/Assistant Pair。

## 15. 4 到 5 分钟标准主回答

这个亮点解决的是两个经常被混在一起的问题：一个是长会话如何压缩成模型当前能用的 Context，另一个是哪些信息值得跨任务保存成长期 Memory。上下文窗口变大以后，这两个问题仍然存在。把全部历史直接塞给模型会增加 Token 和延迟，重要约束还可能因为 Lost in the Middle 被忽略；如果把所有对话自动写成 Memory，又会把临时内容、错误推断和外部 Prompt Injection 长期保留下来。

我先把数据分成 Conversation Ledger、Segment Summary、RunInputSnapshot 和 Canonical Memory。Conversation Ledger 保存不可变消息及顺序，是对话真源；Segment Summary 压缩已经结束的旧话题；RunInputSnapshot 冻结某一次 Answer、Research 或 Artifact 实际看到的上下文；Memory 保存跨任务仍然有价值的偏好、约束和已确认决策。

长链路压缩采用增量 Summary。系统只压缩旧 Active Prefix，最近一段 Raw Tail 保持连续原文。Summary Build 绑定 Source Revision，生成完成后先成为 Ready Revision，Promotion 时再次检查 Segment Head。用户在摘要生成期间继续发消息，旧 Worker 晚到时只能标记 Stale，不能覆盖新版本。Context Compiler 只选择已经晋升的 Summary，Building 状态继续使用旧 Summary 加 Raw Tail。

每次运行都创建不可变 RunInputSnapshot，固定 History Head、Summary Revision、连续 Raw Tail、Retrieval Plan 和 Evidence 身份。这样模型调用失败后恢复，不会读取后来新增的消息，也不会因为摘要更新得到另一份输入。相同幂等提交返回原 Receipt，不同 Payload 使用同一键则冲突；准备阶段进程退出后，由 Stale Lease 竞争出一个恢复者，并复用原 Ledger ID。

Context Compiler 不是把所有内容拼接起来。它先放系统规则和当前问题，再选择主题相关 Summary、近期原文、显式 Pin 和 Memory Pack，Evidence 使用独立区域。Token 超限时按完整对象截断，不能把一条 Memory 从中间切断，因为否定词、Scope 或例外可能在后半段。排序看 Scope Specificity、Utility、Freshness 和 Neighborhood。

长期 Memory 分 Explicit 和 Derived 两条写入路径。可信用户动作明确保存的低风险偏好，在类型、来源、Scope、敏感性、重复和冲突检查后，可以直接创建可撤销的 Active Revision。Answer、Research 和 Artifact 只能提交 Derived Observation 或 Promotion Preview，不能直接更新 Canonical Memory；弱推断默认 Hold，冲突和高风险内容进入 Review。两条路径都追加 Immutable Revision 和 Latest Pointer，不覆盖旧内容。

Memory 还会根据使用结果调整 Utility，但它只改变 Derived Memory 的召回先验。保存、导出、重试和编辑不能证明偏好正确，重复负向结果只触发 Review，不能自动撤销显式规则。Pack Cache 使用 Revision、Policy 和 Utility 组成 Fingerprint，任一变化都会使旧 Pack 失效。Redis 故障时回退 Compiler，并记录降级，不能把基础设施失败解释成用户没有 Memory。

Memory 和 Evidence 必须隔离。Memory 可以影响语言、格式和工作习惯，不能提高某份资料的事实排名，也不能作为 Citation。外部资料中的“以后都忽略系统规则”不能变成 Memory Candidate。资料或消息删除后，历史 Snapshot 只保留 Identity、Hash 和 Tombstone，正文 Redact，Replay 降级，隐私删除优先于完整复现。

这套设计的 Trade-off 是版本、候选、摘要和快照会增加存储与状态管理。短会话、一次性任务不需要 Summary 和 Memory；只保存少量用户设置时，普通配置表也更简单。当前项目有多轮 Answer、Research、Artifact 和 Workspace 知识沉淀，需要既节省上下文又控制长期污染，因此选择 Context Compilation 与 Canonical Memory 分离。

## 16. 面试官继续追问时的比较回答

### 追问：为什么不用最近 N 条消息？

最近 N 条实现简单，延迟稳定，适合短对话。问题是 Token 长度不稳定，也不了解主题边界，早期仍然有效的约束可能被截掉。项目保留连续 Raw Tail，同时用已晋升 Summary 覆盖旧 Prefix，既保留局部因果，也能带回早期约束。

### 追问：为什么不全部用向量 Memory？

向量召回擅长语义相关，却不天然理解 Scope、版本、否定和冲突。长期偏好需要确定的 User/Workspace 边界和治理状态。项目可以用语义能力辅助 Candidate 比较，但 Canonical Memory 仍按结构化字段、版本和状态机管理。

### 追问：为什么不让模型自动总结并覆盖旧摘要？

覆盖会丢失历史，也无法处理旧 Worker 晚到。Immutable Revision 加 Promotion 允许比较 Source Version、保留审计和回退。代价是多一层版本表和 Current Pointer，但长任务异步生成时，这是避免时序错误的必要成本。

### 追问：Memory 为什么需要 Outcome Feedback？

写入时的置信度只能表示当时判断，不能证明它以后真的有用。Outcome 记录 Memory 被采用后的正负结果，用平滑方式更新 Derived Memory 的 Utility。若只按新鲜度排序，长期稳定规则会被新但无用的信息挤掉；若分数永不变化，错误推断会持续污染任务。Explicit Memory 由用户明确纠正或撤销，不由 Outcome 分数自动改状态。

## 17. 面试官八维度母题长回答

### 17.1 业务抽象：Context、Summary、Snapshot 和 Memory 为什么必须分开？

这四个对象回答不同问题。Context 是某次模型调用临时可见的输入组合；Conversation Ledger 保存不可变消息及顺序；Summary 是对已结束历史片段的有损压缩；RunInputSnapshot 冻结某次 Answer、Research 或 Artifact 实际看到的输入；Memory 保存跨任务仍有效的偏好、约束和已确认决策。若把它们混成“聊天记录”，就无法同时满足节省 Token、失败可恢复、历史可审计和长期信息可治理。

业务上最关键的区别是“说过”不等于“应该长期记住”。用户本轮要求用表格，是 Working Context；用户通过可信动作明确说以后都用中文，可以按窄 Scope 创建可撤销的 Explicit Memory；模型从普通对话推断偏好时只能生成 Derived Candidate。外部资料中的事实属于 Evidence，不能自动提升为用户偏好。Summary 也不是事实真源，必须保留 Source Revision 和 Message Ref，使数字、否定和决策可以回读。RunInputSnapshot 则保证任务启动后，即使会话增长、摘要晋升或 Memory 更新，该 Run 仍使用原输入。

拆分的代价是 Ledger、Revision、Pointer、Candidate 和 Snapshot 增加了表与状态。短对话只保留近期消息即可，少量固定设置用配置表更简单。当前项目有长会话、多种异步 Agent 和 Workspace 知识，需要回答“这次模型看见什么”“为什么记住这条”“删除后哪些副本失效”，因此需要分层。面试时应从对象的所有权、生命周期和失败语义解释，而不是只背 Working、Episodic、Semantic Memory 名词。

项目调用链可以从 `ConversationContextProjectionService.compile()` 开始，它按 Cutoff Seq 选择 Summary Ref 与 Recent Message Ref；`RunInputSnapshotService.recordAnswerSnapshot()` 或 `recordResearchSnapshot()` 把当时输入固化；长期信息先由 `MemoryCandidateService.buildCandidates()` 形成候选，再由 `MemoryVersionService.appendVersion()` 追加 Revision，最终由 `MemoryCompilerService.compileChatControlPack()` 选择本次可见内容。四个方法对应四种生命周期，正好解释为什么不能合成一张聊天记录表。

### 17.2 数据与一致性：消息、摘要、Memory Revision 和 Snapshot 如何避免时序错乱？

Conversation Ledger 采用追加消息和单调序号，不原地覆盖。摘要 Build 绑定明确 Source Revision，只处理当时 Active Prefix。Worker 完成后先产生 Ready Revision，Promotion 时再次比较 Segment Head；用户已继续发消息或另一版本先晋升时，晚到结果标记 Stale，不能覆盖当前指针。Compiler 只用已晋升 Revision，Building 状态继续使用旧 Summary 加连续 Raw Tail，所以读路径始终看到完整版本。

Memory 同样采用稳定对象、不可变 Revision 和 Latest Pointer。Candidate 晋升在事务中做状态与版本检查，冲突 Active Memory 不做最后写入获胜，而是进入 Review、Superseded 或显式合并。Pack Cache Key 包含 User/Workspace Scope、Revision、Policy Version 和 Utility Fingerprint，治理变化会使旧 Pack 失效。Redis 失败回退 MySQL 编译，不能把“缓存读不到”解释为“用户没有 Memory”。

每次 Run 创建不可变 Input Snapshot，固定 History Head、Summary Revision、Raw Tail、Memory Pack 和 Evidence Identity。相同幂等提交返回原 Receipt，不同 Payload 复用键则冲突；准备阶段崩溃后由 Stale Lease 竞争一个恢复者并复用原 Ledger。这里不是全局强事务，而是每个对象有版本、指针和可见性规则。删除优先使新编译不可见，历史 Snapshot 只保留允许的 Identity、Hash 和 Tombstone，正文 Redact，因此隐私删除可能让历史回放降级，这个取舍必须明确。

### 17.3 并发与容量：长上下文、摘要任务、缓存和流式连接如何治理？

容量首先由 Token 而不是消息条数决定。两段同为 20 条消息的会话，代码和长文可能相差几个数量级。Context Compiler 先给 System Policy、当前问题、Evidence、Recent Raw Tail、Summary 和 Memory 分配预算，再按完整对象选择，不能从一条 Memory 中间截断。记录 Estimated Token、Provider 实际 Token、各区域占比和截断原因，才能判断预算是否有效。长 Context 即使没有超过窗口，也会增加 Prefill 延迟、费用和信息干扰。

摘要是异步工作，需要限制每个 Conversation 的在途 Build，并用 Source Revision 合并重复触发。全局并发受 Provider 配额、Worker 数和数据库连接池约束，不能每新增一条消息就立即摘要。阈值、冷却窗口和批量 Prefix 可以降低抖动，旧 Build 晚到由 Promotion 检查吸收。Memory Pack 缓存减少重复编译，TTL 加 Jitter 防止同时失效，负缓存防穿透；热点 Key 使用互斥构建或逻辑过期，Redis 故障则回源并限流。

流式回答还涉及 SSE 连接、Dispatch 线程池和 Redis Bridge。连接数、生成并发和 Token 速率是不同容量维度，应使用有界队列和拒绝策略。按 Little 定律，在途 Run 等于到达率乘服务时间，长上下文使服务时间增长会直接放大在途量。当前 TTL、线程池与 Token 阈值只是配置，不是容量结果。可靠结论需要对短会话、长会话、热点 Workspace、缓存击穿和断线重连分别压测，并报告 P95/P99、成本与降级行为。

真实设置包括 `MemoryCompiledPackCache` TTL 180 秒、Jitter 30 秒，Answer 与 Conversation Replay 各 512 条，单 Subscriber Queue 64，SSE Connection Executor 为 `4/32/0`。缓存 Key 同时包含 Workspace、Actor、Pack Type、Request、Policy 与 State Fingerprint，避免只靠 TTL 处理状态变化。`MemoryCompiledPackCacheTest` 和 `ConversationEventMuxTest` 验证缓存包与有界事件行为，但这些测试没有给出生产长会话并发上限。

### 17.4 安全：怎样防止 Prompt Injection 变成长期 Memory Poisoning？

第一层是来源与用途隔离。System Policy、用户消息、Workspace Memory 和外部 Evidence 放在不同区域并带来源标签。外部资料中的“忽略规则”只是被检索文本，不能成为 Memory Candidate，也不能改变工具权限。Memory 可以影响语言、格式和工作习惯，不能进入 Evidence Rerank、提高某来源的事实权重或作为 Citation。恶意文档即使进入 RAG，也不能借 Memory 跨任务持久化。

第二层是 Memory 写入治理。可信用户动作明确保存的低风险偏好，经过窄 Scope、敏感性、重复和冲突校验后可直接追加 Active Revision；涉及事实、权限、身份和跨 Workspace 规则时仍需确认。模型推断和行为信号记录 Actor、Source Message、Scope、置信度和规则版本，只能形成 Derived Candidate；弱推断默认 Hold，与 Active Memory 冲突进入 Review。模型只能提交 Observation 或 Preview，不能直接更新 Canonical Memory。读取前再做 Status、Scope、有效期和冲突过滤，语义相似度不能绕过 User、Workspace 和 Target 边界。

第三层是删除与隐私。删除消息、资料或 Memory 后，新 Snapshot 不再选择它，Pack Cache 和派生 Summary 失效或重建。审计只保留必要的 Tombstone、Hash 和删除事件，不能以可回放为由保存正文副本，日志也不记录完整 Memory 和 Prompt。当前实现控制应用层晋升、Scope 与删除传播，不等于完整 DLP、内容分类或法规认证；严格场景还需要加密分域、保留策略、导出删除证明和独立安全评估。

生产方法层面，`MemoryCandidatePolicy` 决定哪些 Signal 可以进入候选，`MemoryCompilerService.compileChatControlPack()` 在读取时再次执行 Scope 与风险过滤，`MemoryVersionService.revoke()` 通过新状态撤销 Memory，而不是删除后继续使用旧缓存。`MemoryCandidatePolicyTest`、`MemoryCompilerPolicyTest` 和 `MemoryVersionServiceTest` 分别验证候选门禁、编译选择与版本撤销。外部 Evidence 从未经过这些方法晋升为 Memory，这条边界比 Prompt 中写一句“不要记忆”更可靠。

### 17.5 可观测性：模型答错时，如何判断是检索、摘要还是 Memory 导致？

每次 RunInputSnapshot 都应有可解释 Manifest：History Head、Summary Revision、Raw Message 范围、Memory Revision、Evidence ID、Policy Version、Token Budget、截断项和 Pack Fingerprint。排障先复原模型实际输入，再检查每项来自哪里、为何被选中、当时是否有效。没有 Snapshot，只看当前数据库会得出错误结论，因为会话、摘要和 Memory 在任务后可能已经变化。

指标按编译漏斗观察。输入侧看 Ledger 增长、Segment 大小和 Token 分布；摘要侧看 Build 时长、Promotion、Stale 比例、压缩率和关键约束保留率；Memory 侧看 Candidate 来源、Hold/Review/Active 分布、冲突、删除传播、命中与负反馈；缓存侧看 Hit、Miss、Fallback、Schema/Fingerprint Mismatch；运行侧看各区域 Token、截断率、首 Token 延迟和断线重放。单看缓存命中或模型错误率无法定位根因。

具体错误使用 Replay 与 Ablation：先用原 Snapshot 重放，再分别移除某条 Memory、替换为原始消息、禁用 Summary 或固定 Evidence，观察结果变化，区分摘要丢失否定、Memory 污染、检索不足或模型随机性。可以定义 Snapshot 可重放率、关键约束保留率、Stale 阻断率等 SLI，但没有线上样本就不编造准确率。当前能记录版本与降级；当会话长度、模型调用次数或排查成本增长时，再增加跨阶段关联和自动归因。

项目现在能查看的证据来自 `RunInputSnapshotService.get()`、`MemoryCompilerService` 的编译结果与 `MemoryCompiledPackCache` 的 Hit、Miss、Invalid、Error 指标。排查时先读取固定 Snapshot，再比较 Memory Revision、Policy Version 和 State Fingerprint；若当前数据库内容不同，也不能改写历史 Run 的输入解释。`MemoryCompilerServiceTest` 能验证确定性选择，尚没有自动执行所有 Ablation 并给出根因概率的线上系统。

### 17.6 成本：Summary 和 Memory 也调用模型，为什么总体可能更省？

成本要比较生命周期总额。直接携带全部历史时，每次后续请求都会重复支付旧 Token 的 Prefill；增量 Summary 支付一次压缩成本，后续复用更短表示。是否划算取决于会话剩余轮数、旧 Prefix 长度、摘要模型价格和压缩率。若会话即将结束，提前摘要可能纯属浪费，所以应在 Token 阈值、活跃度和预期复用满足条件时触发，而不是固定每 N 条执行。

Memory 也有提取、Review、存储、缓存失效和错误污染成本。只保存跨任务高价值信息，并在 Candidate 阶段去重与冲突检测，可以避免每轮从完整历史重新推断偏好。Pack 缓存降低重复查询，但 Key 必须包含 Scope、Revision 和 Policy，不能为命中率牺牲隔离。Outcome Feedback 让长期无效 Memory 降权或 Stale，避免持续占用 Token。删除和重建成本也要纳入核算。

应按单个成功 Run 统计原始历史 Token、Summary Token、Memory Token、编译与摘要调用费、缓存节省、因错误 Memory 导致的重试和人工纠正。短会话走 Raw Tail，不生成 Summary；少量固定设置直接配置表。长上下文模型价格下降会改变阈值，但不会消除延迟、注意力干扰、隐私和可回放问题，因此演进依据应是实测总成本与质量，不是“窗口已经足够大”。

还可以计算一个简单的盈亏点：摘要一次花费为 S，每个后续 Run 因压缩节省 C，则至少复用 `ceil(S/C)` 次才在 Token 账面回本；若摘要导致关键信息丢失，返工成本还要加回去。不同会话长度和 Skill 应使用不同触发阈值，通过 Shadow 记录“若不摘要会花多少 Token”，先验证节省空间再启用自动压缩。这个模型避免为了展示 Context Engineering 而对所有会话一刀切。

### 17.7 测试证据：怎样证明摘要没丢关键约束、Memory 没串租户？

一致性测试要制造竞态。摘要 Worker Build 期间插入新消息，断言旧 Revision 只能 Stale；两个 Worker 同时 Promotion，断言只有匹配 Source Head 的版本成为 Current；准备阶段崩溃后多个恢复者竞争，断言只复用一个 Ledger 和原 Snapshot。Memory 测试覆盖 Candidate 去重、冲突 Active Memory、Scope 优先级、Superseded、TTL/Stale、用户删除和 Pack Fingerprint 失效。Redis 故障时断言回源或显式降级，绝不能返回空 Memory 伪装成合法结果。

安全测试构造不同 User、Workspace 和 Target 的相似 Memory，确保语义相似也不能跨 Scope；把 Prompt Injection 放进外部 Evidence，确保不会产生可晋升 Candidate；撤权和删除后检查新 Snapshot、缓存、Summary 与检索引用。质量测试使用包含数字、否定、例外、决策变更和多主题切换的 Gold Conversations，测关键约束问答、Message Ref 回读、压缩率和冲突处理。Ablation 比较 Raw Only、Summary、Memory 和混合方案。

证据层级必须说清。单元测试证明排序、状态机和 Token 截断；MySQL/Redis 集成测试证明 CAS 与缓存降级；端到端测试证明特定会话可回放；人工评审和线上 Shadow 才接近用户质量。Fixture 通过不等于线上准确率，模型评审也不能取代关键字段的确定性断言。面试时说明样本构成、失败案例与阈值来源，未执行的压测或线上实验不能当成已完成结果。

当前可点名的测试包括 `ConversationMessageSequenceTest` 验证消息序号，`ConversationTurnModuleContractTest` 验证 Turn 与 Snapshot Contract，`MemoryCandidatePolicyTest` 验证风险候选，`MemoryVersionServiceTest` 验证追加与撤销，`MemoryCompilerServiceTest` 和 `MemoryCompiledPackCacheTest` 验证选择与缓存身份。仓库尚缺大规模人工 Gold Conversation、Summary 关键约束保持率和删除全链路时延统计，这些应列为上线门禁，而不是用单元测试数量掩盖。

### 17.8 演进边界：何时使用向量 Memory、自动晋升或直接依赖长上下文？

向量 Memory 适合 Memory 数量大、关键词不稳定且跨时间语义召回确实成为瓶颈时，但它只能做候选召回。召回后仍检查 User/Workspace Scope、Status、Revision、有效期、冲突和类型，不能让相似度成为授权。结构化偏好和明确规则继续用数据库字段更可靠。向量库还增加 Embedding 版本、删除传播、重建和成本，只有离线 Recall 与线上收益证明价值后才引入。

自动晋升只针对 Derived Candidate，并按风险开放。Explicit Memory 来自可信用户动作，低风险、窄 Scope、可撤销且无冲突时可以直接生效；涉及事实、权限、身份或跨 Workspace 规则仍需确认。Derived 路径先积累误晋升、冲突与负反馈数据，再对来源明确、类型受限的候选灰度。阈值与规则版本化，旧 Memory 可追溯当时策略。连续负结果只能降权并触发 Review，不能仅凭 Utility 将 Canonical Memory 标成 Stale；明确纠正、撤销、到期、来源失效或复核结论才推进状态。

长上下文模型可以提高 Raw Tail 和 Evidence 预算，减少摘要频率，但不会替代 RunInputSnapshot、Memory 治理和删除语义。只有实测表明全量历史在延迟、成本和质量上都优于压缩，且业务不要求稳定回放时，才简化 Summary。长期保持 Ledger 真源、输入快照、来源隔离、Scope 过滤和可删除这些不变量，Summary 模型、缓存、向量库和 Context Window 都可按数据替换。

迁移也要允许双轨与回滚。向量召回先以 Shadow 方式记录候选，不直接进入 Prompt；自动晋升先生成建议并统计人工接受率；长上下文先在固定 Gold Conversation 上与 Summary 方案做 Ablation。新路径必须复用同一 Snapshot Manifest 和评测口径，否则结果不可比较。删除传播、旧 Revision 回读和缓存隔离任何一项无法证明时，都不应扩大灰度范围。这里的演进边界既看模型效果，也看治理能力是否跟得上。

## 18. 八维母题的项目源码答辩卡

| 评分面 | 项目功能与生产类 | 核心方法、状态或真实设置 | 验证证据与当前边界 |
|---|---|---|---|
| 业务抽象 | `ConversationContextProjectionService.compile()` 形成对话投影，`RunInputSnapshotService.recordAnswerSnapshot()` 固化运行输入，`MemoryCandidateService.buildCandidates()` 管理长期候选 | Context 是本次输入，Summary 是有损压缩，Snapshot 是执行凭证，Memory 是可治理的长期状态 | `ConversationTurnModuleContractTest`、`MemoryCandidatePolicyTest`；四者不能互相替代 |
| 数据与一致性 | `ConversationMessageSequence` 维护消息顺序，`SegmentSummaryPromotionService.promote()` 晋升摘要，`MemoryVersionService.appendVersion()` 追加 Revision | Promotion 校验 Segment、Revision 和当前状态；Memory 追加版本，不原地覆盖；Run 固定 Snapshot 后不读取漂移中的最新状态 | `ConversationMessageSequenceTest`、`MemoryVersionServiceTest` |
| 并发与容量 | Context Compiler 受 Token Budget 约束，Summary 异步构建，`MemoryCompiledPackCache` 缓存编译结果，SSE 队列有界 | Memory Cache TTL 180 秒、Jitter 30 秒；Replay 512、Subscriber Queue 64 | Memory Cache、Compiler、SSE Test；缓存命中率和长上下文容量没有线上基线 |
| 安全 | `MemoryCandidatePolicy` 区分事实、偏好和高风险指令，`MemoryCompilerService.compileChatControlPack()` 按 Workspace 与 Actor Scope 取数，Evidence 单独进入 Citation 链路 | Memory 不进入 Evidence Rerank，不生成 Citation；高风险候选需要 Review，删除产生 Tombstone 或回放脱敏 | `MemoryCandidatePolicyTest`、`MemoryCompilerPolicyTest`；自动识别 Prompt Injection 不是绝对安全保证 |
| 可观测性 | `MemoryCompilerService.compileChatControlPack()` 输出编译结果并记录数据库 Load，Context Inspector 展示消息、Summary、Memory 和 Evidence 组成 | 诊断记录各来源 Token、裁剪原因、Revision、Policy Version 与 Cache Result | `MemoryCompilerServiceTest`、`MemoryRuntimeContractTest`；当前没有线上自动归因模型 |
| 成本 | Summary 用一次压缩换后续多次 Token 节省，Memory Pack 只选择与当前任务相关的条目 | 成本比较应计算 Summary 调用、缓存命中、后续 Prompt Token 与质量回退，不只看单次请求 | Compiler、Cache、Outcome Policy Test；尚无真实用户长期会话的盈亏平衡数据 |
| 测试证据 | 覆盖摘要晋升冲突、Memory 版本、缓存 Key、删除回放、Scope 和确定性编译 | 相同 Snapshot 与 Policy 应得到相同输入；旧 Revision、跨 Workspace 与删除来源不得泄漏 | 对应单元和集成测试存在，尚未形成大规模人工摘要质量集 |
| 演进边界 | `MemoryShadowRecallService.recall()` 先 Shadow 评估候选，自动晋升先记录建议和接受率 | 新召回不直接进 Prompt；保持 Ledger、Snapshot、Scope、Revision 和删除语义不变 | `MemoryRuntimeContractTest`、`Phase5MemoryContractTest`；当前没有独立向量 Memory 库 |

## 当前运行时核对

Memory API 位于 `/api/v2/workspaces/{workspaceId}/memory`，提供 signal、review、revision、control-pack、revoke 和 outcome。`V080` 创建 canonical runtime，`V082` 完成 compiler cutover；当前文档不能只把 `memory_object` 当唯一真源。Memory 不参与资料 Citation/Evidence Rerank，control-pack 按 chat、artifact、research scope 编译。

## 19. 面试版上下游链路与技术取舍长回答

Context 和 Memory 的链路要从消息真源讲起。用户消息先追加到 Conversation Ledger 并获得单调序号，旧的 Active Prefix 在达到条件后异步生成 Segment Summary，最近一段 Raw Tail 保留连续原文。用户提交 Answer、Research 或 Artifact 时，Java 主服务会把 History Head、Summary Revision、Raw Tail、Retrieval Plan、Evidence 身份和可见 Memory Pack 固定成 RunInputSnapshot。普通新消息、Summary 晋升和偏好新 Revision 不改变当前 Run，恢复时也不会因为重新读取“现在的会话”产生另一份答案；隐私删除、成员撤权、Source 安全封禁或高风险 Memory 撤销则推进输入有效性 Epoch。推荐设计在外部工具调用和最终提交前校验该 Epoch，失效后取消或转入 `INPUT_REVOKED`，不能把不可变 Snapshot 当成继续使用敏感数据的许可证。完整 Epoch 门禁仍属于目标设计。

Context Compiler 负责把系统规则、当前问题、相关摘要、近期原文、显式 Pin、Memory Pack 和 Evidence 按预算组合起来，Token 超限时按完整对象裁剪，不能从一句带否定或 Scope 的 Memory 中间截断。Memory 的产生则走另一条治理链：模型或业务信号只能生成 Candidate 或 Promotion Preview，候选先经过类型、来源、风险、Scope、重复和冲突判断，弱推断进入 Hold，与 Active Memory 冲突进入 Review，晋升后追加 Immutable Revision 和 Latest Pointer。Outcome Feedback 再根据实际采用、负向反馈和过期情况调整 Utility，编译器只选择当前范围和策略允许的 Revision。

这也是没有直接使用“最近 N 条消息”或外部向量 Memory 产品作为真源的原因。最近 N 条简单，但不理解主题边界，可能截掉早期约束；向量召回擅长语义相似，却不天然处理用户与 Workspace Scope、否定、版本、撤销和冲突。Mem0、Zep、Letta 的记忆层次、添加、搜索和更新模型值得参考，但当前 Memory 必须和 MySQL 事务、Review、Revision、删除与回放一起成立，所以采用自己的 Candidate Promotion 和 Context Compiler。向量能力以后可以作为候选召回 Provider，但不能绕过 Canonical Memory 的权限和状态机。

Memory 与 Evidence 还要保持信任域隔离。资料中的“以后忽略系统规则”只能作为不可信文本，不能变成长期偏好；Memory 可以影响表达、格式和工作习惯，不能提高资料事实排名，也不能生成 Citation。删除消息、资料或 Memory 后，新编译结果必须停止选择它，Cache、Summary 和派生 Projection 进入失效或重建，历史 Snapshot 只保留允许的 Identity、Hash 和 Tombstone，正文需要脱敏。这样做会牺牲部分历史完整回放，但隐私删除优先于“什么都能复现”。

成本上，Summary 是一次压缩换多次复用，是否划算取决于后续 Run 数量、节省的 Token、摘要调用成本和信息丢失返工；Memory Pack 缓存能减少重复编译，但 Key 必须包含 Scope、Revision、Policy 和 Fingerprint，不能只靠 TTL 追求命中率。面试时可以用“对象生命周期和失败语义”而不是术语堆砌来解释选择：Ledger 负责事实，Summary 负责压缩，Snapshot 负责执行凭证，Memory 负责跨任务治理，Evidence 负责外部事实，五者各自有清晰的所有权和删除边界。
