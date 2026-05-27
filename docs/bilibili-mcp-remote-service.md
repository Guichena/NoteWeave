# Bilibili Remote MCP Tool Service

## 1. 定位

这个服务把 B 站链接解析能力从主系统里拆出来，作为一个独立部署的远程 MCP tool service。

它当前只负责两件事：

- 拉取并解析 B 站视频基础信息。
- 提取可用于产物生成的标题和字幕上下文。

主系统仍然负责：

- 产物入口展示和参数收集。
- 对话里的显式工具触发。
- 后续 artifact plan 编排、生成、落库和知识沉淀。

所以这里的设计重点不是“做完整开放 Agent 平台”，而是把外部工具能力和主业务编排解耦。

## 2. 接口

远程服务暴露：

`POST /api/v1/mcp/bilibili/invoke`

请求体：

```json
{
  "url": "https://www.bilibili.com/video/BV1abc123xyz/"
}
```

成功响应示例：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "toolName": "bilibili",
    "displayName": "Bilibili MCP",
    "title": "示例视频标题",
    "promptContext": "整理后的标题与字幕上下文",
    "output": {
      "url": "https://www.bilibili.com/video/BV1abc123xyz/",
      "title": "示例视频标题",
      "subtitleLineCount": 128,
      "pageCount": 1
    }
  }
}
```

## 3. 主系统接入

主系统默认通过远程客户端调用这个服务：

- `RemoteBilibiliMcpToolService`
- `noteweave.studio.mcp.bilibili.remote-enabled=true`

关键配置项：

```yaml
noteweave:
  studio:
    mcp:
      bilibili:
        remote-enabled: true
        remote-base-url: http://localhost:18083
        timeout-seconds: 20
```

对应环境变量：

- `NOTEWEAVE_STUDIO_MCP_BILIBILI_REMOTE_ENABLED=true`
- `NOTEWEAVE_STUDIO_MCP_BILIBILI_REMOTE_BASE_URL=http://localhost:18083`
- `NOTEWEAVE_STUDIO_MCP_BILIBILI_TIMEOUT_SECONDS=20`

测试环境默认会切回本地实现，避免测试依赖远程进程。

## 4. 启动方式

### 启动远程服务

可以直接运行：

```powershell
./scripts/start-bilibili-mcp-remote.ps1
```

或：

```cmd
scripts\start-bilibili-mcp-remote.cmd
```

它们本质上都会启动：

- main class: `com.noteweave_remote.BilibiliMcpRemoteApplication`
- port: `18083`
- `noteweave.studio.mcp.remote-server.enabled=true`

### 启动主系统

确保主系统保留远程开关：

```powershell
$env:NOTEWEAVE_STUDIO_MCP_BILIBILI_REMOTE_ENABLED = "true"
$env:NOTEWEAVE_STUDIO_MCP_BILIBILI_REMOTE_BASE_URL = "http://localhost:18083"
./mvnw.cmd spring-boot:run
```

## 5. 使用效果

当前已经接入两条入口：

- 产物页按钮里增加 B 站工具入口，输入链接后生成产物任务。
- 对话里支持显式触发 MCP 工具，再走同一条 artifact 任务链路。

这意味着面试时可以清楚说明：

- 不是把系统做成完全自主 Agent。
- 也不是只停留在“未来可以接 MCP”的概念层。
- 而是已经把一个真实外部能力拆成独立远程 tool service，并接到 Studio 产物入口和对话触发链路。

## 6. 面试口径

推荐说法：

> 当前没有把 NoteWeave 做成完整开放式 MCP 平台，但已经把 B 站解析能力拆成独立远程 MCP tool service。主系统只负责受控工具注册、产物入口、对话显式触发和后续 artifact workflow 编排，工具能力本身可以单独部署和演进，所以这块是解耦的。

避免说法：

- “我们已经做完完整 MCP 协议生态。”
- “Agent 会自由调用很多远程工具。”
- “这是一个完整通用的 MCP marketplace 平台。”
