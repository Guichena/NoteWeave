# 阶段 6 前端 Research Checkpoint 快照与基线动态面板验收

- 验收日期：2026-07-15
- 范围：选中 checkpoint 的回放头部、比较基线选择、Snapshot 摘要、动态分包与 UI 契约
- 状态：本批完成；provenance、baseline/resume/counterfactual 深度差异仍为后续切片
- 非范围：未读取、未修改 `workers/research-worker/` 与 `workers/artifact-worker/`

## 1. 组件边界

新增 `frontend/src/features/research/ResearchCheckpointSnapshotPanel.tsx`，从 `App.tsx` 迁出：

- 当前回放 checkpoint 的 branch/final 摘要；
- 比较基线选择与无基线选项；
- Snapshot Research Question、Loop State、Table-as-State、Intent Alignment 四项快照摘要。

`App.tsx` 继续根据现有 checkpoint read model 构造快照卡和可选基线，并保留比较状态与回调的唯一 owner。组件只渲染 props，未复制 checkpoint 或 Research server state。

## 2. 动态加载与门禁

`App.tsx` 通过 `React.lazy` + `Suspense` 加载 `ResearchCheckpointSnapshotPanel`，并保留既有审计容器布局。

UI 契约已检查真实 lazy import、基线选择、无基线选项、快照卡渲染和比较回调；同时验证 `App.tsx` 仍形成四个完整的 snapshot read view。

## 3. 自动验证

```powershell
cd frontend
npm.cmd test
npm.cmd run build
npm.cmd run ui:check
```

结果：

- Vitest：28 个测试文件，97/97 通过；
- `tsc -b && vite build`：86 modules transformed；
- 新增 `assets/ResearchCheckpointSnapshotPanel-Cduk9wwy.js`，0.84 kB（gzip 0.47 kB）；
- 主 bundle：468.76 kB（gzip 139.54 kB），未出现 Vite 500 kB warning；
- UI contract 与 `git diff --check` 通过（工作区既有 LF→CRLF 提示除外）。

## 4. Production Preview

```text
GET /                                                    200
GET /assets/ResearchCheckpointSnapshotPanel-Cduk9wwy.js 200
```

临时 preview 进程已清理。

## 5. 后续边界

下一批迁出 Checkpoint Provenance、snapshot findings/conflicts、checkpoint baseline、resume recovery、counterfactual 差异和原始 JSON 审计视图；继续复用 `features/research` model 与 `useResearchState`，不触碰两个独立 worker。
