## 其他模块 · 前端
（审查发现如下，分批追加）

## shared/event-stream（SSE / 流式）

### [高] SSE data 字段被 `\\n` → `\n` 反转义，会篡改合法载荷
- 位置：`frontend/src/shared/event-stream/sse.ts:23-28`
- 问题：`parseBlock` 在拼接 `data:` 各行后额外执行 `.replaceAll("\\n", "\n")`，把两字符序列“反斜杠 + n”一律替换成真实换行。这是一个非标准的私有约定（依赖后端把换行编码成字面 `\n`），不符合 W3C SSE 规范（规范里多行 data 应各自以 `data:` 前缀，用真实 `\n` 连接）。
- 证据：第 27 行 `.replaceAll("\\n", "\n")`；同时第 23-26 行已经按标准把多条 `data:` 行 join("\n")，二者叠加导致语义混乱。
- 影响：当 answer delta / research 报告正文里本身包含字面量 `\n`（例如 Markdown 代码块示例、JSON 字符串转义、正则、Windows 路径 `C:\name`）时会被错误替换成换行，回答内容被静默篡改；JSON.parse(event.data) 若 data 内含被误转义内容也可能失败。
- 建议：删除第 27 行的 `.replaceAll`，严格按 SSE 规范用真实换行连接多条 `data:` 行；如后端确实做了编码，应在协议文档中固化并改用不会与真实内容冲突的转义方案。

### [中] consumeSse / ConversationStreamClient 在 abort 时未释放 reader 锁
- 位置：`frontend/src/shared/event-stream/consume.ts:17-35`、`frontend/src/shared/event-stream/conversationStream.ts:88-109`
- 问题：读取循环 `reader.read()` 被 abort 时会抛 AbortError 直接向上冒泡，`consume` 没有 try/finally 去 `reader.releaseLock()` / `reader.cancel()`；`consumeSse` 同理。`conversationStream.consume` 末尾的 `await reader.cancel()`（第 108 行）在 `while (!this.stopped)` 正常/异常退出路径都执行不到（return 在循环内、异常直接抛出）。
- 证据：consume.ts 第 20-35 行循环内 `return`/异常，无 finally；conversationStream.ts 第 108 行 `reader.cancel()` 位于 while 之后为死代码。
- 影响：频繁切换会话/组件卸载时底层 ReadableStream 未及时取消，可能造成连接与内存无法回收（尤其重连循环反复新建 reader）。
- 建议：用 `try { ... } finally { reader.releaseLock(); }` 包裹读取循环，并在 abort/停止时显式 `reader.cancel()`。

### [中] ConversationStreamClient 的 fetcher 分支绕过鉴权与关联 ID
- 位置：`frontend/src/shared/event-stream/conversationStream.ts:64-66`
- 问题：当传入 `fetcher` 时直接 `this.fetcher(this.path, {...})`，既不经过 `apiClient.url()` 拼 baseUrl，也不注入 `Authorization`/`X-Correlation-ID`。
- 证据：第 64-66 行 fetcher 与 client.raw 两条分支处理不对称。
- 影响：目前 fetcher 主要用于测试，风险有限；但一旦生产注入自定义 fetcher，SSE 会话流会丢失鉴权头导致 401，且无法追踪。
- 建议：让 fetcher 分支也复用 `client.url()` 与统一 header 构造，或在文档中明确 fetcher 仅供测试注入。

## features/answers（回答流状态机）

### [高] `waitFor` 无超时，回答永不终态会永久锁死 UI
- 位置：`frontend/src/features/answers/store.ts:91-104`，配合 `frontend/src/App.tsx:1057`、`frontend/src/App.tsx:1516-1527`
- 问题：连线模式（`conversationStreamConnected===true`）下 `sendConversationPrompt` 执行 `await answerRunStoreRef.current.waitFor(runId)`，而 `waitFor` 返回的 Promise 仅在收到 COMPLETED/FAILED/CANCELLED 事件时才 resolve/reject，没有任何超时兜底。`run()` 包装器在 await 期间一直保持 `isBusy=true`。
- 证据：store.ts 第 99-103 行只把 resolve/reject 存进 `waiters`，无 timer；App.tsx 第 1516-1527 `run()` 仅在 action 结束后才 `setIsBusy(false)`。
- 影响：若后端 run 卡在 GENERATING（模型挂起、worker 崩溃但未写终态、事件丢失），前端将永久转圈、`isBusy` 恒真导致所有按钮 disabled，用户只能刷新页面。
- 建议：给 `waitFor` 增加超时参数（如 120s，与后端 SseEmitter 一致），超时后走 `reconcileAnswerRun` 快照兜底或提示重试。

### [中] 连线快照 reconcile 会覆盖已 COMPLETED 的本地终态
- 位置：`frontend/src/features/answers/store.ts:78-89`
- 问题：`reconcileRun` 不检查当前是否已处于终态，直接用 `snapshot.status || current.status` 覆盖。每次 SSE 重连（后端 115s 周期性关闭）都会重发 `conversation.snapshot` → `applySnapshot` → `reconcileRun`，若快照里某 run 状态回退（如后端短暂返回非终态或空 content），已完成的回答会被回写。
- 证据：第 80-85 行合并逻辑无 `isTerminal` 守卫；对比 `reduceAnswerRunState` 第 164 行有 `if (isTerminal) return current` 守卫，二者不一致。
- 影响：极端时序下已展示的完整回答可能被截断或状态回退，造成 UI 抖动。
- 建议：`reconcileRun` 在 current 已 COMPLETED 时应保留 content/status，仅补齐缺失字段。

### [低] client_request_id 幂等键不足够唯一
- 位置：`frontend/src/App.tsx:1029`
- 问题：`client_request_id: ${mode}-${Date.now()}`，同一毫秒内相同 mode 的两次发送会碰撞。
- 证据：仅用毫秒时间戳，无随机/序列后缀。
- 影响：若后端以 client_request_id 做幂等去重，快速连点可能被误判为重复请求而丢弃。
- 建议：追加 `crypto.randomUUID()` 或递增序列。

## App.tsx（主容器 / 聊天 / Wiki / Research 面板）

### [高] 巨型 props 展开 + `Record<string, any>` 使三大惰性面板每次渲染都重建并丢失类型安全
- 位置：`frontend/src/App.tsx:2870-2977`（ResearchSidebar/ReportPanel）、`frontend/src/App.tsx:3272-3317`（ArtifactRail）；类型定义 `frontend/src/features/research/ResearchSidebar.tsx:12`、`frontend/src/features/artifacts/ArtifactRail.tsx:10`、`frontend/src/features/research/ResearchReportPanel.tsx:16`
- 问题：三个惰性子组件都以 `{...{ 几十个字段 }}` 的方式传入，且 props 类型声明为 `Record<string, any> & {...}`，等于放弃了整块 props 的类型检查（字段名拼错、类型不符编译器不报错）。同时每次 App 渲染都新建这个字面量对象与其中大量内联函数，子组件未用 `React.memo`，导致任何一次 `setState`（例如在聊天输入框敲字触发 `setQuestion`）都会让整个 ResearchSidebar/ReportPanel/ArtifactRail 重渲染。
- 证据：`type ResearchSidebarProps = Record<string, any> & {...}`；App 中 `startDeepResearch`、`toggleResearchScope` 等函数每次渲染重新定义。
- 影响：类型安全形同虚设（契约字段偏差无法在编译期暴露）；大面板无谓重渲染带来明显输入卡顿与性能浪费。
- 建议：为每个面板定义精确 props 接口（去掉 `Record<string, any>`），用 `useCallback`/`useMemo` 稳定回调与派生数据，并对面板套 `React.memo`。

### [中] 使用 `history.pushState` 手写路由但无 `popstate` 监听，浏览器前进/后退与刷新失效
- 位置：`frontend/src/App.tsx:888,903,1262,1325,1535,1847`
- 问题：切换 research/memory/wiki/chat 视图时调用 `window.history.pushState` 改地址栏，但全代码无任何 `popstate` 监听，`view` 也不从 URL 初始化（初始恒为 `"chat"`）。
- 证据：Grep 显示 6 处 `pushState`，0 处 `popstate`/`window.location` 读取；`view` 初值硬编码 `useState("chat")`。
- 影响：点浏览器返回键地址栏变了但界面不变；直接访问 `/research`、`/memory/reviews` 或刷新页面会回到聊天视图，深链失效。
- 建议：接入 react-router 或补一个 `popstate` 监听 + 初始按 `location.pathname` 恢复 `view`。

### [中] fallback 流式回答按“最后一条 assistant 气泡”定位，存在写错气泡的竞态
- 位置：`frontend/src/App.tsx:1069-1089`、`frontend/src/App.tsx:3333-3353`（updateLastAssistantMessage）
- 问题：未连线（SSE 未连通）时走 fallback，`streamEvents` 的回调用 `updateLastAssistantMessage` 按位置更新“最后一条 assistant 消息”，而非按 `answerRunId`。若期间有其他系统/assistant 消息被 append（如任务完成回写 system 消息或用户快速再次发送），delta 会写到错误气泡。
- 证据：`updateLastAssistantMessage` 用倒序找 role==="assistant"，与 `updateAssistantMessageByRun`（按 runId）不一致。
- 影响：并发或交叉消息场景下回答内容错位、串台。
- 建议：fallback 路径同样按 `sent.answer_run_id` 用 `updateAssistantMessageByRun` 定位。

### [低] 大量派生集合在渲染体内直接计算，无 useMemo
- 位置：`frontend/src/App.tsx:429`（`sourceById` Map）、`frontend/src/App.tsx:1603-1765`（groupedWikiPages、researchTimeline、continuity map 等数十个派生值）
- 问题：每次渲染都重建 `new Map(...)`、多次 `.filter/.sort/.reduce`、`Object.fromEntries`，包括 O(n log n) 排序与图谱构建。
- 证据：第 1633-1657、1746-1765 等处链式派生均在组件函数体顶层执行。
- 影响：资料/页面/run 数量增大时，每次 keystroke 级重渲染都要重算，浪费 CPU。
- 建议：对开销较大的派生用 `useMemo` 按真实依赖缓存。

## features/research

### [中] 大量研究面板组件与 hook 方法为死代码，从未被渲染
- 位置：`frontend/src/features/research/ResearchProcessOverview.tsx`、`ResearchRoundTimeline.tsx`、`ResearchProcessStageLanes.tsx`、`ResearchProcessTraceBrowser.tsx`、`ResearchAuditOverview.tsx`、`ResearchClosedLoopStatePanel.tsx`、`ResearchCheckpointReplayPanel.tsx`、`ResearchCheckpointSnapshotPanel.tsx`、`ResearchCheckpointProvenancePanel.tsx`、`ResearchCheckpointEvidenceReviewPanel.tsx`、`ResearchCheckpointCurrentDiffPanel.tsx`；以及 `frontend/src/features/research/useResearchState.ts:113,146`（selectCheckpoint / selectComparison）
- 问题：App 仅惰性引入 `ResearchSidebar`、`ResearchReportPanel`（及 ArtifactRail）；上述 11 个研究面板文件除自身定义外，全仓库无任何 import；`useResearchState` 导出的 `selectCheckpoint`/`selectComparison` 也无调用方（App 只解构 clear/prepareRun/loadHistory/loadRunDetail）。
- 证据：Grep 全 src 对这些组件名只命中各自定义处；ResearchSidebar/ReportPanel 的 import 段未引入它们。
- 影响：checkpoint 回放、对比、审计、trace 浏览等设计文档中的“可恢复/可审计”能力实际上在 UI 层未接线；同时增加维护与打包体积、误导后续开发者。
- 建议：确认这些面板是否为未完成功能——要么接线到 Research 详情弹窗/报告面板，要么删除；`selectCheckpoint`/`selectComparison` 同理。

### [低] `loadRunDetail` 的 useCallback 依赖整个 `visibleState`，回调每次状态变更都重建
- 位置：`frontend/src/features/research/useResearchState.ts:67-111`
- 问题：依赖数组含 `visibleState`，而 `visibleState` 在每次 revision 递增（几乎每个 dispatch）都是新对象，导致 `loadRunDetail`（即 App 里的 `fetchResearchRunDetail`）引用持续变化。
- 证据：第 111 行 `}, [api, gate, options.workspaceId, visibleState]);` 内部仅用到 `priorState.currentResearchRunId`、`priorState.selectedResearchCheckpointNo`。
- 影响：破坏引用稳定性，任何依赖该回调的 memo/effect 都会失效；此处更适合用 ref 读取最新值。
- 建议：改为依赖具体字段或用 `useRef` 保存最新 state，缩小依赖。
