---
title: 异步任务与 Redis 协调
tags: [Redis, asynq, pubsub, cron, 幂等]
aliases: [Redis协调, 异步编排]
---

# 异步任务与 Redis 协调

## 总体思路

Redis 在 WeKnora 里不是单纯缓存，而是协调层：它负责任务去重、跨实例通知、审批回调和部分限流/状态判断。真正的重任务则交给 asynq 或等价任务执行器去做。

## 关键实现

- `Scheduler.Start()` / `Stop()` / `AddOrUpdate()` / `Remove()` / `triggerSync()`：数据源周期同步
- `Gate.NewGate()` / `runSubscriber()` / `deliverLocal()` / `resolveCrossInstance()`：MCP 审批跨实例协调
- `container.go` 中的 Redis 探测和 `TaskEnqueuer` 注入：决定用 asynq 还是同步执行
- `knowledgebase.go` 里的 `DeleteKnowledgeBase()` / `ProcessKBDelete()`：知识库删除异步化

## 为什么这样设计

周期同步和人工审批这两类动作都天然跨越请求边界。前者需要防止多实例重复触发，后者需要把“一个实例发起、另一个实例响应”的事件串起来。Redis 提供了足够轻量的共享状态和 pubsub 机制，正好适合做这层协调。

### 两层幂等

1. **数据库层幂等**：检查上一轮是否还在跑。
2. **Redis 层幂等**：用确定性的 taskID 或 pubsub 去重。

这两个层次叠在一起，才能把多实例重复执行压住。

## tradeoff

- **Redis pubsub**：轻量，但需要关注连接和丢消息边界。
- **分布式锁**：稳定，但实现和维护更复杂。
- **确定性 taskID**：简单直接，但对时间粒度和命名规则有要求。
- **同步执行**：排障简单，但会压住主请求。

## 常见失败模式

- 只做 DB 检查，不做任务幂等，最后多实例重复 enqueue。
- 只做 taskID，不做运行中检查，长期任务重叠执行。
- 审批消息到错实例，或者被自发消息污染。
- Redis 不可用时没有清晰降级，任务完全不可用。

## 面试追问

### 1. 为什么数据源同步要同时用 DB 检查和 Redis taskID？

因为它们解决的是两个不同问题。DB 检查防重叠，Redis taskID 防多实例重复入队；两个都做，才能把重复同步真正压下去。

### 2. 为什么 MCP 审批要做跨实例 pubsub？

因为审批请求和审批响应可能不在同一台机器上。没有 pubsub，HTTP 语义就会和实际执行位置脱节。

### 3. 为什么 Redis 更像协调层而不是事实层？

因为 Redis 的强项是快和轻，不是长期持久。事实源还是要落在 MySQL 里，否则重启、迁移或故障恢复时会丢状态。

### 4. 为什么要保留同步 fallback？

因为开发环境和 Lite 模式并不总有 Redis。fallback 能保证系统在基础场景下继续工作。

### 5. 为什么异步任务适合重操作？

因为删除知识库、同步外部数据、图片多模态都不是用户要立即等到的结果。异步能把主链路释放出来，把重活交给后台。
