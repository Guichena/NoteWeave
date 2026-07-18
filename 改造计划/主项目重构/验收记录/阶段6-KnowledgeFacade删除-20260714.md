# 阶段 6 Knowledge Facade 删除验收

- 日期：2026-07-14
- 范围：Wiki home/index、item list/detail、citation/link read model、source-backed lookup、旧 facade 删除
- 结果：原 1928 行 `KnowledgeService` 已删除

## 最后读边界迁移

`KnowledgeQueryService` 在原 search 与 Answer retrieval read port 基础上继续承接：

- `getWikiHome`；
- `getWikiIndex`；
- `listWikiLinks`；
- `listItems`；
- `getItemDetail`；
- `findSourceBackedWikiItemIds`；
- Knowledge Version citation 与 Wiki outgoing/backlink read model。

`KnowledgeController` 的 home、index、list、detail 已直接调用 Query service。`WikiIngestService` 的 source retract page lookup 也已直接调用 Query service。

## 旧 Facade 删除

`backend/src/main/java/com/noteweave/knowledge/KnowledgeService.java` 已删除。生产代码扫描不存在：

- `KnowledgeService` 类型；
- `knowledgeService.*` 调用；
- Controller 或 Wiki ingest 对旧 facade 的注入。

架构测试新增强制门禁，直接检查导入的生产 class 集中不包含 `KnowledgeService`，防止后续以同名万能 facade 回流。

## 行为保持

- Wiki list 仍按 updated-at 倒序；
- Wiki home 仍返回原 route、页面和 link 列表；
- Wiki index 仍投影 ready Source、source-backed/manual page、统计、近期任务、近期 Source、推荐 action 与 top issues；
- item detail 仍拒绝 inactive item，并按 sort order 返回 citation；
- Wiki detail 仍返回 outgoing/backlink；
- source retract lookup 仍只选择 citation 全部来自指定 Source 的 ACTIVE Wiki page；
- Answer retrieval 的排序、one-hop context 和 citation IDs 未改变。

## 最终 Knowledge 边界

- `KnowledgeQueryService`：API/Answer read model；
- `KnowledgeGraphService`：graph traversal；
- `KnowledgeVersionService`：immutable version list/detail/append；
- `KnowledgeCommandService`：create/save/rename/delete/upsert；
- `KnowledgeGovernanceService`：stats/lint/advice/rebuild/auto-fix；
- `KnowledgeWikiMutationService`：normalize/link/page-kind/audit 支持组件；
- `KnowledgeWikiSearchEngine`：共享 Wiki ranking/read rows。

## 自动验证

定向回归：

- `KnowledgeQueryServiceTest`：3/3；
- `KnowledgeGovernanceServiceTest`：3/3；
- `KnowledgeCommandServiceTest`：3/3；
- `KnowledgeVersionServiceTest`：3/3；
- `Phase3NoteWikiContractTest`：27/27；
- `ArchitectureBoundaryTest`：19/19；
- 合计：58/58。

Backend 全量：

- Tests：237/237；
- Surefire reports：65；
- Failures：0；
- Errors：0；
- Skipped：0。

## 后续

Knowledge 巨型 facade 拆分完成。阶段 6 下一批进入 Memory Candidate Gate 与版本化 policy，继续拆解 Signal/Promotion/Compiler 中的固定评分、自动提升和冲突判断。
