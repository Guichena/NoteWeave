# 阶段 6：Knowledge、Memory 与前端生产化

> 目标：拆解剩余巨型领域服务，形成可解释的 Memory 闭环，完成 Redis 缓存/配额和前端模块化。
> 前置：阶段 1-5 的权限、运行、Source 和 Answer 契约稳定。
> 剩余施工已收缩为简历展示目标，具体以《阶段 5-6：简历展示最小收尾计划》为准；既有前端和 Memory 能力全部保留，前端仅处理直接影响本地演示的事项。Memory 的后续设计与重构另行立项，不在当前收尾范围内。

## 0. 当前施工进度

Knowledge 查询、Graph、Version、Command、Governance 与旧 facade 拆除六批已落地：

- 新增 `KnowledgeQueryService` 与 `WikiRetrievalQueryPort`，Wiki Answer 检索只依赖只读 Port，不再注入 `KnowledgeService`；
- `KnowledgePageHit`、`WikiPageContext` 从巨型 Service 的嵌套 record 提升为独立 read model，Answer/Knowledge 不再通过 Service 内部类型耦合；
- 新增共享 `KnowledgeWikiSearchEngine`，Wiki API search、graph/list read model 与 Answer retrieval 复用同一批数据库行和原 title/summary/content、graph、citation、version 权重；无命中时仍按原 updated-at 顺序返回最多 5 个零分页面；
- `KnowledgeQueryService` 已承接 search、Answer read port、Wiki home/index、item list/detail、citation/link read model 和 source-backed page lookup；Controller 与 Wiki ingest 最后几个 read 调用已迁移；
- 新增 `KnowledgeGraphService`，`KnowledgeController` 的 wiki search/graph 端点分别直接依赖 Query/Graph 服务；旧 facade 不再提供 `searchWikiPages/getWikiGraph`；
- overview/ego graph 保持原 mode、page kind filter、depth 1..3、limit 4..120、degree/citation/version/title overview 排序、无向 BFS 邻居排序、selected edge 裁剪与 invalid center 回退语义；
- 新增 `KnowledgeVersionService`，统一承接 version list/detail/append；`KnowledgeController` 的三个版本端点与旧 facade 内部所有 append 入口均只调用该服务，不保留第二套 version number、citation 或 latest pointer 写入算法；
- append 在事务内先以 `select ... for update` 锁定 KnowledgeItem row，再分配 `max(version_no) + 1`，并发请求被同一 item row 串行化；旧 version 保持 immutable，新 version 写入后原子切换 `latest_version_id`；
- 新增 `KnowledgeWikiMutationService`，Version 写入继续保持 Wiki 内容 normalize、显式 link projection、page kind 推断、出链重建和 audit log；normalize 生成的 `[[...]]` 按最终显式 Wiki link 记录为 `WIKI_LINK`；
- version citation 继续按请求或来源消息中的 sort order 绑定，detail 按相同顺序返回；
- 新增 `KnowledgeCommandService`，统一承接 create、save-as-note、rename、delete 与 Wiki upsert；Controller、Wiki ingest 和 Artifact Knowledge writeback 均直接依赖 command owner，旧 facade 不再暴露这些写方法；
- create 继续原子写入 KnowledgeItem + immutable version + latest pointer，并保持来源消息 citation 顺序、Wiki normalize/link resolve/page-kind/audit；同标题 Wiki 继续转为 Version append；
- rename 继续更新 page kind、解析既有 target、重写 incoming Wiki link 正文并通过 `KnowledgeVersionService` 追加 immutable version；delete 继续 soft delete、清理 outgoing、将 incoming link 置为 unresolved 并记录 audit；
- 新增 `KnowledgeGovernanceService`，统一承接 Wiki stats、lint/issues、rebuild advice、log/task projection、link rebuild 与 auto-fix；Controller 与 Wiki ingest 不再通过旧 facade 访问治理用例；
- lint 保持 BROKEN_LINK 高优先级以及 ORPHAN_PAGE、MISSING_SOURCE、CONTENT_STALE、PLACEHOLDER_CONTENT 诊断与过滤；rebuild 仍通过 Version owner 追加 normalize 后的新版本并保留 citation；auto-fix 仍通过 Command owner 创建补缺页；
- 旧 `KnowledgeService` 已从 1928 行逐步清空并删除；生产代码中不再存在该类型，Knowledge 用例分别由 Query、Graph、Version、Command、Governance 与 Wiki Mutation 边界持有；六批未改变 CRUD、immutable version、link rebuild、governance、Wiki ingest、citation 排序或 HTTP 返回结构；
- 新增 H2 查询契约测试，固定相关排序、无命中 fallback、一跳 outgoing/backlink 和 citation sort order；ArchUnit 禁止 Wiki evidence adapter 回退依赖 `KnowledgeService`，并禁止 `KnowledgeQueryService` 反向依赖旧 facade；
- 新增 Knowledge Version H2 契约测试，固定并发 append 的 `1/2/3` 版本序列、immutable old version、latest pointer、citation 顺序、Wiki normalize/link/page-kind/audit；ArchUnit 禁止 `KnowledgeVersionService` 反向依赖旧 facade；
- 新增 Knowledge Command H2 契约测试，固定来源消息 citation 顺序、Wiki normalize/link、rename incoming version、alias 保留、delete 断链与 audit；ArchUnit 禁止 `KnowledgeCommandService` 反向依赖旧 facade；
- 新增 Knowledge Governance H2 契约测试，固定 issue 优先级/filter/stats/advice、rebuild immutable version 与 citation、auto-fix 补缺页和断链收敛；ArchUnit 禁止 `KnowledgeGovernanceService` 反向依赖旧 facade；
- Query H2 契约进一步固定 Wiki home/index、list/detail、source-backed lookup 和 citation/link 顺序；架构测试直接断言 `KnowledgeService` 类型不存在；
- 新增 `MemoryCandidatePolicy`、`MemoryCandidateGate` 与 `MemoryStatementMatcher`，Signal confidence、Candidate utility/risk/scope/review gate 和 Promotion duplicate merge 已收敛到共享、版本化 policy；
- `memory-candidate-policy-v1` 固定 evidence `0.70`、auto-promotion utility `0.60`、review risk `0.70` 与 equivalent statement similarity `0.85` 阈值，不再把评分和自动提升条件散落在 Service；
- Signal、Candidate 与 promotion ledger 已持久化 policy version，Candidate 额外记录 risk score 与 scope status；弱模型推断、冲突 active memory 必须 review，低风险显式 negative preference 保持可自动提升；
- Candidate novelty/conflict 与 Promotion merge 复用同一 NFKC、标点/空白规范化和 token/bigram 相似度，标点变体不再产生重复 Memory object；
- Architecture gate 要求 Signal/Candidate/Promotion 分别依赖共享 policy/gate/matcher；V033 使用独立 `ADD COLUMN`，兼容 H2/MySQL；
- 新增 `memory_version` immutable content snapshot、`memory_object.latest_version_id/current_version_no` 与独立 `MemoryVersionService`；Promotion 在同一事务创建 Memory Object 和 v1，并保存 candidate/policy/risk/scope provenance；
- append 通过 `select ... for update` 锁定 Memory Object row，串行分配连续 version number；旧版本正文与 compile hints 不覆盖，只关闭 `valid_to` 并转为 `SUPERSEDED`，新版本记录 `supersedes_version_id`；
- revoke 将 latest version 与 object 原子转为 `REVOKED`；Compiler 只消费 latest、ACTIVE 且位于 valid window 的版本，撤销后立即停止应用；
- V034 之前已有 Memory Object 保留兼容读；首次 append/revoke 时惰性生成 `memory-legacy-bootstrap-v1` v1，再执行正常 lifecycle transition；
- 新增 version list/detail、append、revoke API，响应使用独立 `MemoryVersionResponse/MemoryCompileHintsResponse`，不暴露 Signal service 内部类型；
- 并发 H2 契约固定两个 append 产生 `1/2/3`、latest=v3、supersede chain 完整、v1 content immutable；Architecture gate 要求 Promotion 通过 Version owner 创建版本且 Version owner 不反向依赖 Promotion；
- 新增 `MemoryReferenceResponse`，Control Pack 在保留 `memory_object_ids` 兼容字段的同时输出 object/version/utility 引用；Chat、Artifact initial/regenerate、Research initial/resume 均按实际 target 写入 version-aware application log；
- V035 将 `memory_usage_log` 扩展为结构化 outcome ledger，并为 Memory Object 增加 utility、application/positive/negative/edit/retry counters、review status、outcome policy version 与 last outcome；
- 新增 `memory-outcome-policy-v1`，统一 ACCEPTED/POSITIVE/EDITED/RETRIED/NEGATIVE 评分、指数更新、review 和 stale 阈值；首次明确负反馈立即 `REVIEW_REQUIRED`，三次高比例 adverse outcome 进入 `STALE`；
- 新增 target-scoped outcome API，一次反馈原子覆盖该 target 尚未反馈的所有 Memory application；重复反馈不重复计数，返回 `MEMORY_APPLICATION_NOT_FOUND`；
- Compiler 只接纳 `ACTIVE + APPROVED + current valid version`，负反馈触发 review 后立即停止应用，append 新版本作为人工复核写入口会恢复 `ACTIVE + APPROVED`；
- 定向契约固定 Chat/Artifact/Research version provenance、utility 下降、review、STALE、Compiler 排除和 outcome 幂等；Architecture gate 要求 Outcome service 依赖独立版本化 policy；
- 新增通用 `CapabilityCatalogPort`，`ArtifactSkillCatalogService` 作为 adapter 实现；Memory package 不再 import 或依赖 Artifact package，Capability adapter 缺失时按 normalized skill neighborhood 继续编译并在 trace 标记 `capability_catalog_unavailable`；
- 新增 `memory-compiler-policy-v1`，声明 Chat `320`、Artifact/Research `480` token proxy budget，统一 USER/WORKSPACE scope admission、exact/broad/common neighborhood priority、utility、updated-at freshness 与稳定 ID tie-break；
- Compiler 先按 scope -> neighborhood specificity -> utility -> freshness -> ID 排序，再以整个 Memory Object 为最小单元执行增量去重和预算准入；超预算对象整体丢弃，不产生半条规则；
- token 估算使用确定性 proxy：汉字按单 token、非空白非汉字按每四字符约一个 token，再加固定分隔开销；trace 明确标识这是 policy budget，不宣称等同 Provider tokenizer；
- Control Pack 新增 `compilation_trace`，记录 policy、maximum/selected tokens、candidate/selected/dropped、truncated、degraded/reasons；Memory reference 记录 scope/neighborhood priority 和 selection reason；历史 JSON 缺字段时回退 `legacy-unbounded` trace；
- 单元与 H2 契约固定 USER scope 隔离、精确 neighborhood 优先于高 utility broad/common、utility/freshness tie-break、对象级截断、正常 Capability adapter 与缺失 adapter 降级；Architecture gate 禁止 Memory 依赖 Artifact；
- 新增 `MemoryReviewService` 与 Candidate/Object 统一 review read model；队列按冲突 Candidate `100`、STALE Object `90`、REVIEW_REQUIRED Object `80`、普通 Candidate `70` 排序，并支持 kind filter 和有界 limit；
- Candidate review 支持 `APPROVE/REJECT/REPLACE_EXISTING`；冲突 Candidate 禁止普通 `APPROVE`，`REPLACE_EXISTING` 通过共享 `MemoryStatementMatcher` 定位冲突 ACTIVE Object，经 `MemoryVersionService.revoke` 撤销旧对象，再由 `MemoryPromotionService.promoteCandidate` 提升新对象；
- Object review 支持 `APPROVE/REVOKE`；APPROVE 恢复 `ACTIVE + APPROVED` 并重新进入 Compiler，REVOKE 同步写入 `REVOKED + REJECTED`，且撤销对象不会重新进入队列；
- Candidate 队列和决策只允许当前用户访问；Object 队列允许 Workspace scope 或当前用户自己的 USER scope；所有入口继续执行 `MEMORY_REVIEW` 权限检查；
- V036 新增 `memory_review_decision` 审计表，记录 review kind/id、decision、reason、actor、提升结果和被撤销对象；Review Service 只编排 Promotion/Version owner，不复制对象创建或 immutable version 生命周期算法；
- ACL Redis cache key 已统一为 `noteweave:v1:cache:acl:{workspaceId}:{userId}:{aclVersion}`；不包含原始敏感文本，成员变更继续通过递增 `aclVersion` 使旧正向/拒绝缓存自然失效；
- ACL 正向缓存默认 `300s`、拒绝缓存默认 `30s`，共享最多 `60s` jitter，三者均可通过环境变量配置；短拒绝缓存防止不存在成员反复穿透数据库，但只在版本匹配且 value 明确为 `DENIED` 时直接拒绝；
- Redis disabled、bean missing、miss、invalid value 或 command error 均返回 cache miss 并回源 MySQL；数据库查不到 active user/member 时默认拒绝并写短拒绝缓存，不以 Redis 可用性决定授权；
- ACL cache 新增 `noteweave.cache.requests{cache=workspace_acl,result=*}` 与兼容的专用计数，数据库 fallback 新增 `noteweave.security.acl.db.load{result=*}` timer；
- V037 为 Workspace 新增单调 `source_catalog_version`；Source 列表 key 固定为 `noteweave:v1:cache:source_catalog:{workspaceId}:{catalogVersion}`，value 使用显式 `source-catalog-v1` JSON schema；
- `SourceService.listSources` 在权限检查后只读取 catalog version；cache hit 不扫描 Source 表，miss/disabled/schema-version mismatch/Redis error 均回源原排序 SQL并回填 cache，空列表也可缓存；
- Source catalog 默认 TTL `120s`、jitter `30s`，可独立关闭或通过环境变量配置；cache 只保存 API 已返回的 metadata read model，不保存文件正文、chunk 或 token；
- 上传创建、解析状态更新、ES 全量投影完成、Generated Source 创建、Research 报告入库和 Source 删除六类 writer 均在事务边界推进共享 catalog version；旧 key 不扫描删除，由新版本立即旁路并等待 TTL 清理；
- 并发 reader 即使在读取旧 version 后加载到新 DB 状态，也只会把结果写入旧 key，后续 reader 已使用新 version，不会污染当前 read model；ArchUnit 固定六个 writer 必须依赖 `SourceCatalogVersionService`；
- Source cache 使用 `noteweave.cache.requests{cache=source_catalog,result=*}`，DB fallback 使用 `noteweave.source.catalog.db.load{result=success|error}` timer，均不携带 Workspace/Source 等高基数 tag；
- Wiki 页面详情新增 immutable `KnowledgePageVersionSnapshot` cache，key 为 `noteweave:v1:cache:wiki_page_version:{workspaceId}:{itemId}:{latestVersionId}`，value 使用 `wiki-page-version-v1` schema；
- cache 只保存 version content、summary、source message、ordered citations 与 version created-at；item status/title/page kind/updated-at 和 outgoing/backlinks 每次从 DB 实时读取，避免把动态关系错误绑定到 immutable version；
- soft delete 在 cache lookup 前拒绝，rename 直接读取最新 item state，append version 自动切换 latest-version key；旧 immutable snapshot 无需主动删除，默认 TTL `600s`、jitter `120s` 后自然清理；
- Wiki cache disabled、bean missing、miss、schema/identity mismatch、JSON/Redis error 均回源 DB；使用 `noteweave.cache.requests{cache=wiki_page_version,result=*}` 和 `noteweave.knowledge.wiki.page.db.load{result=success|error}`；
- Architecture gate 要求 `KnowledgeQueryService` 通过 `WikiPageVersionCache + KnowledgePageVersionSnapshot` 读取，同时禁止 cache adapter 依赖 Command/Version mutation owner；
- 修复 `ConversationEventMux` replay/terminal 注册竞态：subscriber 在 channel 锁内先 prime replay，再启动串行 drain，避免 live terminal 先关闭 subscriber 后丢失已截取 replay；该测试连续 5 轮和全量回归均稳定通过；
- Memory compiled pack 新增 `memory-compiled-pack-v1` cache；key 由 Workspace、SHA-256 actor fingerprint、pack type、request fingerprint、compiler policy version 和 actor/target-scoped Memory state fingerprint 组成，不暴露 user ID、skill/profile 或 Memory 正文；
- Compiler 每次先执行轻量状态查询，只读取 active/approved/current Memory 的 object/version ID、utility、scope、owner、updated-at、neighborhood 和 valid window；按当前 actor/target 过滤后生成稳定 fingerprint；
- cache hit 跳过 compile-policy JSON 查询、scope/neighborhood 排序、增量去重和 token budget 组装，但不跳过状态指纹查询，因此 review、revoke、append、outcome utility/status 和有效期变化立即切换 key；
- request fingerprint 覆盖 target key、task neighborhood、allowed neighborhoods、evidence policy 与 degradation reasons；Capability adapter 可用性/action 变化不会误复用旧 Artifact pack；
- cached `MemoryControlPackResponse` 保留 version-aware references 和 compilation trace；Chat/Artifact/Research 的 `logPackUsage` 仍在调用侧独立执行，cache 不吞掉 application provenance；
- Memory cache 默认 TTL `180s`、jitter `30s`；disabled、bean missing、miss、schema/key mismatch、JSON/Redis error 均执行完整编译；使用统一 cache counter 和 `noteweave.memory.compiler.db.load{phase=state|pack,result=*}` timer；
- Architecture gate 要求 Compiler 依赖 cache，同时禁止 cache adapter 依赖 Promotion/Version/Review/Outcome 写 owner；
- Redis quota key 使用 `noteweave:v1:quota:{environment}:{rate|concurrency}:...` 独立命名空间；rate key 只保存 Workspace、SHA-256 actor fingerprint 与低基数 workload，concurrency key 保存 Workspace、workload 与不透明 task lease token；
- Chat 在创建 AnswerRun 前执行 Workspace/actor/workload token bucket；Research/Artifact Task 在创建与 redrive 时执行 rate + leased semaphore，在 heartbeat/progress/waiting 时严格续约，在完成/失败/事务回滚后释放；
- rate token bucket、lease acquire、strict renew 与 release 均使用 Redis Lua 原子执行；renew 不会把已过期 lease 静默重新获取，过期 owner 必须收到 `WORKLOAD_LEASE_LOST`；acquire/renew 清理过期成员并累计 recovery 指标；
- Redis unavailable/command error 时使用独立本机 token bucket 与保守 semaphore；Redis 成功 lease 同步维护本机 shadow，使运行中 Redis 故障仍可在原本机严格续约；跨实例共享正确性只由 Redis 提供，不把本机 shadow 描述为分布式真源；
- 新增 `noteweave.quota.decisions`、`noteweave.quota.rejections` 与 `noteweave.quota.lease.recovered` 低基数指标；Compose 和 `.env.example` 暴露 environment、rate、lease、分布式/本机并发配置；
- Backend 常规全量 `300` 项中 `299` 通过、外部 Redis 集成项按显式开关跳过；真实 Redis 双实例集成另行启用后 `1/1` 通过，证明共享 rate、跨实例 semaphore、lease expiry recovery 与旧 owner renew rejection；最新 Backend 容器健康，真实 Chat 请求的 quota meter 为 `backend=redis/result=granted`，Redis key 使用 `development` namespace；
- `ArchitectureBoundaryTest` `27/27` 通过，Task lifecycle 继续作为 Research/Artifact 长任务 lease 的唯一 owner。
- 前端新增 `src/shared/api`，统一持有 API base URL、envelope 解包、JSON verbs、raw/text 请求、Bearer 注入、correlation id、AbortSignal、401 callback，以及带 `status/code/requestId/correlationId` 的 `ApiError`；`App.tsx` 不再直接调用 `fetch` 或声明第二套 HTTP helper；
- 前端新增 `src/shared/event-stream`，统一持有 SSE parser、流式 consume 与 Conversation reconnect/cursor client；旧顶层 `sse.ts/conversationStream.ts` 兼容桥已删除，测试与生产调用均直接引用 shared owner；
- 新增 `src/features/workspace` 与 `src/features/sources` 第一批 feature API/model owner；Workspace create 和 Source list/text-upload/delete 已从 `App.tsx` 迁移，Source upload 顺序固定为 create session -> binary chunk -> complete；
- 新增 `src/features/conversations` 与 `src/features/answers`，Conversation create、Answer send/query 和对应模型均由 feature API 持有，`App.tsx` 不再拼接这三类 endpoint；
- 新增按 `runId` 标准化的 `AnswerRunStore`：conversation event id 去重、`run_sequence` 单调准入、citation 内容去重、终态后拒绝迟到 delta/冲突终态，`conversation.snapshot` 可权威校正运行状态；fallback answer stream 复用同一 reducer，不再维护第二套正文/citation/terminal 归并算法；
- Answer terminal 后通过 `answersApi.getRun()` refetch MySQL AnswerRun snapshot，立即校正最终正文和错误状态，同时保留 streamed citations；waiter 的 resolve/reject/clear 生命周期由 store 统一持有，会话切换时集中清理；
- `ui-contract-check.mjs` 新增架构门禁，禁止 `App.tsx` 回退直接 fetch、重建 HTTP helper、重复 Workspace/Source type，并要求真实引用 shared transport 与已迁移 feature API；
- 前端全量 `61/61`、TypeScript + Vite production build（`58` modules）与 UI contract 均通过；新 bundle 为 `assets/index-BW7eICMB.js`（`475.52 kB`，gzip `137.00 kB`）；
- 同一 Compose 网络内以现有 Nginx runtime 挂载新 dist 的一次性容器通过首页、asset、Workspace/Conversation/Answer proxy smoke；真实 Conversation SSE 观察到 `conversation.snapshot -> answer.delta -> answer.completed`、event id `1/2`，终态 AnswerRun 查询为 `COMPLETED` 且正文长度 `93`；一次性容器、临时 image tag 和本机 preview 均已清理；
- canonical Frontend 镜像 rebuild 连续两次被 Docker Hub `node:22-alpine/nginx:1.27-alpine` metadata TLS handshake timeout 阻塞，未用旧镜像冒充新 build 成功；外部 registry 恢复后需重跑标准 `docker compose --profile app up -d --build frontend`。
- 新增 `src/features/knowledge/model.ts`，统一持有 Wiki home/index/page/link/graph、governance issue/log/advice、Knowledge item/version/citation 等 read/write model；`App.tsx` 已删除对应的二十余个重复类型；
- 新增 `KnowledgeApi`，统一持有 settings、home/index/stats/search、issue/log/advice、graph、item/version CRUD、save-as-note、rebuild 与 auto-fix 协议；search、issue filter 和 overview/ego graph budget/query serialization 一并下沉，`App.tsx` 不再拼接 Knowledge endpoint 或查询参数；
- Knowledge 首批只迁 model/transport owner，React 页面状态、刷新组合和视觉交互保持原位；Artifact version writeback 仍由 Artifact endpoint owner 持有，不因写入目标是 Note/Wiki 而错误归入 Knowledge API；
- `ui-contract-check.mjs` 已禁止 `App.tsx` 回退重复 Wiki/Knowledge type、query builder 或 Knowledge endpoint，并要求真实调用 Knowledge feature API；新增 API 契约覆盖 URL 编码、issue filters、重复 graph kinds、item/version 与治理 mutation path；
- 前端全量更新为 `64/64`，TypeScript + Vite production build 通过（`59` modules）；bundle 为 `assets/index-CVSU7Jc6.js`（`476.24 kB`，gzip `137.32 kB`），UI contract 与本批 `git diff --check` 均通过。
- 新增 `useKnowledgeState` 与纯 `KnowledgeServerState` reducer，统一持有 Wiki home/index/stats/issues/log/advice/graph、选中 item/version/log、search 和 filtered issues；`App.tsx` 已删除十四组 Knowledge server-state `useState` 和三组 debounce request effect，只保留草稿、筛选、graph mode/kinds 与 UI 编排；
- home/index/stats/governance/graph/selection 以单次 reducer snapshot 原子提交，latest version 由选中 item detail 确定性投影；Source 上传继续使用轻量 home refresh，原 `applyWikiHome` 路径则使用完整 snapshot refresh，未无故扩大上传请求规模；
- 新增 `LatestKnowledgeRequestGate`，workspace 切换会 abort 全部在途 read/settings 请求；search、filtered issues、selected log、home/snapshot、selection、graph、version 与 issue focus 使用独立 latest-only channel，同 channel 新请求取消旧请求，迟到响应不能污染新工作台或新筛选状态；
- `KnowledgeApi` 的全部 read protocol 与 settings update 已支持 `AbortSignal` 透传；Hook 卸载统一 cancel，后台 settings 读取失败保持默认状态，不产生未处理 Promise rejection；
- `App.tsx` 不再直接调用任何 `knowledgeApi.get*/search` read protocol，也不保留 `applyWikiHome/loadWikiGraph` 或服务端 Wiki setter；架构门禁固定 Hook import/动作接线并禁止上述 owner 回流；
- 新增 reducer/workspace reset/latest-version/request-gate 测试和 read cancellation 透传测试；前端全量更新为 `70/70`，TypeScript + Vite build 通过（`61` modules），bundle 为 `assets/index-laerR9Hm.js`（`482.34 kB`，gzip `138.88 kB`），UI contract 与 `git diff --check` 通过；production preview 首页、实际 JS/CSS asset 均返回 `200`，临时进程已停止。
- 前端新增 `src/features/memory`，包含 Memory review model/API、纯 reducer、workspace-safe request gate、review Hook 和独立三栏工作台组件；前端此前完全没有 Memory endpoint 或 UI，本批不是兼容桥迁移，而是补齐阶段 6 缺失的人工审核闭环；
- Memory API 统一持有 `ALL/CANDIDATE/OBJECT` review queue、review decision、object immutable version list/detail/append 与 revoke 协议，全部 read/mutation 支持 `AbortSignal`；limit 在前端约束为后端同口径的 `1..200`；
- review Hook 以 workspace 为隔离边界，queue、selection、versions、queue/versions loading、mutating/error 与 last decision 只有一个 owner；queue/versions/mutation 使用独立 latest-only channel，workspace/kind/selection 切换会清空旧投影并取消不再相关的请求；
- Memory 工作台提供 kind filter、priority/risk/utility/policy 解释、审计理由、Candidate `APPROVE/REJECT`、冲突 Candidate `REPLACE_EXISTING/REJECT`、Object `APPROVE/REVOKE`、版本链浏览与 immutable version append；冲突 Candidate UI 不提供普通 APPROVE，严格匹配后端决策集合；
- `App.tsx` 只新增 `memory` shell view、`MemoryReviewWorkbench` 组件分支与“打开 Memory 审核”入口，不持有 Memory endpoint、server state、timer 或 mutation algorithm；UI contract 禁止 `/memory` API、`memoryApi` 和 review state 回流 App，并要求真实组件接线；
- Memory 定向测试 `10/10`，前端全量更新为 `80/80`；TypeScript + Vite build 通过（`65` modules），bundle 为 `assets/index-BiwMy4nQ.js`（`495.50 kB`，gzip `142.79 kB`），UI contract 与 `git diff --check` 通过；production preview 首页与实际 JS/CSS asset 均返回 `200`；
- 真实 Backend `GET /memory/reviews?kind=ALL&limit=50` 返回 `200/OK`、空队列与 request id；应用内浏览器已验证初始 Memory 入口存在且 workspace 创建前禁用，创建请求在本机健康后端最终成功但耗时约 `92s`，浏览器控制插件随后重置且运行文件不可恢复，因此未把完整三栏视觉交互声明为已验收。
- 前端新增 `src/features/executions`，以 `ExecutionTask/ExecutionEvent/ExecutionSnapshot/ExecutionRecord` 统一 Workspace Task 与 Research Task 的 server-state；`WaitContext` 及 provider/approval/reason 类型从页面格式化模块迁入 feature model，`runStatus.ts` 只保留显示格式化与兼容 re-export；
- `ExecutionsApi.load()` 统一执行 task snapshot、持久化事件 SSE snapshot 解析和 terminal snapshot refetch；后端 `TaskService` 的 SSE 现在输出真实数据库 `task_event.id`，前端据此按 event id 去重与稳定排序，旧数据仍有显式 legacy id fallback；
- `useExecutionRegistry` 以 task id 标准化保存多个 execution，只对 tracked 且非终态任务执行 `2.5s` polling；每个任务拥有独立 `AbortController`，workspace 切换与 Hook 卸载统一取消请求和清理 timer。Task events endpoint 当前是一次性数据库 SSE 快照，因此 polling 被明确保留为非持续流资源的降级，不伪装成 live subscription；
- `App.tsx` 只保留 `latestTaskId/latestResearchTaskId` 与 `loadExecution(taskId)` 调用，已删除四套重复的 task fetch、events fetch、SSE parser、Task/StreamEvent 类型和 task/events server-state setter；Research 与普通 Task 共用同一 registry，页面不再是执行状态真源；
- `ui-contract-check.mjs` 已禁止 `App.tsx` 回退 `/api/v2/tasks/`、`parseEventStream`、内嵌 Task/StreamEvent 类型或 Task/ResearchTask server-state setter，并要求真实接入 executions model、Hook、registry 与 task-id state；
- Execution/runStatus 定向测试 `13/13`，前端全量更新为 `24` 个文件、`86/86`；TypeScript + Vite build 通过（`68` modules），bundle 为 `assets/index-SnyDZAXj.js`（`499.37 kB`，gzip `143.93 kB`），CSS 为 `assets/index-bzc9kVQ8.css`（`26.29 kB`，gzip `5.26 kB`）；UI contract 与 `git diff --check` 通过，旧 Task endpoint/parser/setter 静态搜索零残留；
- Backend `Phase1And2ContractTest` `11/11` 通过，固定 Task SSE `id:` 契约；production preview 首页返回 `200`，包含 `#root` 与当前 bundle 引用，临时进程已清理。
- 前端新增 `src/features/artifacts` 与 `src/features/research` 第一批 API owner；Artifact skills/job/version/save-as-source/writeback/regenerate/rollback/compare/export/create 和 Research run list/create/detail/checkpoint/resume/save-report-as-source 路径已全部从 `App.tsx` 下沉；
- Artifact job/version/save-source 响应、file metadata、create/writeback input 与 Research create/save-report command model 已迁入 feature model；Research 的大体量只读投影和展示派生类型暂留 `App.tsx`，明确随下一批 server-state owner 一起迁移，避免协议迁移同时重写闭环审计 UI；
- Artifact/Research read API 支持 `AbortSignal` 透传；Artifact PDF 下载 URL 由 API owner 通过共享 client 生成，页面不再直接调用 `apiUrl`；App 中 `/api/v2/skills`、`/artifact-jobs`、`/research-runs` 静态搜索零残留；
- `ui-contract-check.mjs` 已禁止 Artifact/Research endpoint 回流，并要求真实导入和调用两个 feature API；新增协议测试固定全部 read/mutation path、compare query、PDF export URL 与 cancellation；
- Artifact/Research API 定向测试 `5/5`，前端全量更新为 `26` 个文件、`91/91`；TypeScript + Vite build 通过（`70` modules），bundle 为 `assets/index-BVvJsBt0.js`（`500.16 kB`，gzip `144.22 kB`），CSS 保持 `assets/index-bzc9kVQ8.css`（`26.29 kB`，gzip `5.26 kB`）；UI contract 与本批 `git diff --check` 通过，production preview 首页返回 `200` 并引用当前 bundle；
- 主 chunk 首次越过 Vite `500 kB` 提示线；本批没有隐藏或抬高阈值。下一批将 Research server-state/组件拆分与 route-level code splitting 一起处理，避免把超大 `App.tsx` 继续编入首屏主 chunk。
- 前端新增 `ResearchServerState`、纯 reducer 与 `LatestResearchRequestGate`；run history、当前 run detail、selected/comparison checkpoint 和 resume-source checkpoint 均由 `useResearchState` 作为唯一 server-state owner 持有，workspace 切换、重新选 run、同 channel 新请求和 Hook 卸载会取消旧请求并清空不再相关的投影；
- `App.tsx` 已删除上述八组 Research server-state `useState` 及 setter，保留研究问题、profile、约束、资料范围、筛选和展示/交互草稿；Research 的大体量 response/展示派生类型暂仍与 JSX 共存于 App，待下一批抽出独立工作台组件时一并迁入 read model，避免只迁 type 而保留同一巨型渲染边界；
- history/detail/checkpoint/comparison 统一通过独立 latest-only channel 读取，detail snapshot 会原子提交 resume source 与可保留的 selected checkpoint；新 run 先清空旧 checkpoint/comparison/resume 投影再加载，避免乱序响应污染新的闭环审计面板；
- `ui-contract-check.mjs` 已禁止 Research server-state `useState`、所有对应 setter 以及 Artifact/Research endpoint 回流 App，并要求实际接入 `useResearchState`、history/detail/checkpoint/comparison action；新增 reducer/gate 测试覆盖 workspace reset、new-run selection reset 和 same-channel/workspace abort；
- Research 定向测试 `5/5`，前端全量更新为 `27` 个文件、`94/94`；TypeScript + Vite build 通过（`72` modules），bundle 为 `assets/index-DvrLKQ1S.js`（`504.02 kB`，gzip `144.92 kB`），CSS 保持 `assets/index-bzc9kVQ8.css`（`26.29 kB`，gzip `5.26 kB`）；UI contract 与本批 `git diff --check` 通过，production preview 首页返回 `200` 并引用当前 bundle；
- 当前没有将空壳 `React.lazy()` 误报为拆包成果：Research JSX 仍在 `App.tsx`，故 bundle 尚未下降。下一批须先抽出真实 `ResearchWorkbench` 组件及其 read model，再用 `/research` 动态 import 验证产生独立 chunk。
- Research 的 run summary/detail、checkpoint、intent、counterfactual、closed-loop、process summary、timeline、signal 与 read-model response type 已统一迁入 `src/features/research/model.ts`；`ResearchApi` 与 `useResearchState` 已改用具体 feature model，不再让调用侧用泛型重新声明相同 JSON 投影；
- 真实 `ResearchSidebar` 已从 `App.tsx` 抽出，承接独立研究入口的显式问题/目标/profile/约束/资料范围、run history、关键转折时间线和闭环路径摘要；所有表单值、派生数据与回调以显式 props 传入，保留原控制台行为，不隐式改变 Research 发起契约；
- `App.tsx` 以 `React.lazy` + `Suspense` 动态导入该真实组件，并只保留加载中的 aside fallback；UI contract 同时禁止“独立研究工作台”主 JSX 回流，并读取 Sidebar 文件确认关键控制台内容仍然接线；
- 前端全量仍为 `27` 个文件、`94/94`；TypeScript + Vite build 通过（`74` modules），实际产出 `assets/ResearchSidebar-I8VCc1Nk.js`（`10.25 kB`，gzip `3.71 kB`）和主 bundle `assets/index-DyK2dkYT.js`（`497.87 kB`，gzip `143.82 kB`），Vite 500 kB warning 已消失；production preview 首页和动态 Sidebar asset 均返回 `200`，临时进程已清理；
- Research 主报告、详情 overlay 和审计展示仍在 `App.tsx`，下一批继续以同一 feature model 拆出 `ResearchWorkbench` 主面板；当前先完成了有真实体积收益且可独立验收的控制台边界，不把它宣称为整个 Research 工作台已完全拆离。
- 前端新增 `ArtifactServerState`、纯 reducer、`LatestArtifactRequestGate` 与 `useArtifactState`；Artifact job list、latest version、history selection/loading、source 保存回执与 Knowledge writeback 回执均由 feature 作为唯一 server-state owner；
- job snapshot 读取在同一 latest-only channel 中先取 list、再取最新 version 后原子提交；history version 使用独立 channel，workspace 切换、同 channel 新请求和 Hook 卸载会 abort 旧请求；rollback 立即投影 latest/selected version，source/writeback 回执按 version key 幂等合并；
- `App.tsx` 已删除七组 Artifact server-state `useState` 与对应 setter，只保留 skill catalog、选中 skill、composer 开关、表单和自定义指令等 UI 草稿；保存资料、writeback、regenerate、rollback 的 API 调用仍由页面工作流编排，但回执和版本投影不再回流 App；
- `ui-contract-check.mjs` 禁止 Artifact server-state/ setter 回流，要求实际使用 Artifact hook action；新增 reducer/gate 测试覆盖 workspace reset、回执去重和 history/workspace abort；
- Artifact 定向测试 `6/6`，前端全量更新为 `28` 个文件、`97/97`；TypeScript + Vite build 通过（`76` modules），主 bundle 为 `assets/index-Cb7jYTAI.js`（`501.27 kB`，gzip `144.38 kB`），Research Sidebar chunk 为 `assets/ResearchSidebar-T0y0nK22.js`（`10.25 kB`，gzip `3.71 kB`）；UI contract、`git diff --check` 与 production preview 均通过；
- 新增 Artifact state hook 后主 chunk 再次略超 Vite `500 kB` 提示线。本批不改变阈值，下一批从真实 Artifact rail JSX 抽出可动态加载边界，再以 bundle 输出验证消除 warning。
- 真实 `ArtifactRail` 已从 `App.tsx` 抽出，承接 Studio skill chooser、composer、运行任务、latest result、version history/detail、source/Note/Wiki writeback、regenerate/compare/rollback/PDF export 与更多工具入口；组件只通过显式 props 复用 `useArtifactState` 的 job/version/receipt 投影，不复制服务端状态；
- `App.tsx` 通过 `React.lazy` + `Suspense` 动态导入 Artifact rail；UI contract 禁止原 composer JSX 回流，要求真实 lazy import，并读取 ArtifactRail 文件验证创建产物、最新结果、历史空态与更多工具内容；
- 前端全量仍为 `28` 个文件、`97/97`；TypeScript + Vite build 通过（`77` modules），实际产出 `assets/ArtifactRail-nVwmr-d5.js`（`11.02 kB`，gzip `2.98 kB`）、`assets/ResearchSidebar-BG6WQaD2.js`（`10.25 kB`，gzip `3.71 kB`）和主 bundle `assets/index-CLDnuL0U.js`（`492.60 kB`，gzip `142.88 kB`）；Vite 500 kB warning 已消失；production preview 首页与两个动态 asset 均返回 `200`，临时进程已清理；
- Artifact rail 已拆离，但聊天主面板中仍有 Artifact/Research 的联动入口与 Research 主报告/审计 JSX；下一批继续抽 Research 主报告/详情/审计面板，保持现有 feature model/state owner 不变。
- 真实 `ResearchReportPanel` 已从 `App.tsx` 抽出，承接 Research Run 元信息、结果快照、报告正文、显式 source scope、evidence highlights、writeback receipt 和报告 source 回流动作；组件只接收既有 Research model、派生 read view 和回调 props，不创建第二套 run/checkpoint/Memory 状态；
- `App.tsx` 通过 `React.lazy` + `Suspense` 动态导入报告面板；UI contract 禁止 `Research Report` 正文回流 App，要求真实 lazy import，并读取 ResearchReportPanel 文件验证结果快照、报告、来源、writeback 和保存资料入口仍已接线；
- 前端全量仍为 `28` 个文件、`97/97`；TypeScript + Vite build 通过（`78` modules），新增 `assets/ResearchReportPanel-BkvGKKTe.js`（`9.08 kB`，gzip `3.39 kB`），主 bundle 进一步降至 `assets/index-BGav6BK0.js`（`485.88 kB`，gzip `141.32 kB`）；Research Sidebar/Artifact Rail 两个既有动态 chunk 继续存在，Vite 500 kB warning 未出现；production preview 首页和三个动态 asset 均返回 `200`，临时进程已清理；
- 本批严格限定在 `frontend/` 与改造计划验收文档，未读取或修改 `workers/research-worker/`、`workers/artifact-worker/` 的编排、消费、执行或回调实现。

下一施工切片：继续抽出 Research detail overlay 与闭环审计面板，复用既有 feature model/state owner；再评估聊天主面板剩余 Artifact/Research 联动入口的组件边界。Memory 后续接入 Signal/Outcome 入口时复用既有 feature，Execution 后续扩展实时订阅时复用同一 registry，不建立第二套状态真源。

- Research detail overlay 的第一块真实过程面板已迁至 `frontend/src/features/research/ResearchProcessOverview.tsx`：保留既有 `ResearchProcessSummary`、search/read timeline、source evidence、Execution task event 和连续性派生结果，只通过显式 props 交给组件渲染；不复制 run/checkpoint/task/evidence server state，也未改动回答、检索或审计计算。
- `App.tsx` 以 `React.lazy` + `Suspense` 加载该组件；Search / Fetch / Read / Source Evidence 过程泳道、实时任务等待/事件轨迹、研究总览及刷新/保存入口均从主界面 JSX 移出。UI contract 同时检查真实 lazy import 和新组件的关键入口，防止过程概览回流 App。
- 本批前端回归为 `28` 个测试文件、`97/97` 通过；TypeScript + Vite build 通过（`79` modules），新增 `ResearchProcessOverview` chunk `6.63 kB`（gzip `2.06 kB`），主 bundle `481.15 kB`（gzip `140.79 kB`）；production preview 的首页和新 chunk 均返回 `200`，临时进程已清理。
- 本批严格限于 `frontend/` 与改造计划验收记录，未读取或修改 `workers/research-worker/`、`workers/artifact-worker/`。下一前端切片为 detail overlay 中尚留在 `App.tsx` 的轮次回放、Closed-Loop Audit、checkpoint/counterfactual 审计 JSX。

- Research detail overlay 的轮次回放已进一步迁至 `frontend/src/features/research/ResearchRoundTimeline.tsx`。`App.tsx` 仅将已有 `visibleProcessRounds` 投影为稳定的 read view，并继续作为选轮、全局/当前轮视角和审计聚焦的唯一状态 owner；新组件不复制 run、checkpoint、task 或 evidence server state。
- `ResearchRoundTimeline` 通过 `React.lazy` + `Suspense` 形成真实动态边界，保留最近轮次、空态、decision/reason、搜索命中、阅读窗口、证据卡、来源焦点、变化摘要与“查看这轮审计”入口。UI contract 已检查 lazy import 和关键入口，防止轮次 JSX 回流 `App.tsx`。
- 本批回归：前端 `28` 个测试文件、`97/97` 通过；TypeScript + Vite build 通过（`80` modules），新增 `ResearchRoundTimeline` chunk `2.71 kB`（gzip `1.15 kB`），主 bundle `479.29 kB`（gzip `140.53 kB`）；production preview 的首页和新 chunk 均返回 `200`，临时进程已清理。
- 本批只修改 `frontend/` 与改造计划验收记录；`workers/research-worker/`、`workers/artifact-worker/` 未读取、未修改。下一切片继续收缩 detail overlay 中的过程轨迹浏览与 Closed-Loop Audit / checkpoint / counterfactual 审计 JSX。

- Research detail overlay 的三阶段过程泳道已迁至 `frontend/src/features/research/ResearchProcessStageLanes.tsx`。`App.tsx` 只将现有 Research read model 投影为搜索、阅读和回流说明，并继续作为 source scope、工作台打开和焦点定位操作的唯一工作流 owner；新组件不复制任何 run/checkpoint/task/source/evidence server state。
- 新组件通过 `React.lazy` + `Suspense` 动态加载，保留当前轮/全局搜索和阅读上下文、报告写回、source scope 加入/移出、焦点定位与打开工作台入口。UI contract 已检查 lazy import 和三个泳道的关键操作，防止它们回流 `App.tsx`。
- 本批回归：前端 `28` 个测试文件、`97/97` 通过；TypeScript + Vite build 通过（`81` modules），新增 `ResearchProcessStageLanes` chunk `2.36 kB`（gzip `1.07 kB`），主 bundle `477.81 kB`（gzip `140.36 kB`）；production preview 的首页和新 chunk 均返回 `200`，临时进程已清理。
- 本批只修改 `frontend/` 与改造计划验收记录；`workers/research-worker/`、`workers/artifact-worker/` 未读取、未修改。下一切片继续迁出网页与工具轨迹分组卡、选中详情与 Closed-Loop Audit / checkpoint / counterfactual 审计 JSX。

- Research detail overlay 的网页与工具轨迹浏览器已迁至 `frontend/src/features/research/ResearchProcessTraceBrowser.tsx`。`App.tsx` 继续作为 trace 分类、来源/恢复/outcome 说明、轨迹选择和审计焦点的唯一派生与状态 owner，再将标准化 read view 和回调显式传给组件；新组件不创建第二套 Research/Execution server state，不改变 API、检索、回答或审计算法。
- 新的动态边界完整承接搜索轨迹、页面阅读、工作台来源三组卡、轮次过滤回退、键盘/鼠标选择、选中详情、网页/工作台/source scope/checkpoint/审计动作。UI contract 同时验证 lazy import、浏览交互入口和 `App.tsx` 中三类轨迹 read view，防止语义或 JSX 回流。
- 本批回归：前端 `28` 个测试文件、`97/97` 通过；TypeScript + Vite build 通过（`82` modules），新增 `ResearchProcessTraceBrowser` chunk `8.27 kB`（gzip `2.29 kB`），主 bundle 降至 `471.61 kB`（gzip `139.25 kB`）；production preview 的首页和新 chunk 均返回 `200`，临时进程已清理。
- 本批只修改 `frontend/` 与改造计划验收记录；`workers/research-worker/`、`workers/artifact-worker/` 未读取、未修改。下一切片继续迁出 detail overlay 中的 Closed-Loop Audit、Research Traces、Loop Rounds、Verifier Decisions 与 checkpoint/counterfactual 差异展示。

- Research detail overlay 的 Closed-Loop Audit 摘要已迁至 `frontend/src/features/research/ResearchAuditOverview.tsx`。`App.tsx` 仍是 Research read model、恢复目标格式化与审计聚合的唯一来源，只传递展示值；新组件不复制 task/run/checkpoint/counterfactual/evidence server state，也未改变 API、检索、回答或审计算法。
- 新动态边界承接 Verifier Gate / Final Loop、Counterfactual Branch、Checkpoint / Resume、Harness Control 和 Formal Audit Summary。UI contract 已验证真实 lazy import 和上述审计入口，防止摘要 JSX 回流 `App.tsx`。
- 本批回归：前端 `28` 个测试文件、`97/97` 通过；TypeScript + Vite build 通过（`83` modules），新增 `ResearchAuditOverview` chunk `2.46 kB`（gzip `0.96 kB`），主 bundle `470.46 kB`（gzip `139.07 kB`）；production preview 的首页和新 chunk 均返回 `200`，临时进程已清理。
- 本批仅修改 `frontend/` 与改造计划验收记录；`workers/research-worker/`、`workers/artifact-worker/` 未读取、未修改。下一切片继续迁出审计主体的 Closed-Loop State、Verifier-Gated Rows、checkpoint 回放/比较、resume recovery 与 counterfactual 差异展示。

- Research detail overlay 的 Closed-Loop State 与 Verifier-Gated Rows 已迁至 `frontend/src/features/research/ResearchClosedLoopStatePanel.tsx`。`App.tsx` 保持闭环 read model、恢复目标匹配、阻塞行摘要和来源 label 的唯一派生来源，只交付显式 view props；新组件不建立第二套 Research/checkpoint/task/evidence server state。
- 新动态边界承接 branch/loop/ledger/requirement/recovery 状态与 verifier-gated 阻塞、命中、未覆盖、冲突、partial 汇总和行明细。UI contract 已验证真实 lazy import、闭环状态、阻塞行、恢复覆盖、缺失 requirement 和空态入口。
- 本批回归：前端 `28` 个测试文件、`97/97` 通过；TypeScript + Vite build 通过（`84` modules），新增 `ResearchClosedLoopStatePanel` chunk `1.85 kB`（gzip `0.70 kB`），主 bundle `469.95 kB`（gzip `139.41 kB`）；production preview 的首页和新 chunk 均返回 `200`，临时进程已清理。
- 本批仅修改 `frontend/` 与改造计划验收记录；`workers/research-worker/`、`workers/artifact-worker/` 未读取、未修改。下一切片继续迁出 checkpoint 回放、baseline 比较、resume recovery 和 counterfactual 差异审计。

- Research detail overlay 的 checkpoint 回放列表已迁至 `frontend/src/features/research/ResearchCheckpointReplayPanel.tsx`。`App.tsx` 仍是 checkpoint、trace、loop round、恢复目标和状态转换的唯一数据/工作流 owner，先将快照、来源、连续性和信号归一化为 card read view，再通过回调执行回放或恢复；新组件不复制 checkpoint/Research state。
- 新动态边界承接 checkpoint 快照、branch/loop、verifier/evidence、intent/requirement、来源、recovery、trace、连续性、信号 chip、回放/恢复与空态。UI contract 已验证真实 lazy import 和这些关键入口。
- 本批回归：前端 `28` 个测试文件、`97/97` 通过；TypeScript + Vite build 通过（`85` modules），新增 `ResearchCheckpointReplayPanel` chunk `1.73 kB`（gzip `0.69 kB`），主 bundle `469.29 kB`（gzip `139.50 kB`）；production preview 的首页和新 chunk 均返回 `200`，临时进程已清理。
- 本批仅修改 `frontend/` 与改造计划验收记录；`workers/research-worker/`、`workers/artifact-worker/` 未读取、未修改。下一切片继续迁出选中 checkpoint 的 snapshot/provenance、baseline/resume/counterfactual 差异和原始 JSON 审计视图。

- 选中 checkpoint 的回放头部、基线选择与 Snapshot 四项摘要已迁至 `frontend/src/features/research/ResearchCheckpointSnapshotPanel.tsx`。`App.tsx` 继续以现有 checkpoint read model 构造快照卡、可选基线并持有比较状态/回调的唯一 owner；新组件仅渲染 props，不复制 checkpoint 或 Research server state。
- 新动态边界承接当前回放摘要、比较基线和 Snapshot Research Question / Loop State / Table-as-State / Intent Alignment；保留原有审计外层布局。UI contract 已验证真实 lazy import、选择器、回调与四个 snapshot read view。
- 本批回归：前端 `28` 个测试文件、`97/97` 通过；TypeScript + Vite build 通过（`86` modules），新增 `ResearchCheckpointSnapshotPanel` chunk `0.84 kB`（gzip `0.47 kB`），主 bundle `468.76 kB`（gzip `139.54 kB`）；production preview 的首页和新 chunk 均返回 `200`，临时进程已清理。
- 本批仅修改 `frontend/` 与改造计划验收记录；`workers/research-worker/`、`workers/artifact-worker/` 未读取、未修改。下一切片继续迁出 Checkpoint Provenance、snapshot findings/conflicts、baseline/resume/counterfactual 差异与原始 JSON 审计视图。

- 选中 checkpoint 的来源审计已迁至 `frontend/src/features/research/ResearchCheckpointProvenancePanel.tsx`。`App.tsx` 仍基于既有 checkpoint、trace、loop、verifier 和 outcome diff 生成 provenance read view，并作为 audit focus 的唯一状态 owner；新组件仅渲染卡片并回调定位动作，不创建第二套 Research/checkpoint state。
- 新动态边界承接 checkpoint→current 连续性、source trace、source loop round、source verifier、summary samples 和“定位到 trace/round/verifier”。UI contract 已验证真实 lazy import、聚焦回调和 App 端三类导航 read view。
- 本批回归：前端 `28` 个测试文件、`97/97` 通过；TypeScript + Vite build 通过（`87` modules），新增 `ResearchCheckpointProvenancePanel` chunk `0.61 kB`（gzip `0.34 kB`），主 bundle `467.66 kB`（gzip `139.64 kB`）；production preview 的首页和新 chunk 均返回 `200`，临时进程已清理。
- 本批仅修改 `frontend/` 与改造计划验收记录；`workers/research-worker/`、`workers/artifact-worker/` 未读取、未修改。下一切片继续迁出 snapshot findings/conflicts、checkpoint baseline、resume recovery、counterfactual 差异与原始 JSON 审计视图。

- 选中 checkpoint 的证据审查区已迁至 `frontend/src/features/research/ResearchCheckpointEvidenceReviewPanel.tsx`。`App.tsx` 继续从既有 checkpoint read model 归一化 findings、conflicts、branch decisions、recovery、next actions 和 evidence cards；新组件只渲染显式 props，不复制 Evidence/checkpoint/Research state。
- 新动态边界承接 Snapshot Verified Findings、Conflict And Counterfactual Review、Recovery Status、Evidence Ledger 与 conflict/evidence 空态。UI contract 已验证真实 lazy import 和四类审查入口。
- 本批回归：前端 `28` 个测试文件、`97/97` 通过；TypeScript + Vite build 通过（`88` modules），新增 `ResearchCheckpointEvidenceReviewPanel` chunk `1.65 kB`（gzip `0.60 kB`），主 bundle `466.94 kB`（gzip `139.64 kB`）；production preview 的首页和新 chunk 均返回 `200`，临时进程已清理。
- 本批仅修改 `frontend/` 与改造计划验收记录；`workers/research-worker/`、`workers/artifact-worker/` 未读取、未修改。下一切片继续迁出 Checkpoint To Current、Checkpoint To Checkpoint、Resume Recovery、Counterfactual Branch 差异与原始 JSON 审计视图。

- Checkpoint To Current Diff 已迁至 `frontend/src/features/research/ResearchCheckpointCurrentDiffPanel.tsx`。`App.tsx` 仍为表状态、恢复目标、verifier/loop、证据、findings/conflicts、gated-row 漂移的唯一差异计算与状态 owner，只向面板传递标准化卡片 view；新组件不复制 checkpoint/Research/Evidence state。
- 新动态边界承接四类摘要差异、findings/conflicts/gated-row progress 与新增 verified finding 空态。UI contract 已验证真实 lazy import、面板渲染和 App 端七类 current-diff read view。
- 本批回归：前端 `28` 个测试文件、`97/97` 通过；TypeScript + Vite build 通过（`89` modules），新增 `ResearchCheckpointCurrentDiffPanel` chunk `1.17 kB`（gzip `0.51 kB`），主 bundle `465.52 kB`（gzip `139.88 kB`）；production preview 的首页和新 chunk 均返回 `200`，临时进程已清理。
- 本批仅修改 `frontend/` 与改造计划验收记录；`workers/research-worker/`、`workers/artifact-worker/` 未读取、未修改。下一切片继续迁出 Checkpoint To Checkpoint、Resume Recovery、Counterfactual Branch 差异与原始 JSON 审计视图。

## 1. Knowledge 拆分

原 `KnowledgeService` 曾混合 CRUD、版本、关系图、统计、lint、治理和查询，现已按用例拆分并删除旧 facade：

```text
KnowledgeCommandService
  - create/update/delete note or wiki page
KnowledgeVersionService
  - immutable versions, compare, rollback
KnowledgeQueryService
  - list/detail/search read models
KnowledgeGraphService
  - relation resolve, traversal, limits
KnowledgeGovernanceService
  - lint, stale/orphan/conflict diagnostics
WikiProjectionService
  - source events -> page/version/checkpoint
```

写模型保证 KnowledgeItem + immutable Version；统计和首页视图使用可重建 read model，不在每次请求拼接大量聚合 SQL。Service 之间通过 id/version 和端口协作，不互相注入内部 Repository。

### 1.1 Wiki 投影

以 `(source_id, source_snapshot_version, projector_version)` checkpoint 幂等。新投影先生成新 Knowledge Version，再原子切 latest_version；失败不覆盖当前稳定版本。Source 删除产生 retract version/tombstone，保留审计和引用历史。

### 1.2 Graph

关系边有 source version、relation type、confidence、status 和 provenance。解析与消歧可异步，但用户确认高于自动推断。遍历强制 Workspace、状态、最大 hop/node/time budget；不能在 `KnowledgeService` 中递归无界查询。

## 2. Memory 生命周期

### 2.1 目标模型

```text
Signal -> Candidate -> Review -> MemoryItem/Version
       -> Conflict/Supersede/Staleness
       -> Compile ControlPack
       -> Apply to Answer/Execution
       -> Outcome Feedback -> utility update
```

Memory 保存偏好、工作方式、约束和可复用检查规则，不保存可由资料检索得到的事实副本。事实仍通过 EvidenceBundle 引用 Source/Knowledge。

### 2.2 Candidate Gate

替换固定 source-type 分值和字符串精确相等：

- confidence：信号可靠性、重复支持、显式用户确认；
- utility：适用任务频率、历史应用收益、节省修改次数；
- risk：敏感性、过度泛化、冲突、潜在伤害；
- scope：workspace/user、task neighborhood、有效期；
- novelty：规范化 + embedding/规则相似度候选，最终冲突可人工确认。

高风险/高影响规则必须 REVIEW_REQUIRED；低风险明确偏好可自动 promote，但提供撤销。阈值是版本化 policy，不散落在 Service 常量。

### 2.3 优先级与冲突

建议优先级：当前用户显式指令 > Workspace policy > 已确认专用 Memory > 已确认通用 Memory > 候选不生效。Memory Version 支持 `supersedes_id`、valid_from/to、status ACTIVE/STALE/REVOKED/CONFLICTED。

Compiler 在 token budget 内按 scope、priority、freshness、utility 选择，输出：interaction policy、style/preferences、forbidden patterns、review checklist 和引用的 memory version。它依赖通用 `CapabilityCatalogPort`，不直接依赖 Artifact Skill 实现。

### 2.4 Outcome 闭环

记录 Memory application：run/execution、memory version、使用位置、用户接受/重试/编辑/撤销、质量反馈。定期计算实际效用与负反馈；长期未使用或高编辑率进入 STALE/REVIEW，而不是永久 active。

## 3. Redis 生产化

在 P4 Streams 和 P1 ACL 基础上补齐：

| 用途 | Key/策略 | 降级 |
| --- | --- | --- |
| ACL | workspace/user/aclVersion，短 TTL+jitter | 回源 MySQL，默认不放行 |
| Source metadata | source/snapshot version key | 回源 DB |
| Wiki summary/read model | knowledge version key | 回源 DB |
| Memory compiled pack | actor/scope/policyVersion/memoryVersion hash | 重新编译 |
| Rate/concurrency | Lua token bucket + leased semaphore | 本机保守上限 |

写后失效优先于复杂 write-through；防穿透使用短空值 TTL；热点重建 single-flight 只能优化性能，正确性仍来自 DB。监测 hit ratio、load time、eviction、memory、command latency 和 error。为 key 设 namespace/version，建立容量和清理说明。

## 4. 前端模块化

### 4.1 目录目标

```text
src/app/                 router, providers, shell
src/shared/api/          http client, errors, auth, event stream
src/shared/ui/           design primitives
src/features/workspace/
src/features/sources/
src/features/conversations/
src/features/answers/
src/features/knowledge/
src/features/memory/
src/features/executions/
```

每个 feature 包含 api、model/store、hooks、components、tests。`App.tsx` 最终只保留 provider/router/shell，不保存所有业务状态和计时器。

### 4.2 服务端状态和事件

使用现有依赖或引入单一 query cache（如 TanStack Query）管理服务端数据、缓存键、失效和重试；不要同时保留手写 fetch/polling 和第二套 cache。Answer/Execution 事件进入规范化 store，以 event id 去重；终态后用 query refetch 校正。

轮询只作为不支持流式资源的降级，定时器由 hook 管理并在卸载时清理。所有 feature 从统一 API client 获得 request id、错误码、401/403 处理和 cancellation。

### 4.3 迁移顺序

1. 提取 shared API/error/auth/event-stream；
2. 提取 Workspace 和 Source；
3. 提取 Answer/Conversation，并替换伪 SSE；
4. 提取 Knowledge；
5. 提取 Memory 与 Execution；
6. 移除 App 中重复类型、格式化、fetch、polling 和 derived state；
7. 最后拆 UI shell，避免大爆炸式改写。

保持现有视觉和交互，先迁行为再重设计。每次迁移只有一个数据 owner，旧 prop bridge 登记删除条件。

## 5. API 聚合与读模型

避免前端为首页/Knowledge 页面发几十个请求。后端提供按界面需求设计但不泄漏内部表的 read model，例如 workspace overview、source pipeline status、knowledge page detail、memory review queue、execution timeline。查询服务可并发读取，但通过 `answerIoExecutor` 之外的受控 I/O 或数据库连接预算，不能为每个小字段创建线程。

## 6. 安全与隐私

- Memory 候选/版本按 actor scope 授权；Workspace 管理员不默认读取私人用户偏好；
- 前端不在 localStorage 保存长期 token、完整 prompt、文件正文或 Memory pack；
- 缓存 key 不含原始敏感文本，Redis value 有 TTL 和序列化版本；
- Governance、Memory review、rollback 等高影响操作写审计日志。

## 7. 测试

### Knowledge/Memory

- immutable version、并发更新 CAS、rollback 和 Source retract；
- graph 环、孤儿、消歧、hop/node budget；
- candidate 去重/冲突、policy version、优先级、stale/supersede/revoke；
- Memory 不作为事实 citation；负反馈可降低效用并触发 review；
- CapabilityCatalog adapter 缺失时 Compiler 有明确降级。

### Redis

- 缓存击穿/穿透、TTL jitter、成员变更失效；
- Redis 不可用/重启/淘汰时回源正确；
- 令牌桶和 semaphore 在多实例并发下不超配，lease 可恢复；
- Streams、cache 和 quota 使用不同 key namespace 与指标。

### Frontend

- feature hook/component 集成测试覆盖上传到 READY、三模式回答、断线恢复、权限错误、Memory review；
- fake timers 验证 polling/cleanup，mock stream 验证重复/乱序 event；
- Playwright 覆盖桌面和移动关键流程、刷新恢复、两标签页；
- bundle/build、类型检查和可访问性进入 CI。

## 8. 指标和验收

Knowledge：投影延迟、stale/orphan/conflict 数、graph query latency。Memory：candidate accept/reject、applied、positive/negative outcome、stale、compile latency/token。Redis：hit/error/eviction/latency、quota reject。Frontend：API error、stream reconnect、关键流程耗时和 JS error。

完成标志：KnowledgeService 和 Memory 依赖方向被拆解；Memory 有可解释的版本、冲突和效果闭环；Redis 三类用途都有降级和指标；App 不再是业务单体，关键用户流程由集成/E2E 测试保护。

## 9. 灰度与回滚

Knowledge query/read model、Memory policy、Redis cache/quota、每个前端 feature 均独立开关。回滚只切读路径或 feature route，不删除新版本/反馈数据。缓存可整体 flush 并回源；quota 降级为本机保守限额；前端保留短期旧 route，禁止新旧组件同时修改同一业务状态。
