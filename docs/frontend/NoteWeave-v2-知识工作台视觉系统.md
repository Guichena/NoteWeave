# NoteWeave v2 知识工作台视觉系统

## 1. Visual Theme and Atmosphere

这是一个面向研究者的桌面知识工作台，采用冷白编辑画布、深海军蓝结构和一条珊瑚色“编织线索”作为品牌记忆点。认证页作为产品入口，允许使用 v1 提取出的暖纸张底色与墨蓝封面建立情绪；进入工作台后恢复冷白或深色的高密度操作画布。页面以来源、对话、研究过程和知识网络为真实内容组织，不再在业务工作台用大面积暖米色和重复阴影卡片制造层次。

参考拆解：

- NotebookLM：来源、对话、产物三栏，以及面板可展开的任务结构。
- WeKnora：冷白背景、细分隔、黑色高对比文本、紧凑的左侧导航和明显的实时过程反馈。
- NoteWeave v1：纸张感底色、橙色操作和蓝色状态的品牌层次，保留其“资料进入结论”的编辑气质。

Design read：面向桌面研究者的证据优先知识应用，使用冷静的编辑工作台语言，靠信息结构、细线和单一动态线索表达深度，而不是营销式装饰。

Design dials：`DESIGN_VARIANCE 5`，`MOTION_INTENSITY 4`，`VISUAL_DENSITY 7`。

## 2. Color Palette and Roles

| Token | Value | Role |
| --- | --- | --- |
| `--nw-color-canvas` | `oklch(0.965 0.012 250)` | 页面画布 |
| `--nw-color-surface-raised` | `oklch(1 0 0)` | 对话与阅读主面 |
| `--nw-color-surface-sunken` | `oklch(0.935 0.016 250)` | 来源区、输入区、空状态 |
| `--nw-color-primary-deep` | `oklch(0.27 0.085 255)` | 导航、品牌深色面 |
| `--nw-color-primary` | `oklch(0.36 0.105 255)` | 主导航与选中态 |
| `--nw-color-primary-soft` | `oklch(0.93 0.035 255)` | 选中背景、上下文提示 |
| `--nw-color-accent` | `oklch(0.63 0.16 30)` | 主要动作、编织线索 |
| `--nw-color-signal` | `oklch(0.54 0.15 258)` | 链接、焦点、证据定位 |
| `--nw-color-success` | `oklch(0.53 0.13 155)` | 完成与健康状态 |

暖色不再作为全局底色。海军蓝负责结构，珊瑚色只负责行动和品牌节奏，绿色只表示真正的成功状态。

认证页局部使用 `--nw-auth-canvas`、`--nw-auth-canvas-strong`、`--nw-auth-canvas-deep` 和 `--nw-auth-paper`。它们只用于登录/注册背景、纸页卡片和暖色边框，不得扩散到 Chat、Research、Wiki、Memory 工作台。

## 3. Typography Rules

- UI 与正文使用系统无衬线栈，优先 `Segoe UI Variable`，中文回落到 `PingFang SC` 或 `Microsoft YaHei UI`。
- 标题使用同一无衬线家族的较大字号和紧字距，不混入装饰性衬线。
- 页面标题 24-32px，工作台标题 18-20px，正文 14px，辅助文字 12px。
- 数字和运行状态使用等宽数字特性，长文阅读区域控制在 68-76ch。

## 4. Component Stylings

- 导航：深色研究索引 rail，选中项使用当前工作台领域色，不使用字母方块；Chat / Research / Wiki / Memory 分别以珊瑚、蓝、绿、琥珀建立快速识别。
- 主按钮：珊瑚实底，8px 圆角，按下缩放到 0.98，hover 只做颜色和 1px 上移。
- 次按钮：无填充或浅灰底，8px 圆角，保持安静。
- 面板：14px 外圆角，顶部使用一条 2px 领域色线，边框和阴影共同表达结构；空态使用结构化网格和短色标，不依赖插画占位。
- 输入框：46px 高度，8px 圆角，焦点使用蓝色外环。
- 认证页：暖纸张画布、墨蓝灰封面、暖白横纹表单纸页；只借用 v1 的色彩与材质，不借用旧版文案和业务结构。

## 5. Layout Principles

- 桌面工作台固定左侧导航，主区使用来源、工作区、上下文的三段式组织。
- 1280px 下优先保证主任务和来源区可见，研究详情退为第二层或纵向区域。
- 1440px 以上允许 Research、Wiki、Memory 展示三栏。
- 移动端使用抽屉导航和纵向工作区，任何操作目标不小于 40px。

## 6. Depth and Elevation

采用背景色阶 `canvas -> surface-sunken -> surface -> surface-raised`，再以深色 rail 固定导航结构；画布和空态可使用极低对比度的方格线组织空间。只在真正浮起的面板使用轻阴影，禁止全局渐变背景、模糊光斑、连续玻璃卡片和卡片套卡片。

## 7. Do's and Don'ts

- 做：让来源、证据和运行状态有明确的视觉位置。
- 做：用细分隔和背景色阶替代重复大阴影。
- 做：用一次性的页面进入动效说明路由切换。
- 不做：AI 紫蓝渐变、装饰性发光球和无语义的彩点。
- 不做：每个内容块都包成同样的圆角卡片。
- 不做：用 emoji 或字母代替产品级图标。

## 8. Responsive Behavior

- `>= 961px` 使用桌面左 rail。
- `768px-960px` rail 转为顶部导航，三栏内容收为单列。
- `< 768px` rail 变为左侧抽屉，主内容留出 12px 边距，输入和按钮保持可点击尺寸。
- `prefers-reduced-motion: reduce` 下取消页面进入、编织线索和脉冲动画。

## 9. Agent Prompt Guide

后续前端改动应沿用以下约束：

1. 在 `--nw-color-canvas` 上构建冷白或深色研究工作台，主面板用 `--nw-color-surface-raised`，分区用 `--nw-color-surface-sunken`。
2. 主操作使用 `--nw-color-accent`；Research / Wiki / Memory 仅在对应工作台使用 `--nw-color-signal` / `--nw-color-success` / `--nw-color-warning`，不要把领域色扩散成页面背景。
3. 面板使用 14px、顶部领域色线和轻阴影，卡片使用 10px，输入和按钮使用 8px 圆角。
4. 状态切换使用 `opacity + transform` 的 180-520ms ease-out 动效，并实现 reduced-motion 降级。
5. 所有图标从 `lucide-react` 取用，图标按钮必须有 `aria-label`。
