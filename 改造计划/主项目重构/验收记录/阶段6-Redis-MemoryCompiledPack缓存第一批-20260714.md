# 阶段 6 Redis Memory Compiled Pack 缓存第一批验收

## 1. 本批范围

- 日期：2026-07-14
- 范围：Chat/Artifact/Research Memory Control Pack cache、actor/target/policy/state fingerprint、TTL+jitter、DB fallback、指标和架构边界
- 状态：Memory compiled pack cache 已接入统一 Compiler；rate/concurrency quota 尚未开始

本批缓存确定性的 `MemoryControlPackResponse`，不缓存 Memory application log，也不将 Redis 作为 Memory lifecycle 真源。每次编译仍从 MySQL 读取轻量状态指纹，确保 review、outcome、append、revoke 和有效期变化立即生效。

## 2. Cache key

Key 结构：

```text
noteweave:v1:cache:memory_compiled_pack:
  {workspaceId}:
  {actorFingerprint}:
  {packType}:
  {requestFingerprint}:
  {compilerPolicyVersion}:
  {stateFingerprint}
```

实际 key 为单行字符串。

- `actorFingerprint`：当前 user ID 的 SHA-256，不暴露原始 user ID；
- `packType`：`chat|artifact|research`；
- `requestFingerprint`：pack type、normalized target、task neighborhood、allowed neighborhoods、evidence policy 和 degradation reasons 的 SHA-256；
- `compilerPolicyVersion`：当前 `memory-compiler-policy-v1`；
- `stateFingerprint`：当前 actor/target 真正可参与编译的 Memory 状态 SHA-256。

skill key、profile、answer mode、Memory statement 和 compile hints 不以明文进入 Redis key。

## 3. 轻量状态指纹

Compiler 每次先查询 active、approved 且 current-valid 的 Memory Object/Version，只读取：

- Memory Object ID；
- latest Memory Version ID；
- utility；
- memory scope 与 owner user；
- object updated-at；
- effective task neighborhoods；
- effective status；
- version valid-from/valid-to。

随后使用当前 actor 执行 USER/WORKSPACE scope admission，并使用当前 target/task neighborhood 排除不相关对象。剩余行按 Memory Object ID 稳定排序，采用 length-prefixed UTF-8 字段生成 SHA-256，避免简单分隔符拼接碰撞。

Fingerprint 会因以下变化而切换：

- Candidate promotion 创建新 ACTIVE/APPROVED Object；
- append 产生新的 latest version ID；
- revoke 或 review 使对象离开 ACTIVE/APPROVED 集合；
- Object APPROVE 重新进入集合；
- outcome 改变 utility、review status 或 lifecycle status；
- scope/owner/neighborhood/updated-at 变化；
- valid window 到期使版本离开 current 集合。

因此无需让每个 Memory writer 主动删除 Redis key，也不依赖 timestamp 单字段判断。

## 4. Request fingerprint

Request fingerprint 覆盖：

- pack type；
- normalized target key；
- exact task neighborhood；
- COMMON/broad/exact allowed neighborhood 集合；
- evidence policy；
- degradation reasons。

Artifact capability adapter 缺失时的 `capability_catalog_unavailable` 会进入 fingerprint。Capability action 改变时 allowed neighborhoods 也会改变，不会复用旧 pack。

## 5. Cache value 与 provenance

Value 使用 `memory-compiled-pack-v1` envelope，保存：

- 完整 `MemoryControlPackResponse`；
- cache key fields，用于读取时再次验证；
- version-aware `MemoryReferenceResponse`；
- `MemoryCompilationTraceResponse`。

Cache hit 保留原有：

- selected Memory Object IDs；
- exact latest Memory Version IDs；
- utility、scope/neighborhood priority 和 selection reason；
- token budget、candidate/selected/dropped、truncated 和 degradation trace。

`logPackUsage` 仍由 Chat、Artifact 和 Research 调用侧在具体 target 创建后执行。缓存不会跳过 `memory_usage_log`，application provenance 仍绑定实际 target 与 Memory Version。

## 6. 缓存命中成本与并发正确性

Cache hit 仍执行轻量 state query，但跳过：

- compile-policy JSON 读取和反序列化；
- 完整 ObjectRow hydration；
- scope/neighborhood priority 排序；
- 增量去重；
- token estimate 与对象级 budget admission；
- Control Pack/trace 重新组装。

若 reader 计算 fingerprint F1 后 writer 提交 F2，reader 最多把新编译结果写入旧 F1 key；后续请求重新计算 F2，不会读取 F1。旧 key 等待 TTL 清理，不污染当前状态。

## 7. TTL 与降级

默认配置：

| 配置 | 默认值 |
| --- | ---: |
| `NOTEWEAVE_MEMORY_COMPILED_PACK_CACHE_ENABLED` | true |
| `NOTEWEAVE_MEMORY_COMPILED_PACK_CACHE_TTL_SECONDS` | 180 |
| `NOTEWEAVE_MEMORY_COMPILED_PACK_CACHE_JITTER_SECONDS` | 30 |

TTL 最低为 1 秒，jitter 最低为 0。

以下情况执行完整编译：

- cache disabled；
- Redis bean 不存在；
- key miss；
- schema/key mismatch；
- JSON 解析失败；
- Redis read/write error。

Redis 故障不会导致空 Control Pack，也不会放宽 scope/review/status admission。

## 8. 指标

统一 cache counter：

```text
noteweave.cache.requests{cache=memory_compiled_pack,result=*}
```

result：`hit`、`miss`、`load`、`disabled`、`invalid`、`error`。

Compiler DB timer：

```text
noteweave.memory.compiler.db.load{phase=state|pack,result=success|error}
```

指标不携带 Workspace、user、target、Memory ID 或 fingerprint 等高基数 tag。

## 9. 架构边界

`ArchitectureBoundaryTest` 固定：

- `MemoryCompilerService` 依赖 `MemoryCompiledPackCache`；
- `MemoryCompiledPackCache` 不依赖 `MemoryPromotionService`；
- 不依赖 `MemoryVersionService`；
- 不依赖 `MemoryReviewService`；
- 不依赖 `MemoryOutcomeService`。

Cache 是只读 adapter，不拥有 Memory lifecycle 写入。

## 10. 测试覆盖

新增：

- `MemoryCompiledPackCacheTest` 4/4：fingerprint key、180 秒 TTL、JSON round-trip、schema/key mismatch、Redis error 和 bean missing；
- `MemoryCompilerServiceTest` 扩展为 4/4：相同 fingerprint cache hit；utility/updated-at 改变后 fingerprint 切换并重新编译新 utility reference。

回归：

- `Phase5MemoryContractTest`：9/9；
- `ArchitectureBoundaryTest`：26/26；
- Memory 定向组：43/43。

## 11. 真实验证结果

执行：

```powershell
.\mvnw.cmd -f backend\pom.xml '-Dtest=MemoryCompiledPackCacheTest,MemoryCompilerServiceTest,Phase5MemoryContractTest,ArchitectureBoundaryTest' test
.\mvnw.cmd -f backend\pom.xml test
git diff --check
```

结果：

- Backend 全量：286/286；
- Surefire reports：76；
- Failures/Errors/Skipped：0/0/0；
- `ArchitectureBoundaryTest`：26/26。

## 12. 后续切片

下一批实现 Redis 分布式配额：token bucket 限制请求速率，leased semaphore 限制并发。Redis 不可用时必须回退本机保守上限；lease 需要超时恢复，Streams/cache/quota 必须使用独立 namespace 和低基数指标。
