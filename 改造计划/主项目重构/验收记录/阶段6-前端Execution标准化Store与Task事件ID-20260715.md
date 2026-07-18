# 阶段 6 前端 Execution 标准化 Store 与 Task 事件 ID 验收

- 验收日期：2026-07-15
- 范围：Task/Execution model、API、normalized registry、事件 ID 去重、终态 snapshot refetch、轮询降级生命周期、App shell 迁移与后端 SSE 事件 ID 契约
- 状态：完成；Artifact/Research 领域 API 与页面 server-state owner 留给下一切片

## 1. 缺口与边界

施工前 `App.tsx` 同时持有普通 Task 与 Research Task 的 snapshot、events、请求与解析逻辑，四处重复执行：

```text
GET task snapshot
GET task events
parseEventStream
set task/events server state
```

后端 Task events endpoint 返回一次性数据库 SSE 快照，不是持续保持连接的 live stream；同时 SSE 未输出数据库事件 ID，前端无法真正按持久化事件去重。本批先补齐后端 `id:` 契约，再将两类 Task 收敛到同一 Execution feature，不改变现有任务状态、等待原因或结果引用语义。

## 2. Execution Feature

新增：

```text
src/features/executions/model.ts
src/features/executions/api.ts
src/features/executions/store.ts
src/features/executions/useExecutionRegistry.ts
```

模型统一持有：

- `WaitContext` 以及 provider/approval/reason 类型；
- `ExecutionTask`、`ExecutionEvent`、`ExecutionSnapshot`、`ExecutionRecord`；
- Task 状态、进度、错误、结果引用与等待上下文。

`runStatus.ts` 只负责 UI 格式化并兼容 re-export 类型，不再拥有执行领域模型。

## 3. API、事件与终态校正

`ExecutionsApi.load(taskId)` 的固定顺序为：

```text
task snapshot -> persisted SSE event snapshot -> terminal task snapshot refetch
```

终态事件或状态出现后再次读取 Task snapshot，以 MySQL 持久态校正最终 `result_ref`、error 和 wait context。SSE parser 读取真实 `id:` 与 `event:`；历史或异常响应缺少 `id:` 时使用显式 legacy fallback，不把 fallback 描述为数据库事件身份。

后端 `TaskService` 现在输出：

```text
id: {task_event.id}
event: task.*
data: {...}
```

registry 按 event id 去重并稳定排序，同一 Task 的重复 snapshot 不会重复堆积事件。

## 4. Registry 与轮询生命周期

`useExecutionRegistry` 以 `taskId` 为 key 保存 normalized registry，可同时跟踪普通 Workspace Task 与 Research Task。生命周期约束：

- 只有 tracked 且非终态 Task 执行 `2.5s` polling；
- 每个 Task 使用独立 `AbortController`；
- terminal Task 停止轮询；
- workspace 切换清空旧 registry 并取消全部在途请求；
- Hook 卸载清理 timer 和 controller。

由于后端端点是一次性 SSE 数据库快照，轮询在这里是明确的非持续流降级。后续若增加真实 live subscription，应继续写入同一 registry，而不是创建第二套页面状态。

## 5. App 与架构门禁

`App.tsx` 只保留：

- `latestTaskId`；
- `latestResearchTaskId`；
- `loadExecution(taskId)` 接线；
- 从 registry 派生当前 Task 与事件视图。

已删除内嵌 `TaskStatus`、`StreamEvent`，以及 Task/ResearchTask snapshot/events setter、Task endpoint 拼接和 `parseEventStream`。generic consume SSE 的事件投影补齐 `id` 与 `eventType`，保持统一事件形状。

`ui-contract-check.mjs` 禁止以下实现回流 `App.tsx`：

- `/api/v2/tasks/`；
- `parseEventStream`；
- 内嵌 Task/StreamEvent 类型；
- `setLatestTask/setTaskEvents/setLatestResearchTask/setResearchTaskEvents` 类 server-state setter。

门禁同时要求真实导入 executions model/Hook，调用 `useExecutionRegistry/loadExecution`，并仅以 task id 保存页面选择。

## 6. 自动验证

```powershell
cd frontend
npm.cmd test
npm.cmd run build
npm.cmd run ui:check

cd ..
.\mvnw.cmd -f backend\pom.xml -Dtest=Phase1And2ContractTest test
git diff --check
```

结果：

- Execution API/store 与 `runStatus` 定向测试：`13/13`；
- 前端全量：`24` 个测试文件，`86/86`；
- `tsc -b && vite build`：通过，`68` modules transformed；
- production JS：`assets/index-SnyDZAXj.js`，`499.37 kB`，gzip `143.93 kB`；
- production CSS：`assets/index-bzc9kVQ8.css`，`26.29 kB`，gzip `5.26 kB`；
- UI contract：通过；
- Backend `Phase1And2ContractTest`：`11/11`，`BUILD SUCCESS`；
- `git diff --check`：无 whitespace error，仅报告工作区既有 LF→CRLF 提示；
- `App.tsx` 旧 Task endpoint/parser/setter 静态搜索：零匹配。

测试覆盖 snapshot/event 组合、真实 event id、legacy fallback、去重与排序、terminal refetch、workspace reset 和 WaitContext 格式化。

## 7. Production Preview Smoke

当前 `dist` 通过本机 production preview：

```text
GET http://127.0.0.1:4173/
200 OK
Content-Type: text/html
#root: present
assets/index-SnyDZAXj.js: referenced
```

preview 进程已在 `finally` 中清理。本批未修改视觉布局，因此验收重点为执行状态 owner、事件身份、终态校正和资源生命周期，没有把静态首页 smoke 扩大声明为完整 Task/Research 交互 E2E。

## 8. 下一切片

迁移 Artifact/Research API owner 与对应 server-state，继续将 `App.tsx` 收敛为 provider/router/shell。Artifact/Research 产生的 Task 继续复用本批 Execution registry。
