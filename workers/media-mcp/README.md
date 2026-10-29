# 音视频解析 MCP 服务

独立运行的 Streamable HTTP 服务，提供 `get_video_info`、`parse_video`、`parse_media`。Java 经 Artifact Worker 提交任务，媒体服务不直接参与产物生成。

## 启动

```powershell
uv sync
# 按 .env.example 配置 MEDIA_MCP_AUTH_TOKEN 及输入目录。
uv run uvicorn media_mcp.server:app --host 0.0.0.0 --port 18094
```

MCP 地址为 `/mcp`，请求携带 `X-NoteWeave-Internal-Token`。Compose 中服务仅加入 `media` 网络；Worker 同时加入原网络和媒体网络。媒体服务以只读方式挂载 Worker 输入卷，独立持有 ASR 模型缓存卷。需要 ffmpeg、ffprobe 和带 `eng+chi_sim` 的 tesseract，Dockerfile 已安装。

## 工具参数

- `get_video_info(source_url)`：只接受 B 站标准视频地址，返回 `title`、`source_url`、`duration_ms`、`parts`。
- `parse_video(source_url, parts)`：解析明确选择的分 P，官方字幕优先，无字幕时直接用仓库内的 faster-whisper 实现。
- `parse_media(file_ref, title)`：`file_ref` 是 `MEDIA_MCP_INPUT_ROOT` 内的相对路径，不能包含父目录、绝对路径或符号链接。生产中 Worker 提供 `source-transcriptions/<任务目录>/source.<扩展名>`。

返回 `title`、`source_url`、`segments`、`stats`。每个片段含 `part`、`start_ms`、`end_ms`、`text` 和 `frames`；每张帧含 `frame_id`、`at_ms`、`image_ref`、`caption`、`ocr_text`。`image_ref` 为 PNG 的 data URI，Java 验证图片后存入 MinIO，并将它替换为工作台内的图片读取地址。静音区间中的帧独立成段，不错误归属到其他语音。

`stats` 含 `candidate_frames`、`kept_frames`、`transcript_origin`、`duration_ms` 和 `vision_status`。感知哈希阈值由 `MEDIA_MCP_PHASH_THRESHOLD` 控制，只与上一张保留帧比较。转写与场景处理并行，所有子进程共用解析截止时间；超过时长、正文大小、候选帧或保留帧上限时失败。临时下载的原视频、字幕与帧在解析结束或失败后删除，图片内容已包含在返回值中。

## 视觉模型占位

按用户要求，默认 `MEDIA_MCP_VISION_ENABLED=false`，接口和模型分别为 `https://vision.example.invalid/v1/chat/completions`、`placeholder`。此时 OCR 仍真实执行，`caption` 为空，`vision_status=NOT_CONFIGURED`，不生成虚构的画面描述。需要真实画面描述时配置兼容 Chat Completions 图片输入的接口、模型和密钥，再打开开关。

## 验证范围

用户已要求不运行测试。本次仅进行依赖锁定、源码审阅及语法编译，没有执行单元测试、容器联调、真实视频解析或外部模型调用。
