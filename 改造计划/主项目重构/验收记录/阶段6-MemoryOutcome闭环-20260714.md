# 阶段 6 Memory Outcome 闭环验收

- 日期：2026-07-14
- 范围：Memory application provenance、结构化 outcome、utility 更新、review/stale、Compiler 准入
- 兼容约束：保留现有 Control Pack 字段和 Chat/Artifact/Research 创建行为

## Application Provenance

Control Pack 新增 `memory_references`，每项包含：

- `memory_object_id`；
- `memory_version_id`；
- 编译时 `utility_score`。

旧 `memory_object_ids` 字段继续保留，已有 worker 和历史 JSON 可以继续读取。历史 Control Pack 缺少 `memory_references` 时，usage logger 会通过 object latest pointer 补充 version provenance。

application log 已覆盖：

- Chat：`CONVERSATION_MESSAGE/{assistantMessageId}`；
- Artifact initial/regenerate：`ARTIFACT_JOB_RUN/{taskId}`；
- Research initial/resume：`RESEARCH_RUN/{researchRunId}`。

同一 target/object 的 usage 写入幂等，避免重试导致重复 application。

## 结构化 Outcome Ledger

V035 扩展 `memory_usage_log`：

- `memory_version_id`；
- `outcome_type/outcome_score`；
- `edit_magnitude`；
- `feedback_note`；
- `outcome_policy_version/outcome_at`。

旧 `effect_feedback` 继续同步 outcome type，兼容既有查询。Memory Object 新增：

- utility score；
- application/positive/negative/edit/retry counters；
- review status；
- outcome policy version；
- last outcome timestamp。

Promotion 使用 Candidate marginal utility 初始化 Object utility，不再统一从固定 `0.5` 开始。

## Outcome Policy

新增 `MemoryOutcomePolicy`，版本为 `memory-outcome-policy-v1`。支持：

- `ACCEPTED` / `POSITIVE`：score `1.0`；
- `EDITED`：按 edit magnitude 映射到 `0.2..1.0`；
- `RETRIED`：score `0.15`；
- `NEGATIVE`：score `0.0`。

utility 使用 `old * 0.75 + outcome * 0.25` 更新并保留四位小数。生命周期规则：

- 首次明确 `NEGATIVE` 立即进入 `REVIEW_REQUIRED`；
- adverse outcome 累计达到两次进入 review；
- 至少三次 outcome 后，utility `<= 0.40` 或 adverse ratio `>= 0.67` 时 Object 进入 `STALE`；
- `REVOKED/CONFLICTED` 不被 outcome 自动改写成其他状态。

所有阈值只存在于 Policy，不散落在 Service。

## Outcome API

新增：

```text
POST /api/v2/workspaces/{workspaceId}/memory/outcomes
```

请求以 `target_type + target_id` 定位一次 Answer/Artifact/Research 执行，将同一 target 中所有尚未反馈的 Memory application 在一个事务内更新。每个 application 只接受一次 outcome；重复提交返回 `MEMORY_APPLICATION_NOT_FOUND`，不会重复降低 utility。

返回包含 version、评分前后 utility、各 outcome counter、Object status、review status 和 policy version。

## Compiler 准入

Compiler 现在只消费：

- Object status `ACTIVE`；
- Object review status `APPROVED`；
- latest Version status `ACTIVE`；
- Version 位于有效时间窗。

因此首次明确负反馈后，即使 Object 尚未达到 STALE 阈值，也会立即停止向后续 Control Pack 注入。人工 append 新版本会恢复 `ACTIVE + APPROVED`，作为当前复核写入口。

## 架构防线

`ArchitectureBoundaryTest` 固定：

- `MemoryOutcomeService` 必须依赖 `MemoryOutcomePolicy`；
- Policy 不得反向依赖 Outcome service。

## 自动验证

定向回归：

- `MemoryOutcomePolicyTest`：4/4；
- `MemoryVersionServiceTest`：2/2；
- `Phase5MemoryContractTest`：8/8；
- `ArchitectureBoundaryTest`：22/22；
- 合计：36/36。

Backend 全量：

- Tests：252/252；
- Surefire reports：68；
- Failures：0；
- Errors：0；
- Skipped：0。

## 后续

Outcome 已能影响准入，但 Compiler 仍直接依赖 Artifact Skill catalog，且尚未执行统一 priority/freshness/utility/token budget。下一批先建立通用 Capability port，再把编译排序和预算变为版本化策略。
