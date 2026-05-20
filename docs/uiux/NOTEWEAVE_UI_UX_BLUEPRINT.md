# NoteWeave UI/UX Blueprint

本文档是在 `ui-ux-pro-max` 检索结果之上，结合当前仓库的 `Phase 16` 前端工作台文档与现有静态壳子整理出的定制方案。

可配合以下文件使用：

- `docs/uiux/design-system/noteweave/MASTER.md`
- `docs/uiux/design-system/noteweave/pages/workbench-chat.md`
- `docs/uiux/design-system/noteweave/pages/team-knowledge.md`
- `docs/uiux/design-system/noteweave/pages/personal-research.md`
- `docs/uiux/design-system/noteweave/pages/artifact-viewer.md`
- `docs/uiux/design-system/noteweave/pages/admin-console.md`

## 1. Final Design Direction

NoteWeave 的最优解不是“通用 AI SaaS 模板”，而是：

**Warm Editorial Workbench**

它由两层气质组成：

- 外层是稳定、高效率的 workspace
- 内层是可信、适合长时间阅读的 research surface

一句话描述：

> 像一张带温度的研究桌面，叠加一套清楚的团队操作面板。

## 2. Information Architecture

推荐导航结构保持与 `Phase 16` 一致，但在视觉上分成三层：

### 2.1 Primary Navigation

- Workspace
- Team Knowledge
- Personal Research
- Chat
- Studio
- Artifacts
- Wiki
- Memory
- Admin

### 2.2 Secondary Context

- Current Space
- Current Project / Knowledge Base / Session
- Connection status
- Running task status

### 2.3 Local Actions

- Create
- Upload
- Generate
- Publish
- Distill
- Retry / Cancel

## 3. Core User Flows

### 3.1 团队知识库流

```text
选择 Space
-> 进入 Knowledge Base
-> 上传文档
-> 查看处理状态
-> 测试检索
-> 进入 Team Chat
-> 打开 Citation
-> 沉淀 Wiki
```

### 3.2 个人研究流

```text
创建 Project
-> 导入 Source
-> 查看 Article / Concept / Synthesis Cards
-> 选择 Methodology
-> 生成 Artifact
-> 预览 / 编辑
-> 沉淀为 Synthesis
```

### 3.3 工作台会话流

```text
选择 Session
-> 输入问题
-> 观看流式输出
-> 检查 Citation
-> 停止 / 追问
-> 打开相关 Artifact
```

## 4. Screen Blueprints

### 4.1 App Shell

```text
+---------------------------------------------------------------+
| Left Rail | Top Context Bar                                   |
|           +---------------------------------------------------+
|           | Main Workspace                    | Right Drawer   |
|           |                                   |                |
+---------------------------------------------------------------+
```

### 4.2 Workbench Chat

```text
+-----------+------------------------------------+--------------+
| Sessions  | Stream                             | Evidence     |
|           |                                    | Context      |
|           |                                    | Artifact     |
|           +------------------------------------+--------------+
|           | Composer with Send / Stop                         |
+-----------+---------------------------------------------------+
```

### 4.3 Team Knowledge

```text
Header
Status Metrics
Toolbar
Document Table
Upload / Task Panel
Search Debug
```

### 4.4 Personal Research

```text
Project Header
Project Health Strip
Tabs
Tab Content
```

### 4.5 Artifact

```text
Artifact Header
Reading Surface
Right Actions / Citations / Relations
```

### 4.6 Admin

```text
Header
Metrics
Filter Bar
Dense Table
Right Detail Drawer
```

## 5. Component Priorities

如果我们后面继续做实现，建议优先保证这些组件的统一性：

1. `AppShell`
2. `SpaceSwitcher`
3. `TaskStatusBadge`
4. `SessionList`
5. `ChatMessageList`
6. `ChatInput`
7. `CitationDrawer`
8. `UploadProgressPanel`
9. `ArtifactViewer`
10. `AdminDataTable`

## 6. Visual Translation Of Current Frontend

你仓库现有静态前端已经有一个不错的起点：

- 暖纸感背景是对的
- 三栏工作台结构是对的
- Artifact/Chat 的主次关系已经初步成立

但还需要三类升级：

### 6.1 品牌语言统一

- 从“暖色 UI”升级成“暖中性 + 冷信号 + 陶土 CTA”
- 让状态语义更清晰

### 6.2 内容阅读升级

- 引入 `Newsreader + Public Sans`
- 让 Artifact/Wiki 看起来更像可读内容，而不是后台表单

### 6.3 页面范式统一

- Chat、Knowledge、Research、Admin 各自使用固定布局范式
- 避免每个页面都像临时拼装

## 7. Implementation Order

建议实现顺序：

1. 先把全局 token、字体、状态色、按钮/表单/表格统一
2. 再重做 `AppShell` 与 `Top Context Bar`
3. 然后优先精修 `Workbench Chat`
4. 再补 `Team Knowledge` 与 `Artifact Viewer`
5. 最后统一 `Personal Research` 与 `Admin`

## 8. Non-Goals

这套设计刻意不做：

- 营销首页
- 炫技 3D
- 复杂富文本编辑器
- 多人实时协同光标
- 过度深色赛博风

## 9. Definition Of Good

如果这套 UI/UX 设计落地得好，用户会直观感受到：

- 一进来就知道自己该做什么
- AI 回答不是黑箱，而是可追溯
- 团队资料与个人研究都能自然切换
- 内容看得下去，状态看得明白，操作不慌
