# 阶段 1 Workspace 权限矩阵与 ACL 缓存验收

- 验收日期：2026-07-13
- 状态：本施工增量通过；阶段 1 整体仍在进行
- 数据库版本：V029
- 后端回归：118/118 通过
- Docker Smoke：`target/docker-smoke/phase0a-smoke-20260713032428-2630.json`

## 已验收能力

1. OWNER 拥有全部 7 类 Workspace 权限；EDITOR 除 Workspace 管理外拥有业务写权限；VIEWER 只有读取权限。
2. 授权同时要求 Workspace、User、Membership 均为 ACTIVE；SUSPENDED/REMOVED 成员拒绝访问。
3. 成员管理 API 只允许 Owner 使用，不能降级或移除 Owner 本人。
4. 成员角色/状态变更与 `workspace.acl_version + 1` 在同一事务内提交。
5. Redis key 包含 ACL 版本。旧 key 可以保留到 TTL，但不会被新请求使用。
6. Redis 缓存具备 hit/miss/load/error 指标；Redis 异常时拒绝逻辑仍以 MySQL 为准。
7. 核心业务表具备审计 actor；Docker 主链路新建的 Workspace、Owner Member、Upload、Source、Task、Conversation 的 actor 均实测为 `local-user`。
8. 后台无请求上下文时使用 `SYSTEM:<component>`，不借用开发 fallback 身份。
9. Workspace/Source 到 Wiki 的调用经应用端口适配，4 条 ArchUnit 规则进入测试门禁。
10. ObjectStorage 移出基础设施包，Source/Upload 不再编译依赖 `infra`；Wiki/Knowledge 不再依赖 WorkspaceService 实现。
11. Task 全状态机与异步 Source 投影均更新 actor；Docker 实测 Source 最终为 `SYSTEM:ELASTICSEARCH`，Task 最终为 `SYSTEM:TASK`。
12. ArchUnit 已增强为 7 条，V029 补齐 Conversation `updated_at` 并通过 H2/MySQL 双迁移。
13. `ChunkSearchPort` 隔离 Chat 与 Elasticsearch 实现，ElasticsearchIndexer 成为基础设施适配器；检索不可用时由应用层显式执行 MySQL 降级。
14. Chat 的会话、消息、引用和上下文查询，以及 Upload 的分片、完成态、Source 回读和文件引用计数，已增加 Workspace 条件形成纵深隔离。
15. ArchUnit 增强为 8 条，禁止 Chat 重新依赖 `infra`；完整回归 118/118，V029 Docker 全链路 16 项再次通过。

## Docker 实测

通过成员管理 API 完成以下状态序列：

```text
acl_version 1 -> 2: 添加 VIEWER
VIEWER: GET Source = 200, POST Conversation = 403
acl_version 2 -> 3: 升级 EDITOR
EDITOR: POST Conversation = 200
acl_version 3 -> 4: 移除成员
REMOVED: GET Source = 403
```

Redis 同时存在版本 2、3 的 key，版本 4 请求仍正确拒绝，证明失效依赖版本而不是危险的全量 key 扫描或最终一致删除。

## 尚未宣称完成的范围

阶段 1 还需要把 QA、Note、Wiki 三种检索组织为统一策略接口和答案任务模型，继续收敛 Knowledge 与少量直接资源解析查询，并在拆除遗留循环后启用包循环门禁。本记录不将这些未施工项标记为完成。
