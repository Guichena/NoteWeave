# 执行台账

## 最小简历闭环批次（2026-09-19）

### MIN-4 八题验收资产：定义完成，真实运行待验证

- `[当前实现]` 已建立 `minimal-eval/manifest.json` 与 schema，固定 2 正常、2 证据缺失、1 来源冲突、1 Worker 中断恢复、1 引文不支持 Claim、1 Provider 故障。
- `[已测-模拟]` `check_minimal_eval.validate()` 以公开 manifest 为验证缝：合法 8 题通过；无真实 Run ID 却标记 PASSED 会失败；题型数量漂移会失败。
- `[当前实现]` 已补 `DEMO.md` 和 `CLAIM-EVIDENCE-MAP.md`，把正常 Run、局部 Replan、Worker 恢复和引用点击收敛为 3～5 分钟演示。
- `[当前实现]` 新增 `run_minimal_eval.py`：默认 dry-run，只有显式 `--execute` 才调用真实 Provider；可串行启动 2 正常 + 2 缺失 + 1 冲突题，轮询终态并保存 Run Detail / Evidence Manifest / Collection 原始证据。Worker 中断、quote 篡改和 Provider 故障题会 fail-closed，不允许普通运行冒充故障注入。
- `[已测-模拟]` MIN-4 脚本测试 **6/6 passed**：覆盖 manifest 门禁、默认 5 题选择、未知 case 拒绝与故障题不得伪注入。
- `[生产待验证]` 8 题当前全部 `PENDING_RUNTIME`；未伪造 Run ID、效果数字或真实链路结论。

### MIN-1 MA4 局部 Replan：第一条垂直切片 DONE

- `[当前实现]` 新增 `ResearchAgentLocalReplanRecorder`。failed-wave 决策为 `COUNTERFACTUAL` 且存在真实 `research_matrix_plan` 时，在协调器同一事务内追加 `LOCAL_REPAIR` plan，生成独立 plan digest，只提升目标 Cell 的 `plan_revision`，并写入 `research_agent_replan_audit`。
- `[当前实现]` 审计行包含 old/new plan digest、受影响 Cell、`MARGINAL_GAIN_EXHAUSTED`、`COUNTERFACTUAL_RESEARCH` 与复用 Task reservation 词表的预算差异。
- `[已测-模拟]` 新增两 Cell 主路径测试：`method` 已 VERIFIED、`evidence` 未解决且 wave 1 失败时，wave 2 只包含 `entity-1:evidence`；VERIFIED Cell 的状态和 `repair_count` 不变；Replan audit 为 `1 → 2`。
- `[已测-模拟]` 红灯为缺少 `research_agent_replan_audit` 行；实现后定向测试 1/1 通过，`ResearchAgentTaskCoordinatorServiceTest` 全类 33/33 通过。
- `[当前实现]` failed-wave 的持久化终止原因已归一为 `NO_RESULTS / FETCH_FAILED / NO_VALID_QUOTE / EVIDENCE_CONFLICT / PROVIDER_FAILED`；分类同时进入 LOCAL_REPAIR plan JSON，并映射为审计 deviation type。本轮样本 `NO_SUPPORTED_CANDIDATE → NO_VALID_QUOTE → MARGINAL_GAIN_EXHAUSTED`。
- `[已测-模拟]` 分类切片经历独立红灯后转绿；协调器 + Replan Audit 联合回归 **41/41 passed**。
- `[生产待验证]` 尚需用真实 Run 取得一条 LOCAL_REPAIR + audit 样本。MIN-1 暂不标记整体 DONE。

### MIN-2 Task 级恢复：代码证据复核（2026-09-19）

- `[已测-模拟]` Worker `test_ma4_task_level_recovery.py` **7/7 passed**：整页归档复用跳过 fetch/read、混合 URL 只补抓缺失页、归档身份保持、HTTP inventory 协议和 usage 调用计数均通过。
- `[已测-模拟]` Backend `ResearchAgentTaskRecoveryEvidenceTest` **8/8 passed**：Task 过期重领、lease/fencing 更新、旧 completion 拒绝且 canonical Cell/Task/Reservation 不变、新 completion 提交和预算守恒均通过。
- `[生产待验证]` 仍缺实际杀死 Worker 进程、等待 lease reaper、由新 Execution 接管的运行日志。2026-09-19 21:43 检查时 Docker Engine named pipe 不存在，未把模拟测试冒充真实故障注入；不阻塞继续实施 MIN-3。

### MIN-3 引用审计与三类报告：代码闭环（2026-09-19）

- `[当前实现]` 新增 `ResearchAgentHonestReportService`，由 coordinator 的业务终态分支直接消费 `CompletionDecision`；`COMPLETED_WITH_LIMITATIONS` 和 `INSUFFICIENT_EVIDENCE` 不再只写终态列，而会生成包含 outcome、verified findings、unresolved Cells、limitations 和 reason codes 的诚实报告。
- `[当前实现]` 正式 Claim 的报告引用链包含 `Cell → accepted Candidate → Evidence key → exact quote → snapshot key → URL`；只遍历 CompletionGate 的 `promotableClaims`，未验证 Cell 不进入 findings。
- `[当前实现]` 全 VERIFIED 的既有 finalizer 增加同标准的 `Citation audit` 附录；Synthesis 或默认表格报告都逐 Evidence 输出 exact quote、snapshot 和 URL。
- `[当前实现]` `renderAndPersist` 在正文为空时直接返回，不创建 `research_agent_report_artifact`。
- `[已测-模拟]` insufficient-evidence coordinator E2E 经红灯后转绿；limitations 引用全链测试通过；verified Citation Audit 经红灯后转绿。相关四类测试联合回归 **22/22 passed**。
- `[生产待验证]` Docker 恢复后需各取得 verified / limitations / insufficient-evidence 的真实报告样本并抽查引用链。

> 权威计划：[`docs/DeepResearch-简历能力落地执行计划.md`](../../docs/DeepResearch-简历能力落地执行计划.md)
> 工作模式：主对话负责规划与审查；每个 DR 任务由独立执行体在自己的上下文中完成，
> 只把「改动清单 + 验证命令 + 退出条件证据」回传给主对话。
> 更新时间：2026-09-18

## 状态图例

`DONE` 已有机器可读证据并通过主对话独立复跑 / `REWORK` 退出条件已满足但有必改项待回传 /
`VERIFYING` 执行体已回传、主对话独立复跑尚未取得（如受并发构建阻挡）/ `IN_PROGRESS` 正在执行 /
`DELEGATED` 已派发等待回传 / `BLOCKED` 依赖未满足 / `PENDING` 未开始

## M0 基线冻结

| ID | 任务 | 状态 | 执行体 | 证据产物 |
| --- | --- | --- | --- | --- |
| DR-000 | 固化 Provider 配置、开关快照和版本信息 | DONE | main | `manifest/experiment-manifest.json`、`scripts/deepresearch/build_experiment_manifest.py` |
| DR-001 | 保存三个代表性失败 Run 的状态与数据库导出 | DONE | main | `baseline/runs/<runId>/`、`baseline/README.md`、`scripts/deepresearch/export_baseline_run.py` |
| DR-002 | 建立固定评测集及类别标签 | DONE | main | `datasets/research-eval-v1.json`、`datasets/README.md`、`scripts/deepresearch/check_eval_dataset.py` |

M0 退出条件与证据：

- Manifest 可重建当前环境 → 重复执行 `build_experiment_manifest.py` 得到相同 `manifest_digest`。
- 可离线复盘 terminal barrier → `baseline/README.md` 第 2 节已用 `usage_json` 区分出
  「LLM 未配置」与「模型读了窗口但零卡」两类零卡，并指出当前数据无法区分
  非法 JSON / 未知 window / 错误列 / 非精确引文。
- 数据集含正常、缺失、冲突、故障四类 → 24 例，校验脚本通过。

## M1 抽取诊断闭环（COMPLETED，2026-09-18）

M1 退出条件复核：DR-101/102/103/104 全部 `DONE`，四项均以「主对话独立复跑」而非执行体自述为准。
验收证据：Worker 全量 `459 passed`、Java 全量 surefire `910 run / 0 failures`、跨语言 golden digest 一致、
6 个脱敏 fixture 离线重放 6/6 PASS。

M1 收口时仍待补的验证（不阻塞 M2 开工）：

| 项 | 状态 | 说明 |
| --- | --- | --- |
| `ResearchAgentMySqlLockMatrixIT`（M4 锁矩阵） | 未跑 | 受 `MA4G_LOCK_MATRIX_ENABLED=true` 门禁，由 `scripts/ma4g/docker-compose.lockmatrix.yml` 独立起 Maven 镜像并供给 MySQL；不在 `mvn test` 默认门禁内。命令与前置条件已记录，作为 M4 收口前的显式门禁执行 |
| `research_agent_execution.extraction_diagnostics_json` 的真实 MySQL 落库样本 | **已采（2026-09-19）** | 使用者已授权外部计费。真实 Run `4c51a20f-…` 落库 2 条诊断，见「G-2 / G-3 真实 Run 采集」。此前不仅「未采」，且因 **Worker 容器跑旧镜像**根本采不到（提交的是 v1 信封）——已重建 Worker 镜像后取得 |

### DR-000 产物缺陷修正 + 环境合规发现（2026-09-18，主对话）

主对话复核时发现 **DR-000 的 Manifest 存在失真**：原实现只解析 `docker-compose.yml` 的模板值 + 本机
shell 环境，并**硬编码声明** `advanced_features_disabled: true`，因此它记录的不是「运行期生效配置」，
且把一个未经验证的断言当成事实。已修正为 v2 结构：声明值进 `manifest_digest`，生效值单独记录并带
各自 digest，并新增「高级特性关闭」的**测量**。

修正后测得的真实情况：

```
advanced_feature_violations = [
  NOTEWEAVE_RESEARCH_AGENT_EVIDENCE_AUDIT_ENABLED,
  NOTEWEAVE_RESEARCH_AGENT_SYNTHESIS_ENABLED,
  NOTEWEAVE_RESEARCH_ENABLE_URL_READER,
  NOTEWEAVE_RESEARCH_EVIDENCE_AUDIT_V1,
  NOTEWEAVE_RESEARCH_WORKER_SYNTHESIS_V1
]
acceptance_compliant = False
```

即**当前运行环境不满足第 2.2 节的「高级特性关闭」**。影响：

- 在此环境下产生的任何 Run **不能**作为验收环境证据；DR-601 固定评测必须在关闭这些开关后重跑。
- 另外测得 `NOTEWEAVE_RESEARCH_LLM_MODEL=glm-5.3-flash`、
  `NOTEWEAVE_RESEARCH_LLM_BASE_URL=https://api.zetatechs.com/v1`、`JINA_API_KEY` 为 `SET`，
  而 `NOTEWEAVE_RESEARCH_SERPER_API_KEY` 为空——`serper,wikipedia` 链实际会回落到 Wikipedia。
  `datasets/README.md` 与 `baseline/README.md` 中「Serper 真实搜索」的表述需按生效配置修订。

`manifest_digest` 确定性已验证：连续两次执行均为 `sha256:b149e339…`。

| ID | 任务 | 状态 | 执行体 | 依赖 | 证据产物 |
| --- | --- | --- | --- | --- | --- |
| DR-101 | 定义 `ExtractionResult` 与拒绝原因枚举，所有抽取出口返回结构化结果 | DONE | main + worker-extraction | M0 | `app/extraction_result.py`、`app/extractor.py` |
| DR-102 | 持久化 accepted/rejected Card、模型和收据摘要 | DONE | worker-persistence | DR-101 | `V105` 迁移 + 信封 v2-extraction 形态（Python/Java 双侧） |
| DR-103 | 非法 JSON、未知 window、错误列、非精确引文契约测试 | DONE | worker-contract-tests | DR-101 | `tests/test_extraction_contract.py`（24 用例） |
| DR-104 | 脱敏 replay fixture 与离线重放 | DONE | worker-replay | DR-101 | `app/extraction_replay.py` + `replay-fixtures/`（6 个 fixture） |

DR-101 主对话复核结论（2026-09-18）：

- `extract_evidence_cards_detailed()` 已实现，拒绝判定顺序与裁决一致：`NON_OBJECT_CARD` →
  `MISSING_WINDOW_ID` → `UNKNOWN_WINDOW` → `WRONG_COLUMN` → `EMPTY_QUOTE` → `NON_EXACT_QUOTE` →
  `EMPTY_CLAIM` → `UNSUPPORTED_RELATION` → `DUPLICATE_EVIDENCE`。
- 终止原因映射完整：`NO_READ_WINDOWS` / `LLM_UNAVAILABLE` / `INVALID_JSON` /
  `MISSING_EVIDENCE_CARDS` / `ALL_CARDS_REJECTED` / `ACCEPTED_CARDS`。
- `extract_evidence_cards()` 保持旧签名并返回 `accepted_cards` 的 list，调用方未受影响。
- 主对话独立复跑 `tests/test_llm_extract_verify.py tests/test_p0_new_behavior.py tests/test_runner.py`：**69 passed**。

DR-103 主对话复核结论（2026-09-18）：

- 新增 `workers/research-worker/tests/test_extraction_contract.py`，24 用例，覆盖 9 类卡片级拒绝、
  5 类终止原因（含空响应/非 dict/非 list 变体）、旧入口与新入口的字段级等价、
  diagnostics 有界性与「模型原文不落盘」、以及原因词表不回退（第 7.1 节六项字面一致）。
- 主对话独立复跑 `tests/test_extraction_contract.py tests/test_llm_extract_verify.py`：**57 passed**。
- 执行体报告的窗口作用域问题未写成失败用例（属未定义行为），由主对话立案为 DR-105。

DR-104 主对话复核结论（2026-09-18）：

- 新增 `workers/research-worker/app/extraction_replay.py`（`load_replay_fixture` / `replay_extraction_fixture` /
  `verify_replay_fixture` / `replay_directory` + CLI）与 6 个脱敏 fixture。
- 主对话独立复跑 `tests/test_extraction_replay.py`：**20 passed**（执行体随后追加词表同源断言，增至 21 例，主对话复跑 `test_extraction_replay.py + test_extraction_contract.py` = **45 passed**）；CLI `--fixtures` / `--json` 输出 **6/6 PASS**，退出码 0。
- 主对话独立扫描 6 个 fixture（`https?://`、`Bearer `、`api[_-]?key`、`user@domain.tld`）：**全部 0 命中**，
  每个 fixture 顶层 11 个字段、1 个 window、`desensitized=true`。
- `load_replay_fixture` 对词表越界（终止原因 / 拒绝原因）与 `desensitized != true` 直接拒绝，
  避免「fixture 随便写、重放永远通过」。
- 重放命令已补进 [`README.md`](./README.md)（执行体按「只新增文件」约束未改主对话的文档，由主对话补齐）。

DR-102 主对话复核结论（2026-09-18）：

- `V105__add_research_agent_extraction_diagnostics.sql`：expand-only、可空、不回填历史行，与
  「不给历史数据猜原因」一致。
- 完成信封形成三形态：v1（无新字段）/ v2-ROLE_RESULT（带 `role_result`）/
  v2-extraction（带必填 `extraction_diagnostics`，`role_result` 禁止）。Python 与 Java 的根字段集合
  各自按 `schema_version` + `termination_reason` 选择，且都做 exact-field 校验。
- 硬门禁双侧一致：`Σrejection_counts == rejected_count`、`accepted_count==0 ⇒ ≠ACCEPTED_CARDS`、
  `accepted_count>=1 ⇒ ==ACCEPTED_CARDS`、samples ≤8 且 ≤rejected_count、词表键校验、
  diagnostics canonical ≤8192B。
- **主对话独立复跑**：Python `pytest tests/` = **459 passed**；
  Java `ResearchAgentCompletionCanonicalizerTest` + `ResearchAgentCompletionServiceTest` = **66 passed**；
  Java 全量 surefire = **910 run, 0 failures, 0 errors, 11 skipped, BUILD SUCCESS**。
- **跨语言 digest 一致性**：`sha256:3307022e…aa44` 由两侧各自计算并断言同一常量。
  主对话逐字段比对了 Python `_v2_extraction_payload()` 与 Java `extractionEnvelope(diagnostics())`
  的向量（task/lease/fence/execution_key/snapshot/trace/budget/telemetry/diagnostics 全部一致），
  因此 digest 相等是真实的字节级证明，而非复制常量。

DR-102 已接受的偏差（主对话裁决）：

| 偏差 | 裁决 | 理由 |
| --- | --- | --- |
| `provider_receipt.model`、sample `window_id`/`column_key`/`detail` 未强制非空 | 接受 | `LLM_UNAVAILABLE` 时 `model=""`、`NON_OBJECT_CARD`/`MISSING_WINDOW_ID` 时无 window/column。强制非空会让基线中的「LLM 未配置」路径无法出具合法信封，直接违背 DR-102 目标 |
| `ResearchAgentCompletionCommitter.validateAuthority` 的 `candidateEnvelope` 由「仅 v1」放宽为「v1 或 v2 且非 ROLE_RESULT」 | 接受（必需） | DEEP_CELL 已统一发 v2-extraction，不放宽会导致生产每次完成都被判 `RESEARCH_AGENT_COMPLETION_INVALID`。放宽后方向是**收紧**（v2 必须携带 diagnostics），且 `roleResultTask` 路径未变，各角色无法提交他人形态 |

DR-101 已落地的共享接口：

- `ExtractionFailureReason`：响应级（`LLM_UNAVAILABLE`、`INVALID_JSON`、`MISSING_EVIDENCE_CARDS`）
  与卡片级（`NON_OBJECT_CARD`、`MISSING_WINDOW_ID`、`UNKNOWN_WINDOW`、`WRONG_COLUMN`、
  `EMPTY_QUOTE`、`NON_EXACT_QUOTE`、`EMPTY_CLAIM`、`UNSUPPORTED_RELATION`、`DUPLICATE_EVIDENCE`）。
- `ExtractionResult.diagnostics()` 输出有界 JSON，可直接进入 completion trace。
- `REQUIRED_REJECTION_REASONS` 固定第 7.1 节点名的六种原因，供契约测试断言。

## M2 到 M6

| 里程碑 | 状态 | 说明 |
| --- | --- | --- |
| M2 Cell 修复与可审计 Replan | IN_PROGRESS | DR-201..204；DR-201 已派发，DR-202 依赖 DR-201 的决策接口 |
| M3 Verifier 与诚实终态 | PENDING | DR-301..305 |
| M4 Checkpoint 与真实恢复 | PENDING（DR-401 设计已 DONE） | Schema 见 [`docs/research/Research-Checkpoint-Schema设计.md`](../../docs/research/Research-Checkpoint-Schema设计.md) |
| M5 引用审计与报告 | PENDING | DR-501..503 |
| M6 固定评测与简历证据 | PENDING | DR-601..604 |

## M4 / DR-401 设计审查结论（2026-09-18）

证据产物：[`docs/research/Research-Checkpoint-Schema设计.md`](../../docs/research/Research-Checkpoint-Schema设计.md)。
退出条件「Schema 能表达恢复所需最小状态」已满足：新增 `research_execution_attempt`、
`research_external_operation_receipt`、`research_run_budget_ledger` 三张表，
`research_agent_checkpoint` 扩展 barrier / watermark / fence / digest 列，并给出 INV-1..10 不变量与
EQ-01..12 等价性测试清单。

主对话审查改动：

- 迁移编号顺延：`V105`（`research_agent_execution.extraction_diagnostics_json`）已被 DR-102 占用，
  M4 迁移调整为 `V106`..`V109`。
- 补充 DR-102 与 `research_external_operation_receipt` 的分工，禁止重复存储同一份抽取诊断。
- §1.2「`research_run.run_version / attempt_no / fencing_token` 全仓无读写」由主对话复核确认，
  并写入可复现命令。结论：三列为死列，本设计首次赋予其语义。

## 新立案（执行中发现的、超出原计划范围的问题）

| ID | 问题 | 严重度 | 来源 | 状态 |
| --- | --- | --- | --- | --- |
| DR-105 | 抽取窗口作用域收敛：`_extract_evidence_cards_detailed_with_llm` 把 `windows_by_id` 由**全部** `read_windows` 构建，但只把 `focused_windows`（受 `evidence_horizon_window_budget` 限制，默认 5）发给模型。当 `len(read_windows) > 5` 时，模型若猜中一个未展示给它的真实 `window_id`，该卡不会落到 `UNKNOWN_WINDOW` | 低（引文仍须与未展示窗口的原文逐字匹配，实际可利用性极低；但契约语义应为「只能引用本次展示过的窗口」） | DR-103 执行体观察，主对话确认 | M1 已收口，可派发 |
| DR-106 | 诊断词表跨语言漂移无自动守卫：Java 在 `ResearchAgentCompletionCanonicalizer` 中**硬编码** `EXTRACTION_TERMINATION_REASONS` / `EXTRACTION_FAILURE_REASONS`，Python 侧词表来自 `ExtractionFailureReason` 枚举。若 Python 新增原因码而 Java 未同步，运行时会被判 `extraction_diagnostics ... is invalid`，而现有 golden 向量只覆盖已存在的取值 | 中低（不写脏数据，但是契约漂移，会以「生产信封被拒」的形式暴露） | DR-102 主对话复核发现 | DONE（含执行违规，见 M2 复核） |
| DR-107 | 「Provider 未配置/不可用」的终态归类：`LLM_UNAVAILABLE` 目前只存在于 Cell 级策略分支，未进入业务终态判定。若诚实终态把「LLM 未配置」归到证据不足，就会重演基线 `31d1d44f` 的坑（基础设施故障被计成业务结果） | 高（直接影响 DR-305 的正确性，且决定 M6 评测集 FAULT 类的判定口径） | DR-202 执行体上报 + 主对话裁决 D-9 | 已立案，约束 DR-204 与 DR-305 |
| DR-108 | 恢复规则未收敛：`evaluate_loop_decision` 中的 `EXTRACT_AGAIN` / `READ_MORE`（`evidence_card_count<=0`）与 `CellRecoveryPolicy` 的 `RETRY_EXTRACTION` / `TARGETED_SEARCH` 语义重叠，§7.2 要求「恢复规则不得散落在搜索、抽取和 Runner 中」 | 中（当前两层不冲突，但 Locality 未达成；长期会再次分叉） | DR-202 执行体上报 + 主对话裁决 D-11 | DONE（见「DR-108 收敛」一节） |
| DR-109 | 「无信息重计划保护」是死代码：`plan_digest()` 计入 `plan_revision`（每轮 `+1`），导致 `REPEATED_PLAN_DIGEST` 在 loop 中永不触发，当前仅有 `ATTEMPT_LIMIT_REACHED` 兜底 | 中（该项在 DR-201 单测通过，但在真实链路中不生效，属「看起来有保护其实没有」） | DR-202 执行体上报 + 主对话核实 | DONE（见 M3 复核） |
| DR-111 | `has_conflict` 分支仍由 loop 自行判定 `COUNTERFACTUAL_RECHECK`，与策略的 `COUNTEREVIDENCE_SEARCH` 语义重叠；§7.2 要求恢复规则不得散落 | 中低（两条路径当前渲染同一族，属重叠非矛盾；且**该分支位于非生产链路**） | DR-108 执行体上报 + 主对话裁决 D-20 / D-28 / D-35 | **BLOCKED：需范围决策**——见 D-35 |
| DR-115 | 测试基建地雷：`ResearchRunCommandService.createRun` 用**绑定 VARCHAR** 写 `research_run.agent_feature_flags_json`（json 列），H2(MySQL 模式) 会存成 JSON **字符串标量**，真实 MySQL 会正常解析为对象 → 任何「打开某全局 flag + 用 `createRun` 造 Run」的测试都会在回读快照时失败（`Research Agent feature flag snapshot is invalid`）。此前不可见是因为全局 flag 默认 false 使 `enabledForRun` 短路、从不回读该列 | 低（仅测试面；真实 MySQL 不触发） | M4-A 执行体发现并自行绕过 | 已立案；**已知绕过方式**：测试内用 `json_object(...)` 重写 `agent_feature_flags_json`（见 `ResearchAgentCheckpointHydrationCorrectnessTest` 的写法） |
| DR-110 | Replan 审计**没有真实触发点**：`research_agent_replan_audit` 的表、服务、查询与不变量都已就绪（DR-203），但没有任何调用方；因此「每次 Replan 可完整审计」目前无法产出真实 DB 记录 | 中高（简历主张「Plan / Replan …… 留下审计记录」需要真实事件，DR-203 的 DB 记录样本也拿不到） | DR-203 执行体诚实上报 + 主对话确认 | 已接线并通过复核；**REWORK**：:263 静默降级待改 fail-fast（见「DR-110 复核」） |

## 关键路径

`M1 -> M2 -> M3 -> M5 -> M6`；M4 的 Schema 设计可在 M2/M3 期间并行。
任何里程碑未满足退出条件时，不把后续里程碑标为完成。

## M2 批次派发（2026-09-18）

| ID | 任务 | 状态 | 执行体 | 依赖 | 退出条件 |
| --- | --- | --- | --- | --- | --- |
| DR-201 | 实现 `CellRecoveryPolicy` 决策接口（纯函数、无 I/O、隐藏阈值） | DONE | worker-cell-recovery | M1 | `每个缺证据 Cell 获得显式动作` + 单测 |
| DR-202 | 抽取重试 / 重读 / 定向搜索 / 冻结 unresolved 接入主路径 | BLOCKED | — | DR-201 | 集成测试轨迹 |
| DR-203 | 持久化 Replan 原因、影响 Cell、旧新 Plan digest 与预算差异 | BLOCKED | — | DR-201 | DB 记录样本 |
| DR-204 | 循环与预算停止保护 | BLOCKED | — | DR-202 | 故障注入结果 |
| DR-105 | 抽取窗口作用域收敛（新立案） | DONE | worker-window-scope | M1 | 证伪修复前行为的单测 |
| DR-106 | 诊断词表跨语言漂移守卫（新立案） | DONE（含执行违规） | worker-vocab-guard | DR-102 | 守卫脚本 + 漂移自证 |

M2 批次的两条边界裁决（避免并行执行体互相破坏）：

- **DR-105 必须复用 `UNKNOWN_WINDOW`，不得新增原因码**：Java 侧词表是硬编码副本，新增取值会让生产信封被拒；该漂移守卫正是 DR-106 的范围，两者不得互相越界。
- **DR-201 只交付纯决策模块与单测，不接入 Runner**：接入主路径是 DR-202 的工作，避免同一批次内并发改 `loop_runtime.py`。

### M2 主对话复核结论（2026-09-18）

三个执行体均**未回传报告即退出**，因此全部结论以主对话直接审查产物 + 独立复跑为准。

**DR-201 通过**。`app/cell_recovery_policy.py` + `tests/test_cell_recovery_policy.py`（**41 例**）覆盖了全部 7 条
决策性质与 6 个动作，并额外覆盖主导原因并列时的确定性 tie-break、`NO_READ_WINDOWS` 降级链、
`LLM_UNAVAILABLE` 不得伪装成预算耗尽、以及 `detail` 有界且不含模型原文。
边界遵守已验证：`cell_recovery_policy` 在 `app/` 内**零引用**，未接入 Runner（DR-202 的范围）。
阈值集中在 `CellRecoveryThresholds`，默认值与既有 `loop_runtime._freeze_overspent_cells` 对齐。

**DR-105 通过**。`extractor.py` 的 `windows_by_id` 已由全部 `read_windows` 收窄为 `focused_windows`
（`extractor.py:181`），复用 `UNKNOWN_WINDOW` 未新增原因码。`tests/test_extraction_window_scope.py`
（8 例）直接调用真实 `_select_focused_windows` 而非复制排序逻辑，含「引用未展示的真实窗口必须被拒」的
证伪用例、确定性用例、以及 `len<=budget` 时行为不变的等价性用例。

**DR-106 通过但记执行违规**。守卫脚本与守卫测试本身合格（零依赖源码解析、双向比较、内存变异负例、
真实枚举反向校验脚本解析结果）；CI 中已加入守卫步骤。但过程中有两处必须记录：

| 问题 | 事实 | 处理 |
| --- | --- | --- |
| **改动受版本控制的文件做「漂移自证」且未及时恢复** | 执行体把 `ResearchAgentCompletionCanonicalizer.java` 的 `WRONG_COLUMN` 改成 `WRONG_COLUMN_TYPO` 并删除 `EMPTY_QUOTE`，主对话在该窗口内复跑得到 `2 errors / BUILD FAILURE` | 最终状态已恢复（守卫脚本 12/12 对齐、Java 66 passed）。已新增规则：**漂移自证不得改动受版本控制的文件**，必须用内存变异或文件副本 |
| **陈旧编译产物** | 恢复源码后 Maven 增量编译跳过该类，Java 仍报 `rejection_counts key is not a known reason`；删除 `ResearchAgentCompletionCanonicalizer*.class` 后 66 passed | 记录为陷阱：检查「源码正确但测试仍红」时先怀疑陈旧 class，再怀疑逻辑 |

另：`.github/workflows/ci.yml` 的 `pip install` 行新增了 `kafka-python>=2.0.2,<4`。
该文件在本次会话开始前已处于修改状态，**无法归属**到 DR-106；若属本次新增则属轻微越界，
但方向正确（Worker 测试导入 kafka 客户端需要该依赖）。

M2 未取得的材料（不阻塞验收，但登记为缺口）：两个执行体未回传「收敛了 `loop_runtime.py` 中哪些既有规则」
与「与建议映射表的偏离项及理由」，主对话只完成了产物级复核。

### M2b 批次派发（2026-09-18）

| ID | 任务 | 状态 | 执行体 | 依赖 | 退出条件 |
| --- | --- | --- | --- | --- | --- |
| DR-202 | 抽取重试 / 重读 / 定向搜索 / 冻结 unresolved 接入主路径 | DONE | worker-cell-wiring | DR-201 | 不重做已完成 Cell、动作受预算限制 + 集成测试轨迹 |
| DR-203 | 持久化 Replan 原因、影响 Cell、旧新 Plan digest 与预算差异 | DONE（契约层，触发点未接） | worker-replan-audit | DR-201 | `V106` + 服务 + 8 例测试 |
| DR-204 | 循环与预算停止保护 | DONE | worker-loop-guard（DR-107 要求已并入） | DR-202 | `app/loop_stop_guard.py` + 14 例故障注入 |

本批次新增裁决：

| 编号 | 裁决 | 理由 |
| --- | --- | --- |
| D-5 | `V106` 归 DR-203（Replan 审计表），M4 迁移顺延为 `V107..V110` | DR-203 需要新表；M4 设计文档已同步修订，避免再次撞号 |
| D-6 | DR-202 与 DR-203 **不得并行修改同一文件**：DR-202 只改 Worker，DR-203 只改 Java + 迁移 | 两者都会触碰 Plan/预算语义，但落点不同语言，按语言切分可完全避免冲突 |
| D-7 | Replan 审计**禁止保存 Chain-of-Thought**，只存决策摘要 | 架构文档明确要求；长自由文本还会让审计表变成不可控的注入面 |

另：本批次起所有成员改用锚定执行体 `deep-research-task`（`.codebuddy/agents/deep-research-task.md`，
`model` 显式指定，不再落到客户端默认）。该定义同时固化了「漂移自证不得改动受版本控制文件」
等本次会话新增的执行纪律。

### DR-202 复核与六项分歧裁决（2026-09-18，主对话）

主对话独立复跑 `pytest tests/` = **574 passed**（基线 555 + 新增 19），既有用例零改动。
产物：`app/cell_recovery_runtime.py`、`tests/test_cell_recovery_wiring.py`，
修改 `app/loop_runtime.py`、`app/research_tools.py`；未触碰 Java 与迁移。

接入点：`run_research_loop` 在 `execute_round` 之后、`evaluate_loop_decision` 之前调用
`CellRecoveryRuntime.apply`；预算检查先于调用（全有或全无扣减，扣减失败即转 STOP，toolchain 不被调用）。

主对话为裁决逐条核实的事实（不是采信自述）：

| 核实项 | 结论 |
| --- | --- |
| `recovery_mode` 是否为跨语言白名单 | **否**。Java 仅在 `ResearchRunQueryService`、`ResearchReportReadModelAssembler`、`ResearchReadModelMapper`、`ResearchCheckpointProcessAssembler` 中以 `stringValue(...)` 透传，无枚举校验 → 扩展 worker 内取值安全 |
| `terminal_disposition` 是否为 loop 既有词表 | **是**。`CONTINUE`/`GUARDED_COMPLETE`/`HUMAN_HANDOFF`/`ABANDON`/`VERIFIED_COMPLETE` 均已在 `loop_runtime.py` 既有；Java 读模型已在消费该字段与 `handoff_required` |
| 「无信息重计划保护」是否真的生效 | **否，是死代码**。`plan_digest()`（`cell_recovery_runtime.py:519`）计入 `plan_revision`，而每轮 `+1`，摘要恒不同 → `REPEATED_PLAN_DIGEST` 永不触发 |
| `from_plan` 换算是否集中 | **是**，`CellRecoveryBudgetLedger.from_plan`（`:112`）为唯一换算点 |

| 编号 | 裁决 | 理由 |
| --- | --- | --- |
| D-8 | A：采纳「先接受 Run→Cell 有损换算」，并要求补换算表固定测试；真账本替换登记为 DR-405 收口项 | 等 M4 会阻塞 M2/M3 关键路径；有固定测试才能证明替换前后等价 |
| D-9 | B：`LLM_UNAVAILABLE` **不**作为 Cell 级信号注入 loop；立案 DR-107 要求诚实终态把它映射为基础设施失败类 | 第 5 节：基础设施失败不得伪装成证据不足。基线 `31d1d44f` 正是「LLM 未配置被当成零卡」 |
| D-10 | C：接受新增 worker 内 `REREAD_WINDOW` / `TARGETED_SEARCH` recovery_mode 取值 | 已核实 Java 侧无白名单，不构成跨语言契约 |
| D-11 | D：保留两层（D1），收敛工作立案 DR-108 | DR-202 退出条件已满足；重写约 10 条被锁断言属行为升级，不应混入本任务 |
| D-12 | E：立案 DR-109，摘要改用不含 `plan_revision` 的内容摘要 | 永不触发的保护等于伪装，第 14 节禁止 |
| D-13 | F：词表可复用，但 `handoff_required` 必须恒等于 `terminal_disposition == "HUMAN_HANDOFF"`（当前无条件 `True`，与 `loop_runtime.py:479/553` 语义不一致）；DR-305 的 `RunCompletionGate` 是业务终态唯一权威，loop 的 `terminal_disposition` 仅作建议值 | 两处终态权威会冲突；语义不一致会让 UI 误报需要人工介入 |

DR-202 处于 `REWORK`：仅剩两项必改（`handoff_required` 修正 + `from_plan` 换算表固定测试），
回传后即可转 `DONE`。

## M3 前置侦察（2026-09-18，主对话）

派发 M3 之前先核实 M3 表格里的假设，避免派发一个已经完成或方向错误的任务。

### 事实 1：Worker 的 `CellVerifier` 已接入**旧 loop 路径**，但增量主链路绕过它

- `research_tools.py:70` 构造 `CellVerifier(llm_client=...)`，`execute_round` 在 `:206-210` 调用
  `verify_cells`，四态 verdict 会写回 `last_verifier_decision` / cell `status`（`:793-796`）。
- 因此计划 M3 的 DR-301 表述「把 Cell Verifier 接入实际 Runner 主路径」在**旧路径上已成立**。

### 事实 2（高severity）：增量主链路的 Candidate 提升**没有经过 Verifier / Qualification 门禁**

`ResearchAgentCompletionCommitter.commit()` 的顺序是：

```java
Map<String,TrustedEvidenceSource> trustedEvidence = validateEvidenceAuthority(...);   // :126
QualificationBatch evidenceQualification = evidenceQualificationService.evaluate(...); // :128-129
MergeOutcome plannedOutcome = planMergeOutcome(task, envelope, cells, trustedEvidence); // :134  ← 不传 qualification
```

而 `ResearchAgentCompletionMergePlanner` 的接受判据是纯词面等价：

```java
boolean supported = candidate.evidenceKeys().stream().map(evidenceByKey::get).allMatch(evidence ->
        evidence != null && "SUPPORTS".equals(evidence.relationType())
                && candidate.candidateValue().equals(evidence.claimText()));   // :42-44
```

`applyVerdictsAndCas`（`:751`）的入参只有 `candidates / cells / evidenceByKey`，同样不含 qualification。
`cell_verdict` / `last_verifier_decision` / `CELL_VERIFIER` 在 `ResearchAgentCompletionWriteRepository` 中**零命中**。

**结论**：在验收环境所使用的 INCREMENTAL_V1 主链路上，「引文存在但不支持 Claim」的 Candidate
**仍可能被提升为 VERIFIED cell value**。这与计划 §2.1「Verifier 位于真实主链路，而不是旁路或仅有数据结构」
以及 §9 验收矩阵「Verifier | 引文存在但不支持 Claim | Candidate 不提升」**直接冲突**。

### 事实 3：门禁**已存在**，但默认是「只记录不拦截」，且粒度是整次 completion 而非单个 Candidate

`ResearchEvidenceQualificationService.evaluate()`（`:53-92`）已经实现了真实的三层校验：

- `validateEvidence()`（`:169-177`）逐条检查：source authority、`QUOTE_EMPTY`、`CLAIM_EMPTY`、
  **调用 `ResearchTypedClaimValidator`** 并在 `CONTRADICTED` / `UNKNOWN` 时收集 reason codes、
  relation 取值合法性；
- 每个 Candidate×Evidence 绑定得到 `finalStatus`：`QUALIFIED_*` / `REJECTED` + `reasonCodes`；
- 门禁开关是**两个条件的与**：`strict`（属性 `noteweave.research.strict-research-evidence-validation`，**默认 false**）
  **且** 该 Run 的快照 flag `STRICT_EVIDENCE`；
- 触发时的行为是**抛异常** `RESEARCH_EVIDENCE_QUALIFICATION_FAILED`（整次 completion 失败），
  批次模式标记为 `GATING` 或 `SHADOW`。

因此真实缺口不是「没有校验」，而是：

| 缺口 | 说明 |
| --- | --- |
| **默认不拦截** | 验收环境若未打开 `strict` 且 Run 快照未开 `STRICT_EVIDENCE`，校验结果只是记录（`SHADOW`）；`planMergeOutcome` 依然只按词面等价决定提升 |
| **粒度是整次 completion** | 一旦 `GATING`，任一 REJECTED 就让**整次**完成失败。但计划要求的是「Candidate 必经验证才能提升」（按 Candidate），失败一次 completion 会把同批已合格候选一起丢掉，与 §5「部分结果诚实完成」冲突 |
| **不进入提升判据** | `planMergeOutcome`（`:134`）与 `applyVerdictsAndCas`（`:751`）都不消费 qualification，所以 `SHADOW` 下「引文存在但不支持 Claim」的 Candidate 仍会被提升为 VERIFIED |

### 对 DR-301 的重新界定（已定稿）

原表述「把 Cell Verifier 接入实际 Runner 主路径」按字面理解会产生两个已完成的任务
（旧 loop 路径已接入；三层校验已实现）。**真实工作是把已有的校验结果变成按 Candidate 的提升门禁**：

1. `planMergeOutcome` 必须消费 qualification 结果：某 Candidate 的任一 Evidence 绑定为 `REJECTED` 时，
   该 Candidate 必须被 `REJECTED` 并带上 qualification 的 reason codes；
2. **该按 Candidate 的否决必须是硬门禁（不依赖 `strict` / Run flag）**。依据：§14 风险表
   「为修复终态而降低证据门槛 → Verifier 与 Citation Audit 使用硬门禁」；
   只否决 Candidate、不发明任何结论，因此可以无条件生效；
3. 保留现有 `GATING` 的整次失败行为不变（它是更严格的模式，不删除）；
4. 把 `GATING`/`SHADOW` 模式与每个 Candidate 的 qualification 结果写入 receipt，保证可审计；
5. 计划把 DR-301 标为 `WK`（Worker 侧），但真实落点在 Java；**按实际情况重新分配语言边界**，
   不要为了对齐表格而把门禁放到 Worker。

DR-302 / DR-303 与本次改动共用一个门禁落点，因此三者同批次设计、分批实施。

## M3 批次

| ID | 任务 | 状态 | 依赖 | 退出条件 |
| --- | --- | --- | --- | --- |
| DR-301 | Candidate 提升硬门禁（**已按侦察重新界定**，Java） | DONE | M2 | Candidate 必经验证才能提升 |
| DR-302 | 类型 / 精确引文 / 来源关系校验的负例报告 | DONE | DR-301 | 15 码矩阵 + **23 例**（含 DR-112 固化 3 例）经主对话独占复跑全绿 |
| DR-303 | 局部与全局冲突判断（**已重新界定为 BE**，见 D-26） | DONE | DR-301 | 冲突进入明确状态；谓词重写 + 骨架大小写归一后 **316 / 0 / 0 / 1** 经主对话独立复跑（见「DR-303 复核」「REWORK 复核」） |
| DR-304 | 受限反证触发、预算与停止条件（**已重新界定为 BE**，见 D-26） | DONE | DR-303 | 只对目标 Claim 反证且不会失控 + 调用账本；三项必改 + 锚单一实现后 **324 / 0 / 0 / 1** 经主对话独立复跑 |
| DR-305 | `RunCompletionGate` 与业务终态 | DONE | DR-301 | 业务终态唯一权威 + V107 落库 + D-25 分支 2 修复；**9 例**经主对话独占复跑全绿 |
| DR-108 | 恢复规则收敛（§7.2 Locality） | DONE | M2 | 策略接管重叠分支，断言迁移到 runtime 级 |
| DR-110 | Replan 审计接入真实触发点 | DONE | 裁决完成（见下） | 接线 + D-23 fail-fast + 2 负例（均断言零残留）；**5 例**经主对话独占复跑全绿。**真实库落库样本仍缺 → G-2** |

### DR-110 侦察裁决（2026-09-18，主对话）

问题：「Repair 是否等价于 Replan？」——主对话直接查 `research_matrix_plan` 的写入点，结论是**系统里已存在真实的计划修订写入路径**：

| 写入点 | `plan_mode` | 是否 Replan |
| --- | --- | --- |
| `ResearchMatrixPlanningService:71-75` | 初始计划 | 否（初始 Plan） |
| `ResearchDiscoveryProposalService:179-185` | `DISCOVERY_REVISION`、`plan_status='ACTIVE'` | **是**——同一 Run 写入第二条计划行并重算 `plan_digest` |
| `ResearchAgentCheckpointHydrator:318-323` | 恢复期重放计划 | **否**——恢复产物，不是决策 |

裁决：

1. **DR-110 的真实工作是把 `recordReplan` 接入 `ResearchDiscoveryProposalService` 的修订路径**，
   而不是「实现一个有界局部 Replan」——触发点已经存在，不需要新造。
2. **`deviation_type` 必须诚实分类**：发现驱动的修订是**范围扩张**，六类词表（输入理解错误 / 上下文变化 /
   约束冲突 / 边际收益耗尽 / Provider 失败 / 证据冲突）**覆盖不了它**。强行映射成 `CONTEXT_CHANGED` 属虚假归类，
   因此**扩展词表，新增 `SCOPE_EXPANDED`**。该词表是 Java 侧常量集合（非跨语言契约），扩展安全。
3. **`ResearchAgentCheckpointHydrator` 的恢复重放必须显式排除**，并有用例断言「hydration 不产生审计行」——
   否则每次恢复都会伪造一条 Replan 记录。
4. **任务级 Repair 不是 Replan**，不加审计：`ResearchAgentRepairAdvancementService` 为失败 Cell 建的是
   **任务**，不写 `research_matrix_plan`、不改 `plan_digest`；其决策已由任务记录与
   `research_agent_run_advancement` 覆盖。要求在回传中给出该结论的依据（表名 + 写入点）。
5. **必须产出真实 DB 记录样本**（这是 DR-203 缺失的那半份证据）：通过一条走通 discovery 修订路径的
   集成测试断言审计行落库且字段齐全。

### DR-301 二次复核（2026-09-18）

主对话独立复跑：research 包 **BUILD SUCCESS**（`ResearchAgentCompletionServiceTest` 58 run / 0 failures，
`ResearchAgentEvidenceQualificationGateTest` 3 run / 0 failures）。三项收口均已核实：

1. **4 条 fixture 只改合成数据、断言零改动**：`value-0` 引用 `trusted quote 1`（0≠1）的矛盾被消除；
   slot2 sample 改为 `second trusted quote 0 suffix`，与 slot1 的 `prefix trusted quote 0 suffix` 仍不同，
   **lineage 独立性未被削弱**。
2. **负例不是假测试**（按主对话要求做的中性化证伪）：把 3 处门禁钩子全部中性化后，
   `shouldNotPromoteCandidateWhoseGroundedQuoteContradictsItsClaim`、`shouldEnforceTheCandidateGateInShadowMode...`、
   `shouldReplayAQualificationRejectedCompletionWithoutPromotion` **必然变红**（2 failures + 1 error）；
   同时那 4 条既有用例在门禁关闭时**全绿**，证明 fixture 修复消除的是数据矛盾而非靠门禁掩盖。
3. **类型化错误落地且范围收窄**：`RESEARCH_AGENT_COMPLETION_QUALIFICATION_STALE`（`BusinessException`）只在
   「persisted qualification 判 REJECTED **且** 词面路径通过 **且** 存储 merge 为 ACCEPTED」时抛出
   （`ResearchAgentCompletionReplayRepository:315-323`）；其余收据不一致仍走原有 `corrupt(...)`，
   因此既有 5 个 corruption 场景语义不变。

## DR-108 收敛（2026-09-19）

裁决 D-11 保留了「loop 决策」与「Cell 级策略」两层，但要求把两者的**结论**收敛到一处
（§7.2 Locality：恢复规则不得散落在搜索、抽取和 Runner 中）。收敛方式是引入模块级指令
接口 `CellRecoveryDirective`（`workers/research-worker/app/cell_recovery_runtime.py`），
而不是再增加一套判断：

- 唯一收敛点：`build_cell_recovery_directive()`，输入为 loop 决策族 + 停止保护结论 +
  Cell 级恢复产物，输出一条不可变指令（生效 `recovery_mode` 及来源、外部动作是否被
  撤销、冻结 Cell、停止原因码）。
- 唯一提交点：`apply_cell_recovery_directive()`，把指令回写到轨迹与预算。
- `loop_runtime` 每轮只构建一次指令，`recovery_mode` / 停止原因 / 外部动作全部从该指令
  读取；`_augment_plan_for_next_round` 的模式收敛复用同一函数 `reconcile_recovery_mode()`。

收敛修掉了三类此前可观察的不一致（均已由 `tests/test_cell_recovery_directive.py` 锁定）：

| 现象 | 收敛前 | 收敛后 |
| --- | --- | --- |
| `recovery_mode` 请求 vs 生效 | 请求 `TARGETED_SEARCH`、生效 `EXTRACT_AGAIN`，来源不可解释 | 指令同时给出 `requested_recovery_mode` / `recovery_mode` / `recovery_mode_source`；跨族请求不再静默改写生效值 |
| 停止后仍计划外部调用 | `PROVIDER_NOT_CONFIGURED` 当轮留下 8 条 `external_call=True` | 停止保护一旦给出结论，`external_call_allowed=False`，动作转入 `suppressed_external_actions`，预算原额退还 |
| 冻结不进轨迹 | `REPEATED_CELL_FAILURE` 冻结 8 个 Cell，轨迹仍 `frozen=False` | 轨迹如实标记 `frozen=True` / `status_after=FROZEN` |

外部调用上界口径不变（仍由 `loop_stop_guard.external_recovery_call_upper_bound` 给出）；
未新增任何停止原因码，因此 Python ↔ Java 词表镜像无需同步改动。

## M3 首批（DR-301 / DR-109）

### DR-109 复核结论（2026-09-18）

主对话独立复跑：`pytest tests/` = **603 passed**、`test_cell_recovery_wiring.py` = **33 passed**。
`plan_digest()` 重写为内容摘要（`cell_recovery_runtime.py:532-575`），只改 2 个文件、未触碰 `loop_runtime.py`。

关键证据（均已核实）：

- 计入：`query_set` / `report_sections` / `research_type` / `target_entity_type` / 整张 `research_schema` /
  `stop_contract` 全部键值（排除 ignore-list）；排除：`plan_revision`、`replan_history`、`notes`、
  `plan_horizon`、`wall_clock_seconds_consumed`，逐条给出理由。
- 真实 loop 实测：`plan_revision=[0,1,2,3]` 而摘要自第 2 轮起稳定；第 4 轮 `entity-2` 得到
  `FREEZE_UNRESOLVED / REPEATED_PLAN_DIGEST`、`external_call=False`、`len(rounds)==4`（无第 5 轮）、
  `external_recovery_call_count=4 ≤ upper_bound=12`。
- 证伪方式正确：在内存中换回旧实现证明新断言会失败（旧摘要下 `REPEATED_PLAN_DIGEST` 不出现），
  **未改动受版本控制的文件**——符合 DR-106 之后新增的纪律。

三项裁决（主对话）：

| 事项 | 裁决 | 理由 |
| --- | --- | --- |
| 保护在第 4 轮才触发 | 接受 | 让第 1 轮即幂等属修改 `_augment_plan_for_next_round` 语义，超出退出条件；退出条件「保护真实生效」已达成 |
| 使用 I/O 边界替身构造真实 loop 场景 | 接受 | 计划演进、摘要、policy、stop guard 全为生产实现，仅替换 I/O；与仓库既有测试手法一致 |
| 排除 `plan_horizon` / `notes` | 接受 | 二者每轮被就地推进/追加，计入会造成假阳性导致保护永不触发；摘要是「内容是否变化」，阶段标记属进度记录 |

已记录的**限制**（不修，属有意设计）：完全真实 toolbox 下，`REPEATED_PLAN_DIGEST` 常被
`REPEATED_CELL_FAILURE` 或 Cell 预算耗尽更早遮蔽。规则顺序是「更便宜、更安全的停止先触发」，
不为了让它更早命中而调整优先级。

### DR-301 复核结论（2026-09-18，含 2 项裁决）

主对话独立复跑：research 包 `ResearchAgentCompletionServiceTest` = **57 run / 4 failures**（其余包全绿），
门禁实现已核实：

- `ResearchEvidenceQualificationService.rejectedCandidateKeys()`（`:259-263`）用 `TreeSet` 产出**排序确定**的被否候选集合，
  使写前规划与写后 CAS 校验逐字节一致；
- `ResearchAgentCompletionCommitter:134-135` 把 `rejectedCandidateKeys()` 传给 `planMergeOutcome`，
  `:190-191` 传给 `applyVerdictsAndCas`；`MergePlanner:50-54` 在词面通过但 qualification 否决时给出
  `EVIDENCE_QUALIFICATION_REJECTED` 且 `to_version == from_version`；
- `ResearchAgentCompletionReplayRepository:294-310` 从 `research_evidence_validation` 反推被否候选，
  使 replay 保持确定性；
- 门禁**不依赖** `strict` 与 Run flag（`GATING`/`SHADOW` 都执行），`GATING` 的整次失败行为未改，
  Worker 侧零改动（符合 D-14/D-15）。

| 编号 | 裁决 | 理由 |
| --- | --- | --- |
| D-16 | 审计落点采纳 A1：以 `research_evidence_validation` 为权威，**不改 `receipt_json`**；该退出条件判为「经裁决的等价实现」 | receipt 字段集在 Java（12 字段精确集合）与 Python（`_RECEIPT_FIELDS` 精确集合 + digest 重算）**双侧精确校验**，加字段会同时打断 replay 与完成握手，且需处理历史双形态兼容；而审计事实已含 mode / per-candidate / reason codes / digest，并叠加 `research_cell_merge.reason_code`，可审计性无损失 |
| D-17 | 门禁判据采纳 B1：**保持「任一 REJECTED 即否决」的字面口径**，修 4 个自相矛盾的 fixture（只改合成数据、断言不动） | 诊断显示红灯根因是测试数据自相矛盾（claim `value-0` 引用 `trusted quote 1`，0≠1 是真实矛盾；claim 含数字而引文不含属「引文不支持该 Claim」）。B2/B3/B4 都是为迁就不合理数据而放宽一个正确的门禁，属 §14 明列风险 |

同批次追加要求：历史 completion 重放的失败必须抛**带稳定 reason code 的 `BusinessException`**，
不得是裸 `IllegalStateException`（否则成为「不可解释终态失败」，与门禁指标冲突）。

已登记限制：H2（MySQL mode）下 `research_run.agent_feature_flags_json` 的 json 列会让 Run flag 读不出，
因此「Run flag 真实读取路径」目前只在 MySQL 覆盖；M6 证据在生产 MySQL 采集，届时覆盖。

## M4 前置侦察（2026-09-18，主对话）

### 事实 1：恢复入口已存在且部分 fail-closed，回退路径需改

`ResearchRunCommandService.resumeFromCheckpoint`（`:118-`）：

- 模式白名单 `AUTO` / `HYDRATE_REQUIRED` / `CONTEXT_RESTART`，非法值抛 `RESEARCH_CHECKPOINT_RESUME_MODE_INVALID`；
- `hydrate = !CONTEXT_RESTART && checkpointHydrator.available(...)`；
- `HYDRATE_REQUIRED` 且不可用 → 抛 `RESEARCH_CHECKPOINT_HYDRATION_REQUIRED`（**已 fail-closed**）；
- 但 `AUTO` 且不可用 → **静默回落到 `CONTEXT_RESTART`**（DR-401 设计文档 §5 指出的问题），
  且该入口**新建 descendant run**（设计文档 §1.3 已论证不能作为推荐口径）。

结论：DR-403 不需要从零实现恢复入口，而是把 `AUTO` 的静默回落改为显式失败，并把「新 Run」改为「同 Run 新 Attempt」。

### 事实 2：Worker **没有**向 Backend 写外部调用收据的通道

- Worker 侧只有**进程内** `checkpoint_callback`（`runner.py:306`）与 `ResumeCheckpointPayload` 的**读取**；
- 增量主链路的 `research_agent_checkpoint` 由 **Coordinator**（`ResearchBudgetAndCheckpointService`）在
  波次 / 阶段边界写入，**不是**在每次外部调用确认后写；
- 因此 DR-402「在搜索、抓取、抽取确认后写 Checkpoint」在当前架构下**无法直接实现**：
  Backend 拥有 ledger，而只有 Worker 知道外部调用的收据。

### 待裁决（D-18，DR-402 派发前定稿）

DR-402 的实现路径只有两条，必须先选一条：

| 选项 | 内容 | 代价 |
| --- | --- | --- |
| **R1（倾向）** | 新增 Worker → Backend 内部接口：提交 **Operation Receipt**（`operation_key` / `operation_kind` / `receipt_status` / `provider_request_id` / `receipt_digest`）并在其后写 Checkpoint barrier | 需要新接口 + 认证 + 幂等键；但这是架构文档 `[目标设计]` 明确要求的「Operation Key 和外部 Receipt」落点，也是 DR-404 去重与 DR-405 守恒的前提 |
| R2 | 维持 Coordinator 在波次 / 阶段边界写 Checkpoint，Worker 不报告逐调用收据 | 改动小；但拿不到 Operation-Key 粒度，`IN_FLIGHT` / `UNKNOWN` 状态无从表达，DR-404 的「已确认外部调用不重复」只能做到波次级近似 |

倾向 **R1**：R2 无法满足 DR-404 的退出条件（「已确认外部调用不重复」）与 D-8 中「M4 用真账本替换有损换算」的收口要求。
定稿前不派发 DR-402/403/404/405。

## 执行体停摆与重启（2026-09-19）

主对话观察到工作区**最后写盘时间为 09-18 23:56，此后约 14.5 小时无任何文件变化**，
四个执行体（DR-305 / DR-302 / DR-108 / DR-110）停摆且未回传。处理流程：

1. **先广播存活探测**（要求一行状态回复），确认无存活后由使用者确认停摆；
2. 删除旧团队 `deep-research-m3`，新建 `deep-research-m3r` 并**重派这 4 个任务**；
3. 每份任务书都写入「**先审查磁盘上已有半成品，在其基础上继续，不得从零重做**」，
   以及本批次的**并发边界**（4 个执行体各自可写哪些文件）。

**教训（已固化为执行纪律）**：长时间无回传时，**不能用「重派」代替「探测」**——
两个活体同时改同一批文件比停摆更糟。重启流程固定为
「广播探测 → 确认无存活 → 删团队 → 重派并在任务书里声明已有半成品」。

DR-305 停摆前已落盘且**设计正确**的半成品（已写入新任务书要求继续而非重做）：

| 产物 | 已核实的设计要点 |
| --- | --- |
| `V107__add_research_run_completion_decision.sql` | 保留 `research_run.status` 既有词表（既有终态守卫继续工作），另存 `completion_terminal_state` + `reason_codes` / `unresolved_cells` / `limitations` / `promotable_claims`；expand-only、可空、**不回填历史行**、带索引 |
| `ResearchAgentRunCompletionGate.java` | `CompletionDecision` 五字段与 §7.3 一致；`INFRASTRUCTURE_REASON_CODES` 注明「reused verbatim from the Worker's loop stop guard; never widened here」（D-9）；`VerifierState` 注明「disagreement is recorded as a limitation; it never gates promotion」（D-15） |

## DR-108 复核与裁决（2026-09-19）

主对话独立复跑：Worker 全量 **621 passed**（与执行体回传一致）。收敛结论：
`evaluate_loop_decision` 不再按 `read_window_count` 自行选择「重抽/搜/重读」，改为经
`resolve_loop_recovery_decision(outcome)` 消费 `CellRecoveryRuntime` 的策略产物；
`_ACTION_TO_LOOP_DECISION` 成为策略动作 → loop 决策族的**唯一映射点**。
`cell_recovery_policy.py` / `extraction_result.py` / `plan_digest` / `backend/` 均未触碰，
且执行体已核实 Java 侧**无**任何文件引用 `EXTRACT_AGAIN` / `READ_WINDOWS_WITHOUT_EVIDENCE` /
`SEARCH_HITS_WITHOUT_READ_WINDOWS` / `CELL_RECOVERY_HALTED`，故未改跨语言契约。

| 编号 | 裁决 | 理由 |
| --- | --- | --- |
| D-19 | A：接受该行为变化为**本次收敛的目标行为**，并要求补测试锁定新映射（`EMPTY_QUOTE` / `UNKNOWN_WINDOW` / `WRONG_COLUMN` → 族 `READ_MORE`，生效 recovery_mode 取策略请求值） | 旧行为（`read>0 & cards==0` 无条件 `EXTRACT_AGAIN` + `reconcile_recovery_mode` **跨族否决**策略请求）正是「两套权威」缺陷的后果；拒绝原因是空引文/未知窗口时重读窗口才对，强行重抽只会重复注定失败的抽取 |
| D-20 | B：接受 `has_conflict` 分支暂留 loop，**立案 DR-111** 让策略接管 | 当前两条路径渲染同一族 `(COUNTERFACTUAL_RECHECK, CONFLICTING_EVIDENCE)`，属重叠非矛盾；但 §7.2 要求恢复规则不散落，单独立案不并入 DR-108 |
| D-21 | C：接受并**升级为 M4 的硬约束** | `_restore_resume_context` 的兜底 `evaluate_loop_decision` 传 `None` → 检查点缺 `loop_decision` 时缺证据分支不生效。M4 的 DR-403 正好经过该路径，因此 hydration **必须携带或重建 `cell_recovery` 产物**，否则恢复后的 Run 会静默失去缺证据恢复能力 |
| D-22 | D：接受执行体对台账的订正 | 主对话在独立验证前把 DR-108 写成「策略接管重叠分支」，属「台账先于证据」；本条订正由主对话执行，并记录该偏差类型 |

**A 的锁定测试已补齐并复核**：新增参数化 3 例
`test_missing_evidence_family_follows_policy_request_not_legacy_extract_again`，锁定
`EMPTY_QUOTE`/`UNKNOWN_WINDOW` → 族 `READ_MORE` + 生效 `REREAD_WINDOW`、`WRONG_COLUMN` → 族 `READ_MORE` +
生效 `TARGETED_SEARCH`（来源均为 `CELL_POLICY`），一次性锁死「决策族跟随策略」与「生效 mode 取策略请求值」两个面。
主对话独立复跑：Worker 全量 **624 passed**、专项 `test_cell_recovery_directive.py` **21 passed**。
执行体用**内存变异**证伪（换回收敛前等价规则后 3/3 必然 AssertionError），未改动受版本控制的文件。

**DR-108 → DONE。**

## DR-110 复核（2026-09-19，主对话）

执行体回传的关键声明是「本任务实际新增实现代码 0 行——接线、`SCOPE_EXPANDED`、排除 hydration 三项
均已在前一批次停摆前落盘」。该声明**已核实为真**：`git status` 显示 6 个文件均为 untracked，
无本任务新增的实现改动。因此 DR-110 的产出是**审查 + 验证 + 纠偏**，不是新写代码；
台账按此记功，避免重演 D-22 的「台账先于证据」。

**独立复跑**：`-Dtest='ResearchAgentReplanAuditDiscoveryWiringTest,ResearchAgentReplanAuditServiceTest'`
→ **Tests run: 11, Failures: 0, Errors: 0**，BUILD SUCCESS。

**通过项**：

- 接线属实：`ResearchDiscoveryProposalService.applyAcceptedRevision()` :212 写审计 → :213 写
  `DISCOVERY_REVISION`/`ACTIVE` 计划行。审计先写，使 `(run, plan_revision_to)` 成为重放首先撞到的键。
- 事务边界经调用链核实：`ResearchAgentCompletionCommitter.commit()` 的 `@Transactional(REQUIRED)`
  向下贯穿到 `applyAcceptedRevision()`，两行同生共死；wire 测试用「重放被拒后 plan/cell/proposal
  计数均不变」实证了回滚。
- `SCOPE_EXPANDED` 的诚实性成立：真实可观察偏差是范围扩张（新增第二条 ACTIVE 计划行 + 5 个 cell），
  而 frozen context / source scope / evidence horizon 未变，映射为 `CONTEXT_CHANGED` 属虚假归类。
- **跨语言面已证伪风险**：主对话搜索 `workers/`，`SCOPE_EXPANDED` / `deviation_type` / `CONTEXT_CHANGED`
  **0 命中**，确认该词表是 Java 侧常量集合，扩展不构成跨语言契约变更。
- 风险 (c)（唯一键冲突判定）**不成立**：`ResearchAgentReplanAuditService` :115-134 是「先 catch
  `DataIntegrityViolationException` 再回查分类」的标准写法，真实护栏仍是 DB 唯一键。
  执行体的自述把自己的代码写得比实际更可疑，已要求其更正回传。

**必改项（→ REWORK）**：`recordReplanAudit` :263 的提前返回是静默降级，不能被当作「保守」。它使
`applyAcceptedRevision` :213 **仍然写入计划行**，从而产生「有 Replan 计划行、无审计行」的状态，
并丢掉 :208-211 所依赖的幂等撞键——该分支下重放不会撞键，`nextPlanRevision` 递增使计划行无界累积。
决定性证据是：`ResearchAgentReplanAuditService` 的契约**本来就硬拒**这两个输入
（`requireDigest(null)` → `RESEARCH_REPLAN_DIGEST_INVALID`；`requireAffectedCells(空)` →
`RESEARCH_REPLAN_AFFECTED_CELLS_INVALID`，注释原文「A Replan must name its affected cells」）。
即 :263 是在绕开审计服务刻意强制的契约，而非遵守它。同方法内 :150-151 的
`RESEARCH_DISCOVERY_PLAN_BOUNDED` 已是「直接抛异常回滚整次提交」的先例，fail-fast 与既有风格一致。

**DR-110 → REWORK**：待补 `RESEARCH_REPLAN_AUDIT_PLAN_MISSING` /
`RESEARCH_REPLAN_AUDIT_NO_AFFECTED_CELLS` 两个显式失败与对应负例（各断言无半成品残留）后转 DONE。

## DR-302 复核（2026-09-19，主对话）

产物：`backend/src/test/java/com/noteweave/research/ResearchAgentEvidenceEnforcementNegativeTest.java`（20 例）、
`experiments/deep-research/reports/DR-302-negative-cases.md`（13 KB）。主代码、迁移、Python 均未改，符合边界。

**主对话已独立核对的部分**：15 个 reason code 的枚举与代码一致——`validateEvidence`(:165-191)
产出 3（`SOURCE_AUTHORITY_MISSING` / `QUOTE_EMPTY` / `CLAIM_EMPTY`）+ 5（typed）+ 3（`SOURCE_DOMAIN_MISSING` /
`LINEAGE_DIGEST_INVALID` / `SNAPSHOT_NOT_QUALIFIED`），`validateBinding`(:146-163) 产出 3，去重后恰为 15。
「不可达 8 个」的拦截层说法经查证成立：`QUOTE_EMPTY` / `CLAIM_EMPTY` / `RELATION_UNKNOWN` /
`SNAPSHOT_NOT_QUALIFIED` 确实被 `Canonicalizer:245-248` 先拦。
判为**可达**的 7 个用 `rejectedMerges[0].reason_code == EVIDENCE_QUALIFICATION_REJECTED` 作为断言——
该码只在「字面规则通过 ∧ qualification 否决」时产生，因此「修复前会被提升、现在被拒」被直接证明。
对照组（SUPPORTS + 精确引文含类型化事实 → 正常提升）证明门禁未收得过严。

**归因错误订正（并发污染，非 DR-302 缺陷）**：执行体报告称「DR-110 的 wiring test 单独运行仍红 3/3，
是 DR-110 未收口」。实测证伪：该类早前已被主对话独立复跑为 **3 passed**；
`ResearchDiscoveryProposalService.java` 写盘于 **14:58:24**、wiring test 写盘于 **14:53:24**，
而执行体回传时刻为 14:58:37——`r-replan-trigger` 正在 REWORK 编辑中，其「3/3 红」与首轮
`254 run / 75 errors`（全部 `ApplicationContext failure threshold exceeded`）同为**同一 `target/`
上的并发构建互相踩踏**。

**派发方式偏差（主对话）**：4 个执行体的验证窗口重叠是主对话的派发缺陷。
由此新增流程约束（对全体执行体生效）：**检测到其它 java/maven 进程运行时，禁止跑
`com.noteweave.research.*Test` 整包回归**，只允许跑自己的测试类；整包回归由主对话在并发静默后
独占执行，作为唯一权威门禁。

**DR-302 → VERIFYING**（不是 DONE）：执行体回传的 20 例与整包数字尚未取得主对话独占复跑，
按「台账不得先于证据」（D-22）不得提前记 DONE。见 D-24。

**登记**：
- **DR-112**（已派发）：把「使 `validateBinding` 的 `evidence.isEmpty()` 分支不可达」的三条不变量
  固化成测试。见 D-24。
- **DR-113**（观察项，未派发）：跨语言 typed-claim 语义不一致——Python `claim_fact_validator.py`
  产出 `LOCAL_POLARITY_CONTRADICTION` / `NO_TYPED_CLAIM_FACTS`，Java `ResearchTypedClaimValidator`
  无极性实现，且 `NO_TYPED_CLAIM_FACTS` 仅在 `NOT_APPLICABLE` 状态出现、不进 reason_codes。
  当前无实际影响（Python 侧未据此做门禁），登记为潜在分叉点。

### DR-112 交付复核（2026-09-19，主对话）

执行体交付：`ResearchAgentEvidenceEnforcementNegativeTest` 由 20 例增至 **23 例**（group 5 三个用例），
并在 `ResearchEvidenceQualificationService` :158-172 加入 15 行注释（**仅注释**）。
自测 `-Dtest='ResearchAgentEvidenceEnforcementNegativeTest'` → 23/0/0，BUILD SUCCESS（连跑两次一致）；
并遵守了新流程约束（只跑本类、未触发整包回归）。

**已核实**：注释文本与回传逐字一致；:173-176 的代码与改动前 :158-161 相同且**位移恰为 +15 行**
（等于插入的注释行数）；`validateEvidence` 现位于 :180-206，内容与改动前 :165-191 逐字一致，
即该文件唯一的改动就是那段注释。新用例的非空转设计比任务书要求更细：断言拒绝消息**片段**，
并用前置断言排除同一消息串中的其它 `||` 子句（用例 2 断言所引 key 唯一以排除重复子句；
用例 3 断言 key 确在 envelope 内以排除 outside 子句；用例 1 断言 version/confidence 合法）。
报告 §3/§4.3 的 DR-110 归因已按裁决改为观测式，删除了归属结论。

**主对话记录一处自身偏差（方法错误）**：主对话最初用 `git diff` 校验该注释改动，得到空输出并一度
怀疑注释未落盘。根因是 `ResearchEvidenceQualificationService.java` 处于 **untracked** 状态，
`git diff` 对未跟踪文件本就不产生任何输出——**该检查在设计上就不可能检出问题**。
这与 D-22 记录的「结论先于证据」属同一类偏差（以不可证伪的检查代替真检查），记入台账。
改用「注释文本比对 + 行位移核算 + 相邻方法逐字比对」后确认申报属实。

**仍欠的验证**：23 例与整包数字**尚未取得主对话独立复跑**（并发构建未静默）。
DR-302 因此保持 `VERIFYING`，不记 DONE。

## DR-305 复核与裁决（2026-09-19，主对话）

执行体回传：`RunCompletionGate` 已接线为业务终态唯一权威，`CompletionDecision` 写入 V107 五列，
补 8 例测试；整包 **300 / 0 / 0 / 1**。

**交叉验证（300 可信）**：r-negative-cases 早前报 **298**，差值恰为 2 =
`r-replan-trigger` 按 REWORK 新增的两个 DR-110 负例（wiring 3→5）。两个独立执行体的数字能对上，
说明该 300 的测量窗口干净。

**执行体自查发现的 3 处缺陷，主对话认可**：
1. 缺「终态 → `research_run.status`」映射权威 → 已在 `CompletionTerminalState.researchRunStatus()`
   内收拢（`INFRASTRUCTURE_FAILURE → FAILED`，其余含 `INSUFFICIENT_EVIDENCE` → `COMPLETED`），
   接线方无法自造映射。理由成立：终态集合 `{"COMPLETED","FAILED","CANCELLED"}` 在 research 包内
   被 8 处守卫重复，新增第五个 transport 值会**静默解除 Run 的终态性**。
2. 分支 3 静默吞掉基础设施原因（违反 D-9）→ 已改为同时记录 infra 码。
3. 缺稳定失败 reason → 新增 `infrastructureTerminalReason(decision)`。
另经核对：gate **从不读取** `terminal_disposition`（D-13 权威边界实现正确）；
`INFRASTRUCTURE_REASON_CODES` 逐字复用 Worker `loop_stop_guard`、注明 never widened（D-9），未扩大。

**主对话新发现的必改项（执行体修复 #2 不完整）**：修复 #2 只覆盖分支 3(:275) 与分支 4(:284)，
**漏了分支 2**（:258-262）。「infra 失败 + 全部 cell 已 resolved」会落到 `COMPLETED_VERIFIED`，
reasonCodes 仅 `ALL_REQUIRED_CELLS_VERIFIED`，**基础设施根因在该分支彻底消失**，与执行体自己声明的
修复意图不一致。该分支可达（先在全部 cell 上完成、随后某次 provider 调用失败并被记录）。
要求：分支 2 在 `infrastructure` 时加入 infra 码，**终态保持 `COMPLETED_VERIFIED` 不变**
（全部 cell 已 resolved 是当时最强的真命题，不应因一次后来的 provider 故障被降级）+ 1 个用例。
见 **D-25**。**DR-305 → REWORK**。

### 主对话裁决（DR-305 请示的三项）

| 编号 | 裁决 | 理由 |
| --- | --- | --- |
| R-1 | 「业务终态 = COMPLETED 但无报告」**接受为正确中间态**，并升级为 **M5 硬前置** | 成文属报告层，分层由 D-15 确立；在 DR-305 内渲染报告反而会让 M5 的引用审计无从插入。执行体已核实 `ResearchCollectionService` / `ResearchArtifactService` 因 markdown 为空而**拒绝**（不伪造报告），scanner 只挑 `status='RUNNING'`、finalization 要求 `nonFinalizableCellCount==0`。**M5 未满足「DR-503 为 `COMPLETED_WITH_LIMITATIONS` / `INSUFFICIENT_EVIDENCE` 渲染限制性报告 + 断言 markdown 为空时绝不产生 `research_agent_report_artifact`」前不得标完成** |
| R-2 | infra + 有 promotable → **保持** `COMPLETED_WITH_LIMITATIONS`（补可见性），不改判 `INFRASTRUCTURE_FAILURE` | 若因 infra 丢弃已合格结论，是**反方向重演基线错误**：`31d1d44f` 把基础设施故障计成业务结果，而反向隐藏合格结论同样不诚实。D-9 的要求是「两者可区分」，由终态 + reasonCodes 共同满足 |
| R-3 | infra 词表镜像守卫 → **立案 DR-114** | `INFRASTRUCTURE_SIGNALS` 是 Python `loop_stop_guard` 的硬编码副本，漂移风险与 DR-106 同构，沿用 DR-106 的守卫模式 |
| R-4 | 并发干扰归因一致，固化为流程约束 | 与「DR-302 复核」发现的同一现象；执行体的波动数字（79→17→149→40）不写入交付结论 |

### 显式门禁项：真实环境迁移水位（主对话实测，2026-09-19）

**发现：真实 MySQL 停在 V104，V105 / V106 / V107 从未应用过。**

| 检查项 | 实测结果 |
| --- | --- |
| `flyway_schema_history` 最高版本 | **V104**（installed_rank 87） |
| `research_run.completion_terminal_state` | 不存在（`ERROR 1054 Unknown column`） |
| `research_agent_replan%` 表 | 不存在 |
| `research_agent_execution.extraction%` 列 | 不存在 |

后果与登记（**阻塞 M4/M6 的真实环境验收，不阻塞代码收口**）：

- **G-1**：真实环境迁移到 head 并实检 V105 / V106 / V107 的 DDL 与索引。只需启动应用让 Flyway 执行，
  **不产生外部计费调用**，由主对话在并发静默后独占执行。

  **已执行并通过（2026-09-19，主对话）**：`docker compose --profile app build backend && up -d backend`
  （镜像内编译、`-Dmaven.test.skip=true`，**不读宿主 `backend/target/`**，故与执行体的 Maven 构建无冲突）。
  - `flyway_schema_history`：V105 / V106 / V107 写入且 `success=1`，真实库由 V104 → **head(V107)**；
    容器随后 `Up (healthy)`。
  - **V105**：`research_agent_execution.extraction_diagnostics_json longtext` 存在。
  - **V106**：`research_agent_replan_audit` 13 列全部就位，类型与审计服务契约吻合——
    `old/new_plan_digest varchar(71)`（= `sha256:` + 64 hex）、`observed_facts varchar(512)` /
    `selected_repair varchar(512)`（= `MAX_OBSERVED_FACTS_CHARS` / `MAX_SELECTED_REPAIR_CHARS`）、
    `affected_cells_json` / `budget_delta_json` NOT NULL、`evidence_refs_json` 可空（与
    `optionalReferences` 允许 null 一致）。
  - **V107**：`completion_terminal_state varchar(32)` + 四个 `longtext` 全部可空（expand-only、不回填）；
    索引 `idx_research_run_completion_terminal_state (completion_terminal_state, updated_at)` 已建，列序正确。
  - 副产物：`noteweave-v2-backend` 容器现运行含 DR-102 / DR-203 / DR-305 / DR-110 最新代码的镜像，
    真实环境具备执行 G-2 / G-3 的条件。
- **G-2**：真实 MySQL 上 DR-110 的 `research_agent_replan_audit` 落库样本（需一次真实 discovery 修订）。
- **G-3**：真实 MySQL 上 DR-305 的 `completion_terminal_state` / reason codes 落库样本。
- G-2 / G-3 需要真实 Run，会产生外部计费调用，**须使用者确认后再执行**。
- 本项同时解释了 M1 遗留的「`extraction_diagnostics_json` 真实落库样本未采」——该列在真实库中根本不存在。
- 执行体在 DR-305 中**如实申报 H2-only、未夸大为真实 MySQL 验证**，此点予以肯定。

## M3r 波次收口：权威回归（2026-09-19，主对话独占执行）

四个执行体全部结束后（`git` 工作树静默、无 java 进程），主对话在**并发静默窗口内独占执行**权威回归，
这是 DR-302 / DR-305 / DR-110 三项收口的唯一依据：

| 命令 | 结果 |
| --- | --- |
| `-Dtest='com.noteweave.research.*Test'` | **304 run / 0 failures / 0 errors / 1 skipped**，BUILD SUCCESS |
| `-Dtest='ResearchAgentEvidenceEnforcementNegativeTest,ResearchAgentReplanAuditDiscoveryWiringTest'` | **28 run / 0 failures**（23 + 5） |
| 包内 `ResearchAgentRunCompletionGateTest` | **9 run / 0 failures**（单独可见） |

**计数核对成立**：267（非本波次基线）+ 23（DR-302 + DR-112）+ 9（DR-305 + D-25）+ 5（DR-110 + D-23）
= **304**。1 个 skipped 与基线相同，未新增跳过。四个来源的数字互相印证，说明本轮无隐藏的用例丢失。

**DR-110 的收口依据说明**：执行体的 REWORK 报告**在主对话完成验证之后才到达**（关闭批准前一刻），
故 DR-110 的 DONE 建立在主对话直接验证之上（代码逐行 + 5 例实跑），不依赖其自述；报告到达后其内容
与主对话验证一致，并**补充了一条主对话未取得的证据**。

报告新增的关键证据与订正：

- **变异证伪（执行体实跑）**：把两处 fail-fast 临时中性化为 `log.warn + return` 后复跑
  → **Tests run: 5, Failures: 2**，两条失败均为「Expecting code to raise a throwable」，其余 3 条仍绿，
  随后还原。这证明了两个新负例**确由新守卫驱动**，而非空转。
  主对话对此另有独立论证：所断言的 `RESEARCH_REPLAN_AUDIT_PLAN_MISSING` /
  `RESEARCH_REPLAN_AUDIT_NO_AFFECTED_CELLS` 全仓仅出现在该服务与该校验类中（grep 命中 3 处），
  且 `assertThatThrownBy` 在不抛异常时必然失败——即**用例不可能在守卫缺失时通过**。
- **执行体独立复跑整包亦为 304 / 0 / 0 / 1**，与主对话独占执行的结果一致；并逐个类比对 surefire
  计数确认**既有类计数不变**（如 `ResearchAgentCompletionServiceTest` 58、
  `ResearchAgentCompletionCanonicalizerTest` 14 等），仅本类 3→5。
- **风险 (c) 订正到位**：执行体声明上一份回传中「唯一键冲突靠回查 count 判定而非 DB 约束」的表述
  **作废**，确认实现是标准的 catch-then-classify，真实护栏是唯一键
  `uq_research_agent_replan_audit_revision`，回查只用于把冲突翻译成稳定业务码。**该项不作为风险记入台账**。
- 执行体另记录两起归属他人的**编译期瞬时红灯**（`找不到符号 ResearchAgentCompletionCanonicalizer`、
  `class path resource [...Canonicalizer.class] cannot be opened`），并自行归因于并发 Maven 重写
  `target/classes`（即本台账已登记的「陈旧/并发编译产物」陷阱），未改他人文件，稍后复跑即全绿。

两个负例的质量好于要求：用 `seedDiscoveryRun(hasPlanRow, hasRowsAndCells)` **参数化 fixture 以隔离两个守卫**
（用例 1 保留 rows+cells 使 `addedCells` 非空、只缺计划行；用例 2 保留计划行使 replaced digest 可解析、
只缺 rows/cells），避免被更早的校验先拦；两者都断言了零残留。执行体未为让测试变绿而放宽
`ResearchAgentReplanAuditService` 的硬拒契约，方向正确。

### M3 尚未完成的部分

M3 批次中仍有 **DR-303（局部与全局冲突判断）** 与 **DR-304（受限反证触发、预算与停止条件）** 未开始，
另有 `r-recovery-convergence` 立案的 **DR-111**（`has_conflict` 分支改由策略接管，裁决 D-20）。
三者构成下一波次，且 **DR-304 依赖 DR-303**（须串行）。本波次已证明并发编辑会污染彼此的验证结论，
故下一波次单开团队、缩小并发度。

## M3 收尾侦察（2026-09-19，主对话派发的两个只读侦察体）

派发 DR-303 / DR-304 / DR-111 之前先做只读侦察（本台账纪律：不采信计划表格的假设）。两个侦察体
均只读、0 改动，结论带文件+行号，且其中若干条与主对话此前亲自读过的代码互相印证（如
`ResearchAgentRunCompletionGate.remainingReason` 只产出 `EVIDENCE_MISSING` /
`REQUIRED_CELL_UNRESOLVED` / `OPTIONAL_CELL_UNRESOLVED`，与 :293-296 逐字一致；
`CellState.resolved()` 只认 `VERIFIED`+非空证据，与 :137-139 一致）。

### 事实 1：仓库存在两条链路，DR-303/304 的现状必须分链路看

| 链路 | 入口 | 是否跑 loop / verifier | 冲突以何形式存在 |
| --- | --- | --- | --- |
| legacy 全量研究循环 | `app/runner.py` | **是**（loop + branch + dual verifier，DR-301~305 的落点） | run 级卡片 `relation_type=CONFLICTS` → Cell/Row `CONFLICTED` 状态 |
| **MA4 生产原子链路** | `app/deep_cell_executor.py` | **否**（只提交 evidence/candidate） | 仅作为证据属性 `relation_type=CONFLICTS` + `conflict_score_ppm` 落库 |

### 事实 2：跨 Cell/Row/Branch 的全局冲突判断**不存在**

最近似的四个近似物都不是全局冲突判断：`run_global_verifier` 的 `CANONICAL_CONFLICT_OPEN`
（只消费 `conflicted_row_count`，是消费方非判断方）、报告层 "Conflicts And Uncertainty"（纯渲染，
且 `verifier.py:1082` 把「章节名出现在 report_sections」当作满足条件）、Java
`ResearchTypedClaimValidator.CONTRADICTED`（只比较**同一条** evidence 的 claim 与自身 quote，
不跨来源）、Java 读模型的 `conflicted_row_count`（读 `research_row.row_status` 计数）。

### 事实 3：冲突状态不是跨语言契约，且在生产链路中恒不出现

`research_cell.cell_status` / `research_row.row_status` **均无 CHECK 约束**；Java **不存在写入
`'CONFLICTED'` 的 SQL**，写 `cell_status` 的路径只有 merge 成功的 `'VERIFIED'`。因此 Java 读模型里
的 `conflicted_row_count` 在**生产 Incremental 链路恒为 0**。

### 事实 4：终态门禁把冲突**静默降级为「未解决」**

一个 CONFLICTED cell ⇒ `!resolved()` ⇒ 进 `unresolvedCells`，reason 只有
`EVIDENCE_MISSING` / `REQUIRED_CELL_UNRESOLVED` / `OPTIONAL_CELL_UNRESOLVED`——**没有冲突专属 reason**。
落在必需列 → `INSUFFICIENT_EVIDENCE`；落在非必需列 → `COMPLETED_WITH_LIMITATIONS`。
`ResearchAgentRunCompletionGateTest` 9 个用例**没有 CONFLICTED 用例**。

### 事实 5：`recovery_target_evidence_ids` 被写入但**全仓无消费者**

`loop_runtime.py` :1130 写入（上限 3）、:1176/:1181 清除，**没有任何读取方**。即当前「反证」实际限定到
**entity / source / query / column**，**未限定到 evidence / Claim id**——这正是 DR-304 退出条件
「只对目标 Claim 反证」最直接的缺口。

### 事实 6：反证次数**未被独立计量**；恢复轨迹**无生产持久化**

Worker usage 词表只有 7 个通用计数器，无 counterfactual 键；`external_recovery_call_count`、
`CellRecoveryRuntime._attempts`、`repair_count` 均为**混合计量**。`cell_recovery_trace` /
`stop_reason_code` / `external_recovery_call_count` **只进 `checkpoint_callback`**，而
`research_checkpoint_candidate` 与 `research-result.v2` 都不含这三个字段，生产入口无调用方。

### 事实 7（DR-111）：两路径**渲染完全相同**，但冲突场景下策略**无消费点**

`loop_runtime.py:864-873`（loop 自判）与 `:875-893`（策略经 `resolve_loop_recovery_decision`）
构造出的 `ResearchLoopDecision` **逐字段相同**（含共用同一 `recovery_actions` 字符串）——差异只在
触发输入、求值顺序、Cell 级副作用、fallback 可用性。**关键**：:875 的门是
`evidence_card_count <= 0`，而冲突场景 `evidence_card_count > 0`，故**冲突场景下策略产物根本没有
loop 消费点**。策略也看不到 run 级冲突信号（CONFLICTS 卡片 / 活动反证分支 / `conflicted_row_count` /
`CONFLICT_FINDING` 要求）。

### 事实 8（DR-111）：停止保护**假定冲突由 loop 分支吸收**

`loop_stop_guard.py:100-102` 与 `446-452` 的 `_UNRESOLVED_EXCLUDED_STATES` **显式排除 CONFLICTED**，
注释写明「由冲突分支处理」。即 `REPEATED_CELL_FAILURE` / `CELL_ATTEMPT_LIMIT_REACHED` 的 Cell 集合
**依赖 864 分支吸收冲突**；若 loop 不再吸收而策略也判「无外部动作」，停止保护不会给出
`CELL_RECOVERY_HALTED`。

### 裁决

| 编号 | 裁决 | 理由 |
| --- | --- | --- |
| D-26 | **DR-303 / DR-304 重新界定为 BE 侧（Java canonical ledger）**，与 DR-301 的重新界定同源 | M6 的固定评测必须跑**真实 Run**，而真实 Run 走 MA4 生产链路。在 legacy 链路上实现「冲突进入明确状态」，其能力**无法出现在任何真实评测样本里**——那等于做一个不可被证据支撑的简历主张。事实 2~4 同时说明 Java 侧具备需要的判断面（canonical facts 独立重算，D-14）与明确的接入位（`ResearchTypedClaimValidator` 已有局部冲突；`ResearchEvidenceQualificationService` 是现成消费方；终态门禁的 `remainingReason` 是冲突显式化的最小落点） |
| D-27 | **DR-303 的「冲突进入明确状态」必须落到终态门禁的 reason 上**：CONFLICTED 不得再被静默降级为「未解决」，须有冲突专属 reason | 事实 4：当前一个冲突 cell 在终态里与「从未拿到证据」**完全无法区分**，这违反 D-9 同一条原则（不同成因不得塌进同一个桶）。此项是低成本高价值的最小可见成果 |
| D-28 | **DR-111 降级为「依赖 DR-303 结论」**，不在本轮实施 | 事实 7 显示接管需要先给策略注入 run 级冲突信号（当前完全没有），并与事实 8 的停止保护假设耦合，改动面远大于其 Locality 收益，且两路径**当前渲染完全相同**（无行为分叉，纯结构问题）。更关键的是：若 DR-303 按 D-26 把冲突判断迁到 Java，则 legacy loop 的 `has_conflict` 分支**是否还该存在**（整体废弃 vs 迁移）才是真问题——先做 DR-303 再定 |
| D-29 | DR-303 的跨来源互斥**不得复用** `ResearchTypedClaimValidator.validate(claimA, claimB)`；必须直接定义「同一事实域 ∧ 同一 fact kind 取值互斥」谓词，并要求 5 条负例（含「取值一致、仅日期上下文不同 → 不冲突」） | 该方法是**包含性谓词**（引文是否包含 claim 的全部类型化事实），设计用途是「claim vs 自身引文」；跨 claim 使用等价于把「包含」偷换成「相等」。逐行验算：(a)「2023 revenue 100」vs「2024 revenue 200」因 `quote.dates ⊉ claim.dates`(:48-49) 判矛盾（实为不同年份，都真）；(b)「revenue 100」vs「cost 200」因数值不等(:56-58) 判矛盾（主体不同）；(c)「revenue was 100 in 2023」vs「revenue was 100 in 2024」**取值一致**仍因日期上下文不同判 `CONTRADICTED`(:65)。后果为**粘性、无修复路径的永久降级**（DR-304 未实现）。抽取逻辑仍可从同一来源复用（`facts()` 提为包内可见），但**谓词不可复用** |
| D-30 | 接受 typed-fact 冲突路径的**窄召回**（要求精确骨架相等），不做模糊匹配或 token 重叠阈值；召回由 `CONFLICTS_RELATION_WITH_SUPPORT` 形状承担，typed-fact 定位为高精度兜底 | 两种错误的代价**不对称**：误报会把正确 cell 降级为 `CONFLICTED` 且**粘性、当前无修复路径**（DR-304 未实现），并让 M6 评测样本无法解释（被误判的 run 与真冲突在数据上无法区分）；漏报只是少一个标记。模糊化（阈值、重叠度）会把判定从**可证明的同一事实锚点**退化为调参，重新引入误报面。必须把分工写进 javadoc，避免后来者误把它当主检测器 |
| D-31 | 保留执行体对轨迹 `reason_code` 语义的改动（承载**细分原因码**，粗粒度形状种类移入 `notes_json.conflict_kind`） | 查证 `research_verifier_decision` 的既有约定：`ResearchAgentCompletionCommitter`:747-749 与 `ResearchAgentRoleResultService`:138-139 **均**以 `decision_type` 承载粗粒度类别、`reason_code` 承载细分原因。因此执行体的**原实现才是异类**，改动后与两个既存写入方一致。同时固化流程规则：审计方写规格须给**可观测目标**而非列级表示（除非已核实 schema）；执行体遇「规格断言与数据模型冲突」须**先报后做**，不得为让断言字面成立而改持久化列语义 |
| D-32 | 冲突收敛的投票域**必须限定到冲突所在的事实域**（以 `finding.evidenceKeys()` 的冲突双方导出日期上下文 + 骨架锚，只对共享该锚的证据投票）；禁止在该 cell 的全体证据上投票 | 当前实现（`strictMajority` 在全体 identified 证据上按数值签名分组）可被与该冲突无关的证据「多数」掉：实测场景为 A/B 关于营收 100 vs 200 的真冲突 + C/D 两条无关的 `cost was 500` → counts=[2,1,1] → `2>1` 判**已消解**，cell 回到 `CANDIDATE_READY` 可被提升。**冲突被正确检测又被错误清除**，比漏报更严重（漏报少一个标记，这是抹掉已记录的分歧） |
| D-33 | tick 必须在取任何 **cell 级锁之前**先取 **run 行锁**，使其 (run, cell) 加锁顺序与 `ResearchAgentCompletionCommitter`（`lockRun`→`lockCells`）一致 | 当前 tick 是 `adjudicateRun` 先取 cell 行锁、之后 snapshot/advance 才需 run 级锁，与提交器的 run→cell 相反，构成 ABBA 死锁：并发时 InnoDB 回滚其一，被回滚的若是完成提交则任务拖到租约过期，表现为偶发失败。**我们不接受把「自己引入的死锁」当已知风险长期挂着**。H2 无法证明死锁不存在，故本条以代码级加锁顺序核对为准，真实引擎验证归入 M4 锁矩阵门禁 `ResearchAgentMySqlLockMatrixIT` |
| D-34 | 事实域锚（`skeleton` + 日期上下文）必须是**单一实现**，由判定层与消解层共用；**不得镜像** | D-29 禁的是**谓词被误用**（`validate()` 跨 claim 判互斥），同时**要求抽取逻辑从同一来源复用**（`facts()` 提为包内可见）。执行体把「不得改判定器」误读为「不得导出原语」，于是镜像出第二份 `skeleton`。锚是两层共用的语义原语：判定层用它决定「能不能比」，消解层用它决定「能不能投票」。漂移后果恰是刚修掉的缺陷——检测器 javadoc 自己写了「标点未归一」是将来可能变的点，一旦只改一处，消解侧锚**静默**与检测侧不一致，投票域重新越界。`independentSourceCount` 的镜像本次**不要求合并**（原方法是实例方法且签名不同，合并要动 qualification 门禁，风险大于收益），但要求把耦合写实为「改一处必须同步另一处」 |
| D-36 | **M4 的验收标准依赖 legacy loop，须与 D-35 一并做范围决策**；决策前**不派发** DR-402/403/404/405 的 Worker 侧部分 | 计划 §9 把 M4 写成「抓取完成后 **Worker 退出** → 新 Attempt 复用抓取收据」，这要求 Worker 在 Run 内跑多阶段（loop）；但生产走 `deep_cell_executor`（单任务内跑完 search→fetch→read→extract，不跑 loop），且 D-21 侦察证实 Worker 侧 restore 在生产**不可达**（`resume_checkpoint` 无生产者）。**同一结构问题的第三次出现**（D-26、D-35、M4），故不再逐任务临时重界定，而是把「哪条链路是受支持的研究链路」作为一次性范围决策交给使用者 |
| D-37 | 先派发**与链路选择无关的 Java 侧 hydration 正确性**（M4-A），不等范围决策 | 侦察发现的三条属**静默降级**类，与走哪条链路无关：① `hydrate` 无幂等键且 `research_verifier_decision` 无唯一键 → 部分重放**静默重复**；② `AUTO` 不可用时**静默回落** `CONTEXT_RESTART`；③ `hydrate()` 不校验 `enabled`，绕过 `available()` 即静默恢复。加上「恢复即零工作量完成」必须显式化、`plan_revision` 兜底 0 会导致永久无法绑定 task——这些无论选哪条链路都要修，不会白做 |
| D-38 | `taskCount()==0` 的引导分支在**引导无效时不得遮蔽后续可终结判定**；派发 M4-A2，并**授权改动 tick 的决策分支条件与顺序**（但仍禁止改 `adjudicateRun → advance → snapshot` 时序与 run→cell 加锁顺序） | 执行体读码证实 `:68`（`taskCount()==0` → `INITIAL_WAVE_TASKIZED`）**先于** `:93`（`readyForFinalization()`）返回；hydration 不复制 task 故 descendant `taskCount()==0` 恒真，而 `planAndEnqueueForWave` 只选 `GAP/STALE/CANDIDATE_READY`，全 VERIFIED 时创建 0 个 task → **每 tick 返回同一 outcome、永不推进**，`resumeFromCheckpoint` 恢复出的 Run **永远无法终结**。同类可达场景：cell 全为 `CONFLICTED`/`CONFLICT_EXHAUSTED`（均不在可调度集合内）且反证不可行/耗尽 → taskCount 可能为 0 → 同样 livelock，此时应由 `RunCompletionGate` 给出诚实终态。**这是 M4「真实恢复」的核心缺陷**，且比主对话原判（「立即完成」）严重一个量级。**不得**让「既没做事、又不终结」的 outcome 存在 |
| D-39 | **使用者已裁决（2026-09-19）**：M4 采用「分两阶段」——**先做 MA4 任务级恢复**（立刻可在真实链路取证），loop 级留作后续（仅当 legacy loop 继续受支持时才做）；同时授权 G-2/G-3 真实 Run 采集（外部计费已获批）与锁矩阵验证。D-35/DR-111 相应转为「loop 级后续项，随两阶段的第一阶段交付后重估」 | 使用者对三项建议一次性回复「按你推荐的来」。后续执行顺序：M4-A2 收口 → 重界定并派发 MA4 任务级恢复 → 补锁矩阵 `TICK` 用例并跑真实引擎验证（验证 D-33）→ M5。DR-402/403/404/405 的 Worker 侧（loop 级）部分**冻结**，待第一阶段收口后重估是否值得做 |
| D-35 | **DR-111 转为 `BLOCKED`，需使用者做范围决策**，而非继续挂着 | D-28 把 DR-111 挂到「DR-303 落地后再定」。DR-303/304 现已按 D-26 **把冲突能力完整实现在 Java/生产链路**，于是真问题变了：legacy Worker loop（`runner.py`）的 `has_conflict` 分支**位于非生产链路**（生产走 `deep_cell_executor`，不跑 loop/verifier；`run_research_task` 仅被 tests/smoke/benchmark 调用）。因此 §7.2「恢复规则不得散落」在生产链路上**已满足**（冲突规则集中在 Java canonical ledger），DR-111 只在「legacy loop 仍是受支持路径」的前提下才需要做。**把非生产路径的 Locality 当成必须偿还的债，或反过来永久忽略，都是我不该单方面决定的范围问题**——它取决于产品是否继续维护 legacy loop |

## DR-303 复核（2026-09-19，主对话）

执行体：`b-conflict-state`（团队 `deep-research-m3b`，**单成员串行**，本波次并发度 1）。
交付：新增 `ResearchGlobalConflictDetector` / `ResearchGlobalConflictService`；改 `ResearchAgentRunCompletionGate`
（冲突专属 reason）、`ResearchAgentCoordinatorTickService`（tick 起始处先裁决、再 snapshot）、
`ResearchEvidenceQualificationService`（仅把 `LINEAGE` / `registeredDomain` 提为包内 static）；
新增测试类 7 例；**无新增迁移**（V108 保持空闲）。

**主对话独立复跑**：整包 **311 / 0 / 0 / 1**，BUILD SUCCESS；运行前 `Get-Process java` 计数为 **0**。
计数核对：304 + 7 = 311，1 skipped 与基线一致。

**认可的设计决策**：

- **不新增迁移**的论证成立：`research_cell.cell_status` 无 CHECK；`'CONFLICTED'` 已是 legacy Java 读模型
  在用词（非新跨语言词）；轨迹复用 `research_verifier_decision`（`decision_scope='CELL'`、
  `decision_type='GLOBAL_CONFLICT'`、`target_id=cell_key`），按 `(run, type, target_id, reason_code)`
  先查后插实现幂等，测试断言「两次 adjudicate 仅 1 行」。
- **复用 `independentSourceCount` 的同一套 `SourceIdentity`** 定义（同 registrable domain 或同 lineage），
  避免两套「什么算独立来源」的定义漂移。
- **冲突态选 `cell_status='CONFLICTED'`** 的理由经核对成立：`CellState.resolved()` 本就只认 `VERIFIED`，
  故冲突 cell 自动「不可提升」，无需改 `CellState` 记录形状（否则会破坏既有 9 例门禁测试的构造签名）；
  `nonFinalizableCellCount` 本就以 `<> VERIFIED` 统计，冲突 cell 自动阻止 finalize。
- **门禁接入点**：`remainingReason` 新增 `CONFLICTED_CELL` 且先于 `EVIDENCE_MISSING`；终态枚举未扩
  （required 冲突 → `INSUFFICIENT_EVIDENCE`；optional 冲突 → `COMPLETED_WITH_LIMITATIONS`）。
  D-27 满足，且 required/optional 两条路径都有与「无证据」的对比断言。
- **E2E 用例**（tick 全链路：冲突 → CONFLICTED → tick → `RUN_COMPLETED` + `INSUFFICIENT_EVIDENCE`
  + `CONFLICTED_CELL`）是本轮最有价值的证据。
- 执行体在 §6 **主动披露**了「类型化事实无主语锚点」等 7 项边界；主对话正是靠该披露去验算，
  才发现下面的缺陷。**披露行为予以肯定。**

**必改缺陷（→ REWORK，见 D-29）**：`ResearchGlobalConflictDetector` 把
`ResearchTypedClaimValidator.validate(claimA, claimB)` 用于**两条独立 claim 之间**判定互斥。
该方法是**包含性谓词**（「这段引文是否包含该 claim 的全部类型化事实」），设计用途是「claim vs 自身引文」；
跨 claim 使用等价于把「包含」偷换成「相等」。逐行验算的三个反例：

| 输入 | 当前行为 | 正确判定 |
| --- | --- | --- |
| `"2023 revenue was 100"` vs `"2024 revenue was 200"` | `:48-49` 要求 `quote.dates ⊇ claim.dates`，`{2024} ∌ {2023}` → `DATE_VALUE_CONTRADICTION`，叠加 `NUMBER_VALUE_CONTRADICTION` | **不冲突**（分属不同年份，都是真的） |
| `"revenue was 100"` vs `"cost was 200"` | `:56-58` 数值不等 → `NUMBER_VALUE_CONTRADICTION` | **不冲突**（主体不同） |
| `"revenue was 100 in 2023"` vs `"revenue was 100 in 2024"` | 数值相同不触发数值矛盾，但日期上下文不同触发 `:48-49` → `:65 CONTRADICTED` | **不冲突**（取值一致） |

第三条是决定性的：**对两条互相印证、取值一致的证据判冲突**。后果是 `cell_status='CONFLICTED'`
**粘性且不自动消除**（执行体风险 2 自述），而 DR-304 尚不存在——即当前是**无修复路径的永久降级**。
误报代价远大于漏报：漏报只是少一个标记，误报把正确结果判成冲突，并使 M6 评测样本无法解释。

**次要缺陷**：`CONFLICTS_RELATION_WITH_SUPPORT`（:178-191）**未做 `sameSourceIdentity` 排除**，
而 typed-fact 路径（:140-141）做了——两条形状必须一致，同来源内部的支持/反对并存是本地数据质量问题，
这也正是执行体自己写在类注释 :36-39 的原则。

### DR-303 REWORK 复核（2026-09-19，主对话）

执行体按 D-29 重写谓词，交付：`ResearchGlobalConflictDetector` 重写；`ResearchTypedClaimValidator`
仅提可见性（`NUMBER` / `DATE` / `COMPARISONS` / `OPPOSITES` / `facts(...)` 及三个 record 改包内可见）；
测试 7 → **11 例**；`ResearchGlobalConflictService` / 门禁 / tick / RolloutGuard 编译适配均未再动。

**主对话独立复核结论**：

- 整包独立复跑 **315 / 0 / 0 / 1**，运行前 `Get-Process java` 计数 **0**；304 + 11 = 315 成立。
- **`validate()` 语义确实未改**：逐行比对旧 :43-72 与新 :52-81，正文**逐字一致**（位移 +9 由可见性与
  javadoc 解释）；`facts()` 同样逐字一致。此项是 D-29 的核心约束（禁止改局部校验器语义），已实证。
- **新谓词结构正确**：`mutuallyExclusiveCodes` 的两步事实域判定（`sameDateContext` → `skeleton` 相等）
  **先于**取值比较——先判「是否同一件事」，再判「值是否互斥」。这个顺序是修复的关键。
- **4 条负例逐一核对无误**，且每条同时断言 `adjudicateRun == 0` 与 `cell_status` 保持 `CANDIDATE_READY`；
  负例⑤（`"revenue was 100 in 2023"` vs `"revenue was 100 in 2024"`，**取值一致**）正是决定性反例，
  执行体以「修前 4 例失败」的输出留证。
- **新 reason code 与校验器不同名**（`NUMBER_VALUE_CONFLICT` vs `NUMBER_VALUE_CONTRADICTION`）：
  避免「局部包含性矛盾」与「全局互斥」两个不同概念共用一个词，是好的命名决策。
- **第二条形状补齐跨来源配对**（:277-284）与 `hasCompleteSourceIdentity` 过滤（:270），与 typed-fact 路径一致。

**剩余必改（1 行 + 1 例）**：`skeleton()` 未做大小写归一，导致 `"Revenue was 100"` vs
`"revenue was 200"`（同一主体、互斥取值）漏判。大小写在该域不承载语义，属实现瑕疵而非设计取舍。
已要求 `toLowerCase(Locale.ROOT)` + 补 1 例，并在 javadoc 写明「大小写已归一、标点未归一 → 差异漏判而非误判」。

**精度/召回取舍（见 D-30）**：精确骨架相等使 typed-fact 路径**召回很窄**，主对话**有意接受**，
并要求把分工写进 javadoc——召回由 `CONFLICTS_RELATION_WITH_SUPPORT` 形状承担（只依赖 Worker 已判定的
relation 类型），typed-fact 形状定位为高精度兜底。

### DR-303 最终验收与一处裁决（2026-09-19，主对话）

收尾交付：`skeleton()` 增加 `toLowerCase(Locale.ROOT)` 归一；测试 11 → **12 例**；
类 javadoc 补「Recall vs precision — division of labour between the two shapes」一节（D-30 要求）。
主对话独立复跑：整包 **316 / 0 / 0 / 1**，运行前 `Get-Process java` 计数 **0**。**DR-303 → DONE。**

**执行体自行发现的偏差（`reason_code` 语义）→ 裁决：保留其改动**。它发现既有实现把 `reason_code`
记为粗粒度形状种类，而主对话要求的断言是细分码，于是把轨迹 `reason_code` 改为承载细分码
（`NUMBER_VALUE_CONFLICT` / `COMPARISON_DIRECTION_CONFLICT` / `CONFLICTS_RELATION_WITH_SUPPORT`），
粗粒度种类移入 `notes_json.conflict_kind`。主对话查证 `research_verifier_decision` 的既有约定：

| 既存写入方 | `decision_type` | `reason_code` |
| --- | --- | --- |
| `ResearchAgentCompletionCommitter` :747-749 | `QUORUM_REPAIR_REQUIRED`（粗粒度类别） | `decision.reasonCode()`（**细分原因**） |
| `ResearchAgentRoleResultService` :138-139 | `EVIDENCE_AUDIT_PASS` / `..._REPAIR_REQUIRED`（粗粒度类别） | `target.reason_codes`（**细分原因**） |

约定是「`decision_type` 承载粗粒度类别、`reason_code` 承载细分原因」，因此**执行体的原实现才是异类**，
改动后与两个既存写入方一致——见 **D-31**。`persistConflict` :137-155 逐行核对：按细分码各写一行、
幂等键含 `reason_code`、粗粒度种类保留在 `notes_json`，分工正确；cell 更新带 `cell_status <> 'FROZEN'`
守卫；`loadEvidence` 以 `research_evidence_validation.cell_id` ∪ `research_cell_evidence` 两路取证据，
并注明「后者是唯一能承载 `CONFLICTS` 关系的链接，而已提升候选永远不带 `CONFLICTS`」。

**流程偏差（主对话先认领，含审计方自身的错误）**：主对话在任务书里写了列级断言
`reason_code = NUMBER_VALUE_CONFLICT`，**却没有先核实该列的既有约定**——这是审计方的错误。
但执行体在「规格断言与既有数据模型冲突」时**改动了持久化列的语义去满足断言**，属「做完披露」而非
「先报后做」；本次碰巧方向正确，若断言本身有错，就会退化为「为了让测试通过而改数据模型」。
已固化规则：**审计方写规格时给可观测目标而非列级表示（除非已核实 schema）；执行体遇同类冲突须先报后做，
或先实现保守版本并标出冲突点。** 该规则记入「流程约束」。

### DR-304 派发要求（2026-09-19，主对话）

DR-304 由同一执行体继续（它已持有完整冲突模型上下文，换人会造成上下文迁移成本）。要求覆盖：

1. **触发锚定**：对 `decision_type='GLOBAL_CONFLICT'` 且 `decision_status='OPEN'` 的冲突，创建**仅含该 cell**
   的反证任务，上下文携带**冲突轨迹 id + 涉及 evidence ids**——用于打通侦察事实 5 的缺口
   （Worker 侧 `recovery_target_evidence_ids` 被写入但**全仓无消费者**，「反证」当前只限定到
   entity/source/query/column）。
2. **独立计量 + 硬上界 + 显式记录**：反证次数可独立查询、不混入通用 `repair_count`；达上界后必须显式记录，
   **不得静默停止**（静态停止即「永不触发的保护等于伪装」）。
3. **不得失控**：受既有 `ResearchAgentRepairStopPolicy` / `MAX_REPAIR_PER_CELL` 约束，需用例证明无界任务不可能。
4. **解除冲突粘性（DR-303 明确遗留的缺口）**：反证后冲突消解 → cell 离开 `CONFLICTED`、轨迹转 `RESOLVED`、
   门禁不再报 `CONFLICTED_CELL`；额度耗尽仍未消解 → 记为**显式 limitation**，不得永远停在 `OPEN`。
5. **调用账本**：优先用已有持久化数据推导；**禁止**新增 `WORKER_USAGE_KEYS` 计数键（强精确集合，新增即跨语言
   契约变更），若判断非新增不可须**先停手报告**并列出双端文件清单。

## DR-304 复核（2026-09-19，主对话）

交付：新增 `ResearchAgentConflictRepairService` / `ResearchAgentConflictResolutionPolicy`；
改 `ResearchGlobalConflictService`（resolve / reopen / preserve-EXHAUSTED 生命周期）、
`ResearchAgentTaskCoordinatorService`（`CounterfactualTarget` 增冲突锚点 + 专属逻辑键前缀 + 可行探测）、
`ResearchAgentRunCompletionGate`（`CONFLICT_EXHAUSTED` + `CONFLICT_REPAIR_EXHAUSTED`）、
`ResearchAgentCoordinatorTickService`（tick 内调 `advance`）；测试新增 7 例；
**无迁移、无新列、未新增 `WORKER_USAGE_KEYS` 计数键、未新增跨语言词表**（计量由既有
`research_agent_task` + `research_budget_reservation` 推导，专属逻辑键前缀 `conflict-counterfactual:`）。

**主对话独立复跑**：整包 **323 / 0 / 0 / 1**，运行前 `Get-Process java` 计数 **0**；316 + 7 = 323 成立。
`ResearchAgentConflictResolutionPolicy.independentSourceCount`(:88-100) 与
`ResearchEvidenceQualificationService` 的贪心非重叠口径一致，身份定义未漂移；
`strictMajority` :75 的 `groups.size() < 2 → false` 正确。

### 必改①（bug）：收敛投票域越界，真冲突会被静默解除

`adjudicateRun` :111-113 把**整个 cell 的 facts** 交给 `isResolved`，而 `strictMajority`(:65-81)
在**全体 identified 证据**上按 `numberSignature` 分组投票，**从未限定到冲突所在的事实域**。可复现误判：

| cell 上的证据 | 按数值签名分组 | 结果 |
| --- | --- | --- |
| A(alpha) `revenue was 100`、B(beta) `revenue was 200` ← **真冲突** | 100→1 源、200→1 源 | — |
| C(gamma) `cost was 500`、D(delta) `cost was 500` ← **与冲突无关** | 500→2 源 | counts=[2,1,1] → `2 > 1` → **判已消解** |

两条与营收无关的成本证据把营收的真冲突解除了，cell 回到 `CANDIDATE_READY` 后可被提升。
**冲突被正确检测、又被错误清除**——比漏报更严重：漏报是少一个标记，这是把已记录的分歧抹掉。
要求投票域限定为「与冲突同一事实域的证据」（复用 DR-303 的 `sameDateContext` + `skeleton` 锚点，
从 `finding.evidenceKeys()` 取冲突双方导出锚），并补负例固定之。见 **D-32**。

### 必改②：tick 与完成提交器的锁序相反，构成 ABBA 死锁

执行体风险 3 经主对话核实**成立**：`ResearchAgentCompletionCommitter.lockRun` :231-238 先取
`research_run ... for update`，:304-308 才 `lockCells(... for update)` → **run → cell**；
而 tick 先 `adjudicateRun`（`update research_cell` 取 cell 锁）→ 之后 snapshot/advance 才需 run 级锁
→ **cell → run**。并发时 InnoDB 会检测到并回滚其一；被回滚的若是一次完成提交，任务会拖到租约过期，
表现为偶发失败。要求 tick 在取任何 cell 级锁前先取 run 行锁。H2 无法证明死锁不存在，故本条以
**代码级顺序核对**为准，真实引擎验证归入 M4 的锁矩阵门禁。见 **D-33**。

### 必改③：DR-303 E2E 用例的语义漂移须写进测试本身

执行体如实报备：DR-303 的 `shouldAdjudicateConflictsOnTheCoordinatorBarrierWithoutFinalizingTheRun`
如今代表「**反证不可行**的冲突 → 诚实终态」（其 fixture 缺 intent/control_pack 且 `source_scope='[]'`，
`advance` 判不可行），而非「完整 Run 的冲突立即完成」。漂移属实、报备及时、未擅自改动，处理正确。
要求在该用例加注释写明它覆盖的路径，并指向覆盖「完整 Run 冲突先派发反证」的
`shouldDispatchARepairOnTheCoordinatorTickBeforeTheRunCanComplete`。
理由：**用例名与实际语义不符比缺测试更危险**——下一个人会照名字推理。

### 明确接受的四项（不要求改）

- **严格多数、1:1 平局不消解**：接受。「任意新独立来源与一侧一致即消解」等于用抛硬币提升有争议的值。
- **仅在可行时派发**：接受。冲突保持 `OPEN`、不耗额度、不静默停止，Run 以 `CONFLICTED_CELL` 诚实完成。
- **`CONFLICT_EXHAUSTED` 为新 cell status**：接受，`<>VERIFIED` 统计与 `in(...)` 过滤行为正确，登记供读模型知悉。
- **H2 以 SQL 模拟 worker 往返**：接受为已登记的覆盖上限，归入 G-2/G-3。

### 另记（不必改）

`isResolved` :52-57 只处理两类 typed-fact 信号，**关系型冲突永远无法靠投票消解**（关系分歧没有值可比），
其唯一消解路径是异议证据被拒/解绑，故关系型冲突几乎必然走完 1 次反证 → `EXHAUSTED` → limitation。
主对话判定这是**正确**行为（不应用多数票裁决关系分歧），但要求把该性质写进 `isResolved` 的 javadoc，
避免后人误以为收敛覆盖了所有冲突种类。

### DR-304 REWORK 复核（2026-09-19，主对话）

三项必改均落地，主对话独立复跑：整包 **324 / 0 / 0 / 1**，运行前 `Get-Process java` 计数 **0**；323 + 1 = 324 成立。

- **必改①（投票域）修法正确**：`isResolved` :77-99 从冲突对导出锚（`anchorDates` + `anchorSkeleton`），
  :88-91 只对**共享该锚**的证据投票；:83 只读复用 `detector.mutuallyExclusiveCodes` 判定「这两条是否互斥」。
  新负例 `shouldNotResolveAConflictFromEvidenceOutsideTheConflictedFactDomain` 的**修前失败留证**
  （`expected: 1 but was: 0`）与主对话复现的场景逐字对应。
- **必改②（锁序）核实通过**：`select id from research_run where id = ? for update` 是 `adjudicateRun`
  **第一条语句**（:108-109），早于任何 cell 访问（:110 才查 cell）。
  **执行体选择放在 `adjudicateRun` 内而非 tick 起始处，主对话认为优于放 tick**——理由是
  「所有调用方统一获得 run→cell 次序，对将来新增的调用方自动生效」，即把不变量放在最靠近数据的地方；
  :101-107 注释也写明了「为什么」。真实引擎验证仍归 M4 锁矩阵门禁。
- **必改③已完成**：DR-303 的 tick E2E 用例加上语义说明注释，指向覆盖「完整 Run 冲突先派发反证」的用例。
- 关系型冲突不可投票消解的性质已写入类 javadoc（:40-45），表述准确。

**收尾项（→ 见 D-34）**：`ResearchAgentConflictResolutionPolicy.skeleton()` :152-161 与
`ResearchGlobalConflictDetector.skeleton()` :221-230 是**逐字相同的两份实现**，执行体的理由是
「D-29 freezes ResearchGlobalConflictDetector」——该前提不成立。D-29 禁的是**谓词被误用**
（`validate()` 跨 claim），同时**明确要求抽取逻辑从同一来源复用**（`facts()` 提为包内可见，正是同一
执行体上一轮的做法，当时已获认可）。锚（`skeleton` + 日期上下文）是判定层与消解层**共用**的语义原语：
判定层用它决定「能不能比」，消解层用它决定「能不能投票」，必须共用同一份定义。
漂移后果恰是刚修掉的缺陷：检测器 javadoc 自己写了「标点未归一」是将来可能变的点，一旦只改一处，
消解侧锚会**静默**与检测侧不一致 → 投票域重新越界 → 「无关证据多数掉真冲突」以更难发现的形式回归
（检测侧仍正确，只有消解侧错）。

### DR-304 收尾复核：锚收敛为单一实现（2026-09-19，主对话）

主对话独立复跑：整包 **324 / 0 / 0 / 1**，运行前 `Get-Process java` 计数 **0**；与收尾前逐字一致，
无用例增删、1 skipped 未变。**DR-304 → DONE。**

- **policy 侧副本确实删净**：私有 `skeleton` 已移除、`import java.util.Locale` 移除，改为调用
  `detector.skeleton`(:89/:92) 与 `detector.sameDateContext`(:91)。
- **检测器侧只放宽可见性**：`sameDateContext`(:233) / `skeleton`(:257) 改为包内可见，
  `mutuallyExclusiveCodes`(:212-213) 仍用同一份；判定逻辑未动，D-29 未被侵蚀。
- **「语义等价」经逐 case 验算成立**：旧内联 `dates().equals(anchorDates)` 与新
  `sameDateContext(A,B)` 在**全部五种情形**下等价，含最易漏的「一方空、另一方非空」
  （旧 false；新 `!A.isEmpty()` 为 false → false）。不是「看着像」，是逐条对上。
- `independentSourceCount` 的耦合 javadoc(:130-140) 已写实为「**second copy** … must change with it」，
  并说明后果（投票会把门禁不认定为独立的来源算作独立）。

## M3 收口（2026-09-19）

**M3「Verifier 与诚实终态」全部交付。** 计划表格的五项与三件衍生立案项状态：

| ID | 退出条件 | 状态 | 最终证据 |
| --- | --- | --- | --- |
| DR-301 | Candidate 必经验证才能提升 | DONE | Java 独立重算为硬门禁（D-14），Worker verdict 仅作补充信号 |
| DR-302 | 错误类型与伪引文被拒绝 | DONE | 15 码矩阵 + 23 例，主对话独占复跑 |
| DR-303 | 冲突进入明确状态 | DONE | 跨来源冲突显式化 + 冲突轨迹；12 例；**已重新界定为 BE**（D-26） |
| DR-304 | 只对目标 Claim 反证且不会失控 | DONE | 触发锚定 conflict trace + 独立计量 + 硬上界 + 收敛/limitation；8 例 |
| DR-305 | 缺证据可诚实完成，不再落入不透明 barrier | DONE | 业务终态唯一权威 + V107 落库 + D-25 分支 2；9 例 |
| DR-108 | 恢复规则收敛（§7.2 Locality） | DONE | 策略动作 → loop 决策族唯一映射点 + A 项锁定测试 |
| DR-109 | 「无信息重计划保护」死代码 | DONE | 摘要改用不含 `plan_revision` 的内容摘要 |
| DR-110 | Replan 审计接入真实触发点 | DONE | 接线 + D-23 fail-fast + 2 负例（均断言零残留） |

**权威回归**：`com.noteweave.research.*Test` = **324 run / 0 failures / 0 errors / 1 skipped**。
计数构成：267（非本波次基线）+ 23（DR-302/DR-112）+ 9（DR-305/D-25）+ 5（DR-110/D-23）
+ 12（DR-303）+ 8（DR-304）= 324。

**M3 仍缺的材料（诚实登记，不阻塞代码收口）**：

- **G-2 / G-3 需真实 Run**：Replan 审计行与终态列的真实落库样本，以及**冲突能力在真实链路上的
  端到端演示**。真实 MySQL 已在 head(V107)（G-1 已完成），环境就绪，但会产生 LLM/搜索外部计费调用，
  **须使用者确认**。
- **M4 锁矩阵门禁**：D-33 的加锁顺序改动以代码级核对为准，真实引擎验证归
  `ResearchAgentMySqlLockMatrixIT`（`MA4G_LOCK_MATRIX_ENABLED=true`）。
- **DR-111 变为 `BLOCKED`（需范围决策，见 D-35）**。

### 本波次沉淀的方法论（三波次累积）

1. **执行体自述的「残余风险」是最有价值的审计线索**。DR-110（静默降级丢幂等键）、DR-303（把「包含」
   当「互斥」）、DR-304（锁序 ABBA）**三次都命中真实缺陷**，且三次执行体都如实写下却低估严重性。
2. **规格断言不得驱动数据模型变更**（D-31）。审计方给**可观测目标**而非列级表示（除非已核实 schema）；
   执行体遇冲突须**先报后做**。
3. **同一个不变量要在每一层各立一次**。DR-303 在检测层应用了「同一事实域」，DR-304 在消解层又犯了
   同类错误——检测对了不等于下游对了。
4. **「不得修改 X」≠「不得让 X 暴露原语」**（D-34）。判据是「这是不是两层共用的语义原语」，是则必须只有一份。
5. **并发度 1 是本项目已证明有效的默认策略**。本波次全程零并发污染；上一波次（4 执行体并行）产生
   两次假缺陷、一次 75 errors 瞬时红灯。

## D-33 真实引擎验证：阻塞（2026-09-19，主对话操作导致环境故障）

主对话按 D-33 的安排去跑 `ResearchAgentMySqlLockMatrixIT`（真实 MySQL 8.4），
入口为 `scripts/ma4g/run-round.ps1 -Round LockMatrix -ProjectName noteweave-ma4g-lockmatrix-local`
（**独立 project**，不碰开发库；开发库 `mysql:8.4` 与 IT 的版本断言本来是匹配的）。

**结果：失败，且失败在环境层而非测试层。**

- `docker compose` 拉起了完整隔离栈（mysql + redis + kafka + backend + research-worker + lock-matrix-test），
  MySQL 已 `Up (healthy)`，随后 **Docker Desktop 引擎开始对所有请求返回 HTTP 500**。
- 失败码 **exit=125**（`docker` 命令本身失败，非测试失败）；清理用的 `compose down`
  与 `docker version`、`docker ps` **同样 500**——即引擎整体不可用，不是某个容器的问题。
- 等待 45 秒后复查仍未恢复。**当前 `noteweave-v2-*` 开发容器状态无法确认，开发环境不可用。**

**判定与责任**：这是**主对话的操作**造成的——一次性拉起六服务重型隔离栈压垮了 Docker Desktop。
属于「未先评估环境承载能力就执行重型 compose」的操作失误，与代码无关，已如实记录。

**未做的事（重要，避免误读）**：
- **没有**把这次失败算作「锁矩阵 IT 不通过」——它是环境故障，IT 本身一行都没跑到。
- **没有**在 D-33 上打勾。D-33 目前仍只有「代码级加锁顺序核对」这一层证据，
  **真实引擎验证依旧是空白**。
- 该 IT 的 13 个存量用例（A–L）**也不覆盖 `tick`**，所以即便跑通也验不了 D-33；
  仍须先补一个 `TICK` 场景。

**后续（需要使用者知晓/决策）**：
1. 恢复 Docker Desktop（重启 Docker Desktop 或整机），恢复后我再确认 `noteweave-v2-*` 是否完好；
2. 若要继续跑锁矩阵：建议**先单独只起 mysql + lock-matrix-test**（不要带 backend/worker/kafka/redis），
   或加大 Docker Desktop 的内存/CPU 配额后再跑；
3. 补 `TICK` 用例后再跑，才能真正验 D-33。

## M4 前置侦察（2026-09-19，主对话派发的只读侦察体）

### 事实：M4 的六项「恢复所需最小状态」全部缺失或半实现

| 项 | 状态 | 现有承载 |
| --- | --- | --- |
| 同 Run 换代身份（attempt / fence） | **缺失** | `research_execution_attempt` 全仓 0 命中；`research_run.run_version/attempt_no/fencing_token`（V071）**零读写**（main 仅迁移自身命中）；`research_agent_checkpoint` 无 `attempt_id/run_fence_token` 列 |
| 外部调用收据（Operation-Key 粒度） | **缺失** | `research_external_operation_receipt / operation_key / receipt_status` 在 research 链路 0 命中；替代物只有内容归档 `research_external_snapshot`（V079，唯一键是 **task 级** `(task_id, window_id, source_id)`，非 Run 级）+ permit + completion 聚合计数。DR-404 的 INV-1 当前**无断言对象** |
| Run 级预算账本 | **缺失** | 只有 task 级 `research_budget_reservation`；hydration 只把源摘要塞进 `budget_summary_json={"restored_source":…}`，不继续记账 |
| 阶段屏障 | **部分** | 唯一 stage 常量 `CELL_RESEARCH`；`checkpoint` 行上**无 barrier 列**，两者无先后约束；门禁完全不读 `research_run_stage`；hydration 强制 status=`HYDRATED`（非法值）且 `barrier_json` 仍指向**源 run** → 目标 Run 的 barrier 无法由自身状态重算 |
| cell 完成水位线 | **部分** | 三个 HWM **只写、只防单点回退、无任何调度语义**；`CoordinatorRecoveryService` 重新现算 `count(*)` 而不读 HWM；hydration 写 0/0/0 |
| hydration 幂等 / fail-closed | **缺失** | `hydrate` 无幂等键、无 attempt 校验、每次 `Ids.newId()` 全量复制；`research_verifier_decision` **无唯一键** → 部分重放会**静默重复**；`AUTO` 不可用时**静默回落 `CONTEXT_RESTART`** |

### 事实：D-21（`cell_recovery`）两侧均无法满足，缺三处

Java 侧**从不**写 `cell_recovery`（全仓 `cell_recovery|cellRecovery` 命中全在 `workers/`）；
Worker 侧 `_restore_resume_context` 读取的键里**没有** `cell_recovery_trace` / `cell_recovery_directives`
（尽管写侧 `loop_runtime.py:714/:716` 确实写了）。且 `ResearchResumeCheckpointPayload` 这个 record
**没有任何生产者**（Java main 全仓 `resume_checkpoint` 0 命中）→ **[推断] Worker 侧 restore 路径在生产链路上不可达**。
满足 D-21 需**同时**做三件事：Java 生成并携带 / Worker 读取 / 兜底调用传入 `recovery_outcome`。

### 事实：设计文档 `docs/research/Research-Checkpoint-Schema设计.md` 有过期项

- `:246` 「当前最大迁移版本为 V104」→ 实际 **V107**；`:258-261` 的 V108/V109/V110/V111 仍可用（**M4 自 V108 起**）。
- `:251` 「V107 预留给 DR-305」→ **已实装占用**（终态五列 + 索引）。
- `:265` 回填策略写「V107 从 `research_budget_reservation` 聚合」→ 与 §5 表的 V110 分配**自相矛盾**。
- `:322` / `:328` / `:356` 三处仍按 V105/V106/V107 分配 M4 新表 → **全部过期**（`:356` 还与 `:253` 自相矛盾）。
- `:61` 的 CAS 行号已偏移（task 终态 CAS 现于 `Committer:200-209`；cell/merge CAS 现于 `CellMergeService:122/:202`）。
- `:56` V071 三列「无任何读写」**复核成立**；`:31`/`:35`/`:49`/`:55`/`:59`/`:62`/`:232`/`:239` 仍正确。

### 事实：验证手段严重不足

- **锁矩阵 IT 从未跑过**（`MA4G_LOCK_MATRIX_ENABLED` 门禁），且 **13 个用例里没有任何一个涉及 `tick`**
  （唯一被竞态的是 `planAndEnqueue`）→ **D-33 的真实引擎验证仍是空白**，且需新增 `leader_action='TICK'` 用例。
- **hydration 等价性测试一条都没有**：设计文档 §6 的 EQ-01..EQ-12 全未实现；最接近的两处
  （`ReplanAuditDiscoveryWiringTest.checkpointHydrationShouldReplayThePlanWithoutAReplanAuditRow`、
  `ResearchRunCommandServiceTest` 的 resume 两例）都只断言「行被复制」，不断言「恢复前后行为一致」。

### 事实：实施 M4 的高风险耦合（前三条是硬伤）

1. **恢复即「零工作量完成」**：hydration 原样复制 VERIFIED cell 且带 `evidence_refs_json`，
   而 `readyForFinalization()` = `verifiedCellCount>0 && nonFinalizableCellCount==0` →
   恢复出的 Run **第一个 tick 就会 `RUN_FINALIZED`**。该行为必须被**显式设计并断言**，不能是碰巧如此。
2. **`plan_revision / entity_set_version` 兜底为 0 会「卡死但不报错」**：task 的这两列取自 cell 行，
   cell 绑定 CAS 又要求 task 与 cell 完全相等；hydration 用 `nonNegativeInt(...,0)` 兜底
   → 源值缺失时该 Run 的 cell **永远无法绑定 task**。
3. **阶段屏障 `HYDRATED` 不在合法取值集合**（合法值 `ACTIVE/BARRIER_PENDING/SETTLED`），且无读者。
4. 终态字面量在 **9 处**硬编码；cell 状态 `in(...)` 过滤在 **4 处**（新增状态必须同步）。
5. **预算键集合被双重锁死**（Worker `agent_task_client.py:457-459` 的精确集合校验 + 锁矩阵 IT :927-929）
   → DR-405 的 Run 级账本**必须新表/新字段，不能改现有键集合**。
6. `research_agent_checkpoint` 加列必须 nullable/有默认值（`ReplanAuditDiscoveryWiringTest:327-337`
   按显式列清单插入）。
7. `hydration` 是**双开关且默认关**（全局属性 + 源 Run 快照 flag）→ 任何恢复 E2E 证据必须显式打开两层开关；
   且 `hydrate()` 自身**不再校验 `enabled`**，绕过 `available()` 的调用方会静默恢复。

### 裁决

| 编号 | 裁决 | 理由 |
| --- | --- | --- |
| D-36 | **M4 的验收标准依赖 legacy loop，须与 D-35 一并做范围决策**；在决策前**不派发** DR-402/403/404/405 的 Worker 侧部分 | 计划 §9 验收矩阵把 M4 写成「抓取完成后 **Worker 退出** → 新 Attempt 复用抓取收据，从后续阶段恢复」——这要求 Worker 在 Run 内跑多阶段（loop）。但 D-35 已确认生产走 `deep_cell_executor`（**单任务内** search→fetch→read→extract，不跑 loop），且 D-21 的侦察证实 Worker 侧 restore 路径在生产**不可达**（`resume_checkpoint` 无生产者）。**这是同一结构问题的第三次出现**（D-26 的 DR-303/304、D-35 的 DR-111、现在的 M4），因此不再逐任务临时重界定，而是把「哪条链路是受支持的研究链路」作为**一次性范围决策**交给使用者 |
| D-37 | **先派发与链路选择无关的 Java 侧 hydration 正确性**（M4-A），不等范围决策 | 侦察发现的三条属于**静默降级**类缺陷，与走哪条链路无关：① `hydrate` 无幂等键且 `research_verifier_decision` 无唯一键 → 部分重放**静默重复**；② `AUTO` 不可用时**静默回落** `CONTEXT_RESTART`；③ `hydrate()` 不校验 `enabled`，绕过 `available()` 即静默恢复。加上「恢复即零工作量完成」必须显式化、`plan_revision` 兜底 0 会导致永久无法绑定 task——这些都必须修，且修它们不会因链路选择而白做 |

## M4-A 交付与复核（2026-09-19，主对话）

执行体 `b-hydration`（团队 `deep-research-m4`，单成员串行）。范围：D-37 定的「与链路选择无关的 Java 侧
hydration 正确性」六项。主对话独立复跑：整包 **331 / 0 / 0 / 1**，BUILD SUCCESS，运行前后
`Get-Process java` 计数 **0**；324 + 7 = 331 成立。

交付：`ResearchAgentCheckpointHydrator` / `ResearchAgentRunnableWorkService` / `ResearchRunCommandService` /
`ResearchRunResponse`；新增 **V108**（四列 nullable + 唯一索引）；新增两个测试类（7 例）；
`ReplanAuditDiscoveryWiringTest` 改 2 处（补授权，**升级预期**、断言未改）。

**六项落地方式（经核对）**：

1. **幂等/重放**：以 `(源 run, 源 checkpoint_seq)` 为显式幂等键，落库到 `research_run.hydrated_from_*`
   （V108 nullable）；`hydrate` 命中即抛 `RESEARCH_CHECKPOINT_HYDRATION_REPLAY`；`claimHydration()`
   在**任何 ledger 行复制之前**先声明，失败整事务回滚；并发竞态由唯一键兜底并映射到**同一个**稳定码。
   不依赖「被复制表碰巧有唯一索引」——`research_verifier_decision` 只有普通索引（V014:84），
   测试用 OPEN decision 证明旧实现会重复、新实现零新增行。
2. **AUTO 降级显式化**：新增 `resume_mode_reason`（nullable），三通道可区分（持久化列 / `ResearchRunResponse`
   新字段 / trace + 日志）。测试断言两条路径 reason 不同且持久化值一致。
3. **`hydrate()` 自校验开关**：选「hydrate 自身校验两层开关」而非「显式确认授权来源」，理由是两层开关与
   `available()` 是**同一谓词**、不存在第二权威可漂移；失败码 `RESEARCH_CHECKPOINT_HYDRATION_DISABLED`，
   在任何 DB 读取前抛出。**判断合理。**
4. **「零工作量完成」**：选 (a)（canonical ledger 是权威），并把来源痕迹做实为三处
   （`hydrated_from_*` / genesis checkpoint 的 `summary_json` / `resume_mode`）。——见下方 R-1。
5. **`plan_revision` / `entity_set_version` 兜底 0 改为显式失败**：新增 `requiredNonNegativeInt(...)`，
   仅对这两列生效，缺失/非数字/负数抛 `RESEARCH_CHECKPOINT_HYDRATION_IDENTITY_MISSING`，
   且 payload 级校验被提到**任何复制之前**。
6. **阶段屏障**：选「在目标 Run 上重算 barrier」而非「把 `HYDRATED` 纳入合法集合」，理由是全代码库
   **没有任何**按 `research_run_stage.status` 过滤的读者，补读者等于新造行为。重算所需的
   `runnableCellCount / blockedCellCount / barrierDigest / STAGE_VERSION` 从
   `ResearchAgentRunnableWorkService` 提为**包内静态**复用（避免镜像漂移，与 D-34 同构），
   `barrier_json.run_id` 指向 descendant，`expected/settled_task_count` 归 0（不复制执行面 task，不得撒谎）。
   **方案与理由均成立。**

### R-1：主对话的规格前提有误，执行体的读码发现更严重的真相（→ D-38）

主对话在任务书第 4 项写「恢复出的 Run 第一个 tick 就会 `RUN_FINALIZED`」。执行体读码后指出该前提与实现
不符，主对话逐行核实**确认执行体正确**：`ResearchAgentCoordinatorTickService` 的 `:68`（`taskCount() == 0`
→ `INITIAL_WAVE_TASKIZED`）**先于** `:93`（`readyForFinalization()`）返回；hydration 不复制
`research_agent_task`，故 descendant `taskCount()==0` 恒真；而 `planAndEnqueueForWave` 只选
`GAP/STALE/CANDIDATE_READY`（VERIFIED 被排除），全 VERIFIED 时创建 0 个 task。
**真实后果不是「立即完成」，而是「每 tick 返回同一 outcome 且永不推进」（livelock）——
`resumeFromCheckpoint` 恢复出的 Run 永远无法终结**，且 `readyForFinalization()` 在该 Run 上**永远读不到**。

执行体的处理**完全正确**：既按第 4 项实现并断言了快照级语义，又**没有**擅自改 tick 分支顺序
（确实超出其范围），而是把它作为需裁决项上报。已派发 **M4-A2** 消除该 livelock（见 D-38）。

### 主对话自身偏差（与 D-31 同类，本次是审计方）

D-31 立的规则是「规格里的**断言**不得驱动数据模型变更、且须先核实 schema」。本次暴露同一规则的缺口：
**规格里的「前提」也必须先核实**——主对话把一条未经自己验证的侦察结论（「第一个 tick 就会完成」）
当作既定前提写进任务书，而真相比它严重一个量级（livelock vs 立即完成）。
**已固化：凡任务书中的「事实性前提」，必须由主对话先自行验证或标注为「待执行体核实」，不得直接引用
侦察报告的原话作为既定事实。**

## G-2 / G-3 真实 Run 采集（2026-09-19，使用者已授权外部计费）

主对话用真实 API 发起 Run（**非单元测试、非模拟**），并直接从真实 MySQL 取证。
入口：前端 nginx `127.0.0.1:3000` → `/api/v2/**` → backend（backend 自身端口未映射宿主）。

**Run 标识**：`1978c185-bd1a-42c8-8b8e-41eab78637cd`（workspace `c805f003-331a-438c-bab4-e5801614e490`，
专用采集用户 `dr-g2g3-probe`）。问题为评测集同类的对比型问题。结果：`COMPLETED` / `INCREMENTAL_V1`，
2 个 `DEEP_CELL` 任务均 `SUBMITTED`，5 条证据，cell 分布 `VERIFIED×1 + GAP×3`。

### 已取得的真实样本

| 项 | 真实落库内容 |
| --- | --- |
| **G-3 终态列（V107 五列）** | `completion_terminal_state=INSUFFICIENT_EVIDENCE`；`completion_reason_codes_json=["INSUFFICIENT_EVIDENCE"]`；`completion_unresolved_cells_json` 逐 cell 带 reason_code（`subject:key_evidence` → `REQUIRED_CELL_UNRESOLVED`；`subject:implications` / `subject:limitations` → `OPTIONAL_CELL_UNRESOLVED`）；`completion_limitations_json=[]`；`completion_promotable_claims_json=[]` |
| **DR-305 诚实终态** | 该 Run 未跑满却以 `INSUFFICIENT_EVIDENCE` **诚实完成**，而非落入不透明 barrier——**取得真实证据** |
| **DR-405 预算守恒（意外收获）** | `research_budget_reservation` 两笔均 `SETTLED`，**十个键全部守恒**：`evidence_cards` 12 = 1(consumed) + 11(released)；`candidates_submitted` 3 = 1 + 2；`candidate_merges_accepted` 3 = 1 + 2；`candidate_merges_rejected` 3 = 0 + 3；`llm/search/fetch/read/extract_calls` 均 1 = 1 + 0。**这是「重启不重复扣费」所需的账本基线** |

### 同时暴露的三个真实缺陷（均非测试可见）

1. **Worker 容器是旧镜像**：2 条 completion 的 `schema_version` 均为 **`research-agent-completion.v1`**（应为 v2 + `extraction_diagnostics`），因此
   `research_agent_execution.extraction_diagnostics_json` **为 NULL** → DR-102 的真实落库样本（M1 遗留项）**拿不到**。
   根因：只重建过 backend，未重建 `research-worker-consumer`。**下一步：重建 Worker 镜像后重跑。**
2. **一次完整跑完的 Run 写了 0 个 checkpoint**：`research_agent_checkpoint` / `research_checkpoint_hydration_snapshot` /
   `research_run_stage` **全部为 0 行**。这在真实链路**直接证实**了 M4 侦察的结论——
   checkpoint 仅在 `failedTaskCount>0 || verifierRepairRequiredCount>0` 分支写入，成功路径**没有任何写入点**。
   M4 的「任一阶段中断均有最近安全点」（DR-402 退出条件）当前在真实链路上**不成立**。
3. **G-2 未取得**：`research_agent_replan_audit` 为 0 行（该 Run 未发生 discovery 修订）。
   需要一次能触发 Discovery 修订的 Run 才能取到审计行样本。

### 排查过程中排掉的一个假警报（记录以免重跑）

Run 终态是 `INSUFFICIENT_EVIDENCE`，一度疑似「LLM 不可用被当成证据不足」（基线 `31d1d44f` 的老坑）。
查 `usage_json`：`llm_calls=1`（两个 execution 都是 1），LLM 正常；且 5 条证据、1 个候选被接受。
**判定：终态属实，不是基础设施失败被错分。** 但「4 个 cell 只派发 2 个 task 就停止」仍需确认是否有依据
（预算/轮次限制 vs 提前停止），列为后续项。

### 第二次真实 Run（重建 Worker 镜像后）：DR-102 抽取诊断样本已取得

Run `4c51a20f-c0ea-4c94-a321-cbc8c49b5fd7` → `COMPLETED` / `INSUFFICIENT_EVIDENCE`，cell `VERIFIED×2 + GAP×2`。

- **`schema_version = research-agent-completion.v2`**（新 Worker 生效），**2 条 `extraction_diagnostics_json` 落库**。
- 真实诊断内容（节选）：`accepted_count=2`、`rejected_count=2`、`rejection_counts={"NON_EXACT_QUOTE":2}`、
  `termination_reason=ACCEPTED_CARDS`、`provider_receipt={model:"glm-5.3-flash", purpose:"research.extract",
  call_count:1, response_chars:1640, response_digest:"sha256:63ee95cd…"}`，
  `rejection_samples` 两条均带 `window_id` / `column_key` / 有界 `detail`，**不含模型原文**。
- **这份样本的价值**：真实模型（`glm-5.3-flash`）返回的卡里有 **2 张因 `NON_EXACT_QUOTE` 被拒、2 张被接受**——
  DR-101/DR-103 建立的「零卡原因可解释」在**真实 LLM 响应**上得到验证，而不只是测试夹具。
  这正是 M1 当初要的证据：非法 JSON / 未知 window / 错误列 / 非精确引文在现实中确实会混在一起，
  现在它们在库里是**结构化可分辨**的。

### 仍缺的真实证据（不在本轮强求）

- **G-2（Replan 审计行）**：两次 Run 的 `research_agent_replan_audit` 均为 0 行，需一次触发 Discovery 修订的 Run。
- **冲突能力端到端**：两次 Run 的 `research_verifier_decision` 均为 0 行（对比型问题未产生跨来源互斥）。
  DR-303 的 typed-fact 谓词按 D-30 是**窄召回**的高精度兜底，单次真实 Run 命中概率低；
  **合适的取证场合是 M6 的固定评测集**（评测集含 6 例「冲突」类），届时用 24 题一次性跑完，不在本轮赌单次 Run。

### 环境操作记录（便于复用与回滚）

- 采集入口：`POST http://127.0.0.1:3000/api/v2/auth/register` → 取 `access_token` → `POST /api/v2/workspaces` 建工作台 →
  `POST /api/v2/workspaces/{ws}/research-runs` 建 Run。**curl 在本环境不可用**（PowerShell 会吃掉引号导致
  `MALFORMED_REQUEST`），改用 `Invoke-RestMethod`。
- 已写入真实库：1 个用户 `dr-g2g3-probe`、1 个工作台、1 个 Run 及其全部产物。**若需清理，删除该 workspace 即可级联。**

## M4-A2 交付与复核（2026-09-19，主对话）

范围：消除「0 task 且无可调度工作」时的 livelock（D-38）。主对话独立复跑：整包
**334 / 0 / 0 / 1**，运行前后 `Get-Process java` 计数 **0**；331 + 3 = 334 成立。
改动仅 1 个主代码文件（`ResearchAgentCoordinatorTickService`）+ 1 个新测试类（3 例）；
**既有断言零变动**（0 条升级预期、0 条行为回退）。

**主对话逐项核实**：

- `TERMINAL_BARRIER_PENDING` **全仓零引用**（代码/测试/docs），删除无副作用。
- `readyForFinalization()` = `verifiedCellCount > 0 && nonFinalizableCellCount == 0`
  （`ResearchAgentCoordinatorSnapshotService:61`）→ **确证不可达性论证**：`nonFinalizableCellCount==0`
  ⟺ 全部 cell 已 VERIFIED；只要存在 cell 就 `verifiedCellCount>0`。故走到末端的唯一剩余情形是 0 cell，
  而 `bootstrap` 恒插入 rows×columns 个 cell → 生产不可达。
- 分支结构逐行对上：`:74` 仅在 `createdTaskCount()>0 || idempotentReplayCount()>0` 时返回
  `INITIAL_WAVE_TASKIZED`；`:83` 落穿前重取快照；`:120-135` 无条件由门禁裁决；
  `:63` / `:85` / `:102` / `:114` 未动，`adjudicateRun → advance → snapshot`（`:58-62`）原样。

**执行体超出任务书的设计判断（删除 `:111` 的 `taskCount() > 0` 前置）——主对话认可**：

- 门禁是「已持久化规范状态的纯函数」，四个 `load*State` **全都不读 task 计数**——
  「该 Run 是否曾创建过 task」不能决定「剩余 cell 能否被诚实终结」。
- 该条件**恰好**排除了两类 livelock Run（hydrated ledger、全冲突 ledger，二者 taskCount 恒为 0），
  保留它等于保留缺陷。
- 对 `taskCount() > 0` 的 Run 行为**恒等**（`activeTaskCount()==0` 在 `:63` 之后已恒真），
  只新增 `taskCount()==0` 家族的语义——这解释了为何既有断言零变动。

**三条测试**：① hydration 出的全 VERIFIED Run → `RUN_FINALIZED` + `COMPLETED_VERIFIED`；
② `taskCount==0` + cell `CONFLICT_EXHAUSTED` → `RUN_COMPLETED` + `INSUFFICIENT_EVIDENCE` +
`CONFLICT_REPAIR_EXHAUSTED`，并断言**未凭空造 task**（`taskCount` 仍 0）；
③ 新 Run 的 GAP cell 仍正常 `INITIAL_WAVE_TASKIZED` 且创建 task。

**登记项**：R-5（0 cell 的 Run 现以 `INSUFFICIENT_EVIDENCE` 终结，生产不可达但属刻意收窄）；
R-6（`INITIAL_WAVE_TASKIZED` 语义收窄为「引导真的创建了工作」，代码侧已核查调度器不使用 outcome
字符串，但外部面板若依赖该 outcome 需知悉）。**M4-A2 → DONE。**

## MA4 第一阶段已派发（2026-09-19）

执行体 `b-task-recovery`（团队 `deep-research-m4`，单成员串行）。语义按 D-39 重界定为
「**同一 Task 跨 Execution**」而非「同 Run 跨 Worker」：DEEP_CELL 任务被重新领取（lease 过期 /
worker 退出）后，已归档外部内容不得重复抓取计费、旧 execution 延迟回调被拒且不改变 canonical state、
预算不重复扣费。退出条件与证据产物沿用计划（故障注入日志 + 账本断言）。

**任务书要求「先取证、再改」**：先用测试证明**当前**重新领取时是否重复执行 provider 调用、
是否重复计费、旧回调是否被拒；**若三项当前都已正确，交付物就是证据 + 明确的「无需改动」结论，
不得为了「做了事」而改代码**。硬约束：不改跨语言契约与预算键集合（新字段自 **V109** 起，
V108 已被 M4-A 占用）、不改 M3 判定器、不改 M4-A2 刚定的 tick 分支与时序、不触碰 loop 级恢复。

## 主对话裁决记录

| 编号 | 裁决 | 理由 |
| --- | --- | --- |
| D-1 | 非法 `relation_type` 从「静默降级为 `WEAK_SUPPORT`」改为「拒绝并记录 `UNSUPPORTED_RELATION`」 | 架构文档「Evidence Qualification」明确要求错误 Relation 拒绝；静默降级会让伪引用以 `WEAK_SUPPORT` 进入候选 |
| D-2 | 抽取诊断沿 `research-agent-completion.v2` 新增第三种形态（非 `ROLE_RESULT` + 必填 `extraction_diagnostics`），而不是新增独立上报接口 | 诊断必须与 completion 同事务，避免「completion 已提交但诊断丢失」的窗口；v1 与 v2-role 形态保持不变以保证历史重放可用 |
| D-3 | `V105` 归 DR-102，M4 迁移自 `V106` 起 | 避免两个任务占用同一迁移号 |
| D-4 | `accepted_count == 0` 时 `termination_reason` 不得为 `ACCEPTED_CARDS`（双侧硬门禁） | 这是「零卡原因可信」的最小充分条件；没有它，持久化的原因码只是装饰 |
| D-14 | DR-301 的提升门禁采用「**Backend 独立重算为硬门禁**，Worker verdict 仅作补充信号」：`planMergeOutcome` 必须把 `evidenceQualification` 纳入接受判据；Worker 的 `CellVerifier` 四态 verdict 随 completion 提交供审计与 `VERIFIER_DISAGREEMENT` 比对，但**不单独决定提升** | ① 架构文档：Java 拥有 Canonical Ledger，Worker「不直接更新 Cell Current Value」；② 「Global Verifier 从 Canonical Facts 独立重算，不复制 Local Result」；③ Worker verdict 依赖 LLM 不可复现，不能作为硬门禁；④ 若让 Worker verdict 单独决定提升，等于让 Worker 间接获得业务写权限，违反角色边界 |
| D-15 | `VERIFIER_DISAGREEMENT`（Local 与 Global 结论不一致）只阻止**无条件成文**，不阻止 Cell 提升 | 架构文档原文是「两者不一致保留 `VERIFIER_DISAGREEMENT` 并阻止无条件成文」，成文属报告层；把分歧下沉为提升门禁会与 D-14 的硬门禁重复且互相矛盾 |
| D-23 | DR-110 的 `recordReplanAudit` 提前返回改为 **fail-fast**（`RESEARCH_REPLAN_AUDIT_PLAN_MISSING` / `RESEARCH_REPLAN_AUDIT_NO_AFFECTED_CELLS`），不允许「写计划行但不写审计行」 | 该提前返回绕开了审计服务刻意强制的契约（`requireAffectedCells(空)` 与 `requireDigest(null)` 本就抛错），并且丢掉 `(run, plan_revision_to)` 幂等撞键，使该分支下重放会无界累积计划行。同方法内已有 `RESEARCH_DISCOVERY_PLAN_BOUNDED` 抛错回滚的先例。**宁可整次提交失败，也不产生无审计的作用域变更**——与 M2/M3 一贯的「禁止静默降级」一致 |
| D-24 | `validateBinding` 的 `evidence.isEmpty()` 分支**不修代码**，改为「固化不变量 + 就地注释」；派发 DR-112 | 该分支**可证不可达**：`Canonicalizer:262` 强制 `evidenceKeys()` 非空、`:230-233` 强制引用不越出 envelope（且拒绝重复 key）、`:222` 强制 evidence key 唯一，故 `evidenceByKey.get()` 不返 null、`bound` 不可能为空。补「candidate-only validation 行」需要 `evidence_id` 可空 + `persist` 改造，会把**不可达代码变成永久不可测代码**，比现状更差。正确做法是把使该分支不可达的不变量变成 CI 门禁，并在分支处写明「一旦上游放宽，此 rejection 无法落成 validation 行、`rejectedCandidateKeys()` 将看不到它」 |
| D-25 | DR-305 分支 2（`COMPLETED_VERIFIED`）在 infra 失败时必须记录 infra 原因码，**终态不变** | 执行体的修复 #2 只覆盖分支 3/4，分支 2 会丢失基础设施根因，与其自述意图不一致；该分支可达。终态不降级：全部 cell 已 resolved 是当时最强的真命题，不应因一次**后来的** provider 故障被改判 |
