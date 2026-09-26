# Note 与 Wiki 业界演化研究

> `[行业参考]` 本文只为 Note 与 Wiki 设计提供外部依据。推导内容属于 `[目标设计]`，不能证明 NoteWeave 已获得生产效果。

> 本文不是 Git 历史记录，也不是把竞品功能逐项抄到 NoteWeave。它根据 NoteWeave 的 Workspace、Source、Conversation、Evidence 和版本模型，参考成熟产品与开源项目的公开设计，推导一条更合理的功能演化路线。文中“行业事实”链接到官方文档或官方仓库，“NoteWeave 建议”表示基于约束的设计推论。

## 1. 先区分 Note 与 Wiki

Note 是一次思考过程的可编辑结果，通常由资料、问答或用户输入产生。它强调低摩擦记录、证据回溯和继续编辑，适合从草稿逐步沉淀为 Source。

Wiki 是可持续维护的共享知识页面。它强调稳定标识、页面层次、链接关系、版本、权限和审计。Wiki 不是 Note 的简单“发布按钮”，而是另一种生命周期更长的知识对象。

这一区分解释了 NoteWeave 当前设计中的两个边界：

1. `source-draft` 和 `save-as-source` 只负责把回答转成用户可确认的资料，不直接替用户改写既有知识。
2. `knowledge_item`、`knowledge_version`、citation、link、graph 和 `wiki_log_entry` 共同构成 Wiki 的维护模型，检索投影只能作为查询加速，不能替代页面真源。

对应的项目设计见 [Note 链路设计](../Note链路设计.md) 和 [Wiki 模式设计](../Wiki模式设计.md)。

## 2. 可借鉴的行业事实

### 2.1 Obsidian：文件真源加链接网络

Obsidian 的官方说明把内部链接视为知识网络的基础，支持文件链接、标题链接和块链接，并在重命名文件时自动更新内部链接。它同时保留 Wikilink 和标准 Markdown 两种格式，明确提醒块引用是 Obsidian 专有能力，跨工具互操作时需要谨慎。

- [Obsidian Internal links](https://obsidian.md/help/links)
- [Obsidian Backlinks](https://obsidian.md/help/plugins/backlinks)
- [Obsidian Help 源码仓库](https://github.com/obsidianmd/obsidian-help)

可抽象出的设计知识点：

- 正向链接和反向链接应来自同一份关系数据，而不是分别人工维护。
- 链接目标要有稳定 ID，标题只是展示属性。重命名不应让历史关系失效。
- 标题级、块级引用适合精确证据定位，但专有语法会降低导出和跨系统迁移能力。
- 图谱是关系的投影。图谱展示不能成为唯一事实源，也不能把所有共现关系都误认为语义关系。

NoteWeave 因此应把 `knowledge_item_link` 作为明确关系，把标题、块或 Source 片段的定位信息放在引用中，并把 `wiki-graph` 视为可重建查询投影。

### 2.2 Outline：协作空间、集合权限和非破坏版本

Outline 将文档放入 Collection，Collection 是组织主题和权限管理的主要边界。它支持 View、View + Edit、Admin 等权限，并允许按组授予权限，再用个人权限覆盖组权限。[Outline Collections](https://docs.getoutline.com/s/guide/doc/collections-l9o3LD22sV)

Outline 会自动保存修订历史，编辑期间至少每五分钟记录一个版本。恢复旧版本不会删除历史，而是创建一个新的版本，因此恢复是非破坏操作。[Outline Revision history](https://docs.getoutline.com/s/guide/doc/revision-history-AiL6p22Ssq)

可抽象出的设计知识点：

- 权限最好绑定到内容容器，而不是散落到每个字段。
- 组权限适合规模化协作，个人覆盖适合例外授权。
- 恢复应产生新版本，避免把审计链改写成“从未发生过”。
- 修订记录需要作者、时间、差异和可恢复入口，而不只是一个 `updated_at`。

NoteWeave 的 Workspace 可以承担 Collection 的隔离职责，Wiki 页面和 Source 都必须在 Workspace ACL 下读取和写回。版本冲突时保留当前版本，不应静默覆盖其他编辑者的修改。

### 2.3 GitBook：树形导航加发布边界

GitBook 以 Space 和页面树组织文档。向空父页面添加子页面时，可以自动生成包含子页面链接的目录页，也可以隐藏页面或页面组，使编辑结构和发布导航分离。[GitBook page structure](https://gitbook.com/docs/creating-content/content-structure/page)

GitBook 的站点结构支持调整页面、分组和变体的名称及 slug，并提供 Space history 查看变更历史。[GitBook site structure](https://gitbook.com/docs/publishing-documentation/site-structure)、[GitBook UI 与 Space history](https://gitbook.com/docs/resources)

可抽象出的设计知识点：

- 树适合学习路径、目录和稳定导航，图适合发现关联，两者应并存而非互相替代。
- 编辑树不等于发布树。草稿、隐藏页和发布版本需要不同状态。
- slug 是外部链接稳定性问题，页面标题变化不应轻易破坏 URL。

NoteWeave 的 Wiki 应继续保留页面层次和稳定 permalink，同时允许通过 citation 和 link 形成跨树关系。`rebuild` 只能修复投影，不能重写用户页面的发布语义。

### 2.4 BookStack：显式层级、级联权限和引用索引

BookStack 用 Shelf、Book、Chapter、Page 构成显式层级，并提供侧边栏、面包屑、前后页和搜索等多种导航方式。[BookStack Getting Around](https://www.bookstackapp.com/docs/user/getting-around/)

BookStack 的权限来自角色，也可以在 Shelf、Book、Chapter、Page 级别覆盖。Book 或 Chapter 的权限默认级联到子内容，更具体的规则优先。[BookStack Roles and Permissions](https://www.bookstackapp.com/docs/user/roles-and-permissions/)

它还维护内部引用索引，升级或异常后可以重新生成引用。页面重命名时，系统会利用 revision 和 permalink 尝试保持旧链接可用；页面 include 会再次检查被包含页面的可见权限。[BookStack Updating](https://www.bookstackapp.com/docs/admin/updates/)、[BookStack Reusing Page Content](https://www.bookstackapp.com/docs/user/reusing-page-content/)

可抽象出的设计知识点：

- 级联权限需要明确继承、覆盖和冲突优先级，否则用户无法预测可见性。
- 引用索引属于可重建投影，必须提供 regenerate 能力。
- 页面复用会产生权限穿透风险，渲染 include 时必须按当前用户重新校验。
- 永久链接和内部 ID 要分离，标题和层级调整不应导致数据丢失。

### 2.5 腾讯 WeKnora：从 RAG 知识库走向可维护 Wiki

WeKnora 的官方仓库把产品定位为“把原始文档转成可查询 RAG、自主推理 Agent 和自维护 Wiki”。它的公开版本说明体现了一条与 NoteWeave 相近的演化方向：先支持多类型知识库和导入，再增加 Wiki 浏览器、页面引用关系图、任务队列、死信队列、索引策略和人机审批。[WeKnora 官方仓库](https://github.com/Tencent/WeKnora)、[WeKnora README](https://github.com/Tencent/WeKnora/blob/main/README.md)、[WeKnora CHANGELOG](https://github.com/Tencent/WeKnora/blob/main/CHANGELOG.md)

可抽象出的设计知识点：

- 文档解析、分块、索引和 Wiki 页面应通过异步任务解耦，否则大规模导入会阻塞交互请求。
- Wiki 入库应有失败重试和死信队列，不能把一个坏文件拖垮整个批次。
- 关系图和 Wiki 浏览器适合发现跨页面关系，但仍需要页面、引用和权限等稳定对象支撑。
- MCP 或 Agent 工具需要人机审批和作用域约束，自动化能力越强，治理边界越重要。

NoteWeave 不必复制 WeKnora 的全部运行时，而可以吸收其分层：Source 负责原始资料和版本，Wiki 负责已确认的知识页面，索引、图谱和队列任务都作为可重建投影或异步执行单元。

## 3. NoteWeave 的推测演化路线

下面的阶段不是项目提交历史，而是从最小可交付能力逐步演化到当前设计的合理路线。每一步都说明“解决了什么问题，新增了什么模型，为什么不一步到位”。

### 阶段一：回答先成为可保存的 Note

**问题**：用户问完问题后，回答只存在会话里，无法继续编辑，也无法重新进入资料体系。

**设计**：保留 `Conversation` 和 `answer_run` 作为过程对象，生成 `source-draft`，由用户确认后 `save-as-source`。草稿携带 Workspace、消息、Source 快照和 Evidence 引用。

**知识点**：草稿与正式内容分离，引用溯源，写回幂等，版本乐观锁，Workspace ACL。

**为什么不直接覆盖 Source**：LLM 输出可能有遗漏或错误，直接覆盖会破坏用户资料；草稿让人工确认成为明确的治理门槛。

### 阶段二：将 Note 提升为稳定的知识页面

**问题**：大量 Note 只能按时间流浏览，标题和内容变化后外部链接不稳定。

**设计**：引入稳定的 `knowledge_item_id`，内容保存为不可变的 `knowledge_version`。页面标题、slug、所属 Workspace 和当前版本是索引属性，历史版本保留正文摘要、作者、时间和变更原因。

**知识点**：实体与版本分离，非破坏恢复，乐观并发控制，稳定 permalink。

**为什么不只使用 Markdown 文件名**：文件名适合本地单人笔记，但协作场景需要跨用户权限、版本、数据库事务和可审计的 ID。Markdown 仍可作为导出格式，而不是唯一事实源。

### 阶段三：增加树形目录与页面导航

**问题**：页面数量增长后，单纯反向链接不能表达学习顺序和文档目录。

**设计**：增加父子关系、排序字段、面包屑、前后页和可隐藏页面。树只描述组织关系，不限制页面之间的跨主题链接。

**知识点**：有序树、slug、导航投影、草稿和发布边界。

**为什么不只做图谱**：图谱适合发现关系，但不擅长表达“先读什么、哪一页是章节入口、哪些页面属于同一手册”。树和图承担不同认知任务。

### 阶段四：增加 citation、backlink 和 graph 投影

**问题**：用户看见页面，却不知道它引用了哪份 Source，也无法发现哪些页面依赖当前页面。

**设计**：页面版本保存 citation，页面之间保存显式 link。根据 link 生成 incoming backlinks、outgoing links 和 graph 投影；投影失效时通过 `rebuild-links` 或后台任务重建。

**知识点**：正向边、反向边、引用定位、投影重建、孤儿节点和断链检测。

**为什么不采用“自动把相似度当链接”**：向量相似只说明语义邻近，不能证明作者意图；自动候选可以提示用户，但正式 link 必须可确认、可解释、可删除。

### 阶段五：把权限、修复和审计纳入 Wiki 生命周期

**问题**：多人协作和 AI 自动修复会带来越权、误改和不可追责风险。

**设计**：读操作按 Workspace 和页面权限过滤；写操作校验角色、版本和对象归属；`wiki_log_entry` 记录 rebuild、issue、auto-fix、restore 等业务动作；恢复采用新版本；修复失败保留 issue，不静默丢失。

**知识点**：ACL 继承与覆盖，审计日志，幂等操作，版本冲突，失败可重试，软删除与保留期。

**为什么不让 AI 直接发布**：AI 生成的是候选修改，不是授权事实。当前设计应让 AI 生成 draft 或 repair proposal，由规则检查和用户权限共同决定是否写入。

### 阶段六：与 RAG 和 Research 形成闭环

**问题**：Wiki 既要被问答和 Research 使用，也要把经过确认的结果沉淀回来，容易出现循环污染。

**设计**：检索只消费已发布且对当前 Workspace 可见的版本；Research 或 QA 产出先写 Evidence 和草稿，再经过人工或规则审核形成新版本。旧版本保留其引用快照，新版本通过 digest 标记来源变化。

**知识点**：发布版本与检索投影，Evidence manifest，snapshot，digest，反馈闭环，数据血缘。

**为什么不把向量库作为 Wiki 真源**：向量索引适合近似召回，不能可靠表达权限、版本冲突、作者、审计和精确恢复。MySQL 中的页面和版本是事实，向量及全文索引是可重建投影。

## 4. 当前 NoteWeave 的模块落点

| 模块 | 主要职责 | 应保持的边界 |
| --- | --- | --- |
| Note 草稿 | 将 QA 或资料内容变为可编辑草稿 | 不直接覆盖正式 Source |
| Source 写回 | 校验归属、权限和版本后创建资料版本 | 使用幂等键与乐观锁 |
| Wiki 页面 | 维护稳定知识对象和当前版本 | 不把检索索引当正文 |
| Citation | 保存页面版本与 Source 快照的证据关系 | 保留可回溯定位信息 |
| Link / Backlink | 保存页面间显式关系并生成反向查询 | 相似度只能作为候选 |
| Graph | 展示和探索关系 | 作为投影，可重建，不作为真源 |
| Rebuild / Issue | 发现断链、缺失引用和索引不一致 | 异步执行，记录任务状态 |
| Auto-fix | 生成受控修复建议或新版本 | 不能绕过 ACL 和版本校验 |
| Wiki Log | 记录业务级变更和治理动作 | 不记录密钥和不必要的隐私正文 |

## 5. 技术取舍总表

| 设计问题 | 当前建议 | 相近替代 | 取舍原因 |
| --- | --- | --- | --- |
| 正文真源 | MySQL 版本表，正文可关联对象存储 | 纯 Markdown 文件、向量库 | 需要事务、ACL、版本和审计；文件可作为导入导出 |
| 页面关系 | 显式关系表加查询投影 | 只用向量相似度、直接上图数据库 | 关系规模可控时关系表更简单；语义候选不能替代作者确认 |
| 实时编辑 | 先采用版本化写回和冲突检测 | 一开始引入 CRDT 或 OT | CRDT 适合持续多人光标协同，但会增加合并、权限和持久化复杂度；当前核心需求是安全写回 |
| 版本恢复 | 非破坏恢复，恢复产生新版本 | 覆盖旧版本、Git reset | 保留审计与证据链，避免历史被改写 |
| 权限 | Workspace 统一边界，页面级规则逐步增加 | 每个字段单独授权、完全公开 | Workspace 简化隔离；页面级覆盖满足例外，字段级授权成本和认知负担更高 |
| 目录导航 | 有序树加稳定 slug | 只用标签、只用图 | 树适合教程和手册，标签适合横向筛选，图适合发现，三者互补 |
| AI 写回 | draft、proposal、验证后发布 | 自动覆盖、无审计 Agent | 把不确定性隔离在候选层，降低错误传播 |

## 6. 推荐的教材式阅读顺序

1. 先读 [系统架构设计](../系统架构设计.md)，理解 Workspace、MySQL 真源和异步任务边界。
2. 再读 [资料基础设施详细设计](../资料基础设施详细设计.md)，理解 Source、Snapshot、Chunk 和检索投影。
3. 接着读 [Note 链路设计](../Note链路设计.md)，掌握从回答到草稿再到 Source 的写回过程。
4. 然后读 [Wiki 模式设计](../Wiki模式设计.md)，掌握页面版本、链接、图谱、重建和审计。
5. 最后对照本文的演化阶段和取舍表，判断一个新功能应该新增事实模型、投影、任务还是仅新增 UI。

判断新需求的简单规则是：

- 改变“内容是什么”，新增版本或事实模型。
- 改变“如何找到内容”，新增索引或投影。
- 改变“谁能看或改”，新增 ACL 规则和审计。
- 改变“如何从资料生成内容”，新增草稿、Evidence 和验证阶段。
- 改变“多人如何同时编辑”，才考虑 CRDT 或 OT，而不是为了看起来先进就提前引入。
