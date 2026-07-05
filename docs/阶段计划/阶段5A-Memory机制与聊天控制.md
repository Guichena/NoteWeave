# 阶段5A：Memory 机制与聊天控制

## 1. 阶段目标

这一阶段只聚焦 `Memory机制详细设计.md` 的第一批可运行闭环，不直接展开 Deep Research Worker 和 Artifact Worker 的重实现。

目标是先把下面这条主线跑通：

```text
显式反馈 / 项目口径
  -> Memory Signal
  -> Memory Candidate
  -> Graduated Memory Gates（轻量版）
  -> Memory Object
  -> Chat / Artifact / Research Control Pack
  -> 先接入聊天三链路
```

这一阶段的工程重点不是“把长期记忆做大”，而是：

1. 保住 `Memory 不参与事实检索` 的边界
2. 保住 `Chat / Artifact / Research Control Pack` 的运行时接口
3. 先把 `Chat Control Pack` 接到 `QA / Note / Wiki`
4. 为 `Artifact / Research` 预留编译接口和占位 pack

## 2. 本阶段明确边界

### 当前要做

1. `memory_signal`
2. `memory_candidate`
3. `memory_object`
4. `memory_usage_log`
5. `MemorySignalService`
6. `MemoryCandidateService`
7. `MemoryPromotionService`
8. `MemoryCompilerService`
9. `Chat Control Pack` 接入 `ChatService`
10. `Artifact / Research Control Pack` 编译占位接口

### 当前不做

1. 自动从全部聊天记录无差别抽取长期记忆
2. Memory 参与资料召回、citation 或 evidence rerank
3. Deep Research Runtime 真正消费 Research Control Pack
4. Artifact Runtime 真正消费 Artifact Control Pack
5. 可视化 Memory 管理后台
6. 复杂跨工作台 / 跨用户记忆继承

## 3. 子阶段树

```text
5A.1 Memory 主对象与迁移
5A.2 Graduated Memory 轻量晋升闭环
5A.3 Memory Compiler 与三类 Control Pack
5A.4 Chat Control Pack 接入 QA / Note / Wiki
5A.5 全链路测试：Memory + 聊天窗口压缩 + 三模式
5A.6 Research / Artifact 占位接口
```

## 4. 子阶段 5A.1：Memory 主对象与迁移

### 目标

建立最小可运行 Memory 底座。

### 表结构

1. `memory_signal`
2. `memory_candidate`
3. `memory_object`
4. `memory_usage_log`

### 推荐新增文件

1. `V011__create_memory_tables.sql`
2. `memory/` 包下基础 request / response / service 类

### TDD 要求

先写测试：

1. 表迁移成功
2. 能创建 `memory_signal`
3. 能把 signal 构造成 candidate
4. 能把 candidate 晋升成 object

再写代码。

### 完成定义

1. Memory 四张主表完成迁移
2. 基础服务可读写 signal / candidate / object / usage log

## 5. 子阶段 5A.2：Graduated Memory 轻量晋升闭环

### 目标

实现文档中最核心但最轻量的一版 `Graduated Memory Gates`。

### 第一版必须覆盖的 gate

1. `Evidence Gate`
2. `Novelty Gate`
3. `Semantic Neighborhood Gate`
4. `Marginal Utility Gate`
5. `Conflict & Staleness Guard`（轻量版）

### 第一版简化原则

1. 不上额外模型
2. 不做复杂批归并
3. 不做真正异步晋升 worker
4. 优先支持显式信号和项目口径

### 推荐函数

1. `MemorySignalService.createSignal(...)`
2. `MemoryCandidateService.buildCandidates(workspaceId, signalIds)`
3. `MemoryPromotionService.promoteCandidates(workspaceId, signalIds)`

### TDD 要求

先写测试：

1. `USER_FEEDBACK / PROJECT_DECISION` 的 signal 可通过 `Evidence Gate`
2. 重复 signal 不应生成无意义重复 memory object
3. `NEGATIVE` 类型会晋升成可编译禁用路径 memory
4. 冲突或低价值 candidate 不应直接晋升
5. `MODEL_INFERENCE` 等弱信号默认进入待审，不直接晋升
6. 与既有 active memory 冲突的 signal 必须进入待审，不覆盖原记忆

### 完成定义

1. 至少能从显式反馈稳定晋升出 `Preference / Decision / Negative` 三类 memory object
2. candidate 晋升结果可解释、可追溯

## 6. 子阶段 5A.3：Memory Compiler 与三类 Control Pack

### 目标

把 Memory 的“读”阶段变成运行时接口，而不是只停在表里。

### 三类 pack

1. `Chat Control Pack`
2. `Artifact Control Pack`
3. `Research Control Pack`

### 第一版要求

1. 统一由 `MemoryCompilerService` 产出
2. 支持按 `task_neighborhood` 过滤 ACTIVE memory object
3. 聚合：
   - `style_constraints`
   - `structure_constraints`
   - `terminology_policy`
   - `forbidden_patterns`
   - `interaction_policy`
   - `review_checklist`
4. 固定补入 `evidence_policy`

### 推荐函数

1. `compileChatControlPack(workspaceId, answerMode)`
2. `compileArtifactControlPack(workspaceId, actionKey)`
3. `compileResearchControlPack(workspaceId, profileKey)`

### TDD 要求

先写测试：

1. Chat pack 会按 `QA / NOTE / WIKI` 编译不同邻域
2. Artifact pack 可以返回占位结果
3. Research pack 可以返回占位结果
4. pack 中固定带有“Memory 不作为事实来源”的 evidence policy

### 完成定义

1. 三类 pack 都可被显式查询和调试
2. 编译逻辑不把 Memory 原文塞进 evidence 区

## 7. 子阶段 5A.4：Chat Control Pack 接入 QA / Note / Wiki

### 目标

把 Memory 先真正接到最核心的聊天主链路。

### 接入原则

1. `ChatService` 统一编译一份 `Chat Control Pack`
2. `QA / NOTE / WIKI` 共用 pack
3. pack 只影响：
   - 风格
   - 结构
   - 术语
   - 禁用路径
   - 交互策略
4. pack 不影响：
   - `retrieveForQa`
   - `findNoteRecallPlan`
   - `findRelevantWikiPageContexts`
   - citation
   - evidence ranking

### 推荐改造点

1. `ChatService.buildAnswerDraft(...)`
2. `ChatService.buildQaAnswer(...)`
3. `ChatService.buildNoteAnswer(...)`
4. `ChatService.buildWikiAnswer(...)`
5. `memory_usage_log` 记录 pack 使用

### TDD 要求

先写测试：

1. QA 在有 memory object 时会应用 Chat Control Pack，但 citation 仍正常返回
2. Note 在有 memory object 时会应用 Chat Control Pack，但候选资料 / 原文窗口逻辑不变
3. Wiki 在有 memory object 时会应用 Chat Control Pack，但页面网络检索仍正常
4. 使用 pack 后会写 `memory_usage_log`

### 完成定义

1. 三条聊天链路都能读取并应用 Chat Control Pack
2. Memory 仍不进入事实检索链路

## 8. 子阶段 5A.5：全链路测试

### 目标

在 Memory 接入之后，对“最核心聊天系统”做系统性回归。

### 必测范围

1. 连续对话上下文窗
2. 聊天窗口压缩 / 前序主题摘要
3. Memory 处理
4. QA 链路
5. Note 链路
6. Wiki 链路
7. Chat Control Pack 不污染 citation

### 测试文件建议

1. `Phase1And2ContractTest`
2. `Phase3NoteWikiContractTest`
3. `Phase5MemoryContractTest`

其中 `Phase5MemoryContractTest` 本阶段至少应覆盖：

1. `memorySignalPromotionShouldProduceChatArtifactAndResearchControlPacks`
2. `qaNoteAndWikiChainsShouldApplyChatControlPackWithoutBreakingCoreFlows`
3. `rollingContextWindowAndMemoryShouldWorkTogetherAcrossQaNoteAndWiki`
4. `memoryEnabledChatChainsShouldCutOffOldTopicAcrossQaNoteAndWiki`
5. `graduatedMemoryGatesShouldRejectWeakSignalsMergeDuplicatesAndCompileNegativeMemory`
6. `graduatedMemoryGatesShouldHoldConflictingSignalForReview`

### 完成定义

1. 连续对话窗口和 Memory 可同时工作
2. 聊天三模式在接入 Memory 后没有回退或串味

## 9. 子阶段 5A.6：Research / Artifact 占位接口

### 目标

不给本轮做重，但必须给后续阶段留稳定接口。

### 当前只做

1. `compileArtifactControlPack(...)`
2. `compileResearchControlPack(...)`
3. 对应调试接口 / 契约测试

### 当前不做

1. Python Research Worker 消费 Research pack
2. Python Artifact Worker 消费 Artifact pack
3. runtime injector

## 10. 推荐测试类清单

### `Phase5MemoryContractTest`

先写：

1. `memorySignalPromotionShouldProduceChatControlPack`
2. `chatChainsShouldApplyChatControlPackWithoutBreakingCitationFlow`
3. `artifactAndResearchControlPackShouldCompileAsPlaceholders`
4. `memoryUsageLogShouldBeWrittenAfterChatPackApplication`
5. `graduatedMemoryGatesShouldRejectWeakSignalsMergeDuplicatesAndCompileNegativeMemory`
6. `graduatedMemoryGatesShouldHoldConflictingSignalForReview`

### 既有回归测试

1. `qaModeShouldCarryRollingConversationContextForFollowUpQuestions`
2. `qaModeShouldCompileOlderSameTopicTurnsIntoTopicSummary`
3. `qaModeShouldCutOffOldWindowWhenUserStartsANewExplicitTopic`
4. `noteModeShouldCarryRollingConversationContextForFollowUpQuestions`
5. `wikiModeShouldCarryRollingConversationContextForFollowUpQuestions`
6. `memoryEnabledChatChainsShouldCutOffOldTopicAcrossQaNoteAndWiki`

## 11. 本阶段验收清单

1. `V011__create_memory_tables.sql` 已落地
2. `MemorySignal / Candidate / Promotion / Compiler` 服务可运行
3. `Chat / Artifact / Research Control Pack` 可查询
4. `Chat Control Pack` 已接入 `QA / NOTE / WIKI`
5. `memory_usage_log` 可记录聊天使用
6. Memory 不参与 citation、chunk 召回、Note 候选资料排序、Wiki 页面检索
7. `mvn test` 通过
8. `npm run build` 通过
