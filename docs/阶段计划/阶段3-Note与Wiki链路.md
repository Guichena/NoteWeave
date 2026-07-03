# 阶段3：Note与Wiki链路

## 1. 阶段目标

阶段 3 把统一聊天区补齐为三种回答链路：

1. `QA`
2. `NOTE`
3. `WIKI`

重点是“尽量迁移旧 Wiki 能力，而不是从零再造一套知识系统”。

## 2. 与原始 NoteWeave Phase 的关系

当前阶段 3 主要对应两部分：

1. 原始 `Phase 1：问答模式` 里保存 Note、基于 Note 检索的部分
2. 原始 `Phase 2：Wiki 模式`

## 3. 子阶段树

```text
3.1 Knowledge 模型迁移
3.2 Note 链路
3.3 Wiki 链路
```

## 4. 子阶段 3.1 Knowledge 模型迁移

### 目标

把旧版 Wiki 的可复用能力收敛为：

1. `knowledge_item`
2. `knowledge_version`
3. `knowledge_version_citation`

### 优先迁移

1. 页面存储
2. 版本管理
3. 引用关系
4. 页面检索

### 推荐函数与类

1. `KnowledgeItemService.createNoteItem(workspaceId, title)`
2. `KnowledgeItemService.createWikiItem(workspaceId, title)`
3. `KnowledgeVersionService.appendVersion(itemId, content, citations)`
4. `KnowledgeCitationService.bindVersionCitations(versionId, citationIds)`

### 中间件接入

MySQL：

1. `knowledge_item`
2. `knowledge_version`
3. `knowledge_version_citation`

Elasticsearch：

1. alias：`nw-knowledge`
2. 用于 Note / Wiki 的检索与列表读取

### TDD 要求

先写：

1. `knowledge_*` repository 测试
2. 版本递增测试
3. 引用关联测试

再写：

1. knowledge entity
2. version service
3. citation binding

### 完成定义

1. Note / Wiki 可共用同一知识对象底座

## 5. 子阶段 3.2 Note 链路

### 目标

实现 `answer_mode = NOTE`。

### 核心要求

1. 先定位候选资料
2. 再读取原文窗口
3. 生成带摘录和结构化组织的回答
4. 支持保存为 Note

### 推荐函数与类

1. `ChatOrchestrator.answerNote(messageId, context)`
2. `NoteCandidateLocator.findCandidateSources(workspaceId, query)`
3. `SourceWindowService.openSourceWindows(sourceIds, queryGoal)`
4. `NoteAnswerAssembler.buildNoteAnswer(excerpts, citations)`
5. `NoteSaveService.saveFromAnswer(messageId, answerPayload)`

### 中间件接入

MySQL：

1. `knowledge_item`
2. `knowledge_version`
3. `knowledge_version_citation`
4. `source_window`

Elasticsearch：

1. `nw-source-chunk`
2. `nw-knowledge`

Redis / SSE：

1. 仍沿用聊天流状态，不新开一套流协议

### TDD 要求

先写：

1. Note 模式回答接口测试
2. 保存为 Note 测试
3. Note 引用落库测试

再写：

1. Note retrieval funnel
2. note answer renderer
3. save note action

### 完成定义

1. Note 模式能输出结构化回答
2. 可以保存为 Note 版本对象

## 6. 子阶段 3.3 Wiki 链路

### 目标

实现 `answer_mode = WIKI`。

### 核心要求

1. 优先读取已有 Wiki 页面
2. 回答中给出相关页面与来源依据
3. 支持跳转到默认 Wiki 工作台

### 推荐函数与类

1. `ChatOrchestrator.answerWiki(messageId, context)`
2. `WikiRetriever.findRelevantPages(workspaceId, query)`
3. `WikiAnswerAssembler.buildWikiAnswer(pages, citations)`
4. `WikiPageQueryService.getWorkspaceWikiHome(workspaceId)`

### 中间件接入

MySQL：

1. `knowledge_item`
2. `knowledge_version`
3. `knowledge_version_citation`

Elasticsearch：

1. `nw-knowledge`

Frontend：

1. 统一聊天区继续走 SSE
2. Wiki 工作台走普通页面查询接口

### TDD 要求

先写：

1. Wiki 模式接口测试
2. 相关页面返回测试
3. 前端跳转测试

再写：

1. wiki-first retrieval
2. wiki answer assembler
3. wiki page viewer entry

### 完成定义

1. Wiki 模式能基于已有页面回答
2. 能跳转查看默认 Wiki 工作台

## 7. 本阶段禁止项

1. 不做复杂知识图谱编辑器
2. 不做 Research
3. 不做 Memory 晋升
