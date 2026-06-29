---
title: IM / 外部集成
tags: [IM, WebSearch, 集成, 外部能力]
aliases: [外部集成, IM集成]
---

# IM / 外部集成

## 总体思路

WeKnora 不把自己限制在 Web UI，而是把能力扩展到企业 IM、网络搜索、外部服务和平台插件中。这样它才是一个平台，而不只是一个网站。

## 子文档

| 文档 | 说明 |
|---|---|
| [IM 接入与通道编排](01_IM接入与通道编排.md) | 企微、飞书、Slack、Telegram 等通道接入 |
| [Web 搜索与工具扩展](02_Web搜索与工具扩展.md) | 网络搜索 provider、扩展接口和工具化思路 |

## 关键实现

- `internal/handler/im.go`：管理 IM 通道的创建、更新、开关和回调入口。
- `internal/im/service.go`：统一负责通道加载、消息处理、会话映射、文件入库、流式回复和摘要通知。
- `LoadAndStartChannels()` / `StartChannel()` / `HandleMessage()`：把通道生命周期和消息处理串起来。
- `resolveSession()` / `resolveUserSession()` / `resolveThreadSession()`：把 IM 原生线程映射成系统会话。
- `handleMessageStream()` / `fallbackNonStream()` / `runQA()`：处理流式问答和降级问答。
- `processFileToKnowledgeBase()` / `watchAndSendSummary()`：让 IM 里的文件和长对话能回流到知识库。
- `internal/infrastructure/web_search/registry.go`、`internal/application/service/web_search.go`、`internal/handler/web_search_provider.go`：把网络搜索做成 provider 化的外部能力。

## tradeoff

- **只做 Web**：简单，但入口有限。
- **多 IM**：覆盖广，但通道治理更复杂。
- **外部搜索集成**：增强能力，但要处理噪声和稳定性。
- **通道层编排**：适合多平台，但状态机更复杂。
- **统一会话映射**：体验更完整，但线程与 session 的绑定要处理好。

## 常见失败模式

- IM 回调重复，消息被二次处理。
- 线程上下文丢失，导致群聊追问和历史消息无法串起来。
- 文件消息和文本消息走同一分支，最后知识库链路和问答链路互相污染。
- 外部搜索结果不做过滤，噪声直接进入模型上下文。

## 面试追问

### 1. 为什么要把 IM 当成产品入口？

因为很多真实用户更习惯在 IM 里发起问题，而不是打开 Web 页面。把能力嵌入到工作流现场，能显著提升使用频率。IM 场景天然带来消息重试、排队、去重、线程上下文和会话映射，所以它不是简单 webhook，而是一条完整接入链路。

### 2. 为什么 Web Search 不能直接返回给模型？

因为外部搜索噪声高，结果质量参差不齐。需要先做筛选、排序和证据组织，再交给模型。

### 3. 为什么扩展能力要统一成 provider？

因为这样能把不同外部服务的差异收口，Agent 层只关心“能用什么”，不关心“底层怎么连”。这就是平台化设计。

### 4. 为什么 IM 里的消息处理要做去重和排队？

因为 IM 天然存在重复回调、消息风暴和异步通知延迟。去重和队列可以把不稳定入口变成可控入口，避免机器人重复答、并发打爆下游或者把会话状态写乱。

### 5. 为什么文件消息要回流到知识库？

因为很多真实协作的价值就藏在附件里。把文件进入知识库链路后，IM 不只是聊天入口，还能成为资料沉淀入口，后续检索和复用成本会低很多。
