# 文件：04_Redis_缓存_运行态_数据结构加深版.md

## 1. 这个主题要回答什么

这个主题覆盖 Redis 和缓存相关高频问题：

- Redis 在项目里怎么用？
- 为什么用 Redis？
- 用了哪些数据结构？
- Redis 和 MySQL 怎么分工？
- 缓存击穿、穿透、雪崩是什么？
- 有没有本地缓存 + Redis 二级缓存？
- Redis 挂了怎么办？

## 2. 面向初学者：Redis 是什么

Redis 是内存型 key-value 数据库。它把数据主要放在内存里，所以读写很快。

常见用途：

- 缓存热点数据。
- 分布式锁。
- 计数器。
- 限流。
- 延迟队列。
- 会话状态。
- bitmap 状态记录。
- pub/sub 或 stream。

但 Redis 不是万能的：

- 内存比磁盘贵。
- 数据可能过期或被淘汰。
- 不能替代所有关系型查询。
- 不适合作为复杂业务事实源。

## 3. NoteWeave 里 Redis 的职责

NoteWeave 里 Redis 的定位是：

```text
短期状态 + 高速运行态
```

主要用于三类场景：

### 3.1 上传分片进度

分片上传时，Redis 记录哪些 chunk 已上传。

业务链路：

```text
uploadChunk
-> 分片写 MinIO
-> UploadChunk 写 MySQL
-> Redis bitmap 标记 chunkIndex
-> status 接口读取 bitmap 返回进度
```

注意：Redis bitmap 不是唯一事实。merge 时仍要校验：

- UploadChunk 表记录数量。
- chunkIndex 连续。
- MinIO object 存在。
- bitmap 标记完整。

### 3.2 WebSocket ticket

WebSocket 连接前先申请 ticket：

```text
HTTP 获取 ticket
-> Redis 写 ticket:userId，TTL 很短
-> WebSocket 连接时消费 ticket
```

原因：

- WebSocket URL 不长期暴露 JWT。
- ticket 一次性消费，降低重放风险。
- TTL 过期后自动失效。

### 3.3 Chat runtime state

WebSocket 流式输出中，Redis 保存：

- RuntimeState：RUNNING、STOPPED、FAILED、IDLE。
- StreamState：streamId、partialContent。
- ShortTermContext：短期上下文。
- event buffer：用于 ack/resume。

这些状态生命周期短，读写频繁，适合 Redis。

## 4. 八股知识点：Redis 常见数据结构

### 4.1 String

最基础结构，value 可以是字符串、数字、JSON。

常见用途：

- token。
- ticket。
- 配置缓存。
- 计数器。

内部原理：

Redis String 底层使用 SDS，Simple Dynamic String。SDS 比 C 字符串多保存长度和容量，能 O(1) 获取长度，也避免缓冲区溢出。

NoteWeave 里 ticket、runtime JSON 都可以用 String 形式保存。

### 4.2 Hash

Hash 是 field-value 结构。

常见用途：

- 用户信息缓存。
- 对象属性缓存。
- runtime 状态字段。

内部结构：

数据量小时可能用 listpack，数据量大时转 hashtable。

### 4.3 List

双向链表或 quicklist，用于队列。

NoteWeave 没把它作为主任务队列，因为主任务队列用 Kafka。

### 4.4 Set

无序集合，适合去重。

用途：

- 在线用户集合。
- 标签集合。

### 4.5 Sorted Set

带 score 的有序集合。

用途：

- 排行榜。
- 延迟任务。
- 按时间排序事件。

### 4.6 Bitmap

Bitmap 本质上是 String 的位操作。每个 bit 表示一个状态。

NoteWeave 上传进度很适合 bitmap：

```text
bit 0 = chunk0 是否上传
bit 1 = chunk1 是否上传
bit 2 = chunk2 是否上传
```

优点：

- 空间非常省。
- 查询某个 chunk 是否上传很快。
- 统计已上传数量也方便。

缺点：

- 只适合表示布尔状态。
- 不能保存 chunk 详细信息。
- Redis 丢失后需要 DB/MinIO 兜底。

### 4.7 Stream

Redis Stream 是消息流结构，有 consumer group。它可以做轻量消息队列。

但 NoteWeave 没有把 Redis Stream 用作主任务队列，原因是项目已经统一 Kafka，Redis 只做 runtime。

## 5. 八股知识点：缓存穿透、击穿、雪崩

### 5.1 缓存穿透

查询一个不存在的数据，缓存没有，DB 也没有。大量这种请求会打到 DB。

解决：

- 缓存空值。
- 布隆过滤器。
- 参数校验。

NoteWeave 当前不是典型缓存系统，但如果未来缓存 PromptVersion 或 Methodology，可以考虑空值缓存。

### 5.2 缓存击穿

某个热点 key 过期，大量请求同时打到 DB。

解决：

- 互斥锁。
- 热点 key 不过期或逻辑过期。
- 提前刷新。

### 5.3 缓存雪崩

大量 key 同时过期，或 Redis 整体不可用。

解决：

- 过期时间加随机抖动。
- 多级缓存。
- 限流降级。
- Redis 高可用。

NoteWeave 的 runtime key 通常有 TTL，如果大量 session 同时恢复，可能给 Redis 带来压力；但它不应该影响 MySQL 正式数据。

## 6. Redis 和 MySQL 的边界

### 6.1 Redis 存什么

- 短期状态。
- 临时票据。
- 上传进度。
- 运行态快照。
- partial content。
- ack 后事件。

### 6.2 MySQL 存什么

- 用户。
- Space。
- Task。
- Document。
- ChatMessage。
- Citation。
- Artifact。
- Memory。
- Trace。
- Admin audit。

判断标准：

```text
丢了会不会破坏业务事实？
需要不需要审计？
需要不需要复杂查询？
需要不需要权限关系？
```

如果答案是“需要”，就不应该只放 Redis。

## 7. 为什么没有本地缓存主链路

当前项目没有明确实现本地缓存 + Redis 二级缓存。不要硬说有。

如果未来要加，本地缓存适合：

- PromptVersion。
- Methodology preset。
- 静态配置。
- 健康检查结果短暂缓存。

但要处理：

- TTL。
- 版本号。
- 主动失效。
- 多实例一致性。
- 权限变更。

## 8. 可直接复述的深答

Redis 在 NoteWeave 里主要承担短期状态，而不是长期业务事实。上传分片时，Redis 用 bitmap 记录某个 uploadId 下哪些 chunk 已上传，状态查询很快，但 merge 时仍然要检查 UploadChunk 表和 MinIO object，因为 Redis 不是唯一事实源。WebSocket 链路里，Redis 保存一次性 ticket、runtime state、stream state、partialContent 和事件缓冲，用来支持 stop、resume 和断线恢复。这些数据生命周期短、读写频繁、有 TTL，适合 Redis。长期业务状态，比如 message、citation、task、memory、artifact 都在 MySQL。主异步任务也走 Kafka，不走 Redis Stream。当前没有生产 Redis QPS/P99，我不会编造；如果要验证，会看 Redis latency、slowlog、内存、key 数量，以及 WebSocket 和上传并发压测结果。

## 9. 面试官可能追问

### 9.1 Redis 挂了怎么办

影响 WebSocket ticket、resume、partialContent 和上传进度体验，但不会丢正式 message、citation、task 和 artifact。

### 9.2 为什么不用 Redis 做主队列

因为项目已经用 Kafka 统一长任务，Redis 只做短期状态。混用会让任务模型分裂。

### 9.3 Redis bitmap 为什么适合上传

因为上传分片是典型布尔状态：某个 chunk 上传了还是没上传。bitmap 空间省、查询快。

### 9.4 缓存一致性怎么处理

当前主链路不是缓存型系统。未来缓存配置时可用 TTL + version + 主动失效。权限和 citation 这类强一致资源不适合只靠缓存。

