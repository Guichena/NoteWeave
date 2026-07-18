# 阶段 5 Note Journal 拆分验收

- 日期：2026-07-13
- 状态：Journal 数据访问与 freshness/source signal 已独立；完整 Recall 尚未拆完

## 本批完成

- 新增 `NoteJournalRetriever`，承接最近 Journal 召回、fresh/stale/unavailable 判定与 source signal；
- `findNoteRecallPlan` 改为调用新组件，不改变候选配额、关系扩展和 verify admission；
- H2 Phase3 Note/Wiki 契约实际执行新 Journal SQL。

## 验证

- Note/Phase3 定向回归：30/30；
- Backend `mvn clean test`：通过；
- `note-marginalia-v1` 与回答/citation 行为未变。

## 后续

- 删除 `NoteRetrievalService` 内旧 Journal 私有/公开双实现；
- 拆出 Relation/Ranker 与最终 NoteRecallRetriever；
- 建 BM25 deterministic baseline/gold set。
