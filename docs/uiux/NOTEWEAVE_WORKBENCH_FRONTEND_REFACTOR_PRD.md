# NoteWeave 前端工作台重构施工 PRD

本文档基于：

- `docs/uiux/NOTEWEAVE_WORKBENCH_FRONTEND_REFACTOR_PLAN.md`

目标是把“前端重构方案”转换成可执行的产品与工程施工文档，供后续编码、联调、验收与迭代排期使用。

---

## 1. 项目名称

NoteWeave Workbench Frontend Refactor

---

## 2. 项目背景

随着 Team Knowledge、Chat Runtime、Artifact、Wiki、Memory、Studio 与统一 Knowledge Graph 后端能力逐步齐备，当前前端工作台已出现明显结构瓶颈：

1. 多业务模块入口分散，工作流割裂。
2. 多会话场景没有被提升为核心视图结构。
3. Wiki 与 Graph 已具备后端能力，但前端仍未建立统一的承载面。
4. 现有 `app.js` 和 `app.css` 仍适合“阶段性交付”，不适合后续持续扩展。

因此需要进行一次以“工作台形态重构”为核心、但不切换前端技术栈的前端升级。

---

## 3. 项目目标

本项目要达成以下结果：

1. 把前端重构为“窄导航 + 上下文栏 + 大中栏 + 按需右栏”的工作台。
2. 让多会话成为一级结构，而不是次级功能。
3. 让 Wiki、Graph、Artifact 进入统一工作流。
4. 保持中央主画布为最高优先级区域。
5. 建立后续前端持续演进的模块边界。

---

## 4. 成功标准

满足以下条件即视为本项目成功：

1. 用户可在一个 Space 内快速切换会话、Wiki、Artifact、Graph，而不产生“跳产品”的感觉。
2. 会话树、页面树、最近打开、收藏对象可在左侧上下文栏中稳定浏览。
3. 中栏在 Chat 模式下始终占据主视觉重心。
4. 右侧 Inspector 可以统一承载 Citation、Wiki 关系、版本历史、Graph 节点详情等上下文内容。
5. Graph 能以“嵌入卡片 + 独立视图”两级方式进入前端。
6. 前端代码结构开始从单文件页面转向模块化组织。

---

## 5. 用户与场景

## 5.1 目标用户

- 团队知识工作者
- 个人研究型用户
- 需要在“会话探索”和“知识沉淀”之间频繁切换的用户
- 管理员与运维观察者

## 5.2 核心场景

### 场景 A：多会话推进同一项目

用户在同一项目下维护多个会话，例如：

- 主分析会话
- 补充检索会话
- 草稿讨论会话
- 面向产出的收敛会话

需要快速切换、保留上下文，并从会话沉淀到 Wiki 与 Artifact。

### 场景 B：从 Wiki 进入关系探索

用户在阅读或编辑 Wiki 页面时，希望看到：

- 该页面关联了哪些其他页面
- 哪些页面引用了它
- 有哪些未解析链接
- 与哪些 Artifact / Document / Card 相连

### 场景 C：从 Graph 回到具体内容

用户在图谱中发现节点与路径后，需要快速回到：

- 具体 Wiki 页面
- 具体 Artifact
- 具体 Document 或相关对象

---

## 6. 需求范围

## 6.1 In Scope

本项目包含：

1. App Shell 重构
2. Global Rail 重构
3. Context Rail 重构
4. Inspector 统一化
5. Chat Workbench 重构
6. Wiki Workbench 重构
7. Graph 视图接入
8. Artifact 阅读面的壳层统一
9. 关键弹窗与抽屉交互规范化
10. 前端模块拆分基础建设

## 6.2 Out of Scope

本项目不包含：

1. React/Vue/Next 技术栈切换
2. 富文本编辑器大换血
3. 移动端优先重构
4. 多人实时协同编辑
5. 图谱高级算法可视化增强
6. 新一轮后端接口设计
7. 新前端构建链引入，例如 bundler、TypeScript、JSX 改造

---

## 7. 信息架构需求

## 7.1 Global Rail

必须支持：

- Space Home
- Team Knowledge
- Personal Research
- Chat
- Wiki
- Graph
- Artifacts
- Memory
- Admin

验收标准：

- 仅显示一级入口
- 图标有 tooltip
- 当前入口高亮
- 宽度稳定，不随页面波动

## 7.2 Context Rail

必须支持根据模式显示不同分组内容。

### Chat 模式

- 会话列表
- 草稿 / 正式状态
- 最近活跃
- 最近相关 Artifact

### Wiki 模式

- Wiki 页面树
- 搜索
- 最近打开页面

### Graph 模式

- 节点筛选器
- 视图入口
- 已选节点集合

验收标准：

- 不出现大面积表单
- 分组可折叠
- 当前对象明显高亮

## 7.3 Main Canvas

必须支持：

- Chat 消息流
- Wiki 阅读 / 编辑
- Graph 主画布
- Artifact 阅读面

验收标准：

- 在 1440px 宽屏下保持充足阅读空间
- 在 Chat 模式下中栏最大化
- 输入区固定稳定

## 7.4 Inspector

必须支持：

- Citation 详情
- Wiki 关系
- Wiki 版本
- Graph 节点详情
- Artifact 关系
- 发布动作

验收标准：

- 可收起 / 展开
- 内容结构统一
- 不退化为纯 JSON dump

---

## 8. 功能需求拆分

## 8.1 Epic A：App Shell 重构

### 目标

建立新工作台骨架。

### 功能项

1. 新增 Global Rail
2. 重构 Top Context Bar
3. 把当前右侧 Drawer 升级为统一 Inspector
4. 支持不同模式下的布局切换

### 验收标准

- 所有主页面均套用统一壳层
- 壳层可稳定承载 Chat / Wiki / Graph / Artifact

## 8.2 Epic B：Chat Workbench 重构

### 目标

让多会话进入工作台主结构。

### 功能项

1. 会话列表进入 Context Rail
2. 消息流进入 Main Canvas
3. Citation / Context / Artifact 详情进入 Inspector
4. Composer 固定底部
5. 流式状态和中断状态视觉稳定

### 验收标准

- 切换会话不会导致布局跳动
- Citation 打开不会遮挡输入区
- 右栏关闭时，中栏自然扩展

## 8.3 Epic C：Wiki Workbench 重构

### 目标

让 Wiki 从传统表单页升级为知识沉淀工作面。

### 功能项

1. Wiki 树进入 Context Rail
2. 阅读 / 编辑视图进入 Main Canvas
3. 版本、关系、发布进入 Inspector
4. 接入页面关系接口

### 依赖接口

- `GET /api/v1/team/wiki-pages/{pageId}`
- `GET /api/v1/team/wiki-pages/{pageId}/versions`
- `GET /api/v1/team/wiki-pages/{pageId}/relations`

### 验收标准

- 可从页面直接查看关系摘要
- 可在不离开页面的情况下查看版本历史
- 发布动作采用确认弹窗

## 8.4 Epic D：Graph 接入

### 目标

让统一知识图谱成为可进入、可利用的工作视图。

### 功能项

1. 在 Wiki / Chat / Artifact Inspector 中加入图谱概览卡
2. 新增独立 Graph 视图
3. 支持节点详情
4. 支持 neighborhood
5. 支持 path 查询入口

### 依赖接口

- `GET /api/v1/spaces/{spaceId}/knowledge-graph`
- `GET /api/v1/team/wiki-pages/{pageId}/knowledge-graph`
- `GET /api/v1/spaces/{spaceId}/knowledge-graph/nodes/{nodeId}`
- `GET /api/v1/spaces/{spaceId}/knowledge-graph/neighborhood/{nodeId}`
- `GET /api/v1/spaces/{spaceId}/knowledge-graph/path`

### 验收标准

- 从 Wiki 页面可进入局部图谱
- 从图谱节点可跳转到原对象
- Inspector 能稳定显示节点详情

## 8.5 Epic E：前端模块化基础

### 目标

在不换框架的前提下，解决单文件膨胀。

### 功能项

1. Shell 组件拆分
2. Page 组件拆分
3. 通用组件拆分
4. 状态按域拆分

### 验收标准

- `app.js` 不再继续作为所有页面逻辑的唯一承载点
- 新页面能力可在模块边界内独立扩展

---

## 9. 交互规则

## 9.1 弹窗规则

使用弹窗的动作：

- 新建
- 设置
- 分享
- 发布确认
- 删除确认
- 高级筛选

验收标准：

- 不使用弹窗承载长时阅读内容
- 不使用弹窗承载主编辑区

## 9.2 Inspector 规则

使用 Inspector 的内容：

- 证据
- 关系
- 版本
- 节点详情
- 发布上下文

验收标准：

- 打开 Inspector 不应破坏当前主流程
- 用户可一边看中栏一边对照右侧

---

## 10. 视觉与布局要求

## 10.1 布局宽度

默认桌面：

- Global Rail：60px
- Context Rail：280px
- Inspector：320px 到 360px
- Main Canvas：剩余空间优先

退化顺序：

1. 先收起 Inspector
2. 再压缩 Context Rail
3. 最后让 Context Rail 进入抽屉模式

## 10.2 风格要求

- 延续 NoteWeave 当前的研究型气质
- 中栏更重阅读性
- 状态色清晰
- 图谱视图关系线易辨识
- 避免后台感过强的“表单堆”

---

## 11. 实施阶段

## Phase A：文档与壳层

交付项：

- 重构方案文档
- 施工 PRD
- App Shell 初版

完成标志：

- 新壳层能渲染当前主路由

## Phase B：Chat 优先落地

交付项：

- 会话 Context Rail
- Chat Main Canvas 重排
- Inspector 接入 Citation

完成标志：

- 多会话工作流明显优于当前版本

## Phase C：Wiki Workbench

交付项：

- Wiki 树
- 阅读 / 编辑重排
- 版本 / 关系侧栏

完成标志：

- Wiki 已具备工作台形态

## Phase D：Graph 接入

交付项：

- 嵌入式图谱卡片
- Graph 独立视图
- 节点详情 Inspector

完成标志：

- 用户可从 Wiki / Chat 顺滑进入 Graph

## Phase E：统一收口

交付项：

- Artifact / Memory / Admin 壳层统一
- 样式与组件收口
- 文档回补

完成标志：

- 工作台体验在主要页面中趋于一致

---

## 12. 技术实现建议

## 12.1 保持当前技术栈

当前阶段继续使用：

- `index.html`
- `app.css`
- `js/app.js`
- `js/api.js`

不引入新框架作为本 PRD 前提。
同时不引入新的 bundler 或编译步骤，继续使用浏览器原生 ESM 与静态资源组织方式。

## 12.2 推荐拆分方向

建议逐步拆分为：

- `shell/*`
- `pages/chat/*`
- `pages/wiki/*`
- `pages/graph/*`
- `components/*`
- `state/*`

补充要求：

- 新增模块文件必须可直接被当前静态页通过 `type="module"` 加载。
- 所有拆分路径应保持 `static/js` 下的相对导入可运行。
- 不允许在本轮任务中把“模块拆分”偷换成“先引入新工程化体系”。

## 12.3 测试建议

至少覆盖：

- 路由切换
- 会话切换
- Inspector 打开关闭
- Wiki 关系加载
- Graph 节点详情加载

可结合现有 Playwright smoke 流程逐步补齐。

---

## 13. 风险与缓解

## 13.1 风险：布局先改，页面不跟

表现：

- 新壳层与旧页面视觉冲突

缓解：

- 先落 Chat 和 Wiki 两个最高频页

## 13.2 风险：右栏内容失控

表现：

- Inspector 变成另一个杂物区

缓解：

- 坚持“解释型内容进右栏，动作型内容进弹窗”

## 13.3 风险：Graph 过重

表现：

- 图谱挤压主工作流

缓解：

- 先做嵌入式卡片，再做独立视图

## 13.4 风险：单文件重构中断

表现：

- 业务改到一半，`app.js` 更难维护

缓解：

- 以壳层、页面、组件三个维度同步拆分

---

## 14. 验收清单

项目最终验收时，至少确认以下条目：

1. Chat 模式下多会话切换顺畅
2. 中栏在 Chat 模式下明显是主视觉核心
3. Wiki 页面可查看关系、版本和发布动作
4. Graph 可从 Wiki 或 Chat 进入
5. Inspector 在各主视图下结构统一
6. 至少完成一轮模块拆分，避免继续单文件堆叠
7. 主要宽屏场景下布局稳定，不出现严重压缩

---

## 15. 后续衔接

本 PRD 完成后，后续建议继续补三类文档：

1. 页面级线框说明
2. 前端组件 API 清单
3. 施工任务拆解与 issue 清单

如果进入正式编码阶段，建议以本 PRD 为母文档，再拆成：

- `App Shell 实施单`
- `Chat Workbench 实施单`
- `Wiki Workbench 实施单`
- `Graph 视图实施单`
