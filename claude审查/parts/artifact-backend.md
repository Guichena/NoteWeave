## Artifact 后端 · Java 层
（审查发现如下，分批追加）

## ArtifactJobService

### [严重] completeFromWorker 生成版本号存在并发竞态，可产生唯一约束冲突或丢失更新
- 位置：`backend/src/main/java/com/noteweave/artifact/ArtifactJobService.java:516-547`（`findByTaskId` 543-582）
- 问题：`completeFromWorker` 先 `findByTaskId` 读出 `latest_version_no`（无 `for update`），再插入 `version_no = latest+1`，最后 `update artifact_job`。整个读改写序列没有行锁。而 `regenerateVersion`/`rollbackVersion` 都用了 `select ... for update`，唯独完成回调没有。
- 证据：`findByTaskId` 的 SQL 无 `for update`；`nextVersionNo = row.latestVersionNo() + 1`；`artifact_version` 上有 `uq_artifact_version_job_no (artifact_job_id, version_no)`（V012:35）。
- 影响：同一 job 的两个 run（如 regenerate 旧任务与新任务）几乎同时回调、或重复回调被并行处理时，两次都读到相同 latest_version_no，插入相同 version_no → 触发唯一索引冲突使一次完成失败并回滚；或 latest_version_no 被覆盖为较小值造成后续版本号错乱。
- 建议：在 `completeFromWorker` 里对 `artifact_job` 行加 `select latest_version_no ... for update`（与 rollback 一致），或用 `insert ... select coalesce(max(version_no),0)+1` 原子取号并捕获唯一约束冲突重试。

### [高] complete 回调缺省无幂等键，重复回调会重复生成版本
- 位置：`backend/src/main/java/com/noteweave/worker/WorkerTaskCallbackService.java:109-136,179-193`
- 问题：`registerCallback` 在 `idempotencyKey` 为空/空白时直接 `return null`（视为不去重）。`complete(taskId, request)` 无参重载传入 `""`，因此不带幂等键的完成回调完全没有去重保护，`completeFromWorker` 会被执行多次。
- 证据：`if (idempotencyKey == null || idempotencyKey.isBlank()) { return null; }`；`public WorkerAckResponse complete(String taskId, WorkerCompleteRequest request) { return complete(taskId, request, ""); }`。V024 建的 `worker_callback_receipt(task_id, idempotency_key)` 唯一索引仅在提供 key 时才生效。
- 影响：Worker 因超时重发完成回调（分布式常态）时会追加多条 `artifact_version`，即使有版本号竞态保护也会产生重复版本；与"幂等回调"设计目标不符。
- 建议：完成/失败类回调应强制要求幂等键（缺失即拒绝或用 `taskId+callbackType` 兜底），或在 `completeFromWorker` 内以 `origin_task_id`（origin_task_id = taskId）判重，存在已完成版本则直接返回既有结果。

### [高] 完成回调事务内发起远程 HTTP 拉取 PDF，长时间持有数据库事务
- 位置：`backend/src/main/java/com/noteweave/worker/WorkerTaskCallbackService.java:114-136`、`backend/src/main/java/com/noteweave/artifact/ArtifactExportService.java:55-70,162-176,209-229`
- 问题：`complete(...)` 方法整体 `@Transactional`，其中调用 `artifactExportService.materializeExports(...)`，后者对 `bilibili_course_note_pdf` 等会同步调用 `artifactWorkerClient` 拉取 PDF 字节（`fetchWorkerExport`），且 RestClient 未设置连接/读取超时。
- 证据：`complete` 上 `@Transactional`；`materializeExports` → `persistWorkerPdf` → `fetchWorkerExport` 走 `RestClient .get()...body(byte[].class)`；构造器只 `RestClient.builder().baseUrl(...)`，无 timeout。
- 影响：一次外部网络阻塞会把 DB 事务和连接一起挂住，占用连接池、放大锁持有时间，Worker 慢响应可拖垮回调链路；PDF 可能很大，还会在事务里做对象存储写入。
- 建议：将 PDF 归档移出完成事务（先提交版本、再异步/独立事务物化导出），并给 RestClient 配置 connectTimeout/readTimeout 与大小上限。

### [高] export_trace.file_name 非法会使整个完成回调事务回滚
- 位置：`backend/src/main/java/com/noteweave/artifact/ArtifactExportService.java:56-69,295-312`
- 问题：`materializeExports` 调用 `readCompiledFileNameIfPresent`，当 `export_trace.status=COMPILED` 但 `file_name` 为空/非 .pdf/含路径分隔符时抛 `BusinessException`。该异常发生在 `persistFile` 的 try/catch 之外，会向上冒泡到 `complete()` 事务。
- 证据：`readCompiledFileNameIfPresent` 中 `throw new BusinessException("ARTIFACT_EXPORT_INVALID", ...)`；调用点 `String pdfFileName = readCompiledFileNameIfPresent(...)` 不在任何 try 内；`complete` 为 `@Transactional`。
- 影响：Worker 上报的一个坏文件名会让 `artifact_version` 插入、任务完成、幂等回执一起回滚，任务反复"完成失败"，无法收敛（毒药消息）。
- 建议：把导出物化整体设为 best-effort（捕获所有异常并落 FAILED 元数据，不影响主完成事务），或在物化前对 file_name 仅做降级处理而非抛错中断完成。

### [中] markRunning/markWaiting 按 task_id 更新，regenerate 后旧任务回调静默失效
- 位置：`backend/src/main/java/com/noteweave/artifact/ArtifactJobService.java:497-513`、`146-194`
- 问题：`markRunning`/`markWaiting` 用 `where task_id = ?` 更新 `artifact_job.status`；但 `regenerateVersion` 会把 `artifact_job.task_id` 改写为新任务。旧 run 的进度回调再来时匹配 0 行，静默无效；同时新旧两个 run 的状态都压在同一个 `artifact_job.status` 字段上，语义相互覆盖。
- 证据：`update artifact_job set status=... where task_id = ?`；regenerate 里 `update artifact_job set task_id = ?, ... where id = ?`。
- 影响：并发/再生成场景下 job 状态与真实执行不一致；旧任务进度不可见，前端 `status` 抖动。
- 建议：run 级状态应落在 `artifact_job_run`（增加 status 列）而非仅 `artifact_job`；或完成/进度均以 taskId 校验属于当前活跃 run 再更新。

### [中] completeFromWorker 用 where id 更新 job，旧 run 迟到完成会覆盖标题/状态
- 位置：`backend/src/main/java/com/noteweave/artifact/ArtifactJobService.java:538-546`
- 问题：完成时 `update artifact_job set status='COMPLETED', result_title=?, latest_version_no=? where id=?`（仅按 job id）。若某个已被 regenerate 取代的旧 run 迟到完成，仍会把 job 的 result_title/status 覆盖为旧 run 结果，即便更晚的新 run 已经在跑或已完成。
- 证据：更新条件只有 `where id = ?`，无 run/task 归属校验；nextVersionNo 基于当前 latest 追加。
- 影响：最终展示的"最新版本标题/状态"可能来自被放弃的旧 run，产生错乱。
- 建议：完成时校验 taskId 是否为该 job 当前活跃 task，或以 run_no 单调性判断是否允许更新 job 汇总字段。

### [中] loadSourceScopeSnapshot 存在 N+1 查询
- 位置：`backend/src/main/java/com/noteweave/artifact/ArtifactJobService.java:692-736`
- 问题：`getWorkerInput` 还原资料范围快照时，对快照里的每个 sourceId 各发一条 SQL（`loadSourceScopeItem`），且每条 SQL 内还有子查询取 `sample_text`。sourceId 最多 20 个（createJob 限制），即每次 Worker 拉取输入触发最多 20 次查询。
- 证据：`sourceIds.stream().map(sourceId -> loadSourceScopeItem(workspaceId, sourceId)).flatMap(...)`；`loadSourceScopeItem` 单条 where `s.id = ?`。
- 影响：典型 N+1；Worker 每次取输入都放大数据库往返，属可避免的性能损耗。
- 建议：改为 `where s.id in (...)` 单次批量查询并按原顺序重排。

### [中] 快照资料范围丢失原始顺序，且过滤 READY 会静默丢弃已失效资料
- 位置：`backend/src/main/java/com/noteweave/artifact/ArtifactJobService.java:692-736`
- 问题：`loadSourceScopeItem` 要求 `status='READY'`，若快照中某资料后来被删除或转为非 READY，会被静默 flatMap 丢弃，Worker 拿到的资料范围与创建时快照不一致（快照本应是不可变证据）。同时逐条查询虽保留了外层顺序，但 `in` 化后需注意重排。
- 证据：`where s.workspace_id = ? and s.id = ? and s.status = 'READY'`；缺失即返回空列表被 flatMap 跳过。
- 影响：破坏"上下文快照可复现"的语义，再生成结果不可解释；也无任何告警。
- 建议：快照读取不应再以当前 status 过滤，或对缺失项保留占位并在 trace 标注失效。

### [低] toInstant 对 null 时间戳返回 Instant.now()，掩盖数据缺失
- 位置：`backend/src/main/java/com/noteweave/artifact/ArtifactJobService.java:1080-1082`
- 问题：`toInstant(null)` 返回 `Instant.now()` 而非 null。created_at/updated_at 都是 NOT NULL DEFAULT，正常不会为 null，但一旦为 null，接口会返回"当前时间"这一虚假值。
- 证据：`return value == null ? Instant.now() : value.toInstant();`
- 影响：错误数据被伪装成合理时间，排查困难。
- 建议：null 时返回 null 或抛出，不要编造时间。

### [中] createJob 与 regenerateVersion 的 outbox 入队逻辑重复且 message_key 相同
- 位置：`backend/src/main/java/com/noteweave/artifact/ArtifactJobService.java:119-135`（createJob 内联）、`630-649`（enqueueArtifactTask）
- 问题：createJob 内联写 task_outbox，regenerate 走 `enqueueArtifactTask`，两处 payload 结构几乎相同（重复代码）。且两处 `message_key` 都取 `artifactJobId`，多次 run 的消息 key 相同；若 outbox 存在按 message_key 去重/压缩逻辑，regenerate 消息可能被误判为重复。
- 证据：createJob `message_key = artifactJobId`；enqueueArtifactTask `message_key = artifactJobId`；两段 payload map 字段完全一致，仅 trace_id 后缀不同。
- 影响：代码坏味道（重复）；潜在的 outbox 去重误伤，导致再生成任务不被派发。
- 建议：createJob 复用 `enqueueArtifactTask`；message_key 采用 taskId（每 run 唯一）以避免跨 run 冲突。

### [低] createJob 忽略 skill 的 action 绑定，action_key 永远写 null
- 位置：`backend/src/main/java/com/noteweave/artifact/ArtifactJobService.java:88-105`
- 问题：`ArtifactSkillCatalogService` 维护了 `resolveActionKey(skillKey)` 与 `actionBindings`，但 createJob 插入 artifact_job 时 `action_key` 硬编码为 `null`。V021 才把该列改为 nullable，说明此前它是必填的业务字段，现在被完全弃用。
- 证据：insert 的 action_key 位置传 `null`；`ArtifactSkillCatalogService.resolveActionKey` 存在但从未在 job 落库时使用。
- 影响：`action_key` 列与 `resolveActionKey`/`actionBindings` 逻辑成为半死代码；若下游报表或迁移依赖 action_key 则数据缺失。
- 建议：明确 action_key 是否仍需要；需要则 createJob 用 `resolveActionKey` 落库，否则删除该列与相关映射。

### [低] userRequirement 长度上限在创建/再生成/DTO 之间不一致
- 位置：`CreateArtifactJobRequest.java:9`（max 4000）、`RegenerateArtifactVersionRequest.java:7`（max 20000）
- 问题：创建作业时 userRequirement 上限 4000，再生成时上限 20000，同一语义字段两套约束。
- 影响：用户可在再生成时提交远超创建上限的文本；约束不一致易引发困惑与数据不齐。
- 建议：统一上限。

## ArtifactExportService

### [高] downloadPdf 为 GET 请求，却触发远程拉取+对象存储写入且非原子
- 位置：`backend/src/main/java/com/noteweave/artifact/ArtifactExportService.java:72-83`、`ArtifactJobController.java:143-160`
- 问题：`GET .../export.pdf` 在 `!hasFile(PDF)` 时调用 `materializeExports`（远程 HTTP + MinIO 写 + DB 写）。GET 带副作用违反语义；且 `downloadPdf` 方法本身无 `@Transactional`，`hasFile → materializeExports → loadStoredFile` 跨多个事务，两个并发下载会重复物化、重复远程拉取。
- 证据：`downloadPdf` 无事务注解；`if (!hasFile(row.versionId(), PDF)) { materializeExports(row.versionId()); }` 随后 `loadStoredFile`。
- 影响：可被反复触发昂贵的 Worker 拉取；并发下载竞态可能互相干扰（尽管 upsert 有唯一键兜底）；GET 幂等性被破坏。
- 建议：下载与物化分离，下载只读；物化在完成流程完成，或对物化加分布式/行级锁并整体事务化。

### [中] copyFiles 在回滚后单独事务执行，可能拉取已失效的旧版本 PDF，且与版本创建非原子
- 位置：`ArtifactJobController.java:100-117`、`ArtifactExportService.java:108-134`
- 问题：rollback 控制器先 `rollbackVersion`（独立事务提交），再 `copyFiles(sourceVersionId, rolledBackVersionId)`（另一独立事务，内部 `materializeExports(sourceVersionId)` 会用旧版本 origin_task_id 远程拉 PDF）。旧任务的 Worker 导出可能早已不可用 → PDF FAILED；且若 copyFiles 抛错，回滚版本已存在但无文件。
- 证据：controller 顺序调用三个独立事务方法；`copyFiles` 先 `materializeExports(sourceVersionId)` 再逐个复制。
- 影响：回滚版本可能缺文件或产生 FAILED 记录；跨事务不原子，失败后状态不一致。
- 建议：回滚复制文件应基于已存储的对象直接复制（不再远程拉取），并与版本创建纳入同一事务或明确的补偿流程。

### [中] listFiles 直接对 created_at 调用 toInstant 未判空，与 JobService 版本不一致
- 位置：`backend/src/main/java/com/noteweave/artifact/ArtifactExportService.java:104`
- 问题：此处 `rs.getTimestamp("created_at").toInstant()` 若时间戳为 null 会 NPE；而 `ArtifactJobService.loadArtifactFiles` 读同一张表用了 `toInstant(...)` 兜底。两处读取 artifact_file 的映射逻辑重复且不一致。
- 证据：ExportService `rs.getTimestamp("created_at").toInstant()`；JobService:1047 `toInstant(rs.getTimestamp("created_at"))`。
- 影响：重复代码、行为不一致；理论 NPE 风险。
- 建议：抽取公共 RowMapper，统一空值处理。

### [中] persistFile 对空内容写入 READY 判断与"已存在即跳过"逻辑掩盖失败态
- 位置：`backend/src/main/java/com/noteweave/artifact/ArtifactExportService.java:136-160,271-279`
- 问题：`persistFile` 开头 `if (hasFile(versionId, format)) return;`，而 `hasFile` 只统计 `status='READY'`。若某格式此前是 FAILED 记录，`hasFile` 返回 false，会再次进入 upsert（合理）；但 `materializeExports` 每次 complete 都会重跑 MARKDOWN 物化，靠 hasFile 幂等——一旦 MARKDOWN 曾 FAILED（空内容场景写 FAILED），会反复重试且每次都是空内容，无退避。
- 证据：`hasFile` where `status='READY'`；空内容分支 `upsertFileMetadata(..., "FAILED", "产物导出文件为空")` 且不写对象存储。
- 影响：内容恒为空时无意义重试；FAILED 记录长期存在，前端文件列表混入 FAILED 项。
- 建议：区分"内容本就为空"（终态，不重试）与"存储异常"（可重试）；对 FAILED 明确重试策略。

### [低] safeStem 先 trim 再截断，可能在多字节字符边界截断 & 未防空
- 位置：`backend/src/main/java/com/noteweave/artifact/ArtifactExportService.java:314-325`
- 问题：`safeStem` 用 `substring(0, min(len,120))` 按 char 截断标题，中文标题在 120 char 内一般安全，但 emoji/代理对可能被从中间截断产生非法半字符；`safeFileName` 仅替换非法字符但未限制长度，超长文件名可能超出对象存储 key 限制。
- 证据：`stem.substring(0, Math.min(stem.length(), 120))`；`safeFileName` 无长度上限。
- 影响：极端文件名可能损坏或超限。
- 建议：按 code point 截断并限制 safeFileName 总长。

### [低] ExportService 与 JobService 各自维护 artifact_file 查询与 SQL 重复
- 位置：`ArtifactExportService.java:85-106` 与 `ArtifactJobService.java:1027-1049`
- 问题：两个类几乎逐字重复 artifact_file 的 select+RowMapper（字段顺序、coalesce error_message 都一样）。
- 影响：维护成本翻倍，字段变更易漏改一处。
- 建议：抽取共享查询组件。

## ArtifactSkillCatalogService

### [中] validateAndNormalizeInputs 只校验 string 类型，其他声明类型完全放行
- 位置：`backend/src/main/java/com/noteweave/artifact/ArtifactSkillCatalogService.java:203-220`
- 问题：`normalizeInputValue` 仅对 `declaredType=="string"` 做类型/enum 校验，其它任何类型（含未声明 type）直接 `return rawValue` 原样透传。schema 里若声明 `integer`/`boolean`/`object`，均不校验。
- 证据：方法体只有 `if ("string".equals(declaredType)) {...}` 分支，最后 `return rawValue;`。
- 影响：非 string 字段的类型约束形同虚设，脏数据（如 url 传对象、嵌套结构）会流入 inputs_json 并传给 Worker。当前 schema 只有 string 字段所以暂不暴雷，但属隐性契约漏洞。
- 建议：至少对已声明的非 string 类型做基本校验，或显式记录"仅支持 string"约束。

### [中] url 类型输入无格式/协议校验，存在 SSRF 面
- 位置：`ArtifactSkillCatalogService.java:304-315`（`languageAndUrlSchema`）、`203-220`
- 问题：`bilibili_course_note_pdf`/`video_summary` 的 `url` 字段 schema 仅 `type:string`，无 pattern/host 白名单。normalizeInputValue 对 string 只 trim + enum 校验（url 无 enum），因此任意字符串（含内网地址、file://、非 B 站域名）都会被接受并下发给 Worker 去抓取。
- 证据：`properties.put("url", Map.of("type", "string"))`；无 format/pattern；无域名校验。
- 影响：若 Worker 侧据此发起抓取，构成 SSRF/任意 URL 抓取风险；且"B站讲义"却能传任意 URL，与业务语义不符。
- 建议：对 url 增加协议(https)与域名白名单校验（bilibili 场景限定 bilibili.com），Worker 侧同样二次校验。

### [低] 默认值/规范化把空串统一转为 null 丢弃，可能与必填校验产生耦合歧义
- 位置：`ArtifactSkillCatalogService.java:102-128,203-230`
- 问题：normalize 时空串 string 返回 null 被丢弃，随后 `isMissingRequiredValue` 再判缺失。逻辑正确但分散在多处；`Map.copyOf(normalized)` 不允许 null value（已保证不放 null），耦合较隐晦。
- 影响：可维护性；边界修改易引入 NPE / 必填误判。
- 建议：集中处理"空即缺失"的规则并加注释。

### [低] course_notes 与 bilibili_course_note_pdf 绑定同一 action_key，可能造成路由歧义
- 位置：`ArtifactSkillCatalogService.java:45-57`
- 问题：`actionBindings` 中 `course_notes` 与 `bilibili_course_note_pdf` 都映射到 `COURSE_NOTES`。若下游按 action_key 反查 skill 或做能力路由，两者不可区分。
- 影响：能力/审批路由或统计按 action_key 聚合时二者混淆。
- 建议：确认是否需要区分；需要则拆分 action_key。

## Controller 层

### [中] compareVersions 无参数校验，from/to 任意负数或缺失版本会各自单查
- 位置：`ArtifactJobController.java:88-98`、`ArtifactJobService.java:235-269`
- 问题：`compareArtifactVersions` 直接接收 `from`/`to` int，无 `@Min`/顺序校验；service 分别 `getVersionDetail` 两个版本，任一不存在抛 NOT_FOUND。from>to、from==to 均无语义提示。
- 影响：无效比较请求得不到清晰错误；缺失参数走 Spring 默认 400 但语义弱。
- 建议：加 `@RequestParam` 必填与 `@Min(1)`，并在 service 校验 from<to。

### [低] rollback 控制器三次独立调用 + 事务边界分散
- 位置：`ArtifactJobController.java:100-117`
- 问题：控制器编排了 `getVersionDetail`（读）→`rollbackVersion`（写事务）→`copyFiles`（写事务+远程）→再`getVersionDetail`（读），业务编排逻辑下沉到 Controller，且横跨多个事务无整体一致性。
- 影响：编排逻辑放错层，难以在 service 层保证原子性/复用；见前述 copyFiles 非原子问题。
- 建议：将回滚+文件复制收敛到 service 单一事务方法，Controller 只做转发。

### [低] 所有 Controller 方法均为包私有（缺省访问修饰符）
- 位置：`ArtifactJobController.java`、`ArtifactSkillController.java`、`ArtifactWorkerInputController.java` 各 handler
- 问题：handler 方法无 `public` 修饰。Spring MVC 目前可代理调用包私有方法，但不是稳健写法（CGLIB/未来版本、AOT 编译可能受影响）。
- 影响：可维护性/前向兼容风险。
- 建议：统一显式 `public`。

## DTO / 契约

### [中] 冗余的 @JsonProperty 显式 snake_case，与全局 SNAKE_CASE 策略重复且易漂移
- 位置：`ArtifactSavedSourceResponse.java` 全字段、`ArtifactAcquisitionCallbackReceiptTraceResponse.java`、`ArtifactAcquisitionOperationTraceResponse.java`
- 问题：全局 `spring.jackson.property-naming-strategy: SNAKE_CASE`（application.yml:20）已把 camelCase 自动转 snake_case。这些 DTO 又对部分字段手写 `@JsonProperty("source_id")`/`@JsonProperty("provider_job_status")`，而同类中其他字段（如 receipt 里的 `taskId`/`sourceId`、operation 里的 `requestId`）却不加。加与不加最终序列化结果相同（都 snake_case），属冗余；但一旦有人误以为不加就是 camelCase 而据此调整，会产生契约漂移。
- 证据：ArtifactSavedSourceResponse 每字段 `@JsonProperty`；两个 acquisition trace 只对个别字段加 `@JsonProperty`。
- 影响：契约表达不一致，误导维护者；无功能性差异但坏味道明显。
- 建议：统一依赖全局策略，删除这些冗余注解（除非确需覆盖）。

### [中] readRuntimeTrace 依赖注入的 ObjectMapper(SNAKE_CASE) 做 convertValue，字段策略隐式耦合
- 位置：`ArtifactJobService.java:794-896,957-992`
- 问题：`readRuntimeTrace` 把 result_payload_json 解析成 Map 后，用同一个 SNAKE_CASE 的 `objectMapper.convertValue` 转成各 Trace DTO。转换正确性依赖"map key 是 snake_case 且 DTO 用 camelCase + 全局 SNAKE_CASE"这一隐式约定。一旦某处改用 camelCase Mapper 或 DTO 加了错误 @JsonProperty（见上条），trace 字段会静默变 null。
- 证据：大量 `convertValue(runtimeTracePayload.get("xxx"), XxxTraceResponse.class, ...)`。
- 影响：trace 展示对命名策略高度敏感、脆弱；出错时字段静默丢失而非报错。
- 建议：为 trace 反序列化使用一个显式配置、与 Worker 输出契约锁定的 Mapper，并加单测覆盖真实 payload。

### [低] normalizeAndStripLegacyActionKeys 递归剥离遗留 action 字段属技术债兼容层
- 位置：`ArtifactJobService.java:921-955`
- 问题：为兼容历史 payload，递归遍历整棵 trace 树删除 `action_key`/`action_scope` 等遗留键、并把 `action_checks` 改名 `contract_checks`。这是运行时对旧数据的持续兼容处理，属技术债。
- 影响：每次读取版本详情都全树递归，CPU 开销随 payload 增大；长期存在增加复杂度。
- 建议：以一次性数据迁移清洗历史 payload，之后移除运行时剥离逻辑。

## 数据库迁移（V012 及后续 artifact 表）

### [高] artifact_job 缺少 workspace_id + id 的复合唯一/查询索引，越权校验与详情查询走非最优索引
- 位置：`V012__create_research_and_artifact_tables.sql:1-20`
- 问题：`artifact_job` 主键仅 `id`，另建 `idx_artifact_job_workspace_created(workspace_id, created_at)` 与 `idx_artifact_job_task(task_id)`。但代码大量以 `where workspace_id=? and id=?` 校验归属（getJob/requireArtifactJob/loadRegenerationRow 等），该组合只能用主键 id 命中、再回表过滤 workspace_id，或用 workspace_created 索引扫描。虽功能正确，但归属校验是热点路径。
- 证据：`requireArtifactJob`、`getJob`、`loadRollbackRow` 等均 `where workspace_id = ? and id = ?`。
- 影响：越权校验查询未被专门索引覆盖，数据量大时效率下降。
- 建议：视访问模式增加 `(id, workspace_id)` 或确认主键 id 定位后回表成本可接受。

### [中] V012 建表时 artifact_job.action_key NOT NULL，与 skill-first 设计冲突，靠 V021 补救
- 位置：`V012:6`（`action_key varchar(64) not null`）、`V018`、`V021`
- 问题：初版按 action-first 设计（action_key 必填、无 skill_key/user_requirement/inputs_json）。后续 V018 补 skill 字段、V021 才把 action_key 改 nullable。当前代码 action_key 恒写 null（见前述），迁移历史暴露设计反复。
- 影响：schema 与代码语义不一致的历史债；action_key 列已沦为死列。
- 建议：确认后以迁移删除 action_key 或恢复其用途。

### [中] artifact_job 无 CHECK 约束限制 status 取值，状态机仅靠应用层字符串
- 位置：`V012:10`（`status varchar(32) not null`）
- 问题：status（QUEUED/RUNNING/WAITING_FOR_PROVIDER/COMPLETED/FAILED 等）无枚举/CHECK 约束，`markWaiting` 还会写入任意 `waitStatus.trim()`（来自 Worker phase 字符串）。
- 证据：`markWaiting` 写入 `waitStatus.trim()`；DB 无约束。
- 影响：Worker 传入任意 phase 字符串都会落成 job.status，前端状态机可能遇到未知状态；数据可信度低。
- 建议：约束 status 白名单（应用层枚举 + 可选 DB CHECK），markWaiting 前校验。

### [中] artifact_version FK 无级联，且 origin_task_id FK（V023）指向 task，删除任务受阻/孤儿风险
- 位置：`V012:22-33`、`V023:1-12`
- 问题：`artifact_version` 对 `artifact_job` 有 FK 无 ON DELETE 处理；V023 又加 `fk_artifact_version_origin_task` 指向 task。artifact_file 也对 artifact_version 有 FK。三层 FK 均无级联策略，删除 job/task 会被 FK 阻塞或需手工按序清理。
- 影响：数据清理/GDPR 删除困难；无级联也无软删，长期堆积。
- 建议：明确删除策略（级联或应用层顺序删除 + 归档）。

### [中] artifact_version 缺 workspace_id 冗余列，所有版本查询强制 join artifact_job
- 位置：`V012:22-35`、`ArtifactJobService.java:360-423` 等
- 问题：artifact_version 不含 workspace_id，listVersions/getVersionDetail/loadVersionForSource/ExportService 全部 `join artifact_job aj on aj.id=av.artifact_job_id where aj.workspace_id=?`。越权校验完全依赖 join。
- 影响：每次版本访问都需 join；索引 `uq_artifact_version_job_no` 覆盖了 (artifact_job_id, version_no) 尚可，但 workspace 过滤走 job 表。属可接受但增加复杂度。
- 建议：视性能考虑冗余 workspace_id 到 version 并加校验，或维持现状但确认 join 索引充分。

### [低] artifact_job_run.task_id 作为主键，隐含"一个 task 只能一个 run"，语义脆弱
- 位置：`V023:14-27`
- 问题：`artifact_job_run` 以 `task_id` 为主键，同时另有 `uq_artifact_job_run_no(artifact_job_id, run_no)`。以 task_id 作 PK 依赖"每次 run 都新建独立 task"这一约定。若将来复用 task 或补偿重试同 task，会主键冲突。
- 影响：耦合"task 与 run 一一对应"的隐式约定，扩展受限。
- 建议：用独立 run id 作主键，task_id 加唯一索引表达一一对应更清晰。

### [低] research_trace.trace_message varchar(1000) 与 artifact 侧 error_message 截断策略分散
- 位置：`V012:63`、`V023:52`、`ArtifactExportService.java:335-338`
- 问题：多处对消息/错误做 1000 长度约束，应用层 `abbreviate(msg,1000)` 与 DB 列宽各自维护，若列宽调整需同步改代码常量。
- 影响：魔法数字分散，易不一致。
- 建议：集中常量或依赖 DB 报错处理。

## 与设计文档偏差

### [中] 完成回调因坏 file_name 回滚，违反契约"归档失败不回滚正文版本"
- 位置：`ArtifactExportService.java:295-312`、契约 `docs/最小接口契约.md:565`
- 问题：契约明确"归档失败记录 FAILED + error_message，不回滚正文版本；后续下载会重试"。但 `readCompiledFileNameIfPresent` 对非法 file_name 抛 BusinessException 冒泡到 complete 事务，导致正文版本插入一并回滚——与契约相反。
- 证据：契约 565 行；代码抛异常路径见前述[高]项。
- 影响：实现与冻结契约不一致，坏消息使任务无法完成。
- 建议：按契约将所有归档异常降级为 FAILED 记录，绝不回滚版本。

### [低] ArtifactJobResponse 多返回 skill_key，超出契约 3.17 声明字段
- 位置：`ArtifactJobResponse.java`、契约 `最小接口契约.md:480-484`
- 问题：契约 3.17 返回字段仅 `artifact_job_id / task_id / status`，实现额外返回 `skill_key`。
- 影响：属超集（一般兼容），但与冻结契约不符，前端若严格校验可能报错。
- 建议：更新契约或移除多余字段以对齐。

### [低] ArtifactSavedSourceResponse 多返回 generated_by/generated_ref_id，超出契约 3.21
- 位置：`ArtifactSavedSourceResponse.java`、契约 `最小接口契约.md:529-536`
- 问题：契约 3.21 返回 `source_id/artifact_job_id/artifact_version_id/status/parse_status/index_status`，实现额外返回 `generated_by`、`generated_ref_id`。
- 影响：超集偏差，同上。
- 建议：对齐契约。

### [低] writeVersionToKnowledge 未校验 versionNo 内容与 item_type 组合，Wiki 追加语义依赖下游
- 位置：`ArtifactJobService.java:456-474`、契约 `最小接口契约.md:551-553`
- 问题：写回直接把版本正文交给 `knowledgeCommandService.createItem`，title 覆盖逻辑简单。契约称"Wiki 同标题写回沿用追加版本语义"，该语义完全依赖 KnowledgeCommandService，本层无校验/无幂等，重复点击会重复写回。
- 影响：重复写回产生多条知识条目/版本；无操作幂等。
- 建议：增加写回幂等（如按 version+item_type 去重）或提示已写回。

## 其他坏味道 / 缺失校验 / 测试缺口

### [中] extractMarkdown 兜底把整个 payload JSON 当正文，可能污染 content_markdown
- 位置：`ArtifactJobService.java:1057-1070`
- 问题：当 payload 无 `markdown`/`content_markdown` 字段时，`extractMarkdown` 返回 `Json.write(objectMapper, payload)`，即把整个结果载荷序列化成 JSON 字符串塞进 content_markdown。
- 证据：`return Json.write(objectMapper, payload);`
- 影响：正文变成一坨 JSON，save-as-source / writeback / 导出都会把该 JSON 当 Markdown 使用，语义错误；下游资料解析也会被污染。
- 建议：无正文时应置空并置版本状态为异常/降级，而非塞 JSON。

### [中] markFailed 的 WorkerFailRequest 参数完全未使用，失败原因未落库
- 位置：`ArtifactJobService.java:549-556`
- 问题：`markFailed` 只把 job.status 置 FAILED，`request`（含 errorCode/errorMessage/phase）完全未使用。artifact_job 也无 error 字段。失败原因只进 task 表，job 侧无留痕。
- 证据：方法体仅一条 update，未引用 `request`。
- 影响：产物 job 层看不到失败原因；排查需跨表；参数是死参。
- 建议：在 artifact_job 增加 error_code/error_message 或至少去掉误导性的未用参数。

### [低] compareVersions 采用行频次差分而非真正 diff，结果易误导
- 位置：`ArtifactJobService.java:235-269,651-657`
- 问题：比较逻辑基于"每行文本出现次数"的多重集差集统计新增/删除/保留行数，非顺序/上下文 diff。相同内容行移动位置会被判为"保留"，重排、局部编辑的统计与直觉不符。
- 影响：版本对比数字对用户有误导性。
- 建议：明确其为"行集合近似统计"或改用真正的 LCS/diff。

### [低] saveVersionAsSource 无幂等，重复保存产生多条资料
- 位置：`ArtifactJobService.java:425-454`
- 问题：每次调用都 `generatedSourceService.saveMarkdown(...)` 新建资料，传入 `row.versionId()` 作为 generatedRefId 但本层不检查是否已保存过。
- 影响：重复点击"保存为资料"生成重复 source。
- 建议：按 (generated_by, generated_ref_id=versionId) 去重或返回既有资料。

### [中] 只读查询方法未加 @Transactional(readOnly=true)，且 requireWorkspace 与业务查询非同一事务
- 位置：`ArtifactJobService.java:271-423`（listJobs/getJob/listVersions/getVersionDetail 等均无事务注解）
- 问题：读方法无 `readOnly` 事务，多条 SQL（requireWorkspace + 主查询 + loadArtifactFiles 等）各自独立连接，无一致性快照；也拿不到只读优化。
- 影响：读到跨语句不一致的中间态（如查详情时版本被并发插入）；轻微性能损失。
- 建议：读路径加 `@Transactional(readOnly=true)`。

### [中] 测试缺口：并发完成/重复回调/坏 file_name/regenerate 旧任务迟到完成 等关键路径缺覆盖
- 位置：整体
- 问题：从代码看，版本号竞态、无幂等键重复 complete、export_trace 坏文件名回滚、regenerate 后旧 run markRunning 静默失效、下载触发远程物化并发等高风险路径，缺少针对性测试（现有 Phase6ResearchArtifactContractTest 偏契约字段）。
- 影响：上述严重/高危问题无回归防护。
- 建议：补并发与幂等专项测试（含唯一约束冲突、重复回调、毒药消息、跨 run 状态）。
