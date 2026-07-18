# 阶段 6 Memory Version 生命周期验收

- 日期：2026-07-14
- 范围：immutable Memory Version、latest pointer、并发 append、supersede、revoke、Compiler current-version 消费
- 兼容约束：保留现有 Signal/Promotion/Control Pack 行为和 Memory Object 返回结构

## 数据模型

V034 新增：

- `memory_version`；
- `memory_object.latest_version_id`；
- `memory_object.current_version_no`。

每个 Memory Version 保存：

- immutable canonical statement；
- task neighborhoods 与 compile hints；
- forbidden patterns；
- version number、status、valid from/to；
- supersedes version；
- source candidate；
- policy version、risk score 与 scope status。

V034 是新增迁移；V001-V033 未被改写。

## Version Owner

新增 `MemoryVersionService`，统一承接：

- Promotion 创建 v1；
- version list/detail；
- append/supersede；
- revoke；
- V034 之前 legacy object 的惰性 bootstrap。

`MemoryPromotionService` 仍决定 Candidate 是否可提升，但不直接写 `memory_version`。Object 与初始 Version 位于同一个 Promotion 事务，任一步失败都会回滚。

## 并发与不可变性

append 在事务内先使用 `select ... for update` 锁定 Memory Object row，再执行：

1. 读取 current/latest version；
2. 分配 `current_version_no + 1`；
3. 插入新 ACTIVE version；
4. 将旧 version 的生命周期状态转为 `SUPERSEDED` 并关闭 `valid_to`；
5. 原子移动 latest pointer；
6. 同步 legacy object 投影字段，兼容尚未迁移的读路径。

旧版本的 canonical statement、neighborhood、compile hints、forbidden patterns 和 provenance 不更新。并发 H2 契约证明两个 append 最终形成连续 `1/2/3`，latest 指向 v3，supersedes chain 包含两条边，v1 正文保持不变。

## Legacy Bootstrap

V034 不使用数据库特定 UUID 函数批量回填历史对象。对 `current_version_no=0/latest_version_id=null` 的 legacy object，首次 append 或 revoke 时：

- 读取现有 object snapshot；
- 创建 `memory-legacy-bootstrap-v1` v1；
- 再执行正常的 supersede/revoke；
- Compiler 在 bootstrap 前继续兼容读取 legacy object 字段。

因此迁移不要求停机回填，也不会使已有 ACTIVE Memory 在升级后消失。

## Compiler 边界

`MemoryCompilerService` 现在以 latest version 为真源，只接纳：

- Object status 为 `ACTIVE`；
- latest Version status 为 `ACTIVE`；
- `valid_from <= now`；
- `valid_to` 为空或晚于 now。

版本存在时不再信任 legacy object 的 compile JSON。契约测试直接篡改 object 投影后，Control Pack 仍读取 v1；append 后只读取新版本；revoke 后该 Memory 立即从 Control Pack 消失。

## API

新增：

- `GET /api/v2/workspaces/{workspaceId}/memory/objects/{memoryObjectId}/versions`；
- `GET /api/v2/workspaces/{workspaceId}/memory/objects/{memoryObjectId}/versions/{memoryVersionId}`；
- `POST /api/v2/workspaces/{workspaceId}/memory/objects/{memoryObjectId}/versions`；
- `POST /api/v2/workspaces/{workspaceId}/memory/objects/{memoryObjectId}/revoke`。

Version API 需要 `MEMORY_REVIEW` 权限。公开响应使用独立 `MemoryVersionResponse` 和 `MemoryCompileHintsResponse`，不泄漏 `MemorySignalService` 内部 row 类型。

## 架构防线

`ArchitectureBoundaryTest` 固定：

- `MemoryPromotionService` 必须依赖 `MemoryVersionService`；
- `MemoryVersionService` 不得反向依赖 `MemoryPromotionService`。

## 自动验证

定向回归：

- `MemoryVersionServiceTest`：2/2；
- `Phase5MemoryContractTest`：7/7；
- `ArchitectureBoundaryTest`：21/21；
- 合计：30/30。

Backend 全量：

- Tests：246/246；
- Surefire reports：67；
- Failures：0；
- Errors：0；
- Skipped：0。

## 后续

Version owner 已完成 immutable snapshot、supersede 和 revoke，但 `memory_usage_log.effect_feedback` 仍只是未使用的文本列。下一批建立结构化 outcome feedback、实际 utility 更新和 STALE/REVIEW policy。
