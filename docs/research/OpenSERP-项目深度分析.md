# OpenSERP 项目深度分析

> 研究对象：[`karust/openserp`](https://github.com/karust/openserp)  
> 固定基线：[`e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f`](https://github.com/karust/openserp/tree/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f)（2026-07-13）  
> 本地镜像：`reference/openserp`  
> 调研日期：2026-07-18  
> 方法：以固定提交源码、仓库内 OpenAPI/架构文档、官方 SDK/MCP 源码为一手来源；GitHub 项目元数据只用于活跃度快照。除末章外，本文分析 OpenSERP 本身，不以 NoteWeave 对比替代项目研究。

## 1. 执行摘要

OpenSERP 是一个 MIT 许可的 Go 单体服务与 CLI，把六个公开搜索引擎的实时 SERP 页面转换为统一 JSON/Markdown/Text/NDJSON，并附带多引擎聚合、图片搜索、SERP feature、目标网页正文抽取、代理池和浏览器反检测能力。它面向三类用户：需要新鲜 Web grounding 的 LLM/Agent/RAG 工程团队、需要多地区多引擎排名数据的 SEO/监测团队，以及希望自托管搜索抓取基础设施的自动化开发者。[README](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/README.md#openserp)

它的核心不是“调用搜索 API”，而是直接抓取公开搜索页：默认用 Rod 驱动 Chromium，Google、Yandex、Baidu、Ecosia 另有基于 `tls-client` 与 `goquery` 的 raw HTTP 路径。统一 engine 接口隐藏浏览器/raw 差异，HTTP 层再做 resilience、响应归一化、聚类和格式化。[架构文档](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/docs/ARCHITECTURE.md#overview) [engine 注册表](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/cmd/engines.go)

OpenSERP 最有差异化的部分有四个：

1. Google 之外还把 Yandex、Baidu、Bing、DuckDuckGo、Ecosia 放在同一契约下，适合中俄语覆盖。
2. `balanced`、`any`、`fast` 三种 mega 执行策略，不只是简单并发 fan-out。
3. 把搜索结果与正文提取统一到同一响应，可在一次请求中为 top 1-5 结果生成 Markdown/Text。
4. 对搜索抓取的工程细节投入较深：Chrome profile、代理粘性 lane、cookie 复用/丢弃、captcha 识别与代理轮换、网络字节和 browser profile 可观测性。

但它仍是 1.0 前项目。当前代码版本是 `0.8.10`，最近正式 GitHub Release 为 `v0.8.6`；官方 JS/Python SDK 均明确标为 Alpha。项目测试面较广，但 live integration workflow 只手动触发；维护与贡献高度集中。更重要的是，自托管 OSS 服务本身没有认证/租户授权，搜索引擎页面结构漂移、IP 信誉和代理成本仍是无法由代码完全消除的主要运行风险。[版本常量](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/cmd/root.go#L18-L22) [CI](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/.github/workflows/ci.yml) [integration workflow](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/.github/workflows/integration.yml)

## 2. 项目历史、定位和用户

### 2.1 历史轨迹与当前状态

Git 历史始于 2023-06-23。早期提交集中在 Google/Yandex/Baidu 的浏览器抓取、captcha 检测、超时和 rate limiter；2025-2026 年扩展到更多引擎、标准化 v2 response、mega search、抽取、代理池、稳定性和发布流水线。固定基线共有 148 个提交和 14 个版本标签，标签从 `v0.1.1` 演进到 `v0.8.6`。截至调研日，GitHub API 快照为 1,108 stars、134 forks、15 open issues；这些数字只能说明关注度，不能代表生产 SLA。[提交历史](https://github.com/karust/openserp/commits/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f) [tags](https://github.com/karust/openserp/tags) [GitHub API](https://api.github.com/repos/karust/openserp)

提交贡献高度集中：固定历史中 129/148 个提交来自 Rustem Kamalov，18 个来自 Pachakutiq，另有 1 个外部贡献。这使架构一致性较高，也形成明显的 bus factor 风险。仓库有贡献指南、engine 增加清单、issue/PR 模板，但多数变更直接落到 main 的发布注释也承认了这一工作方式。[贡献指南](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/docs/CONTRIBUTING.md) [release workflow](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/.github/workflows/release.yml)

### 2.2 产品定位

OpenSERP 同时提供：

- 本地/自托管 OSS server，无 API key；
- 无需启动 server 的本地 CLI；
- 由作者运营、契约相近且带认证/credits 的 OpenSERP Cloud；
- 官方 JS、Python SDK，MCP server 和 n8n community node。

因此它更准确的定位是“SERP 获取与页面抽取基础设施”，不是研究 Agent、搜索质量评估器，也不是带索引的搜索引擎。它不负责拆解研究问题、迭代查询、判断来源可信度、构造证据关系或生成报告。`clusters[].score` 只是跨引擎排名一致性，不是事实可信度。[README deployment/SDK](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/README.md#deployment-options) [cluster 实现](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/clusters.go)

### 2.3 目标用户与典型任务

| 用户 | OpenSERP 解决的问题 | 它没有解决的问题 |
|---|---|---|
| LLM/Agent/RAG 工程师 | 实时网页发现、top-N 正文、MCP/SDK 工具面 | 证据验证、引用约束、研究规划 |
| SEO/品牌监测 | 多引擎/地区排名、广告与 organic rank、SERP feature | 长期存储、调度、趋势分析 UI |
| 数据/自动化工程师 | 统一 API、NDJSON、n8n、CLI | 队列、幂等任务、数据仓库 |
| 自托管团队 | 控制浏览器、代理、cache 和部署 | 开箱即用认证、多租户配额、托管 SLA |

## 3. 总体架构

OpenSERP 是单 Go module、单可执行文件的分层单体：

```text
CLI / HTTP API / SDK / MCP
          |
          v
Query parsing + middleware + request context
          |
          v
ResilientSearcher
  rate limit -> proxy policy -> retry -> circuit breaker
          |
          v
SearchEngine interface
  browser engine (all six) | raw HTTP engine (four)
          |
          v
engine-specific URL builder + selectors + parser
          |
          v
response enrichment -> mega dedupe/cluster -> optional extract
          |
          v
JSON / Markdown / Text / NDJSON
```

入口 `main.go` 只执行 Cobra root command；`cmd` 负责配置、server/CLI 装配和 engine 注册；`core` 负责公共模型、server、browser/raw transport、resilience、proxy、cache、格式化；六个 engine package 各自保存 URL、selector、parser、browser/raw search；`extract` 负责正文提取。[入口](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/main.go) [项目布局](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/docs/ARCHITECTURE.md#project-layout)

这是务实的“共享 runtime + engine plugin”结构，不是运行时动态插件系统。新增 engine 要修改编译期 `engineSpecs()` 注册表；该单表同时驱动 CLI、server、alias、parse endpoint 和 raw 支持，减少多处注册漂移。[ADDING_AN_ENGINE](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/docs/ADDING_AN_ENGINE.md) [engineSpecs](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/cmd/engines.go#L52-L63)

## 4. Engine 契约与六引擎实现

### 4.1 公共接口

所有 browser engine 实现 `core.SearchEngine`：

```go
Search(context.Context, Query) ([]SearchResult, error)
SearchImage(context.Context, Query) ([]SearchResult, error)
IsInitialized() bool
Name() string
GetRateLimiter() *rate.Limiter
```

接口刻意返回较小的内部 `SearchResult`（rank、absolute rank、type、URL、title、description、ad、features），公共 v2 response 在 server/CLI 统一 enrichment，避免每个 engine 重复稳定 ID、domain、favicon、position、classification 等逻辑。[接口说明](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/docs/ARCHITECTURE.md#core-interfaces) [内部结果](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/common.go#L87-L115) [response builder](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/response_builder.go)

raw parser 另有 `ParseHTML(io.Reader)` 能力，使固定 HTML fixture、`POST /{engine}/parse` 和 raw search 可以复用同一 parser。这是抵抗 selector 漂移的重要可测试边界。[ADDING_AN_ENGINE](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/docs/ADDING_AN_ENGINE.md#2-implement-the-search-contract)

### 4.2 能力矩阵

| Engine | Browser Web | Browser Image | Raw Web | HTML parse endpoint | 主要区域价值 |
|---|---:|---:|---:|---:|---|
| Google | 是 | 是 | 是 | 是 | 全球、SEO 主流 |
| Yandex | 是 | 是 | 是 | 是 | 俄语/独联体 |
| Baidu | 是 | 是 | 是 | 是 | 中文大陆 |
| Bing | 是 | 是 | 否 | 是 | 全球、Microsoft 生态 |
| DuckDuckGo | 是 | 是 | 否 | 是 | 无 key fallback/隐私搜索 |
| Ecosia | 是 | 是 | 是 | 是 | 欧洲与补充覆盖 |

矩阵来自固定提交的 `engineSpecs()`：只有 Google、Yandex、Baidu、Ecosia 设置 `rawSearchFn`；全部设置 browser factory 和 `parseHTMLFn`。[注册表](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/cmd/engines.go#L52-L63)

各 engine 仍依赖目标站点 DOM 和页面行为。selector 文件会优先稳定 data attribute，并允许明确 fallback；fixture 测试可以发现已知页面结构回归，却无法证明实时页面、地区变体、A/B test 和登录/consent 页面持续兼容。[engine 指南](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/docs/ADDING_AN_ENGINE.md#1-create-the-engine-package)

## 5. Browser 与 Raw 两条执行路径

### 5.1 Browser：主兼容路径

默认 server 使用 `go-rod` 控制 Chromium。server 建立有界 browser pool，以代理身份为 key 复用 Chrome；每次导航创建隔离 BrowserContext/page，完成后关闭页面和 context。pool 默认最多 6 个 Chrome process、idle TTL 5 分钟，并按 LRU/idle 回收。[browser pool](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/cmd/serve.go#L247-L506) [默认配置](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/config.yaml#L9-L20)

browser profile 不是随机改一个 User-Agent：profile 同时描述 UA/UA-CH、platform/version/architecture、locale/languages/timezone、viewport 等，并按 OS、headless/GPU 条件选择；同一代理 lane 用稳定 salt 选择 profile。运行时还用真实 Chromium version 修正 profile，尽量保持 surface coherence。[profile 模型与选择](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/browser/profile.go) [profile 应用](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/browser.go#L648-L1042)

默认阻断 image/font/css/media 和已知 tracker，以降低网络与渲染成本；但这也可能破坏依赖 CSS/资源加载的页面。认证 HTTP proxy 通过 CDP Fetch auth listener 处理；认证 SOCKS 在 browser runtime 明确不支持。每次响应可通过 `X-Network-Bytes` 和 `X-Browser-Profile-Id` 暴露资源使用和 profile。[资源阻断](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/browser.go#L184-L237) [代理限制](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/cmd/serve.go#L620-L636) [OpenAPI headers](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/docs/openapi.yaml#L61-L81)

### 5.2 Raw：低成本但覆盖较窄

raw 路径使用 `bogdanfinn/tls-client`/`fhttp` 模拟 Chrome TLS/headers，以 `goquery` 解析静态 HTML。transport 按代理、profile、TLS 与 private-network guard 维度缓存，最多保留有限 client；重定向最多 10 次并逐跳验证。它避免 Chrome process，通常更快更省内存，但无法执行 JS，且当前只有四个 engine 支持。[raw client](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/http_client.go) [架构执行模式](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/docs/ARCHITECTURE.md#overview)

server 的 `server.raw_requests` 是全局模式，不是每请求自适应。CLI 用 `--raw` 明确选择；在 raw 模式请求不支持 raw 的 Bing/DDG 会失败，而不是自动启动 browser。这个边界需要调用方配置时显式认识。[CLI](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/cmd/search.go) [配置](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/config.yaml#L2-L8)

## 6. 多引擎 Mega Search

`/mega/search` 和 `/mega/image` 接收 `engines=...`；未给时使用所有已配置 engine，未知值跳过、重复值消除。只要至少一个 engine 成功就返回部分结果，失败项进入 `meta.engines_failed` 和带清洗消息的 `meta.engine_errors`。[mega handler](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/server.go#L933-L1137)

三种 mode 的真实语义是：

| mode | 调度 | 结果语义 | cache |
|---|---|---|---|
| `balanced`（默认） | 所选引擎并行 | 聚合全部成功结果 | 可缓存 |
| `any` | 按输入顺序串行 | 第一个成功引擎 | 可缓存 |
| `fast` | 按 circuit breaker 记录的历史成功均值选择最快健康引擎 | 单引擎低延迟结果 | 不缓存 |

`fast` 不是“并发竞速后取消 loser”，而是依据进程内历史延迟选择；冷启动或样本不足时不能保证真是最快。`any` 是可用性 fallback，`balanced` 才产生完整跨引擎覆盖。[resilient mega 实现](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/resilient.go) [mega 配置解析](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/server.go#L1157-L1200)

`dedupe` 默认 true，控制 flat result URL 去重；`merge` 默认 true，控制是否保留多引擎集合并生成 clusters。`merge=false` 最终只保留请求顺序中第一个有结果的 engine，不等于“返回按 engine 分组的多个列表”。mega 总 deadline 默认 90 秒，慢 engine 可被截断并形成部分成功。[merge policy](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/server.go#L1202-L1230) [配置](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/config.yaml#L20-L21)

## 7. Query 与 Response 契约

### 7.1 Query

公共参数包括 `text`、`lang`、`region`、`date`、`file`、`site`、`limit`、`start`、`filter`、`features`、`extract`、`extract_mode`、`min_runes` 和 `format`。`text/site/file` 至少一个非空；`limit` 默认 10、范围 1-100；`start >= 0`。`filter` 和部分 region/date 语义仍会被各 engine 翻译，不是完全同质的跨引擎过滤器。[Query](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/common.go#L249-L436) [README parameters](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/README.md#query-parameters)

`features` 默认 true。`extract` 同时接受 bool/int：`true` 等于 top 1，`N` 限制到 1-5；显式 `extract=0` 优先关闭，即使给了 `extract_mode/min_runes`；否则后两者会隐式开启 extraction。[extract query parser](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/common.go#L438-L504)

代理/市场还可由 `X-Use-Proxy`、`X-Proxy-URL`、`X-Proxy-Country/Class/Provider/Session-ID` 和 `X-Tenant` 影响。`X-Tenant` 用于 request context 与 proxy lane 分区，不是认证后的 tenant identity。[middleware](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/middleware.go#L50-L67) [proxy lane key](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/proxy_lane.go#L130-L180)

### 7.2 v2.1 response

Web response envelope 包含：

- `query`：已解释的 text/lang/region 与 engines requested；
- `meta`：request ID、时间、耗时、responded/failed、engine error、schema version；
- `results`：稳定 ID、rank/type/title/url/snippet/domain/favicon/position/engine、domain info、classification、可选 extracted；
- `serp_features`：非 organic SERP 模块；
- `pagination`；
- mega search 可选 `clusters`。

`results[].id`、feature ID 和 cluster ID 是内容派生的短 MD5 标识，用于响应内稳定关联，不应被当成密码学身份或永久数据库主键。分页 `has_more` 由“返回数量 >= limit”推断，不能保证上游确实有下一页。[response types](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/response.go) [result types](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/result.go) [ID 构造](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/response_builder.go)

格式由 `format` 或 Accept header 选择：JSON、Markdown、Text、NDJSON。只有 JSON 进入 response cache；cache hit 会刷新 request ID/requested_at/took_ms，防止把旧 request metadata 原样返回。[format/cache](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/docs/ARCHITECTURE.md#response-formatting)

## 8. SERP Feature

OpenSERP 把 AI summary、answer box、featured snippet、knowledge panel、people-also-ask、related questions/searches、calculator、weather、dictionary 等模块从可排名 organic results 中分离，统一为 `SerpFeature`：type/title/text/items/links/source result IDs/position/confidence/extracted timestamp。[类型定义](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/result.go#L3-L94)

实现采用每 engine selector spec：按候选 container、title/text/item/link selectors 抽取，只有真正得到 text/items/links 才输出，并按内容去重。块级感知文本 flatten 尽量保持段落结构；相对链接根据 engine base URL 补全。[通用 feature extractor](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/feature_selectors.go) [feature enrichment](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/response_builder.go#L93-L178)

这个设计避免把 Yandex Neuro answer 或 DuckDuckGo instant answer 错算 organic rank，但 feature 内容仍是“搜索引擎页面展示的二手摘要”，不是已经核验的证据。`confidence` 是 selector spec 的静态字段，不是模型校准概率；调用方必须沿 `links` 回到原网页。[Yandex feature](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/yandex/features.go) [Google feature](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/google/features.go)

## 9. URL Extraction

### 9.1 独立与内嵌两种入口

`GET/POST /extract` 返回 title/lang/text/markdown/html/meta；搜索 endpoint 的 `extract=N` 则把 top-N 页面内容放入 `results[].extracted`。独立 endpoint 支持 `clean`、`use_llms_txt`、`min_runes`；搜索内嵌会跳过无 URL 或失败候选，并额外尝试最多 3 个候补来填足成功数量，单项失败写入 result error 而不把整个 search 变成 500。[extract handler](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/server_extract.go#L27-L111) [search enrichment](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/server_extract.go#L218-L360)

### 9.2 `fast`、`rendered`、`auto`

- `fast`：guarded raw HTTP，直接解析 response HTML；
- `rendered`：Chromium 导航后读取 rendered HTML；
- `auto`：先 raw；若错误或正文少于 `min_runes`，再 rendered；若 rendered 比已有 raw 内容更短，则保留 raw。

正文清理优先 `go-trafilatura`，可选择 full body；HTML 再转换 Markdown，支持 table/strikethrough。对站点根 URL 可先试 `/llms-full.txt`、`/llms.txt`。默认每 URL 20 秒、2 MB、并发 2；auto 最坏包含 raw+rendered 两次 timeout，batch deadline 按 wave 数推导。因此 `extract=5` 在低并发和慢站点上会显著扩大尾延迟。[extractor](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/extract/extractor.go) [正文算法](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/extract/content.go) [预算](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/extract/config.go)

### 9.3 SSRF 防护与剩余边界

默认 extraction 只允许 HTTP(S) 且拒绝 private、loopback、link-local、multicast、CGNAT 地址。raw path 在 DNS 后直接 dial 已验证 IP，并逐跳验证 redirect，抵御 DNS rebinding；rendered path 因 Chrome 自行 DNS，先要求 hostname 的所有地址均 public，并用 HEAD 预检 redirect。[network guard](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/network_guard.go) [rendered preflight](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/server_extract.go#L181-L216)

仍有两点不能忽略：启用 `extract.allow_private_networks` 会主动放开内网；rendered 的 HEAD 预检若遇到一般网络错误会在初始目标已验证后继续，因此最终浏览器导航安全依赖初始 DNS 策略和环境网络边界。生产环境仍应使用容器/网络 egress policy，而不是只信应用层 guard。

## 10. Dedupe、Cluster 与 Rank 语义

单 engine 内部去重区分 ad 与 organic，同 URL 的广告和自然结果不会相互吞掉；排序优先 absolute position，再处理 ad/rank/URL。organic rank 不被广告位置挤移，absolute rank 表达混合 SERP 位置，适合 SEO 场景。[rank/dedupe](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/common.go#L116-L247)

mega flat dedupe 对规范化 URL 分组，选择 rank 更高的结果，平 rank 时按 engine 名称稳定打破；这意味着 dedupe 后只保留一个 snippet/title，其他 engine 的文本差异只剩在 cluster occurrence 中的 engine/rank/result ID，不能完整还原。[mega dedupe](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/server.go#L1280-L1345)

cluster score 公式为：

```text
score = min(1, sum(1 / occurrence.rank) / engines_queried)
```

按 score 降序、best rank 升序排列，保留 canonical URL、occurrences、engine count。分母是“被请求 engine 数”，包括失败 engine，因此同一结果在部分失败请求中的 score 会下降；`engines_count` 当前实际是 occurrence 数，不是 distinct engine set 数，如果同 engine 意外产生同 URL 多次可能被高估。score 衡量跨引擎发现与排名共识，不衡量网页质量、时效或事实正确性。[cluster 实现](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/clusters.go)

## 11. Cache、Retry、Circuit Breaker

### 11.1 Cache

cache 是进程内、mutex 保护的有界 TTL map；默认 TTL 120 秒、最多 1,000 条，满时扫描并淘汰最旧 entry。没有 Redis/持久化/跨副本共享，也没有 stale-while-revalidate。key 包含 engine/action/query/filter/features 和代理市场元数据；无法确定代理市场时绕过 cache。只有 JSON、非 extraction 请求可缓存；fast mega 不缓存，dedicated fallback 结果不缓存。[cache](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/cache.go) [cache policy](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/server.go#L974-L1000)

### 11.2 Retry

retry 使用 exponential backoff + jitter，server 示例默认 `max_retries=1`。captcha、blocked、429、proxy unavailable、parser error、engine panic 与 context done 都不重试；这避免对结构性失败放大流量，但也意味着 selector 短暂异常不会被 retry 掩盖。request timeout 根据 attempt timeout、最坏 backoff 和 slack 推导，防止外层 deadline 提前截断配置的 retries。[retry](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/retry.go)

### 11.3 Circuit breaker

每个 engine 一个内存 breaker，默认 5 次连续失败开路、60 秒后 half-open、2 次成功关闭；统计还记录成功平均延迟，供 fast mode 选择。context cancellation、proxy unavailable 和 circuit already open 不计入 engine failure。状态不跨进程共享，横向扩容时每个 replica 会独立学习与冲击上游。[circuit breaker](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/circuit_breaker.go) [record policy](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/resilient.go#L246-L257)

## 12. Proxy 与 Captcha

支持 HTTP/HTTPS/SOCKS5/SOCKS5H proxy、全局代理、按 engine tag pool、`X-Use-Proxy: direct|tag` 和可选 `X-Proxy-URL`。tag pool round-robin；连续网络失败达到阈值后 disable proxy，全池失效进入 quarantine，结束后选择最少失败者探测恢复。只有 connect/auth/timeout 会损害 proxy health，parser/captcha 不会把代理标记为网络故障。[proxy registry](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/proxy.go) [错误分类](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/common.go#L14-L85)

proxy lane key 是 tenant + engine + session ID；lane 保存稳定 profile 和 cookies，默认最多 100 个、LRU 淘汰。遇到 captcha/challenge 时可清 cookies；若 tag pool 至少两个健康 proxy，会把 challenged proxy 暂时降权并再换一个 IP 尝试一次。这个设计把“代理网络故障”和“IP 被挑战”分开建模，是项目中较成熟的运行时细节。[lane store](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/proxy_lane.go) [challenge rotation](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/resilient.go#L190-L244)

captcha 首先由 engine selector/URL 识别并返回 `ErrCaptcha`。可选 2Captcha solver 必须同时满足全局 `captcha.solver_enabled=true`、engine captcha flag 与 API key；默认关闭。solver 是外部付费依赖，也不能保证所有 challenge 类型、地区或搜索引擎策略下成功。[captcha config](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/cmd/captcha_config.go) [solver](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/captcha.go)

## 13. API、CLI、SDK、MCP 与自动化生态

### 13.1 OSS API

主要 endpoint：`/{engine}/search`、`/{engine}/image`、`/{engine}/parse`、`/mega/search`、`/mega/image`、`/mega/engines`、`/extract`、`/health`、`/ready`、`/stats`、`/stats/cache|proxy|cb`、Swagger `/docs` 与 `/openapi.yaml`。parse body 最大 10 MB，适合接外部 HTML provider；health 与 ready 区分存活和就绪。[OpenAPI](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/docs/openapi.yaml) [route wiring](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/server.go#L213-L245)

### 13.2 CLI

`openserp serve` 启动服务；`openserp search ENGINE QUERY` 无 server 直连 engine，支持 filters、format、raw、proxy、extract top-N 和 `--full` feature 输出。CLI 与 API 共用 Query、engine parser、response builder 和 extraction，减少两套行为漂移；但 CLI 只做 dedicated single-engine search，没有本地 mega command。[CLI README](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/README.md#cli-search) [CLI 实现](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/cmd/search.go)

### 13.3 官方 SDK

- JS/TS `@openserp/sdk`：Node 18+、ESM/CJS，固定审查提交 [`8eba209`](https://github.com/openserpapi/sdk-js/tree/8eba209e61d84062d5635095286fcac96e7b27f4)；
- Python `openserp`：Python 3.10+、同步/async client、可选 pandas，固定审查提交 [`4700ddc`](https://github.com/openserpapi/sdk-python/tree/4700ddc51b6d4aeed3206f5611b2a777c745226c)。

两者用 `baseUrl/base_url` 在 OSS 与 Cloud 间迁移；Cloud 默认 `/v1` + Bearer API key，OSS 默认 localhost 无 key。两者封装 search/mega/fast/any/image/extract、typed error 与 response telemetry；SDK 自己默认不 retry，只提供调用方 hook。两个 README 都明确标注 Alpha，生产需要 pin version。[JS README](https://github.com/openserpapi/sdk-js/blob/8eba209e61d84062d5635095286fcac96e7b27f4/README.md) [Python README](https://github.com/openserpapi/sdk-python/blob/4700ddc51b6d4aeed3206f5611b2a777c745226c/README.md)

### 13.4 MCP 与 n8n

官方 MCP 固定提交 [`be12c2d`](https://github.com/openserpapi/mcp/tree/be12c2d319785dc97584caba0813fcd6007c751a)，提供 `search`、`mega_search`、`fast_search`、`any_search`、`image_search`、`mega_image`、`extract`、`get_usage`、`list_engines` 九个工具。默认 stdio，也可开 Streamable HTTP `/mcp` 和旧 SSE `/sse`；无 key 时连接 localhost OSS，有 key 时连接 Cloud。[MCP README](https://github.com/openserpapi/mcp/blob/be12c2d319785dc97584caba0813fcd6007c751a/README.md)

n8n community node 固定提交 [`d7b0a94`](https://github.com/openserpapi/n8n/tree/d7b0a9412a3fea2d72bae803cc866eaab82f43a4)，覆盖 search/image/extract 和 Cloud account/capability/status。它证明 API 已形成初步工具生态，但 SDK/MCP/n8n 是独立仓库、独立版本，必须额外管理契约兼容矩阵。[n8n README](https://github.com/openserpapi/n8n/blob/d7b0a9412a3fea2d72bae803cc866eaab82f43a4/README.md)

## 14. 部署、容量与资源成本

### 14.1 部署形态

可通过 `go install`、源码编译、release 二进制或 Docker 部署。Docker 是 multi-stage：Go 1.24.6 编译静态 binary，运行层使用 pin digest 的 `chromedp/headless-shell:stable`、非 root `chrome` 用户、healthcheck；tag release 构建 linux amd64/arm64 image 和 Darwin/Linux/Windows 多架构二进制。[Dockerfile](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/Dockerfile) [Docker workflow](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/.github/workflows/docker.yml)

Compose 明确给 `/dev/shm` 2 GB，因为 Chromium 在默认 64 MB shm 下会 OOM；容器 `init: true` 负责回收子进程。这是最低可运行提示，不是容量承诺。[compose](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/docker-compose.yaml)

### 14.2 成本驱动因素

| 因素 | 成本影响 |
|---|---|
| browser mode | 每个 active proxy identity 可能占一个 Chrome process；CPU/RAM 显著高于 raw |
| `max_processes=6` | 是单进程 browser pool 上限，不是全局 query concurrency/SLA |
| balanced 6 engines | 最多同时触发六条 SERP 路径，网络与 challenge 风险相乘 |
| `extract=N` | 再抓 N 个目标站；auto 最坏 raw + rendered 两次 |
| proxy | 稳定 Google/Baidu/Yandex 通常依赖有质量的 IP；住宅/移动代理是主要外部成本 |
| captcha solver | 2Captcha 按次收费，且增加延迟与数据外发 |
| cache | 只在单实例内省流量，横向副本不共享 |

项目没有提供基准测试、每 QPS CPU/RAM 曲线、推荐 pod requests/limits 或代理成功率数据。因此无法从源码给出可靠“每节点 QPS”；上线前必须按目标 engine、地区、proxy 类型、browser/raw、extract depth 建压测矩阵。`X-Network-Bytes`、`/stats/proxy`、`/stats/cb`、cache stats 是建立成本模型的现成观测点。[stats endpoints](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/README.md#health--stats)

## 15. 配置与安全

配置优先级为 CLI flags > `OPENSERP_*` env > `config.yaml` > code default，Viper 自动映射。主要配置域为 server、app/browser、extract、proxies/lanes、cache、resilience/circuit breaker、CORS、captcha 与各 engine rate limit。[配置初始化](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/cmd/root.go) [config 示例](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/config.yaml)

安全判断必须区分“代码默认”和“随仓库 config 示例”：代码默认 `server.insecure=false`、`allow_request_proxy_url=false`、`allow_private_networks=false`；但仓库根 `config.yaml` 设置了 `server.insecure=true` 和 `proxies.allow_request_proxy_url=true`。Dockerfile 又直接复制此 config，Compose 挂载同一文件。因此直接照 quickstart 自建镜像/Compose 时可能运行在更宽松策略。[代码默认](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/cmd/root.go#L390-L441) [示例配置](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/config.yaml#L1-L34)

OSS OpenAPI 顶层 `security: []`，server 没有 API key/JWT/ACL middleware。`Authorization` 只被 CORS allow headers 接受，不表示服务验证它；`X-Tenant` 也由客户端任意给定。默认 CORS origin 为 `*`。因此 OSS 不应裸露公网，应置于认证 gateway/内网，限制 origin、body/rate/timeout，并关闭 request proxy URL。[OpenAPI security](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/docs/openapi.yaml#L1-L17) [middleware](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/middleware.go)

建议最小 hardening：

1. `server.insecure=false`；
2. `proxies.allow_request_proxy_url=false`，只允许预注册 tag；
3. `extract.allow_private_networks=false`，容器层禁访问 metadata/内网；
4. CORS 仅允许可信 origin；
5. 通过 gateway 提供认证、tenant 绑定、quota 和总并发；
6. 2Captcha key、proxy credential 仅用 secret 注入，日志继续使用已实现的 masked URL；
7. debug/fingerprint endpoint 保持关闭；
8. 明确搜索引擎条款、robots、隐私和地区合规责任。

## 16. 测试、CI 与成熟度

固定基线有 71 个 `_test.go` 文件、约 385 个 `Test*` 函数，覆盖 query/parser fixture、SERP feature、cache/retry/circuit、proxy/lanes、SSR​F/network guard、server error/timeout/panic、browser profile coherence、resource blocking、captcha selector 等。默认单元测试要求无 browser/network，live tests 用 build tag 与 `OPENSERP_INTEGRATION_TESTS=1` 隔离。[测试约定](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/docs/CONTRIBUTING.md#testing) [testutil](https://github.com/karust/openserp/tree/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/testutil)

主 CI 对 push/PR 执行 `go test -race`、`go vet`、OpenAPI lint、build、golangci-lint；Docker PR/main 验证 build；release 验证 tag 与代码 version 一致并发布二进制/image。live integration workflow 仅 `workflow_dispatch`，所以实时搜索兼容性不构成每次合并 gate。[CI](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/.github/workflows/ci.yml) [integration](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/.github/workflows/integration.yml) [release](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/.github/workflows/release.yml)

本次环境没有 Go runtime，未能独立执行 `go test ./...`；成熟度判断基于测试源码和 CI 定义，不把“存在 CI”写成“当前固定提交已由本次验证通过”。

综合成熟度判断：

| 维度 | 判断 | 理由 |
|---|---|---|
| 架构边界 | 中高 | engine registry、transport/parser、enrichment、resilience 分层清楚 |
| 单元/fixture 测试 | 中高 | 测试数量和失败类型覆盖较广 |
| live 兼容性 | 中 | 手动集成 CI；DOM/反爬天然易漂移 |
| API 稳定性 | 中低 | 0.8.x；SDK 官方标 Alpha；OpenAPI info 2.2 与 response constant 2.1 还有版本表达差异 |
| 可运维性 | 中 | stats/health/proxy/cb 有基础；无分布式状态与指标标准导出 |
| 安全开箱 | 低 | OSS 无认证，示例 config 比代码默认宽松 |
| 社区/bus factor | 中低 | 关注度可观，但贡献高度集中 |

## 17. 限制与开放问题

### 17.1 已确认限制

1. **上游脆弱性**：没有官方搜索 API 契约，DOM、A/B、地区、consent 和 bot challenge 可随时改变。
2. **覆盖不对称**：六 engine 都有 browser，但只有四个 raw；各 engine filter/image/feature 完整度不一致。
3. **不是搜索质量系统**：没有 query expansion、semantic rerank、source trust、freshness policy、evidence verification。
4. **聚类信息有损**：flat dedupe 丢掉其他 engine 的 title/snippet；cluster 只留 occurrence metadata。
5. **内存态**：cache、breaker、延迟学习、proxy health、lane cookies 都不跨进程。
6. **自托管无认证**：需要外部 gateway；客户端可伪造 tenant header。
7. **资源不可预测**：Chromium、proxy、extract 和 captcha 让成本高度依赖实际 traffic；无官方 benchmark。
8. **法律/政策责任**：直接抓 SERP 的条款、频率、内容再利用和地区合规由部署方承担。
9. **版本未完全统一**：代码 CLI `0.8.10`、最新 release `0.8.6`、response `2.1`、OpenAPI info `2.2.0`，调用方应按实际 schema 测试而非只看一个 version 字段。

### 17.2 上线前需要验证的开放问题

- 目标地区和语言下，每 engine 的成功率、captcha 率、空结果率、parser error 率是多少？
- `fast` 的冷启动选择和重启后学习时间是否满足延迟目标？
- 代理供应商、session sticky 语义和 OpenSERP lane 是否一致？
- balanced 部分成功时，业务是否接受 score 分母仍包含失败 engine？
- extraction 对 PDF、登录墙、无限滚动、非 HTML、超大页面的产品预期是什么？
- multi-replica 是否需要 Redis cache、共享 breaker/proxy health，还是允许各副本独立？
- 自托管 API 与 SDK/MCP 各版本的契约兼容如何 gate？
- 是否应在返回里保留每 engine 的完整 duplicate result，而非只有 cluster occurrence？
- 实时 selector canary、fixture 自动更新和失败告警由谁维护？
- 对搜索结果和被抓网页的 retention、PII、版权、robots 与删除请求政策是什么？

## 18. 对 NoteWeave 的借鉴、不可照搬与分阶段建议

本章是应用建议，不改变前文结论：OpenSERP 是 Web discovery/fetch infrastructure，不是 Research Agent runtime。

### 18.0 NoteWeave 当前检索基线

当前 Research Agent 虽由独立 Python worker 执行，但检索范围仍被业务契约和 adapter 组装绑定到 Workspace。Java 创建 Research Run 时要求至少一个 ready workspace source（[ResearchRunService.java](../../backend/src/main/java/com/noteweave/research/ResearchRunService.java#L97-L101)）；Python 在没有外部 provider key 时只构造 `WorkspaceSearchAdapter`，配置 key 后才构造 Workspace + 外部 provider 的 `CompositeSearchAdapter`（[search_adapters.py](../../workers/research-worker/app/search_adapters.py#L386-L391)，[provider 构造](../../workers/research-worker/app/search_adapters.py#L784-L826)）。因此当前部署若没有搜索 key，实际就是 Workspace-only；配置 Serper 或其他 provider 后才是混合检索。

OpenSERP 能补的是外部 Web provider 层，而不是替代整个 Research Agent。正确接法是解除“必须有 Workspace source”约束，把 `WEB_ONLY`、`WEB_PLUS_SEEDS`、`SOURCES_ONLY` 设为显式运行模式，再把 OpenSERP 接入 Web provider registry。`workspace_id` 仍保留为 NoteWeave 的租户、ACL、配额和产物归属字段，但不再隐式决定搜索语料范围。

### 18.1 值得借鉴

1. **统一 provider contract，保留 provider-specific metadata**：借鉴 SearchEngine + enrichment 分层，但 NoteWeave adapter 应把 OpenSERP 视为一个 provider，不把六 engine 直接泄漏到 planner 的全部业务代码。
2. **三种多引擎调度语义**：`balanced` 适合高价值查询，`any` 适合 provider fallback，`fast` 适合低延迟探索；在 NoteWeave 中应由 query family/budget 决定，而不是所有查询固定 fan-out。
3. **partial success 与 typed engine error**：一台 engine 失败不应丢弃其他结果，错误细节要进入 task telemetry。
4. **rank 与 evidence quality 分离**：cluster agreement 可作为 fetch priority feature，但必须和 source authority、freshness、claim support 分开。
5. **raw-first/rendered fallback**：正文读取可先走低成本 HTTP，再按内容质量/JS 需要升级 browser；每次升级必须受阶段预算控制。
6. **代理 challenge 与网络故障分开**：不要用 captcha 降低 proxy health；sticky lane、cookie drop、一次换 IP 重试值得复用。
7. **SSRF 策略**：DNS pin、redirect hop validation、private network fail-close 应成为任何外部 fetch 的共同底座。
8. **可观测性字段**：保留 request ID、engine responded/failed、cache、network bytes、profile、mode used、extraction error，便于研究成本归因。
9. **fixture parser contract**：保存真实但清洗过的 SERP fixture，持续检测 selector 漂移。

### 18.2 不可照搬

1. 不把 cluster score 当 citation quality 或事实置信度。
2. 不让 `search?extract=N` 绕过 NoteWeave 的 Fetch/Read snapshot、SSRF、prompt-injection、content hash 和 evidence ledger。
3. 不直接公网暴露 OSS server，不信任 `X-Tenant`，不沿用宽松示例 config。
4. 不把进程内 cache/breaker/proxy health 当分布式可靠性方案。
5. 不依赖 OpenSERP 做 query planning、rerank、source credibility、evidence verification 或 citation generation。
6. 不在默认每个 query 上跑六引擎 + top-5 extraction；这会把搜索预算、Chrome 和代理成本指数式放大。
7. 不把 SDK/MCP Alpha 契约直接设为内部核心 domain model；应以自有 adapter + contract test 隔离。

### 18.3 分阶段升级

**阶段 0：契约与基准（先做）**

- 定义 NoteWeave `WebSearchProvider`：query、results、engine provenance、partial error、cost/latency；
- 定义 `WebFetchProvider`，与 search 分开计 budget/snapshot；
- 用固定中英查询集对 Serper、OpenSERP 单 engine 与 mega 做 shadow benchmark；
- 指标至少包括成功率、有效 URL 率、重复率、证据采用率、P50/P95、网络字节、captcha/parser error 和单研究成本。

**阶段 1：低风险 Search Adapter**

- 只接 `/mega/search` 或 dedicated search，不启用内嵌 extract；
- 优先 `any`/两引擎 `balanced`，保留 provider fallback；
- 把 clusters 作为候选优先级 feature，原始 engine occurrences 保留到 provenance；
- 通过 gateway/internal network 调用并 pin OpenSERP image/commit。

**阶段 2：Fetch fallback**

- 将 `/extract` 仅作为现有 Read pipeline 的 provider fallback；
- raw-first，只有低内容量/JS 页面升级 rendered；
- NoteWeave 自己执行 URL policy、snapshot、hash、injection scan、引用定位；
- 设全局和 per-run browser/extract semaphore，不沿用单请求默认并发即认为安全。

**阶段 3：多地区与代理运行时**

- 根据真实缺口启用 Baidu/Yandex 和地区 proxy；
- 复制 lane/challenge/network error 分类思想，而不是暴露客户端任意 proxy header；
- 增加 selector canary、engine SLO、自动降级和代理成本报警。

**阶段 4：有证据再决定自托管深度**

- 若 volume、合规或中俄覆盖证明收益，再建设共享 cache/metrics、autoscaling、egress policy 和灾备；
- 若运维/代理成本高于 managed provider，则保留 OpenSERP Cloud/Serper 多 provider，而不是为了“开源”强行全自托管。

最终建议不是“用 OpenSERP 替代现有 search”，而是先把它纳入可测量、可降级的 provider portfolio。真正的升级价值来自多引擎覆盖、raw/rendered 分层、typed partial failure、代理运行模型和抓取安全；Research Agent 的规划、证据与验证闭环仍应由 NoteWeave 自己持有。

## 19. 一手来源索引

- OpenSERP 固定源码：[`e7827cc`](https://github.com/karust/openserp/tree/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f)
- [README](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/README.md)
- [Architecture](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/docs/ARCHITECTURE.md)
- [OpenAPI](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/docs/openapi.yaml)
- [Adding an Engine](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/docs/ADDING_AN_ENGINE.md)
- [Server/Mega](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/server.go)
- [Browser](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/browser.go)
- [Raw HTTP](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/http_client.go)
- [Resilience](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/resilient.go)
- [Extraction](https://github.com/karust/openserp/blob/e7827cc03d68edbe5eab0f3ba23cf3c3ff29931f/core/server_extract.go)
- [JS SDK `8eba209`](https://github.com/openserpapi/sdk-js/tree/8eba209e61d84062d5635095286fcac96e7b27f4)
- [Python SDK `4700ddc`](https://github.com/openserpapi/sdk-python/tree/4700ddc51b6d4aeed3206f5611b2a777c745226c)
- [MCP `be12c2d`](https://github.com/openserpapi/mcp/tree/be12c2d319785dc97584caba0813fcd6007c751a)
- [n8n `d7b0a94`](https://github.com/openserpapi/n8n/tree/d7b0a9412a3fea2d72bae803cc866eaab82f43a4)
