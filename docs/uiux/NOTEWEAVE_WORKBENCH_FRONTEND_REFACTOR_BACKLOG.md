# NoteWeave 前端工作台重构施工任务清单

本文档把以下文档进一步拆解为可执行 backlog：

- `docs/uiux/NOTEWEAVE_WORKBENCH_FRONTEND_REFACTOR_PLAN.md`
- `docs/uiux/NOTEWEAVE_WORKBENCH_FRONTEND_REFACTOR_PRD.md`

目标不是重复方案，而是把方案转换为：

- 可排期的阶段
- 可分配的任务
- 可验收的交付项
- 可直接转成 issue / 子任务的实施清单

---

## 1. 使用方式

建议按以下顺序使用本清单：

1. 先按 Phase 建立大任务
2. 每个 Phase 下按 Epic 建子任务
3. 每个 Epic 下按“页面 / 模块 / 文件”继续拆分
4. 编码时以“验收条件”作为完成判定

推荐任务状态：

- TODO
- IN_PROGRESS
- BLOCKED
- REVIEW
- DONE

---

## 2. 总体施工策略

本次前端重构不采用“大爆炸替换”，而采用“壳层先行、主路径优先、逐页迁移”的方式。

原则：

1. 先搭新工作台外壳，再迁移页面。
2. 先迁移 Chat，再迁移 Wiki，再接 Graph。
3. 右侧 Inspector 能力统一后，再逐步把旧页面里的零散信息搬进去。
4. 每个阶段都保持前端可运行，不允许出现长时间半瘫痪状态。

---

## 3. Phase 0：文档冻结与设计基线

## 3.1 目标

把本轮前端重构的文档口径固定下来，形成唯一施工基线。

## 3.2 任务清单

### Task 0.1

标题：

`冻结前端重构方案文档`

内容：

- 审阅并确认 `PLAN` 文档结构
- 审阅并确认 `PRD` 范围和非目标
- 确认 Global Rail / Context Rail / Main Canvas / Inspector 的术语统一

验收条件：

- 团队后续讨论统一使用这四个术语
- 不再在执行中反复回到“到底几栏”这种基础问题

### Task 0.2

标题：

`补充 backlog 与 issue 模板`

内容：

- 基于本文档建立 implementation issue 列表
- 确认每个阶段的 owner 和依赖顺序

验收条件：

- 所有核心任务已能映射到 issue

---

## 4. Phase 1：App Shell 重构

## 4.1 目标

建立新的工作台骨架，让所有主页面共享统一壳层。

## 4.2 Epic 1A：Shell 结构重构

### Task 1A.1

标题：

`抽离 App Shell 主结构`

内容：

- 从现有 `app.js` 中抽离全局壳层渲染逻辑
- 明确以下区域：
  - Global Rail
  - Context Rail
  - Main Canvas
  - Inspector

涉及文件：

- `src/main/resources/static/js/app.js`
- 新增 `src/main/resources/static/js/shell/app-shell.js`

补充约束：

- 保持浏览器原生 ESM 结构
- 不引入 bundler 作为前置依赖

验收条件：

- 所有主页面通过同一壳层挂载
- 不再出现“某些页面有右栏，某些页面无壳层”的混搭状态

### Task 1A.2

标题：

`重构顶部上下文条`

内容：

- 缩减 topbar 职责
- 仅保留当前对象标题、关键状态、主动作、空间切换
- 去除未来不应放在顶部的复杂切换内容

涉及文件：

- `src/main/resources/static/js/app.js`
- 新增 `src/main/resources/static/js/shell/topbar.js`
- `src/main/resources/static/app.css`

验收条件：

- 顶部条高度和结构稳定
- 不承担多会话主导航职责

### Task 1A.3

标题：

`统一右侧 Drawer 为 Inspector`

内容：

- 重命名概念和 CSS class
- 统一空态、标题栏、关闭行为
- 为后续 Citation / Wiki 关系 / Graph 节点详情预留结构

涉及文件：

- `src/main/resources/static/js/app.js`
- 新增 `src/main/resources/static/js/shell/inspector.js`
- `src/main/resources/static/app.css`

验收条件：

- Inspector 在所有主模式下行为一致
- 可收起、展开、替换内容

## 4.3 Epic 1B：布局与尺寸系统

### Task 1B.1

标题：

`建立四区布局尺寸变量`

内容：

- 在 CSS 中加入布局变量：
  - rail width
  - context width
  - inspector width
  - canvas min width

涉及文件：

- `src/main/resources/static/app.css`

验收条件：

- 宽度通过 token 可控
- Chat / Wiki / Graph 可共享同一尺寸基础

### Task 1B.2

标题：

`实现响应式退化策略`

内容：

- 窄屏时先收起 Inspector
- 再压缩 Context Rail
- 再把 Context Rail 切为抽屉模式

涉及文件：

- `src/main/resources/static/app.css`
- `src/main/resources/static/js/shell/app-shell.js`

验收条件：

- 主要断点下布局不破

---

## 5. Phase 2：导航与上下文栏

## 5.1 目标

把“多会话 / 页面树 / 最近打开 / 收藏对象”真正放进左侧结构。

## 5.2 Epic 2A：Global Rail

### Task 2A.1

标题：

`实现 Global Rail 导航`

内容：

- 新增图标式一级导航
- 对应入口：
  - Team Knowledge
  - Personal Research
  - Chat
  - Wiki
  - Graph
  - Artifacts
  - Memory
  - Admin

涉及文件：

- 新增 `src/main/resources/static/js/shell/global-rail.js`
- `src/main/resources/static/app.css`

验收条件：

- 当前主模式可高亮
- 图标有 tooltip

## 5.3 Epic 2B：Context Rail

### Task 2B.1

标题：

`实现 Context Rail 基础框架`

内容：

- 支持标题区
- 支持分组
- 支持折叠
- 支持当前项高亮

涉及文件：

- 新增 `src/main/resources/static/js/shell/context-rail.js`
- `src/main/resources/static/app.css`

验收条件：

- 不同模式可以复用同一上下文栏壳子

### Task 2B.2

标题：

`实现 Recent / Pinned 通用分组`

内容：

- 提供最近打开对象与收藏对象的通用渲染结构

涉及文件：

- `src/main/resources/static/js/shell/context-rail.js`
- 可能新增 `src/main/resources/static/js/components/list.js`

验收条件：

- Chat / Wiki / Artifact 均可复用

---

## 6. Phase 3：Chat Workbench 优先重构

## 6.1 目标

先把最核心的多会话工作流做顺。

## 6.2 Epic 3A：会话上下文栏

### Task 3A.1

标题：

`把会话列表迁入 Context Rail`

内容：

- 当前会话列表不再作为独立页面内栏存在
- 改为 Context Rail 的“会话分组”

涉及文件：

- `src/main/resources/static/js/app.js`
- 新增 `src/main/resources/static/js/pages/chat/session-list.js`

验收条件：

- 会话切换后中栏无明显跳动
- 当前会话状态清晰

### Task 3A.2

标题：

`补充会话元信息展示`

内容：

- 正式 / 草稿
- 最近活跃时间
- 运行中状态
- 是否有相关 Artifact

验收条件：

- 列表更像工作树，而不是联系人列表

## 6.3 Epic 3B：消息流与输入区重排

### Task 3B.1

标题：

`重构 Chat 主画布`

内容：

- 消息流进入 Main Canvas
- Composer 固定底部
- 中栏优先占宽

涉及文件：

- `src/main/resources/static/js/pages/chat/index.js`
- `src/main/resources/static/js/pages/chat/message-stream.js`
- `src/main/resources/static/js/pages/chat/composer.js`
- `src/main/resources/static/app.css`

验收条件：

- Chat 模式中栏明显为视觉核心
- 输入区不被 Citation 打开动作遮挡

### Task 3B.2

标题：

`优化 streaming 状态显示`

内容：

- 正在生成时的视觉状态更清晰
- Stop 按钮优先级提升

验收条件：

- 流式生成不影响布局稳定性

## 6.4 Epic 3C：Citation / Artifact Inspector

### Task 3C.1

标题：

`把 Citation 明细统一接入 Inspector`

内容：

- 点击消息引用后右栏展示：
  - 来源
  - 引用片段
  - 位置
  - 相关上下文

涉及文件：

- `src/main/resources/static/js/pages/chat/index.js`
- `src/main/resources/static/js/shell/inspector.js`

验收条件：

- Citation 不再是临时块状内容
- 右栏结构统一

### Task 3C.2

标题：

`把会话相关 Artifact 预览接入 Inspector`

内容：

- 在会话中点击 Artifact 后优先右栏预览或打开详情入口

验收条件：

- 会话与成果之间跳转成本降低

---

## 7. Phase 4：Wiki Workbench 重构

## 7.1 目标

让 Wiki 从“列表 + 编辑表单页”升级成真正的知识沉淀工作区。

## 7.2 Epic 4A：Wiki Context Rail

### Task 4A.1

标题：

`实现 Wiki 页面树`

内容：

- 页面列表进入 Context Rail
- 支持当前页高亮
- 支持最近打开页

涉及文件：

- `src/main/resources/static/js/pages/wiki/wiki-tree.js`
- `src/main/resources/static/app.css`

验收条件：

- Wiki 不再依赖中栏左半部分做列表

### Task 4A.2

标题：

`实现 Wiki 搜索入口迁移`

内容：

- 把 Wiki 搜索入口从旧页面内部移到 Context Rail 顶部

验收条件：

- 搜索结果与页面树关系清晰

## 7.3 Epic 4B：Wiki 主画布

### Task 4B.1

标题：

`重构 Wiki 阅读态`

内容：

- 中栏优先服务页面阅读
- 标题、状态、摘要、正文层次明确

涉及文件：

- `src/main/resources/static/js/pages/wiki/wiki-reader.js`
- `src/main/resources/static/app.css`

验收条件：

- 页面可作为真正阅读面，而不是后台表单

### Task 4B.2

标题：

`重构 Wiki 编辑态`

内容：

- 维持轻编辑器，不大换富文本
- 明确阅读态 / 编辑态切换

涉及文件：

- `src/main/resources/static/js/pages/wiki/wiki-editor.js`

验收条件：

- 编辑不破坏整体工作台结构

## 7.4 Epic 4C：Wiki Inspector

### Task 4C.1

标题：

`接入 Wiki 页面关系侧栏`

内容：

- 对接 `GET /api/v1/team/wiki-pages/{pageId}/relations`
- 展示：
  - 关系摘要
  - 出链
  - 入链
  - 未解析链接
  - 邻居页

涉及文件：

- `src/main/resources/static/js/api.js`
- `src/main/resources/static/js/pages/wiki/wiki-relations-panel.js`

验收条件：

- 页面关系可在不离开当前页面的情况下查看

### Task 4C.2

标题：

`把版本历史迁入 Inspector`

内容：

- 当前版本历史从整块页面区域迁入右栏

验收条件：

- 版本查看不再打断主阅读流

### Task 4C.3

标题：

`把发布动作改为确认弹窗`

内容：

- 发布不是常驻长表单，而是一个明确动作

涉及文件：

- 新增 `src/main/resources/static/js/components/modal.js`

验收条件：

- 发布过程清晰且不破坏主页面布局

---

## 8. Phase 5：Graph 接入

## 8.1 目标

把已完成的后端 Graph 能力接入前端，并纳入工作台流转。

## 8.2 Epic 5A：Graph API 接入

### Task 5A.1

标题：

`补充 graph API client`

内容：

- 在 `api.js` 新增 graph 相关接口封装

涉及文件：

- `src/main/resources/static/js/api.js`

验收条件：

- 支持空间图谱、页面图谱、节点详情、neighborhood、path
- 明确统一知识图谱接口为主，旧 `wiki-graph` 接口仅作兼容保留，不作为新工作台主实现依赖

## 8.3 Epic 5B：嵌入式 Graph Card

### Task 5B.1

标题：

`在 Wiki Inspector 中加入图谱概览卡`

内容：

- 当前页面相关节点数
- 关系统计
- 进入 Graph 视图按钮

验收条件：

- Wiki 到 Graph 的过渡自然

### Task 5B.2

标题：

`在 Chat Inspector 中加入图谱概览卡`

内容：

- 允许从会话上下文进入图谱

验收条件：

- Chat 不再是孤立对话面

## 8.4 Epic 5C：独立 Graph 视图

### Task 5C.1

标题：

`新增 Graph 主视图路由与页面`

内容：

- 新增 `/spaces/:spaceId/graph`
- 提供空间级图谱入口

涉及文件：

- `src/main/resources/static/js/app.js`
- 新增 `src/main/resources/static/js/pages/graph/index.js`

验收条件：

- 可独立进入 Graph 模式
- `route parser`、`routeLink()`、导航高亮与页面描述配置同步更新

### Task 5C.2

标题：

`实现 Graph Canvas 基础视图`

内容：

- 渲染节点与边
- 支持点击节点
- 支持聚焦当前节点

涉及文件：

- `src/main/resources/static/js/pages/graph/graph-canvas.js`
- `src/main/resources/static/app.css`

验收条件：

- 图谱可浏览，不要求第一版就很花哨

### Task 5C.3

标题：

`实现 Graph Node Inspector`

内容：

- 展示节点标题、类型、状态、邻接边
- 支持跳转原对象

涉及文件：

- `src/main/resources/static/js/pages/graph/graph-node-detail.js`

验收条件：

- 节点详情结构化，不是 JSON dump

### Task 5C.4

标题：

`实现 Graph filters 与 path 查询入口`

内容：

- 节点类型筛选
- 边类型筛选
- neighborhood depth
- path 查询入口

涉及文件：

- `src/main/resources/static/js/pages/graph/graph-filters.js`

验收条件：

- 图谱具备基础探索能力

---

## 9. Phase 6：Artifact / Knowledge / Memory 收口

## 9.1 目标

把其他主页面逐步迁入统一工作台体验。

## 9.2 Epic 6A：Artifact 阅读面

### Task 6A.1

标题：

`重构 Artifact 主画布`

内容：

- 强化阅读态
- 右栏承载关系和引用

验收条件：

- Artifact 更像可编辑成果，而不是后台详情页

## 9.3 Epic 6B：Knowledge 与 Memory 壳层统一

### Task 6B.1

标题：

`统一 Knowledge 页的工具栏和表格结构`

验收条件：

- 和新壳层风格一致

### Task 6B.2

标题：

`统一 Memory 页的表单与信息分层`

验收条件：

- 避免旧式双栏卡片堆叠感

---

## 10. Phase 7：模块化与技术债收口

## 10.1 目标

避免新 UI 外壳搭起来后，逻辑仍全部堵在 `app.js`。

## 10.2 Epic 7A：代码拆分

### Task 7A.1

标题：

`拆分 shell 模块`

内容：

- `app-shell.js`
- `topbar.js`
- `global-rail.js`
- `context-rail.js`
- `inspector.js`

验收条件：

- 全局壳层不再写死在 `app.js`

### Task 7A.2

标题：

`拆分 chat / wiki / graph 页面模块`

验收条件：

- 页面逻辑能局部维护

### Task 7A.3

标题：

`抽离通用组件`

内容：

- modal
- tabs
- empty state
- badges
- list item
- search box

验收条件：

- 不同页面避免复制拼接 HTML 模板

## 10.3 Epic 7B：状态整理

### Task 7B.1

标题：

`按域拆分 state`

内容：

- `shellState`
- `chatState`
- `wikiState`
- `graphState`
- `artifactState`

验收条件：

- 新能力接入时不必持续扩张单个大对象

---

## 11. 推荐 issue 拆分模板

每个 implementation issue 推荐使用如下模板：

### 标题模板

`[Workbench][Phase X][Area] 任务标题`

示例：

- `[Workbench][Phase 3][Chat] 把会话列表迁入 Context Rail`
- `[Workbench][Phase 4][Wiki] 接入页面关系侧栏`
- `[Workbench][Phase 5][Graph] 实现节点详情 Inspector`

### 描述模板

包含：

- 背景
- 目标
- 涉及文件
- 依赖接口
- 验收条件
- 非目标

---

## 12. 推荐实施顺序

如果只考虑最小有效路径，推荐按以下顺序推进：

1. Phase 1 App Shell
2. Phase 2 导航与上下文栏
3. Phase 3 Chat 重构
4. Phase 4 Wiki 重构
5. Phase 5 Graph 接入
6. Phase 6 其他页面收口
7. Phase 7 技术债清理

---

## 13. 最小可上线切片

如果需要先做一个可演示版本，最小切片建议是：

### Slice A

- 新 App Shell
- Global Rail
- Chat Context Rail
- Chat Main Canvas
- Citation Inspector

### Slice B

- Wiki Tree
- Wiki Reader
- Wiki Relations Inspector

### Slice C

- Graph 概览卡
- 独立 Graph 页面
- 节点详情 Inspector

这三个切片做完，NoteWeave 的新工作台形态就已经成立。

---

## 14. 交付后建议

完成 backlog 文档后，建议继续补两类材料：

1. 页面级线框文档
2. 组件级接口清单

如果你要继续推进实现，下一步最有价值的动作是：

- 先做 `App Shell + Chat Workbench` 的第一版编码任务拆解
- 直接把本 backlog 转成实际 issue 或迭代任务单
