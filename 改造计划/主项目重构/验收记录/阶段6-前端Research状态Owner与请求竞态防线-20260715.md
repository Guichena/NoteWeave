# 阶段 6 前端 Research 状态 Owner 与请求竞态防线验收

- 验收日期：2026-07-15
- 范围：Research run/detail/checkpoint server-state、workspace reset、乱序请求取消、App 迁移与架构门禁
- 状态：本批完成；Research JSX/read model 的独立工作台与延迟加载留给下一批

## 1. 问题与边界

在 API owner 迁移后，`App.tsx` 仍同时持有 Research history、current run detail、selected checkpoint、comparison checkpoint 和 resume-source checkpoint 的八组 `useState`，并在 history/detail/refresh/resume/open checkpoint 路径中分散写入。这会造成 workspace 切换和延迟响应之间的投影污染风险。

本批迁移服务端状态与请求生命周期，不改变 Deep Research 的创建、恢复、报告写回、审计说明、筛选或展示文案。问题、profile、约束、显式资料范围和展示层 UI 草稿继续由页面保存。

## 2. Server-State Owner

新增：

```text
src/features/research/state.ts
src/features/research/useResearchState.ts
src/features/research/state.test.ts
```

`ResearchServerState` 是唯一持有以下投影的 reducer state：

- run history；
- 当前 run id/detail；
- 当前 selected checkpoint；
- comparison checkpoint；
- resumed-from run 的 source checkpoint。

`useResearchState` 对外提供 `loadHistory`、`loadRunDetail`、`prepareRun`、`selectCheckpoint`、`selectComparison` 和 `clear`。`App.tsx` 只组合这些 action、表单输入和可视化派生，不再直接持有相关 server-state setter。

## 3. 请求竞态规则

`LatestResearchRequestGate` 使用独立 channel：

```text
history
run
checkpoint
comparison
```

- 同 channel 新请求立即 abort 旧请求；
- workspace 切换 abort 全部 in-flight 请求，并创建空的新 workspace state；
- Hook 卸载 abort 全部请求；
- `prepareRun` 先取消 run/checkpoint/comparison channel 并清空旧 checkpoint/comparison/resume 投影；
- run detail 与 resume-source / selected checkpoint 作为一个 reducer snapshot 提交；
- checkpoint 选择与比较基线一次并发读取后原子提交。

因此旧 workspace、旧 run 或旧 checkpoint 的迟到响应不能覆盖当前审计面板。

## 4. App 与门禁

`App.tsx` 已删除：

```text
researchRuns/currentResearchRun/currentResearchRunId useState
selected/compare checkpoint useState
resumeSourceCheckpoint useState
上述全部 set* server-state setter
```

`ui-contract-check.mjs` 禁止这些 owner 回流，同时要求真实接入 `useResearchState` 和 history/detail/checkpoint/comparison action。Research endpoint 仍仅由 `ResearchApi` 持有。

## 5. 自动验证

```powershell
cd frontend
npm.cmd test -- --run src/features/research/api.test.ts src/features/research/state.test.ts
npm.cmd test
npm.cmd run build
npm.cmd run ui:check
```

结果：

- Research API/state 定向测试：`5/5`；
- 前端全量：`27` 个测试文件，`94/94`；
- `tsc -b && vite build`：通过，`72` modules transformed；
- production JS：`assets/index-DvrLKQ1S.js`，`504.02 kB`，gzip `144.92 kB`；
- production CSS：`assets/index-bzc9kVQ8.css`，`26.29 kB`，gzip `5.26 kB`；
- UI contract：通过；
- 本批 `git diff --check`：无 whitespace error，仅报告工作区既有 LF→CRLF 提示；
- `App.tsx` Research server-state setter 静态搜索：零匹配。

reducer/gate 测试覆盖 workspace reset、new-run checkpoint reset、same-channel abort 和 workspace replacement abort。

## 6. Production Preview 与未完成边界

当前 `dist` production preview：

```text
GET http://127.0.0.1:4173/
200 OK
#root: present
assets/index-DvrLKQ1S.js: referenced
```

preview 进程已清理。当前主 chunk 为 `504.02 kB`，仍高于 Vite 500 kB 提示线。

本批没有创建只包含壳的 `React.lazy()` 并声称完成拆包：Research JSX 和大体量 response/展示派生 type 仍在 `App.tsx`，所以编译产物尚未产生独立 Research chunk。下一批必须把真实 `ResearchWorkbench`、其 read model 和展示 helper 一起抽出，再以 `/research` 动态 import 的 bundle 输出作为验收证据。

## 7. 下一切片

抽出真实 Research 工作台组件与 read model，完成 `/research` 延迟加载；随后再对 Artifact 的 job/version state 与组件采用同一 owner 模式。
