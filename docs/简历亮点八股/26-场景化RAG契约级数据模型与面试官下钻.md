# 场景化 RAG 契约级数据模型与面试官下钻

> 默认主回答见[场景化 RAG 一体化面试手册](31-场景化RAG一体化面试手册.md)。本文只在面试官追问 RetrievalPlan、EvidenceBundle、PromptSpec、AnswerRun、三种策略合同和失败降级时使用，不重复讲完整演进。

> 证据标签：`[当前实现]` 只说明合同或链路能从源码证明；`[已测-模拟]` 只说明固定回放覆盖了某类 Case；`[演练假设]` 是理想数据卡；`[目标设计]` 包括 Note Draft Revision、Wiki Proposal 和更完整审批；线上质量、P95 和行业横比仍是 `[生产待验证]`。当前 Wiki 是受限关系读取，不等于完整 GraphRAG。

> 定位：QA、Note、Wiki 共享一次回答的运行骨架，但不共享同一套召回和成文策略。这篇用合同、数据对象和反例解释为什么要拆三条链路，也给出当前实现与面试包装之间的稳定边界。

## 1. 一句话定位

我没有把 RAG 抽象成一个固定的 `retrieve(query) -> topK`，而是把稳定骨架定义为 `RetrievalPlan -> EvidenceBundle -> PromptSpec -> AnswerRun`，再让 QA、Note、Wiki 分别实现面向“直接回答、连续深读、知识组织”的策略。共享的是权限、版本、快照、引用和运行协议，不共享的是证据单位、排序目标和上下文组织方式。

## 2. 为什么一套 RAG 不够

| 模式 | 用户真正要的结果 | 最小证据单位 | 主要失败方式 |
| --- | --- | --- | --- |
| QA | 快速、直接、可引用的答案 | Passage / Chunk | 召回不到答案，或引用与答案不对应 |
| Note | 围绕某一资料连续理解和整理 | Source 内相邻 Reading Window | 单点命中却丢失上下文，笔记支离破碎 |
| Wiki | 稳定页面、链接、反链和主题关系 | Wiki Page Version + Link Neighborhood | 页面版本漂移、链接噪声和错误图扩展 |

如果三者都使用同一 TopK Passage：

1. QA 会被过长的相邻内容挤占 Token，答案反而不聚焦。
2. Note 只得到互不相邻的片段，无法解释定义的前提和例子。
3. Wiki 会把“文本相似”误当成“概念关系”，产生大量弱链接。

因此“场景化”不是 UI 上多三个按钮，而是检索目标函数不同。

## 3. 统一运行合同

```text
Turn Submission
  -> AnswerModeStrategy
  -> RetrievalPlan
  -> RetrievalOrchestrator
  -> EvidenceBundle + EvidenceBundleSnapshot
  -> PromptSpec
  -> AnswerRun / streamed events
  -> Citation / Feedback / Replay
```

### 3.1 RetrievalPlan

Plan 表达“准备怎样查”，而不是直接保存结果。典型内容包括模式、查询改写、来源范围、版本约束、召回通道、候选数、Rerank 和证据预算。把 Plan 固化有三个好处：

- 同一个问题可以在不同策略版本下 Shadow Replay。
- 出现坏答案时能区分是 Query、Retrieval、Selection 还是 Generation 的问题。
- 参数和 Feature Flag 在 Run 中冻结，避免执行中漂移。

### 3.2 EvidenceBundle

Bundle 是经过权限、归属、版本和预算过滤后的证据集合。它不等同于检索引擎返回的 Hit。每条 Evidence 需要带 Source、Snapshot、Chunk/Window/Page 定位、排名信号和模式元数据。

`EvidenceBundleSnapshot` 用于让 AnswerRun 在重放时看到当时那一版证据，而不是重新检索后得到变化的结果。

### 3.3 PromptSpec

PromptSpec 把系统指令、模式策略、引用约束、Evidence 和输出结构分开。这样可以单独升级 Prompt Version，并在评测中锁定“只变 Prompt，不变 Retrieval”。

### 3.4 AnswerRun

AnswerRun 是一次不可变的语义回答运行，记录状态、输入快照、策略、模型、总预算、事件和最终答案。Turn 是用户语义动作；人工再生成、模型或要求改变会在同一 Turn 下创建新 AnswerRun，只有被选中的结果进入会话视图。`[目标设计]` 瞬时 Provider 故障、Owner 丢失和进程恢复只在同一 Run 下追加 AnswerExecutionAttempt，Attempt 记录 Lease、Provider Receipt 和本代新增消费，不能把基础设施重试计成用户再生成，也不能为每代重置总预算。

## 4. 三种策略怎样接入同一骨架

| 合同环节 | QA | Note | Wiki |
| --- | --- | --- | --- |
| `buildPlan` | 多路 Passage 召回 | Source Recall 后定位 Anchor | 当前 Page 和受限邻居范围 |
| `retrieveEvidence` | BM25、向量、融合、Rerank | 同一 Source 内扩 Reading Window | Page Version、链接、反链、图邻居 |
| `selectEvidence` | 集合级去重、覆盖和 Token 预算 | 保持相邻次序与章节连续性 | 限跳数、限边数、限关系类型 |
| `buildPrompt` | 直接回答与引用 | 深读、解释、笔记草稿 | 页面摘要、关系解释和治理提示 |
| 核心 Guard | Workspace + Source Snapshot | Source 归属 + Window 边界 | Page Version + Workspace Link |

面试时要强调：`AnswerModeStrategy` 负责策略差异，`RetrievalOrchestrator` 负责稳定执行阶段，`EvidenceOwnershipGuard` 与 `EvidenceScopeGuard` 负责所有模式共用的安全不变量。它不是复制三份 Controller。

## 5. QA：Hybrid、RRF、Rerank 与集合级选择

### 5.1 为什么 Hybrid

BM25 擅长课程编号、术语和错误码等精确词，向量召回擅长同义表达和语义相近问题。单路召回会在另一类问题上形成系统性盲点，所以 QA 先保留多路候选。

### 5.2 为什么用 RRF，而不是直接相加分数

BM25 分数和向量相似度不在同一量纲，直接线性相加需要持续校准。RRF 使用排名而不是原始分数：

```text
RRF(d) = Σ w_i / (k + rank_i(d))
```

它对异构检索器更稳健，工程解释简单。代价是丢失原始分数间隔信息，因此高质量 Reranker 可在融合后进一步判断 Query 与 Passage 的相关性。

### 5.3 Rerank 不是最后一步

按相关性取前 N 条仍可能全部来自同一页、表达同一个事实。Evidence Selection 还要做 Snapshot 去重、来源多样性、关键子问题覆盖和 Token Budget。这里的目标是选择“共同构成足够回答依据的一组证据”，不是选出 N 个独立最高分。

### 5.4 拒答

拒答依据应来自证据充分性，而不是模型自报置信度。可以综合最高相关性、覆盖子问题数、引用资格和冲突状态。阈值过高会降低 Answer Rate，过低会提高 Unsupported Claim，因此需要用 Gold Set 画 Coverage 与 Faithfulness 的折中曲线。

## 6. Note：Source Recall、Anchor 与 Reading Window

### 6.1 两阶段检索

Note 先召回最相关 Source，再在 Source 内找 Anchor，并按结构和相邻位置扩成 Reading Window。这样把“资料是否相关”和“资料内哪一段值得细读”拆开，避免全库 Passage 竞争把同一章节的上下文打散。

### 6.2 Window 如何控制

不能固定向前后各取三个 Chunk。更合理的边界包括标题层级、段落位置、最大字符数、重叠率和章节跳变。扩得太小缺上下文，扩得太大浪费 Token，并把弱相关内容送入模型。

### 6.3 草稿与事实边界

Note 模式生成的是可编辑草稿，用户可以修改后继续使用。当前前端具备编辑体验，但后端还没有完整的 Draft Revision、Reject、Expected Version 和保存幂等合同。因此推荐包装为：

> 我把模型输出定位成可编辑 Note Draft，并在检索侧实现 Source 到 Reading Window 的连续深读；如果继续生产化，会把草稿升级为独立聚合根，增加 Revision、乐观锁、审批与幂等保存。

不要说“已经实现多人协作草稿和版本冲突合并”。这类追问会落到不存在的后端状态机。

## 7. Wiki：页面版本、链接和受限图扩展

### 7.1 Wiki 为什么不是对 Chunk 做 GraphRAG

Chunk 共现会制造大量偶然边。Wiki 使用稳定的 Knowledge Item 和 Page Version 作为节点，显式链接、反链和来源回链作为可解释关系。检索时只做受限图扩展，文本相关性仍然参与候选选择。

### 7.2 为什么必须版本化

页面内容、出链和摘要属于同一语义版本。如果正文更新而链接投影还没收敛，查询必须知道自己读的是哪一版，不能把新正文和旧关系拼成一个不存在的状态。

### 7.3 图预算

图扩展至少受跳数、最大节点、最大边、关系类型和 Workspace 约束。否则一个课程总览页会把整个知识库拖入上下文，成本和噪声呈指数增长。

### 7.4 当前写入边界

当前追加页面版本没有完整 Expected Head Version 合同；Auto-fix 会直接创建缺页，不是先生成 Proposal 再审批。因此推荐说：

> 当前实现覆盖页面版本、搜索、图谱、问题检测和自动修复演示。目标态会把高风险自动修复改成 Proposal，写入时校验 Expected Head Version，避免并发编辑和 Agent 修复互相覆盖。

不要把目标态审批流说成已经上线。

## 8. 三条可演示链路

### 8.1 QA 演练

用户问“课程退选的截止规则是什么”。精确规则词由 BM25 命中，语义通道补充“撤课时限”的同义表述；RRF 融合后 Reranker 排序，Evidence Selection 保留正式通知和课程手册两类来源。答案逐句带引用，若资料版本冲突则显式提示日期差异。

### 8.2 Note 演练

用户打开数据库课程讲义并请求“整理 MVCC 的实现逻辑”。系统先锁定该 Source，再以 Read View 段落为 Anchor，扩展到 undo log、可见性判断和示例所在连续 Window，生成可编辑的层级笔记。该模式不主动混入其他课程资料，除非用户扩大范围。

### 8.3 Wiki 演练

用户打开“事务隔离级别”页面。系统读取当前 Page Version，沿显式链接扩展到“MVCC”和“间隙锁”，但受两跳和节点预算约束。回答引用页面来源回链；检测到指向缺失页面的链接时，当前演示可直接 Auto-fix，生产化口径则是先生成待审批 Proposal。

## 9. 数据指标与业务意义

| 模式 | 第一指标 | 计算 | 面试解释 |
| --- | --- | --- | --- |
| QA | Recall@K | `Gold Evidence 被 TopK 召回的问题数 / 总问题数` | 检索是否有机会答对 |
| QA | Citation Correctness | `引用真正支持 Claim 的数量 / 引用总数` | 引用不是装饰 |
| QA | Unsupported Claim Rate | `无证据关键 Claim / 关键 Claim` | 回答可信度 |
| Note | Source Hit@K | `目标 Source 出现在 TopK / 样本数` | 第一阶段是否找对资料 |
| Note | Window Coverage | `Gold Span 被 Window 覆盖的字符或事实比例` | 连续深读是否完整 |
| Note | Compression Utility | 人工保留的有效笔记点 / 输入 Token | 花同样上下文得到多少有效整理 |
| Wiki | Link Precision | `正确关系边 / 生成或解析边` | 图是否可解释 |
| Wiki | Orphan Rate | `无有效入边和出边页面 / 页面数` | 知识组织是否断裂 |
| Wiki | Version Consistency | 同次回答中 Page、Link、Citation 是否来自兼容版本 | 是否出现混合版本 |

不能用 Recall@K 证明最终答案正确，也不能用用户点赞直接证明检索好。面试时要先指出指标属于 Retrieval、Generation 还是 Business Outcome。

## 10. 典型 Trade-off

### 为什么不用单个大模型直接读全部资料

小数据 Demo 的确更简单，但成本随资料量增长，权限和版本边界难证明，坏答案也无法定位到具体证据。RAG 增加索引和评测成本，换来可扩展范围、引用和分层诊断。

### 为什么不做一个万能 Retriever

统一接口可以复用，但统一目标函数会抹平场景差异。项目选择“合同统一、策略分离”，代价是每种模式都要维护 Gold Set 和参数。

### 为什么不是纯 GraphRAG

图适合关系扩展，不擅长覆盖所有文本相关问题；构图也有成本和误差。Wiki 使用受限图增强，QA 和 Note 仍以文本检索为主。

### 为什么 Evidence 要保存快照

重新查询更省存储，却无法复现旧答案。保存 Bundle Snapshot 增加空间和隐私治理成本，换来可回放、可审计和版本一致性。

## 11. 面试官连续追问

### Q1：三条链路如何避免代码重复

共用 Turn、AnswerRun、RetrievalPlan、EvidenceBundle、PromptSpec、权限 Guard、引用和事件协议；模式只实现 Plan、Retriever、Budgeter 和 Prompt 差异。抽象点是稳定生命周期，不是强行统一算法。

### Q2：RRF 的 `k` 和各通道权重怎样定

先用 Gold Set 网格搜索并观察 Recall、MRR、来源覆盖和延迟，不凭经验拍值。`k` 越大，头部排名差异越平滑；通道权重反映当前数据分布，但必须按 Query 类型分桶检查，防止总体平均掩盖术语类问题退化。

### Q3：向量库挂了怎么办

如果 BM25 投影可用，可以降级为词法召回并在 Run 中记录 Degradation Mode；高风险回答可以提高拒答阈值。不能静默返回与正常 Hybrid 相同的质量承诺。

### Q4：怎样防止跨 Workspace 召回

查询阶段必须带 Workspace 和 Snapshot 范围，返回后再由 EvidenceOwnershipGuard 做归属校验。双重检查是为了防投影配置错误。高风险路径在权限服务不可用时关闭访问，而不是放行。

### Q5：Prompt Injection 怎么处理

Source 内容按不可信数据处理，不获得系统指令优先级；工具能力与检索分开授权；引用内容进行定界，输出写回还要走独立审批或 Guard。RAG 不能天然解决 Prompt Injection。

### Q6：你们的效果比业界好吗

不能用内部小样本宣布行业领先。合理说法是采用业界常见的 Recall、NDCG、Citation Correctness 和 Faithfulness 口径，并用固定 Gold Set 做回归。只有在同数据集、同指标和同预算下才有横向可比性。

### Q7：Note 和 Wiki 为什么不直接共用 Source

Note 是面向用户的临时理解和编辑产物，允许围绕原资料重组；Wiki 是团队共享的稳定知识页面，需要版本、链接和治理。生命周期、并发冲突和发布责任不同，所以不能只靠一个 `type` 字段区分。

## 12. 推荐包装与退守口径

### 简历表达

> 设计 QA、Note、Wiki 三条场景化 RAG，在统一 RetrievalPlan、EvidenceBundle、PromptSpec 和 AnswerRun 合同上实现 Hybrid/RRF/Rerank、Source 内 Reading Window 与受限图扩展，并通过 Evidence Snapshot 支持引用追溯和策略回放。

### 被问上线规模

> 项目起点是学校资料服务原型，后续由我做面试向工程化重构。目前最完整的是代码、迁移、固定回放和本地演示证据，真实生产规模仍需要部署后的埋点验证。

### 被问尚未实现的目标态

> Note 的独立 Draft Revision、Wiki 的 Expected Head Version 与 Proposal 审批是我识别出的下一阶段边界。当前演示链路可用，但我不会把目标设计说成已经具备的多人生产协作能力。

## 13. 源码导航

| 主题 | 入口 |
| --- | --- |
| 统一策略合同 | `answer/strategy/AnswerModeStrategy`、`RetrievalPlan`、`EvidenceBundle`、`PromptSpec` |
| 调度与安全 | `RetrievalOrchestrator`、`EvidenceOwnershipGuard`、`EvidenceScopeGuard` |
| QA | `QaAnswerModeStrategy`、`QaHybridRetriever`、`QaRerankService`、`QaPassageEvidenceRetriever` |
| Note | `NoteAnswerModeStrategy`、`NoteRetrievalService`、`NoteReadingPlanner`、`NoteReadingRetriever` |
| Wiki | `WikiAnswerModeStrategy`、`WikiEvidenceRetriever`、`WikiGraphBudgeter`、`KnowledgeController` |
| 回放评测 | `retrieval/eval` 下 Shadow Export 和 Validator |
| 权威设计 | [QA 链路](../问答RAG链路设计.md)、[Note 链路](../Note链路设计.md)、[Wiki 链路](../Wiki模式设计.md) |

## 14. 面试前自检

1. 为什么 QA、Note、Wiki 的证据单位不同。
2. RetrievalPlan、EvidenceBundle 与 AnswerRun 分别解决什么问题。
3. RRF 为什么适合异构召回，它丢失了什么信息。
4. Rerank 后为什么还要集合级 Evidence Selection。
5. Reading Window 的边界如何推导，不是固定取几个 Chunk。
6. Wiki 图扩展怎样限制噪声和成本。
7. Source 更新后，旧 AnswerRun 为什么仍能解释当时答案。
8. Note 和 Wiki 哪些写入协议仍是目标态，如何诚实地讲亮点。
