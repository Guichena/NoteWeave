# 阶段 5 Note Relation 拆分验收

- 日期：2026-07-14
- 后端测试：155/155
- 状态：Journal 双实现已清理，Relation 纯算法已独立

## 完成内容

- 删除 `NoteRetrievalService` 内旧 Journal SQL、freshness helpers 与私有 DTO；
- 新增 `NoteRelationGraph`，承接共现信号、tag/title/co-citation 权重、图构建和 restart 传播；
- Recall relation score 与 related-entry preview 均使用同一图实现；
- 服务内旧 coOccurrence/build/propagate 算法已删除。

## 验证

- Relation/Note/Phase3 定向回归：31/31；
- `mvn clean test`：155/155，0 failures，0 errors，0 skipped；
- `git diff --check`：通过。

## 后续

- 拆出 NoteRecallRanker 与最终 NoteRecallRetriever；
- 建 BM25 deterministic baseline/gold set；
- 再评估 vector/fusion/rerank。
