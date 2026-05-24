# Playwright Smoke

用于对 NoteWeave 当前主界面做一轮真实浏览器冒烟回归。

覆盖范围：
- `admin`：Spaces、Team Knowledge、Chat Workbench、Citation Inspector、Artifacts、Wiki、Graph、Memory、Admin Tasks、Health、Evaluation、Logs
- `alice`：Personal Projects、Sources、Cards、Generate、Memory

## 运行前提

先确保后端和依赖服务已经启动，并且前端可在浏览器中正常访问。

`dev` profile 默认启用 stub LLM，因此本脚本可以在没有 `LLM_API_KEY` 的本地环境里验证 WebSocket 流式链路。若要改用真实模型，可在启动后端前设置：

```powershell
$env:NOTEWEAVE_LLM_STUB_ENABLED="false"
$env:LLM_API_KEY="your-api-key"
```

推荐环境变量：

```powershell
$env:NOTEWEAVE_BASE_URL="http://127.0.0.1:18082"
$env:NOTEWEAVE_ADMIN_USERNAME="admin"
$env:NOTEWEAVE_ADMIN_PASSWORD="NoteWeave123!"
$env:NOTEWEAVE_ALICE_USERNAME="alice"
$env:NOTEWEAVE_ALICE_PASSWORD="NoteWeave123!"
```

## 首次安装

```powershell
cd tools/playwright-smoke
npm install
npm run install:browsers
```

## 执行

```powershell
cd tools/playwright-smoke
npm run smoke
```

脚本会：
- 真正打开 Chromium 并执行点击/输入
- 按当前 Workbench 的 Global Rail + Context Rail + Main Canvas + Inspector 结构切换路由
- 创建一条临时聊天会话，并验证 WebSocket 流式响应；当本次回答返回 Citation 时，会继续验证 Citation 加载和 Inspector 展示
- 收集控制台错误和失败请求
- 将结果输出到终端
- 同时写入 `tools/playwright-smoke/last-run.json`

## 结果判读

- `issues`：缺失控件、阻塞点击或致命失败
- `consoleErrors`：前端控制台报错
- `requestFailures`：4xx/5xx 或请求失败
- `snapshots`：每个关键页面的 URL、标题、Workbench 三栏是否存在，以及是否出现 `.error-state`

脚本基于当前静态前端实现，不依赖 React/Vue/构建产物；如果未来全局导航或关键 `data-action` 发生变化，请同步更新本脚本。
