# NoteWeave 2026 Benchmark Evaluation Plan

目标：用 2026 年新 benchmark 的小比例抽样，验证 NoteWeave 在团队知识协作和个人研究沉淀两条主链路上的真实能力，并形成可展示、可复跑、可营销的评测资产。

当前原则：

- 先不跑正式评测，先把数据、代码、指标、对比、营销和轮次计划建好。
- 团队和个人分开测，结果分开解释，不混成一个总分。
- 大数据集只抽小比例，优先保留失败/困难样本。
- 先做本地小样本闭环，再扩大到真实 benchmark 抽样。
- 所有 benchmark 适配都放在 `bench-eval/`，稳定后再接入后端 `rageval`。

## 1. 评测对象

### 团队线

验证 NoteWeave 的团队知识工作台能力：

- 文档上传、解析、chunk、索引。
- Hybrid RAG：BM25、向量召回、Wiki 召回、RRF 融合。
- Citation 和 RetrievalTrace。
- 多轮 workspace chat 的上下文延续。
- 无答案、冲突信息、缺失信息场景。
- Wiki 沉淀后是否改善团队检索。

重点 benchmark：

- `enterprise_rag_bench`
- `mtrag_un`
- `parsebench`
- `agentic_rag_tracer`

### 个人线

验证 NoteWeave 的个人研究链路：

- Source 导入和编译。
- ArticleCard / ConceptCard / SynthesisCard 组织质量。
- Methodology 驱动 Artifact 生成。
- 引用支持、来源推荐和研究结论完整度。
- 个人资料与团队资料边界。

重点 benchmark：

- `autoresearchbench`
- `citerag`
- `parsebench`
- `agentic_rag_tracer`

## 2. 当前目录职责

```text
bench-eval/
  benchmarks/   benchmark 注册表
  configs/      指标、抽样、基线、轮次配置
  data/         小样例与标准化数据
  docs/         调研、指标、对比、营销、轮次文档
  downloads/    真实 benchmark 原始下载
  results/      后续评测输出
  schemas/      标准化 case schema
  scripts/      适配、导出、评分、manifest 生成代码
```

## 3. 数据建设计划

### Step A: 先建种子数据

目的：不用下载大数据，就能让 schema、导出格式和评分字段稳定下来。

已规划：

- `data/team_seed_cases.jsonl`
- `data/personal_seed_cases.jsonl`
- `samples/enterprise_questions.sample.jsonl`

### Step B: 下载真实 benchmark

下载位置统一为：

```text
bench-eval/downloads/{suite_id}/
```

下载顺序：

1. EnterpriseRAG-Bench questions 和 gold docs。
2. MTRAG-UN / MTRAGEval conversation tasks。
3. ParseBench 小页集。
4. AutoResearchBench task split。
5. CiteRAG 小比例任务。
6. AgenticRAGTracer multi-hop 样本。

### Step C: 标准化成统一 JSONL

统一输出：

```text
bench-eval/data/{suite_id}.{lane}.normalized.jsonl
```

每行遵循：

```text
schemas/normalized_case.schema.json
```

### Step D: 导出到 NoteWeave

团队线先导出到现有后端格式：

```text
rag_eval_case:
  name
  queryText
  expectedAnswer
  expectedSourceJson
  tagsJson
  enabled
```

个人线先导出为执行 manifest，不直接写后端表，因为当前后端没有 `personal_eval_case`：

```text
personal project -> sources -> compile -> artifact -> distill -> judge
```

## 4. 代码建设计划

`scripts/noteweave_bench.py` 负责：

- 读取 benchmark registry 并生成抽样计划。
- 标准化 EnterpriseRAG 风格 question JSONL。
- 导出团队 RAG eval case。
- 导出个人研究/Artifact 执行 manifest。
- 校验 normalized case。
- 根据轮次配置生成 run manifest。
- 对系统输出做基础离线评分。
- 汇总分组指标。

后续如果需要接后端，再补：

- `scripts/noteweave_api_import.py`
- `scripts/noteweave_api_run.py`
- `scripts/noteweave_api_collect.py`

当前阶段不跑这些。

## 5. 交付物

评测准备完成的最低标准：

- benchmark 注册表覆盖团队和个人线。
- 每个 benchmark 有抽样比例、最大 case 数、分层字段。
- 指标体系覆盖检索、生成、引用、解析、个人研究和运维。
- 对比对象写清：内部 ablation、外部 benchmark baseline、市场竞品。
- 营销口径写清：能说什么，不能说什么。
- 评测轮次写清：R0 到 R5 的目的、数据、输出和停止条件。
- 脚本命令存在，但正式评测可以等你确认再跑。
