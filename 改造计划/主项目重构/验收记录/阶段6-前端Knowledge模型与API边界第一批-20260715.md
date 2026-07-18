# 阶段 6 前端 Knowledge 模型与 API 边界第一批验收

- 验收日期：2026-07-15
- 范围：Knowledge/Wiki model owner、API owner、查询参数契约、App transport 接线和架构门禁
- 状态：第一批完成；页面状态与视觉交互保持不变

## 1. Model Owner

新增 `src/features/knowledge/model.ts`，统一持有：

- Wiki page、link、home、settings、index、stats；
- graph node/edge/meta 与 overview/ego mode；
- governance issue、log、rebuild advice、task/source projection；
- Knowledge item detail、immutable version detail/summary 与 ordered citation；
- issue filters、graph options、create/append input 和 auto-fix result。

`App.tsx` 已删除对应的二十余个重复类型，只从 feature model 导入 UI 需要的类型。HTTP 返回字段、nullable 语义和现有页面读取方式未改变。

## 2. API Owner

新增 `KnowledgeApi`，统一持有：

```text
settings get/update
home/index/stats/search
issues/log/rebuild-advice
overview/ego graph
item create/detail/rename/delete
version list/detail/append
message save-as-note
link rebuild / workspace rebuild / auto-fix
```

search keyword、issue type/severity/auto-fix/current-page filters，以及 graph mode/center/depth/limit/repeated kinds 的 query serialization 均由 feature API 构建。`App.tsx` 不再掌握 Knowledge endpoint 或 URL 参数契约。

Artifact version writeback 仍调用 Artifact endpoint；它虽然可写入 Note/Wiki，但协议 owner 是 Artifact job，不迁入 Knowledge API。

## 3. App 接线

- Workspace 创建读取 Wiki settings、Source 上传/删除刷新 Wiki home 均调用 `knowledgeApi`；
- Wiki 搜索、筛选 issue、页面 log、graph、item/version 读取均调用 `knowledgeApi`；
- 手动补页、追加版本、重命名、删除、save-as-note 和治理操作均调用 `knowledgeApi`；
- React state、debounce timer、页面刷新组合和 JSX 保持原位，本批不同时重写 server-state lifecycle。

## 4. 架构门禁

`frontend/scripts/ui-contract-check.mjs` 新增约束：

- 禁止 `App.tsx` 重新声明 Wiki/Knowledge 核心类型；
- 禁止恢复 `buildWikiIssueQuery` / `buildWikiGraphQuery`；
- 禁止在 `App.tsx` 直接出现 Knowledge/Wiki endpoint；
- 要求真实导入 Knowledge API/model，并调用 home、graph、item、create、append 和 issues 协议。

## 5. 自动验证

```powershell
cd frontend
npm test -- --run src/features/knowledge/api.test.ts
npm test
npm run build
npm run ui:check
```

结果：

- Knowledge API 定向测试：`3/3`；
- 前端全量：`19` 个文件，`64/64`；
- `tsc -b && vite build`：通过，`59` modules transformed；
- production bundle：`assets/index-CVSU7Jc6.js`，`476.24 kB`，gzip `137.32 kB`；
- UI contract：通过；
- `git diff --check`：通过；
- `App.tsx` 中 Knowledge endpoint、重复模型和 query builder：零残留。

## 6. 下一切片

以 Knowledge 页面为边界收敛 server state、刷新组合和 debounce lifecycle，让 `App.tsx` 不再作为 Knowledge 状态 owner；本批 `KnowledgeApi` 与 model 保持唯一 transport/schema owner。
