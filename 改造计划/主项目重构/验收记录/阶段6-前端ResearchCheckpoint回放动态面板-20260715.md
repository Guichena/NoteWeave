# 阶段 6 前端 Research Checkpoint 回放动态面板验收

- 验收日期：2026-07-15
- 范围：Research detail overlay 的 checkpoint 列表、回放/恢复操作、动态分包与 UI 契约
- 状态：本批完成；选中 checkpoint 的深度差异、resume recovery 与 counterfactual 审计仍为后续切片
- 非范围：未读取、未修改 `workers/research-worker/` 与 `workers/artifact-worker/`

## 1. 组件边界

新增 `frontend/src/features/research/ResearchCheckpointReplayPanel.tsx`，从 `App.tsx` 迁出：

- checkpoint 快照、branch/loop、verifier、evidence、intent/requirement 摘要；
- summary source、recovery target/叙事、trace、连续性与轮次变化说明、signal chips；
- 回放与“从此恢复”操作入口及无 checkpoint 空态。

`App.tsx` 继续持有 checkpoint、trace、loop round、恢复目标及状态转换的唯一来源，只计算 card read view 并处理回调。组件不创建第二套 checkpoint 或 Research state。

## 2. 动态加载与门禁

`App.tsx` 通过 `React.lazy` + `Suspense` 加载 `ResearchCheckpointReplayPanel`。

UI 契约已验证真实 lazy import、回放卡、连续性说明、回放与恢复入口，以及无 checkpoint 空态。

## 3. 自动验证

```powershell
cd frontend
npm.cmd test
npm.cmd run build
npm.cmd run ui:check
```

结果：

- Vitest：28 个测试文件，97/97 通过；
- `tsc -b && vite build`：85 modules transformed；
- 新增 `assets/ResearchCheckpointReplayPanel-BdHBsEvz.js`，1.73 kB（gzip 0.69 kB）；
- 主 bundle：469.29 kB（gzip 139.50 kB），未出现 Vite 500 kB warning；
- UI contract 与 `git diff --check` 通过（工作区既有 LF→CRLF 提示除外）。

## 4. Production Preview

```text
GET /                                                  200
GET /assets/ResearchCheckpointReplayPanel-BdHBsEvz.js 200
```

临时 preview 进程已清理。

## 5. 后续边界

下一批迁出选中 checkpoint 的 snapshot、provenance、baseline/resume/counterfactual 差异和原始 JSON 审计视图；继续复用 `features/research` model 与 `useResearchState`，不触碰两个独立 worker。
