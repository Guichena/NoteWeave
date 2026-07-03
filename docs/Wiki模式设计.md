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
  -> 读取页面最新版本
  -> 汇总页面来源引用
  -> 基于 Wiki 网络生成回答
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
- 创建、追加、维护 Wiki 页面应通过研究工作台级按钮触发
- Wiki 页面不默认绑定最近一条聊天消息
- Wiki 构建由工作台级 `wiki_enabled` 开关控制
- 当 `wiki_enabled` 从 `false` 切到 `true` 时，系统会对当前工作台内已经解析完成的资料做一次 Wiki 回补
- 当 `wiki_enabled = true` 时，后续资料上传、重解析、删除等文件变化会进入 Wiki ingest / retract 队列
- 当 `wiki_enabled = false` 时，资料只进入普通问答和 Note 检索索引，不触发 Wiki 构建
- 用户也可以在默认 Wiki 工作台中点击“按当前资料重建 Wiki”，对全部 READY 资料重新执行 Wiki ingest
- 用户删除资料时，系统会软删除 Source；如果 Wiki 构建已开启，则触发 `WIKI_RETRACT`，撤回由该资料自动生成的 Wiki 页面
- 如果用户明确要把某条回答整理进 Wiki，可以作为人工编辑/修正入口，而不是主流程

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
  -> WikiIngestService 消费并生成 / 更新页面
  -> 写入 Citation / Link / Log / Issue Signal
  -> Wiki Search / Graph / Stats / Lint / Auto Fix
```

## 6. 回答形态

Wiki 模式回答建议包含：

```text
基于 Wiki 的回答
命中的 Wiki 页面
相关页面 / 反向链接
来源引用
默认 Wiki 工作台入口
```

如果没有可命中的 Wiki 页面：

```text
当前 Wiki 知识网络不足
建议创建或维护 Wiki 页面
默认 Wiki 工作台入口
```

这个场景不直接回退到普通资料 RAG，因为普通资料问答应由 `问答 RAG` 链路承接；深读资料应由 `Note` 链路承接。

## 7. 默认 Wiki 工作台

Wiki 工作台不是 Wiki 链路本身，而是查看和维护 Wiki 网络的辅助页面。聊天模式下用户仍然停留在聊天框里提问和接收回答。

参考 WebKonra / WeKnora，默认 Wiki 工作台至少包含：

```text
左侧：Wiki Index / 页面列表 / 页面类型
中间：Wiki Page 正文 / 版本
右侧：页面关系 / 反向链接 / 来源引用 / 最近更新
```

工作台维护能力包括：

- 搜索页面和概念
- 查看页面图谱
- 按当前资料重建 Wiki
- 重建页面链接
- 检查断链
- 检查缺少来源
- 自动修复低风险问题
- 人工确认高风险修改

## 8. 页面与维护能力

Wiki 工作台覆盖完整的个人研究工作台维护闭环：

- 新建 Wiki 页面
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
- Lint
- Rebuild Links
- Auto Fix
- Wiki Log
- 资料变化触发 Wiki ingest
- 资料删除触发 Wiki retract
- 开启 Wiki 后回补已有 READY 资料
- 手动按当前资料重建 Wiki

这里不引入 WeKnora 的企业级多人审核、租户权限和独立 issue 状态表，而是用 NoteWeave 的 `knowledge_item.status`、`knowledge_version`、`knowledge_item_link`、`wiki_log_entry` 和动态 lint 结果完成个人工作台治理。

## 9. 页面类型

初始页面类型：

- `概念页`
- `主题页`
- `总览页`

这三种已经足够支撑个人研究工作台的 Wiki 网络。

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
  只重新解析现有 Wiki 页面正文中的 `[[页面名]]`，刷新页面关系，不重新读取原始资料。

`retract` 的范围是资料生命周期驱动的自动清理：当 Source 被删除时，系统只撤回由该 Source 自动生成、且最新版本 citations 全部来自该 Source 的 Wiki 页面；人工维护的综合页、对比页和多来源页面不会被误删。

## 13. 最终口径

`Wiki 链路的核心是全量 Wiki 检索，而不是页面入口。它参考 WebKonra / WeKnora 的 Wiki 知识资产形态，在聊天时优先检索当前工作台的 Wiki Index、页面正文、页面链接、反向链接、图关系和来源回链，再基于正式知识网络回答问题；默认 Wiki 工作台只是查看、编辑和维护这套知识网络的辅助入口。`
