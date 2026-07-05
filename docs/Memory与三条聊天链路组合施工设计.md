# Memory 与三条聊天链路组合施工设计

## 1. 目标

这份施工文档只解决一个问题：

`如何把 Memory 机制稳定接入统一聊天页，同时与 QA / Note / Wiki 三条检索链路和连续对话上下文窗一起工作。`

当前我们已经有三块能力：

1. `Topic-Aware Rolling Context Window`
2. `Graduated Memory + Task-Neighborhood Memory Compiler`
3. `QA / Note / Wiki` 三条差异化检索链路

本阶段的施工重点不是继续扩功能，而是把这三块能力真正拼成一条可验证的主线。

## 2. 组合原则

### 2.1 聊天页仍然只有一条主入口

用户始终在统一聊天页发消息。

系统只根据 `answer_mode` 选择背后的检索链路：

1. `QA`
2. `NOTE`
3. `WIKI`

这意味着：

1. 上下文窗只编译一次
2. Chat Control Pack 只编译一次
3. 三条链路共享同一份聊天上下文与同一份 Memory 控制信息

### 2.2 检索和记忆严格分层

组合后的总原则：

1. `上下文窗` 解决“这轮问题和前几轮是什么关系”
2. `检索链路` 解决“去哪里找事实证据”
3. `Memory` 解决“最终怎么表达、怎么组织、哪些路径不要走”

因此：

1. Memory 不进资料召回
2. Memory 不进 citation
3. Memory 不进 evidence rerank
4. Memory 不替代 Note 原文窗口
5. Memory 不替代 Wiki 页面网络

## 3. 运行时总流程

```text
用户发送消息
  -> ChatService.sendMessage(...)
  -> buildConversationContext(...)
  -> MemoryCompilerService.compileChatControlPack(...)
  -> switch(answer_mode)
       -> buildQaAnswer(...)
       -> buildNoteAnswer(...)
       -> buildWikiAnswer(...)
  -> assistant message 落库
  -> citation / existing citation 绑定
  -> memory_usage_log 落库
```

组合后的时序口径：

1. 先编译聊天上下文
2. 再编译 Chat Control Pack
3. 再进入对应检索链路
4. 最后把 Memory 的使用结果记账

## 4. 三条链路的接法

### 4.1 QA 链路

函数入口：

1. `ChatService.buildQaAnswer(...)`
2. `RetrievalService.retrieveForQa(...)`

接入方式：

1. `ConversationContext.retrievalQuestion` 进入 QA 检索
2. `MemoryControlPackResponse` 只进入回答控制区
3. `appendChatControlSection(...)` 在回答尾部输出控制信息

必须保持：

1. QA citation 仍来自 `RetrievedChunk`
2. QA 回答仍围绕工作台资料池
3. Chat Control Pack 不改变召回证据集合

### 4.2 Note 链路

函数入口：

1. `ChatService.buildNoteAnswer(...)`
2. `RetrievalService.findNoteRecallPlan(...)`
3. `RetrievalService.readEntriesMetadataForNote(...)`
4. `RetrievalService.openSourceWindowsForNote(...)`

接入方式：

1. `ConversationContext.retrievalQuestion` 进入资料级候选定位
2. `MemoryControlPackResponse` 只控制正文表达与整理口径
3. 原文窗口、摘录证据、候选资料排序仍由 Note 检索逻辑负责

必须保持：

1. Note 仍体现 `先定位资料、再打开窗口、再给回答`
2. citation 仍来自原文窗口转成的证据块
3. Memory 不改变 `window readiness / relation expansion / journal freshness`

### 4.3 Wiki 链路

函数入口：

1. `ChatService.buildWikiAnswer(...)`
2. `KnowledgeService.findRelevantWikiPageContexts(...)`

接入方式：

1. `ConversationContext.retrievalQuestion` 进入 Wiki 页面网络检索
2. `MemoryControlPackResponse` 只控制页面讲解风格、结构和术语口径
3. 页面、链接、反链、来源回链仍由 Wiki 网络负责

必须保持：

1. Wiki 回答仍然围绕工作台 Wiki 网络
2. Memory 不覆盖页面事实
3. Memory 不替代页面来源回链

## 5. 与连续对话上下文窗的组合方式

### 5.1 先做上下文编译，再做链路检索

当前统一入口：

1. `buildConversationContext(...)`

上下文对象：

1. `retrievalQuestion`
2. `contextApplied`
3. `windowTurnCount`
4. `topicAnchor`
5. `topicSummary`
6. `workingTurns`

组合原则：

1. 三条链路只消费 `retrievalQuestion`
2. 三条链路都可以展示 `topicAnchor / topicSummary`
3. Memory 不干预上下文窗截断与摘要压缩

### 5.2 为什么要把它们分开

如果把 Memory 也放进上下文编译，会出现两个问题：

1. 用户长期偏好会污染事实检索 query
2. 聊天压缩结果会和长期记忆混成一团，难以解释

所以这里必须坚持：

1. `上下文窗 = 会话级工作记忆`
2. `Memory = 跨会话行为控制`

## 6. 本阶段需要落的文件

### 6.1 已有实现核心

1. [backend/src/main/java/com/noteweave/chat/ChatService.java](/D:/java-projects/NoteWeave-v2/backend/src/main/java/com/noteweave/chat/ChatService.java)
2. [backend/src/main/java/com/noteweave/memory/MemorySignalService.java](/D:/java-projects/NoteWeave-v2/backend/src/main/java/com/noteweave/memory/MemorySignalService.java)
3. [backend/src/main/java/com/noteweave/memory/MemoryCandidateService.java](/D:/java-projects/NoteWeave-v2/backend/src/main/java/com/noteweave/memory/MemoryCandidateService.java)
4. [backend/src/main/java/com/noteweave/memory/MemoryPromotionService.java](/D:/java-projects/NoteWeave-v2/backend/src/main/java/com/noteweave/memory/MemoryPromotionService.java)
5. [backend/src/main/java/com/noteweave/memory/MemoryCompilerService.java](/D:/java-projects/NoteWeave-v2/backend/src/main/java/com/noteweave/memory/MemoryCompilerService.java)
6. [backend/src/main/resources/db/migration/V011__create_memory_tables.sql](/D:/java-projects/NoteWeave-v2/backend/src/main/resources/db/migration/V011__create_memory_tables.sql)

### 6.2 本阶段重点测试文件

1. [backend/src/test/java/com/noteweave/Phase1And2ContractTest.java](/D:/java-projects/NoteWeave-v2/backend/src/test/java/com/noteweave/Phase1And2ContractTest.java)
2. [backend/src/test/java/com/noteweave/Phase3NoteWikiContractTest.java](/D:/java-projects/NoteWeave-v2/backend/src/test/java/com/noteweave/Phase3NoteWikiContractTest.java)
3. [backend/src/test/java/com/noteweave/Phase5MemoryContractTest.java](/D:/java-projects/NoteWeave-v2/backend/src/test/java/com/noteweave/Phase5MemoryContractTest.java)

## 7. TDD 执行顺序

### 7.1 第一步：Memory 基本闭环

先验证：

1. signal 能创建
2. signal 能晋升成 candidate / object
3. Chat / Artifact / Research pack 能编译

对应测试：

1. `memorySignalPromotionShouldProduceChatArtifactAndResearchControlPacks`
2. `graduatedMemoryGatesShouldRejectWeakSignalsMergeDuplicatesAndCompileNegativeMemory`
3. `graduatedMemoryGatesShouldHoldConflictingSignalForReview`

### 7.2 第二步：Memory 接入聊天三链路

先验证：

1. QA 能用 Chat Control Pack
2. Note 能用 Chat Control Pack
3. Wiki 能用 Chat Control Pack
4. `memory_usage_log` 正常记账

对应测试：

1. `qaNoteAndWikiChainsShouldApplyChatControlPackWithoutBreakingCoreFlows`

### 7.3 第三步：上下文压缩 + Memory + 三链路组合

先验证：

1. QA 连续追问会出现 `前序主题摘要`
2. Note 在连续追问场景下仍能走原文窗口
3. Wiki 在连续追问场景下仍能走页面网络
4. 三条链路都会应用 Chat Control Pack
5. citation 不因 Memory 接入而中断

对应测试：

1. `rollingContextWindowAndMemoryShouldWorkTogetherAcrossQaNoteAndWiki`
2. `memoryEnabledChatChainsShouldCutOffOldTopicAcrossQaNoteAndWiki`

### 7.4 第四步：回归测试

必须一起跑：

1. `Phase1And2ContractTest`
2. `Phase3NoteWikiContractTest`
3. `Phase5MemoryContractTest`

推荐命令：

```powershell
.\mvnw.cmd -f backend/pom.xml "-Dtest=Phase5MemoryContractTest,Phase1And2ContractTest,Phase3NoteWikiContractTest" test
```

## 8. 完成定义

本施工文档对应的完成定义是：

1. Memory 四张主表迁移完成
2. Memory 已具备 signal -> candidate -> object 的最小晋升闭环
3. Chat / Artifact / Research 三类 Control Pack 可编译
4. Chat Control Pack 已接入 QA / Note / Wiki
5. 连续对话上下文窗和 Memory 可同时工作
6. QA / Note / Wiki 在接入 Memory 后没有回退
7. citation、原文窗口、Wiki 页面网络没有被 Memory 污染

## 9. 后续占位

本阶段不继续做重：

1. Research Runtime 真正消费 `Research Control Pack`
2. Artifact Runtime 真正消费 `Artifact Control Pack`
3. 自动从长聊天记录批量抽取长期记忆

但接口已经预留：

1. `compileArtifactControlPack(...)`
2. `compileResearchControlPack(...)`
3. `memory_usage_log`

后续只需要在 Python Worker 侧把 pack 注入执行控制区即可。
