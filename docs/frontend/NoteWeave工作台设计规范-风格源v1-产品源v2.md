# NoteWeave v2 工作台设计规范

> **风格源**：NoteWeave v1（`../NoteWeave/docs/uiux/` 蓝图与 design system 原则）  
> **产品源**：NoteWeave-v2 已实现能力与本仓库前端代码  
> **状态**：PR0 设计冻结（2026-07-21）  
> **范围**：视觉与交互原则、壳层与展示契约；**不**迁移 v1 业务、路由或静态工程

关联：

- [前端现实基线-20260721](./前端现实基线-20260721.md)
- v1 参考（只读，上一级目录）：`NoteWeave/docs/uiux/NOTEWEAVE_UI_UX_BLUEPRINT.md`、`design-system/noteweave/MASTER.md`、`pages/workbench-chat.md`
- 壳层改版后续：PR1 左导航骨架 → PR2 Chat 分区观感 → PR3 二级页统一 → PR4 区域 busy

---

## 0. 一句话

**皮**（密度、阅读感、冷静导航、证据优先、少 hero）学 v1；**骨**（Workspace、三模式、Research 审计、Artifact 写回、Wiki/Memory、**非协作**）是 v2。

---

## 1. 学 / 不学

| 学（风格 · 设计） | 不学 / 不搬 |
| --- | --- |
| Warm editorial workbench：中性工作台底、克制边框、可读正文 | v1 Space / Team / Admin / 协作 IA |
| 工作台优先：首屏进任务，不做大 Hero、不做阶段宣传 | 整仓拷贝 `app.css` / 静态 `workbench-shell` DOM |
| 证据优先：citation、来源、任务态比装饰醒目 | v1 API、页面树、smoke 导航当 v2 backlog |
| 区域状态：流式/生成用局部 busy，不全站锁死 | 必须 1:1 陶土 CTA / Newsreader 字体（可按 v2 绿系转译） |
| Chat 原则：composer 贴底；右栏=工具非导航 | 强制 v1 四栏 class 名与像素宽 |

次要对照（非主源）：

- ChatGPT / Gemini：左导航、极简顶栏  
- NotebookLM：资料一等、Studio 在工具侧——仅验证分工，不替换 v2 产品叙事  

---

## 2. 产品叙事（v2，必须被 UI 看见）

> 在同一 Workspace 里，用 **可切换检索范式** 回答，用 **可审计的深度研究** 推进，用 **可版本化的产物** 回写知识。

| 能力 | 展示要求 |
| --- | --- |
| **QA / Note / Wiki** | 同一会话内一级模式切换；消息分型不同；非三个独立 App |
| **Note** | 漏斗/摘录类卡片；回答后可整理入库为 Source |
| **Wiki** | Chat 内=用 Wiki 答；治理（索引/图/lint）=独立工作台 |
| **Research** | 全页主场：报告 / 过程 / 审计（checkpoint 等）；非「高级聊天皮肤」 |
| **Artifact** | Skill → 表单 → 版本 → 写回 Source/Note/Wiki；右栏工具区 |
| **Memory** | 审核全页；不进 composer |
| **协作** | **本阶段不做**（无成员/邀请/分享/ACL 产品面） |

---

## 3. 体验原则（转译自 v1）

1. **工作台优先** — 去掉大 hero 与大块「管理卡」占屏；主区留给对话与内容。  
2. **证据优先** — citation、资料范围、任务/解析状态可扫读。  
3. **安静有层次** — 中性底 + 软边框 + 少量强调色；不靠满屏主色。  
4. **人引导 AI** — 模式、范围、Studio 操作显式；禁止假能力入口。  
5. **单操作者** — 顶栏/设置不出现协作；Workspace 仅切换当前工作台与会话。

---

## 4. 视觉 token（v1 原则 → v2 绿系转译）

当前前端已用偏绿工作台色；**结构与层级跟 v1 原则，色值可渐进收敛**，不要求一次换肤。

### 4.1 建议 CSS 变量（后续 PR 落地到 `styles.css`）

| Role | 建议值 | 说明 |
| --- | --- | --- |
| Canvas | `#f7f7f5`（现状可保留）或更暖 `#F5EFE6` | 全局背景；二选一后全站统一 |
| Surface | `#ffffff` / `#FFFAF4` | 面板、卡片 |
| Border soft | `#dfe3df` / `#D9CDBD` | 常规分割 |
| Ink | `#202124` / `#1F2937` | 主文本 |
| Ink muted | `#5f6368` / `#5B6472` | 次文本 |
| Primary CTA | `#176b4d`（现状绿） | 主按钮；与 v1 陶土不同，**允许** |
| Signal / link | 蓝系 `#2F5BD1` 档 | citation、可追踪链接 |
| Success / Warning / Danger | 绿 / 琥珀 / 红 | 仅状态，不抢 CTA |

规则：

- 背景不超过两级中性；状态色与 CTA 分离。  
- 大面积浅底深字；不默认深色模式。  

### 4.2 间距与圆角

| Token | Value |
| --- | --- |
| space 1–6 | 4 / 8 / 12 / 16 / 20 / 24 px |
| radius control | 8–10 px |
| radius card | 12–14 px |
| radius panel | 16–18 px |

### 4.3 字体（渐进）

- 现状可用系统栈（Aptos / Segoe UI）。  
- 升级可选：UI `Public Sans` 或系统 sans；长文标题可选衬线——**非 PR0 必须**。  
- 正文约 14/22；辅助 12/18；Artifact/Wiki 长文行宽约 68–76ch。

### 4.4 文案

- 产品名：NoteWeave；副文案最多一句「研究工作台」。  
- **禁止**：「阶段 x」「联调」「原型已接入…」、demo 口号墙。  
- 按钮示例：上传资料 / 发送 / 打开产物 / 深度研究 / 进入 Wiki 工作台。

---

## 5. 信息架构与壳层（目标态，供 PR1+）

### 5.1 分区（风格像 v1 密度，实现是 v2 自建）

```text
┌──────────┬─────────────────────┬──────────────────┐
│ Nav      │ Main                │ Inspector        │
│ 左       │ 主工作区            │ 右·可关          │
│ Chat     │ Chat / 研究 / Wiki  │ 产物 Studio      │
│ 研究     │ / Memory 内容       │ 情境 / 证据捷径  │
│ Wiki     │                     │                  │
│ Memory   │ composer 贴底(Chat) │                  │
│ Workspace│ 可选薄 status 条    │                  │
│ 设置*    │                     │                  │
└──────────┴─────────────────────┴──────────────────┘
* 设置：仅非协作项（如 Wiki 构建开关）；无成员/分享
```

| 区域 | 职责 | 非职责 |
| --- | --- | --- |
| **左 Nav** | 视图：Chat / 研究 / Wiki / Memory；底：Workspace 切换 | 放表单、成员、长列表 |
| **Main** | 对话流或全页工作台 | 大段品牌说明 |
| **Inspector** | Artifact、Note 入库、研究捷径、可选 citation | **一级导航** |
| **顶栏** | 默认无；必要时 Main 顶一条 status chip | Hero、大 workspace-card |

Chat 时资料：

- 目标：Sources **可扫读**（左第二列或可固定展开的资料区），避免永远折叠在 `<details>` 里才算「有资料」。  
- PR0 不强制改代码；PR2 落实。

### 5.2 路由（目标）

| Path | 视图 |
| --- | --- |
| `/` | Chat |
| `/research` | 研究工作台 |
| `/wiki`（或 wiki_url） | Wiki 工作台 |
| `/memory/reviews` | Memory 审核 |

要求：`pushState` **与** `popstate` + 启动 path 同步；刷新不丢视图意图。

### 5.3 非协作硬边界

不做：成员/邀请/角色 UI、分享链接、公开 notebook、多用户共编。  
Auth 登录/登出可保留（个人会话 ≠ 协作产品）。

---

## 6. 能力展示契约

### 6.1 三模式（Chat Main）

- 控件：segmented / mode-tabs，文案可演进为更短标签，但须保留 QA / Note / Wiki 三 key（见 `routes.tsx`）。  
- 切换时一行情境说明（各模式差异一句）。  
- 消息：继续并强化 `MessageBubble` 分模式卡片（Note 漏斗、QA citation）。  
- 空态：本模式示例问题，非通用插画墙。

### 6.2 Sources

- 展示解析/索引状态、来源徽章（上传 / 报告回写 / Note 入库）。  
- QA scope 与 Research scope **语义分开**（若同 UI，标签写清「本次问答范围」）。  
- 上传进度行内，不占消息区半屏。

### 6.3 Research

全页至少可到达：

1. **报告**（结论、证据、写回 Source）  
2. **过程**（轮次 / 泳道 / 轨迹）  
3. **审计**（checkpoint、闭环等）  

左 Nav「研究」与 Inspector「深度研究」进同一路由；审计入口在 2 次点击内。

### 6.4 Artifact（Inspector）

Skill → 表单 → 生成 → 版本 → 写回 / 重生成 / 回滚 / PDF。  
生成中可继续阅读消息（区域 busy）。默认可关；入口常在。

### 6.5 Wiki / Memory

- Chat-Wiki：检索式回答。  
- `/wiki`：治理与图谱。  
- `/memory`：审核队列。

---

## 7. 交互状态

| 场景 | 规范 |
| --- | --- |
| 回答流式 | 仅 composer / 该消息 busy；**禁止**全局 `isBusy` 锁全站（目标态） |
| 上传/解析 | Source 行 + 可选任务条 |
| Studio 生成 | Inspector 内状态；不挡中栏阅读 |
| 错误 | 行内或顶 status chip；可重试 |
| 空资料 | 可引导上传；发送策略与后端一致并明示 |

---

## 8. 演示主路径（5 分钟，验收用）

```text
1. 选/建 Workspace → 上传资料（见状态）
2. QA 提问 → citation
3. 切 Note → 结构化卡片 → 整理入库 → 资料列表出现回写痕迹
4. 打开产物 → 生成短结果 → 版本/写回可见
5. 深度研究 → 报告 → 过程或 checkpoint 入口可见
6. （可选）Wiki 工作台 / Memory 审核各点一下
```

任一步需进三层菜单或找不到模块 = 未达标。

---

## 9. 与现状差距（PR0 记录，不在本 PR 改完 UI）

| 现状（2026-07-21） | 目标 |
| --- | --- |
| `hero` + 大 `workspace-card` | 左 Nav + 薄/无顶栏 |
| 资料在 `source-drawer` 折叠 | Sources 易见 |
| 全局 `isBusy` | 区域 busy |
| `pushState` 无 `popstate` | 可刷新/后退 |
| `ui-contract` 曾假定 App 内 Artifact lazy | 已按 ChatWorkbench 对齐（见脚本） |
| App ~2580 行编排根 | 壳层改版不强制一次拆完 handler |

详见 [前端现实基线-20260721](./前端现实基线-20260721.md)。

---

## 10. PR 切片（本规范的执行顺序）

| PR | 内容 | 本规范章节 |
| --- | --- | --- |
| **PR0** | 本文 + 现实基线 + ui-contract 对齐代码 | 全文 |
| **PR1** | 左导航壳、去 hero、路由 popstate | §5 |
| **PR2** | Chat 分区、Sources/Inspector、模式情境 | §5–6 |
| **PR3** | 研究/Wiki/Memory 同一视觉语言 | §5–6 |
| **PR4** | 区域 busy、折起、空态抛光 | §7 |

**不做（本轨）**：协作 UI；拷贝 v1 工程；为瘦 App 大爆炸重写业务算法。

---

## 11. Definition of Good

用户应感到：

1. 这是研究工作台，不是阶段 demo 海报。  
2. 三模式、研究、产物差异一眼可指。  
3. 回答可追证据；任务态可扫。  
4. 生成/流式不锁死整页。  
5. 无协作噪音入口。

---

## 12. 文档权威

- 与旧「阶段 6 前端终态 = App 仅 shell」文档冲突时：**壳层视觉以本文为准**；模块拆分节奏另计。  
- 与审查报告中过时行数/orphan 面板描述冲突时：**以 [现实基线](./前端现实基线-20260721.md) 与代码为准**。  
- 风格细节与 v1 MASTER 冲突时：**原则跟 v1，色值/组件实现跟 v2 仓库**。
