# 阶段 5：Workspace QA 灰度与回滚开关验收（2026-07-16）

> 历史验收记录：该切片描述的是当时的 baseline/V2 灰度能力。当前简历展示版的新 QA AnswerRun 已统一固定为 `qa-retrieval-v2`，`retrieval_strategy_v2_enabled` 保留为历史数据/API，不再参与运行时策略选择；Workspace 仍继续承担权限和资料隔离。

## 1. 定位与非目标

本切片补齐 Workspace 级、默认关闭、可审计的 QA relevance 策略灰度与回切能力。它是 Backend 技术开关，不代表 V2 已完成 production 发布，也不改变用户交互：普通用户提问后仍直接看到回答正文和引用来源，不经过人工审核；`PENDING`、`REVIEWED`、gold、shadow、comparison 与 quality gate 只属于开发侧离线评测。

开关只作用于 QA。Note/Wiki 的策略、回答算法和用户呈现均不受影响。本切片没有前端改造，也没有读取或修改 `workers/research-worker/`、`workers/artifact-worker/`。

## 2. 持久化、API 与授权

- V046 为 `workspace` 增加 `retrieval_strategy_v2_enabled boolean not null default false`，没有增加索引。
- Workspace 成员可调用 `GET /api/v2/workspaces/{workspaceId}/retrieval-settings` 查看当前值。
- 只有 Workspace owner 可调用 `PUT /api/v2/workspaces/{workspaceId}/retrieval-settings` 修改；普通成员更新会被权限边界拒绝。
- PUT 必须显式提供布尔字段 `retrieval_strategy_v2_enabled`；字段缺失或为 `null` 返回 400，不把缺省值解释为 `false`。
- 成功更新同步写入 `updated_by` 与 `updated_at`，保留操作者和更新时间审计。

数据库默认值为 `false`，因此部署迁移不会自动把既有 Workspace 切入 V2。

## 3. 一次读取与 AnswerRun 冻结

`ChatService` 只在创建新 QA run、构造 `AnswerContext` 时读取一次 Workspace 开关。`QaAnswerModeStrategy` 将选择出的 profile、plan、relevance、selection 和 V2 flag 固化进本轮 `RetrievalPlan`；后续 Elasticsearch/MySQL retriever 从该 plan 解析 profile，不再次读取可变 Workspace 状态。

因此：

- 开关变更只影响变更后创建的 QA AnswerRun。
- 已开始或已完成的 AnswerRun 不因 Workspace 当前值改变而漂移。
- AnswerRun、`retrieval.summary`、execution trace、EvidenceBundle 与指标可以按回答当时的 profile 审计。

## 4. Profile 对照与不变量

| 情况 | Strategy profile | Plan version | Relevance policy | Selection policy | Step measurement |
| --- | --- | --- | --- | --- | --- |
| Baseline | `qa-retrieval-baseline-v1` | `qa-passage-baseline-v1` | `qa-lexical-sufficiency-v1` | `qa-source-diverse-budget-v2` | `strategy_v2_enabled=0` |
| V2 | `qa-retrieval-v2` | `qa-passage-v2` | `qa-lexical-sufficiency-v2` | `qa-source-diverse-budget-v2` | `strategy_v2_enabled=1` |
| Legacy V2 导出兼容 | 归入 `qa-retrieval-v2` | `qa-passage-v1` | `qa-lexical-sufficiency-v2` | `qa-source-diverse-budget-v2` | 历史记录允许缺省 |

Baseline 只回切 relevance admission。两个新 profile 都继续使用候选上限 12、最多 6 条 evidence、最多 8,000 字符以及同一个 Source 多样性 selection policy；以下边界不会因回滚而关闭：

- Workspace、显式 Source scope 与 ownership 复核；
- 只接纳最新 Source Snapshot/Knowledge Version；
- Elasticsearch 失败后的 MySQL fallback 与 degradation 审计；
- 最少证据、明确拒答、EvidenceBudgeter 和 citation 持久化校验；
- 低基数 trace、metric 与日志边界。

开关不恢复旧 QA builder、`ChatService` mode switch 或双写，也不引入 vector、RRF 或 rerank。

## 5. Plan、trace、exporter 与质量门禁审计

- 新 AnswerRun 的持久化 plan、summary 与 trace 都携带 `strategy_profile`、relevance/selection policy；step measurement 记录 `strategy_v2_enabled=0/1`。
- AnswerRun summary 指标使用低基数 `plan_version`，不能记录 query、正文或资源 ID。
- `QaAnswerRunShadowExportRequest` 已升级为 `qa-answer-run-shadow-export-request-v2`，强制声明一个受支持的 `strategyProfile`；一批中禁止混合 baseline/V2 AnswerRun。
- 输出仍为 `retrieval-shadow-v1`，但显式携带同一 `strategyProfile`。comparator 将其传播到 comparison report，quality gate policy/result 也携带 profile 并要求精确匹配；baseline shadow 不能使用 V2 policy，非空 production profile 也不能使用空 generic policy。
- 新 AnswerRun 的 profile、plan、relevance、selection、预算和 `strategy_v2_enabled` 任一缺失或漂移都会使整批导出 fail closed。
- `qa-passage-v1` 只兼容数据库中的历史 V2 AnswerRun，不代表旧 `qa-answer-run-shadow-export-request-v1` 仍受支持；未知 tuple 和混合 cohort 均被拒绝。

## 6. 操作性回滚检查

建议每次灰度或回切按以下顺序执行并留存证据：

1. owner 调用 PUT 将目标 Workspace 的 `retrieval_strategy_v2_enabled` 设置为 `false`。
2. 成员调用 GET，确认返回值为 `false`。
3. 新建一轮 QA AnswerRun，确认 plan 为 `qa-passage-baseline-v1`、profile 为 `qa-retrieval-baseline-v1`、relevance 为 v1，step measurement 为 0。
4. 复核该回答仍直接向用户展示正文和引用，scope/ownership/latest Snapshot、degradation、预算与 citation 校验保持启用。
5. 恢复为 `true` 后再新建 QA AnswerRun，确认 plan 为 `qa-passage-v2`、profile 为 `qa-retrieval-v2`、relevance 为 v2，step measurement 为 1。
6. 复核切换前的历史 AnswerRun profile、plan、summary 和 citation 均未改变。
7. 导出时按 profile 分批，分别使用匹配的 quality gate policy；不得把 baseline/V2 合并成一个 shadow。

## 7. 自动化验证

以下数字是 Workspace/profile gate 切片完成时的历史截面，不包含随后新增的 r6/recovered receipt、缺省 profile 回归和后续测试修正；不得把它们继续表述为当前工作区的最新全量结果。

- Backend main compile：402 个 main 源文件，通过。
- Backend testCompile：106 个测试源文件，通过。
- QA/Workspace 核心定向：`50/50`，0 failures、0 errors。
- online exporter/CLI：`24/24`，0 failures、0 errors。
- retrieval eval 14 类：`48/48`，0 failures、0 errors；其中 profile-aware comparator/gate 聚焦测试为 `6/6`。
- ES 日志安全聚焦测试：`3/3`，验证固定 reason、不含 Workspace/query/异常原文或 Throwable，同时保留既有异常 cause 契约。
- 架构、权限、迁移、validator 与 Orchestrator 定向：`42/42`，0 failures、0 errors。
- V046 已随全部 46 个 Flyway migration 成功应用。
- 该切片当时的完整 Backend 回归覆盖 104 个 Surefire test suite，执行 `478 tests`：`0 failures / 0 errors / 2 skipped`，Maven `BUILD SUCCESS`。

完整代码回归当前全绿，但它仍不能替代真实 online AnswerRun 导出、按 profile 生产分布、阈值校准和灰度观测。

## 8. 验收结论与剩余门禁

本切片关闭了“缺少 Workspace 级、默认关闭、可审计 QA relevance 回切开关”的工程缺口，并关闭了 shadow/comparison/quality policy 之间 profile 丢失导致跨 cohort 误用的风险。它没有关闭阶段 5 production gate。

当前仍缺少：

- 真实 online AnswerRun 样本导出结果；
- baseline/V2 各自的真实分布、尾延迟、灰度与回切观测；
- 按 `strategyProfile` 校准并发布的 production quality policy；
- faithfulness、answer relevance 与 cost 指标；
- 定时 benchmark、CI 四层评测编排和合并后容器 smoke。

现有 r5 文档只保留历史截面；随后建立的 r6/recovered lineage 仍只是 `qa-retrieval-v2` 的 8-case 离线证据，不是原 r5 artifact 的恢复，也不能形成 baseline 指标、真实灰度/回切观测或生产开关发布依据。在上述证据闭环前，Workspace 技术开关保持默认关闭，阶段 5 production gate 保持未完成。
