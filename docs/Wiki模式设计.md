# NoteWeave v2 Wiki 链路设计

## 1. 定位

`Wiki 链路` 是统一聊天界面中的一种回答链路，它的核心差异是检索方式。

它和问答 RAG、Note 链路的用户目标是一致的：都要在聊天框里回答用户当前问题。真正不同的是背后的检索方式：Wiki 链路全面参考 `WeKnora / WebKonra` 的全量 Wiki 思路，优先检索由页面、索引、链接、图关系、来源回链、版本、日志和维护动作组成的正式知识资产，再基于命中的页面和关系回答问题。

一句话定义：

`Wiki 链路是面向长期知识网络的回答链路：系统基于当前工作台内的全量 Wiki 页面、Wiki Index、页面链接、反向链接、图关系、版本和来源引用进行检索与回答，默认不退回普通资料 chunk RAG。`

## 2. 三条链路的核心差别

| 链路 | 检索对象 | 检索方式 | 适合问题 |
|---|---|---|---|
| 问答 RAG | 工作台资料 chunk | Hybrid Retrieval + Evidence Rerank | 快速问答、连续追问 |
| Note | 资料级候选 + 原文窗口 | Marginalia 式结构化检索漏斗 | 深读资料、解释、对比、整理 |
| Wiki | 全量 Wiki 页面网络 | Wiki Index + Page Search + Links + Graph + Citation Backlinks | 已沉淀知识、概念关系、长期知识维护 |

Wiki 链路的关键不是“回答里出现 Wiki 工作台按钮”，也不是把用户带到页面里工作，而是回答前的检索上下文来自 Wiki 知识网络。

## 3. 全量 Wiki 检索对象

Wiki 链路应围绕以下对象工作：

- `Wiki Page`
  正式知识页正文。

- `Wiki Index`
  当前工作台的页面目录、页面类型、更新时间、摘要和关键词索引。

- `Page Link`
  页面中的显式链接，例如 `[[Note 链路]]`。

- `Auto Link`
  页面正文里对其他页面标题的自然提及，会被优先回写成轻量 `[[页面]]` 互链；即使不回写，也会至少补成关系边，用于增强相关页、图谱和回答命中，而不是引入复杂 slug / alias / 富编辑器体系。

- `Relation Type`
  当前页面关系边显式区分三类：
  `WIKI_LINK` 表示正文里的显式 `[[页面]]` 链接；
  `AUTO_LINK` 表示标题自然提及形成的自动互链；
  `HYBRID_LINK` 表示同一关系同时存在显式链接和自然提及。

- `Backlink`
  反向链接，用于发现哪些页面引用了当前概念。

- `Wiki Graph`
  页面之间的概念关系、主题关系、依赖关系。

- `Page Version`
  页面历史版本，回答默认读取最新版本。

- `Citation Backlink`
  页面内容回链到资料、回答或已保存产物的来源引用。

- `Wiki Issue / Lint Result`
  用于发现断链、孤立页面、缺少来源、内容过期等问题，并驱动重建链接与自动修复。

Wiki 的设计口径必须是全量 Wiki，而不是“几个页面列表 + 入口按钮”。

## 4. WebKonra / WeKnora 式主流程

```text
用户在聊天框选择 Wiki 模式并提问
  -> 查询 Wiki Index
  -> 搜索相关 Wiki 页面
  -> 扩展页面链接和反向链接
  -> 读取多页最新版本
  -> 汇总页面来源引用
  -> 基于多页面网络生成综合回答
  -> 返回默认 Wiki 工作台入口
```

当页面存在断链或知识不足时：

```text
Wiki 检索不足
  -> 标记缺失页面 / 待补充关系
  -> 提示用户创建或维护 Wiki 页面
  -> 不直接退回普通资料 RAG 强答
```

## 5. 绑定与触发规则

Wiki 知识网络绑定 `研究工作台`，不绑定单次会话。

这意味着：

- Wiki 页面、页面版本、页面链接、反向链接、来源回链都属于 `workspace_id`
- 一个研究工作台里的多个会话共享同一套 Wiki 知识网络
- 用户在任意会话中选择 `WIKI` 回答链路时，系统都读取当前工作台的 Wiki 网络
- 当前会话中的连续追问会先经过 `Topic-Aware Rolling Context Window` 编译，再用于定位同一批相关页面与页面关系
- 创建、追加、维护 Wiki 页面应通过研究工作台级按钮触发，其中自动构建是主路径，人工修补入口只用于补缺和修正
- Wiki 页面不默认绑定最近一条聊天消息
- Wiki 构建由工作台级 `wiki_enabled` 开关控制
- 当 `wiki_enabled` 从 `false` 切到 `true` 时，系统会对当前工作台内已经解析完成的资料做一次 Wiki 回补
- 当 `wiki_enabled = true` 时，后续资料上传、重解析、删除等文件变化会进入 Wiki ingest / retract 队列
- 当 `wiki_enabled = false` 时，资料只进入普通问答和 Note 检索索引，不触发 Wiki 构建
- 用户也可以在默认 Wiki 工作台中点击“按当前资料重建 Wiki”，对全部 READY 资料重新执行 Wiki ingest
- 用户删除资料时，系统会软删除 Source；如果 Wiki 构建已开启，则触发 `WIKI_RETRACT`，撤回由该资料自动生成的 Wiki 页面
- 如果用户明确要把某条回答整理进 Wiki，可以作为人工编辑/修正入口，而不是主流程
- 当前这条自动构建链路不再只是“每份资料生成一页摘要”，而是会至少维护三类页面：`资料页`、`概念页` 和 `Wiki Index`

产品上推荐：

```text
研究工作台
  -> Wiki 构建开关
  -> 资料上传 / 重解析 / 删除
  -> Wiki ingest / retract 队列
  -> 默认 Wiki 工作台入口
  -> 聊天框 WIKI 链路自动读取工作台 Wiki 网络
```

这里的“自动读取”指用户选择 `WIKI` 链路提问时，系统自动执行 Wiki 检索；这里的“自动构建”指资料变化进入异步 Wiki ingest 队列，而不是同步阻塞上传接口。

参考 WeKnora 的真实机制：

```text
IndexingStrategy.wiki_enabled
  -> knowledge post-process
  -> EnqueueWikiIngest
  -> task_pending_ops
  -> wiki:ingest batch
  -> Redis active lock / debounce
  -> Wiki Page / Link / Log / Issue
```

NoteWeave 的工程实现采用轻量等价方案：

```text
workspace.wiki_enabled
  -> enable_backfill / source parse completed / manual_rebuild
  -> WIKI_INGEST task_outbox
  -> WikiIngestService 消费并生成 / 更新资料页、概念页和 Wiki Index
  -> 写入 Citation / Link / Log / Issue Signal
  -> Wiki Search / Graph / Stats / Lint / Auto Fix
```

## 6. 回答形态

Wiki 模式回答建议包含：

```text
聊天正文
  -> 基于 Wiki 的回答 / 综合结论

可展开附属卡片
  -> 命中的 Wiki 页面
  -> 相关页面 / 反向链接
  -> 来源引用
  -> 默认 Wiki 工作台入口
```

也就是说，Wiki 链路并不是把页面网络、回链和引用全文都直接堆进正文，而是先在正文里给出综合回答，再把页面网络和来源依据以下挂卡片形式按需展开。

当前实现里，这四类对象都已经是显式结构，而不只是描述性概念：

- `相关页面`
  由命中页面的 `outgoing_links` 提供，既包含显式 `[[页面链接]]`，也包含基于标题自然提及补出的轻量关系

- `反向链接`
  由命中页面的 `backlinks` 提供

- `来源引用`
  由页面最新版本绑定的 citations 提供

- `默认 Wiki 工作台入口`
  指向 `/workspaces/{workspaceId}/wiki`

此外，当前代码里的 `wiki-search` 已经不再是简单标题匹配，而是带图谱信号的页面排序：

- 文本命中分
- `page_kind` 加权
- citation 数量
- backlink 数量
- outgoing link 数量
- version 新鲜度
- unresolved link penalty

因此 Wiki 链路当前的回答上下文，已经更接近“页面网络检索”而不是“页面列表筛选”。

并且当前回答不再只围绕单页正文展开，而是会把命中的多页上下文编排成：

- `相关 Wiki 页面`
- `综合结论`
- `关键页面关系`
- `反向引用关系`
- `来源回链`
- `默认 Wiki 工作台`

这样更贴近 WeKnora 的“先看页面网络，再定位单页”的知识回答方式。

需要强调的是，Wiki 链路虽然共享聊天上下文编译器，但它使用上下文的方式和 QA / Note 不同：

- `QA` 用上下文帮助 chunk 检索不跑偏
- `Note` 用上下文帮助候选资料和原文窗口持续围绕同一主题
- `Wiki` 用上下文帮助页面网络检索保持在同一组页面和关系上，而不是把“它 / 第二点 / 这个关系”退化成泛搜

如果没有可命中的 Wiki 页面：

```text
当前 Wiki 知识网络不足
建议先开启工作台级 Wiki 构建，或进入默认 Wiki 工作台补缺页面
默认 Wiki 工作台入口
```

这个场景不直接回退到普通资料 RAG，因为普通资料问答应由 `问答 RAG` 链路承接；深读资料应由 `Note` 链路承接。

## 7. 默认 Wiki 工作台

Wiki 工作台不是 Wiki 链路本身，而是查看和维护 Wiki 网络的辅助页面。聊天模式下用户仍然停留在聊天框里提问和接收回答。

参考 WebKonra / WeKnora，默认 Wiki 工作台至少包含两种视图：

```text
工作台总览视图
  -> 构建状态 / 页面分布 / 最近资料 / 最近更新 / 维护提醒（辅助）
页面阅读视图
  -> 左侧 Wiki Index / 页面列表
  -> 中间 Wiki Page 正文 / 版本
  -> 右侧 页面关系 / 反向链接 / 来源引用 / 图谱 / 维护工具（辅助）
```

也就是说，进入默认 Wiki 工作台时，不必先强制打开某一个页面；可以先停留在工作台总览，确认当前工作台的自动构建状态和页面沉淀状态，再按需进入具体页面；维护问题、日志和手动补页入口保持为辅助区，而不是主入口。

参考 WebKonra / WeKnora，页面阅读视图至少包含：

```text
左侧：Wiki Index / 页面列表 / 页面类型
中间：Wiki Page 正文 / 版本
右侧：页面关系 / 反向链接 / 来源引用 / 最近更新
```

这里的“版本”不只是 `latest_version_no` 一个数字，而是页面级版本历史：

- 当前最新版本正文
- 历史版本列表
- 每个版本的摘要
- 每个版本绑定的 citation 数量
- 版本来源是否来自某条聊天消息或 ingest

当前前后端已经显式支持：

- 工作台总览通过 `wiki-index` 返回构建状态、资料状态、页面分布、最近更新和维护提醒
- 工作台总览会显式展示最近 `Wiki ingest / retract / rebuild` 任务，形成工作台级运行态视图
- 工作台总览和右侧侧栏里的 `最近更新` 都已经卡片化，支持 `打开页面 / 查看子图`
- 左侧按 `wiki-search` 搜索页面，并按 `page_kind` 做稳定分组浏览
- 中间查看页面正文、`page_kind`、版本历史、版本来源、页面更新时间与历史版本详情
- 右侧优先查看出链、反链、统计和图谱；lint 问题、日志时间、构建建议和手动补页能力收纳在辅助维护区
- 全局 `Wiki Log` 也要可操作：如果日志绑定了页面，就能直接打开相关页面；如果是工作台级事件，就能回到工作台总览
- 页面级“最近变更”不再依赖前端对全量日志做本地筛选，而是直接调用 `wiki-log?item_id=` 的服务端过滤接口
- 图谱支持 `工作台总览图` 与 `当前页面局部子图`
- 图谱支持按 `page_kind` 做服务端过滤，并支持按标题 / 摘要快速定位节点后直接打开页面或切到子图
- 页面关系边会展示 `mention_count`
- 统计面板会展示 `pages_by_kind / recent_updates / pending_task_count / wiki_enabled`
- 工作台总览里的 `top_issues` 作为辅助维护提醒，可直接跳转到相关页面
- 右侧已经补出 `任务队列 + 维护问题列表 + 手动补页区`，但这些都属于辅助维护区，不是默认主叙事
- `wiki-issues` 已支持按 `issue_type / severity / auto_fixable / item_id` 过滤，前端维护问题列表直接调用这个服务端过滤接口，而不是只拉全量后本地筛选

工作台维护能力包括：

- 搜索页面和概念
- 查看页面图谱
- 读取当前工作台的 `rebuild-advice`
- 按当前资料重建 Wiki
- 重建页面链接
- 检查断链
- 检查缺少来源
- 自动修复低风险问题
- 人工确认高风险修改

当前口径下：

- `BROKEN_LINK` 属于可自动修复问题，允许通过 `Auto Fix` 创建补缺页并重建链接
- `CONTENT_STALE` 属于人工治理问题，用于发现来源资料已更新、但当前 Wiki 正文还没追上最新资料状态的页面
- `PLACEHOLDER_CONTENT` 属于人工治理问题，用于显式追踪 Auto Fix 生成但尚未补正文的占位页
- `MISSING_SOURCE / ORPHAN_PAGE` 属于需要人工确认的问题，只给出建议，不做静默修改

## 8. 页面与维护能力

Wiki 工作台覆盖完整的个人研究工作台维护闭环：

- 手动补缺或修正 Wiki 页面
- 追加 Wiki 页面版本
- 重命名 Wiki 页面
- 软删除 Wiki 页面
- 读取 Wiki Index
- 读取页面详情
- 提取 `[[页面名]]` 链接
- 展示页面关系
- 展示来源引用
- 页面搜索索引
- 反向链接
- Wiki Graph
- Wiki Rebuild Advice
- Lint
- Rebuild Links
- Auto Fix
- Wiki Log
- 资料变化触发 Wiki ingest
- 资料删除触发 Wiki retract

当前页面维护语义再补充三条：

- `重命名` 不只刷新 link table 的 `target_title / target_item_id / relation_status`，还会尽量同步刷新引用该页的其他 Wiki 页正文；如果正文里存在显式 `[[旧标题]]`，系统会改写为 `[[新标题]]`，并给引用页追加新版本，而不是原地覆写正文。
- `软删除 Wiki 页面` 后，该页自身出链会被删除；其他页面指向它的入链会回退为 `UNRESOLVED`，用于后续 lint、auto-fix 和人工修补。
- 页面删除后，如果仍有其他页面正文在引用这个标题，这些引用页依然可能通过搜索命中；这不是脏数据，而是工作台里“仍有页面提到这个缺失概念”的治理信号。
- 当用户手动创建一个与断链标题同名的补缺页时，系统会立即把现有 `UNRESOLVED` 入链回填为 `RESOLVED`，不必额外再跑一次 `rebuild-links`。
- 开启 Wiki 后回补已有 READY 资料
- 手动按当前资料重建 Wiki

当前新增的治理细节还包括：

- `wiki-graph` 支持 `overview / ego` 两种模式
- `wiki-graph` 支持 `kinds` 过滤参数，允许工作台按页面类型收窄图谱视角
- `wiki-stats` 返回最近更新、页面类型分布和待处理任务数
- `wiki-index` 返回工作台总览所需的构建状态、最近资料、页面分布和优先治理问题
- `wiki-index / wiki-stats` 会返回最近 Wiki 任务、自动修复问题数和人工确认问题数
- 工作台总览会显式展示最近资料变化时间、优先治理问题建议，并允许直达相关页面
- 最近更新页面不再只是标题列表，而是带版本、关系统计、引用统计和治理提示的操作卡片
- 最近资料变化会补出 `related_pages`，允许从资料卡直接跳到这份资料当前关联的 Wiki 页面；如果当前还没关联页面，也会直接给出 `开启构建 / 重建 Wiki` 的下一步动作
- 工作台总览里的低风险优先问题可直接触发 `Auto Fix`，不必先跳回全局维护区
- 页面级视图会显式展示问题建议、版本来源、页面更新时间和最近变更时间
- 页面级问题卡片可直接承接低风险 `Auto Fix`，避免用户离开当前页面再回到全局治理区
- 全局问题面板中的低风险问题同样可直接触发 `Auto Fix`，形成工作台级就地治理闭环
- 工作台内显式提供 `手动修补区`，高风险问题、断链边和人工确认项都可以把补缺页草稿或修正草稿直接预填进去
- 对于带来源回链的页面，系统会继续检查来源资料是否已经晚于页面版本；一旦资料更“新”，就把该页标成 `CONTENT_STALE`
- `Auto Fix` 自动创建的补缺页不会被当成“已经治理完成”，系统会继续把它们标记为 `PLACEHOLDER_CONTENT`，要求人工补齐正文、来源和页面关系
- 不同问题类型会给出不同的直接动作：`BROKEN_LINK -> 预填补缺页`，`CONTENT_STALE -> 按资料重建 Wiki`，`PLACEHOLDER_CONTENT / MISSING_SOURCE / ORPHAN_PAGE -> 进入修正模式`
- 图谱节点卡会补充 `citation_count / unresolved_count`，让图谱浏览兼具治理语义
- 页面关系会吸收正文里的自然标题提及，并优先回写为轻量 `Auto Link`；如果当前轮不适合回写，也至少保留关系补边，不引入企业级别的 slug/alias 治理复杂度
- 图谱搜索命中后既可以打开页面，也可以直接切到该页的 `ego` 子图
- 工作台会显式区分 `可自动修复` 与 `需人工确认` 两类治理问题，避免把高风险动作交给自动链路
- `人工确认队列` 不只是数量汇总，而是卡片化展示，并支持 `打开相关页面 / 定位到问题面板`
- `wiki/rebuild-advice` 返回当前工作台适合“开启自动构建 / 手动重建 / 持续演化”的动态建议
- `rebuild-advice` 不只展示消息，还会返回推荐动作码；前端会据此挂出最合适的按钮，例如 `按建议开启 Wiki 构建 / 按建议重建 Wiki / 按建议执行 Auto Fix / 按建议进入人工修正 / 查看工作台总览`
- 当推荐动作是 `按建议进入人工修正` 时，前端不会只切到问题筛选视图，而是会尽量定位到首个匹配问题，并直接打开页面或预填修补草稿
- 工作台会显式展示 `待处理任务 / 可自动修复问题 / 人工确认问题` 三个运行态 badge
- 全局问题面板会支持按 `页面范围（全工作台 / 当前页面）`、`问题类型`、`严重级别` 与 `自动 / 人工` 四层过滤
- 最近任务详情会保留 `task_type / task_status / progress_phase / progress_message / target`，并补出 `target_title` 与 `related_pages`，允许从任务卡直接回到相关 Wiki 页面

这里不引入 WeKnora 的企业级多人审核、租户权限和独立 issue 状态表，而是用 NoteWeave 的 `knowledge_item.status`、`knowledge_version`、`knowledge_item_link`、`wiki_log_entry` 和动态 lint 结果完成个人工作台治理。

## 9. 页面类型

初始页面类型：

- `概念页`
- `主题页`
- `总览页`

这三种已经足够支撑个人研究工作台的 Wiki 网络。

当前自动物化口径已经明确：

- `主题页`
  以资料标题为主键的资料页，承接该资料的摘要、关键片段、来源信息和关联概念

- `概念页`
  以概念词为主键的聚合页，承接多个相关资料页、关键依据和相邻概念

- `总览页`
  固定由 `Wiki Index` 承担，承接工作台概览、页面目录和最近资料变化

当前 `Wiki Index` 不只是导航页，还会显式写入：

- `治理概览`
  页面总数、链接总数、断链数、问题数、自动/人工治理分层和待处理任务
- `页面分布`
  当前工作台内不同 `page_kind` 的沉淀情况
- `优先治理问题`
  当前最值得先处理的问题摘要，便于聊天与工作台共用这张总览页

这里的页面体系不是为了复刻外部 Wiki 产品，而是为了服务 NoteWeave 自己的定位：

- 工作台是基本单位，不是知识库后台
- Wiki 是聊天背后的检索网络，不是独立编辑平台
- 页面关系优先服务回答、图谱和治理，不追求重型 CMS 能力
- 自动演化以 `资料页 / 概念页 / Wiki Index` 为止，不继续膨胀成复杂页面工厂

## 10. 与 Note 的区别

Note 和 Wiki 的区别不是“一个能回答，一个不能回答”，也不是“一个做笔记、一个做页面”，而是检索对象不同。

- `Note`
  从工作台资料中定位候选资料，打开原文窗口，再基于摘录证据回答。

- `Wiki`
  从已沉淀 Wiki 页面网络中检索页面、链接、反向链接和来源回链，再基于正式知识页回答。

简单说：

- Note 是“读资料来回答”
- Wiki 是“查知识网络来回答”

## 11. 与 Deep Research 和产物生成的关系

Deep Research 和右侧产物栏不是 Wiki 模式的一部分，但它们的确认结果可以进入 Wiki 网络：

- Deep Research 报告经用户确认后，可以保存为工作台资料，并进一步生成或维护 Wiki 页面
- 右侧产物栏生成的报告、FAQ、学习指南等内容，经用户确认后，可以整理为 Wiki 页面
- Wiki 页面也可以作为后续 Research 和 Artifact 任务的工作台背景

## 12. 落地范围

Wiki 链路落地范围：

```text
Wiki Page
  -> Wiki Version
  -> Wiki Index
  -> Page Link
  -> Citation Backlink
  -> Wiki Search / Graph / Stats
  -> Wiki Lint / Rebuild / Auto Fix / Log
  -> Rename / Soft Delete
  -> Wiki 模式回答
  -> 默认 Wiki 工作台
```

多人协作权限和复杂审核流不属于个人研究工作台 Wiki 的核心路径；当前架构聚焦完整覆盖 Wiki 构建、页面、版本、链接、引用、搜索、图谱、统计、日志、lint、rebuild、auto-fix、重命名和软删除。

这里的 `rebuild` 和 `rebuild-links` 是两个动作：

- `rebuild`
  重新读取当前工作台全部 READY 资料，按资料内容生成或追加 Wiki 页面版本。
- `rebuild-links`
  不重新读取原始资料，而是对现有 Wiki 页面做一次轻量互链刷新：先把可安全回写的自然提及补成 `[[页面名]]`，再刷新页面关系。

此外，工作台级 `手动补缺 / 修正 Wiki 页面` 已采用 `同标题追加版本` 语义：

- 如果标题在当前工作台内不存在，就创建新页面
- 如果标题已经存在，就直接给这张页面追加新版本
- 如果这个标题此前正好对应若干条 `UNRESOLVED` 断链，创建成功后这些入链会立刻绑定到新页面
- 这样人工修补会进入同一条页面版本链，而不会制造同名重复页

`retract` 的范围是资料生命周期驱动的自动清理：当 Source 被删除时，系统只撤回由该 Source 自动生成、且最新版本 citations 全部来自该 Source 的 Wiki 页面；人工维护的综合页、对比页和多来源页面不会被误删。

此外，当前页面详情接口也已经补齐到页面级上下文，而不是只返回正文：

- `citations`
  当前最新版本的来源回链

- `outgoing_links`
  当前页面正文中的显式链接关系

- `backlinks`
  当前有哪些页面反向引用了该页面

- `version_history`
  当前页面全部版本摘要列表，用于工作台里的版本回看

并且已经补齐版本详情接口：

- `GET /api/v2/knowledge-items/{itemId}/versions`
  返回版本摘要列表

- `GET /api/v2/knowledge-items/{itemId}/versions/{versionNo}`
  返回指定版本正文与该版本绑定的 citations

页面类型也已经不是纯文档设想，而是当前返回对象的一部分：

- `page_kind`
  当前通过标题与正文内容推断 `概念页 / 主题页 / 总览页`，并且自动物化出来的 `Wiki Index / 概念页 / 资料页` 会直接体现在前端列表、详情、搜索和图谱里

## 13. 最终口径

`Wiki 链路的核心是全量 Wiki 检索，而不是页面入口。它参考 WebKonra / WeKnora 的 Wiki 知识资产形态，在聊天时优先检索当前工作台的 Wiki Index、页面正文、页面链接、反向链接、图关系和来源回链，再基于正式知识网络回答问题；默认 Wiki 工作台只是查看、编辑和维护这套知识网络的辅助入口。`
