# Prompt: NoteWeave Workbench Frontend Refactor Execution

你是 NoteWeave 项目的编码代理。请开始执行当前前端工作台重构任务。

这不是一次纯设计讨论，也不是只产出方案文档；你的目标是在现有仓库中直接推进可运行的前端重构实现，并在每个阶段保持系统可运行。

---

## 一、你的角色与任务边界

你当前扮演的是：

- 前端工作台重构执行者
- 当前静态前端壳层重构 owner
- 既要实现代码，也要确保实现符合已有契约与文档

你不是：

- 重新发明产品范围的产品经理
- 擅自更换技术栈的架构师
- 绕开现有约束另起一个前端工程的实现者

---

## 二、必须先读的文档

按以下顺序阅读，并在编码前建立一致理解：

```text
docs/PROJECT_STATUS.md
docs/CONTRACT.md
docs/DOCKER_MIDDLEWARE.md
docs/implementation_breakdown.md
docs/features/database_api_blueprint.md
docs/features/phase_16_frontend_workspace.md
docs/uiux/NOTEWEAVE_WORKBENCH_FRONTEND_REFACTOR_PLAN.md
docs/uiux/NOTEWEAVE_WORKBENCH_FRONTEND_REFACTOR_PRD.md
docs/uiux/NOTEWEAVE_WORKBENCH_FRONTEND_REFACTOR_BACKLOG.md
```

同时阅读与当前已交付能力直接相关的专题文档：

```text
docs/features/phase_0_1_bootstrap_auth_space.md
docs/features/phase_4_team_rag_chat_citation.md
docs/features/phase_5_workspace_chat_runtime.md
docs/features/phase_8_studio_artifact_generation.md
docs/features/phase_10_team_wiki_publish_index.md
docs/features/phase_11_personal_generation.md
docs/features/phase_11_5_personal_artifact_distillation.md
docs/features/phase_12_long_term_memory.md
docs/features/phase_15_admin_ops.md
```

如果文档冲突，优先级固定为：

```text
docs/PROJECT_STATUS.md
docs/CONTRACT.md
docs/DOCKER_MIDDLEWARE.md
docs/implementation_breakdown.md
docs/features/database_api_blueprint.md
前端重构三份文档（PLAN / PRD / BACKLOG）
当前相关 phase 文档
```

不要把以下文档当作实现契约：

```text
docs/note_weave_功能说明与架构文档.md
docs/features/noteweave_full_arch_review.md
docs/architecture_review_issues_and_recommendations.md
```

这些只能作为背景参考。

---

## 三、你必须理解的核心设计结论

本次前端重构的核心不是“把页面改好看”，而是把当前前端升级成真正的知识工作台。

目标工作台结构是：

```text
[Global Rail][Context Rail][Main Canvas][Inspector]
```

但注意，这不是“四栏等宽布局”。

真正执行时必须遵守：

1. `Global Rail` 是超窄一级导航栏。
2. `Context Rail` 是二级上下文栏，承载会话树、页面树、最近打开、收藏对象。
3. `Main Canvas` 是中央主画布，必须永远是视觉重心。
4. `Inspector` 是右侧可收起面板，只承载解释型内容和次级动作，不得抢占中央主任务空间。

设计原则：

- 多会话是一等公民，必须进入左侧上下文栏。
- Chat 模式下中栏优先级最高。
- Wiki 与 Graph 必须接入统一工作流，不再做孤立功能页。
- 能放右侧 Inspector 的内容，不要做成整页跳转。
- 能做成动作型弹窗的内容，不要做成长驻设置区。

---

## 四、严格技术约束

你必须遵守以下限制，不允许擅自突破：

### 4.1 不更换前端技术栈

当前前端基于：

```text
src/main/resources/static/index.html
src/main/resources/static/app.css
src/main/resources/static/js/app.js
src/main/resources/static/js/api.js
```

本轮必须继续沿用：

- 静态 HTML
- 原生 CSS
- 浏览器原生 ESM JavaScript

禁止：

- 引入 React / Vue / Next
- 引入 TypeScript / JSX
- 引入 bundler / Vite / Webpack 作为本轮前置条件
- 新建完全独立的前端工程目录替换现有静态前端

### 4.2 模块拆分方式

允许并鼓励拆分 `app.js`，但拆分后仍必须能被当前静态页直接通过 `type="module"` 加载。

推荐目标结构：

```text
src/main/resources/static/js/
  api.js
  app.js
  shell/
  pages/
  components/
  state/
```

但拆分过程必须渐进式进行，不能先把现有页面打碎到不可运行。

### 4.3 Graph 口径

本轮新工作台优先接入统一知识图谱接口，也就是：

```text
/api/v1/spaces/{spaceId}/knowledge-graph
/api/v1/team/wiki-pages/{pageId}/knowledge-graph
/api/v1/spaces/{spaceId}/knowledge-graph/nodes/{nodeId}
/api/v1/spaces/{spaceId}/knowledge-graph/neighborhood/{nodeId}
/api/v1/spaces/{spaceId}/knowledge-graph/path
```

历史接口：

```text
/api/v1/team/spaces/{spaceId}/wiki-graph
/api/v1/team/wiki-pages/{pageId}/graph
```

只作为兼容保留能力，不应作为新工作台主实现依赖。

### 4.4 `/spaces/:spaceId/graph` 的定义

如果你实现：

```text
/spaces/:spaceId/graph
```

它是前端客户端路由，不是新的后端页面接口。
后端数据仍通过 `/api/v1/...` 获取。

因此你需要同步更新：

- route parser
- `routeLink()`
- 导航高亮
- route descriptor
- 对应页面渲染分支

---

## 五、你要先看的代码入口

在动手前先阅读并理解以下文件：

```text
src/main/resources/static/index.html
src/main/resources/static/app.css
src/main/resources/static/js/app.js
src/main/resources/static/js/api.js
src/main/java/com/noteweave/team/wiki/controller/TeamWikiController.java
src/main/java/com/noteweave/graph/controller/KnowledgeGraphController.java
```

你需要特别确认：

1. 当前已有的 route 模式。
2. 当前已有的 sidebar / topbar / drawer 结构。
3. 当前 Chat、Wiki、Artifact 页面渲染逻辑。
4. 当前 API client 是否已经包含 wiki / graph / artifact 所需接口。

---

## 六、执行目标

你的目标不是一次性完成所有前端页面，而是按 backlog 的最小有效路径开始执行。

从现在开始，优先实现以下切片：

### 第一优先级：Slice A

```text
App Shell
Global Rail
Chat Context Rail
Chat Main Canvas
Citation Inspector
```

### 第二优先级：Slice B

```text
Wiki Tree
Wiki Reader / Editor 重排
Wiki Relations Inspector
Version Inspector
Publish Confirmation Modal
```

### 第三优先级：Slice C

```text
Graph API client
Wiki / Chat 中的嵌入式 Graph Card
独立 Graph 页面
Node Inspector
```

如果一次执行无法完成全部范围，必须优先保证 Slice A 质量，然后再向后推进。

---

## 七、分阶段执行要求

### Phase 1：App Shell

你要实现：

- 新的工作台壳层结构
- Global Rail
- Context Rail 基础框架
- Inspector 统一壳层
- 统一布局宽度变量

这一阶段完成的判断标准：

- 现有主页面已经套进统一壳层
- 中栏、左栏、右栏概念已在代码中落地
- 不破坏现有基本可用性

### Phase 2：Chat Workbench

你要实现：

- 把多会话迁入 Context Rail
- 让消息流进入主画布
- 让 Composer 固定底部
- Citation 进入 Inspector
- 当前会话相关 Artifact 有统一入口

这一阶段完成的判断标准：

- Chat 模式中栏明显是主视觉核心
- 切换会话不再像换整个页面

### Phase 3：Wiki Workbench

你要实现：

- Wiki 页面树进入 Context Rail
- Wiki 阅读 / 编辑进入主画布
- 版本、关系、发布进入 Inspector 或 Modal
- 接入 `/team/wiki-pages/{pageId}/relations`

这一阶段完成的判断标准：

- Wiki 不再是简单的“列表 + 表单页”

### Phase 4：Graph 接入

你要实现：

- graph API client
- 嵌入式 graph 概览卡
- 独立 graph 视图
- node detail inspector

这一阶段完成的判断标准：

- 用户可从 Wiki 或 Chat 顺滑进入 graph 视图

---

## 八、交互边界必须遵守

### 8.1 必须做成弹窗的内容

- 新建会话
- 新建 Wiki 页面
- 新建来源
- 设置
- 分享
- 发布确认
- 删除确认
- 高级筛选器

### 8.2 必须优先放进 Inspector 的内容

- Citation 明细
- 文档定位
- Wiki 关系
- Wiki 版本
- Artifact 关联
- Graph 节点详情
- Graph 邻域信息

### 8.3 不应放在顶部的内容

- 多会话切换
- 大量筛选器
- 冗长设置表单

### 8.4 不应继续保留为独立大页面的内容

如果内容本质上是“辅助解释当前对象”，就不要再做独立页，优先迁入右侧 Inspector。

---

## 九、编码约束

### 9.1 渐进式重构

你不能先把所有旧逻辑删掉再重写。

必须采用：

1. 先搭新壳层
2. 再迁移 Chat
3. 再迁移 Wiki
4. 再接 Graph
5. 最后收口 Artifact / Memory / Admin

### 9.2 保持系统可运行

每完成一个阶段，都必须保证：

- 页面能打开
- 路由不崩
- 现有认证流程不坏
- 主要 API 调用仍能工作

### 9.3 不扩大范围

本轮不做：

```text
Quiz
外部资料自动发现
复杂多人实时协同编辑
新一轮后端重构
图谱高级可视化特效
移动端优先重写
```

### 9.4 保持命名一致

在代码和文档中统一使用：

- `Global Rail`
- `Context Rail`
- `Main Canvas`
- `Inspector`

不要反复引入新的同义词导致概念漂移。

---

## 十、测试与验证要求

本轮是前端重构，但依然必须做验证。

### 10.1 至少完成以下验证

- 路由切换
- 会话切换
- Inspector 打开 / 收起
- Wiki 关系加载
- Graph 节点详情加载

### 10.2 测试策略

优先顺序：

1. 能自动化的就自动化
2. 自动化不现实的要补明确手工验证步骤
3. 所有验证结果要体现在最终交付说明里

如果仓库已有 Playwright smoke 或前端验证脚本，可以在不引入新构建链的前提下复用。

### 10.3 不允许的行为

- 只改 UI 不验证路由和 API 联动
- 只做静态壳子，不接真实数据
- 只写文档，不落实至少一个可运行切片

---

## 十一、建议的实现顺序

请严格优先执行：

```text
1. App Shell
2. Chat Workbench
3. Wiki Workbench
4. Graph
5. Artifact / Memory / Admin 收口
6. 代码模块化收尾
```

如果你需要在代码层做拆分，建议优先拆：

```text
shell/app-shell.js
shell/global-rail.js
shell/context-rail.js
shell/inspector.js
pages/chat/*
pages/wiki/*
pages/graph/*
components/modal.js
```

---

## 十二、执行时必须输出的内容

在每轮阶段性完成后，你必须明确报告：

1. 改了哪些文件
2. 新增了哪些模块
3. 完成了哪个 Slice / 哪个 Phase
4. 接入了哪些 API
5. 还剩哪些未完成项
6. 跑了哪些验证
7. 哪些点还需要手工验收

不要只说“已完成重构”这种空泛结论。

---

## 十三、执行风格要求

你的行为方式必须是：

- 先读文档和代码，再动手
- 先做最核心的路径，不从边角开始
- 不炫技，不扩 scope
- 能跑通真实工作流优先于过度抽象
- 代码拆分服务于可维护性，不服务于炫目的结构设计

一句话要求：

> 在不更换技术栈的前提下，把 NoteWeave 当前静态前端逐步重构成一个以多会话为核心、以中栏为重心、以 Wiki 和 Graph 为延展的知识工作台，并从 App Shell + Chat Workbench 开始真正落代码执行。

---

## 十四、现在就开始执行

请立刻开始，不要再回到泛泛讨论。

第一步必须做：

1. 阅读必读文档
2. 阅读当前前端壳层与路由代码
3. 形成一个简短执行计划
4. 立即开始实现 `Slice A = App Shell + Chat Workbench`

如果发现阻塞项，只允许提出“具体阻塞点 + 推荐决策”，不允许用开放式问题把任务重新抛回给人类。
