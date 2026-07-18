# 阶段 5 Evidence 预算执行与 Snapshot 对齐验收

- 日期：2026-07-14
- 后端测试：193/193
- 计划版本：`qa-passage-v1`、`note-marginalia-v1`、`wiki-page-graph-v1`
- 状态：既有 RetrievalPlan 候选/字符预算已成为统一可执行契约，未更换三种模式的召回和排序算法

## 问题边界

阶段 5 已在 `RetrievalPlan` 中声明：

- 每个 channel step 的 `candidateLimit`；
- 全局 `maxEvidence`；
- 全局 `maxEvidenceCharacters`。

此前 `RetrievalOrchestrator` 只执行 `maxEvidence`，Note/Wiki retriever 也没有统一执行 step candidate limit，字符预算完全未生效。更重要的是，Note/Wiki 策略通过 bundle metadata 中的 snapshot 重放旧回答模板；即使 Orchestrator 淘汰 evidence，snapshot 仍可能包含被淘汰窗口或页面，导致回答正文和 Wiki 既有 citation 越过最终 EvidenceBundle。

## 完成内容

- 新增 `EvidenceBudgeter`，集中执行候选与 evidence 预算；
- Orchestrator 对每个 retriever 输出按 `candidateLimit` 截断，再合并所有 channel 候选；
- 全局选择保持原 `fusedScore` 降序，在 `maxEvidence` 和 `maxEvidenceCharacters` 内逐项接纳；不能装入剩余字符预算的 evidence 被跳过，后续更小 evidence 仍可继续接纳；
- 负数 character cost 按 0 处理，非正候选/数量/字符预算产生空选择，不允许越过计划上限；
- Note retriever 在编码 `NoteRetrievalSnapshot` 前应用 step candidate limit，evidence 与 snapshot window 顺序保持一致；
- Wiki retriever 在查询既有 citation 和编码 `WikiRetrievalSnapshot` 前应用 step candidate limit；
- Wiki evidence 的 `characterCost` 使用实际 excerpt 长度；有 summary 时不再按整页 content 长度计费；
- Note strategy 使用最终 bundle evidence IDs 过滤 snapshot windows；被预算淘汰的窗口不进入综合回答、深读窗口或摘录卡；
- Wiki strategy 使用最终 Knowledge Version IDs 过滤 snapshot contexts；被预算淘汰的页面不进入页面列表、综合结论、关系或来源回链；
- Wiki retriever 记录预算前 Knowledge evidence IDs。若全部入选，ChatService 保留旧的全局 citation 顺序和重复语义；若发生预算淘汰，只绑定入选 evidence 的 `citation_ids`。

## 算法兼容性

- QA 的 ES 优先、MySQL fallback、关键词评分和来源多样性未改；
- Note 的 metadata 权重、Journal、关系传播、verify admission 与阅读窗口规划未改；
- Wiki 的页面评分、页面关系、反链和来源召回未改；
- 当前三个 plan 的既有上限与各 retriever 原最大返回量一致，正常未超预算请求的可见回答和 citation 顺序保持原行为；
- 只有候选或 evidence 字符实际越过已声明计划预算时才发生截断，此时正文和 citation 与最终 EvidenceBundle 同步收敛。

## 验证

- `RetrievalOrchestratorTest`：4/4，覆盖全局数量预算、通道候选上限、字符预算装箱和缺失 channel 显式降级；
- `NoteEvidenceRetrieverTest`：2/2，固定 evidence/snapshot 同序与 candidate limit 同步；
- `WikiEvidenceRetrieverTest`：2/2，固定 Knowledge Version、citation snapshot 与 candidate limit 同步；
- `NoteAnswerModeStrategyTest`：2/2，证明 snapshot 中未入 bundle 的 window 不进入回答；
- `WikiAnswerModeStrategyTest`：3/3，证明 snapshot 中未入 bundle 的页面不进入回答；
- `ChatServiceWikiCitationSelectionTest`：2/2，覆盖无淘汰时旧 citation 顺序保留和淘汰时 selected-only citation；
- `ArchitectureBoundaryTest`：13/13；
- `mvnw.cmd -f backend/pom.xml clean test`：54 份 Surefire 报告，193/193，0 failures，0 errors，0 skipped。

## 后续边界

- 当前切片执行的是已声明的 lexical evidence 预算，不引入 vector、RRF 或 rerank；
- 真实 QA gold、shadow 与生产门禁阈值仍需等待可标注的真实 workspace/source 数据；
- 后续多 channel 检索接入时仍由同一 `EvidenceBudgeter` 执行全局预算，不允许各 retriever 自行绕过。
