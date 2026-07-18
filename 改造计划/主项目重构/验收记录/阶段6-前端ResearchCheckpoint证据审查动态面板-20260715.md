# 阶段 6 前端 Research Checkpoint 证据审查动态面板验收

- 验收日期：2026-07-15
- 范围：选中 checkpoint 的 findings、conflicts、recovery、evidence ledger 审查区、动态分包与 UI 契约
- 状态：本批完成；checkpoint baseline/resume/counterfactual 差异和原始 JSON 仍为后续切片
- 非范围：未读取、未修改 `workers/research-worker/` 与 `workers/artifact-worker/`

## 1. 组件边界

新增 `frontend/src/features/research/ResearchCheckpointEvidenceReviewPanel.tsx`，从 `App.tsx` 迁出：

- Snapshot Verified Findings；
- Snapshot Conflict And Counterfactual Review 与分支决策；
- Snapshot Recovery Status、next actions；
- Snapshot Evidence Ledger 与各空态。

`App.tsx` 继续从既有 checkpoint read model 构造 finding、conflict、branch、recovery 和 evidence 卡数据；组件仅渲染显式 view props，不创建第二套 Evidence、checkpoint 或 Research 状态。

## 2. 动态加载与门禁

`App.tsx` 使用 `React.lazy` + `Suspense` 加载 `ResearchCheckpointEvidenceReviewPanel`。

UI 契约已验证真实 lazy import、四类证据审查入口及 conflict/evidence 空态。

## 3. 自动验证

```powershell
cd frontend
npm.cmd test
npm.cmd run build
npm.cmd run ui:check
```

结果：

- Vitest：28 个测试文件，97/97 通过；
- `tsc -b && vite build`：88 modules transformed；
- 新增 `assets/ResearchCheckpointEvidenceReviewPanel-BC8Y5vdw.js`，1.65 kB（gzip 0.60 kB）；
- 主 bundle：466.94 kB（gzip 139.64 kB），未出现 Vite 500 kB warning；
- UI contract 与 `git diff --check` 通过（工作区既有 LF→CRLF 提示除外）。

## 4. Production Preview

```text
GET /                                                        200
GET /assets/ResearchCheckpointEvidenceReviewPanel-BC8Y5vdw.js 200
```

临时 preview 进程已清理。

## 5. 后续边界

下一批迁出 Checkpoint To Current、Checkpoint To Checkpoint、Resume Recovery、Counterfactual Branch 差异和原始 JSON 审计视图；继续复用 `features/research` model 与 `useResearchState`，不触碰两个独立 worker。
