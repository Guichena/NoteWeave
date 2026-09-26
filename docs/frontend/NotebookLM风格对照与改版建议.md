# NotebookLM 风格对照与改版建议

> 只读，不改代码。  
> 产品方向：**像 Google NotebookLM 的来源笔记本**，借鉴空间语法和干净程度，不复制品牌、插画、文案或功能名。  
> 本文覆盖前两份里互相打架的审美建议：
> - [架构审美排版审查](./前端架构审美与排版审查报告.md) 里「海军+珊瑚、永久三栏」——三栏留下，配色不要珊瑚海军仪表盘。
> - [GPT-Gemini 对照](./GPT-Gemini风格对照与改版建议.md) 里「藏起来源、对话占满」——**不再采用**。NotebookLM 的来源列是产品本身。
>
> 架构债（`App.tsx` 总编排、双 CSS、主题双份）仍然有效。

---

## 1. 目标：笔记本，不是聊天站，也不是监控台

NotebookLM 打开一本 notebook 时，屏幕几乎固定是：

```text
┌ 笔记本标题 ─────────────────────────────────────────┐
│ 来源 Sources │ 对话 Chat              │ Studio 产物 │
│ 列表+勾选    │ 基于已选来源提问         │ 音频/指南/  │
│ 添加来源     │ 回答带数字引用           │ 笔记/简报   │
└──────────────┴─────────────────────────┴────────────┘
```

和 ChatGPT / Gemini 的差别：

| | GPT / Gemini | NotebookLM | NoteWeave 应对 |
| --- | --- | --- | --- |
| 主角 | 对话 | **来源集合** | Workspace 里的资料 |
| 左栏 | 历史会话 | **来源列表** | 完整来源，可勾选进本次问答 |
| 中栏 | 无边界聊天 | 对着这堆来源聊 | Chat（QA / Note / Wiki 是问法，不是三个 App） |
| 右栏 | 偶尔 Canvas | **常驻 Studio** | Artifact：讲义、测验、脑图、摘要 |
| 空状态 | 「有什么想问」 | 「先添加来源」 | 没资料时中栏也引导加来源 |

仓库设计规范第 6 节已经写了这套隐喻（Workspace = 笔记本，Sources = 来源，Chat = 提问，Artifact = Studio）。实现没有把这一屏当默认产品。

不完全照抄：可以保留 Deep Research、Wiki、Memory，但它们是**这本笔记本上的动作或深层页**，不是和笔记本平级的五个网站。

---

## 2. 现在差在哪

### 已经接近的

- 意图上的三栏：左资料、中对话、右产物。
- Workspace 作为「这一本」的边界。
- 引用、可检索 / 处理中状态、Skill 网格（Audio / 笔记 / 测验 / 脑图，和 Studio 卡片很像）。
- 浅色画布、圆角、chip，不是重仪表盘皮肤。

### 不像 NotebookLM 的

1. **五个一级入口**（对话 / 资料库 / 研究 / Wiki / 记忆）把「一本笔记本」拆成套件。NotebookLM 顶部是笔记本标题，不是 App 切换器。
2. **左栏不是来源列表。** `ChatSourcesPane` 只是摘要：三格统计、最近 4 条、大按钮「打开资料库」。NotebookLM 左栏是完整清单 + 勾选哪些进入对话 + 添加来源。勾选对应你们已有的 QA `sourceScope`。
3. **右栏不是常驻 Studio。** 产物是全屏 modal，对话和来源被盖住。NotebookLM 的 Studio 一直在，空着也占位（「Audio Overview」「Study Guide」等卡片）。
4. **中栏在教架构。** 英文 label、模式说明书、资料库→会话流程图。NotebookLM 中栏很空：来源够了就问，不够就提示加来源。
5. **会话列表塞在全局 rail。** NotebookLM 很少把「聊天线程」当主导航；一本 notebook 通常一个对话语境。你们可以保留多会话，但应是笔记本内部的次级切换，不是和「研究/Wiki」并列。
6. **引用不够「数字角标」。** NotebookLM 是正文里 `[1][2]`，点开看原文片段。你们是消息下多张 `details` 卡片，Note/Wiki 还会摊漏斗和关系。
7. **资料库是另一页。** 上传/解析可以是来源列顶部的「添加」或轻量抽屉，不必先离开笔记本。
8. **视觉偏 ChatGPT 盒 + 后台顶边色条。** NotebookLM 更像 Material 3：大圆角卡片、轻分隔、来源行可勾选、Studio 卡片浅底，没有模式说明横条，也没有英文装饰标签。

---

## 3. 建议的默认屏（借鉴，不抄名）

```text
┌ NoteWeave    《工作台名称》                          [共享] [账号] ┐
├──────────────┬──────────────────────────┬────────────────────────┤
│ 来源  12     │  问这批资料               │  Studio                │
│ [+] 添加     │                          │  音频摘要  学习指南     │
│ ☑ 讲义.pdf   │  （消息 / 空状态）        │  测验      脑图         │
│ ☑ 笔记.md    │                          │  研究报告  课程笔记     │
│ ☐ 草稿.txt   │  ┌──────────────────┐    │  最近生成…             │
│ 处理中…      │  │ 输入…        发送 │    │                        │
└──────────────┴──────────────────────────┴────────────────────────┘
```

映射：

| NotebookLM | NoteWeave 用自己的词 |
| --- | --- |
| Notebook | 工作台（顶栏标题，可改名） |
| Sources | 来源 |
| Chat | 对话；模式下拉：问答 / 精读 / 知识库（内部仍是 qa/note/wiki） |
| Studio | 产物 |
| 勾选来源 | 已有 QA `selectedQaSourceIds`，做成来源行上的勾选 |
| 添加来源 | 打开上传，不必跳整页；解析中在该行显示状态 |
| 引用数字 | 回答里的来源角标，点开看窗口原文 |
| Audio Overview 等 | 现有 Skill 卡片，少滤镜、少「Workspace Studio」英文 |

Deep Research：产物列一张「深入研究」卡片，或对话里一次发起；过程用对话内进度，不要默认三列审计墙。  
Wiki：来源/对话之外的「知识页」，从工作台菜单或产物「保存到知识库」进入。  
Memory：设置或审核入口，不进主三栏。

---

## 4. 布局与排版（Notebook 口径）

**栏宽（桌面 ≥ 1280）：** 来源约 260–300px（完整列表），对话自适应，产物约 300–340px 常驻。中等宽度：来源和产物改成顶上两个 toggle，主列仍是对话。不要 GPT 方案那种默认藏来源。

**左栏来源行：** 勾选 + 文件图标 + 标题 + 一行状态（可检索 / 处理中 / 失败）。不要三格大数字 + 「绑定当前工作台」说明 + 再跳转资料库。空状态：短句「添加来源后即可提问」+ 添加按钮。

**中栏：** 去掉 `Current conversation`、模式说明条、知识路径图。空状态一句话即可。Header：工作台或会话名 + 问法下拉。composer 贴对话列底部，圆角可以大，但不要在盒外再写范围长文——范围就是左边勾选。

**右栏 Studio：** 桌面始终在。上：可生成的卡片（用你们自己的 Skill 名）。下：最近一次产物状态。点卡片进入该 Skill 表单（仍在右栏内，不要 modal）。

**引用：** 正文数字芯片，点击弹出该 source 的一段原文（你们已有 window/locator）。消息底部最多一个「来源」折叠，不要默认四张卡。

**字号：** 来源标题 14px，对话正文 15–16px，Studio 卡片标题 14px，辅助 12–13px。不要 0.58rem 英文 label。

**颜色：** 保持浅色笔记本，一种行动色（现有绿可用）。少顶边领域彩条、少网格光晕。卡片用浅灰底 + 细边，不要大阴影悬浮。

**圆角：** 比现在的 8/10/14 可以略靠近 Material（控件 12、卡片 16、面板 20），但不要做成 GPT 那种独立悬浮聊天气泡墙。

---

## 5. 建议顺序

**P0 把默认屏收成一本笔记本**

1. Chat 桌面三栏常驻：完整来源列表 | 对话 | 产物网格。产物从 modal 改回右栏。
2. 来源行支持勾选，接到现有 QA scope；列表展示全部来源，不只 4 条。
3. 删中栏说明书和流程图；问法下拉放到对话标题旁。
4. 中文 UI；Studio 标题用「产物」。

**P1 入口降级**

5. 顶栏是工作台名。rail 五入口收到菜单：资料处理详情、Wiki、Memory、设置。
6. 「添加来源」在左栏完成上传；资料库页改为高级/全部文件管理。
7. 回答改为数字引用芯片。
8. 收紧双 CSS 里吞掉来源列的 `layout-with-inspector` 规则（必须三列同时在）。

**P2**

9. Deep Research 从产物或对话发起，报告可进右栏或中栏长文。
10. Wiki 当笔记本的知识页，不是默认治理三列。
11. 单一 Theme provider；整理 `visual-refresh.css` 重复项。

`ui:check` 目前要求 Chat 里有 `sources-pane`、Artifact lazy、`mode-context-line`。改 P0 时要同步脚本：资料列应保留，`mode-context-line` 可以删，Artifact 改为栏内 lazy 而不是 portal。

---

## 6. 不要照抄的

- NotebookLM 的插画、Google 彩标、Audio Overview 播放器皮肤、英文 Sources/Studio 词。
- 强制单会话；你们可以有多会话，只要藏在笔记本内部。
- 把 Wiki 治理、Memory 审核、Research checkpoint 删掉；只是不要放在进门第一屏。
- 为了像而重写 API 或状态机。

---

## 7. 源码复核（对照当前 `frontend/src`）

复核结论：**上一节判断成立，没有写反。** 又核对到几条实现细节，改 P0 时要一起看。

### 7.1 已核对为事实

| 点 | 代码位置 | 现状 |
| --- | --- | --- |
| 左栏不是完整来源表 | `ChatSourcesPane.tsx`：`sources.slice(0, 4)`，aria-label「资料库摘要」 | 三格统计 + 最近 4 条 +「打开资料库」；无勾选、无添加、无点开原文 |
| QA 范围存在但放错地方 | `useChatSessionController.toggleQaScope`；`ChatWorkbench` composer 里 `details` + `filter-pill` | 只在问答模式、输入盒下方；Note/Wiki 发送时 `source_scope_source_ids` 为空 |
| 产物默认不在场 | `ChatWorkbench`：`artifactRailOpen` 初始 `false`；`createPortal` + `role="dialog" aria-modal="true"` | 发送行「产物」才打开全屏层；打开时 `document.body.style.overflow = hidden` |
| 三栏 class 未使用 | 根节点 `className="layout"`，从不加 `layout-with-inspector` | `ChatWorkbenchContext.dom.test.tsx` **断言** inspector class 为 false，且必须是 `dialog` |
| 一级导航五个 | `WORKBENCH_NAV_ITEMS` | 对话 / 资料库 / 研究 / Wiki / 记忆 |
| 上传不在笔记本左栏 | `SourceLibraryWorkbench` 支持拖放 PDF/MD/TXT/JSON/CSV | 添加来源必须离开 Chat 进 `/library` |
| 引用不是文内数字角标 | `MessageBubble`：`details.message-card`，标题「来源引用」默认 `open` | 底部卡片 + 编号列表；Note 还会多漏斗类卡片 |
| Studio 里混了别的 App | `ArtifactUtilityPanel`：「工作台工具」打开 Wiki / Research / Memory、开关 Wiki 构建、Note 入库 | 右栏不像 Studio，像总控 |

### 7.2 先前略写的

- **「填入对话」**（`ArtifactRail`）会把 Skill 表单灌进 Chat composer。NotebookLM 是在 Studio 里直接生成。可保留为高级能力，不要当主按钮压过「生成产物」。
- **脑图预览**另有一层 `createPortal` dialog（`ArtifactMindMapPreview`）。即便产物回到右栏，脑图仍可能再盖一层，需要单独收进栏内。
- **中栏模式选择器**已经是下拉，不是三 Tab，这一点接近笔记本；问题是它在发送行，说明书还在头顶。
- **空状态有示例 chip**，这可以留；要删的是知识路径图和模式说明条。

### 7.3 改三栏时会被测试挡住

`ChatWorkbenchContext.dom.test.tsx` 当前锁定：

- `.layout` 没有 `layout-with-inspector`
- 产物是 `aria-modal="true"` 的 dialog
- 仍能看到「资料库摘要」

落地 Notebook 默认屏时，这些断言要改成：三列同时存在、产物是 `aside` 而非 modal、来源列表可勾选。`ui:check` 仍要求 `sources-pane` 和 Artifact lazy，与三栏不冲突；`mode-context-line` 若删除需改脚本。

---

## 8. 一句话

NoteWeave 适合做成 **「带来源勾选和产物卡的笔记本」**，不是 GPT 那种纯对话，也不是现在五个工作台并列。  
默认就三栏：**来源（完整、可勾选）| 对话（干净）| 产物（常驻卡片）**。Deep Research / Wiki / Memory 从这本笔记本长出来。  
源码复核：左栏摘要、产物 modal、范围在输入盒下、测试还锁着「不要 inspector」——P0 就是改这四件，不是再找新方向。
