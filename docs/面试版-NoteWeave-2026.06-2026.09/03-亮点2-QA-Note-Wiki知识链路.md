# 亮点 2：任务感知的 QA / Note / Wiki 知识链路

## 1. 简历亮点对应的面试回答

我没有把 QA、Note 和 Wiki 都当成“向量 TopK 后生成一段文本”。三种任务对“相关”的定义不同：QA 需要少量、可引用、可拒答的证据；Note 需要围绕一份资料连续阅读；Wiki 需要维护可编辑的长期页面和来源回链。因此三条链路共享 Source、Snapshot、Workspace ACL 和 Evidence 契约，但拆开检索单元、上下文组织和写回语义。

## 2. 统一检索合同

```text
RetrievalRequest
├─ workspace_id / actor_id / source_scope
├─ mode: QA | NOTE | WIKI
├─ query / conversation_cutoff
├─ snapshot_policy
└─ budget: recall_k, rerank_k, evidence_tokens

RetrievalResult
├─ candidates[]
├─ selected_evidence[]
├─ source_snapshot_refs[]
├─ retrieval_trace
└─ scope_violation / degraded_reason
```

命中 ES 不等于有权引用。最终使用前，按 `workspace_id + source_id + snapshot_id` 回 MySQL 校验归属、删除状态和当前可见版本。Scope Violation 是硬门槛，不能依赖 Prompt 让模型自己“注意权限”。

## 3. QA 链路：回答问题，不拼资料

### 3.1 请求理解

先对多轮问题做结构化 Query Rewrite，补齐代词、时间和资料范围，但保留原问题作为审计输入。Rewrite 只生成检索约束，不直接改写最终回答语义。低置信度时并行尝试原查询和改写查询，若两者冲突则追问用户。

### 3.2 多路召回与融合

1. Scope Filter 先限制 Workspace、资料类型和版本。
2. BM25 召回专名、课程号、章节号、数字和精确短语。
3. Vector 召回同义表达和自然语言描述。
4. 使用 Weighted RRF 融合不同召回排名，避免不同分数空间直接相加。
5. Cross-Encoder Rerank 对较小候选集做精排，控制延迟和成本。
6. Evidence Selection 做集合级选择，去掉重复片段，保证结论的关键字段由证据覆盖。

### 3.3 生成与拒答

回答器只能使用 Evidence Bundle，输出 Claim、Citation 和 Uncertainty。若证据之间冲突、引用覆盖不足或来源已失效，返回澄清、拒答或“当前资料无法证明”，不凭模型常识补全。

## 4. Note 链路：连续阅读与可审阅写回

Note 的目标不是回答一个问题，而是从资料中形成一份可审阅笔记。第一阶段做 Source Recall，先判断哪几份资料相关；第二阶段找到章节或段落 Anchor，向前后扩展连续 `Reading Window`，尽量保留定义、例子和上下文；第三阶段生成 Draft Revision，保留原文定位、摘要和待确认项；用户确认后才写回 Note 版本。

这样做的原因是，直接对 Chunk TopK 生成笔记会把相邻段落打散，模型拿到结论却看不到定义和限定条件。窗口大小由段落长度、章节边界和 Token Budget 共同决定，超预算优先缩减远处窗口，不静默删除当前 Anchor 附近的原文。

## 5. Wiki 链路：版本化知识投影

Wiki 页面不是一张覆盖更新的正文表。每次编辑生成不可变 `PageVersion`，`PageHead` 指向当前版本，关系表维护链接和反链，Source Backlink 记录每个段落或结论的来源。页面正文可以人工编辑，但来源和版本不能被覆盖删除。

页面读取优先走当前 Head 和受限关系扩展，不自动回退成普通 QA。这样能区分“页面还没有维护”和“资料里没有答案”，也能在资料版本变化时标记页面需要复核。关系图只用于导航和局部扩展，不宣称已经实现全量 GraphRAG。

## 6. 三条链路为什么不共用一个 Prompt

| 维度 | QA | Note | Wiki |
| --- | --- | --- | --- |
| 检索单元 | 片段和证据集合 | 资料、Anchor、连续窗口 | 页面、关系、来源回链 |
| 输出 | 带引用回答或拒答 | 可审阅草稿 | 版本化页面 |
| 成功条件 | Claim 被 Evidence 支撑 | 覆盖主题且保留定位 | 页面版本可追溯、关系可重建 |
| 失败处理 | 澄清、拒答、降级 | 缩小窗口、标记缺口 | 保留旧 Head、进入待复核 |

统一实现会减少代码，但会把不同任务的上下文和写回语义混在一起，导致 QA 的短证据挤满 Note，或 Wiki 的旧页面被当成实时事实。当前选择是共享底层契约和投影能力，使用不同 Adapter。

## 7. 关键 Trade-off 与参数思路

- **BM25 + Vector，而不是只用向量：** 专名、编号和数字对词法更敏感；向量补充同义表达。代价是两套索引和融合逻辑。
- **RRF，而不是直接相加分数：** 两路分数不可比，RRF 只依赖名次，调参更稳。代价是不能直接表达分数的绝对置信度。
- **先精排再 Evidence Selection：** Rerank 负责单片段相关性，Selection 负责集合覆盖和去重。代价是多一个步骤，但能减少“TopK 都在重复同一段”的问题。
- **资料级召回再做连续阅读：** Note 先找资料再展开窗口，避免全库拼接。代价是窗口扩展逻辑复杂，需要控制 Token。
- **PageVersion 而不是原地更新：** 支持审计、回滚和来源回链。代价是读取要解析 Head，关系和版本清理也更复杂。

## 8. 面试官高频追问

**问：为什么 Rerank 后还要 Evidence Selection？**  
Rerank 只回答“这一段像不像问题的答案”，不能保证候选集合覆盖结论所需的多个字段。Selection 负责去重、覆盖和冲突保留，最后才形成可引用 Evidence Bundle。

**问：为什么不直接上专用向量数据库？**  
当前资料有较多课程号、专名和权限过滤，ES 的 BM25、过滤和向量能力已经能覆盖需求。等向量规模、写入吞吐或 P95 成为主要瓶颈，再拆独立向量库，避免先引入双写和额外一致性问题。

**问：检索结果相关但越权怎么办？**  
相关性和授权是两个维度。召回可以返回候选，但引用前必须按当前 Snapshot 回库校验归属和删除状态；校验失败直接丢弃候选并记录 Scope Violation。

