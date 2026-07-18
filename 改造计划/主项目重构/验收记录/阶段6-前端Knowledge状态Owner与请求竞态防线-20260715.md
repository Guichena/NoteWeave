# 阶段 6 前端 Knowledge 状态 Owner 与请求竞态防线验收

- 验收日期：2026-07-15
- 范围：Knowledge server state、刷新组合、debounce lifecycle、workspace 隔离、迟到响应防线和 App 接线
- 状态：完成；Knowledge transport/schema owner 延续第一批边界

## 1. 单一 Server-State Owner

新增：

```text
src/features/knowledge/state.ts
src/features/knowledge/useKnowledgeState.ts
src/features/knowledge/state.test.ts
```

`KnowledgeServerState` 统一持有：

- Wiki home、URL、enabled、index、stats、issues、log、rebuild advice 与 graph；
- selected item、immutable version list/current version 与 selected item log；
- debounced search result 和 filtered governance issues。

`App.tsx` 已删除十四组 Knowledge server-state `useState` 和三组 search/issues/log debounce effect，只保留草稿、rename/append 输入、筛选条件、graph mode/kinds/search 与 UI 编排。

## 2. 原子快照与刷新边界

- 完整 Wiki refresh 并发读取 index、stats、issues、log、advice、graph 和可选 item detail/versions，全部成功后以一次 reducer action 提交；
- selected page detail、versions 和 ego/overview graph 以一次 selection transition 提交；
- current immutable version 由 selected item detail 的 latest-version 字段确定性投影，restore 不复制第二套组装逻辑；
- Source 上传保留原轻量 home refresh，不因状态迁移额外加载整套治理 read model；
- Source 删除、页面 CRUD、link rebuild、workspace rebuild 与 auto-fix 保持原完整 refresh 语义。

## 3. 请求竞态防线

`LatestKnowledgeRequestGate` 为每类请求提供独立 channel：

```text
settings / settings-update
home-only / home / snapshot
search / issues / selected-log / focus-issues
selection / graph / version
```

- 同 channel 新请求会 abort 旧请求；
- workspace 切换会 abort 全部在途请求并清空 channel；
- 响应只有在 workspace、channel 和 controller 仍匹配时才可提交；
- Hook 卸载集中 cancel；
- search、issues 和 selected log 的 debounce timer 由 Hook 创建并清理；
- `KnowledgeApi` read protocol 与 settings update 将 `AbortSignal` 原样传入 shared API transport。

该防线阻止旧搜索、旧筛选、旧选中页日志或旧工作台 snapshot 在迟到后覆盖当前页面。

## 4. App 与架构门禁

`App.tsx` 不再包含：

- Knowledge read API 调用；
- Knowledge server-state setter；
- `applyWikiHome`、`loadWikiGraph` 或手工 latest-version projection；
- search/issues/log 请求计时器。

`ui-contract-check.mjs` 要求真实接入 `useKnowledgeState` 的 home refresh、snapshot refresh、selection、graph 和 issue focus 动作，并禁止以上旧 owner 回流。页面仍保留 rename 输入同步 effect，它只桥接 selected detail 到 UI 草稿，不持有服务端真源。

## 5. 自动验证

```powershell
cd frontend
npm test -- --run src/features/knowledge/api.test.ts src/features/knowledge/state.test.ts
npm test
npm run build
npm run ui:check
```

结果：

- Knowledge 定向测试：`9/9`；
- 前端全量：`20` 个文件，`70/70`；
- `tsc -b && vite build`：通过，`61` modules transformed；
- production bundle：`assets/index-laerR9Hm.js`，`482.34 kB`，gzip `138.88 kB`；
- UI contract：通过；
- `git diff --check`：通过；
- `App.tsx` 中 Knowledge read API、server-state useState/setter、旧 refresh helper：零残留。

Production preview smoke：

- `/`：`200`；
- `/assets/index-laerR9Hm.js`：`200`，`505997` bytes；
- `/assets/index-CBwGi2l8.css`：`200`，`24755` bytes；
- 临时 `4173` preview 进程已在 `finally` 中停止。

## 6. 下一切片

迁移 Memory review/model/API 与 server-state owner，再迁 Execution feature。Knowledge 后续只继续拆 UI component/shell，不重新引入页面级 transport、cache 或 server-state owner。
