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

### LLM 评分

每个 Run 默认获取三次有效评分，按多数决定是否通过，维度分数取平均。每次输入问题、`must_cover`、报告正文、单元格引用列表和过程计数，输出五个维度的 0 到 1 分：

| 维度 | 评分要点 |
|---|---|
| factual_accuracy | 结论是否正确，错误或过时的事实按严重程度扣分 |
| citation_accuracy | 结论与引用原文是否一致 |
| completeness | `must_cover` 每一项是否被实质回答，并逐项返回是否覆盖 |
| source_quality | 是否优先使用官方文档、规范、论文等一手来源 |
| tool_efficiency | 结合检索次数和有效结论数判断是否有明显浪费 |

证据不足时如实说明缺口不扣分，编造结论严重扣分。

**通过**：取得指定次数的有效评分，其中多数判定通过，且每次判定同时检查事实准确和引用准确不低于 0.7。评分次数不完整时不能提前判定通过。

评分输出做结构校验（五个维度齐全、分数在 0 到 1 之间、`must_cover` 逐项返回），不合规时带着错误信息重试一次。评分模型未配置、调用失败或两次输出都不合规时记为 `NOT_RUN`，不计入通过率，报告里单独列出未评分数量。

## 运行

```bash
python scripts/deepresearch/run_quality_eval.py
```

默认只打印将运行的题目。真实运行需要启动完整环境并设置测试账号：

```bash
NOTEWEAVE_EVAL_USERNAME=eval NOTEWEAVE_EVAL_EMAIL=eval@example.test NOTEWEAVE_EVAL_PASSWORD=... python scripts/deepresearch/run_quality_eval.py --execute --runs-dir experiments/deep-research/quality-eval/runs-new-baseline
```

可以用 `--case <case_key>` 只跑部分题目。每个 Run 的详情和附属接口（plan、cells、counter-checks、replans、discovery-proposals）原样保存在本轮目录的 `<case_key>.json`。未指定 `--runs-dir` 时按 UTC 时间新建目录；已有同题记录时拒绝覆盖，避免混入旧轮次。

打分：

```bash
NOTEWEAVE_EVAL_JUDGE_BASE_URL=https://.../v1 NOTEWEAVE_EVAL_JUDGE_API_KEY=... NOTEWEAVE_EVAL_JUDGE_MODEL=... python scripts/deepresearch/score_quality_eval.py --runs-dir experiments/deep-research/quality-eval/runs-new-baseline
```

`--no-judge` 只计算结构化指标。评分前校验每份记录的题目和数据集版本，版本不一致时拒绝评分。结果写入 `reports/quality-<时间>.json` 和同名 `.md`。评分模型应与被测系统使用的模型不同，避免自评偏差；报告中记录所用模型。

脚本测试：

```bash
python -m unittest discover -s scripts/deepresearch/tests
```

## 纪律

- 指标只能由脚本从 `runs/` 的原始记录生成，不手工改写。
- 未通过和未评分的题目与通过的题目一起保留。
- 对外引用的数字要注明数据集版本、运行时间和评分模型。

## 完成度改造后的运行与指标

2026-10-21 主体代码实施记录见 `docs/升级改造/05-Research完成度改造实施记录-2026-10-21.md`，计划最新状态见第 18 节。本节说明已接入的工具；没有启动新一轮真实评测，也没有更新历史质量基线。

### 运行身份与可比较性

- `run_quality_eval.py` 在独立结果目录的 `metadata/manifest.json` 保存数据集、源码、允许公开的配置、角色模型路由和容器镜像身份，不保存密钥。
- 运行中发现源码、数据集、配置或镜像身份变化时标记 `INVALID`。无法取得运行镜像身份时保留 `UNAVAILABLE`，`runtime_comparable=false`；接回已有 Run 的采集也不作为同一冻结环境的新运行。
- 增加 `requirements` 原始接口记录。评分汇总区分研究需求覆盖、报告答案覆盖和原单元格指标，不能把三者视作同一完成率。
- 成本包含结算调用及失败/取消成文的未提交授权。任何用量未知时保留 `null`；存在未提交授权时总 token 也保持未知。授权尝试不能作为精确 Provider 请求数。
- 评分缓存按端点、模型和完整输入隔离。`judge_identity` 记录模型标识、端点摘要、研究角色模型集合和按模型标识判断的独立性；底层模型身份仍需人工确认。

### 不调用 Provider 的机制回放

在具备 Worker 依赖的 Python 环境中运行：

```bash
python scripts/deepresearch/replay_mechanisms.py --output-dir /tmp/noteweave-mechanism-python-new
```

输出目录必须是新目录。正式登记 28 个场景，Python 单独运行执行其中 18 个，Java 与跨语言场景保留 `NOT_RUN`。检查 `passed_count`、`scenario_count` 与 `all_scenarios_passed`，不能只看 `state=COMPLETED`。

具备 JDK、Python、Worker 依赖、已编译后端和后端依赖 classpath 的同一环境可执行全部场景：

```bash
python scripts/deepresearch/replay_mechanisms.py \
  --output-dir /tmp/noteweave-mechanism-full-new \
  --backend-classes backend/target/classes \
  --java-classpath "$REPLAY_JAVA_CLASSPATH"
```

`REPLAY_JAVA_CLASSPATH` 应由当前后端依赖构建生成。分镜像环境可先生成 Python 材料，再运行 `scripts/deepresearch/replay/run_java_replays.sh`，最后运行 `check_contract_v4.py`；所需路径变量在脚本开头列明。Java 回放必须读取最新主代码编译产物，不能在 Maven 编译期间执行。

回放使用固定输入、模拟模型和受控数据库；来源资格及部分租约入口使用预设通过状态。它验证确定的机制行为，不能替代真实鉴权、归档、并发和模型判断质量。

本地已经准备后端构建镜像和 Worker 镜像时，可构建同时包含 Python、JDK 与缓存依赖的独立验证镜像，使 28 个场景由同一次命令生成结果：

```bash
docker build --network none -t noteweave-research-validation:local scripts/deepresearch/replay
```

该 Dockerfile 只复用本地 `noteweave-backend-build-table-search` 和 `noteweave-research-worker-consumer`，不会替换服务镜像。运行时挂载当前仓库、已编译后端和新输出目录，并使用前述完整回放命令；镜像仍需与当前依赖版本匹配。

MySQL 迁移验证入口为 `scripts/deepresearch/replay/run_mysql_migration_replay.sh`。它仅接受隔离的空测试库，分别以 `FRESH` 和 `UPGRADE` 模式验证全量迁移及 V168→V179 升级。所需变量为 `NOTEWEAVE_REPLAY_ROOT`、`NOTEWEAVE_REPLAY_CLASSPATH_FILE`、`NOTEWEAVE_MIGRATION_JDBC_URL`、`NOTEWEAVE_MIGRATION_MODE` 和 `NOTEWEAVE_MIGRATION_REPORT`。固定使用测试容器本地 root 空密码且不开放网络端口；不能用于现有业务库。

### 沿用聊天模型进行开发评分

需要沿用聊天模型时，在评分命令中显式指定：

```bash
python scripts/deepresearch/score_quality_eval.py \
  --runs-dir /absolute/path/development-run-new \
  --judge-from-chat --judge-samples 3
```

此选项读取当前进程的 `NOTEWEAVE_LLM_ENDPOINT`、`NOTEWEAVE_LLM_API_KEY` 和 `NOTEWEAVE_LLM_MODEL`，仅在进程中设置评分配置，不写入 `.env`。端点可以是服务根地址或以 `/chat/completions` 结尾的完整地址。缺少任一配置时明确报错，不自动改用其他模型。

报告记录 `judge_identity.configuration_source=CHAT_MODEL`，并比较评分模型与运行清单中的研究角色模型。若使用相同模型，结果只作为开发自评，不能证明独立评审通过，也不能替代新留出集验收。留出集的独立模型检查继续保留。

隔离候选环境可用 `NOTEWEAVE_EVAL_RUNTIME_CONTAINERS` 指定逗号分隔的容器名，运行清单记录这些容器的镜像及启动身份；未指定时继续从当前 Compose 项目采集。候选环境需要提供 Redis 限流依赖，并把内部后端地址加入 Worker 的 `NO_PROXY` 与 `no_proxy`。

### 冻结新的留出集

实际留出集尚未创建冻结。准备与开发集格式相同的新数据集后，先固定题目和 `must_cover`，再执行：

```bash
python scripts/deepresearch/holdout_manifest.py \
  --dataset /absolute/path/holdout-dataset.json \
  --development-dataset experiments/deep-research/quality-eval/dataset.json \
  --manifest /absolute/path/holdout-manifest.json
```

至少 20 道新题，四种题型各至少 4 道，题目和编号唯一；不得与开发题完全相同。工具只检查规范化题目是否完全重复，语义近似与答案泄漏需人工核查。清单以独占方式创建，禁止覆盖；追加 `--verify` 可核对冻结摘要。

真实运行和评分同时指定相同的数据集与清单：

```bash
python scripts/deepresearch/run_quality_eval.py --execute \
  --dataset /absolute/path/holdout-dataset.json \
  --holdout-manifest /absolute/path/holdout-manifest.json \
  --runs-dir /absolute/path/holdout-run-new
python scripts/deepresearch/score_quality_eval.py \
  --dataset /absolute/path/holdout-dataset.json \
  --holdout-manifest /absolute/path/holdout-manifest.json \
  --runs-dir /absolute/path/holdout-run-new --judge-samples 3
```

账号及评分器变量按前文在本地配置，密钥不放入命令记录或报告。留出评分拒绝未配置评分器、研究角色同模型标识或少于三次评分的配置；未取得全部有效评分不能提前通过。若使用留出结果继续调优，这批题改为开发集，下轮验收需重新冻结新题。

### 联调前提

本地新增 `V169` 至 `V179` 尚未在真实环境应用，需要先验证 MySQL/Flyway 和存量数据，再部署兼容的 Java 与 Worker。新版 Run 使用状态版本 5 和 `completion_runtime_v1`，旧 Run 保留相应协议。

2026-10-21 后续已在隔离 MySQL 8.4 测试容器完成空库迁移和 V168→V179 升级验证，业务数据库未迁移。完整回归与联调进展见 `docs/升级改造/06-Research完成度集中验证记录-2026-10-21.md`，不能据此替代真实数据规模和部署验收。

`NOTEWEAVE_RESEARCH_REQUIREMENT_COVERAGE_V1` 默认关闭；影子计算、事实累计、需求驱动调度的配置与顺序见实施记录。报告片段自动清理也默认关闭。本批没有修改 `.env`、启用新路径或部署服务。

2026-10-21 后续已按用户选择，使用聊天模型 `deepseek-v4.1-flash` 完成两次真实定向运行及每轮三次自评。环境启动失败、首轮等待超时和接回采集均保留记录。两轮最终均为证据不足，规划结构也不同，不能据此更新历史 6/20 或宣称整体完成率提高。详细结果、日期校验修复与覆盖回执问题见集中验证记录第 6 节及计划第 20 节。最新 Markdown 报告分别展示模型评分、交付状态、需求覆盖和报告覆盖。
