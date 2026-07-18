# 阶段 5 Deterministic BM25 评测基线验收

- 日期：2026-07-14
- 后端测试：161/161
- 数据集版本：`stage5-fixture-20260714`
- 基线版本：`deterministic-bm25-v1`
- 状态：PR 级 deterministic gold set、离线 replay 和确定性指标门禁已建立

## 完成内容

- 新增 `RetrievalGoldSet` 版本化 schema：mode、query、allowed source scope、候选 evidence、相关 evidence、期望 citation、拒答条件和 topK；
- 新增 `DeterministicBm25Baseline`：固定 `k1=1.2`、`b=0.75`，字段权重对齐当前 ES 查询的 `content^3/title^2/source_type`；
- tokenizer 使用 NFKC、小写归一、字母数字 token，以及汉字整段/单字/bigram，排序同分时按 evidence id 固定；
- 新增 `RetrievalBenchmarkReplay` 文件/程序入口，输出 case 级排序与 aggregate/by-mode 指标；
- 指标包括 Recall@K、MRR、nDCG@K、citation precision、citation coverage、scope violation count 和 refusal accuracy；
- schema 校验拒绝未知 mode、非正 topK、重复/空 evidence id、空 source id、缺失 relevant evidence、越权 relevant evidence、错误 citation 归属和拒答/相关证据冲突；
- 新增 `stage5-retrieval-gold-v1.json`，覆盖 QA、Note、Wiki 和无证据拒答；QA/Note fixture 均包含文本高度相关但不在 allowed scope 的候选；
- `RetrievalBenchmarkReplayTest` 连续回放两次并比较完整报告相等，同时固定三模式排序与全部指标；
- 本批只增加离线评测基础设施，没有改变线上检索算法，因此不提升 `retrieval_plan_version`。

## 当前 fixture 结果

- retrieval cases：3；refusal cases：1；
- macro Recall@K：1.0；
- macro MRR：1.0；
- macro nDCG@K：1.0；
- macro citation precision/coverage：1.0/1.0；
- refusal accuracy：1.0；
- scope violations：0。

这些结果仅证明当前小型 deterministic fixture 可回放且满足预期，不代表真实数据集、线上 ES 或外部 benchmark 的质量结论。离线实现复用字段权重，但不复刻 ES analyzer、fuzziness 和索引统计。

## 验证

- 评测/三模式/架构定向回归：27/27；
- `mvn clean test`：161/161，0 failures，0 errors，0 skipped；
- PR 回放命令：`.\mvnw.cmd -f backend\pom.xml "-Dtest=RetrievalBenchmarkReplayTest" test`；
- 程序入口：`RetrievalBenchmarkReplay.main(<gold-set.json>)`。

## 后续

- 从真实但脱敏的 Source/Passage/Knowledge snapshot 生成扩展 gold set；
- 增加线上 ES shadow query 与离线结果对照，记录 analyzer/fuzziness 差异；
- 增加 p50/p95 latency、候选规模和成本报告；
- 只有 baseline 指标与延迟预算稳定后，才引入 vector recall、RRF fusion 和 rerank。
