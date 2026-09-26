# NoteWeave v2 全站视觉基线

## 1. 设计定位

NoteWeave v2 面向研究人员与知识工作者，是一个证据优先的中性研究工作台。界面应让用户快速分辨来源、证据、状态和当前操作；装饰只用于组织空间与建立品牌识别，不抢夺研究内容的注意力。

视觉方向：冷静、可信、克制，业务工作台采用冷白编辑画布与深色研究 rail；登录/注册入口采用 v1 提取出的暖纸张底色与墨蓝封面。珊瑚色负责 NoteWeave 的操作节奏，蓝、绿、琥珀只在对应研究领域承担语义。v1 项目只作为视觉参考来源，不作为 v2 的文案、业务语义、路由或信息架构来源。

- `DESIGN_VARIANCE`: 5
- `MOTION_INTENSITY`: 4
- `VISUAL_DENSITY`: 7

## 2. 设计原则

1. 证据优先：来源、引用、检索状态和回答内容拥有最高视觉优先级。
2. 语义优先：组件使用语义 token，不在组件内部重新定义颜色和阴影。
3. 领域色有边界：珊瑚色表达主要操作和 NoteWeave 品牌节奏；蓝色表达 Research / 证据定位；绿色表达 Wiki 健康与完成；琥珀表达 Memory 审核与等待。颜色不跨领域泛化。
4. 层级优先：通过画布、下沉面板、普通面板、提升卡片和覆盖层区分空间，不依赖大面积渐变。
5. 可访问性优先：正文、控件、焦点、错误和禁用态都必须保持可识别；动效必须支持 `prefers-reduced-motion`。

## 3. 颜色语义

所有颜色由 `frontend/src/styles/tokens.css` 的语义 token 提供，`visual-refresh.css` 只负责组件与布局。业务组件优先使用语义 token，不直接写 hex、rgba 或局部渐变。

| 语义 | Token | 用途 |
| --- | --- | --- |
| 页面画布 | `--nw-color-canvas` | 页面主背景 |
| 画布深层 | `--nw-color-canvas-deep` | 页面底部或低层级区域 |
| 画布洗色 | `--nw-color-canvas-wash` | 页面顶部的轻微层次过渡 |
| 下沉面板 | `--nw-color-surface-sunken` | 代码区、输入组、次级容器 |
| 普通面板 | `--nw-color-surface` | 侧栏、普通内容面板、控件 |
| 提升面板 | `--nw-color-surface-raised` | 当前操作卡片、认证卡片 |
| 覆盖层 | `--nw-color-surface-overlay` | 弹层、菜单、对话框 |
| 悬停面 | `--nw-color-surface-hover` | 可交互元素 hover |
| 按下面 | `--nw-color-surface-pressed` | 可交互元素 active/pressed |
| 主文字 | `--nw-color-ink` | 标题和核心内容 |
| 次文字 | `--nw-color-ink-soft` | 标签和辅助标题 |
| 弱文字 | `--nw-color-muted` | 描述、占位、次要信息 |
| 结构主色 | `--nw-color-primary` | 结构文字、通用选中辅助 |
| 结构深色 | `--nw-color-primary-deep` | rail、品牌深色面 |
| 结构浅层 | `--nw-color-primary-soft` | 通用轻量标记 |
| 证据蓝 | `--nw-color-signal` | 来源、引用、证据链 |
| 证据蓝浅层 | `--nw-color-signal-soft` | 证据标签和辅助背景 |
| 暖色辅助 | `--nw-color-accent` | 轻量提示和少量品牌温度 |
| 成功 | `--nw-color-success` | 成功和完成 |
| 警告 | `--nw-color-warning` | 风险、等待、需要注意 |
| 错误 | `--nw-color-danger` | 错误和阻断 |

工作台背景允许使用单一、极低对比度的方格线组织空间。认证页可以使用低对比度暖纸张横纹，但暖色认证底不得进入业务工作台；禁止使用暖橙与冷蓝同时形成装饰性光晕，也禁止用渐变替代内容层级。

## 4. 画布、面板与层级

页面遵循四级空间层级：

1. `sunken`：下沉区域，与页面画布形成轻微反差，用于输入组、代码块和次级内容。
2. `default`：普通面板，用于侧栏和大多数工作区容器。
3. `raised`：提升卡片，用于当前任务、认证表单和需要聚焦的内容。
4. `overlay`：覆盖层，用于菜单、弹窗和临时操作。

提升面和覆盖层应少量使用，并配套 `--nw-shadow-raised`、`--nw-shadow-overlay`。边框优先使用 `--nw-color-border-soft`，只有需要明确分隔或聚焦时才使用 `--nw-color-border-strong`。

## 5. 形状、间距与字体

- 控件圆角使用 `--nw-radius-control`。
- 内容卡片圆角使用 `--nw-radius-card`。
- 工作台面板和认证容器使用 `--nw-radius-panel`。
- 胶囊形状只用于 tabs、状态和筛选控件。
- 间距使用 `--nw-space-*`，新组件不得散落任意间距值。
- 正文使用无衬线字体；标题通过字号、字重和留白建立层级，不使用大面积口号或装饰性标点。

## 6. 状态与动效

所有异步区域都必须有 loading、empty、error 和完成态。Hover 只改变面或边框对比度；Focus 必须保留清晰主绿色焦点环；Disabled 降低对比度但仍要能识别控件边界。动效只用于加载、反馈和层级变化，并支持 `prefers-reduced-motion`。

## 7. 页面落地顺序

1. 统一 tokens、页面画布和基础 elevation。
2. 统一 `WorkbenchShell`、侧栏、面板、按钮、输入框、tabs 和状态组件。
3. 将登录/注册页作为第一批验证页面，检查背景、卡片、表单和响应式层级。
4. 依次扩展到 Chat、Research、Wiki、Memory 和 Artifact 页面。

## 8. 研究依据

本基线采用“语义 token + 中性层级 + 明确 elevation”的方向，参考了以下公开设计系统：

- [Microsoft Fluent 2 Color Tokens](https://fluent2.microsoft.design/color-tokens/)：颜色按语义角色组织，并区分中性、品牌和状态用途。
- [Atlassian Design Tokens](https://atlassian.design/foundations/design-tokens)：将 token 作为跨产品的一致性来源，组件依赖语义而非原始色值。
- [Atlassian Elevation](https://atlassian.design/foundations/elevation/)：用 sunken、default、raised、overlay 表达空间层级，并让提升层与阴影配对。
- [IBM Carbon Color](https://v10.carbondesignsystem.com/guidelines/color/overview/)：以中性灰组织页面区域，主要操作色保持单一，其他颜色只承担明确语义。
- [Material Color System](https://m2.material.io/guidelines/style/color.html)：通过 primary、surface、background、error 及对应的 on-colors 表达层级和可读性。
