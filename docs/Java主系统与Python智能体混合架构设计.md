# NoteWeave v2 Java 主系统与 Python 智能体混合架构设计

## 1. 目的

这份文档用于正式确定 NoteWeave v2 的实现分工：

- 哪些部分继续放在 `Java` 主系统中
- 哪些部分拆成独立的 `Python` 智能体执行器
- 这两部分之间如何协作

这份文档不重新定义产品边界，而是回答一个工程问题：

`当 Deep Research 智能体与受控式异步产物生成智能体的执行流程相对独立，且只在结果回写与知识链路上发生关系时，是否适合从主系统中拆出，作为独立 Python 执行器实现。`

结论是：`适合。`

## 2. 最终架构结论

NoteWeave v2 推荐采用下面这套混合架构：

- `Java 主系统`
  承担工作区、资料、知识对象、权限、任务真源、回写链路与前后端接口。

- `Python Research Worker`
  承担 `Deep Research` 的研究循环、工具调用、搜索、验证、纠偏与研究报告合成。

- `Python Artifact Worker`
  承担右侧产物栏背后的 `Artifact Agent` 执行，包括 skill 编排、MCP 调度、结构约束、局部修复与产物生成。

这套架构的核心判断不是“Python 更高级”，而是：

- 三种聊天回答链路与业务主链路高度耦合，适合留在 `Java`
- 两个智能体的执行流程高度独立、策略变化快、实验性强，更适合放在 `Python`
- 两个智能体和主系统真正发生耦合的地方，主要是：
  - 任务创建
  - 输入快照
  - 运行事件
  - 最终产物回写

也就是说：

`模式层放 Java，智能执行层拆 Python，是最符合当前结构化笔记Weave 真实场景的做法。`

## 3. 为什么这次拆分是合理的

## 3.1 三种聊天链路不适合拆到 Python

`问答链路`、`Note 链路`、`Wiki 链路` 本质上是统一聊天前端背后的业务回答链路，而不是独立智能体。

它们的特点是：

- 强依赖工作区模型
- 强依赖权限和回写
- 强依赖 `资料 / Note / Wiki / Citation / Version`
- 强依赖低延迟 API 与前端状态同步

因此它们更适合继续放在 `Java + Spring Boot` 主系统中。

## 3.2 两个智能体更适合拆到 Python

`Deep Research` 和 `Artifact Agent` 则不同。

它们的共同点是：

- 都是长任务
- 都有复杂执行循环
- 都依赖模型策略、tool orchestration、skill / MCP 组合
- 都需要频繁试验 prompt、skill、verifier 与 repair
- 都更容易从现有 Python 参考项目中吸收实现经验

因此，把这两条执行链路拆到 Python 更有现实收益：

- 复用 Python agent 生态更方便
- 策略试错成本更低
- 研究型逻辑和业务型逻辑分层更清楚

## 3.3 为什么可以拆成两个独立执行器

你的判断是成立的：

- `Research Agent` 的主流程基本独立
- `Artifact Agent` 的主流程基本独立
- 它们和系统主链路真正打通的地方，主要在结果回写

这意味着二者不需要和 `Ask / Note / Wiki` 共用一套执行内核。

更准确地说：

- `Research Agent` 独立负责研究型长任务
- `Artifact Agent` 独立负责生成型长任务
- 它们都把结果回传给 Java 主系统
- Java 再负责写回 `Artifact / Note / Wiki`

## 4. 总体混合架构

```text
Frontend
  -> Java API App
      -> MySQL
      -> Redis
      -> Kafka
      -> MinIO
      -> Elasticsearch
      -> SSE / WebSocket
  -> Python Research Worker
      -> OpenAI SDK / Search Tools / MCP Tools
  -> Python Artifact Worker
      -> OpenAI SDK / Skill Runtime / MCP Tools
```

更完整的职责分层：

```text
NoteWeave v2
  ├── Java 主系统
  │     ├── Ask / Note / Wiki 三种聊天链路
  │     ├── Workspace / Topic Scope / Material / Knowledge
  │     ├── Task / Outbox / Trace 真源
  │     ├── Artifact / Note / Wiki 回写
  │     └── API / SSE / 权限 / 状态管理
  ├── Python Research Worker
  │     ├── Planner
  │     ├── Search
  │     ├── Read
  │     ├── Extract
  │     ├── Verify
  │     ├── Repair / Branch
  │     └── Synthesis
  └── Python Artifact Worker
        ├── Action Resolver
        ├── Context Compiler
        ├── Skill Graph Runtime
        ├── Capability Resolver
        ├── Schema Gate
        ├── Verifier / Repair
        └── Artifact Result Builder
```

## 5. Java 主系统职责

以下内容必须留在 `Java`：

- 用户体系
- 工作区与主题边界
- 文件上传与资料管理
- 问答链路、Note 链路、Wiki 链路
- `资料 / Note / Wiki / Artifact` 的主业务对象
- `Research Run / Artifact Job` 的任务真源
- MySQL 持久化
- Kafka 投递
- Redis 进度快照
- SSE / WebSocket 反馈
- 最终结果回写
- 权限控制与审计

一句话：

`Java 负责业务真源、系统编排与用户可见聊天链路。`

## 6. Python Research Worker 职责

`Python Research Worker` 只负责 `Deep Research` 长任务执行。

它承担：

- 研究问题重写
- 宽搜索
- 网页 / PDF / 文档读取
- 证据抽取
- 字段填充
- 局部验证
- 反证分支
- 冲突修复
- 研究报告合成

它不承担：

- 工作区主状态维护
- 知识对象生命周期维护
- 最终权限裁决
- 前端接口管理

一句话：

`Python Research Worker 是被 Java 调度的研究执行内核。`

## 7. Python Artifact Worker 职责

`Python Artifact Worker` 只负责 `Artifact Job` 执行。

它承担：

- `Production Action` 解析
- `Style Profile` 装配
- `Prompt Recipe` 执行
- `Skill Graph` 编排
- `MCP Capability` 受控调用
- `Schema Gate`
- 局部 repair
- 产物结构化结果生成

它不承担：

- `Artifact` 主对象生命周期真源
- 最终版本写库
- 与三种聊天链路的主链路耦合

一句话：

`Python Artifact Worker 是被 Java 调度的生成执行内核。`

## 8. 两个执行器为什么不直接合并成一个 Python 平台

虽然这两个智能体都适合用 Python，但当前不建议把它们升级成一个通用 Agent 平台。

原因：

- `Research Agent` 和 `Artifact Agent` 的执行目标不同
- 一个偏开放式研究闭环
- 一个偏受控式生成编排
- 强行平台化会让当前架构变重

所以当前最合理的方式是：

- 部署形态可以是两个独立 worker
- 代码仓内可以共用基础层
- 但逻辑上明确分成两条执行链路

也就是说：

- `python-research-worker`
- `python-artifact-worker`

可以共享：

- OpenAI SDK 封装
- 工具调用适配层
- MCP 客户端
- 通用 trace 结构

但不应该强行合成一个“大而全 Agent 平台”。

## 9. 任务链路设计

## 9.1 Research Run

```text
用户点击 Deep Research
  -> Java 创建 research_run
  -> Java 创建 task(RESEARCH_RUN)
  -> Java 写 task_outbox
  -> Kafka 投递 research.run
  -> Python Research Worker 消费
  -> 执行研究循环
  -> 回传进度事件 / trace 事件 / 结果补丁
  -> Java 落库
  -> Java 推送 SSE
  -> Java 完成最终回写
```

## 9.2 Artifact Job

```text
用户点击右侧产物按钮
  -> Java 创建 artifact_job
  -> Java 创建 task(ARTIFACT_JOB)
  -> Java 写 task_outbox
  -> Kafka 投递 artifact.job
  -> Python Artifact Worker 消费
  -> 执行 Skill Graph / Prompt / Capability Runtime
  -> 回传进度事件 / trace 事件 / 产物结果
  -> Java 落库 artifact / artifact_version
  -> Java 处理保存为结构化笔记 / Wiki / Artifact
```

## 10. 哪些表只能由 Java 主写

下面这些表建议以 `Java` 为唯一主写方：

- `workspace`
- `topic_scope`
- `source`
- `conversation`
- `knowledge_item`
- `knowledge_version`
- `artifact`
- `artifact_version`
- `research_run`
- `artifact_job`
- `task`
- `task_outbox`
- `task_event`

Python 不直接把自己当业务主系统去深写这些表，而是：

- 返回结构化结果
- 返回结构化事件
- 由 Java 落库

这样可以避免双语言同时维护业务主状态。

## 11. Python 回传给 Java 的内容

## 11.1 Research Worker 回传

推荐回传：

- `progress_event`
- `trace_event`
- `row_patch`
- `cell_patch`
- `evidence_patch`
- `branch_patch`
- `final_report_payload`

## 11.2 Artifact Worker 回传

推荐回传：

- `progress_event`
- `trace_event`
- `artifact_draft_payload`
- `section_patch`
- `verification_result`
- `writeback_suggestion`

这些内容应当是结构化 JSON，而不是把所有逻辑都塞回自由文本。

## 12. 中间件边界

## 12.1 Java 持有

- MySQL
- Kafka topic 创建与任务投递
- Redis 主 key 空间
- MinIO 主对象命名规则
- Elasticsearch 主索引命名规则

## 12.2 Python 使用

Python Worker 可以读取或写入：

- MinIO 中间快照
- Elasticsearch 检索
- Kafka 消费与结果事件投递

但这些能力的资源边界仍由 Java 主系统定义。

## 13. 对现有文档的影响

这份架构结论意味着后续文档口径应统一为：

1. `三种聊天回答链路继续由 Java 主系统承载`
2. `Deep Research` 与 `Artifact Agent` 作为相对独立的 Python 执行器实现
3. `Java 负责任务真源、结果回写和业务主状态`
4. `Python 负责智能执行循环`

因此需要同步修改：

- [系统架构设计](D:/java-projects/NoteWeave-v2/docs/系统架构设计.md)
- [技术架构详细设计](D:/java-projects/NoteWeave-v2/docs/技术架构详细设计.md)
- [技术栈与数据库设计](D:/java-projects/NoteWeave-v2/docs/技术栈与数据库设计.md)
- [深度研究智能体工程落地设计](D:/java-projects/NoteWeave-v2/docs/深度研究智能体工程落地设计.md)
- [受控式异步产物生成Agent编排升级设计](D:/java-projects/NoteWeave-v2/docs/受控式异步产物生成Agent编排升级设计.md)

## 14. 设计结论

当前最合理的工程实现不是：

- 把所有东西都写成 Python
- 也不是把所有 Agent 都硬塞回 Java

而是：

- `Java` 保持主系统与聊天链路
- `Python` 承担两个相对独立的智能执行器

这和你的核心判断一致：

`Research Agent` 与 `Artifact Agent` 的流程本身相对独立，真正和系统主链路发生关系的地方主要在任务创建、运行事件和结果回写，因此它们适合从 Java 主系统中拆出，作为独立 Python 执行器实现。`
