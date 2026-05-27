# Redis：运行态与短期状态

## 0. 本篇定位

这篇用于回答 NoteWeave 为什么用 Redis、Redis 在上传和 WebSocket Runtime 中具体怎么用、它和 MySQL/Kafka 的边界，以及面试里的 Bitmap、TTL、缓存一致性、穿透击穿雪崩、分布式锁等八股问题该怎么结合项目讲。

## 1. 本主题面试官想考什么

面试官问 Redis，通常会看你是否知道：

1. Redis 在项目里到底存什么。
2. 哪些数据不能放 Redis 当唯一事实源。
3. Bitmap、TTL、短期状态、断线恢复如何落地。
4. Redis 和 Kafka、MySQL 的边界是否清楚。
5. 常见缓存八股能不能结合真实场景，而不是硬背。

## 2. 高频问题清单

基础问题：

- NoteWeave 为什么用 Redis？
- Redis 在上传链路里怎么用？
- Redis 在 WebSocket Runtime 里怎么用？

进阶问题：

- 为什么 Redis 不作为主任务队列？
- 为什么 runtime state 不全部落 MySQL？
- Redis 里的 key 过期后会怎样？

深挖追问：

- Bitmap 为什么适合记录分片上传？
- stop/resume 如何依赖 Redis runtime state？
- Redis 挂了，哪些功能受影响，哪些不受影响？

压力追问：

- 项目有没有本地缓存 + Redis 二级缓存？
- Redis QPS/P99 怎么回答？
- 缓存穿透、击穿、雪崩怎么结合项目讲？

## 3. 问答与讲解

### Q1：NoteWeave 里 Redis 承担什么职责？

#### 面试官为什么问

这是边界题。面试官想确认你不会把 Redis 讲成万能数据库或主队列。

#### 回答思路

先说 Redis 存短期运行态和轻量进度状态，再明确正式业务事实仍在 MySQL，后台任务分发仍在 Kafka。

#### 结合 NoteWeave 怎么答

Redis 主要有两类用途：上传链路用 Bitmap 标记分片状态；Chat Runtime 用 key/value JSON 保存 runtime state、short term context、stream state、events，并设置 2 小时 TTL。

#### 技术原理 / 链路设计讲解

Redis 适合低延迟、高频读写、短生命周期状态。上传进度和 WebSocket 运行态都属于这种数据。但正式消息、Citation、Task、Artifact、Memory、权限关系仍然必须落 MySQL。

#### 实现兜底锚点

- `UploadBitmapService`
- `ChatRuntimeStateStore`
- `WebSocketTicketService`
- `Phase5WorkspaceChatRuntimeIntegrationTest`
- `ChatRuntimeStateStoreTest`
- `SystemHealthService.checkRedis()`

#### 可直接复述的面试回答

Redis 在 NoteWeave 里主要承载短期状态，而不是长期业务事实。上传链路里，它用 Bitmap 记录某个 uploadId 下哪些 chunk 已上传，便于快速查看进度和支持断点续传；但 merge 时仍然会校验 MySQL 的上传记录和 MinIO 对象。WebSocket Runtime 里，Redis 保存 runtime state、short term context、stream state、event buffer、partial content 和 ack/resume 信息，用来支持流式输出、中断和断线恢复。这些数据生命周期短、更新频繁，适合 Redis。正式消息、Citation、Task、Memory、Artifact 仍然落 MySQL，主异步任务仍然走 Kafka。

#### 常见追问

- Redis 挂了会不会丢数据？
- 为什么 runtime state 不直接存 MySQL？
- Redis 里 key 怎么命名？

#### 常见坑

- 不要说 Redis 是主任务队列。
- 不要说 Redis 是唯一事实源。

### Q2：上传链路为什么用 Redis Bitmap？

#### 面试官为什么问

这是数据结构题。面试官想看你能不能把 Redis 八股落到项目。

#### 回答思路

讲分片上传本质是“第几个 chunk 是否已到”，Bitmap 用 bit 表达最自然，空间小、读写快。

#### 结合 NoteWeave 怎么答

`UploadBitmapService` 的 key 是 `upload:{uploadId}`，通过 `getBit/setBit` 标记 chunkIndex，上传完成后或取消/清理时删除 key，并设置 TTL。

#### 技术原理 / 链路设计讲解

Bitmap 适合记录大量布尔状态。相比用 set 存 chunkIndex，Bitmap 在 chunk 数较多时更紧凑。TTL 避免上传会话异常中断后 Redis key 永久存在。

#### 实现兜底锚点

- `UploadBitmapService.markChunkUploaded`
- `UploadBitmapService.getUploadedChunks`
- `UploadBitmapService.clear`
- `Phase2UploadFlowIntegrationTest`

#### 可直接复述的面试回答

上传链路用 Redis Bitmap，是因为分片上传的状态非常适合用 bit 表示：第 n 个 chunk 是否已经上传。NoteWeave 里 key 类似 `upload:{uploadId}`，每个 chunkIndex 对应一个 bit。上传 chunk 成功后设置 bit，查询上传状态时扫这些 bit，merge 前再结合 MySQL 和 MinIO 做完整性校验。Bitmap 的好处是空间占用低、读写快，也天然支持断点续传。这里 Redis 只是进度加速层，不是唯一事实源，所以 cancel、merge 或过期清理时会清掉 bitmap。

#### 常见追问

- 为什么不用 Redis Set？
- totalChunks 很大时扫 bit 会不会慢？
- Redis bitmap 和 MySQL upload_chunk 如何配合？

#### 常见坑

- 不要说只看 Redis bit 就能安全 merge。
- 不要忽略 TTL 和异常清理。

### Q3：WebSocket Runtime 为什么用 Redis？

#### 面试官为什么问

这是实时状态管理题。面试官会追断线恢复、stop、resume、partial content。

#### 回答思路

讲 WebSocket Runtime 是执行态，不是最终消息；执行态短生命周期、高频变化，适合 Redis。

#### 结合 NoteWeave 怎么答

`ChatRuntimeStateStore` 保存 `chat:{sessionId}:runtime`、`short_term`、`stream`、`events`，TTL 是 2 小时。事件 append 时会分配 seq，ack 时记录 lastAckSeq，resume 时读取 seq 之后的事件。

#### 技术原理 / 链路设计讲解

流式事件和 partial content 更新频繁，如果每个 delta 都落 MySQL，会放大写压力，也会把临时状态混入正式消息。Redis 可做短期 event buffer 和恢复窗口。

#### 实现兜底锚点

- `ChatRuntimeStateStore.appendEvent`
- `ChatRuntimeStateStore.acknowledge`
- `ChatRuntimeStateStore.readEventsAfter`
- `ChatRuntimeStateStore.readSnapshot`
- `ChatRuntimeService`

#### 可直接复述的面试回答

WebSocket Runtime 用 Redis，是因为它承载的是会话执行态，而不是最终业务事实。比如 token 流式 delta、partial content、当前 streamId、lastSeq、lastAckSeq、stop/resume 状态，这些都更新频繁、生命周期短。如果每个 delta 都落 MySQL，会让正式消息表和临时执行态混在一起，也会增加写压力。NoteWeave 把这些状态以 JSON 存到 Redis，并设置 2 小时 TTL；用户断线后可以根据 ackSeq 读取后续事件，恢复 runtime snapshot。最终完成的消息、Citation 和 Memory 仍然落 MySQL。

#### 常见追问

- Redis 过期后还能恢复 partial content 吗？
- 为什么不用前端自己拼？
- stop 如何停止后续 delta？

#### 常见坑

- 不要说 Redis runtime 是永久消息日志。
- 不要说 DRAFT 会写长期 Memory。

### Q4：Redis 常见八股怎么结合 NoteWeave 回答？

#### 面试官为什么问

面试官可能从项目里的 Redis 使用切到基础知识。

#### 回答思路

把数据结构、TTL、持久化、缓存问题都落到项目场景。

#### 结合 NoteWeave 怎么答

- String：runtime state JSON、health check echo。
- Bitmap：上传 chunk 状态。
- TTL：上传状态和 runtime state 的生命周期控制。
- 缓存一致性：当前 Redis 主要不是业务缓存，关键一致性由 MySQL/MinIO/ES/Kafka 链路保证。
- 分布式锁：当前主链路没有把 Redis 锁作为核心能力，任务 claim 主要靠 MySQL 行锁和状态机。

#### 可直接复述的面试回答

Redis 八股我会结合 NoteWeave 讲。Bitmap 用在上传分片状态，适合记录 chunk 是否已上传；String 用在 WebSocket runtime state，保存 JSON 状态和事件缓冲；TTL 用来控制短期状态生命周期，避免异常断线或上传中断后 key 长期堆积。缓存一致性方面，NoteWeave 当前没有把 Redis 当主业务缓存，所以不会出现大量业务缓存失效问题；真正的业务事实在 MySQL，对象在 MinIO，索引在 ES。分布式锁方面，当前任务 claim 主要靠 MySQL 行锁和 Task 状态机，而不是 Redis 锁。

#### 常见追问

- Redis 持久化 RDB/AOF 怎么理解？
- 缓存穿透、击穿、雪崩项目里有没有？
- Redis 单线程为什么快？

#### 常见坑

- 不要把没有落地的本地缓存 + Redis 二级缓存说成当前能力。
- 不要为了答八股硬说用了 Redis 分布式锁。

### Q5：Redis 挂了怎么办？

#### 面试官为什么问

这是故障边界题。面试官想看你是否知道哪些数据会丢，哪些不会。

#### 回答思路

区分短期状态和长期事实。Redis 故障影响上传进度体验和 WebSocket 恢复窗口，但不应该破坏正式业务数据。

#### 结合 NoteWeave 怎么答

上传 merge 会再查 MySQL/MinIO；正式消息、Task、Citation、Artifact、Memory 在 MySQL；Redis 丢失后未完成 partial content 可能无法恢复，只能降级读已落库消息或重新发起。

#### 可直接复述的面试回答

Redis 挂了不会让 NoteWeave 的正式业务事实丢失，因为正式数据不在 Redis。受影响的是短期体验，比如上传状态查询可能不准确，需要回退到 MySQL/MinIO 校验；WebSocket 的未完成 partial content、事件缓冲和 resume 窗口可能丢失，只能降级读取 MySQL 已完成消息，或者让用户重新发起。这个边界本身就是设计取舍：Redis 用来提升短期运行态体验，不承担不可丢的业务事实。

#### 常见追问

- 是否需要 Redis 高可用？
- 是否需要把事件流持久化？
- 如何监控 Redis？

#### 常见坑

- 不要说 Redis 挂了完全无影响。
- 不要说 Redis 丢了会导致正式消息丢失。
