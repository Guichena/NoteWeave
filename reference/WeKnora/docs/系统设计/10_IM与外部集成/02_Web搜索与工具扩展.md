---
title: Web 搜索与工具扩展
tags: [WebSearch, 工具, 扩展, Provider]
aliases: [网络搜索, Provider扩展]
---

# Web 搜索与工具扩展

## 总体思路

WeKnora 把 Web Search 当成 Agent 的外部信息补充能力。不同 Provider 通过统一接口接入，Agent 再按需调用。它的定位不是“直接把搜索结果扔给模型”，而是“先把外部噪声变成可控证据，再进入推理链路”。

## 关键模块

- `internal/infrastructure/web_search/registry.go`
- `internal/infrastructure/web_search/bing.go`
- `internal/infrastructure/web_search/google.go`
- `internal/infrastructure/web_search/duckduckgo.go`
- `internal/infrastructure/web_search/tavily.go`
- `internal/infrastructure/web_search/searxng.go`
- `internal/infrastructure/web_search/ollama.go`
- `internal/handler/web_search.go`
- `internal/handler/web_search_provider.go`

## 关键实现

- `NewRegistry()` / `Register()` / `CreateProvider()`：把各搜索源注册成 provider，并按类型动态创建实例。
- `WebSearchService.Search()` / `resolveProvider()`：执行一次检索时先选 provider，再做参数合并和调用。
- `CompressWithRAG()`：把原始搜索结果压缩成更适合模型消费的证据包。
- `selectReferencesRoundRobin()` / `consolidateReferencesByURL()`：控制证据覆盖面，避免单一来源刷屏。
- `filterBlacklist()` / `matchesBlacklistRule()`：过滤掉不适合进入上下文的结果。
- `ConvertWebSearchResults()`：把 provider 输出收敛成统一搜索结果结构。
- `ListProviders()` / `CreateProvider()` / `UpdateProvider()` / `TestProviderByID()` / `TestProviderRaw()`：提供 provider 的管理、测试和联调入口。

## 为什么这样设计

网络搜索是知识平台的外部补充源，但搜索质量、延迟和可用性都不稳定。Provider 抽象可以让系统按配置选择不同搜索源。

更重要的是，Web Search 的输出天然不干净，所以需要先做规范化、黑名单过滤、引用整理和结果压缩，再进入 Agent 上下文。

## tradeoff

- **搜索源越多**：覆盖越广，但噪声和治理成本越高。
- **统一接口**：便于 Agent 使用，但 provider 差异要自己消化。
- **外部搜索**：增强实时性，但答案稳定性要靠过滤和引用约束。
- **检索先压缩再入模**：减少噪声，但会牺牲一部分原始细节。
- **provider 可测试**：更利于运维，但管理接口更多。

## 常见失败模式

- provider 配置没校验，运行时才发现参数不兼容。
- 搜索结果不做合并，重复 URL 占满上下文。
- 黑名单规则太弱，广告页、镜像页和空壳页混进来。
- 压缩过度，最后证据太少，模型只能“像是知道”却不能“真的说明白”。

## 面试追问

### 1. 为什么要把搜索源做成可插拔？

因为不同搜索源在可用性、费用、延迟和结果质量上差异很大。可插拔能让系统在不同部署条件下切换，不被单一供应商绑死。

### 2. 为什么外部搜索一定要加引用约束？

因为外部内容最容易噪声化。没有引用约束，模型很容易把不可靠内容当成事实。

### 3. Web Search 在知识系统里更像什么角色？

它更像外部证据补充层，而不是核心知识源。它用来补实时性和外部事实，不替代内部知识库。

### 4. 为什么要先压缩再交给模型？

因为外部搜索的成本不只是钱，还有上下文长度和噪声。压缩能让模型把注意力放在真正相关的证据上，而不是被一堆重复结果带偏。

### 5. 为什么要给 provider 单独做测试入口？

因为搜索源最常见的问题不是“完全不能用”，而是参数错、代理错、速率限制、返回格式漂移。单独测试入口能把这些问题在配置阶段暴露出来，而不是拖到用户问答时才爆。
