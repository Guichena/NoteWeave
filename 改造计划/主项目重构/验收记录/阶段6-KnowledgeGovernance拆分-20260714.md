# 阶段 6 Knowledge Governance 拆分验收

- 日期：2026-07-14
- 范围：Wiki stats、lint/issues、rebuild advice、log/task projection、link rebuild、auto-fix
- 算法约束：保持既有诊断类型、优先级、过滤、建议决策和修复副作用

## 治理边界

新增 `KnowledgeGovernanceService`，统一承接：

- `getWikiStats`；
- `getWikiRebuildAdvice`；
- `lintWiki` / `listWikiIssues`；
- `listWikiLog` / `listRecentWikiTasks`；
- `rebuildWikiLinks`；
- `autoFixWiki`；
- Wiki task 与 Source 关联页面投影。

`KnowledgeController` 的 stats、advice、issues、log、rebuild-links 和 auto-fix 端点已直接调用 Governance service。`WikiIngestService` 的 ingest summary 也直接读取 Governance service，不再通过旧 facade。

## 诊断语义

保留五类既有治理诊断：

- `BROKEN_LINK`：HIGH、auto-fixable，排序优先；
- `ORPHAN_PAGE`：MEDIUM、人工修复；
- `MISSING_SOURCE`：MEDIUM、人工补充来源；
- `CONTENT_STALE`：MEDIUM、建议重建；
- `PLACEHOLDER_CONTENT`：MEDIUM、人工补正文和来源。

issue type、severity、auto-fixable 和 item ID 过滤保持不变。Rebuild advice 继续按 enable-Wiki、无页面、stale、broken link、placeholder、missing source、orphan 的原决策顺序选择 action 与 focus issue。

## 修复写边界

Governance service 不复制 Knowledge 写算法：

- link rebuild 读取当前 ACTIVE Wiki 页面；
- 正文需要新增 Auto Link 时，通过 `KnowledgeVersionService.appendVersion` 追加 immutable version；
- 原 version citation ID 按 sort order 复制到新 version；
- 正文不变时只重建 link projection；
- auto-fix 通过 `KnowledgeCommandService.createItemWithVersion` 创建缺失占位页；
- 补缺页创建继续 resolve incoming unresolved link；
- `REBUILD_LINKS` 与 `AUTO_FIX` audit 保持。

因此治理编排只决定“需要修什么”，Command/Version owner 仍决定“如何写入”。

## 结构结果

- `KnowledgeService`：1928 行 -> 294 行；
- Governance 公开方法只存在于 `KnowledgeGovernanceService`；
- `KnowledgeGovernanceService` 不依赖 `KnowledgeService`；
- Controller 与 Wiki ingest 已迁移；
- Flyway 历史迁移未修改。

## 自动验证

定向回归：

- `KnowledgeGovernanceServiceTest`：3/3；
- `KnowledgeCommandServiceTest`：3/3；
- `KnowledgeVersionServiceTest`：3/3；
- `Phase3NoteWikiContractTest`：27/27；
- `ArchitectureBoundaryTest`：18/18；
- 合计：54/54。

Backend 全量：

- Tests：235/235；
- Surefire reports：65；
- Failures：0；
- Errors：0；
- Skipped：0。

## 后续

旧 `KnowledgeService` 目前只剩 Wiki home/index、item list/detail、link/detail read model 和 source-backed page lookup。下一批扩展 `KnowledgeQueryService` 承接这些读用例，迁移 Controller 与 Wiki ingest 后删除旧 facade。
