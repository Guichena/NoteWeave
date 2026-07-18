## 其他模块 · task/worker/workspace/quota/infra/security/config/common + 迁移/配置/构建
（审查发现如下，分批追加）

## task 模块

### [低] 任务端点鉴权依赖拦截器路径正则，Controller 自身无兜底
- 位置：`backend/src/main/java/com/noteweave/task/TaskController.java:20-28`
- 问题：`GET /api/v2/tasks/{taskId}` 的鉴权完全依赖 `WorkspaceAuthorizationInterceptor` 中 `TASK_PATH` 正则匹配后调用 `requireTaskPermission`（已确认覆盖，非越权）。但 Controller/Service 层无兜底校验，一旦拦截器正则或注册路径被改动即静默失去鉴权。
- 影响：授权逻辑与路由正则强耦合，可维护性/健壮性风险。
- 建议：在 Service 关键入口补充 `WorkspaceAccessGuard` 显式校验，做纵深防御。

### [中] streamEvents 并非真正的 SSE 流，一次性拼接全量返回
- 位置：`backend/src/main/java/com/noteweave/task/TaskService.java:441-467`、`TaskController.java:25-28`
- 问题：端点声明 `produces=TEXT_EVENT_STREAM_VALUE` 但返回值是普通 `String`，把当前所有 task_event 拼成一个字符串一次性返回，连接随即关闭。无法增量推送后续进度事件，也无 `Last-Event-ID`/断点续传处理。
- 证据：方法签名 `public String streamEvents(...)`，循环 `for (TaskEventResponse event : events)` 拼 StringBuilder 后 return。
- 影响：前端以 EventSource 订阅将只收到历史事件后即断开并不断重连，退化为轮询；与设计文档中“任务进度实时流”契约不符。
- 建议：改用 `SseEmitter`/`Flux<ServerSentEvent>` 结合 Redis pub/sub 或轮询增量推送；至少支持 `Last-Event-ID`。

### [低] streamEvents 重复查询与全表事件加载
- 位置：`backend/src/main/java/com/noteweave/task/TaskService.java:442-454`
- 问题：先 `getTask(taskId)`（内部又加载 waitContext 并再查 task_event）仅用于存在性校验，随后再次全量查询 task_event，且无分页/上限。
- 影响：长生命周期任务事件表膨胀后单次响应可能很大，重复 SQL 增加开销。
- 建议：存在性校验用轻量 `currentTaskStatus`，事件查询加 `limit`/游标。

### [低] failTask 的 error_message 由 errorCode+message 拼接，可能超列长
- 位置：`backend/src/main/java/com/noteweave/task/TaskService.java:326`
- 问题：`error_message = errorCode + ": " + message`，未截断；若迁移中该列有长度限制且 message 较长会抛异常导致失败流程本身失败。
- 建议：截断到列长或使用 text 列。

## worker 模块（通用回调/幂等）

### [中] 幂等去重依赖“唯一冲突后同事务可继续查询”，仅在 MySQL 成立，移植 PostgreSQL 会破裂
- 位置：`backend/src/main/java/com/noteweave/worker/WorkerTaskCallbackService.java:179-193`
- 问题：`registerCallback` 在同一 `@Transactional` 内 `insert` 命中唯一索引后捕获 `DataIntegrityViolationException`，随后继续 `taskService.getTaskRef/getTask`。本项目实际使用 MySQL（`application.yml` 为 `jdbc:mysql`，依赖 `mysql-connector-j`/`flyway-mysql`），InnoDB 下重复键错误只回滚该语句、事务仍可用，故当前可正常返回 `DUPLICATE_*`。但该写法强依赖引擎语义：一旦迁移到 PostgreSQL，唯一冲突会使整个事务进入 aborted 状态，catch 内 SELECT 必然再抛错，幂等应答变 500 并触发 worker 重试风暴。
- 影响：数据库可移植性隐患；与部分设计文档若声明 PostgreSQL 存在偏差（需核对 `docs/技术栈与数据库设计.md`）。
- 建议：改用 `INSERT ... ON DUPLICATE KEY`（MySQL）/`ON CONFLICT DO NOTHING`（PG）并按受影响行数判重，避免依赖异常+同事务续查。

## config / 构建 / docker

### [高] 默认部署链路无生产加固：内置弱口令 + 回退开启 + prod profile 从不激活
- 位置：`application.yml:14,54,91,97-98`、`docker-compose.yml:152-154,7,10,138`
- 问题：多处硬编码/弱默认值：datasource 密码默认 `noteweave123`，MinIO `minioadmin/minioadmin`，MySQL root `root`，内部服务令牌 docker 默认 `noteweave-internal-dev`（恰是 `ProductionConfigurationGuard.isWeak` 明令禁止的值），`NOTEWEAVE_LOCAL_USER_FALLBACK` 默认 `true`。而 `ProductionConfigurationGuard` 仅在 `@Profile("prod")` 生效；compose 里 `NOTEWEAVE_ENVIRONMENT=development` 且从无任何地方设置 `SPRING_PROFILES_ACTIVE=prod`。
- 证据：compose backend 段落 152-154；guard 类 `@Profile("prod")`。
- 影响：按仓库默认 compose 起服务即“无认证 + 弱口令”全开；且没有任何生产 profile/override 文件把守，极易被误部署上线。
- 建议：提供 `application-prod.yml` 与 prod compose override，并在启动脚本强制 `SPRING_PROFILES_ACTIVE=prod`；或将关键校验从 profile 判定改为无条件（按 environment!=local 触发）。

### [中] Actuator 位于 /actuator 而非 /api/v2/actuator，身份过滤器的豁免分支为死代码；actuator 端点无认证
- 位置：`application.yml:136-140`、`security/ApiRequestIdentityFilter.java:30-33`
- 问题：actuator 默认 base-path 为 `/actuator`（compose healthcheck 也命中 `/actuator/health`），不在 `/api/v2/` 前缀内，`ApiRequestIdentityFilter` 本就不拦截它，因此代码中 `path.startsWith("/api/v2/actuator")` 的豁免分支永不命中（死代码）。同时 `/actuator/*`（health/info/metrics）对外无认证。
- 影响：metrics/info 可能泄露内部指标与构建信息；豁免分支误导维护者以为 actuator 在 /api/v2 下受控。
- 建议：删除无效豁免分支；对 actuator 端点加内网限制或独立端口/凭证。

### [中] 依赖版本偏旧且无依赖漏洞扫描
- 位置：`backend/pom.xml:10,52-59,99-102`
- 问题：Spring Boot 3.3.5（2024-10）、minio 8.5.12、elasticsearch-java 8.15.3，截至 2026-07 均非最新且可能存在已披露 CVE；构建无 `dependency-check`/`versions` 插件与 SBOM。
- 影响：潜在已知漏洞未被发现；升级滞后。
- 建议：升级到受支持补丁版本并接入 OWASP dependency-check / CI 依赖审计。

## workspace 模块

### [中] 会话令牌校验查询无索引，认证热路径全表扫描
- 位置：`db/migration/V001__create_user_and_workspace_tables.sql:13-21`、`security/ApiRequestIdentityFilter.java:61-67`
- 问题：`user_session` 仅在 `id` 上有主键，`session_token` 无索引；而每个 `/api/v2/**` 请求都执行 `where s.session_token = ?` 联表查询。随会话表增长，认证将逐渐退化为全表扫描。
- 影响：登录用户越多，每请求鉴权延迟越高，DB 负载放大。
- 建议：为 `user_session(session_token)` 加唯一索引（同时天然防重复令牌），或存令牌哈希并对哈希列建索引。

### [低] putMember 采用 count 后 update/insert 的非原子 upsert
- 位置：`workspace/WorkspaceMembershipService.java:65-79`
- 问题：先 `select count(*)` 再决定 update 或 insert；虽有 `uk_workspace_member_user` 唯一约束兜底，但并发下 insert 分支可能撞唯一键抛异常（且在 MySQL 事务内仍会失败该语句）。
- 建议：改用 `insert ... on duplicate key update` 单语句 upsert。

### [优化] 成员角色/状态入参已用 @Pattern 白名单校验，无越权升级为 OWNER 风险（确认项）
- 位置：`workspace/UpdateWorkspaceMemberRequest.java:7-8`
- 说明：`role` 限 `EDITOR|VIEWER`、`status` 限 `ACTIVE|SUSPENDED`，且 `requireNotOwner` 阻止改动 owner，故无法经成员接口制造第二个 OWNER 或注入非法角色。此为正向确认，非缺陷。

## 迁移（V001~V047）

### [低] task_outbox 的可靠性列（attempt_count/lease/next_attempt_at/dead_lettered_at）迟至 V022/V023 才补齐
- 位置：`V002__create_task_and_outbox_tables.sql:40-52`、`V022__harden_artifact_outbox_dispatch.sql:1-6`、`V023__productionize_artifact_files_and_outbox.sql:60-64`
- 问题：`TaskOutboxDispatcherService` 依赖的 attempt_count、lease_owner、lease_until、next_attempt_at、dead_lettered_at、last_error 等列在 V002 建表时不存在，由后续 V022/V023（命名偏 artifact）补齐并加索引。经核对列与索引均已具备，无缺列运行时错误；但可靠性字段与初始建表分离、且靠 artifact 主题迁移附带完成，语义耦合易混淆。
- 影响：可维护性/可读性；若有人只回滚 V023/V022 而保留分发器代码将直接报列不存在。
- 建议：迁移文件命名与内容对齐（通用 outbox 单独迁移），并在分发器补充列存在性/健康检查。

### [优化] 迁移版本 V001~V047 连续无缺号，建表顺序（用户→工作台→任务/outbox→资料→会话→...）符合外键依赖
- 位置：`db/migration/`
- 说明：正向确认，未发现乱序或缺失版本；外键引用的父表均在更早版本创建。

### [高] 幂等键为可选请求头，缺失时完全无幂等保护
- 位置：`WorkerTaskCallbackController.java:25,34,43,52`、`WorkerTaskCallbackService.java:180-182`
- 问题：`X-NoteWeave-Idempotency-Key` 声明 `required=false`，Service 中 key 为空即 `return null` 跳过去重。worker 若未携带该头，complete/fail 等回调在网络重试下会重复执行 completeFromWorker/materializeExports 等副作用。
- 影响：重复计费/重复导出/重复状态事件；complete 的产物物化可能重复生成文件。
- 建议：对写类回调（progress/complete/fail）将幂等键设为必填并校验非空，缺失返回 400。

### [中] complete 回调在数据库事务内执行重导出物化，长事务持锁
- 位置：`WorkerTaskCallbackService.java:114-136`
- 问题：`complete` 标注 `@Transactional`，内部调用 `artifactExportService.materializeExports(outcome.resultRef())`，该操作可能涉及对象存储 I/O 与大量文件写入，却被包在持有 task 行锁的数据库事务中。
- 影响：事务时间被外部 I/O 拉长，占用连接与行锁，降低并发；外部存储超时会导致整笔状态更新回滚。
- 建议：先提交状态，再在事务外/异步物化导出，或用 outbox 触发。

### [低] worker_callback_receipt 唯一索引不含 callback_type，跨回调类型复用同一幂等键会误判为重复
- 位置：`db/migration/V024__add_worker_callback_idempotency.sql:10-11`
- 问题：唯一索引为 `(task_id, idempotency_key)`，但代码按 `callback_type` 生成 `DUPLICATE_<type>` 应答。若不同类型回调复用同一 key，会被判为重复并跳过。
- 建议：视契约将索引改为 `(task_id, callback_type, idempotency_key)`，或强制 key 全局唯一。

## quota 模块

### [中] Redis 不可用时降级为进程内限流，多实例下并发上限失去全局约束
- 位置：`backend/src/main/java/com/noteweave/quota/WorkloadQuotaService.java:192-212,340-357`
- 问题：Redis 异常时 `acquireLease` 走 `localLeaseDecision`，`localConcurrencyLimit` 默认 1，`distributedConcurrencyLimit` 默认 4。N 个后端实例各自本地限流，全局并发可达 N×local，且与分布式配额语义不一致。
- 影响：Redis 抖动期间配额被绕过或收紧，行为不确定；生产环境可能超发下游 worker 任务。
- 建议：Redis 不可用时对关键 workload 采取 fail-closed 或告警，并在文档中明确降级语义。

### [低] localBuckets 无淘汰，随 workspace×workload 组合增长
- 位置：`WorkloadQuotaService.java:107,324-325`
- 问题：`localBuckets` 以 key 缓存令牌桶且从不清理（不同于 localLeases 会按过期删除条目）。
- 影响：长期运行下按 workspace/workload/用户指纹维度累积，缓慢内存增长。
- 建议：加 TTL/LRU 上限或定期清理空闲桶。

### [优化] requireRate 与 acquireLease 为两次独立 Redis 调用，非原子
- 位置：`WorkloadQuotaService.java:161-212`、`task/TaskService.java:88-89`
- 问题：限流与并发租约分两步执行，之间存在窗口；高并发下速率通过但租约拒绝或反之，语义可接受但产生额外往返。
- 建议：如需强一致可合并为单个 Lua 脚本。

## security / config 模块

### [高] 本地用户回退默认开启，生产防护仅在 prod profile 激活时才生效
- 位置：`security/ApiRequestIdentityFilter.java:23,46-52`、`security/CurrentUserProvider.java:20-31`、`config/ProductionConfigurationGuard.java:9,53-55`
- 问题：`noteweave.security.local-user-fallback` 默认 `true`，无 Bearer 会话时把请求当作 `local-user` 放行，等于关闭认证。唯一强制关闭它的 `ProductionConfigurationGuard` 标注 `@Profile("prod")`，只有当运行时激活 `prod` profile 才会启动校验。
- 证据：Guard 类 `@Profile("prod")`；若部署时未设置 `SPRING_PROFILES_ACTIVE=prod`，Guard 不加载，回退保持开启。
- 影响：任何未正确设置 prod profile 的生产部署将完全无认证，所有 API 以 `local-user` 身份可访问全部工作台数据。
- 建议：将 fallback 默认改为 `false`（显式在 local/dev profile 打开），或把生产校验改为基于显式开关而非 profile 存在性；启动时对 fallback=true 且非 dev 环境强制告警/拒绝。

### [中] 会话令牌以明文存储并明文比较
- 位置：`security/ApiRequestIdentityFilter.java:60-67`
- 问题：`where s.session_token = ?` 直接用请求中的原始 token 与库中列等值比较，说明 `user_session.session_token` 以明文持久化。
- 影响：数据库或备份泄露即导致所有活动会话可被直接冒用；无法做令牌轮换/吊销哈希对比。
- 建议：存储令牌的 SHA-256/HMAC 摘要，比较时对入参哈希；令牌本身只在下发时出现。

### [中] 授权拦截器仅覆盖硬编码的一组路径正则，其余 /api/v2 资源端点无集中鉴权
- 位置：`security/WorkspaceAuthorizationInterceptor.java:14-53`、`config/WebMvcAuthorizationConfig.java:19`
- 问题：拦截器仅匹配 workspaces / knowledge-items / messages…/save-as-note / tasks / conversations/{id}/messages / uploads / chat/requests/{id}/stream。诸如 `GET /api/v2/conversations/{id}`（无 /messages）、`/api/v2/research-runs/{id}`、`/api/v2/artifact-jobs/{id}`、`/api/v2/memory/*`、`/api/v2/knowledge-citations/*` 等不带 workspaceId 前缀的端点均不被匹配，`preHandle` 直接 `return true` 放行，鉴权完全依赖各 Controller 自行调用 guard。
- 影响：任何遗漏自查的 Controller 即产生越权；授权面分散、易漏。
- 建议：改为默认拒绝（白名单显式豁免）或为所有资源端点建立统一的资源→workspace 解析与校验机制。

### [中] /api/v2/actuator 被身份过滤器整体跳过
- 位置：`security/ApiRequestIdentityFilter.java:30-33`
- 问题：`shouldNotFilter` 对 `/api/v2/actuator` 前缀返回 true，跳过身份校验；若 actuator 暴露在该路径且开放敏感端点（env/heapdump/loggers），将无认证可达。
- 影响：取决于 actuator 暴露配置，可能泄露配置/内存或允许运行时改日志级别。
- 建议：核对 `management.endpoints` 暴露面，对 actuator 单独加内网/凭证保护，勿简单豁免。

### [优化] requireResourcePermission 用字符串拼接表名执行 SQL
- 位置：`security/WorkspaceAccessGuard.java:141`
- 问题：`"select workspace_id from " + table + " where id = ?"`，table 目前均为内部字面量（非用户输入），无注入风险，但模式脆弱。
- 建议：改为固定映射/枚举到预定义 SQL，杜绝未来误用。

## common 模块

### [中] 全局异常处理缺少常见 4xx 分支，参数类错误被降级为 500
- 位置：`common/GlobalExceptionHandler.java:39-44`
- 问题：仅处理 `BusinessException`、`MethodArgumentNotValidException`、`HttpMediaTypeNotSupportedException`，其余全部落入通用 `Exception` → 返回 500。`HttpMessageNotReadableException`（JSON 解析失败）、`ConstraintViolationException`（方法级 @Validated 校验 path/query）、`MissingServletRequestParameterException`、`MethodArgumentTypeMismatchException` 等客户端错误都会被当作服务器 500。
- 影响：客户端错误返回 500，污染错误率指标与告警，语义误导，且可能触发上游重试。
- 建议：为上述异常补充 400/404 分支，返回规范错误码。

## infra 模块 · task_outbox 分发 / Kafka 消费

### [中] Outbox 投递成功与状态落库非原子，进程崩溃会重复投递（依赖消费端幂等）
- 位置：`infra/TaskOutboxDispatcherService.java:118-126`
- 问题：`kafkaTemplate.send(...).get()` 成功后再单独 UPDATE 为 SENT。若在 send 成功与 UPDATE 之间进程崩溃/超时，lease 到期后该行被重新 claim 并再次 send，产生重复消息。
- 影响：至少一次投递语义，下游消费者必须幂等。`onSourceParse` 有 `isProcessable` 兜底，但 wiki ingest/retract 等 handler 幂等性需逐一确认。
- 建议：文档明确 at-least-once 契约；对每个 topic 消费方补充幂等键校验。

### [中] 死信重驱 resumeRunning 判定硬编码 topic 名，与可配置 topic 脱钩
- 位置：`infra/TaskOutboxDispatcherService.java:190`
- 问题：`taskService.redriveTask(row.taskId(), "noteweave.source.index".equals(row.topic()))`，用硬编码字符串与 `row.topic()` 比较，而 topic 实际来自 `NoteWeaveProperties.Topics.sourceIndex()` 可配置值。若配置改名，判定恒为 false。
- 建议：与 `configured.sourceIndex()` 比较而非字面量。

### [低] 单线程顺序分发，每条最长阻塞 10s，批量 50 时可能积压
- 位置：`infra/TaskOutboxDispatcherService.java:87-89,119-120`
- 问题：`dispatchReadyMessages` 顺序遍历最多 50 行，每次 `send().get(10, SECONDS)` 同步阻塞；Kafka 慢时单轮可长达数百秒，与 1s fixedDelay 严重不匹配。
- 建议：批量异步发送后统一等待，或减小批量/并行化。

### [中] Kafka 消费重试耗尽后消息进入 DLT，但关联 task 未被置为 FAILED，永久卡在 RUNNING
- 位置：`infra/KafkaConfig.java:94-113`、`infra/KafkaTaskConsumer.java:118-123`
- 问题：`kafkaListenerContainerFactory` 已配置 `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` + `FixedBackOff(1s, 2)`（共 3 次投递），所以毒消息不会无限重投（此前担忧已被证伪）。但 `recovered` 时仅把消息发到 `<topic>.DLT` 并提交 offset，未调用 `taskService.failTask`；而 `onSourceParse` 已在消费时 `startTask` 置 RUNNING。
- 证据：RetryListener.recovered 只做计数，无业务失败处理；且无 `<topic>.DLT` 的消费者。
- 影响：解析/摄取确定性失败的任务被静默投入 DLT，任务永远停留在 RUNNING，用户侧无终态、无重试入口。
- 建议：在 recoverer/DLT 消费链路中将对应 task 置 FAILED；或提供 DLT 消费者与重驱。

### [中] LocalObjectStorage 的 write/read/exists 未校验 objectKey 路径穿越（仅 delete 校验）
- 位置：`infra/LocalObjectStorage.java:26-49`（对比 `delete` 52-57 有 `startsWith` 校验）
- 问题：`write`/`read`/`exists` 直接 `root.resolve(bucket).resolve(objectKey).normalize()`，未校验结果是否仍在 bucket 根内。若 objectKey 含 `../` 序列，可读写 bucket/root 之外的文件；`delete` 却做了 `startsWith(bucketRoot)` 校验，说明作者已知风险但未统一。
- 影响：若 objectKey 部分来自外部输入（文件名/导出键），存在任意文件读写。
- 建议：抽取统一的 `resolveWithin(bucketRoot, objectKey)` 校验并在四个方法复用。

### [低] source.chunk / generated.ingest 消费者为空实现占位
- 位置：`infra/KafkaTaskConsumer.java:63-67,100-104`
- 问题：两个 `@KafkaListener` 仅打 debug 日志、无处理逻辑，却会消费并提交 offset。
- 影响：占位死代码，易误以为已实现链路。
- 建议：若链路未落地，暂不订阅或补 TODO 与告警。
