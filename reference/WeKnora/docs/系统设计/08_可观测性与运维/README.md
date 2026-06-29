---
title: 可观测性与运维
tags: [可观测性, 审计, 运维, Langfuse, 日志]
aliases: [Observability, 运维排障]
---

# 可观测性与运维

## 总体思路

WeKnora 把 Agent 轨迹、审计日志、保留策略和运行时追踪当成核心能力，而不是附属功能。因为知识系统一旦复杂起来，没有观测就没法维护。

## 关键模块

- `internal/tracing/langfuse/manager.go`
- `internal/tracing/langfuse/tracer.go`
- `internal/application/service/audit_log.go`
- `internal/application/service/audit_log_retention.go`
- `internal/handler/audit_log.go`
- `docs/Langfuse集成.md`
- `docs/日志配置.md`

## 关键实现

- `langfuse.Init()` / `GetManager()` / `Enabled()` / `Shutdown()`：负责 Langfuse 生命周期管理，支持启停和优雅退出。
- `StartTrace()` / `ResumeTrace()` / `StartSpan()` / `StartGeneration()`：把一次请求拆成 trace、span、generation 三层可观测对象。
- `GinMiddleware()`：把 tracing 接到 HTTP 请求生命周期里，自动采集 user、session、path 等上下文。
- `auditLogService.Log()` / `LogDenied()` / `List()` / `Purge()`：负责业务审计、拒绝审计和保留清理。
- `NewAuditLogRetentionRunner()` / `Start()` / `Stop()` / `runOnce()`：以后台任务方式做审计日志保留，不阻塞主链路。

## 观测策略

1. **请求链路要可回放**：知道每一步做了什么，而不只是最终输出了什么。
2. **审计链路要可追责**：知道谁发起、谁拒绝、谁修改、谁共享。
3. **保留策略要可运维**：旧日志要清理，但不能清到影响排障。
4. **观测不能反向拖慢业务**：发生异常时宁可降级，不要阻塞主流程。

## 为什么这样设计

Agent 系统最难 debug 的不是代码崩，而是“为什么这次检索没命中、为什么工具调用失败、为什么回答偏了”。没有 tracing 和审计，问题只能靠猜。

Langfuse 这一类系统的价值不只是“看图”，而是把推理过程拆成可读事件。再配合本地审计日志，就可以同时回答“系统怎么跑”和“用户做了什么”。

## tradeoff

- **全量追踪**：最容易排查，但成本高。
- **采样追踪**：成本低，但可能漏问题。
- **审计 + tracing 双轨**：更适合生产系统。
- **异步入队**：不阻塞业务，但要接受队列满时的降级。
- **保留清理**：节省成本，但要保住关键时间窗。

## 常见失败模式

- Langfuse 队列满了以后直接阻塞请求，最后观测把业务拖慢。
- 只采样不审计，导致某些问题能看链路却看不到责任主体。
- 保留任务没有幂等，重复清理或漏清理。
- 追踪字段太少，只能看到“失败了”，看不到“为什么失败”。

## 面试追问

### 1. 为什么知识系统比普通 API 更需要 tracing？

因为它不是一个请求完成一个结果，而是多阶段链路。问题可能出在检索、路由、工具调用、摘要、生成或权限过滤的任何一层。普通 API 看请求耗时和错误码就能定位很多问题，但 Agent/RAG 系统必须知道每个阶段发生了什么。

### 2. Langfuse 这类工具的价值是什么？

它们把“LLM 运行过程”从黑盒变成可观测对象。对于 Agent 系统来说，这和后端链路监控一样重要。要记录 trace、span、generation、工具调用和 token 使用，才能回答“为什么这次结果不对”。

### 3. 审计和 tracing 为什么要一起看？

tracing 能告诉你系统怎么跑，审计能告诉你用户做了什么。对企业知识平台来说，这两个视角缺一不可。

### 4. 为什么 tracing 可以采样，审计却不能随便采样？

因为 tracing 的目的是排障和分析趋势，适合按比例取样；审计的目的是留痕和追责，不能因为省成本就丢掉关键操作记录。两者目标不同，所以策略也不能一样。

### 5. 为什么观测层要默认异步？

因为它的职责是记录，而不是决定业务成败。只要不影响结果，观测就应该尽量异步；真出问题时，可以降级、丢非关键事件，但不能把主请求卡住。
