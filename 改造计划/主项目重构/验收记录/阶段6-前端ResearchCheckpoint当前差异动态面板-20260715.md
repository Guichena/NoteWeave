# 阶段 6 前端 Research Checkpoint 当前差异动态面板验收

- 验收日期：2026-07-15
- 范围：Checkpoint To Current Diff、动态分包与 UI 契约
- 状态：本批完成；Checkpoint To Checkpoint、Resume Recovery、Counterfactual Branch 和原始 JSON 仍为后续切片
- 非范围：未读取、未修改 `workers/research-worker/` 与 `workers/artifact-worker/`

## 1. 组件边界

新增 `frontend/src/features/research/ResearchCheckpointCurrentDiffPanel.tsx`，从 `App.tsx` 迁出：

- Table-as-State、Recovery Target、Verifier/Loop、Evidence 四类摘要差异；
- verified findings、conflict resolution、verifier-gated row 漂移；
- 自 snapshot 以来新增 verified findings 及空态。

`App.tsx` 继续作为所有 diff、恢复目标匹配、来源叙事和审计聚焦的唯一计算/状态 owner，仅输出标准化展示卡。组件不复制 checkpoint、Research 或 Evidence 状态。

## 2. 动态加载与门禁

`App.tsx` 通过 `React.lazy` + `Suspense` 加载 `ResearchCheckpointCurrentDiffPanel`。

UI 契约已验证真实 lazy import、通用差异卡渲染、新增 finding 区域，并检查 App 端仍生成七类完整 current-diff read view。

## 3. 自动验证

```powershell
cd frontend
npm.cmd test
npm.cmd run build
npm.cmd run ui:check
```

结果：

- Vitest：28 个测试文件，97/97 通过；
- `tsc -b && vite build`：89 modules transformed；
- 新增 `assets/ResearchCheckpointCurrentDiffPanel-CvqcEvqI.js`，1.17 kB（gzip 0.51 kB）；
- 主 bundle：465.52 kB（gzip 139.88 kB），未出现 Vite 500 kB warning；
- UI contract 与 `git diff --check` 通过（工作区既有 LF→CRLF 提示除外）。

## 4. Production Preview

```text
GET /                                                     200
GET /assets/ResearchCheckpointCurrentDiffPanel-CvqcEvqI.js 200
```

临时 preview 进程已清理。

## 5. 后续边界

下一批迁出 Checkpoint To Checkpoint、Resume Recovery、Counterfactual Branch 差异与原始 JSON 审计视图；继续复用 `features/research` model 与 `useResearchState`，不触碰两个独立 worker。
