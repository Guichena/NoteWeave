## 其他模块 · memory/source/upload

### MemorySignalService

### [中] findSignals 逐条查询构成 N+1
- 位置：`backend/src/main/java/com/noteweave/memory/MemorySignalService.java:87-122`
- 问题：`findSignals` 对传入的每个 signalId 各发一条 `select ... where workspace_id=? and id=?`，晋升/批量构建候选时按信号数量线性放大数据库往返。
- 证据：`for (String signalId : signalIds) { jdbcTemplate.query(... , workspaceId, signalId); }`。
- 影响：批量晋升多信号时 DB 往返 = 信号数，链路（promoteSignals 在事务内调用）延迟随批量线性增长。
- 建议：改为单条 `where workspace_id=? and id in (...)`，一次取回后在内存按序组装并校验缺失。

### GeneratedSourceService

### [高] 对象 key 由 sanitize 生成但保留 `.`，`..` 可构造路径穿越
- 位置：`backend/src/main/java/com/noteweave/source/GeneratedSourceService.java:74-78,148-152,169-172`
- 问题：snapshotObjectKey / file_object objectKey 由 `sanitize(generatedBy)`、`sanitize(generatedRefId)`、`sanitize(title)` 拼入路径。sanitize 的正则 `[^\p{IsHan}a-zA-Z0-9._-]+` 保留 `.`，因此 `..` 原样通过，`/`、`\` 才被替换。若 generatedRefId/title 含 `..`，对象 key 形如 `workspace/ws/generated/by/../final.md`，交由 storage.write 后可能穿越出预期目录（取决于 LocalObjectStorage 是否规范化）。
- 证据：`replaceAll("[^\\p{IsHan}a-zA-Z0-9._-]+", "-")` 未去除 `..` 段。
- 影响：路径穿越写入，覆盖 bucket 内其他工作台对象或逃逸存储根（结合 LocalObjectStorage 实现确认）。
- 建议：sanitize 后额外剥离 `..`/前导点，或对每段做白名单 + 拒绝含 `..`。

### [中] saveMarkdown 幂等靠"查后插"，并发生成会重复落库
- 位置：`backend/src/main/java/com/noteweave/source/GeneratedSourceService.java:51-96`
- 问题：以 (workspace_id, generated_by, generated_ref_id) 查存在再插入，无唯一约束/锁保护。两次并发保存同一 artifact 产物会各查到空、各插入一条 source。
- 证据：select limit 1 → if empty → insert，中间无 DB 唯一约束保证。
- 影响：同一产物重复生成资料、重复解析索引、重复 wiki ingest。
- 建议：对 (workspace_id, generated_by, generated_ref_id) 建唯一索引并 catch 冲突返回既有行。

### [中] 存储写入在事务提交前执行，回滚产生孤儿对象
- 位置：`backend/src/main/java/com/noteweave/source/GeneratedSourceService.java:79,142,153`
- 问题：`storage.write(...)` 在 @Transactional 方法内、DB 提交前调用。若后续 insert/其他步骤抛异常回滚，已写入的对象存储文件不会被清理。
- 证据：write 早于 return load()；无失败补偿。
- 影响：对象存储累积孤儿文件；file_object 去重下更隐蔽。
- 建议：改为提交后写入（事件/afterCommit）或失败时补偿删除。

### SourceParseService

### [中] chunk/window/outbox 逐行 insert，大文档写放大
- 位置：`backend/src/main/java/com/noteweave/source/SourceParseService.java:74-112`
- 问题：对每个 chunk 逐条 insert source_chunk，再对每个 window 逐条 insert source_window，再逐条 insert task_outbox，全部单行 update，无 batchUpdate。大文档（数百 chunk × 多 window）产生数千次 DB 往返，全部在一个事务内。
- 证据：三重 `jdbcTemplate.update` 均在 for 循环内单行执行。
- 影响：大资料解析延迟高、长事务占用连接与锁。
- 建议：用 `jdbcTemplate.batchUpdate` 批量插入 chunk/window/outbox。

### [低] summarize 用 substring(0,359) 可能截断 UTF-16 代理对
- 位置：`backend/src/main/java/com/noteweave/source/SourceParseService.java:198-204`
- 问题：`normalized.substring(0, 359)` 按 char 截断，若第 359 位落在 emoji/补充平面字符的代理对中间，产生半个字符（乱码）。buildReadWindows 的 substring 同理。
- 证据：`return normalized.substring(0, 359) + "..."`。
- 影响：摘要/窗口尾部偶发乱码字符。
- 建议：按 code point 边界截断（`offsetByCodePoints`）。

### [低] @Transactional 在 parseAndIndex 上，async 路径经 self-invocation 失效
- 位置：`backend/src/main/java/com/noteweave/source/SourceParseService.java:58-59,140-153`
- 问题：parseAndIndexAsync 内 `transactionTemplate.executeWithoutResult(() -> parseAndIndex(...))` 为同类自调用，parseAndIndex 上的 `@Transactional` 不会生效（Spring 代理仅拦截外部调用）。当前靠 transactionTemplate 提供事务，行为正确但注解具误导性，未来若移除 template 包裹将悄然失去事务。
- 证据：self-invocation 绕过 AOP 代理。
- 影响：可维护性隐患。
- 建议：移除误导性注解或拆分到独立 bean。

### UploadService

### [高] getOrCreateFileObject 非 local 存储后端 bucket/key 拆分错误
- 位置：`backend/src/main/java/com/noteweave/upload/UploadService.java:228-244`
- 问题：秒传命中已有 file_object 时，为判断对象是否存在，代码用一段 `backendName().equals("local") ? ... : ref.objectKey().substring(0, indexOf('/'))` 从 objectKey 推断 bucket/key。但 objectKey 落库形如 `workspace/<ws>/file_object/...`（不含 bucket 前缀），非 local 分支会把首段 `workspace` 当作 bucket，key 取剩余部分，导致 `storage.exists`/`storage.write` 指向错误 bucket。
- 证据：`String bucket = ...local... : ref.objectKey().contains("/") ? ref.objectKey().substring(0, indexOf('/')) : BUCKET_SOURCE;`
- 影响：生产（非 local 后端）秒传去重的对象存在性判断错误，可能重复写或写错桶；逻辑脆弱难维护。
- 建议：objectKey 与 bucket 分离存储或统一约定，去掉字符串切分推断。

### [中] acceptChunk 首次并发上传同一分片存在重复计数/唯一约束冲突
- 位置：`backend/src/main/java/com/noteweave/upload/UploadService.java:106-125`
- 问题：先 update（命中已存在分片行）否则 insert 并 `uploaded_chunks + 1`。两个请求并发上传同一 chunkIndex 且该行尚不存在时，两者 update 均命中 0 行 → 都走 insert 分支：若 (upload_id, chunk_index) 有唯一约束则第二个 500，无约束则重复行 + uploaded_chunks 双增。
- 证据：check(update)-then-insert 非原子，无行锁/ON CONFLICT。
- 影响：断点续传并发场景下计数漂移或异常；uploaded_chunks 失真。
- 建议：改用 upsert（ON CONFLICT DO UPDATE）并以实际分片行数为准。

### [中] completeUpload 事务内全量合并入内存 + 同步解析，长事务与内存压力
- 位置：`backend/src/main/java/com/noteweave/upload/UploadService.java:129-202,215-226`
- 问题：completeUpload 为 @Transactional，内部 mergeChunks 把最多 128MB 文件整体读入 ByteArrayOutputStream，随后（Kafka 关闭时）在同一事务内同步执行 parseAndIndex（大量 chunk/window/outbox 插入）。事务跨越对象存储 IO + 全文解析。
- 证据：`byte[] merged = mergeChunks(...)`（全内存）+ `sourceParsePort.parseAndIndex(...)` 均在事务内。
- 影响：大文件占用堆内存与数据库连接/锁时间过长，并发上传易压垮连接池；对象存储 IO 计入事务超时窗口。
- 建议：合并/解析移出事务；采用流式合并；仅将状态落库放入短事务。

### [中] 存储写入先于事务提交，回滚/失败产生孤儿对象与临时分片无清理
- 位置：`backend/src/main/java/com/noteweave/upload/UploadService.java:104-105,153-154,247`、`mergeChunks:215-226`
- 问题：acceptChunk 将分片写入 `workspace/<ws>/upload_tmp/<uploadId>/<idx>`，completeUpload 写 snapshot/file_object 对象——均在事务提交前写存储；若后续步骤失败回滚，已写对象成为孤儿。更关键：completeUpload 成功后从不删除 upload_tmp 下的分片临时对象，也无失败/超时上传的清理任务（设计"资料基础设施"应有临时文件回收）。
- 证据：无任何 `storage.delete(upload_tmp...)`；无对 status='UPLOADING' 超时上传的清理逻辑。
- 影响：每次上传遗留 N 个临时分片对象，长期累积占满对象存储；回滚孤儿无补偿。
- 建议：complete 后删除临时分片；增加过期上传清理任务；对象写入改 afterCommit 或失败补偿删除。

### [低] 秒传去重命中不校验既有 file_object 内容一致性（SHA-256 碰撞信任）
- 位置：`backend/src/main/java/com/noteweave/upload/UploadService.java:228-244`
- 问题：秒传按 (workspace_id, sha256) 命中即复用，仅在对象缺失时重写。理论上 SHA-256 碰撞或历史脏数据下会复用错误内容；且 `storage.exists` 判断跨上文错误 bucket 时更不可靠。
- 证据：命中后不比对 file_size/内容，直接 ref_count+1 返回。
- 影响：极低概率内容错配；主要与上文 bucket bug 叠加放大风险。
- 建议：命中时校验 file_size 一致，异常则告警。

### [低] uploaded_chunks 计数与 complete 权威判断不一致（冗余字段）
- 位置：`backend/src/main/java/com/noteweave/upload/UploadService.java:120-124,144-146`
- 问题：acceptChunk 维护 document_upload.uploaded_chunks，但 completeUpload 用 `chunkRows.size() != totalChunks` 判断完整性，从不读 uploaded_chunks。该字段成为易漂移的冗余状态（并发/重传下与实际行数不符）。
- 证据：complete 判断依据为 join 查询计数，非 uploaded_chunks。
- 影响：字段误导、维护成本；若他处依赖会出错。
- 建议：移除该字段或改由实际行数派生。

### [低] 偏离设计：缺少 MERGING 状态，且原始对象路径用文件名而非 file_hash.ext
- 位置：`backend/src/main/java/com/noteweave/upload/UploadService.java:153,196-200`；对照 `docs/资料基础设施详细设计.md:166-185,199-205`
- 问题：设计 §4.3 规定合并阶段进入 `MERGING` 状态，实际 document_upload 状态直接 UPLOADING→COMPLETED，无 MERGING 中间态，前端无法感知合并进行中。设计 §4.3 推荐最终对象路径 `.../original/{file_hash}.{ext}`，代码用 `.../original/{sanitize(fileName)}`，既偏离命名约定又可能因同名文件覆盖（虽 sourceId 唯一前缀隔离）。
- 证据：无 `status='MERGING'` 更新；objectKey 用 `sanitize(upload.fileName())`。
- 影响：状态机与设计不一致；对象命名与文档约定不符，跨实现协作易混淆。
- 建议：补 MERGING 状态或文档化省略理由；原始对象命名对齐 `{file_hash}.{ext}`。

### [低] sanitize 保留 `.`，依赖 createUpload 前置校验兜底路径穿越
- 位置：`backend/src/main/java/com/noteweave/upload/UploadService.java:306-309`、`UploadSecurityPolicy.java:26-31`
- 问题：completeUpload 的 objectKey 用 sanitize(fileName) 拼接，sanitize 正则 `[^a-zA-Z0-9._-]` 保留 `.`，本身不阻止 `..`。路径穿越目前完全依赖 createUpload 时 validateMetadata 拒绝 `..`/`/`/`\\`。防御纵深单点：若未来有别处（如 generated 复用 sanitize 思路）或校验被绕过即失效。
- 证据：validateMetadata 拦 `..`（UploadSecurityPolicy:28），sanitize 自身不拦。
- 影响：防御依赖单层校验，健壮性不足。
- 建议：sanitize 内也剥离 `..` 与前导点，形成纵深防御。

### UploadController / 鉴权

### [信息] chunk/complete 接口路径无 workspaceId，鉴权经 upload 归属工作台校验（正确）
- 位置：`backend/src/main/java/com/noteweave/upload/UploadController.java:31-44`、`UploadService.java:92-94,131-132`
- 问题：`/uploads/{uploadId}/chunks/..` 与 `/complete` 不带 workspaceId，acceptChunk/completeUpload 先 findUpload 取其 workspaceId 再 requirePermission(SOURCE_WRITE)，隔离正确、无 IDOR。此处仅记录已确认安全，非缺陷。
- 建议：无需修改；保持 findUpload→requirePermission 顺序。
- 位置：`backend/src/main/java/com/noteweave/memory/CreateMemorySignalRequest.java:13-18`、`MemorySignalService.java:43-50`
- 问题：signalType/sourceType/signalText 等标量已加 `@NotBlank/@Size`，但 styleConstraints、structureConstraints、terminologyPolicy、forbiddenPatterns、interactionPolicy、reviewChecklist 六个 List 既无 `@Size` 限制列表元素数，也无元素级长度约束。normalizeList 仅去重去空，可无界落库并进入 compiler token 预算计算。
- 证据：DTO 中六个 List 无任何校验注解；normalizeList 无数量上限。
- 影响：单条信号可携带超大 compile hints，膨胀 memory_signal/candidate/object JSON 列，拖慢 compiler estimateTokens。
- 建议：对列表加 `@Size(max=...)` 及元素 `@Size`。

### [低] normalizeToken 未指定 Locale 的 toUpperCase
- 位置：`backend/src/main/java/com/noteweave/memory/MemorySignalService.java:153-155`
- 问题：`toUpperCase()` 使用默认 Locale，土耳其语等环境下 i/I 转换异常，token 归一化不稳定。
- 证据：`return value.trim().toUpperCase().replace('-', '_')...`。
- 影响：跨环境 taskNeighborhood/signalType 归一化不一致，可能命中不到 neighborhood。
### MemoryCandidateService

### [高] buildCandidates 每个信号全表扫描 memory_object，N×全表 + 无事务
- 位置：`backend/src/main/java/com/noteweave/memory/MemoryCandidateService.java:40-103,165-190`
- 问题：`buildCandidates` 对每个信号调用 `findMatchingActiveObjects`，后者每次 `select ... from memory_object where workspace_id=? and status='ACTIVE'` 全量拉取该工作台所有活跃 Memory 到内存做相似度比对；信号数 × 活跃对象数放大，且方法本身无 `@Transactional`（依赖调用方 promoteSignals 的事务，但 buildCandidates 也被直接暴露，见 controller）。
- 证据：`for (SignalRow signal : signals) { ... findMatchingActiveObjects(...) }`，内部无 LIMIT、无索引维度过滤，全部加载后 `statementMatcher.similarity` 逐条计算。
- 影响：工作台 Memory 增长后候选构建 O(信号×对象) CPU + 每信号一次全表扫描；直接调用路径下多条 insert 非原子，可能部分落库。
- 建议：一次性加载活跃对象并在循环外复用；按 task_neighborhood 预过滤 SQL；给 buildCandidates 显式事务边界或明确仅内部调用。

### [中] candidate_type 直接取 signalType，与 Memory 类型枚举语义混淆
- 位置：`backend/src/main/java/com/noteweave/memory/MemoryCandidateService.java:68`（写入）与 `MemoryPromotionService.java:92`（memory_type=candidate_type）
- 问题：候选 candidate_type 与晋升后的 memory_type 都直接等于 signal 的 signalType。设计文档区分 signal_type（信号语义）与 memory_type（PREFERENCE/DECISION/NEGATIVE/INTERACTION/FORMAT，见设计§6）。若前端传的 signalType 不属于这五类，memory_type 就会写入非法值。
- 证据：`signal.signalType()` → candidate_type → memory_type，无映射/校验。
- 影响：memory_type 脏值，compiler/review 依赖类型分类时行为不可预期；与设计§6 五类偏差。
### MemoryPromotionService

### [中] 无自动 Staleness Guard；口径演进只靠人工 REPLACE_EXISTING
- 位置：`backend/src/main/java/com/noteweave/memory/MemoryPromotionService.java:80-146`、`MemoryReviewService.java:132-148`
- 问题：冲突消解确由 review 路径 REPLACE_EXISTING → versionService.revoke 处理（正确）。但设计§7.5 的 Conflict & Staleness Guard 还包含 STALE/SUPERSEDED 的自动检测。auto-promote（promoteReadyCandidate）只做「等价合并或插入 ACTIVE」，对同邻域语义相近但未被 matcher 判等价、也非 NEGATIVE 冲突的"口径更新"，既不合并也不 supersede 旧对象。
- 证据：promoteReadyCandidate 无 status='SUPERSEDED'/'STALE' 迁移；仅 EXISTING_EQUIVALENT 走合并。
- 影响：口径渐进演进时新旧 Memory 可同时 ACTIVE，compiler 同时注入产生矛盾约束。
- 建议：补充 staleness 检测或将近似口径纳入 NEEDS_REVIEW，交人工消解。

### MemoryReviewService

### [中] review 队列 SQL 无 LIMIT，全量拉取后内存截断
- 位置：`backend/src/main/java/com/noteweave/memory/MemoryReviewService.java:52-79,256-318`
- 问题：listCandidateReviews / listObjectReviews 均无 SQL LIMIT，把工作台所有待审候选/对象全部取回内存再排序 `.limit(min(limit,200))`。
- 证据：两条 query 无 `limit` 子句；截断发生在 stream 层。
- 影响：待审积压时全表读放大内存与延迟。
- 建议：SQL 层加 `order by ... limit ?`（或至少下推优先级排序 + limit）。

### [中] 候选审阅按 user_id 过滤，跨用户 review 不可达且与对象审阅口径不一致
- 位置：`backend/src/main/java/com/noteweave/memory/MemoryReviewService.java:266,320-344`
- 问题：listCandidateReviews 和 lockCandidate 都用 `user_id = ?`（当前用户）过滤，而对象审阅用 `memory_scope='WORKSPACE' or user_id=?`（工作台级可见）。持有 MEMORY_REVIEW 权限的审阅者只能看到/处置自己创建信号产生的候选，无法审阅他人候选。
- 证据：candidate 查询 `and user_id = ?`；object 查询 `and (memory_scope='WORKSPACE' or user_id=?)`。
- 影响：工作台级 Memory 审阅流程被割裂，协作场景下候选无人可审；两条链口径矛盾。
- 建议：候选审阅统一按工作台可见性（保留权限校验），或明确文档化"候选仅本人可审"。

### [中] findConflictingActiveObjects 全表扫描活跃对象
- 位置：`backend/src/main/java/com/noteweave/memory/MemoryReviewService.java:368-394`
- 问题：REPLACE_EXISTING 决策时再次 `select ... where status='ACTIVE'` 全量拉取活跃对象做内存比对，与 candidate/promotion 中相同的无界扫描坏味道。
- 证据：无邻域/类型下推过滤，全部加载后循环 statementMatcher.equivalent。
- 影响：大工作台单次冲突消解全表扫描。
### MemoryCompilerService

### [中] logPackUsage 逐条 count-then-insert，存在 N+1 与并发重复写竞态
- 位置：`backend/src/main/java/com/noteweave/memory/MemoryCompilerService.java:108-144`
- 问题：对每个 memory reference 先 `select count(*)` 判存在再 insert，既是 N+1（2×引用数 往返），check-then-insert 又非原子，两次并发同 target 会双写日志（无唯一约束/ON CONFLICT）。
- 证据：循环内 `queryForObject(count...)` + `if(existing>0) continue;` + `insert`。
- 影响：使用日志重复、写放大；usage 统计（outcome policy 依赖）失真。
- 建议：加 `(workspace_id,target_type,target_id,memory_object_id)` 唯一索引 + `insert ... on conflict do nothing`，或批量 insert。

### [中] 每次编译强制全表扫描 loadStateRows，缓存命中仍付一次全量查询
- 位置：`backend/src/main/java/com/noteweave/memory/MemoryCompilerService.java:146-182,269-316`
- 问题：为构造缓存 key 的 stateFingerprint，compilePack 每次都先执行 loadStateRows（工作台全部活跃对象 join version 全表扫描）；命中缓存也省不掉该扫描。未命中时再执行 loadObjectRows——一条几乎相同的全表扫描，两次查询数据高度重叠。
- 证据：loadStateRows 无条件调用；loadObjectRows 与其仅差 compile_policy_json 列。
- 影响：Redis 缓存只省 CPU 编译，不省 DB；且每次至少一次全量扫描，热点链路（每次聊天/产物/研究都编译）压力大。
- 建议：合并为一条查询同时取 state+hints；或缓存 key 用轻量版本号（如 max(updated_at)+count）避免全量拉取指纹。

### [低] chat answer_mode 未校验白名单，可生成任意 neighborhood
- 位置：`backend/src/main/java/com/noteweave/memory/MemoryCompilerService.java:54-63`
- 问题：compileChatControlPack 直接 normalizeToken(answerMode) 拼 `CHAT_<mode>`，未限制 ask/note/wiki（设计§9.4）。传任意值生成孤立 neighborhood，永远匹配不到 Memory。
- 证据：无 `Set.of("ASK","NOTE","WIKI").contains(...)` 校验。
- 影响：非法 mode 静默返回空 pack 而非 400，问题难定位。
- 建议：校验 answer_mode 白名单。

### [中] COMMON 邻域相当于全局注入，偏离"必须有明确适用邻域"设计
- 位置：`backend/src/main/java/com/noteweave/memory/MemoryCompilerPolicy.java:37-51`、`MemoryCompilerService.java:57,83,98`
- 问题：neighborhoodPriority 对含 COMMON 的对象返回 2（<3 即入选），且每个 pack 的 allowedNeighborhoods 都加入 COMMON。设计§7.3 明确"一条 Memory 必须有明确适用邻域，不能默认全局注入"。COMMON 恰好构成默认全局注入通道。
- 证据：`neighborhoods.add("COMMON")` 出现在 chat/artifact/research 三处；priority 2 仍入选。
- 影响：带 COMMON 的 Memory 无差别注入所有链路，与设计边界冲突。
### MemoryController / MemoryCompilerService

### [高] 三个 control-pack GET 接口无 workspace 权限校验，存在越权读取
- 位置：`backend/src/main/java/com/noteweave/memory/MemoryController.java:56-78`、`MemoryCompilerService.java:54-106,146-155`
- 问题：getChat/Artifact/Research ControlPack 直达 compiler，compilePack 只调 `currentUserProvider.requireUserId()`，从不调用 `workspaceAccessGuard.requirePermission(workspaceId, ...)`（其他所有 memory 写/审阅接口都调了）。任意已认证用户传入他人 workspaceId 即可编译并读回该工作台的 Memory Control Pack（含 forbidden_patterns / style / terminology 等偏好口径）。
- 证据：MemoryCompilerService 未注入 WorkspaceAccessGuard；scopeAllowed 仅按 memory_scope+ownerUser 过滤 USER 级，WORKSPACE 级 Memory 对任何请求者可见。
- 影响：跨工作台越权读取记忆约束，违反 workspace 隔离。
- 建议：三个编译入口先 `requirePermission(workspaceId, ANSWER_RUN)`（或等价读权限）。

### [中] logPackUsage 无权限校验且被编译链路调用，写入他人工作台使用日志
- 位置：`backend/src/main/java/com/noteweave/memory/MemoryCompilerService.java:108-144`
- 问题：logPackUsage 同样无 workspace 权限校验，配合上条越权可向任意 workspace 写 memory_usage_log。
- 证据：方法内无 requirePermission。
- 影响：使用日志与 outcome 统计可被污染。
- 建议：同上补权限校验，或依赖调用方已校验并文档化。

### MemoryOutcomeService / MemoryVersionService

### [低] recordOutcome 对同一 memory_object 的多条 usage log 重复加锁更新
- 位置：`backend/src/main/java/com/noteweave/memory/MemoryOutcomeService.java:57-124`
- 问题：一个 target 若引用同一 memory_object 多次（多条 usage log），循环内对该对象反复 lockObject + update，累计计数逻辑虽正确但存在重复行更新与锁竞争放大。
- 证据：`for (application : applications) { lockObject(...); update memory_object ... }`。
- 影响：极端情况下同对象多次更新，性能与可读性欠佳。
- 建议：按 memory_object_id 聚合后一次结算。

### [低] currentVersionOrBootstrap 引导插入的 update 缺乏乐观条件（依赖行锁）
- 位置：`backend/src/main/java/com/noteweave/memory/MemoryVersionService.java:370-403`
- 问题：createInitialVersion 的 update 带 `latest_version_id is null and current_version_no = 0` 乐观条件并校验 updated==1，但 bootstrap 分支的 update 无同等条件、也不校验返回值，仅靠 lockObject 的 `for update` 串行化。
- 证据：bootstrap update 无 where 乐观条件、无 updated 校验。
- 影响：一致性依赖锁而非条件更新，坏味道；若未来去锁则可双写 version_no=1。
- 建议：补乐观条件与返回值校验，保持与 createInitialVersion 一致。

### [高] memory_scope 恒为 'WORKSPACE'，candidate.scopeStatus 计算后被丢弃
- 位置：`backend/src/main/java/com/noteweave/memory/MemoryPromotionService.java:108`
- 问题：insert 中 memory_scope 硬编码 `'WORKSPACE'`，而 gate 计算的 scopeStatus（VALID/INVALID）与设计§7.4 scope_fit（用户级/工作台级）无关联；scopeStatus 仅塞进 ledger，从不决定 memory_scope。compiler 却按 memory_scope 做 scopeAllowed/scopePriority 分级（USER vs WORKSPACE）。
- 证据：`values (?, ?, ?, ?, 'WORKSPACE', ...)`；scopeStatus 只进 MemoryLedger。
- 影响：永远无法产生用户级 Memory，设计的 scope_fit 分级形同虚设；compiler 的 USER 优先级分支永不触发。
- 建议：由候选/策略推导 memory_scope（USER/WORKSPACE）并落库。

### [中] findEquivalentActiveObject 每候选全表扫描 memory_object
- 位置：`backend/src/main/java/com/noteweave/memory/MemoryPromotionService.java:148-173`
- 问题：与 candidate 服务同样问题——晋升每个候选都全量拉取活跃对象在内存比对，promoteSignals 批量时叠加放大。
- 证据：`select ... from memory_object where workspace_id=? and status='ACTIVE'` 无邻域/类型过滤 + 内存循环 statementMatcher.equivalent。
- 影响：批量晋升 O(候选×活跃对象)，大工作台性能退化。
- 建议：批内复用一次加载结果，或 SQL 侧按 memory_type + neighborhood 过滤。

### [低] readStringList 静默吞异常返回空列表，与其他服务抛错不一致
- 位置：`backend/src/main/java/com/noteweave/memory/MemoryPromotionService.java:175-185`
- 问题：解析失败返回 `List.of()`，而 CandidateService/CompilerService 同名方法抛 BusinessException。等价性判断会因邻域丢失而误判为"无交集→新建"。
- 证据：`catch (JsonProcessingException ex) { return List.of(); }`。
- 影响：脏 JSON 时静默重复晋升，产生重复 Memory Object。
- 建议：统一错误处理策略。

### [中] negativeMemory 仅按 signalType=="NEGATIVE" 判定，与 candidate_type 归一化不一致
- 位置：`backend/src/main/java/com/noteweave/memory/MemoryCandidateService.java:45,145-153`
- 问题：`"NEGATIVE".equals(signal.signalType())` 精确匹配，但 signalType 已被 normalizeToken 归一化为大写下划线；若来源写 "negative"/"Negative Memory" 归一化成 "NEGATIVE"/"NEGATIVE_MEMORY"，后者判不出 negative，冲突消解（detectConflict）与 risk 加分全部失效。
- 证据：normalizeToken 产出可能为 NEGATIVE_MEMORY，而比对硬编码 "NEGATIVE"。
- 影响：Negative Memory 与正向记忆冲突检测漏判，违反设计§7.5「旧记忆不能污染新决策」。
- 建议：统一常量并对齐归一化后的确切 token。
