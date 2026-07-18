# 阶段 6 Redis Source Catalog 缓存第一批验收

## 1. 本批范围

- 日期：2026-07-14
- 范围：Workspace Source metadata 列表缓存、单调版本、六类写入口失效、JSON schema、TTL+jitter、DB fallback、指标和架构门禁
- 状态：Source catalog cache 已接入真实 `/sources` API；Wiki read model、Memory compiled pack 和 quota 尚未开始

Source API 当前只有 Workspace 级列表 read model。本批没有创建无人使用的单 Source cache，而是直接缓存实际页面和 API 消费的 Source catalog，同时保持 MySQL 为唯一真源。

## 2. V037 与单调版本

V037 为 `workspace` 新增：

```text
source_catalog_version bigint not null default 1
```

`SourceCatalogVersionService` 提供：

- `current(workspaceId)`：读取当前 catalog version；
- `bump(workspaceId)`：原子执行 `version = version + 1`；
- Workspace 不存在时 fail closed，不生成孤立 cache key。

版本存放在数据库而不是 Redis，因此 Redis 重启、淘汰或整体 flush 不会丢失失效顺序。

## 3. Cache key 与 value schema

Key：

```text
noteweave:v1:cache:source_catalog:{workspaceId}:{catalogVersion}
```

Value 使用 `source-catalog-v1` envelope，包含：

- schema version；
- catalog version；
- `List<SourceResponse>`。

缓存内容只包含既有 API 已返回的：Source ID、title、type、status、parse/index status、generated provenance 和 updated-at。文件正文、object key、chunk、窗口、citation、token 和用户凭证均不进入 Redis。

读取时同时验证 schema version 与 catalog version。任一不匹配均视为 miss，不尝试兼容性猜测。

## 4. 真实读路径

`SourceService.listSources` 执行顺序：

1. 使用 `WorkspaceAccessGuard` 检查 `WORKSPACE_READ`；
2. 从 MySQL 读取单个 `source_catalog_version`；
3. 按 Workspace/version 查询 Redis；
4. hit 时直接返回，且不扫描 Source 表；
5. miss 时执行原 Source SQL，保持 `status <> DELETED` 和 `updated_at desc, id desc` 排序；
6. 将结果写入当前 version key 后返回。

空列表同样写入 cache，防止空 Workspace 重复穿透数据库。

## 5. TTL、降级与并发正确性

默认配置：

| 配置 | 默认值 |
| --- | ---: |
| `NOTEWEAVE_SOURCE_CATALOG_CACHE_ENABLED` | true |
| `NOTEWEAVE_SOURCE_CATALOG_CACHE_TTL_SECONDS` | 120 |
| `NOTEWEAVE_SOURCE_CATALOG_CACHE_JITTER_SECONDS` | 30 |

TTL 最低为 1 秒，jitter 最低为 0。

以下情况全部回源 MySQL：

- cache disabled；
- Redis bean 不存在；
- key miss；
- JSON 解析失败；
- schema/catalog version mismatch；
- Redis command error。

缓存写失败不影响 API 返回，数据库结果直接返回给调用者。

并发场景中，reader 可能先读取 v1，随后 writer 提交 v2，再由 reader 读到新数据库状态。但 reader 只能把结果写入 v1 key；所有后续 reader 已读取 v2，因此旧 key 不会污染当前 read model，只等待 TTL 清理。

## 6. 写后失效覆盖

以下可见 Source catalog mutation 均推进共享 version：

1. `UploadService` 创建上传 Source；
2. `SourceParseService` 更新 parse/index/status/metadata；
3. `ElasticsearchIndexer` 在全部 chunk 投影完成后将 Source 转为 READY/INDEXED；
4. `GeneratedSourceService` 创建生成式 Source；
5. `ResearchRunService` 将最终报告保存为 Source；
6. `SourceService` 删除 Source。

版本推进与对应数据库写处于同一事务时会随事务一起提交或回滚。异步 ES 完成路径在状态更新后推进版本。无需扫描或删除旧 key，也不会因 Redis 删除失败继续读取旧状态。

## 7. 指标

统一 cache 计数：

```text
noteweave.cache.requests{cache=source_catalog,result=*}
```

result 为：`hit`、`miss`、`load`、`disabled`、`invalid`、`error`。

数据库 fallback timer：

```text
noteweave.source.catalog.db.load{result=success|error}
```

指标不包含 Workspace/Source/key 等高基数 tag。

## 8. 架构门禁

`ArchitectureBoundaryTest.sourceCatalogWritersMustAdvanceTheSharedCacheVersion` 固定以下类型必须依赖 `SourceCatalogVersionService`：

- `SourceService`；
- `SourceParseService`；
- `GeneratedSourceService`；
- `UploadService`；
- `ResearchRunService`；
- `ElasticsearchIndexer`。

这使新增或重构写 owner 时，遗漏 catalog invalidation 会在架构测试中显式失败。

## 9. 测试覆盖

新增测试：

- `SourceCatalogCacheTest` 4/4：versioned key、TTL、JSON round-trip、schema/version mismatch、Redis error、bean missing；
- `SourceCatalogVersionServiceTest` 2/2：读取/递增和 missing Workspace fail closed；
- `SourceServiceCacheTest` 2/2：cache hit 不需要 Source 表、miss 回源并回填当前 version snapshot。

既有测试继续固定：

- `ElasticsearchIndexerTest` 3/3：只有全部 chunk 投影完成才推进 READY；
- `Phase3NoteWikiContractTest` 27/27：上传与删除均推进 catalog version，删除后 retract 行为不变；
- `ArchitectureBoundaryTest` 26/26（后续 Wiki page version cache 加入 immutable snapshot gate）。

## 10. 真实验证结果

执行：

```powershell
.\mvnw.cmd -f backend\pom.xml '-Dtest=SourceCatalogCacheTest,SourceCatalogVersionServiceTest,SourceServiceCacheTest,ElasticsearchIndexerTest,Phase3NoteWikiContractTest,ArchitectureBoundaryTest' test
.\mvnw.cmd -f backend\pom.xml test
git diff --check
```

结果：

- 初次 Source 定向组：62/62；
- 最终 `ArchitectureBoundaryTest`：26/26；
- 最新 `SourceServiceCacheTest`：2/2；
- Backend 全量：281/281（后续 Wiki page version cache 与事件竞态修复后全量重跑）；
- Surefire reports：75；
- Failures/Errors/Skipped：0/0/0。

## 11. 后续切片

下一批实现 Wiki read model cache。优先选择已有 immutable `knowledge_version`/`latest_version_id` 作为 key version，避免再引入模糊的时间戳失效；之后再实现 Memory compiled pack cache，key 必须包含 actor/scope/compiler policy 和选中 Memory Version 指纹。
