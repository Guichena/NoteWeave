# 受控式异步产物生成 Agent 编排升级设计

## 1. 目的

这份文档在 [改造计划/受控式异步产物生成智能体架构设计.md](D:/java-projects/NoteWeave-v2/改造计划/受控式异步产物生成智能体架构设计.md) 的基础上，吸收 2026 年关于 agent 编排、skill 组合、tool / MCP 调度的最新设计思想，给出更适合 NoteWeave 的升级方案。

目标不是把产物生成模块做成“大而全 Agent 平台”，而是让右侧产物栏背后的 Agent：

- 更可控
- 更可扩展
- 更容易接入用户风格配置
- 更容易接入用户自定义 MCP
- 更适合异步长任务与版本化产物体系

在工程实现上，这个 Agent 更适合作为：

- `Java` 主系统调度的独立 `Python Artifact Worker`

也就是说：

- `Java` 负责 `Artifact Job` 主对象、任务状态、结果落库与显式回写
- `Python Artifact Worker` 负责 skill 编排、tool / MCP 调度、结构校验、局部修复与最终产物草稿生成

## 2. 输入边界

产物生成 Agent 默认绑定当前研究工作台，而不是绑定某一次会话。

它的输入需要拆成三类，避免把内容来源、生成目标和运行能力混在一起。

### 2.1 原材料

原材料只指 Agent 真正读取、整理和引用的内容来源：

- 当前研究工作台资料池
- 工作台内已经保存为资料的系统产物
- 资料对应的解析文本、索引片段、标题、摘要、标签、元数据和引用信息

其中，系统产物只有在用户明确保存为资料后，才会和用户上传文件一样进入解析、索引、引用和后续复用链路。

### 2.2 生成要求

生成要求描述用户这次希望系统产出什么，不属于原材料：

- 产物类型：报告、FAQ、测验、学习指南、Wiki 页面、结构化笔记
- 风格配置：篇幅、语气、结构、受众、引用密度和输出格式
- 回写方式：仅生成草稿、保存为产物版本、导出文件或保存为资料

### 2.3 运行能力

运行能力描述 Agent 可以用什么方式完成任务，也不属于原材料：

- 内置 Skill
- 内置 MCP 能力
- 用户自定义 MCP 能力
- Prompt Recipe
- 输出 schema、证据约束和局部修复策略

正式口径是：

`产物生成 Agent 以当前研究工作台资料池和已保存为资料的系统产物作为原材料；Production Action 与 Style Profile 是本次任务的生成要求；Skill、MCP、Prompt Recipe 与 schema gate 是运行能力和执行约束。`

## 2. 先给结论

NoteWeave 的产物生成 Agent 不建议走“一个大 Agent 自己规划一切”的路线，而更适合升级为：

`Production Action + Style Profile + Skill Graph + MCP Capability + Policy Gate`

其中：

- `Production Action`
  面向用户的产物动作，例如报告、测验、学习指南、FAQ、Wiki 页面

- `Style Profile`
  用户可配置的写作风格、输出结构、篇幅和受众偏好

- `Skill Graph`
  面向任务执行的可组合技能图，而不是平铺技能列表

- `MCP Capability`
  对外部工具的标准能力抽象，而不是把所有 MCP 工具直接暴露给模型

- `Policy Gate`
  对 schema、权限、审批、证据和回写行为做统一约束

这套设计的核心不是“让 Agent 更自由”，而是“让编排层更强”。

## 3. 2026 最值得吸收的设计思想

### 3.1 Skill Graph 替代平铺 Skill 列表

2026 年一个很明显的趋势是：skill 不再适合只做语义检索后的平铺列表，而更适合组织成带依赖关系的图结构。

这背后的启发是：

- skill 之间存在前置关系
- skill 之间存在增强关系
- skill 之间存在冲突关系
- 调度时应该检索“技能子图”，而不是检索“技能 top-k 列表”

对 NoteWeave 的意义：

- 报告生成不是单个 skill，而是一组可组合 skill
- 测验生成不是单个 prompt，而是一条可验证的技能链
- Wiki 页面生成需要结构化大纲、引用检查、风格改写等多个节点协同

因此，NoteWeave 不建议继续把 `Skill Registry` 理解成简单 skill 列表，而建议升级为：

`Skill Registry + Skill Dependency Graph`

### 3.2 Skill Program 替代纯 Prompt Skill

2026 年另一个有价值的方向是：skill 不只是 prompt 模板，而是可执行的技能程序。

对 NoteWeave 来说，skill 更适合作为：

- 一个具备输入契约的节点
- 一个带前置条件的执行器
- 一个带输出契约的中间能力
- 一个可决定是否触发 verifier / repair 的程序单元

也就是说，下面这些都更适合被设计成 `Skill Program`：

- `outline_planner`
- `evidence_synthesizer`
- `citation_checker`
- `style_rewriter`
- `quiz_difficulty_normalizer`
- `wiki_structure_enforcer`

这样 skill 就不只是“另一段提示词”，而是运行时可编排、可替换、可记录的能力节点。

### 3.3 Schema-Gated Orchestration

2026 很值得借鉴的一个名词是：

`Schema-Gated Orchestration`

它的核心思想是：

- 用户可以自然语言表达目标
- 模型可以自由理解和补全计划
- 但真正进入执行的工作流，必须先通过 schema 校验

这不是只校验单个 tool call，而是校验整条执行计划。

对 NoteWeave 的落地方式：

- 用户点击右侧产物栏按钮
- 系统先生成 `Artifact Execution Plan`
- 计划要包含 action、style、skills、needed capabilities、evidence requirement、output contract
- 只有整个计划通过 schema gate，任务才进入 `ArtifactJob`

这会显著提升：

- 可解释性
- 可回放性
- 权限控制
- 失败定位能力

### 3.4 Lazy Capability Loading

2026 关于 tool orchestration 的一个重要结论是：

最大问题往往不是“模型不会选工具”，而是“把太多工具定义一次性塞给模型”。

因此更合理的做法是：

- 先只给模型能力摘要
- 运行时按 action 和 skill graph 筛能力
- 只把本次真正需要的 MCP tool schema 提升到完整上下文
- 剩余工具保留 summary，不加载 full schema

对 NoteWeave 来说，这意味着：

- 不应该把全部 MCP server 的全部 tools 暴露给 Artifact Runtime
- 应该先做标准 capability 层
- 再由 runtime 只加载本次需要的少量 tools

这可以直接降低：

- 上下文长度
- 工具选择干扰
- prompt injection 面积
- 用户自定义 MCP 的失控风险

### 3.5 Local Repair 替代全局重跑

2026 的图结构编排普遍强调：失败时不要全局重跑，而是做局部修复。

这对产物生成非常重要。

比如：

- 大纲结构错了，不需要重新抓内容
- 引用缺失，不需要重做全文生成
- 风格不符合，不需要重跑 retrieval

因此 Artifact Runtime 更适合把 repair 做成局部节点：

- `outline_repair`
- `citation_repair`
- `style_repair`
- `section_regeneration`

而不是失败就直接整个 job 重跑。

### 3.6 Capability Union Policy

2026 在安全方向一个很关键的新思想是：

风险对象不是单个 skill，也不是单个 tool，而是“当前会话里已安装能力的并集”。

对 NoteWeave 特别重要，因为你希望支持用户自定义 MCP。

因此权限模型不应该只问：

- 这个 MCP 能不能接

还要问：

- 它和当前 Action 组合后会不会越权
- 它和当前 Skill Graph 组合后会不会形成数据外泄路径
- 它和当前 Workspace 数据权限组合后会不会突破边界

因此建议引入：

`Capability Union Policy`

专门评估：

- 当前 Action
- 当前 Skill Graph
- 当前 MCP capabilities
- 当前 workspace scope
- 当前 writeback target

这些能力合起来是否安全。

## 4. 对现有架构的升级方式

不是推翻旧设计，而是在旧设计上做五个升级。

### 4.1 从 Prompt Recipe 升级到双层配置

原设计里已有 `Prompt Recipe`，这是好的，但还不够。

建议升级为：

- `Style Profile`
  负责“怎么写”

- `Prompt Recipe`
  负责“当前产物节点怎么生成”

`Style Profile` 推荐包含：

- 语气：学术 / 咨询 / 教学 / 简洁
- 结构：先结论 / 先背景 / 问答式 / 大纲式
- 篇幅：短 / 中 / 长
- 证据密度：高 / 中 / 低
- 面向对象：自己 / 团队 / 面试汇报 / 对外客户

这样用户配置风格时，不需要复制一整套 skill 或 action。

### 4.2 从 Skill Registry 升级到 Skill Graph Registry

建议把现有 `Skill Registry` 升级为：

- `Skill Definition`
- `Skill Edge`
- `Skill Pack`
- `Skill Graph Template`

一个 skill 至少要描述：

- `skill_key`
- `skill_version`
- `skill_type`
- `input_contract`
- `output_contract`
- `required_capabilities`
- `verifier_policy`
- `allowed_repair_modes`

skill edge 推荐至少支持：

- `PREREQUISITE`
- `ENHANCE`
- `OPTIONAL`
- `CONFLICT`

### 4.3 从 Universal Graph 升级到 Schema-Gated Skill Graph Runtime

原有主链路仍然保留：

```text
Validate Input
  -> Resolve Action
  -> Normalize Input
  -> Plan Content Acquisition
  -> Acquire Content
  -> Build CCO
  -> Compile Context
  -> Run Prompt Recipe
  -> Verify / Repair
  -> Commit Artifact
  -> Index Artifact
```

但中间的 `Run Prompt Recipe` 不再只是单次 prompt 执行，而是升级为：

```text
Build Execution Plan
  -> Schema Gate
  -> Build Skill Subgraph
  -> Load Needed Capabilities
  -> Execute Skill Nodes
  -> Node-Level Verify
  -> Local Repair
  -> Final Artifact Commit
```

这就是：

`Schema-Gated Skill Graph Runtime`

### 4.4 从 MCP Tool 直连升级到 Capability Layer

用户未来可以添加 MCP，但 Artifact Runtime 不应该直接面对工具原名。

建议统一为 capability：

- `READ_WEB_PAGE`
- `SEARCH_WEB`
- `EXTRACT_TRANSCRIPT`
- `TRANSCRIBE_AUDIO`
- `READ_WORKSPACE_DOC`
- `EXPORT_FILE`
- `TABULAR_ANALYZE`

运行时只声明需要 capability，不直接声明工具名。

再由 `Capability Resolver` 去决定：

- 选哪个 MCP server
- 选哪个具体 tool
- 是否需要 approval
- 是否允许网络出站

### 4.5 从单次权限判断升级到 Policy Gate

建议在 Artifact Runtime 新增四类 gate：

1. `Schema Gate`
   校验执行计划是否合法

2. `Capability Gate`
   校验本次任务是否允许使用对应 capability

3. `Approval Gate`
   对高风险 MCP / 外部写入 / 导出行为要求审批

4. `Evidence Gate`
   控制哪些产物类型必须满足最小引用要求

如果将来支持回写，再补：

5. `Writeback Gate`
   控制是否允许写回 Note / Wiki / Memory Candidate

## 5. NoteWeave 版本的推荐对象模型

### 5.1 Production Action

面向用户的产物入口对象。

关键字段建议包括：

- `action_key`
- `display_name`
- `artifact_type`
- `default_style_profile_id`
- `default_skill_graph_id`
- `default_prompt_recipe_id`
- `required_evidence_level`
- `allow_custom_mcp`
- `allow_writeback`

### 5.2 Style Profile

面向用户风格配置。

关键字段建议包括：

- `profile_key`
- `profile_name`
- `tone`
- `structure_mode`
- `audience_type`
- `length_preference`
- `citation_density`
- `format_constraints_json`

### 5.3 Skill Definition

关键字段建议包括：

- `skill_key`
- `skill_version`
- `skill_type`
- `input_contract_json`
- `output_contract_json`
- `required_capabilities_json`
- `verifier_policy_json`
- `repair_policy_json`

### 5.4 Skill Graph Template

关键字段建议包括：

- `graph_key`
- `graph_name`
- `action_type`
- `nodes_json`
- `edges_json`
- `schema_contract_json`

### 5.5 MCP Capability Binding

关键字段建议包括：

- `capability_name`
- `server_id`
- `tool_name`
- `scope_type`
- `approval_mode`
- `risk_level`
- `allowed_actions_json`

### 5.6 Capability Union Policy

关键字段建议包括：

- `policy_key`
- `action_scope`
- `skill_scope`
- `capability_scope`
- `workspace_scope`
- `decision`
- `reason_code`

## 6. 推荐执行流程

```text
用户在当前研究工作台点击右侧产物栏
  -> Resolve Production Action
  -> Load Style Profile
  -> Resolve Workspace Material Scope
  -> Build Artifact Execution Plan
  -> Schema Gate
  -> Expand Skill Graph Template
  -> Resolve Needed MCP Capabilities
  -> Capability Union Policy Check
  -> Lazy Load Tool Schemas
  -> Execute Skill Nodes
  -> Node-Level Verify
  -> Local Repair
  -> Commit Artifact Version
  -> Optional Writeback / Retrieval Feedback / Memory Candidate
```

这条链路里最关键的三个新节点是：

- `Schema Gate`
- `Lazy Capability Loading`
- `Capability Union Policy`

## 7. 哪些能力适合先做成内置 Skill

为了避免过度设计，第一批只建议内置少量高价值 skill：

- `outline_planner`
- `evidence_synthesizer`
- `report_writer`
- `study_guide_writer`
- `quiz_designer`
- `faq_writer`
- `wiki_drafter`
- `citation_checker`
- `style_rewriter`
- `section_repair`

这些 skill 已经足够覆盖当前几条亮点主线，不需要再扩成通用 skill 市场。

## 8. 用户自定义 MCP 应该怎么做

用户可以添加 MCP，但建议走三段式。

### 8.1 接入

- 填写 server 信息
- 健康检查
- 拉取工具清单
- 风险扫描
- 标记 read / write / external network

### 8.2 授权

- 勾选允许使用的 tools
- 配置 approval mode
- 配置 workspace scope
- 配置 action scope

### 8.3 绑定

- 绑定到具体 capability
- 绑定到具体 Production Action 或 Skill Graph
- 默认不全局开放给所有生成任务

这样用户添加的是“能力来源”，不是直接给模型一套万能工具。

## 9. 最值得写进项目亮点的升级点

如果后面要把这部分写进亮点，最值得强调的不是“支持 MCP”，而是下面三个点：

1. `Schema-Gated Skill Graph Runtime`
   说明你不是在堆 tool，而是在做受控编排运行时

2. `Style Profile + Skill Graph + Capability Layer`
   说明你把用户风格、任务模板和外部能力明确拆层了

3. `Capability Union Policy`
   说明你在支持用户自定义 MCP 的同时，考虑了组合权限风险

## 10. 对 NoteWeave 最合适的一句话定位

`NoteWeave 的受控式异步产物生成 Agent 默认绑定研究工作台，以工作台资料池和已保存为资料的系统产物作为原材料；用户从右侧产物栏选择 Production Action 与 Style Profile 后，系统通过 Schema-Gated Skill Graph Runtime 动态展开技能子图、按需加载 MCP 能力，并通过 schema、approval、evidence 与 capability union policy 约束执行过程，从而在保证可扩展性的同时避免系统演化为失控的通用 Agent 平台。`

## 11. 2026 参考资料

### 11.1 论文

- GraSP: Graph-Structured Skill Compositions for LLM Agents
  [https://arxiv.org/abs/2604.17870](https://arxiv.org/abs/2604.17870)

- Talk Freely, Execute Strictly: Schema-Gated Agentic AI for Flexible and Reproducible Scientific Workflows
  [https://arxiv.org/abs/2603.06394](https://arxiv.org/abs/2603.06394)

- Measuring Compositional Risk in Agent Skill Ecosystems
  [https://arxiv.org/abs/2606.00448](https://arxiv.org/abs/2606.00448)

- Self-Evolving Typed Skill Graphs for LLM Skill Selection at Scale
  [https://arxiv.org/html/2606.03056v1](https://arxiv.org/html/2606.03056v1)

- Accelerating LLM Agents via Pattern-Aware Speculative Tool Execution
  [https://arxiv.org/html/2603.18897v1](https://arxiv.org/html/2603.18897v1)

### 11.2 官方设计资料

- Microsoft Agent Framework Overview
  [https://learn.microsoft.com/en-us/agent-framework/overview/](https://learn.microsoft.com/en-us/agent-framework/overview/)

- Workflow orchestrations in Agent Framework
  [https://learn.microsoft.com/en-us/agent-framework/workflows/orchestrations/](https://learn.microsoft.com/en-us/agent-framework/workflows/orchestrations/)

- Azure AI Agent Orchestration Patterns
  [https://learn.microsoft.com/en-us/azure/architecture/ai-ml/guide/ai-agent-design-patterns](https://learn.microsoft.com/en-us/azure/architecture/ai-ml/guide/ai-agent-design-patterns)

- Agent Executor, Google's distributed Agent Runtime
  [https://cloud.google.com/blog/products/ai-machine-learning/agent-executor-googles-distributed-agent-runtime](https://cloud.google.com/blog/products/ai-machine-learning/agent-executor-googles-distributed-agent-runtime)

- I/O '26 news for agent developers on Google Cloud
  [https://cloud.google.com/blog/topics/developers-practitioners/io26-news-for-agent-developers-on-google-cloud](https://cloud.google.com/blog/topics/developers-practitioners/io26-news-for-agent-developers-on-google-cloud)
