# 阶段 6 前端 Research Checkpoint 来源审计动态面板验收

- 验收日期：2026-07-15
- 范围：选中 checkpoint 的 provenance、trace/round/verifier 锚点、审计定位操作、动态分包与 UI 契约
- 状态：本批完成；snapshot findings/conflicts、baseline/resume/counterfactual 差异和原始 JSON 仍为后续切片
- 非范围：未读取、未修改 `workers/research-worker/` 与 `workers/artifact-worker/`

## 1. 组件边界

新增 `frontend/src/features/research/ResearchCheckpointProvenancePanel.tsx`，从 `App.tsx` 迁出：

- checkpoint 到当前 run 的 branch、来源焦点、finding/conflict 连续性；
- checkpoint source trace、source loop round、source verifier decision 与 summary samples；
- “定位到 trace / round / verifier”审计焦点操作。

`App.tsx` 继续负责从既有 checkpoint、trace、loop、verifier 和 outcome diff 计算 provenance read view，并保持 audit focus state 唯一。组件只渲染卡片并回调聚焦动作，不创建第二套 Research 或 checkpoint 状态。

## 2. 动态加载与门禁

`App.tsx` 使用 `React.lazy` + `Suspense` 加载 `ResearchCheckpointProvenancePanel`。

UI 契约已验证真实 lazy import、provenance 卡片、审计聚焦回调，以及 App 内来源模型中的三种定位动作。

## 3. 自动验证

```powershell
cd frontend
npm.cmd test
npm.cmd run build
npm.cmd run ui:check
```

结果：

- Vitest：28 个测试文件，97/97 通过；
- `tsc -b && vite build`：87 modules transformed；
- 新增 `assets/ResearchCheckpointProvenancePanel-ZsWKxtIS.js`，0.61 kB（gzip 0.34 kB）；
- 主 bundle：467.66 kB（gzip 139.64 kB），未出现 Vite 500 kB warning；
- UI contract 与 `git diff --check` 通过（工作区既有 LF→CRLF 提示除外）。

## 4. Production Preview

```text
GET /                                                      200
GET /assets/ResearchCheckpointProvenancePanel-ZsWKxtIS.js 200
```

临时 preview 进程已清理。

## 5. 后续边界

下一批迁出 snapshot findings/conflicts、checkpoint baseline、resume recovery、counterfactual 差异和原始 JSON 审计视图；继续复用 `features/research` model 与 `useResearchState`，不触碰两个独立 worker。
