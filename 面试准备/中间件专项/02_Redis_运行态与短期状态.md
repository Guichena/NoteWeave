# Redis：运行态、票据与短期状态

> 本文件为 2026-06-01 加深版。Redis 在 NoteWeave 里是短期状态层，不是业务事实源，也不是后台任务主队列。

## 0. 一句话定位

Redis 用来承接“过期后可以丢、丢了可以从 MySQL 事实源降级恢复”的状态：WebSocket 一次性 ticket、流式运行态、事件缓冲、上传 bitmap、health echo。

## 1. 当前真实用法

### WebSocket ticket

代码锚点：`WebSocketTicketService`

- key：`chat:ws-ticket:{ticket}`
- TTL：60 秒
- 行为：创建 ticket 后，握手时 `getAndDelete` 一次性消费。

面试怎么讲：
- 这是为了避免 WebSocket 直接带长期 token，也避免 ticket 被重复使用。
- ticket 失效后重新申请即可，不影响 MySQL 中正式会话和消息。

### Chat runtime state

代码锚点：`ChatRuntimeStateStore`

- key：`chat:{sessionId}:runtime`
- key：`chat:{sessionId}:short_term`
- key：`chat:{sessionId}:stream`
- key：`chat:{sessionId}:events`
- TTL：2 小时

保存内容：
- runtime：lastSeq、lastAckSeq、streamId、requestId。
- short term：当前会话短期上下文。
- stream：正在流式输出的状态。
- events：服务端事件列表，用于断线后按 seq 恢复。

面试怎么讲：
- Redis 保存的是体验层运行态，正式 ChatMessage、Citation、Trace 仍然落 MySQL。
- Redis 丢失会影响“继续推送和恢复细粒度 delta”，但不应该让正式历史消息丢失。

### 上传 bitmap

代码锚点：`UploadBitmapService`

- key：`upload:{uploadId}`
- 数据结构：bitmap，按 chunkIndex 标记分片是否已上传。
- TTL：由上传会话过期时间控制。

面试怎么讲：
- bitmap 适合快速判断某个 chunk 是否已上传，比每次查 DB 更轻。
- 但最终合并仍要回到 `document_upload`、`upload_chunk`、MinIO 对象和 Task 状态。

### Health check

代码锚点：`SystemHealthService`

- 写入临时 `noteweave:health:{nanoTime}`，读回后删除。
- 结果落 `system_health_snapshot`，供 Admin/Ops 查看。

## 2. 高频深问与答法

### Q1: 为什么 runtime state 放 Redis，不直接放 MySQL？

答：流式输出和断线恢复会频繁写 lastSeq、partial state、events。如果都写 MySQL，会把高频运行态和正式业务事实混在一起。Redis 适合低延迟和 TTL 自动过期；MySQL 只保存最终消息、Citation、Trace 和长期 Memory。这样既保证体验，又不污染事实源。

### Q2: Redis 丢失后系统怎么兜底？

答：分层兜底。WebSocket ticket 丢失就重新申请；runtime/events 丢失就不能精细恢复 delta，但可以从 MySQL 的 ChatSession、ChatMessage、Citation 看到正式历史；上传 bitmap 丢失时可以回查 `upload_chunk` 或让客户端重传/校验；长期 Memory 不依赖 Redis，因此不会因为 Redis 丢失而丢长期知识。

### Q3: 为什么 Redis 不做 Task 队列？

答：当前后台任务需要审计、重试、取消、Admin 介入和业务状态对齐，所以主线是 MySQL Task/Outbox + Kafka。Redis Stream 也能做队列，但这里会削弱 Outbox 和 TaskEvent 的统一排障模型。面试要明确：Redis 是运行态，不是后台任务事实源。

### Q4: Redis 里存 events 会不会无限增长？

答：不会按永久日志设计。`chat:{sessionId}:events` 有 2 小时 TTL，服务端只用于短期断线恢复；长期审计和历史回答在 MySQL 的 ChatMessage、RetrievalTrace、LLMCallLog 中。

## 3. 性能和风险怎么说

- 可观察指标：Redis health latency、runtime key 数量、WebSocket 断线恢复成功率、上传 bitmap 命中率。
- 风险点：Redis 宕机会影响实时体验；因此必须把正式结果落 MySQL，不能只靠 Redis。
- 扩展点：如果连接数上升，可考虑 runtime key 分片、事件列表长度限制、session 级限流和更细的 WebSocket backpressure。

## 4. 不能说满

- 不要说 Redis 是主任务队列。
- 不要说 Redis 是长期 Memory 事实源。
- 不要说 Redis 丢了用户所有会话都丢了。
- 不要说 stop 一定能中断外部 LLM 已经发出的请求；它只能让后端在安全点停止继续推送和写入。
