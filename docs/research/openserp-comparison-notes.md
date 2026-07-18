# OpenSERP 一手资料研究笔记

> 调研日期：2026-07-18  
> 对象：[`karust/openserp`](https://github.com/karust/openserp)  
> 源码基线：[`e7827cc`](https://github.com/karust/openserp/commit/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f)  
> 范围：只描述 OpenSERP，不分析 NoteWeave 本地 Research Agent；供后续逐项比较使用。

## 结论先行

OpenSERP 的准确定位是**可自托管的搜索与网页正文抽取基础设施**，不是完整的 research agent。它把 Google、Yandex、Baidu、Bing、DuckDuckGo、Ecosia 的实时 SERP 抓取统一成 API/CLI，并提供多引擎并发、URL 归一化去重、简单跨引擎聚类评分、目标页 Markdown/Text 抽取以及官方 MCP/SDK。[官方 README](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/README.md#L10-L28)

它适合作为 agent 的一个 `web_search` / `fetch_url` 工具后端；从公开源码所呈现的职责看，它本身不包含研究问题拆解、查询改写循环、证据可信度审查、引用绑定、预算/检查点、长期任务状态或综合报告生成。换言之，OpenSERP 解决的是“**怎样拿到搜索结果和网页正文**”，上层 research agent 仍需解决“**搜什么、何时继续、相信什么、怎样综合并交付**”。这一判断可由其 [HTTP 请求流与核心接口](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/docs/ARCHITECTURE.md#L48-L84) 以及[官方 MCP 暴露的工具集合](https://github.com/openserpapi/mcp/blob/be12c2d319785dc97584caba0813fcd6007c751a/README.md#L30-L42)直接验证。

## 1. 项目定位与边界

- 免费、MIT 许可、开源的 SERP API 与 CLI；支持本地运行、自托管，也提供同 API 形状的 OpenSERP Cloud。[README：定位与部署](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/README.md#L10-L14)；[部署选项](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/README.md#L165-L172)；[MIT License](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/LICENSE)
- 面向 LLM、agent、RAG 与 SEO rank tracking；强调 Yandex/Baidu 带来的俄语、中文搜索覆盖，而非 Google-only。[README](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/README.md#L10-L14)
- 产出是规范化搜索结果、SERP features、图片结果与目标页抽取内容，不负责生成最终研究结论。[公共响应结构](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/docs/ARCHITECTURE.md#L85-L109)

## 2. 架构与请求链路

项目是 Go 单体 API + CLI，使用 Fiber 提供 HTTP 服务。每个搜索引擎实现统一 `SearchEngine` 接口；请求经过参数解析、缓存、韧性封装，再进入具体引擎的浏览器或 raw HTTP 抓取器，最后统一富化并序列化。[架构文档](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/docs/ARCHITECTURE.md#L1-L84)

核心链路可概括为：

```text
HTTP / CLI
  -> 查询参数与输出格式
  -> 缓存、限流、重试、熔断、代理策略
  -> 单引擎抓取或 Mega 多引擎执行
  -> SERP DOM/HTML 解析
  -> URL、domain、stable id、classification 富化
  -> 可选目标页正文抽取
  -> JSON / Markdown / Text / NDJSON
```

值得注意的实现特征：

- 默认兼容路径是 headless Chromium + `go-rod`；raw HTTP + `goquery` 当前仅覆盖 Google、Yandex、Baidu、Ecosia，Bing 与 DuckDuckGo 没有 raw 搜索实现。[架构说明](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/docs/ARCHITECTURE.md#L7-L12)；[引擎注册表源码](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/cmd/engines.go#L33-L42)
- 韧性层含单引擎 rate limiter、retry/backoff、circuit breaker、代理健康与响应缓存；验证码错误不重试，专用引擎端点默认不跨引擎 fallback。[架构说明](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/docs/ARCHITECTURE.md#L159-L175)
- 配置优先级为 CLI flags > `OPENSERP_*` 环境变量 > `config.yaml` > 默认值。[配置说明](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/docs/ARCHITECTURE.md#L197-L201)

## 3. 搜索源与执行模式

官方内置六个搜索引擎：Google、Yandex、Baidu、Bing、DuckDuckGo、Ecosia；均有专用 web/image endpoint，另有 `/mega/search` 与 `/mega/image`。[README：Search Endpoints](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/README.md#L204-L243)；[服务路由源码](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/server.go#L182-L236)

Mega 有三种执行模式：

| 模式 | 行为 | 适合场景 |
|---|---|---|
| `balanced` | 对所选引擎并行请求，允许部分成功，合并结果 | 常规多源检索 |
| `fast` | 并行竞争，使用最快健康引擎 | 低延迟工具调用 |
| `any` | 按顺序尝试，首个成功即返回 | fallback/可用性优先 |

服务端分派实现见 [`handleMegaEndpoint`](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/server.go#L1017-L1035)，默认值是 `balanced + dedupe=true + merge=true`，[解析源码](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/server.go#L1137-L1172)。

查询参数覆盖语言、地区、日期范围、文件类型、站点、分页、SERP features 与抽取深度；结果上限为 100，嵌入式正文抽取上限为前 5 条。[README：Query Parameters](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/README.md#L324-L351)

## 4. API、CLI 与部署

- HTTP API 暴露专用引擎搜索/图片、Mega 搜索/图片、引擎列表、`/extract`，以及 health/ready/stats/cache/proxy/circuit-breaker 观测端点。[路由源码](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/server.go#L182-L236)
- 自带 OpenAPI YAML 与本地 Swagger UI：`/openapi.yaml`、`/docs`。[README](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/README.md#L174-L181)；[OpenAPI spec](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/docs/openapi.yaml)
- CLI 可直接查询，无需先启动 server；CLI 与 API 共用引擎、格式和过滤能力。[README：CLI](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/README.md#L263-L319)
- 可从源码构建、`go install`，或运行官方 `karust/openserp` 镜像。镜像采用多阶段构建，运行层为 headless-shell，非 root 用户并带 healthcheck。[Dockerfile](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/Dockerfile)；[Compose](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/docker-compose.yaml)
- 官方 Compose 明确把 `/dev/shm` 提高到 2 GB，注释说明默认 64 MB 会使 Chrome 页面加载 OOM；这意味着 browser-rendered 模式有不可忽略的内存/进程运维成本。[Compose](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/docker-compose.yaml#L1-L13)

## 5. 抓取与解析

### SERP 抓取

- Browser mode 通过真实 headless Chromium 导航并解析 DOM，是所有引擎的主兼容路径。
- Raw mode 直接发 HTTP 请求并用 `goquery` 解析，资源更轻但覆盖少两个引擎，且对搜索引擎页面结构、封禁策略更敏感。[架构说明](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/docs/ARCHITECTURE.md#L7-L12)
- 各引擎包分别维护 URL 构建、selectors、HTML parser、SERP feature parser、captcha/block/no-result 检测与 fixture 测试；例如 [Google 包](https://github.com/karust/openserp/tree/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/google) 和 [Baidu 包](https://github.com/karust/openserp/tree/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/baidu)。

### 目标页正文抽取

`/extract` 与搜索参数 `extract=N` 可返回目标页的 Markdown/Text。抽取有三种模式：

- `fast`：仅 raw HTTP；
- `rendered`：仅浏览器渲染；
- `auto`：先 raw；正文达到质量阈值就返回，否则升级到 browser-rendered，并比较两者正文长度，避免渲染后反而只拿到 bot wall/consent page。[Extractor 源码](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/extract/extractor.go#L22-L75)

正文清洗使用 Go 版 trafilatura 提取主内容，再通过 html-to-markdown 转 Markdown；如果主内容少于 250 runes，会回退到可读 body 全文，减少 landing page、文档索引被“清洗空”的情况。[内容抽取源码](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/extract/content.go#L24-L100)

抽取器还支持根站点优先尝试 `llms-full.txt` / `llms.txt`，并含 URL/网络边界校验选项；这对 agent grounding 很实用，但它仍只是内容获取和清洗，不负责内容真实性判断。[Extractor 源码](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/extract/extractor.go#L38-L46)；[llms.txt 实现](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/extract/llmstxt.go)

## 6. 排序、合并与去重

OpenSERP 没有 LLM reranker 或语义 reranker。它保留搜索引擎原始 rank，并做确定性合并：

1. Mega 默认按**归一化 URL**去重；同一 URL 冲突时保留更小 rank，rank 相同用 engine 名字作确定性 tie-break。[去重源码](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/server.go#L1278-L1329)
2. 聚类在 flat dedupe 前基于所有引擎结果构造，记录每个 URL 在各引擎的 occurrence、best rank、engines count。[Mega 构建流程](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/server.go#L1075-L1102)
3. 聚类分数是 `sum(1/rank) / engines_queried`，上限 1.0，按 score 降序、best rank 升序排列。[聚类源码](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/clusters.go#L8-L84)

因此该分数表达的是“多个搜索引擎是否共同把某 URL 排在前面”，不是页面质量、权威性、时效性、与研究命题的证据相关度，也不是来源多样性约束。上层 agent 若要可靠研究，仍需单独做 source policy、可信度判断、claim-evidence matching 与 reranking。

## 7. Agent 集成方式

官方提供三条主要路径：

1. 直接把 HTTP/OpenAPI 封装成 agent tools；
2. 使用官方 JavaScript/TypeScript 或 Python SDK；
3. 使用官方 [`@openserp/mcp`](https://github.com/openserpapi/mcp) MCP server。[README：SDKs & Examples](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/README.md#L183-L202)

MCP server 同时支持本地 OSS 和 Cloud：未设置 `OPENSERP_API_KEY` 时默认连接 `http://localhost:7000`；可用 `OPENSERP_BASE_URL` 指向任意兼容实例。[MCP README](https://github.com/openserpapi/mcp/blob/be12c2d319785dc97584caba0813fcd6007c751a/README.md#L6-L28)

它向 MCP client 暴露 9 个工具：`search`、`mega_search`、`fast_search`、`any_search`、`image_search`、`mega_image`、`extract`、`list_engines`、`get_usage`，可用 stdio、Streamable HTTP 或旧 SSE transport。[MCP README](https://github.com/openserpapi/mcp/blob/be12c2d319785dc97584caba0813fcd6007c751a/README.md#L30-L42)；[传输配置](https://github.com/openserpapi/mcp/blob/be12c2d319785dc97584caba0813fcd6007c751a/README.md#L82-L116)

MCP 实现基本是参数 schema + SDK 转发：`search` 调 `client.search`、`mega_search` 调 `client.megaSearch`、`extract` 调 `client.extract`。[工具转发源码](https://github.com/openserpapi/mcp/blob/be12c2d319785dc97584caba0813fcd6007c751a/src/tools.ts#L10-L43) 这再次说明它是 agent 的搜索工具层，不是 agent planner/orchestrator。

## 8. 成熟度信号

截至 2026-07-18，从官方 GitHub 可观察到：

- 仓库创建于 2023-06-23，约 1.1k stars、134 forks，MIT 许可；这些是采用度信号，不等同于生产 SLA。[GitHub 仓库](https://github.com/karust/openserp)
- 最新正式 release 为 [`v0.8.6`](https://github.com/karust/openserp/releases/tag/v0.8.6)（2026-06-29），仍处于 1.0 前版本；主分支在 release 后继续快速修改 captcha/block detection 与浏览器 fingerprinting。[最新主分支提交](https://github.com/karust/openserp/commits/main/)
- 当前主分支对应的官方 push CI 成功。[GitHub Actions run](https://github.com/karust/openserp/actions/runs/29220985394)
- 测试资产较丰富：核心缓存、代理、熔断、重试、浏览器、解析器均有单元测试，各引擎还有保存的 SERP HTML fixture 和 integration test 文件。[core tests](https://github.com/karust/openserp/tree/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core)；[Google tests](https://github.com/karust/openserp/tree/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/google)
- 官方 MCP 包版本为 `0.1.6`，也明显处于早期版本。[MCP package.json](https://github.com/openserpapi/mcp/blob/be12c2d319785dc97584caba0813fcd6007c751a/package.json#L1-L7)

本地没有可用 Go toolchain，因此本次未独立执行 `go test ./...`；成熟度判断基于官方源码、release 和官方 CI，而非本地实测。

## 9. 限制与风险

### 结构性限制

- **依赖搜索引擎页面结构**：它抓取并解析公开 SERP HTML/DOM，而不是调用各搜索引擎的稳定官方 API；selector 或页面结构变化会造成解析退化。
- **反自动化与代理成本是核心运营问题**：源码有 captcha、soft block、proxy rotation、fingerprint、2Captcha、retry/circuit breaker 等专门模块，说明封禁处理不是边缘功能，而是系统常态风险。[浏览器实现](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/browser.go)；[Captcha 实现](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/captcha.go)
- **raw 覆盖不完整**：Bing、DuckDuckGo 只能走 browser mode；低资源部署不能在所有引擎上都获得 raw 模式收益。[引擎注册表](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/cmd/engines.go#L33-L42)
- **内置缓存是进程内有界 TTL cache**，不等于分布式缓存；多副本间不会天然共享命中与一致性。[缓存源码](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/cache.go#L18-L25)
- **排序不是研究质量排序**：只利用原始 rank、归一化 URL 和跨引擎一致性；没有语义相关性、authority、freshness 或 claim-level evidence 评分。
- **不构成完整研究闭环**：没有任务持久化、研究轮次、planner、synthesis、citation verification、人工审查或最终报告 artifact。

### 官方 issue 中仍开放的运行问题

以下只能视为“项目维护者尚未关闭的报告”，不能据此断言所有部署都能复现：

- Google captcha：[#36](https://github.com/karust/openserp/issues/36)
- SERP features 始终为空的用户报告：[#33](https://github.com/karust/openserp/issues/33)
- 2Captcha 求解结果接收失败：[#9](https://github.com/karust/openserp/issues/9)
- raw-mode 各引擎支持矩阵的文档仍待补：[#30](https://github.com/karust/openserp/issues/30)

## 10. 后续比较时建议使用的维度

为了避免把搜索后端和 research agent 错当成同类产品，建议后续分层比较：

| 层次 | OpenSERP 已提供 | 需要上层系统承担 |
|---|---|---|
| Search acquisition | 六引擎、图片、过滤、分页、代理、browser/raw | 搜索源政策、何时用哪个源 |
| Content acquisition | URL 抽取、Markdown/Text、auto render fallback | PDF/附件专项解析、付费墙/登录态策略 |
| Result normalization | 统一 schema、stable id、domain enrichment | 统一证据对象、claim/citation 映射 |
| Merge/rank | URL 去重、原始 rank、跨引擎 reciprocal-rank 聚类 | 语义 rerank、权威性/时效性/多样性 |
| Reliability | 限流、重试、熔断、代理、cache、partial success | 跨任务预算、checkpoint、恢复、审计 |
| Agent access | REST、OpenAPI、SDK、MCP | planner、query expansion、reflection、synthesis |
| Delivery | JSON/Markdown/Text/NDJSON 搜索结果 | 可验证引用、完整报告、artifact 生命周期 |

最合理的关系不是“OpenSERP 替代 research agent”，而是“OpenSERP 可替换或增强 research agent 的 web search + page extraction provider”。是否值得接入，关键看现有系统是否缺少多引擎中文/俄文覆盖、browser-rendered SERP、统一抽取和自托管代理治理；如果现有短板位于研究编排、证据审计或报告综合，OpenSERP 本身不会补齐。

## 11. 与 NoteWeave Research Agent 的直接差异

两者不在同一层：OpenSERP 是 **acquisition infrastructure**，NoteWeave Research Agent 是 **research orchestration and evidence runtime**。

| 维度 | OpenSERP | NoteWeave Research Agent | 判断 |
|---|---|---|---|
| 研究问题拆解 | 不提供 | Planner、query family、cell task | 保留 NoteWeave |
| 公网搜索覆盖 | Google/Yandex/Baidu/Bing/DDG/Ecosia | 外部 provider chain 默认 Serper，但当前增量运行默认关闭 external evidence；所谓 workspace search 只是对显式 `source_scope` 摘要/首个文本窗口排序 | OpenSERP 明显补强公网链路 |
| 搜索执行 | 单引擎或 Mega 并行/竞速/fallback | 按研究计划和预算调用 provider，provider 间主要是顺序配额与 fallback | 可组合，职责不同 |
| 网页获取 | raw/browser/llms.txt，主内容 Markdown/Text | Jina/HTTP transport、token budget、SSRF 防护、prompt-injection 标记、snapshot | OpenSERP 补渲染；NoteWeave 保证证据边界 |
| 去重/排序 | URL 去重、跨引擎 reciprocal-rank cluster | workspace 优先、URL/title/source 去重、coverage/source-quality、恢复目标排序 | OpenSERP 提供候选共识，不能替代证据排序 |
| 证据建模 | 不提供 | read window、evidence card、row/cell ledger、claim relation | NoteWeave 独有 |
| 验证与纠偏 | 不提供 | local/global verifier、counterfactual recheck、guarded write | NoteWeave 独有 |
| 长任务可靠性 | 单请求 retry/circuit/cache/proxy | checkpoint/resume、Kafka task、lease/fencing、permit、atomic completion/merge | NoteWeave 独有 |
| 最终交付 | 搜索/抽取结果 | citations/provenance、报告、审计、artifact/writeback | NoteWeave 独有 |

本地代码证据：

- 闭环与 verifier：`workers/research-worker/app/loop_runtime.py` 的 `run_research_loop`、local/global verifier 和 `COUNTERFACTUAL_RECHECK` 决策。
- 受控 agent 执行：`workers/research-worker/app/deep_cell_executor.py` 的 `Search -> Fetch -> Read -> Extract`、permit、外部 snapshot 归档和原子 completion envelope。
- 并发但不共享可变 ledger：`workers/research-worker/app/local_parallel_scheduler.py`。
- 版本校验与 verifier-gated merge：`workers/research-worker/app/merge_gate.py`。
- 当前公网搜索接入：`workers/research-worker/app/search_adapters.py`。注意：`WorkspaceSearchAdapter` 不查询工作区全文索引，只调用 `search.py` 对传入的 `source_scope` 快照做字段匹配。

## 12. 对我们最有帮助的四个点

### P0：把 OpenSERP 接成新的 Search Provider

这是收益最大、侵入最小的方案。建议新增专用 `OpenSerpSearchTransport`，调用 `/mega/search`，把 `results[]` 归一化到现有 `ResearchSearchHit`，并把以下信息保留进 trace/source metadata：

- `results[].engine`
- `meta.engines_failed`
- `clusters[].engines_count / best_rank / score`
- OpenSERP `request_id` 与 `took_ms`

收益：

1. 中文研究可以显式加入 Baidu，俄语加入 Yandex，不再主要依赖 Google 索引代理。
2. 单一商业 SERP key 故障时，可使用自托管 OpenSERP 作为 provider fallback。
3. cluster score 可作为“多引擎共同命中”特征输入 NoteWeave ranker/verifier，但不能直接当作 source quality。
4. OpenSERP 的 partial success、熔断和代理治理把搜索引擎侧故障隔离在 acquisition 层。

当前产品入口强制至少选择一份 READY workspace source，且 `NOTEWEAVE_RESEARCH_AGENT_EXTERNAL_EVIDENCE_ENABLED` 默认是 `false`。因此在默认配置下，Research Agent 实际是“显式资料范围研究”，不是 Web Research；若产品目标是通用 Deep Research，应先解除 workspace source 的强制依赖，再接 OpenSERP。

当前不能只改环境变量直接接入，原因是现有通用 GET transport：

- 强制要求非空 API key，自托管 OpenSERP 默认无需 key；
- 发送 `q/num/page`，OpenSERP 使用 `text/limit/start`；
- 没有 `engines/mode/lang/region/date/site` 参数映射；
- 没有解析 `meta.engines_failed`、`clusters` 和每条结果的 `engine`。

因此需要专用 transport，而不是把 base URL 指向 OpenSERP 后假定兼容。

### P1：把 `/extract` 接成 UrlSnapshotTransport 的可选 fallback

现有抓取链是 Jina（有 key 时）和直接 HTTP；对于依赖 JavaScript 的页面，直接 HTTP 可能只得到壳页面。OpenSERP 的 `auto -> rendered` 可以补这一块。

推荐链路：

```text
Jina（若配置） -> 当前安全 HTTP -> OpenSERP extract(auto/rendered) -> snippet fallback
```

或者把 OpenSERP 放在 HTTP 前面，仅用于允许浏览器渲染的 workspace/profile。无论哪种顺序，返回内容仍必须经过 NoteWeave 自己的：

- 公网 URL/SSRF 策略；
- token/byte budget；
- prompt-injection detection；
- immutable snapshot archive；
- evidence/read-window/provenance 绑定。

不建议让 `/mega/search?extract=N` 直接绕过现有 Fetch/Read 阶段，否则会破坏阶段级预算、permit、snapshot 归档和审计语义。

### P1：把多引擎共识作为候选排序特征

OpenSERP cluster score 适合增加一个 `cross_engine_agreement` 特征，用于候选发现阶段：多个引擎共同高排的 URL 可以优先进入 Fetch/Read。但必须与以下特征分开：

- 来源权威性；
- 与目标 claim/cell 的相关性；
- 时效性；
- 独立来源数量；
- evidence support/conflict score。

也就是说，它可以改善“先读谁”，不能决定“相信谁”。

### P2：复用其搜索侧运维能力

如果研究量上升，OpenSERP 可独立承担浏览器池、代理池、captcha detection、engine rate limiting、retry、circuit breaker、短 TTL cache 与 health/stats。这样 research-worker 不必内嵌 Chromium 和各搜索引擎 selector，故障域也更清晰。

代价是新增一个明显偏重的服务：官方 Compose 为 Chromium 配置 2 GB `/dev/shm`，还需要代理质量、验证码、引擎 DOM 变化和多副本缓存策略的持续运维。

## 13. 推荐落地顺序

1. **先修正运行模式。** 允许 `WEB_ONLY` 在没有 workspace source 时启动；把当前 `WorkspaceSearchAdapter` 更名/重构为 `ScopedSourceAdapter` 或 `SeedSourceAdapter`，明确它不是 workspace 全库检索。
2. **再做 OpenSERP search adapter，不启用 extract。** 用 `/mega/search` 补 Baidu/Yandex/多引擎候选，保持现有 Fetch/Read/Evidence 全链路不变。
3. **做 shadow evaluation。** 对同一批研究问题同时跑 `Serper` 与 `OpenSERP`，比较 hit coverage、有效 fetch 率、verified evidence 数、最终 citation support、P95 延迟与单 run 成本。
4. **只在确有 JS 页面缺口时接 `/extract`。** 作为独立 fetch transport，并继续走 NoteWeave snapshot/security/evidence pipeline。
5. **验证稳定后再决定 provider 策略。** 推荐 Web providers 之间做并行配额或 fallback；显式资料仅作为 seeds，不应默认排在所有 Web 结果之前。

建议的验收指标：

- 中文问题 Top-20 unique-domain recall；
- primary-source 命中率；
- search hit -> archived read window 转化率；
- verifier-approved evidence/card 数；
- unsupported citation rate；
- captcha/all-engines-failed 比例；
- P50/P95 搜索与完整 run 延迟；
- 每个成功 verified finding 的综合成本。

## 14. 最终建议

**值得接，但只应作为 Research Agent 的工具层增强。**

短期最合理的投入是一个专用 OpenSERP search adapter，加一个小规模 shadow benchmark。它最可能改善我们的中文公网覆盖、provider 可用性和 JS SERP 获取能力；不会替代现有 planner、Table-as-State、dual verifier、counterfactual、checkpoint、原子 merge 和报告交付，也不应该绕过这些边界。

## 一手来源索引

- [OpenSERP 主仓库](https://github.com/karust/openserp)
- [README（固定到调研 commit）](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/README.md)
- [架构文档](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/docs/ARCHITECTURE.md)
- [OpenAPI](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/docs/openapi.yaml)
- [核心 server/mega 实现](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/server.go)
- [正文抽取实现](https://github.com/karust/openserp/tree/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/extract)
- [官方 MCP 仓库](https://github.com/openserpapi/mcp)
- [Releases](https://github.com/karust/openserp/releases)
- [Issues](https://github.com/karust/openserp/issues)
