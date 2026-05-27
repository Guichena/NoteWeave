# 文件：07_Artifact_Skill_Agent边界加深版.md

## 0. 本篇定位

这篇是 `07_Artifact_Skill_Agent边界.md` 的加深版，只补 workflow 与 Agent 的本质差异、Methodology 为什么是治理层、Artifact 生命周期治理，以及开放 Agent 未来需要补的控制面。

因此这里不再重复保存标准版里的 Artifact / Skill 主答。普通版 `07` 负责把当前事实和风险词边界讲顺；这篇负责在面试官继续追问“你们到底算不算 Agent”时，把边界说得更稳。

## 1. 这篇只补哪些深度

普通版 `07` 已经覆盖：

- Artifact 生成主链路。
- Skill 是受控工作流步骤，不是开放插件系统。
- MethodologyCard 提供 workflow、结构和质量检查。
- MCP / Bibtex / GraphRAG 的当前边界。

这篇额外补的是：

- Workflow、Tool、Agent 到底差在哪。
- 为什么当前阶段更适合受控 pipeline，而不是开放式自主规划。
- Artifact 为什么一定要和长期知识之间隔一层治理。
- 如果未来做开放 Agent，需要补哪些治理能力。

## 2. Workflow、Tool、Agent 不是一回事

### 2.1 Workflow 更强调“系统先定义步骤”

也就是：

```text
先加载上下文
-> 再选证据
-> 再生成内容
-> 再保存版本
```

它的核心优点是：

- 可预测。
- 可测试。
- 可取消。
- 可审计。

### 2.2 Tool 更强调“某个能力的调用接口”

Tool 或 Skill 的本质是一个带输入输出契约的能力单元。它可以被 workflow 调用，也可以在未来被更开放的 agent 调用。

### 2.3 Agent 更强调“模型自主决定下一步”

一旦进入 Agent 叙事，系统就要允许模型做更多事情：

- 规划步骤。
- 选择工具。
- 看工具结果再决定后续动作。
- 直到达到目标或终止。

所以 Agent 的难点从来不只是“会不会调工具”，而是“怎么把自主性关进治理笼子里”。

## 3. 为什么当前项目更适合受控 pipeline

NoteWeave 当前更强调：

- 证据优先。
- 过程可追溯。
- 生成结果可版本化。
- 失败可定位。
- 权限和预算可控。

这些诉求天然更适合固定 plan + Skill 执行，而不是让模型自由游走。

换句话说，当前阶段更在乎：

```text
稳
-> 可控
-> 可观测
-> 可沉淀
```

而不是“模型自主性越强越好”。

## 4. Methodology 为什么不是普通 Prompt 模板

如果把 MethodologyCard 只理解成 prompt 模板，会低估它的治理价值。

它更像一层“生成策略配置”：

- workflow 怎么组织。
- 输出结构怎么约束。
- 质量检查怎么显式化。
- 不同项目 / space / preset 如何复用和覆盖。

这意味着它不是把 prompt 写死在代码里，而是把生成结构显式化、可管理化、可演进化。

这类设计在面试里很加分，因为它说明你不是只会“调词”，而是会把 AI 生成行为收成系统可治理对象。

## 5. 为什么 Artifact 和长期知识之间必须隔一层

Artifact 是成果，长期知识是被确认过、希望反复复用的稳定内容。这两者不隔开，风险很大：

- 生成时的幻觉会直接污染知识库。
- 暂时结构化不好的内容也会进入长期沉淀。
- 用户还没确认的草稿会被系统误当事实。

所以中间必须要有：

- proposal / preview。
- confirm / publish。
- artifactVersion 绑定。
- citation 复制或重建。
- stale version 校验。

这才说明你真的理解“生成”与“沉淀”不是一回事。

## 6. 如果未来要做开放 Agent，需要补什么

这是面试里特别容易被带偏的点。更稳的回答不是“以后接 MCP 就行”，而是明确说要补控制面：

- tool schema 与参数校验。
- tool permission 与可见性边界。
- sandbox / side-effect 控制。
- max steps / max tokens / timeout / budget。
- tool call log / observation log。
- 失败状态、回滚和人工接管。
- eval 和 bad case 回归。

这时再说 MCP、开放工具或多 Agent，才不会显得浮。

## 7. 风险词怎么讲才不显得虚

### 7.1 被问“这是 Agent 吗”

稳的说法是：当前更准确地讲是受控 workflow / Skill pipeline。它已经具备部分 agent-like 元素，比如多步生成和上下文选择，但还不是完整开放 Agent 平台。

### 7.2 被问“为什么不用 LangChain / LangGraph / Spring AI”

不要急着站队框架。更稳的是：

- 当前核心难点在业务边界、引用关系、任务状态、治理和沉淀，不在于是不是用了某个 orchestration 框架。
- 框架可以辅助，但不应该替代系统边界本身。

## 8. 边界、不能说满和扩展方向

- 这篇只补 workflow 与 Agent 的本质差异和治理控制面，不再重复标准版主链路。
- 当前可以坚定讲：ArtifactVersion、SkillExecutionLog、Methodology、proposal/confirm、受控 workflow。
- 当前不要讲成：完整开放 Agent、完整开放式 MCP 平台主链路、ReAct 主链路、GraphRAG 主链路已经落地。更准确的说法是 MCP 已有远程 B 站 tool service 这个受控落地样例。
- 扩展方向可以讲：未来在工具 schema、权限、预算和评测补齐后，再逐步开放更自主的工具调用。

## 9. 继续追问怎么接

如果面试官继续往下压，这一题最稳的承接顺序是：

```text
先讲 Workflow / Tool / Agent 的区别
-> 再讲为什么当前更适合受控 pipeline
-> 再讲 Methodology 和 Artifact 沉淀治理
-> 最后讲未来开放 Agent 需要补的控制面
```

这样你给出的不是“概念热闹”，而是清晰的演进边界。
