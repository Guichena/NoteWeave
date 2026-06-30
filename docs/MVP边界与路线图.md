# MVP 边界与路线图

## 1. 目的

这份文档用于锁定 v2 的第一版落地边界，目标不是“把所有想法都做出来”，而是用最小闭环证明三模式统一工作台是可成立的。

## 2. MVP 使命

MVP 要证明的一件核心事情是：

`一个统一工作区可以同时承载问答、Wiki 维护与资料调查三种工作模式，并通过 Deep Research 按钮、右侧产物栏与门控式记忆晋升机制，把复杂任务沉淀为可追溯、可复用的知识结果。`

因此 MVP 的目标不是做一个“大而全 Agent 平台”，而是做一个边界清晰、模式清晰、证据链清晰的工作台原型。

## 3. MVP 范围内能力

### 3.1 统一工作区底座

- 单一用户工作区边界
- Topic Scope 主题组织边界
- 统一权限与归属关系
- 统一导航模型

### 3.2 Source 导入与证据底座

- 文件导入
- URL 导入
- 文本导入
- 原始文件与解析文本保存
- Source metadata
- 原文定位信息
- Citation / Evidence 基础能力

### 3.3 问答模式 MVP

- 基于 Source / Note / Wiki 的检索问答
- 当前会话连续性
- 回答可携带引用
- 一键保存为 Note
- 右侧出现默认产物按钮

### 3.4 Note 沉淀层 MVP

- 创建 Note
- 从 Ask 保存
- 从调查结果保存
- 在 Ask 模式中被检索复用

### 3.5 Wiki 模式 MVP

- 创建与编辑 Wiki 页面
- 页面版本管理
- 页面链接与反向链接
- 页面引用支撑
- 基础覆盖度 / 关系摘要

### 3.6 资料调查模式 MVP

- 创建调查主题
- 浏览 Source metadata
- 生成 SourceCard 候选列表
- 打开 SourceWindow 原文窗口
- 生成 Investigation Note

### 3.7 全局 Deep Research 入口 MVP

- 提供独立的 `Deep Research` 按钮
- 可以从问答、Wiki、调查任一上下文发起
- 自动创建 `Research Run`
- 结果可回流到问答、Wiki、调查或 Note / Artifact

### 3.8 Deep Research Engine MVP

- 创建 Research Run
- 生成研究计划
- 持久化 Research Table
- 行 / 单元格状态管理
- 证据绑定
- 至少一轮 Search -> Read -> Extract -> Verify loop
- 冲突标记与低置信结果保留
- 最终报告写入 Artifact

MVP 阶段不要求完整多代理复杂编排，但必须证明：

- Deep Research 是显式长任务
- 有外部状态
- 有预算 / 状态 / trace
- 不等同于一次性 Prompt

### 3.9 右侧产物栏 MVP

- 在阅读 / 对话主区右侧提供固定产物栏
- 针对当前上下文触发生成
- 至少支持报告、测验、学习指南、FAQ 4 个按钮
- 支持产物生成进度与结果回看

### 3.10 Artifact Runtime MVP

- 固定主链路的受控生成流程
- Artifact Job 异步执行
- Versioned Artifact 输出
- 至少支持 4 个默认 `Production Action`
- 支持 Skill / MCP 能力接入

推荐默认 Action：

- 研究报告
- 测验
- 学习指南
- FAQ
- Wiki 草稿

### 3.11 Agent / Skill / MCP 能力栈 MVP

- Agent 用于受控决策与验证
- Skill 用于沉淀可复用生成能力
- MCP 用于接入外部读取、转写、搜索等能力
- Action 能显式声明自己依赖的 Skill / MCP 能力

### 3.12 Graduated Memory MVP

- Conversation 可抽取轻量 `Context Signal`
- Research Trace 可抽取研究偏好与方法信号
- 被确认的 Artifact / Note / Wiki 可形成 `Memory Candidate`
- 至少支持一次 `Novelty Gate + Task Neighborhood Validation` 的门控晋升
- 长期记忆只在问答与产物生成中按需注入，不默认污染 Deep Research 主循环

MVP 阶段不追求完整自动记忆治理，但至少要证明：

- 聊天、研究过程、产物三类来源可以进入同一记忆晋升链路
- 长期记忆不是对话历史原样堆积
- 被晋升的内容具备来源、门控记录与可回读能力

### 3.13 统一回写链路 MVP

- Ask -> Note
- Investigation -> Investigation Note
- Deep Research -> Artifact
- Artifact -> Note 草稿 / Wiki 草稿 / Investigation Note

所有回写必须显式确认，不允许静默写入稳定知识。

## 4. MVP 明确不做的内容

第一版原型有意排除以下能力：

1. 默认启用的大规模自动化外部网页抓取
2. 企业级多租户组织设计
3. 无门控的全局自治记忆治理
4. 实时协同编辑
5. 完整 Claim / Entity / Knowledge Graph 推理层
6. 面向 benchmark 的 Research Agent 优化
7. 完整生产级审批工作流
8. 无约束的自由自治 Agent 编排

## 5. 任务边界

### 5.1 问答任务

边界如下：

- 低延迟
- 检索增强回答
- 结果可保存
- 默认不升级为 Deep Research

### 5.2 Note 任务

边界如下：

- 轻量记录与整理
- 面向中间理解
- 可晋升但不自动规范化

### 5.3 Wiki 任务

边界如下：

- 面向长期知识编织
- 强调页面维护与关系组织
- 生命周期严格于 Note

### 5.4 调查任务

边界如下：

- 面向资料探索与原文阅读
- 强调 SourceCard / SourceWindow / Investigation Note
- 可以停留在人工调查，不必一定升级为 Deep Research

### 5.5 Deep Research 任务

边界如下：

- 面向开放式、多步骤研究
- 通过独立按钮显式触发
- 显式异步状态
- 具备 Table、Trace、Budget、Verification
- 最终输出是报告或草稿，而不是静默注入真值

### 5.6 Artifact 任务

边界如下：

- 由右侧产物栏或系统动作触发
- 受控生成
- 结果带版本
- 输出可复用
- 需经过确认后才能回写为更稳定知识

### 5.7 Graduated Memory 任务

边界如下：

- 面向长期可复用知识的门控晋升
- 会话、研究轨迹、产物都可提供信号
- 只有通过门控验证的内容才进入长期记忆
- 默认服务问答与右侧产物生成，不直接干预 Deep Research 主 loop

## 6. 建议的原型推进顺序

### Phase A：统一底座

- 创建 Workspace 与 Topic Scope
- 创建 Source 模型
- 创建 Citation / Evidence 基础能力
- 创建 Async Task 基础能力

### Phase B：问答与 Note

- 实现 Ask 模式
- 实现 Conversation
- 实现 Note 保存与检索复用
- 实现右侧产物栏基础 UI

### Phase C：Wiki 模式

- 实现 Wiki Page 与 Version
- 打通链接、反向链接与引用
- 统一 Note / Wiki 存储规则

### Phase D：资料调查模式

- 实现调查主题容器
- 实现 SourceCard 列表
- 实现 SourceWindow 原文阅读
- 实现 Investigation Note

### Phase E：Deep Research 引擎

- 实现 Deep Research 按钮入口
- 实现 Research Run
- 实现 Research Table 与 Trace
- 跑通最小 loop：Search -> Read -> Extract -> Verify
- 产出研究报告 Artifact

### Phase F：Artifact Runtime 与回写

- 实现 Artifact Job
- 实现默认 Production Action 与 Prompt Recipe
- 接入默认 Skill / MCP 能力声明
- 实现 Artifact Version
- 打通回写为 Note / Wiki 草稿

### Phase G：Graduated Memory 与演示闭环

- 实现 Context Signal 抽取
- 实现 Memory Candidate 与门控晋升
- 在问答和产物生成中接入最小记忆回读
- 展示记忆来源、晋升记录与复用效果

### Phase H：前端打磨与演示闭环

- 统一工作台导航
- 对齐模式切换体验
- 展示进度、证据和版本链路
- 让整套系统可演示、可说明、可写入简历

## 7. 架构评审检查清单

在正式实现前，建议先确认以下问题：

1. `Topic Scope` 是否作为主题聚合边界落地
2. `Knowledge Item` 是否作为 Note / Wiki / Investigation Note 的共享存储基础
3. 调查模式与 Deep Research 的升级边界是否明确
4. MVP 阶段默认支持哪些 `Production Action`
5. 右侧产物栏的默认按钮集合是否锁定为报告 / 测验 / 学习指南 / FAQ
6. `Skill` 与 `Production Action` 的绑定方式是否固定
7. `Claim / Entity / Concept` 是否明确延后到第二阶段
8. 旧项目迁移是数据迁移、逻辑迁移，还是仅作为设计参考

## 8. MVP 最终总结

MVP 不需要证明所有能力都成熟，但必须证明以下闭环已经成立：

`资料进入工作区 -> 用户在问答 / Wiki / 调查中消费资料 -> 用户通过 Deep Research 按钮或右侧产物栏发起重型任务 -> 系统通过 Agent / Skill / MCP 能力栈生成可追溯结果 -> 结果经过确认回写为可沉淀知识 -> 高价值内容通过门控晋升为可复用长期记忆。`
