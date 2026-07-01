# NoteWeave v2 Note 链路设计

## 1. 目标

这份文档专门定义 NoteWeave v2 的 `Note 链路`。

Note 链路不是一个独立的 Note 页面，也不是“把问答结果保存成笔记”的按钮，而是统一聊天界面背后的一条回答链路。

它要解决的问题是：

- 用户在聊天中问问题时，系统不仅要回答，还要帮用户整理材料
- 回答不能只停留在一段文字，而要沉淀为有来源、有摘录、有结构的 Note 草稿
- Note 草稿后续可以继续编辑、回写、转成 Source，或进一步生成 Wiki 草稿

一句话定义：

`Note 链路是面向“边问边整理”的溯源式问答链路：系统在回答用户问题的同时，借鉴 Marginalia 的结构化检索漏斗，先定位候选资料，再读取原文窗口，抽取可引用摘录，最后生成可编辑、可回写、可复用的 Note 草稿。`

## 2. 全局前提

Note 链路不单独定义资料范围。

在 NoteWeave v2 中，`问答 / Note / Wiki` 三种链路都默认基于当前研究工作台内的全部可用资料工作，包括：

- 用户上传文件
- 已确认进入资料池的 Deep Research 报告
- 已确认进入资料池的产物
- 已确认的 Note
- 已发布的 Wiki 页面
- 其他被显式纳入当前工作台的资料对象

因此 Note 链路的差异不在“检索哪些资料”，而在“如何组织检索、如何读取原文、如何输出结果”。

## 3. 与三种链路的区别

### 3.1 问答链路

问答链路是 `Answer-first`。

它的目标是：

- 快速回答用户问题
- 给出简洁结论
- 保留必要引用
- 尽量降低交互延迟

典型输出：

```text
直接答案
  -> 关键依据
  -> 引用来源
```

### 3.2 Note 链路

Note 链路是 `Answer + Organize`。

它也必须回答问题，但回答方式更强调整理和沉淀。

它的目标是：

- 先回答用户问题
- 再把相关资料整理成结构化笔记
- 抽取可引用摘录
- 形成可编辑 Note 草稿
- 支持后续回写、转 Source 或生成 Wiki 草稿

典型输出：

```text
直接答案
  -> 关键观点整理
  -> 摘录卡片
  -> Note 草稿
  -> 可选操作：保存为 Note / 转为 Source / 生成 Wiki 草稿
```

### 3.3 Wiki 链路

Wiki 链路是 `Wiki-first`。

它的目标是：

- 优先读取已有 Wiki 页面
- 利用页面链接、反向链接和来源回链回答问题
- 让用户必要时进入默认 Wiki 工作台维护稳定知识

典型输出：

```text
基于 Wiki 的回答
  -> 简要依据
  -> 进入 Wiki 工作台
```

## 4. 借鉴 Marginalia 的设计

Note 链路重点借鉴 Marginalia 的不是界面，而是检索和阅读方法。

Marginalia 的核心思想可以概括为：

`Structured retrieval before raw reading`

也就是不要一上来就把 top-k chunk 塞给模型，而是先通过结构化信号缩小候选范围，再打开原文窗口做验证。

可借鉴的能力包括：

- `metadata-first recall`
  先看标题、摘要、标签、文件夹、类型、时间、来源等结构化信息。

- `journal / prior note signal`
  历史整理记录可以作为后续问题的导航线索，但不能直接当作最终证据。

- `related entry expansion`
  通过资料之间的共现、引用、相似主题和人工关系边扩展候选资料。

- `read original source window`
  最终要回到原文窗口读取，而不是只依赖摘要或 chunk。

- `original-source verification`
  Note 草稿里的关键观点必须能回到原始资料、页码、段落或 quote locator。

这些能力在 NoteWeave 中不需要照搬 Marginalia 的全部工程复杂度，而是收敛为一条轻量但有区分度的 Note 链路。

## 5. Note 链路主流程

推荐主流程如下：

```text
用户问题 / 当前聊天上下文
  -> 识别整理意图
  -> 工作台级资料召回
  -> SourceCard 候选资料卡
  -> SourceWindow 原文窗口
  -> ExcerptCard 摘录卡片
  -> Note Draft 草稿生成
  -> 用户确认保存 / 修改 / 转 Source / 生成 Wiki 草稿
```

### 5.1 识别整理意图

Note 链路适合以下问题：

- “帮我整理一下这些资料”
- “这几份文件对某个观点分别怎么说”
- “把这个主题总结成笔记”
- “提取关键观点和证据”
- “整理成学习笔记”
- “把这个回答保存成更正式的 Note”

如果用户只是问一个简单事实，Note 链路也要回答，但可以减少摘录卡片和草稿结构。

### 5.2 工作台级资料召回

系统默认从当前研究工作台的全部可用资料中召回，不要求用户手动选择资料范围。

召回顺序建议是：

```text
metadata / title / summary / tag
  -> lexical / vector recall
  -> prior Note / Artifact / Wiki signal
  -> related source expansion
  -> candidate source ranking
```

这里的重点是：

- 先找到“哪些资料可能相关”
- 再决定“读哪些原文窗口”
- 不直接把若干 chunk 拼成回答

### 5.3 SourceCard 候选资料卡

`SourceCard` 是 Note 链路的候选资料单元。

每张卡建议包含：

- Source ID
- 标题
- 类型
- 摘要
- 标签
- 来源时间
- 相关原因
- 命中的关键词或主题
- 是否来自用户上传、研究报告、产物或系统生成资料

SourceCard 的作用是：

- 让系统先做资料级筛选
- 让用户能理解系统为什么读这些资料
- 为后续 SourceWindow 和 ExcerptCard 提供来源边界

## 6. SourceWindow 原文窗口

`SourceWindow` 是 Note 链路区别于普通 RAG 的关键。

普通问答链路可以只读取片段，但 Note 链路需要打开原文窗口，保留上下文。

SourceWindow 建议支持：

- PDF 页码窗口
- 文档段落窗口
- Markdown 标题区间
- 网页正文区间
- 视频 / 音频转写时间段
- 表格行列区间

SourceWindow 的输出应该包含：

- source id
- locator
- 原文片段
- 上下文前后文
- 页码 / 段落 / 时间戳
- 可引用 quote
- 该窗口与问题的关系说明

这样生成的 Note 草稿才不是“模型自己总结”，而是能回到原文证据。

## 7. ExcerptCard 摘录卡片

`ExcerptCard` 是从 SourceWindow 中抽出的可沉淀片段。

它不是最终 Note，而是 Note 草稿的材料卡。

每张摘录卡建议包含：

- 摘录原文
- 中文解释
- 对应观点
- 来源定位
- 标签
- 置信度
- 是否可直接进入 Note
- 是否存在冲突或待确认

示例：

```text
观点：RAG 系统容易受检索噪声影响
摘录："..."
解释：该片段说明模型生成质量高度依赖召回材料质量。
来源：source_id + page / paragraph / quote
标签：RAG / 检索噪声 / 证据
状态：可进入 Note
```

## 8. Note Draft 草稿结构

Note 链路最终输出不应该只是一段回答，而应该生成 `Note Draft`。

推荐默认结构：

```text
# 标题

## 直接回答

## 关键观点

## 证据摘录

## 来源对比

## 待确认问题

## 可继续沉淀方向
```

不同问题可以选择不同模板：

- 概念解释型 Note
- 多资料对比型 Note
- 论文 / 报告阅读型 Note
- 学习笔记型 Note
- 观点证据型 Note

第一版不需要做复杂模板市场，只需要内置几种常见结构即可。

## 9. 输出形态

Note 链路的一次回答建议包含四层：

### 9.1 直接答案

先回答用户问题，避免用户感觉系统只在整理、不回答。

### 9.2 关键依据

列出支撑答案的核心资料和依据。

### 9.3 摘录卡片

展示可被保存进 Note 的摘录卡。

### 9.4 Note 草稿

生成一份可编辑的结构化草稿。

推荐输出：

```text
回答：
...

整理：
- 观点 1
- 观点 2
- 观点 3

摘录卡片：
1. ...
2. ...

Note 草稿：
...

操作：
[保存为 Note] [转为 Source] [生成 Wiki 草稿]
```

## 10. 回写规则

Note 链路生成的内容不能静默进入长期知识。

推荐规则：

- 用户点击 `保存为 Note` 后，生成正式 Note
- 用户点击 `更新 Note` 后，写入已有 Note 的新版本
- 用户点击 `转为 Source` 后，Note 作为正式资料进入工作台检索链路
- 用户点击 `生成 Wiki 草稿` 后，进入 Wiki 草稿链路
- 系统可以推荐回写，但不能自动替用户发布 Wiki 或晋升长期记忆

## 11. 数据对象建议

Note 链路可以复用现有领域模型，不需要引入大量新对象。

建议对象：

- `Conversation`
  保存当前聊天上下文和链路类型。

- `Source`
  承接用户上传文件、研究报告和被确认产物。

- `SourceCard`
  运行时候选资料卡，不一定持久化。

- `SourceWindow`
  运行时原文窗口，可在引用和审计中保留 locator。

- `ExcerptCard`
  可作为 Note 草稿材料，建议可持久化。

- `Knowledge Item`
  Note 和 Wiki 的统一知识对象。

- `Knowledge Version`
  保存 Note 的版本。

- `Citation`
  保存摘录、草稿与原始资料之间的引用关系。

## 12. 检索与生成策略

第一版建议保持轻量：

```text
metadata / title / summary / tag 检索
  + chunk / vector recall 辅助
  + SourceCard 排序
  + SourceWindow 原文读取
  + ExcerptCard 抽取
  + Note Draft 生成
```

暂时不做：

- 复杂知识图谱
- 自动关系治理
- 大规模 journal 失效机制
- 多智能体协作
- 独立 Note 工作台
- 用户手动选择资料范围

这些不是 Note 链路成立的前置条件。

## 13. 与 Marginalia 的取舍

保留：

- 结构化检索优先
- 候选资料卡
- 原文窗口读取
- 历史整理记录作为导航信号
- 证据和引用优先

简化：

- 不照搬完整 ReAct 调查循环
- 不把 journal 做成第一版核心强依赖
- 不做复杂 relation mining
- 不做本地优先桌面资料库形态
- 不把 Note 做成另一个独立产品

升级：

- Marginalia 更偏调查 agent；NoteWeave 的 Note 链路更偏“聊天中边问边整理”
- Marginalia 的 journal 是调查记忆；NoteWeave 的 Note 是用户可见、可编辑、可转 Source 的中间知识对象
- Marginalia 强调原文验证；NoteWeave 需要把原文验证进一步产品化成 ExcerptCard 和 Note Draft

## 14. 最终口径

Note 链路最终可以这样对外描述：

`Note 链路是 NoteWeave 中面向资料整理的溯源式问答链路。它默认使用研究工作台的全量资料底座，在回答用户问题的同时，借鉴 Marginalia 的结构化检索漏斗，先形成 SourceCard 候选资料，再读取 SourceWindow 原文窗口，抽取 ExcerptCard 摘录卡片，最终生成可编辑、可回写、可转 Source 的 Note 草稿，让一次问答自然沉淀为后续可复用的笔记。`
