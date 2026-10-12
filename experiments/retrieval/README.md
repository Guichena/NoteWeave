# 检索评测（E2）

端到端评估 QA、多轮追问和 Wiki 三条检索链路：用固定语料建工作台，通过公开接口提问，读取每次回答的证据清单打分。

`backend/.../retrieval/eval/` 已有基于候选池的离线回放和质量门禁，适合在单元测试里比较排序策略；本目录评估的是线上完整链路（查询改写、索引、融合、重排、证据选择），两者互补。

## 语料

[`corpus/`](corpus/) 是三篇设计文档在 2026-10-12 的快照：`qa-rag.md`（问答 RAG 链路）、`memory.md`（Memory 机制）、`wiki.md`（Wiki 模式）。`dataset.json` 记录每个文件的 SHA-256（统一换行符后计算），运行脚本发现语料变化会拒绝运行。语料不随 `docs/` 更新，需要换语料时新增数据集版本。

## 问题集

[`dataset.json`](dataset.json)：`retrieval-eval-v1`。

| 问题集 | 模式 | 数量 | 说明 |
|---|---|---|---|
| `first_turn` | QA | 20 | 单轮问题，按 `question_type` 分为 EXACT（含专有名词）、NUMERIC（问参数值）、SEMANTIC（换了说法的问题） |
| `multi_turn` | QA | 12 | 两轮对话，只评估最后一轮。按可回答性分为 ANSWERABLE 6、PARTIAL 3、UNANSWERABLE 3 |
| `wiki_cross_page` | WIKI | 8 | 每题需要两篇资料编译出的页面才能回答 |

多轮集的追问都依赖上一轮的指代（那它、过期时间呢），用来检验 3A 查询改写。其中 `mt-memory-cache` 与 `mt-wiki-cache` 的追问几乎相同，只有上一轮的话题不同，检索结果应当分别来自不同文档。

## 相关性口径

每题列出若干要点（fact），每个要点给出一组或多组词项。一条证据的标题加摘录包含某组的全部词项，即命中该要点；比较时忽略空白和反引号，英文不区分大小写。

这种口径不依赖片段 ID，5A、5B 改变切片方式后评测集仍然可用。代价是摘录被截断时可能漏判，因此要点词项尽量取短而独特的原文。单轮和多轮集的全部要点在建集时已校验确实出现在对应语料中（见脚本测试）。Wiki 页面由模型编译，措辞可能改写，所以 Wiki 集的词项只取最不容易被改写的术语和数字。

## 指标

| 指标 | 口径 |
|---|---|
| Recall@10 | 前 10 条证据命中的要点数除以要点总数 |
| MRR | 第一条命中任一要点的证据排名的倒数，前 10 条都未命中记 0 |
| nDCG@10 | 二值增益，每条证据只为前面证据尚未命中的要点得分，避免重复片段抬高分数；理想排序是前 min(要点数, 10) 位各命中一个新要点 |
| 延迟 P50 / P95 | 从提交问题到回答进入终态的端到端耗时，含生成时间 |
| 证据充分性准确率 | 只对多轮集统计：ANSWERABLE 期望 `SUFFICIENT`，PARTIAL 期望 `PARTIAL`，UNANSWERABLE 期望 `INSUFFICIENT`。后端没有返回 `evidence_sufficiency` 或判定为 `NOT_RUN` 的题目不计入，单独报告已判定题数 |

UNANSWERABLE 题没有要点，不参与排序指标。各指标按题目取平均（宏平均），并按题型或可回答性分组报告。

`retrieval_rewrite`（3A）和 `evidence_sufficiency`（3·S7）字段按接口约定读取，C 线实现之前记录为空。

## 运行

```bash
python scripts/retrieval/run_retrieval_eval.py
```

默认只打印计划。真实运行需要启动完整环境：

```bash
NOTEWEAVE_EVAL_USERNAME=eval NOTEWEAVE_EVAL_EMAIL=eval@example.test NOTEWEAVE_EVAL_PASSWORD=... python scripts/retrieval/run_retrieval_eval.py --execute
```

脚本新建工作台，跑 Wiki 集时先打开 Wiki，再上传语料，等资料索引完成（跑 Wiki 集时还要等 Wiki 页面数稳定），然后为每题新建对话依次提问。`--set first_turn` 可以只跑部分问题集。原始记录写入 `runs/<问题集>/<case_key>.json`。

打分：

```bash
python scripts/retrieval/score_retrieval_eval.py
```

结果写入 `reports/retrieval-<时间>.json` 和同名 `.md`。

脚本测试：

```bash
python -m unittest discover -s scripts/retrieval/tests
```

## 对比实验

改造前后对比时，在同一份语料和数据集版本上各运行一次，并在报告旁记录：后端提交号、各检索开关（查询改写、Rerank、向量化）的取值、Embedding 与 Rerank 模型。只改一个开关的消融结果才能归因到该改动。
