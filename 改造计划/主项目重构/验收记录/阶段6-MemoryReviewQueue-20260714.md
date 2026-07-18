# 阶段 6 Memory Review Queue 验收

## 1. 本批范围

- 日期：2026-07-14
- 范围：Candidate/Object 统一 review read model、显式决策命令、冲突替换、Object 恢复/撤销、决策审计和依赖方向
- 状态：Memory review queue 后端闭环已落地；尚未接入前端 Memory feature

本批承接 Candidate policy、immutable Memory Version、Outcome policy 与 Compiler policy。目标是让 `NEEDS_REVIEW` Candidate、`REVIEW_REQUIRED/STALE` Object 和 conflicting active memory 通过同一个可排序队列处理，不再依赖直接修改数据库或借用 version append 间接完成复核。

## 2. API 与统一 read model

新增接口：

```text
GET  /api/v2/workspaces/{workspaceId}/memory/reviews
POST /api/v2/workspaces/{workspaceId}/memory/reviews/{reviewKind}/{reviewId}/decisions
```

GET 支持：

- `kind=ALL|CANDIDATE|OBJECT`，默认 `ALL`；
- `limit` 强制收敛到 `1..200`；
- Candidate 与 Object 统一投影为 `MemoryReviewItemResponse`，包含 statement、neighborhood、review/lifecycle/conflict/evidence 状态、risk、utility、policy/version、priority 和时间；
- 全局按 priority 降序，再按 created-at 和 review id 稳定排序。

优先级固定为：

| Review item | Priority |
| --- | ---: |
| conflicting Candidate | 100 |
| STALE Object | 90 |
| REVIEW_REQUIRED Object | 80 |
| 普通 NEEDS_REVIEW Candidate | 70 |

## 3. Candidate 决策

Candidate 支持：

- `APPROVE`：将普通 Candidate 转为 `READY`，再交给 `MemoryPromotionService.promoteCandidate`；
- `REJECT`：写入 `REJECTED`，不创建 Memory Object；
- `REPLACE_EXISTING`：只允许 `CONFLICTING_ACTIVE_MEMORY` Candidate 使用。

冲突 Candidate 使用普通 `APPROVE` 时返回 `MEMORY_REVIEW_CONFLICT_RESOLUTION_REQUIRED`，避免静默保留两条互相冲突的 ACTIVE Memory。

`REPLACE_EXISTING` 的执行顺序为：

1. 使用共享 `MemoryStatementMatcher`，按 statement 等价、不同 memory type 和 neighborhood overlap 找到冲突 ACTIVE Object；
2. 逐个调用 `MemoryVersionService.revoke`，同步撤销对象和 latest immutable version；
3. 将 Candidate 标记为 `RESOLVED_REPLACE + READY`；
4. 调用 `MemoryPromotionService.promoteCandidate` 创建新 Object 与 v1；
5. 在响应和审计中返回 promoted object 与 revoked object IDs。

Review Service 不复制 Memory Object 创建、ledger、initial version 或 duplicate merge 算法。

## 4. Object 决策

Object 队列接纳：

- `review_status=REVIEW_REQUIRED`；
- `status=STALE`；
- 排除 `status=REVOKED`。

Object 支持：

- `APPROVE`：恢复 `status=ACTIVE`、`review_status=APPROVED`，Compiler 可再次选择当前有效版本；
- `REVOKE`：调用 `MemoryVersionService.revoke`，再同步写入 `review_status=REJECTED`。

撤销后 Object 为 `REVOKED + REJECTED`，不会再次出现在 review queue，也不会进入 Control Pack。

## 5. 权限和 scope

- list/decision 均要求 Workspace `MEMORY_REVIEW` 权限；
- Candidate 查询和 `select ... for update` 决策均要求 `user_id` 等于当前用户；
- Object 允许 `memory_scope=WORKSPACE`，或 `user_id` 等于当前用户的 USER Memory；
- Workspace 中其他用户的私有 Candidate/User Object 不会被读取或决策。

## 6. 审计迁移

新增 V036 `memory_review_decision`：

- `workspace_id`、`review_kind`、`review_id`；
- `decision`、可选 `reason`、`actor_user_id`；
- `result_object_id`；
- `revoked_object_ids_json`；
- `created_at` 与 workspace/kind/time 索引。

APPROVE、REJECT、REPLACE_EXISTING 和 Object APPROVE/REVOKE 均写入决策审计。审计记录只保存决策结果和对象身份，不复制 Memory 正文或版本内容。

## 7. Owner 与架构边界

依赖方向固定为：

```text
MemoryReviewService
  -> MemoryPromotionService
  -> MemoryVersionService
  -> MemoryStatementMatcher
```

ArchUnit 同时禁止：

- `MemoryPromotionService -> MemoryReviewService`；
- `MemoryVersionService -> MemoryReviewService`。

因此 Review 是应用编排层，Promotion 仍拥有 Candidate -> Object 写入，Version Service 仍拥有 immutable version/supersede/revoke 生命周期。

## 8. 验收场景

`Phase5MemoryContractTest` 已固定：

1. weak model inference Candidate 进入队列后可 APPROVE，并进入 Chat Control Pack；
2. Candidate REJECT 后不再出现在队列；
3. conflicting Candidate priority 为 100；
4. conflicting Candidate 普通 APPROVE 被拒绝；
5. REPLACE_EXISTING 撤销旧 Object、提升新 Candidate，并让新规则进入 Compiler；
6. Outcome 首次明确负反馈产生 REVIEW_REQUIRED Object；
7. Object APPROVE 后恢复 Compiler；
8. 三次 adverse outcome 产生 priority 90 的 STALE Object；
9. Object REVOKE 后写入 `REVOKED + REJECTED` 且队列清空；
10. Candidate 决策写入 V036 审计且 APPROVE/REJECT 数量准确。

## 9. 真实验证结果

执行：

```powershell
.\mvnw.cmd -f backend\pom.xml test
git diff --check
```

结果：

- Backend：266/266（后续 Redis ACL cache 第一批加入 5 个单测后全量重跑）；
- Surefire reports：71；
- `Phase5MemoryContractTest`：9/9；
- `ArchitectureBoundaryTest`：24/24；
- Failures/Errors/Skipped：0/0/0；
- `git diff --check`：通过。

## 10. 后续切片

Memory 生命周期后端主体已形成 Candidate -> Review -> Versioned Object -> Compile -> Apply -> Outcome -> Review 的闭环。下一批进入 Redis 生产化：优先建立 ACL、Source、Wiki、Memory cache namespace/version、TTL+jitter、DB fallback 和 cache metrics，再实现 rate/concurrency quota。前端 Memory review queue 在 Memory feature 模块化时接入本批 read model。
