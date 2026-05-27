# 文件：04_Redis_缓存_运行态边界.md

## 0. 本篇定位

这篇负责 Redis、缓存和运行态边界的主题深答。它适合回答 Redis 在项目里存什么、为什么不做主事实源、运行态和持久化如何分层，以及 Redis 挂了会影响哪些能力。

## 1. 本主题覆盖的通用问题

- 项目里为什么用 Redis？
- Redis 的职责和业务边界是什么？
- 用了哪些数据结构，分别承载什么？
- Redis 和 MySQL 的边界在哪里？
- Redis 和本地缓存怎么配合？
- Redis 实际 QPS、P99 怎么回答？
- Redis 挂了系统会怎样？

## 2. 架构视角怎么切入

Redis 在 NoteWeave 中的定位是短期状态存储，不是业务事实源。这个边界要讲清：

- `MySQL` 存长期业务事实：Task、Document、Message、Citation、Memory、Artifact。
- `Redis` 存短期运行态：上传进度、WebSocket ticket、runtime state、partial content、event buffer。
- `Kafka` 承担主异步任务队列，不用 Redis 替代。

## 3. 完整链路深讲

### 3.1 上传分片进度

```text
uploadChunk
-> chunk object 写 MinIO
-> UploadChunk 写 MySQL
-> Redis bitmap 标记 chunkIndex completed
-> status 接口快速读取 uploaded chunks
-> merge 时仍校验 MySQL + MinIO + bitmap
```

Redis bitmap 用于提升进度查询效率，但不是唯一事实。merge 必须检查 UploadChunk 数量和 MinIO object 是否存在。

### 3.2 WebSocket ticket

```text
POST /chat/ws-ticket
-> Redis 写一次性 ticket，TTL 60s 左右
-> WebSocket /ws/chat/{ticket}
-> 消费 ticket
-> 建立连接
```

ticket 防止长期 token 暴露在 WebSocket URL 中，也降低重放连接风险。

### 3.3 WebSocket runtime

```text
chat.message
-> RuntimeState(RUNNING/STOPPED/FAILED/IDLE)
-> StreamState(partialContent, streamId)
-> ShortTermContext(recent messages, evidence titles)
-> event buffer(seq, ack)
-> chat.resume 重放 ack 后事件
```

Redis 适合这类短生命周期、高频读写、有 TTL 的状态。正式 message 和 citation 仍然落 MySQL。

## 4. 关键实现锚点

- `UploadBitmapService`
- `WebSocketTicketService`
- `ChatRuntimeStateStore`
- `RuntimeState`
- `StreamState`
- `ShortTermContext`
- `RuntimeSnapshot`
- `ActiveExecutionRegistry`

## 5. 为什么这么设计

### 5.1 为什么 Redis 不做长期记忆

长期记忆需要查询、编辑、删除、审计、TTL、pin、权限隔离。MySQL 更适合做长期事实源。Redis 丢失或过期不能影响长期记忆。

### 5.2 为什么 Redis 不做主任务队列

项目已经用 Kafka 统一长任务。如果上传解析走 Kafka、Chat runtime 走 Redis、Artifact 生成又走内存队列，任务模型会分裂。Redis 只承担 runtime，Kafka 承担 worker 任务。

### 5.3 为什么没有本地缓存主链路

当前项目没有把本地缓存作为核心能力。因为热点配置和高频读压力还没有生产证据。未来可以缓存 PromptVersion、Methodology preset、权限快照等，但需要：

- TTL。
- 版本号。
- 主动失效。
- 权限变更后的缓存一致性。

## 6. 可直接复述的深答

Redis 在 NoteWeave 中主要承载短期状态，而不是长期业务事实。上传链路里，它用 bitmap 记录某个 uploadId 下哪些分片已上传，方便快速查进度，但 merge 时仍然要检查 MySQL 的 UploadChunk 和 MinIO 对象是否存在。WebSocket 链路里，Redis 保存一次性 ticket、runtime state、stream state、partialContent 和事件缓冲，用来支持 stop、resume 和断线恢复。长期 message、citation、memory、task 状态都在 MySQL，主异步任务走 Kafka。这样 Redis 挂了最多影响上传进度体验和 WebSocket 恢复，不会破坏已持久化的正式业务数据。当前没有生产 Redis QPS/P99，我会用 runtime 压测、上传并发压测、Redis latency、slowlog 和 memory usage 去评估。

## 7. 追问兜底

### 如果问“Redis 挂了怎么办”

WebSocket ticket 和 resume 会受影响，上传进度查询可能降级，但已落库的 message、citation、task、document 不应丢失。恢复后可以重新建立 runtime。

### 如果问“为什么不用本地缓存”

当前没有足够热点读压力证据。引入本地缓存会增加失效和一致性复杂度，后续可用于配置型数据，而不是权限和证据主链路。

### 如果问“Redis QPS/P99”

回答没有生产数字，不编造。说明会如何压测和观测。

