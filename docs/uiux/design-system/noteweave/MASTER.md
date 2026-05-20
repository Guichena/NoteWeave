# NoteWeave Design System Master

> 使用规则：
> 1. 先看 `pages/[page-name].md` 是否存在。
> 2. 若存在，则页面 override 优先于本文件。
> 3. 若不存在，则严格按本文件落地。

---

**Project:** NoteWeave  
**Generated Base:** `ui-ux-pro-max`  
**Curated For:** AI 知识工作台 / 团队协作 / 个人研究

## 1. Product Archetype

NoteWeave 不是营销站，也不是纯 BI 仪表盘。

它的正确产品形态是：

- AI knowledge workspace
- evidence-first research tool
- team knowledge base + personal research desk
- data-dense workbench with editorial reading surfaces

因此整体视觉应该是：

- 左右分栏的工作台骨架
- 中高信息密度
- 稳定、克制、可信
- 阅读体验优先于炫技
- 状态感和证据感强

## 2. Experience Thesis

### 2.1 关键词

- Warm editorial workbench
- Calm density
- Trustworthy evidence
- Operational clarity
- Human-guided AI

### 2.2 三条体验原则

1. 工作台优先  
首屏必须直接进入任务，不做大 Hero，不做大段品牌口号。

2. 证据优先  
Citation、来源、任务状态、最近处理记录都应当比装饰更醒目。

3. 安静而有层次  
通过纸感背景、克制阴影、明确边框和少量强调色建立层级，而不是靠大面积饱和色。

## 3. Visual Direction

`ui-ux-pro-max` 的检索里，适合 NoteWeave 的不是单纯的落地页 minimalism，也不是硬核 BI 蓝色大盘，而是两者的混合：

- 页面模式：`Data-Dense + Drill-Down`
- 内容气质：`editorial / trustworthy / informative`
- 色彩基底：`Knowledge Base/Documentation` + `SaaS General`

最终定稿方向：

- 工作区采用温暖中性色
- 操作与导航采用冷静蓝
- 强调操作采用陶土橙
- 内容阅读面使用高对比、低噪音排版

## 4. Color System

### 4.1 Core Palette

| Role | Hex | Token | Usage |
|---|---|---|---|
| Canvas | `#F5EFE6` | `--bg-canvas` | 全局背景 |
| Surface | `#FFFAF4` | `--bg-surface` | 主卡片、主面板 |
| Surface Alt | `#F1E8DB` | `--bg-surface-alt` | 次级容器、hover |
| Border | `#D9CDBD` | `--border-soft` | 常规边框 |
| Border Strong | `#B9A892` | `--border-strong` | 激活态、分隔强调 |
| Ink | `#1F2937` | `--text-strong` | 主文本 |
| Ink Soft | `#5B6472` | `--text-muted` | 次文本 |
| Accent Warm | `#C96A3D` | `--accent-warm` | 主 CTA、关键操作 |
| Accent Warm Deep | `#A8522A` | `--accent-warm-deep` | hover/pressed |
| Signal Blue | `#2F5BD1` | `--signal-blue` | 链接、会话激活、可追踪状态 |
| Success | `#2E7D61` | `--signal-success` | 完成、已连接 |
| Warning | `#A56A17` | `--signal-warning` | 处理中、等待中 |
| Danger | `#BA4A3C` | `--signal-danger` | 失败、删除、断连 |

### 4.2 Color Rules

- 背景最多使用 2 个暖色层级，不要把页面做成大面积泛黄。
- 信息状态优先使用蓝、绿、橙、红，不要用暖棕色承担全部语义。
- CTA 与系统状态颜色分离：CTA 用 `Accent Warm`，系统状态用 `Signal Blue/Success/Warning/Danger`。
- 大面积容器优先浅底深字，不默认上深色模式。

## 5. Typography

`ui-ux-pro-max` 的 typography 检索里，最适合 NoteWeave 的方向是 editorial + utility 的组合，而不是手写体，也不是纯代码风。

### 5.1 Font Pairing

- Heading: `Newsreader`
- UI / Body: `Public Sans`
- Mono: `JetBrains Mono`

### 5.2 Usage

- 一级标题、Artifact 标题、Wiki 页面标题使用 `Newsreader`
- 导航、表格、输入框、状态栏、按钮统一使用 `Public Sans`
- 引用定位、任务 id、文件 hash、调试信息使用 `JetBrains Mono`

### 5.3 Type Scale

| Token | Size | Usage |
|---|---|---|
| `--text-display` | `40/44` | 极少量关键标题 |
| `--text-h1` | `32/38` | 页面标题 |
| `--text-h2` | `24/30` | 区块标题 |
| `--text-h3` | `18/24` | 卡片标题 |
| `--text-body` | `14/22` | 常规正文 |
| `--text-small` | `12/18` | 标签、辅助说明 |

### 5.4 Type Rules

- 页面里最多只允许一个 serif 角色：标题或长文标题，不要混用多种衬线体。
- 表格和表单不使用 serif。
- 大段 Artifact/Wiki 正文行宽控制在 `68ch - 76ch`。

## 6. Spacing And Radius

| Token | Value |
|---|---|
| `--space-1` | `4px` |
| `--space-2` | `8px` |
| `--space-3` | `12px` |
| `--space-4` | `16px` |
| `--space-5` | `20px` |
| `--space-6` | `24px` |
| `--space-8` | `32px` |
| `--space-10` | `40px` |

| Radius Token | Value |
|---|---|
| `--radius-sm` | `10px` |
| `--radius-md` | `14px` |
| `--radius-lg` | `18px` |
| `--radius-xl` | `24px` |

规则：

- 页面主容器默认 `18px - 24px` 圆角。
- 小按钮与输入框统一 `10px - 12px` 圆角。
- 不要同屏出现超过 3 种圆角等级。

## 7. Shadow And Depth

| Token | Value | Usage |
|---|---|---|
| `--shadow-1` | `0 8px 24px rgba(68, 45, 24, 0.08)` | 卡片轻微抬升 |
| `--shadow-2` | `0 18px 40px rgba(68, 45, 24, 0.12)` | 主面板、抽屉 |
| `--shadow-3` | `0 28px 60px rgba(68, 45, 24, 0.16)` | 浮层、模态 |

规则：

- 以边框分层为主，阴影分层为辅。
- hover 不使用明显 scale，优先边框加深、背景轻变、阴影轻抬升。

## 8. Layout System

### 8.1 App Shell

标准结构：

```text
Left Rail / Top Context Bar / Main Workspace / Right Context Drawer
```

推荐桌面布局：

- 左导航：`248px - 272px`
- 主内容：自适应
- 右抽屉：`340px - 380px`

### 8.2 Responsive Breakpoints

- Mobile: `< 768px`
- Tablet: `768px - 1179px`
- Desktop: `1180px - 1439px`
- Wide: `>= 1440px`

规则：

- Desktop 才显示固定三栏。
- Tablet 收起右侧抽屉为覆盖层。
- Mobile 不保留永久三栏，采用分段视图和底部主操作。

## 9. Component Rules

### 9.1 Buttons

- 主按钮：暖色实底，用于创建、发送、发布、确认。
- 次按钮：浅底描边，用于切换、打开、筛选。
- 危险按钮：仅删除、取消、停用。

交互：

- hover 仅做颜色和阴影变化
- 禁止通过 `transform: scale()` 造成布局跳动
- disabled 必须降低透明度并禁用 cursor

### 9.2 Inputs

- 输入框高度统一
- 说明文字放上方，不做仅靠 placeholder 的表单
- 错误信息必须有文字，并用 `aria-live` 或 `role=alert`

### 9.3 Cards

- NoteWeave 卡片是“工作卡”，不是营销卡
- 卡片内容优先级：标题 > 状态 > 最新活动 > 次要元数据
- 避免卡片内部再嵌大卡片

### 9.4 Tables

- 管理后台、任务列表、文档列表优先表格
- 表头固定中性浅底
- 行 hover 允许轻微高亮
- 状态列必须同时有文字和颜色

### 9.5 Badges

- Badge 只承担状态和标签，不承担主要导航
- 状态 badge 使用统一语义色
- 同一视图不要出现 6 种以上 badge 颜色

### 9.6 Drawer

- 右抽屉用于 Citation、上下文、任务详情、版本详情
- 抽屉标题区固定，正文滚动
- 抽屉内首屏先展示“为什么打开它”，再展示全文

## 10. Motion

- 统一过渡时长：`160ms / 220ms / 280ms`
- 只对以下内容做动画：
- 抽屉展开/收起
- 流式消息渐入
- 列表筛选结果切换
- Toast 进出场

禁止：

- 大面积漂浮动画
- 高频 shimmer
- 连续 scale 弹跳

需要支持：

- `prefers-reduced-motion`

## 11. Accessibility

来自 `ui-ux-pro-max` 检索结果，以下规则对 NoteWeave 是强制项：

- 流式回答必须逐步显示，不要长时间只显示 spinner
- 错误提示必须可被读屏宣布
- 文本对比度至少 `4.5:1`
- 颜色不能单独承担状态语义
- 所有内容图像必须有 `alt`

此外：

- 所有可点击卡片必须有 `cursor-pointer`
- 键盘焦点样式必须明显
- Citation 点击后应可通过键盘关闭抽屉并返回触发点

## 12. Page Pattern Library

NoteWeave 页面只使用以下模式：

1. Navigator + Detail  
适用于知识库、Wiki、Artifact、Admin Logs

2. Session Rail + Stream + Context Drawer  
适用于 Chat / Workbench

3. Project Overview + Tabbed Detail  
适用于 Personal Research

4. Dense Table + Filter Toolbar  
适用于 Admin

5. Reading Surface + Action Sidebar  
适用于 Artifact 阅读与编辑

## 13. Anti-Patterns

不要使用：

- 营销型单列大首页当作工作台主页
- 过于“AI 产品模板化”的紫粉渐变
- 手写体、脚本体、过强装饰字体
- 大面积深色背景配低对比度文本
- 过度玻璃拟态
- 卡片套卡片再套卡片
- 只有颜色没有文字的状态表达
- 纯装饰型图表

## 14. Delivery Checklist

- [ ] 首屏是工作任务而非品牌宣言
- [ ] 左导航、顶部上下文栏、主工作区职责清晰
- [ ] Citation / Evidence 比装饰更容易被发现
- [ ] 所有状态有文本、有颜色、有层级
- [ ] Artifact/Wiki 正文阅读宽度舒适
- [ ] 表格、表单、消息流使用统一间距和圆角
- [ ] 375px、768px、1024px、1440px 下无横向滚动
- [ ] keyboard/focus/error/live region 已覆盖
