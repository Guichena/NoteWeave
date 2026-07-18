# 阶段 6 Knowledge Version 拆分验收

- 日期：2026-07-14
- 范围：Knowledge Version list/detail/append、并发版本号、citation 顺序、Wiki version 副作用、Controller 与内部写入口收敛
- 算法约束：不修改 immutable version、latest pointer、Wiki normalize/link/page-kind/audit 或 citation 排序语义

## 施工结果

新增 `KnowledgeVersionService`，统一承接：

- `listItemVersions`；
- `getItemVersionDetail`；
- `appendVersion`。

`KnowledgeController` 的版本列表、详情和追加端点直接依赖该服务。`KnowledgeService` 内 create-existing-Wiki、refresh、rollback/rebuild 等既有追加入口也全部委托同一 owner；旧 facade 中不再保留第二套 version list/detail/append 方法定义。

新增 `KnowledgeWikiMutationService` 作为独立支持组件，避免 Version service 为复用 Wiki normalize/link helpers 而反向依赖巨型 facade，也避免形成循环依赖。

## 并发与事务边界

`appendVersion` 在事务开始后读取 KnowledgeItem 时使用 `select ... for update`：

1. 同一 KnowledgeItem 的并发 append 先在 item row 上串行化；
2. 获得锁后再计算 `max(version_no) + 1`；
3. 插入新的 immutable `knowledge_version`；
4. 按顺序绑定 version citation；
5. 原子更新 `knowledge_item.latest_version_id`、page kind、actor 和时间；
6. Wiki item 在同一事务内完成出链重建和 audit log。

并发测试以两个线程同时追加同一 item，最终固定得到版本号 `1, 2, 3`，latest pointer 指向 version 3。数据库现有 `(item_id, version_no)` 唯一约束继续作为最后一道完整性防线。

## 行为兼容

- 旧 version 内容不会被覆盖；
- list 仍按 version number 倒序返回；
- detail 仍返回指定 version 的正文、summary、source message 和 citation；
- citation 按来源消息或显式 ID 的原 sort order 写入，并按该顺序读取；
- Wiki append 仍先 normalize 正文、推断 page kind，再按最终正文投影 link；普通文本 `Related Page` 被 normalize 为 `[[Related Page]]` 后属于显式 `WIKI_LINK`；
- Wiki 出链替换、target resolve、page kind 与 `UPDATE_PAGE` audit 均保持；
- Controller 和内部调用方使用同一 Version service，不存在并行 append 算法。

## 结构结果

- `KnowledgeService`：1928 行 -> 1112 行；
- `KnowledgeVersionService` 不依赖 `KnowledgeService`；
- `KnowledgeWikiMutationService` 不依赖旧 facade；
- Flyway 历史迁移未修改。

## 自动验证

定向回归：

- `KnowledgeVersionServiceTest`：3/3；
- `KnowledgeGraphServiceTest`：3/3；
- `KnowledgeQueryServiceTest`：2/2；
- `Phase3NoteWikiContractTest`：27/27；
- `ArchitectureBoundaryTest`：16/16；
- 合计：51/51。

Backend 全量：

- Tests：227/227；
- Surefire reports：63；
- Failures：0；
- Errors：0；
- Skipped：0。

## 后续

继续建立 `KnowledgeCommandService`，迁移 create/rename/delete 等 command 用例，并逐步把统计、lint、治理和 Wiki ingest 从旧 facade 拆出。该后续工作不得重新引入第二套 Version 写路径。
