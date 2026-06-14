# WebSocket、Memory、会话恢复加深版

> 本文件为 2026-06-01 重构版，依据当前代码、测试和 Flyway 迁移整理。不要再按旧阶段计划或旧题库口径背。

## 0. 通用问题如何转成项目深答
先把通用八股问题落到 NoteWeave 的真实模块，再回答场景、方案、收益、权衡、故障和指标。下面是本主题的项目化深答。

## 1. 本篇定位
真实工作台需要流式输出、停止、恢复、草稿和长期记忆边界，不能只靠一次 HTTP 返回。

## 2. 面试先说版
WebSocket runtime 这块我会讲成用户体验和状态一致性的结合。HTTP 问答可以完成基本 RAG，但工作台需要流式 token、停止、刷新恢复和临时草稿。所以系统先通过 POST /api/v1/chat/ws-ticket 发一次性 ticket，握手后建立 /ws/chat/{ticket}。后端发统一事件 envelope，包括 connected、started、delta、completed、stopped、failed、restored。运行中的 partialContent、event seq、stop 标记和短期状态放 Redis，正式消息和最终结果落 MySQL。DRAFT 和 FORMAL 分开是关键：DRAFT 适合临时探索，不写长期 Memory；FORMAL 才会经过 MemoryWritebackStrategy，在过滤短问候、敏感内容和低价值输入后写 session summary、space memory 或 user memory。

## 3. 当前真实口径
NoteWeave 用 WebSocket runtime 处理流式交互，用 DRAFT/FORMAL 区分临时探索和正式沉淀，用 MemoryWritebackStrategy 控制长期记忆。

### 已实现
- WebSocketTicketController 提供 `/api/v1/chat/ws-ticket`。
- WebSocketConfig 和 WebSocketAuthHandshakeInterceptor 处理 WebSocket 注册与握手安全。
- ChatRuntimeService、ChatRuntimeStateStore、ActiveExecutionRegistry 实现 runtime、stop、resume。
- MemoryController、MemoryWritebackService、MemoryWritebackStrategy、ContextReadRouter 实现长期记忆和上下文读取。

### 设计目标
- ws-ticket 一次性消费，WebSocket 发送 chat.connected/chat.started/chat.delta/chat.completed/chat.stopped/chat.failed/chat.restored，Redis 保存 runtime/short-term/event state，Formal 会话写 MySQL 消息并触发受控 memory writeback。
- 用户刷新、停止和恢复有状态可依，同时 DRAFT 不污染长期记忆。

### 后续可扩展
- Redis runtime state 丢失后用户还能看到什么？
- 哪些内容不能写入 Memory？
- ContextReadRouter 为什么要分层读，而不是全量历史塞 prompt？

## 4. 代码和测试锚点
- src/main/java/com/noteweave/chat/runtime/service/ChatRuntimeService.java
- src/main/java/com/noteweave/chat/runtime/service/ChatRuntimeStateStore.java
- src/main/java/com/noteweave/chat/runtime/service/ActiveExecutionRegistry.java
- src/main/java/com/noteweave/memory/service/MemoryWritebackStrategy.java
- src/test/java/com/noteweave/chat/Phase5WorkspaceChatRuntimeIntegrationTest.java
- src/test/java/com/noteweave/memory/Phase12LongTermMemoryIntegrationTest.java

## 5. 必会问题与答题骨架

### Q1: HTTP 能用，为什么还要 WebSocket？

回答时按四步走：
1. 先说场景：真实工作台需要流式输出、停止、恢复、草稿和长期记忆边界，不能只靠一次 HTTP 返回。
2. 再说方案：ws-ticket 一次性消费，WebSocket 发送 chat.connected/chat.started/chat.delta/chat.completed/chat.stopped/chat.failed/chat.restored，Redis 保存 runtime/short-term/event state，Formal 会话写 MySQL 消息并触发受控 memory writeback。
3. 再说收益：用户刷新、停止和恢复有状态可依，同时 DRAFT 不污染长期记忆。
4. 最后落到真实代码锚点，不要停在概念。

可直接复述：

> WebSocket runtime 这块我会讲成用户体验和状态一致性的结合。HTTP 问答可以完成基本 RAG，但工作台需要流式 token、停止、刷新恢复和临时草稿。所以系统先通过 POST /api/v1/chat/ws-ticket 发一次性 ticket，握手后建立 /ws/chat/{ticket}。后端发统一事件 envelope，包括 connected、started、delta、completed、stopped、failed、restored。运行中的 partialContent、event seq、stop 标记和短期状态放 Redis，正式消息和最终结果落 MySQL。DRAFT 和 FORMAL 分开是关键：DRAFT 适合临时探索，不写长期 Memory；FORMAL 才会经过 MemoryWritebackStrategy，在过滤短问候、敏感内容和低价值输入后写 session summary、space memory 或 user memory。

常见追问：
- Redis runtime state 丢失后用户还能看到什么？
- 哪些内容不能写入 Memory？
- ContextReadRouter 为什么要分层读，而不是全量历史塞 prompt？

### Q2: DRAFT 和 FORMAL 为什么要区分？

回答时按四步走：
1. 先说场景：真实工作台需要流式输出、停止、恢复、草稿和长期记忆边界，不能只靠一次 HTTP 返回。
2. 再说方案：ws-ticket 一次性消费，WebSocket 发送 chat.connected/chat.started/chat.delta/chat.completed/chat.stopped/chat.failed/chat.restored，Redis 保存 runtime/short-term/event state，Formal 会话写 MySQL 消息并触发受控 memory writeback。
3. 再说收益：用户刷新、停止和恢复有状态可依，同时 DRAFT 不污染长期记忆。
4. 最后落到真实代码锚点，不要停在概念。

可直接复述：

> WebSocket runtime 这块我会讲成用户体验和状态一致性的结合。HTTP 问答可以完成基本 RAG，但工作台需要流式 token、停止、刷新恢复和临时草稿。所以系统先通过 POST /api/v1/chat/ws-ticket 发一次性 ticket，握手后建立 /ws/chat/{ticket}。后端发统一事件 envelope，包括 connected、started、delta、completed、stopped、failed、restored。运行中的 partialContent、event seq、stop 标记和短期状态放 Redis，正式消息和最终结果落 MySQL。DRAFT 和 FORMAL 分开是关键：DRAFT 适合临时探索，不写长期 Memory；FORMAL 才会经过 MemoryWritebackStrategy，在过滤短问候、敏感内容和低价值输入后写 session summary、space memory 或 user memory。

常见追问：
- Redis runtime state 丢失后用户还能看到什么？
- 哪些内容不能写入 Memory？
- ContextReadRouter 为什么要分层读，而不是全量历史塞 prompt？

### Q3: runtime state 为什么放 Redis？

回答时按四步走：
1. 先说场景：真实工作台需要流式输出、停止、恢复、草稿和长期记忆边界，不能只靠一次 HTTP 返回。
2. 再说方案：ws-ticket 一次性消费，WebSocket 发送 chat.connected/chat.started/chat.delta/chat.completed/chat.stopped/chat.failed/chat.restored，Redis 保存 runtime/short-term/event state，Formal 会话写 MySQL 消息并触发受控 memory writeback。
3. 再说收益：用户刷新、停止和恢复有状态可依，同时 DRAFT 不污染长期记忆。
4. 最后落到真实代码锚点，不要停在概念。

可直接复述：

> WebSocket runtime 这块我会讲成用户体验和状态一致性的结合。HTTP 问答可以完成基本 RAG，但工作台需要流式 token、停止、刷新恢复和临时草稿。所以系统先通过 POST /api/v1/chat/ws-ticket 发一次性 ticket，握手后建立 /ws/chat/{ticket}。后端发统一事件 envelope，包括 connected、started、delta、completed、stopped、failed、restored。运行中的 partialContent、event seq、stop 标记和短期状态放 Redis，正式消息和最终结果落 MySQL。DRAFT 和 FORMAL 分开是关键：DRAFT 适合临时探索，不写长期 Memory；FORMAL 才会经过 MemoryWritebackStrategy，在过滤短问候、敏感内容和低价值输入后写 session summary、space memory 或 user memory。

常见追问：
- Redis runtime state 丢失后用户还能看到什么？
- 哪些内容不能写入 Memory？
- ContextReadRouter 为什么要分层读，而不是全量历史塞 prompt？

### Q4: stop/resume 怎么保证不重复、不丢状态？

回答时按四步走：
1. 先说场景：真实工作台需要流式输出、停止、恢复、草稿和长期记忆边界，不能只靠一次 HTTP 返回。
2. 再说方案：ws-ticket 一次性消费，WebSocket 发送 chat.connected/chat.started/chat.delta/chat.completed/chat.stopped/chat.failed/chat.restored，Redis 保存 runtime/short-term/event state，Formal 会话写 MySQL 消息并触发受控 memory writeback。
3. 再说收益：用户刷新、停止和恢复有状态可依，同时 DRAFT 不污染长期记忆。
4. 最后落到真实代码锚点，不要停在概念。

可直接复述：

> WebSocket runtime 这块我会讲成用户体验和状态一致性的结合。HTTP 问答可以完成基本 RAG，但工作台需要流式 token、停止、刷新恢复和临时草稿。所以系统先通过 POST /api/v1/chat/ws-ticket 发一次性 ticket，握手后建立 /ws/chat/{ticket}。后端发统一事件 envelope，包括 connected、started、delta、completed、stopped、failed、restored。运行中的 partialContent、event seq、stop 标记和短期状态放 Redis，正式消息和最终结果落 MySQL。DRAFT 和 FORMAL 分开是关键：DRAFT 适合临时探索，不写长期 Memory；FORMAL 才会经过 MemoryWritebackStrategy，在过滤短问候、敏感内容和低价值输入后写 session summary、space memory 或 user memory。

常见追问：
- Redis runtime state 丢失后用户还能看到什么？
- 哪些内容不能写入 Memory？
- ContextReadRouter 为什么要分层读，而不是全量历史塞 prompt？

### Q5: 长期记忆为什么要分 session summary、space memory、user memory？

回答时按四步走：
1. 先说场景：真实工作台需要流式输出、停止、恢复、草稿和长期记忆边界，不能只靠一次 HTTP 返回。
2. 再说方案：ws-ticket 一次性消费，WebSocket 发送 chat.connected/chat.started/chat.delta/chat.completed/chat.stopped/chat.failed/chat.restored，Redis 保存 runtime/short-term/event state，Formal 会话写 MySQL 消息并触发受控 memory writeback。
3. 再说收益：用户刷新、停止和恢复有状态可依，同时 DRAFT 不污染长期记忆。
4. 最后落到真实代码锚点，不要停在概念。

可直接复述：

> WebSocket runtime 这块我会讲成用户体验和状态一致性的结合。HTTP 问答可以完成基本 RAG，但工作台需要流式 token、停止、刷新恢复和临时草稿。所以系统先通过 POST /api/v1/chat/ws-ticket 发一次性 ticket，握手后建立 /ws/chat/{ticket}。后端发统一事件 envelope，包括 connected、started、delta、completed、stopped、failed、restored。运行中的 partialContent、event seq、stop 标记和短期状态放 Redis，正式消息和最终结果落 MySQL。DRAFT 和 FORMAL 分开是关键：DRAFT 适合临时探索，不写长期 Memory；FORMAL 才会经过 MemoryWritebackStrategy，在过滤短问候、敏感内容和低价值输入后写 session summary、space memory 或 user memory。

常见追问：
- Redis runtime state 丢失后用户还能看到什么？
- 哪些内容不能写入 Memory？
- ContextReadRouter 为什么要分层读，而不是全量历史塞 prompt？

## 6. 大厂深挖追问路径
1. 先问你做了什么。
2. 再问为什么这样设计，不用更简单方案。
3. 再问失败、重试、越权、删除、断线、重建索引时会发生什么。
4. 最后问如何量化效果和下一步演进。

把答案往下压一层：
- 业务层：真实工作台需要流式输出、停止、恢复、草稿和长期记忆边界，不能只靠一次 HTTP 返回。
- 架构层：ws-ticket 一次性消费，WebSocket 发送 chat.connected/chat.started/chat.delta/chat.completed/chat.stopped/chat.failed/chat.restored，Redis 保存 runtime/short-term/event state，Formal 会话写 MySQL 消息并触发受控 memory writeback。
- 数据层：引用 MySQL、Redis、MinIO、ES、Kafka 或 Citation/Trace 的真实职责。
- 测试层：能说出对应 IntegrationTest 或 ServiceTest。
- 边界层：明确哪些是后续扩展，不冒充已落地。

## 7. 不能说满的地方
- 不要把 Redis 说成主业务事实源。
- 不要说 DRAFT 会自动写长期记忆。
- 不要说 stop 一定能终止外部 LLM 已经产生的所有 token，只能保证后端不继续推送并保存可控状态。

## 8. 零基础记忆法
记住一句话：先讲“为什么需要这个模块”，再讲“请求从哪里来、状态落在哪里、失败怎么恢复、证据怎么追踪、权限怎么兜底”。按这个顺序答，大多数追问都能接住。
