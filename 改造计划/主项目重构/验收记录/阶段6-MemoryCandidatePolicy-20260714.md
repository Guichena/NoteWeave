# 阶段 6 Memory Candidate Policy 验收

- 日期：2026-07-14
- 范围：Signal confidence、Candidate evidence/utility/risk/scope gate、statement novelty/conflict、Promotion duplicate merge
- 行为约束：保持既有 Signal、Promotion、Control Pack API 与低风险显式偏好自动提升行为

## 版本化 Policy

新增 `MemoryCandidatePolicy`，以 `memory-candidate-policy-v1` 集中声明：

- minimum evidence confidence：`0.70`；
- minimum auto-promotion utility：`0.60`；
- review-required risk：`0.70`；
- equivalent statement similarity：`0.85`；
- source confidence、marginal utility 与 risk 评分规则。

`MemorySignalService` 不再持有 source confidence switch；`MemoryCandidateService` 不再内嵌 utility、evidence gate 和 ready 判断；Promotion ledger 记录实际使用的 policy version。

## Candidate Gate

新增 `MemoryCandidateGate`，统一输出：

- `policyVersion`；
- `evidenceGateStatus`；
- `riskScore`；
- `scopeStatus`；
- `reviewStatus`。

固定行为：

- 弱 `MODEL_INFERENCE` confidence 不满足 evidence gate，进入 `NEEDS_REVIEW`；
- 明确、低风险的 `USER_FEEDBACK/NEGATIVE` preference 继续进入 `READY`；
- 与 active memory 冲突时 risk 达到 review 阈值，禁止自动提升；
- workspace/user/task neighborhood 不完整时 scope 无效，禁止自动提升。

V033 为 Signal/Candidate 增加 policy/risk/scope 审计字段。迁移中的 Candidate 三列使用独立 `ALTER TABLE ... ADD COLUMN`，可在 H2 2.2 与 MySQL 执行，未修改 V001-V032 历史迁移。

## Statement Matcher

新增共享 `MemoryStatementMatcher`：

- NFKC 与小写规范化；
- 标点、符号和连续空白归一；
- token Jaccard 与 compact bigram Jaccard 取最大相似度；
- Candidate novelty/conflict 和 Promotion duplicate merge 使用相同阈值与算法。

因此“正式文档不要写废弃说明或历史演进”和带中文标点的等价变体只生成一个 active Memory object；不再由 Candidate 和 Promotion 分别执行不同的字符串判断。

## 持久化与 API 投影

- `memory_signal.policy_version` 保存信号评分 policy；
- `memory_candidate.policy_version/risk_score/scope_status` 保存准入决策；
- `MemorySignalResponse` 投影 policy version；
- `MemoryCandidateResponse` 投影 policy version、risk score 与 scope status；
- promoted Memory ledger 保存 policy/risk/scope，支持回溯当时的 gate 决策。

## 架构防线

`ArchitectureBoundaryTest` 固定：

- `MemorySignalService` 必须依赖 `MemoryCandidatePolicy`；
- `MemoryCandidateService` 必须依赖 Policy、Gate 与 Matcher；
- `MemoryPromotionService` 必须依赖共享 Matcher。

## 自动验证

定向回归：

- `MemoryCandidatePolicyTest`：4/4；
- `QaAnswerModeStrategyTest`、QA Evidence/Citation/Registry 契约与阶段 1/2、Memory 契约合计：55/55；
- `ArchitectureBoundaryTest`：20/20。

Backend 全量：

- Tests：242/242；
- Surefire reports：66；
- Failures：0；
- Errors：0；
- Skipped：0。

## 后续

Candidate Gate 只决定是否可提升，尚未建立 immutable Memory Version、latest pointer、supersede/revoke/stale/conflicted 生命周期。下一批引入独立 lifecycle owner，并让 Compiler 只消费当前有效版本。
