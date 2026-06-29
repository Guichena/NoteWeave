---
title: MySQL 持久化与工作表
tags: [MySQL, GORM, 任务表, 死信表, Wiki]
aliases: [持久化工作表, 数据事实层]
---

# MySQL 持久化与工作表

## 总体思路

MySQL 是 WeKnora 的事实层。页面、日志、任务、同步状态、死信、问题单、共享关系和多数元数据都要落在 MySQL 里。Redis 可以协调，MQ 可以搬运，但最后真正能被检索、审计和回放的记录，还是要在关系型数据库里。

## 关键实现

- `taskPendingOpsRepository.Enqueue()` / `PeekBatch()` / `DeleteByIDs()` / `IncrFailCount()` / `PendingCount()` / `DeleteByDedupKey()`
- `taskDeadLetterRepository.Insert()` / `ListByScope()` / `ListByTaskType()` / `DeleteByID()`
- `wikiPageRepository.Create()` / `Update()` / `UpdateAutoLinkedContent()` / `UpdateMeta()` / `List()` / `Search()` / `CountOrphans()`
- `wikiLogEntryRepository.AppendBatch()` / `List()` / `DeleteByKB()`
- `knowledgebase.go` 与 `datasource/scheduler.go` 里的状态记录逻辑

## 为什么这样设计

工作表模式的核心思路是把“待处理”和“已处理”都显式落库，这样任何后台任务都能被查询、重试、归档和回放。对于 Wiki、数据源同步和知识库清理这种需要长期维护的系统，这比单纯把任务扔进队列更可靠。

### Wiki 为什么特别依赖 MySQL

- 页面要版本化。
- 链接要能重建。
- 问题要能列出来并变更状态。
- 索引页要能快速分页。
- 反链和来源要能追踪。

这些能力都更适合关系型数据库，而不是纯内存队列。

## tradeoff

- **MySQL 工作表**：可审计、可搜索、可回放，但吞吐不如专门 MQ。
- **乐观锁**：能防并发覆盖，但冲突时要重试。
- **批量写入**：效率高，但异常排查更难。
- **单条记录状态机**：更清楚，但写路径更多。

## 常见失败模式

- 页面更新没做乐观锁，最后把别人改的内容覆盖掉。
- 任务表只入不出，最后堆成死数据。
- 死信表没有分页和筛选，运维根本找不到问题。
- 把日志和事实混在一起，导致页面状态被审计信息污染。

## 面试追问

### 1. 为什么 Wiki 页面要用乐观锁？

因为页面会被多人编辑，也会被自动修复和工具改写。乐观锁能避免最后写入的人把前面的修改悄悄覆盖掉。

### 2. 为什么要有任务表和死信表两张表？

因为正常待处理和失败待处理是两种不同状态。分开以后，查询、重试和清理逻辑都会更清楚。

### 3. 为什么不把所有后台任务都只放 MQ？

因为 MQ 解决的是执行，不解决审计、分页、搜索和长期可视化。MySQL 工作表把“正在处理什么”和“曾经失败什么”都保留下来，运维体验会好很多。

### 4. 为什么 Wiki 适合 MySQL 而不是纯文档存储？

因为 Wiki 不只是正文，它还有 slug、版本、来源、链接、问题单和统计数据。关系型数据库更适合承载这些约束。

### 5. 为什么任务失败后还要记 fail_count？

因为 fail_count 是退避、死信和人工介入的依据。没有这个计数，任务就只是“失败了”，没有任何后续策略。
