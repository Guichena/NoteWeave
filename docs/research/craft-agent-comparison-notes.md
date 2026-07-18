# Craft Agents 与 NoteWeave Research Agent 对比

> 调研日期：2026-07-18  
> 官方仓库：[`craft-ai-agents/craft-agents-oss`](https://github.com/craft-ai-agents/craft-agents-oss)  
> 本地基线：[`4289b160`](https://github.com/craft-ai-agents/craft-agents-oss/commit/4289b16097322e9911d3078d8a64bd8c830717c3)  
> 许可证：Apache-2.0

## 结论

Craft Agents 不是专用 Deep Research Agent，而是一个通用、可扩展的桌面/服务端 Agent 工作台。它最值得 NoteWeave 借鉴的不是研究算法，而是边界设计：

1. `workspace` 是会话、配置、权限、技能、source 注册和文件落点的容器。
2. source 是可选激活的工具，不会因为 session 属于 workspace 就自动成为当前任务的检索语料。
3. Web Search 是独立内建工具，不依赖 workspace source。
4. session 可以在 workspace 下运行，但数据访问由显式 source、`@mention`、工具权限和 working directory 决定。

这正好说明 NoteWeave 可以保留 `workspace_id` 作为租户、权限和产物归属边界，同时让 Research Agent 默认执行公网搜索，而不强制 `source_scope`。

## 1. Craft Agents 的准确定位

官方把 Craft Agents 定位为面向文档工作的通用 Agent UI/runtime，强调多会话、任意 API/MCP 接入、session sharing、sources、skills、权限模式、后台任务和自动化，而不是结构化研究报告系统。[README](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/README.md#why-craft-agents-was-built)

它同时使用 Claude Agent SDK 和 Pi SDK，支持 Anthropic、OpenAI/ChatGPT、Google、GitHub Copilot 等连接；桌面端可以连接远程 headless server，session、工具执行和 LLM 调用在服务端运行。[README：Agent Backends](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/README.md#agent-backends)

## 2. Workspace 和 Source 的边界

Craft 的 workspace 负责组织：

- sessions 与消息历史；
- 默认模型连接；
- sources 配置；
- skills；
- automations；
- working directory、计划和 session data。

其本地目录结构位于 `~/.craft-agent/workspaces/{id}`，sources、skills 和 sessions 分目录保存。[README：Data Storage](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/README.md#data-storage)

但是 source 是可选的。Quick Start 明确把“Connect sources”列为 optional；CLI 的 `run` 命令也用可重复的 `--source <slug>` 显式启用 source。[README：Quick Start](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/README.md#quick-start)；[`docs/cli.md`](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/docs/cli.md#run-command)

源码中的 `SourceManager` 分开追踪：

- 当前真正运行的 `activeSlugs`；
- UI 期望激活的 `intendedSlugs`；
- workspace 中所有可用 sources；
- 当前 session 是否已经看过某个 source 的说明。

它会把 active/inactive source 状态注入 prompt，并能在模型调用未激活 source 的 MCP 工具时自动激活和重试，但不会把所有 workspace sources 自动读入当前任务。[`source-manager.ts`](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/shared/src/agent/core/source-manager.ts)

## 3. Web Search 实现

Craft 的 Pi backend 提供统一 `web_search(query, count)` 工具，底层 provider 对模型透明。[`create-search-tool.ts`](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/pi-agent-server/src/tools/search/create-search-tool.ts)

Provider 根据当前 LLM connection 选择：

1. OpenAI API：Responses API 原生 `web_search`；
2. ChatGPT Plus：ChatGPT backend search；
3. OpenRouter：Responses-compatible search；
4. Google：Gemini native Google Search grounding；
5. 没有可用 provider credential：DuckDuckGo 无 Key fallback。

见 [`resolve-provider.ts`](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/pi-agent-server/src/tools/search/resolve-provider.ts)。

DuckDuckGo provider 自己还有三级 fallback：JS library、HTML endpoint、Lite endpoint，并对瞬时错误做有限重试。[`ddg.ts`](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/pi-agent-server/src/tools/search/providers/ddg.ts)

这套 Web Search 不依赖 workspace source。Workspace 决定 session 属于哪里，LLM connection 决定搜索 provider，source activation 决定可调用哪些外部业务系统，三者彼此分开。

## 4. Web Fetch 与 Browser

Craft 的 `web_fetch` 负责：

- HTTP/HTTPS URL 校验；
- DNS 解析和私网/保留地址阻断；
- 流式读取和 50 MB 上限；
- HTML 主体转 Markdown；
- 文本截断到 50,000 字符；
- PDF/图片等二进制文件下载到 session data 目录。

见 [`web-fetch.ts`](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/pi-agent-server/src/tools/web-fetch.ts)。源码也明确承认 DNS 校验与实际 fetch 之间仍有 TOCTOU 缝隙。

此外还有 session-bound 的 `browser_tool`，支持打开页面、导航、snapshot、查找元素、点击、输入、等待、截图和窗口归属管理。[`browser-tools.ts`](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/shared/src/agent/browser-tools.ts)

## 5. 与 NoteWeave 的核心差异

| 维度 | Craft Agents | NoteWeave Research Agent |
|---|---|---|
| 产品定位 | 通用 Agent 工作台 | 专用、证据型 Deep Research runtime |
| Workspace | 会话/配置/权限/文件归属容器 | 正确目标也应是租户和产物归属；当前代码额外强制 source scope |
| Sources | 可选 MCP/API/filesystem 工具，session 级激活 | 当前创建 Research Run 时必须选择 READY workspace sources |
| Web Search | 独立内建，provider-native + DDG fallback | ExternalSearchAdapter 存在，但增量运行默认关闭 external evidence |
| 网页读取 | `web_fetch` + in-app browser | Jina/HTTP Fetch、Read Window、外部 snapshot archive |
| Agent 循环 | SDK 驱动的通用 tool loop | Planner、Search/Fetch/Read/Extract、Verifier、Recovery |
| 证据模型 | 没有专用 evidence ledger | Evidence card、row/cell、claim relation、provenance |
| 研究质量门 | 没有 local/global verifier | Dual Verifier、guarded write、counterfactual recheck |
| 持久化 | workspace/session JSONL、headless server | DB/Kafka、checkpoint、lease/fencing、atomic completion/merge |
| 最终交付 | 对话、文件、文档和工具结果 | 研究报告、引用、审计、artifact/writeback |

## 6. 对 NoteWeave 最有价值的启发

### P0：修正 Workspace 边界

应采用 Craft 的基本模型：

```text
workspace_id = owner / ACL / quota / run / artifact destination
source selection = optional task input
web_search = independent built-in capability
```

因此 NoteWeave 应允许没有 `source_scope` 的 Research Run。Workspace source 只在用户显式附加时成为 seed/evidence source，而不是 Research Agent 的启动条件。

### P0：分开 Source Registry 与 Run Input

Craft 的 workspace 可以注册很多 sources，但 session 只激活需要的部分。NoteWeave 也应区分：

- workspace 中“可用的资料/API/MCP”；
- 当前 Research Run 显式选择的 seeds；
- Research Agent 自主发现的 Web sources；
- 最终通过 verifier 的 evidence sources。

当前 `source_scope` 同时承担“workspace 资料选择”和“Research 必需输入”，职责混在一起。

### P1：提供无需额外 Key 的搜索降级

Craft 在 provider-native search 不可用时回退 DuckDuckGo，因此不会因没有 Serper Key 就完全失去 Web Search。NoteWeave 可以采用：

```text
OpenSERP/Serper/provider-native search
    -> DuckDuckGo 或 OpenSERP self-hosted fallback
```

### P1：把浏览器能力放在工具层

Craft 的 browser 与 session 绑定，但不与 workspace corpus 绑定。NoteWeave 如果需要浏览器渲染，应把它作为 Fetch/Browser provider，并继续经过 permit、snapshot archive、prompt-injection detection 和 evidence verifier。

### P2：借鉴 source hot activation，不照搬动态复杂度

`@source`、guide prerequisite、mid-turn activation 对通用工作台很有价值。但 NoteWeave Research Agent 是受控服务，不应允许运行中任意安装未知 MCP。可以借鉴显式 source activation 和工具说明读取，不应放弃静态 allowlist、permit 与预算控制。

## 7. 不应该照搬的部分

1. Craft 的 agent loop 主要依赖通用 SDK 和模型自行工具调用，不具备 NoteWeave 的 evidence state machine。
2. 搜索结果只是 title/URL/snippet，没有 research-level source-quality、claim support 和 citation verification。
3. Session JSONL 和后台任务不能替代数据库中的研究 checkpoint、fencing 与 atomic merge。
4. 动态接入任意 API/MCP 会扩大凭据、网络和供应链风险，不适合直接进入受控 Research Worker。
5. Craft 的 workspace 是本地工作目录和配置容器；NoteWeave 是多租户 SaaS，必须保留服务端 ACL、quota 和 ownership 防线。

## 8. 建议目标模型

```text
Workspace (ownership / ACL / quota / artifacts)
  └─ Research Run
      ├─ explicit question / intent / budget
      ├─ optional seed sources
      ├─ independent Web Search providers
      ├─ controlled Fetch / Browser providers
      ├─ Evidence Ledger + Verifiers
      └─ Report / Citations / Artifact
```

最终判断：Craft Agents 进一步证明当前 NoteWeave “必须先选 Workspace source 才能启动 Research”的契约是错误耦合；但 Python Research Worker 的 planner、evidence、verifier、checkpoint 和报告体系仍有价值，不需要推倒重写。主要应重构 Java 创建/协调契约、task snapshot source policy 和搜索 provider 选择。
