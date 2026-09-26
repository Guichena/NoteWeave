# ipvoov/Craft-Agent 项目分析

> `[行业参考]` 本文是外部项目分析，不证明 NoteWeave 的当前实现、测试结果或生产收益；相关采用建议仅属于 `[目标设计]`。

> 调研对象：[`ipvoov/Craft-Agent`](https://github.com/ipvoov/Craft-Agent)  
> 固定版本：[`3ce19c4e0382b54f27c9d04b0a723eca50687409`](https://github.com/ipvoov/Craft-Agent/tree/3ce19c4e0382b54f27c9d04b0a723eca50687409)  
> 上游基线：ByteDance DeerFlow 1.x，仓库初始提交记录的上游 commit 为 [`b7a4b0f44610150e1f535dc70e3f57d0d5f56fcd`](https://github.com/bytedance/deer-flow/tree/b7a4b0f44610150e1f535dc70e3f57d0d5f56fcd)  
> 调研日期：2026-07-18  
> 本文严格区分 README 宣称、源码事实和推断。

## 1. 结论摘要

`ipvoov/Craft-Agent` 和 NoteWeave Research Agent 的业务方向确实比 `craft-ai-agents/craft-agents-oss` 更接近。它是一套面向终端用户的 Deep Research Web 应用，主链路包含需求澄清、背景搜索、研究计划、人工审阅、逐步搜索与阅读、报告生成，并额外提供“生成网站”的第二条工作流。

但它不是一个全新原创的 Research Agent 架构。源码和 Git 历史能够明确证明，它以 ByteDance DeerFlow 1.x 为底座进行二次开发：

- 初始提交中存在一个名为 `deer-flow` 的 Gitlink，精确指向 ByteDance DeerFlow commit `b7a4b0f4`。
- 初始提交与该 DeerFlow 版本有 232 个同路径文件，其中 191 个 blob 完全相同，相同率约 82.3%。
- 当前仓库仍有 114 个文件保留 `Copyright (c) 2025 Bytedance Ltd.` 版权头，前端也保留大量 `deer-flow` 目录名。
- 研究图的 Coordinator、Planner、Human Feedback、Researcher、Coder、Reporter 结构是 DeerFlow 1.x 的典型节点体系。

因此，对它最准确的定位是：

> 基于 DeerFlow 1.x 的轻量 Deep Research 产品分支，并新增网站生成、Pexels 图片素材、部分中文化和前端产品包装。

它值得保留在 `reference/` 中，但用途应标记为“产品交互与 DeerFlow 1.x 二次开发样本”，不应把它当成 Research Agent 核心算法、证据治理或生产运行时的主要标杆。若要研究其 Research Agent 原理，DeerFlow 1.x 才是更可靠的一手上游。

## 2. 项目是什么

README 将产品描述为“深度研究助手 + 网站生成”的一体化系统，并列出以下能力：

- 自动规划、搜索、阅读和整理资料；
- 人类在环修改研究计划；
- 输出结构化研究报告；
- 将想法或研究成果生成可预览前端网站；
- FastAPI + LangGraph/LangChain 后端；
- Next.js 15 + React 19 前端；
- Tavily 搜索、Jina + Readability 网页抓取、Python REPL。

这些高层能力大部分能在源码中找到对应实现，但成熟度不一致。研究图、Tavily 搜索、Jina 抓取、SSE 流式 UI 和网站生成是实际代码；README 暗示的 MCP、RAG、多种持久化、工具执行前审批等能力则存在接口或 UI 残留，但没有形成可用后端闭环。

项目有两条相互独立的 LangGraph：

| 工作流 | 节点 | 主要产物 |
|---|---|---|
| Deep Research | coordinator → background_investigator → planner → human_feedback → research_team → researcher/coder → reporter | Markdown 研究报告 |
| Web Generate | outline → web_source → codegen → edit | 可预览、可下载的网站源码 |

源码依据：研究图节点注册见 [`src/graph/builder.py#L60-L79`](https://github.com/ipvoov/Craft-Agent/blob/3ce19c4e0382b54f27c9d04b0a723eca50687409/src/graph/builder.py#L60-L79)，网站生成图见 [`src/graph/builder.py#L115-L141`](https://github.com/ipvoov/Craft-Agent/blob/3ce19c4e0382b54f27c9d04b0a723eca50687409/src/graph/builder.py#L115-L141)。

## 3. 原创性与真实上游

### 3.1 可直接证明的继承关系

仓库不是 GitHub 平台意义上的 fork，GitHub API 的 `fork` 字段为 `false`。但这不代表代码独立原创。

本地 Git 对象给出了更强的证据：初始提交 `7a36af6d` 的树中包含：

```text
160000 commit b7a4b0f44610150e1f535dc70e3f57d0d5f56fcd  deer-flow
```

这是一个没有配套 `.gitmodules` 的 Gitlink，目标 commit 恰好属于 `bytedance/deer-flow`。初始提交还包含：

- `.idea/deer.iml`；
- `web/public/images/deer-hero.svg`；
- `web/src/components/deer-flow/`；
- ByteDance 版权头；
- DeerFlow 的 replay、mock、SSE、Research UI 和节点代码。

对初始提交与上游 `b7a4b0f4` 进行 blob 级比对：

| 指标 | 数量 |
|---|---:|
| Craft-Agent 初始提交文件 | 267 |
| DeerFlow 对应提交文件 | 402 |
| 同路径文件 | 232 |
| 内容完全相同的同路径文件 | 191 |
| 同路径完全相同比例 | 82.3% |

这足以将它认定为 DeerFlow 1.x 的二次开发，而不仅是“受到启发”。

### 3.2 不要与 DeerFlow 2.0 混为一谈

DeerFlow 官方当前 README 明确说明，2.0 是从零重写，与 v1 不共享代码；原 Deep Research framework 维护在 `main-1.x`，当前 2.0 已转向 super agent harness。官方说明见 [`bytedance/deer-flow README`](https://github.com/bytedance/deer-flow/blob/a028dfd5fb70bd6e26c7dbf9e89543c7c006f9a2/README.md)。

Craft-Agent 继承的是 2025 年的 DeerFlow 1.x Research Agent 节点体系，不应拿它代表当前 DeerFlow 2.0。

### 3.3 项目自己的实质新增

相对于初始 DeerFlow 基线，仓库后续较明确的新增包括：

- `WebGenState` 和网站生成图；
- outline、web_source、codegen、edit 节点；
- Pexels 图片搜索与下载；
- 网站文件读写工具和预览、下载 API；
- Craft-Agent 品牌、中文化文案与新的 landing page；
- 搜索结果后处理和新的 crawler 封装；
- Docker 镜像和 Compose 启动方式。

所以它并非“只改名字”，但 Research 主链路的架构原创性仍主要属于 DeerFlow 1.x。

## 4. 代码规模、活跃度和成熟度

截至调研固定版本：

| 指标 | 结果 |
|---|---:|
| GitHub Stars | 约 158 |
| Forks | 约 18 |
| Commits | 36 |
| 作者邮箱身份 | 3 个，其中两个属于同一开发者环境 |
| Tags / Releases | 0 |
| 仓库创建时间 | 2025-11-24 |
| 最后代码提交 | 2025-12-17 |
| 后端测试文件 | 0 |
| 前端测试文件 | 7 |
| 前端测试函数规模 | 以 store、消息合并、Markdown 为主 |

活动曲线很短。第一个提交一次性导入约 9.5 万行和大量 DeerFlow 文件，随后约一个月内完成网站生成功能和包装，之后没有持续代码演进。当前 Star 数说明项目有一定可见度，但还不能称为行业标杆或成熟社区项目。

仓库工程卫生也偏原型：

- 跟踪了约 700 个 `web/.next/` 构建产物文件；
- 跟踪了 `.DS_Store`、早期 `.idea` 和 `web/.env`；
- README 声称 MIT 并链接 `LICENSE`，但当前仓库实际没有 `LICENSE` 文件；
- README 写 Python 3.11+，而 `pyproject.toml` 和 Dockerfile 要求 Python 3.13；
- 没有版本 tag、release note、CI 工作流或后端测试。

因此其成熟度应评为“可运行的个人/小团队原型”，不宜按生产级框架评估。

## 5. LangGraph 状态模型

研究状态继承 `MessagesState`，增加以下字段：

- `research_topic`、`clarified_research_topic`；
- `observations`；
- `plan_iterations`、`current_plan`；
- `final_report`；
- `auto_accepted_plan`；
- `enable_background_investigation` 和背景调查结果；
- 澄清轮次、澄清历史；
- `goto`。

定义见 [`src/graph/State.py#L10-L33`](https://github.com/ipvoov/Craft-Agent/blob/3ce19c4e0382b54f27c9d04b0a723eca50687409/src/graph/State.py#L10-L33)。

计划模型包括 `Plan` 和 `Step`。每个 Step 有 `need_search`、`title`、`description`、`step_type` 和 `execution_res`，`step_type` 只有 `research` 与 `processing` 两类。Planner 通过 `has_enough_context` 决定直接进入 Reporter，还是产生待执行步骤。定义见 [`src/prompts/planner_model.py#L10-L59`](https://github.com/ipvoov/Craft-Agent/blob/3ce19c4e0382b54f27c9d04b0a723eca50687409/src/prompts/planner_model.py#L10-L59)。

这个模型的优点是简单、容易在 UI 中展示，也容易支持人工修改计划。缺点是状态粒度很粗：

- `observations` 只是字符串列表；
- 搜索结果、读取窗口、证据、claim、citation 没有独立数据模型；
- Step 的完成条件只是 `execution_res` 非空；
- 失败文本也被写入 `execution_res`，因此失败步骤同样会被视为完成；
- 没有 source snapshot、evidence ID、claim ID、verifier decision 等可审计实体。

与 NoteWeave 相比，Craft-Agent 的状态更像“Agent 对话草稿”，NoteWeave 的 `ResearchPlan`、search hit、fetched document、read window、evidence card、state ledger、verifier decision、branch 和 checkpoint 则是可持久化研究对象。

## 6. 研究执行流程

### 6.1 Coordinator：澄清需求

Coordinator 支持多轮澄清，并通过 `handoff_to_planner` 或 `handoff_after_clarification` 工具将任务转交 Planner。达到最大澄清轮数后会强制转交。它也可以根据开关先进入背景调查。

这个设计对 NoteWeave 有产品价值：研究问题不清楚时，应在高成本检索前形成明确的 research brief。NoteWeave 当前重点在后端研究执行，Craft-Agent 展示了如何把“澄清”作为图中的显式交互状态，并通过 SSE 返回前端。

### 6.2 Background Investigator：先搜一次背景

背景调查在规划前直接调用 Tavily。若 query 超过 100 字符，则先让模型压缩为搜索 query，再把搜索结果注入 Planner。源码见 [`background_investigator.py`](https://github.com/ipvoov/Craft-Agent/blob/3ce19c4e0382b54f27c9d04b0a723eca50687409/src/graph/nodes/background_investigator.py)。

这相当于“先建立问题地形，再规划”。它是一个可以借鉴的轻量技巧，但源码只执行单次搜索，没有搜索质量门、query bundle、来源类型分类或背景结果验证。

### 6.3 Planner：结构化计划与人工审阅

Planner 使用 Pydantic `Plan` 结构化输出。普通模式使用 basic model 的 `with_structured_output`，deep thinking 模式改用 reasoning model 流式输出后再修复 JSON。计划会被 `validate_and_fix_plan` 补齐 `step_type`，并可强制至少有一个联网步骤。

Human Feedback 节点通过 LangGraph `interrupt()` 暂停，接受：

- `[ACCEPTED]`，继续执行；
- `[EDIT_PLAN]`，回到 Planner 重写。

这是真实实现，不只是 README 宣称。它是项目中对 NoteWeave 最值得借鉴的部分之一，因为它把用户审阅计划变成一个明确、可恢复的图状态，而不是仅在 UI 中显示一段文本。

### 6.4 Research Team：顺序调度，不是并行多 Agent

`research_team_node` 本身只记录日志然后 `pass`。真正的调度由条件边完成：寻找第一个 `execution_res` 为空的 Step，根据 `step_type` 路由到 Researcher 或 Coder。完成后返回 Research Team，再选择下一个 Step。

所以 README 和 UI 使用“多智能体”表述，但源码执行是：

```text
第一个未完成步骤 → 单个 Researcher/Coder 执行 → 写回结果 → 下一个步骤
```

它没有：

- 同级研究分支并行；
- 子任务队列；
- worker pool；
- quorum candidate；
- branch merge；
- 独立 verifier worker；
- 动态 DAG 调度。

NoteWeave 已有 `local_parallel_scheduler`、cell task、Kafka agent command、candidate quorum、branch recovery 和 merge gate，因此执行治理明显比 Craft-Agent 深。

### 6.5 Researcher：ReAct 搜索与抓取

Researcher 为每个 research Step 临时创建一个 LangChain agent，仅绑定两个工具：

- `web_search`；
- `crawl_tool`。

它会把已完成步骤的文本拼到当前步骤 prompt 中，然后运行 ReAct 循环，最后把模型的末条消息写入 `execution_res` 和 `observations`。源码见 [`src/graph/nodes/researcher.py#L31-L58`](https://github.com/ipvoov/Craft-Agent/blob/3ce19c4e0382b54f27c9d04b0a723eca50687409/src/graph/nodes/researcher.py#L31-L58) 和 [`#L73-L213`](https://github.com/ipvoov/Craft-Agent/blob/3ce19c4e0382b54f27c9d04b0a723eca50687409/src/graph/nodes/researcher.py#L73-L213)。

这说明它确实是外部 Web Research Agent，不依赖 workspace 搜索。仓库保留了 `resources` 和 RAG 的模型/UI 残留，但后端传参、Researcher local search 和 Planner resource 使用都被注释掉了。

### 6.6 Reporter：基于 observations 一次生成报告

Reporter 将所有 observation 作为消息追加到 prompt，要求输出关键要点、概述、详细分析和末尾参考链接，然后一次调用 LLM 生成 `final_report`。见 [`src/graph/nodes/reporter.py#L21-L61`](https://github.com/ipvoov/Craft-Agent/blob/3ce19c4e0382b54f27c9d04b0a723eca50687409/src/graph/nodes/reporter.py#L21-L61)。

这里没有“报告生成后验证”节点。最终报告是否忠实于搜索结果，主要依赖 prompt 和模型自律。

## 7. 搜索 Provider

源码只实现一个文本搜索 provider：Tavily。

调用参数为：

- `search_depth="advanced"`；
- `include_raw_content=True`；
- `include_images=True`；
- `include_image_descriptions=True`；
- `include_answer=False`；
- 最大结果数来自 `config.yaml` 的 `TAVILY_MAX_RESULTS`。

见 [`src/tools/search.py#L10-L29`](https://github.com/ipvoov/Craft-Agent/blob/3ce19c4e0382b54f27c9d04b0a723eca50687409/src/tools/search.py#L10-L29)。

搜索结果后处理会：

- 按 URL 去重；
- 删除 base64 图片；
- 截断 `content` 和 `raw_content`；
- 按 provider score 降序排序。

但没有：

- provider registry；
- 多 provider fallback；
- provider health、retry、circuit breaker；
- query 级预算；
- domain allowlist/denylist 的实际配置；
- freshness、authority、source type 评分；
- 搜索结果归档和可重复快照。

还有一个实现偏差：API 请求中的 `max_search_results` 会被 Researcher 读取并记录日志，但 `web_search` 实际仍使用全局 `Config["TAVILY_MAX_RESULTS"]`，所以请求级参数没有控制真实结果数。

NoteWeave 已有 Workspace 与 external adapter 组合、Serper provider、provider chain、attempt/resolution/fallback metadata。Craft-Agent 在搜索基础设施上不比 NoteWeave 先进，OpenSERP 对这一层更有参考价值。

## 8. 网页抓取与阅读

Researcher 的抓取流程是：

```text
目标 URL → 发送给 Jina Reader → 获取 HTML → Readability 提取正文 → Markdown → 截断前 1000 字符
```

Jina Client 将任意 URL 作为 JSON 发往 `https://r.jina.ai/`，Readability 使用 `readabilipy.simple_json_from_html_string`。最终 `crawl_tool` 无条件截断到 1000 个字符，见 [`src/tools/crawl.py#L10-L29`](https://github.com/ipvoov/Craft-Agent/blob/3ce19c4e0382b54f27c9d04b0a723eca50687409/src/tools/crawl.py#L10-L29)。

优点：

- 实现很短；
- 对普通新闻、博客和文档页面容易获得干净 Markdown；
- 把直接抓取压力交给 Jina；
- Researcher 可自主决定搜哪些 URL、读哪些页面。

主要限制：

- 1000 字符不足以支持复杂 claim 的上下文验证；
- 没有窗口定位、段落坐标、字符范围或稳定快照 hash；
- 没有 PDF、动态浏览器、登录页面和附件处理；
- 没有 HTTP 重试、超时和内容大小控制；
- 抓取失败只返回错误字符串；
- 没有区分搜索 snippet、Jina 正文、原始 HTML 的证据可信等级。

NoteWeave 的 fetch adapter、read window、content origin、transport attempts、fallback reason、archive-ready snapshot 更适合证据型研究。

## 9. Citation、Evidence 与 Verifier

这是 Craft-Agent 与 NoteWeave 差距最大的部分。

### 9.1 README 和 prompt 层面的“引用”

Researcher prompt 要求跟踪来源，并在步骤结尾输出 Markdown 链接列表；Reporter prompt 要求把所有引用放到报告末尾的“关键引用”。见 [`researcher.py#L125-L132`](https://github.com/ipvoov/Craft-Agent/blob/3ce19c4e0382b54f27c9d04b0a723eca50687409/src/graph/nodes/researcher.py#L125-L132)。

### 9.2 源码事实

仓库没有独立的：

- evidence model；
- citation model；
- claim-to-evidence mapping；
- evidence ledger；
- citation verifier；
- local/global verifier；
- polarity、support、contradiction 检查；
- 报告 Citation Completeness 与 Citation Support 分层门禁；
- 来源快照归档。

搜索结果和抓取文本进入模型上下文后，最终只留下自然语言 observation。Reporter 可能复制链接，也可能遗漏、错配或生成不存在的引用，系统不会检查。

NoteWeave 已实现 evidence card、state ledger、conflict relation、local/global verifier、citation verifier、accepted/rejected evidence IDs、evidence manifest 和报告门禁。因此，Craft-Agent 不适合替代或指导 NoteWeave 的证据体系。它只能提供一个“无证据账本时，Research Agent 最小流程是什么样”的对照基线。

## 10. 持久化、中断和恢复

### 10.1 实际存在的能力

两个图都使用 LangGraph `MemorySaver`，并以 `thread_id` 支持计划审阅和网站编辑的 `interrupt()` / `Command(resume=...)` 交互。SSE 层能发送 interrupt event，并在用户选择“Edit plan”或“Start research”后恢复。

### 10.2 README/注释与实现的差距

`app.py` 的长注释称可自动选择 PostgreSQL、MongoDB 或内存 checkpointer，但源码只读取：

- `LANGGRAPH_CHECKPOINT_SAVER`；
- `LANGGRAPH_CHECKPOINT_DB_URL`。

随后两个变量没有被使用，图始终采用模块启动时创建的 `MemorySaver`。见 [`src/server/app.py#L394-L409`](https://github.com/ipvoov/Craft-Agent/blob/3ce19c4e0382b54f27c9d04b0a723eca50687409/src/server/app.py#L394-L409)。

这意味着：

- 服务进程重启后状态丢失；
- 无跨实例共享；
- 无数据库 checkpoint；
- 无 checkpoint 列表、版本、审计或输入快照；
- 无 durable retry；
- 无崩溃后的任务所有权恢复。

此外，LangGraph checkpointer 通常要求把 `thread_id` 放在 `configurable` 下，而该仓库传入的是顶层 `workflow_config["thread_id"]`。这需要实际依赖环境运行验证，但至少是一个高风险接线点。

NoteWeave 已把 checkpoint 持久化到后端数据模型，支持 `RESEARCH_LOOP_CHECKPOINT`、resume payload、round/cursor、evidence cards、ledger、verifier decision，并有 Kafka lease、callback fencing 和恢复流程。持久性和恢复能力远超 Craft-Agent。

## 11. 并发、任务队列和运行治理

Craft-Agent 的 FastAPI endpoint 在单请求内运行 LangGraph，并通过 SSE 推流。没有外部任务队列、Redis/Kafka/Celery、worker lease、heartbeat、DLQ 或 outbox。

Research Step 逐个执行，没有并行搜索分支。单个 Researcher 内部的 LangChain agent 可能连续调用工具，但这不等同于任务级并行。

运行治理缺失包括：

- 无 run/task 数据库记录；
- 无任务状态机和幂等 callback；
- 无 provider rate-limit service；
- 无 cancellation/drain；
- 无 worker instance ownership；
- 无 task priority；
- 无 budget checkpoint；
- 无重试分类和死信队列；
- 无可观测的 per-stage metrics。

NoteWeave 的 Java backend + Python worker + Kafka command/outbox 架构更复杂，但也正是为这些生产问题设计。Craft-Agent 的同步 SSE 模式适合 demo 和单机本地使用，不适合直接迁移到 NoteWeave 的多租户生产执行层。

## 12. 安全分析

### 12.1 API 与租户边界

FastAPI 没有用户认证、Workspace ACL 或 project ownership。CORS 配置为 `allow_origins=["*"]`、允许所有方法和 header，同时允许 credentials，见 [`src/server/app.py#L132-L139`](https://github.com/ipvoov/Craft-Agent/blob/3ce19c4e0382b54f27c9d04b0a723eca50687409/src/server/app.py#L132-L139)。

生成的网站源码直接挂载到 `/api/preview`，文件树和 zip 下载接口也没有鉴权。该模式只适合可信本地环境。

### 12.2 Python REPL

Coder 使用 LangChain Experimental `PythonREPL` 在服务进程内执行模型生成的 Python。没有容器级一次性 sandbox、文件系统隔离、网络限制、CPU/内存限制或 syscall policy。默认 `.env.example` 还启用 `ENABLE_PYTHON_REPL=true`。

执行点见 [`src/tools/python_repl.py#L8-L69`](https://github.com/ipvoov/Craft-Agent/blob/3ce19c4e0382b54f27c9d04b0a723eca50687409/src/tools/python_repl.py#L8-L69)。这在暴露公网或接收不可信 prompt 时属于高危能力。

### 12.3 文件与图片工具

文件工具试图用 `resolve()` + 字符串 `startswith()` 限制路径，但字符串前缀检查不能安全替代 `Path.is_relative_to()`，相似前缀目录可能绕过。见 [`src/tools/file.py#L13-L26`](https://github.com/ipvoov/Craft-Agent/blob/3ce19c4e0382b54f27c9d04b0a723eca50687409/src/tools/file.py#L13-L26)。

Pexels 下载工具直接使用模型给出的 `image_name` 拼接保存路径，没有再次 resolve 和目录边界检查，存在路径穿越风险。见 [`src/tools/image.py#L108-L120`](https://github.com/ipvoov/Craft-Agent/blob/3ce19c4e0382b54f27c9d04b0a723eca50687409/src/tools/image.py#L108-L120)。

### 12.4 Web 内容与 Prompt Injection

Jina/Readability 提取的网页内容直接进入 Researcher 上下文。系统 prompt 没有形成结构化 untrusted-content boundary，也没有 tool-output sanitization policy、来源信任级别或 prompt injection 检测。

NoteWeave 已有 HTTP security、trace security、内部服务认证、Workspace ACL、evidence ownership 和 worker 隔离边界。这些能力不能从 Craft-Agent 反向简化。

## 13. 测试和可运行性核验

本次完成了以下核验：

- Python 3.12 下 `python -m compileall -q src` 通过，说明 Python 源码至少可解析；
- 统计 Git 历史、作者、tag、固定 commit；
- 对初始 commit 与 DeerFlow 上游进行 blob 级比对；
- 检查后端测试、前端测试和构建产物；
- 对 Research 图、搜索、抓取、报告、恢复与安全路径进行静态源码审阅。

没有执行完整端到端研究，因为需要 Tavily、Jina、LLM、Pexels 等外部凭据，也没有安装它声明的 Python 3.13 运行环境。仓库没有后端 pytest，因此不存在可直接运行的后端回归套件。

前端 7 个测试文件主要覆盖 store、消息列表、JSON、Markdown 和 KaTeX，没有覆盖 Research Graph、search/fetch、checkpoint、security 或网站生成的后端契约。

## 14. 与 NoteWeave Research Agent 的实现级对比

### 14.1 相似点

| 能力 | Craft-Agent | NoteWeave |
|---|---|---|
| 独立 Python 研究执行 | FastAPI 进程内 LangGraph | 独立 `research-worker` |
| 研究规划 | Pydantic Plan/Step | ResearchPlan、query set、stop contract |
| 外部 Web 搜索 | Tavily | external search adapter/provider chain |
| 网页读取 | Jina + Readability | fetch adapter + read adapter + windows |
| 多阶段研究 | plan → search/read → report | plan → search → fetch → read → extract → verify → report |
| 人工交互 | 澄清、计划接受/编辑 | 后端任务与 UI 具备状态面，但计划审阅交互仍可加强 |
| 恢复概念 | LangGraph interrupt + MemorySaver | 持久 checkpoint + resume contract |
| 流式呈现 | SSE 传消息、工具和 interrupt | 前端/后端有 run status、timeline 和交付事件 |

用户的直觉是对的：从产品流程看，它确实比复数命名的 Craft Agents OSS 更像 NoteWeave Research Agent。

### 14.2 关键差异

| 维度 | ipvoov/Craft-Agent | NoteWeave 当前实现 |
|---|---|---|
| 搜索范围 | 外部 Web-only，Tavily 固定 | Workspace + external adapters，但 backend 当前仍强制 ready workspace source |
| 研究状态 | messages、observations、Plan 字符串 | typed hits、documents、windows、cards、ledger、branches、verdicts |
| 证据链 | prompt 要求末尾链接 | evidence ID、citation verifier、manifest、claim grounding |
| 验证 | 无 verifier | local/global/cell/citation verifier 与 merge gate |
| 并行 | 步骤顺序执行 | local parallel、cell task、candidate quorum、Kafka worker |
| 恢复 | 进程内 MemorySaver | 数据库 checkpoint、resume payload、lease/fencing |
| 调度 | 一个 FastAPI 请求内完成 | Java control plane + Python data plane + Kafka/outbox |
| 安全 | 无 auth/tenant ACL，REPL 无 sandbox | Workspace ACL、internal auth、evidence ownership、HTTP security |
| 搜索 provider | Tavily 单 provider | provider chain 与 fallback metadata，可继续接 OpenSERP |
| 产品附加能力 | 研究后可直接生成网站 | Research 报告进入 Artifact/Source 产品链路 |

### 14.3 它暴露了 NoteWeave 当前真正需要修正的点

Craft-Agent 的 Research 图从一开始就允许只给问题、直接搜索 Web。它没有 Workspace 概念，也没有要求内部 source。

在本轮改造前，NoteWeave Python worker 已经有 external adapter，甚至能在 `source_scope` 为空时运行；但它仍用 `NO_SEARCH_HITS_WITHOUT_SOURCE_SCOPE` 表达零命中，Java backend 的 `ResearchRunService.createRun()` 和增量 task coordinator 也强制至少一个 ready workspace source。也就是说：

> NoteWeave 的“只能从 Workspace source 开始”主要是 control plane 的产品契约约束，不是 Python Research Agent 的固有技术限制。

Craft-Agent 能作为一个清晰的外部 Web-only UX 对照，但无需复制它的 LangGraph 才能修正 NoteWeave。当前改造采用了显式的 `WEB_ONLY`、`WEB_PLUS_SEEDS`、`SOURCES_ONLY`：Research Run 仍强制且唯一归属于当前 Workspace，只由 retrieval mode 决定 Workspace seeds 是否参与检索。零 Web 命中现在按“没有获得证据”处理，不再把空 Workspace source scope 当成失败原因。

## 15. 值得借鉴的内容

### 15.1 高优先级：研究前澄清

把复杂问题的澄清做成显式节点，并保存：

- 原始问题；
- 澄清问题与用户回答；
- clarified research brief；
- 是否已确认。

NoteWeave 可以把它放在 Research Run 创建后、Planner 之前，而不必把整个 worker 改造成 LangGraph。

### 15.2 高优先级：计划可视化与 Human-in-the-loop

Craft-Agent 的 UI 能展示 plan，并允许“编辑计划”或“开始研究”。NoteWeave 后端已经有强大的任务结构，更应该把计划、query bundle、stop contract 和预算暴露为用户可审阅对象。

建议增加：

- `PLANNING_WAIT_USER` 状态；
- plan revision；
- accepted/edited/rejected audit；
- 用户可调整检索范围、深度、输出结构和预算；
- 超时后的默认策略。

### 15.3 中优先级：先做背景调查，再形成完整计划

Craft-Agent 的 background investigator 是一个简单但有效的两阶段规划：先用低预算搜索建立地形，再生成详细计划。NoteWeave 可以在 DEEP 模式中增加 `DISCOVERY` round，但发现结果必须进入 typed search hit 和 snapshot，而不是拼接字符串。

### 15.4 中优先级：研究过程的 SSE 可解释呈现

Craft-Agent 对 tool call、message chunk、interrupt、agent name 做了前端事件映射。NoteWeave 已有 timeline 和 run status，可以继续强化用户看到的过程：

- 当前研究问题；
- 当前 search query；
- 正在阅读的来源；
- 证据卡新增；
- verifier 缺口；
- recovery branch；
- 为什么继续或停止。

### 15.5 中优先级：Research → Artifact 的一键衔接

Craft-Agent 把 Deep Research 和 Website Generation 放在同一产品中，但两张图基本独立。它提供的是产品启发，不是理想的数据架构。

NoteWeave 已经有 Artifact worker，更合适的设计是让 Research 输出标准化 report artifact、evidence manifest 和 section contract，再由 Artifact Agent 将其转为网页、PPT、白皮书等，而不是让 Researcher 直接写站点文件。

## 16. 不应照搬的内容

- 不要用字符串 `observations` 取代 NoteWeave 的 evidence ledger。
- 不要把末尾 Markdown 链接当作 citation verification。
- 不要退回单进程 MemorySaver。
- 不要取消 Kafka/outbox、lease、fencing、idempotency 和 durable callback。
- 不要在 Research worker 主进程内运行无 sandbox Python REPL。
- 不要把所有网页截断成前 1000 字符。
- 不要将 Tavily provider 直接硬编码到 Researcher。
- 不要使用无鉴权静态 preview 和任意项目下载接口。
- 不要把“多个节点名称”描述成真正的并行 multi-agent runtime。
- 不要继承它不完整的 MIT 许可证处理方式。

## 17. 对 NoteWeave 的具体升级建议

### P0：修正搜索模式契约

在 API、数据库、task snapshot 和 worker contract 中增加明确字段：

```text
retrieval_mode = WEB_ONLY | WEB_PLUS_SOURCES | SOURCES_ONLY
```

规则：

- `WEB_ONLY`：允许空 `source_scope`，必须存在可用 external provider；
- `WEB_PLUS_SOURCES`：source 可选，内部和外部证据统一进入 ledger；
- `SOURCES_ONLY`：至少一个 ready source，禁止 external adapter；
- Workspace 始终保留为租户、ACL、配额、run/artifact 归属，不等于检索语料范围。

### P1：增加 Plan Review Gate

复用 NoteWeave 现有 run/task 模型，不引入 Craft-Agent 的内存 checkpointer：

```text
CREATED → DISCOVERING → PLAN_READY → WAITING_PLAN_APPROVAL
       → EXECUTING → VERIFYING → REPORTING → COMPLETED
```

计划修改生成新 revision，不覆盖旧计划。

### P1：增加 Discovery Round

对 DEEP 研究先运行低成本 query bundle，形成：

- 初始实体；
- 主要来源域；
- 时效范围；
- 歧义；
- 预期证据类型；
- 缺口。

Discovery 结果进入正式 snapshot，Planner 再据此生成 cell schema 和 stop contract。

### P2：统一 Research 与 Artifact 交付契约

让研究报告产出：

- report Markdown；
- section outline；
- claim/citation mapping；
- evidence manifest；
- visualizable data/table candidates；
- artifact generation brief。

然后由 Artifact worker 生成网站等产物。这样吸收 Craft-Agent 的“研究后生成网站”产品价值，同时保留 NoteWeave 的证据治理。

## 18. 是否值得加入 reference

结论：值得保留，但要正确分级。

建议在 reference catalog 中标记：

| 字段 | 建议值 |
|---|---|
| 角色 | DeerFlow 1.x 二次开发与 Research UX 样本 |
| 参考优先级 | B/C 级，不是核心架构上游 |
| 主要可借鉴 | 澄清、计划审阅、SSE Research UI、Research → Web 产品路径 |
| 不应借鉴 | 证据模型、验证、持久化、并发、队列、安全 |
| 上游依赖 | `bytedance/deer-flow` main-1.x |
| 成熟度 | 原型/小团队项目 |
| 许可证风险 | README 声称 MIT，但仓库缺失 LICENSE，应单独核验 |

如果 reference 目录只保留“最权威实现”，则应优先加入 DeerFlow 1.x，并将 Craft-Agent 作为其衍生样本。当前已经克隆的 `reference/ipvoov-Craft-Agent` 可以保留，因为它清楚展示了如何把 DeerFlow Research 流程包装成中文产品，并增加网站生成；但任何核心算法结论都应回溯 DeerFlow 1.x 源码。

## 19. 最终判断

用户说“它好像跟我们的 Research Agent 很像”，这个判断在产品链路上成立：两者都不是 Workspace 文件搜索器，而是会规划、搜索 Web、阅读、综合并输出报告的 Research Agent。

但实现层面不能高估它：

1. 它的 Research 主体来自 DeerFlow 1.x；
2. 它比 NoteWeave 更简洁，主要因为省略了 evidence、verifier、durable runtime、multi-tenant 和 task control plane；
3. 它在 UX 上比 NoteWeave 更直观，尤其是澄清、计划审阅、过程流和研究后生成网站；
4. 它不能成为 NoteWeave 证据系统和生产运行时的升级来源；
5. 它最有价值的作用，是提醒我们把 NoteWeave 已经很强的 Research backend 能力，以更自然的 Web-only / mixed-mode 入口和可审阅研究过程呈现给用户。

一句话概括：

> Craft-Agent 是一个与 NoteWeave 产品目标相近、但工程深度较浅的 DeerFlow 1.x 衍生应用。值得学它的研究交互和交付体验，不值得用它替换 NoteWeave 的 Research runtime、证据账本与治理架构。

## 20. 一手来源索引

- [Craft-Agent 固定 commit](https://github.com/ipvoov/Craft-Agent/tree/3ce19c4e0382b54f27c9d04b0a723eca50687409)
- [Craft-Agent README](https://github.com/ipvoov/Craft-Agent/blob/3ce19c4e0382b54f27c9d04b0a723eca50687409/README.md)
- [Research State](https://github.com/ipvoov/Craft-Agent/blob/3ce19c4e0382b54f27c9d04b0a723eca50687409/src/graph/State.py)
- [LangGraph Builder](https://github.com/ipvoov/Craft-Agent/blob/3ce19c4e0382b54f27c9d04b0a723eca50687409/src/graph/builder.py)
- [Planner](https://github.com/ipvoov/Craft-Agent/blob/3ce19c4e0382b54f27c9d04b0a723eca50687409/src/graph/nodes/planner.py)
- [Human Feedback](https://github.com/ipvoov/Craft-Agent/blob/3ce19c4e0382b54f27c9d04b0a723eca50687409/src/graph/nodes/human_feedback.py)
- [Researcher](https://github.com/ipvoov/Craft-Agent/blob/3ce19c4e0382b54f27c9d04b0a723eca50687409/src/graph/nodes/researcher.py)
- [Reporter](https://github.com/ipvoov/Craft-Agent/blob/3ce19c4e0382b54f27c9d04b0a723eca50687409/src/graph/nodes/reporter.py)
- [Tavily Search](https://github.com/ipvoov/Craft-Agent/blob/3ce19c4e0382b54f27c9d04b0a723eca50687409/src/tools/search.py)
- [Jina Crawler](https://github.com/ipvoov/Craft-Agent/tree/3ce19c4e0382b54f27c9d04b0a723eca50687409/src/crawler)
- [FastAPI/SSE Server](https://github.com/ipvoov/Craft-Agent/blob/3ce19c4e0382b54f27c9d04b0a723eca50687409/src/server/app.py)
- [Python REPL](https://github.com/ipvoov/Craft-Agent/blob/3ce19c4e0382b54f27c9d04b0a723eca50687409/src/tools/python_repl.py)
- [DeerFlow 上游对应 commit](https://github.com/bytedance/deer-flow/tree/b7a4b0f44610150e1f535dc70e3f57d0d5f56fcd)
- [DeerFlow 2.0 与 1.x 关系说明](https://github.com/bytedance/deer-flow/blob/a028dfd5fb70bd6e26c7dbf9e89543c7c006f9a2/README.md)
