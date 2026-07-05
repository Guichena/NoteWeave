# NoteWeave v2 Note 链路设计

## 1. 定位

`Note 链路` 不是“只负责生成笔记”的模式，而是统一聊天界面中的一种回答链路。

它和问答 RAG、Wiki 链路的用户目标是一致的：都要在聊天框里回答用户当前问题。真正不同的是背后的检索方式：Note 链路参考 `shenmintao/marginalia` 的个人资料库检索思路，先用资料级结构化信号缩小候选范围，再打开原文窗口读取证据，最后生成带引用回答。结构化笔记只是这条链路回答后的附加沉淀能力，不是主职责。

一句话定义：

`Note 链路是面向资料深读的回答链路：用户仍然在聊天框里提问，系统先通过标题、摘要、标签、目录、历史笔记、结构化元数据和关系信号定位候选资料，再读取原文窗口和摘录证据，最终生成可引用回答；如果用户需要，再把回答沉淀为结构化笔记。`

## 2. 三条链路的核心差别

三条链路的主要区别不是“前端长得不一样”，也不是“最终产物不一样”，更不是“一个负责回答、一个负责整理、一个负责页面维护”。它们都服务聊天框回答，差别只在背后的检索路径不同。

| 链路 | 检索重点 | 回答特点 |
|---|---|---|
| 问答 RAG | 直接对资料 chunk 做快速混合召回、rerank 和证据拼装 | 快、轻、适合连续追问 |
| Note | 先做资料级候选定位，再打开原文窗口读取证据 | 更像深读资料，适合整理、比较、解释和带引用回答 |
| Wiki | 基于全量 Wiki 页面、索引、页面链接、图关系和来源回链检索 | 优先使用已沉淀知识网络回答 |

因此 Note 链路必须能回答问题。它不是把 QA 的结果包装成笔记，而是用 Marginalia 式检索方式得到不同的证据上下文，再回答同一个用户问题。

## 3. Marginalia 式检索方式

Note 链路学习的是 Marginalia 的检索漏斗：

```text
metadata / folder / catalog / tag / journal / relation
  -> candidate entries
  -> candidate triage
  -> verify batch
  -> source windows
  -> evidence excerpts
  -> cited answer
  -> optional structured note
```

对应到 NoteWeave：

```text
资料标题 / 摘要 / 标签 / 文件夹 / 文档类型 / 历史笔记 / 关系信号
  -> 候选资料
  -> 候选资料排序
  -> 验证批次
  -> 原文窗口
  -> 摘录卡片
  -> 带引用回答
  -> 可选保存为结构化笔记
```

这条链路不应该一上来就取 chunk top-k。它先判断“哪些资料值得读”，再决定“打开哪些原文窗口”，最后才让模型基于可验证片段回答。

工程实现中，`recall_knowledge` 被拆成 NoteWeave 内部的轻量步骤：

```text
search_journal
  -> search_metadata
  -> relation_hint_expand
  -> candidate_triage
  -> build_verify_batch
  -> read_entries_metadata
  -> read_source_windows
  -> evidence_excerpt_cards
```

这些步骤主要服务实现和调试，不作为对外产品口径平铺展示。现行方案只强调“主回答 + 依据卡片”：

```text
先定位资料
  -> 候选资料
再深读窗口
  -> 原文窗口
再基于证据回答
  -> 摘录证据
```

其中 `search_journal` 不是额外记忆系统，而是读取用户保存过的 `KnowledgeItem(type=NOTE)` 及其 citation，作为“哪些资料曾经被整理过、哪些结论曾经被引用过”的结构化检索信号。
同时，Journal 信号不是永远等权复用：系统会校验历史 Note 绑定的来源是否已经更新或失效。来源已更新的历史 Note 会被标成 `stale-source-updated` 并降权；来源失效的历史 Note 只作为审计线索保留，不再强驱动候选召回。

## 4. 核心原则

### 4.1 工作台是默认边界

Note 链路默认只在当前研究工作台内工作，不做全局开放召回。

工作台内可参与候选定位的对象包括：

- 用户上传文件
- 文本或网页导入资料
- 音视频转写文本
- 用户保存的结构化笔记
- 已确认并保存为资料的研究报告
- 已确认并保存为资料的右侧产物
- 已维护的 Wiki 页面资料化版本

### 4.2 绑定规则

Note 链路的回答行为绑定 `conversation_id`，因为它发生在某一次聊天上下文里；但它的检索边界绑定 `workspace_id`，因为资料池属于研究工作台。

这意味着：

- 用户在某个会话里选择 `NOTE` 链路提问
- 系统读取该会话的近期上下文理解问题，并通过 `Topic-Aware Rolling Context Window` 编译最近相关多轮对话
- 系统只在当前会话所属工作台内做 Marginalia 式资料级检索
- 本次回答保存为聊天消息，属于该会话
- 如果用户点击“保存为 Note”，才会生成工作台级 `KnowledgeItem(type=NOTE)`

简单说：

```text
Note 回答：conversation_id
Note 检索资料边界：workspace_id
保存后的 Note 对象：workspace_id
```

其中连续对话上下文不会直接替代资料检索，而是先被编译成：

- `连续对话窗口`
- `主题锚点`
- `前序主题摘要`

再注入候选资料定位、原文窗口打开和摘录证据整理这三个步骤，保证 Note 在多轮追问里仍然围绕同一批资料持续深读。

### 4.3 先找资料，再读片段

Note 链路的关键不是多拿几个 chunk，而是先通过结构化信号找资料：

- 标题
- 摘要
- 标签
- 文件夹
- 文档类型
- 创建时间和更新时间
- 历史笔记
- 历史整理记录
- 资料间引用或主题关系

只有候选资料确认后，系统才打开原文窗口读取可验证片段。

候选资料排序由以下信号共同决定：

- `metadata signal`
  标题、摘要、标签、类型、结构化元数据和样本文本。
  当前实现不是把这些字段简单拼接后做一次命中判断，而是采用 `coverage-aware metadata rank`：
  按 `title / summary / tags / metadata / sample_text / source_type` 做字段加权，
  同时计算 `coverage_terms / query_coverage / matched_fields`，让覆盖更多查询意图、且命中更关键字段的资料排在前面。

- `journal signal`
  已保存 Note 的标题、摘要、正文与 citation 回链。
  当前实现还会做 `Journal Freshness Guard`：
  如果历史 Note 绑定的 source 在记录后已经更新，则该 Note 进入 `stale-source-updated`；
  如果绑定 source 已失效，则进入 `source-unavailable`。
  这两类历史 Note 仍可在 Journal 卡片中展示，但排序会落后于 fresh Note，且不会再和最新资料同权参与候选召回。

- `relation signal`
  候选资料之间的标签重叠、主题共现和历史 Note 共同引用。
  当前实现会把命中的资料作为 anchor，基于共享标签、历史 Note 共同引用、历史回答共引和关系图传播给相邻资料加权，并在候选资料的召回信号中标记为 `relation-expansion`。

- `source-type quota`
  候选主集合不会默认被同一种资料形态挤满。
  当前实现会在 `journal-quota / metadata-quota / relation-quota` 之后，再补一层 `source-type-quota`，
  让不同 `source_type` 的资料都更有机会进入候选主集合与验证批次，
  例如让 `MARKDOWN / PDF / SPREADSHEET / LOG` 这类不同形态的资料同时参与深读，而不是只保留同类型高分资料。

- `window readiness`
  资料是否已经完成切片并具备可打开的原文窗口。
  当前实现不再把 `sample_text` 误当成“可深读”的证据，而是显式基于 `source_window` 数量判断：
  有真实原文窗口的资料会得到 `source-window-ready` 信号与可读性加分；
  没有原文窗口的资料会被标记为 `source-window-missing`，并在验证批次里尽量后置，只在工作台确实缺少可读窗口时才以 `window-fallback` 方式兜底保留。

### 4.4 回答是主线，笔记是附加

Note 链路默认要回答用户问题。

结构化笔记的定位是：

- 对高价值回答进行保存
- 把证据摘录组织成后续可复用材料
- 支撑后续 Wiki 页面和右侧产物生成

所以文档、代码和前端都不应该把 Note 描述成“只生成笔记”。

## 5. 回答形态

Note 模式的回答不应该把所有检索过程都塞进聊天正文。
像引用来源、摘录证据、原文窗口这类“证明回答为什么成立”的对象，可以不直接铺在正文里，而是以下挂折叠卡片的形式呈现，用户点击后再看细节。

现行对外口径进一步收敛为“主回答 + 依据卡片”：

```text
聊天正文
  -> 主回答

可展开附属卡片
  -> 资料定位
  -> 深读窗口
  -> 摘录证据
  -> 引用来源
```

这里的“依据卡片”不是指底层只做这几件事，而是指用户感知层只需要看到和当前回答直接相关的解释对象：

- `资料定位`
  对应检索说明、候选资料、关系扩展和验证批次，回答“这轮先锁定了哪些资料”
- `深读窗口`
  对应条目元数据和原文读取计划，回答“接下来系统具体打开哪些原文窗口深读”
- `摘录证据`
  对应本轮真正参与回答的原文片段，回答“这次回答具体引用了哪些证据”

前端呈现上，这些信息不再混在聊天正文里顺序铺开，而是统一做成可点击的信息卡片：

- 正文只保留主回答
- `资料定位 / 深读窗口 / 摘录证据` 作为 Note 的主要依据卡片固定出现
- 卡片摘要直接显示 `几份候选资料 / 几个阅读窗口 / 几条摘录证据 / 几条来源引用`
- 用户点开后再看原文窗口定位、摘录内容和引用细节
- `来源引用` 只保留一张统一卡片，避免和正文说明或 Wiki 回链信息重复展示

这样做的原因是：

- 聊天区首先要可读，用户应先看到结论
- Note 链路的深读特征仍要保留，但用户不需要先理解一整套检索治理结构
- 引用、摘录和原文窗口默认折叠，避免把一屏聊天正文挤成“证据流水账”
- 后续右侧产物生成和保存为 Note 时，也更容易直接复用这些结构化卡片，而不是反解析整段正文

### 5.1 直接回答

先回答用户问题，不能让用户感觉系统只是在整理材料。

当前实现里，`直接回答` 已经不再只是“我准备怎么检索”的流程说明，而是会先基于本轮命中的原文窗口给出一版可验证综合结论；如果 Journal 里存在 `stale-source-updated` 或 `source-unavailable`，正文也会显式说明“本轮已按当前可读原文重新核对”，避免把旧整理当成当前事实。

### 5.2 候选资料卡

展示系统为什么选择这些资料，而不是直接展示一堆 chunk。

候选资料卡应包含：

- 资料标题
- 资料摘要
- 命中原因
- 标签或类型
- 可读片段数量
- `query_coverage`
  当前 query 被这份资料覆盖了多少个关键项。
- `coverage_terms / matched_fields`
  这份资料具体覆盖了哪些查询词、命中了哪些 metadata 字段。
- `selection_reason`
  这份资料是因为 `journal-quota / metadata-quota / relation-quota / source-type-quota / top-score-backfill` 中的哪一类准入槽位进入候选主集合。

### 5.3 关系扩展卡

关系扩展不是直接把更多 chunk 塞给模型，而是把与主候选资料相关、但还没有进入主候选集合的资料显式列出来。

当前实现里，关系扩展主要来自：

- 标签重叠
- 历史 Note 共同引用
- 历史回答共引
- 与 anchor 资料的主题相邻性

这些资料会进入扩展候选，用于后续验证批次，而不是直接替代主候选资料。

### 5.4 验证批次卡

验证批次是当前实现里非常关键的一层，用来把“候选资料”和“关系扩展资料”收束成真正要打开原文窗口的一小批可验证对象。

默认结构包括：

- `candidate_sources`
  当前轮主候选资料数量

- `relation_expansion_sources`
  当前轮关系扩展资料数量

- `verify_batch_sources`
  最终进入原文窗口读取的资料数量

- `trace`
  本轮 metadata、journal、relation 三类信号的累计得分

- `verify_admission_reason`
  每份资料为什么进入验证批次，例如 `candidate:journal-quota`、`candidate:metadata-quota`、`candidate:source-type-quota`、`candidate:top-score-backfill`、`relation-expansion` 或 `relation-expansion:source-type-quota`

- `candidate_quota_trace / verify_admission_trace`
  不只解释单条资料，也解释整轮证据收束策略：
  前者汇总候选主集合的准入槽位分布，后者汇总验证批次的准入来源分布。

这一步的价值是：

- 避免 Note 链路退化成“全库 chunk top-k”
- 让回答前真正完成一次资料级 triage
- 让前端和测试能看到这轮回答到底验证了哪些资料

### 5.5 条目元数据卡

对齐 `marginalia/read_entries_metadata` 的思路，Note 链路在进入原文窗口之前，会先读取验证批次中每个资料的结构化元数据视图。

当前实现会为每个 verify source 补齐：

- `tags`
  资料标签与主题词

- `metadata_signals`
  资料摘要、解析元数据、entry strategy、chunk/window 数量等结构化信号

- `related_entries`
  与该资料存在共享标签、历史 Note 共引、标题摘要邻近或关系图传播邻近的相邻资料

- `read plan`
  把高分窗口细分为 `primary-window / continuation-window / secondary-window`，更像 `marginalia/read_files` 里的“先打开主片段，再沿附近窗口续读”的精读方式
  当前实现还会显式补出 `read_objective`，区分 `best-evidence / adjacent-context / secondary-evidence`

这一步的目的不是直接回答，而是先让系统知道“这份资料是什么、可读性如何、和谁相关”，再决定后续窗口阅读和证据摘录。

当前代码已经把这一层显式落成了可返回结构，而不是抽象概念：

- `tags`
  直接来自 `source.tags_json`

- `metadata_signals`
  由 `source.summary`、`source.metadata_json`、chunk / window 数量等信号拼成

- `parse / index state`
  显式返回当前资料的 `parse_status / index_status`，避免把“可读性”只当成隐含前提

- `window_locators`
  提前给出该资料下最值得打开的窗口定位，包含 `chunk_no / heading / window_no / location_info / score / read_role / read_objective`

- `window_has_more`
  当当前展示的 window locators 只是这份资料可读窗口的一部分时，显式告诉上层还有更多窗口可继续展开

- `related_entries`
  给出共享标签、历史 Note 共引、标题摘要邻近和关系图传播形成的相邻资料预览，并附带关系理由

- `co_cited_turns`
  给出该资料是否曾在历史某一轮回答中与当前 anchor 资料共同被引用，用来模拟 `mine_citation_graph` 带来的细粒度关系发现

### 5.6 原文窗口卡

原文窗口是 Note 链路区别于普通 RAG 的关键。

窗口可以对应：

- PDF 页码范围
- Markdown / Word 段落
- 网页正文片段
- 表格行列范围
- 音视频转写时间段

当前实现不是把全部窗口一股脑塞进模型，而是遵守两层收束规则：

- 先按 `source` 分桶，每份资料最多保留 2 个高分窗口
- 再做一次全局排序，最终最多带 8 个窗口进入回答

此外，当前 `source_window` 已不再等同于“一个 chunk 只有一个窗口”，而是在解析阶段把 chunk 进一步切成多个重叠 read windows。这样 `window_no`、`window_locators`、`continuation-window` 和后续精读计划才真正有意义，而不是对整块 chunk 的伪窗口命名。

这样可以保证 Note 真的是“先定资料、再开窗口”，而不是退化成高噪声 chunk 堆叠。

### 5.7 摘录证据卡

摘录证据用于支撑回答中的关键结论。

每条摘录应包含：

- 原文摘录
- 来源位置
- 对应观点
- 相关原因
- 是否存在待确认或冲突

### 5.8 可选结构化笔记卡

当用户点击保存或需要整理时，系统可以把本次回答沉淀为结构化笔记。

默认结构：

```text
# 标题

## 直接结论

## 关键观点

## 证据摘录

## 来源对比

## 待确认问题

## 后续整理方向
```

## 6. 检索流程

```text
用户在聊天框提问
  -> 读取当前研究工作台
  -> 解析问题意图
  -> search_journal：从已保存 Note 和 citation 回链读取历史整理信号
  -> journal freshness guard：校验历史 Note 绑定来源是否已更新或失效，对 stale / unavailable Journal 降权
  -> search_metadata：基于标题、摘要、标签、结构化元数据和样本文本做字段加权 metadata rank，并输出 query coverage / matched fields
  -> relation_hint_expand：用标签重叠、历史共同引用、标题摘要邻近、关系图传播和窗口可读性补充关系信号
  -> candidate_triage：按 journal / metadata / relation 配额选出主候选资料
  -> build_verify_batch：把主候选和关系扩展收束为验证批次
  -> read_entries_metadata：读取 verify source 的 tags / metadata_signals / related_entries
  -> 按 primary / continuation / secondary 读取计划打开验证批次中的原文窗口
  -> 抽取摘录卡片
  -> 基于证据和引用生成回答
  -> 用户可选保存为结构化笔记
```

当前 Java 原型中的对应落点是：

- `RetrievalService.findNoteRecallPlan(...)`
  负责 `search_journal / journal freshness guard / search_metadata / relation_hint_expand / candidate_triage / build_verify_batch`
  其中候选排序还会显式纳入 `window readiness`，优先保证真正能打开原文窗口的资料先进入验证批次

- `RetrievalService.readEntriesMetadataForNote(...)`
  负责 `tags / metadata_signals / window_locators / related_entries / co-cited-turns`
  其中 `window_locators` 会显式补出 `read_objective`

- `RetrievalService.openSourceWindowsForNote(...)`
  负责按 source 分桶生成精读读取计划，并输出带 `heading / window_no / location_info / read_role / read_objective` 的窗口集合

- `ChatService.buildNoteAnswer(...)`
  负责把 Journal 信号、候选资料、关系扩展、验证批次、条目元数据、关键观点、摘录证据和引用回答组织成可直接流式返回的文本结构

## 7. 内部对象

```text
Workspace
  -> Source
  -> SourceMetadata
  -> NoteRecallPlan
  -> NoteRecallTrace
  -> NoteEntryMetadata
  -> RelatedEntryPreview
  -> SourceWindow
  -> NoteJournalSignal
  -> ExcerptCard
  -> Citation
  -> KnowledgeItem(type=NOTE)
```

其中：

- `SourceMetadata` 承载标题、摘要、标签、文件夹、类型等候选定位信号
- `NoteRecallPlan` 承载 journal hits、candidate sources、relation expansion sources 和 verify batch
- `CandidateSource` 当前不仅承载标题、摘要和召回信号，还会显式携带 `selection_reason / verify_admission_reason`
- `NoteRecallTrace` 承载本轮召回中 metadata / journal / relation 三类信号的汇总轨迹
- `NoteEntryMetadata` 承载 verify source 的 tags、metadata_signals、chunk/window readiness 和 related_entries
- `RelatedEntryPreview` 承载共享标签与历史共引形成的相邻资料视图
- `RelatedEntryPreview` 当前还会显式输出 `co_cited_turn_count / relation_reason / lexical_overlap_score / graph_neighborhood_score`，用于说明该资料为什么会被拉入邻接推荐
- `SourceWindow` 表示可回跳原文窗口
- `NoteJournalSignal` 来自已保存 Note 及其 citation 回链，用于提示“哪些资料被历史整理过”
- `NoteJournalSignal` 还会附带 freshness 状态，区分 `fresh / stale-source-updated / source-unavailable`
- `ExcerptCard` 表示摘录证据
- `KnowledgeItem(type=NOTE)` 只表示用户保存后的结构化笔记，不等于 Note 链路本身

## 8. 落地范围

Note 链路落地范围：

```text
资料级候选定位
  -> 历史 Note / Journal 信号召回
  -> 关系信号扩展
  -> 验证批次构建
  -> 原文窗口读取
  -> 窗口相关性重排
  -> 摘录证据
  -> 带引用回答
  -> 可选保存为 Note
```

当前阶段已经完成的实现边界：

- 服务端输出已收敛为 `资料定位 -> 深读窗口 -> 摘录证据` 三段，前端默认以下挂依据卡片展示
- `资料定位` 内部继续带 `Journal 信号 / 候选资料 / 关系扩展 / 验证摘要`
- `深读窗口` 内部继续带 `资料元信息 / 原文窗口`
- 已把 `coverage-aware metadata rank` 落到代码里，候选资料和验证批次会显式输出 `query_coverage / coverage_terms / matched_fields`
- 已补入 `source-type-quota`，避免候选主集合和验证批次被同一种资料形态挤满
- 已把 `window readiness` 接到真实 `source_window` 上，候选资料与验证批次会区分 `source-window-ready / source-window-missing`
- 已把 `heading`、`window_locators` 和 `read_objective` 纳入返回契约与测试
- 已把 `Journal Freshness Guard` 落到代码里，历史 Note 会根据来源更新或失效状态自动降权
- 已支持 `save-as-note`，并把回答引用保留到 `knowledge_version_citation`

当前阶段暂不额外引入：

- 多智能体角色分工
- 独立 Note 页面
- 向量数据库专用实现

Note 链路不单独拆成另一个产品页面，它始终服务聊天框回答。以下能力作为 Note 链路的设计边界处理：

- 不把 Note 写成 QA 换皮
- 不把 Note 写成只生成笔记
- 不把 Note 与 Wiki 合并成一条泛化链路
- 不把保存后的 Note 自动发布成 Wiki 页面
- 不引入多智能体协作来完成资料深读

## 9. 最终口径

`Note 链路的核心不是生成笔记，而是采用 Marginalia 式结构化检索漏斗回答问题。它先通过 search_journal、带字段加权与 query coverage 的 search_metadata，以及 relation_hint_expand 定位候选资料，再通过 candidate triage 和 verify batch 收束真正要验证的资料窗口，最后抽取摘录卡片并生成带引用回答；在交互上，聊天区只保留正常回答，资料定位、深读窗口、摘录证据与引用来源通过可展开卡片呈现；结构化笔记只是用户确认后的附加沉淀能力。`
