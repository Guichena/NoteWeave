# 阶段 6 ConversationEventMux Replay/终态竞态修复验收

## 1. 问题

Backend 全量回归曾出现：

```text
ConversationEventMuxTest.shouldAggregateMultipleRunsUnderOneConversationCursor
expected: [1, 2, 3]
actual:   [3]
```

竞态顺序为：

1. follower 在 channel 锁内截取 replay 1/2 并注册 subscriber；
2. follower 尚未把 replay enqueue 到 subscriber；
3. publisher 发布 terminal 3；
4. 同步 dispatch 先消费 terminal 并关闭 subscriber；
5. follower 再 enqueue replay 1/2 时因 subscriber 已关闭而丢弃。

这不是测试等待不足，而是 live terminal 可以越过已截取 replay 的真实顺序漏洞。

## 2. 修复

`ConversationEventMux.follow` 现在与 `SessionEventMux` 使用相同模式：

1. 在 channel 锁内计算 replay；
2. 注册 subscriber 并增加 active count；
3. 在仍持有 channel 锁时 `prime(replay)`，只写 subscriber pending queue，不启动 dispatch；
4. 释放 channel 锁后 `startDrain()`；
5. 后续 live event 只能追加在 primed replay 之后。

Subscriber 的 queue admission 被收敛到共享 `appendPendingLocked`，replay 和 live event 使用相同容量、delta coalescing 和 overflow 规则。`scheduleDrain` 统一处理 executor rejection。

## 3. 验证

- `ConversationEventMuxTest` 独立连续执行 5 轮：5/5；
- 每轮内部 2 个测试均通过；
- Backend 全量：281/281；
- Failures/Errors/Skipped：0/0/0。

修复未改变 conversation cursor、run cursor、Redis bridge pump、terminal 类型或 subscriber queue 容量，仅保证 replay-before-live 的顺序不变量。
