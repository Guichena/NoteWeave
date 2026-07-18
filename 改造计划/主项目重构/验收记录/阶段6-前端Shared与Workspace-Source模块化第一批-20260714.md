# 阶段 6 前端 Shared 与 Workspace/Source 模块化第一批验收

- 验收日期：2026-07-14
- 范围：shared API/error/auth/event-stream、Workspace feature API、Source feature API、App 接线与架构门禁
- 状态：第一批完成；页面状态与 UI 暂留 `App.tsx`，下一批迁 Answer/Conversation normalized store

## 1. Shared API

`src/shared/api` 成为唯一 HTTP transport owner：

- 统一拼接 `VITE_API_BASE_URL`；
- 解包 `{success, code, message, data, request_id}`；
- 后端业务错误映射为 `ApiError(status, code, requestId, correlationId)`；
- 从 response body/header 收集 `request_id`，从 `X-Correlation-ID` 收集 correlation；
- 每次请求生成并发送 `X-Correlation-ID`；
- 支持内存 Bearer token provider 与 401 callback，不在 localStorage 保存 token；
- `RequestInit.signal` 原样传给 fetch，调用方可取消；
- JSON GET/POST/PUT/PATCH/DELETE、raw binary 与 text 请求共用同一错误路径。

`App.tsx` 已删除内嵌 `ApiResponse`、`get/post/put/patch/del/request/requestText` 和直接 `fetch`。

## 2. Shared Event Stream

`src/shared/event-stream` 持有：

- 标准 SSE block parser；
- incremental response body consume；
- Conversation cursor、`Last-Event-ID`、指数重连和 AbortController lifecycle。

Conversation client 默认使用 shared `ApiClient.raw`，因此 stream 与普通 API 共享 base URL、auth、correlation 和错误处理。测试可注入原始 fetcher。旧 `src/sse.ts` 与 `src/conversationStream.ts` 只保留 re-export，删除条件是所有测试与剩余 feature import 均改到 shared path。

## 3. Workspace 与 Source Feature

首批 feature owner：

```text
src/features/workspace/api.ts
src/features/workspace/model.ts
src/features/sources/api.ts
src/features/sources/model.ts
```

- Workspace feature 持有 create path、请求和响应模型；
- Source feature 持有 list、delete 和 text upload 协议；
- upload 顺序由 feature 固定为 upload session -> binary chunk PUT -> complete；
- Artifact/Research 写回后刷新 Source 列表也通过 `sourcesApi.list`；
- 本批不迁移 React 页面状态，避免 transport、store 和 UI 同时重写。

## 4. 持续架构门禁

`frontend/scripts/ui-contract-check.mjs` 现在禁止 `App.tsx` 包含：

- 直接 `fetch(`；
- 内嵌 HTTP helper；
- `ApiResponse` envelope；
- 重复 `Workspace` / `SourceAsset` 类型。

同时要求 App 真实导入 shared API/event-stream 和 Workspace/Source feature，并调用 create/upload/list/remove；只创建未接线目录无法通过。

## 5. 自动验证

```powershell
cd frontend
npm test
npm run build
npm run ui:check
```

结果：

- Vitest：15 个文件、52/52；
- `tsc -b && vite build`：通过，55 modules transformed；
- `dist/assets/index-C2rnAFOh.js`：497019 bytes，gzip 136.36 kB；
- UI contract：通过。

新增测试覆盖 success envelope、auth/correlation header、结构化错误、AbortSignal、401 callback、Workspace create path、Source upload 顺序与 Source list/delete path。既有 SSE chunk/cursor/reconnect 测试通过兼容桥继续验证 shared 实现。

## 6. 运行态验证

Vite production preview：

- `/`：200；
- production JS asset：200，497019 bytes；
- 临时 preview 进程验证后已停止。

Docker Hub 在 canonical Frontend build 时连续两次对 `node:22-alpine` / `nginx:1.27-alpine` 返回 metadata TLS handshake timeout。本机没有这两个基础镜像，无法在不访问 registry 的情况下完成标准多阶段 rebuild。

为验证新 bundle 而不伪报 canonical build，使用当前已运行 Frontend 的 Nginx runtime 镜像，在 `noteweave-v2_default` 网络启动一次性容器并只读挂载新 `dist`：

- `/healthz`：200；
- `/`：200；
- JS asset：200，497019 bytes；
- `/api/v2/workspaces` proxy：200；
- 容器与临时 image tag 均已删除。

外部 registry 恢复后的补验命令：

```powershell
docker compose --profile app up -d --build frontend
docker inspect --format '{{.State.Health.Status}}' noteweave-v2-frontend
```

## 7. 回滚与下一批

本批未改变 API path、JSON 字段、页面视觉或业务状态 owner。回滚可将 App import 临时切回兼容桥，但禁止恢复直接 fetch 和重复 error handling。下一批迁移 Answer/Conversation normalized store、event reducer 和 feature hooks；完成后删除顶层 SSE 兼容桥，再迁 Knowledge/Memory/Execution。
