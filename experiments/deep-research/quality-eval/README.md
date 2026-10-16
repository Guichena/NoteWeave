# Deep Research 质量评测（E1）

`research-eval-v1`（上级目录 `datasets/`）检查终态、引用链、故障分类等健壮性断言；本目录评估结论本身的质量，两者互补。

## 评测集

[`dataset.json`](dataset.json)：`research-quality-v1`，20 道真实研究问题，2026-10-12 冻结。

| 题型 | 数量 | 说明 |
|---|---|---|
| COMPARISON | 8 | 两个对象按多个维度对比，附 `expected_entities` 用于检查规划是否覆盖了题目中的对象 |
| SURVEY | 4 | 一类产品或一组变更的整体情况 |
| FACT | 4 | 需要精确事实（版本号、PEP 或 JEP 编号、命令）的问题 |
| CONFLICT | 4 | 可信来源结论不一致，要求保留双方证据 |

每题有 3 条 `must_cover` 要点，供评分模型判断完整性。题目来自 `research-eval-v1` 的，在 `research_eval_v1_case` 中注明，方便对照两套结果。

版本规则与 `research-eval-v1` 相同：已被引用的版本不允许原地修改问题或要点，需要变更时新增版本号。

## 指标

### 脚本统计的结构化指标

| 指标 | 口径 |
|---|---|
| 有效单元格完成率 | 有效单元格数除以规划单元格数，汇总时按微平均计算（全部 Run 的有效单元格之和除以规划单元格之和）。有效单元格指状态为 `VERIFIED`、至少有一条可回溯的结论，并且高风险列有不少于 2 个独立来源。 |
| 每个有效单元格的检索次数 | Run 的检索查询总数除以有效单元格数，衡量检索效率 |
| 实体覆盖率 | 只对 COMPARISON 题统计：`expected_entities` 中出现在规划行里的比例 |
| 反证未执行数 | `counter-checks` 中判定为 `NOT_RUN` 的条数 |

单元格数据来自 `GET /research-runs/{runId}/cells`（1D 实现）。接口不可用的 Run 记入 `runs_without_cells`，不算作 0。

### 单次 LLM 评分

每个 Run 调用一次评分模型，输入问题、`must_cover`、报告正文、单元格引用列表和过程计数，输出五个维度的 0 到 1 分：

| 维度 | 评分要点 |
|---|---|
| factual_accuracy | 结论是否正确，错误或过时的事实按严重程度扣分 |
| citation_accuracy | 结论与引用原文是否一致 |
| completeness | `must_cover` 每一项是否被实质回答，并逐项返回是否覆盖 |
| source_quality | 是否优先使用官方文档、规范、论文等一手来源 |
| tool_efficiency | 结合检索次数和有效结论数判断是否有明显浪费 |

证据不足时如实说明缺口不扣分，编造结论严重扣分。

**通过**：评分模型判定通过，并且事实准确和引用准确都不低于 0.7。

评分输出做结构校验（五个维度齐全、分数在 0 到 1 之间、`must_cover` 逐项返回），不合规时带着错误信息重试一次。评分模型未配置、调用失败或两次输出都不合规时记为 `NOT_RUN`，不计入通过率，报告里单独列出未评分数量。

## 运行

```bash
python scripts/deepresearch/run_quality_eval.py
```

默认只打印将运行的题目。真实运行需要启动完整环境并设置测试账号：

```bash
NOTEWEAVE_EVAL_USERNAME=eval NOTEWEAVE_EVAL_EMAIL=eval@example.test NOTEWEAVE_EVAL_PASSWORD=... python scripts/deepresearch/run_quality_eval.py --execute
```

可以用 `--case <case_key>` 只跑部分题目。每个 Run 的详情和附属接口（plan、cells、counter-checks、replans、discovery-proposals）原样保存在 `runs/<case_key>.json`。

打分：

```bash
NOTEWEAVE_EVAL_JUDGE_BASE_URL=https://.../v1 NOTEWEAVE_EVAL_JUDGE_API_KEY=... NOTEWEAVE_EVAL_JUDGE_MODEL=... python scripts/deepresearch/score_quality_eval.py
```

`--no-judge` 只计算结构化指标。结果写入 `reports/quality-<时间>.json` 和同名 `.md`。评分模型应与被测系统使用的模型不同，避免自评偏差；报告中记录所用模型。

脚本测试：

```bash
python -m unittest discover -s scripts/deepresearch/tests
```

## 纪律

- 指标只能由脚本从 `runs/` 的原始记录生成，不手工改写。
- 未通过和未评分的题目与通过的题目一起保留。
- 对外引用的数字要注明数据集版本、运行时间和评分模型。
