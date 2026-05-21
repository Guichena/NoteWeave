# NoteWeave 前端工作台重构方案

本文档用于定义 NoteWeave 下一轮前端重构的目标形态、信息架构、交互边界与实施原则。

适用范围：

- 当前静态前端壳：`src/main/resources/static/index.html`
- 当前前端逻辑入口：`src/main/resources/static/js/app.js`
- 当前前端样式入口：`src/main/resources/static/app.css`
- 已完成后端能力：Chat / Wiki / Artifact / Memory / Studio / Knowledge Graph

关联文档：

- `docs/features/phase_16_frontend_workspace.md`
- `docs/uiux/NOTEWEAVE_UI_UX_BLUEPRINT.md`
- `docs/uiux/design-system/noteweave/MASTER.md`
- `docs/uiux/design-system/noteweave/pages/workbench-chat.md`

术语约定：

- `Global Rail`：一级全局导航窄栏
- `Context Rail`：二级上下文栏
- `Main Canvas`：中央主工作区
- `Inspector`：右侧解释与辅助动作面板
- `Graph`：默认指统一知识图谱能力，即 `/knowledge-graph` 相关接口；历史 `wiki-graph` 仅视为兼容保留能力

---

## 1. 背景与问题

当前前端已经具备一个可工作的单页壳，但仍存在几个结构性问题：

1. 页面组织仍偏“功能页拼接”，还不是一个连续的知识工作台。
2. Chat、Wiki、Artifact、Studio 之间切换成本偏高，用户需要频繁跳页。
3. 多会话是核心场景，但当前页面结构没有把“会话树”提升为一级工作对象。
4. 右侧抽屉已经存在，但尚未形成统一的“上下文 / 证据 / 关系 / 版本”承载面。
5. Graph 后端已经具备空间图谱、页面邻域、节点详情和路径能力，但前端尚未为它准备合适的承载位。
6. `app.js` 单文件持续膨胀，后续如果继续直接往里堆功能，维护成本会快速上升。

本次重构的目标不是换框架，不是做漂亮官网，也不是立刻推翻现有前端，而是在现有原生 JS 单页前端的基础上，把它升级成一个真正面向知识工作的 Workbench。

---

## 2. 总体设计结论

### 2.1 不是固定四等栏

不采用“四栏等宽”的工作台。那会直接压缩中央主画布，尤其不利于会话与内容阅读。

### 2.2 采用“概念上四区，视觉上三区”的结构

推荐布局：

```text
[Global Rail][Context Rail][Main Canvas][Inspector]
```

但视觉上按下面方式实现：

- `Global Rail`：超窄常驻导航栏，56px 到 64px
- `Context Rail`：二级上下文栏，240px 到 300px
- `Main Canvas`：主工作区，始终优先保证宽度
- `Inspector`：右侧按需展开，不作为永远占宽的大栏

因此真正的桌面体验是：

```text
窄导航 + 上下文栏 + 超大中栏 + 可折叠右侧栏
```

### 2.3 中栏优先

核心原则：

- 聊天、Wiki 阅读/编辑、Artifact 预览、Graph 探索都必须以中栏为第一阅读面。
- 多会话不放顶部 tab，不放右侧，不塞进弹窗，而是进入左侧上下文栏。
- 右侧面板只承担“解释当前对象”和“执行次级动作”的职责。

---

## 3. 目标体验

用户进入一个空间后，应当形成如下直觉：

1. 左边先看到自己当前在哪个空间、哪个项目、有哪些会话和页面。
2. 中间始终是当前最重要的对象：会话流、Wiki 页面、Graph 画布或 Artifact。
3. 右侧只在需要时出现，帮助理解证据、引用、关系、版本和发布动作。
4. 整个产品像一个连续工作台，而不是多个后台页面拼在一起。

一句话定义：

> NoteWeave 应该像一个“面向多会话与知识沉淀的研究工作台”，而不是一个“功能列表式后台”。

---

## 4. 信息架构

## 4.1 一级导航：Global Rail

Global Rail 保持超窄，只放一级入口与少量全局动作。

推荐项：

- Space Home
- Team Knowledge
- Personal Research
- Chat Workbench
- Wiki
- Graph
- Artifacts
- Memory
- Admin

设计原则：

- 只用图标，hover 或聚焦时显示 tooltip。
- 不显示复杂文案，不在这一层放业务列表。
- 仅用于“切换工作域”，不用于“浏览具体对象”。

## 4.2 二级导航：Context Rail

Context Rail 是本次重构的关键。它承载“当前工作域下的树状上下文”。

推荐结构：

```text
当前空间
当前项目或工作域

分组一：会话
- 最近会话
- 收藏会话
- 草稿会话

分组二：Wiki
- 页面树
- 最近打开页面

分组三：Artifacts
- 最近成果
- 当前会话相关成果

分组四：Pinned / Recent
- 收藏节点
- 最近打开对象
```

设计原则：

- Chat 模式时默认聚焦“会话”分组。
- Wiki 模式时默认聚焦“页面树”分组。
- Graph 模式时默认聚焦“筛选器 + 节点集合”分组。
- 同一栏内允许折叠分组，但不允许同时承载大量编辑表单。

## 4.3 主画布：Main Canvas

Main Canvas 承载真正的主任务。

它有四类主视图：

1. Chat Canvas
2. Wiki Canvas
3. Graph Canvas
4. Artifact Canvas

主视图切换后，外壳不换，只有中间内容与右侧 Inspector 变化。

## 4.4 右侧：Inspector

Inspector 是“当前对象的解释面板”，不是另一个完整页面。

允许承载：

- Citation 明细
- 来源文档定位
- Wiki 页面关系
- Wiki 版本历史
- Artifact 关联关系
- Graph 节点详情
- Graph 邻域卡片
- 发布动作
- 生成动作

不建议承载：

- 长表单主编辑
- 完整对象列表
- 会话树
- 大面积搜索主结果

---

## 5. 三种主模式

## 5.1 Chat 模式

这是最高优先级模式。

布局建议：

```text
[Global Rail][Sessions / Wiki context][Message Stream][Inspector?]
```

其中：

- `Global Rail` 常驻
- `Context Rail` 以会话树为主
- `Main Canvas` 为消息流与输入框
- `Inspector` 默认收起，点开 Citation / 来源 / 关系后再滑出

Chat 模式下的重点：

- 中栏最大化
- 输入框固定底部
- 右栏默认不抢空间
- 当前会话相关 Artifact 可在左栏次级显示

## 5.2 Wiki 模式

Wiki 模式强调阅读、编辑、版本与关系。

布局建议：

```text
[Global Rail][Wiki tree][Page reader/editor][Relations / Versions / Publish]
```

其中：

- 左侧上下文栏显示页面树、搜索、最近打开
- 中栏显示阅读或编辑界面
- 右侧 Inspector 可以默认展开

Wiki 模式下的重点：

- 页面标题、摘要、状态、版本放在中栏顶部
- 版本历史不再单独占整页
- 页面关系和图谱邻域进入右侧
- 发布动作使用确认弹窗，不打断主阅读流

## 5.3 Graph 模式

Graph 模式强调探索与定位。

布局建议：

```text
[Global Rail][Filters / node sets][Graph canvas][Node detail]
```

其中：

- 左侧显示筛选条件、节点类型、已选路径入口
- 中间是主图谱画布
- 右侧显示节点详情、相邻边、跳转动作

Graph 模式下的重点：

- 中间应尽可能宽
- 节点详情不要弹窗化，应进入右侧 Inspector
- 路径查询与 neighborhood 查询可作为右栏操作区或顶部轻工具条

---

## 6. 为什么不把所有次级内容都改成弹窗

弹窗适合“一次性完成动作”，不适合“持续参考上下文”。

### 6.1 适合弹窗的内容

- 新建会话
- 新建 Wiki 页面
- 新建来源
- 分享
- 空间设置
- 发布确认
- Graph 高级筛选器
- 删除确认

### 6.2 适合右侧 Inspector 的内容

- 引用证据
- 页面关系
- 节点详情
- 版本历史
- Artifact 关联
- 邻域图谱
- 运行中任务的上下文

### 6.3 设计原则

- “动作型”能力优先弹窗
- “解释型 / 浏览型 / 对照型”能力优先右侧 Inspector

---

## 7. Wiki 与 Graph 的前端承接方案

## 7.1 Wiki 主界面升级方向

当前 `renderWikiPage()` 更像一个传统表单页，需要升级成工作台式布局。

目标结构：

```text
顶部：标题 / 状态 / 页面元信息 / 快捷动作
左侧：页面树 / 搜索 / 最近打开
中间：阅读或编辑视图
右侧：关系 / 版本 / 发布 / 邻域图谱
```

### Wiki 中栏应包含

- 页面标题
- 页面摘要或引言
- 正文阅读态
- 编辑态切换
- 与引用、图谱相关的轻量标记

### Wiki 右栏应包含

- `页面关系摘要`
- `出链 / 入链`
- `未解析链接`
- `邻居页`
- `版本历史`
- `发布按钮`
- `展开图谱` 入口

## 7.2 Graph 的两级呈现

Graph 不应一上来就是全屏模式。

建议采用两级方案：

### 第一级：嵌入式 Graph Card

出现位置：

- Chat 右栏
- Wiki 右栏
- Artifact 右栏

展示内容：

- 当前对象相关节点数
- 入边 / 出边计数
- 关键邻居节点
- “进入图谱视图”入口

### 第二级：全屏 Graph Canvas

使用场景：

- 需要跨页面探索
- 需要找最短路径
- 需要筛选多种节点类型
- 需要分析空间级关系网络

### 这样做的好处

- 日常不被图谱打断
- 需要深入时又能顺滑进入
- 更符合知识工作流中的“轻查看 -> 深探索”

---

## 8. 组件边界与模块拆分

本次重构不建议先换 React/Vue。优先把当前原生 JS 单页前端拆成更清晰的模块。

这里的“模块拆分”是指继续使用浏览器原生 ESM，在 `static/js` 下拆文件并用相对路径 `import` 组织代码；本轮不引入 bundler、TypeScript、JSX 或新的前端构建链。

## 8.1 当前问题

`app.js` 已经同时承担：

- route 管理
- 页面渲染
- 事件处理
- 状态管理
- 网络联动
- 工具函数

继续膨胀会让 Wiki / Graph / Chat 的改造成本快速上升。

## 8.2 目标目录建议

```text
src/main/resources/static/js/
  api.js
  app.js
  state/
    store.js
    route.js
  shell/
    app-shell.js
    topbar.js
    global-rail.js
    context-rail.js
    inspector.js
  pages/
    chat/
      index.js
      session-list.js
      message-stream.js
      composer.js
    wiki/
      index.js
      wiki-tree.js
      wiki-reader.js
      wiki-editor.js
      wiki-relations-panel.js
    graph/
      index.js
      graph-canvas.js
      graph-filters.js
      graph-node-detail.js
    artifacts/
      index.js
    knowledge/
      index.js
    personal/
      index.js
    memory/
      index.js
    admin/
      index.js
  components/
    modal.js
    drawer.js
    badge.js
    empty-state.js
    tabs.js
    list.js
    search-box.js
```

## 8.3 状态拆分建议

现有大对象状态可以保留，但建议逐步按域收口：

- `shellState`
- `chatState`
- `wikiState`
- `graphState`
- `artifactState`
- `knowledgeState`
- `personalState`
- `adminState`

其中 `graphState` 新增：

- `spaceGraph`
- `activeNodeId`
- `activePath`
- `filters`
- `inspectorMode`

---

## 9. 布局尺寸建议

桌面端建议采用以下基准：

### 9.1 默认桌面宽度

- Global Rail：`60px`
- Context Rail：`280px`
- Inspector：`360px`
- Main Canvas：剩余宽度，最小不低于 `720px`

### 9.2 Chat 模式

- Global Rail：`60px`
- Context Rail：`280px`
- Inspector：默认 `0`，展开时 `360px`
- Main Canvas：优先占剩余

### 9.3 Wiki 模式

- Global Rail：`60px`
- Context Rail：`260px`
- Inspector：默认 `320px`
- Main Canvas：剩余宽度

### 9.4 Graph 模式

- Global Rail：`60px`
- Context Rail：`280px`
- Inspector：`320px`
- Main Canvas：尽量放大

### 9.5 宽度退化策略

当视口变窄时：

1. 先折叠 Inspector
2. 再压缩 Context Rail 到 `220px`
3. 仍不够时，Context Rail 改为可切换抽屉

---

## 10. 视觉与交互原则

## 10.1 视觉方向

保留当前产品已有的“温暖纸面感 + 工具理性”气质，但整体收敛得更冷静。

建议方向：

- 主背景减少大面积装饰感，强化工作台稳定性
- 中栏阅读面更干净
- 右栏卡片更结构化
- 图谱视图采用更深一点的中性底色，提升关系线辨识度

## 10.2 顶栏原则

顶部不再承担复杂导航，仅承担：

- 当前对象标题
- 面包屑
- 关键状态
- 主动作按钮

不再承担：

- 多会话切换
- 大量筛选器
- 长表单

## 10.3 会话列表原则

会话列表必须像“项目内工作树”，不是即时通讯联系人列表。

需要体现：

- 当前会话
- 最近活跃时间
- 草稿 / 正式状态
- 是否有未完成任务
- 是否关联 Artifact / Wiki

## 10.4 阅读面原则

中栏内容必须优先服务“阅读”和“思考”，而不是“卡片堆叠”。

因此：

- 消息区减弱装饰
- Wiki 阅读面强化排版
- Artifact 以文章阅读面为主
- Graph 以探索面为主

---

## 11. 路由与模式建议

路由可以继续沿用当前设计，但前端展示层采用“同壳不同模式”。

推荐映射：

- `/spaces/:spaceId/workbench/chat` -> Chat 模式
- `/spaces/:spaceId/wiki` -> Wiki 模式
- `/spaces/:spaceId/artifacts/:artifactId` -> Artifact 模式
- `/spaces/:spaceId/graph` -> Graph 模式

说明：

- 这里的 `/spaces/:spaceId/graph` 是建议新增的前端客户端路由，用于挂载图谱工作台视图，不对应新的后端页面接口。
- 后端数据仍通过 `/api/v1/...` 图谱接口获取。
- 当前代码里尚未存在 `graph` 路由模式，因此后续实施时需要同步更新前端 route parser、`routeLink()` 与导航描述配置。

如果暂不新增 `/graph` 路由，也可先通过：

- `/spaces/:spaceId/wiki?view=graph`
- `/spaces/:spaceId/workbench/chat?view=graph`

做过渡，但长期建议保留独立 graph 路由。

---

## 12. 与后端能力的对接建议

当前后端已经具备的新增能力，可以直接为前端工作台所用：

### 12.1 Wiki 关系侧栏

优先接入：

- `GET /api/v1/team/wiki-pages/{pageId}/relations`

前端用途：

- 右栏关系摘要
- 邻居页列表
- 未解析链接提示

### 12.2 空间级 Graph

优先接入：

- `GET /api/v1/spaces/{spaceId}/knowledge-graph`
- `GET /api/v1/spaces/{spaceId}/knowledge-graph/nodes/{nodeId}`
- `GET /api/v1/spaces/{spaceId}/knowledge-graph/neighborhood/{nodeId}`
- `GET /api/v1/spaces/{spaceId}/knowledge-graph/path`

前端用途：

- Graph 主画布
- 节点详情右栏
- 路径探索

### 12.3 页面级 Graph

优先接入：

- `GET /api/v1/team/wiki-pages/{pageId}/knowledge-graph`

前端用途：

- Wiki 右栏嵌入式关系图
- “从页面进入图谱”过渡入口

### 12.4 兼容说明：旧 wiki-graph 接口

当前后端仍保留：

- `GET /api/v1/team/spaces/{spaceId}/wiki-graph`
- `GET /api/v1/team/wiki-pages/{pageId}/graph`

它们可以继续服务旧前端或做短期兼容，但本轮新工作台应优先围绕统一知识图谱接口建设，避免前端同时围绕两套图结构做双份适配。

---

## 13. 非目标

本轮前端重构不做：

- 前端技术栈整体切换
- 完整富文本编辑器替换
- 多人实时协作光标
- 移动端优先重做
- 图谱三维化
- 类似 IM 的强社交系统

---

## 14. Definition of Done

如果本次方案落地良好，应达到以下效果：

1. 用户能在一个稳定工作台内完成“会话 -> 引用 -> 沉淀 Wiki -> 查看关系 -> 进入图谱”。
2. 多会话真正成为一级对象，不再依赖顶部或零散入口切换。
3. 中栏始终保持为主任务区域，不被右侧能力面板挤压。
4. Wiki 和 Graph 不再是孤立页面，而是嵌入同一工作流。
5. `app.js` 不再继续单文件膨胀，前端可以持续迭代。

---

## 15. 推荐实施顺序

建议顺序：

1. 先重做 App Shell 与三段式宽度系统
2. 再重做 Chat 模式与会话上下文栏
3. 再重做 Wiki 模式与关系侧栏
4. 然后接入 Graph 右栏卡片与独立 Graph 视图
5. 最后统一 Artifact / Memory / Admin 的壳层风格

本方案本身不包含任务拆分、优先级、验收条款与施工节奏，详见配套 PRD：

- `docs/uiux/NOTEWEAVE_WORKBENCH_FRONTEND_REFACTOR_PRD.md`
