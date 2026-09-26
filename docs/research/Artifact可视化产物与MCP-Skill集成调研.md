# Artifact 可视化产物与 MCP、Skill 集成调研

> 调研快照：2026-08-24。本文只引用项目官方仓库、官方文档与 MCP 协议规范。版本与维护状态会变化，进入实现前必须锁定版本并重新核对安全公告。

## 1. 结论先行

NoteWeave 最值得新增的不是一个可以执行任意工具的“产物市场”，而是六类有稳定输入输出契约的系统 Skill：思维导图、文本图表、数据图表、演示文稿、白板画布和交互式报告。它们继续复用现有的 Input Snapshot、Skill Graph、Verifier、Artifact Version、File Manifest、Digest 和 Writeback，不另建一套没有版本治理的文件生成链路。

优先级建议如下：

| 优先级 | 能力 | 首选实现 | 原因 |
| --- | --- | --- | --- |
| Now | 思维导图 | Markmap 直接库，社区 Markmap MCP 只作适配参考 | Markdown 是天然可审阅真源，React 可直接渲染交互 SVG，CLI 可生成离线 HTML，接入成本最低 |
| Now | 流程图、时序图、架构图 | Mermaid 浏览器库 + Mermaid CLI | 文本契约成熟，覆盖 20 多种图，前端预览和 Worker 静态导出可以共用同一源文件 |
| Now | 数据图表 | Vega-Lite JSON + `vl-convert-python` | JSON Schema 可验证，前端可交互，Python Worker 可离线生成 SVG、PNG、PDF 和 HTML |
| Next | 演示文稿 | Marp CLI | Markdown 到 HTML、PDF、PPTX、图片的本地确定性流水线成熟，官方 Docker 镜像可隔离浏览器依赖 |
| Next | 白板、画布 | Excalidraw React 组件 | 与现有 React 前端直接适配，开放 `.excalidraw` JSON，可导出 PNG、SVG，支持明暗主题 |
| Later | 交互式数据故事 | Observable Framework | 能把 Markdown、JavaScript 与 Python/Java 数据加载器构建成静态站点，但构建和脚本安全面明显更大 |
| Later | 高级可编辑 PPTX | 隔离部署 Presenton 做受控 Provider | 有本地 Docker、HTTP API 和 MCP，但它自身包含模型、密钥、用户、存储和编辑控制面，与 NoteWeave 重叠较多 |
| Later | 多图形引擎统一服务 | Kroki 安全模式 + 允许列表 | 一个 API 可覆盖 Mermaid、Vega、GraphViz、PlantUML 等，但多引擎显著扩大供应链和执行面 |

核心技术判断：前三项不需要先引入第三方 MCP Server。直接使用官方可编程库或 CLI，可以减少协议兼容、路径治理和文件归属问题。MCP 更适合作为 NoteWeave 内部的系统 Provider 边界，而不是让用户或模型注册任意 Server。

## 2. 与当前 NoteWeave 架构的适配结论

### 2.1 当前 MCP Runtime 是受限适配器，不是通用 MCP Client

当前 `workers/artifact-worker/app/custom_mcp_executor.py` 固定使用 stdio，直接发送 `initialize` 和 `tools/call`，然后只接受：

1. `structuredContent` 为 JSON object；或
2. 第一段 TextContent 能解析成 JSON object。

它暂不处理 ImageContent、ResourceLink、EmbeddedResource、多段内容，也不先执行 `tools/list` 与输出 Schema 校验。MCP 官方规范允许工具返回文本、图片、资源链接、嵌入资源和结构化内容，并要求客户端在提供 `outputSchema` 时验证结果；stdio 规范还定义了由客户端启动子进程、逐行 JSON-RPC、stdout 不得混入日志等约束。[MCP Tools 规范](https://modelcontextprotocol.io/specification/2025-06-18/server/tools) [MCP Transport 规范](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports)

因此，不能假设网上任意 MCP Server 都能直接接入当前 Worker。最稳妥的生产契约是由系统托管的薄适配器统一返回：

```json
{
  "artifact_kind": "MINDMAP",
  "source_format": "text/markdown",
  "source_path": "run-relative/mindmap.md",
  "files": [
    {
      "role": "INTERACTIVE_PREVIEW",
      "path": "run-relative/mindmap.html",
      "mime_type": "text/html"
    },
    {
      "role": "STATIC_PREVIEW",
      "path": "run-relative/mindmap.svg",
      "mime_type": "image/svg+xml"
    }
  ],
  "renderer": {"key": "markmap", "version": "pinned-version"}
}
```

路径必须是 Run 专属 Sandbox 内的相对路径。Worker 验证文件存在、大小、MIME 和 Digest 后交给 Java 控制面提交 Artifact File，不能把第三方 Server 返回的绝对路径直接当成业务真源。

### 2.2 推荐的分层

```text
Java / Spring 控制面
  -> 冻结 Workspace ACL、Input Snapshot、Skill 与版本
  -> 创建 Artifact Job / Run / Task / Outbox

Python Artifact Worker
  -> 生成并验证规范化 Artifact IR
  -> 调用固定版本 Renderer 或系统 MCP Provider
  -> 校验文件、摘要、来源覆盖和渲染缺陷

React 前端
  -> 直接渲染受支持的 IR
  -> 在沙箱 iframe 中展示交互 HTML
  -> 下载静态派生文件或编辑开放格式
```

每类产物都应保存“可编辑真源 + 派生交付物”，而不是只保存截图：

| Artifact Type | 可编辑真源 | 派生文件 |
| --- | --- | --- |
| `MINDMAP` | Markmap Markdown 或规范化 Tree JSON | HTML、SVG、PNG |
| `DIAGRAM` | Mermaid `.mmd` | SVG、PNG、PDF |
| `CHART` | Vega-Lite JSON + 冻结数据快照 | HTML、SVG、PNG、PDF |
| `SLIDE_DECK` | Marp Markdown + Theme CSS + Asset Manifest | HTML、PDF、PPTX、逐页 PNG |
| `WHITEBOARD` | `.excalidraw` JSON + Asset Manifest | SVG、PNG |
| `INTERACTIVE_REPORT` | 页面源码、组件与冻结数据 Bundle | 静态站点 Bundle、缩略图、PDF 快照 |

## 3. 候选逐项核实

### 3.1 Markmap：思维导图首选

- 许可证：MIT。[官方仓库](https://github.com/markmap/markmap)
- 维护状态：仓库未归档，2026-06-21 仍有提交；官方组织页面显示持续更新。GitHub Release 页的最新正式 Release 仍是 `v0.18.0`，但仓库和包版本继续演进，因此实现时应锁定实际 npm 版本，而不是只看 GitHub Release。[官方 Releases](https://github.com/markmap/markmap/releases)
- 输入：Markdown，经 `markmap-lib` 的 Transformer 转成树数据。[markmap-lib 官方 API](https://markmap.js.org/api/modules/markmap-lib.html)
- 输出：`markmap-view` 将树数据渲染到 SVG；`markmap-cli` 可从 Markdown 生成 HTML，并支持 `--offline` 内联资源。[markmap-view 文档](https://markmap.js.org/docs/packages--markmap-view) [markmap-cli 文档](https://markmap.js.org/docs/packages--markmap-cli)
- 本地部署：纯 npm 包或本地 CLI，不需要云 API。
- NoteWeave 适配：React 端用 `markmap-lib` + `markmap-view` 展示缩放、折叠和节点交互；Worker 用同一 Markdown 真源生成离线 HTML 与 SVG/PNG。主题颜色由 NoteWeave Token 映射，不能固定黑底。

建议新增 `mindmap_from_workspace` Skill。生成阶段先输出受 Schema 约束的树，再由确定性 Composer 写成 Markmap Markdown。Verifier 至少检查单根节点、最大分支数、最大深度、孤立节点、节点文本长度、来源引用覆盖和渲染后边界。

#### 可借鉴的 MCP 与 Skill

[`jinzcdev/markmap-mcp-server`](https://github.com/jinzcdev/markmap-mcp-server) 是可运行的 MIT 项目，`v0.2.0` 于 2026-08-04 发布。它以 Node.js 20 + MCP TypeScript SDK + Markmap 运行，支持 Markdown 或本地文件输入，输出交互 HTML、PNG、JPG、SVG，支持 stdio、Docker、离线 HTML和 path/content/both 返回模式。[官方 README](https://github.com/jinzcdev/markmap-mcp-server#readme) [官方 Releases](https://github.com/jinzcdev/markmap-mcp-server/releases)

它适合做 PoC 和系统适配器参考，不建议原样接管 NoteWeave 文件生命周期：它自带输出目录、覆盖同名文件、列表和清理工具，而 NoteWeave 已有 Artifact Version、File Manifest 和对象存储真源。生产接入时只暴露 `markdown_to_mindmap`，禁用 `cleanup_mindmaps`、自动打开浏览器和任意 `inputPath`，并强制输出到 Run Sandbox。

[`galiacheng/mindmap-skills`](https://github.com/galiacheng/mindmap-skills) 是 2026 年发布的 MIT Skill 项目，输入文件、URL、粘贴文本或主题，输出 Markmap Markdown，可选用 CLI 生成独立 HTML。其 `4-7` 个主分支、`3-4` 层深度、短语化节点，以及 source fidelity、leaf legibility、visual balance 等评分维度，适合转成 NoteWeave Skill Prompt 和 Validator Rubric。[官方 README 与输出契约](https://github.com/galiacheng/mindmap-skills#readme)

不建议照搬它的 `3 proposers + 3 judges + 1 synthesizer` 默认流程。该项目自己也说明此模式约消耗 7 个 Agent，成本较高。NoteWeave 应先做单次结构生成 + 确定性校验 + 最多一次局部 Repair，只有高价值长文档显式选择“精细规划”时才启用多候选评审。

### 3.2 Mermaid 与 Mermaid CLI：文本图表首选

- 许可证：Mermaid 与 Mermaid CLI 均为 MIT。[Mermaid 官方仓库](https://github.com/mermaid-js/mermaid) [Mermaid CLI 官方仓库](https://github.com/mermaid-js/mermaid-cli)
- 维护状态：Mermaid 仓库在 2026-08-21 仍有提交；Mermaid CLI `11.16.0` 于 2026-06-29 发布，属于高活跃项目。[Mermaid CLI Releases](https://github.com/mermaid-js/mermaid-cli/releases)
- 输入：Markdown 风格的图定义，覆盖流程图、时序图、类图、状态图、ER、Gantt、Git Graph、Mindmap、Timeline、Sankey 等 20 多种类型。[官方 README](https://github.com/mermaid-js/mermaid#readme)
- 输出：浏览器端生成 SVG；CLI 可本地生成 SVG、PNG、PDF，并提供官方容器和 Node API。[Mermaid CLI README](https://github.com/mermaid-js/mermaid-cli#readme)
- 本地部署：npm、Node API 或官方容器，不依赖云服务。
- NoteWeave 适配：前端用 Mermaid 做即时预览，Worker 用 CLI 固化 SVG/PNG/PDF。规范化 `.mmd` 是真源，静态图只是派生文件。

Mermaid 自带 Mindmap，但官方文档仍将其标记为实验图类型，虽然语法除图标集成外已较稳定。因此 Now 阶段让 Markmap 负责思维导图，Mermaid 负责流程、时序、状态和架构图，避免两个思维导图 IR 同时成为真源。[Mermaid Mindmap 文档](https://mermaid.js.org/syntax/mindmap.html)

社区 [`peng-shawn/mermaid-mcp-server`](https://github.com/peng-shawn/mermaid-mcp-server) 可以把 Mermaid 代码渲染成 PNG/SVG，但最新 Release 为 2025-03，仓库最后推送为 2025-06，维护活跃度明显低于官方 CLI。它没有提供足以抵消额外 MCP 兼容成本的独有能力，因此不进入推荐清单。

### 3.3 Vega-Lite + VlConvert：数据图表首选

- 许可证：Vega-Lite 与 VlConvert 都是 BSD-3-Clause。[Vega-Lite 官方仓库](https://github.com/vega/vega-lite) [VlConvert 官方仓库](https://github.com/vega/vl-convert)
- 维护状态：Vega-Lite `v6.4.3` 于 2026-04-24 发布，仓库 2026-08-19 仍有提交；VlConvert `v1.9.0` 于 2026-01-20 发布，2026-08 仍有提交。[Vega-Lite Changelog](https://github.com/vega/vega-lite/blob/main/CHANGELOG.md) [VlConvert Releases](https://github.com/vega/vl-convert/releases)
- 输入：声明式 Vega-Lite JSON Spec，可用官方 JSON Schema 和 TypeScript 类型验证；编译后得到 Vega Spec。[Vega-Lite Overview](https://vega.github.io/vega-lite/docs/) [TypeScript 用法](https://vega.github.io/vega-lite/usage/typescript.html)
- 输出：浏览器端交互图；VlConvert 的 Rust CLI 与 Python 包可输出 SVG、PNG、JPEG、PDF、HTML、Vega Spec 和 Scenegraph。[VlConvert README](https://github.com/vega/vl-convert#readme)
- 本地部署：`vl-convert-python` 将多版本 Vega/Vega-Lite JavaScript 内联在 Rust 库中，官方说明无需网络，是与现有 Python Worker 最匹配的候选。
- NoteWeave 适配：LLM 只产生小型、受限的 Vega-Lite JSON，不产生任意 JavaScript。数据只能引用 Input Snapshot 中冻结的内联 dataset 或受控 Artifact File，禁止运行时任意 URL。Worker 用 `vl-convert-python` 导出静态文件，React 用 Vega Embed 或官方运行时渲染交互版本。

建议新增 `chart_from_dataset` Skill。输入至少包含 dataset file id、字段语义、用户问题、目标图类型或自动建议标志；输出包含 Spec、数据快照摘要和自然语言解读。Verifier 检查 JSON Schema、字段存在、聚合是否合法、数据点上限、色彩对比、轴标题、空值处理、敏感字段和静态导出可打开性。

#### ECharts 的位置

Apache ECharts 是 Apache-2.0 的纯 JavaScript 交互图表库，支持 Canvas 与 SVG，官方也提供服务端 SVG/Canvas 渲染方案；`6.1.0` 于 2026-05-19 发布，2026-08 仍有更新。[官方仓库](https://github.com/apache/echarts) [官方 SSR 文档](https://echarts.apache.org/handbook/en/how-to/cross-platform/server/)

ECharts 更适合高度定制的产品内仪表盘，但 `option` 可包含函数和复杂运行时配置，不如 Vega-Lite JSON 适合作为由模型生成、长期版本化和 Schema 校验的 Artifact IR。建议 Next 阶段只把它作为固定模板的前端 Renderer，不开放“生成任意 ECharts 代码”的 Skill。

### 3.4 Marp CLI：演示文稿首选起点

- 许可证：MIT。[官方仓库](https://github.com/marp-team/marp-cli)
- 维护状态：`v4.5.0` 于 2026-07-17 发布，2026-07-20 仍有提交。[官方 Releases](https://github.com/marp-team/marp-cli/releases)
- 输入：Marp / Marpit Markdown、Theme CSS、图片等资产。
- 输出：静态 HTML、PDF、PPTX、单张或多张 PNG/JPEG、演讲者备注。[官方 README](https://github.com/marp-team/marp-cli#readme)
- 本地部署：Node.js 18+；PDF、PPTX 和图片需要 Chrome、Edge 或 Firefox。官方提供包含浏览器的 Docker 镜像和跨平台独立二进制。[官方 Dockerfile](https://github.com/marp-team/marp-cli/blob/main/Dockerfile)
- NoteWeave 适配：Worker 容器执行固定版本 Marp CLI，React 预览 HTML 或逐页图片。Skill 先生成 Outline，用户确认后再生成 Deck，复用现有 Artifact 版本比较和再生成。

关键边界：Marp 官方说明普通 PPTX 主要由预渲染背景图组成，内容不能在 PowerPoint 中编辑；`--pptx-editable` 仍是实验功能，需要 LibreOffice，复杂主题可能失败，而且视觉还原度更低。因此首版产品文案只能写“可打开、可演示的 PPTX”，不能写“完全可编辑 PPTX”。[Marp PPTX 官方说明](https://github.com/marp-team/marp-cli#convert-to-powerpoint-document---pptx-)

建议新增 `slide_deck` Skill，分 `OUTLINE_DRAFT -> OUTLINE_APPROVED -> DECK_RENDERED -> VISUAL_VERIFIED`。Verifier 除文件可打开外，还要渲染逐页图片检查溢出、碰撞、空白页、字体缺失、图片损坏、引用脚注和页数约束。

社区 [`masaki39/marp-mcp`](https://github.com/masaki39/marp-mcp) 能通过结构化工具创建和编辑 Marp Deck，并调用 Marp CLI 导出 HTML、PDF、PPTX；项目 MIT，`v1.7.0` 于 2026-06-08 发布。但它的核心能力仍来自 Marp CLI。NoteWeave 已有 Skill Graph 和文件版本控制，优先直接封装官方 CLI，避免重复一层很小的社区 MCP。

### 3.5 Excalidraw：白板和画布首选

- 许可证：MIT。[官方仓库](https://github.com/excalidraw/excalidraw)
- 维护状态：`v0.18.1` 于 2026-04-21 发布，仓库 2026-08-22 仍有提交，维护活跃。[官方 Releases](https://github.com/excalidraw/excalidraw/releases)
- 输入：开放的 `.excalidraw` JSON Scene 和图片资产。
- 输出：可继续编辑的 Scene JSON，以及 PNG、SVG、Clipboard 导出。官方 README 明确支持无限画布、暗色模式、图片、形状库、箭头绑定、撤销重做、缩放与平移。[官方 README](https://github.com/excalidraw/excalidraw#readme)
- 本地部署：`@excalidraw/excalidraw` 是可直接嵌入 React 的组件；字体可以复制到本地静态目录并设置 `EXCALIDRAW_ASSET_PATH`，无需依赖官方 CDN。[React 包 README](https://github.com/excalidraw/excalidraw/blob/master/packages/excalidraw/README.md)
- NoteWeave 适配：前端负责交互编辑，Java 控制面保存 Scene Revision，Worker 只做初始 Scene 生成、静态导出和校验。模型不能直接输出任意 SVG/HTML，应该输出受约束的白板 IR，再由 Composer 生成 Excalidraw Elements。

建议 Next 阶段先做“从 Note、Wiki、Research 报告生成可编辑白板”，不先做多人实时协作。多人协作、端到端加密和冲突合并属于另一个领域，不应混入第一版 Artifact Version。

社区 Excalidraw MCP 项目数量不少，但成熟度和 Scene 契约不统一。现阶段直接使用官方 React 组件比再接一个 MCP Server 更稳。

### 3.6 Observable Framework：交互式报告候选

- 许可证：ISC。[官方仓库](https://github.com/observablehq/framework)
- 维护状态：`v1.13.4` 于 2026-03-02 发布，仓库 2026-05-15 仍有提交。[官方 Releases](https://github.com/observablehq/framework/releases)
- 输入：Markdown 页面、JavaScript 组件、静态数据与数据加载器。
- 输出：可部署到任意静态服务器的 `dist` 站点。
- 本地部署：Node.js 18+。数据加载器可用 JavaScript、TypeScript、Python、R、Java 或任意可执行程序，并在构建时输出 CSV、JSON、PNG 等静态快照。[官方 Data Loaders 文档](https://observablehq.com/framework/data-loaders) [官方 Getting Started](https://observablehq.com/framework/getting-started)
- NoteWeave 适配：适合作为“交互式研究报告”独立 Artifact Bundle，复用 Python 或 Java 数据处理。但不能把生成的 JavaScript 直接放进 NoteWeave 主站同源执行，必须在无凭据、无主站 Cookie、严格 CSP 的独立域或 sandbox iframe 中展示。

因为它允许多语言 Loader 和客户端 JavaScript，安全、构建、缓存和 Bundle 验证成本都明显高于前三项，应放 Later。

### 3.7 Presenton：高级演示文稿 Provider 候选

- 许可证：Apache-2.0。[官方仓库](https://github.com/presenton/presenton)
- 维护状态：`electron-v0.9.7-beta` 于 2026-08-18 发布，仓库 2026-08-20 仍有提交，活跃度高。
- 输入：Prompt、上传文档、自定义 slide markdown、模板、语言、页数和多种模型配置。
- 输出：PPTX、PDF、presentation id、文件 path 和 Web 编辑 path；官方宣称 PPTX 可编辑。[官方 README 与 API 示例](https://github.com/presenton/presenton#readme)
- 本地部署：官方 Docker 镜像、Docker Compose 或 Electron；后端包含 FastAPI，提供生成 API 与带 Bearer Key 的 HTTP MCP Endpoint。
- NoteWeave 适配：可以作为隔离的外部 Provider，通过固定模板、固定模型和固定网络策略生成高级 Deck，再把文件导入 NoteWeave Artifact Manifest。

不建议 Now 阶段把它合并进主栈。Presenton 自带用户、API Key、模型选择、文件上传、Mem0、编辑 UI、搜索与 MCP 鉴权，和 NoteWeave 的 ACL、Provider、Memory、Source、Artifact 版本职责重叠。若进入试验，使用独立容器和服务账号，不向它传递 NoteWeave 用户 Token，只传已经冻结并脱敏的 Input Snapshot；将返回的绝对 path 当成未验证载荷重新摄取。

### 3.8 Kroki：统一图形渲染服务候选

- 许可证：MIT。[官方仓库](https://github.com/yuzutech/kroki)
- 维护状态：`v0.32.1` 于 2026-08-12 发布，仓库 2026-08-23 仍有提交，活跃度高。[官方 Releases](https://github.com/yuzutech/kroki/releases)
- 输入：POST JSON 或纯文本，声明 diagram source、diagram type 和 output format。
- 输出：SVG、PNG，部分引擎支持 PDF。
- 本地部署：官方 Docker 镜像与 Compose；一个 API 可覆盖 Mermaid、Vega、Vega-Lite、GraphViz、PlantUML、D2、Excalidraw 等多种引擎。[官方 README](https://github.com/yuzutech/kroki#readme)
- NoteWeave 适配：当图形类型扩展到 Mermaid 之外时，它可以减少多个 CLI 的进程管理，但当前 Worker MCP 只支持 stdio，需要增加受控 HTTP Provider 或在前面加一个系统 MCP Adapter。

安全上不能默认开启全部引擎。Kroki 2026 年 Release 连续修复 TikZ 任意命令、BPMN HTML/JavaScript、Mermaid 外部 URL、任意文件读取和 Chromium 资源耗尽等问题。若采用，必须锁定已修复版本、开启安全模式、禁止 TikZ/diagrams.net 等非必要引擎、关闭外网、限制并发与内存，并用 diagram type 允许列表发布。[Kroki Security Releases](https://github.com/yuzutech/kroki/releases)

## 4. Now / Next / Later 交付清单

### Now：先形成三个可用闭环

1. `mindmap_from_workspace`
   - 真源：Markmap Markdown + Source Reference Map。
   - 文件：离线 HTML、SVG、PNG。
   - 前端：交互折叠、缩放、搜索、适配浅色与深色、导出。
   - 验证：结构深度、分支数、来源覆盖、超长节点、渲染边界。

2. `diagram_from_evidence`
   - 真源：Mermaid `.mmd`。
   - 首批子类型：Flowchart、Sequence、State、ER、Architecture。
   - 文件：SVG 为主、PNG/PDF 为派生。
   - 验证：语法解析、节点/边上限、远程资源禁用、SVG 清洗、静态导出。

3. `chart_from_dataset`
   - 真源：Vega-Lite JSON + Frozen Dataset Snapshot。
   - 文件：交互 HTML、SVG、PNG、PDF。
   - 验证：Schema、字段、聚合、数据量、可访问性、数据和文字解读一致性。

产品入口不要先增加三个孤立按钮。Artifact 创建器用一个“产物类型”选择器，按用户选中的 Source、Note、Wiki 或 Research Snapshot 推荐 2 到 3 个适合类型，仍由用户确认。生成后统一进入预览、来源、版本、文件、比较、再生成和写回界面。

### Next：演示和可编辑画布

1. `slide_deck` 使用 Marp，先 Outline 审批，再生成 HTML/PDF/PPTX 和逐页缩略图。
2. `whiteboard_canvas` 使用 Excalidraw，先支持 AI 初稿 + 用户编辑 + Revision 保存 + SVG/PNG 导出。
3. 把 ECharts 作为固定模板 Renderer，用于 Artifact 运行、引用覆盖、成本、版本采用等产品内仪表盘，不把任意 ECharts JavaScript 作为用户 Artifact。

### Later：隔离的高级 Provider

1. 交互式报告使用 Observable Framework，产物部署在独立静态源或严格 sandbox iframe。
2. Presenton 只作为独立 Provider 做高级 PPTX 对比试验，与 Marp 在同一固定任务集上比较内容、设计、可编辑性、文件可打开性、生成成本与 Major Rework。
3. 只有图形类型扩展需求真实出现后才引入 Kroki，并只开放审核过的 engine allowlist。

## 5. 实现门禁与评测

### 5.1 供应链与执行安全

- 生产环境固定 npm/pip/container 版本与镜像 Digest，不运行 `npx -y ...@latest`。
- Renderer 无主库凭据、无 Workspace 全盘读取权限，只挂载单次 Run Sandbox。
- 默认禁止网络。图片、字体和数据先由 Source/Asset 管道摄取，再以受控本地文件传给 Renderer。
- HTML 使用独立 Origin 或 `<iframe sandbox>`，设置 CSP，不允许继承主站 Cookie。
- SVG 必须清除 script、event handler、foreignObject、外部 URL 和危险 data URI，再进入预览。
- 限制输入字节、节点/元素数、数据点数、输出文件数、单文件大小、CPU、内存、进程时间和并发。
- 模型只能选择 Catalog 中已发布的 Artifact Skill，不能提交任意命令、包名、MCP Server URL 或输出路径。
- MCP 工具结果必须按 `outputSchema` 和 NoteWeave File Manifest 二次校验。MCP 官方规范也要求服务端验证输入、做访问控制和限流，客户端验证结果、超时和记录审计。[MCP Tools Security Considerations](https://modelcontextprotocol.io/specification/2025-06-18/server/tools#security-considerations)

### 5.2 每类产物的最小真实用户评测

| 类型 | 确定性指标 | 用户任务指标 |
| --- | --- | --- |
| Mindmap | 语法通过、单根、无孤立节点、边界无裁切、引用覆盖 | 找到关键分支耗时、折叠/展开成功、导出后继续使用、Major Rework |
| Diagram | 解析通过、边/节点上限、SVG 安全、静态文件可打开 | 关系理解正确率、用户修正节点/边次数、保存或写回率 |
| Chart | Schema、字段、聚合、数据一致、轴/图例、导出一致 | 读数任务正确率、图型更换率、结论采纳率 |
| Slides | 页数、溢出、碰撞、字体、图片、引用、PPTX/PDF 可打开 | Outline 接受率、逐页大改率、Presentation Kept、演示/分享率 |
| Whiteboard | Scene Schema、元素上限、资产存在、SVG/PNG 可导出 | 首次编辑成功、撤销恢复、编辑后保存、复用率 |
| Interactive Report | Bundle Manifest、构建成功、CSP、无外网、资源预算 | 交互任务成功、加载时间、分享/写回、错误恢复 |

至少准备中文和英文、短文和长文、单 Source 和多 Source、浅色和深色、桌面和 375px、缺失数据、超长标签、恶意链接/HTML、重复生成和 Worker Kill 的固定回放集。没有这些证据时，只能说“新增了格式能力”，不能说产物质量已经提升。

## 6. 最终候选矩阵

| 候选 | License | 截至 2026-08-24 的维护信号 | 输入 | 输出 | 本地部署 | 推荐级别 |
| --- | --- | --- | --- | --- | --- | --- |
| Markmap | MIT | 2026-06 有提交，未归档 | Markdown / Tree | 交互 SVG、HTML | npm / CLI | Now，思维导图真源与 Renderer |
| Markmap MCP Server | MIT | v0.2.0，2026-08-04 | Markdown / local path | HTML、PNG、JPG、SVG、path/content | Node 20 / Docker / stdio | Now PoC，生产建议自有薄适配器 |
| mindmap-skills | MIT | v0.3.0，2026-06-16 | file / URL / text / topic | Markmap Markdown、HTML | Skill + Node | Prompt 与 Rubric 参考，不作 Runtime |
| Mermaid | MIT | 2026-08 活跃 | Mermaid text | 浏览器 SVG | npm | Now，文本图表真源与前端预览 |
| Mermaid CLI | MIT | 11.16.0，2026-06-29 | `.mmd` | SVG、PNG、PDF | npm / Docker | Now，Worker 导出 |
| Vega-Lite | BSD-3-Clause | v6.4.3，2026-04-24；2026-08 有提交 | JSON Spec + data | 交互 Vega View | npm | Now，数据图表真源 |
| VlConvert | BSD-3-Clause | v1.9.0，2026-01-20；2026-08 有提交 | Vega-Lite / Vega JSON | SVG、PNG、JPEG、PDF、HTML | Python / Rust CLI | Now，Python Worker 导出 |
| Apache ECharts | Apache-2.0 | v6.1.0，2026-05-19；2026-08 有提交 | option + data | Canvas、SVG、SSR | npm | Next，固定模板 Dashboard |
| Marp CLI | MIT | v4.5.0，2026-07-17 | Markdown + theme/assets | HTML、PDF、PPTX、images | Node / Docker / binary | Next，演示文稿 |
| Excalidraw | MIT | v0.18.1，2026-04-21；2026-08 有提交 | Scene JSON + assets | JSON、PNG、SVG | React npm | Next，白板画布 |
| Observable Framework | ISC | v1.13.4，2026-03-02；2026-05 有提交 | Markdown、JS、loaders | static site bundle | Node | Later，隔离交互报告 |
| Presenton | Apache-2.0 | beta release 2026-08-18；2026-08 活跃 | prompt/files/slide markdown | editable PPTX、PDF、edit path | Docker / API / HTTP MCP | Later，隔离高级 Slides Provider |
| Kroki | MIT | v0.32.1，2026-08-12；2026-08 活跃 | diagram source/type/format | SVG、PNG、部分 PDF | Docker / HTTP | Later，多引擎统一服务 |

## 7. 推荐决策

如果只批准一个功能，先做 Markmap 思维导图。它与用户当前提出的“产物可以搞思维导图”完全吻合，且能以最小架构改动形成可编辑真源、交互预览、静态导出、版本比较和写回闭环。

如果批准一个小版本，做 Markmap + Mermaid + Vega-Lite 三件套，并共享一套 `VisualArtifactManifest`、Sandbox Renderer、SVG/HTML 安全门禁和 Artifact Viewer。这样新增的不是三个孤立插件，而是一层可继续扩展到 Slides 与 Canvas 的“可视化产物基础设施”。

第三方 MCP Server 的定位应是候选实现和协议适配参考。生产主路径优先封装官方库；确实需要 MCP 时，由 NoteWeave 发布系统托管、固定版本、固定工具、固定输出 Schema 的 Provider，并将其纳入 Skill Release Bundle。

## 8. 一手来源索引

- [MCP Tools Specification](https://modelcontextprotocol.io/specification/2025-06-18/server/tools)
- [MCP Transports Specification](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports)
- [Markmap 官方仓库](https://github.com/markmap/markmap)
- [Markmap 官方文档](https://markmap.js.org/)
- [Markmap MCP Server](https://github.com/jinzcdev/markmap-mcp-server)
- [mindmap-skills](https://github.com/galiacheng/mindmap-skills)
- [Mermaid 官方仓库](https://github.com/mermaid-js/mermaid)
- [Mermaid CLI](https://github.com/mermaid-js/mermaid-cli)
- [Vega-Lite 官方文档](https://vega.github.io/vega-lite/docs/)
- [VlConvert](https://github.com/vega/vl-convert)
- [Apache ECharts](https://github.com/apache/echarts)
- [Marp CLI](https://github.com/marp-team/marp-cli)
- [Excalidraw](https://github.com/excalidraw/excalidraw)
- [Observable Framework](https://github.com/observablehq/framework)
- [Presenton](https://github.com/presenton/presenton)
- [Kroki](https://github.com/yuzutech/kroki)

## 9. 产品对标：从单个思维导图升级为跨产物 Artifact Studio

本节只使用产品官方帮助中心、官方产品页、官方文档或官方仓库，研究的是成熟产品如何组织完整产物体验，而不只是比较谁能生成思维导图。覆盖 12 个产品，分别落在多产物 Studio、文字转图与流程图、数据图表、演示文稿、白板与画布、知识关联和交互报告七类。

### 9.1 分类结论

| 产品类型 | 代表产品 | 已验证的成熟模式 | 对 NoteWeave 的意义 |
| --- | --- | --- | --- |
| 多产物 Studio | NotebookLM、Canva Magic Studio | 产物类型统一入口，每种产物再暴露少量专属参数 | `Artifact Studio` 应是顶层入口，Mindmap 只是其中一种 Renderer |
| 文字转图与流程图 | Napkin AI、Whimsical AI | 先给文字或选中内容，再浏览多个候选，生成后仍是可编辑对象 | 先解决“从证据到可编辑初稿”，不要只交付图片 |
| 数据图表与数据故事 | Flourish | 先上传数据、推荐图型，再用对话调整设置，最后发布为响应式交互内容 | Chart 需要保留冻结数据、Spec、叙事与可交互视图，不是普通插图 |
| 演示文稿 | Gamma、NotebookLM、Canva | 先选来源和受众，先确认 outline，再生成整套内容，支持整套或局部再编辑 | Slides 应有 Outline 审批，不直接从 Prompt 跳到 PPTX |
| 白板与画布 | Miro、Heptabase、Obsidian Canvas | 无限画布承载可编辑知识对象，AI 负责生成和整理，不替代人工塑形 | Whiteboard 应引用 Note/Wiki/Source 对象，不复制一份失去来源的文本 |
| 知识关联 | Obsidian Graph、Heptabase、Mapify、XMind AI | 全局图和当前主题局部图分开，节点可回原文或继续提问 | Wiki Graph 是导航和分析视图，不应成为孤立的装饰图 |
| 交互报告 | Observable、Flourish | 文本、数据、图表和控件组成可发布、可嵌入的动态文档 | Interactive Report 应放 Later，并在隔离 Origin 或 sandbox 中运行 |

最重要的产品结构不是“多放几个生成按钮”，而是统一下面这条链路：

```text
选择产物类型
  -> 选择来源范围与输入快照
  -> 选择目的、受众、结构、深度和视觉偏好
  -> 预览计划或 Outline
  -> 后台生成
  -> 原生编辑或对话式局部修改
  -> 节点继续提问，引用跳回来源
  -> 保存新版本、比较、恢复
  -> 导出、发布或写回 Note / Wiki
```

### 9.2 多产物 Studio

#### NotebookLM：来源驱动的统一 Studio

- 入口组织：Notebook 的 Sources、Chat、Studio 三栏中，Studio 集中承载 Mind Map、Report、Data Table、Flashcards/Quiz、Slide Deck、Infographic、Audio/Video Overview 等产物。用户不是先挑工具，而是先建立来源集合，再决定把同一批来源转成什么。[NotebookLM 官方帮助中心](https://support.google.com/notebooklm/?hl=en) [Studio 产物官方说明](https://support.google.com/gemininotebook/answer/16206563?hl=en)
- 生成前选择：不同产物使用不同的小型配置面板。Slide Deck 可选 Detailed Deck 或 Presenter Slides、语言、长度、受众、风格和重点；Infographic 可选细节级别、方向、视觉风格和自定义提示。[Slide Deck 官方说明](https://support.google.com/notebooklm/answer/16757456?hl=en) [Infographic 官方说明](https://support.google.com/gemininotebook/answer/16758265?hl=en)
- 交互与回源：Mind Map 支持缩放、展开和折叠，点击节点会把主题带回 Chat 继续问；聊天引用可跳到来源具体位置。[Mind Map 官方说明](https://support.google.com/gemininotebook/answer/16212283?hl=en) [Chat 与引用官方说明](https://support.google.com/gemininotebook/answer/16179559?hl=en)
- 编辑与版本：Slide Deck 可以逐页写修改指令并批量生成 revised deck，每次修订产生一个新 Deck；目前不能增删页，而且官方明确说明修订阶段不重新参考 Sources。Mind Map 更接近重新生成而非结构化编辑，没有清晰的版本树和差异比较。
- 导出与移动端：Deck 可下载 PDF、PPTX 或分享 artifact link，Infographic 可下载 PNG。移动端支持部分产物，但当前不支持 Notes、Mind Map、Report 和 Data Table，也不能在 App 内分享 Notebook。[移动端官方说明](https://support.google.com/gemininotebook/answer/16296687?hl=en)
- 借鉴：统一 Studio、来源范围先选、每种产物只显示相关参数、后台生成、节点继续提问和 artifact 直链。
- 不照搬：删除后再生成的心智负担，Mind Map 不能原生编辑，Deck 修订脱离 Sources，以及桌面与移动端能力断层。

#### Canva Magic Studio：同一内容跨格式复用

- 入口组织：Canva AI 从首页提供统一输入，可加入上传文件、已有设计或 Brand Kit，再选 Design、Image 或 Video，并继续选择设计类型、风格和格式。它把 AI 能力放进统一 Visual Suite，而不是让用户理解不同模型。[Magic Design 官方帮助](https://www.canva.com/help/use-magic-design/) [Magic Studio 官方发布](https://www.canva.com/newsroom/news/magic-studio/)
- 生成前选择：用户输入短描述或媒体，选择产物类型、风格、格式和品牌，先浏览多个模板候选，再选一个进入编辑器。Presentation 会生成统一叙事、Outline 和内容。[AI Presentation 官方页](https://www.canva.com/create/ai-presentations/)
- 编辑与复用：生成结果继续使用 Canva 标准拖拽编辑器；Resize & Magic Switch 可以把白板转成邮件、Deck 转成 Summary Doc 或 Blog，并批量改尺寸、改语言。[Magic Studio 官方发布](https://www.canva.com/newsroom/news/magic-studio/)
- 移动端：Magic Design 官方帮助同时给出 Desktop 与 Mobile 流程，说明其策略是保持同一入口，再按设备压缩编辑面板，而不是移动端另建一套产品。
- 借鉴：`Artifact Studio` 可增加“从现有产物转换”动作，例如 Report 转 Slides、Mindmap 转 Outline、Chart 转一页简报，并复用 Source Reference Map。
- 不照搬：Canva 的格式和模板数量非常大，NoteWeave 不应变成素材市场，也不应让模板选择压过来源可信度和内容核验。

### 9.3 文字转图、流程图与思维导图

#### Napkin AI：先选视觉候选，再进入细节编辑

- 入口组织：先粘贴或导入文字，再点击生成视觉。系统根据文字给出多个相关视觉，用户选一个最能表达观点的候选，而不是先学习图表语法。[Napkin 官方产品页](https://www.napkin.ai/) [Getting Started 官方集合](https://help.napkin.ai/en/collections/3741376-getting-started)
- 生成前选择：基础路径几乎不要求 Prompt；需要控制时可使用 Custom Generation 和 Custom Brand，指定视觉目标、颜色、字体和品牌。品牌也可应用到已经生成的视觉。[Features 官方集合](https://help.napkin.ai/en/collections/10643465-features) [Custom Brands 官方说明](https://help.napkin.ai/en/articles/10696628-custom-brands-formerly-custom-styles)
- 编辑：生成结果不是固定截图，用户可以修改颜色、字体、布局、图标和独立元素，或整体替换视觉。
- 导出与分享：支持 PPT、PNG、PDF 和 SVG；PPT 中还能继续改文字、颜色、尺寸和动画。[PPT Export 官方发布](https://www.napkin.ai/blog/napkin-launches-ppt-export-files-import/)
- 移动端：手机可查看既有工作并创建、编辑 Slides，但 Visual Generation 暂不可用，因此其移动策略仍是查看和轻编辑优先。[Napkin 官方产品页](https://www.napkin.ai/)
- 借鉴：NoteWeave 的 Diagram 生成可以先给 3 个结构候选，只让用户选择“表达意图”，再进入编辑，而不是在生成前暴露十几个图型参数。
- 不照搬：Napkin 的核心是文本到视觉表达，没有 NoteWeave 所需的逐节点证据回链。不能因为图好看就丢掉 Citation Map。

#### Whimsical AI：可编辑画布与官方 MCP 工具链

- 入口组织：AI 可从一个 Prompt 创建 Flowchart、Mind Map、Wireframe；Mind Map 还能在选中节点后点击 AI，每次生成 5 个子节点，并把已有路径作为上下文。[Whimsical AI 官方页](https://whimsical.com/ai) [AI Mind Map 官方指南](https://whimsical.com/learn/get-started/ai-mind-maps)
- 画布交互：生成结果直接落到无限画布，支持节点拖拽、自动布局、多人编辑、评论和版本恢复。Mind Map 节点可添加链接，相关文件和任务可互相连接。[Mind Map 官方页](https://whimsical.com/mind-maps)
- 官方 MCP：Whimsical 提供 OAuth 远程 MCP 和本地 Desktop MCP。远程工具包括搜索、读取、目录树、创建、编辑、自动布局、评论和删除，能够创建 Flowchart、Mind Map、Sequence Diagram、Wireframe、Board、Sticky Notes 和 Table，并在聊天中返回预览和链接。[Whimsical MCP 官方指南](https://whimsical.com/learn/integrations/mcp) [官方 MCP 工具清单](https://whimsical.com/learn/ai/mcp-tools)
- 导出：Board 可导出 PNG、SVG、PDF，Flowchart 和 Sequence Diagram 可复制为 Mermaid，Doc 可导出 Markdown。[官方导出说明](https://whimsical.com/learn/imports-exports/exporting-from-whimsical)
- 版本与移动端：Board 有 Version History 并可恢复。手机没有独立 App，移动浏览器主要用于查看和评论，编辑能力明显收敛。[版本历史官方说明](https://whimsical.com/learn/faqs/version-history) [移动端官方说明](https://whimsical.com/learn/faqs/mobile)
- 借鉴：官方 MCP 的 `search -> fetch -> edit -> auto_layout` 组合，正好说明“外部 Agent 生成后仍可继续修改”应该是一条工具链，而不是一次文件下载。
- 不照搬：Whimsical 是通用协作白板，NoteWeave 不需要一次开放任意 Board 操作和删除能力；外部 MCP 也不能绕过 Artifact Version、ACL 和文件校验。

#### XMind AI：Discussion 与 Apply 分离，生成即原生对象

- 入口组织：Web 从 Sidebar > Xmind AI，桌面从 Create with AI，iOS 在新建时横滑选择模式。可输入文字、文件、链接和 YouTube，选择 Work Breakdown、To-Do、Thinking、Online Search，以及 Logic、Tree、Matrix 等结构和输出语言。[Create with AI 官方指南](https://xmind.com/user-guide/create-with-ai)
- 对话式编辑：地图旁常驻 AI Panel，响应模式分 Quick、Balanced、Deep。Discussion Mode 用于只讨论不改图，也能明确作用于空白图、当前图或选中节点。[XMind AI 官方指南](https://xmind.com/user-guide/xmind-ai-chatbot)
- 回源：Online Search 会展示网页来源，用户可以先审阅，再决定是否加入地图。地图可有多个私密 Chat 线程，但不是每个节点都天然拥有逐段 Citation。
- 编辑与导出：AI 可以扩展、去重、重组、翻译、转任务、换布局和主题；结果仍是原生节点。支持 PNG、SVG、PDF、Excel、Word、OPML、PowerPoint、Markdown 等格式，也支持公开链接、密码、嵌入和实时协作。[文件与导出官方指南](https://xmind.com/user-guide/working-with-files) [分享协作官方指南](https://xmind.com/user-guide/sharing-and-collaborating)
- 官方 MCP 与 Skill：XMind 官方 MCP 能创建、读取和编辑云端 Mind Map；官方 Skill & CLI 能生成、校验并保存可编辑 `.xmind` 本地文件。[XMind MCP 官方指南](https://xmind.com/user-guide/xmind-mcp) [XMind Skill & CLI 官方指南](https://xmind.com/user-guide/xmind-cli)
- 借鉴：给 NoteWeave 增加“只讨论”和“应用到产物”两个明确动作；AI 修改默认只作用于选中节点；来源搜索结果先预览再写入。
- 不照搬：模式、结构、模板和平台差异很多，容易形成选择疲劳。首版只保留 Purpose、Depth、Layout 三组高价值选项。

#### Mapify：报告与同步思维导图是一对产物

- 入口组织：可从文字、网页、PDF、图片、音视频、YouTube 和多文件对话创建 Mind Map；还提供 Deep Research 入口，而不是只接受空白 Prompt。[Mapify 官方产品页](https://mapify.so/) [Ask Anything 官方页](https://mapify.so/ask-anything)
- 生成前选择：Deep Research 先选择语言和 Instant / Powerful 工作模式，再通过澄清问题确定范围。运行中显示 Planning、Researching、Creating 和已收集来源。[Deep Research 官方说明](https://mapify.so/blog/mapify-deep-research)
- 回源与继续提问：Deep Research 同时生成有编号引用的 Report 和结构同步、可编辑的 Mind Map。新版 Mapify Chat 不只回答地图相关问题，也能通过自然语言扩展、精简、翻译和重组地图。[Mapify Chat 官方说明](https://mapify.so/blog/new-mapify-chat-ai-mind-map-assistant)
- 编辑与导出：可手工改内容、分支和样式，也可用 Chat 修改；Mind Map 可导出 PDF、图片、XMind、Markdown 等，Report 可复制、下载 PDF 或通过链接分享。
- 移动端：官方产品信息提供 Web、Mobile 和 Browser Extension，并有 iOS、Android 应用。[跨平台官方说明](https://mapify.so/blog/mapify-vs-mindmap-ai-comparison)
- 借鉴：同一次 Run 生成“可引用研究报告 + 同步结构图”，两者共享 Source Reference Map；Mind Map 的节点修改应能同步影响 Report Outline，但正文修改仍需用户确认。
- 不照搬：不要把所有来源都压缩成树。复杂论证、冲突证据和多对多关系仍需 Graph、Table 或 Report 承载。

### 9.4 数据图表与交互数据故事

#### Flourish：数据优先、可解释修改和官方 MCP Connector

- 入口组织：`Start with data` 先让用户上传数据，再自动推荐适合的图表，用户预览候选后点击 Create。官方明确说明这一步不是用 AI 生成图表，而是确定性推荐。[Start with data 官方指南](https://helpcenter.flourish.studio/hc/en-us/articles/11526240361615-Start-with-data-workflow)
- 生成前选择：官方 MCP Connector 允许用户提供或请求一个 Dataset、描述想表达的内容，Agent 推荐 Chart Type、选择 Template、转换数据并设置标题、来源、字体和颜色，最后返回可继续编辑的 URL。[Flourish Connector 官方指南](https://helpcenter.flourish.studio/hc/en-us/articles/16487012057999-Flourish-Connector-an-intro)
- 编辑：Flourish Assistant 在编辑器内通过自然语言改设置，会列出并高亮实际变更，并允许撤销最近一组 AI 修改；它读取数据作为上下文但不直接改数据。[Flourish Assistant 官方指南](https://helpcenter.flourish.studio/hc/en-us/articles/16681550872719-Flourish-Assistant-an-overview)
- 交互报告：Chart 可以组合成逐页 Story 或 Scrollytelling，并支持筛选、比较、Tooltip、自动播放和循环。响应式布局默认适配移动端。[Data Storytelling 官方页](https://flourish.studio/product/data-storytelling/) [Data Explorer 官方页](https://flourish.studio/visualisations/data-explorer/index.html)
- 导出与发布：可发布链接、响应式 Embed、PNG/JPEG/SVG、Canva、PowerPoint，部分计划可下载 HTML 自托管；发布后未发布变更与线上版本有清晰状态。[导出发布官方指南](https://helpcenter.flourish.studio/hc/en-us/articles/8761565550607-Exporting-publishing-embedding-and-sharing) [移动预览和导出官方教程](https://helpcenter.flourish.studio/hc/en-us/articles/16751528655759-Learn-Flourish-fundaments-step-by-step-tutorial)
- 版本：可以 Duplicate Project 作为备份或变体，Template 大版本不会自动迁移旧图，避免破坏已有发布内容。[Duplicate 官方指南](https://helpcenter.flourish.studio/hc/en-us/articles/8761537320719-How-to-duplicate-a-project) [Template 版本官方指南](https://helpcenter.flourish.studio/hc/en-us/articles/8761522376463-How-to-use-the-latest-version-of-a-template)
- 借鉴：模型只负责意图和设置建议，Dataset 与聚合仍由确定性管道控制；每次 AI Edit 显示具体 Diff 并可撤销；Preview 要自带 Desktop、Tablet、Mobile 尺寸切换。
- 不照搬：Connector 会让 Agent 转置或重塑数据，官方也警告复杂数据可能产生错误。NoteWeave 必须让 Worker 验证行数、字段、聚合和数值一致性，不能只信远端返回的 Chart。

### 9.5 演示文稿与可发布内容

#### Gamma：先共同确定 Outline，再生成整套产物

- 入口组织：首页将创建分为 Generate、Paste、Import，另有 Create with Agent。Agent 模式可组合 PDF、Doc、Deck、URL、图片和已有 Gamma，也能补充 Web Research。[创建模式官方说明](https://help.gamma.app/en/articles/7838093-how-do-i-create-a-new-presentation-document-or-webpage-in-gamma) [Create with Agent 官方说明](https://help.gamma.app/en/articles/15002203-how-do-i-create-with-agent-in-gamma)
- 生成前选择：用户先回答少量问题，与 Agent 一起编辑 Outline，选择 Minimal、Visual、Classic、Consultant 等 Deck Style，主题继续控制颜色和字体。只有用户确认 Outline 并点击 Generate 后才构建 Deck。
- 编辑与再生成：生成后可手工编辑，也可让 Agent 对单页或整套内容做研究、引用、重写、翻译、增页和整体 Restyle；用户可以在多个输出中选择、改 Prompt、再次生成。[Agent 编辑官方说明](https://help.gamma.app/en/articles/8033284-can-i-edit-my-content-using-ai)
- 特殊视觉：Infographic 支持 Prompt Enhance、Layout、Art Style、Aspect Ratio 和 Image Model；再生成会产生新的静态 Infographic，当前不是元素级可编辑图。[Infographic 官方说明](https://help.gamma.app/en/articles/13920805-how-do-i-add-infographics-in-gamma)
- 导出与分享：可用实时 Web Link、网站发布、PDF、PPTX、PNG 等方式分发；Web Link 会始终呈现最新修改，静态导出反映 Present Mode，可能和 Edit Mode 略有差异。[Gamma 官方产品页](https://gamma.app/) [导出差异官方说明](https://help.gamma.app/en/articles/15939201-why-doesn-t-my-exported-pdf-or-powerpoint-match-what-i-see-in-gamma)
- 移动端：官方说明 Agent 编辑已支持移动端，Gamma 的 Card 布局也更容易在不同宽度重排，但复杂逐页设计仍然明显更适合桌面。
- 借鉴：Slides 首先让用户批准 Outline；把 Content Style 与 Theme 分开；允许局部修订和整套 Restyle；产物 Link 指向持续更新的当前发布版本。
- 不照搬：Gamma 的 Web Card 不是传统固定页面，导出 PPTX 可能需要清理。NoteWeave 首版必须明确区分“Web 演示”“可播放 PPTX”和“元素级可编辑 PPTX”。

### 9.6 白板、知识关系和回源闭环

#### Miro AI：生成草图，确认后才应用到 Canvas

- 入口组织：从 Creation Toolbar 进入 Create with AI，再从 Formats 选择 Diagram 或 Mindmap，选图类型并描述目标。也可以选中 Board 上已有内容作为上下文。[Miro Create with AI 官方说明](https://help.miro.com/hc/en-us/articles/20164358139794-Create-with-AI) [AI Diagram 与 Mindmap 官方说明](https://help.miro.com/hc/en-us/articles/28782102127890-Miro-AI-with-Diagrams-and-mindmaps)
- 生成前与确认：AI 先生成 Sketch，用户可以 Apply to canvas、Discard all，或在侧栏继续描述修改后再应用。Mind Map 还支持 Expand with questions、ideas、topics。[Mind Map 官方说明](https://help.miro.com/hc/en-us/articles/360017730753-Mind-map)
- 编辑：应用后是完整 Canvas 对象，可移动节点、改内容、自动布局和继续人工编辑。手绘 Diagram 也能转为可编辑对象。
- 导出与分享：Board 或 Diagram 支持图片、PDF、SVG、链接和 Embed，但移动端不支持 Board Export。[Board 导出官方说明](https://help.miro.com/hc/en-us/articles/360017572754-How-to-export-your-board) [Diagram 官方说明](https://help.miro.com/hc/en-us/articles/25275263961874-Miro-Diagrams)
- 移动端：AI、Shapes、Sticky Notes、Comments、Connector 和 Drawings 可用，但 Templates、Export 和 Presentation Mode 等缺失或受限。[Miro 移动端官方说明](https://help.miro.com/hc/en-us/articles/360017572834-Mobile-app)
- 借鉴：NoteWeave 的高成本白板与 Diagram 应先出 Draft Preview，再明确 Apply；Discard 不产生业务版本，Apply 才提交 Artifact Version。
- 不照搬：Miro 的整个 Board 都能成为 AI Context，但 NoteWeave 必须显示实际选择了哪些 Source、Note、Wiki 和 Artifact，不能用不可见的“全画布上下文”。

#### Obsidian Canvas 与 Graph：创作画布和分析图谱是两种视图

- 入口组织：Canvas 用于主动创作，可放 Note、Text Card、Attachment、Web Page 和 Folder；Graph 自动从 Markdown Link 派生，全局图和当前 Note 的 Local Graph 分开。[Canvas 官方说明](https://obsidian.md/help/plugins/canvas) [Graph 官方说明](https://obsidian.md/help/plugins/graph)
- 画布交互：Canvas 支持无限二维空间、多选、分组、带方向/标签/颜色的连接线，并可跳到连接 Source 或 Target；`.canvas` 使用开放 JSON Canvas 格式。[JSON Canvas 官方规范](https://jsoncanvas.org/)
- 图谱交互：节点大小随引用数变化，Hover 突出邻接边，点击直接打开 Note；可搜索、过滤、分组、显示箭头和调整 Local Graph 深度。
- 回源：Graph 节点打开原 Note，Canvas Card 本身就是文件视图。纯 Text Card 不进入 Backlinks，必须先转成文件。
- 版本与移动端：File Recovery 可恢复 `.md` 和 `.canvas` 本地快照，Sync 另有版本历史；官方移动端支持 Local Graph。[File Recovery 官方说明](https://obsidian.md/help/plugins/file-recovery) [Sync 版本官方说明](https://obsidian.md/help/sync/version-history) [移动端官方说明](https://obsidian.md/help/mobile)
- 借鉴：Wiki 关系展示要同时提供 Overview Graph 和 Current Page Graph；Graph 是导航与诊断，Canvas 是编辑与组织；节点点击必须回 Wiki Page。
- 不照搬：不要把 Force、Repel、Link Distance 等专业参数全部暴露给普通用户；也不要让画布临时文字游离在 Wiki、Backlink 和 Citation 体系外。

#### Heptabase：把回答转成知识对象，而不是留在聊天记录里

- 入口组织：Research a topic 可上传 PDF、YouTube、Docx、文字和图片，并在新 Whiteboard 开始研究；白板或 Card 的 Chat 自动把当前 Tab 加入上下文，也可用 `+` 或 `@` 精确指定 Whiteboard、Section、Card、PDF 页码和视频。[Heptabase AI 官方说明](https://wiki.heptabase.com/work-with-ai)
- 知识对象与画布：Card 和画布位置解耦，同一 Card 可出现在多个 Whiteboard；画布支持 Mindmap、Section、Sub-whiteboard、Arrow 和批量操作。[Fundamental Elements 官方说明](https://wiki.heptabase.com/fundamental-elements)
- 回源与继续提问：AI 回答的 Citation 可跳到 PDF 段落或视频 Timestamp；有价值的 AI Message 可直接拖到 Whiteboard，成为可编辑、可注释、可连接的知识对象，整个 Whiteboard 又能成为下一轮 Chat Context。
- 导出分享与版本：Whiteboard 可发布持续更新的只读 Link，也可使用旧 Snapshot；版本历史分别保护 Card 内容和 Whiteboard 布局，保留 60 天。[Whiteboard 发布官方说明](https://support.heptabase.com/en/articles/12121546-how-do-i-publish-whiteboards-with-a-public-link) [版本恢复官方说明](https://support.heptabase.com/en/articles/10448124-how-to-restore-cards-and-whiteboards-from-version-history)
- 移动端：官方提供 iOS、iPadOS、Android，强调移动捕捉、编辑、实时同步和离线。[官方下载页](https://heptabase.com/download)
- 官方 Skill：Heptabase CLI Skill 明确支持 Codex 与 Claude Code，可创建和读写 Card、Journal、Tag、Whiteboard，分页读取 PDF，读取音视频转录，并返回 JSON。[Heptabase CLI 官方说明](https://support.heptabase.com/en/articles/14715462-how-to-use-heptabase-cli)
- 借鉴：增加“保存为 Note”“加入 Wiki”“放到 Canvas”三个明确动作；保存后得到真正的知识对象和 Citation，而不是 MessageBubble 的视觉副本。
- 不照搬：多 Pane、Card Library、Whiteboard 和大量右键动作学习成本高；内容版本和布局版本也不应让用户分别理解。

### 9.7 交互报告

#### Observable：Notebook、Canvas 和 Data App 分层

- 入口组织：Observable 把产品分成 Notebook、Canvas 和 Framework Data App。Notebook 用 Cell 混合 Markdown、JavaScript、SQL、HTML、Table 和 Visualization；Canvas 用于协作式数据探索；Framework 用于构建 Dashboard、Report 和 Embedded Analytics。[Observable 官方文档入口](https://observablehq.com/documentation/) [Notebook 官方说明](https://observablehq.com/documentation/notebooks/)
- 交互：Observable Inputs 提供 Button、Slider、Dropdown、Table、Text Input，和 Chart 绑定后形成响应式分析界面；数据可来自文件、Spreadsheet、API 和 Database。[数据官方说明](https://observablehq.com/documentation/data/)
- 分享与版本：可把一个或多个 Named Cells 通过 iframe、JavaScript Runtime 或 React 嵌入其他产品；静态场景可下载 PNG，代码可下载为 JavaScript Module 或 Tarball，并能锁定具体 Published Version。[Embed 官方说明](https://observablehq.com/documentation/embeds/) [Advanced Embed 官方说明](https://observablehq.com/documentation/embeds/advanced)
- 移动端：产品重点是响应式 Web Viewer 和 Embed，而不是手机上的完整代码编辑。作者仍需为小屏设计 Layout 和 Controls。
- 借鉴：Later 阶段允许从 Verified Dataset 生成交互报告 Bundle，并支持 Artifact 中嵌入一个 Chart Cell，而不必每次发布完整站点。
- 不照搬：Observable 允许任意代码和数据连接，安全模型远超普通可视化产物。生成代码不能在 NoteWeave 主域运行，必须使用独立 Origin、CSP、无凭据环境和 Sandbox。

### 9.8 应统一到 NoteWeave Artifact Studio 的模式

| 模式 | 采用方式 | 首批适用产物 |
| --- | --- | --- |
| NotebookLM 的统一 Studio | 一个入口展示推荐类型和全部类型，最近版本也在同处管理 | 全部 |
| Gamma 的 Outline 审批 | 内容较长或成本较高时，先生成 Plan / Outline，用户确认后再渲染 | Slides、Report、Interactive Report |
| Miro 的 Draft / Apply / Discard | 未 Apply 的结果是临时预览，不进入正式 Artifact Version | Diagram、Whiteboard、Mindmap |
| XMind 的 Discussion / Apply | 聊天默认不改产物，只有显式 Apply 才产生 Patch 和新版本 | Mindmap、Diagram、Chart、Slides |
| Heptabase 的 Message to Knowledge Object | 回答可保存成 Note、Wiki Page 或 Canvas Card，保留来源关系 | Note、Wiki、Whiteboard |
| Obsidian 的 Overview / Local Graph | 全局关系探索和当前对象邻域使用同一数据、不同默认范围 | Wiki Graph、Source Graph |
| Flourish 的可解释 AI Edit | 展示 AI 实际改了哪些配置或数据绑定，并提供 Undo | Chart、Interactive Report |
| Canva 的跨格式转换 | 从已有 Artifact 派生新 Artifact，继承 Input Snapshot 与 Citation Map | Report to Slides、Mindmap to Outline、Chart to Brief |

不适合统一的部分也要明确：专业白板的全部绘图工具、XMind 的近百模板、Obsidian 的所有力导参数、Observable 的任意代码执行，以及 Canva 的素材市场，不应塞进首版 Artifact Studio。首版 Studio 应保持一个稳定心智模型，专属高级编辑器按产物类型按需打开。

### 9.9 产品对标后的产品优先级调整

1. `Now`：Artifact Studio Shell、Source Scope、Purpose / Depth / Layout、后台生成、Draft / Apply、Artifact 版本和 Citation Map。
2. `Now`：Mindmap、Mermaid Diagram、Vega-Lite Chart，但三者共用创建器、版本、来源、导出和写回，不做三个孤岛页面。
3. `Next`：Report 与 Mindmap 联动、Slides Outline 审批、Message 保存为知识对象、Overview / Local Wiki Graph。
4. `Next`：官方受控 Provider 试验。优先评估 XMind MCP / CLI、Whimsical MCP、Flourish Connector 和 Heptabase CLI，所有写操作仍通过 NoteWeave ACL、Artifact Version 和 Manifest 审核。
5. `Later`：Excalidraw Whiteboard、Observable Interactive Report，以及 Artifact 跨格式派生。

这 12 个产品共同说明，产物竞争力不来自“能生成多少格式”，而来自生成之后是否还能继续思考、编辑、回到证据、形成版本，并被下一轮工作复用。NoteWeave 的差异化应放在 Source Grounding、Citation、Artifact Version、Writeback 和统一 Skill Contract 上，而不是复制某一个制图产品的完整编辑面板。
