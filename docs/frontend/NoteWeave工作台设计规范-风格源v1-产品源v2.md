# NoteWeave v2 工作台设计规范

本文是前端长期视觉与交互约束，产品能力以 `frontend/src` 和后端 API 为准。它借鉴来源驱动研究工作台的空间语法，不复制任何外部产品的品牌、图标或文案。

## 1. 信息架构

- Workspace 是当前工作上下文，左侧负责导航、来源和会话范围。
- Chat 是 QA、Note、Wiki 的同一会话入口，模式切换不创建三套产品。
- Research 是报告、过程、checkpoint、证据和审计的独立工作台。
- Artifact 是 Skill、Job、Version、状态和写回的工具侧栏。
- Wiki 是知识版本、链接、图谱、问题和治理的工作台。
- Memory 是审核队列和 revision 决策页，不放进 composer，也不等于自动保存所有聊天。

## 2. 默认布局

```text
┌────────────┬──────────────────────────────┬──────────────────┐
│ 导航/来源   │ Chat / Research / Wiki       │ Artifact/Inspector│
│ Workspace   │ 当前主要工作区                │ 证据与工具上下文   │
└────────────┴──────────────────────────────┴──────────────────┘
```

桌面端保留三栏语法；中等宽度将来源和 inspector 变为可切换侧栏；移动端使用抽屉或底部面板。右栏是工具和证据上下文，不是第二套主导航。Composer 贴底但不得遮挡消息和引用。

## 3. 视觉语言

- 中性、略暖的画布与近白面板，使用软边框和留白表达层级。
- NoteWeave 深绿色用于主操作和选中状态，蓝色用于 citation/evidence 链接，成功/等待/警告/错误只表达状态。
- 正文使用可读 Sans，标题、状态和正文形成明确层级；避免装饰性口号。
- 控件约 8px 圆角，内容卡片约 12px，工作台面板约 16px。胶囊仅用于状态、筛选和 segmented control。
- 动效只表达加载、反馈和层级，必须尊重 `prefers-reduced-motion`，不使用霓虹渐变、滚动劫持或全局鼠标跟随。

## 4. 交互状态

每个异步区域都要有 loading、empty、error、success/terminal 状态；全局 shell 不因单个 chat、upload、research、artifact 或 wiki 操作而锁死。SSE 断线应显示可恢复状态并使用最后事件序号重连。引用、来源范围、任务状态和版本号优先于装饰。

## 5. 维护边界

视觉文档不能修改 URL、API、表单字段、埋点语义和业务状态 owner。新增页面复用 tokens、WorkbenchShell 和既有 feature 组件；如果设计依赖尚未实现的 provider 或路由，必须显式标为未实现，而不是放置假入口。

## 6. 来源驱动隐喻

视觉上可以用“来源驱动研究工作台”统一理解产品：Workspace 对应研究上下文，Sources pane 对应来源集合，Chat 对应围绕来源提问，Artifact inspector 对应可继续处理的产物，Note/Wiki 对应可复用知识，Citation/Evidence 对应可回溯证据。Research 仍要展示报告、过程和审计，Wiki 仍要展示版本、链接和治理，Memory 仍是审核队列，不能被压缩成普通笔记列表。

这只是信息层级参考，不复制外部产品的品牌、图标、文案或视觉资产。当前前端使用 `WorkbenchShell`、视图级 lazy loading、Sources pane、Chat 三模式、Research、Wiki、Memory review 和 Artifact rail，视觉改动不得改变 `/api/v2`、表单字段或状态 owner。
