# 阶段3：Note 与 Wiki 链路

## 1. 阶段目标

阶段 3 把统一聊天区补齐为三种回答链路。三条链路的共同目标都是在聊天框里回答用户问题，差别只在背后的检索方式和证据组织方式：

1. `QA`
2. `NOTE`
3. `WIKI`

这一阶段的关键不是再造一个泛化 Knowledge 系统，而是把三条有区分度的检索链路落清楚：

- `QA` 采用工作台资料 chunk 的快速召回、结构化元数据评分、来源多样性证据选择和 citation-grounded answer。
- `NOTE` 参考 `shenmintao/marginalia`：采用结构化阅读检索漏斗，先定位候选资料，再打开原文窗口和摘录证据，最后生成带引用回答；结构化笔记只是可选沉淀。
- `WIKI` 参考 `WebKonra / WeKnora` 的全量 Wiki 检索思想，但按 NoteWeave 的研究工作台定位做轻量等价实现：优先读取 Wiki Index、页面正文、页面链接、反向链接、图关系、版本和来源回链，并保留默认 Wiki 工作台入口。

绑定规则：

```text
QA：回答绑定 conversation_id，检索边界绑定 workspace_id
NOTE：回答绑定 conversation_id，检索边界绑定 workspace_id，保存后的 Note 对象绑定 workspace_id
WIKI：Wiki 知识网络绑定 workspace_id，聊天中的 WIKI 链路只读取当前工作台 Wiki 网络
```

Wiki 构建通过研究工作台级 `wiki_enabled` 开关控制，不默认绑定最近聊天消息。开启开关时会回补当前工作台内已解析完成的资料；开启后，资料上传和解析完成会进入 Wiki ingest 链路，并生成或更新工作台级 `资料页 / 概念页 / Wiki Index`、引用、链接和日志。这里强调的是“聊天背后的知识网络自动演化”，不是照抄一个独立企业 Wiki 后台。用户也可以在默认 Wiki 工作台中按当前资料手动重建 Wiki。

## 2. 当前实现状态

当前阶段的 Java + React 原型已经完成以下可运行能力：

1. `NOTE` 模式回答已对外收敛为“聊天主回答 + 资料定位 / 深读窗口 / 摘录证据”，内部仍保留 `Journal 信号 / 候选资料 / 关系扩展 / 验证摘要 / 资料元信息 / 原文窗口` 等结构用于前端分卡与契约测试
2. `条目元数据` 已补齐 `metadata_signals / parse_status / index_status / window_locators / read_role / window_has_more / related_entries / relation_reason / graph_neighborhood_score / co_cited_turns`
3. `NOTE` 候选资料召回已补成 `coverage-aware metadata rank`，会显式输出 `query_coverage / coverage_terms / matched_fields`
4. `NOTE` 候选主集合与验证批次已补入 `source-type-quota`，避免同一种资料形态挤满深读对象
5. `NOTE` 已把 `window readiness` 绑定到真实 `source_window`，不再把 `sample_text` 误判成可深读资料；验证批次会优先选择 `source-window-ready`
6. `NOTE` 的 Journal 信号已补入 `Journal Freshness Guard`：来源更新会标记 `stale-source-updated` 并降权，来源失效会标记 `source-unavailable`
7. `NOTE` 的 `直接回答` 已升级为基于原文窗口的综合回答，不再只是流程说明；当 Journal 存在 stale / unavailable 来源时，正文会显式提示“已按当前可读原文重新核对”
8. `WIKI` 模式已支持工作台级全量 Wiki 搜索、页面详情、版本历史、版本详情、出链、反链、来源回链和多页面综合回答
9. `wiki_enabled` 已打通开启回补、上传触发 `WIKI_INGEST`、删除触发 `WIKI_RETRACT`
10. 前端已支持同一聊天框切换三模式，并支持进入默认 Wiki 工作台先看工作台总览，再进入页面、版本、总览图/当前页面子图和工作台级构建建议
11. Wiki 工作台保留 `任务队列 + 维护问题列表 + 手动补页区`，但这些能力已经收进辅助维护区，不再从聊天页直接暴露修补入口
12. Wiki ingest 已从“单资料单页摘要”升级成“资料页 + 概念页 + Wiki Index”的工作台级自动物化，并通过轻量自动互链服务聊天回答、图谱和治理
13. 聊天展示层已经统一成 `主回答正文 + 可展开证据卡片`；Note 对外收敛为 `资料定位 / 深读窗口 / 摘录证据` 三张依据卡，`来源引用` 作为附属卡片默认折叠，避免正文变成证据流水账；Wiki 工作台里的最近任务、最近更新、最近资料变化和人工确认队列也都按卡片化交互呈现
13. Wiki 工作台内已经补出 `手动修补区`，断链问题、人工确认项和未解析关系边都可以直接预填补缺页或修正草稿；同标题手动修补不再创建重复页面，而是追加到现有 Wiki 页版本链
14. 手动创建与断链标题同名的补缺页后，现有 `UNRESOLVED` 入链会立即转成 `RESOLVED`，不必额外执行 `rebuild-links`
15. `Auto Fix` 创建的补缺页会继续被系统标成 `PLACEHOLDER_CONTENT`，要求人工补正文与来源，避免“自动建页后就被误判为已治理完成”
16. 带来源回链的 Wiki 页面现在还会被检测 `CONTENT_STALE`，只要其绑定资料的更新时间晚于页面当前版本，就会进入人工治理队列
17. 工作台问题卡不再只有通用按钮，而是按问题类型挂具体动作：`BROKEN_LINK -> 预填补缺页`，`CONTENT_STALE -> 按资料重建 Wiki`，`PLACEHOLDER_CONTENT / MISSING_SOURCE / ORPHAN_PAGE -> 进入修正模式`
18. `wiki/rebuild-advice` 现在会直接返回推荐动作码，前端据此选择 `开启构建 / 重建 Wiki / Auto Fix / 人工修正 / 查看总览`，不再只靠静态条件推断
19. `wiki-issues` 现在也会直接返回 `action_code`，`rebuild-advice` 还会附带 `focus_item_id / focus_title`，让前端直接打开相关页面或预填修补草稿，而不是自行推测首个治理对象
19. `最近资料变化` 在尚未关联页面时，不再只显示提示，而是直接给出 `开启构建 / 重建 Wiki` 的下一步动作入口
20. 当系统建议“进入人工修正”时，前端会进一步定位到首个匹配问题，并直接打开对应页面或预填修补草稿，而不是只切换过滤器
21. Wiki 关系边已显式区分 `WIKI_LINK / AUTO_LINK / HYBRID_LINK` 三类，前端展示为 `显式链接 / 自动互链 / 混合互链`
22. Wiki 页面重命名时，不只刷新关系边，还会同步刷新引用页正文里的显式 `[[旧标题]]` 并追加新版本
23. Wiki 页面删除后，引用它的页面关系会回退为 `UNRESOLVED`；如果引用页正文里仍提到该标题，搜索继续命中引用页属于预期治理行为

## 3. 子阶段树

```text
3.1 知识对象与版本底座
3.2 Note 链路：Marginalia 式结构化阅读检索漏斗
3.3 Wiki 链路：WebKonra / WeKnora 式全量 Wiki 检索体系
3.4 Wiki 工作台治理能力
3.5 前端三模式入口与 Wiki 工作台入口
```

## 4. 子阶段 3.1：知识对象与版本底座

### 目标

建立 Note 和 Wiki 可保存、可追溯、可更新的底座。

### 表结构

MySQL：

1. `knowledge_item`
2. `knowledge_version`
3. `knowledge_version_citation`
4. `knowledge_item_link`

### 当前类与函数

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
6. Wiki 页面版本历史可以独立读取

### 完成定义

1. Note 可以由助手回答保存而来。
2. Wiki 可以创建、列表读取、追加版本。
3. Wiki 页面详情可以读取最新正文、版本号、引用关系和页面链接。
4. Wiki 页面版本历史可以独立读取并在工作台展示。

## 5. 子阶段 3.2：Note 链路

### 目标

实现 `answer_mode = NOTE`。

### 设计口径

Note 链路参考 `shenmintao/marginalia`，不直接把 chunk top-k 当作答案上下文，而是采用：

```text
资料标题 / 摘要 / 标签 / 结构化元数据
  -> coverage-aware metadata rank（字段加权 + query coverage）
  -> 历史 Note / Journal 信号
  -> 关系信号扩展（标签重叠 + 历史 Note 共同引用 + 历史回答共引 + 标题摘要邻近 + 关系图传播）
  -> 候选资料
  -> 验证批次
  -> 条目元数据读取（tags / metadata_signals / related_entries / relation_reason）
  -> 原文读取计划（primary-window / continuation-window / secondary-window + read_objective）
  -> 摘录卡片
  -> 带引用回答
  -> 可选结构化笔记
```

当前对外最小实现只强调“主回答 + 依据卡片”：

```text
先定位资料
  -> 候选资料
再深读窗口
  -> 原文窗口
再基于证据回答
  -> 摘录证据
```

更细的 `Journal / 关系扩展 / 验证批次 / 条目元数据` 继续作为内部解释与调试结构存在，但前端展示不再把它们当成和用户平级的独立模式概念。

### 当前类与函数

1. `RetrievalService.findNoteRecallPlan(workspaceId, query)`
2. `RetrievalService.findNoteJournalHits(workspaceId, query)`
3. `RetrievalService.readEntriesMetadataForNote(workspaceId, verifySources, query)`
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
2. Note 模式回答包含 Journal 信号、关系扩展、验证批次、条目元数据、原文窗口和摘录卡片。
3. 上传资料解析后写入摘要、标签和结构化元数据。
4. 保存为 Note 后保留引用关系。
5. 保存过 Note 后，再次 Note 提问可以利用历史 Note 作为 journal 信号。
6. Note 模式回答能够显式输出 `candidate_sources / relation_expansion_sources / verify_batch_sources`。
7. Note 模式回答能够显式输出 `selection_reason / verify_admission_reason`，说明候选集合和验证批次的准入原因。
8. Note 模式回答能够显式输出 `candidate_quota_trace / verify_admission_trace`，说明整轮证据收束策略，其中包含 `source-type-quota`。
9. Note 模式回答能够显式输出 `related_entries`，并附带为什么相关的关系理由。
10. Note 模式回答能够显式输出 `window_locators`、`heading`、`read_role` 和 `read_objective`。
11. Note 模式回答能够显式输出 `co_cited_turns`，把历史回答共引纳入关系发现层。
12. Note 模式回答能够显式输出 `query_coverage / coverage_terms / matched_fields`，并验证字段加权 metadata rank 的排序结果。

### 完成定义

1. Note 模式不是 QA 换皮，而是能体现 journal 信号、候选资料、关系扩展、验证批次、原文窗口、摘录证据和带引用回答。
2. Note 必须能回答问题，结构化笔记只是用户确认后的可选保存能力。

## 6. 子阶段 3.3：Wiki 链路

### 目标

实现 `answer_mode = WIKI`。

### 设计口径

Wiki 链路参考 `WebKonra / WeKnora`，核心是全量 Wiki 检索：

```text
Wiki Index / 页面列表 / 页面正文
  -> 页面链接 / 反向链接 / 图关系
  -> 页面版本 / 来源回链
  -> 基于多页面 Wiki 网络的综合回答
  -> 默认 Wiki 工作台入口
```

### 当前类与函数

1. `KnowledgeService.findRelevantWikiPages(workspaceId, query)`
2. `KnowledgeService.citationIdsForWikiPages(pages)`
3. `KnowledgeService.findRelevantWikiPageContexts(workspaceId, query)`
3. `KnowledgeService.getWikiHome(workspaceId)`
4. `KnowledgeService.listWikiLinks(workspaceId)`
5. `KnowledgeService.getItemDetail(itemId)`
6. `KnowledgeService.getItemVersionDetail(itemId, versionNo)`
7. `ChatService.buildWikiAnswer(workspaceId, request)`

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
2. Wiki 模式回答包含命中页面、综合结论、页面关系、来源引用和默认 Wiki 工作台入口。
3. Wiki 页面追加版本后，回答读取最新版本。
4. Wiki 页面中的 `[[页面名]]` 可以生成链接关系。
5. Wiki 页面详情接口可以读取最新正文和 citation。
6. Wiki 页面版本历史接口可以按 `version_no desc` 返回版本列表。
7. Wiki 页面版本详情接口可以按 `version_no` 读取正文与 citations。
8. 当前工作台没有 Wiki 页面时，Wiki 模式不退回普通资料 RAG，而是提示创建或维护 Wiki 页面。
9. Wiki 构建开启后，已有 READY 资料会回补，后续资料上传会触发 `WIKI_INGEST`，自动生成或更新 Wiki 页面。
10. Wiki 支持搜索、图谱、图谱类型过滤、图谱节点搜索定位、统计、日志、lint、按当前资料重建、rebuild links、source delete retract、auto-fix、治理分层（可自动修复 / 需人工确认）、问题过滤面板和 rebuild-advice。

### 完成定义

1. Wiki 模式基于全量 Wiki 页面网络回答，而不是直接基于普通 chunk 回答。
2. Wiki 页面支持持续维护和版本递增。
3. 用户可从聊天回答进入默认 Wiki 工作台。
4. 空 Wiki 页面场景不会产生普通资料 citation，保持全量 Wiki 知识网络的链路边界。
5. Wiki 工作台具备页面治理能力，不只是页面浏览入口。

## 7. 子阶段 3.4：Wiki 工作台治理能力

### 目标

补齐 WeKnora 式 Wiki 资产治理能力。

### 完整实现要求

1. Wiki 构建开关：`GET / PUT /api/v2/workspaces/{workspaceId}/wiki-settings`
2. Wiki ingest：资料解析完成后按 `wiki_enabled` 触发 `WIKI_INGEST`
   当前至少维护 `资料页 / 概念页 / Wiki Index`
   并支持轻量 `Auto Link`：对页面正文中的自然标题提及做安全回写或关系补边
   `Wiki Index` 还需要内置治理概览、页面分布和优先问题摘要，成为可被检索的正式总览页
3. Wiki search：`GET /api/v2/workspaces/{workspaceId}/wiki-search`
4. Wiki graph：`GET /api/v2/workspaces/{workspaceId}/wiki-graph`，支持 `mode / center / depth / limit / kinds`
5. Wiki stats：`GET /api/v2/workspaces/{workspaceId}/wiki-stats`
6. Wiki index：`GET /api/v2/workspaces/{workspaceId}/wiki-index`
7. Wiki rebuild advice：`GET /api/v2/workspaces/{workspaceId}/wiki/rebuild-advice`
8. Wiki issues：`GET /api/v2/workspaces/{workspaceId}/wiki-issues`
9. Wiki log：`GET /api/v2/workspaces/{workspaceId}/wiki-log`
10. Rebuild links：`POST /api/v2/workspaces/{workspaceId}/wiki/rebuild-links`
11. Rebuild wiki from sources：`POST /api/v2/workspaces/{workspaceId}/wiki/rebuild`
12. Source list：`GET /api/v2/workspaces/{workspaceId}/sources`
13. Source delete + wiki retract：`DELETE /api/v2/workspaces/{workspaceId}/sources/{sourceId}`
14. Auto fix：`POST /api/v2/workspaces/{workspaceId}/wiki/auto-fix`
15. Rename page：`PATCH /api/v2/knowledge-items/{itemId}/title`
16. Soft delete page：`DELETE /api/v2/knowledge-items/{itemId}`
17. Version history：`GET /api/v2/knowledge-items/{itemId}/versions`

## 7. 子阶段 3.5：前端三模式入口与 Wiki 工作台入口

### 目标

前端不做三套聊天页面，只在同一聊天框中切换背后的回答链路。

### 完整实现要求

1. 三个模式按钮：`问答 / Note / Wiki`
2. Note 模式说明：Journal 信号、候选资料、关系扩展、原文窗口、摘录证据、带引用回答、可选保存笔记
3. Wiki 模式说明：全量 Wiki 检索、多页面综合回答、页面链接、图谱、反向链接、来源回链、默认 Wiki 工作台入口
4. Wiki 工作台入口按钮：`/workspaces/{workspaceId}/wiki`
5. 默认 Wiki 工作台视图：先显示工作台总览，再进入左侧 Wiki Index / 页面列表、中间页面正文与版本信息、右侧页面链接、统计、图谱、图谱过滤/搜索、问题、日志、最近 Wiki 任务和构建建议
6. Wiki 页面详情：点击页面后读取最新正文、版本号、citation、outgoing links 和 backlinks
7. Wiki 版本历史：在当前页面下展示版本列表、版本摘要和 citation 数，并支持点击读取指定版本详情
8. 右侧知识沉淀入口：保存最新回答为 Note、手动补缺或修正 Wiki 页面
9. Wiki 工作台需要显式展示待处理任务 badge、自动/人工问题 badge、最近任务详情，以及按页面范围、问题类型、严重级别和自动/人工治理分层的全局问题过滤面板
   页面级问题卡片还需要支持低风险 `Auto Fix` 的就地触发
   工作台总览里的优先问题与全局问题面板中的低风险问题也需要支持 `Auto Fix` 直达
   全局问题面板的结果列表直接调用 `GET /wiki-issues` 的过滤参数，不只是在前端对全量问题做本地筛选
   最近任务详情除了状态字段外，还要补出 `target_title / related_pages`，支持从任务卡直达相关 Wiki 页面
   人工确认队列也要卡片化，并支持 `打开相关页面 / 定位到问题面板`
   最近资料变化也要卡片化，并补出 `related_pages`，支持从资料卡直达当前关联的 Wiki 页面
   全局 Wiki Log 也要支持 `打开相关页面 / 返回工作台总览`
   rebuild-advice 也要从纯文本升级为推荐动作入口，支持 `按建议开启 Wiki 构建 / 按建议重建 Wiki / 查看工作台总览`
   页面级最近变更直接调用 `GET /wiki-log?item_id=` 的服务端过滤接口，不再只依赖前端本地筛日志
10. Wiki 工作台页面维护入口：追加 Wiki 新版本、重命名、软删除、按当前资料重建 Wiki、重建链接、资料删除同步 Wiki、Auto Fix
11. 聊天流式结果需要展示主回答正文，并把 `检索说明 / citation / 候选资料 / 原文窗口 / 摘录证据` 等对象以下挂可展开卡片呈现；卡片默认只展示摘要计数，点击后再展开细节；`来源引用` 只保留统一卡片，避免和正文说明、Wiki 来源回链重复堆叠

## 8. 当前接口口径

当前阶段前后端联调用到的核心接口如下：

1. `POST /api/v2/workspaces`
2. `POST /api/v2/workspaces/{workspaceId}/conversations`
3. `POST /api/v2/conversations/{conversationId}/messages`
4. `GET /api/v2/chat/requests/{assistantRequestId}/stream`
5. `POST /api/v2/messages/{messageId}/save-as-note`
6. `POST /api/v2/workspaces/{workspaceId}/knowledge-items`
7. `POST /api/v2/knowledge-items/{itemId}/versions`
8. `GET /api/v2/knowledge-items/{itemId}`
9. `GET /api/v2/knowledge-items/{itemId}/versions`
10. `GET /api/v2/knowledge-items/{itemId}/versions/{versionNo}`
11. `GET /api/v2/workspaces/{workspaceId}/wiki-home`
12. `GET /api/v2/workspaces/{workspaceId}/wiki-search`
13. `GET /api/v2/workspaces/{workspaceId}/wiki-graph`
14. `GET /api/v2/workspaces/{workspaceId}/wiki-stats`
15. `GET /api/v2/workspaces/{workspaceId}/wiki-index`
16. `GET /api/v2/workspaces/{workspaceId}/wiki/rebuild-advice`
17. `GET /api/v2/workspaces/{workspaceId}/wiki-issues`
    支持 `issue_type / severity / auto_fixable / item_id` 过滤参数
18. `GET /api/v2/workspaces/{workspaceId}/wiki-log`
19. `PUT /api/v2/workspaces/{workspaceId}/wiki-settings`
20. `POST /api/v2/workspaces/{workspaceId}/wiki/rebuild`
21. `POST /api/v2/workspaces/{workspaceId}/wiki/rebuild-links`
22. `POST /api/v2/workspaces/{workspaceId}/wiki/auto-fix`

## 9. 本阶段禁止项

1. 不做复杂知识图谱编辑器。
2. 不做独立 Note 工作台。
3. 不做 Research。
4. 不做 Memory 晋升。
5. 不把 Wiki 和 Note 写成同一条泛化链路。

## 10. 阶段口径

`阶段3的目标是让聊天区真正具备三种有区分度的检索型回答链路：QA 负责快速证据问答与来源覆盖，Note 参考 Marginalia 做带字段加权与 query coverage 的资料级候选、Journal 信号、关系扩展与原文窗口检索，Wiki 参考 WebKonra / WeKnora 做全量 Wiki 页面网络检索。`
