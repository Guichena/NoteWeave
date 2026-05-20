# Playwright Smoke

用于对 NoteWeave 当前主界面做一轮真实浏览器冒烟回归。

覆盖范围：
- `admin`：Spaces、Team Knowledge、Chat、Artifacts、Wiki、Memory、Admin Tasks、Health、Evaluation、Logs
- `alice`：Personal Projects、Sources、Cards、Generate、Memory

## 运行前提

先确保后端和依赖服务已经启动，并且前端可在浏览器中正常访问。

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
- 收集控制台错误和失败请求
- 将结果输出到终端
- 同时写入 `tools/playwright-smoke/last-run.json`

## 结果判读

- `issues`：缺失控件、阻塞点击或致命失败
- `consoleErrors`：前端控制台报错
- `requestFailures`：4xx/5xx 或请求失败
- `snapshots`：每个关键页面的 URL、标题和是否出现错误态

当前脚本把“无法对团队空间 Artifact 做个人蒸馏”视为正常行为，因此不会把该入口缺失判为功能故障。
