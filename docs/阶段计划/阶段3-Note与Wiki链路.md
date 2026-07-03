# 阶段3：Note 与 Wiki 链路

## 1. 阶段目标

阶段 3 把统一聊天区补齐为三种回答链路：

1. `QA`
2. `NOTE`
3. `WIKI`

这一阶段的关键不是再造一个泛化 Knowledge 系统，而是把两条有区分度的链路落清楚：

- `NOTE` 参考 `shenmintao/marginalia`：采用结构化阅读检索漏斗，先定位候选资料，再打开原文窗口，最后生成摘录卡片与结构化笔记。
- `WIKI` 参考 `WeKnora`：采用 Wiki-first 页面链路，优先读取已有 Wiki 页面、页面链接、索引和来源回链，并保留默认 Wiki 工作台入口。

## 2. 子阶段树

```text
3.1 知识对象与版本底座
3.2 Note 链路：Marginalia 式结构化阅读检索漏斗
3.3 Wiki 链路：WeKnora 式 Wiki-first 页面体系
3.4 前端三模式入口与 Wiki 工作台入口
```

## 3. 子阶段 3.1：知识对象与版本底座

### 目标

建立 Note 和 Wiki 可保存、可追溯、可更新的底座。

### 表结构

MySQL：

1. `knowledge_item`
2. `knowledge_version`
3. `knowledge_version_citation`
4. `knowledge_item_link`

### 推荐类与函数

1. `KnowledgeService.createItem(workspaceId, request)`
2. `KnowledgeService.saveMessageAsNote(messageId, request)`
3. `KnowledgeService.appendVersion(itemId, request)`
4. `KnowledgeService.findRelevantWikiPages(workspaceId, query)`
5. `KnowledgeService.getWikiHome(workspaceId)`

### TDD 要求

先写测试：

1. 创建 Note 后生成 `knowledge_item + knowledge_version`
2. 创建 Wiki 页面后生成页面版本
3. 追加 Wiki 版本后 `latest_version_no = 2`
4. Wiki 页面引用可绑定到聊天引用
5. Wiki 页面链接可以进入默认 Wiki 工作台

### 完成定义

1. Note 可以由助手回答保存而来。
2. Wiki 可以创建、列表读取、追加版本。
3. Wiki 页面链接和引用关系可回查。

## 4. 子阶段 3.2：Note 链路

### 目标

实现 `answer_mode = NOTE`。

### 设计口径

Note 链路参考 `shenmintao/marginalia`，不直接把 chunk top-k 当作答案上下文，而是采用：

```text
资料标题 / 摘要 / 标签 / 结构化元数据
  -> 候选资料
  -> 原文窗口
  -> 摘录卡片
  -> 带引用回答
  -> 结构化笔记
```

### 推荐类与函数

1. `RetrievalService.findCandidateSourcesForNote(workspaceId, query)`
2. `RetrievalService.openSourceWindowsForNote(workspaceId, candidates)`
3. `ChatService.buildNoteAnswer(workspaceId, request)`
4. `KnowledgeService.saveMessageAsNote(messageId, request)`

### 中间件接入

MySQL：

1. `source.summary`
2. `source.tags_json`
3. `source.metadata_json`
4. `source_window`
5. `citation`
6. `knowledge_item`
7. `knowledge_version`
8. `knowledge_version_citation`

Elasticsearch 后续替换点：

1. `nw-source-metadata`
2. `nw-source-window`

当前阶段先用 MySQL 完成契约闭环，后续再把候选定位和窗口读取替换为 ES / 向量混合检索。

### TDD 要求

先写测试：

1. Note 模式回答包含候选资料。
2. Note 模式回答包含原文窗口和摘录卡片。
3. 上传资料解析后写入摘要、标签和结构化元数据。
4. 保存为 Note 后保留引用关系。

### 完成定义

1. Note 模式不是 QA 换皮，而是能体现候选资料、原文窗口、摘录卡片和结构化笔记。
2. Note 结果可保存到工作台，并作为后续资料沉淀的基础。

## 5. 子阶段 3.3：Wiki 链路

### 目标

实现 `answer_mode = WIKI`。

### 设计口径

Wiki 链路参考 `WeKnora`，核心是 Wiki-first：

```text
Wiki Index / 页面列表
  -> 相关 Wiki 页面
  -> 页面链接 / 反向链接
  -> 页面来源引用
  -> 基于正式页面的回答
  -> 默认 Wiki 工作台入口
```

### 推荐类与函数

1. `KnowledgeService.findRelevantWikiPages(workspaceId, query)`
2. `KnowledgeService.citationIdsForWikiPages(pages)`
3. `KnowledgeService.getWikiHome(workspaceId)`
4. `KnowledgeService.listWikiLinks(workspaceId)`
5. `ChatService.buildWikiAnswer(workspaceId, request)`

### 中间件接入

MySQL：

1. `knowledge_item`
2. `knowledge_version`
3. `knowledge_version_citation`
4. `knowledge_item_link`

Elasticsearch 后续替换点：

1. `nw-wiki-page`
2. `nw-wiki-link`

Frontend：

1. 统一聊天区继续使用 SSE。
2. 回答中只保留默认 Wiki 工作台入口。
3. Wiki 工作台通过普通页面接口读取页面、链接和来源。

### TDD 要求

先写测试：

1. Wiki 模式优先命中已有 Wiki 页面。
2. Wiki 模式回答包含默认 Wiki 工作台入口。
3. Wiki 页面追加版本后，回答读取最新版本。
4. Wiki 页面中的 `[[页面名]]` 可以生成链接关系。

### 完成定义

1. Wiki 模式基于已有页面回答，而不是直接基于普通 chunk 回答。
2. Wiki 页面支持持续维护和版本递增。
3. 用户可从聊天回答进入默认 Wiki 工作台。

## 6. 子阶段 3.4：前端三模式入口与 Wiki 工作台入口

### 目标

前端不做三套聊天页面，只在同一聊天框中切换背后的回答链路。

### 最小实现

1. 三个模式按钮：`问答 / Note / Wiki`
2. Note 模式说明：候选资料、原文窗口、摘录卡片、结构化笔记
3. Wiki 模式说明：Wiki-first、页面链接、默认 Wiki 工作台入口
4. Wiki 工作台入口按钮：`/workspaces/{workspaceId}/wiki`

## 7. 本阶段禁止项

1. 不做复杂知识图谱编辑器。
2. 不做独立 Note 工作台。
3. 不做 Research。
4. 不做 Memory 晋升。
5. 不把 Wiki 和 Note 写成同一条泛化链路。

## 8. 阶段口径

`阶段3的目标是让聊天区真正具备三种有区分度的回答链路：QA 负责快速证据问答，Note 参考 Marginalia 做结构化阅读检索漏斗，Wiki 参考 WeKnora 做 Wiki-first 页面回答和默认 Wiki 工作台入口。`
