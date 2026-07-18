# 阶段 6 Redis Wiki Page Version 缓存第一批验收

## 1. 本批范围

- 日期：2026-07-14
- 范围：Wiki 页面详情 immutable version snapshot cache、动态 item/link 边界、TTL+jitter、DB fallback、指标与架构门禁
- 状态：Wiki page detail cache 已接入 `/api/v2/knowledge-items/{itemId}`；Wiki home/index/search/graph 仍直接读取数据库

本批没有缓存整个 `KnowledgeItemDetailResponse`。详情中只有正文、summary、source message、version citations 和 version created-at 随 immutable `knowledge_version` 固定；title/status/page kind/updated-at 与 outgoing/backlinks 可能在不修改当前页面 version 的情况下变化，必须保持实时。

## 2. Cache key 与 schema

Key：

```text
noteweave:v1:cache:wiki_page_version:{workspaceId}:{itemId}:{latestVersionId}
```

Value 使用 `wiki-page-version-v1` envelope，内部为 `KnowledgePageVersionSnapshot`：

- item ID；
- version ID/no；
- content、summary、source message ID；
- 按 sort order 固定的 `KnowledgeCitationResponse`；
- version created-at。

读取时同时验证 schema、item ID 和 version ID。任何 identity mismatch 都视为 miss。

## 3. 动态边界

每次详情请求仍实时查询 `knowledge_item`：

- workspace ID；
- item type/page kind/title；
- status；
- latest version pointer；
- updated-at。

因此：

- soft-deleted item 在 cache lookup 前返回 `KNOWLEDGE_ITEM_INACTIVE`；
- rename 立即显示新 title/page kind/updated-at；
- append version 后 latest pointer 改变，自动使用新 version key；
- 旧 version snapshot 保留审计价值但不会被 latest detail 再读取。

Wiki outgoing links 和 backlinks 每次从 DB 查询，不进入 version snapshot。其他页面 rename/delete、incoming link resolve/unresolve 或 mention count 更新无需失效当前页面缓存。

非 Wiki Knowledge Item 保持直接加载 version，不进入 Wiki cache。

## 4. TTL 与降级

默认配置：

| 配置 | 默认值 |
| --- | ---: |
| `NOTEWEAVE_WIKI_PAGE_VERSION_CACHE_ENABLED` | true |
| `NOTEWEAVE_WIKI_PAGE_VERSION_CACHE_TTL_SECONDS` | 600 |
| `NOTEWEAVE_WIKI_PAGE_VERSION_CACHE_JITTER_SECONDS` | 120 |

immutable snapshot 使用比 Source catalog 更长的 TTL。TTL 最低为 1 秒，jitter 最低为 0。

以下情况全部回源原 version/citation SQL：

- cache disabled；
- Redis bean 不存在；
- key miss；
- schema 或 identity mismatch；
- JSON 解析失败；
- Redis read/write error。

cache 写失败不影响详情返回。由于 latest pointer 和 ACTIVE 状态始终来自 MySQL，Redis 不参与 Knowledge 生命周期判定。

## 5. 指标

统一 cache 计数：

```text
noteweave.cache.requests{cache=wiki_page_version,result=*}
```

result：`hit`、`miss`、`load`、`disabled`、`invalid`、`error`。

DB version/citation fallback timer：

```text
noteweave.knowledge.wiki.page.db.load{result=success|error}
```

指标不携带 Workspace、item、version 或 title 等高基数 tag。

## 6. 架构边界

`ArchitectureBoundaryTest.knowledgeQueryMustCacheOnlyImmutableWikiVersionSnapshots` 固定：

- `KnowledgeQueryService` 依赖 `WikiPageVersionCache`；
- `KnowledgeQueryService` 依赖 `KnowledgePageVersionSnapshot`；
- `WikiPageVersionCache` 不依赖 `KnowledgeCommandService`；
- `WikiPageVersionCache` 不依赖 `KnowledgeVersionService`。

Cache adapter 只负责 immutable read snapshot，不拥有 create/append/rename/delete/link mutation 生命周期。

## 7. 测试覆盖

新增：

- `WikiPageVersionCacheTest` 4/4：versioned key、600 秒 TTL、JSON round-trip、schema/identity mismatch、Redis error 和 bean missing；
- `KnowledgeQueryServiceTest` 扩展为 4/4：cache hit 保留 immutable content，同时动态 link mention count 实时更新；item 转为 DELETED 后即使 cache hit 也拒绝读取；
- `ArchitectureBoundaryTest` 扩展为 26/26。

定向验证：

```text
WikiPageVersionCacheTest + KnowledgeQueryServiceTest + ArchitectureBoundaryTest = 34/34
```

## 8. 真实验证结果

执行：

```powershell
.\mvnw.cmd -f backend\pom.xml '-Dtest=WikiPageVersionCacheTest,KnowledgeQueryServiceTest,ArchitectureBoundaryTest' test
.\mvnw.cmd -f backend\pom.xml test
git diff --check
```

结果：

- Wiki 定向组：34/34；
- Backend 全量：286/286（后续 Memory compiled pack cache 加入 5 个测试后全量重跑）；
- Surefire reports：76；
- `ArchitectureBoundaryTest`：26/26；
- Failures/Errors/Skipped：0/0/0。

## 9. 后续切片

下一批实现 Memory compiled pack cache。Key 必须包含 actor、target/task neighborhood、compiler policy version 和选中 latest Memory Version 指纹；Memory review、append、revoke、outcome 导致的 ACTIVE/APPROVED/utility 变化都必须切换或失效 fingerprint，不能只按 Workspace/answer mode 缓存。
