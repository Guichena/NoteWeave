# NoteWeave v2 Memory 机制详细设计

## 1. 定位

这份文档专门定义 NoteWeave v2 的 Memory 机制。

正式口径：

`NoteWeave 的 Memory 不是资料库，也不是事实检索索引，而是一套门控式记忆晋升 + 任务邻域记忆编译机制。`

它包含两层能力：

1. `Graduated Memory`
   负责记忆从弱信号到长期可复用对象的门控晋升。

2. `Task-Neighborhood Memory Compiler`
   负责在不同任务中把记忆编译成风格约束、结构约束、禁用路径和交互策略。

一句话定义：

`Memory 机制用于沉淀跨会话可复用的用户偏好、项目口径、否定路径和交互习惯；它通过 Graduated Memory 控制写入质量，再通过 Task-Neighborhood Memory Compiler 控制回读方式，使系统在问答、Note、Wiki、产物生成和 Deep Research 中保持一致的个性化行为，同时不污染资料证据链。`

## 2. 核心边界

NoteWeave 已经有三条清晰的资料使用链路：

- 问答 RAG：基于工作台资料池做低延迟问答
- Note 链路：基于 Marginalia-style Structured Reading Funnel 做资料级候选定位和原文窗口阅读
- Wiki 链路：基于 Wiki 页面、页面链接和来源回链做稳定知识回答

因此 Memory 必须遵守以下边界：

- 不作为 Citation
- 不作为 Source Chunk
- 不参与 Evidence Rerank
- 不参与候选资料排序
- 不替代原文窗口、Wiki 页面和工作台资料池
- 不复制用户上传资料或已确认产物正文

Memory 只影响：

- 回答风格
- 输出结构
- 术语口径
- 禁用表达
- 用户偏好
- 交互策略
- 研究或生成时的软约束

这个边界很重要。它能保证三条检索链路仍然可解释，也能避免 Memory 变成另一套难维护的事实真源。

## 3. 总体架构

```text
跨会话反馈 / 项目口径 / 明确否定路径 / 产物修改 / 协作习惯
  -> Memory Signal
  -> Memory Candidate
  -> Graduated Memory Gates
       -> Evidence Gate
       -> Novelty Gate
       -> Semantic Neighborhood Gate
       -> Marginal Utility Gate
       -> Conflict & Staleness Guard
  -> Memory Object
  -> Memory Ledger
  -> Task-Neighborhood Memory Compiler
  -> 风格约束 / 结构约束 / 禁用路径 / 交互策略
```

这套设计可以概括为：

`先门控晋升，再任务编译。`

也就是说：

- 写入阶段强调 `Graduated Memory`
- 使用阶段强调 `Memory Compiler`

两者不是冲突关系，而是同一套 Memory 机制的两个阶段。

## 4. Memory 不同于工作台资料池

工作台资料池负责“内容事实”：

- 用户上传文件
- URL / 文本导入
- Deep Research 最终报告
- 用户确认保存的 Wiki / Note / FAQ / 测验
- 其他被保存为资料的系统产物

Memory 负责“行为控制”：

- 用户喜欢怎样表达
- 项目已经确认哪些口径
- 哪些方案明确不要再出现
- 不同任务应该用什么输出结构
- 当前工作台长期协作时有哪些交互习惯

因此，已确认产物默认进入工作台资料池，像用户上传文件一样参与解析、索引、引用和复用；它不默认复制进 Memory。

Memory 可以从产物修改行为中抽取信号，但不保存产物正文。

例如：

- 用户多次把报告改成“先问题、再方法、再效果”
  可以形成结构偏好 Memory。

- 用户确认“产物生成 Agent 默认绑定工作台资料池”
  可以形成项目口径 Memory。

- 用户明确说“不要 P0/P1/P2”
  可以形成 Negative Memory。

## 5. 记忆来源

### 5.1 用户明确反馈

最可靠来源。

例子：

- “这个说法可以”
- “以后不要这么写”
- “这里不要提旧方案”
- “亮点要按问题、方法、效果写”

这类信号优先进入 Memory Candidate。

### 5.2 跨会话重复偏好

用户在多个会话中反复表达的偏好。

例子：

- 中文为主，核心方法名保留英文
- 不要堆太多英文名词
- 讨论完再同步文档

这类信号需要经过 Marginal Utility Gate，避免把一次性偏好写成长期行为。

### 5.3 项目口径确认

用户已经确认的系统设计边界。

例子：

- 三种聊天链路共享工作台资料池，但检索逻辑不同
- 产物生成默认绑定工作台资料池
- Memory 不参与事实检索链路

这类 Memory 的价值是保持跨会话口径一致。

### 5.4 Negative Signal

用户明确否定的方向。

例子：

- 不要分 P0/P1/P2
- 不要在正式文档里写废弃说明
- 不要把聊天上下文写成产物生成原材料
- 不要让 Memory 参与资料检索

这类信号进入 `Negative Memory`，在生成前转成禁用路径检查。

### 5.5 产物修改行为

用户对报告、FAQ、Wiki 页面、Note 文档的修改可以形成偏好信号。

注意：

- 修改后的产物正文仍然属于资料池或 Artifact
- Memory 只提取结构偏好、风格偏好、禁用模式和项目口径

## 6. Memory 类型

### 6.1 Preference Memory

用户表达偏好。

用途：

- 控制语言风格
- 控制回答详略
- 控制是否先给结论
- 控制中英文术语比例

### 6.2 Decision Memory

项目已确认口径。

用途：

- 保持架构边界一致
- 防止后续文档写回旧口径
- 保证多会话讨论不反复推翻已确认设计

### 6.3 Negative Memory

用户明确否定过的路径。

用途：

- 生成前检查 forbidden patterns
- 文档同步前检查旧口径
- 产物生成前避免重复犯错

### 6.4 Interaction Memory

用户协作习惯。

用途：

- 决定先讨论还是直接改文档
- 决定是否需要同步所有文档
- 决定是否需要提交和推送

### 6.5 Format Memory

输出结构偏好。

用途：

- 简历亮点结构
- 报告结构
- Wiki 页面结构
- Note 整理结构

## 7. Graduated Memory Gates

`Graduated Memory` 是写入阶段的核心。

它解决的问题是：

`哪些内容值得被系统长期记住。`

### 7.1 Evidence Gate

判断候选记忆是否有明确来源。

优先级：

1. 用户明确确认
2. 用户明确否定
3. 用户多次重复偏好
4. 用户对产物的稳定修改
5. 模型推测

模型推测不能直接晋升。

### 7.2 Novelty Gate

判断候选记忆是否相对已有 Memory 有新增价值。

如果只是重复已有偏好，不新增 Memory，只更新使用次数或置信度。

### 7.3 Semantic Neighborhood Gate

判断候选记忆适用于哪些任务邻域。

任务邻域包括：

- 问答 RAG
- Note 整理
- Wiki 维护
- 产物生成
- Deep Research
- 技术架构讨论
- 简历亮点表达
- 文档同步

一条 Memory 必须有明确适用邻域，不能默认全局注入。

### 7.4 Marginal Utility Gate

判断候选记忆的边际效用。

评分维度：

| 维度 | 含义 |
|---|---|
| correction_value | 是否减少重复纠错 |
| reuse_frequency | 是否可能跨任务复用 |
| stability | 是否是稳定偏好或稳定口径 |
| scope_fit | 是否适合用户级或工作台级 |
| conflict_risk | 是否容易和已有记忆冲突 |

只有边际效用足够高的候选，才晋升为 Memory Object。

### 7.5 Conflict & Staleness Guard

判断候选记忆是否和已有 Memory 冲突，或者已有 Memory 是否过期。

状态建议：

- `ACTIVE`
- `STALE`
- `SUPERSEDED`
- `NEEDS_REVIEW`
- `DISABLED`

这能解决一个重要问题：

`旧记忆不能污染新决策。`

## 8. Memory Ledger

每条 Memory 都必须有账本。

Memory Ledger 记录：

- 来源类型
- 来源会话或文档
- 用户确认方式
- 适用任务邻域
- 晋升理由
- 被拒绝理由
- 使用次数
- 最近使用时间
- 是否被新口径覆盖
- 是否触发过修正

它的价值是：

- 记忆可解释
- 记忆可追溯
- 记忆可失效
- 记忆可调试

## 9. Task-Neighborhood Memory Compiler

`Memory Compiler` 是回读阶段的核心。

它解决的问题是：

`已经晋升的 Memory 应该如何在当前任务里使用。`

它不把 Memory 原文直接塞进 prompt，而是编译成结构化的 `Memory Control Pack`。

`Memory Control Pack` 是 Memory 和业务链路之间唯一推荐的运行时接口。

```text
当前任务
  -> 判断 Task Neighborhood
  -> 读取可用 Memory Object
  -> 过滤 STALE / SUPERSEDED / DISABLED
  -> 编译 Memory Control Pack
  -> 注入到对应链路的非证据控制区
```

Memory Control Pack 必须进入 prompt 或执行计划中的“控制区”，不能进入 evidence/context 区。

### 9.1 输入

- 当前用户
- 当前研究工作台
- 当前任务类型
- 当前回答链路
- 当前产物类型
- 可用 Memory Object
- Negative Memory
- Style Profile

### 9.2 输出

```json
{
  "pack_type": "chat | artifact | research",
  "task_neighborhood": "",
  "style_constraints": [],
  "structure_constraints": [],
  "terminology_policy": [],
  "forbidden_patterns": [],
  "evidence_policy": [],
  "interaction_policy": [],
  "review_checklist": []
}
```

### 9.3 三类 Memory Control Pack

NoteWeave 的运行时只设计三类 Control Pack，不为每个功能单独发明一套 Memory 接入。

```text
Memory Control Pack
  ├── Chat Control Pack
  │     ├── Ask
  │     ├── Note
  │     └── Wiki
  ├── Artifact Control Pack
  └── Research Control Pack
```

### 9.4 Chat Control Pack

问答、Note、Wiki 都发生在同一个聊天页面，所以不需要三套 Memory 接入。

统一链路：

```text
用户在聊天框提问
  -> Conversation Orchestrator
  -> 判断 answer_mode: ask / note / wiki
  -> Memory Compiler 生成 Chat Control Pack
  -> 进入对应回答链路
```

`Chat Control Pack` 的基础字段：

```json
{
  "pack_type": "chat",
  "answer_mode": "ask | note | wiki",
  "style_constraints": [],
  "terminology_policy": [],
  "forbidden_patterns": [],
  "interaction_policy": [],
  "evidence_policy": [
    "Memory 不作为事实来源，事实内容必须来自工作台资料池、原文窗口或 Wiki 页面"
  ],
  "review_checklist": []
}
```

三种回答链路只做轻量差异：

| answer_mode | 额外编译内容 |
|---|---|
| ask | 回答详略、语气、术语偏好、禁用表达 |
| note | 笔记结构、整理风格、摘录解释风格、禁用路径 |
| wiki | 页面命名偏好、正式表达、术语规范、禁用旧口径 |

### 9.5 Artifact Control Pack

产物生成是右侧按钮触发的独立异步任务，不和单次聊天强绑定。

统一链路：

```text
用户点击右侧产物按钮
  -> Artifact Job
  -> Resolve Production Action
  -> Load Style Profile
  -> Memory Compiler 生成 Artifact Control Pack
  -> Skill Graph / Prompt Recipe
  -> Verify / Repair
```

`Artifact Control Pack` 的典型字段：

```json
{
  "pack_type": "artifact",
  "artifact_type": "report | faq | quiz | study_guide | wiki_page | note_doc",
  "style_constraints": [],
  "structure_constraints": [],
  "audience_policy": [],
  "format_policy": [],
  "forbidden_patterns": [],
  "evidence_policy": [
    "产物事实必须来自工作台资料池或已保存为资料的系统产物",
    "Memory 不作为产物生成原材料"
  ],
  "review_checklist": []
}
```

Artifact Control Pack 只控制“怎么写”，不控制“读什么资料”。

### 9.6 Research Control Pack

Deep Research 是显式长任务，Memory 不能成为研究结论或 verifier 证据。

统一链路：

```text
用户点击 Deep Research
  -> Research Run
  -> Memory Compiler 生成 Research Control Pack
  -> Research Planning
  -> Search / Read / Extract / Verify loop
  -> Final Report Synthesis
```

`Research Control Pack` 的典型字段：

```json
{
  "pack_type": "research",
  "research_preferences": [],
  "report_structure_policy": [],
  "forbidden_patterns": [],
  "evidence_policy": [
    "Memory 不作为研究证据",
    "研究结论必须由搜索结果、工作台资料或验证证据支持"
  ],
  "review_checklist": []
}
```

Research Control Pack 可以影响研究报告结构和表达方式，但不能影响证据真假判断。

### 9.7 关键原则

- 问答时不注入事实型 Memory
- Note 时不让 Memory 影响候选资料排序
- Wiki 时不让 Memory 覆盖页面内容
- 产物生成时不把 Memory 当原材料
- Deep Research 时不把 Memory 当研究结论

Memory 只控制表达和行为。

## 10. 与系统功能的结合

### 10.1 聊天页统一接入

问答、Note、Wiki 都在统一聊天页进行，因此统一接入 `Chat Control Pack`。

区别不在于 Memory 接入三次，而在于 `answer_mode` 不同：

- `ask` 使用问答 RAG 链路
- `note` 使用结构化阅读漏斗
- `wiki` 使用 Wiki-first 回答逻辑

Memory Compiler 只生成一份 Chat Control Pack，再由对应链路读取其中适合自己的字段。

### 10.2 问答 RAG

事实来源是工作台资料池、Source Chunk 和 Citation。

Memory 只通过 Chat Control Pack 控制回答风格、术语偏好、禁用表达和用户偏好。

Memory 不参与检索召回、rerank 和 citation。

### 10.3 Note 链路

事实来源是候选资料、原文窗口和摘录卡片。

Memory 只通过 Chat Control Pack 控制笔记结构、整理风格、禁用旧方案和用户偏好的表达方式。

Memory 不参与候选资料排序、原文窗口选择和摘录事实生成。

### 10.4 Wiki 链路

事实来源是 Wiki 页面、页面链接和来源回链。

Memory 只通过 Chat Control Pack 控制页面命名偏好、页面组织风格、禁用旧口径和展示方式。

Memory 不参与 Wiki 页面检索、页面事实覆盖和来源回链替代。

### 10.5 产物生成 Agent

原材料：

- 当前研究工作台资料池
- 已保存为资料的系统产物

Memory 通过 Artifact Control Pack 控制：

- Style Profile 默认值
- 报告结构
- 受众和语气
- 禁用路径
- 交互策略

Memory 不作为产物生成原材料。

### 10.6 Deep Research

事实来源：

- 外部搜索
- 工作台资料
- 研究过程中的证据验证

Memory 通过 Research Control Pack 控制：

- 研究偏好
- 报告结构
- 输出风格
- 用户明确禁止的表达方式

Memory 不作为研究结论，也不作为 verifier 的事实依据。

## 11. 数据模型建议

### 11.1 `memory_signal`

用途：

- 保存弱信号。

关键字段：

- `id`
- `workspace_id`
- `user_id`
- `source_type`
- `source_id`
- `signal_type`
- `signal_text`
- `task_neighborhood`
- `confidence_score`
- `created_at`

### 11.2 `memory_candidate`

用途：

- 保存待门控的候选记忆。

关键字段：

- `id`
- `workspace_id`
- `user_id`
- `candidate_type`
- `normalized_statement`
- `task_neighborhood_json`
- `evidence_gate_status`
- `novelty_score`
- `marginal_utility_score`
- `negative_memory_flag`
- `conflict_status`
- `staleness_status`
- `created_from_signal_ids_json`
- `review_status`
- `created_at`
- `updated_at`

### 11.3 `memory_object`

用途：

- 保存已晋升 Memory。

关键字段：

- `id`
- `workspace_id`
- `user_id`
- `memory_type`
- `memory_scope`
- `canonical_statement`
- `task_neighborhood_json`
- `compile_policy_json`
- `forbidden_pattern_json`
- `ledger_json`
- `status`
- `created_at`
- `updated_at`

### 11.4 `memory_usage_log`

用途：

- 记录 Memory 如何被使用。

关键字段：

- `id`
- `memory_object_id`
- `workspace_id`
- `task_type`
- `target_type`
- `target_id`
- `compiled_as`
- `used_at`
- `effect_feedback`

## 12. 运行流程

### 12.1 写入流程

```text
用户反馈 / 明确确认 / 明确否定 / 产物修改 / 项目口径变更
  -> 抽取 Memory Signal
  -> 形成 Memory Candidate
  -> Evidence Gate
  -> Novelty Gate
  -> Semantic Neighborhood Gate
  -> Marginal Utility Gate
  -> Conflict & Staleness Guard
  -> 写入 Memory Object
  -> 记录 Memory Ledger
```

### 12.2 回读流程

```text
任务开始
  -> 判断 task_neighborhood
  -> 读取可用 Memory Object
  -> 过滤 STALE / SUPERSEDED 记忆
  -> 编译为 style_constraints / structure_constraints / forbidden_patterns
  -> 注入生成计划或回答策略
  -> 生成后记录 usage log
```

## 13. 亮点表述

正式亮点可以写成：

`面向研究工作台中多会话、多链路、多产物协作时用户偏好难保持、表达口径易漂移、否定方案反复出现和旧记忆污染新决策的问题，设计门控式任务邻域记忆机制：通过 Graduated Memory 将用户反馈、项目口径、产物修改和 Negative Memory 从弱信号逐步晋升为可追溯 Memory Object，再通过 Task-Neighborhood Memory Compiler 编译为三类 Memory Control Pack：面向统一聊天页的 Chat Control Pack、面向右侧产物生成的 Artifact Control Pack、面向 Deep Research 的 Research Control Pack；Memory 不作为事实来源或检索索引，从而在不污染证据链的前提下保持系统行为一致。`

## 14. 实现边界

当前需要做：

- Memory Signal
- Memory Candidate
- Graduated Memory Gates
- Negative Memory
- Staleness Guard
- Memory Ledger
- Task-Neighborhood Memory Compiler

当前不做：

- Memory 参与事实检索
- Memory 作为 Citation
- Memory 自动覆盖 Wiki 页面
- Memory 自动修改工作台资料
- Memory 复制产物正文
- 复杂可视化 Memory 管理后台
