# 阶段 6 前端 Artifact 状态 Owner 与请求竞态防线验收

- 验收日期：2026-07-15
- 范围：Artifact job/version server-state、history selection、mutation receipt、workspace/request lifecycle、App 迁移与架构门禁
- 状态：完成；Artifact rail 的真实动态组件边界留给下一批

## 1. 施工前缺口

`App.tsx` 同时持有 Artifact jobs、latest version、selected history version/key/loading 和 source/writeback 回执的七组服务端状态。job refresh、history detail、rollback 和 mutation receipt 分散更新这些 setter，workspace 切换或迟到 history 请求可能污染当前投影。

本批不迁移 skill chooser、composer、表单或自定义指令；它们是页面 UI 草稿，不是服务端状态。

## 2. Server-State Owner

新增：

```text
src/features/artifacts/state.ts
src/features/artifacts/useArtifactState.ts
src/features/artifacts/state.test.ts
```

`ArtifactServerState` 是下列投影的唯一 owner：

- Artifact job list；
- latest Artifact version；
- selected history version/key/loading；
- version 保存为资料后的 source receipt；
- version 写回 Note/Wiki 后的 receipt。

`useArtifactState` 提供 `refreshJobs`、`selectHistoryVersion`、`recordSavedSource`、`recordWriteback`、`applyRollback` 和 `clear`。页面继续编排用户可见的 mutation 流程，但不再持有 version/receipt server-state setter。

## 3. 请求与竞态规则

`LatestArtifactRequestGate` 区分：

```text
jobs
history
```

- `jobs` 同一 lease 先读取 job list，再读取由 list 确定的 latest version，最后原子提交 snapshot；
- 同 channel 新请求 abort 旧请求；
- workspace 切换与 Hook 卸载 abort 所有在途请求；
- history latest version 可复用时直接选中，否则走独立 history channel；
- rollback 立即更新 latest/selected version，后续 refresh 再用持久化 job list 校正；
- source/writeback receipt 使用 version key，并对重复 item type 幂等去重。

## 4. App 与架构门禁

`App.tsx` 已删除 Artifact jobs/latest/history/receipt 的七组 `useState` 与全部对应 setter。`ui-contract-check.mjs` 禁止这些 owner 回流，并要求真实导入 `useArtifactState`、调用 refresh/history/receipt action。

Artifact endpoint 仍由 `ArtifactsApi` 持有；页面保留 create/regenerate/rollback/writeback 的工作流文案、busy 状态和 Sources/Wiki refresh 编排。

## 5. 自动验证

```powershell
cd frontend
npm.cmd test -- --run src/features/artifacts/api.test.ts src/features/artifacts/state.test.ts
npm.cmd test
npm.cmd run build
npm.cmd run ui:check
```

结果：

- Artifact API/state 定向测试：`6/6`；
- 前端全量：`28` 个测试文件，`97/97`；
- `tsc -b && vite build`：通过，`76` modules transformed；
- 主 JS：`assets/index-Cb7jYTAI.js`，`501.27 kB`，gzip `144.38 kB`；
- Research Sidebar chunk：`assets/ResearchSidebar-T0y0nK22.js`，`10.25 kB`，gzip `3.71 kB`；
- CSS：`assets/index-bzc9kVQ8.css`，`26.29 kB`，gzip `5.26 kB`；
- UI contract：通过；
- `git diff --check`：无 whitespace error，仅报告工作区既有 LF→CRLF 提示；
- `App.tsx` Artifact server-state setter 静态搜索：零匹配。

reducer/gate 测试覆盖 workspace reset、source/writeback receipt 去重、same-channel history abort 和 workspace replacement abort。

## 6. Production Preview 与 Bundle 信号

```text
GET /                                      200
GET /assets/ResearchSidebar-T0y0nK22.js    200
```

首页包含 `#root` 与当前主 bundle，preview 进程已清理。

主 chunk 本批为 `501.27 kB`，Vite 重新输出 500 kB warning。该 warning 不影响功能验证，但不能通过提高阈值隐藏；下一批应抽出真实 Artifact rail/job-version-history JSX 并动态加载，以构建产物证明主 bundle 回到阈值内。

## 7. 下一切片

抽出 Artifact rail 的真实控制台组件、接入动态 import 并验证独立 chunk；继续复用本批 `useArtifactState`，不创建第二套 job/version 状态。
