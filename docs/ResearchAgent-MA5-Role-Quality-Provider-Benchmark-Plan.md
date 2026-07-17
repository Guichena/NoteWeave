# Research Agent MA5：角色质量、高风险双候选与 Provider A/B 执行方案

> 文档状态：IMPLEMENTED / DISTRIBUTED QUORUM VERIFIED / EXTERNAL PROVIDER EVIDENCE PENDING  
> 日期：2026-07-17  
> 前置阶段：MA4A-J 已在 deterministic fake-provider + MySQL/Kafka 隔离证据范围完成  
> 唯一启用条件：Run 的 `agent_execution_mode=INCREMENTAL_V1`  
> 边界：本阶段补质量策略和可复核评测，不承诺生产灰度，也不在缺少真实 Provider 凭证时声称质量或 p95 提升

## 1. 阶段目标

MA5 要回答的不是“能否启动多个 Worker”，MA4 已经回答了这一点；MA5 要回答以下三个质量问题：

1. Wide、Deep、Counterfactual、Verifier 是否拥有不同且受控的执行画像，而不是只换一个 role 字符串。
2. 高风险核心 cell 是否能在不互相泄漏候选答案的前提下形成两个独立候选，并由单写 Verifier 决定合并。
3. sequential、parallel、speculative 是否能在相同输入、模型、来源策略、预算和随机策略下公平比较，并留下可审计原始记录。

简历项目范围内保留最有解释力的机制：不可变任务、候选隔离、证据绑定、quorum、盲验、公平 A/B、自动恢复和审计。明确不实现多地域调度、leader election、跨集群复制或复杂实验平台。

## 2. 当前基线与缺口

当前已有：

- `AgentRole`、不可变 `CellTaskBundle`、candidate、Merge Gate；
- 单进程受限并行和 MA4 分布式 DEEP_CELL 执行；
- entity/cell/row F1、exact-table、citation、counterfactual 等离线指标；
- Worker 独立的 `NOTEWEAVE_RESEARCH_LLM_*` 配置和 LLM usage ledger；
- 自动 wave/checkpoint/repair/finalization。

当前仍有两类边界：其一是外部证明，当前进程的 Research LLM key/base URL/model 均未配置，真实 Provider 退出门尚不可执行；其二是外部网页证据尚未纳入 v2 原子 completion 的 URL/snapshot authority contract。因此当前自动主链要求至少一份已解析 workspace source。角色画像、分布式双候选 taskization、匿名盲验、benchmark manifest/archive/comparator 和 429/5xx 记账均已落地并通过 deterministic 测试。

## 3. 冻结的公开测试 seam

### 3.1 `RoleProfileCatalog.resolve(role)`

输入正式 Agent role，输出不可变 `RoleProfile`。公开行为包括：

- 每个角色有独立 `profile_key/model_purpose/temperature/tool set/source policy/budget ceiling`；
- Deep 与 Counterfactual 可 search/fetch/read/extract，但 Counterfactual 强制独立来源；
- Verifier 不允许 search/fetch，也不能获得另一个候选的生成上下文；
- 不支持的 role fail closed。

### 3.2 `BlindCandidateVerifier.verify(task, candidates, evidence)`

输入高风险 task、候选和 evidence，输出确定性 `BlindVerificationDecision`。公开行为包括：

- `candidate_quorum=1` 维持普通 cell 单候选路径；
- `candidate_quorum=2` 必须来自不同 `candidate_slot/execution_id`，且至少有两个独立来源域；
- Verifier 输入只含匿名候选 ID、value 和 evidence，不含 Worker、role profile、生成顺序或对方 reasoning；
- 相同规范化值且证据满足时接受；冲突、同域伪 quorum、证据缺失时拒绝并触发 repair/counterfactual；
- 任意输入顺序产生同一决策。

### 3.3 `BenchmarkRunner.run(profile, case)`

输入冻结的 `BenchmarkProfile` 与 `BenchmarkCase`，通过显式注入的运行边界执行一次 rollout，输出 `BenchmarkRecord`。公开行为包括：

- manifest 固定 question/schema/source policy/provider/model/budget/random policy/gold version；
- sequential/parallel/speculative 只允许 execution mode 不同；
- 单次记录质量、wall-clock、调用量、token、cost、retry、429/5xx 和 termination；
- 每次 rollout 写 immutable JSON；聚合器要求每个 mode 至少四轮，条件不一致则拒绝比较；
- 没有真实 Provider 时只允许标记 `SIMULATED`，不得生成 `QUALITY_IMPROVED` 结论。

这些 seam 是本阶段 TDD 的固定测试边界。

## 4. 角色画像

| role | 目标 | 工具 | 默认策略 | 主要门控 |
| --- | --- | --- | --- | --- |
| `WIDE_DISCOVERY` | 找全实体与候选来源 | search/read | 较宽 query，低单来源深度 | entity freeze 前运行，不写 canonical cell |
| `DEEP_CELL` | 针对冻结 cell 深读取证 | search/fetch/read/extract | 低温度，primary-source 优先 | evidence-bound candidate only |
| `COUNTERFACTUAL` | 主动寻找反例和冲突 | search/fetch/read/extract | 排除原候选来源/域 | 独立来源不足不得计票 |
| `CELL_VERIFIER` | cell 候选裁决 | read | temperature 0，匿名输入 | 无 search/fetch，无 canonical 直写 |
| `GLOBAL_VERIFIER` | wave/report 放行 | read | 聚合 ledger，不看生成者身份 | 只消费 durable candidate/evidence |

画像决定预算上限和工具权限，但不会在代码中硬编码真实 secret。Provider/model 仍由 Worker 独立环境配置提供，profile 只选择 purpose 和上限。

## 5. 高风险 cell 与 quorum

简历项目采用显式、精炼的风险规则：只有 required cell 且满足以下任一条件时才建议 quorum=2：

- 数值/日期会直接影响核心比较结论；
- 首个候选存在冲突证据；
- 来源质量低于阈值或只有单一来源域；
- schema 将该列标记为 `critical=true`。

普通 cell 保持 quorum=1，避免无条件成本翻倍。两个候选必须绑定不同 slot，执行上下文隔离；第二槽优先使用 Counterfactual profile 和排除第一槽来源域。Coordinator/Backend 仍是唯一 canonical writer。

## 6. Benchmark 数据与指标契约

每个 case 至少包含：

- `case_key/question/schema/gold_version`；
- 固定 workspace source snapshot 或允许归档的 external source policy；
- expected entities、required cells、精确值及逐 cell normalization rule；
- citation 和 counterfactual 最低门槛。

每个 rollout 必报：

- 质量：exact-table、entity/cell/column/row F1、cell value accuracy、citation association/support、branch correction；
- 效率：wall-clock、search/fetch/read/LLM calls、有效并发；
- 成本：input/output token、estimated cost、retry overhead；
- 稳定性：429、5xx、timeout、lease expiry、stale/rejected merge、termination reason；
- 可比性：manifest digest、source snapshot digest、provider/model、budget/random policy。

聚合放行规则：真实 Provider、每 mode 至少四轮、manifest 除 execution mode 外完全相同；关键质量相对 sequential 不低于 2 个百分点、错误 VERIFIED 为 0、成本不超 profile 上限。延迟提升只在真实 wall-clock 数据上报告。

## 7. TDD 垂直切片

1. RED/GREEN：角色画像可解析、差异化、越权 fail closed。
2. RED/GREEN：quorum=2 拒绝同 execution/同 slot/同域候选。
3. RED/GREEN：匿名候选同值接受、冲突拒绝、输入乱序等价。
4. RED/GREEN：BenchmarkRunner 固定 manifest 并采集完整运行指标。
5. RED/GREEN：archive JSON 稳定、不可覆盖；条件漂移拒绝聚合。
6. RED/GREEN：四轮真实 Provider 门和 simulated 防误声明。

每个切片只通过上述公开 seam 断言，不测试私有 helper，不 mock 项目内部对象；仅在 Provider/clock/filesystem 这些系统边界使用替身。

## 8. 验证矩阵

| 层级 | 本轮可执行 | 退出证据 |
| --- | --- | --- |
| 角色/盲验纯契约 | 是 | 定向 pytest，乱序与伪 quorum 覆盖 |
| benchmark harness | 是 | deterministic runner + archive + aggregate 测试 |
| Worker 全量回归 | 是 | Docker/Python 3.12 全量 pytest |
| fake-provider A/B | 是 | 只证明 harness 和采集，不证明质量提升 |
| 真实 Provider 4-rollout A/B | 当前否 | key/base URL/model 配置后执行并归档 |

## 9. 阶段完成口径

代码和离线证据完成后，允许表述：

> Research Agent 已具备角色隔离、高风险 cell 双候选盲验和可复核 A/B benchmark 契约；自动恢复、checkpoint 和原子收口已接入受控多 Worker 主链。真实 Provider 质量、延迟和成本结论仍以归档的四轮对照为准。

在真实 Provider 证据完成前，MA5 状态为 `IMPLEMENTED / EXTERNAL EVIDENCE PENDING`，不得写“质量优于 TAS/MiroFlow/Marco/DeepWideSearch”。

## 10. MA6 边界

MA6 只做简历项目需要的运行治理：`INCREMENTAL_V1` 唯一授权、健康指标阈值、暂停新的 initial wave、保留 candidate/merge/checkpoint 审计。已有增量历史的 Run 不切回 legacy，而是继续 recovery/finalize。不会实现多阶段生产流量百分比平台；没有真实 MA5 基线时，MA6 只能验证保护机制，不能宣称生产 SLO。

## 11. 2026-07-17 实施记录

已完成：

- `RoleProfileCatalog/RoleProfile`，Deep Cell 执行上下文实际使用 profile 的工具权限、LLM call cap 与 temperature；
- `candidate_quorum/high_risk/candidate_slot` 契约、critical cell 单独成 bundle；旧单候选 executor 遇到 quorum=2 时 fail closed；
- `BlindCandidateVerifier` 的独立 execution/slot/domain、匿名摘要、冲突修复和输入乱序确定性；
- `BenchmarkRunner/Archive/Comparator`、Worker runtime adapter、CLI、exact-table workspace gold case；
- LLM usage ledger 新增跨重试 `http_429_count/http_5xx_count`；
- simulated rollout 已归档到 `scripts/ma5/evidence/simulated-20260717T200945Z`，其 exact-table 失败被如实保留。

新增完成：

- Backend/Coordinator 对 high-risk cell 正式 fan-out 两个 durable execution slot；两个 slot 具有独立 task/reservation/outbox/lease/fence/execution，共享 quorum group；
- 首候选 immutable `QUORUM_PENDING`、双候选独立来源同值单次 CAS、同域/值冲突 fail-closed、乱序/replay/CAS 后回滚均已验证；
- open `QUORUM_REPAIR_REQUIRED` verifier decision 已接入 gap projection、checkpoint advancement 和 COUNTERFACTUAL repair taskization；
- quorum group lifecycle 在单 slot retry/failed 时保留 binding，最后一个 sibling terminal 后释放；
- Worker v2 snapshot 与合法 COUNTERFACTUAL quorum slot/quorum=1 repair task 对齐；repair accepted merge 会在同一 completion 事务中把对应 OPEN decision 置为 `RESOLVED`。

验证证据：Backend MySQL 8.4 Research 聚合 `202 tests, 0 failures, 0 errors, 1 skipped`（skip 为环境门控 Redis integration），MySQL LockMatrix `13/13`，Research Worker `361/361`。补充回归覆盖自动 Run bootstrap、legacy callback fail-closed、DLQ redaction、provider redirect fail-closed、Java/Python canonical Unicode key 顺序、deterministic report Markdown 转义和 Redis permit `limited` 指标。

尚未完成且不得改写为已完成：

- Research Worker 独立 `API_KEY/BASE_URL/MODEL` 当前环境均未设置，真实 Provider 每 mode 四轮 A/B 未执行；
- external web evidence archive/provenance 与真实质量、延迟、成本结论。
