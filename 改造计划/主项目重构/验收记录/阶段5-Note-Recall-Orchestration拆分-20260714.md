# 阶段 5 Note Recall Orchestration 拆分验收

- 日期：2026-07-14
- 后端测试：159/159
- 状态：metadata scoring 与 Recall orchestration 已从 `NoteRetrievalService` 完整抽离

## 完成内容

- `NoteRecallRanker` 承接 coverage-aware metadata scoring、字段权重、term weight、coverage terms 和 metadata trace；
- 新增 `NoteRecallRepository`，统一读取最多 40 个 READY 候选 Source、Note citation groups 与 answered-turn citation groups；
- 新增 `NoteRecallRetriever`，编排 query terms、Journal、relation anchors/signals、Ranker、workspace recency fallback 和 `NoteRecallTrace`；
- `NoteEvidenceRetriever` 直接调用 `NoteRecallRetriever`，`NoteRetrievalService` 不再暴露或代理 Recall plan；
- `NoteRetrievalService` 只保留 Note metadata/window facade 与 related-entry preview，并通过共享 Repository 复用 citation group 查询；
- `NoteRelationGraph.overlapTerms` 开放复用，删除服务内重复 lexical overlap 实现；
- 删除服务内旧 metadata rank、term rank、relation signal orchestration、candidate SQL、citation group SQL、readiness/recall signal helpers 和 accumulator DTO；
- 保持候选上限 4、relation expansion 上限 3、verify 上限 6、anchor 上限 8、候选 Source 上限 40，以及原权重/fallback/reason 语义不变；
- `note-marginalia-v1` 未升级版本，因为本批仍是职责迁移，没有改变检索或回答算法。

## 验证

- Recall/Ranker/Relation/Note/Phase3 定向回归：34/34；
- `NoteRecallRankerTest` 精确固定 metadata score 183、matched fields、coverage terms、候选顺序和 admission reasons；
- `NoteRecallRetrieverTest` 验证 Repository → Journal/Relation → Ranker → Plan/Trace orchestration；
- 架构门禁禁止 `NoteRetrievalService -> NoteRecallRetriever` 和 `NoteRecallRetriever -> JdbcTemplate`；
- `mvn clean test`：159/159，0 failures，0 errors，0 skipped；
- 80-source related-entry preview 仍固定 4 次 JDBC 查询；
- 旧 metadata/Recall orchestration 残留搜索为 0。

## 后续

- 建立 BM25 deterministic baseline/gold set；
- 增加离线 retrieval replay 与 Recall@K/MRR/nDCG/citation 指标汇总；
- 再根据离线质量与 p95 延迟评估 vector、fusion 和 rerank；
- 算法行为变化时再提升 `retrieval_plan_version`。
