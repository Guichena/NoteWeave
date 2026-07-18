# 阶段 5：真实 QA Reviewed Gold、Scope 投影与 r5 对齐验收（2026-07-15）

> 历史状态：本文记录 2026-07-15 的 r5 指标、artifact 与测试截面。r5 临时 target artifacts 后续被并发 `clean` 清除，旧 HMAC 盐按安全要求未持久化，因此无法恢复相同假名 ID、fingerprint、digest 或 byte-for-byte 重建 r5。2026-07-16 起，当前可复验的离线 artifact 证据由新的 [r6 恢复谱系与评测证据回执](./阶段5-r6恢复谱系与评测证据回执-20260716.md) supersede；r6 不是 r5 artifact 恢复，本文历史数值不被改写为 r6。

## 定位与用户边界

本批是开发侧离线检索质量基准，不是面向普通用户的审核流程。在线 QA 在检索和生成完成后直接向用户展示回答正文与引用来源，不需要人工审核；`PENDING`、`REVIEWED`、gold、shadow、漂移检查和质量指标均不进入普通用户前端。这里的人工标注只用于构造开发侧 gold，不是回答发布前置条件。

Research 前端继续维持与 Gemini/GPT/MiroThinker 类似的轻量报告展示，本批不建设复杂审核工作台。数据集中出现 Research 与 Artifact 主题，只表示 QA 检索已上传资料中的相关内容，不会调用、读取或改造两个独立 worker；本批未触碰 `workers/research-worker/`、`workers/artifact-worker/`，也不包含 Research/Artifact Worker 前端改造。

## 8-case 真实数据与 draft v2 闭环

通过既有 Workspace、Upload、Parse、Index API 链路导入 3 份仓库实际文档，并由 `scripts/prepare-stage5-qa-annotation.ps1` 生成 8 个真实 QA case。Windows PowerShell 5 对脚本内中文字面量存在编码风险，因此同义改写使用英文 query，避免把乱码误判为检索质量问题。

| Case | 覆盖目标 | Reviewed 结论 |
| --- | --- | --- |
| `project-capabilities` | NoteWeave v2 能力与模块边界 | 可回答 |
| `qa-annotation-workflow` | QA annotation capture/compile 流程 | 可回答 |
| `research-capability-coverage` | Research 能力及验证覆盖 | 可回答 |
| `project-capabilities-paraphrase` | 产品能力同义改写与 Java/Python 分工 | 可回答 |
| `qa-workflow-source-scope-refusal` | query 命中其他 Source、但显式 scope 不允许 | 拒答 |
| `unknown-feature-refusal` | 虚构 quantum banana theorem 能力 | 拒答 |
| `artifact-version-capabilities` | Artifact Version 保存、再生成、比较、回滚、导出 | 可回答 |
| `research-safety-verification` | HTTP fetch、SSRF、prompt injection 与 citation 验证 | 可回答 |

最终 r5 reviewed gold 为 6 个可回答 case、2 个拒答案例，8/8 均为 `REVIEWED`。标注只依据 query、候选正文和允许 Source，不依据 score 或排名自动推断 ground truth；越界 Source 候选不能成为 relevant evidence 或 expected citation。

annotation draft 已升级为 `retrieval-qa-annotation-draft-v2`，每个 case 新增 `rawInputFingerprint`：它对 case/workspace/query/topK/允许 Source 以及原始候选的顺序、ID、Snapshot、chunk、标题、正文、类型和 score 做规范化拼接，再生成带盐 HMAC-SHA256 fingerprint。原始输入本身不随 fingerprint 落盘；即使两次原始敏感值脱敏后看起来相同，compiler 仍可通过 fingerprint 发现漂移。`compile-reviewed` 会同时核对 fingerprint、raw `candidateCount` 和 sanitized candidates，任何候选身份、顺序、score、Snapshot 或正文变化都要求重新 capture/review。

生产 `ChunkSearchPort` 捕获的 request、draft、reviewed gold 与 shadow 产物当时只保存在 `backend/target/retrieval-eval`，不提交仓库；这些 r5 临时产物后来已被并发 `clean` 清除。HMAC 盐只在受控进程内生成、使用并清除，不写入文件或日志，因此 r5 不能按原假名和摘要 byte-for-byte 恢复；URL、本地绝对路径及其他敏感正文仍在持久化前脱敏。

## relevance/selection v2 与 QA@6 预算

在线 QA 与离线 capture/compiler/shadow 现在共享两条明确版本化的策略边界：

- `qa-lexical-sufficiency-v2`：继续以词法覆盖决定候选是否足以支撑问题，不修改 BM25 score 或候选顺序；它会聚焦多轮 query 中的“当前问题/主题锚点”，并允许有上下文支撑的显著技术标识符，避免泛化历史问题稀释当前问题，同时保留首 chunk 连续上下文和稳定实体锚点防线。
- `qa-source-diverse-budget-v2`：先按 Source 多样性与 evidence identity 去重选取，再按统一 fused score 进入最终 bundle；线上 EvidenceBundle 和离线 shadow 均最多保留 6 条证据、累计最多 8,000 个字符。

QA 的 RetrievalPlan 仍可从主检索获取 12 个候选用于 scope、ownership 和 relevance 诊断，但面向回答的最终语义是 **QA@6 + 8,000 字符预算**。r5 中广义 Research case 的 `topK` 因此从 8 校正为 6，使 gold 指标分母、离线 admitted ranking 和线上 EvidenceBundle 的可交付上限一致。在线 trace 继续只记录安全计数，包括 `relevant_primary_hit_count`、`relevance_rejected_count`、`mysql_relevance_rejected_count`，不记录 query、正文或资源 ID。

这里的一致性只覆盖 `ChunkSearchPort`、Source scope、relevance/selection 与最终数量/字符预算。离线评测 CLI Profile 没有装配 JDBC ownership hydrate，因而 r5 shadow 假定本次受控 fixture 的 chunk/source/snapshot ownership 有效；它不能证明 stale 或 identity mismatch 候选已经经过与线上完全相同的 ownership 复核，也不是最终线上 `EvidenceBundle` 的端到端等价导出。

## Scope 投影与五轮漂移校验

annotation draft 为审核和诊断保留 Workspace 内、显式 Source scope 外的 raw candidate；compiler/capture 先投影允许 Source，再应用 relevance v2 和 selection v2。最终约束为：

- draft 保留越界 raw candidate，但 gold relevant evidence、shadow ranking、EvidenceBundle 和 citation 均不得越界；
- `candidateCount` 记录过滤前 raw candidate 数量，保留召回池诊断信息；
- shadow ranking 只包含同时满足 Source scope、证据充分性、去重、多样性和最终 bundle 预算的 admitted candidate；
- gold、shadow、Prompt、EvidenceBundle 与 citation 使用同一 Source-scope 和 QA@6 语义。

五轮 `compile-reviewed` 当时均使用同一 r5 request 与进程内盐重新调用真实 Port，并全部通过 draft v2 的 raw fingerprint 与候选漂移校验；当时形成 5 份 sanitized shadow，共 40 次 case 运行。五轮完整 admitted ranking 签名只有 1 种，未观察到排名漂移。该陈述是历史验收记录，不表示这些临时 artifact 当前仍存在。

## 最终 r5 Pilot 指标（历史截面）

- `macro Recall@K = 0.916667`。
- `macro MRR = 1.0`。
- `macro NDCG@K = 0.967468`。
- `macro citation precision = 0.822222`。
- `macro citation coverage = 0.916667`。
- `refusal accuracy = 1.0`。
- `scope violation = 0`。
- 五轮 admitted ranking 签名：1 种。
- 五轮 40 次 case 延迟：min `27,746 us`、p50 `224,125 us`、p95 `631,365 us`、max `857,016 us`。
- Snapshot 5 自身延迟：min `28,311 us`、p50 `33,470 us`、p95/max `293,605 us`。

r4 曾把广义 Research case 记为 `topK=8`，且尚未用 selection v2 的 6 条/8,000 字符最终 bundle 预算约束离线 ranking，因此 r4 的 Recall、citation 与延迟结论均不再代表线上 QA 可交付口径。r4 的 `0.958333` Recall/coverage、`0.933333` citation precision 等旧数值明确作废；在本文 2026-07-15 截面中，有效的历史 pilot 结论是上述 r5 结果。当前可复验 artifact lineage 已由 r6 supersede，不能再把 r5 写成当前可重放产物。

## r5 当时的自动化验证状态

- QA 定向回归：`38/38` 通过，`0 failures / 0 errors / 0 skipped`；覆盖 AnswerModeStrategy、在线 ES/MySQL 检索、relevance v2、selection v2、draft v2 fingerprint、scope 投影、compiler 与 shadow capture。
- `com.noteweave.retrieval.eval.*Test` 全集：`23/23` 通过，`0 failures / 0 errors / 0 skipped`。
- 完整 Backend 受控回归：`365` tests，`0 failures / 0 errors / 2 skipped`。
- 真实 `capture-draft`、五轮 `compile-reviewed` 与最终 `RetrievalShadowComparator` 均成功。

完整 Backend 回归使用：

```powershell
.\mvnw.cmd -f backend\pom.xml "-Dspring.kafka.listener.auto-startup=false" test
```

显式关闭测试环境 Kafka listener 是为了避免 listener 与 Backend Research task 测试并发污染，不涉及读取或修改 Research Worker。

## 验收结论与下一门禁

截至 2026-07-15，r5 已完成真实 QA 的 draft v2 防漂移、Source scope 投影、relevance/selection v2、QA@6/8,000 字符预算、拒答、引用和当时的完整 Backend 回归闭环，可以作为阶段 5 的历史 **pilot policy 证据**。普通用户仍直接获得回答和引用，不经过人工审核。

但 8-case、3 份语料和 5 轮回放不足以直接发布 production quality gate：样本规模、问题多样性、长文档分布、跨 Source 组合、低召回与延迟尾部仍需扩展，离线 CLI 也尚未覆盖 JDBC ownership hydrate。r5 pilot 不能直接作为生产门禁，也不据此提前引入 vector、RRF 或 rerank。后续 r6 已从仍存在的 3 个 Source、16 个 indexed chunk 语料及 raw 输入建立新的 HMAC lineage，并生成 content-free evidence receipt，但它仍是离线 artifact 证据；正式门禁仍需通过 `RetrievalExecutionShadowExporter` 从最终持久化的 `EvidenceBundle` 与 execution trace 导出真实 online AnswerRun，并在扩充的独立数据集上持续验证 profile policy、质量、拒答、scope、延迟、faithfulness、answer relevance 与 cost。
