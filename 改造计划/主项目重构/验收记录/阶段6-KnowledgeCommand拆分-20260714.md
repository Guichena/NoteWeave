# 阶段 6 Knowledge Command 拆分验收

- 日期：2026-07-14
- 范围：Knowledge create、save-as-note、rename、delete、Wiki upsert 及生产调用方迁移
- 算法约束：不改变 KnowledgeItem + immutable Version、latest pointer、citation 顺序或 Wiki normalize/link/audit 语义

## 单一 Command Owner

新增 `KnowledgeCommandService`，统一承接：

- `createItem`；
- `saveMessageAsNote`；
- `renameItem`；
- `deleteItem`；
- `upsertWikiPage`；
- 首版本 KnowledgeItem + KnowledgeVersion 的事务写入。

生产调用方已迁移：

- `KnowledgeController` 的 create、rename、delete、save-as-note 端点直接调用 Command service；
- `WikiIngestService` 的 page upsert 与 retract delete 直接调用 Command service；
- `ArtifactJobService` 的 Knowledge writeback 直接调用 Command service；
- 旧 `KnowledgeService` 的 auto-fix 只委托 Command service 创建缺失页面，不保留第二套创建算法。

生产代码中 create/save/rename/delete/upsert 的公开 owner 只剩 `KnowledgeCommandService`。

## 创建与 Upsert 边界

创建仍在同一事务内完成：

1. Workspace 存在性校验；
2. 从来源助手消息按 `message_citation.sort_order` 读取 citation，或接收显式 citation ID；
3. Wiki 内容 normalize 和 page kind 推断；
4. 插入 KnowledgeItem；
5. 插入 version 1 并设置 latest pointer；
6. 按原顺序绑定 version citation；
7. Wiki 出链投影、incoming unresolved link resolve 和 `CREATE_PAGE` audit。

同标题 ACTIVE Wiki 仍不创建第二个 item，而是委托 `KnowledgeVersionService.appendVersion` 追加 immutable version。

## Rename 与 Delete 边界

Wiki rename 保持原副作用：

- 更新 item title、page kind、actor 和时间；
- 更新指向旧标题和新标题的 target resolve；
- incoming page 的 `[[Old Page]]` / `[[Old Page|alias]]` 正文被重写；
- incoming page 通过 `KnowledgeVersionService` 追加新版本，旧版本不覆盖，alias 保留；
- 重建 renamed page 的 outgoing link；
- 写入 `RENAME_PAGE` 与 `REFRESH_LINK_CONTENT` audit。

Wiki delete 保持 soft-delete 语义：

- item status 更新为 `DELETED`；
- 删除该页面 outgoing link；
- incoming link 的 target item 清空并置为 `UNRESOLVED`；
- 写入 `DELETE_PAGE` audit。

## 结构结果

- `KnowledgeService`：1928 行 -> 785 行；
- `KnowledgeCommandService` 不依赖 `KnowledgeService`；
- `KnowledgeVersionService` 仍是后续版本追加的唯一 owner；
- Flyway 历史迁移未修改。

## 自动验证

定向回归：

- `KnowledgeCommandServiceTest`：3/3；
- `KnowledgeVersionServiceTest`：3/3；
- `Phase3NoteWikiContractTest`：27/27；
- `Phase6ResearchArtifactContractTest`：32/32；
- `ArchitectureBoundaryTest`：17/17；
- 合计：82/82。

Backend 全量：

- Tests：231/231；
- Surefire reports：64；
- Failures：0；
- Errors：0；
- Skipped：0。

## 后续

下一批建立 `KnowledgeGovernanceService`，迁移 Wiki stats、lint/issues、rebuild advice、link rebuild 与 auto-fix。治理修复需要继续通过 Command/Version owner 写入，禁止重新复制首版本或 append 算法。
