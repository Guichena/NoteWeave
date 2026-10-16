# Artifact Worker 转写任务

阶段 5D 将音视频转写接入固定工作线程和有界接纳队列。默认同时执行 1 个任务、最多等待 8 个任务；真实模型质量和性能评测暂缓，自动化测试使用替身。

| 配置 | 默认值 | 范围 |
|---|---|---|
| `NOTEWEAVE_TRANSCRIPTION_POOL_SIZE` | 1 | 1 至 8 |
| `NOTEWEAVE_TRANSCRIPTION_QUEUE_CAPACITY` | 8 | 1 至 64 |

总接纳容量为执行槽与等待槽之和。容量在创建目录和写入媒体前预留；不同任务不会创建新的转写线程。缓冲区包含尚未被工作线程取走的执行预留槽，实际转写并发由独立信号量限制到线程池大小。

`POST /internal/source-transcriptions` 使用二进制请求体，最大 128 MiB。容量暂满或停机时返回 429、`Retry-After: 5`，原因码为 `SOURCE_TRANSCRIPTION_CAPACITY_EXHAUSTED`。Java 应保留 `TRANSCRIBING` 并按退避重试。接纳成功仍返回 202 和 `accepted / snapshot_id / duplicate`。

接纳参数增加可选 `task_id`，生产请求应携带当前任务 ID。工作台、资料、快照、任务、文件名、归一后的 MIME、实际 SHA-256、文件大小与转写模型配置冻结保存。重复的活动或排队请求必须完全匹配，才返回重复接纳；身份或内容变化会被拒绝。同一快照的不同任务在旧活动结束前返回 429。

旧无任务 ID 的目录沿用 `inputs/source-transcriptions/{snapshot_id}`；有任务 ID 时使用 `inputs/source-transcriptions/{snapshot_id}--{task_id}`。新任务不会覆盖旧任务媒体或检查点，回调也保留冻结 `task_id`。恢复时核对目录归属、任务身份、媒体名称、大小及真实摘要，拒绝路径逃逸和符号链接指向的外部媒体。

完成或失败的回调内容先原子保存到 `callback-pending.json`。网络失败、服务错误或没有明确确认时保留媒体、转写结果和回调检查点；重启后只重新发送该回调，不再执行转写或模型校对。Java 返回 `ApiResponse.data` 后，只有匹配快照的布尔 `accepted=true` 或 `discarded=true` 才确认清理。`discarded` 表示旧任务已终止，不表示新资料转写成功。

恢复只把有限任务放入队列，工作线程在处理结束或空闲时持续补入剩余磁盘任务。回调未确认的任务保留恢复时间，避免立即重复请求。关闭服务时禁止新接纳，尚未开始的队列任务保留原件和检查点；实际执行中的任务完成后才释放对应槽位。重启不能与尚未结束的旧线程并行执行同一快照。`run_async=False` 仍可用于同步调用和测试，其执行在注册锁外进行并共享并发限制。

本阶段没有运行真实 faster-whisper、真实校对模型或音视频质量评测。转写、容量和回调回归使用临时文件及替身验证。
