# 阶段 4 Conversation 会话事件流验收

- 验收日期：2026-07-13
- 状态：Conversation 聚合与跨实例转发通过；阶段 4 仍在进行
- 后端回归：126/126 通过
- 主 Docker Smoke：`target/docker-smoke/phase0a-smoke-20260713043215-9213.json`
- 双 Backend Smoke：`target/answer-redis-smoke/answer-redis-peer-20260713043235-1393.json`
- Redis 故障 Smoke：`target/answer-redis-smoke/answer-redis-degradation-20260713043727-1375.json`
- 实时会话基线：`target/conversation-realtime-baseline/conversation-realtime-20260713044639-8098.json`
- 最新主 Docker Smoke：`target/docker-smoke/phase0a-smoke-20260713044728-2518.json`
- 最新前端镜像 Docker Smoke：`target/docker-smoke/phase0a-smoke-20260713132048-3036.json`
- 慢消费者基线：`target/conversation-realtime-baseline/conversation-slow-consumer-20260713132016-8798.json`

## 已验收能力

1. 新增 Workspace scoped 会话事件端点：`GET /api/v2/workspaces/{workspaceId}/conversations/{conversationId}/events`。
2. 首帧为 `conversation.snapshot`，内容来自 MySQL 中最近 20 个 AnswerRun；Redis 不承担业务真相。
3. 同一 Conversation 的多个 AnswerRun 共享会话 cursor，但事件保留 `run_id` 和 `run_sequence`，可按回答归并并校验 run 内顺序。
4. `Last-Event-ID` 与 `after` 支持续传；使用最新 cursor 重连只返回快照，不重复发送已经消费的 completed。
5. Redis 使用 `stream:conversation:{conversationId}` 和 `seq:conversation:{conversationId}`，MAXLEN 近似裁剪至 512，TTL 为 600 秒。
6. Redis 写入失败时当前实例继续使用有界内存 replay；MySQL snapshot 仍可恢复最终内容。
7. 单次 AnswerRun canonical SSE、旧 Chat SSE 均未改变，避免会话聚合改造破坏已有调用方。
8. 主 Docker Smoke 31 项全部通过，其中会话 Stream 实测 3 条事件、TTL 599 秒。
9. 双 Backend 专项 Smoke 中，peer 实例从 Redis 收到 sequence 1/2 的 delta/completed，`noteweave.conversation.events.remote_delivered=2`。
10. 每个会话订阅者使用串行有界队列和独立分发线程池；慢客户端不会占用 Answer 生成线程，delta 合并、队列溢出和执行器拒绝均有 Counter。
11. Redis 容器真实停止期间，回答仍完成为 `COMPLETED/FINAL`，run 流与 conversation 流均由本机 mux 正常交付。
12. Redis 与 Backend 依次重启后，最终答案和 Conversation snapshot 仅依靠 MySQL 恢复，过期 live terminal 未被错误重放。
13. Redis 空读/失败增加 250ms 退避，复验中 conversation Redis error 从 169 次降为 3 次，消除故障时忙轮询。
14. 活动 Conversation SSE 存在时，POST message 自动异步启动 AnswerRun；旧客户端仍可由 canonical SSE 启动，迁移期间没有双写或破坏性切换。
15. 5 次连续回答共用一条会话流，运行期请求由 10 次降为 6 次（40%）；POST 到 completed P50/P95 为 212.91/225.43ms。
16. `Last-Event-ID` 重连 snapshot 为 89.59ms，未重复投递已消费 terminal；LLM disabled，未伪造模型延迟。
17. 前端正式接入持久 `ConversationStreamClient`，通过 run id reducer 更新对应助手消息；断线自动携带会话 cursor 重连。
18. 前端完整回归增加到 45/45，生产构建和 Docker 前端镜像构建通过。
19. 1KB/s 慢消费者下实际注入 601 条事件，合并 104 次 delta、无 subscriber overflow，健康 API P95 为 24.75ms。

## 尚未完成

- `ChatService` 仍同时承担回答生成、状态推进和兼容流协议，需拆为 Answer 应用编排与兼容 facade；
- Execution 统一模型完成后，把后台执行摘要纳入同一 Conversation stream。

本记录只确认 Conversation 实时聚合切片完成，不代表阶段 4 或主项目重构整体完成。
