# Craft Agents OSS 项目深度分析

> 研究基线：`craft-ai-agents/craft-agents-oss`，commit `4289b16097322e9911d3078d8a64bd8c830717c3`，标签 `v0.11.1`，2026-07-10。
> 本文把官方 README 的产品叙述与固定提交中的源码事实分开；源码链接均固定到该提交。

## 1. 结论先行

Craft Agents 不是一个“只会执行 prompt 的聊天壳”，而是一个以会话为中心的 agent operating surface：桌面 GUI、WebUI、远程 headless server 和 CLI 共用一套 workspace/session 领域模型、RPC 协议、工具权限、source/MCP、技能、凭据和自动化系统。它的核心价值是让 agent 在可见、可恢复、可扩展的会话中操作文件、API、MCP 和浏览器。

这里的产品身份需要特别澄清：本文分析的是 Craft.do 团队开发、后来被 Polymarket 收购的 **Craft Agents**，即当前仓库 `craft-ai-agents/craft-agents-oss`；不是 GitHub 上单数命名、主打“深度研究 + 网站生成”的 `ipvoov/Craft-Agent`。外部材料把 Craft Agents 称为 Claude Cowork-like agent experience，但它仍是通用 Agent 工作台，不是专门的 Deep Research runtime。

它的设计有四个相互依赖的边界：

1. **Workspace 是资源与策略边界**：配置、来源、技能、标签、自动化、会话目录和凭据归属在 workspace 下；它不是一个自动注入给模型的知识库。
2. **Session 是执行边界**：会话持有消息、工作目录、模型连接、权限模式、启用的 source、SDK 会话 ID、附件、plans/data 目录。
3. **Backend 是模型运行时边界**：Anthropic Claude SDK 与 Pi coding-agent SDK 通过统一 `BaseAgent`/`AgentBackend` 合约接入。
4. **Tool 是可审计动作边界**：Claude 的 SDK hook 与 Pi 子进程的 JSONL pre-tool handshake 都进入同一套权限、来源激活和输入变换逻辑。

这比“Workspace 与搜索能力解耦”完整得多：Workspace 负责组织和治理，Web search、browser、web fetch、MCP/API/local source 是可选能力；会话可以没有启用 source，也可以只启用一个 source，不能据此把 Craft 归类为 Workspace 检索产品。

## 2. 历史、定位与目标用户

官方 README 说明 Craft Agents 起源于 Craft 团队内部，用来处理多任务、API/服务连接、共享 session 和 document-centric 工作流；产品定位是“更有主张、非 CLI 优先”的 agent 工作界面，并同时使用 Claude Agent SDK 和 Pi SDK（[README](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/README.md#why-craft-agents-was-built)）。

2026-06-26，Craft 联合创始人 Balint Orosz 宣布 Polymarket 已收购 Craft Agents，部分 Craft 团队成员加入 Polymarket，他本人将负责 Polymarket 的 Product Engineering（[Balint Orosz 声明](https://x.com/balintorosz/status/2070572407702650999)）。Gergely Orosz 随后进一步确认被收购产品是开源的 Craft Agents，并将其描述为早于 Claude Cowork 构建、理念相近的 agent experience（[Gergely Orosz 说明](https://x.com/GergelyOrosz/status/2070886113179558157)）。这次交易针对 Craft Agents 产品及部分团队，不等于 Craft.do 整家公司被收购；Craft.do 同期宣布公司已回购外部投资者股份，转为团队所有（[Craft.do 声明](https://x.com/craftdocs/status/2070579587902230564)）。

仓库身份也与这条历史一致：固定提交的 README 写明它由 `craft.do` 团队内部开发，Electron 包作者为 `Craft Docs Ltd.`；README 仍保留旧地址 `lukilabs/craft-agents-oss`，当前 canonical remote 已迁移为 `craft-ai-agents/craft-agents-oss`。因此仓库组织名变化不能被解释为另一个同名项目。

固定快照的 Git 历史只有一次提交，无法从本地 commit graph 重建完整演化过程。GitHub API 显示仓库创建于 2026-01-19，基线发布为 `v0.11.1`；同一仓库在该时点已有桌面、server、CLI、WebUI、MCP、source、skills 和 automations 的完整目录。历史判断因此分成两层：

- **可核验历史事实**：仓库创建时间、版本发布顺序、Apache-2.0、当前固定提交。
- **不能过度推断的部分**：本地 OSS 镜像是 squashed snapshot，不能把当前文件注释中的“迁移”“修复”当成完整发布历史。

目标用户覆盖四类：

- 需要多个并行会话、长任务、文件/文档工作流的个人用户；
- 需要连接 Linear、Slack、Gmail、GitHub、Notion 等 API/MCP 的运营与研究用户；
- 想在本地或 VPS 上保留会话、使用自己模型凭据的开发者；
- 需要脚本化、CI 验证或批量运行的 CLI 用户。

它不是专门的 Deep Research runtime，也没有 evidence ledger、引用验证或研究报告的领域模型；Web search 返回搜索结果，可信度判断仍由上层 agent 和用户完成。

## 3. 整体架构与部署形态

### 3.1 Monorepo 分层

核心包可按职责分为：

```text
apps/electron        Electron 主进程、preload、React renderer
apps/webui           浏览器 WebUI
apps/cli             WebSocket CLI 客户端与自包含 run
apps/viewer          只读 session viewer
packages/core        共享基础类型
packages/shared      workspace/session/agent/auth/source/automation 业务逻辑
packages/server-core RPC、handlers、transport、headless bootstrap
packages/server      headless server 入口
packages/pi-agent-server Pi SDK 子进程
packages/session-tools-core 脚本运行时、路径/文件/网络隔离
packages/ui          React UI 组件
```

README 的架构图与实际目录相符（[README architecture](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/README.md#architecture)）。业务逻辑主要在 `packages/shared`，因此 Electron、WebUI、CLI 不是三套 agent 实现。

### 3.2 Electron 桌面模式

Electron 是主要交互入口：renderer 只通过 preload/transport 调用受控 channel；主进程持有会话、agent、凭据、文件和 native browser 能力。`RoutedClient` 对每个 RPC channel 做 `LOCAL_ONLY` / `REMOTE_ELIGIBLE` 分类，并支持本地 workspace 与远程 workspace 客户端切换（[routed-client.ts](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/apps/electron/src/transport/routed-client.ts#L1-L218)，[routing.ts](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/shared/src/protocol/routing.ts#L1-L487)）。

### 3.3 WebUI

Server 可托管 Vite 构建的 WebUI。HTTP 层提供 health、登录、静态文件和 OAuth callback；登录成功后以 cookie JWT 保护 WebUI 和 WebSocket upgrade（[http-server.ts](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/server-core/src/webui/http-server.ts#L180-L394)，[auth.ts](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/server-core/src/webui/auth.ts#L1-L104)）。WebUI 是远程 server 的薄客户端，不是另一套 worker。

### 3.4 Headless server 与远程桌面

`packages/server/src/index.ts` 启动 headless server；`CRAFT_SERVER_TOKEN` 是远程 bearer token，`CRAFT_RPC_HOST/PORT` 控制绑定，TLS 证书变量开启 `wss://`（[server README](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/README.md#remote-server-headless)）。transport server 同时支持本地无认证与远程认证，握手要求协议版本、token/cookie、客户端能力，并返回 clientId、协议版本、可用 channel（[server.ts](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/server-core/src/transport/server.ts#L382-L603)）。

### 3.5 CLI

CLI 是 WebSocket 客户端，支持 `ping/health/versions/workspaces/sessions/send/cancel/invoke/listen`；`run` 可自动启动 server、创建 session、发送 prompt、流式输出并退出；`--validate-server` 执行 21 步生命周期验证，包括 source 与 skill 创建/使用/删除（[docs/cli.md](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/docs/cli.md#run-self-contained)，[validate section](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/docs/cli.md#validate-server)）。

## 4. Agent backend：Claude 与 Pi

### 4.1 统一 backend 合约

`createBackend()` 只注册两个 provider：`anthropic` -> `ClaudeAgent`，`pi` -> `PiAgent`；provider driver 负责连接、模型、凭据和运行时解析（[factory.ts](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/shared/src/agent/backend/factory.ts#L1-L115)）。`BaseAgent` 共享权限、source manager、技能、session recovery、mini completion、spawn_session 和事件契约（[base-agent.ts](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/shared/src/agent/base-agent.ts#L148-L220)）。

连接解析顺序是 session connection -> workspace default -> global default；这说明 workspace 默认模型是配置继承，不是模型检索范围（[factory.ts](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/shared/src/agent/backend/factory.ts#L175-L226)）。

### 4.2 Claude backend

ClaudeAgent 直接使用 `@anthropic-ai/claude-agent-sdk` 的 `query()`。它支持 SDK resume、branch/fork、`resumeSessionAt`、slash `/compact`、流式输入以及跨 turn 保活以承载 background sub-agents（[claude-agent.ts](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/shared/src/agent/claude-agent.ts#L1548-L1680)）。源码显式把 SDK 的 permission mode 设为 `bypassPermissions`，然后在自有 `PreToolUse` hook 中执行权限检查；这使策略集中，但也让 hook 成为关键安全边界（同文件 [L1228-L1577](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/shared/src/agent/claude-agent.ts#L1228-L1577)）。

### 4.3 Pi backend

PiAgent 是主进程的薄客户端，启动 `pi-agent-server` 子进程，通过 stdin/stdout JSONL 传递 init、agent events、tool execution、pre-tool、compact、credential refresh 和错误（[pi-agent.ts](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/shared/src/agent/pi-agent.ts#L153-L190)，[spawn/init](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/shared/src/agent/pi-agent.ts#L410-L610)）。子进程不能直接访问主进程的 MCP pool 和权限状态，所以工具被包装成 proxy；每个调用通过 `pre_tool_use_request` 回主进程审批，主进程返回 allow/block/modify（[pi-agent-server/index.ts](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/pi-agent-server/src/index.ts#L683-L760)）。

Pi provider 适配 OpenAI、OpenRouter、Google、Copilot、Bedrock、Anthropic-compatible 等连接；Claude provider 则保留 Anthropic SDK 原生能力。该拆分是能力与凭据路由的工程边界，不是两个不同产品。

## 5. Workspace、Session、Project 与 Source

### 5.1 Workspace 的真实角色

workspace 默认落在 `~/.craft-agent/workspaces/`，以目录内 `config.json` 保存名称、默认 model/permission、enabled source、主题和配置（[workspaces/storage.ts](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/shared/src/workspaces/storage.ts#L1-L165)）。它还承载 source、skills、statuses、labels、automations 和 projects。workspace 是本地资源组织、配置继承和运行归属边界；远程服务另有连接认证和部分资源 ownership 检查，但源码没有展示 NoteWeave 式的多租户 workspace ACL 模型。它也不等价于“全文检索索引”。

Project 是 workspace 内可选的工作目录绑定；session 可继承 project 的 cwd，也可覆盖（[projects/types.ts](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/shared/src/projects/types.ts#L1-L60)）。

### 5.2 Session 的执行模型

session 文件位于 `{workspaceRoot}/sessions/{id}/session.jsonl`，同目录有 `attachments/`、`plans/`、`data/`、long responses/downloads；header 放元数据，后续每行是消息（[sessions/storage.ts](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/shared/src/sessions/storage.ts#L1-L140)）。session 保存 `sdkSessionId`、workingDirectory、permissionMode、llmConnection、enabledSourceSlugs、labels、branch metadata 等，因此是可恢复执行上下文，而不是单纯聊天记录。

### 5.3 Source 不是 Workspace corpus

Source 类型是 `mcp | api | local`。MCP 支持 HTTP、SSE 和本地 stdio；API 支持 bearer/header/query/basic/oauth/none；local 保存 filesystem/Obsidian/Git/SQLite 等格式提示（[sources/types.ts](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/shared/src/sources/types.ts#L1-L30)，[MCP transport](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/shared/src/sources/types.ts#L240-L280)）。Source 配置保存在 workspace，但只有默认启用或显式 `enabledSourceSlugs`/mention 后才注册到 session 工具。

MCP pool 维护连接、工具定义、配置变化重连和 source slug 标记；远程 HTTP 与本地 stdio 使用不同 transport（[mcp-pool.ts](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/shared/src/mcp/mcp-pool.ts#L101-L120)，[client.ts](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/shared/src/mcp/client.ts#L1-L110)）。因此 Craft 的 workspace 里可以有 source，但 agent 不会自动“搜索 workspace 全库”。

## 6. Skills、Mentions 与动态工具

Skill 是目录下的 `SKILL.md`，YAML frontmatter 提供 name/description/requiredSources 等元数据；模型先被要求读取 skill 文件，再执行技能步骤（[skills/storage.ts](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/shared/src/skills/storage.ts#L39-L126)，[base-agent skill pipeline](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/shared/src/agent/base-agent.ts#L920-L1045)）。requiredSources 可在运行时触发 source prerequisite。

mention 解析支持 `[source:slug]`、`[skill:slug]`/`[skill:workspace:slug]`、`[file:path]`；它们被转换成语义 marker，避免把 mention 当成裸路径或从自然语言中误删（[mentions/index.ts](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/shared/src/mentions/index.ts#L70-L180)）。source mention 的实际 activation 发生在 pre-tool 阶段，可在会话中途激活而无需重启；这是“资源注册延迟到需要时”的设计，而不是 workspace 检索。

## 7. Web Search、Web Fetch 与 Browser

### 7.1 Search provider 链

Pi 的统一工具名始终是 `web_search`，模型不需要知道底层 provider。provider 选择顺序是：OpenAI Responses API、ChatGPT backend（OpenAI OAuth）、OpenRouter、Google native grounding，最后 DuckDuckGo；provider 异常时自动 fallback 到 DDG（[resolve-provider.ts](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/pi-agent-server/src/tools/search/resolve-provider.ts#L1-L107)，[create-search-tool.ts](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/pi-agent-server/src/tools/search/create-search-tool.ts#L1-L124)）。返回只有标题、URL、description snippet，最多 10 条；它不去重跨 provider、不做证据快照、不验证来源。

Claude backend 使用 Claude SDK 自己的 web tools；Pi backend 才在仓库内显式实现 provider routing。不要把两者描述成相同搜索结果或相同网络策略。

### 7.2 web_fetch

Pi 的 `web_fetch` 支持 HTML->Markdown、PDF 文本、JSON pretty print、纯文本和图片落盘；使用 DNS lookup 检查非 HTTP scheme 与私网/保留 IP，限制响应大小并设置 30 秒超时（[web-fetch.ts](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/pi-agent-server/src/tools/web-fetch.ts#L42-L163)，[execute](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/pi-agent-server/src/tools/web-fetch.ts#L338-L410)）。这属于单页读取器，不是网页搜索或研究证据管线。

限制：源码注释自己称 DNS 检查是 defense-in-depth 而非完整 SSRF mitigation；fetch 使用 `redirect: follow`，重定向后的地址没有在本函数中重新做同等解析校验。这是需要外部 egress proxy 或逐跳校验补强的真实边界。

### 7.3 browser_tool

browser_tool 是 session-bound 的可视浏览器控制面，支持 open/navigate/snapshot/click/type/select/scroll/wait/downloads/resize；默认后台打开，只有明确 foreground 才抢焦点（[browser-tools.ts](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/shared/src/agent/browser-tools.ts#L1-L220)，[browser-tool-runtime.ts](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/shared/src/agent/browser-tool-runtime.ts#L680-L840)）。它解决 JS 页面和交互式登录，不能替代抓取快照、引用管理或 verifier。

## 8. 权限、pre-tool 检查与隔离

### 8.1 三种权限模式

产品公开三档：`safe`/Explore（只读）、`ask`/Ask to Edit（危险动作确认）、`allow-all`/Auto（自动允许）。模式按 session 保存，并由统一 PermissionManager 调用 mode-manager、bash read-only 规则、API endpoint allowlist 和 `permissions.json`（[permission-manager.ts](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/shared/src/agent/core/permission-manager.ts#L1-L112)）。`ask` 中危险命令永远不会静默放行；session 级 always-allow command/domain whitelist 会在当前会话生效。

### 8.2 统一 pre-tool pipeline

Claude 在 SDK hook 中、Pi 在子进程握手中调用相同的 pre-tool checks：读取权限模式、阻止/放行/修改输入、激活 source、注入 skill prerequisite、处理 `spawn_session`、计划文件、图片尺寸和工具元数据。该 pipeline 是重要架构资产，但它是应用层 policy gate；`allow-all` 下并不等同于 OS sandbox。

### 8.3 脚本隔离的实际强度

`session-tools-core` 为脚本提供环境变量清洗、路径与 symlink containment、macOS `sandbox-exec`、Linux `bwrap`/`firejail`/`unshare`；不支持的平台会返回 `unavailable`，由调用方决定是否 fail-safe（[sandbox-env.ts](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/session-tools-core/src/runtime/sandbox-env.ts#L1-L78)，[filesystem-isolation.ts](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/session-tools-core/src/runtime/filesystem-isolation.ts#L1-L108)，[network-isolation.ts](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/session-tools-core/src/runtime/network-isolation.ts#L1-L90)）。普通 Bash/Claude SDK 子进程不自动获得这些隔离；权限策略、路径检查和 OS sandbox 是三个不同层次。

## 9. 持久化、远程协议与恢复

### 9.1 JSONL 与原子写

session 使用 header + message lines 的 JSONL；写入采用 `.tmp` 后 rename，Windows 上先删除目标文件，读取时跳过损坏 message line 以保留其他历史（[jsonl.ts](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/shared/src/sessions/jsonl.ts#L77-L165)，[persistence-queue.ts](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/shared/src/sessions/persistence-queue.ts#L51-L165)）。队列会合并外部 metadata 变更，避免 watcher 与本地写入互相覆盖。

### 9.2 Bundle 与迁移

session bundle 把 JSONL、attachments、plans、data、downloads 放进可校验 envelope，并限制总大小；内部 server 字段在导出时剥离（[bundle.ts](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/shared/src/sessions/bundle.ts#L33-L153)）。路径以 portable token 保存，允许 session 在机器/目录间迁移，但凭据不随 bundle 导出。

### 9.3 WebSocket RPC

协议版本当前为 `1.0`；握手超时 5 秒，主版本不兼容直接拒绝；每个 request 有 channel/id，server 有 handler timeout、client capability、push event、重连序号和 heartbeat（[protocol/types.ts](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/shared/src/protocol/types.ts#L31-L149)，[server.ts](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/server-core/src/transport/server.ts#L382-L844)）。这使 Electron、WebUI、CLI 可以共享 handler，但同时要求 channel routing exhaustiveness test。

## 10. Background tasks、spawn session 与 automations

### 10.1 spawn_session

`spawn_session` 是 session-scoped tool，支持 help 模式查询 connections/models/sources；实际模式创建独立 session，继承父 session 或 workspace defaults，也可覆盖 model、connection、permission、thinking、sources、labels、workingDirectory、project 和附件，然后 fire-and-forget（[spawn-session-tool.ts](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/shared/src/agent/spawn-session-tool.ts#L1-L110)）。它是“并行会话编排”，不是同一上下文内的轻量 function call。

### 10.2 SDK background tasks

Claude SDK 的 Agent/Task 与 Bash 后台 shell 被主进程 registry 追踪，事件包含 task/shell id、intent、turn id、terminal/orphaned 状态；当 turn 结束且没有 keep-alive 时，后台任务可能被终止，系统提示明确要求通过 `list_background_tasks` 查询而不是猜测（[tool-matching.ts](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/shared/src/agent/tool-matching.ts#L408-L510)，[system prompt](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/shared/src/prompts/system.ts#L970-L990)）。这是可用性能力，同时也是必须显式暴露 orphaned 语义的复杂性。

### 10.3 TaskRunner 与 automations

TaskRunner 把任务 DAG 的每个节点派生为 child session；子节点继承 orchestrator cwd，可指定 model、connection 和 source（[TaskRunner.ts](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/server-core/src/tasks/TaskRunner.ts#L340-L395)）。

AutomationSystem 是 workspace 级事件总线：监听 LabelAdd/Remove、FlagChange、SessionStatusChange、PermissionModeChange、SchedulerTick，以及 PreToolUse/PostToolUse/UserPromptSubmit/SessionStart/SessionEnd/Stop 等 agent 事件；matcher 可带 regex、5-field cron 和 AND/OR/NOT 条件，action 目前主要是 prompt 或 webhook（[automations/types.ts](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/shared/src/automations/types.ts#L1-L232)，[automation-system.ts](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/shared/src/automations/automation-system.ts#L40-L293)）。Scheduler 每分钟对齐边界并跳过仍在执行的 tick，避免重入（[scheduler-service.ts](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/shared/src/scheduler/scheduler-service.ts#L1-L86)）。

## 11. 凭据、安全与信任边界

凭据默认存储于 `~/.craft-agent/credentials.enc`，AES-256-GCM、随机 IV、PBKDF2 派生稳定机器密钥；支持 legacy key migration、过期检查、OAuth refresh 和 health check（[secure-storage.ts](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/shared/src/credentials/backends/secure-storage.ts#L1-L30)，[key derivation](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/shared/src/credentials/backends/secure-storage.ts#L259-L360)）。source credentials 按 workspace/source 维度寻址；stdio source 不需要远程 credential，API/MCP OAuth 支持按请求刷新。

安全强项：敏感环境变量会从脚本子进程清除；API endpoint 有方法/路径检查；MCP/API 凭据不写入 session bundle；远程连接可用 TLS、bearer token 或 WebUI cookie JWT；权限请求默认拒绝没有 handler 的场景。

安全限制：AES 文件密钥与本机身份绑定，不是多租户 KMS；`allow-all` 与普通 Bash 仍是高信任模式；Pi 子进程的安全性依赖主进程 pre-tool round-trip；Web fetch 的 redirect/逐跳 SSRF 校验需要外部代理补强；stdio MCP 是本机任意子进程，source 配置本身就是执行权限。

## 12. 测试、构建、部署与成熟度

仓库包含约 360 个测试文件，主要集中在 shared（158）、Electron（84）、UI（33）、server-core（28）、messaging（25）和 session-tools（15）；server lifecycle、协议握手、权限、MCP validation、凭据、session persistence、automations、TaskRunner 均有测试。CI 有 `validate.yml` 与跨平台 `validate-server.yml`；README/`package.json` 提供 `typecheck:all`、`bun test`、Electron build/dist、server build、Docker 和 CLI 21-step validation（[package.json](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/package.json#L23-L101)，[validate-server workflow](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/.github/workflows/validate-server.yml#L1-L80)）。

成熟度判断：这是一个功能面很宽、测试数量可观、仍快速迭代的 0.x 项目。证据包括版本高频发布、单一 OSS snapshot、后台任务生命周期注释中的已知 race、跨平台 sandbox backend 的 unavailable 分支、以及仍在兼容 legacy credential/session 字段。它适合借鉴架构和工程模式，不宜直接当成高保证研究执行底座或安全沙箱。

### 12.1 已确认限制与开放问题

- Craft 是通用 agent 工作界面，不是 Deep Research runtime；没有 evidence ledger、claim-citation 对齐、来源覆盖率、矛盾消解和报告级 verifier。
- Workspace 主要是本地资源与配置边界；远程认证和局部 ownership 检查不构成通用多租户 Workspace ACL。
- `web_fetch` 已做 SSRF 检查，但源码明确保留 DNS 到 fetch 的 TOCTOU 与 redirect 逐跳校验限制；不能直接作为高信任抓取边界。
- pre-tool permission 是应用层策略门；普通 Bash/SDK 工具执行不自动获得 `session-tools-core` 的 OS sandbox。
- Pi 的 Web search 路由依赖模型/provider 能力并以 DuckDuckGo 兜底，Claude 则走 Claude SDK 工具；两条 backend 的搜索语义、可观测性和结果契约并不完全一致。
- 固定 OSS 快照的本地 Git 历史只有一次提交。项目的长期演化、真实多用户负载、故障恢复 SLO 和各 provider 的线上质量，需要部署压测或上游材料才能进一步确认。

## 13. 对 NoteWeave 的借鉴、不可照搬与升级建议

### 13.0 NoteWeave 当前检索基线

当前实现不是“独立 Python worker，所以天然只搜外网”。Java 在创建 Research Run 时硬性要求至少一个 ready workspace source（[ResearchRunService.java](../../backend/src/main/java/com/noteweave/research/ResearchRunService.java#L97-L101)）；Python 的默认 adapter 在没有外部 provider key 时只返回 `WorkspaceSearchAdapter`，有 key 时才组合 Workspace 与一个或多个外部 adapter（[search_adapters.py](../../workers/research-worker/app/search_adapters.py#L386-L391)，[provider 构造](../../workers/research-worker/app/search_adapters.py#L784-L826)）。因此当前实际语义是：无 key 时 Workspace-only，有 key 时 Workspace + Web；worker 的进程独立性没有让检索范围与 Workspace 自动解耦。

这也是 Craft 对 NoteWeave 最直接的校正价值：`workspace_id` 可以继续承担租户、ACL、配额和 run/artifact 归属，但检索模式与 source activation 应成为显式运行输入，不能由“任务位于某个 Workspace”隐式决定。

### 13.1 值得借鉴

| Craft 设计 | 对 NoteWeave 的启发 | 建议落点 |
|---|---|---|
| workspace/session/project 分层 | 归属、权限、工作目录、执行上下文分开 | Java coordinator 的 run/task snapshot；Python worker 只接 run contract |
| backend driver + BaseAgent | provider-specific runtime 与统一生命周期分离 | 将 Search/Fetch provider 置于 adapter registry，不污染 research state machine |
| Pi JSONL pre-tool handshake | 外部 worker 不绕过主协调器策略 | worker 每个外部搜索/抓取动作先过 coordinator policy |
| source 按需激活与 mention | source 是 optional seed/tool，不是隐式全库 | `WEB_ONLY` 默认；显式 seed 只在任务输入中出现 |
| provider-native search + DDG fallback | 搜索 provider chain、能力探测、故障降级 | OpenSERP/Serper/DDG 按质量和预算路由 |
| session JSONL 原子持久化 | checkpoint/resume 需抗崩溃和 watcher 竞争 | 保留当前 checkpoint，增加 atomic snapshot 与 schema version |
| background task registry/orphaned | 并发研究必须可查询真实状态 | worker lease、task status、orphaned/retry 明示到 UI/report |
| CLI self-contained validation | 端到端 contract test 很有价值 | 增加 Research Run 21-step smoke：search/fetch/evidence/verifier/abort/resume |
| encrypted credential manager | 凭据不进入 prompt、evidence 或 bundle | provider keys 与 research artifacts 分离存储 |

### 13.2 不可照搬

- 不要把 Craft 的通用聊天 loop 当成 Research Planner；它没有 source coverage、evidence ledger、citation verifier 或 contradiction protocol。
- 不要把 `web_search` snippets 当证据；必须保存 URL、抓取快照、时间、抽取片段和 provenance。
- 不要把 workspace 级 source 默认值误当作检索范围；NoteWeave 的 `workspace_id` 只应做项目归属/ACL，`source_scope` 应是可选 seed。
- 不要直接采用 `allow-all` 或未经逐跳 SSRF 校验的 `web_fetch`；研究 worker 应默认 deny、固定 egress、审计每次请求。
- 不要把 spawn_session 的 fire-and-forget 当成可靠 DAG；研究任务需要 lease、幂等、原子 merge、取消传播和 fencing。
- 不要复制依赖 Claude SDK 私有 session 文件的 resume 语义；NoteWeave 应以自己的 checkpoint/schema 为权威状态。

### 13.3 推荐升级路径

**P0：先修正职责边界。** Research Run 不再强制 Workspace source；建立 `WEB_ONLY`、`WEB_PLUS_SEEDS`、`SOURCES_ONLY` 三种显式模式。workspace 只负责 ACL、quota、run/artifact 归属；Python worker 的搜索是外部 Web provider，seed source 可选。

**P0：引入 provider registry。** 定义 `SearchProvider`、`FetchProvider`、`BrowserProvider` 能力接口，支持 OpenSERP、Serper、DDG、直接 HTTP、浏览器；provider 选择、重试、熔断、预算和降级由 coordinator 管理，不由模型自行决定。

**P1：把 Craft 的 pre-tool 思想移植到研究动作。** 每次 `search/query/fetch/browser` 前执行 SSRF、域名/robots、成本、速率、租约和 prompt-injection 检查；返回 allow/block/modify 与可审计原因。

**P1：把“source activation”改造成“evidence source admission”。** 搜索结果只是 candidate；Fetch 后生成 immutable snapshot，再进入 evidence ledger；跨 provider agreement 只能是排序特征，不能替代 source quality/verifier。

**P1：可靠的并发子任务。** 借鉴 spawn session 的显式 parent/child、继承与覆盖字段，但实现 lease/fencing/idempotency/cancellation；任何 orphaned task 都必须在 checkpoint 和报告中可见。

**P1：协议与持久化。** worker 使用版本化 JSONL/RPC contract；每个 request 有 id、deadline、attempt、run generation；checkpoint 用 tmp+rename，保存 provider response、snapshot hash、evidence IDs 和 verifier state。

**P2：浏览器与安全执行。** 对 JS-heavy 页面提供 session-bound browser；对脚本/解析器采用 Linux/macOS/Windows 明确能力矩阵，unsupported 时 fail-closed；web fetch 使用逐跳 DNS/IP revalidation 或统一 egress proxy。

**P2：工程验证。** 增加跨平台 server smoke、provider fallback contract、SSRF redirect corpus、source scope isolation、checkpoint crash recovery、citation completeness、verifier disagreement 和 budget exhaustion 测试。

## 14. 最终判断

Craft Agents 最值得 NoteWeave 学习的不是某一个 `web_search` provider，而是把“组织归属、会话执行、模型 backend、工具权限、外部 source、持久化和远程协议”拆成可组合层。它证明 workspace 可以是执行与治理容器，而不必是搜索语料库；同时也提醒我们，通用 agent 的工具成功不等于研究结论可信。

NoteWeave 应吸收 Craft 的边界、provider registry、pre-tool admission、session/child orchestration、原子 checkpoint 和 contract tests，再叠加自身的 Research Planner、evidence ledger、local/global verifier、citation/provenance 和 counterfactual recheck。这会形成一个真正独立于 Workspace 的外部 Research Agent，而不是把 workspace source 误当成 search agent 的数据源。

## 15. 一手来源索引

- 官方仓库与许可证：[GitHub repository](https://github.com/craft-ai-agents/craft-agents-oss/tree/4289b16097322e9911d3078d8a64bd8c830717c3)
- 产品定位、功能、安装、远程/CLI：[README](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/README.md)
- backend 工厂：[factory.ts](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/shared/src/agent/backend/factory.ts)
- Claude backend：[claude-agent.ts](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/shared/src/agent/claude-agent.ts)
- Pi backend 与 server：[pi-agent.ts](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/shared/src/agent/pi-agent.ts)，[pi-agent-server/index.ts](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/pi-agent-server/src/index.ts)
- workspace/session/source：[workspaces/storage.ts](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/shared/src/workspaces/storage.ts)，[sessions/storage.ts](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/shared/src/sessions/storage.ts)，[sources/types.ts](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/shared/src/sources/types.ts)
- search/fetch：[resolve-provider.ts](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/pi-agent-server/src/tools/search/resolve-provider.ts)，[web-fetch.ts](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/pi-agent-server/src/tools/web-fetch.ts)
- 权限/隔离：[permission-manager.ts](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/shared/src/agent/core/permission-manager.ts)，[pre-tool hook](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/shared/src/agent/claude-agent.ts#L1228-L1577)，[runtime isolation](https://github.com/craft-ai-agents/craft-agents-oss/tree/4289b16097322e9911d3078d8a64bd8c830717c3/packages/session-tools-core/src/runtime)
- 持久化/协议/自动化：[jsonl.ts](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/shared/src/sessions/jsonl.ts)，[transport/server.ts](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/server-core/src/transport/server.ts)，[automations/types.ts](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/packages/shared/src/automations/types.ts)
- 安全与工程：[SECURITY.md](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/SECURITY.md)，[CONTRIBUTING.md](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/CONTRIBUTING.md)，[package.json](https://github.com/craft-ai-agents/craft-agents-oss/blob/4289b16097322e9911d3078d8a64bd8c830717c3/package.json)
