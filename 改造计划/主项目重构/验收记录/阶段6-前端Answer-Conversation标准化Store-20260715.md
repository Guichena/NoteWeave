# 阶段 6 前端 Answer/Conversation 标准化 Store 验收

- 验收日期：2026-07-15
- 范围：Conversation/Answer feature API、AnswerRun 标准化 store、conversation SSE reducer、终态快照校正与 App 接线
- 状态：完成；保持现有回答算法、API 路径、页面视觉和交互

## 1. Feature 边界

新增：

```text
src/features/conversations/model.ts
src/features/conversations/api.ts
src/features/answers/model.ts
src/features/answers/api.ts
src/features/answers/store.ts
```

- `ConversationsApi` 持有工作台会话创建协议；
- `AnswersApi` 持有消息发送和 AnswerRun snapshot 查询协议；
- `App.tsx` 不再直接拼接 Conversation create、Answer send 或 AnswerRun query endpoint；
- 顶层 `src/sse.ts` 与 `src/conversationStream.ts` 兼容桥已删除，测试和生产代码直接使用 `src/shared/event-stream`。

## 2. AnswerRun 单一状态 Owner

`AnswerRunStore` 以 `runId` 标准化保存正文、citation、status、error 和最后接纳的 `run_sequence`：

- conversation event id 去重，重连 replay 不重复应用；
- `run_sequence` 必须单调递增，拒绝同 run 的重复或乱序事件；
- citation 按内容去重；
- live terminal 后拒绝迟到 delta 和冲突 terminal；
- `conversation.snapshot` 作为 MySQL 权威状态投影，可校正重连后的运行状态；
- fallback answer stream 复用 `reduceAnswerRunState`，不保留第二套正文/citation/terminal 归并算法。

终态 waiter 的 resolve、reject 和 clear 由 store 集中管理。会话切换或组件清理时，所有未完成 waiter 被明确拒绝，不再把 Promise 生命周期散落在 `App.tsx`。

## 3. 终态一致性

Conversation stream 观察到 terminal 后，前端调用 `answersApi.getRun(workspaceId, runId)` 读取持久化 AnswerRun snapshot，再通过 `reconcileRun` 校正最终正文、status 与 error。该流程保留已流式接收的 citation，避免终态事件先于最终正文持久化投影时展示不完整内容。

## 4. 自动验证

```powershell
cd frontend
npm test
npm run build
npm run ui:check
```

结果：

- Vitest：`18` 个文件，`61/61`；
- `tsc -b && vite build`：通过，`58` modules transformed；
- production bundle：`assets/index-BW7eICMB.js`，`475.52 kB`，gzip `137.00 kB`；
- UI contract：通过；
- `git diff --check`：本批前端与阶段文档无空白错误；
- `App.tsx` 无旧 Answer waiter、AnswerRun Map、envelope reducer 或直接 `fetch` 残留。

测试覆盖 Conversation/Answer endpoint、snapshot 对账、event id 去重、乱序 `run_sequence`、citation 去重、terminal 后迟到事件、waiter 生命周期和 fallback reducer。

## 5. 运行态 Smoke

新 `dist` 通过一次性 Nginx runtime 以只读挂载方式加入 Compose 网络：

- `/` 与 production JS asset：`200`；
- Workspace、Conversation、Answer 代理路径：成功；
- Conversation SSE：`conversation.snapshot -> answer.delta -> answer.completed`；
- conversation event id：`1, 2`；
- terminal AnswerRun query：`status=COMPLETED`，`content length=93`。

一次性容器、临时 image tag 和 `4173` preview 均已清理。canonical Frontend 镜像未更新：标准 rebuild 连续两次被 Docker Hub 对 `node:22-alpine` / `nginx:1.27-alpine` 的 TLS handshake timeout 阻塞，因此不将旧容器声明为新 bundle 验收结果。

## 6. 下一切片

迁移 `src/features/knowledge` 的 model/API owner，先移除 `App.tsx` 中 Knowledge endpoint 和重复类型，再按页面边界收敛 server state；本批 normalized AnswerRun store 继续作为唯一回答事件状态 owner。
