# 阶段 5 Shadow Replay 与性能报告验收

- 日期：2026-07-14
- 后端测试：163/163
- Shadow schema：`retrieval-shadow-v1`
- 状态：外部排名对照、漂移指标与 p50/p95/workload 报告已建立

## 完成内容

- `RetrievalBenchmarkReplay.evaluateRankings` 允许任意外部/线上排名复用同一 gold set、scope、citation、拒答与质量指标；
- `BenchmarkReport` 将原 `baselineVersion` 泛化为 `rankingVersion`，可区分 deterministic baseline 与具体 shadow snapshot；
- 新增 `RetrievalShadowSnapshot`：snapshot version、case id、latency、candidate count 和 ranked evidence；
- 新增 `RetrievalShadowComparator`：输出 baseline/shadow 完整报告和 case 级 drift；
- drift 包括 topK Jaccard overlap、位置一致性、top1 变化、Recall/MRR/nDCG delta、citation delta、refusal correctness 变化和 scope violation；
- shadow 汇总包括 p50/p95 latency、候选总数和平均候选数；
- 新增 `RetrievalBenchmarkProfiler`：warmup + measured iterations，若任意轮完整报告不同则失败；
- profiler 输出 min/p50/p95/max/average latency，以及 case、总候选、scope 后候选、query/corpus token、topK、返回 evidence、relevant evidence 和 expected citation workload；
- 新增 `stage5-shadow-fixture-v1.json`，故意包含越权 top1、Wiki 漏召回和错误拒答，验证 comparator 能检测回退。

## 受控 Shadow Fixture 结果

- baseline macro Recall/MRR：1.0/1.0；
- shadow macro Recall/MRR：0.5/0.5；
- shadow citation precision/coverage：0.5/0.5；
- refusal accuracy：0；
- scope violation：1；
- mean topK overlap：1/3；mean position agreement：0.25；top1 changed：3；
- p50/p95：800/1200 微秒；候选总数 11，平均 2.75。

这些数字来自故意构造的受控 drift fixture，只用于验证检测器，不代表真实 Elasticsearch 性能或质量。

## 验证

- Shadow/Profiler/Replay 定向回归：4/4；
- `mvn clean test`：163/163，0 failures，0 errors，0 skipped；
- `RetrievalShadowComparatorTest` 固定质量 delta、scope/refusal 回退和 p50/p95；
- `RetrievalBenchmarkProfilerTest` 固定 workload cost，并校验 latency percentile 单调关系；
- 程序入口：`RetrievalShadowComparator.main(<gold-set.json>, <shadow.json>)`；
- profiler 入口：`RetrievalBenchmarkProfiler.main(<gold-set.json>, [warmup], [iterations])`。

## 后续

- 实现 Source/Passage/Knowledge snapshot 的可审计脱敏导出；
- 通过 `ChunkSearchPort` 捕获真实 ES shadow ranking、score、latency 和 candidate count；
- 为 macro Recall/MRR/nDCG、citation、scope、refusal 和 p95 设置版本化阈值；
- 阈值稳定后再进行 vector/RRF/rerank shadow 对照。
