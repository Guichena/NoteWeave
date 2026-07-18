# 阶段 5 Retriever/Hydrator 第一批验收

- 日期：2026-07-13
- 后端测试：150/150
- 状态：QA 与 Note hydrate N+1 已消除；Retriever 领域拆分和评测基线待继续施工

## 本批边界

1. 新增 `RetrievalHydrator`，负责按 source id 集合批量读取 provenance、Note 阅读窗口和 source 状态统计。
2. `RetrievalService` 保留原 QA/Note 召回、评分、窗口规划和 related-entry 算法，只把数据库 hydrate 改为批量快照。
3. 不调整 `qa-passage-v1`、`note-marginalia-v1`、EvidenceBundle、PromptSpec 或 citation 持久化契约。
4. 本批不引入 vector、fusion 或 rerank，也不改变 MySQL fallback 行为。

## QA 查询边界

- ES 返回命中后，一次收集全部 source ids 并调用 `hydrateSourceProvenance`；
- Hydrator 对 source ids 去重后执行单条 `workspace_id + id in (...)` 查询；
- 100 个 ES hit 的 provenance 查询次数固定为 1；
- ES 打分、`selectDiverseEvidence`、最终 6 条 evidence 和 Research Report provenance 展示保持原行为。

## Note 查询边界

- `openSourceWindowsForNote` 对本批 source 一次加载全部窗口，再在内存中复用原评分和 `selectReadPlanForSource`；
- `readEntriesMetadataForNote` 对本批 source 各执行一次 window hydrate 与 state/count hydrate；
- related-entry 一次加载 answered-turn 图、Note citation 图、READY source 快照和本批 anchor 的 co-citation 快照；
- 80 个 anchor 下 Hydrator 固定调用 2 次，related-entry JDBC 固定查询 4 次，均不随 source 数量增长；
- READY source 快照取最近 31 条，每个 anchor 在内存排除自身后截断 30 条，保持旧候选边界。

## 自动验证

- `RetrievalHydratorTest,RetrievalServiceHydrationTest`：5/5；
- QA/Note strategy、retriever 与 Phase3 契约定向回归：35/35；
- 完整 Backend：36 suites，150/150，0 failures，0 errors，0 skipped；
- `git diff --check`：通过，仅有工作区既有 CRLF 提示；
- H2 集成契约实际执行新批量 SQL，证明 SQL 语法与当前测试数据库兼容。

## 尚未完成

- `RetrievalService` 仍聚合 QA、Note 多类召回、排序和关系图职责，尚未完成按 Retriever/Ranker/Hydrator 的领域拆分；
- 固定 gold set、BM25 可回放 baseline 与质量/延迟指标尚未建立；
- vector、fusion、rerank 必须在 baseline 与评测门禁完成后再引入；
- EvidenceBundle 仍只在运行时完整存在，revision 当前仅保存 `evidence-bundle:<bundleId>` 引用。
