# 阶段 5 Note Recall Ranker 拆分验收

- 日期：2026-07-14
- 后端测试：156/156
- 状态：候选选择、关系扩展与验证准入已形成单一 Ranker 实现

## 完成内容

- 新增 `NoteRecallRanker`，承接候选配额、source type 多样性、top-score backfill、relation expansion 和 verify admission；
- `NoteRetrievalService` 改为把已评分候选交给 Ranker，并继续负责当前 metadata scoring 与 Recall orchestration；
- 删除服务内旧 `selectCandidateSources`、`takeByQuota`、`takeBySourceTypeQuota`、`buildVerifyBatch` 和 `ScoredCandidateSource`；
- 删除随旧选择逻辑遗留的 `normalizeSourceType` helper；
- 保持候选上限 4、关系扩展上限 3、验证批次上限 6，以及原 selection/verify reason 不变；
- `note-marginalia-v1` 未升级版本，因为本批只迁移职责，没有修改检索或回答算法。

## 验证

- Ranker/Relation/Note/Phase3 定向回归：31/31；
- `NoteRecallRankerTest` 固定候选顺序、配额原因、关系扩展和验证准入原因；
- `mvn clean test`：156/156，0 failures，0 errors，0 skipped；
- Surefire XML 汇总：41 reports，156 tests，0 failures，0 errors，0 skipped；
- 旧选择实现残留搜索为 0；
- `git diff --check`：通过，仅输出工作区既有 LF/CRLF 转换提示。

## 后续

- 把 metadata scoring 迁入 `NoteRecallRanker`；
- 抽出最终 `NoteRecallRetriever` orchestration；
- 建立 BM25 deterministic baseline/gold set；
- 再基于离线指标评估 vector/fusion/rerank。
