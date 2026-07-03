# 阶段3：Note 与 Wiki 链路

## 1. 阶段目标

阶段 3 把统一聊天区补齐为三种回答链路。三条链路的共同目标都是在聊天框里回答用户问题，差别只在背后的检索方式和证据组织方式：

1. `QA`
2. `NOTE`
3. `WIKI`

这一阶段的关键不是再造一个泛化 Knowledge 系统，而是把三条有区分度的检索链路落清楚：

- `QA` 采用工作台资料 chunk 的快速召回、结构化元数据评分、来源多样性证据选择和 citation-grounded answer。
- `NOTE` 参考 `shenmintao/marginalia`：采用结构化阅读检索漏斗，先定位候选资料，再打开原文窗口和摘录证据，最后生成带引用回答；结构化笔记只是可选沉淀。
- `WIKI` 参考 `WebKonra / WeKnora`：采用全量 Wiki 检索，优先读取 Wiki Index、页面正文、页面链接、反向链接、图关系、版本和来源回链，并保留默认 Wiki 工作台入口。

绑定规则：

```text
QA：回答绑定 conversation_id，检索边界绑定 workspace_id
NOTE：回答绑定 conversation_id，检索边界绑定 workspace_id，保存后的 Note 对象绑定 workspace_id
WIKI：Wiki 知识网络绑定 workspace_id，聊天中的 WIKI 链路只读取当前工作台 Wiki 网络
```

Wiki 构建通过研究工作台级 `wiki_enabled` 开关控制，不默认绑定最近聊天消息。开启开关时会回补当前工作台内已解析完成的资料；开启后，资料上传和解析完成会进入 Wiki ingest 链路，并生成或更新工作台级 Wiki 页面、引用、链接和日志。用户也可以在默认 Wiki 工作台中按当前资料手动重建 Wiki。

## 2. 子阶段树

```text
3.1 知识对象与版本底座
3.2 Note 链路：Marginalia 式结构化阅读检索漏斗
3.3 Wiki 链路：WebKonra / WeKnora 式全量 Wiki 检索体系
3.4 Wiki 工作台治理能力
3.5 前端三模式入口与 Wiki 工作台入口
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
6. `KnowledgeService.getItemDetail(itemId)`

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
3. Wiki 页面详情可以读取最新正文、版本号、引用关系和页面链接。

## 4. 子阶段 3.2：Note 链路

### 目标

实现 `answer_mode = NOTE`。

### 设计口径

Note 链路参考 `shenmintao/marginalia`，不直接把 chunk top-k 当作答案上下文，而是采用：

```text
资料标题 / 摘要 / 标签 / 结构化元数据
  -> 历史 Note / Journal 信号
  -> 关系信号扩展（标签重叠 + 历史 Note 共同引用）
  -> 候选资料
  -> 验证批次
  -> 原文窗口相关性重排
  -> 摘录卡片
  -> 带引用回答
  -> 可选结构化笔记
```

### 推荐类与函数

1. `RetrievalService.findNoteRecallPlan(workspaceId, query)`
2. `RetrievalService.findCandidateSourcesForNote(workspaceId, query)`
3. `RetrievalService.findNoteJournalHits(workspaceId, query)`
4. `RetrievalService.openSourceWindowsForNote(workspaceId, candidates, query)`
5. `ChatService.buildNoteAnswer(workspaceId, request)`
6. `KnowledgeService.saveMessageAsNote(messageId, request)`

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

当前实现先用 MySQL 保证契约、引用和版本链稳定；ES / 向量检索作为同一接口下的检索实现替换点，不改变 Note 链路的产品口径。

### TDD 要求

先写测试：

1. Note 模式回答包含候选资料。
2. Note 模式回答包含 Journal 信号、关系扩展、验证批次、原文窗口和摘录卡片。
3. 上传资料解析后写入摘要、标签和结构化元数据。
4. 保存为 Note 后保留引用关系。
5. 保存过 Note 后，再次 Note 提问可以利用历史 Note 作为 journal 信号。
6. Note 模式回答能够显式输出 `candidate_sources / relation_expansion_sources / verify_batch_sources`。

### 完成定义

1. Note 模式不是 QA 换皮，而是能体现 journal 信号、候选资料、关系扩展、验证批次、原文窗口、摘录证据和带引用回答。
2. Note 必须能回答问题，结构化笔记只是用户确认后的可选保存能力。

## 5. 子阶段 3.3：Wiki 链路

### 目标

实现 `answer_mode = WIKI`。

### 设计口径

Wiki 链路参考 `WebKonra / WeKnora`，核心是全量 Wiki 检索：

```text
Wiki Index / 页面列表 / 页面正文
  -> 页面链接 / 反向链接 / 图关系
  -> 页面版本 / 来源回链
  -> 基于全量 Wiki 网络的回答
  -> 默认 Wiki 工作台入口
```

### 推荐类与函数

1. `KnowledgeService.findRelevantWikiPages(workspaceId, query)`
2. `KnowledgeService.citationIdsForWikiPages(pages)`
3. `KnowledgeService.getWikiHome(workspaceId)`
4. `KnowledgeService.listWikiLinks(workspaceId)`
5. `KnowledgeService.getItemDetail(itemId)`
6. `ChatService.buildWikiAnswer(workspaceId, request)`

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
3. Wiki 工作台通过普通页面接口读取页面正文、链接和来源。

### TDD 要求

先写测试：

1. Wiki 模式优先命中已有 Wiki 页面。
2. Wiki 模式回答包含命中页面、页面关系、来源引用和默认 Wiki 工作台入口。
3. Wiki 页面追加版本后，回答读取最新版本。
4. Wiki 页面中的 `[[页面名]]` 可以生成链接关系。
5. Wiki 页面详情接口可以读取最新正文和 citation。
6. 当前工作台没有 Wiki 页面时，Wiki 模式不退回普通资料 RAG，而是提示创建或维护 Wiki 页面。
7. Wiki 构建开启后，已有 READY 资料会回补，后续资料上传会触发 `WIKI_INGEST`，自动生成或更新 Wiki 页面。
8. Wiki 支持搜索、图谱、统计、日志、lint、按当前资料重建、rebuild links、source delete retract 和 auto-fix。

### 完成定义

1. Wiki 模式基于全量 Wiki 页面网络回答，而不是直接基于普通 chunk 回答。
2. Wiki 页面支持持续维护和版本递增。
3. 用户可从聊天回答进入默认 Wiki 工作台。
4. 空 Wiki 页面场景不会产生普通资料 citation，保持全量 Wiki 知识网络的链路边界。
5. Wiki 工作台具备页面治理能力，不只是页面浏览入口。

## 6. 子阶段 3.4：Wiki 工作台治理能力

### 目标

补齐 WeKnora 式 Wiki 资产治理能力。

### 完整实现要求

1. Wiki 构建开关：`GET / PUT /api/v2/workspaces/{workspaceId}/wiki-settings`
2. Wiki ingest：资料解析完成后按 `wiki_enabled` 触发 `WIKI_INGEST`
3. Wiki search：`GET /api/v2/workspaces/{workspaceId}/wiki-search`
4. Wiki graph：`GET /api/v2/workspaces/{workspaceId}/wiki-graph`
5. Wiki stats：`GET /api/v2/workspaces/{workspaceId}/wiki-stats`
6. Wiki issues：`GET /api/v2/workspaces/{workspaceId}/wiki-issues`
7. Wiki log：`GET /api/v2/workspaces/{workspaceId}/wiki-log`
8. Rebuild links：`POST /api/v2/workspaces/{workspaceId}/wiki/rebuild-links`
9. Rebuild wiki from sources：`POST /api/v2/workspaces/{workspaceId}/wiki/rebuild`
10. Source list：`GET /api/v2/workspaces/{workspaceId}/sources`
11. Source delete + wiki retract：`DELETE /api/v2/workspaces/{workspaceId}/sources/{sourceId}`
12. Auto fix：`POST /api/v2/workspaces/{workspaceId}/wiki/auto-fix`
13. Rename page：`PATCH /api/v2/knowledge-items/{itemId}/title`
14. Soft delete page：`DELETE /api/v2/knowledge-items/{itemId}`

## 7. 子阶段 3.5：前端三模式入口与 Wiki 工作台入口

### 目标

前端不做三套聊天页面，只在同一聊天框中切换背后的回答链路。

### 完整实现要求

1. 三个模式按钮：`问答 / Note / Wiki`
2. Note 模式说明：Journal 信号、候选资料、关系扩展、原文窗口、摘录证据、带引用回答、可选保存笔记
3. Wiki 模式说明：全量 Wiki 检索、页面链接、图谱、反向链接、来源回链、默认 Wiki 工作台入口
4. Wiki 工作台入口按钮：`/workspaces/{workspaceId}/wiki`
5. 默认 Wiki 工作台视图：左侧 Wiki Index / 页面列表，中间页面正文与版本信息，右侧页面链接、统计、图谱、问题和日志
6. Wiki 页面详情：点击页面后读取最新正文、版本号和 citation
7. 右侧知识沉淀入口：保存最新回答为 Note、创建 Wiki 页面
8. Wiki 工作台页面维护入口：追加 Wiki 新版本、重命名、软删除、按当前资料重建 Wiki、重建链接、资料删除同步 Wiki、Auto Fix
9. 聊天流式结果需要展示回答正文和 citation，避免前端只展示模型正文而丢失来源闭环

## 8. 本阶段禁止项

1. 不做复杂知识图谱编辑器。
2. 不做独立 Note 工作台。
3. 不做 Research。
4. 不做 Memory 晋升。
5. 不把 Wiki 和 Note 写成同一条泛化链路。

## 9. 阶段口径

`阶段3的目标是让聊天区真正具备三种有区分度的检索型回答链路：QA 负责快速证据问答与来源覆盖，Note 参考 Marginalia 做 Journal 信号、资料级候选、关系扩展与原文窗口检索，Wiki 参考 WebKonra / WeKnora 做全量 Wiki 页面网络检索。`
