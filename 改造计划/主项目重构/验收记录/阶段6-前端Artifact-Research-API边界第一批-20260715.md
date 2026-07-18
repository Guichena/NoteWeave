# 阶段 6 前端 Artifact / Research API 边界第一批验收

- 验收日期：2026-07-15
- 范围：Artifact/Research feature API、命令与 Artifact 响应模型、App endpoint 迁移、取消信号、架构门禁和协议测试
- 状态：第一批完成；Research 大体量 read model、server-state owner 与独立工作台拆分留给下一批

## 1. 施工前缺口

`App.tsx` 仍直接拼接两组领域协议：

- Artifact：skills、job list/create、version detail、save-as-source、writeback、regenerate、rollback、compare、PDF export；
- Research：run list/create/detail、checkpoint detail、resume、save-report-as-source。

这些调用与页面状态、展示派生和交互逻辑混在同一文件，使路径变更、取消、测试和后续 server-state 迁移缺少单一 owner。

## 2. 新增 Feature 边界

```text
src/features/artifacts/model.ts
src/features/artifacts/api.ts
src/features/artifacts/api.test.ts
src/features/research/model.ts
src/features/research/api.ts
src/features/research/api.test.ts
```

`ArtifactsApi` 统一持有：

- skill list；
- job list/create；
- version detail；
- save-as-source、Knowledge writeback、regenerate、rollback；
- version compare query；
- PDF export URL。

`ResearchApi` 统一持有：

- run list/create/detail；
- checkpoint detail；
- resume-from-checkpoint；
- save-report-as-source。

全部 read protocol 可透传 `AbortSignal`。PDF URL 通过共享 `ApiClient.url()` 生成，页面不再直接依赖 `apiUrl`。

## 3. 模型迁移策略

Artifact 的 job/version/save-source 响应、file metadata、create/writeback input 已迁入 `features/artifacts/model.ts`。Research 的 create input、create response 与 save-report response 已迁入 `features/research/model.ts`。

Research 详情包含闭环状态、checkpoint、counterfactual、verifier、process summary 和大量展示派生类型。该批类型与当前页面 helper 强耦合，本批没有用一次机械搬运同时改写数百行审计 UI；它们将在下一批随 `useResearchState`、request gate 和独立工作台组件一起迁移，届时形成真正的 read-model owner。

## 4. App 与架构门禁

`App.tsx` 已不再出现：

```text
/api/v2/skills
/artifact-jobs
/research-runs
```

页面只调用 `artifactsApi` 与 `researchApi`，继续保留现有加载、选择和视觉编排行为。`ui-contract-check.mjs` 禁止上述 endpoint 回流，并要求真实导入与调用：

- `artifactsApi.listSkills/listJobs/createJob`；
- `researchApi.listRuns/createRun/getRun`；
- Artifact/Research feature model import。

## 5. 自动验证

```powershell
cd frontend
npm.cmd test -- --run src/features/artifacts/api.test.ts src/features/research/api.test.ts
npm.cmd test
npm.cmd run build
npm.cmd run ui:check
```

结果：

- Artifact/Research API 定向测试：`5/5`；
- 前端全量：`26` 个测试文件，`91/91`；
- `tsc -b && vite build`：通过，`70` modules transformed；
- production JS：`assets/index-BVvJsBt0.js`，`500.16 kB`，gzip `144.22 kB`；
- production CSS：`assets/index-bzc9kVQ8.css`，`26.29 kB`，gzip `5.26 kB`；
- UI contract：通过；
- 本批 `git diff --check`：无 whitespace error，仅报告工作区既有 LF→CRLF 提示；
- `App.tsx` Artifact/Research endpoint 静态搜索：零匹配。

协议测试覆盖 read/mutation path、version compare query、PDF export URL、create payload、resume/save-report path 与 cancellation 透传。

## 6. Production Preview 与 Bundle 信号

当前 `dist` production preview：

```text
GET http://127.0.0.1:4173/
200 OK
#root: present
assets/index-BVvJsBt0.js: referenced
```

preview 进程已清理。主 chunk 本次达到 `500.16 kB`，Vite 首次输出 chunk size warning。该 warning 不影响构建成功，但说明继续把 Research 工作台保留在主 `App.tsx` 已到边界；后续应以独立组件和 route-level dynamic import 解决，不通过提高 warning threshold 隐藏。

## 7. 下一切片

先迁 Research read model、server-state reducer/Hook、workspace-safe request gate 和独立工作台组件，并把 `/research` 形成延迟加载边界；随后迁 Artifact job/version server-state 与组件。两者创建的 Task 继续复用既有 Execution registry。
