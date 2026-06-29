---
title: Redis / MQ / MySQL 任务编排
tags: [Redis, MQ, MySQL, 异步任务, 队列, 持久化]
aliases: [任务队列, 中间件依赖, 异步编排]
---

# Redis / MQ / MySQL 任务编排

## 总体思路

WeKnora 里很多关键能力都不是“一个请求返回一个结果”这么简单，而是跨请求、跨实例、跨时间的状态机。数据源同步、知识库删除、图片多模态、MCP 审批、Wiki 修复、任务重试，这些都需要 Redis、队列和 MySQL 一起工作。

这类模块最重要的不是“跑得快”，而是“跑得稳、能重复、能追踪、能补偿”。

## 子文档

| 文档 | 说明 |
|---|---|
| [异步任务与 Redis 协调](01_异步任务与Redis协调.md) | cron、asynq、pubsub、跨实例协调和幂等 |
| [MySQL 持久化与工作表](02_MySQL持久化与工作表.md) | 任务表、死信表、Wiki 表和乐观锁 |

## 关键实现

- `internal/datasource/scheduler.go`：数据源同步调度器
- `internal/application/repository/task_queue.go`：待处理任务表和死信表
- `internal/application/service/knowledgebase.go`：知识库删除异步化
- `internal/application/service/image_multimodal.go`：图片多模态任务消费
- `internal/agent/approval/gate.go`：跨实例 MCP 审批协调
- `internal/application/repository/wiki_page.go`：Wiki 页面持久化和乐观锁
- `internal/container/container.go`：按环境决定 Redis / asynq / sync fallback

## 设计原则

1. **Redis 管协调**：做分布式锁、pubsub、队列和幂等辅助。
2. **MySQL 管事实**：做任务状态、页面、日志和审计的系统记录。
3. **MQ 管延迟执行**：重任务和可重试任务不要阻塞主请求。
4. **任务必须可补偿**：失败可以重试，重试失败可以落死信。

## tradeoff

- **纯同步处理**：最简单，但会拖慢主链路。
- **Redis 协调 + MQ 执行**：更稳，但状态更复杂。
- **DB 工作表**：易于审计和回放，但吞吐不如专门 MQ。
- **内存队列**：调试方便，但跨实例能力弱。

## 常见失败模式

- 任务只写 Redis，不落 MySQL，最后无法追责和回放。
- 只做重试，不做死信，失败任务永远在队列里打转。
- 没有幂等键，多实例下同一个任务被执行多次。
- 把 Redis 当事实源，服务重启后状态丢失。

## 面试追问

### 1. 为什么这类模块不能只靠数据库？

因为数据库适合存事实，不适合承担高频协调和实时 fan-out。Redis / MQ 能把“临时协调”和“长期事实”分开，系统才更稳。

### 2. 为什么任务一定要支持重试和死信？

因为外部依赖会抖，网络会断，模型会超时，存储会不可用。没有重试就太脆，没有死信就没法兜底。

### 3. 为什么多实例部署特别依赖 Redis？

因为多实例下，任务去重、审批回调、同步协调都需要一个共享状态层。Redis 在这里扮演的是协调面，而不是业务事实源。

### 4. 为什么 MySQL 还要保留任务表？

因为很多任务的可见性、分页、搜索、审计和回放都需要落表。只在队列里跑一遍，运维是看不见的。

### 5. 为什么要接受同步 fallback？

因为本地开发、Lite 模式和 Redis 不可用时，系统仍然要能跑。降级不等于偷懒，而是保证最小可用性。
